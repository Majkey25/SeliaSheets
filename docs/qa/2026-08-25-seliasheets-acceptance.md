# SeliaSheets 0.2.0 beta acceptance

Date: August 25, 2026

## Release build

The clean release command passed:

```powershell
.\gradlew.bat -PseliaSheetsKeystoreProperties=<local-properties-file> clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease --console=plain
```

Result: `BUILD SUCCESSFUL` in 1 minute 40 seconds. The properties file and upload key remain outside the repository.

Release signature checks passed:

- `app-release.apk`: APK Signature Scheme v2, one signer.
- `app-release.aab`: `jarsigner -verify` returned `jar verified`.

SHA-256:

```text
AB5A7504C2EB3BDBFDDE2F4E096BB2B8701CB5B26ABAD950203D4EBEB5067214  app-release.aab
61DE4E73546C0C9EEBDB1F2BD0A0A45C05407AC72CCD978D29224AD2E7D799EB  app-release.apk
```

## Emulator acceptance

Both emulators ran the complete instrumentation suite from the same clean build.

| Target | Serial | Result | Duration |
| --- | --- | --- | --- |
| Android 10, API 29 phone | `emulator-5590` | `OK (97 tests)` | 141.549 s |
| Android 17, API 37 tablet | `emulator-5594` | `OK (97 tests)` | 618.545 s |

The suite covers Room migrations, validated editable backups, isolated PDF inspection and rendering, PDF import and export, full-page typing limits, chapters and Contents, pan and zoom, stylus and finger routing, segment erasing, smart-shape Undo and Redo, settings, navigation, and app identity.

No physical Android device was accessed. No emulator outside `emulator-5590` and `emulator-5594` was modified.

## Store assets

- App icon: 512 × 512 PNG.
- Feature graphic: 1024 × 500 PNG with SeliaSheets branding.
- Phone screenshots: 1080 × 2280 PNG.
- Tablet screenshots: 2560 × 1600 PNG.
- Default app and Play listing language: English (United States).
