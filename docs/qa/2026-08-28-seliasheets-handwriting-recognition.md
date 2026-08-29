# SeliaSheets 0.3.0-beta.1 handwriting recognition Huawei QA

Date: August 28, 2026

## Result

Accepted for the requested final exact-source physical QA gates. The final reviewed source passed
the complete local build gate, a focused Huawei gate of 42 tests, and the full isolated Huawei
suite of 230 tests without a retry.

The authorized Huawei YAL-L21 (Android 10) passed
the reviewed `preContext` fix's honest online/offline ML Kit probe evidence, fresh focused
instrumentation, and the complete isolated suite after four confirmed Compose harness flakes
passed individually on retry. This does not sign, publish, or install a production artifact.

Production `com.majkeylab.seliadocs` was not installed over, cleared, or removed. The isolated
`com.majkeylab.seliadocs.qa` app remains installed. Its test package was removed.

## Changed files

- `app/src/androidTest/java/com/majkeylab/seliadocs/settings/SettingsRecognitionScreenTest.kt`
  scrolls the `LazyColumn` to each exact action before clicking it. Retry now scrolls to the retry
  action, rather than only the error text; it selects the clickable semantics node.
- `app/src/androidTest/java/com/majkeylab/seliadocs/editor/HandwrittenMathFlowTest.kt` waits for
  both the generic recognition message and the rolled-back empty element list before proving Undo.
- A temporary `TemporaryMlKitProbeTest.kt` was built, run online/offline, then deleted via patch.
  It is not in the final source tree.

## Build and identity

- `./gradlew.bat --no-daemon :app:compileDebugAndroidTestKotlin` with the temporary probe:
  `BUILD SUCCESSFUL`, 30 tasks, 27 s.
- QA app/test APK build after test changes: `BUILD SUCCESSFUL`, 72 tasks, 17 s. A later QA test
  APK build without the temporary probe: `BUILD SUCCESSFUL`, 55 tasks, 25 s. Every temporary
  `applicationId` change to `.qa` was restored before device execution.
- Final original-source static gate:
  `:app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:lintDebug` ->
  `BUILD SUCCESSFUL`, 49 tasks, 10 s.
- The earlier release artifact check remains valid: unsigned release APK reports production
  package, SeliaSheets label, version code 4, version `0.3.0-beta.1`, min SDK 29, target SDK 37.

## Device/model evidence

- Cold launch resolved the QA `MainActivity`, created its process, and found no QA crash-buffer
  entry. The compact Android 10 library showed the full SeliaSheets label without clipping.
- Real app settings showed recognition disabled by default, Czech selected, the approximately
  20 MB model action, and the on-device/SDK disclosure. Evidence retained:
  `.reference/tmp/handwriting-recognition/qa-recognition-settings.png`.
- Real app lifecycle: Czech Ready; English Ready; English delete -> Download model; English
  redownload -> Ready. UI exposed no byte count and no error.
- Task 2's temporary probe independently asserted Czech/English download and Ready status, then
  recognized synthetic `1+1=` online and after offline app force-stop/restart: `OK (1 test)` in
  each run. It was removed after the run.
- Original connectivity was restored after the offline run: airplane mode 0, Wi-Fi 1, mobile data 1.

## Instrumentation

- Focused corrected classes:
  `SettingsRecognitionScreenTest` + `HandwrittenMathFlowTest` -> `OK (26 tests)`, 11.452 s.
  This includes all four recognition-settings UI states and the candidate-choice rollback/Undo path.
- Temporary real-model probe, online: 1/1 failure, 1.073 s. It threw
  `IllegalStateException: Missing required properties: preContext` at
  `MlKitInkTextRecognizer.kt:29` before evaluating the first synthetic `1+1=` fixture.
- Temporary real-model probe, offline after force-stop/restart: same 1/1 failure, 0.957 s, same
  location. Only one fixture variant was attempted; changing geometry cannot supply a missing
  ML Kit context property.
- Complete QA instrumentation suite excluding only `AppIdentityTest`:
  211 tests, 1 failure, 150.344 s. Recognition mapper (6), settings repository/flow/screen (12),
  handwritten math flow (22), smart-shape, typed text, and page workflows passed.
- Remaining failure:
  `StylusRoutingTest.stylusCancelsActiveFingerStrokeBeforeDrawing` at line 205. It is outside the
  two authorized harness test edits and blocks a release-quality full-suite pass.

Fresh final Huawei execution after the reviewed fix:

- Focused mapper/settings/handwritten-math/prior-stylus method: `OK (34 tests)`, 13.354 s.
  Mapper now includes seven tests.
