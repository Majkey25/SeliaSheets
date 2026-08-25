# SeliaDocs research refresh

Date: 2026-08-25

Status: approved direction

This document updates the research and delivery order in
`2026-08-24-seliadocs-smart-study-notebook-design.md`. The earlier document remains the main
product specification. This addendum resolves the next implementation priorities.

## The product stays notebook-first

SeliaDocs is a local-first school notebook for Android phones and tablets. A student opens a
subject and starts typing or writing without choosing a content container first. Pages, chapters,
search, and study tools support that action.

The app keeps these constraints:

- Android 10 through Android 17;
- English user interface;
- no required account;
- no required cloud service;
- editable local storage and portable `.seliadocs` backups;
- useful behavior without a generic AI chat screen;
- emulator-only acceptance unless the user authorizes a physical device.

SeliaDocs adopts proven interaction patterns. It does not copy another app's pixels, icons, or
brand assets.

## What the refreshed research confirms

Current note apps agree on a small set of important behaviors.

### Text is a normal page capability

Apple Notes supports headings, checklists, tables, collapsible sections, images, scans,
handwriting, and sketches in one note. MyScript Notes lets users mix keyboard text, dictation, and
pen input. A modal text-box workflow is too slow for class notes.

SeliaDocs must make **Type** a first-class page mode. New typed content uses a continuous text
document inside the page margins. A dialog does not create the text. Existing freeform text
elements remain editable for diagrams and imported backups.

### Ink tools need one interaction model

Goodnotes separates precision, segment, and whole-stroke erasing. Notability lets users group,
move, scale, and rotate selected ink. Samsung Notes and MyScript Notes regularize a shape when the
user holds the pen at the end of a stroke.

SeliaDocs uses these rules:

- a stylus draws when a drawing tool is active;
- one finger pans or scrolls unless **Draw with a finger** is enabled;
- two fingers always pan and zoom;
- the stylus eraser button activates stroke erasing;
- canceled palm input rolls back the active stroke;
- a held shape stroke becomes an editable shape;
- Undo restores the original stroke after shape conversion.

### Smart features must allow correction

Apple Notes solves typed or written expressions after an equal sign. OneNote and Notability let
the user correct a symbol when math recognition is wrong. MyScript Notes converts selected
handwriting and shapes instead of silently replacing the page.

SeliaDocs must never overwrite recognized content without a reversible action. Handwriting and
math conversion show the recognized result first. Ambiguous symbols show candidates. The original
ink stays available through Undo.

### Search spans content types and scopes

Apple Notes searches typed text, handwriting, images, and scans. OneNote searches typed text,
handwriting, images, tags, and recorded speech. Goodnotes searches handwriting, typed text, PDF
text, scan text, titles, folders, and outlines.

SeliaDocs search uses four scopes:

1. current page;
2. current chapter;
3. current notebook;
4. entire library.

Typed content, titles, chapter names, tables, checklists, and math enter the index first. Image OCR
and handwriting recognition join the same result model after their local models are available.

### Study tools belong beside the notebook

Goodnotes combines notebooks with masking tape and spaced-repetition study sets. Samsung Notes
and Goodnotes can replay notes with synchronized audio. These features help students, but audio
changes storage, permissions, backup size, and the page event model.

SeliaDocs implements masking tape and flashcards before audio. Audio-linked notes require a
separate approved design.

## Chosen delivery approach

SeliaDocs uses depth-first vertical packages. Each package must work end to end before the next
package starts.

Two alternatives are rejected:

- A feature-parity dump would add many controls without reliable editing, recovery, or tests.
- Recognition-first development would produce impressive demos on top of a weak editor.

## Package A: finish the editor foundation

This package completes the interactions that every later feature depends on.

### Full-page typing

- **Type** focuses a structured text layer inside the page margins.
- A new paper page starts at the top margin with a visible cursor.
- Enter creates paragraphs. Text flows to a new page when the fixed page is full.
- Flow pages keep one continuous text region.
- Formatting includes title, heading, subheading, body, bold, italic, highlight, checklist, and
  table.
- Headings can collapse their following content.
- Android 14 and newer use system stylus handwriting in text fields where it does not conflict
  with the ink canvas.
- Existing `ElementKind.TEXT` items remain freeform objects. New normal typing does not create one.

### Unified selection

- A tap selects an image, shape, math item, table, tape strip, or freeform text object.
- Lasso can select ink, objects, or both.
- The selection box has move, resize, and rotate handles of at least 48 dp.
- The contextual toolbar offers duplicate, layer order, group, ungroup, color, and delete.
- Movement and resizing stay within page bounds.
- Invalid or non-finite transforms do not reach Room.
- Every committed transform creates one undo step.

### Navigation and erasing

- Pinch zooms around the gesture focus point.
- Drag pans while zoomed.
- Double tap fits the page width.
- Precision eraser removes only the touched segment.
- Stroke eraser removes the whole stroke.
- Object eraser removes selected content types through explicit filters.
- Page switching keeps bounded undo history for the ten most recently edited pages.

### Smart shapes and thumbnails

- Holding for 450 ms after a line, arrow, ellipse, rectangle, or triangle regularizes the stroke.
- A small preview appears before the conversion commits.
- Page thumbnails render paper, ink, text, images, shapes, and math.
- Thumbnail generation runs off the main thread and uses a bounded invalidation cache.

## Package B: add notebook structure and typed search

