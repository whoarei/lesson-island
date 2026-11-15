"""Run the maintained offline regression set on an explicitly selected dedicated emulator."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from ci_check import PACKAGE, ROOT
import test_apks


SELECTORS = ROOT / "tools/android-test-classes.txt"
SCREENSHOTS = "/sdcard/Android/data/" + PACKAGE + "/files/test-screenshots"


def selectors(path=SELECTORS):
    result = []
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        value = raw.strip()
        if not value or value.startswith("#"):
            continue
        if not re.fullmatch(r"top\.mossmoss\.lessonisland\.[A-Za-z][A-Za-z0-9_]*(?:#test[A-Za-z0-9_]+)?", value):
            raise ValueError("Invalid Android test selector: " + value)
        if value == PACKAGE + ".AppFlowTest" or "#testZLive" in value:
            raise ValueError("The offline regression set must not include live-model API tests.")
        if value in result:
            raise ValueError("Duplicate Android test selector: " + value)
        result.append(value)
    if not result:
        raise ValueError("The offline Android test list is empty.")
    return result


def instrumentation_count(output):
    if re.search(r"FAILURES!!!|INSTRUMENTATION_(?:FAILED|ABORTED)|^INSTRUMENTATION_STATUS_CODE:\s*-[12]\s*$",
                 output, re.MULTILINE):
        raise ValueError("Android instrumentation reported a test failure or abort.")
    counts = re.findall(r"^\s*OK \(([0-9]+) tests?\)\s*$", output, re.MULTILINE)
    if len(counts) != 1 or int(counts[0]) < 1:
        raise ValueError("Android instrumentation did not report OK (N tests) with N > 0.")
    codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?[0-9]+)\s*$", output, re.MULTILINE)
    if codes and codes[-1] != "-1":
        raise ValueError("Android instrumentation did not finish successfully.")
    return int(counts[0])


def adb_path():
    if os.environ.get("ANDROID_ADB"):
        return os.environ["ANDROID_ADB"]
    available = shutil.which("adb")
    if available:
        return available
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        path = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if path.is_file():
            return str(path)
    raise ValueError("Cannot find adb; set ANDROID_HOME or ANDROID_ADB.")


def run_logged(command, path, timeout):
    try:
        result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                encoding="utf-8", errors="replace", timeout=timeout)
        output = result.stdout or ""
    except subprocess.TimeoutExpired as error:
        output = error.output or ""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        output += "\nCommand timed out after " + str(timeout) + " seconds.\n"
        result = subprocess.CompletedProcess(command, 124, output)
    except OSError as error:
        output = str(error) + "\n"
        result = subprocess.CompletedProcess(command, 126, output)
    path.write_text(output, encoding="utf-8")
    return result.returncode, output


def now():
    return datetime.now(timezone.utc).isoformat()


def run(serial, apks, output):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("Select a dedicated emulator explicitly, for example --serial emulator-5554.")
    apks, output = Path(apks).resolve(), Path(output).resolve()
    if apks == output or apks.is_relative_to(output) or output.is_relative_to(apks):
        raise ValueError("APK and report directories must be separate, without overlapping paths.")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("The report directory must be empty; preserve prior evidence in its own directory.")
    report = {"schemaVersion": 1, "serial": serial, "startedAt": now(), "status": "failed",
              "complete": False, "testsPassed": False, "testsRun": 0, "screenshotsCollected": False}
    exit_code = 1
    try:
        selected = selectors()
        report["selectors"] = selected
        print("Verifying source commit, APK hashes and the existing signing certificate...", flush=True)
        report["stage"] = "verify"
        # Execute the existing verifier as a child to capture its SDK/Java stderr as well as Python output.
        code, _ = run_logged([sys.executable, "-B", str(Path(test_apks.__file__).resolve()), "verify", str(apks)],
                             output / "verification.txt", 120)
        if code:
            raise ValueError("Signed test APK verification failed; no APK was installed. See verification.txt.")
        adb = adb_path()

        def device(*args, log, timeout=120):
            return run_logged([adb, "-s", serial, *map(str, args)], output / log, timeout)

        report["stage"] = "device"
        code, state = device("get-state", log="device.txt", timeout=30)
        if code or "device" not in state.splitlines():
            raise ValueError("The selected emulator is not ready. See device.txt.")
        for name, log in (("LessonIsland-debug.apk", "install-application.txt"),
                          ("LessonIsland-androidTest.apk", "install-instrumentation.txt")):
            report["stage"] = "install"
            print("Installing " + name + " on " + serial + "...", flush=True)
            code, result = device("install", "-r", apks / name, log=log)
            if code or not re.search(r"^Success\s*$", result, re.MULTILINE):
                raise ValueError("APK installation failed. See " + log + ".")
        code, _ = device("shell", "am", "start", "-n", PACKAGE + "/.MainActivity", log="launch.txt")
        if code:
            raise ValueError("Cannot launch the test application. See launch.txt.")
        report["stage"] = "instrumentation"
        print("Running offline Android regression tests...", flush=True)
        code, result = device("shell", "am", "instrument", "-w", "-e", "class", ",".join(selected),
                              PACKAGE + ".test/android.test.InstrumentationTestRunner",
                              log="instrumentation.txt", timeout=900)
        failure = None
        try:
            if code:
                raise ValueError("The instrumentation command failed. See instrumentation.txt.")
            report["testsRun"] = instrumentation_count(result)
            report["testsPassed"] = True
        except ValueError as error:
            failure = error
        screenshot_directory = output / "screenshots"
        code, _ = device("pull", SCREENSHOTS, screenshot_directory, log="screenshot-pull.txt")
        report["screenshotsCollected"] = not code and any(screenshot_directory.rglob("*.png"))
        if failure:
            raise failure
        if not report["screenshotsCollected"]:
            report["status"] = "passed_without_screenshots"
            report["message"] = "Tests passed, but screenshot collection failed or returned no PNGs; QA evidence is incomplete."
            exit_code = 2
        else:
            report["status"] = "passed"
            report["complete"] = True
            exit_code = 0
    except (ValueError, OSError) as error:
        report["message"] = str(error)
    finally:
        report["exitCode"] = exit_code
        report["finishedAt"] = now()
        (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if exit_code:
        print(report.get("message", "Device regression failed."), file=sys.stderr)
    else:
        print("Passed " + str(report["testsRun"]) + " offline tests; logs and screenshots: " + str(output))
    return exit_code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Dedicated emulator serial, such as emulator-5554.")
    parser.add_argument("--apks", type=Path, required=True, help="Signed APK directory containing test-apks.json.")
    parser.add_argument("--output", type=Path, required=True, help="Empty directory for logs, screenshots and result.json.")
    args = parser.parse_args()
    try:
        return run(args.serial, args.apks, args.output)
    except (ValueError, OSError) as error:
        print("Device regression failed: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
