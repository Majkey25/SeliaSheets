# SeliaSheets ink, media, and brush controls QA

- Version: `0.4.2-beta.1` (`versionCode 8`)

## Target

- Device: Huawei YAL-L21
- Serial: `BQLDU19927002646`
- Android: 10 / API 29
- QA packages: `com.majkeylab.seliadocs.debug` and `com.majkeylab.seliadocs.debug.test`
- Production package was not replaced or cleared.
- Device input report: `ExternalStylusConnected: false`; real pressure hardware is unavailable on this phone.

## Root-cause reproduction

`rootStylusWithKnownZoomAndPanCommitsAtVisiblePaperPoint` passed when the page was composed initially at 2x. The new `rootStylusAfterPinchCommitsAtVisiblePaperPoint` failed before the fix with a ten-second timeout and no finished stroke.

Direct dispatch to `InkCanvasView` after the same pinch passed. Root dispatch failed. This isolated the defect to the dynamically transformed platform-view hit region, not stroke persistence or AndroidX Ink.

Replacing `graphicsLayer` with `requiredWidth`, `requiredHeight`, and placement offset fixed root dispatch. The resulting seven-test `PageViewportFlowTest` class passed, including maximum-zoom Stylus, Lasso, Eraser, pan, and selected-element drag.

## Focused verification

- `PageViewportFlowTest`
- `StylusRoutingTest`
- `ElementFlowTest`
- `ElementSelectionFlowTest`
- `EditorCompactUiTest`
- `InkCodecTest`
- `SettingsRepositoryTest`

Result: `OK (60 tests)` on the Huawei.

Covered behavior:

- dynamic pinch followed by off-center stylus input;
- Lasso and Eraser coordinates after maximum zoom;
- pressure samples `0.15 -> 0.9` retained by the official pressure-pen brush;
- imported image auto-selection;
- image move, resize, rotate, Undo, and Redo persistence;
- pen range `1..32` and highlighter range `4..64`;
- continuous editor slider, color controls, Smart shapes, and compact phone UI.

## Build gate

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease --console=plain
```

Final source gate after the version bump: `BUILD SUCCESSFUL in 2m 11s`, 149 actionable tasks, 57 executed, 92 up-to-date.

The first release build lacked the external signing property and produced an unsigned artifact. It was rejected before publication. The release was rebuilt with the existing external SeliaSheets upload-key properties. Result: `BUILD SUCCESSFUL in 1m 14s`, 62 executed tasks.

Signed artifact checks:

- APK SHA-256: `E3F73128E89693FD93815C2C1848DD4C7D41B9D5EAFC77C6E413E8CE003732C4`
- AAB SHA-256: `10AB0391E07C29141766846CE1EB8E5F79861F6E5FC3E88B3A1629A003F7CDFE`
- Package: `com.majkeylab.seliadocs`
- Version: `0.4.2-beta.1` (`versionCode 8`)
- SDK: min 29, target 37, compile 37
- APK: one RSA 4096-bit v2 signer, certificate SHA-256 `2741c19cc8754690dd3b91ffe51dad2e5565afaccd58c6b0667d49bfd716349c`
- AAB: `jarsigner -verify` returned exit code 0 and `jar verified`

## Full instrumentation audit

The first complete run executed all 248 instrumentation tests. It reported two failures:

1. `AppIdentityTest` expected the production package while intentionally running the isolated `.debug` build. The assertion was corrected to compare the installed package with `BuildConfig.APPLICATION_ID` while still requiring the base ID `com.majkeylab.seliadocs`.
2. `SettingsFlowTest.appDetailsShowsVersionAndSupport` lost its Compose hierarchy when `com.majkeylab.weatheraladin.debug` from another test worker took foreground during the method. The crash buffer remained empty.

Both tests passed on isolated rerun. Two later complete runs reached 247 of 248 and each lost a different unrelated UI or callback test under the long shared-device run. Each missed test passed immediately on isolated rerun. Across the complete runs and isolated reruns, every one of the 248 tests passed. No stable failure remained.

## Live Huawei verification

After clearing only `com.majkeylab.seliadocs.debug`, the app cold-started in 1420 ms. The live UI showed the complete bottom tool row and the width slider without clipping.

The live pen slider changed from 4 to 25 and then to the maximum 32. The Highlighter tool switched to its independent value of 22. The test UI exposed the full `4..64` highlighter range through semantics.

The Android photo picker imported the current screenshot. SeliaSheets returned to the editor in Lasso mode with the image selected. The move, resize, and rotate handles and the Duplicate, Bring forward, and Delete element actions were visible.

Live transform evidence:

- move bounds changed from `[339,885][741,1757]` to `[468,971][870,1843]`;
- resize bounds changed from `[468,971][870,1843]` to `[468,971][933,1903]`;
- the other test worker took foreground during the live rotate gesture, so rotation relies on the passing rotation instrumentation tests.

Reviewed screenshots:

- `.reference/seliasheets-brush-live.png`
- `.reference/seliasheets-image-selected.png`

These screenshots remain local QA artifacts and are not release assets.

## Cleanup

Removed the generated XML and screenshots from `/sdcard`. Uninstalled `com.majkeylab.seliadocs.debug.test` and `com.majkeylab.seliadocs.debug`. The final package query returned only `com.majkeylab.seliadocs.qa`, which belongs to the other test worker and was not modified.
