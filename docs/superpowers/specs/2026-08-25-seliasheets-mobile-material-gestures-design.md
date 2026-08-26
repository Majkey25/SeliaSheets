# SeliaSheets mobile Material and page gestures design

## Goal

Make the notebook easy to scan and operate on a phone while preserving the paper-first tablet editor.

## Scope

This slice changes presentation and touch navigation only:

- Material 3 top bars respect safe system insets.
- Compact phone UI follows the current app-window width.
- The library shows two readable notebook covers on a normal 360 dp phone.
- Notebook actions use `ModalBottomSheet`.
- The compact editor uses a fixed bottom palette instead of horizontally hidden primary tools.
- A finger swipe turns pages without creating ink when finger drawing is off.
- A two-finger horizontal swipe turns pages when finger drawing is on.
- Users can change finger drawing for the current notebook.
- Selected tools and bookmark state have explicit semantics.
- Settings starts as a short list of collapsed groups.

## Non-goals

This slice does not add handwriting recognition, OCR, audio, cloud sync, accounts, a navigation rail, a new icon dependency, or a new animation system. It does not replace the existing expanded tablet Contents pane.

Automatic handwritten arithmetic is a separate release. It needs a downloaded recognition model, an updated privacy policy, and new Google Play Data Safety answers. The existing textbox-based Math action stays secondary until that release replaces it.

## Window classes

Every layout decision uses the current composable width:

- compact: less than 600 dp;
- medium: 600 through 839 dp;
- expanded: 840 dp or wider.

Compact and medium editors use the page plus a Contents bottom sheet. Expanded editors keep the current 244 dp Contents pane.

## Material layout

All root screens use Material 3 `TopAppBar`. The bar handles top and horizontal safe-drawing insets. Screen content consumes the bar inset once.

The compact editor has:

- top bar: Back, a tappable notebook title and `page / count`, Undo, Redo, and More;
- bottom palette: Type, Pen, Highlighter, Eraser, Lasso, and Insert;
- More: Search, Pencil preset, Export PDF, Settings, and the current-notebook **Draw with finger** toggle;
- Insert: Text object, Image, PDF, Shape conversion when selection exists, and Math.

No primary compact tool requires horizontal scrolling. Every action has a 48 dp minimum target. Selected tools expose `selected = true` semantics.

## Library and settings

The library grid uses a 148 dp adaptive minimum, 16 dp outer padding, and 12 dp gaps on compact windows. A normal 360 dp phone shows two covers. Font scale at 1.5 or greater uses one column. Bottom content padding keeps the last notebook above the create action.

Notebook actions use `ModalBottomSheet`. Existing rename, favorite, trash, restore, and delete behavior stays unchanged.

Settings removes the large introduction. Notebook defaults, Pen and touch, Appearance, and App and privacy start collapsed. Each row exposes Button role plus `Expanded` or `Collapsed` state description.

## Page gesture arbitration

`PageGestureArbiter` is a pure state reducer. It owns only finger navigation intent. Stylus and eraser input stays in `InkCanvasView`.

Rules:

- Finger drawing off: one finger can turn a page at zoom 1.
- Finger drawing on: one finger draws. A second finger cancels uncommitted finger ink, then the two-finger centroid can turn a page.
- A meaningful scale change owns the gesture for pinch zoom.
- A zoomed page pans and never turns.
- Horizontal movement must exceed 25 percent of the visible page width and dominate vertical movement by 1.4 times.
- One gesture turns at most one page.
- First and last page consume the completed swipe without mutation.
- `ACTION_CANCEL` never commits ink or turns a page.
- Stylus or hardware eraser ownership blocks finger page turns until the tool interaction ends.

The gesture reducer returns one of `NONE`, `PREVIOUS`, or `NEXT`. The editor calls the existing `selectPreviousPage()` and `selectNextPage()` methods.

## Shape behavior

The existing 450 ms draw-and-hold signal remains. It already converts line, arrow, ellipse, rectangle, and triangle and keeps raw ink as the previous Undo state. The UI calls ellipse **Circle / ellipse**. Quick strokes and ambiguous scribbles remain raw ink.

## Data

`SeliaDocsRepository.setFingerDrawing(notebookId, enabled)` updates the current notebook inside a Room transaction and touches `updatedAt`. Backup/export already carries the notebook flag, so no schema migration is required.

## Verification

Required widths are 360, 600, 840, and 1280 dp. Verify portrait, landscape, 200 percent font scale, dark theme, TalkBack traversal, and API 29 plus API 37. Page gesture tests cover compact happy, edge, cancellation, zoom, finger-drawing, and stylus-blocked paths. Store screenshots are regenerated only after runtime acceptance.
