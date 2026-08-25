# SeliaDocs PDF materials and study implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add PDF-backed notebook pages, vector-preserving annotation export, reference links, navigation history, masking tape, flashcards, and local spaced review.

**Architecture:** The original PDF remains a private material asset. Android `PdfRenderer` renders visible tiles on API 29 through 37. `pdfbox-android:2.0.27.0` appends vector annotations to copies of original pages during export. Room migration 4 to 5 adds materials, reference links, masks, flashcards, and review state. Study tools point to existing page regions and never duplicate source notes.

**Tech Stack:** Kotlin, Room 2.8.4, Android `PdfRenderer`, `com.tom-roush:pdfbox-android:2.0.27.0`, Compose, AndroidX Ink, Storage Access Framework

**Spec:** `docs/superpowers/specs/2026-08-24-seliadocs-smart-study-notebook-design.md`

## Global constraints

- Android support: API 29 through API 37.
- Original PDFs remain unchanged in private storage.
- PDF annotations remain separate editable SeliaDocs data.
- Export preserves original PDF vector content and adds vector ink and shapes.
- Rendering and PDFBox work run off the main thread.
- Visible PDF tiles and decoded bitmaps use bounded caches.
- Study masks and flashcards reference source page regions.
- Audio-linked notes remain excluded from this plan.
- Every source entity must round-trip through `.seliadocs` backup.

---

### Task 1: Add materials and study schema through migration 4 to 5

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/Entities.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsDatabase.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/materials/MaterialDao.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/study/StudyDao.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/data/Migration4To5Test.kt`
- Update: Room schema `5.json`

**Interfaces:**
- Consumes: schema 4.
- Produces: materials, reference links, masks, flashcards, reviews, and PDF page references.

- [ ] **Step 1: Write a failing migration test**

Create schema 4 content with search rows, table cells, groups, and backup-compatible source data. Migrate to 5 and assert every count and byte array survives.

- [ ] **Step 2: Add material and reference entities**

```kotlin
@Entity(tableName = "materials", indices = [Index("notebookId"), Index("assetId", unique = true)])
internal data class MaterialEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val assetId: String,
    val title: String,
    val mimeType: String,
    val pageCount: Int,
    val createdAt: Long,
)

@Entity(tableName = "reference_links", indices = [Index("sourcePageId"), Index("targetPageId")])
internal data class ReferenceLinkEntity(
    @PrimaryKey val id: String,
    val sourcePageId: String,
    val sourceLeft: Float,
    val sourceTop: Float,
    val sourceRight: Float,
    val sourceBottom: Float,
    val targetPageId: String,
    val targetLeft: Float?,
    val targetTop: Float?,
)
```

Add `materialId` and `materialPageIndex` to `PageEntity`.

- [ ] **Step 3: Add study entities**

```kotlin
@Entity(tableName = "study_masks", indices = [Index("pageId")])
internal data class StudyMaskEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String?,
)

@Entity(tableName = "flashcards", indices = [Index("notebookId"), Index("chapterId")])
internal data class FlashcardEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val chapterId: String?,
    @Embedded(prefix = "question_") val question: PageRegion,
    @Embedded(prefix = "answer_") val answer: PageRegion,
    val repetitions: Int,
    val intervalDays: Int,
    val easeFactor: Double,
    val dueAt: Long,
)