- Add one chapter level, page titles, bookmarks, and a Contents view.
- Keep unfiled pages available during migration.
- Add the Inbox notebook and Quick Note entry.
- Add Room FTS for titles, chapters, page text, tables, checklists, and math.
- Open a search result at the exact page and highlight the matched block.
- Keep the tablet page rail visible. Use drawers for the same controls on phones.

## Package C: add local recognition and math

### Recognition

- Use ML Kit Digital Ink Recognition for downloaded handwriting and shape models.
- Offer English and Czech handwriting models first.
- Show model size, download state, and delete controls in Settings.
- Recognition runs offline after the model download.
- Use ML Kit Text Recognition for imported images and scans.
- Store recognized text separately from the original media and ink.

### Math

- Support typed and horizontal handwritten expressions ending in `=`.
- Offer **Insert results**, **Suggest results**, and **Off** modes.
- Support deterministic arithmetic, parentheses, powers, roots, common functions, and page-scoped
  variables.
- Show candidate corrections for ambiguous symbols.
- Support `y = f(x)` graphs as editable page elements.
- Preserve the original expression and the rendered result in editable backup records.

## Package D: add materials and study tools

- Import PDFs through the Storage Access Framework without changing the source file.
- Annotate PDF-backed pages with the same ink and object tools.
- Add links from notebook pages to a PDF location and preserve Back navigation.
- Add masking tape that hides a rectangular study region without deleting the content.
- Add flashcards with local active-recall scheduling and review history.
- Export chapters or the full notebook as vector PDF.
- Design audio-linked notes separately before adding microphone permission or audio archive data.

## Interface rules

The current paper-led visual language remains.

- Periwinkle, salmon, sage, sand, graphite, paper white, and semantic Material colors form the
  palette.
- The page is the visual focus. Toolbars use flat surfaces and restrained borders.
- Common tools stay visible. Rare actions move to one contextual sheet.
- The app does not add gradients, glass effects, decorative dashboards, or repeated card grids.
- Touch targets are at least 48 dp.
- Every icon-only action has a content description.
- Keyboard focus order follows the visual order.
- Reduced-motion settings disable page-turn and selection animations.

Tablet layout uses a compact notebook header, a page or Contents rail, the page canvas, and a
movable tool palette. Phone layout shows one page and moves rails into drawers. Both layouts expose
the same editing actions.

## Data and recovery rules

- Room remains the source of truth.
- The editor observes content only for the selected page.
- Search, thumbnails, OCR text, and recognition results are derived data and can be rebuilt.
- The `.seliadocs` format gains versioned records before new content ships.
- Import validates checksums, paths, sizes, references, and versions before a Room transaction.
- Autosave never blocks stylus input on the main thread.
- A failed recognition, transform, export, or import leaves the last valid page state intact.

## Acceptance gates

Each package must pass these checks before commit and push:

- unit tests, Android lint, debug build, and relevant instrumentation tests;
- API 29 and API 37 emulator flows;
- stylus, finger, mouse, and keyboard paths where the feature supports them;
- TalkBack labels and keyboard focus for every new control;
- rotation during dialogs and long operations;
- Back behavior at every new navigation level;
- backup round trip for every new stored field;
- a 500-page notebook that loads only the selected page content;
- no skipped destructive confirmation;
- no physical-device access without explicit permission.

Package A also needs live cases for a happy path, a page-boundary transform, invalid geometry, undo,
redo, a palm-canceled stroke, and a recent-page history return.

## Research sources

- Apple, Create and format notes on iPad:
  https://support.apple.com/en-euro/guide/ipad/ipad99e3f0bb/ipados
- Apple, Enter formulas and equations in Notes on iPad:
  https://support.apple.com/guide/ipad/enter-formulas-and-equations-ipad10f28bec/ipados
- Apple, Search through your notes on iPad:
  https://support.apple.com/en-ca/guide/ipad/ipad64863a98/ipados
- Samsung, Organize notes and PDFs in Samsung Notes:
  https://www.samsung.com/us/support/answer/ANS10004548/
- Samsung, Samsung Notes app support:
  https://www.samsung.com/us/support/owners/app/samsung-notes
- Microsoft, Search notes in OneNote:
  https://support.microsoft.com/en-us/onenote/onenote-help-and-learning/search-notes-in-onenote
- Microsoft, Create math equations with Math Assistant:
  https://support.microsoft.com/en-us/education/onenote/create-math-equations-using-ink-or-text-with-math-assistant-in-onenote
- Goodnotes, Search your notes:
  https://support.goodnotes.com/hc/en-us/articles/7353743594127-How-to-Search-Your-Notes
- Goodnotes, Study Sets and Smart Learn:
  https://support.goodnotes.com/hc/en-us/articles/7353756529551-Getting-Started-with-Study-Sets-and-Smart-Learn
- Goodnotes, Audio recordings and Note Replay:
  https://support.goodnotes.com/hc/en-us/articles/7352688559631-Add-Audio-Recordings-to-your-documents
- Notability, Handwriting and Math Conversion:
  https://support.gingerlabs.com/hc/en-us/articles/360003878731-Handwriting-and-Math-Conversion
- MyScript Notes, Android feature history:
  https://help.myscript.com/notes/versions/android/
- Android Developers, Ink API:
  https://developer.android.com/develop/ui/compose/touch-input/stylus-input/about-ink-api
- Android Developers, Stylus palm rejection:
  https://developer.android.com/develop/adaptive-apps/cookbook/stylus-palm-rejection
- Google ML Kit, Digital Ink Recognition:
  https://developers.google.com/ml-kit/vision/digital-ink-recognition
