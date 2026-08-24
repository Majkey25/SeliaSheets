# SeliaDocs organization and search implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add chapters, page titles, bookmarks, paper and flow page modes, adaptive Contents navigation, Quick Note, Inbox, and local scoped full-text search.

**Architecture:** Room migration 1 to 2 adds chapters, page metadata, and ordered flow blocks. Room migration 2 to 3 adds source metadata and FTS4 text. The existing notebook remains the root container, Search remains derived data, and tablet and phone layouts share the same Contents and search models.

**Tech Stack:** Kotlin, Room 2.8.4, Jetpack Compose, DataStore, AndroidX Activity, existing PDF exporter and backup codec

**Spec:** `docs/superpowers/specs/2026-08-24-seliadocs-smart-study-notebook-design.md`

## Global constraints

- Application ID: `com.majkeylab.seliadocs`.
- Android support: API 29 through API 37.
- Use one chapter level only.
- Unfiled pages remain valid.
- Existing v1 notebooks and pages must survive migration.
- Do not use destructive Room migration.
- `PAPER` stays the default page mode.
- `FLOW` supports long typed notes without becoming a free canvas.
- Every new source entity must round-trip through `.seliadocs` backup.

---

### Task 1: Add Room migration 1 to 2

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/Entities.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsDatabase.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/data/Migration1To2Test.kt`
- Update: `app/schemas/com.majkeylab.seliadocs.data.SeliaDocsDatabase/2.json`

**Interfaces:**
- Consumes: database version 1.
- Produces: `ChapterEntity`, `BlockEntity`, `PageMode`, and database version 2.

- [ ] **Step 1: Write the failing migration test**

Create a version 1 database with one notebook, two pages, one stroke, and one text element. Run migration 1 to 2. Assert that all source rows survive and both pages use `PAPER` with no chapter.

```kotlin
assertEquals(PageMode.PAPER.name, migratedPage.pageMode)
assertNull(migratedPage.chapterId)
assertFalse(migratedPage.bookmarked)
```

- [ ] **Step 2: Add the new entities and enums**

```kotlin
internal enum class PageMode { PAPER, FLOW, PDF }

@Entity(
    tableName = "chapters",
    indices = [Index("notebookId"), Index(value = ["notebookId", "orderIndex"], unique = true)],
)
internal data class ChapterEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val title: String,
    val colorArgb: Int,
    val orderIndex: Int,
)

@Entity(
    tableName = "blocks",
    indices = [Index("pageId"), Index(value = ["pageId", "orderIndex"], unique = true)],
)
internal data class BlockEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val orderIndex: Int,
    val kind: String,
    val text: String?,
    val checked: Boolean,
    val payloadId: String?,
)
```

Add `chapterId`, `title`, `pageMode`, `bookmarked`, `createdAt`, and `updatedAt` to `PageEntity`. Index `chapterId`. Repository code validates that a chapter belongs to the same notebook as its page.

- [ ] **Step 3: Implement migration 1 to 2**

Execute these operations in order:

```sql
CREATE TABLE IF NOT EXISTS chapters (...);
ALTER TABLE pages ADD COLUMN chapterId TEXT;
ALTER TABLE pages ADD COLUMN title TEXT;
ALTER TABLE pages ADD COLUMN pageMode TEXT NOT NULL DEFAULT 'PAPER';
ALTER TABLE pages ADD COLUMN bookmarked INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pages ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pages ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0;
UPDATE pages SET createdAt = COALESCE((SELECT createdAt FROM notebooks WHERE notebooks.id = pages.notebookId), 0);
UPDATE pages SET updatedAt = COALESCE((SELECT updatedAt FROM notebooks WHERE notebooks.id = pages.notebookId), 0);
CREATE INDEX IF NOT EXISTS index_pages_chapterId ON pages(chapterId);
CREATE TABLE IF NOT EXISTS blocks (...);
```

Use the exact columns and foreign keys from the entity schema in the final migration strings.

- [ ] **Step 4: Register `MIGRATION_1_2` and increment the Room version**

Add the migration to the single database builder. Do not add `fallbackToDestructiveMigration`.

- [ ] **Step 5: Run migration, repository, unit, and lint checks**

Expected: Room validates schema 2 and all v1 content survives.

- [ ] **Step 6: Commit the schema**

```powershell
git add app/src/main app/src/androidTest app/schemas
git commit -m "feat: add chapters and flow page schema"
```

### Task 2: Add chapter and page metadata operations

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/NotebookDao.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/PageDao.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/data/ChapterRepositoryTest.kt`

