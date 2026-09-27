# Document import checks

Unreleased branch: `feat/presentation-import/26-09-2026`. Checks ran on September 26–27, 2026. No version bump, GitHub release, or Google Play upload is included in this increment.

## Changes

- Offline PPTX conversion to static, PDF-backed notebook pages. Add slides after the selected page without losing its chapter or following notes.
- Editable TXT and literal Markdown import, with strict UTF-8 or BOM-marked UTF-16 decoding.
- Image import validates file content even when the provider supplies a generic or missing MIME type.
- BMP and GIF still images use the existing drawing path. Import and backup validation share the accepted formats and reject truncated image data.
- PDF export checks job cancellation before opening the destination, during file copies, and between pages. Failed copies retain the existing destination-cleanup behavior.
- Imported PDF/slide content no longer has the app's page-number label drawn over it. Navigation still shows the page number.
- Offline renderer licenses in Settings.
- The full notebook cover, including its title, opens the notebook. Its actions button remains a separate touch/accessibility target.
- Page edits wait for content belonging to the selected page; loading a different page cannot seed a draft from the old page. Save and Back remain available during that wait.
- Opening a search result clears its query/results before publishing the destination highlight.

See [format limits](../IMPORT_FORMATS.md). Original PPTX objects are not editable. Converted slide backgrounds are rasterized; local OCR is needed for their text search. Backups retain converted backgrounds and editable annotations, not the original presentation.

## Verification

- Debug APK and instrumentation APK built. All 148 JVM tests passed, including four new export cancellation cases.
- `lintDebug` passed. Its report contains 0 errors, 80 warnings, and 5 hints; this is not a zero-warning result.
- Huawei YAL-L21, Android 10, WebView 153.0.8010.36: the final feature/nearby-flow batch passed 64 tests. Coverage includes PPTX text/shapes/images/tables/charts, sparse category labels, unsupported chart failures, ZIP/XML/media limits, private-cache cleanup, cancellation, typed text, PDF source ownership, backup contents, settings, and page navigation.
- A separate Huawei input batch reported 63 cases: 60 passed and 3 expected hardware/API assumptions. It covers injected stylus/finger routing, live zoom/pan, pressure samples, and ink handoff. This phone does not certify physical active-pen pressure, tilt, hover, or buttons.
- The 100-slide fixture passed page-count and alternating-color pixel checks on every page. The final converter wrote 50 small PDF chunks in 30,052 ms, totaling 9,898,386 bytes. The importer inspects those chunks through one sandbox binding and installs them in one database transaction.
- Real Android picker roundtrip: `SeliaSheets-lecture-QA.pptx` imported into `PowerPoint-QA-26Sep`; the notebook contained its initial note page plus both slides. The system save picker exported a three-page PDF. An ADB swipe with an unspecified tool type did not create ink; it is not counted as a successful manual pen test.
- Independent review covered import/security boundaries and later identified the cross-page content race described below. Universal format support and a full professional graphics suite were not certified.

After the Chrome 74 compatibility rebuild, 30 focused document tests passed again on Huawei, including the 100-slide fixture. The input batch again reported 63 cases: 60 passed and the same 3 hardware/API assumptions. Five Node compatibility checks passed, and regenerating the renderer reproduced its pinned checksum. API 29 CI run `36308594343` subsequently passed its 527-case primary batch and 63-case input batch; it also passed the reproducible renderer build check.

The final image/export increment passed two disjoint Huawei batches: 37 export/image/backup tests and 24 presentation/text/PDF-import tests. The image tests check first-frame pixels, original bytes, backup validation, and truncated BMP/GIF rejection. The export tests cancel actual coroutine jobs after rendering and during destination opening, writing, and closing; they also check bitmap cleanup.

The final library fix passed all 10 library tests on Huawei, including a real touch-dispatch title regression, separate actions, rename/trash, compact columns, and large fonts. A direct ADB touchscreen tap on the title's current UI bounds also opened the existing PowerPoint notebook.

Latest tested debug APK SHA-256: `3bac7b866b2f92146640e20e2299c8e449267da71dc8d300fddd71ba0a2c62c3` (page-content guard). Its 22-case Huawei batch passed delayed-content editing/closing, search, page flow, and two-notebook workspace/history checks. The library-only build was `5b45610ec185e66afad74e089dc8b141beac720a870713cd8fd4146f00fd554b`; the image/export batches used `83addf431a88ca510360742e4cc78d97397b7ac85942b7a0cf9cd09e85051ec9`. The earlier compatibility/input batch used `65e9f771e639efe022b6c890e7734ca2f95a8cd98d26d9180ac80f5cb6775f62`; the earlier 64-test batch used `f62218b30fac630a1a6e6cd85b354e78d9c4117765e8f4084f27696c30c5f830`.

[Huawei screenshot after reopening the imported slides](screenshots/2026-09-27-powerpoint-phone.png). The app page-number overlay is absent from the slide; the toolbar still shows its position in the notebook.

Local logs: `.reference/tmp/device-qa-20260926-220410-259.log` (64 tests), `device-qa-20260927-101642-583.log` (input batch), and `device-qa-20260926-214358-144.log` (batched large deck). Earlier batches overlap these checks and are not added to their totals.

Compatibility rebuild logs: `device-qa-20260927-110725-468.log` (30 document tests) and `device-qa-20260927-110954-509.log` (input batch).

Final image/export logs: `device-qa-20260927-112341-904.log` (37 tests) and `device-qa-20260927-112454-125.log` (24 tests). Final local lint again has 0 errors, 80 warnings, and 5 hints.

