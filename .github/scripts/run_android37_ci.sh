#!/bin/sh
set -eu

diagnostics="$RUNNER_TEMP/seliasheets-api37"
serial="emulator-${EMULATOR_PORT}"

capture_diagnostics() {
  status=$?
  if [ "$status" -ne 0 ]; then
    mkdir -p "$diagnostics"
    timeout 30s adb -s "$serial" logcat -b all -d -v threadtime > "$diagnostics/logcat.txt" 2>&1 || true
    timeout 90s adb -s "$serial" bugreport "$diagnostics/bugreport.zip" > "$diagnostics/bugreport.txt" 2>&1 || true
    timeout 15s adb -s "$serial" shell dumpsys dropbox --print system_server_crash > "$diagnostics/system-server-crash.txt" 2>&1 || true
    timeout 15s adb -s "$serial" shell df -h /data /sdcard > "$diagnostics/storage.txt" 2>&1 || true
    timeout 15s adb -s "$serial" shell mount > "$diagnostics/mounts.txt" 2>&1 || true
  fi
  exit "$status"
}

trap capture_diagnostics EXIT

./gradlew connectedDebugAndroidTest --console=plain "-Pandroid.testInstrumentationRunnerArguments.notClass=com.majkeylab.seliadocs.editor.PageViewportFlowTest,com.majkeylab.seliadocs.editor.StylusRoutingTest"
./gradlew connectedDebugAndroidTest --console=plain "-Pandroid.testInstrumentationRunnerArguments.class=com.majkeylab.seliadocs.editor.PageViewportFlowTest,com.majkeylab.seliadocs.editor.StylusRoutingTest"
python .github/scripts/run_emulator_stylus_ci.py
