# SeliaSheets release safety implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship SeliaSheets 0.2.1 beta to Google Play closed testing without the verified text, restore, backup, Android 10 scale, history-memory, PDF-memory, or stylus-routing defects.

**Architecture:** Keep the existing Room and Compose structure. Add one process-wide mutation gate for editor writes and restore, make replacement success invalidate editor generations, align backup validation with repository invariants, query notebook content by `notebookId`, and bound history and bitmap memory.

**Tech Stack:** Kotlin, Jetpack Compose, Room 2.8.4, AndroidX Ink, JUnit 4, Android instrumentation, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-25-seliasheets-release-safety-design.md`

## Global Constraints

- Package name stays `com.majkeylab.seliadocs`.
- Display name stays `SeliaSheets`.
- `minSdk` stays 29 and `targetSdk` stays 37.
- Version becomes `versionCode 3` and `versionName 0.2.1-beta.1`.
- The app keeps no `INTERNET`, ads, analytics, accounts, or telemetry.
- Public copy must not claim a visible tilt-sensitive brush until the renderer uses tilt.
- Closed testing is the only Google Play target for this release.
- Use existing dependencies only.
- Write a failing regression test before each production change.

---

### Task 1: Preserve the newest page-text draft

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PageCanvas.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/PageTextFlowTest.kt`

**Interfaces:**
- Consumes: `PageTextLayer(..., onTextChanged: (String, String) -> Unit)`.
- Produces: the same callback API with monotonic local-draft behavior.

- [ ] **Step 1: Add the stale-echo regression test**

Add a test that types `A`, lets `onPageTextChanged` capture the submitted value without updating `blocks`, types `B`, then publishes a Room-style `blocks = ["A"]` echo. Assert that the field still exposes `AB`. Publish `blocks = ["AB"]` and assert that the field stays `AB`.

```kotlin
@Test
fun staleStoredEchoDoesNotReplaceNewerDraft() {
    // Render PageCanvas with mutable blocks.
    // Type "A" and wait for the debounced callback.
    // Type "B", then publish the older "A" block.
    // Assert EditableText is "AB".
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb -s <authorized-serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <authorized-serial> install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s <authorized-serial> shell am instrument -w -e class com.majkeylab.seliadocs.editor.PageTextFlowTest com.majkeylab.seliadocs.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `staleStoredEchoDoesNotReplaceNewerDraft` fails because the field becomes `A`.

- [ ] **Step 3: Track the last submitted text**

In `PageTextLayer`, initialize `lastSubmittedText` from `storedText`. Before calling `onTextChanged`, set `lastSubmittedText = draft.text`. Apply a new `storedText` only when `draft.text == lastSubmittedText` or `storedText == draft.text`.

```kotlin
var lastSubmittedText by remember(page.id) { mutableStateOf(storedText) }

