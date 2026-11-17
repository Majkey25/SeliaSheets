#!/bin/sh
set -eu

status=0
./gradlew connectedDebugAndroidTest --console=plain "-Pandroid.testInstrumentationRunnerArguments.notClass=com.majkeylab.seliadocs.editor.PageViewportFlowTest,com.majkeylab.seliadocs.editor.StylusRoutingTest" || status=$?
if [ -d app/build/outputs/androidTest-results ]; then
  mkdir -p app/build/qa/primary-instrumentation
  cp -R app/build/outputs/androidTest-results/. app/build/qa/primary-instrumentation/
fi
if [ "$status" -ne 0 ]; then
  mkdir -p app/build/qa/primary-instrumentation
  if ! adb pull /sdcard/Android/data/com.majkeylab.seliadocs.debug/files/workspace-chrome-failed.png app/build/qa/primary-instrumentation/; then
    printf '%s\n' 'Workspace failure screenshot is unavailable' >&2
  fi
fi
./gradlew connectedDebugAndroidTest --console=plain "-Pandroid.testInstrumentationRunnerArguments.class=com.majkeylab.seliadocs.editor.PageViewportFlowTest,com.majkeylab.seliadocs.editor.StylusRoutingTest" || status=$?
exit "$status"