**Interfaces:**
- Consumes: schema 2 entities.
- Produces: chapter CRUD and page title, bookmark, chapter assignment, page-mode, and ordering methods.

- [ ] **Step 1: Write repository tests**

Cover chapter creation, unique order, page assignment, cross-notebook rejection, chapter reorder, page move between chapters, title normalization, bookmark toggle, and deletion that moves pages to Unfiled.

- [ ] **Step 2: Add chapter DAO queries**

```kotlin
@Query("SELECT * FROM chapters WHERE notebookId = :notebookId ORDER BY orderIndex")
fun observeChapters(notebookId: String): Flow<List<ChapterEntity>>

@Query("UPDATE pages SET chapterId = :chapterId, updatedAt = :updatedAt WHERE id = :pageId")
suspend fun assignPageToChapter(pageId: String, chapterId: String?, updatedAt: Long)
```

- [ ] **Step 3: Add repository methods**

```kotlin
suspend fun createChapter(notebookId: String, title: String, colorArgb: Int): String
suspend fun renameChapter(id: String, title: String)
suspend fun moveChapter(notebookId: String, fromIndex: Int, toIndex: Int)
suspend fun deleteChapter(id: String, deletePages: Boolean)
suspend fun assignPageToChapter(pageId: String, chapterId: String?)
suspend fun renamePage(pageId: String, title: String?)
suspend fun setPageBookmarked(pageId: String, bookmarked: Boolean)
```

Reuse the existing offset-and-reassign ordering method. Keep one transaction per reorder.

- [ ] **Step 4: Run repository tests and one nearby page-duplicate regression**

Expected: page order remains contiguous and duplicated pages keep the source chapter.

- [ ] **Step 5: Commit repository operations**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: manage chapters and page metadata"
```

### Task 3: Add block persistence and a minimal flow editor

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/flow/FlowEditor.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/flow/FlowViewModel.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/flow/BlockKind.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/PageDao.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/flow/FlowEditorTest.kt`

**Interfaces:**
- Consumes: `BlockEntity`, page ID, and repository transactions.
- Produces: ordered heading, paragraph, checklist, bulleted-list, numbered-list, image, math, divider, and paper-section blocks.

- [ ] **Step 1: Write failing block tests**

Test insertion, text update, checklist toggle, reorder, deletion, and process restart. Assert contiguous `orderIndex` values.

- [ ] **Step 2: Add block DAO and repository operations**

```kotlin
fun observeBlocks(pageId: String): Flow<List<BlockEntity>>
suspend fun insertBlock(pageId: String, afterId: String?, kind: BlockKind): String
suspend fun updateBlock(block: BlockEntity)
suspend fun moveBlock(pageId: String, fromIndex: Int, toIndex: Int)
suspend fun deleteBlock(id: String)
```

- [ ] **Step 3: Build the flow editor with Compose lazy content**

Use one `LazyColumn`. Keep text state in the ViewModel and save after IME commit or focus loss. Do not create a separate rich-text engine. Heading and list style are block kinds.

- [ ] **Step 4: Add paper-section blocks**

A paper section owns one existing canvas page ID through `payloadId`. Render it at a fixed height with an Expand action. The raw ink stays in the existing stroke tables.

- [ ] **Step 5: Run long-text, rotation, checklist, and paper-section tests**

Expected: text reflows after rotation and block order persists.

- [ ] **Step 6: Commit flow pages**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: add flow pages"
```

### Task 4: Build adaptive Contents navigation

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/contents/ContentsPane.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/contents/ContentsSheet.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/contents/ChapterRow.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/contents/PageRow.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/contents/ContentsFlowTest.kt`

**Interfaces:**
- Consumes: chapter flow, page metadata flow, and cached thumbnails.
- Produces: a tablet Contents pane and phone Contents sheet with the same callbacks.

- [ ] **Step 1: Write tablet and phone navigation tests**

Create two chapters and 20 pages. Verify collapse, expand, direct page selection, page title, bookmark, page move, and chapter move.

- [ ] **Step 2: Extract current page-strip and thumbnail code from `EditorScreen.kt`**

Delete the old number-only phone strip after both adaptive containers use `PageRow`.

- [ ] **Step 3: Implement one shared Contents model**

```kotlin
internal data class ContentsState(
    val chapters: List<ChapterEntity>,
    val pages: List<PageEntity>,
    val selectedPageId: String?,
    val collapsedChapterIds: Set<String>,
)
```

Use `BoxWithConstraints`: expanded width shows the 232 dp pane; compact width shows `Page N of M · Chapter` and opens the sheet.

- [ ] **Step 4: Add drag mode and direct page-number navigation**

Enable drag only after an explicit Reorder action. Validate the page number before navigation.

