# Google Play store listing

## Main listing

**App name:** SeliaSheets

**Default language:** English (United States)

**Short description:** Private study notebooks for handwriting, typing, PDFs, shapes, and math.

**Full description**

SeliaSheets turns an Android tablet or phone into a private backpack of paper notebooks.

Start with an illustrated ruled, grid, dotted, or blank template. A live preview shows the cover, paper, and orientation before creation. Organize ordered pages into chapters, add page titles and bookmarks, and search page titles, chapter titles, typed text, and math.

Type directly on a page or write with a stylus using pressure. Switch between pen, pencil, and highlighter. Erase stroke segments or complete strokes, select with a lasso, and use up to 100 Undo and Redo steps. Draw and hold to clean a line, arrow, ellipse, rectangle, or triangle. Undo restores the original ink.

Import images through Android Photo Picker. Import a PDF, annotate its pages, and preserve the original document in an editable backup. Calculate arithmetic locally and place the result on the page. Export the complete notebook to a PDF location that you choose.

Privacy is the default:

- no account;
- no ads or cloud sync;
- private on-device notebook storage;
- user-controlled PDF export.

Image text recognition is on by default for imported images and can be disabled in Settings. It uses a bundled on-device Latin model.

Optional handwriting features require a Google language model downloaded through Settings. After download, selected handwritten strokes can be recognized on-device. Choose a text suggestion to add to the page; the original ink stays unchanged. Automatic handwriting math supports simple single-line arithmetic, not general two-dimensional math or LaTeX. Check recognition results before relying on them.

Notebook content, imported images, raw ink, and recognition results stay on your device unless you explicitly export them. Google ML Kit processes recognition on-device and collects technical SDK metadata, including device and app information, per-installation identifiers, and performance and usage metrics, for diagnostics and analytics. Network access supports Google model downloads and SDK diagnostics. See the privacy policy for details.

SeliaSheets supports Android 10 and newer and adapts to phones and large tablet canvases.

This is a beta release. Accounts, collaboration, and cloud sync are not included.

## Czech privacy and recognition text (cs-CZ)

Replace the corresponding privacy and recognition paragraphs in an existing Czech listing with the following text. Keep its other feature descriptions unchanged. Do not claim that the app has no network permission or SDK telemetry, or that handwriting-to-text is unavailable.

SeliaSheets nevyžaduje účet, neobsahuje reklamy a nesynchronizuje sešity do cloudu. Sešity ukládá do soukromého úložiště aplikace v zařízení. O exportu PDF rozhodujete vy.

Rozpoznávání textu v importovaných obrázcích je ve výchozím nastavení zapnuté a lze je vypnout v Nastavení. Používá přibalený model pro latinku, který pracuje přímo v zařízení.

Volitelné funkce rozpoznávání rukopisu vyžadují stažení jazykového modelu Google v Nastavení. Poté lze vybrané ručně psané tahy rozpoznat přímo v zařízení. Z nabídnutých výsledků vyberete text, který se přidá na stránku; původní rukopis zůstane beze změny. Automatické rozpoznávání ručně psaných výpočtů podporuje jednoduché jednořádkové aritmetické výrazy, nikoli obecné dvourozměrné matematické zápisy nebo LaTeX. Výsledky rozpoznávání si před použitím zkontrolujte.

Obsah sešitů, importované obrázky, tahy rukopisu a výsledky rozpoznávání zůstávají v zařízení, pokud je sami neexportujete. Google ML Kit provádí rozpoznávání přímo v zařízení a shromažďuje technická metadata SDK, včetně informací o zařízení a aplikaci, identifikátorů jednotlivých instalací a metrik výkonu a používání, pro diagnostiku a analytiku. Síťový přístup slouží ke stahování modelů Google a diagnostice SDK. Podrobnosti najdete v zásadách ochrany osobních údajů.

## Publication checks

The recognition wording above is verified against release `0.10.0-beta.1`, version code 24. Selected-ink conversion and automatic arithmetic are separate features. The app checks the configured handwriting model's status at startup, even when automatic handwriting recognition is off; do not describe all SDK diagnostics as optional or disabled by that setting. Compare the saved Console text with this source before submitting a listing update.

## Classification

- App category: Productivity
- Tags: note taking, handwriting, stylus, notebook, drawing
- Contains ads: No
- App access: All functionality is available without login or special access
- Target audience: Ages 13 and older
- News app: No
- Health app: No
- Financial features: No
- Government app: No

## Contact

- Email: majkeylab@gmail.com
- Website: https://github.com/Majkey25/SeliaSheets
- Privacy policy: https://majkey25.github.io/SeliaSheets/privacy/

## Release notes

0.6 beta 1 improves writing and editing. Pencil strokes now react to pressure, tilt, and orientation throughout each stroke. The editor now has a stylus hover preview and direct scale, rotate, duplicate, recolor, and delete actions for selected ink. Inline text drafts now survive rotation and save before tool changes or Back. Phone and tablet toolbars now use fixed icon palettes with brush controls on demand. Backups now reject oversized, malformed, or unreferenced data.
