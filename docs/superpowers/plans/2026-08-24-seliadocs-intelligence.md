# SeliaDocs recognition, smart ink, math, and tables implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional local handwriting recognition, bundled offline OCR, hold-to-shape cleanup, math suggestions with variables and graphs, editable tables, grouping, and layer order.

**Architecture:** ML Kit produces derived search rows and never replaces raw ink or images. Shape and math engines remain deterministic Kotlin code with explicit confidence or parse errors. Room migration 3 to 4 adds tables, table cells, and optional group references. Recognition runs for the current saved page and repairs stale derived rows when Search opens.

**Tech Stack:** Kotlin, ML Kit Digital Ink Recognition 19.0.0, ML Kit Text Recognition 16.0.1, Room 2.8.4, Compose, AndroidX Ink, coroutines

**Spec:** `docs/superpowers/specs/2026-08-24-seliadocs-smart-study-notebook-design.md`

## Global constraints

- Raw strokes, images, and PDF assets remain authoritative.
- Recognition and OCR run on the device.
- English and Czech handwriting packs are the first supported packs.
- OCR for Latin text works without a model download.
- Recognition never inserts or replaces visible content without user confirmation.
- Math mode defaults to Suggest.
- Invalid or ambiguous math never displays a confirmed result.
- Tables are editable data, not images.
- Every source change replaces or removes stale search rows.
- Database migration 3 to 4 preserves all source and search data.

---

### Task 1: Add recognition dependencies and model-pack management

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/majkeylab/seliadocs/recognition/RecognitionLanguage.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/recognition/ModelPackManager.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/AppSettings.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/SettingsScreen.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/recognition/ModelPackManagerTest.kt`

**Interfaces:**
- Consumes: ML Kit `RemoteModelManager` and DataStore.
- Produces: `installed(language)`, `download(language)`, `delete(language)`, and a model state flow.

- [ ] **Step 1: Add exact dependencies**

```kotlin
implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
implementation("com.google.mlkit:text-recognition:16.0.1")
```

Build once and inspect the merged manifest. Record whether either dependency adds `INTERNET`.

- [ ] **Step 2: Define supported languages and state**

```kotlin
internal enum class RecognitionLanguage(val tag: String, val label: String) {
    ENGLISH("en", "English"),
    CZECH("cs", "Czech"),
}

internal sealed interface ModelPackState {
    data object NotInstalled : ModelPackState
    data class Downloading(val progressText: String) : ModelPackState
    data object Installed : ModelPackState
    data class Failed(val message: String) : ModelPackState
}
```

- [ ] **Step 3: Bridge ML Kit Tasks without adding another dependency**

Create one private `Task<T>.awaitResult()` with `suspendCancellableCoroutine`. Resume exactly once and propagate the original exception.

- [ ] **Step 4: Implement model lookup, download, and delete**

Build identifiers from BCP-47 tags. Show approximately 20 MB before download. Do not mark a pack installed until `isModelDownloaded` returns true.

- [ ] **Step 5: Add Recognition and OCR Settings detail**

Show language, status, size note, Download or Delete, local-processing disclosure, handwriting search toggle, and OCR toggle.

- [ ] **Step 6: Run installed, missing, canceled, failed-network, and delete tests**

The network failure scenario must keep note editing available.

- [ ] **Step 7: Commit model packs**

```powershell
git add app/build.gradle.kts app/src/main app/src/androidTest
git commit -m "feat: manage handwriting model packs"
```

### Task 2: Index OCR from imported images

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/recognition/ImageOcrIndexer.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/ImageImporter.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/search/SearchIndexer.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/recognition/ImageOcrIndexerTest.kt`
- Add: `app/src/androidTest/assets/ocr/latin-study-note.png`

**Interfaces:**
- Consumes: validated private image asset, page ID, source element ID, and revision.
- Produces: OCR `SearchDocument` text and page-coordinate regions.

- [ ] **Step 1: Add a real OCR fixture test**

The fixture contains `Organic chemistry 2026` in clear Latin text. Import it, run OCR, and assert the exact phrase and at least one finite region.

