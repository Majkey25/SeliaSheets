#!/bin/sh
set -eu

status=0
./gradlew connectedDebugAndroidTest --console=plain "-Pandroid.testInstrumentationRunnerArguments.notClass=com.majkeylab.seliadocs.editor.PageViewportFlowTest,com.majkeylab.seliadocs.editor.StylusRoutingTest" || status=$?
if [ -d app/build/outputs/androidTest-results ]; then
  mkdir -p app/build/qa/primary-instrumentation
  cp -R app/build/outputs/androidTest-results/. app/build/qa/primary-instrumentation/
fi
./gradlew connectedDebugAndroidTest --console=plain "-Pandroid.testInstrumentationRunnerArguments.class=com.majkeylab.seliadocs.editor.PageViewportFlowTest,com.majkeylab.seliadocs.editor.StylusRoutingTest" || status=$?
exit "$status"
