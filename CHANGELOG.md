# Changelog

All notable changes to SeliaSheets are documented here.

## [0.8.0-beta.1] - Unreleased

### Added

- Open a second notebook in a resizable tablet split or a phone dialog, with independent pages, drafts, and zoom.
- Search PDF body text and stored highlights, underlines, and strikeouts. Open matches at their page region. Scanned pages and older Android versions use opt-in local OCR.
- Insert note pages or PDF slides after the current page while preserving its chapter and paper dimensions.
- Choose custom RGB/hex brush colors and pen/pencil opacity without losing opacity when changing presets.
- Filter bookmarked pages, jump to a page number, or preview page position with a slider.

### Fixed

- Save both editor sessions before leaving the workspace. Failed writes retain drafts and offer Retry.
- Keep popup controls visible above the keyboard, including after activity recreation.
- Prevent stale Undo history from replacing content edited through another session.
- Retire successfully saved page drafts so later navigation cannot overwrite another pane's newer text.
- Preserve completed ink when replacing drawing surfaces during resizing, backgrounding, or reattachment.
- Route search-result navigation through the existing text and ink save barrier.
- Use state-based name fields, focus the chapter/page name editor, and support keyboard Done. Chapter names now follow the storage limit before saving.
- Keep tool icons readable independently of the selected brush opacity and color.

## [0.7.0-beta.2] - 2026-09-12

### Fixed

- Selecting a diagram no longer offers unrelated native PDF text outside the selected area. Off-region results fall back to local OCR or image capture.

## [0.7.0-beta.1] - Unreleased candidate

### Added

- Select PDF text with Lasso, then copy, highlight, underline, or strike out the selection. Android 15+ uses native PDF text. Older devices and scanned pages use local OCR when enabled.
- Copy text or capture a page region into a notebook with a link back to its source. Captures include PDF content, text, annotations, and ink.
- Edit, recolor, duplicate, delete, and undo text marks. Move and resize captured images with the existing selection handles.

### Fixed

- Finger taps select content in Lasso mode without enabling finger drawing. Swipes and pinches retain page navigation and zoom behavior.
- Small text marks retain their size when moved or duplicated.
- Exported highlights preserve black source text. Small image captures preserve detail instead of downsampling the full image to the crop size.

### Changed

- Editable backups use format 6 for PDF marks and source links. Formats 1–5 remain readable. New backups require an updated reader.
- Room migration 4→5 preserves existing content and adds nullable annotation metadata.

## [0.6.4-beta.1] - 2026-09-12

### Fixed

- Settings refreshes no longer replace highlights while their saves are pending. Failed saves remove unsaved ink, and completed groups reconcile with the latest page snapshot.
- Highlighter opacity is validated so an invisible saved brush setting cannot disagree with the controls.

### Added

- Highlighter opacity slider from 10% to 80%. Color changes preserve opacity. The unrelated Smart shapes switch is hidden for highlighters.

## [0.6.3-beta.1] - 2026-09-12

### Fixed

- Live ink stays under the pen on zoomed and panned pages. The low-latency drawing surface is limited to the visible viewport without changing stored stroke coordinates.
- Native screen-pixel regressions cover highlighter visibility before pen-up, after handoff, and after saving without switching tools.

## [0.6.2-beta.1] - 2026-09-08

### Fixed

- Pressing or releasing either stylus barrel button during a stroke switches between ink and erasing without changing the selected tool.
- Ink and eraser edits commit in their original order, including when a palm is touching the page.
- Finished strokes survive immediate page removal. Back, Undo, Redo, and keyboard page navigation wait for pending ink.
- Retained input excludes duplicate samples and predictions while preserving the zoom transform and supported pen axes.

## [0.6.1-beta.1] - 2026-09-06

### Changed

- New pen strokes respond across the full stylus-pressure range. Existing pen strokes retain their original appearance, and finger drawing keeps a fixed width.
- Editable backups use format 5 for the new pen. Formats 1–4 remain readable; new backups require an updated reader.

### Fixed

- A resting palm no longer pans a zoomed page after the pen or hardware eraser lifts.
- Partial erasing preserves pencil direction when stylus orientation crosses zero degrees.

## [0.6.0-beta.1] - 2026-09-05

### Added

- Added a Pencil brush whose size responds to pressure, whose width and opacity respond to tilt, and whose tip rotation follows orientation. Older stored strokes remain readable.
- Added a non-writing stylus hover preview.
- Added scale, rotate, duplicate, recolor, and delete actions for lasso-selected ink.

### Changed

- Updated AndroidX Ink from `1.0.0` to `1.1.0-alpha07`.
- Replaced text-heavy phone and tablet toolbars with fixed Material icon palettes and on-demand brush and eraser controls.
- Editable backups use format 4 for Pencil strokes. Older backups remain readable; new backups require SeliaSheets 0.6 or later.
- Enabled R8 and resource shrinking, pinned CI actions to reviewed revisions, and made invalid signing paths fail during Gradle configuration.

### Fixed

- Initialized the ink engine when the canvas attaches so the first finger or short pen stroke does not depend on a preceding hover event.
- Retained unsaved inline text through Activity recreation and saved it before tool changes or editor exit.
- Let text fields keep their own Undo, Redo, Page Up, and Page Down keyboard events.
- Saved pending text before importing or exporting documents; clearing an existing text object can be undone.
- Validated text against page bounds and kept page typography consistent across system font sizes and PDF export.
- Moved image OCR and PDF rendering outside the document save lock; added OCR retry and PDF rendering error feedback.
- Applied image EXIF orientation and preserved recognized image text through Undo/Redo.
- Prevented a closed editor's background cleanup from deleting image files still needed by another editor's Undo history. Orphan cleanup now waits for library startup or explicit notebook deletion.
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