- [ ] **Step 2: Map ML Kit image regions into page coordinates**

Account for element position, element scale, image dimensions, crop, and rotation. Reject regions outside the page after transformation.

- [ ] **Step 3: Index OCR only after image import commits**

If OCR fails, keep the image and store no OCR row. A Retry OCR action runs against the existing asset.

- [ ] **Step 4: Remove OCR rows on image deletion or replacement**

- [ ] **Step 5: Test clear image, rotated image, corrupt image, disabled OCR, and deleted image**

- [ ] **Step 6: Commit image OCR**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: index text from imported images"
```

### Task 3: Convert vector strokes into handwriting search text

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/recognition/InkRecognitionMapper.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/recognition/HandwritingIndexer.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/InkCodec.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/recognition/InkRecognitionMapperTest.kt`

**Interfaces:**
- Consumes: ordered `StrokeEntity` rows, page bounds, recognition language, and page revision.
- Produces: top recognition candidates and one `SearchDocument` with a union region.

- [ ] **Step 1: Write deterministic stroke-mapping tests**

Verify point order, stroke boundaries, timestamps, scale, and empty input. The mapper test does not download a model.

- [ ] **Step 2: Build ML Kit `Ink` from decoded stroke inputs**

Preserve source order. Convert stored elapsed times to monotonically increasing milliseconds. If old strokes have no valid times, synthesize increasing times from point order.

- [ ] **Step 3: Implement page recognition**

```kotlin
internal data class RecognitionCandidate(val text: String, val confidenceRank: Int)

suspend fun recognizePage(
    strokes: List<StrokeEntity>,
    language: RecognitionLanguage,
    revision: Long,
): List<RecognitionCandidate>
```

Return an explicit `ModelNotInstalled` failure instead of starting an implicit download.

- [ ] **Step 4: Debounce recognition after a saved page edit**

In `EditorViewModel`, cancel the previous page recognition job and wait one second after the latest committed stroke mutation. Before writing results, verify the page revision still matches.

- [ ] **Step 5: Add Search repair for stale or missing handwriting rows**

When Search opens and handwriting search is enabled, process at most 20 stale pages in the foreground session. Show indexing progress and allow Cancel.

- [ ] **Step 6: Verify disabled, missing-model, stale-revision, cancellation, and installed-model scenarios**

- [ ] **Step 7: Commit handwriting indexing**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: index handwritten page content"
```

### Task 4: Add lasso conversion with candidate correction

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/recognition/ConversionSheet.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/recognition/HandwritingConversionTest.kt`

**Interfaces:**
- Consumes: selected stroke IDs and recognition candidates.
- Produces: Copy as text or Replace with text as one undoable page transaction.

- [ ] **Step 1: Write tests that preserve raw ink by default**

Open Convert, select another candidate, cancel, and assert the strokes remain. Copy as text also preserves strokes. Replace deletes selected strokes and inserts one normal paragraph block at the selection's reading position in one history step. It must not create a positioned text box.

- [ ] **Step 2: Add Convert to the lasso action menu**

Disable the action when no model is installed. The disabled label explains which pack is needed.

- [ ] **Step 3: Build the candidate sheet**

Show interpreted text, up to five candidates, Copy, Replace, and Cancel. Do not label confidence as a percentage because ML Kit exposes ordered candidates, not calibrated probability.

- [ ] **Step 4: Run cancel, copy, replace, undo, redo, and missing-pack tests**

