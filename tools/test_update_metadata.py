import hashlib
from pathlib import Path
import tempfile
import unittest

from update_metadata import manifest


class UpdateMetadataTest(unittest.TestCase):
    def test_metadata_uses_the_actual_apk_size_and_digest(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "signed.apk"
            apk.write_bytes(b"test APK content")
            data = manifest(apk, "0.3.0", 5, "\n更新说明\n", "owner/releases", 26, "source")
            self.assertEqual(data["apkSize"], apk.stat().st_size)
            self.assertEqual(data["sha256"], hashlib.sha256(apk.read_bytes()).hexdigest())
            self.assertEqual(data["notes"], "更新说明")
            self.assertEqual(data["apkUrl"], "https://github.com/owner/releases/releases/download/v0.3.0/LessonIsland-0.3.0.apk")

    def test_ambiguous_versions_and_destinations_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "signed.apk"
            apk.write_bytes(b"test content")
            for version, repo in (("01.3.0", "owner/repo"), ("0.3", "owner/repo"),
                                  ("0.3.0", "https://other.example"), ("0.3.0", "owner/repo/extra")):
                with self.subTest(version=version, repo=repo), self.assertRaises(ValueError):
                    manifest(apk, version, 5, "notes", repo, 26, "source")


if __name__ == "__main__":
    unittest.main()