LaunchedEffect(storedText) {
    if (draft.text == lastSubmittedText || storedText == draft.text) {
        draft = TextFieldValue(storedText)
        lastSubmittedText = storedText
    }
}
```

- [ ] **Step 4: Run PageTextFlowTest and verify GREEN**

Run the Step 2 commands. Expected: every `PageTextFlowTest` test reports `OK`.

- [ ] **Step 5: Commit Task 1**

```powershell
git add app/src/main/java/com/majkeylab/seliadocs/editor/PageCanvas.kt app/src/androidTest/java/com/majkeylab/seliadocs/editor/PageTextFlowTest.kt
git commit -m "fix(editor): preserve newer text drafts"
```

### Task 2: Isolate library replacement from editor state

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/data/LibraryMutationGate.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupImporter.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/SeliaDocsApp.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/backup/BackupFlowTest.kt`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/backup/BackupImporterTest.kt`

**Interfaces:**
- Produces: `internal object LibraryMutationGate { suspend fun <T> withLock(block: suspend () -> T): T }`.
- Produces: `BackupUiState.replacementGeneration: Long`.
- Produces: `BackupRoute(onClose: () -> Unit, onLibraryReplaced: (Long) -> Unit)`.
- Produces: `EditorRoute(..., libraryGeneration: Long, ...)`.

- [ ] **Step 1: Add failing navigation and restore tests**

Add one `BackupFlowTest` that renders a running backup state and verifies `pressBack()` stays on **Backup & restore**. Add one `BackupImporterTest` that starts an editor mutation under `LibraryMutationGate`, starts `REPLACE`, and asserts replacement completes only after the first mutation releases the gate.

- [ ] **Step 2: Run the new tests and verify RED**

Run the targeted Huawei instrumentation command for `BackupFlowTest,BackupImporterTest`. Expected: Back closes the screen and the restore does not wait on a shared gate.

- [ ] **Step 3: Add the shared mutation gate**

```kotlin
internal object LibraryMutationGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
```

Wrap the body of `EditorViewModel.mutate` and the validated install-plus-transaction part of `BackupImporter.restore` with this gate. Keep archive validation outside the gate.

- [ ] **Step 4: Block Back and publish replacement completion**

Add `BackHandler(enabled = state.running) {}` in `BackupScreen`. Increment `replacementGeneration` only after a successful `RestoreMode.REPLACE`. In `SeliaDocsApp`, handle the callback by setting `notebookId = null`, closing Backup and Settings, and storing the new generation. Include the generation in the editor ViewModel key.

- [ ] **Step 5: Run targeted tests and verify GREEN**

Run `BackupFlowTest`, `BackupImporterTest`, and `NavigationBackTest` on the Huawei device. Expected: all tests report `OK`.

- [ ] **Step 6: Commit Task 2**

```powershell
git add app/src/main/java/com/majkeylab/seliadocs app/src/androidTest/java/com/majkeylab/seliadocs/backup/BackupFlowTest.kt app/src/androidTest/java/com/majkeylab/seliadocs/backup/BackupImporterTest.kt
git commit -m "fix(backup): isolate library replacement"
```

### Task 3: Make backup invariants self-consistent and bounded

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/NotebookDao.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupValidator.kt`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/backup/BackupValidatorTest.kt`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/backup/BackupImporterTest.kt`
- Test: `app/src/test/java/com/majkeylab/seliadocs/editor/ElementTransformTest.kt`

**Interfaces:**
- Produces: contiguous chapter order after `deleteChapter`.
- Consumes: `clampElementTransform` for element duplication.
- Produces: `BackupValidator(..., maxRecords: Int = 200_000)`.

- [ ] **Step 1: Add failing chapter, geometry, ownership, and limit tests**

Add tests for these literal cases:

- Delete chapter index `1` from indexes `[0,1,2]`, export, validate, and restore. The remaining indexes are `[0,1]`.
- Duplicate a `45f` rotated element at the page edge, export, and validate.
- A page in notebook `A` references a chapter in notebook `B`. Validation throws `InvalidRelationship`.
- A PDF page in notebook `A` references a PDF source in notebook `B`. Validation throws `InvalidRelationship`.
- A validator constructed with `maxRecords = 2` rejects a three-record archive with `LimitExceeded`.

- [ ] **Step 2: Run the tests and verify RED**

Run `ElementTransformTest` locally and `BackupValidatorTest,BackupImporterTest` on Huawei. Confirm that each new test fails for its named defect.

- [ ] **Step 3: Reindex chapters and clamp duplicates**

After chapter deletion, offset remaining chapter indexes and rewrite them in order. Replace duplicate-element `coerceIn` calls with `clampElementTransform(source.transform().copy(x = source.x + 12f, y = source.y + 12f), page.widthPoints.toFloat(), page.heightPoints.toFloat())`.

- [ ] **Step 4: Validate ownership and record count**

Use maps from page, chapter, and PDF-source IDs to notebook IDs. Reject cross-notebook references after all records are indexed. Increment `recordCount` before dispatching each parsed record and reject values above `maxRecords`.

- [ ] **Step 5: Run targeted tests and verify GREEN**

Run the Step 2 tests. Expected: unit tests pass and both instrumentation classes report `OK`.

- [ ] **Step 6: Commit Task 3**

