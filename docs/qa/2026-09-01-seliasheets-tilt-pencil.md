# SeliaSheets 0.5.3 stylus acceptance

## Scope

Version `0.5.3-beta.1` keeps the stable AndroidX pressure-pen renderer. Pencil reads the initial Android stylus tilt for each stroke: a more tilted pencil starts wider and lighter, while pressure remains dynamic and every pressure, tilt, and orientation sample remains stored.

Hardware eraser tips and both Android stylus-button states pass through selected image overlays. Normal stylus image transforms, delayed two-finger pinch, zoom coordinates, palm cancellation, and immediate eraser-to-pen transitions remain available.

## Evidence

- The tilt regression failed before implementation because upright and tilted Pencil strokes used identical brush size and opacity.
- The focused Huawei Android 10 Pencil test passed after implementation.
- The affected 37-test stylus, viewport, and codec run passed with three expected external-pen skips.
- A fresh full Huawei run executed 276 tests: 271 passed, four expected external-active-pen tests skipped, and one shared-device Compose lifecycle case was interrupted; that exact case passed immediately when rerun alone.
- JVM tests, Android lint, debug and instrumentation APK assembly, and the signed release APK/AAB build passed.
- Release metadata reports `com.majkeylab.seliadocs`, version code `12`, and version name `0.5.3-beta.1`; APK Signature Scheme v2 and AAB JAR verification passed.

## Android 17 runtime boundary

Compile SDK and target SDK 37 pass the build and lint gates. Cloud instrumentation could not produce app evidence: both Android 17 `google_apis` and 16 KB `google_apis_ps16k` images disconnected their virtual `/sdcard` before activity launch, then reported `Unable to resolve activity` from the test package. The same 31-test stylus/viewport suite passes on Huawei API 29. Android 17 runtime acceptance remains open until a stable API 37 target is available.

## Hardware boundary

Huawei `BQLDU19927002646` reports no connected active stylus. Synthetic `MotionEvent` coverage verifies routing and axis handling, but physical pressure, hover, tilt feel, and barrel-button reporting still require a compatible active-stylus tablet.
