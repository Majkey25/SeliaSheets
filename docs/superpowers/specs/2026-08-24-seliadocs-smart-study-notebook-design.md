# SeliaDocs smart study notebook design

Status: Approved in chat on August 24, 2026

## Purpose

SeliaDocs is an offline-first Android notebook for students. It treats a tablet as a backpack of subject notebooks instead of a collection of unrelated note cards.

The app must support two jobs without exposing two separate products:

- capture a short text note, checklist, sketch, or shopping list;
- maintain a subject notebook with chapters, hundreds of pages, handwriting, imported material, search, and study tools.

The editor remains useful without an account, a network connection, or a recognition model.

## Product identity and platform limits

- Public name: `SeliaDocs`
- GitHub repository: `Majkey25/SeliaDocs`
- Android application ID: `com.majkeylab.seliadocs`
- UI language for the first release: English
- Minimum Android version: Android 10, API 29
- Compile and target SDK: Android 17, API 37
- Primary form factor: tablet with an active stylus
- Supported secondary form factor: phone with touch, keyboard, or stylus input
- Storage model: private local storage with user-directed import, export, backup, and restore
- Business model for the first release: free, with an optional Buy Me a Coffee link

The package rebrand must finish before the first public release. Set the Android namespace and every Kotlin package to `com.majkeylab.seliadocs`. Rename the main app, theme, repository, database, Room schema directory, and database file from `Perko` names to `SeliaDocs` names. No public installation uses the new application ID yet, so Package 0 performs this rename before the first Room migration. The repository must not ship with contradictory package instructions.

## Research findings

Current note apps converge on several useful patterns:

- Apple Notes keeps capture simple while indexing typed text, handwriting, scanned text, and attachments. It also supports inline math, tables, headings, checklists, and PDF annotation.
- Samsung Notes prioritizes stylus latency, individual pages or infinite scrolling, page templates, handwriting cleanup, shape cleanup, and PDF annotation.
- OneNote uses notebooks, sections, pages, and subpages. Its hierarchy and scoped search work well for large subjects, but its free canvas is weaker for a physical-page product.
- Goodnotes combines notebook covers, page thumbnails, handwriting search, editable backup files, and study sets.
- Notability uses a compact tool palette, perfect shapes, handwriting and math conversion, masking tape, and audio linked to ink.
- MyScript Notes separates notebooks, responsive documents, boards, and PDFs. Its strongest patterns are pen gestures, handwriting conversion, handwriting search, and hold-to-perfect shapes.
- Flexcil connects notebooks with source PDFs. It also uses reference links, multi-page views, masking tools, and audio replay.
- Squid proves that Android users value low-latency vector ink, active-pen behavior, editable backup files, and portable PDF export.

Recent user feedback repeats the same complaints:

- pen latency or handwriting that looks worse than the system note app;
- long notebooks without chapters, page titles, bookmarks, or useful search;
- cloud or device lock-in;
- bitmap PDF output;
- Android ports that feel like web apps;
- toolbars that hide common actions or show too many uncommon actions;
- lost undo history after changing pages;
- degraded performance as a notebook grows.

These findings support a notebook-first design with local data ownership. They do not justify copying another app's visual design.

## Chosen product model

SeliaDocs uses one content hierarchy:

```text
Library
├── Inbox notebook
│   └── Flow pages for quick notes
└── User notebook
    ├── Chapter
    │   ├── Paper page
    │   ├── Flow page
    │   └── PDF-backed page
    └── Unfiled pages
```

The hierarchy has one chapter level. Nested chapter groups are out of scope. One level covers semesters, modules, lecture blocks, and book chapters without adding a second navigation problem.

### Notebook

A notebook stores its title, cover, default paper, page orientation, input defaults, favorite state, creation time, update time, and trash state.

A notebook may contain unfiled pages. A grocery list does not need a chapter.

### Chapter

A chapter groups pages inside one notebook. A chapter stores an ID, a notebook ID, a title, a color, and an order index.

Moving a chapter moves its pages as one ordered group. Deleting a non-empty chapter requires the user to choose whether to move its pages to Unfiled or delete them.

### Page

A page stores an optional title, a chapter ID, an order index, a page mode, bookmark state, timestamps, and mode-specific data.

