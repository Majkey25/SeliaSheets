# Study workspace checks

Candidate: `0.9.0-beta.1`, version code 23. Uploaded to Play as an unpublished Alpha draft with English notes. Play validation reports no lost device support. Version 22 remains available to selected testers.

## Implemented scope

- Editable DOCX body-text import and typed-text export. Original files remain unchanged and are not copied into notebook backups.
- Workspace-wide save before DOCX or PDF export, including another pane's inline draft. Failed saves retain the destination for retry.
- First-run introduction with Skip, Back, persistence, and Settings replay. A failed completion write offers session-only access to notes.
- Six Material palettes with previews and named radio semantics. No paper, stroke, or export color changes.
- Opening-cover decoration and existing page transitions behind one setting. Defaults off, preserves existing choices, and respects system-disabled animations.

DOCX uses the platform ZIP/XML APIs without extraction or external requests. Limits cover compressed and actual inflated bytes, individual parts, entries, XML events/depth, text, and inserted pages. Macros, embedded objects, encryption, fields, and tracked changes are rejected. Tables become plain paragraphs. Rich layout and images are not imported.

## Checks recorded

- 144 JVM tests passed; debug APK and instrumentation APK built.
- Huawei YAL-L21, Android 10, serial `BQLDU19927002646`: 41 focused checks passed for DOCX codec/flow, keyboard palette selection, and cover input/lifetime behavior.
- A second Huawei batch passed 40 checks: settings-read recovery, first-run persistence, opening motion, DOCX boundary cases, and both-pane draft saving before export. These batches overlap; they are not 81 distinct tests.
- Transient settings-read failure reproduced on Huawei: successful Skip write left the introduction visible. The regression failed before the repository retry fix and passed afterward.
- The first lint run found a redundant `RequiresApi` on an SDK-filtered test. Removed it; final `lintDebug` passed, including the export-recovery fixes.
- Final Huawei editor batch: 56 checks passed, including failed export/new destination recovery, workspace recreation, compact controls, save barriers, and opening decoration.
- Huawei input batch: 60 passed, 3 expected hardware/API skips (JUnit reports 63 tests). Includes visible highlighter pixels, pinch/pan coordinates, palm cancellation, pressure routing, and tool transitions with injected events.
- Signed release build, APK signature, AAB JarFile signature, and expected upload certificate verified. Version 23 installed over 22 without clearing data. Onboarding Skip and existing notebook reopen succeeded; native-window ink smoke checks passed at fit and after pinching.
- Signed release file-picker roundtrip passed: imported a three-paragraph DOCX with Czech text and an emoji, edited it through the Android keyboard, and exported through the system save picker. The exported `word/document.xml` contains `Edited-on-Huawei-Release-23` and the Unicode text. The original file's SHA-256 stayed `465a910b1200f8eb127122d94ebd503802f867450a94fe36e67fff1dfc1a8d78`.
- Manual phone inspection verified the insertion dialog and DOCX action. The detailed import warning was shortened after inspection; the completion dialog retains the full source-fidelity warning.

Physical run logs are local under `.reference/tmp`: `device-qa-20260923-000104-742.log` (expected red), `device-qa-20260923-000130-950.log` (41 passed), `device-qa-20260923-000918-591.log` (40 passed), `device-qa-20260923-001921-047.log` (56 passed), and `device-qa-20260923-002214-973.log` (60 passed, 3 skips). Signed-window captures are `release23-fit.png` and `release23-pinch.png`. An earlier invocation returned zero tests while APK installation was still running; it is not verification evidence. The local runner now rejects zero-test results.

## Performance and remaining verification

Opening motion changes only a cover graphics layer, not the native ink surface. The frame value is read in that layer to avoid per-frame composition/layout. A single warm debug opening on Huawei recorded 17 frames, 3 janky frames, and a 5 ms median; startup and debug overhead remain in that sample. This is not a release-performance benchmark or evidence that all jank is fixed.

Review also found a stale-destination retry after output cleanup and duplicate workspace-save dispatch after recreation. The saved-request deduplication passed JVM checkpoint tests. A failed export releases the workspace and leaves its error in the exporter UI; only a draft-save failure retains the destination for retry. The extended physical recovery check passed. Android 17 CI must still execute the native PDF edit capability test. Huawei has no compatible active pen attached, so synthetic input is not pressure/tilt/hover hardware certification.

CI run `35791581317`: build passed. The primary Android 10 suite reported 481 tests, 1 failure, and 7 skips. Its failure was a setup click before the merged `New notebook` target was ready after a DataStore update. The shared PDF fixture now waits for that same visible/enabled target; it does not switch to an unmerged node or remove the PDF-search assertions. All six PDF study flows then passed on Huawei (`device-qa-20260923-004009-052.log`). Full CI rerun is still required.

The same run's Android 17 direct emulator stylus injection passed both pressure-at-zoom and live-pinch checks. Its separate input suite completed 65 cases with 2 expected skips and no failures. The primary suite failed seven early UI taps plus the experimental PDF fixture's object-count assumption. A shared JUnit RuleChain now waits for the visible, enabled library action before every MainActivity test starts. A 38-case Huawei batch covering those routes, backups, settings, and onboarding passed (`device-qa-20260923-005945-852.log`). The PDF probe now uses two direct Helvetica text objects instead of Skia-dependent content grouping; it still requires text replacement, source/neighbor preservation, and visible rendering.

Run `35793760072` reproduced early library taps in other fixtures and exposed a handwriting-test setup race: the page list grew before `addPage()` finished selecting its new page. That fixture now waits for the exact new page before returning to page one and verifies the raw stroke in the repository. All 34 handwriting checks passed on Huawei (`device-qa-20260923-010604-350.log`). A separate rapid-selection/ink check also passed without changing production navigation (`device-qa-20260923-010946-834.log`). The application source tree and frozen signed artifacts are unchanged by these test fixes.

## Document-editing boundary

Android's native PDF object-edit APIs are being tested against a new output copy, with source/neighbor preservation and reopened text/render checks. No source-text edit or secure-redaction UI is enabled by this increment. A white overlay is not secure redaction.

Primary references: [Android PDF page objects](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page), [PDF text objects](https://developer.android.com/reference/android/graphics/pdf/component/PdfPageTextObject), and [Compose graphics-layer camera distance](https://android.googlesource.com/platform/frameworks/support/+/43cba8b9616aa64413bce47b4c3d79b1b20e5614/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/graphics/GraphicsLayerScope.kt).
