# Notes and drawing capabilities

Checked September 19, 2026 against official product documentation. This is a capability comparison, not a verified popularity ranking or hands-on certification of the other apps.

| Reference | Workflows worth implementing | Platform or behavior limit |
| --- | --- | --- |
| [Flexcil](https://support.flexcil.com/hc/en-us/articles/8131728266393-Delete-pages-add-a-new-page-and-move-page-s-to-another-document) | Insert/move selected pages, PDF study, linked excerpts, multi-page reading, masks and synchronized audio | Deletion and transfer must retain SeliaSheets' recovery safeguards. |
| [Samsung Notes](https://www.samsung.com/us/support/answer/ANS10003634/) | Handwriting conversion/alignment, lasso, PDF annotation, configurable pens | S Pen, device and language support vary. [Maths Solver](https://www.samsung.com/in/support/mobile-devices/how-to-use-maths-solver-feature-in-notes-on-galaxy-devices/) evaluates supported handwritten expressions ending with `=`; recognition can be wrong. |
| [Apple Notes](https://support.apple.com/en-euro/guide/ipad/ipad10f28bec/ipados) | Inline expressions, variables, graphs, handwriting editing and audio | Apple hardware/language gates apply. This is not a portable Android recognition engine. |
| [Goodnotes](https://support.goodnotes.com/hc/en-us/articles/7922907940367-Frequently-requested-features-for-Android-Windows-and-Web) | Notebook tabs, page reorder, handwriting search/conversion, planner links | Android does not have every iPad feature. Requests for flashcards or multi-window drag/drop are not evidence of shipped Android support. |
| [MyScript Notes](https://www.myscript.com/notes/) | Mixed handwriting/typing, gesture editing, searchable ink, diagrams, responsive documents | Math objects are distinct from universal automatic recognition and from the separate MyScript Math product. |
| [Notability](https://support.gingerlabs.com/hc/en-us/articles/5955260981786-Settings-Appearances-Tools-Gestures) | Configurable toolbox, ruler, hold-to-shape, tape recall, audio-linked replay | [Android availability](https://intercom.help/notability/en-us/articles/16300000-notability-for-android-faq) differs from iOS, including backup and text-only mode. |
| [OneNote](https://support.microsoft.com/en-us/onenote/take-handwritten-notes-in-onenote-for-android) | Lasso move/resize, ruled/grid paper, finger navigation and collapsible tools | Its documented Math Assistant uses selection and a Math panel on supported platforms, not automatic Android math everywhere. |
| [Sketchbook](https://www.sketchbook.com/apps) | Brush library, layers, blend modes, guides and stroke stabilization | Stabilization adds deliberate lag/offset. Pen pressure and tilt depend on platform/device support. |
| [Concepts](https://concepts.app/en/manual/precision-tools) | Editable vector strokes, layers, snapping, measurement and shape guides | [Export formats](https://concepts.app/en/manual/export) have different fidelity limits. |
| [Infinite Painter](https://docs.infinitestudio.art/painter/layers/) | Layer visibility/locking/opacity, clipping, custom brushes, fill and perspective | These require a real compositor and persistent layer model, not additional toolbar icons. |

## Current increment

- **Page insertion:** separate note-page and PDF-slide actions, inserted after the selected page. Preserve source dimensions and chapter. Index changes and imports share a transaction.
- **Name fields:** use state-based Material text fields for chapter/page/notebook names. Chapter input now matches its 120-character storage limit. The chapter/page dialog establishes focus and supports IME Done.
- **Drawing controls:** custom RGB/hex colors, light/dark preview, pen/pencil opacity, retained opacity when changing presets, and opaque contrast-checked tool icons.

These are implemented changes awaiting integrated device and CI acceptance, not a claim of full competitor parity. [Android's handwriting contract](https://developer.android.com/develop/ui/compose/touch-input/stylus-input/stylus-input-in-text-fields) requires Android 14+, a compatible pen, and a handwriting-capable keyboard for direct Compose field handwriting. Huawei Android 10 can verify text input and app behavior but cannot certify that hardware path.

## Remaining implementation groups

1. Persistent graphics layers: reorder, rename, visibility, lock, opacity, selection, export and backup round trips.
2. Saved brush presets, pressure-response profiles, measured stabilization, rulers and symmetry/perspective guides.
3. Study masks, reveal/reset and progress; audio-linked notes with permission, interruption and crash recovery.
4. Rich text, tables, diagram connectors, recognition correction, then verified two-dimensional math and graphs.
5. PDF outlines, annotation filters, editable exports, and page-range import.
6. Movable/resizable second windows and workspace restoration after process death.
7. Optional provider-backed backup/sync with conflict handling and explicit user-controlled transfers.

The existing [parity specification](../superpowers/specs/2026-09-12-flexcil-parity.md) remains the broader checklist. Pen hardware accuracy, data recovery, accessibility, and export fidelity remain acceptance requirements for each increment.