SeliaDocs supports three page modes:

- `PAPER`: fixed-size paper with vector ink and positioned elements;
- `FLOW`: a reflowing block document for typed notes, checklists, headings, and long-form writing;
- `PDF`: a page backed by an imported PDF page with a separate editable annotation layer.

The app does not convert a page between modes after the user adds content. Users can copy content into a page with another mode.

### Inbox

SeliaDocs creates the Inbox notebook when the user creates the first Quick Note. A Quick Note creates a `FLOW` page without showing the notebook creator.

The Inbox is visible in the library. Users can move an Inbox page into another notebook and chapter.

## Rejected product models

### Separate mini-apps for notebooks, documents, boards, and PDFs

This model offers flexibility, but each content type needs separate navigation, search, backup, and settings. SeliaDocs keeps one hierarchy and uses page modes instead.

### One infinite canvas for every note

An infinite canvas simplifies free placement. It weakens page navigation, printing, PDF export, and the physical notebook model. SeliaDocs may add a board page later, but an infinite canvas is not the default.

### A cloud account as the primary data model

An account would simplify cross-device sync. It would also add a backend, authentication, conflict resolution, privacy obligations, and operating cost. The first release uses local editable backup files.

## Library design

The library represents a backpack, not a dashboard.

### Tablet

The tablet library uses a 72 dp navigation rail with these destinations:

- Library
- Search
- Quick Note
- Trash
- Settings

The content area shows:

1. a small Recent row;
2. the Inbox when it exists;
3. notebook covers in a responsive grid.

Notebook covers remain the strongest color in the app. The surrounding UI uses the existing warm neutral background.

### Phone

The phone library uses a compact top app bar and bottom navigation. It defaults to a two-column notebook grid when the width permits. It switches to a list when accessibility display scaling makes two columns too narrow.

### Library interactions

- Tap a cover to open its last visited page.
- Long-press a cover to enter selection mode.
- Use the cover action button for rename, favorite, backup, move to trash, and permanent deletion.
- Drag a cover only when selection mode is active. Normal scrolling must not start a drag.
- Show Quick Note as a primary action. Do not place it inside the notebook creator.

## Notebook editor design

The editor gives the page most of the screen. Persistent controls use flat regions and thin dividers. It must not use a stack of cards around the canvas.

### Tablet layout

An expanded tablet uses these regions:

1. a 56 dp app bar;
2. a collapsible 232 dp Contents pane;
3. the page canvas;
4. an optional context pane.

The app bar contains Back, notebook title, current chapter, page count, Search, Share, and More.

The Contents pane contains chapter tabs and real page thumbnails. A user can:

- collapse or expand a chapter;
- add, rename, recolor, move, or delete a chapter;
- add, rename, bookmark, duplicate, move, or delete a page;
- drag pages within a chapter or into another chapter;
- scrub through thumbnails;
- enter a page number.

The context pane stays closed during normal writing. It opens for search results, selection properties, math confirmation, table properties, or source material.

### Phone layout

The phone editor uses the full screen for the page. The top bar shows Back, a shortened notebook title, the page position, Search, and More.

The bottom palette contains the active writing tools. Contents, search results, and properties open as bottom sheets.

The compact page control reads, for example, `Page 12 of 84 · Thermodynamics`. Tapping it opens Contents.

### Page zoom and navigation

Each paper page supports Fit width, Fit page, and manual zoom. Fit width is the default on tablets because it gives handwriting more horizontal space.

Two-finger horizontal swipes change pages. One-finger movement pans only when finger drawing is disabled or Pan is active. Stylus input never changes pages.

A page change uses a 140 to 180 ms horizontal slide with a page-edge shadow. Reduced motion replaces the slide with an immediate change.

## Tool palette

The palette is icon-first. Every icon has an accessible label and a minimum 48 dp target.

The primary row contains:

- Pen
- Highlighter
- Eraser
- Lasso
- Shape
- Insert
- Undo
- Redo

Pencil remains a pen preset instead of a separate top-level destination. The active tool expands to show color, width, opacity, and tool-specific settings.

Insert contains:

- Text
- Image
- Checklist
- Table
- Math
- New page
- Import PDF

The palette may dock at the top or bottom. A left-handed setting mirrors contextual controls but does not mirror text direction.

