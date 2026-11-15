import unittest

from ci_check import check_assets, check_tag, read_version


class ReleaseGuardsTest(unittest.TestCase):
    def test_version_format_and_android_code_limits(self):
        self.assertEqual(read_version("versionName '1.2.3'\nversionCode 4\n"), ("1.2.3", 4))
        for name, code in (("01.2.3", 4), ("1.2", 4), ("1.2.3-beta", 4),
                           ("1.2.3", 0), ("1.2.3", 2100000001)):
            with self.subTest(name=name, code=code), self.assertRaises(ValueError):
                read_version(f"versionName '{name}'\nversionCode {code}\n")

    def test_tag_requires_matching_version_and_release_notes(self):
        changelog = "## 未发布\n\n- Work in progress\n\n## 1.2.3 — 2026-11-15\n\n- Released\n"
        check_tag("1.2.3", "v1.2.3", changelog)
        for tag, notes in (("v1.2.4", changelog), ("v01.2.3", changelog),
                           ("v1.2.3", "## 未发布\n\n- Work in progress\n")):
            with self.subTest(tag=tag, notes=notes), self.assertRaises(ValueError):
                check_tag("1.2.3", tag, notes)

    def test_test_assets_are_rejected_in_application_apk(self):
        check_assets(["AndroidManifest.xml", "classes.dex", "assets/readme.txt"])
        for name in ("assets/courses", "assets/courses/a.json", "assets/audio/a.mp3",
                     "assets/fixtures/courses/a.json", "assets/fixtures/images/a.jpg"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                check_assets(["classes.dex", name])


if __name__ == "__main__":
    unittest.main()
