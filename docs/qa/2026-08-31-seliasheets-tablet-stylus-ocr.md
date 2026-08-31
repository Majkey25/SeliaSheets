# SeliaSheets tablet, stylus, OCR, and handwriting QA

- Version: `0.5.0-beta.1` (`versionCode 9`)
- AVD: Pixel Tablet, Android 16, API 36
- Serial: `emulator-5594`
- Display: 2560 x 1600 at 320 dpi

## Active stylus

The Android Emulator gRPC `PenEvent` API injected the pen stream. Android reported `TOOL_TYPE_STYLUS`, stylus source `20482`, and pressure from `0.15625` to `0.87890625`.

`externalTabletStylusDrawsAfterLivePinch` sent a real two-finger pinch, then the active-pen stream. The test verified that zoom increased above 100 percent, the stroke reached the visible page center, and both pressure levels reached AndroidX Ink.

## Fixed defects

- The debug launcher label no longer changes the in-app product title.
- `InkCanvasView` attaches its finished-stroke listener after every reattach.
- The canvas drains AndroidX Ink work before renderer teardown.
- AndroidX Ink initializes on stylus hover or first contact instead of every unused page attachment.
- The recreation regression no longer crashes the renderer.
- Page changes cancel in-flight handwriting conversion so stale candidates cannot appear on another page.

## New behavior

- Imported image text is searchable through the bundled ML Kit Latin OCR model.
- Settings can disable image OCR. OCR failure does not block image import.
- Selected handwriting offers up to five recognition candidates. The chosen text is added to normal page text, while the original ink stays unchanged.
- Typed and recognized math supports postfix percentages, `sqrt`, `sin`, `cos`, `pi`, `e`, and variables assigned on earlier page-text lines.

## Test evidence

The final tablet suite discovers 260 tests. The main run passed 232 tests. Android's task-snapshot code then blocked `WindowManagerService`, and the API 36 watchdog restarted `system_server`.

A fresh 25-test run covered the interrupted tail and passed with `OK (25 tests)`. A final bounded editor slice passed `OK (61 tests)`, including handwriting conversion, math rollback, OCR image search, image transforms, stylus reattachment, pressure, zoom, pan, and pinch routing. A follow-up `OK (26 tests)` covered disabled OCR search, manual math variables, and every viewport/stylus regression. `externalTabletStylusDrawsAfterLivePinch` also passed separately with the emulator's real pen-event stream.

The system watchdog stack ended in `android.window.ScreenCapture.captureLayers` and `TaskSnapshotController`. It had no SeliaSheets frame. Bounded runs avoid this Android 16 emulator-image defect.

Active-stylus hover now reaches AndroidX Ink through Android's hover channel instead of the touch listener. Synthetic API 29 tests wait for the attached render surface and send hover before contact. Activity recreation creates a fresh canvas on Android 10; newer Android versions additionally verify safe same-instance reattachment.

GitHub runs the two AndroidX Ink instrumentation classes in a fresh API 29 process after the general suite. This preserves all assertions while avoiding SwiftShader renderer degradation after more than 200 unrelated tests.

## Build gate

This command passed with `BUILD SUCCESSFUL` and 149 tasks:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease --console=plain
```

The final clean gate used the existing external SeliaSheets upload key. `apksigner` verified one APK signer, and `jarsigner` reported `jar verified` for the AAB. Final release hashes are generated after the release commit and published in the GitHub release `SHA256SUMS` asset.
