#!/usr/bin/env bash
set -euo pipefail

test_serial=emulator-5554
adb -s "$test_serial" root
adb -s "$test_serial" wait-for-device
# The API 29 default image omits this property and otherwise limits the app to 16 MB.
adb -s "$test_serial" shell setprop dalvik.vm.heapgrowthlimit 256m
adb -s "$test_serial" shell setprop sys.boot_completed 0
adb -s "$test_serial" shell setprop ctl.restart zygote
sleep 3
for attempt in {1..90}; do
  boot_completed="$(adb -s "$test_serial" shell getprop sys.boot_completed | tr -d '\r')"
  package_service="$(adb -s "$test_serial" shell service check package)"
  if [[ "$boot_completed" == "1" && "$package_service" == *": found"* ]]; then
    adb -s "$test_serial" shell input keyevent KEYCODE_WAKEUP
    adb -s "$test_serial" shell wm dismiss-keyguard
    adb -s "$test_serial" shell settings put system screen_off_timeout 2147483647
    adb -s "$test_serial" shell settings put global window_animation_scale 0
    adb -s "$test_serial" shell settings put global transition_animation_scale 0
    adb -s "$test_serial" shell settings put global animator_duration_scale 0
    python3 -B tools/run_device_tests.py --serial "$test_serial" \
      --apks build/device-test-apks --output build/device-test-reports
    exit 0
  fi
  sleep 1
done
printf 'The emulator did not complete its system restart in time.\n' >&2
exit 1