The app preserves the current AndroidX Ink implementation for pressure, tilt, stylus eraser, palm cancellation, and motion prediction.

## Content and editing

### Vector ink

Raw ink is authoritative. Search, conversion, and cleanup features store derived data without replacing the strokes.

Ink remains vector data in editable backups and PDF export. Export must not flatten all handwriting into one low-resolution bitmap.

### Selection

Lasso selection supports ink and positioned elements. The selection menu provides:

- Move
- Resize
- Rotate
- Duplicate
- Copy
- Cut
- Delete
- Change style
- Convert
- Group
- Bring forward
- Send backward

The current repository already stores element transforms and has repository methods for update and delete. The implementation must connect those methods to the editor before adding more element kinds.

### Shapes

The user draws a shape and holds the stylus at the end. SeliaDocs replaces a confident match with a clean shape in one undoable action.

Supported initial shapes are:

- line;
- arrow;
- ellipse or circle;
- rectangle or square;
- triangle.

Low-confidence input remains unchanged. A short hint offers manual cleanup. The shape tool also supports direct insertion.

### Tables

A table is an editable page element. It stores rows, columns, cell text, column widths, row heights, borders, background colors, merged-cell metadata, and its page transform.

Users can insert a table from Insert. SeliaDocs may also detect a grid made from straight strokes. Grid conversion requires user confirmation.

The first table release supports text cells, row and column insertion, row and column deletion, resizing, and basic merging. Spreadsheet formulas are out of scope.

### Flow pages

A flow page stores ordered blocks. Initial block kinds are:

- heading;
- paragraph;
- checklist;
- bulleted list;
- numbered list;
- image;
- table;
- math;
- divider;
- embedded paper section.

Flow pages support long-form writing and device rotation without manual repositioning. Paper sections let users add handwriting inside a typed document without making the whole document a free canvas.

## Search and recognition

Search is a product destination and an in-notebook tool.

### Search scopes

Users can search:

- the current page;
- the current chapter;
- the current notebook;
- all notebooks.

Search filters include content type, bookmark state, date, chapter, and page mode.

### Indexed sources

SeliaDocs indexes:

- notebook titles;
- chapter titles;
- page titles;
- typed blocks and text elements;
- checklist text;
- table cell text;
- math expressions and results;
- PDF text layers;
- OCR from imported images and image-only PDFs;
- recognized handwriting.

Each result stores the notebook ID, chapter ID, page ID, content type, text, and an optional page bounding region. Opening a result scrolls to the region and highlights it.

### Database design

Room FTS4 stores searchable text. A normal Room entity stores result metadata and bounding regions. The FTS table does not own the source content.

The indexer updates typed content in the same transaction as the source change. OCR and handwriting recognition update the index after the source transaction commits.

Deleting a source item removes its index rows. Updating a source replaces its old index rows. Backup restore rebuilds the FTS index instead of trusting archived FTS files.

### Handwriting recognition

Handwriting recognition is optional. The app offers language packs in Settings and when the user first enables handwriting search.

The first supported product languages are English and Czech. The model selector may expose other ML Kit languages after compatibility tests.

Each model is about 20 MB. The download screen shows the language, approximate size, local-only behavior, progress, failure state, and Delete action.

Recognition runs on saved vector strokes. The app does not upload note content. If the selected SDK adds a network permission for model download, the manifest, privacy policy, Play data safety answers, and in-app disclosure must state that model files are downloaded while note content stays local.

The recognizer stores candidates and confidence. Search uses the best candidate. Conversion UI lets the user choose another candidate.

### Image OCR

The first OCR release uses a bundled Latin-script model so English and Czech image text works offline after installation.

OCR runs only on images selected by the user and imported PDF pages. It stores text and bounding regions. Users can disable OCR or remove the derived index without deleting the source image.

## Math design

Math stays deterministic. The app does not use a language model to calculate a result.

Settings provide three math result modes:

- Off
- Suggest
- Insert

The default is Suggest.

For typed or confidently recognized linear math, an equals sign requests evaluation. Suggest shows a small result control. Insert adds the result only after the parser accepts the expression.

The first smart math release supports:

