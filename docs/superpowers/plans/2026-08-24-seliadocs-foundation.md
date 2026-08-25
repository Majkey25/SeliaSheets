# SeliaDocs foundation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a clean SeliaDocs release baseline, validated editable backup and restore, page-scale performance, bounded undo history, editable elements, and real thumbnails.

**Architecture:** The first five tasks finish the product identity, navigation, Android 10 reliability, and release baseline without changing the Room schema. The remaining tasks add a streaming `.seliadocs` archive, staged restore, selected-page loading, bounded page histories, element transforms, and cached thumbnails. The plan keeps the current Compose, Room, DataStore, and AndroidX Ink structure.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Activity, Room 2.8.4, AndroidX Ink 1.0.0, Gradle, GitHub Actions, Android emulator QA

**Spec:** `docs/superpowers/specs/2026-08-24-seliadocs-smart-study-notebook-design.md`

## Global constraints

- Public name: `SeliaDocs`.
- Android namespace and application ID: `com.majkeylab.seliadocs`.
- Android support: API 29 through API 37.
- UI language: English.
- Preserve the existing icon colors and artwork.
- Do not touch a physical Android device.
- Do not add analytics, ads, telemetry, accounts, cloud sync, or broad storage permissions.
- Keep unrelated working-tree changes intact.
- Run release checks before push, merge, tag, or release creation.
- Backup export and import use the Storage Access Framework.
- Import never mutates the live library until validation succeeds.
- Raw ink and positioned elements remain editable.
- Opening one page in a 500-page notebook must not load content from the other 499 pages.
- Keep backup queues, histories, bitmaps, and caches bounded.
- Preserve existing data after every failed import path.

---

### Task 1: Finish the package and symbol rename

**Files:**
- Move: `app/src/main/java/cz/majkey/perko/` to `app/src/main/java/com/majkeylab/seliadocs/`
- Move: `app/src/test/java/cz/majkey/perko/` to `app/src/test/java/com/majkeylab/seliadocs/`
- Move: `app/src/androidTest/java/cz/majkey/perko/` to `app/src/androidTest/java/com/majkeylab/seliadocs/`
- Move: `app/schemas/cz.majkey.perko.data.PerkoDatabase/` to `app/schemas/com.majkeylab.seliadocs.data.SeliaDocsDatabase/`
- Rename: `PerkoApp.kt` to `SeliaDocsApp.kt`
- Rename: `PerkoTheme.kt` to `SeliaDocsTheme.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: all moved Kotlin files
- Modify: `settings.gradle.kts`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/AppIdentityTest.kt`

**Interfaces:**
- Consumes: the current application ID `com.majkeylab.seliadocs` and version `0.1.0-beta.1`.
- Produces: `SeliaDocsApp()`, `SeliaDocsTheme()`, `SeliaDocsRepository`, `SeliaDocsDatabase`, and package `com.majkeylab.seliadocs`.

- [ ] **Step 1: Write the failing identity test**

```kotlin
@RunWith(AndroidJUnit4::class)
class AppIdentityTest {
    @Test
    fun installedPackageAndDatabaseUseSeliaDocsIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.majkeylab.seliadocs", context.packageName)
        assertEquals("seliadocs.db", SeliaDocsDatabase.FILE_NAME)
    }
}
```

- [ ] **Step 2: Run the identity test and record the expected compile failure**

Run:

```powershell
$env:ANDROID_SERIAL='emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.majkeylab.seliadocs.AppIdentityTest' --console=plain
Remove-Item Env:ANDROID_SERIAL
```

Expected: compilation fails because `SeliaDocsDatabase` and its package do not exist.

- [ ] **Step 3: Move the source directories and rename public project symbols**

Use `git mv` for directories and files. Replace package declarations and imports with `com.majkeylab.seliadocs`. Rename these symbols and their call sites:

```text
PerkoApp -> SeliaDocsApp
PerkoTheme -> SeliaDocsTheme
PerkoRepository -> SeliaDocsRepository
PerkoDatabase -> SeliaDocsDatabase
perko.db -> seliadocs.db
```

Set the Gradle identity:

```kotlin
android {
    namespace = "com.majkeylab.seliadocs"
    defaultConfig {
        applicationId = "com.majkeylab.seliadocs"
    }
}
```

Expose the database filename for the test:

```kotlin
internal abstract class SeliaDocsDatabase : RoomDatabase() {
    companion object {
        const val FILE_NAME = "seliadocs.db"
    }
}
```