internal data class PageRegion(
    val pageId: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

@Entity(tableName = "reviews", indices = [Index("flashcardId"), Index("reviewedAt")])
internal data class ReviewEntity(
    @PrimaryKey val id: String,
    val flashcardId: String,
    val quality: Int,
    val reviewedAt: Long,
    val nextDueAt: Long,
)
```

- [ ] **Step 4: Implement and register migration 4 to 5**

Create new tables and indices. Add nullable material columns to pages. Validate finite normalized regions at repository boundaries.

- [ ] **Step 5: Run migration and schema validation**

- [ ] **Step 6: Commit schema 5**

```powershell
git add app/src/main app/src/androidTest app/schemas
git commit -m "feat: add materials and study schema"
```

### Task 2: Import and map PDF materials

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/majkeylab/seliadocs/materials/PdfImporter.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/materials/MaterialRepository.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/AssetStore.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/contents/ContentsPane.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/materials/PdfImporterTest.kt`
- Add: `app/src/androidTest/assets/pdf/vector-study-material.pdf`
- Add: `app/src/androidTest/assets/pdf/corrupt.pdf`

**Interfaces:**
- Consumes: a user-selected PDF URI and target notebook or chapter.
- Produces: one `MaterialEntity` and ordered `PDF` pages linked to original page indices.

- [ ] **Step 1: Add the vector export dependency**

```kotlin
implementation("com.tom-roush:pdfbox-android:2.0.27.0")
```

Initialize `PDFBoxResourceLoader` once in `MainActivity.onCreate` before PDFBox use. Update `THIRD_PARTY_NOTICES.md` with the Apache-2.0 dependency.

- [ ] **Step 2: Write import tests**

Import a three-page vector PDF. Assert MIME validation, page count, title, page order, source asset hash, `PDF` mode, and page indices. A corrupt PDF must leave no material, page, or private asset.

- [ ] **Step 3: Validate and copy through SAF**

Accept only `%PDF-` input with MIME `application/pdf`. Stream to private staging, cap the source at 1 GiB, verify that both platform `PdfRenderer` and PDFBox can open it, then move it into content-addressed asset storage.

- [ ] **Step 4: Create material pages in one transaction**

Assign the target chapter, preserve original dimensions in points, and use contiguous notebook order.

- [ ] **Step 5: Add Import PDF to Insert and notebook actions**

Use `ActivityResultContracts.OpenDocument` with `application/pdf`. Show target notebook and chapter before import.

- [ ] **Step 6: Run valid, corrupt, oversized, canceled, landscape, and mixed-page-size tests**

- [ ] **Step 7: Commit PDF import**

```powershell
git add app/build.gradle.kts app/src/main app/src/androidTest THIRD_PARTY_NOTICES.md
git commit -m "feat: import PDF materials"
```

### Task 3: Render PDF pages with bounded tiles

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/materials/PdfPageRenderer.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/materials/PdfTileCache.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PageCanvas.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/materials/PdfRenderingTest.kt`

**Interfaces:**
- Consumes: material asset, page index, viewport, zoom, and render revision.
- Produces: visible bitmap tiles and page text metadata when available.

- [ ] **Step 1: Write render and cache tests**

Render page 1 at Fit page and two zoomed tiles. Assert dimensions, nonblank pixels, cache keys, eviction, and page release.

- [ ] **Step 2: Implement one renderer owner per open document**

Open `ParcelFileDescriptor` and `PdfRenderer` on `Dispatchers.IO`. Serialize page open, render, and close operations because one platform renderer cannot keep multiple pages open safely.

- [ ] **Step 3: Implement tile keys and limits**

Key by material ID, page index, zoom bucket, tile x, tile y, and asset revision. Keep at most 96 MiB of bitmap tiles. Recycle or release every evicted bitmap.

- [ ] **Step 4: Draw the PDF background before annotation layers**

Reuse existing page-coordinate transforms so ink, elements, search regions, masks, and links align at every zoom.

- [ ] **Step 5: Run API 29 and API 37 zoom, page-switch, rotation, and memory scenarios**

- [ ] **Step 6: Commit rendering**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: render PDF notebook pages"
```

### Task 4: Export PDF materials with vector annotations

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/materials/AnnotatedPdfExporter.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PdfExporter.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/PdfExporterTest.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/materials/AnnotatedPdfExporterTest.kt`

**Interfaces:**
- Consumes: original PDF, source page mapping, strokes, elements, tables, math, masks visibility choice, and output stream.
- Produces: a PDF that retains source text and vector paths and appends SeliaDocs annotations.

- [ ] **Step 1: Write a vector-preservation test**

Export one annotated source page. Reopen it with PDFBox. Assert source text remains extractable and annotation strokes exist as page content operators, not a full-page bitmap.

- [ ] **Step 2: Load a private working copy with PDFBox**

Use `PDDocument.load(file)`. For each source page, append through `PDPageContentStream` with `AppendMode.APPEND`, compression enabled, and graphics-state reset enabled.

- [ ] **Step 3: Convert page coordinates to PDF coordinates**

Flip the Y axis and scale from SeliaDocs points to the source page media box. Emit ink with `moveTo`, `lineTo`, line width, color, and `stroke`. Emit shapes and table borders as paths. Add text with embedded fonts already used by the exporter.

- [ ] **Step 4: Save to a temporary file and stream to the caller output**

Do not load the output into a byte array. Delete the temporary file after success or failure.

- [ ] **Step 5: Test vector text, ink, image, shape, math, table, mixed page size, and corrupt source**

- [ ] **Step 6: Commit vector annotation export**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: export vector PDF annotations"
```

### Task 5: Add PDF search and OCR integration

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/materials/PdfTextIndexer.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/recognition/ImageOcrIndexer.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/search/SearchIndexer.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/materials/PdfSearchTest.kt`

**Interfaces:**
- Consumes: PDF text layer or rendered OCR bitmap.
- Produces: `PDF_TEXT` or `PDF_OCR` search documents with page regions.

- [ ] **Step 1: Add text-layer and image-only PDF fixtures**

- [ ] **Step 2: Extract source text with PDFBox off the main thread**

Index one page at a time. Preserve page association. If the text layer is blank and OCR is enabled, render an OCR-sized bitmap and call the existing OCR indexer.

- [ ] **Step 3: Cancel stale indexing when the material asset changes or disappears**

- [ ] **Step 4: Run text, OCR, disabled OCR, delete, restore, and scoped-search tests**

- [ ] **Step 5: Commit PDF search**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: search imported PDF materials"
```

### Task 6: Add reference links and navigation history

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/materials/ReferenceLinkOverlay.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/navigation/PageNavigationHistory.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Create: unit and instrumentation tests

**Interfaces:**
- Consumes: selected source region and target page or target page region.
- Produces: reference link entities and bounded Back and Forward history.

- [ ] **Step 1: Write history and broken-link tests**

Navigate A to B to C, go Back twice, Forward once, delete B, and assert history skips the missing destination. Limit history to 100 entries.

- [ ] **Step 2: Add Create reference to the selection menu**

Open a target picker scoped to the current notebook by default. Store normalized source and target regions.

- [ ] **Step 3: Draw and activate reference affordances**

Show a small link marker, not a full region overlay. A stylus tap follows the link only when the current tool is Pan or Select.

- [ ] **Step 4: Add Back and Forward controls to the app bar when history exists**

- [ ] **Step 5: Run paper, flow, PDF, broken target, backup, and restore tests**

- [ ] **Step 6: Commit references**

```powershell
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat: link notebook references"
```

### Task 7: Add masking tape and study mode

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/study/StudyModeScreen.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/study/MaskOverlay.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/study/MaskingTapeTest.kt`

**Interfaces:**
- Consumes: selected region and `StudyMaskEntity` rows.
- Produces: hidden regions that reveal on tap and record Right or Review without changing page content.

- [ ] **Step 1: Write mask persistence and non-destructive tests**

Create a mask over handwriting, reveal it, leave Study mode, and assert strokes remain unchanged and the mask remains.

- [ ] **Step 2: Add Create mask to selection and Insert**

Clamp regions to page bounds. Require positive area. Give each mask an optional label.

- [ ] **Step 3: Build Study mode**

Show one page at a time with masks opaque. Tap reveals one mask. Right and Review advance to the next mask and update session counts only.

- [ ] **Step 4: Test overlapping masks, delete, page navigation, reduced motion, and PDF pages**

- [ ] **Step 5: Commit masking tape**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: add masking tape study mode"
```

### Task 8: Add flashcards and SM-2 review scheduling

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/study/FlashcardRepository.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/study/Sm2Scheduler.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/study/FlashcardStudyScreen.kt`
- Create: scheduler unit tests and flashcard instrumentation tests

**Interfaces:**
- Consumes: question region, answer region, notebook, optional chapter, and review quality.
- Produces: due cards and deterministic next-review state.

- [ ] **Step 1: Write SM-2 scheduler tests**

Map Again to quality 1, Hard to 3, Good to 4, and Easy to 5. Use these rules:

```text
quality < 3: repetitions = 0, intervalDays = 1
first successful review: intervalDays = 1
second successful review: intervalDays = 6
later successful review: intervalDays = round(previousInterval * easeFactor)
easeFactor = max(1.3, easeFactor + 0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))
```

- [ ] **Step 2: Add repository methods**

```kotlin
suspend fun createCard(question: PageRegion, answer: PageRegion, chapterId: String?): String
fun observeDueCards(notebookId: String?, now: Long): Flow<List<FlashcardEntity>>
suspend fun recordReview(cardId: String, quality: Int, reviewedAt: Long)
```

- [ ] **Step 3: Add Create flashcard to selection**

Capture question first, then answer. Show both previews before Save.

- [ ] **Step 4: Build the review screen**

Show question, Reveal answer, Again, Hard, Good, and Easy. Display due count and chapter filter. Do not show fake learning scores.

- [ ] **Step 5: Test same-page, cross-page, deleted source, chapter filter, due order, and process restart**

- [ ] **Step 6: Commit flashcards**

```powershell
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat: add local flashcard review"
```

### Task 9: Extend backup and run materials-study acceptance

**Files:**
- Modify: backup models, exporter, importer, and tests
- Modify: `README.md`
- Modify: `PRIVACY.md`
- Modify: store listing and data safety documents
- Create: `docs/qa/2026-08-24-materials-study-acceptance.md`

**Interfaces:**
- Consumes: schema 5 entities and PDF assets.
- Produces: complete editable archive compatibility and release evidence.

- [ ] **Step 1: Add optional material, reference, mask, flashcard, and review records**

Archive original PDF assets by hash. Preserve study scheduling state. Rebuild PDF text and OCR search after restore.

- [ ] **Step 2: Run a complete vector PDF round trip**

Import, annotate, back up, restore to a fresh emulator, export, and verify extractable source text and vector annotation paths.

- [ ] **Step 3: Run complete API 29 and API 37 instrumentation**

Include PDF render, zoom, search, OCR, reference links, masking, flashcards, backup, restore, and normal notebook regression.

- [ ] **Step 4: Profile PDF memory and long study sessions**

Inspect tile cache size and `dumpsys meminfo` before and after 50 page changes. Verify bitmaps release after leaving the material.

- [ ] **Step 5: Update public documentation and commit evidence**

```powershell
git add app/src/main app/src/androidTest README.md PRIVACY.md docs site/privacy
git commit -m "docs: verify PDF materials and study tools"
```