- arithmetic;
- parentheses;
- percentages;
- powers;
- named variables declared earlier on the page;
- simple functions;
- two-dimensional graphs for supported functions.

Variable evaluation follows page reading order from top to bottom. A result that depends on an undefined variable shows an explicit error.

Ambiguous handwritten symbols open a confirmation panel. The panel shows the interpreted expression and alternative symbols. SeliaDocs never displays a guessed answer as confirmed math.

Matrices, multi-line fractions, symbolic algebra, and step-by-step proofs require a specialized math engine. They are not part of the first smart math release.

## Imported material and PDF

A notebook can store imported PDF materials. The original PDF remains a private asset.

Each PDF page maps to a `PDF` page in the notebook. Vector ink and elements remain separate annotations. Export combines the source PDF page with the annotation layer.

The PDF reader provides:

- thumbnails;
- page-number navigation;
- text search when the PDF has a text layer;
- OCR for image-only pages;
- bookmarks;
- internal links;
- Fit width and Fit page;
- pen, highlighter, eraser, lasso, text, image, and shape tools.

Android 10 compatibility rules out relying only on newer platform PDF editing APIs. The implementation must use a tested renderer path that works on API 29. Alpha Jetpack PDF components may be evaluated in a spike, but an alpha dependency cannot replace release verification.

Reference links connect a note region to another page or imported material. Tapping a reference opens the target and adds the source page to navigation history.

## Study tools

Study tools use existing content. They do not create a second library.

### Masking tape

Masking tape hides a selected region. Study mode reveals regions on tap and records Right or Review. The mask never deletes or modifies the underlying content.

### Flashcards

A flashcard links a question region or text block to an answer region or text block. Cards belong to a notebook and may be filtered by chapter.

The first scheduler uses a documented spaced-repetition algorithm. It stores the next review time and response history locally.

### Audio

Audio linked to ink is a later subsystem. It adds microphone permission, large assets, playback synchronization, and privacy work. The design reserves timestamps on strokes, but the first implementation plans do not include audio.

### No generic AI chat

SeliaDocs does not add a global chatbot. Future summary or quiz generation requires a separate approved design with explicit model, privacy, cost, and offline behavior.

## Backup, restore, and portability

PDF export is not a backup. SeliaDocs provides both an editable archive and portable exports.

### Editable archive

The extension is `.seliadocs`. The file is a ZIP archive with this structure:

```text
manifest.json
notebooks.jsonl
chapters.jsonl
pages.jsonl
strokes.jsonl
elements.jsonl
blocks.jsonl
materials.jsonl
assets/<sha256>.<extension>
checksums.json
```

`manifest.json` stores the archive format version, app version, export time, counts, and required feature flags. The archive stores source entities and assets. It does not store Room internal files, cache files, thumbnails, FTS tables, or undo history.

### Backup scopes

Users can export:

- one notebook;
- selected notebooks;
- the complete library.

The export screen shows progress, item counts, estimated size, destination, completion, and failure details.

### Restore modes

Import into library is the default. It validates the archive, remaps colliding IDs, copies assets, and adds the imported notebooks without changing existing notebooks.

Replace library is an advanced action. Before replacement, SeliaDocs creates a local rollback archive. The app swaps the validated staged library into place only after every database row and asset is ready.

### Archive validation

The importer must:

- reject absolute paths, parent traversal, duplicate entries, and unsupported compression methods;
- stream each entry instead of loading the archive into memory;
- cap extracted bytes at the smaller of 80 percent of available storage or 8 GiB;
- cap one entry at 1 GiB;
- verify every SHA-256 value;
- validate archive and schema versions;
- validate entity kinds, finite coordinates, dimensions, text lengths, page ordering, and foreign keys;
- reject references to missing assets;
- stage files in private storage;
- apply database changes in a Room transaction;
- remove staged files after success or failure;
- rebuild search and thumbnail indexes after import.

The user chooses the destination and source through Android's Storage Access Framework. SeliaDocs does not request broad storage access.

### Portable export

Users can export a notebook or chapter as PDF. Paper and annotations remain vector content when the target format supports it. Flow pages render with stable pagination. Users may also export selected pages as PNG.

## Data model changes

The design adds these entities:

- `ChapterEntity`
- `BlockEntity`
- `MaterialEntity`
- `SearchSourceEntity`
- `SearchTextEntity` with Room FTS4
- `SearchRegionEntity`
- `StudyMaskEntity`
- `FlashcardEntity`
- `ReviewEntity`

`PageEntity` gains:

- `chapterId: String?`
- `title: String?`
- `pageMode: String`
- `favorite: Boolean`
- `createdAt: Long`
- `updatedAt: Long`
- `materialId: String?`
- `materialPageIndex: Int?`

`ElementKind` gains `TABLE` after element transforms work in the UI.

The first schema change requires a real Room migration and a migration test. The app must not use destructive migration.

## Performance architecture

The current editor observes all strokes and elements in one notebook and filters them in memory. That model cannot support a book-length notebook.

The new editor observes content for the selected page only. It preloads adjacent page metadata and cached thumbnails, not full stroke data.

The implementation must:

- query page content by page ID;
- page the Contents list for large notebooks;
- generate thumbnails in a bounded background queue;
- invalidate only the thumbnail for a changed page;
- cache rendered PDF tiles by page, zoom, and revision;
- cancel recognition work when the source revision changes;
- keep a bounded in-memory undo history for recently edited pages;
- preserve per-page undo history while the app session remains active;
- avoid database or file I/O on the main thread;
- stream backup and restore data;
- release page bitmaps when they leave the visible window.

Acceptance fixtures include notebooks with 500 pages. Opening or editing one page must not load strokes or elements from the other 499 pages.

## Settings

Tablet Settings uses a two-pane list and detail layout. Phone Settings uses a category list followed by a detail screen.

Categories are:

- Notebook defaults
- Writing and stylus
- Recognition and OCR
- Math
- Study tools
- Backup and storage
- Interface and accessibility
- App and privacy

Each setting shows its effect. Paper, covers, tools, and motion use previews. Recognition models show installed size and status. Backup shows the last successful manual backup only after a backup completes.

The Buy Me a Coffee control remains in App details and does not unlock features.

## Accessibility

- Interactive targets are at least 48 dp.
- Every icon-only control has a content description.
- Selection is not communicated by color alone.
- Text meets WCAG AA contrast against its background.
- Reduced motion disables page slides and nonessential animation.
- Large controls increase palette targets without changing canvas scale.
- Hardware keyboard users can reach search, page navigation, tool selection, undo, redo, and dialogs.
- Screen readers can read notebook, chapter, page, search-result, and model-download state.

## Errors and recovery

The app reports failures by operation. It does not collapse database, storage, recognition, model download, PDF, and validation failures into one message.

Mutating operations either complete or leave the previous state intact. Import, page reorder, chapter reorder, shape replacement, and restore use transactions or staged files.

The editor autosaves source content before it starts derived work such as OCR, recognition, thumbnail rendering, or PDF export.

## Privacy

Source notes stay in private app storage unless the user exports them.

Recognition and OCR run on the device. Model downloads may contact the model provider, but the app must not send note content with a model request.

The app has no analytics, ads, telemetry, crash-reporting SDK, account requirement, or remote note database.

Any new permission or SDK requires updates to:

- `PRIVACY.md`;
- `site/privacy/index.html`;
- the in-app privacy disclosure;
- `docs/play-store/DATA_SAFETY.md`;
- the live Play Console declaration.

## Testing and acceptance

Every implementation package must pass:

- repository unit tests;
- Room migration tests when the schema changes;
- lint;
- a debug build;
- a signed release build before publication;
- emulator instrumentation on Android 10 and Android 17;
- phone and tablet visual inspection;
- negative-path tests for untrusted imports;
- regression tests for writing, page navigation, PDF export, Settings, and Back behavior.

Backup tests cover:

- complete round trip;
- one-notebook import;
- ID collision;
- missing asset;
- corrupt checksum;
- unsupported version;
- path traversal;
- extraction-size limit;
- interrupted staging;
- existing-library preservation after failure.

Search tests cover typed text, math, OCR, handwriting, deletion, update replacement, scope filters, and an impossible query.

Stylus emulator tests cover routing, cancellation, eraser behavior, pressure and tilt persistence, and page gestures. The project must not claim hardware latency or hover quality without explicit physical-device authorization.