- [ ] **Step 5: Verify touch targets, screen reader labels, and reduced motion**

Expected: every row and action is at least 48 dp and selected state is not color-only.

- [ ] **Step 6: Commit Contents navigation**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: add chapter and page navigation"
```

### Task 5: Refactor the editor shell, palette, zoom, and page transitions

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorAppBar.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/ToolPalette.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/PageViewport.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/InsertMenu.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/AppSettings.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/EditorShellTest.kt`

**Interfaces:**
- Consumes: editor state, Contents callbacks, tool state, zoom state, and motion settings.
- Produces: icon-first palette, Fit width, Fit page, manual zoom, two-finger page navigation, and reduced-motion transitions.

- [ ] **Step 1: Write phone and tablet shell tests**

Assert the primary tools Pen, Highlighter, Eraser, Lasso, Shape, Insert, Undo, and Redo are reachable without horizontal scrolling. Assert Insert exposes Text, Image, Checklist, Table, Math, New page, and Import PDF.

- [ ] **Step 2: Extract the app bar and palette from `EditorScreen.kt`**

Use icon buttons with at least 48 dp targets and content descriptions. Pencil becomes a pen preset. The active tool opens color, width, opacity, and tool-specific controls.

- [ ] **Step 3: Implement viewport modes**

```kotlin
internal enum class ViewportMode { FIT_WIDTH, FIT_PAGE, MANUAL }

internal data class ViewportState(
    val mode: ViewportMode,
    val scale: Float,
    val offset: Offset,
)
```

Clamp manual zoom and page offsets. Fit width is the tablet default. Fit page remains available from the app bar.

- [ ] **Step 4: Add two-finger page changes**

One-finger input continues to draw or pan according to the active tool. Stylus input never changes pages. A two-finger horizontal swipe changes one page only after it crosses the configured distance and velocity threshold.

- [ ] **Step 5: Add the page transition**

Use a 160 ms horizontal slide with one page-edge shadow. Reduced motion changes pages immediately. Do not animate ink or re-render old pages during the transition.

- [ ] **Step 6: Run touch, stylus, keyboard, phone, tablet, rotation, and reduced-motion tests**

- [ ] **Step 7: Commit the editor shell**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: refine the notebook editor shell"
```

### Task 6: Build adaptive Settings navigation

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/settings/SettingsCategory.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/settings/SettingsNavigation.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/SettingsScreen.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/settings/SettingsFlowTest.kt`

**Interfaces:**
- Consumes: existing visual setting sections and previews.
- Produces: tablet two-pane category and detail Settings and phone category then detail navigation.

- [ ] **Step 1: Write adaptive Settings tests**

On expanded width, assert category and selected detail are visible together. On compact width, assert selecting a category opens a detail screen and Back returns to categories.

- [ ] **Step 2: Define the category list**

```kotlin
internal enum class SettingsCategory {
    NOTEBOOK_DEFAULTS,
    WRITING_STYLUS,
    RECOGNITION_OCR,
    MATH,
    STUDY_TOOLS,
    BACKUP_STORAGE,
    INTERFACE_ACCESSIBILITY,
    APP_PRIVACY,
}
```

- [ ] **Step 3: Move existing sections into category detail composables**

Keep live notebook, paper, and tool previews. Remove the one long tablet column after every setting is reachable through a category.

- [ ] **Step 4: Add category summaries, selected state, and accessible Back behavior**

- [ ] **Step 5: Run visual QA at phone, split-screen, tablet, and large text sizes**

- [ ] **Step 6: Commit adaptive Settings**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: add adaptive Settings navigation"
```

### Task 7: Add Quick Note and Inbox

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/Entities.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/library/LibraryScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/SeliaDocsApp.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/library/QuickNoteTest.kt`

**Interfaces:**
- Consumes: flow pages from Task 3.
- Produces: `createQuickNote(): String` that returns a page ID inside the single system Inbox notebook.

- [ ] **Step 1: Write failing Inbox tests**

Assert that the first Quick Note creates one Inbox and one flow page. A second Quick Note reuses the same Inbox. Moving the page to a user notebook leaves no duplicate.

- [ ] **Step 2: Mark the system notebook explicitly**

Add `systemKind: String?` to `NotebookEntity` through a migration amendment before schema 2 ships. Use value `INBOX`. Enforce at most one active Inbox in repository transactions.

- [ ] **Step 3: Add Quick Note routes**

Tablet navigation rail and phone primary action call `createQuickNote()` and open the returned flow page immediately.

- [ ] **Step 4: Test empty, existing Inbox, trash, and move scenarios**

