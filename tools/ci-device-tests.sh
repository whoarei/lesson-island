#!/usr/bin/env bash
set -euo pipefail

test_serial=emulator-5554
adb -s "$test_serial" root
adb -s "$test_serial" wait-for-device
# The API 29 default image omits this property and otherwise limits the app to 16 MB.
adb -s "$test_serial" shell setprop dalvik.vm.heapgrowthlimit 256m
adb -s "$test_serial" shell setprop ctl.restart zygote
sleep 3
for attempt in {1..30}; do
  package_service="$(adb -s "$test_serial" shell service check package)"
  if [[ "$package_service" == *": found"* ]]; then
    python3 -B tools/run_device_tests.py --serial "$test_serial" \
      --apks build/device-test-apks --output build/device-test-reports
    exit 0
  fi
  sleep 1
done
printf 'The emulator package service did not restart in time.\n' >&2
exit 1
