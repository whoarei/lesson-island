"""Build the public, credential-free metadata consumed by the Android updater."""

import hashlib
import json
from pathlib import Path
import re

from ci_check import VERSION_PATTERN


def manifest(apk, version, code, notes, repository, min_sdk, commit):
    if not re.fullmatch(VERSION_PATTERN, version) or not 1 <= code <= 2100000000:
        raise ValueError("Invalid update version.")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("An existing owner/repository is required for update downloads.")
    if min_sdk < 26 or not notes.strip() or not commit:
        raise ValueError("Missing update compatibility or release information.")
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    size = apk.stat().st_size
    if size <= 0:
        raise ValueError("The update APK is empty.")
    return {"schemaVersion": 1, "versionName": version, "versionCode": code,
            "notes": notes.strip(),
            "apkUrl": f"https://github.com/{repository}/releases/download/v{version}/LessonIsland-{version}.apk",
            "apkSize": size, "sha256": digest, "minSdk": min_sdk, "sourceCommit": commit}


def write_manifest(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read_min_sdk(build_file):
    match = re.search(r"^\s*minSdk\s+([0-9]+)\s*$", build_file.read_text(encoding="utf-8"), re.MULTILINE)
    if not match:
        raise ValueError("Cannot read minSdk from app/build.gradle.")
    return int(match[1])
