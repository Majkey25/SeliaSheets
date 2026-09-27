# Import formats

These additions are unreleased. The current downloadable release remains `0.9.0-beta.1`.

Open a notebook and choose **Add page** to append document pages after the selected page. New pages keep the selected chapter. Add images through the editor's **Image** tool.

| Input | Notebook result | Limits |
| --- | --- | --- |
| PDF | Original PDF pages with separate editable ink and annotations | 256 MiB; up to 2,000 pages |
| PowerPoint `.pptx` | Static slides converted locally to PDF-backed pages | 32 MiB; up to 100 slides; system fonts; no PowerPoint object editing |
| Word `.docx` | Editable body text | 16 MiB; excludes source layout, pictures, headers, and notes |
| `.txt`, `.md` | Editable text, including literal Markdown syntax | 4 MiB; 1,000,000 UTF-16 code units; UTF-8 or BOM-marked UTF-16 |
| JPEG, PNG, WebP, HEIF, HEIC | Movable, resizable image on the current page | Device decoder support; bounded file size, dimensions, and decoded allocation |

PowerPoint conversion needs a current Android System WebView. It blocks remote document resources and does not upload the presentation. Conversion uses one slide at a time, and cancellation removes the temporary rendering view and private file copies. The original presentation stays unchanged. Notebook backups contain the converted PDF and editable annotations, not the original PPTX.

Converted slides are rasterized PDF backgrounds. Their text is searchable through local OCR when enabled, not through a retained PowerPoint text layer. Export to PDF from PowerPoint first to preserve its original text and vector graphics.

PowerPoint layout can differ because of font substitution and renderer compatibility. Export from PowerPoint to PDF first when exact layout matters. Macro-enabled or encrypted presentations, external dependencies, audio/video, animated images, and unsupported vector media are rejected. Supported raster media inside slides includes PNG, JPEG, WebP, and BMP, subject to validation. Charts use their cached data; embedded Excel workbooks are not executed or edited.

Legacy `.ppt`, `.doc`, Keynote, OpenDocument, spreadsheets, SVG, and arbitrary binary files are not general import formats. Do not rename their extensions to bypass the picker. Export them to a supported format first.

PDF export is flattened. Use a `.seliasheets` backup to retain editable notebook annotations.
