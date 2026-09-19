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

Search currently caps combined results at 100. On Android 15+, a page with native text does not OCR its embedded scanned regions after an unsuccessful native query. Search reports unreadable pages instead of silently presenting an exhaustive result. Workspace activity recreation is covered; process-death workspace restoration and movable/resizable phone pop-ups are not implemented.

References: [Flexcil document search](https://support.flexcil.com/hc/en-us/articles/8155027675545-Search-text-in-the-document), [Android PDF search](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page#searchText(java.lang.String)), and [Compose dialog window behavior](https://developer.android.com/reference/kotlin/androidx/compose/ui/window/DialogProperties#decorFitsSystemWindows()).
