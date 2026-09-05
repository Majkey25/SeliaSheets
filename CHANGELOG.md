# Changelog

All notable changes to SeliaSheets are documented here.

## [0.6.0-beta.1] - 2026-09-05

### Added

- Added a Pencil brush whose size responds to pressure, whose width and opacity respond to tilt, and whose tip rotation follows orientation. Older stored strokes remain readable.
- Added a non-writing stylus hover preview.
- Added scale, rotate, duplicate, recolor, and delete actions for lasso-selected ink.

### Changed

- Updated AndroidX Ink from `1.0.0` to `1.1.0-alpha07`.
- Replaced text-heavy phone and tablet toolbars with fixed Material icon palettes and on-demand brush and eraser controls.
- Enabled R8 and resource shrinking, pinned CI actions to reviewed revisions, and made invalid signing paths fail during Gradle configuration.

### Fixed

- Retained unsaved inline text through Activity recreation and saved it before tool changes or editor exit.
- Let text fields keep their own Undo, Redo, Page Up, and Page Down keyboard events.
- Bounded backup manifests and checksum data, enforced element and asset relationships, and removed incomplete export destinations after failure.
- Excluded private app data from Android cloud backup and device transfer.

### Security

- Overrode ML Kit's vulnerable transitive OkHttp `3.12.1` dependency with OkHttp `4.12.0`.

## [0.5.3-beta.1] - 2026-09-01

### Added

- Made Pencil strokes wider and lighter when an active stylus starts at a steeper tilt while retaining pressure sensitivity and stored tilt/orientation samples.

### Fixed

- Routed inverted eraser tips and both barrel-button erase gestures through selected image overlays at zoom.
- Preserved normal stylus image transforms, delayed two-finger pinch zoom, and back-to-back eraser-to-pen transitions.

## [0.5.2-beta.1] - 2026-09-01

### Fixed

- Made primary and secondary active-stylus barrel buttons act as a temporary eraser without changing the selected pen tool.

## [0.5.1-beta.1] - 2026-08-31

### Added

- Added exact on-page highlighting for matching OCR text inside imported images.
- Persisted bounded OCR line regions across notebook backup and restore.

### Fixed

- Regenerated missing OCR regions lazily for notebooks migrated from older versions.
- Rejected malformed OCR-region metadata during backup validation.
- Bounded backup JSON parsing on low-memory Android 10 devices without requiring field order.

## [0.5.0-beta.1] - 2026-08-31

### Added

- Added bundled on-device OCR so text in imported images appears in notebook search.
- Added selected-handwriting conversion into normal full-page text while preserving the original ink.
- Added percentages, common functions, constants, and earlier page variables to typed and handwritten math.

### Fixed

- Preserved active-stylus pressure and page coordinates after pinch zoom, canvas reattachment, and Activity recreation.
- Prevented stale handwriting candidates from appearing after a page change.
- Gave debug installs a distinct launcher label without changing the in-app SeliaSheets title.

## [0.4.2-beta.1] - 2026-08-30

### Added

- Added continuous pen and highlighter width sliders with live previews and wider ranges.

### Fixed

- Kept stylus, lasso, and eraser input aligned and reachable after live page zoom.
- Selected imported images immediately so move, resize, rotate, Undo, and Redo are available without another selection step.

## [0.4.1-beta.1] - 2026-08-30

### Fixed

- Enlarged the installed adaptive and themed launcher artwork while preserving its centered layout, colors, and transparent foreground.

## [0.4.0-beta.1] - 2026-08-29

### Added

- Expanded notebook search to page titles, chapter titles, movable text, math expressions, and math results.
- Kept one search result per matching page when several content types contain the same query.

## [0.3.1-beta.1] - 2026-08-29

### Fixed

- Enlarged the installed launcher icon while keeping its visible artwork inside the adaptive safe zone.
- Added native themed-icon support for Android 13 and later launchers.

## [0.3.0-beta.1] - 2026-08-28

### Added

- Optional on-device handwriting recognition for simple single-line arithmetic after an explicit Google ML Kit language-model download.

### Changed

- Updated privacy and Play data-safety disclosures for Google ML Kit Digital Ink Recognition.

## [0.2.1-beta.1] - 2026-08-26

### Added

- One-finger page turns when finger drawing is off and two-finger page turns when it is on.
- Compact phone editor controls with accessible More and Insert menus.

### Changed

- Hardened backup replacement, large-notebook export, chapter ordering, text autosave, and bounded Undo memory.
- Improved stylus/finger ownership, page-gesture cancellation, system insets, and adaptive phone/tablet layouts.
- Made PDF export failure-safe and kept Undo/Redo history aligned with transactional writes.
- Preserved pending text across Back, Settings, and Activity recreation before clearing editor state.

## [0.2.0-beta.1] - 2026-08-25

### Added

- Full-page typing with autosave, Undo, Redo, search, backup, and PDF export.
- Chapters, page titles, bookmarks, live page previews, and a compact Contents sheet.
- Pan and zoom, segment erasing, draw-and-hold smart shapes, and precise polygon lasso selection.
- Isolated-process PDF import and annotation with editable backup preservation.
- Android 10 and Android 17 acceptance coverage with 97 instrumentation tests.

### Changed

- Renamed the public app and repository to SeliaSheets.
- Moved secondary editor actions into an accessible overflow menu.
- Renamed new editable backup files to `.seliasheets`; existing backup content remains compatible.

## [0.1.0-beta.1] - 2026-08-24

### Added

- Offline notebook library with covers, search, favorites, trash, and ordered pages.
- AndroidX Ink pen, pencil, highlighter, eraser, lasso, movement, and bounded undo/redo.
- Private text and image elements, deterministic shape cleanup, and local arithmetic.
- Complete PDF export through Android's system document picker.
- English settings, theme and drawing defaults, privacy links, and optional support control.
- Android 10 through Android 17 support with API 29 and API 37 emulator acceptance evidence.

[0.1.0-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.1.0-beta.1
[0.2.0-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.2.0-beta.1
[0.2.1-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.2.1-beta.1
[0.3.0-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.3.0-beta.1
[0.3.1-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.3.1-beta.1
[0.4.0-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.4.0-beta.1
[0.4.1-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.4.1-beta.1
[0.4.2-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.4.2-beta.1
[0.5.0-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.5.0-beta.1
[0.5.1-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.5.1-beta.1
[0.5.2-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.5.2-beta.1
[0.5.3-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.5.3-beta.1
[0.6.0-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.6.0-beta.1