- [ ] **Step 4: Remove stale source identity**

Run:

```powershell
rg -n "Péřko|cz\.majkey\.perko|\bPerko(App|Theme|Repository|Database)\b|perko\.db" app settings.gradle.kts
```

Expected: no match outside historical migration notes that explicitly describe the rename.

- [ ] **Step 5: Run unit, lint, identity, and build checks**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
$env:ANDROID_SERIAL='emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.majkeylab.seliadocs.AppIdentityTest' --console=plain
Remove-Item Env:ANDROID_SERIAL
```

Expected: both commands pass.

- [ ] **Step 6: Commit the identity change**

```powershell
git add app settings.gradle.kts
git commit -m "refactor: finish SeliaDocs identity"
```

### Task 2: Make system Back follow app navigation

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/SeliaDocsApp.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/PageFlowTest.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/NavigationBackTest.kt`

**Interfaces:**
- Consumes: `notebookId: String?`, `settingsOpen: Boolean`, and the existing editor `onBack` callback.
- Produces: system Back closes Settings first, then the editor, and exits only from the library root.

- [ ] **Step 1: Write failing Back tests**

```kotlin
@Test
fun systemBackReturnsFromEditorToLibrary() {
    createNotebook("Back test")
    rule.onNodeWithText("Back test").performClick()
    pressBack()
    rule.onNodeWithText("SeliaDocs").assertIsDisplayed()
}

@Test
fun systemBackClosesSettingsWithoutLeavingNotebook() {
    openNotebookAndSettings()
    pressBack()
    rule.onNodeWithContentDescription("Add page").assertIsDisplayed()
}
```

- [ ] **Step 2: Run the tests and verify the current failure**

Run the class on `emulator-5594`. Expected: Back exits the activity or returns to the wrong destination.

- [ ] **Step 3: Add one root Back handler**

```kotlin
BackHandler(enabled = settingsOpen || notebookId != null) {
    if (settingsOpen) {
        settingsOpen = false
    } else {
        notebookId = null
    }
}
```

Do not add a handler at the library root.

- [ ] **Step 4: Make PageFlow return to the library before test teardown**

End `PageFlowTest.addDuplicateAndDeletePages()` with `pressBack()` and an assertion for `SeliaDocs`.

- [ ] **Step 5: Run Back and existing page tests on API 29 and API 37**

Expected: both targets pass and no test leaves an editor activity active.

- [ ] **Step 6: Commit the navigation fix**

```powershell
git add app/src/main app/src/androidTest
git commit -m "fix: handle system Back navigation"
```

### Task 3: Fix notebook actions with more than one notebook

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/library/LibraryScreen.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/library/LibraryFlowTest.kt`

**Interfaces:**
- Consumes: `NotebookEntity`, `trash: Boolean`, and callbacks for favorite, rename, trash, restore, and delete.
- Produces: one root-level `NotebookActionSheet(notebook, trash, ...)` and one clickable action semantics node per cover.

- [ ] **Step 1: Add a two-notebook regression test**

```kotlin
@Test
fun newestNotebookMenuOpensWhenAnotherNotebookExists() {
    createNotebook("Existing notebook")
    val newest = "Newest notebook"
    createNotebook(newest)
    rule.onNodeWithContentDescription("Notebook actions: $newest").performClick()
    rule.onNodeWithText("Move to trash").assertIsDisplayed()
}
```

- [ ] **Step 2: Reproduce on API 29 and capture the semantics tree**

Run only `LibraryFlowTest` after `PageFlowTest` on the API 29 test emulator. Expected before the fix: the action does not expose the menu within 10 seconds.

- [ ] **Step 3: Use one in-tree action sheet and keep the newest notebook visible**

```kotlin
var actionTarget by remember { mutableStateOf<NotebookEntity?>(null) }
val gridState = rememberLazyGridState()

LaunchedEffect(state.notebooks.firstOrNull()?.id) {
    gridState.scrollToItem(0)
}

