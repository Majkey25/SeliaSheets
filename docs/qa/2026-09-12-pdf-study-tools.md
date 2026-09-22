# PDF study tools verification

This work extends the [Flexcil workflow comparison](2026-09-12-flexcil-highlighting.md). It is not a full parity release.

Version 19 was an unsubmitted Play draft. Android 17 CI caught off-region native text snapping before publication. Version 20 (`0.7.0-beta.2`) supersedes that candidate; native text must intersect the requested selection area.

## Implemented paths

- The isolated PDF service returns selected text and normalized rectangles on Android 15+. Older devices and scanned pages use local word OCR when image OCR is enabled.
- Lasso selection offers copy, highlight, underline, strikeout, and linked excerpts. Annotations retain their text, color, and rectangles through editing, Undo, export, and backup.
- Text and cropped PNG excerpts retain source page/rectangle metadata. Captures use the same PDF, text, element, and ink rendering as export.
- Room migration 4→5 adds four nullable element fields. Backup v6 reads older formats and remaps source links without resolving missing sources to unrelated imported pages.

## Checks completed

- JVM suite: 117 tests passed. Command: `./gradlew :app:testDebugUnitTest --console=plain`.
- Debug app/test APKs build. Android lint completes with existing dependency-update and platform warnings.
- Huawei Android 10, `device-qa-20260912-182345-120.log`: 19 focused backend/data tests passed, with two expected API-35 skips.
- Huawei, `device-qa-20260912-183742-644.log`: PDF highlight visibility, Undo/Redo, reopening, nearby typing, capture rendering, and data checks passed. Two excerpt UI scenarios failed and remained under investigation.
- Huawei, `device-qa-20260912-182306-508.log`: exported highlight, underline, and strikeout pixel checks passed after correcting the blend mode.
- Huawei, `device-qa-20260912-185625-626.log`: all five PDF study UI flows and two fit/zoom/pan finger-selection flows passed. The new checkerboard crop-detail test failed before its fix.
- Huawei, `device-qa-20260912-190013-471.log`: 66 input, capture, and export tests passed, with three expected hardware/emulator skips. The checkerboard test passed after removing crop-canvas dimensions from the image decode target.
- Android 17 CI passed the backend increment in [run 34705329886](https://github.com/Majkey25/SeliaSheets/actions/runs/34705329886). Its Android 10 job exposed the deletion-test synchronization race described below.
- A clean version-19 build passed all JVM/lint tasks and produced signed APK/AAB outputs. APK verification confirms one expected signer, package `com.majkeylab.seliadocs`, version `0.7.0-beta.1`, minSdk 29, targetSdk 37, and 16 KB alignment. AAB verification reports the existing self-signed-certificate, timestamp, POSIX-attribute, and JAR stream-order warnings.
- Huawei version 19, `device-qa-20260912-191714-168.log`: all 28 final PDF study, capture, export, legacy-ID, migration, and backup tests passed.
- Signed version 19 passed `ReleaseInkSmokeTest` with explicit stylus pressure events, first at fit and then with `pinchBeforeStroke=true`. The test checks foreground package, visible blue pixels, and stability after handoff. Existing signed-app notes survived the upgrade. The temporary smoke-test page was removed afterward.
- Clean version 20 passed 119 JVM tests, lint, signed APK/AAB verification, and the expected package/certificate/alignment checks. Huawei `device-qa-20260912-202625-929.log` passed 12 PDF UI/backend tests with three expected API-35 skips. Signed version 20 passed cold and post-pinch `ReleaseInkSmokeTest`; existing notes remained intact.
- Final version-20 [CI run 34710873293](https://github.com/Majkey25/SeliaSheets/actions/runs/34710873293) passed build, Android 10 instrumentation, and Android 17 instrumentation. [PR 29](https://github.com/Majkey25/SeliaSheets/pull/29) merged as `fe5e950eccd487b04075ba1ca386efdcab677a08` with an identical source tree to the tested release commit.
- [GitHub prerelease 0.7.0-beta.2](https://github.com/Majkey25/SeliaSheets/releases/tag/v0.7.0-beta.2) contains APK SHA-256 `e9ffcfe8d35ec3050fe6ba0eb4251a4584850a4e9f499d9d29c47debc2c62386` and AAB SHA-256 `fda3dab3ded9f625acc570f39a482fccf72161693f16b67bc10522f6ea79dccd`. GitHub asset digests match the local frozen artifacts.
- Google Play closed Alpha version 20 was submitted on September 12. At 21:10 CEST, quick checks had finished and Play reported the changes under review. This does not establish tester availability. The superseded version-19 bundle remains only in the artifact library.
- On September 19, Play Console confirmed version 20 available to selected Alpha testers, with a September 12 21:25 release time. Version 21 had not been uploaded.

## Bugs and test defects found

- Legacy `PorterDuff.Mode.MULTIPLY` also multiplies alpha. Using it for export made black source text translucent. `BlendMode.MULTIPLY` preserves destination opacity. The strict black-text pixel assertion failed before the change and passed afterward. See the [Android blend equations](https://developer.android.com/reference/android/graphics/BlendMode).
- Element transforms used a 24-point minimum. Small text marks now use their own minimum, preserving glyph-sized bounds during movement and duplication.
- Finger drawing disabled also left single taps without a selection action. Lasso-mode taps now use page coordinates adjusted for zoom and pan.
- The first markup visibility selector used two test tags on one modifier. Tests use the existing element tag instead.
- The initial yellow-pixel detector counted gray RGB values 201–209. Screenshot inspection identified all 52 false positives. The corrected detector requires color separation as well as brightness; visibility and black-text thresholds remain unchanged.
- The destination test matched both the dialog notebook and the background page-location button. Exact-title selectors are now scoped to the dialog.
- Source references accept the same bounded opaque identifiers as legacy pages, including Unicode and punctuation. They are resolved through Room, not filesystem paths or URLs.
- Captures use 2048-pixel page rasters and a 4MP inserted-image decode budget. Ordinary PDF export retains its 4096-pixel/16MP limits. The capture bitmap-buffer calculation is at most 64 MiB; this is not a measured heap peak.
- Android 10 CI exposed an existing deletion-test race: Room emitted the deleted stroke list before selection/history controls updated. The test now awaits the complete state and retains its kept-stroke and Undo assertions.
- The integrated Android 10 run completed 371 tests with one existing history-toolbar synchronization failure and four expected skips. That test now waits for asynchronous Undo completion and settled tool state. Its focused Huawei rerun passed in `device-qa-20260912-194155-230.log`.
- The integrated Android 17 run failed before app input because a SystemUI boot ANR dialog held focus. CI now builds APKs before starting the emulator to separate compilation from boot load. Native focus checks and stylus assertions remain unchanged; the final version-20 run passed.

## Remaining acceptance

Version 20 is published on GitHub and available to Google Play closed Alpha testers. Split/pop-up workspace and PDF body-search work remain separate, unshipped changes. Audio-linked notes, study masking, annotation-preserving PDF export, and other parity items remain open in the [implementation checklist](../superpowers/specs/2026-09-12-flexcil-parity.md).

Injected stylus events exercise Android input routing. They do not certify a physical active pen's pressure, tilt, palm rejection, or vendor buttons. The shared Huawei has no attached active pen.