The current Android 10 multi-notebook action-menu failure remains a release blocker until a clean reproduction and root-cause fix pass the full suite. A partial test pass does not clear the blocker.

## Delivery packages

The work is split into independently releasable packages.

### Package 0: Release baseline

- finish the SeliaDocs identity;
- fix Android system Back behavior;
- fix the Android 10 multi-notebook action-menu failure;
- reconcile README, privacy, store assets, package references, and release artifacts;
- restore green local and GitHub CI.

### Package 1: Data safety and page-scale performance

- add editable backup and restore;
- connect element move, resize, rotate, and delete;
- replace notebook-wide content observation with selected-page queries;
- preserve bounded per-page undo history;
- add real thumbnails and the 500-page fixture.

### Package 2: Notebook structure

- add chapters, page titles, bookmarks, and Contents navigation;
- add Quick Note and Inbox;
- add chapter-aware PDF export;
- add tablet and phone adaptive layouts.

### Package 3: Typed search

- add FTS4 entities and index maintenance;
- index titles, typed text, tables, checklists, and math;
- add global and scoped search UI;
- add result navigation and highlighting.

### Package 4: Recognition

- add offline Latin OCR;
- add English and Czech handwriting model management;
- index OCR and recognized handwriting;
- add lasso conversion with candidate correction.

### Package 5: Smart ink and math

- add hold-to-shape cleanup;
- add math result modes, variables, and supported graphs;
- add editable tables and grid conversion;
- add selection grouping and layer order.

### Package 6: Materials and study

- add PDF import and annotation;
- add reference links and navigation history;
- add masking tape;
- add flashcards and local review scheduling;
- evaluate audio-linked notes in a separate design before implementation.

### Package 7: Publication

- run complete local and GitHub verification;
- merge the release pull request;
- deploy GitHub Pages and verify the live privacy URL;
- publish signed APK and AAB assets in a GitHub prerelease;
- complete the Google Play listing, declarations, screenshots, and testing track;
- report any Play account testing gate as an external blocker with live evidence;
- close and remove only SeliaDocs emulators and regenerable build data after remote artifacts are verified.

## Features excluded from this design

- mandatory accounts;
- cloud sync or collaboration;
- a generic AI chatbot;
- automatic replacement of handwriting;
- AI-generated images;
- a subscription paywall;
- nested chapter groups;
- spreadsheet formulas;
- symbolic proof generation;
- an infinite canvas as the default page;
- background network upload of notes;
- broad storage permissions.

## Source references

- [Apple Notes handwriting](https://support.apple.com/guide/ipad/add-drawings-and-handwriting-ipada87a6078/26/ipados/26)
- [Apple Notes search](https://support.apple.com/en-euro/guide/ipad/ipad64863a98/ipados)
- [Apple Notes math](https://support.apple.com/en-ie/guide/ipad/ipad10f28bec/26/ipados/26)
- [Samsung Notes organization and page styles](https://www.samsung.com/us/support/answer/ANS10004548/)
- [OneNote organization](https://support.microsoft.com/en-us/onenote/organize-your-notes)
- [OneNote ink, shapes, and math](https://support.microsoft.com/en-us/onenote/onenote-help-and-learning/convert-your-ink-to-text-shape-and-math-equations)
- [Goodnotes search](https://support.goodnotes.com/hc/en-us/articles/7353743594127-How-to-Search-Your-Notes)
- [Goodnotes manual backup](https://support.goodnotes.com/hc/en-us/articles/7353694866831-Back-up-and-restore-your-library-manually)
- [Notability handwriting and math conversion](https://support.gingerlabs.com/hc/en-us/articles/360003878731-Handwriting-and-Math-Conversion)
- [MyScript Notes product model](https://www.myscript.com/notes/)
- [Flexcil study workflow](https://www.flexcil.com/)
- [Squid Android features](https://www.squidnotes.com/?lang=en)
- [ML Kit Digital Ink Recognition](https://developers.google.com/ml-kit/vision/digital-ink-recognition)
- [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [Room FTS4](https://developer.android.com/reference/androidx/room/Fts4)
- [Android Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [Android stylus guidance](https://developer.android.com/develop/ui/compose/touch-input/stylus-input)
- [Android PDF rendering](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)