- Complete suite excluding only `AppIdentityTest`: 212 tests, 4 initial failures. They were all
  Compose harness setup/timeouts in `EditorCompactUiTest` (three methods) and
  `PageFlowTest.addDuplicateAndDeletePages`.
- The four exact methods were rerun together once: `OK (4 tests)`, 14.549 s. They are reported as
  harness flakes, not hidden from the suite result.

## Release blockers

No unresolved functional blocker remains in the final exact-source acceptance. An earlier
full-suite run recorded four
Compose harness flakes; the exact one-time rerun of each passed. Preserve those counts in release
review rather than presenting the initial suite output as clean.

Publication remains blocked separately: the current release AAB is unsigned, and the source is
not committed or pushed under the active repository instructions.

## Final-acceptance revalidation

The reviewed `preContext` fix and its Task 2 Huawei evidence were inspected. A fresh isolated QA
app/test APK build succeeded, production identity was restored in source, and the final original
source gate passed: debug APK, AndroidTest APK, JVM tests, lint, and release-main-manifest
processing (`BUILD SUCCESSFUL`, 91 tasks, 72 s). The unsigned production artifact still reports
version code 4, `0.3.0-beta.1`, SDK 29/37, SeliaSheets label, and the intended Internet/network
permissions.

At 2026-08-28 14:34-14:49 +02:00, the shared device was checked 15 times without interruption:
14 foreground snapshots were `seliacycles` and one Nearby Sharing settings. When it returned to
the Huawei launcher with no foreign instrumentation, a fresh QA app/test pair was built and
installed. An initial stale production-identity artifact was rejected by Android signature safety;
it did not replace production. The QA pair was then rebuilt with `.qa`, source restored, and both
QA packages installed successfully.

## Final exact-source acceptance

- Local exact-source gate: JVM tests, lint, debug APK, AndroidTest APK, release manifest, release
  APK, and release AAB -> `BUILD SUCCESSFUL`, 149/149 tasks executed in 2 minutes.
- Huawei focused gate: `OK (42 tests)`, 18.937 seconds.
- Huawei full suite excluding only the production-package `AppIdentityTest`:
  `OK (230 tests)`, 156.022 seconds. No retry was needed.
- Handwritten math immediate Undo/new-stroke class: `OK (25 tests)`; final viewport/gesture class:
  `OK (4 tests)`; affected gesture classes previously passed `OK (23 tests)`.
- Real ML Kit synthetic `1+1=` remained verified online and offline with Czech and English models
  ready; model delete/redownload lifecycle was verified earlier in this same QA cycle.
- Final unsigned release APK SHA-256:
  `E964246FEE7CF90F6313047D666EB96EF8A3B58F92785CDA2CD11AE29C5DD6DF`.
- Final unsigned release AAB SHA-256:
  `E050FA53F793D13293275C896BC1A1F234D89FFA0F50B3780A2657F41E520381`.
- Artifact identity: `com.majkeylab.seliadocs`, SeliaSheets, version code 4,
  `0.3.0-beta.1`, minimum SDK 29, target SDK 37. `jarsigner -verify` reports the AAB unsigned.

## Earlier post-acceptance blocker (resolved)

The later exact-source QA run passed `HandwrittenMathFlowTest` 25/25 in 9.965 s, including the
immediate-Undo visibility race. Its full suite then failed 13/231: two adaptive-icon float-format
assertions (`32%` vs `32.000004%`, `LauncherIconResourceTest.kt:190`); toolbar selected-state
assertion (`EditorCompactUiTest.kt:99`); six ElementSelectionFlow failures (four Activity
`setContent` ownership errors at lines 95/234/120/53, null at 189, assertion at 160); two
PageViewport 3 s timeouts (lines 132/91); and StylusRouting assertion at line 205. This invalidates
the prior acceptance. No source change was made in this QA run.

Those failures were corrected before the final 42-test focused and 230-test full runs above.

## Cleanup

- Removed `com.majkeylab.seliadocs.qa.test` only; QA app retained.
- Final package inspection also found the stale test-only `com.majkeylab.seliadocs.test` from the
  rejected production-identity test install. `adb -s BQLDU19927002646 uninstall
  com.majkeylab.seliadocs.test` returned `Success`; final package output contains only production,
  legacy debug, and QA apps.
- Production package/data unchanged.
- Removed temporary probe source and temporary logs. Only the non-sensitive settings screenshot
  above remains under `.reference/tmp/handwriting-recognition/`.
- No commit, stage, push, PR, release, or Play Console action.
