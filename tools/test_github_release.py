import base64
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import configure_github_signing as setup
import github_release as release
from ci_check import RELEASE_CERTIFICATE_SHA256, check_certificate


class ReleaseTest(unittest.TestCase):
    def test_notes_only_include_the_selected_release(self):
        notes = "## 未发布\n\n- Future\n\n## 1.2.3 — 2026-11-15\n\n- Selected\n\n## 1.2.2 — 2026-11-14\n\n- Old\n"
        self.assertEqual(release.release_notes("1.2.3", notes), "- Selected\n")
        with self.assertRaises(ValueError):
            release.release_notes("1.2.3", "## 1.2.3 — 2026-11-15\n\n")

    def test_missing_or_invalid_signing_secrets_fail(self):
        env = {"ANDROID_KEYSTORE_BASE64": base64.b64encode(b"test data").decode(),
               "ANDROID_KEYSTORE_PASSWORD": "test password", "ANDROID_KEY_ALIAS": "test alias"}
        self.assertEqual(release.signing_secrets(env), b"test data")
        for key in env:
            with self.subTest(missing=key), self.assertRaises(ValueError):
                release.signing_secrets({name: value for name, value in env.items() if name != key})
        with self.assertRaises(ValueError):
            release.signing_secrets(dict(env, ANDROID_KEYSTORE_BASE64="bad!"))

    def test_exactly_the_existing_certificate_is_required(self):
        line = f"Signer #1 certificate SHA-256 digest: {RELEASE_CERTIFICATE_SHA256}\n"
        check_certificate(line, RELEASE_CERTIFICATE_SHA256)
        for output in ("", line.replace(RELEASE_CERTIFICATE_SHA256, "0" * 64), line + line):
            with self.subTest(output=output), self.assertRaises(ValueError):
                check_certificate(output, RELEASE_CERTIFICATE_SHA256)

    def test_source_archive_excludes_private_files(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "source.zip"
            for name in (".local/signing.properties", "libb.keystore", "keys/release.jks", "llm info.txt"):
                with self.subTest(name=name):
                    with zipfile.ZipFile(path, "w") as archive:
                        archive.writestr(name, "test data")
                    with self.assertRaises(ValueError):
                        release.check_source_archive(path)
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("signing.properties.example", "storePassword=")
            release.check_source_archive(path)

    def test_moved_remote_tag_is_rejected(self):
        with patch.object(release, "github_api", return_value={"object": {"type": "commit", "sha": "other"}}):
            with self.assertRaises(ValueError):
                release.check_remote_tag("owner/repo", "v1.2.3", "expected")

    def test_published_releases_and_foreign_drafts_are_protected(self):
        release.check_draft(None, "expected")
        release.check_draft({"draft": True, "target_commitish": "expected"}, "expected")
        for draft, commit in ((False, "expected"), (True, "other")):
            with self.subTest(draft=draft, commit=commit), self.assertRaises(ValueError):
                release.check_draft({"draft": draft, "target_commitish": commit}, "expected")

    def test_draft_lookup_includes_later_pages(self):
        draft = {"tag_name": "v1.2.3", "draft": True}
        with patch.object(release, "github_api", side_effect=[[{"tag_name": "other"}] * 100, [draft]]) as api:
            self.assertEqual(release.find_release("owner/repo", "v1.2.3"), draft)
            self.assertIn("page=2", api.call_args.args[0])

    def test_new_draft_lookup_retries_only_until_visible(self):
        draft = {"draft": True, "tag_name": "v1.2.3"}
        with patch.object(release, "find_release", side_effect=[None, None, draft]) as find, \
                patch.object(release.time, "sleep") as sleep:
            self.assertEqual(release.wait_for_release("owner/repo", "v1.2.3"), draft)
            self.assertEqual(find.call_count, 3)
            self.assertEqual(sleep.call_count, 2)
        with patch.object(release, "find_release", return_value=None) as find, \
                patch.object(release.time, "sleep") as sleep:
            with self.assertRaises(ValueError):
                release.wait_for_release("owner/repo", "v1.2.3")
            self.assertEqual(find.call_count, 6)
            self.assertEqual(sleep.call_count, 5)

    def test_signing_failure_removes_temporary_key(self):
        env = {"ANDROID_KEYSTORE_BASE64": base64.b64encode(b"not a real key").decode(),
               "ANDROID_KEYSTORE_PASSWORD": "test password", "ANDROID_KEY_ALIAS": "test alias"}
        key_paths = []

        def fail_sign(*args, **kwargs):
            key = Path(args[args.index("--ks") + 1])
            key_paths.append(key)
            self.assertTrue(key.is_file())
            self.assertNotIn("test password", args)
            self.assertEqual(kwargs["env"]["ANDROID_KEY_PASSWORD"], "test password")
            raise subprocess.CalledProcessError(1, "test signing failure")

        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, env), \
                patch.object(release, "release_context", return_value=("1.2.3", 1, "v1.2.3", "commit", "notes")), \
                patch.object(release, "check_apk"), patch.object(release.subprocess, "run"), \
                patch.object(release, "build_tools", return_value=Path(temp)), \
                patch.object(release, "apksigner", side_effect=fail_sign):
            with self.assertRaises(subprocess.CalledProcessError):
                release.prepare(Path(temp) / "unsigned.apk", Path(temp) / "output")
        self.assertEqual(len(key_paths), 1)
        self.assertFalse(key_paths[0].exists())

    def test_publish_waits_for_verified_assets_before_publishing(self):
        for corrupt in (False, True):
            with self.subTest(corrupt=corrupt), tempfile.TemporaryDirectory() as temp:
                output = Path(temp)
                apk = output / "LessonIsland-1.2.3.apk"
                source = output / "LessonIsland-1.2.3-source.zip"
                apk.write_bytes(b"test apk")
                source.write_bytes(b"test source")
                (output / "release-notes.md").write_text("test notes")
                sums = output / "SHA256SUMS.txt"
                sums.write_text("".join(f"{release.sha256(p)}  {p.name}\n" for p in (apk, source)), encoding="utf-8")
                assets = [{"name": p.name, "size": p.stat().st_size,
                           "digest": "sha256:" + release.sha256(p)} for p in (apk, source, sums)]
                if corrupt:
                    assets[0]["digest"] = "sha256:" + "0" * 64
                draft = {"id": 42, "draft": True, "target_commitish": "commit", "assets": assets}
                published = dict(draft, draft=False, html_url="https://github.com/owner/repo/releases/tag/v1.2.3")
                with patch.dict(os.environ, GITHUB_REPOSITORY="owner/repo"), \
                        patch.object(release, "release_context", return_value=("1.2.3", 1, "v1.2.3", "commit", "notes")), \
                        patch.object(release, "check_apk"), patch.object(release, "check_source_archive"), \
                        patch.object(release, "check_remote_tag"), \
                        patch.object(release, "find_release", side_effect=[None, draft]), \
                        patch.object(release, "github_api", side_effect=[draft, published]), \
                        patch.object(release.subprocess, "run") as run, patch("builtins.print"):
                    if corrupt:
                        with self.assertRaises(ValueError):
                            release.publish(output)
                    else:
                        release.publish(output)
                    commands = [call.args[0] for call in run.call_args_list]
                    self.assertIn("--draft", commands[0])
                    self.assertEqual(any("--draft=false" in cmd for cmd in commands), not corrupt)

    def test_setup_checks_certificate_before_uploading_and_hides_passwords(self):
        for matching in (True, False):
            with self.subTest(matching=matching), tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / "test.keystore"
                path.write_bytes(b"not a real key")
                cert = b"test public certificate"
                expected = hashlib.sha256(cert).hexdigest() if matching else "0" * 64
                with patch.object(setup, "RELEASE_CERTIFICATE_SHA256", expected), \
                        patch.object(setup.getpass, "getpass", side_effect=["test password", ""]), \
                        patch.object(setup.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, cert)) as run, \
                        patch("builtins.print"):
                    if matching:
                        setup.configure("owner/repo", path, "test alias")
                    else:
                        with self.assertRaises(ValueError):
                            setup.configure("owner/repo", path, "test alias")
                    uploads = [call for call in run.call_args_list if call.args[0][1:3] == ["secret", "set"]]
                    self.assertEqual(len(uploads), 4 if matching else 0)
                    for call in run.call_args_list:
                        self.assertNotIn("test password", call.args[0])


if __name__ == "__main__":
    unittest.main()
