# SeliaSheets ink, media, and brush controls design

## Goal

Make zoomed stylus input land under the pen, make imported images immediately editable, and replace fixed brush widths with a continuous professional control while preserving the existing notebook-first editor.

## Confirmed defects

- `Paper` scales an `AndroidView` subtree with `graphicsLayer`. After a live pinch, the page is painted at the new transform but the platform view's root hit region is stale, so root stylus events do not reach `InkCanvasView`.
- A page composed initially at 2x accepts a center stylus event, which hid the defect. A real pinch followed by a root-dispatched stylus event reproduces the dropped stroke.
- `importImage` ignores the ID returned by `repository.addElement`; the imported image is centered but not selected and the editor stays in the previous tool.
- Move, resize, rotate, undo, and direct tap hit-testing already exist. They should be exposed after import, not rebuilt.
- The editor palette offers only three hardcoded width buttons although Settings already uses a continuous slider.
- Pen strokes already use AndroidX Ink `StockBrushes.pressurePen`, and stroke persistence already stores pressure, tilt, and orientation. Coverage does not vary pressure within a stroke.

## Design

### Zoomed ink

`PageCanvas` replaces visual-only `graphicsLayer` scaling with `requiredWidth` and `requiredHeight` plus placement offset. Required dimensions may exceed the viewport constraints, so the Android view owns the complete visible paper hit region after every pinch. Its existing view-size-to-page transform remains the single owner of MotionEvent-to-page conversion for pen, eraser, and lasso input.

Regression coverage uses the real Compose `PageCanvas`, a transformed page, and root-dispatched stylus events. It covers 1x, 2x, and 4x zoom with pan and verifies that strokes are committed at the visible paper point.

### Imported images

`importImage` keeps the returned element ID. After the repository write succeeds, controls switch to `EditorTool.LASSO`, select that ID, and clear any stroke selection. The existing overlay then appears immediately with move, resize, and rotate handles.

Tap-to-reselect continues through the existing short-lasso hit test. Transform persistence, bounds, undo, and redo continue through `updateSelectedElement` and `PageHistory`.

### Brush width and pressure

The hardcoded width buttons are replaced by one compact Material slider with a live line sample. Shared ranges prevent Settings and the editor from drifting:

- pen and pencil: 1 through 32 page points;
- highlighter: 4 through 64 page points.

The slider previews continuously and persists once when the drag finishes. Existing color swatches and Smart shapes remain unchanged.

The stable AndroidX Ink pressure pen remains the rendering engine. Tests feed low and high pressure through real `MotionEvent` objects and verify the finished stroke preserves both values while using the pressure-pen family. No alpha Ink dependency or second drawing engine is added.

### Physical QA isolation

Debug builds use `com.majkeylab.seliadocs.debug`. This permits instrumentation on the approved Huawei without replacing the Play-signed `com.majkeylab.seliadocs` package or touching user data. The debug package is removed after QA.

The Huawei reports no connected active stylus. It can verify zoom transforms with synthetic stylus events and live finger/UI flows, but real hardware pressure requires a pressure-capable device.

## Non-goals

- No raster layer engine, brush marketplace, custom alpha Ink brush graph, or UI copy from another product.
- No Room migration, backup format change, network permission, analytics, or Play Data Safety change.
- No replacement of the existing element overlay, history, or selection geometry.

## Acceptance

- Pen, eraser, and lasso coordinates stay correct at 1x, 2x, and 4x zoom with pan.
- Zoomed stylus input commits a stroke instead of dropping it.
- A newly imported image shows selection handles immediately.
- The image can be moved, resized, rotated, undone, redone, and reselected after reopening.
- Width controls cover the complete configured ranges and show a live sample.
- Finished pressure-pen input preserves distinct low and high pressure samples.
- Debug instrumentation runs beside the Play app on the Huawei, then the debug package is removed.
