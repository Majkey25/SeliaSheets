# Presentation fixture

`lecture.pptx` contains original SeliaSheets test content: two slides with text, a table, a chart, and the project's launcher artwork. Generated with PptxGenJS 4.0.1 (MIT), not copied from a third-party presentation. No Office fonts are embedded.

The fixture exercises ordinary Office package structure, including unused media MIME declarations, slide masters/layouts, notes, and an embedded chart-data workbook. Tests verify rendering, not PowerPoint editing or exact font fidelity.

`stress.pptx` contains 100 original slides with a title, body text, launcher artwork, and alternating green/blue shapes. It tests the slide-count limit and detects blank or reordered pages during conversion.
