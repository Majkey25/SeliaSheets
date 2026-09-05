# SeliaSheets editor and ink redesign

## Goal

Make SeliaSheets a notebook-first editor that feels dependable with an active stylus, remains clear on phones, and keeps text, ink, images, shapes, PDFs, and math on one page without modal-box friction.

The first release slice fixes the ink core and tool interaction. Later slices reuse that base for selection, text, math, and page navigation.

## Evidence

Current production already has page notebooks, chapters, page search, PDF annotation, OCR, full-page typing, movable elements, backups, draw-and-hold shapes, and optional local handwriting recognition.

The current gaps are structural:

- Pencil tilt is sampled once at stroke start. It does not vary along the stroke.
- Tablet tools are a long horizontally scrolling row of text labels. Brush settings expand inside that row.
- Pen and Pencil share one width and color state. There are no reusable tool presets.
- Ink selection can move strokes but cannot resize, rotate, recolor, duplicate, or filter selected content types.
- Text boxes start in a dialog and appear at a fixed location. Full-page text is a separate mode.
- Automatic math is opt-in and model-dependent. Manual math remains a separate command.
- The connected Huawei API 29 phone reports `ExternalStylusConnected: false`; synthetic events cannot prove physical pen feel.

## Reference patterns

- Apple Notes: mixed typed and handwritten content, a compact movable markup palette, handwriting refinement, Smart Selection, images that resize in the drawing area, searchable handwriting, and inline handwritten calculations. [Apple handwriting](https://support.apple.com/en-mide/guide/ipad/ipada87a6078/ipados) [Apple math](https://support.apple.com/guide/ipad/enter-formulas-and-equations-ipad10f28bec/ipados)
- Samsung Notes: pen types, size and color controls, highlighter opacity, stroke and area erasers, lasso and rectangle selection, page sorter, PDF annotation, handwriting alignment, text conversion, auto-fix shapes, and zoom lock. [Samsung Notes tools](https://www.samsung.com/us/support/answer/ANS10001469/) [Samsung handwriting](https://www.samsung.com/us/support/answer/ANS10003634/)
- Goodnotes: object-aware lasso, selection filters, direct image/text/ink editing, circle-to-lasso, tool presets, hold-to-shape, and predictable page scrolling. [Goodnotes lasso](https://support.goodnotes.com/hc/en-us/articles/7353695644175-Select-move-and-edit-content-on-the-page) [Goodnotes shapes](https://support.goodnotes.com/hc/en-us/articles/13682939148943-Improved-Shape-Tool)
- Excalidraw: direct selection, explicit hand/pan mode, stable shape tools, editable arrows, locking, grouping, snapping, and object-local actions. [Excalidraw features](https://github.com/excalidraw/excalidraw/blob/master/README.md)
- Android: per-input pressure, tilt, orientation, hover, motion prediction, text-field handwriting, and custom brush behaviors. [Stylus guidance](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input) [Ink releases](https://developer.android.com/jetpack/androidx/releases/ink) [Compose handwriting](https://developer.android.com/develop/ui/compose/touch-input/stylus-input/stylus-input-in-text-fields)

## 2026 competitor findings

- Goodnotes keeps Lasso active after selection. The selected object receives direct resize and rotation handles plus an object menu for duplicate, delete, color, conversion, and layer actions. Its lasso can filter handwriting, images, text boxes, and other content. [Goodnotes selection](https://support.goodnotes.com/hc/en-us/articles/7353695644175-Select-move-and-edit-content-on-the-page)
- Notability uses freehand and rectangular selection. A selected drawing can move, scale, rotate, change style, convert, duplicate, group, or delete. Its Text tool starts typing where the user taps. [Notability selection](https://support.gingerlabs.com/hc/en-us/articles/360018646412-Select-Tool) [Notability text](https://support.gingerlabs.com/hc/en-us/articles/206059387-Text-and-Text-Boxes)
- MyScript Notes separates fixed-page notebooks, structured documents, freeform boards, and PDFs. The notebook format combines handwriting, typing, images, zoom, page thumbnails, conversion, and export. [MyScript note types](https://help.myscript.com/notes/overview/notes/)
- OneNote for Android keeps the ink toolbar collapsible and uses two-finger canvas movement. Its Lasso moves and resizes ink. [OneNote Android ink](https://support.microsoft.com/en-us/onenote/take-handwritten-notes-in-onenote-for-android)

These products agree on the core behavior. Selection must edit content directly. Text must start at the tapped page location. Tool settings must stay secondary to the canvas. SeliaSheets should implement those behaviors before adding study extras such as tape, audio, or generated summaries.

## Approaches considered

### Surface polish only

Replace labels with icons and rearrange menus. Low risk, but it leaves the one-sample tilt model, fragmented selection, modal text boxes, and weak gesture ownership. Rejected.

### Replace the editor with a custom engine

One renderer and object model could eventually match a whiteboard app. It would discard working AndroidX Ink, storage, PDF, OCR, history, and migration code. Rejected as too risky.

### Staged core repair

Keep the current database, page model, backup format, and AndroidX Ink integration. Replace the weak parts in risk order, with compatibility tests after each slice. Selected.

## Interaction design

### Tablet

- Keep the page sorter visible in expanded width.
- Use one compact icon palette near the page edge. Primary tools are Select, Pen, Pencil, Highlighter, Eraser, Text, and Insert.
- Tapping the active tool opens a small anchored panel for presets and detailed options. It must not resize the page or extend the main toolbar.
- Undo, redo, search, page add, and notebook menu remain in the app bar.
- Tooltips and content descriptions expose labels; permanent text labels are not required beside every icon.

### Phone

- Keep the canvas dominant.
- Use a six-slot bottom palette: Select, current writing preset, Highlighter, Eraser, Text, Insert.
- A tool panel opens as a bottom sheet. Page navigation and notebook actions stay in the top bar.
- No horizontal toolbar scrolling.

### Tool presets

- Each Pen, Pencil, and Highlighter preset owns brush family, color, size, opacity, smoothing, pressure response, and tilt response.
- Ship three editable presets per writing tool. A preset tap selects it; a second tap edits it.
- Keep safe defaults when pressure, tilt, or orientation is unavailable.

## Ink input contract

- Read tool type for every pointer, not only the event source.
- Stylus and eraser input own the ink path. Touch never becomes ink while a stylus is active.
- One finger pans or changes page only when finger drawing is off. Two fingers always pan and zoom.
- Pressure affects width continuously. Pencil tilt and orientation affect tip width, opacity, and rotation continuously, not once per stroke.
- Hover prewarms rendering and shows a non-persistent cursor preview when the device reports hover.
- Coordinates must stay invariant across zoom and pan. The same screen point must map to the same visible paper point for pen, eraser, lasso, and selection handles.
- Barrel buttons use configurable momentary actions, initially eraser and lasso.

## Smart editing contract

- Hold at the end of a stroke converts line, arrow, ellipse, rectangle, or triangle in place.
- Undo after conversion restores the exact raw ink. Redo reapplies the shape.
- Lasso can target ink, text, images, shapes, or any combination. Selection opens one object menu.
- Direct canvas tap creates or edits text. Full-page typing remains available without creating a visible box.
- Android 14+ text fields use platform stylus handwriting. Static text placeholders delegate handwriting to the actual field.
- A handwritten expression ending with `=` may show an inline result beside the ink. Recognition failure leaves ink unchanged and silent.

## Visual system

- Material 3 foundation with restrained SeliaSheets beige, periwinkle, ink black, and sparse semantic colors.
- 48 dp minimum touch targets, 8 dp spacing rhythm, 10 to 12 dp control radius, low elevation.
- Icons communicate tools. Color and line previews communicate brush state.
- Selected state uses one filled tonal container. Hover, pressed, disabled, and focus states remain visible.
- Motion lasts 120 to 220 ms and respects reduced motion.

## Implementation slices

1. Ink engine proof: pin and test AndroidX Ink 1.1 alpha behavior support, continuous pressure/tilt/orientation, hover preview, and API 29 fallback.
2. Tool presets and palette: replace the text toolbar, add anchored/bottom-sheet options, preserve accessibility and compact layouts.
3. Unified selection: transform and style ink, images, text, and shapes from one object menu.
4. Direct text: tap-to-edit text objects, full-page flow, Android handwriting delegation.
5. Smart writing: inline math result, recognition correction, shape preview, raw-ink undo.
6. Page flow: continuous or paged navigation setting, zoom lock, page sorter polish, search navigation.

## Acceptance

- Pressure changes width within one stroke.
- Tilt changes Pencil shape within one stroke; missing tilt produces a stable fallback.
- Pen, eraser, lasso, selection handles, and inserted images remain aligned at 100%, 200%, and 400% zoom.
- A palm cannot create a mark or steal an active stylus stroke.
- A selected image can move, resize, rotate, duplicate, crop, lock, and delete.
- Shape conversion and inline math are automatic after the required gesture, and Undo restores raw input.
- Phone and tablet expose every primary tool without toolbar clipping or horizontal scrolling.
- Existing notebooks, backups, PDF export, OCR, search, and Android 10 behavior do not regress.
- Physical active-stylus acceptance records tool type, pressure, tilt, orientation, buttons, hover, coordinates, cancellation, and latency on a compatible device. Synthetic tests remain supporting evidence only.

## Limits

- Do not copy proprietary icons, assets, wording, or layouts pixel for pixel.
- No cloud sync, collaboration, generative AI, or new account system in this redesign.
- Do not claim physical stylus acceptance from the current Huawei phone.
