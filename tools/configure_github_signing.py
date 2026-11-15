"""One-time, interactive upload of the existing signing key to repository Secrets."""

import argparse
import base64
import getpass
import hashlib
import os
from pathlib import Path
import subprocess
import sys

from ci_check import RELEASE_CERTIFICATE_SHA256


def configure(repo, keystore, alias):
    # Fail before asking for passwords if gh is unavailable or cannot access the repo.
    subprocess.run(["gh", "repo", "view", repo, "--json", "nameWithOwner"], check=True, stdout=subprocess.DEVNULL)
    encoded = base64.b64encode(keystore.read_bytes()).decode("ascii")
    if len(encoded) > 48 * 1024:
        raise ValueError("The Base64 keystore exceeds GitHub's 48 KiB Secret limit.")
    store_password = getpass.getpass("Keystore password (hidden): ")
    key_password = getpass.getpass("Key password (hidden; Enter to reuse keystore password): ") or store_password
    if not store_password or not alias.strip():
        raise ValueError("The keystore password and key alias are required.")
    env = dict(os.environ, LESSON_ISLAND_SETUP_PASSWORD=store_password)
    result = subprocess.run(["keytool", "-exportcert", "-keystore", str(keystore), "-alias", alias,
                             "-storepass:env", "LESSON_ISLAND_SETUP_PASSWORD"], env=env, capture_output=True)
    if result.returncode:
        raise ValueError("Cannot open the signing certificate; check the keystore password and alias.")
    if hashlib.sha256(result.stdout).hexdigest() != RELEASE_CERTIFICATE_SHA256:
        raise ValueError("This key does not match the existing Lesson Island release certificate.")
    secrets = {"ANDROID_KEYSTORE_BASE64": encoded, "ANDROID_KEYSTORE_PASSWORD": store_password,
               "ANDROID_KEY_ALIAS": alias, "ANDROID_KEY_PASSWORD": key_password}
    for name, value in secrets.items():
        subprocess.run(["gh", "secret", "set", name, "--repo", repo], input=value, encoding="utf-8", check=True)
        print(f"Configured {name} in {repo}.")
    print("Signing secrets configured. No tag or Release was created.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="whoarei/lesson-island")
    parser.add_argument("--keystore", type=Path, default=Path("E:/Android/libb.keystore"))
    parser.add_argument("--alias", help="Existing private-key alias; prompted when omitted.")
    args = parser.parse_args()
    configure(args.repo, args.keystore, args.alias or input("Existing key alias: ").strip())


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Signing setup failed: {error}", file=sys.stderr)
        sys.exit(1)
