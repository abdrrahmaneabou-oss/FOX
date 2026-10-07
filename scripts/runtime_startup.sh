#!/usr/bin/env bash
set -euo pipefail

mkdir -p runtime-results runtime-original
unzip -q FOX_AWG_Private_Project.zip input/FOX_ORIGINAL.apk -d runtime-original

adb shell getprop > runtime-results/device-properties.txt
adb root > runtime-results/adb-root.txt 2>&1 || true
adb wait-for-device

# Prevent Android's one-time immersive-mode tutorial from covering the app UI
# and confusing UIAutomator-based checks on fresh CI emulators.
adb shell settings put secure immersive_mode_confirmations confirmed > /dev/null 2>&1 || true

for fox_kind in original fixed; do
  if [[ "$fox_kind" == original ]]; then
    fox_apk=runtime-original/input/FOX_ORIGINAL.apk
    fox_package=com.fox.onev8
  else
    fox_apk=runtime-apk/FOX_V12_Modern.apk
    fox_package=com.fox.onev8
    adb uninstall com.fox.onev8 > runtime-results/remove-original.txt 2>&1 || true
  fi

  if ! adb install -r "$fox_apk" > "runtime-results/$fox_kind-install.txt" 2>&1; then
    cat "runtime-results/$fox_kind-install.txt"
    echo "ERROR: failed to install $fox_kind APK" >&2
    exit 1
  fi

  adb logcat -c
  if ! adb shell am start -W -n "$fox_package/com.ponie.dayov12.LoginActivity" > "runtime-results/$fox_kind-start.txt" 2>&1; then
    cat "runtime-results/$fox_kind-start.txt"
    echo "ERROR: failed to launch $fox_kind APK" >&2
    exit 1
  fi

  sleep 5
  adb logcat -d -v threadtime > "runtime-results/$fox_kind-logcat.txt"
  adb shell dumpsys activity activities > "runtime-results/$fox_kind-activities.txt"
  adb shell dumpsys activity exit-info "$fox_package" > "runtime-results/$fox_kind-exit-info.txt" || true
  adb shell pidof "$fox_package" > "runtime-results/$fox_kind-pid.txt" || true
  adb exec-out screencap -p > "runtime-results/$fox_kind-screen.png"
  adb shell uiautomator dump /sdcard/fox-startup-ui.xml > "runtime-results/$fox_kind-ui-dump.txt" 2>&1 || true
  adb pull /sdcard/fox-startup-ui.xml "runtime-results/$fox_kind-ui.xml" > /dev/null 2>&1 || true
  adb shell find "/data/user/0/$fox_package" -type f > "runtime-results/$fox_kind-data-files.txt" 2>&1 || true
  adb pull "/data/user/0/$fox_package" "runtime-results/$fox_kind-app-data" > "runtime-results/$fox_kind-data-pull.txt" 2>&1 || true

  echo "==== $fox_kind ===="
  cat "runtime-results/$fox_kind-install.txt" "runtime-results/$fox_kind-start.txt" "runtime-results/$fox_kind-pid.txt"
  grep -n -A 35 -E 'FATAL EXCEPTION|Fatal signal|Unable to instantiate|Unable to start|Caused by:' "runtime-results/$fox_kind-logcat.txt" || true

  if [[ "$fox_kind" == fixed ]]; then
    if [[ ! -s runtime-results/fixed-pid.txt ]]; then
      echo "ERROR: fixed APK process is not alive after startup" >&2
      exit 1
    fi
    if ! grep -q 'com.fox.onev8/com.ponie.dayov12.MainActivity' runtime-results/fixed-activities.txt; then
      echo "ERROR: fixed APK did not reach MainActivity" >&2
      exit 1
    fi
  fi

  adb shell am force-stop "$fox_package"
done

# UI text is intentionally not asserted here: a first-boot Android system overlay
# can own the accessibility tree even while FOX is correctly visible underneath.
# The presentation test below performs app-aware UI checks and dismisses such overlays.
python3 scripts/runtime_presentation.py
