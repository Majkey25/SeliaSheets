# Péřko

[![Android CI](https://github.com/Majkey25/Perko/actions/workflows/android.yml/badge.svg)](https://github.com/Majkey25/Perko/actions/workflows/android.yml)
[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/10)
[![Release](https://img.shields.io/github/v/release/Majkey25/Perko?include_prereleases)](https://github.com/Majkey25/Perko/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

<p align="center">
  <img src="branding/perko-android-icon-preview.png" width="220" alt="Péřko notebook and stylus icon">
</p>

Péřko is a private, offline-first Android notebook built for tablets and styluses while remaining fully usable on phones. It combines physical page organization, low-latency ink, text and images, deterministic shape cleanup, local arithmetic, and complete PDF export in one restrained paper workspace.

## Highlights

- Android 10 through Android 17 (`minSdk 29`, `targetSdk 37`).
- Low-latency AndroidX Ink with pressure, tilt, stylus eraser, palm cancellation, and motion prediction.
- Pen, pencil, highlighter, stroke eraser, lasso selection, bounded movement, and 100-step undo/redo.
- Multiple notebooks with original covers, search, favorites, trash, ordered pages, and page duplication with content.
- Blank, ruled, grid, and dot paper in portrait or landscape.
- Private image import through Android Photo Picker with MIME, dimension, allocation, and corruption checks.
- Persisted text, images, clean lines/arrows/ellipses/rectangles/triangles, and local calculator blocks.
- A4/landscape PDF export containing every page, paper pattern, ink, text, image, shape, and math result.
- Working settings for default tools, widths, finger drawing, paper, orientation, theme, and motion.
- No account, analytics, ads, telemetry, cloud sync, or app network permission.

## Screenshots

| Notebook editor | Lasso selection |
| --- | --- |
| ![Tablet editor](docs/qa/screenshots/tablet-editor.png) | ![Lasso selection](docs/qa/screenshots/tablet-lasso.png) |

| Text and image | Shape cleanup |
| --- | --- |
| ![Text and image](docs/qa/screenshots/tablet-text-image.png) | ![Shape cleanup](docs/qa/screenshots/tablet-shape-cleanup.png) |

| Local math | App details |
| --- | --- |
| ![Local math](docs/qa/screenshots/tablet-math.png) | ![App details](docs/qa/screenshots/tablet-app-details.png) |

## Privacy

Notebook content and imported media stay in private app storage. Péřko has no `INTERNET` permission. External privacy, source, and support links open only after a user action in the system browser. See the [privacy policy](PRIVACY.md) and [Google Play data-safety notes](docs/play-store/DATA_SAFETY.md).

## Build

Requirements: JDK 17 and Android SDK platforms 29 and 37.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease
```

The default release bundle is unsigned. Publication uses an external upload keystore that is never committed. See [release signing](docs/RELEASE.md).

## Verification

The beta was validated on dedicated Android 17 tablet and Android 10 phone emulators. Both targets passed the same 24-test instrumentation suite. The complete evidence and screenshots are in [the acceptance report](docs/qa/2026-08-24-emulator-acceptance.md).

## Scope

This beta deliberately omits accounts, cloud sync, collaboration, and handwriting-to-text recognition. Typed arithmetic and confirmed shape cleanup work locally without a downloaded model. Hardware-specific hover and stylus-button behavior requires later physical-device QA with explicit authorization.

## Support

Optional support does not unlock features or change priority: [Buy Me a Coffee](https://www.buymeacoffee.com/majkey).

For bugs and feature requests, use [GitHub Issues](https://github.com/Majkey25/Perko/issues). For privacy questions, email [majkeylab@gmail.com](mailto:majkeylab@gmail.com).

## License

Copyright © 2026 Majkey25. Licensed under the [Apache License 2.0](LICENSE). Third-party components retain their respective licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