actionTarget?.let { notebook ->
    NotebookActionSheet(notebook = notebook, trash = state.trash, ...)
}
```

Keep the sheet in the same Compose root as the library. Do not use a per-card popup window.

- [ ] **Step 4: Run one-notebook, two-notebook, trash, and manual tap scenarios**

Verify:

1. one active notebook;
2. two active notebooks;
3. one trashed notebook;
4. manual tap on the first and second visible covers.

Expected: every menu opens once and targets the correct notebook.

- [ ] **Step 5: Run the full 28-test suite on API 29 and API 37**

Expected: both complete with zero skipped and zero failed tests.

- [ ] **Step 6: Commit the menu fix**

```powershell
git add app/src/main/java/com/majkeylab/seliadocs/library app/src/androidTest/java/com/majkeylab/seliadocs/library
git commit -m "fix: stabilize notebook action menus"
```

### Task 4: Reconcile branding, links, and release metadata

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `PRIVACY.md`
- Modify: `CONTRIBUTING.md`
- Modify: `THIRD_PARTY_NOTICES.md`
- Modify: `docs/RELEASE.md`
- Modify: `docs/play-store/`
- Modify: `site/`
- Modify: `.github/workflows/android.yml`
- Modify: `.github/ISSUE_TEMPLATE/bug_report.yml`
- Rename: `branding/perko-android-icon*.png` to `branding/seliadocs-android-icon*.png`

**Interfaces:**
- Consumes: repository URL `https://github.com/Majkey25/SeliaDocs` and Pages base URL `https://majkey25.github.io/SeliaDocs/`.
- Produces: one public identity across the repository, site, privacy policy, app links, badges, and store listing.

- [ ] **Step 1: Scan all tracked files for stale public identity**

```powershell
git grep -n -I -E 'Péřko|Majkey25/Perko|majkey25.github.io/Perko|cz\.majkey\.perko'
```

Expected: the command lists every file that needs an identity update. Internal historical references must state that they describe the pre-release name.

- [ ] **Step 2: Update public copy and URLs**

Use `SeliaDocs`, `Majkey25/SeliaDocs`, `https://majkey25.github.io/SeliaDocs/privacy/`, and `com.majkeylab.seliadocs` consistently.

- [ ] **Step 3: Verify README assets and badges**

Check that every local image path exists and every badge points to `Majkey25/SeliaDocs`.

- [ ] **Step 4: Run the local Pages site and inspect desktop and mobile layouts**

Use the existing local site command. Verify the landing page, privacy page, source link, and contact link with no console errors or horizontal overflow.

- [ ] **Step 5: Commit branding and documentation**

```powershell
git add README.md CHANGELOG.md PRIVACY.md CONTRIBUTING.md THIRD_PARTY_NOTICES.md docs site branding .github
git commit -m "docs: complete SeliaDocs release identity"
```

### Task 5: Rebuild and clear release gates

**Files:**
- Update generated files only under ignored `dist/`
- Modify: `docs/qa/2026-08-24-emulator-acceptance.md` only with new evidence

**Interfaces:**
- Consumes: external signing properties from `SELIADOCS_KEYSTORE_PROPERTIES`.
- Produces: signed `SeliaDocs-v0.1.0-beta.1.apk`, signed `SeliaDocs-v0.1.0-beta.1.aab`, public certificate, and matching `SHA256SUMS.txt`.

- [ ] **Step 1: Run the complete local build**

```powershell
$env:SELIADOCS_KEYSTORE_PROPERTIES='C:\Users\mates\Documents\Codex\2026-08-24\p-ko-j-pot-ebuju-ud\work\perko-release-secrets\keystore.properties'
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --console=plain
$code=$LASTEXITCODE
Remove-Item Env:SELIADOCS_KEYSTORE_PROPERTIES
exit $code
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run full emulator acceptance**

Run `connectedDebugAndroidTest` separately with `ANDROID_SERIAL=emulator-5590` and `ANDROID_SERIAL=emulator-5594`. Never run the task without a serial while a physical device is attached.

- [ ] **Step 3: Verify the exact release APK**

Use `apksigner verify --verbose --print-certs` and `aapt dump badging`. Verify:

```text
package: com.majkeylab.seliadocs
versionName: 0.1.0-beta.1
minSdkVersion: 29
targetSdkVersion: 37
application-label: SeliaDocs
```

Install and cold-launch the signed APK on API 29 and API 37.

- [ ] **Step 4: Refresh release files and hashes**

Copy the verified APK and AAB to `dist/` with `SeliaDocs` names. Recalculate SHA-256 values and verify the checksum file against the copied bytes.

- [ ] **Step 5: Push and wait for GitHub CI**

```powershell
git push origin feat/release/24-08-2026
gh pr checks 1 --watch --interval 10
```

Expected: all GitHub Actions checks pass.

### Task 6: Define the archive model and streaming JSON codec

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupModels.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupJson.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/backup/BackupJsonTest.kt`