```powershell
git add app/src/main/java/com/majkeylab/seliadocs/data app/src/main/java/com/majkeylab/seliadocs/backup/BackupValidator.kt app/src/androidTest/java/com/majkeylab/seliadocs/backup app/src/test/java/com/majkeylab/seliadocs/editor/ElementTransformTest.kt
git commit -m "fix(backup): enforce portable backup invariants"
```

### Task 4: Remove Android 10 notebook-query and PDF-image memory traps

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/PageDao.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PdfExporter.kt`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/LargeNotebookTest.kt`
- Create: `app/src/test/java/com/majkeylab/seliadocs/editor/PdfExportImageSampleTest.kt`

**Interfaces:**
- Produces: `PageDao.getStrokesForNotebook(notebookId: String)`, `getElementsForNotebook`, and `getBlocksForNotebook`.
- Produces: `internal fun imageSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int`.

- [ ] **Step 1: Add failing 1,200-page and sample-size tests**

Extend `LargeNotebookTest` to create 1,200 pages and assert `repository.loadNotebook(notebookId)` returns all 1,200 strokes and elements. Add literal sample-size cases: `8192x8192 -> 1024x1024` returns `8`; `1000x800 -> 1200x900` returns `1`.

- [ ] **Step 2: Run tests and verify RED**

Run `PdfExportImageSampleTest` locally and `LargeNotebookTest` on Huawei. Expected: the helper is absent and the 1,200-page load hits SQLite's variable limit.

- [ ] **Step 3: Query by notebook ID**

Replace the three list-parameter DAO queries with subqueries against `pages.notebookId`. Change `loadNotebook` to call the three notebook queries directly.

- [ ] **Step 4: Group page content and sample images**

In `PdfExporter.write`, build `strokesByPage`, `elementsByPage`, and `blocksByPage` once. Read image bounds first, calculate `inSampleSize`, and decode at the nearest power-of-two size that remains at least the rendered target.

- [ ] **Step 5: Run tests and verify GREEN**

Run the Step 2 tests. Expected: all pass on API 29.

- [ ] **Step 6: Commit Task 4**

```powershell
git add app/src/main/java/com/majkeylab/seliadocs/data/PageDao.kt app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt app/src/main/java/com/majkeylab/seliadocs/editor/PdfExporter.kt app/src/androidTest/java/com/majkeylab/seliadocs/editor/LargeNotebookTest.kt app/src/test/java/com/majkeylab/seliadocs/editor/PdfExportImageSampleTest.kt
git commit -m "fix(data): bound large notebook exports"
```

### Task 5: Bound Undo history memory

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PageHistory.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PageHistoryStore.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Modify: `app/src/test/java/com/majkeylab/seliadocs/editor/PageHistoryTest.kt`
- Modify: `app/src/test/java/com/majkeylab/seliadocs/editor/PageHistoryStoreTest.kt`

**Interfaces:**
- Produces: `PageHistory(initial, limit, maxWeight, weightOf)`.
- Keeps: `PageHistoryStore.history(pageId, initial)`.

- [ ] **Step 1: Add failing byte-budget tests**

Use string length as a deterministic weight. Push `aaaa`, `bbbb`, and `cccc` into a history with `maxWeight = 8`. Assert only the current value and the newest prior value remain undoable. Verify that Undo and Redo move entries without increasing retained weight.

- [ ] **Step 2: Run unit tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.majkeylab.seliadocs.editor.PageHistoryTest" --tests "com.majkeylab.seliadocs.editor.PageHistoryStoreTest" --console=plain
```

Expected: compilation fails because weighted history parameters do not exist.

- [ ] **Step 3: Implement weighted history**

Track the weight of current, Undo, and Redo states. Drop the oldest Undo states until retained weight is within the budget. If current alone exceeds the budget, keep current and drop all history.

Configure `EditorViewModel` with four cached pages and an 8 MiB budget per page. Estimate snapshot weight from stroke byte arrays, text length, element strings, and fixed per-entity overhead.

- [ ] **Step 4: Run unit tests and verify GREEN**

Run the Step 2 command. Expected: both test classes pass.

- [ ] **Step 5: Commit Task 5**

```powershell
git add app/src/main/java/com/majkeylab/seliadocs/editor/PageHistory.kt app/src/main/java/com/majkeylab/seliadocs/editor/PageHistoryStore.kt app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt app/src/test/java/com/majkeylab/seliadocs/editor/PageHistoryTest.kt app/src/test/java/com/majkeylab/seliadocs/editor/PageHistoryStoreTest.kt
git commit -m "fix(editor): bound undo history memory"
```

### Task 6: Complete finger-to-stylus arbitration and cut false public claims

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/InkCanvasView.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/StylusRoutingTest.kt`
- Modify: `README.md`
- Modify: `docs/play-store/STORE_LISTING.md`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Keeps existing `InkCanvasView` callback types.
- Produces: active stroke origin tracking by `MotionEvent` tool type.