Expected: trashing the Inbox is not allowed. Users may delete or move its pages.

- [ ] **Step 5: Commit Quick Note**

```powershell
git add app/src/main app/src/androidTest app/schemas
git commit -m "feat: add Quick Note inbox"
```

### Task 8: Extend backup and PDF export for schema 2

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupModels.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupExporter.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupImporter.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PdfExporter.kt`
- Modify: backup and PDF tests

**Interfaces:**
- Consumes: chapters, page metadata, blocks, and existing backup format 1.
- Produces: backup feature flags `chapters` and `flow-blocks`, plus chapter-scoped PDF export.

- [ ] **Step 1: Add a schema 2 round-trip test**

Export and restore a notebook with chapters, paper pages, a flow page, checklist state, titles, and bookmarks.

- [ ] **Step 2: Add chapter and block JSONL files**

Keep `formatVersion = 1`. Add feature flags and optional files so older Package 1 backups remain importable.

- [ ] **Step 3: Add chapter export scope**

```kotlin
data class Chapter(val notebookId: String, val chapterId: String) : BackupScope
```

PDF chapter export keeps page order and renders flow blocks with stable page breaks.

- [ ] **Step 4: Run old-backup compatibility and new round-trip tests**

Expected: Package 1 archives import with all pages Unfiled and `PAPER`.

- [ ] **Step 5: Commit compatibility work**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: back up chapters and flow pages"
```

### Task 9: Run notebook-structure acceptance

**Files:**
- Modify: `README.md`
- Modify: `docs/play-store/STORE_LISTING.md`
- Create: `docs/qa/2026-08-24-notebook-structure-acceptance.md`

**Interfaces:**
- Consumes: complete notebook structure package.
- Produces: phone and tablet evidence for chapters, Contents, Quick Note, flow pages, backup, and export.

- [ ] **Step 1: Run unit, migration, lint, and build checks**

- [ ] **Step 2: Run full instrumentation on API 29 and API 37**

- [ ] **Step 3: Verify a 10-chapter, 500-page notebook live**

Check chapter collapse, page-number jump, thumbnail scroll, page move, title, bookmark, Quick Note, and Back.

- [ ] **Step 4: Capture current store screenshots**

Capture the library, Contents pane, phone Contents sheet, flow page, and notebook creator under the SeliaDocs name.

- [ ] **Step 5: Commit acceptance evidence**

```powershell
git add README.md docs/play-store docs/qa
git commit -m "docs: verify notebook structure"
```

### Task 10: Add FTS schema and migration 2 to 3

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/Entities.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsDatabase.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/search/SearchDao.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/data/Migration2To3Test.kt`
- Update: Room schema `3.json`

**Interfaces:**
- Consumes: schema 2.
- Produces: `SearchSourceEntity`, `SearchTextEntity`, `SearchRegionEntity`, and `SearchDao`.

- [ ] **Step 1: Write the failing migration test**

Create schema 2 data, migrate, and assert source counts are unchanged and search tables are empty.

- [ ] **Step 2: Define search entities**

```kotlin
@Entity(tableName = "search_sources")
internal data class SearchSourceEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val notebookId: String,
    val chapterId: String?,
    val pageId: String,
    val sourceId: String,
    val sourceType: String,
    val revision: Long,
)

@Fts4
@Entity(tableName = "search_text")
internal data class SearchTextEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val text: String,
)