**Interfaces:**
- Consumes: `NotebookEntity`, `PageEntity`, `StrokeEntity`, and `ElementEntity`.
- Produces: `BackupManifest`, `BackupRecord`, `BackupJson.writeManifest`, `BackupJson.readManifest`, `BackupJson.writeRecord`, and `BackupJson.readRecords`.

- [ ] **Step 1: Write failing JSON round-trip tests**

```kotlin
@Test
fun manifestAndRecordsRoundTripWithoutLosingStrokeBytes() {
    val manifest = BackupManifest(formatVersion = 1, appVersion = "0.1.0-beta.1", exportedAt = 42L)
    val stroke = BackupStroke("stroke", "page", 0, "PEN", 0xff000000.toInt(), 3f, 0.1f, byteArrayOf(1, 2, 3))
    val bytes = encode(manifest, stroke)
    val decoded = decode(bytes)
    assertEquals(manifest, decoded.manifest)
    assertArrayEquals(stroke.inputs, decoded.strokes.single().inputs)
}
```

- [ ] **Step 2: Run the test and verify missing codec failures**

Run the class on `emulator-5590`. Expected: compilation fails because backup types do not exist.

- [ ] **Step 3: Add typed archive models**

```kotlin
internal data class BackupManifest(
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: Long,
    val notebookCount: Int = 0,
    val pageCount: Int = 0,
    val assetCount: Int = 0,
    val featureFlags: Set<String> = emptySet(),
)

internal sealed interface BackupRecord
```

Add one typed record for each current entity. Encode stroke inputs as Base64 inside JSONL. Reject non-finite coordinates during decoding.

- [ ] **Step 4: Implement streaming JSON with platform readers and writers**

Use `JsonWriter` and `JsonReader`. Do not build one in-memory JSON tree. Make unknown optional fields skippable and reject unknown required record kinds.

- [ ] **Step 5: Run round-trip, malformed number, oversized text, and unknown-version tests**

Expected: valid records preserve every field. Invalid records return a typed `BackupFailure`.

- [ ] **Step 6: Commit the archive format**

```powershell
git add app/src/main/java/com/majkeylab/seliadocs/backup app/src/androidTest/java/com/majkeylab/seliadocs/backup
git commit -m "feat: define editable backup format"
```

### Task 7: Export notebooks and assets as `.seliadocs`

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupExporter.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/AssetStore.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/backup/BackupExporterTest.kt`

**Interfaces:**
- Consumes: `SeliaDocsRepository.loadNotebook`, `AssetStore`, and `OutputStream`.
- Produces: `BackupExporter.export(scope: BackupScope, output: OutputStream): BackupSummary`.

- [ ] **Step 1: Write a failing complete-notebook export test**

Create a notebook with two pages, ink, text, math, and an image asset. Export to a byte stream. Assert ZIP entry names, record counts, and asset SHA-256.

- [ ] **Step 2: Add export scope and summary types**

```kotlin
internal sealed interface BackupScope {
    data class Notebook(val id: String) : BackupScope
    data class Selected(val ids: Set<String>) : BackupScope
    data object Library : BackupScope
}

internal data class BackupSummary(
    val notebooks: Int,
    val pages: Int,
    val assets: Int,
    val bytesWritten: Long,
)
```

- [ ] **Step 3: Stream ZIP entries and calculate hashes while copying**

Write `manifest.json`, entity JSONL files, hashed assets, and `checksums.json`. Use a 64 KiB copy buffer. Do not close the caller-owned output stream.

- [ ] **Step 4: Test empty library, one notebook, selected notebooks, and missing asset failure**

Expected: a missing asset aborts export and reports its asset ID.

- [ ] **Step 5: Commit export**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: export editable SeliaDocs backups"
```

### Task 8: Validate, stage, and merge backup imports

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupImporter.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupValidator.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupFailure.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/backup/BackupImporterTest.kt`

**Interfaces:**
- Consumes: `InputStream`, `SeliaDocsDatabase`, `AssetStore`, and archive records from Task 1.
- Produces: `BackupImporter.import(input: InputStream, mode: RestoreMode): RestoreSummary`.

- [ ] **Step 1: Write failing negative-path tests**

Add tests for parent traversal, absolute paths, duplicate ZIP entries, wrong checksum, missing asset, unsupported version, foreign-key mismatch, one entry larger than 1 GiB, extracted bytes above `min(availableBytes * 0.8, 8 GiB)`, and an interrupted stream.

- [ ] **Step 2: Define restore modes and typed failures**

```kotlin
internal enum class RestoreMode { MERGE, REPLACE }