- [ ] **Step 1: Add the finger-first regression test**

Start finger ink with `fingerDrawing = true`. Add a stylus pointer before the finger lifts. Assert that the finger interaction is canceled, no finger stroke commits, and the stylus stroke can commit.

- [ ] **Step 2: Run StylusRoutingTest and verify RED**

Run the targeted Huawei instrumentation class. Expected: the finger stroke remains active or commits.

- [ ] **Step 3: Track interaction origins**

Store the starting tool type for each active pointer. Before starting a stylus or hardware-eraser interaction, cancel active `TOOL_TYPE_FINGER` ink interactions. Preserve the existing stylus-first cancellation path and existing hardware-eraser behavior.

- [ ] **Step 4: Update version and public copy**

Set `versionCode = 3` and `versionName = "0.2.1-beta.1"`. Remove visible tilt-brush claims from README and the local Play listing. Keep pressure support and persisted tilt data claims only where the distinction is explicit.

- [ ] **Step 5: Run StylusRoutingTest and identity tests**

Run `StylusRoutingTest`, `AppIdentityTest`, and unit tests. Expected: all pass.

- [ ] **Step 6: Commit Task 6**

```powershell
git add app/src/main/java/com/majkeylab/seliadocs/editor/InkCanvasView.kt app/src/androidTest/java/com/majkeylab/seliadocs/editor/StylusRoutingTest.kt README.md docs/play-store/STORE_LISTING.md app/build.gradle.kts
git commit -m "fix(stylus): cancel finger ink on pen input"
```

### Task 7: Verify and package 0.2.1 beta

**Files:**
- Modify: `docs/qa/2026-08-25-seliasheets-release-safety.md`
- Modify: `docs/play-store/STORE_LISTING.md`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: signed `SeliaSheets-v0.2.1-beta.1.apk` and `.aab` plus a QA report.

- [ ] **Step 1: Run the clean quality gate**

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run all instrumentation on API 29 and API 37**

Install the debug APK and test APK on one API 29 target and one API 37 target. Run `com.majkeylab.seliadocs.test/androidx.test.runner.AndroidJUnitRunner`. Expected: both suites report `OK` with zero failures.

- [ ] **Step 3: Run Huawei live scenarios**

On the explicitly authorized physical test device, verify:

1. Type while an older autosave echo returns. The latest text remains.
2. Replace the library and return to Library. Reopening a notebook exposes no pre-restore Undo state.
3. Export and restore a notebook after deleting a middle chapter and duplicating a rotated element.
4. Load and export a notebook above 999 pages without `SQLiteException`.

- [ ] **Step 4: Verify release signatures and hashes**

Run `apksigner verify --verbose --print-certs` on the APK and `jarsigner -verify` on the AAB. Write SHA-256 values into the QA report.

- [ ] **Step 5: Commit the QA report**

```powershell
git add docs/qa/2026-08-25-seliasheets-release-safety.md docs/play-store/STORE_LISTING.md
git commit -m "docs: record 0.2.1 beta acceptance"
```

- [ ] **Step 6: Review, push, and publish closed testing**

Run a whole-branch review. Push `fix/release-hardening/25-08-2026`, open a PR to `main`, merge only after green CI, create GitHub pre-release `v0.2.1-beta.1`, upload the signed assets, upload the AAB to Google Play Alpha, submit it for closed-test review, and verify the opt-in URL.