- [ ] **Step 5: Commit conversion**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: convert selected handwriting"
```

### Task 5: Recognize held shapes without changing ambiguous ink

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/ShapeCleanup.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/InkCanvasView.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Modify: `app/src/test/java/com/majkeylab/seliadocs/editor/LineRecognizerTest.kt`
- Create: `app/src/test/java/com/majkeylab/seliadocs/editor/ShapeRecognizerTest.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/HoldShapeFlowTest.kt`

**Interfaces:**
- Consumes: the last completed stroke or selected stroke set.
- Produces: `ShapeMatch(kind, box, rotation, score)` or no match.

- [ ] **Step 1: Add shape fixture tests**

Test noisy line, arrow, circle, ellipse, rectangle, square, triangle, open scribble, letter, and dot. Every fixture states the expected shape or `null`.

- [ ] **Step 2: Implement normalized scoring**

```kotlin
internal data class ShapeMatch(
    val kind: ShapeKind,
    val box: ShapeBox,
    val rotation: Float,
    val score: Float,
)
```

Normalize points into the selection box. Score closure, segment count, angle consistency, radial variance, and maximum line deviation. Accept only scores at or above one named constant.

- [ ] **Step 3: Detect the hold gesture**

After the stylus stops moving within a small radius for the configured delay, finalize the stroke and request recognition. Movement or cancellation aborts the hold.

- [ ] **Step 4: Replace only confident matches in one transaction**

Keep the raw stroke for low scores. A short Undo hint appears after replacement.

- [ ] **Step 5: Run shape, palm cancellation, normal writing, and reduced-motion tests**

- [ ] **Step 6: Commit held shapes**

```powershell
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat: clean held ink shapes"
```

### Task 6: Extend deterministic math and add graph elements

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/MathEvaluator.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/MathEnvironment.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/MathGraph.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PageCanvas.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/AppSettings.kt`
- Modify: math tests

**Interfaces:**
- Consumes: typed or confirmed linear expression and earlier page assignments.
- Produces: `MathResult.Value`, `MathResult.Assignment`, `MathResult.Graph`, or typed failure.

- [ ] **Step 1: Add parser tests before changing implementation**

Cover arithmetic, percentages, powers, unary signs, `sqrt`, `sin`, `cos`, variable assignment, variable use, undefined variable, division by zero, malformed input, recursion limit, and unsupported matrices.

- [ ] **Step 2: Replace `Result<Double>` with typed results**

```kotlin
internal sealed interface MathResult {
    data class Value(val value: Double) : MathResult
    data class Assignment(val name: String, val value: Double) : MathResult
    data class Graph(val expression: String, val points: List<CanvasPoint>) : MathResult
}
```

Keep parser depth and token count bounded.

- [ ] **Step 3: Build the environment in page reading order**

Read math elements from top to bottom, then left to right. Ignore an assignment after the current expression. An undefined variable returns its name.

- [ ] **Step 4: Add Off, Suggest, and Insert settings**

Suggest shows a compact result control after a typed or confirmed expression ending in `=`. Insert adds the result only after tap. Off performs no evaluation.

- [ ] **Step 5: Render supported graphs**

Sample a bounded x range into at most 512 points. Split discontinuities instead of drawing vertical joins. Store the expression, not sampled points, in the element.

- [ ] **Step 6: Run parser, graph, ambiguity, and PDF export tests**

- [ ] **Step 7: Commit math**

```powershell
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat: add variable math and graphs"
```

### Task 7: Add table schema through migration 3 to 4

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/Entities.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsDatabase.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/PageDao.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/data/Migration3To4Test.kt`
- Update: Room schema `4.json`

**Interfaces:**
- Consumes: schema 3.
- Produces: `TableEntity`, `TableCellEntity`, `groupId` on strokes and elements, and `tableId` on elements.

- [ ] **Step 1: Write migration and table-integrity tests**

Assert source and search rows survive. Assert one table can store merged cells without overlapping invalid spans.

- [ ] **Step 2: Define normalized table entities**

```kotlin
@Entity(tableName = "tables")
internal data class TableEntity(
    @PrimaryKey val id: String,
    val rows: Int,
    val columns: Int,
    val borderColorArgb: Int,
    val revision: Long,
)

