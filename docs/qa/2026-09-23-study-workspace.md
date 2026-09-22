# Study workspace checks

Candidate: `0.9.0-beta.1`, version code 23. Not published.

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
- The first lint run found a redundant `RequiresApi` on an SDK-filtered test. Removed it; `lintDebug` passed on the combined implementation. The last two export-recovery fixes have another lint run pending.
- Manual phone inspection verified the insertion dialog and DOCX action. The detailed import warning was shortened after inspection; the completion dialog retains the full source-fidelity warning.

Physical run logs are local under `.reference/tmp`: `device-qa-20260923-000104-742.log` (expected red), `device-qa-20260923-000130-950.log` (41 passed), and `device-qa-20260923-000918-591.log` (40 passed). An earlier invocation returned zero tests while APK installation was still running; it is not verification evidence. The local runner now rejects zero-test results.

## Performance and remaining verification

Opening motion changes only a cover graphics layer, not the native ink surface. The frame value is read in that layer to avoid per-frame composition/layout. A single warm debug opening on Huawei recorded 17 frames, 3 janky frames, and a 5 ms median; startup and debug overhead remain in that sample. This is not a release-performance benchmark or evidence that all jank is fixed.

Review also found a stale-destination retry after output cleanup and duplicate workspace-save dispatch after recreation. The saved-request deduplication passed JVM checkpoint tests. A failed export releases the workspace and leaves its error in the exporter UI; only a draft-save failure retains the destination for retry. The extended physical recovery check is pending. Android 17 CI must execute the native PDF edit capability test. Huawei has no compatible active pen attached, so synthetic input is not pressure/tilt/hover hardware certification.

## Document-editing boundary

Android's native PDF object-edit APIs are being tested against a new output copy, with source/neighbor preservation and reopened text/render checks. No source-text edit or secure-redaction UI is enabled by this increment. A white overlay is not secure redaction.

Primary references: [Android PDF page objects](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page), [PDF text objects](https://developer.android.com/reference/android/graphics/pdf/component/PdfPageTextObject), and [Compose graphics-layer camera distance](https://android.googlesource.com/platform/frameworks/support/+/43cba8b9616aa64413bce47b4c3d79b1b20e5614/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/graphics/GraphicsLayerScope.kt).
