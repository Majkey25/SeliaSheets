# Changelog

All notable changes to SeliaSheets are documented here.

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

[0.2.0-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.2.0-beta.1
[0.1.0-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.1.0-beta.1
[0.2.1-beta.1]: https://github.com/Majkey25/SeliaSheets/releases/tag/v0.2.1-beta.1
