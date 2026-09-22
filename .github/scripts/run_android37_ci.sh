#!/bin/sh
set -eu

diagnostics="$RUNNER_TEMP/seliasheets-api37"
serial="emulator-${EMULATOR_PORT}"

finish_run() {
  status=$?
  if [ "$status" -ne 0 ]; then
    mkdir -p "$diagnostics" || true
    timeout -k 5s 30s adb -s "$serial" logcat -b all -d -v threadtime > "$diagnostics/logcat.txt" 2>&1 || true
    timeout -k 5s 90s adb -s "$serial" bugreport "$diagnostics/bugreport.zip" > "$diagnostics/bugreport.txt" 2>&1 || true
    timeout -k 5s 15s adb -s "$serial" shell dumpsys dropbox --print system_server_crash > "$diagnostics/system-server-crash.txt" 2>&1 || true
    timeout -k 5s 15s adb -s "$serial" shell df -h /data /sdcard > "$diagnostics/storage.txt" 2>&1 || true
    timeout -k 5s 15s adb -s "$serial" shell mount > "$diagnostics/mounts.txt" 2>&1 || true
  fi
  # The runner's unbounded `adb emu kill` can hang after acknowledging shutdown.
  timeout -k 5s 20s adb -s "$serial" emu kill || true
  pkill -KILL -f "^${ANDROID_HOME}/emulator/qemu/.* -port ${EMULATOR_PORT}( |$)" || true
  exit "$status"
}

trap finish_run EXIT

python .github/scripts/run_emulator_stylus_ci.py
sh .github/scripts/run_android_instrumentation.sh