Library regression logs: `device-qa-20260927-113531-410.log` reproduces the original title-tap failure; `device-qa-20260927-114157-780.log` passes all 10 cases after the fix. An intermediate parent-click implementation failed existing action semantics checks and was corrected before commit; the tests were not weakened.

The page-content guard batch is `device-qa-20260927-122606-059.log` (22 tests). `device-qa-20260927-122215-174.log` first reproduced Back waiting on a deliberately blocked page read. The corrected guard permits Close/WorkspaceSave without releasing that read and keeps editing actions deferred. The preceding search/highlighter batch passed 13 cases in `device-qa-20260927-115819-470.log`, including the unchanged native-window keyboard test.

The same final APK then completed 117 editor/library/viewport/stylus-routing cases: 114 passed and 3 expected hardware/API assumptions (`device-qa-20260927-123222-689.log`). Those cases and the 22-case batch are disjoint: 136 passed, 3 skipped, no failures. The final test-only tablet assertion change compiled, and `lintDebug` passed again with the same 80 warnings and 5 hints.

## Problems found and corrected

The first conversion prototype held all native PDF snapshots until completion. On the 100-slide fixture, sampled main-process PSS reached 540,724 KiB. The converter now releases snapshots in 8-megapixel batches. At twice the original capture resolution, the comparable sampled maximum was 232,643 KiB. These are debug-device snapshots, not a complete peak trace; they exclude the separate WebView renderer process.

The published renderer resolved its chart-ready promise before chart animations finished and omitted single-level `multiLvlStrCache` labels. Static conversion disables chart animation. The local vendor patch preserves cached category indexes and surfaces errors that previously produced placeholders. Visual PDF review found the missing labels after the initial tests had passed; the new category regressions cover that failure.

Native preflight initially rejected harmless, unused video MIME declarations found in ordinary Office packages. It now checks actual parts while preserving rejection of real audio/video content. ZIP parser-differential checks cover local/central records, payloads, and Unicode path aliases.

Direct phone use exposed a notebook-title dead zone that semantic click tests missed. The clickable layer sat behind the title Surface, so only uncovered cover areas received the tap. The click target now belongs to the parent container. The actions button uses its native Material click semantics instead of clearing them and forwarding a manual action.

The selected page ID and its Room content originally reached the UI through separate flows without a payload page ID. A new page could briefly display the previous page's text and accept edits into that wrong draft. Payloads now carry their queried page ID. Mismatched content is hidden, the text editor is not mounted, and input/editing actions wait for matching content. Tests delay a real Room flow, check text/ink/object isolation, and verify the original and edited pages separately. No database schema change is involved.

CI run `36309452902` exposed a search-state ordering race: opening a result could emit its highlight with the old result list. Clearing search before publishing the highlight removes that intermediate combination; the existing assertion remains and now also checks an empty query and stopped search.

Two tablet test assumptions were corrected without changing their functional assertions or increasing timeouts. The highlighter/settings test forces a phone-sized Compose viewport inside a native tablet window; it now dismisses the native IME and waits for stable page/tool geometry before its physical tool click. The native-window keyboard test still requires a visible IME. The library title-touch test expected phone-only page-counter text on the expanded tablet layout; it now checks the common toolbar, exact notebook title, and visible paper. The physical title/action taps remain unchanged.

One lint invocation stalled while files were changing; only its task-owned daemon was stopped. A standalone rerun completed. Later verification resumed after an overnight pause, so its wall-clock duration is not a build-performance measurement.

CI run `36306118850` passed the build job. Its Android 17 stylus preflight failed before any injection marker: the emulator's Nexus Launcher ANR dialog owned `mCurrentFocus`, so both test activities timed out waiting for window focus. The coordinator now recovers that specific boot dialog once, before the first marker. It cannot target a physical serial and does not dismiss SeliaSheets or other app failures. Seven coordinator tests and Ruff checks pass locally. Rerun `36307221589` passed Android 17: two external stylus-injection cases, a 529-case primary batch, and the 63-case input batch. These batches include API/hardware assumptions; passing the job does not certify a physical pen. Stylus assertions and timeouts are unchanged. This CI result predates the Chrome 74 bundle and final image/export changes.

The first run's Android 10 primary batch reported 527 cases, 11 failures, and 7 skips. All failures were PowerPoint startup on its WebView 74 provider: the original browser bundle used unsupported JavaScript syntax and `Promise.allSettled`. The checked-in renderer now has a reproducible Chrome 74 syntax transform and a conditional shim. CI rebuilds the asset and checks its checksum without removing any Android rendering tests. The API 29 rerun at `81203da` passed; the later image/export and library changes still require their own newest-head CI result.

## Security and remaining limits

The pinned browser renderer has no native JavaScript bridge and cannot load external resources or navigate to document links. Input, XML, media, render batches, output bytes, slide count, and conversion time are bounded. Embedded font decompression is disabled.

Package audit reports one moderate ECharts advisory, not a clean audit. The vulnerable `LinesSeries` module is absent from the shipped subset. See the [artifact-specific security assessment](../third-party/presentation-security.md), exact hash, source evidence, and invalidation conditions.

No release is claimed here. Remaining work includes broader format compatibility, cross-provider WebView checks, richer graphics tools, and large-notebook export memory review. Ordinary PDF export still uses the existing flattened exporter; this increment bounds PowerPoint conversion, not every export workload.