@Entity(tableName = "search_regions", primaryKeys = ["rowId", "regionIndex"])
internal data class SearchRegionEntity(
    val rowId: Long,
    val regionIndex: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
```

- [ ] **Step 3: Create and register migration 2 to 3**

Create all three tables and indices for notebook, chapter, page, source ID, and source type. Register `SearchDao` in the database.

- [ ] **Step 4: Run migration and schema validation**

- [ ] **Step 5: Commit FTS schema**

```powershell
git add app/src/main app/src/androidTest app/schemas
git commit -m "feat: add local search schema"
```

### Task 11: Index typed source content transactionally

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/search/SearchIndexer.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/search/SearchSourceType.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/search/SearchIndexerTest.kt`

**Interfaces:**
- Consumes: notebook, chapter, page, block, text-element, table-cell, and math source changes.
- Produces: `index(source: SearchDocument)`, `remove(sourceId: String)`, and `rebuild()`.

- [ ] **Step 1: Write stale-index tests**

Insert text, query it, update it, and assert the old term disappears. Delete the source and assert every metadata, FTS, and region row disappears.

- [ ] **Step 2: Define the indexing input**

```kotlin
internal data class SearchDocument(
    val notebookId: String,
    val chapterId: String?,
    val pageId: String,
    val sourceId: String,
    val sourceType: SearchSourceType,
    val revision: Long,
    val text: String,
    val regions: List<RectF> = emptyList(),
)
```

- [ ] **Step 3: Replace search rows in one transaction**

Delete rows for `sourceId`, insert metadata to obtain `rowId`, insert FTS text with the same row ID, then insert finite regions. Blank normalized text removes old rows without inserting new rows.

- [ ] **Step 4: Call the indexer from source transactions**

Index notebook, chapter, and page titles; flow block text; checklists; text elements; and current math expression and result. Do not index raw stroke bytes.

- [ ] **Step 5: Add `rebuild()` for restore and migration repair**

Clear derived tables and stream source rows in batches of 200.

- [ ] **Step 6: Run insert, update, delete, rebuild, and Unicode tests**

- [ ] **Step 7: Commit indexing**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: index typed notebook content"
```

### Task 12: Implement scoped search queries

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/search/SearchDao.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/search/SearchRepository.kt`
- Create: `app/src/test/java/com/majkeylab/seliadocs/search/SearchQueryTest.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/search/SearchRepositoryTest.kt`

**Interfaces:**
- Consumes: FTS query text and `SearchScope`.
- Produces: `search(query: String, scope: SearchScope, filters: SearchFilters): Flow<List<SearchResult>>`.

- [ ] **Step 1: Define scope, filters, and result types**

```kotlin
internal sealed interface SearchScope {
    data object All : SearchScope
    data class Notebook(val id: String) : SearchScope
    data class Chapter(val id: String) : SearchScope
    data class Page(val id: String) : SearchScope
}

internal data class SearchResult(
    val notebookId: String,
    val chapterId: String?,
    val pageId: String,
    val sourceId: String,
    val sourceType: SearchSourceType,
    val snippet: String,
    val regions: List<RectF>,
)
```

- [ ] **Step 2: Write query normalization tests**

Escape FTS operators that users type as plain text. Reject a query longer than 500 characters. Preserve quoted phrase search only when quotes are balanced.

- [ ] **Step 3: Add DAO queries for every scope**

Join `search_text` to `search_sources` by `rowid`. Use `MATCH :query` and scope columns. Limit one response to 200 results and sort page-local results by source order.

- [ ] **Step 4: Run all scope, filter, phrase, special-character, and impossible-query tests**

- [ ] **Step 5: Commit search repository**

```powershell
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat: query scoped notebook search"
```

### Task 13: Add global and in-notebook search UI

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/search/SearchScreen.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/search/SearchPanel.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/search/SearchViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/SeliaDocsApp.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/search/SearchFlowTest.kt`

**Interfaces:**
- Consumes: `SearchRepository` and navigation callback `openSearchResult(result)`.
- Produces: global Search destination, tablet context panel, and phone result sheet.

- [ ] **Step 1: Write search-flow tests**

Search from the library, notebook, chapter, and current page. Tap a result and assert the target page and matching source are visible.

- [ ] **Step 2: Build the global search screen**

Use one search field, scope chips, filter button, result count, and a lazy result list. Each result shows notebook cover color, notebook, chapter, page title, source type, and snippet.

- [ ] **Step 3: Build in-notebook results**

Expanded tablets open `SearchPanel` in the context pane. Phones open a bottom sheet. The query and selected filters survive page navigation until the user closes search.

- [ ] **Step 4: Highlight the result region**

Pass regions to the editor. Draw a temporary accessible highlight that clears on the next edit or explicit Close.

- [ ] **Step 5: Verify keyboard, stylus handwriting input field, Back, empty, and error states**

- [ ] **Step 6: Commit search UI**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: add notebook search UI"
```

### Task 14: Rebuild search after restore and verify scale

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupImporter.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/search/SearchIndexer.kt`
- Create: `docs/qa/2026-08-24-search-acceptance.md`

**Interfaces:**
- Consumes: successful backup import and the 500-page fixture.
- Produces: a rebuilt index and acceptance evidence.

- [ ] **Step 1: Trigger rebuild only after restore commits**

If rebuilding fails, keep restored source data and show `Search index needs repair`. The next Search open retries rebuild.

- [ ] **Step 2: Test restore with intentionally wrong archived search files**

Search files are not part of the archive. Assert that restored results come only from rebuilt source data.

- [ ] **Step 3: Run 500-page typed search on API 29 and API 37**

Record result correctness and memory evidence. Do not claim a latency target without measurement.

- [ ] **Step 4: Run full regression and commit acceptance**

```powershell
git add app/src/main app/src/androidTest docs/qa
git commit -m "docs: verify local notebook search"
```