internal sealed class BackupFailure(message: String) : Exception(message) {
    class InvalidPath(val entry: String) : BackupFailure("Invalid archive path: $entry")
    class ChecksumMismatch(val entry: String) : BackupFailure("Checksum mismatch: $entry")
    class UnsupportedVersion(val version: Int) : BackupFailure("Unsupported backup version: $version")
    class LimitExceeded(val limit: String) : BackupFailure("Backup limit exceeded: $limit")
}
```

- [ ] **Step 3: Implement streaming validation and private staging**

Resolve every entry against one newly-created private staging directory. Reject any resolved path outside that directory. Count bytes while copying. Hash every staged file and compare `checksums.json` before parsing records.

- [ ] **Step 4: Implement atomic merge**

Validate every ID and relationship first. In `MERGE`, remap all colliding notebook, page, stroke, element, and asset IDs through one map. In one Room transaction, insert records after assets finish staging. Move staged assets into private storage only when their destination names do not exist.

- [ ] **Step 5: Implement replace with rollback archive**

Create a private rollback `.seliadocs` file before `REPLACE`. Swap data only after the imported library validates. If the transaction or asset move fails, restore the rollback data and preserve the failure cause.

- [ ] **Step 6: Run every negative path and complete round trip**

Expected: every invalid archive leaves notebooks and assets byte-for-byte unchanged.

- [ ] **Step 7: Commit import**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: validate and restore SeliaDocs backups"
```

### Task 9: Add backup and restore UI through SAF

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupScreen.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/backup/BackupFlowTest.kt`

**Interfaces:**
- Consumes: `BackupExporter`, `BackupImporter`, `ActivityResultContracts.CreateDocument`, and `ActivityResultContracts.OpenDocument`.
- Produces: Backup and storage Settings detail with export and restore progress and results.

- [ ] **Step 1: Write UI tests for export scope and restore confirmation**

Assert that Export library launches a create-document request with MIME `application/zip` and filename `SeliaDocs-backup-YYYY-MM-DD.seliadocs`. Assert that Replace library shows a destructive confirmation while Merge does not.

- [ ] **Step 2: Add the Backup and storage detail screen**

Show scope, estimated counts, destination action, restore mode, progress, completion summary, and typed failure text. Disable duplicate submissions while work runs.

- [ ] **Step 3: Stream content resolver I/O off the main thread**

Use `Dispatchers.IO` inside the ViewModel. Close resolver-owned streams with `use`. Do not retain a URI permission after a one-time export or import.

- [ ] **Step 4: Run UI and process-recreation tests**

Expected: rotation preserves visible progress state and never starts a second operation.

- [ ] **Step 5: Commit backup UI**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: add backup and restore controls"
```

### Task 10: Load only selected-page content

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/PageDao.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/PageFlowTest.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/LargeNotebookTest.kt`

**Interfaces:**
- Consumes: `selectedPageId: StateFlow<String?>`.
- Produces: `observeStrokes(pageId: String)` and `observeElements(pageId: String)` flows that never join all notebook pages.

- [ ] **Step 1: Add DAO query-count instrumentation for a 500-page fixture**

Create 500 pages and add one stroke to page 250. Select page 250. Assert state contains one page's content and the DAO never returns content for another page.

- [ ] **Step 2: Add page-scoped DAO flows**

```kotlin
@Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY zIndex")
fun observeStrokes(pageId: String): Flow<List<StrokeEntity>>

