# SeliaSheets 0.6 beta verification

Candidate: version code 13, `0.6.0-beta.1`, package `com.majkeylab.seliadocs`.

## Physical Android 10

Huawei USB test runs on September 5 used an exclusive device window. No app data was cleared.

- The broad instrumentation run at 18:52 reported `OK (311 tests)`, with one external-stylus assumption skip.
- The separate viewport/input run at 18:57 reported `OK (40 tests)`, with three API/hardware assumption skips.
- These runs cover inline text persistence and recreation, backup validation and migrations, image transforms and OCR history, PDF retry, ink selection, pressure-event routing, palm/eraser transitions, and zoomed coordinates.
- Those results precede the subsequent same-text input callback fix and ink-duplicate test synchronization change. Their targeted Huawei rerun at 19:29 passed all 10 tests.

Injected Android `MotionEvent` tests verify application routing. They do not verify a physical pen digitizer. The attached phone has no detected active stylus, so hardware pressure, tilt, hover, barrel buttons, and palm rejection remain unverified.

## Build and CI

The local gate passed again after the same-text callback and ink-duplicate test fixes: JVM tests, Android lint, debug/test APKs, and externally signed release APK/AAB builds, 150 tasks in 3m 8s. The JVM report contains 97 tests. Lint reports no errors, 61 warnings, and two hints.

The candidate APK passes APK v2 signature verification with one expected RSA-4096 signer and 16 KB ZIP alignment. The AAB reports `jar verified`, with self-signed/no-timestamp and JAR stream-order warnings. These are candidate checks; final release hashes must be taken from the merged commit's signed build.

The live Play Console still serves code 12 in closed `alpha`. Its saved data-safety preview declares Diagnostics, App interactions, and Device or other IDs; no sharing, encrypted transit, no publisher deletion request, and the expected GitHub Pages privacy URL. No declaration was changed during this check.

[CI run 33978858120](https://github.com/Majkey25/SeliaSheets/actions/runs/33978858120) passed the build job but failed runtime checks:

- Android 10 completed the broad suite with three failures. Two rejected-paste tests exposed same-text focus/selection callbacks being forwarded as draft changes. The ink-copy test inspected Undo before the combined state finished updating.
- Android 17 could not resolve the test activity and then reported `INSTRUMENTATION_ABORTED: System has crashed` after 11 of 311 tests. The native emulator pressure/pinch stage did not run.

The release remains gated on corrected runtime checks, signed release smoke testing, artifact verification, and Play acceptance. A green local build is not release acceptance.

## Follow-up runtime checks

[CI run 33980852841](https://github.com/Majkey25/SeliaSheets/actions/runs/33980852841) passed the build and Android 10 jobs. Both Android 17 instrumentation stages completed without failures: the runner reported 312 and 42 tests, with expected external-input assumption skips. Guest storage was healthy and the diagnostics contained no system-server crash. The final native pressure/pinch stage could not start because the emulator's gRPC service was not enabled. The next run explicitly enables the authenticated loopback service with `-grpc 8556 -grpc-use-token`.

The signed, minified code-13 APK installed and cold-launched on Huawei in 308 ms. Manual checks covered notebook creation, full-page typing, persistence after reopening, adding a page, selecting pages through Contents, and opening Settings. The app's memory snapshot after this sequence was 73,598 KB PSS with one Activity. The mixed interaction sequence reported 580 rendered frames, 78 janky frames, and 8/20/32/77 ms at the 50th/90th/95th/99th percentiles. This is a smoke-test observation, not a controlled performance comparison.

Android 10's `adb input touchscreen swipe` produced `TOOL_TYPE_UNKNOWN`, not finger or stylus input. The opt-in input diagnostic confirmed `tool=0`, `source=4098`; its stylus assertion failed as expected. These swipes cannot establish drawing acceptance because the editor deliberately accepts only identified finger/stylus/eraser tools. The signed-release drawing check therefore uses explicit stylus `PointerProperties` through `UiAutomation.injectInputEvent`, with screenshot assertions near the requested stroke.

The signed APK passed that stylus pixel check at fitted scale and after two successive native pinches. The test reads the accessibility zoom percentage and requires an increase before drawing. Switching back to full-page typing kept all ink visible; reopening retained both typed text and strokes. The exported PDF contains two A4 pages, 14,323 bytes, with the expected text, strokes, and paper pattern. Both pages were rendered with Poppler and inspected.

Native finger testing found a separate reproducible first-input failure: the first finger stroke in a freshly reopened fitted editor produced no ink, despite finger drawing being enabled. The same coordinates worked for stylus input; subsequent finger input then worked. Two cold-input tests failed without the old API 29 test-only hover warmup: both a first finger stroke and a short first stylus stroke timed out after 10 seconds.

Moving ink initialization earlier with `InProgressStrokesView.eagerInit()` in `InkCanvasView.onAttachedToWindow()` made both tests pass unchanged. The obsolete synthetic hover and fixed readiness delays were then removed. The full viewport/stylus group reported `OK (42 tests)`, including three expected API/external-input skips. Six targeted text, shape, and deferred-cleanup regressions passed. The new signed APK also passed the first-finger pixel check immediately after a fresh process/editor launch, followed by a native pinch and stylus pixel check. JVM tests, lint, and signed APK/AAB builds passed, 102 tasks in 1m 15s.

Review of [run 33982362074](https://github.com/Majkey25/SeliaSheets/actions/runs/33982362074) also found an unsafe cleanup path. A closed editor's detached global sweep could delete files held only in another editor's Undo history. The sweep was removed. Orphan images remain until the next library startup; explicit notebook deletion still cleans its unreferenced files. The regression test verifies preservation after editor close and cleanup at fresh library startup. The local build, 97 JVM tests, lint, and signed artifact build passed after this removal, 150 tasks in 2m 38s. Physical reruns remain pending.
