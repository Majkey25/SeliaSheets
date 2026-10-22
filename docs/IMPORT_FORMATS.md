# Import formats

Available in [0.10.0-beta.2](https://github.com/Majkey25/SeliaSheets/releases/tag/v0.10.0-beta.2), version code 25.

Open a notebook and choose **Add page** to append document pages after the selected page. New pages keep the selected chapter. Add images through the editor's **Image** tool.

| Input | Notebook result | Limits |
| --- | --- | --- |
| PDF | Original PDF pages with separate editable ink and annotations | 256 MiB; up to 2,000 pages |
| PowerPoint `.pptx` | Static slides converted locally to PDF-backed pages | 32 MiB; up to 100 slides; system fonts; no PowerPoint object editing |
| Word `.docx` | Editable body text | 16 MiB; excludes source layout, pictures, headers, and notes |
| `.txt`, `.md` | Editable text, including literal Markdown syntax | 4 MiB; 1,000,000 UTF-16 code units; UTF-8 or BOM-marked UTF-16 |
| JPEG, PNG, WebP, HEIF, HEIC, BMP, GIF | Movable, resizable still image on the current page | Device decoder support; bounded file size, dimensions, and decoded allocation |

PowerPoint conversion needs a current Android System WebView. It blocks remote document resources and does not upload the presentation. Conversion uses one slide at a time, and cancellation removes the temporary rendering view and private file copies. The original presentation stays unchanged. Notebook backups contain the converted PDF and editable annotations, not the original PPTX.

Converted slides are rasterized PDF backgrounds. Their text is searchable through local OCR when enabled, not through a retained PowerPoint text layer. Export to PDF from PowerPoint first to preserve its original text and vector graphics.

PowerPoint layout can differ because of font substitution and renderer compatibility. Export from PowerPoint to PDF first when exact layout matters. Macro-enabled or encrypted presentations, external dependencies, audio/video, animated images, and unsupported vector media are rejected. Supported raster media inside slides includes PNG, JPEG, WebP, and BMP, subject to validation. Charts use their cached data; embedded Excel workbooks are not executed or edited.

Chart caches are limited to 10,000 positions each and 100,000 positions across the presentation. Sparse indexes, empty positions, data-table rows, and repeated chart references count toward this limit. Export larger charts to PDF first.

Legacy `.ppt`, `.doc`, Keynote, OpenDocument, spreadsheets, SVG, and arbitrary binary files are not general import formats. Do not rename their extensions to bypass the picker. Export them to a supported format first.

PDF export is flattened. Use a `.seliasheets` backup to retain editable notebook annotations.

Backup restore also checks combined metadata and page content against the device's memory budget. Many strokes, text elements, or image copies on one page can exceed that budget even when each item is valid. Split dense pages or restore smaller notebook exports on devices with less memory. Existing per-file limits still apply; PDF sources retain support for 256 MiB and 2,000 pages. Duplicate PDF pages remain supported and share search work.

Restore staging uses at most one third of available storage, capped at 8 GiB. Before changing the library, restore reserves additional space for installed assets and database writes; replacement rollback files cannot consume that reserve. If a limit is exceeded, restore fails and removes its temporary files without replacing existing notebooks.

Image annotations do not play animations: GIF and animated WebP use the first frame; PNG uses the native decoder's still image. Original image bytes are retained in backups. Import and restore reject incomplete image data through Android's [strict image decoder](https://developer.android.com/reference/android/graphics/ImageDecoder).