@Query("SELECT * FROM elements WHERE pageId = :pageId ORDER BY zIndex")
fun observeElements(pageId: String): Flow<List<ElementEntity>>
```

Remove the notebook-wide reactive joins after all callers use page-scoped flows.

- [ ] **Step 3: Rebuild EditorViewModel content flow around selected page ID**

Use `flatMapLatest`. An empty selection emits empty content. Page selection cancels the previous page collectors.

- [ ] **Step 4: Run mutation regression tests**

Verify add stroke, erase, lasso move, text, math, shape replacement, page duplicate, and page delete on API 29 and API 37.

- [ ] **Step 5: Commit selected-page loading**

```powershell
git add app/src/main app/src/androidTest
git commit -m "perf: load editor content by page"
```

### Task 11: Preserve bounded page history and complete element transforms

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/PageHistoryStore.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PageCanvas.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Create: `app/src/test/java/com/majkeylab/seliadocs/editor/PageHistoryStoreTest.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/ElementFlowTest.kt`

**Interfaces:**
- Consumes: existing `PageHistory<PageSnapshot>` and repository `updateElement` and `deleteElement`.
- Produces: `PageHistoryStore(maxPages = 10, stepsPerPage = 100)` and editor callbacks for move, resize, rotate, and delete.

- [ ] **Step 1: Write history eviction and page-switch tests**

```kotlin
@Test
fun returningToRecentPageKeepsUndoHistory() {
    val store = PageHistoryStore<String>(maxPages = 2, stepsPerPage = 3)
    store.history("one", "a").push("b")
    store.history("two", "x")
    assertEquals("a", store.history("one", "b").undo())
}
```

Also assert that opening a third page evicts the least-recently-used page and never exceeds two histories.

- [ ] **Step 2: Implement the bounded access-ordered map**

Use `LinkedHashMap(pageId, history, accessOrder = true)`. Remove the eldest entry after insertion when `size > maxPages`.

- [ ] **Step 3: Wire element transforms through the existing repository methods**

Add ViewModel methods:

```kotlin
fun updateElementTransform(id: String, x: Float, y: Float, width: Float, height: Float, rotation: Float)
fun deleteElement(id: String)
```

Validate finite values and positive dimensions before calling the repository.

- [ ] **Step 4: Add selection handles and a Delete action**

Show handles only for the selected element. Keep handles at least 48 dp. Clamp transformed content to the page bounds.

- [ ] **Step 5: Run happy, boundary, invalid-transform, undo, and page-switch scenarios**

Expected: invalid transforms do not change data. Undo and redo operate on the correct page.

- [ ] **Step 6: Commit history and transforms**

```powershell
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat: preserve page history and edit elements"
```

### Task 12: Render and cache real page thumbnails

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/PageThumbnailRenderer.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/PageThumbnailCache.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/PageThumbnailTest.kt`

**Interfaces:**
- Consumes: page paper, selected-page vector content, and private cache storage.
- Produces: `render(pageId: String, revision: Long, widthPx: Int): Bitmap` and `thumbnail(pageId, revision): File?`.

- [ ] **Step 1: Write a thumbnail invalidation test**

Render a blank page, add a stroke, render with a new revision, and assert that the bytes differ. Assert that an unchanged revision reuses the same cache file.

- [ ] **Step 2: Implement a bounded cache**

Store WebP thumbnails under `cacheDir/page-thumbnails`. Keep at most 200 files and 64 MiB. Delete least-recently-used files when either limit is exceeded.

- [ ] **Step 3: Render on `Dispatchers.Default` and write on `Dispatchers.IO`**

Use the existing page drawing functions at thumbnail scale. Do not decode full imported images above the target thumbnail size.

- [ ] **Step 4: Replace number-only thumbnails in the editor**

Keep the page number and action menu. Add the rendered preview with an accessible `Page N` label.

- [ ] **Step 5: Run the 500-page fixture and memory snapshots**

Scroll through 500 thumbnails. Verify that the cache stays within both limits and visible thumbnails load without holding 500 bitmaps.

- [ ] **Step 6: Commit thumbnails**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: render cached page thumbnails"
```

### Task 13: Run foundation acceptance and update backup documentation

**Files:**
- Modify: `README.md`
- Modify: `PRIVACY.md`
- Modify: `site/privacy/index.html`
- Modify: `docs/play-store/DATA_SAFETY.md`
- Create: `docs/qa/2026-08-24-backup-performance-acceptance.md`

**Interfaces:**
- Consumes: the complete Package 1 implementation.
- Produces: verified backup, restore, page performance, and privacy evidence.

- [ ] **Step 1: Run unit, lint, build, and complete instrumentation checks**

Run unit and lint once. Run full instrumentation separately on API 29 and API 37.

- [ ] **Step 2: Run live backup scenarios**

Verify library export, notebook export, merge restore, replace restore, corrupt archive rejection, and round trip to a fresh emulator installation.

- [ ] **Step 3: Run the 500-page notebook scenario**

Record page-open behavior, memory before and after, thumbnail cache size, and the selected-page query evidence. Do not invent latency numbers.

- [ ] **Step 4: Update privacy and release documentation**

State that backups contain user-selected notebook data and are written only to a user-selected destination.

- [ ] **Step 5: Commit evidence and documentation**

```powershell
git add README.md PRIVACY.md site/privacy docs/play-store docs/qa
git commit -m "docs: verify backup and large notebooks"
```
