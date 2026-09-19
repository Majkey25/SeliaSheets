# Workspace and PDF search verification

These changes follow the [PDF study release](2026-09-12-pdf-study-tools.md). They are not included in version 20.

## Implemented paths

- Two editor sessions keep separate pages, text drafts, and bounded viewport histories. Large screens use a resizable split; phones use a second-editor dialog.
- Both panes save before Settings or workspace exit. A failed write keeps the editors and drafts available for Retry. A shared page has one writable pane.
- Undo history is revalidated against current database content, so another pane's edits cannot be overwritten by stale history.
- Notebook search includes stored PDF mark text and PDF body matches. Opening a result uses the existing draft/ink save barrier and reveals the matching page region without creating an annotation.
- Native PDF search runs in the isolated renderer on Android 15+. Older devices and image-only pages use local OCR when enabled. Queries, results, rectangles, and OCR memory are bounded.

## Evidence

- `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed with 129 JVM tests. `:app:lintDebug` passed.
- Huawei Android 10, `device-qa-20260912-204931-027.log`: 14 of 15 tests passed. Draft recreation, same-page read-only behavior, finger navigation, stale-history protection, recognition cancellation, and markup search passed. The failed-save banner was not always visible.
- `workspace-failure-initial.txt` and its screenshot established that the dialog moved above the screen when the keyboard opened. The error text was at y=-285..-69 and Retry at y=-237..-117. The draft and failure state were intact. The fix uses a non-floating dialog with consumed safe-area and IME insets.
- Huawei, `device-qa-20260912-210850-990.log`: all four search-orchestration tests and six Android-10 PDF backend checks passed. Two native-search checks skipped. The pre-fix workspace banner test failed as expected.
- Huawei, `device-qa-20260912-211335-862.log`: all six tests passed after the dialog fix. These cover both panes across recreation/Settings, failed save/recreation/Retry, same-page read-only behavior, finger page navigation, queued PDF import, and UI search through an unannotated PDF while preserving typed text.

## Remaining acceptance and limits

The stronger post-recreation edit assertion, tablet split insets, and final integrated regression still require a fresh run. Native PDF search requires Android 15+ CI acceptance. The phone has no active pen attached; injected stylus tests do not certify hardware pressure, tilt, hover, or palm behavior.

## September 19 integration

- Candidate version 21 adds insertion after the selected page, native text-field handwriting integration, custom colors, and opacity controls. See the [official-source comparison](2026-09-19-notes-and-drawing-comparison.md).
- [Run 35438127348](https://github.com/Majkey25/SeliaSheets/actions/runs/35438127348), commit `647da09`: build and Android 10 instrumentation passed. Android 17 stopped at 192 of 418 tests: a workspace visibility assertion raced the keyboard animation, then AndroidX Ink crashed during a viewport transition. This run is not release acceptance.
- The crash enters an old `CanvasInProgressStrokesRenderHelperV33.Viewport` callback, then checks the new viewport's render thread through the manager. Updating a transform less often did not eliminate it. Pinned alpha07 and the inspected alpha08 renderer share this implementation; no dependency upgrade is claimed as a fix.
- Candidate `87c0e30` isolates authoring-view lifetimes across buffer resizing, window hiding, and reattachment. A resize waits until active input ends; completed captured ink enters the dry layer before persistence. Resize/first-stroke and background/resume regression tests were added. Android 17 acceptance is still required; same-size buffer-transform changes remain a hardware/lifecycle risk to test.
- Local candidate debug/test builds, lint, signed APK/AAB builds, and 132 JVM tests passed. Both artifacts use the expected upload certificate. APK metadata confirms version 21, `0.8.0-beta.1`, package `com.majkeylab.seliadocs`, minSdk 29 and targetSdk 37; 16 KB alignment passes. AAB verification reports the existing self-signed-certificate, timestamp, POSIX-attribute and JAR stream-order warnings.
- Two local Java processes crashed in native JVM code during separate builds; their private crash logs are retained under `.reference/tmp`. The final build passed with a single-use JVM and per-command Serial GC. No system-wide JVM or device configuration was changed.
- In [run 35439506056](https://github.com/Majkey25/SeliaSheets/actions/runs/35439506056), Android 10 completed 424 primary-group tests with one OCR-history timeout and seven expected skips. The test requested OCR before the selected image reached combined editor state; it now awaits the image selection, as the existing image-flow test does. Its metadata and Undo assertions are unchanged. Both instrumentation groups now run even if the first fails, and either failure keeps CI red.
- The shared Huawei disconnected repeatedly during the September 19 follow-up window. `adb devices -l` reported it offline; no installation or instrumentation was queued in that window. The phone was released to the other projects. This does not validate the new renderer lifecycle on a physical device.
- A follow-up review found that ordinary page navigation retained an already saved text draft. A later action could replay that old page's text over edits from the other pane. The shared action barrier now acknowledges only its exact successfully saved draft and session epoch. The focused JVM regression failed with the retained `Saved text` before the fix; all 135 JVM tests passed afterward. A real two-pane navigation/Settings regression is included but still needs runtime acceptance.
- Run `35440632545` completed the Android 10 primary group successfully. Its 66-test ink group found one new test-coordinate defect: the post-pan sample was above the live viewport (`y=150`, viewport top `170`). The test now asserts that its second stroke lies inside the visible native surface and uses `y=250`. The handoff requirement and timeout remain unchanged; a rerun must establish whether this resolves the failure.
- The same run completed the Android 17 ink group successfully and passed both external-emulator-input pressure/pinch tests. Its primary group timed out waiting for a notebook-picker row. The diagnostic log shows the Dialog opened; the test now waits for a populated list and scrolls to the exact notebook before clicking, rather than requiring it to be on-screen first.
- Clean page text now adopts committed database updates even while edit input is locked. Previously the store-observation marker advanced after a rejected draft callback, leaving the displayed value stale and eligible for a later autosave. Dirty user drafts and user-input acceptance checks are unchanged. A disabled-input/store-refresh/disposal regression is included; runtime verification remains pending.
- The Android 10 run for `d6c16ce` still failed the resize handoff test with valid coordinates. Source/bytecode inspection traced a different backend behavior: `graphics-core 1.0.4` advances its pending draw queue before calling a nullable persisted renderer. A newly created API 29 surface can therefore consume requests before drawing is ready. Fresh authoring-view replacement is now restricted to the affected API 33+ viewport-thread backend; Android 10–12 retain their initialized renderer. The test still requires both native callbacks and input coordinates on every API, with no warmup, timeout increase, or test skip. New runtime acceptance remains required.

Search currently caps combined results at 100. On Android 15+, a page with native text does not OCR its embedded scanned regions after an unsuccessful native query. Search reports unreadable pages instead of silently presenting an exhaustive result. Workspace activity recreation is covered; process-death workspace restoration and movable/resizable phone pop-ups are not implemented.

References: [Flexcil document search](https://support.flexcil.com/hc/en-us/articles/8155027675545-Search-text-in-the-document), [Android PDF search](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page#searchText(java.lang.String)), and [Compose dialog window behavior](https://developer.android.com/reference/kotlin/androidx/compose/ui/window/DialogProperties#decorFitsSystemWindows()).