@Entity(tableName = "table_cells", primaryKeys = ["tableId", "row", "column"])
internal data class TableCellEntity(
    val tableId: String,
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
    val text: String,
    val backgroundArgb: Int?,
)
```

- [ ] **Step 3: Add migration SQL and validation**

Add `groupId` to strokes and elements and `tableId` to elements. Create table and cell tables with indices. Register migration 3 to 4.

- [ ] **Step 4: Run migration and DAO tests**

- [ ] **Step 5: Commit schema 4**

```powershell
git add app/src/main app/src/androidTest app/schemas
git commit -m "feat: add editable table schema"
```

### Task 8: Build table editing and grid conversion

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/table/TableEditor.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/table/TableRenderer.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/table/GridRecognizer.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PageCanvas.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/flow/FlowEditor.kt`
- Create: table unit and instrumentation tests

**Interfaces:**
- Consumes: table entities and selected straight strokes.
- Produces: editable canvas table elements and flow table blocks.

- [ ] **Step 1: Write grid-recognition and edit tests**

Recognize a 3 by 4 grid with noisy straight lines. Reject incomplete, crossing, and non-grid selections. Test cell edit, insert row, insert column, delete, resize, and valid merge.

- [ ] **Step 2: Implement direct Insert Table**

Start with 3 by 3. Limit initial UI to 20 by 20. Larger imported tables remain readable but the editor does not allocate all cell editors at once.

- [ ] **Step 3: Implement table renderer and editor**

Render borders and text from normalized cells. Open one cell editor at a time. Save on IME commit or focus loss.

- [ ] **Step 4: Convert selected grid strokes after confirmation**

Show recognized dimensions and Cancel or Convert. Conversion deletes source grid strokes and inserts the table in one undo step.

- [ ] **Step 5: Index table text and render tables in PDF**

- [ ] **Step 6: Commit tables**

```powershell
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat: add editable tables"
```

### Task 9: Add grouping and layer order

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/GroupingTest.kt`

**Interfaces:**
- Consumes: selected stroke and element IDs.
- Produces: group, ungroup, bring forward, and send backward transactions.

- [ ] **Step 1: Write mixed ink-element grouping tests**

Group two strokes and one image. Move the group, undo, ungroup, and assert source IDs remain stable.

- [ ] **Step 2: Add repository transactions**

```kotlin
suspend fun setGroup(pageId: String, strokeIds: Set<String>, elementIds: Set<String>, groupId: String?)
suspend fun moveLayer(pageId: String, elementId: String, direction: LayerDirection)
```

Normalize z-indices after layer changes.

- [ ] **Step 3: Add selection actions and bounded movement**

- [ ] **Step 4: Run group, layer, undo, backup, and PDF regressions**

- [ ] **Step 5: Commit grouping**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: group and order page content"
```

### Task 10: Extend backup, privacy, and complete acceptance

**Files:**
- Modify: backup codec, exporter, importer, and tests
- Modify: `PRIVACY.md`
- Modify: `site/privacy/index.html`
- Modify: `docs/play-store/DATA_SAFETY.md`
- Modify: `docs/play-store/STORE_LISTING.md`
- Create: `docs/qa/2026-08-24-intelligence-acceptance.md`

**Interfaces:**
- Consumes: all intelligence package source entities and SDK behavior.
- Produces: schema 4 backup compatibility, current privacy disclosure, and emulator evidence.

- [ ] **Step 1: Add table and group records to backup format 1**

Use optional `tables.jsonl` and `table-cells.jsonl` with feature flags. Do not archive search or OCR rows.

- [ ] **Step 2: Update privacy from the merged manifest evidence**

If `INTERNET` exists, disclose model download traffic and keep note-content processing local. If it does not exist, retain the no-network-permission claim and document how model delivery works.

- [ ] **Step 3: Run offline OCR and installed handwriting scenarios**

Verify airplane-mode OCR, model-missing handwriting, installed English and Czech packs, stale revision cancellation, and model deletion.

- [ ] **Step 4: Run full API 29 and API 37 acceptance**

Include normal writing, shapes, math, graphs, tables, search, backup, restore, PDF export, Back, and reduced motion.

- [ ] **Step 5: Commit evidence and disclosures**

```powershell
git add app/src/main app/src/androidTest PRIVACY.md site/privacy docs/play-store docs/qa
git commit -m "docs: verify local note intelligence"
```
