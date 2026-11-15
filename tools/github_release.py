"""Sign a checked APK and publish an existing version tag to GitHub Releases."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import zipfile

from ci_check import (ROOT, RELEASE_CERTIFICATE_SHA256, apksigner, build_tools,
                      check_apk, check_tag, read_version)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def release_notes(version, changelog):
    check_tag(version, f"v{version}", changelog)
    match = re.search(rf"^## {re.escape(version)}[ \t]+—[ \t]+[^\n]+\n(.*?)(?=^## |\Z)",
                      changelog, re.MULTILINE | re.DOTALL)
    if not match or not match[1].strip():
        raise ValueError("The release section in CHANGELOG.md must contain release notes.")
    return match[1].strip() + "\n"


def release_context():
    version, code = read_version((ROOT / "app/build.gradle").read_text(encoding="utf-8"))
    tag = os.environ.get("GITHUB_REF_NAME", "")
    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    if os.environ.get("GITHUB_REF_TYPE") != "tag":
        raise ValueError("Releases require an explicitly pushed version tag.")
    check_tag(version, tag, changelog)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    tagged = subprocess.check_output(["git", "rev-parse", f"refs/tags/{tag}^{{commit}}"],
                                     cwd=ROOT, text=True).strip()
    if commit != os.environ.get("GITHUB_SHA") or tagged != commit:
        raise ValueError("The checkout, version tag and triggering commit must match.")
    return version, code, tag, commit, release_notes(version, changelog)


def signing_secrets(env):
    required = ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS")
    missing = [name for name in required if not env.get(name)]
    if missing:
        raise ValueError("Configure GitHub Actions signing secrets: " + ", ".join(missing))
    try:
        keystore = base64.b64decode(env["ANDROID_KEYSTORE_BASE64"], validate=True)
    except ValueError:
        raise ValueError("ANDROID_KEYSTORE_BASE64 must contain valid Base64.") from None
    if not keystore:
        raise ValueError("The signing keystore is empty.")
    return keystore


def check_source_archive(path):
    private_names = {"llm info.txt", "local.properties", "signing.properties", "keystore.properties"}
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            parts = Path(name).parts
            if ".local" in parts or Path(name).name.lower() in private_names or name.lower().endswith((".jks", ".keystore")):
                raise ValueError("Private file found in the source archive: " + name)


def prepare(unsigned_apk, output):
    version, code, tag, commit, notes = release_context()
    keystore = signing_secrets(os.environ)
    check_apk(unsigned_apk, version, code)
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("The release output directory must be empty.")
    apk = output / f"LessonIsland-{version}.apk"
    zipalign = build_tools() / ("zipalign.exe" if os.name == "nt" else "zipalign")
    # Temporary signing material lives outside the checkout and is removed on failure too.
    with tempfile.TemporaryDirectory(prefix="lesson-island-sign-", dir=os.environ.get("RUNNER_TEMP")) as temp:
        key_path = Path(temp) / "release.keystore"
        key_path.write_bytes(keystore)
        key_path.chmod(0o600)
        aligned = Path(temp) / "aligned.apk"
        subprocess.run([str(zipalign), "-P", "16", "-f", "4", str(unsigned_apk), str(aligned)], check=True)
        sign_env = dict(os.environ)
        sign_env["ANDROID_KEY_PASSWORD"] = sign_env.get("ANDROID_KEY_PASSWORD") or sign_env["ANDROID_KEYSTORE_PASSWORD"]
        apksigner("sign", "--ks", key_path, "--ks-key-alias", sign_env["ANDROID_KEY_ALIAS"],
                  "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
                  "--v4-signing-enabled", "false", "--out", apk, aligned, env=sign_env)
    check_apk(apk, version, code, RELEASE_CERTIFICATE_SHA256)
    subprocess.run([str(zipalign), "-c", "-P", "16", "4", str(apk)], check=True)
    source = output / f"LessonIsland-{version}-source.zip"
    subprocess.run(["git", "archive", "--format=zip", f"--output={source.resolve()}", commit], cwd=ROOT, check=True)
    check_source_archive(source)
    (output / "SHA256SUMS.txt").write_text(
        "".join(f"{sha256(path)}  {path.name}\n" for path in (apk, source)), encoding="utf-8")
    (output / "release-notes.md").write_text(
        notes + f"\nSource commit: `{commit}`\n\nCertificate SHA-256: `{RELEASE_CERTIFICATE_SHA256}`\n", encoding="utf-8")
    print(f"Prepared signed APK and source for {tag} at {commit}.")


def github_api(endpoint):
    result = subprocess.run(["gh", "api", endpoint], capture_output=True, encoding="utf-8")
    if result.returncode:
        raise ValueError("GitHub API request failed: " + result.stderr.strip())
    return json.loads(result.stdout)


def find_release(repo, tag):
    # The tag endpoint only guarantees published releases. Listing with write access
    # also includes drafts, so interrupted uploads can be resumed safely.
    page = 1
    while True:
        releases = github_api(f"repos/{repo}/releases?per_page=100&page={page}")
        for release in releases:
            if release["tag_name"] == tag:
                return release
        if len(releases) < 100:
            return None
        page += 1


def check_remote_tag(repo, tag, commit):
    obj = github_api(f"repos/{repo}/git/ref/tags/{tag}")["object"]
    for _ in range(10):
        if obj["type"] != "tag":
            break
        obj = github_api(f"repos/{repo}/git/tags/{obj['sha']}")["object"]
    if obj["type"] != "commit" or obj["sha"] != commit:
        raise ValueError("The remote release tag no longer matches the checked commit.")


def wait_for_release(repo, tag):
    # Newly created drafts can take a few seconds to appear in the REST listing.
    for attempt in range(6):
        release = find_release(repo, tag)
        if release is not None:
            return release
        if attempt < 5:
            time.sleep(5)
    raise ValueError("Cannot find the newly created release draft after retrying.")


def check_draft(release, commit):
    if release and (not release["draft"] or release["target_commitish"] != commit):
        raise ValueError("Refusing to overwrite a published Release or a draft for another commit.")


def check_uploaded_assets(release, paths):
    expected = {path.name: (path.stat().st_size, "sha256:" + sha256(path)) for path in paths}
    actual = {asset["name"]: (asset["size"], asset.get("digest")) for asset in release["assets"]}
    if actual != expected:
        raise ValueError("Release assets do not match the expected filenames, sizes and SHA-256 digests.")


def publish(output):
    version, code, tag, commit, _ = release_context()
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("GITHUB_REPOSITORY must select an existing owner/repository.")
    apk = output / f"LessonIsland-{version}.apk"
    source = output / f"LessonIsland-{version}-source.zip"
    paths = [apk, source, output / "SHA256SUMS.txt"]
    check_apk(apk, version, code, RELEASE_CERTIFICATE_SHA256)
    check_source_archive(source)
    expected_sums = "".join(f"{sha256(path)}  {path.name}\n" for path in (apk, source))
    if paths[2].read_text(encoding="utf-8") != expected_sums:
        raise ValueError("SHA256SUMS.txt does not match the release files.")
    check_remote_tag(repo, tag, commit)
    release = find_release(repo, tag)
    check_draft(release, commit)
    common = [tag, "--repo", repo]
    if release is None:
        subprocess.run(["gh", "release", "create", *common, "--verify-tag", "--target", commit,
                        "--title", f"Lesson Island {version}", "--notes-file", str(output / "release-notes.md"),
                        "--draft"], check=True)
        release = wait_for_release(repo, tag)
        check_draft(release, commit)
    else:
        subprocess.run(["gh", "release", "edit", *common, "--notes-file", str(output / "release-notes.md")], check=True)
    endpoint = f"repos/{repo}/releases/{release['id']}"
    subprocess.run(["gh", "release", "upload", *common, *map(str, paths), "--clobber"], check=True)
    release = github_api(endpoint)
    check_draft(release, commit)
    check_uploaded_assets(release, paths)
    check_remote_tag(repo, tag, commit)
    subprocess.run(["gh", "release", "edit", *common, "--verify-tag", "--draft=false"], check=True)
    published = github_api(endpoint)
    if published["draft"]:
        raise ValueError("GitHub Release is still a draft.")
    check_uploaded_assets(published, paths)
    print("Published " + published["html_url"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("unsigned_apk", type=Path)
    prepare_parser.add_argument("output", type=Path)
    commands.add_parser("publish").add_argument("output", type=Path)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.unsigned_apk, args.output)
    else:
        publish(args.output)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        sys.exit(1)
