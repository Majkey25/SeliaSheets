# Document import checks

Unreleased branch: `feat/presentation-import/26-09-2026`. Checks ran on September 26–27, 2026. No version bump, GitHub release, or Google Play upload is included in this increment.

## Changes

- Offline PPTX conversion to static, PDF-backed notebook pages. Add slides after the selected page without losing its chapter or following notes.
- Editable TXT and literal Markdown import, with strict UTF-8 or BOM-marked UTF-16 decoding.
- Image import validates file content even when the provider supplies a generic or missing MIME type.
- Imported PDF/slide content no longer has the app's page-number label drawn over it. Navigation still shows the page number.
- Offline renderer licenses in Settings.

See [format limits](../IMPORT_FORMATS.md). Original PPTX objects are not editable. Converted slide backgrounds are rasterized; local OCR is needed for their text search. Backups retain converted backgrounds and editable annotations, not the original presentation.

## Verification

- Debug APK and instrumentation APK built. All 144 JVM tests passed.
- `lintDebug` passed. Its report contains 0 errors, 80 warnings, and 5 hints; this is not a zero-warning result.
- Huawei YAL-L21, Android 10, WebView 153.0.8010.36: the final feature/nearby-flow batch passed 64 tests. Coverage includes PPTX text/shapes/images/tables/charts, sparse category labels, unsupported chart failures, ZIP/XML/media limits, private-cache cleanup, cancellation, typed text, PDF source ownership, backup contents, settings, and page navigation.
- A separate Huawei input batch reported 63 cases: 60 passed and 3 expected hardware/API assumptions. It covers injected stylus/finger routing, live zoom/pan, pressure samples, and ink handoff. This phone does not certify physical active-pen pressure, tilt, hover, or buttons.
- The 100-slide fixture passed page-count and alternating-color pixel checks on every page. The final converter wrote 50 small PDF chunks in 30,052 ms, totaling 9,898,386 bytes. The importer inspects those chunks through one sandbox binding and installs them in one database transaction.
- Real Android picker roundtrip: `SeliaSheets-lecture-QA.pptx` imported into `PowerPoint-QA-26Sep`; the notebook contained its initial note page plus both slides. The system save picker exported a three-page PDF. An ADB swipe with an unspecified tool type did not create ink; it is not counted as a successful manual pen test.
- Independent integration review found no additional blocking defect. Universal format support and a full professional graphics suite were not certified.

Final tested debug APK SHA-256: `f62218b30fac630a1a6e6cd85b354e78d9c4117765e8f4084f27696c30c5f830`.

[Huawei screenshot after reopening the imported slides](screenshots/2026-09-27-powerpoint-phone.png). The app page-number overlay is absent from the slide; the toolbar still shows its position in the notebook.

Local logs: `.reference/tmp/device-qa-20260926-220410-259.log` (64 tests), `device-qa-20260927-101642-583.log` (input batch), and `device-qa-20260926-214358-144.log` (batched large deck). Earlier batches overlap these checks and are not added to their totals.

## Problems found and corrected

The first conversion prototype held all native PDF snapshots until completion. On the 100-slide fixture, sampled main-process PSS reached 540,724 KiB. The converter now releases snapshots in 8-megapixel batches. At twice the original capture resolution, the comparable sampled maximum was 232,643 KiB. These are debug-device snapshots, not a complete peak trace; they exclude the separate WebView renderer process.

The published renderer resolved its chart-ready promise before chart animations finished and omitted single-level `multiLvlStrCache` labels. Static conversion disables chart animation. The local vendor patch preserves cached category indexes and surfaces errors that previously produced placeholders. Visual PDF review found the missing labels after the initial tests had passed; the new category regressions cover that failure.

Native preflight initially rejected harmless, unused video MIME declarations found in ordinary Office packages. It now checks actual parts while preserving rejection of real audio/video content. ZIP parser-differential checks cover local/central records, payloads, and Unicode path aliases.

One lint invocation stalled while files were changing; only its task-owned daemon was stopped. A standalone rerun completed. Later verification resumed after an overnight pause, so its wall-clock duration is not a build-performance measurement.

CI run `36306118850` passed the build job. Its Android 17 stylus preflight failed before any injection marker: the emulator's Nexus Launcher ANR dialog owned `mCurrentFocus`, so both test activities timed out waiting for window focus. The coordinator now recovers that specific boot dialog once, before the first marker. It cannot target a physical serial and does not dismiss SeliaSheets or other app failures. Seven coordinator tests and Ruff checks pass locally. Live CI confirmation remains pending; stylus assertions and timeouts are unchanged.

## Security and remaining limits

The pinned browser renderer has no native JavaScript bridge and cannot load external resources or navigate to document links. Input, XML, media, render batches, output bytes, slide count, and conversion time are bounded. Embedded font decompression is disabled.

Package audit reports one moderate ECharts advisory, not a clean audit. The vulnerable `LinesSeries` module is absent from the shipped subset. See the [artifact-specific security assessment](../third-party/presentation-security.md), exact hash, source evidence, and invalidation conditions.

No release is claimed here. Remaining work includes broader format compatibility, cross-provider WebView checks, richer graphics tools, and large-notebook export memory review. Ordinary PDF export still uses the existing flattened exporter; this increment bounds PowerPoint conversion, not every export workload.
