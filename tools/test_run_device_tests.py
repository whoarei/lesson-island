import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import run_device_tests as runner


class DeviceRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.apks = self.base / "apks"
        self.apks.mkdir()
        self.output = self.base / "reports"
        self.commands = []

    def fake_command(self, command, log, timeout):
        self.commands.append(command)
        if "verify" in command:
            result = "Signed test APKs verified for test commit\n"
        elif "get-state" in command:
            result = "device\n"
        elif "install" in command:
            result = "Performing Streamed Install\nSuccess\n"
        elif "instrument" in command:
            result = "INSTRUMENTATION_RESULT: stream=\nOK (37 tests)\nINSTRUMENTATION_CODE: -1\n"
        elif "pull" in command:
            screenshot = Path(command[-1]) / "test-screenshots" / "sample.png"
            screenshot.parent.mkdir(parents=True)
            screenshot.write_bytes(b"test image bytes")
            result = "1 file pulled\n"
        else:
            result = "Starting: Intent\n"
        log.write_text(result, encoding="utf-8")
        return 0, result

    def invoke(self, command=None):
        with patch.object(runner, "adb_path", return_value="adb"), \
                patch.object(runner, "run_logged", side_effect=command or self.fake_command), \
                patch("builtins.print"):
            return runner.run("emulator-5554", self.apks, self.output)

    def report(self):
        return json.loads((self.output / "result.json").read_text(encoding="utf-8"))

    def test_shared_selectors_are_explicit_offline_cases(self):
        selected = runner.selectors()
        self.assertEqual(sum(".AppFlowTest#" in name for name in selected), 19)
        self.assertEqual(len(selected), 23)
        self.assertNotIn("top.mossmoss.lessonisland.AppFlowTest", selected)
        self.assertFalse(any("testZLive" in name for name in selected))
        path = self.base / "cases.txt"
        for text in ("", "top.mossmoss.lessonisland.AppFlowTest\n",
                     "top.mossmoss.lessonisland.AppFlowTest#testZLiveImageToPublishedCourse\n",
                     "top.mossmoss.lessonisland.AppFlowTest#testZLiveSpeechPreviewAndItemRegeneration\n",
                     "another.package.Tests\n", selected[0] + "\n" + selected[0] + "\n"):
            with self.subTest(text=text):
                path.write_text(text, encoding="utf-8")
                with self.assertRaises(ValueError):
                    runner.selectors(path)

    def test_physical_device_and_wildcard_serials_are_rejected_before_any_command(self):
        with patch.object(runner, "run_logged") as command:
            for serial in ("phone-123", "localhost:5555", "emulator-*", "", "emulator-5554 extra"):
                with self.subTest(serial=serial), self.assertRaises(ValueError):
                    runner.run(serial, self.apks, self.output)
            command.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_apks_cannot_be_in_the_report_upload_tree(self):
        with self.assertRaises(ValueError):
            runner.run("emulator-5554", self.apks, self.base)
        with self.assertRaises(ValueError):
            runner.run("emulator-5554", self.apks, self.apks / "reports")
        previous = self.base / "previous"
        previous.mkdir()
        evidence = previous / "old.txt"
        evidence.write_text("preserve me")
        with self.assertRaises(ValueError):
            runner.run("emulator-5554", self.apks, previous)
        self.assertEqual(evidence.read_text(), "preserve me")

    def test_failed_verification_prevents_every_adb_call(self):
        def reject(command, log, timeout):
            self.commands.append(command)
            log.write_text("Source commit or certificate mismatch\n")
            return 1, "Source commit or certificate mismatch\n"

        self.assertEqual(self.invoke(reject), 1)
        self.assertEqual(len(self.commands), 1)
        self.assertIn("verify", self.commands[0])
        self.assertFalse(self.report()["testsPassed"])
        self.assertFalse(self.report()["complete"])

    def test_installation_failure_prevents_test_execution(self):
        def fail_install(command, log, timeout):
            if "install" in command:
                self.commands.append(command)
                log.write_text("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\n")
                return 1, log.read_text()
            return self.fake_command(command, log, timeout)

        self.assertEqual(self.invoke(fail_install), 1)
        self.assertFalse(any("instrument" in command for command in self.commands))
        self.assertEqual(self.report()["stage"], "install")

    def test_success_keeps_all_commands_scoped_and_collects_reports_only(self):
        self.assertEqual(self.invoke(), 0)
        for command in self.commands[1:]:
            self.assertEqual(command[:3], ["adb", "-s", "emulator-5554"])
        instrument = next(command for command in self.commands if "instrument" in command)
        self.assertIn(",".join(runner.selectors()), instrument)
        report = self.report()
        self.assertTrue(report["complete"])
        self.assertTrue(report["screenshotsCollected"])
        self.assertEqual(report["testsRun"], 37)
        self.assertTrue((self.output / "verification.txt").is_file())
        self.assertTrue((self.output / "instrumentation.txt").is_file())
        self.assertFalse(list(self.output.rglob("*.apk")))

    def test_missing_screenshots_are_distinguished_from_complete_qa(self):
        def fail_pull(command, log, timeout):
            if "pull" in command:
                self.commands.append(command)
                log.write_text("remote object does not exist\n")
                return 1, log.read_text()
            return self.fake_command(command, log, timeout)

        self.assertEqual(self.invoke(fail_pull), 2)
        report = self.report()
        self.assertTrue(report["testsPassed"])
        self.assertFalse(report["complete"])
        self.assertEqual(report["status"], "passed_without_screenshots")
        self.assertTrue((self.output / "instrumentation.txt").is_file())

    def test_failures_override_a_spurious_ok_marker_and_keep_screenshots(self):
        def fail_tests(command, log, timeout):
            if "instrument" in command:
                self.commands.append(command)
                text = "OK (37 tests)\nFAILURES!!!\nINSTRUMENTATION_CODE: -1\n"
                log.write_text(text)
                return 0, text
            return self.fake_command(command, log, timeout)

        self.assertEqual(self.invoke(fail_tests), 1)
        self.assertFalse(self.report()["testsPassed"])
        self.assertTrue(self.report()["screenshotsCollected"])
        self.assertTrue(any("pull" in command for command in self.commands))

    def test_instrumentation_requires_a_positive_successful_test_count(self):
        self.assertEqual(runner.instrumentation_count("OK (1 test)\nINSTRUMENTATION_CODE: -1\n"), 1)
        for text in ("", "OK (0 tests)\n", "INSTRUMENTATION_FAILED: Process crashed\n",
                     "OK (3 tests)\nINSTRUMENTATION_STATUS_CODE: -2\n",
                     "OK (3 tests)\nINSTRUMENTATION_CODE: 0\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                runner.instrumentation_count(text)

    def test_timed_out_command_preserves_partial_output_and_nonzero_status(self):
        log = self.base / "timeout.txt"
        with patch.object(runner.subprocess, "run", side_effect=subprocess.TimeoutExpired(["adb"], 3, b"partial output\n")):
            code, text = runner.run_logged(["adb"], log, 3)
        self.assertEqual(code, 124)
        self.assertIn("partial output", text)
        self.assertIn("timed out", log.read_text())


if __name__ == "__main__":
    unittest.main()
