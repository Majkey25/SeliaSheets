# PowerPoint renderer security assessment

Reviewed: 2026-09-27. Review again by 2026-10-27, or whenever the renderer bundle,
chart registry, import options, or WebView restrictions change.

## Audited artifact

- Component: `@aiden0z/pptx-renderer` 1.3.0, locally patched browser bundle with
  JavaScript syntax transformed for Chrome 74.
- Dependency: `echarts` 6.0.0, a selected set of chart modules, not the full package.
- File: `app/src/main/assets/presentation/renderer.js`.
- Reviewed SHA-256: `e521a425d945696bfcdb2ada6502b59fe1dcf63103e0239a7c888a762d02c136`.
- Upstream SHA-256: `46b61afa1435de0c194f93324c9467ca392c891c6e07517727c8ffb5e51c376b`.
- Local patch: [pptx-renderer-1.3.0.patch](pptx-renderer-1.3.0.patch).
- Build source: [tools/presentation](../../tools/presentation/README.md), with
  esbuild 0.28.2 and a conditional `Promise.allSettled` shim.
- Licenses and source links: [bundled NOTICE](../../app/src/main/assets/presentation/NOTICE.txt).

The local patch changes renderer error reporting and chart category parsing.
The build verifies the upstream bundle checksum, applies that patch, transforms
JavaScript syntax with `target: chrome74`, and prepends the compatibility shim.
It preserves legal comments. It does not rebundle or upgrade the embedded
ECharts 6.0.0 implementation from installed dependencies. This assessment applies
to the reviewed artifact, not to every package named `echarts` or every build of
the renderer.

## CVE-2026-45249 / GHSA-fgmj-fm8m-jvvx

Package status: affected. ECharts 6.0.0 is within the advisory's affected range,
versions below 6.1.0. Product assessment: **not affected** at the artifact pin above.
VEX justification: `vulnerable_code_not_present`.

The reported vulnerability requires the ECharts **Lines** series, enabled
tooltips, a supplied `series.data[i].name`, and no custom tooltip formatter.
The affected default formatter returns the name as raw HTML. The generic tooltip
renderer then places that value into an HTML sink. The **Line** series is a
different module. [Apache's advisory](https://www.openwall.com/lists/oss-security/2026/05/23/4),
[GitHub advisory](https://github.com/advisories/GHSA-fgmj-fm8m-jvvx).

The [upstream fix 1e39b00](https://github.com/apache/echarts/commit/1e39b00)
changes `src/chart/lines/LinesSeries.ts` and its regression test. The fix removes
the raw-name return from `LinesSeriesModel.formatTooltip` and sends the name
through the structured tooltip markup builder.

Evidence from the exact browser bundle:

- Registered series are `bar`, `candlestick`, `custom`, `line`, `pie`, `radar`,
  and `scatter`. This matches the pinned
  [upstream chart registry](https://github.com/aiden0z/pptx-renderer/blob/v1.3.0/src/renderer/chart/echartsRuntime.ts).
- `series.lines`, `LinesSeries`, `LinesChart`, `fromName`, and `toName` have no
  matches. The affected formatter and the Lines registration are absent.
- PowerPoint `lineChart` and `line3DChart` route to series type `line`, not `lines`.
  The document parser generates chart options. It does not accept an arbitrary
  document-provided ECharts option object or load additional chart modules.
- Generic tooltip HTML code is present. Its presence alone does not make the
  absent Lines formatter reachable. This finding does not clear other tooltip
  paths or future advisories.

The release does not need an ECharts 6.1.0 rebuild solely for this CVE while these
conditions hold. Adding `LinesChart`, importing full ECharts, or changing the
bundle invalidates this assessment. Such a change requires a fresh review and,
if the affected module is included, ECharts 6.1.0 or a verified upstream fix.

## Dependency audit result

On 2026-09-27, this command ran against the local audit lockfile in
`.reference/tmp/presentation-audit`:

```text
npm audit --package-lock-only --omit=dev --ignore-scripts --json
```

Result: exit code 1, one moderate advisory, zero high, and zero critical.
The finding was `GHSA-fgmj-fm8m-jvvx` for ECharts 6.0.0, with 6.1.0 offered as a fix.
This is **not a clean dependency audit**. The product assessment above records
why the reported vulnerable module is absent from the shipped browser subset.

The audit pinned renderer 1.3.0, ECharts 6.0.0, JSZip 3.10.1,
mtx-decompressor 1.4.2, pako 1.0.11, tslib 2.3.0, and zrender 6.0.0.
That scratch lockfile describes packages for advisory lookup. It is not an
application build lockfile and does not reconstruct the vendored bundle.

A separate build-tool audit ran on 2026-09-27 in `tools/presentation`:

```text
npm audit --package-lock-only --ignore-scripts --json
```

That command returned exit code 0 and zero advisories, including development
dependencies. Its lockfile resolves ECharts 6.1.0 and zrender 6.1.0 as transitive
build-package dependencies. The build reads the checksum-pinned prebuilt browser
artifact instead of those installed chart modules. The clean build-tool audit
therefore does not clear or update the runtime ECharts 6.0.0 advisory above.

`npm test` in `tools/presentation` passed all five shim tests on 2026-09-27.
Those Node tests do not establish Android WebView rendering compatibility.

The static bundle checks used these commands from the repository root:

```text
Get-FileHash -Algorithm SHA256 -LiteralPath app/src/main/assets/presentation/renderer.js
rg -n 'series\.lines|LinesSeries|LinesChart|fromName|toName' app/src/main/assets/presentation/renderer.js
rg -n 'type = "series\.|type: "line"|case "lineChart"' app/src/main/assets/presentation/renderer.js
```

The second command returned no matches, exit code 1. The registry and chart
dispatch were also inspected directly. A negative text search alone is not the
basis for the assessment. No CVE exploit was executed during this review.

## Additional import boundaries

These controls are independent of the missing Lines module. They do not change
the package advisory result or replace validation of untrusted documents.

- `PowerPointRenderer.kt` serves only four fixed local-origin paths. Other
  requests receive HTTP 403. Network loads, file access, content access, DOM
  storage, mixed content, popups, and document navigation are disabled.
- No `addJavascriptInterface` bridge is installed. Native calls evaluate fixed
  scripts, and read bounded conversion status rather than executing document text.
- `presentation/index.html` permits scripts only from the local origin.
  CSP does not permit inline scripts or `unsafe-eval`. Frames and workers are
  blocked. Images and fonts use data or blob URLs, not external URLs.
- `PowerPointPreflight.kt` checks local and central ZIP records, CRCs, actual
  inflated byte counts, part names, relationships, content types, and raster
  image dimensions before JavaScript parses the file.
- Limits include 32 MiB input, 128 MiB inflated data, 16 MiB per part, 2,048 ZIP
  entries, 100 slides, 32 million image pixels, 500,000 XML events, and XML depth 64.
  DTDs, active Office content, and external media are rejected.
- `embeddedFontLimits: { maxFaces: 0 }` prevents embedded-font decompression.
  Upstream font output-size and elapsed-time checks occur after decompression,
  so those default checks alone are not a safe resource boundary.

This review verifies source-level controls and the specific advisory's module
reachability. It does not certify all JavaScript, image decoders, or WebView
versions. Functional tests, malformed-document tests, and physical-device
resource measurements remain separate release evidence.
