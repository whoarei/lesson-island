"""Small, dependency-free release guards for GitHub Actions and local checks."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]
VERSION_PATTERN = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
PACKAGE = "top.mossmoss.lessonisland"
# Public fingerprint of the existing libb.keystore release certificate.
RELEASE_CERTIFICATE_SHA256 = "28c461e4f2f8612f9d7717d727e772177474798fd7c48b03ca3ffbca8c81bf0a"


def read_version(build_text):
    name = re.search(r"^\s*versionName\s+'([^']+)'\s*$", build_text, re.MULTILINE)
    code = re.search(r"^\s*versionCode\s+([0-9]+)\s*$", build_text, re.MULTILINE)
    if not name or not re.fullmatch(VERSION_PATTERN, name[1]):
        raise ValueError("versionName must use x.y.z without leading zeroes.")
    if not code or not 1 <= int(code[1]) <= 2100000000:
        raise ValueError("versionCode must be an Android-compatible positive integer.")
    return name[1], int(code[1])


def check_tag(version, tag, changelog):
    if tag != f"v{version}":
        raise ValueError(f"Release tag {tag!r} must match versionName: v{version}.")
    heading = rf"^## {re.escape(version)}[ \t]+—[ \t]+[0-9]{{4}}-[0-9]{{2}}-[0-9]{{2}}[ \t]*$"
    if not re.search(heading, changelog, re.MULTILINE):
        raise ValueError(f"CHANGELOG.md needs a dated release section for {version}.")


def check_assets(names):
    forbidden = ("assets/courses", "assets/audio", "assets/fixtures")
    unexpected = [name for name in names if any(
        name == prefix or name.startswith(prefix + "/") for prefix in forbidden
    )]
    if unexpected:
        raise ValueError("Test content found in the application APK: " + ", ".join(unexpected[:5]))


def build_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Set ANDROID_HOME to the Android SDK directory.")
    return Path(sdk) / "build-tools" / "35.0.0"


def apksigner(*args, **kwargs):
    return subprocess.check_output(
        ["java", "-jar", str(build_tools() / "lib/apksigner.jar"), *map(str, args)],
        encoding="utf-8", **kwargs)


def check_certificate(output, expected):
    fingerprints = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$",
                              output, re.MULTILINE)
    if [value.lower() for value in fingerprints] != [expected.lower()]:
        raise ValueError("APK must have exactly one signer matching the existing release certificate.")


def check_apk(apk, version, code, certificate=None):
    aapt = build_tools() / ("aapt.exe" if os.name == "nt" else "aapt")
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], encoding="utf-8")
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package.groups() != (PACKAGE, str(code), version):
        raise ValueError("APK package/version does not match app/build.gradle.")
    with zipfile.ZipFile(apk) as archive:
        check_assets(archive.namelist())
    if certificate:
        check_certificate(apksigner("verify", "--print-certs", apk), certificate)
    print(f"APK verified: {PACKAGE}, version {version} ({code}), no bundled test content.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("version")
    apk_parser = subparsers.add_parser("apk")
    apk_parser.add_argument("path", type=Path)
    apk_parser.add_argument("--signed", action="store_true", help="Require the existing release certificate.")
    args = parser.parse_args()
    version, code = read_version((ROOT / "app/build.gradle").read_text(encoding="utf-8"))
    if args.command == "apk":
        check_apk(args.path, version, code, RELEASE_CERTIFICATE_SHA256 if args.signed else None)
        return
    if os.environ.get("GITHUB_REF_TYPE") == "tag":
        check_tag(version, os.environ["GITHUB_REF_NAME"],
                  (ROOT / "CHANGELOG.md").read_text(encoding="utf-8"))
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"version={version}\n")
    print(f"Version verified: {version} ({code}).")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"CI check failed: {error}", file=sys.stderr)
        sys.exit(1)
