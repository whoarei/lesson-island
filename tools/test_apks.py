"""Sign and verify private APK artifacts for dedicated emulator regression tests."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from ci_check import (PACKAGE, RELEASE_CERTIFICATE_SHA256, apksigner, build_tools,
                      check_apk, check_certificate, read_version, ROOT)
from github_release import sha256, signing_secrets


def output_apk(directory):
    metadata = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
    if len(metadata["elements"]) != 1:
        raise ValueError("Expected exactly one universal APK.")
    name = metadata["elements"][0]["outputFile"]
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+\.apk", name):
        raise ValueError("Invalid APK output filename.")
    path = directory / name
    if not path.resolve().is_relative_to(directory.resolve()) or not path.is_file():
        raise ValueError("The APK must be a file inside its output directory.")
    return path


def verify(directory):
    metadata = json.loads((directory / "test-apks.json").read_text(encoding="utf-8"))
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if metadata["sourceCommit"] != commit:
        raise ValueError("The test APKs must match the checked-out source commit.")
    for name in ("LessonIsland-debug.apk", "LessonIsland-androidTest.apk"):
        path = directory / name
        if sha256(path) != metadata["sha256"][name]:
            raise ValueError("Test APK checksum mismatch: " + name)
    version, code = read_version((ROOT / "app/build.gradle").read_text(encoding="utf-8"))
    check_apk(directory / "LessonIsland-debug.apk", version, code, RELEASE_CERTIFICATE_SHA256)
    for name in ("LessonIsland-debug.apk", "LessonIsland-androidTest.apk"):
        path = directory / name
        check_certificate(apksigner("verify", "--print-certs", path), RELEASE_CERTIFICATE_SHA256)
    aapt = build_tools() / ("aapt.exe" if os.name == "nt" else "aapt")
    badging = subprocess.check_output(
        [str(aapt), "dump", "badging", str(directory / "LessonIsland-androidTest.apk")], encoding="utf-8")
    if not re.search(r"^package: name='" + re.escape(PACKAGE + ".test") + r"'", badging):
        raise ValueError("Unexpected instrumentation APK package.")
    print("Signed test APKs verified for " + commit)


def sign(directory):
    key = signing_secrets(os.environ)
    directory.mkdir(parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise ValueError("The test APK output directory must be empty.")
    unsigned = [output_apk(ROOT / "app/build/outputs/apk/debug"),
                output_apk(ROOT / "app/build/outputs/apk/androidTest/debug")]
    names = ["LessonIsland-debug.apk", "LessonIsland-androidTest.apk"]
    zipalign = build_tools() / ("zipalign.exe" if os.name == "nt" else "zipalign")
    with tempfile.TemporaryDirectory(prefix="lesson-island-tests-", dir=os.environ.get("RUNNER_TEMP")) as temp:
        key_path = Path(temp) / "user.keystore"
        key_path.write_bytes(key)
        key_path.chmod(0o600)
        env = dict(os.environ)
        env["ANDROID_KEY_PASSWORD"] = env.get("ANDROID_KEY_PASSWORD") or env["ANDROID_KEYSTORE_PASSWORD"]
        for apk, name in zip(unsigned, names):
            aligned = Path(temp) / name
            subprocess.run([str(zipalign), "-P", "16", "-f", "4", str(apk), str(aligned)], check=True)
            apksigner("sign", "--ks", key_path, "--ks-key-alias", env["ANDROID_KEY_ALIAS"],
                      "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
                      "--v4-signing-enabled", "false", "--out", directory / name, aligned, env=env)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    (directory / "test-apks.json").write_text(json.dumps(
        {"sourceCommit": commit, "sha256": {name: sha256(directory / name) for name in names}},
        indent=2) + "\n", encoding="utf-8")
    verify(directory)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("sign", "verify"))
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        (sign if args.command == "sign" else verify)(args.directory)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Test APK preparation failed: {error}", file=sys.stderr)
        sys.exit(1)
