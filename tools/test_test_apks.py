import contextlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import test_apks as apks
from github_release import sha256


class DeviceTestApksTest(unittest.TestCase):
    COMMIT = "a" * 40
    APK_NAMES = ("LessonIsland-debug.apk", "LessonIsland-androidTest.apk")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / "apks"
        self.directory.mkdir()
        for name in self.APK_NAMES:
            (self.directory / name).write_bytes(("test bytes for " + name).encode())
        self.write_manifest()

    def write_manifest(self, commit=None, bad_hash=None):
        metadata = {
            "sourceCommit": commit or self.COMMIT,
            "sha256": {name: sha256(self.directory / name) for name in self.APK_NAMES},
        }
        if bad_hash:
            metadata["sha256"][bad_hash] = "0" * 64
        (self.directory / "test-apks.json").write_text(json.dumps(metadata), encoding="utf-8")

    def write_outputs(self, names):
        (self.directory / "output-metadata.json").write_text(
            json.dumps({"elements": [{"outputFile": name} for name in names]}), encoding="utf-8")

    @contextlib.contextmanager
    def verification_tools(self, instrumentation_package=None, certificates=None):
        package = instrumentation_package or apks.PACKAGE + ".test"
        certificate = "Signer #1 certificate SHA-256 digest: " + apks.RELEASE_CERTIFICATE_SHA256 + "\n"

        def command_output(command, **kwargs):
            if command[:3] == ["git", "rev-parse", "HEAD"]:
                return self.COMMIT + "\n"
            return "package: name='" + package + "' versionCode='1' versionName='1.2.3'\n"

        with patch.object(apks.subprocess, "check_output", side_effect=command_output) as commands, \
                patch.object(apks.subprocess, "run") as mutations, \
                patch.object(apks, "read_version", return_value=("1.2.3", 7)), \
                patch.object(apks, "check_apk") as check_apk, \
                patch.object(apks, "build_tools", return_value=self.directory / "sdk"), \
                patch.object(apks, "apksigner", side_effect=certificates or [certificate, certificate]) as signer, \
                patch("builtins.print"):
            yield commands, mutations, check_apk, signer

    def test_output_metadata_requires_one_real_apk_file(self):
        self.write_outputs([self.APK_NAMES[0]])
        self.assertEqual(apks.output_apk(self.directory), self.directory / self.APK_NAMES[0])
        for names in ([], list(self.APK_NAMES)):
            with self.subTest(names=names):
                self.write_outputs(names)
                with self.assertRaises(ValueError):
                    apks.output_apk(self.directory)
        self.write_outputs(["missing.apk"])
        with self.assertRaises(ValueError):
            apks.output_apk(self.directory)

    def test_output_metadata_rejects_paths_and_non_apk_names_on_both_platforms(self):
        for name in ("", "..", "../escape.apk", "/tmp/escape.apk", "..\\escape.apk",
                     "C:\\escape.apk", "C:escape.apk", "subdir/app.apk", "state.json", None, 1):
            with self.subTest(name=name):
                self.write_outputs([name])
                with self.assertRaises(ValueError):
                    apks.output_apk(self.directory)

    def test_symlink_to_an_apk_outside_the_output_directory_is_rejected(self):
        outside = Path(self.temp.name) / "outside.apk"
        outside.write_bytes(b"outside test bytes")
        link = self.directory / "linked.apk"
        try:
            link.symlink_to(outside)
        except OSError:
            self.skipTest("The host does not allow creating test symlinks.")
        self.write_outputs([link.name])
        with self.assertRaises(ValueError):
            apks.output_apk(self.directory)

    def test_commit_mismatch_stops_before_apk_parsing_or_other_commands(self):
        self.write_manifest(commit="different-checkout")
        with self.verification_tools() as (commands, mutations, check_apk, signer):
            with self.assertRaisesRegex(ValueError, "checked-out source commit"):
                apks.verify(self.directory)
            self.assertEqual(commands.call_count, 1)  # Only git rev-parse, no SDK parser.
            mutations.assert_not_called()
            check_apk.assert_not_called()
            signer.assert_not_called()

    def test_both_checksums_are_checked_before_any_sdk_processing(self):
        original = {name: (self.directory / name).read_bytes() for name in self.APK_NAMES}
        for name in self.APK_NAMES:
            with self.subTest(apk=name):
                self.write_manifest(bad_hash=name)
                with self.verification_tools() as (commands, mutations, check_apk, signer):
                    with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                        apks.verify(self.directory)
                    self.assertEqual(commands.call_count, 1)
                    mutations.assert_not_called()
                    check_apk.assert_not_called()
                    signer.assert_not_called()
                self.assertEqual(original, {p: (self.directory / p).read_bytes() for p in self.APK_NAMES})

    def test_wrong_instrumentation_certificate_or_package_is_rejected(self):
        valid = "Signer #1 certificate SHA-256 digest: " + apks.RELEASE_CERTIFICATE_SHA256 + "\n"
        wrong = "Signer #1 certificate SHA-256 digest: " + "0" * 64 + "\n"
        with self.verification_tools(certificates=[valid, wrong]) as (commands, mutations, check_apk, signer):
            with self.assertRaisesRegex(ValueError, "release certificate"):
                apks.verify(self.directory)
            self.assertEqual(commands.call_count, 1)
            mutations.assert_not_called()
        with self.verification_tools(instrumentation_package="another.application.test") as (_, mutations, _, _):
            with self.assertRaisesRegex(ValueError, "instrumentation APK package"):
                apks.verify(self.directory)
            mutations.assert_not_called()

    def test_valid_pair_checks_both_signers_and_matching_app_version_without_installing(self):
        with self.verification_tools() as (commands, mutations, check_apk, signer):
            apks.verify(self.directory)
            check_apk.assert_called_once_with(
                self.directory / self.APK_NAMES[0], "1.2.3", 7, apks.RELEASE_CERTIFICATE_SHA256)
            self.assertEqual(signer.call_count, 2)
            self.assertEqual(commands.call_count, 2)  # git plus instrumentation manifest.
            mutations.assert_not_called()

    def test_cli_verification_failure_returns_nonzero_without_changing_artifacts(self):
        self.write_manifest(commit="different-checkout")
        before = {path.name: path.read_bytes() for path in self.directory.iterdir()}
        result = subprocess.run(
            [sys.executable, "-B", str(Path(apks.__file__).resolve()), "verify", str(self.directory)],
            cwd=apks.ROOT, capture_output=True, encoding="utf-8")
        self.assertEqual(result.returncode, 1)
        self.assertIn("checked-out source commit", result.stderr)
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.directory.iterdir()})


if __name__ == "__main__":
    unittest.main()
