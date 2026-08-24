# Péřko Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an offline, tablet-first Android notebook with persistent pages, low-latency stylus ink, text and images, shape cleanup, local arithmetic, PDF export, emulator evidence, and a verified GitHub beta release.

**Architecture:** One Android application module uses Compose for adaptive screens, Room for transactional document metadata, private files for imported images, and AndroidX Ink for stroke capture, rendering, and serialization. Small page-scoped state holders own editing history. The interface is English only. Optional ML Kit models recognize Czech or English handwriting without uploading note content; confirmed shape cleanup uses local geometry.

**Tech Stack:** JDK 17, AGP 9.1.1, Gradle 9.3.1, built-in Kotlin 2.3.20, KSP2 2.3.10, Compose UI 1.12.0, Material 3 1.4.0, Activity 1.13.0, Lifecycle 2.11.0, Room 2.8.4, Preferences DataStore 1.2.1, AndroidX Ink 1.0.0, ML Kit Digital Ink Recognition 19.0.0.

**Spec:** `docs/superpowers/specs/2026-08-24-perko-android-design.md`

## Global Constraints

- Use `cz.majkey.perko`, `minSdk = 29`, `compileSdk = 37`, and `targetSdk = 37`.
- Use only stable dependency versions named above. Do not add a dependency-injection, navigation, formatting, logging, or math library.
- Keep the product offline-first. Network access is only for explicit ML Kit model downloads.
- Store imported media in private app storage. Never request all-files access.
- Keep undo history at 100 immutable page states.
- Preserve original ink when recognition or shape cleanup fails.
- Use original icons, colors, copy, and layouts. Do not copy Apple or Samsung assets or trademarks.
- Test only emulators. Do not access a physical phone or tablet.
- Before each `connectedDebugAndroidTest` command, set `$env:ANDROID_SERIAL = 'emulator-5590'`. Remove `Env:ANDROID_SERIAL` after the command so other Android work is unaffected.
- Do not commit signing material, secrets, build output, emulator files, or `local.properties`.

## File map

```text
settings.gradle.kts                         repositories and app module
build.gradle.kts                            AGP and KSP plugin versions
gradle.properties                          AndroidX and JVM settings
gradle/wrapper/*                            Gradle 9.3.1 wrapper
app/build.gradle.kts                        Android config and dependencies
app/src/main/AndroidManifest.xml            app declaration and no storage permission
app/src/main/java/cz/majkey/perko/
  MainActivity.kt                           activity entry point
  PerkoApp.kt                               library/editor screen switch
  data/Database.kt                          Room database
  data/Entities.kt                          notebook, page, stroke, element records
  data/NotebookDao.kt                       notebook and page transactions
  data/PageDao.kt                           stroke and element queries
  data/PerkoRepository.kt                   validated document operations
  data/AssetStore.kt                        private image import, copy, and delete
  library/LibraryViewModel.kt               library state and actions
  library/LibraryScreen.kt                  adaptive notebook library
  library/CreateNotebookDialog.kt           validated creation form
  settings/AppSettings.kt                   validated preference model
  settings/SettingsRepository.kt            Preferences DataStore persistence
  settings/SettingsScreen.kt                grouped settings UI
  settings/AppDetailsSection.kt             version, legal, source, and support
  editor/EditorViewModel.kt                 page load, save, and editing state
  editor/EditorScreen.kt                    adaptive editor shell and page rail
  editor/PageCanvas.kt                      paper, element, and ink host
  editor/InkCanvasView.kt                   AndroidX Ink input and finished rendering
  editor/InkCodec.kt                        stable stroke binary encoding
  editor/PageHistory.kt                     bounded undo and redo
  editor/StrokeHitTest.kt                   lasso and stroke eraser geometry
  editor/MathEvaluator.kt                   safe arithmetic parser
  editor/ImageImporter.kt                   MIME, bounds, and decode validation
  editor/ShapeCleanup.kt                    deterministic line and shape cleanup
  editor/DigitalInkModels.kt                model download and recognition
  editor/PageRenderer.kt                    shared screen and PDF page drawing
  editor/PdfExporter.kt                     complete paginated PDF renderer
  ui/PerkoTheme.kt                          original restrained visual system
  ui/Icons.kt                               original vector icons
app/src/main/res/values/strings.xml          English strings
app/src/test/...                             pure JVM checks
app/src/androidTest/...                      Room, Compose, stylus, and export checks
.github/workflows/android.yml                build and test CI
.github/workflows/release.yml                signed tag release
README.md, CHANGELOG.md, PRIVACY.md           public project documentation
```

---

### Task 1: Bootstrap a launchable Android 17 app

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `.gitignore`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/cz/majkey/perko/MainActivity.kt`
- Create: `app/src/main/java/cz/majkey/perko/PerkoApp.kt`
- Create: `app/src/main/java/cz/majkey/perko/ui/PerkoTheme.kt`
- Create: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/cz/majkey/perko/AppLaunchTest.kt`

**Interfaces:**
- Produces: `MainActivity`, `@Composable fun PerkoApp()`, and a Gradle wrapper that all later tasks use.

- [ ] **Step 1: Install the exact Android 17 platform and emulator image**

Run:

```powershell
& 'C:\Users\mates\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat' 'platforms;android-29' 'system-images;android-29;google_apis;x86_64' 'platforms;android-37.0' 'system-images;android-37.0;google_apis;x86_64'
```

Expected: all four packages finish with no SDK Manager error. Do not install a 37.2 beta package.

- [ ] **Step 2: Create dedicated phone and tablet AVDs**

Run each command. Enter `no` if `avdmanager` asks for a custom hardware profile.

```powershell
& 'C:\Users\mates\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat' create avd --name Perko_Tablet_API_37 --package 'system-images;android-37.0;google_apis;x86_64' --device pixel_tablet --force
& 'C:\Users\mates\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat' create avd --name Perko_Phone_API_29 --package 'system-images;android-29;google_apis;x86_64' --device pixel_4 --force
```

Expected: `emulator.exe -list-avds` lists both names.

- [ ] **Step 3: Start the dedicated API 37 tablet**

```powershell
Start-Process -FilePath 'C:\Users\mates\AppData\Local\Android\Sdk\emulator\emulator.exe' -ArgumentList '-avd Perko_Tablet_API_37 -port 5590 -no-snapshot-save' -WindowStyle Hidden
adb -s emulator-5590 wait-for-device
adb -s emulator-5590 shell getprop sys.boot_completed
```

Expected: the last command returns `1`. If it returns an empty value, repeat only the last command until it returns `1`.

- [ ] **Step 4: Write the build definition**

Use AGP built-in Kotlin. Do not apply `org.jetbrains.kotlin.android` or kapt.

```kotlin
// build.gradle.kts
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
```

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cz.majkey.perko"
    compileSdk = 37

    defaultConfig {
        applicationId = "cz.majkey.perko"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.0")
}
```

- [ ] **Step 5: Write the failing launch check**

```kotlin
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun libraryTitleIsVisible() {
        rule.onNodeWithText("Péřko").assertIsDisplayed()
    }
}
```

- [ ] **Step 6: Generate the wrapper and verify the check fails**

Generate the Gradle 9.3.1 wrapper with the verified local distribution:

```powershell
& 'C:\Users\mates\.gradle\wrapper\dists\gradle-9.3.1-bin\23ovyewtku6u96viwx3xl3oks\gradle-9.3.1\bin\gradle.bat' wrapper --gradle-version 9.3.1
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

Expected before `PerkoApp` exists: compilation or assertion failure that names the missing app UI.

- [ ] **Step 7: Implement the minimum app shell**

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PerkoTheme { PerkoApp() } }
    }
}

@Composable
internal fun PerkoApp() {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) {
        Box(Modifier.fillMaxSize().padding(it))
    }
}
```

- [ ] **Step 8: Run the first gates**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

Expected: all tasks pass and `app-debug.apk` has package `cz.majkey.perko`.

- [ ] **Step 9: Commit the launchable scaffold**

```text
build: bootstrap Android 17 app
```

---

### Task 2: Persist notebooks and ordered pages

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/cz/majkey/perko/data/Entities.kt`
- Create: `app/src/main/java/cz/majkey/perko/data/NotebookDao.kt`
- Create: `app/src/main/java/cz/majkey/perko/data/PageDao.kt`
- Create: `app/src/main/java/cz/majkey/perko/data/Database.kt`
- Create: `app/src/main/java/cz/majkey/perko/data/PerkoRepository.kt`
- Test: `app/src/androidTest/java/cz/majkey/perko/data/PerkoRepositoryTest.kt`

**Interfaces:**
- Produces: `PerkoRepository.observeNotebooks`, `createNotebook`, `renameNotebook`, `setFavorite`, `setTrashed`, `addPage`, `duplicatePage`, `movePage`, and `deletePage`.
- Produces: stable `NotebookEntity`, `PageEntity`, `StrokeEntity`, and `ElementEntity` records consumed by every editor task.
- Produces: `suspend fun loadNotebook(id: String): NotebookContent`, where `NotebookContent` contains the notebook and ordered page, stroke, and element records.

- [ ] **Step 1: Add Room and coroutine dependencies**

```kotlin
implementation("androidx.room:room-runtime:2.8.4")
implementation("androidx.room:room-ktx:2.8.4")
ksp("androidx.room:room-compiler:2.8.4")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
androidTestImplementation("androidx.room:room-testing:2.8.4")
```

- [ ] **Step 2: Write failing repository checks**

```kotlin
@Test fun createNotebookAlsoCreatesFirstPage() = runTest {
    val id = repository.createNotebook(CreateNotebookRequest("Physics", CoverColor.COBALT, PaperTemplate.GRID, PageOrientation.PORTRAIT, false))
    assertEquals("Physics", repository.getNotebook(id).title)
    assertEquals(listOf(0), repository.getPages(id).map(PageEntity::pageIndex))
}

@Test fun pageMovesRemainContiguous() = runTest {
    val id = repository.createNotebook(request)
    repository.addPage(id)
    repository.addPage(id)
    repository.movePage(id, fromIndex = 2, toIndex = 0)
    assertEquals(listOf(0, 1, 2), repository.getPages(id).map(PageEntity::pageIndex))
}
```

- [ ] **Step 3: Verify the checks fail**

```powershell
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.data.PerkoRepositoryTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

Expected: failure because the Room schema and repository do not exist.

- [ ] **Step 4: Implement the fixed schema**

Use `String` UUIDs and explicit columns. `ElementEntity` has typed nullable payload columns rather than JSON.

```kotlin
@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val coverColor: String,
    val coverPattern: String,
    val defaultPaper: String,
    val orientation: String,
    val fingerDrawing: Boolean,
    val favorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val trashedAt: Long?,
)

@Entity(
    tableName = "pages",
    foreignKeys = [ForeignKey(
        entity = NotebookEntity::class,
        parentColumns = ["id"], childColumns = ["notebookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["notebookId", "pageIndex"], unique = true)],
)
data class PageEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val pageIndex: Int,
    val paper: String,
    val widthPoints: Int,
    val heightPoints: Int,
)
```

Add `StrokeEntity` with brush family, ARGB color, size, epsilon, and input BLOB. Add `ElementEntity` with kind, bounds, rotation, text, asset ID, shape kind, expression, and result columns.

- [ ] **Step 5: Implement transactional ordering**

```kotlin
@Transaction
suspend fun movePage(notebookId: String, fromIndex: Int, toIndex: Int) {
    val pages = getPagesNow(notebookId).toMutableList()
    require(fromIndex in pages.indices && toIndex in pages.indices)
    pages.add(toIndex, pages.removeAt(fromIndex))
    pages.forEachIndexed { index, page -> updatePage(page.copy(pageIndex = index + 10_000)) }
    pages.forEachIndexed { index, page -> updatePage(page.copy(pageIndex = index)) }
}
```

The temporary offset avoids the unique `(notebookId, pageIndex)` constraint during reordering.

- [ ] **Step 6: Run schema export and repository checks**

```powershell
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.data.PerkoRepositoryTest --console=plain
Remove-Item Env:ANDROID_SERIAL
.\gradlew.bat :app:lintDebug :app:assembleDebug --console=plain
```

Expected: repository checks pass and Room exports schema version 1 under `app/schemas`.

- [ ] **Step 7: Commit persistence**

```text
feat(data): persist notebooks and pages
```

---

### Task 3: Deliver the notebook library flow

**Files:**
- Modify: `app/src/main/java/cz/majkey/perko/PerkoApp.kt`
- Create: `app/src/main/java/cz/majkey/perko/library/LibraryViewModel.kt`
- Create: `app/src/main/java/cz/majkey/perko/library/LibraryScreen.kt`
- Create: `app/src/main/java/cz/majkey/perko/library/CreateNotebookDialog.kt`
- Create: `app/src/main/java/cz/majkey/perko/ui/Icons.kt`
- Modify: both `strings.xml` files
- Test: `app/src/test/java/cz/majkey/perko/library/NotebookTitleTest.kt`
- Test: `app/src/androidTest/java/cz/majkey/perko/library/LibraryFlowTest.kt`

**Interfaces:**
- Consumes: `PerkoRepository` notebook operations from Task 2.
- Produces: `LibraryUiState` and `onOpenNotebook(id: String)` for the editor shell.

- [ ] **Step 1: Write title and flow checks**

```kotlin
@Test fun blankTitleUsesLocalizedFallback() {
    assertEquals("Untitled notebook", normalizeTitle("  ", "Untitled notebook"))
}

@Test fun createFavoriteTrashAndRestore() {
    rule.onNodeWithContentDescription("New notebook").performClick()
    rule.onNodeWithText("Notebook name").performTextInput("Physics")
    rule.onNodeWithText("Create").performClick()
    rule.onNodeWithText("Physics").assertIsDisplayed()
    rule.onNodeWithContentDescription("Notebook actions").performClick()
    rule.onNodeWithText("Move to trash").performClick()
    rule.onNodeWithText("Physics").assertDoesNotExist()
}
```

- [ ] **Step 2: Run both checks and observe failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*NotebookTitleTest*' --console=plain
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.library.LibraryFlowTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

- [ ] **Step 3: Implement state and direct repository wiring**

```kotlin
data class LibraryUiState(
    val query: String = "",
    val showTrash: Boolean = false,
    val notebooks: List<NotebookEntity> = emptyList(),
)

internal fun normalizeTitle(raw: String, fallback: String): String =
    raw.trim().ifEmpty { fallback }
```

Use one `AndroidViewModel`. Construct `PerkoRepository` from `PerkoDatabase.get(application)` without a factory or DI container.

- [ ] **Step 4: Implement the adaptive library**

Use `LazyVerticalGrid(GridCells.Adaptive(176.dp))` above 600 dp. Use one-column `LazyColumn` below 600 dp. Each notebook uses a paper-like cover with title, modified date, favorite marker, and one overflow action.

Creation fields are title, cover color, cover pattern, default paper, orientation, and finger drawing. Rename, favorite, trash, restore, and permanent delete call repository methods directly through the view model.

- [ ] **Step 5: Run library checks and visual smoke**

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.library.LibraryFlowTest --console=plain
Remove-Item Env:ANDROID_SERIAL
.\gradlew.bat :app:lintDebug :app:assembleDebug --console=plain
```

Expected: the checks pass and the library stays in English under English and Czech system locales.

- [ ] **Step 6: Commit the library slice**

```text
feat(library): manage local notebooks
```

---

### Task 4: Add ordered pages and the adaptive editor shell

**Files:**
- Modify: `PerkoApp.kt`
- Create: `editor/EditorViewModel.kt`
- Create: `editor/EditorScreen.kt`
- Create: `editor/PageCanvas.kt`
- Modify: both `strings.xml` files
- Test: `app/src/androidTest/java/cz/majkey/perko/editor/PageFlowTest.kt`

**Interfaces:**
- Consumes: notebook and page repository methods.
- Produces: `EditorUiState(notebook, pages, selectedPageId, fingerDrawing)` and a stable `PageCanvas(page, content, tool, callbacks)` host.

- [ ] **Step 1: Write the failing page flow**

```kotlin
@Test fun addReorderDuplicateAndDeletePages() {
    openSeedNotebook()
    rule.onNodeWithContentDescription("Add page").performClick()
    rule.onAllNodesWithTag("page-thumbnail").assertCountEquals(2)
    rule.onNodeWithContentDescription("Page 2 actions").performClick()
    rule.onNodeWithText("Duplicate page").performClick()
    rule.onAllNodesWithTag("page-thumbnail").assertCountEquals(3)
}
```

- [ ] **Step 2: Run and verify failure**

```powershell
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.editor.PageFlowTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

- [ ] **Step 3: Implement the screen switch and editor state**

`PerkoApp` stores one nullable notebook ID with `rememberSaveable`. A non-null ID shows `EditorScreen`; back clears it. No navigation dependency is needed.

```kotlin
data class EditorUiState(
    val notebook: NotebookEntity? = null,
    val pages: List<PageEntity> = emptyList(),
    val selectedPageId: String? = null,
    val fingerDrawing: Boolean = false,
)
```

- [ ] **Step 4: Implement tablet and phone shells**

Use a persistent 144 dp thumbnail rail when width is at least 840 dp. Use a modal drawer below 840 dp. The canvas fills remaining space. Tool controls remain at least 48 dp.

Use `AnimatedContent` keyed by page ID with a 220 ms horizontal slide and shadow change. When animator duration scale is zero, use `EnterTransition.None` and `ExitTransition.None`.

- [ ] **Step 5: Run page and regression checks**

```powershell
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.editor.PageFlowTest --console=plain
Remove-Item Env:ANDROID_SERIAL
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Expected: page CRUD passes and the library flow still passes when run in the full connected suite.

- [ ] **Step 6: Commit the editor shell**

```text
feat(editor): add paged notebook shell
```

---

### Task 5: Implement the safe arithmetic engine

**Files:**
- Create: `app/src/main/java/cz/majkey/perko/editor/MathEvaluator.kt`
- Test: `app/src/test/java/cz/majkey/perko/editor/MathEvaluatorTest.kt`

**Interfaces:**
- Produces: `internal fun evaluateExpression(source: String): Result<Double>`.
- Produces: `internal fun formatMathResult(value: Double): String`.

- [ ] **Step 1: Write exhaustive parser checks**

```kotlin
@Test fun precedenceAndParentheses() {
    assertEquals(14.0, evaluateExpression("2+3*4=").getOrThrow())
    assertEquals(20.0, evaluateExpression("(2+3)*4=").getOrThrow())
}

@Test fun unicodeOperatorsAndPower() {
    assertEquals(7.0, evaluateExpression("18÷3+1=").getOrThrow())
    assertEquals(8.0, evaluateExpression("2^3=").getOrThrow())
}

@Test fun invalidInputsFail() {
    assertTrue(evaluateExpression("1/0=").isFailure)
    assertTrue(evaluateExpression("2+=").isFailure)
    assertTrue(evaluateExpression("1".repeat(257) + "=").isFailure)
}
```

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*MathEvaluatorTest*' --console=plain
```

- [ ] **Step 3: Implement one recursive-descent parser**

Normalize `×` to `*` and `÷` to `/`. Parse expression, term, power, unary, and primary functions. Require an ending `=`, consume the complete input, cap length at 256, reject zero divisors, and require `Double.isFinite()`.

```kotlin
internal fun evaluateExpression(source: String): Result<Double> = runCatching {
    require(source.length <= 256 && source.endsWith('='))
    Parser(source.dropLast(1).replace('×', '*').replace('÷', '/'))
        .parse()
        .also { require(it.isFinite()) }
}
```

- [ ] **Step 4: Run parser checks**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*MathEvaluatorTest*' --console=plain
```

Expected: all valid and invalid checks pass.

- [ ] **Step 5: Commit math**

```text
feat(math): evaluate local arithmetic
```

---

### Task 6: Capture, render, and persist low-latency ink

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `editor/InkCodec.kt`
- Create: `editor/InkCanvasView.kt`
- Modify: `editor/PageCanvas.kt`
- Modify: `editor/EditorViewModel.kt`
- Test: `app/src/androidTest/java/cz/majkey/perko/editor/InkCodecTest.kt`
- Test: `app/src/androidTest/java/cz/majkey/perko/editor/StylusRoutingTest.kt`

**Interfaces:**
- Produces: `InkCodec.encode(stroke: Stroke): EncodedStroke` and `InkCodec.decode(encoded: EncodedStroke): Stroke`.
- Produces: `InkCanvasView.Listener.onStrokeFinished(stroke: Stroke)` and `onStrokeCanceled(pointerId: Int)`.

```kotlin
internal data class EncodedStroke(
    val brushKind: BrushKind,
    val colorArgb: Int,
    val size: Float,
    val epsilon: Float,
    val inputs: ByteArray,
)

internal enum class BrushKind { PRESSURE_PEN, MARKER, HIGHLIGHTER }
```

- [ ] **Step 1: Add only the stable Ink modules**

```kotlin
implementation("androidx.ink:ink-authoring:1.0.0")
implementation("androidx.ink:ink-brush:1.0.0")
implementation("androidx.ink:ink-rendering:1.0.0")
implementation("androidx.ink:ink-storage:1.0.0")
implementation("androidx.ink:ink-strokes:1.0.0")
implementation("androidx.input:input-motionprediction:1.0.0")
```

- [ ] **Step 2: Write failing round-trip and routing checks**

```kotlin
@Test fun encodedStrokeRoundTripsBrushAndInputs() {
    val encoded = InkCodec.encode(samplePressureStroke())
    val decoded = InkCodec.decode(encoded)
    assertEquals(encoded.colorArgb, decoded.brush.colorIntArgb)
    assertEquals(samplePressureStroke().inputs, decoded.inputs)
}

@Test fun canceledPalmStrokeIsNotCommitted() {
    canvas.dispatchTouchEvent(stylusDown())
    canvas.dispatchTouchEvent(stylusMove(pressure = .7f, tilt = .4f))
    canvas.dispatchTouchEvent(stylusUp(flags = MotionEvent.FLAG_CANCELED))
    assertTrue(listener.finished.isEmpty())
}
```

- [ ] **Step 3: Verify failure**

```powershell
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.editor.InkCodecTest,cz.majkey.perko.editor.StylusRoutingTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

- [ ] **Step 4: Implement stable Ink serialization**

```kotlin
internal object InkCodec {
    fun encode(stroke: Stroke): EncodedStroke {
        val bytes = ByteArrayOutputStream().use { output ->
            stroke.inputs.encode(output)
            output.toByteArray()
        }
        return EncodedStroke(brushKind(stroke.brush), stroke.brush.colorIntArgb,
            stroke.brush.size, stroke.brush.epsilon, bytes)
    }

    fun decode(value: EncodedStroke): Stroke {
        val inputs = ByteArrayInputStream(value.inputs).use { input ->
            StrokeInputBatch.decode(input)
        }
        return Stroke(createBrush(value), inputs)
    }
}
```

Pin stock brush family version V1 for pressure pen, marker, and highlighter so saved ink keeps the same appearance after library upgrades.

- [ ] **Step 5: Implement the Android view stack**

`InkCanvasView` owns a finished-stroke drawing view and an `InProgressStrokesView`. Use `MotionEventPredictor.newInstance(this)`. Route `ACTION_DOWN`, `ACTION_MOVE`, `ACTION_UP`, and `ACTION_CANCEL` to `startStroke`, `addToStroke`, `finishStroke`, and `cancelStroke`.

Cancel when either condition is true:

```kotlin
event.actionMasked == MotionEvent.ACTION_CANCEL ||
    event.flags and MotionEvent.FLAG_CANCELED != 0
```

Accept `TOOL_TYPE_STYLUS` and `TOOL_TYPE_ERASER`. Accept touch only when `fingerDrawing` is true. A second pointer cancels the active stroke and returns control to page pan and zoom.

- [ ] **Step 6: Save finished strokes off the main thread**

In the same UI loop, add finished strokes to the finished renderer, call `invalidate()`, and remove their IDs from `InProgressStrokesView`. Send encoded records to `EditorViewModel`; perform Room writes on `Dispatchers.IO`.

- [ ] **Step 7: Run ink gates**

```powershell
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.editor.InkCodecTest,cz.majkey.perko.editor.StylusRoutingTest --console=plain
Remove-Item Env:ANDROID_SERIAL
.\gradlew.bat :app:lintDebug :app:assembleDebug --console=plain
```

Expected: pressure survives serialization, palm cancellation leaves no stroke, eraser tool type selects erasing, and a saved stroke reappears after activity recreation.

- [ ] **Step 8: Commit inking**

```text
feat(ink): persist low-latency strokes
```

---

### Task 7: Add bounded undo, lasso, and stroke erasing

**Files:**
- Create: `editor/PageHistory.kt`
- Create: `editor/StrokeHitTest.kt`
- Modify: `editor/EditorViewModel.kt`
- Modify: `editor/EditorScreen.kt`
- Modify: `editor/InkCanvasView.kt`
- Test: `app/src/test/java/cz/majkey/perko/editor/PageHistoryTest.kt`
- Test: `app/src/test/java/cz/majkey/perko/editor/StrokeHitTestTest.kt`

**Interfaces:**
- Produces: `PageHistory<T>.push`, `undo`, and `redo` with a fixed 100-state limit.
- Produces: `selectStrokes(lasso: List<PointF>, strokes: List<StrokeEntity>): Set<String>` and `hitStroke(point: PointF, radius: Float, stroke: StrokeEntity): Boolean`.

- [ ] **Step 1: Write history and geometry checks**

```kotlin
@Test fun historyDropsOldestStateAtOneHundred() {
    val history = PageHistory(0)
    repeat(101) { history.push(it + 1) }
    repeat(100) { history.undo() }
    assertEquals(1, history.current)
}

@Test fun lassoSelectsStrokeWithMajorityInside() {
    assertEquals(setOf("inside"), selectStrokes(square, listOf(insideStroke, outsideStroke)))
}
```

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*PageHistoryTest*' --tests '*StrokeHitTestTest*' --console=plain
```

- [ ] **Step 3: Implement immutable snapshot history**

Store lists of immutable canvas object references. Copy list containers, not stroke BLOBs. Clear redo on every new edit. Remove the oldest undo state after 100 entries.

- [ ] **Step 4: Implement direct geometry**

Use ray casting for point-in-polygon. Select a stroke when at least half of sampled input points fall inside the lasso. Use squared distance to line segments for the stroke eraser. Do not rasterize or add a geometry dependency.

- [ ] **Step 5: Wire toolbar behavior**

Tool order: pen, pencil, highlighter, stroke eraser, lasso, text, image, shape. Undo and redo stay separate. Lasso moves selected objects by updating their transform in one Room transaction.

- [ ] **Step 6: Run checks and live editor regression**

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
Remove-Item Env:ANDROID_SERIAL
.\gradlew.bat :app:lintDebug :app:assembleDebug --console=plain
```

- [ ] **Step 7: Commit editing tools**

```text
feat(editor): add lasso and stroke history
```

---

### Task 8: Insert text and validated private images

**Files:**
- Create: `data/AssetStore.kt`
- Create: `editor/ImageImporter.kt`
- Modify: `data/PerkoRepository.kt`
- Modify: `editor/PageCanvas.kt`
- Modify: `editor/EditorScreen.kt`
- Modify: `editor/EditorViewModel.kt`
- Test: `app/src/androidTest/java/cz/majkey/perko/editor/ImageImporterTest.kt`
- Test: `app/src/androidTest/java/cz/majkey/perko/editor/ElementFlowTest.kt`

**Interfaces:**
- Produces: `suspend fun ImageImporter.import(uri: Uri): Result<ImportedAsset>`.
- Produces: `ImportedAsset(id: String, mimeType: String, width: Int, height: Int, file: File)`.
- Extends repository with `upsertElement`, `deleteElement`, `transformElement`, and full notebook duplication including private media.

- [ ] **Step 1: Write valid, corrupt, and oversized image checks**

```kotlin
@Test fun pngIsCopiedToPrivateStorage() = runTest {
    val asset = importer.import(validPngUri).getOrThrow()
    assertEquals("image/png", asset.mimeType)
    assertTrue(asset.file.canonicalPath.startsWith(context.filesDir.canonicalPath))
}

@Test fun corruptImageLeavesNoPrivateFile() = runTest {
    assertTrue(importer.import(corruptPngUri).isFailure)
    assertTrue(assetDirectory.listFiles().orEmpty().isEmpty())
}
```

- [ ] **Step 2: Run and verify failure**

```powershell
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.editor.ImageImporterTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

- [ ] **Step 3: Implement boundary validation**

Allow only JPEG, PNG, WebP, and HEIF MIME types. Read image bounds before pixels. Reject zero dimensions, dimensions above 16,384 pixels, or decoded allocation estimates above 128 MiB. Copy to a temporary file under `filesDir/assets`, decode once, then atomically rename to the generated asset ID.

- [ ] **Step 4: Implement text and image elements**

Text boxes use an outlined editor only while selected. Persist plain UTF-8 text, bounds, rotation, and z-index. Images use `ContentScale.Fit`; transform gestures clamp the object so at least 24 dp remains on the page.

- [ ] **Step 5: Complete notebook duplication**

Create new notebook, page, stroke, and element IDs in one Room transaction. Copy referenced assets before committing the transaction. On database failure, delete only newly copied asset files.

- [ ] **Step 6: Run element checks**

```powershell
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.editor.ImageImporterTest,cz.majkey.perko.editor.ElementFlowTest --console=plain
Remove-Item Env:ANDROID_SERIAL
.\gradlew.bat :app:lintDebug :app:assembleDebug --console=plain
```

Expected: valid media persists, corrupt media fails visibly, transforms survive restart, and duplicated notebooks do not share mutable asset files.

- [ ] **Step 7: Commit elements**

```text
feat(editor): add text and image elements
```

---

### Task 9: Clean shapes with local geometry and optional ML Kit

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `editor/DigitalInkModels.kt`
- Create: `editor/ShapeRecognizer.kt`
- Modify: `editor/EditorViewModel.kt`
- Modify: `editor/EditorScreen.kt`
- Test: `app/src/test/java/cz/majkey/perko/editor/LineRecognizerTest.kt`
- Test: `app/src/androidTest/java/cz/majkey/perko/editor/ShapeRecognizerTest.kt`

**Interfaces:**
- Produces: `sealed interface ShapeResult` with `Recognized(kind, confidence)`, `Ambiguous`, and `ModelMissing`.
- Produces: `suspend fun recognizeShape(strokes: List<StrokeEntity>): ShapeResult`.
- Produces: `suspend fun downloadModel(tag: String): Result<Unit>` and `suspend fun isDownloaded(tag: String): Boolean`.

- [ ] **Step 1: Add ML Kit**

```kotlin
implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
```

- [ ] **Step 2: Write line and missing-model checks**

```kotlin
@Test fun nearlyStraightStrokeBecomesLine() {
    assertEquals(ShapeKind.LINE, recognizeLine(strokeWithMaxDeviation(.012f)))
}

@Test fun curvedStrokeDoesNotBecomeLine() {
    assertNull(recognizeLine(strokeWithMaxDeviation(.18f)))
}

@Test fun absentShapeModelKeepsOriginalInk() = runTest {
    assertEquals(ShapeResult.ModelMissing, recognizer.recognizeShape(circleInk))
}
```

- [ ] **Step 3: Run and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*LineRecognizerTest*' --console=plain
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.editor.ShapeRecognizerTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

- [ ] **Step 4: Implement the model manager with official identifiers**

Create identifiers through `DigitalInkRecognitionModelIdentifier.fromLanguageTag`. Use `zxx-Zsym-x-shapes` for shapes, `cs` for Czech, and `en-US` for English. Use `RemoteModelManager.isModelDownloaded` before recognition. Download only from an explicit dialog action.

- [ ] **Step 5: Implement recognition and confidence handling**

Convert selected AndroidX Ink points to ML Kit `Ink`. Lower ML Kit scores are better. Accept `RECTANGLE`, `TRIANGLE`, `ARROW`, or `ELLIPSE` only when both scores exist and `second.score - first.score >= MIN_SHAPE_SCORE_MARGIN`. Start `MIN_SHAPE_SCORE_MARGIN` at `0.5f`, keep it in `ShapeRecognizer.kt`, and verify it against the checked-in clear and ambiguous shape fixtures. If scores are absent or the margin is smaller, return `Ambiguous`.

- [ ] **Step 6: Preview replacement and preserve undo**

Draw the clean shape as a preview. Confirm replaces the selected ink in one history action. Cancel, ambiguity, model failure, and network failure leave original strokes unchanged.

- [ ] **Step 7: Run shape gates**

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.editor.ShapeRecognizerTest --console=plain
Remove-Item Env:ANDROID_SERIAL
.\gradlew.bat :app:lintDebug :app:assembleDebug --console=plain
```

- [ ] **Step 8: Commit shape cleanup**

```text
feat(shapes): clean local ink geometry
```

---

### Task 10: Connect handwriting recognition to arithmetic

**Files:**
- Modify: `editor/DigitalInkModels.kt`
- Modify: `editor/EditorViewModel.kt`
- Modify: `editor/EditorScreen.kt`
- Modify: both `strings.xml` files
- Test: `app/src/androidTest/java/cz/majkey/perko/editor/HandwrittenMathTest.kt`

**Interfaces:**
- Consumes: `evaluateExpression`, selected stroke conversion, and Czech or English model manager.
- Produces: `suspend fun recognizeText(strokes, languageTag, writingArea): RecognitionOutcome`.

- [ ] **Step 1: Write success and failure checks**

```kotlin
@Test fun recognizedArithmeticCreatesResultElement() = runTest {
    viewModel.applyRecognizedMath("2+3=", selectedInk)
    assertEquals("5", viewModel.state.value.elements.single().resultText)
}

@Test fun invalidRecognitionKeepsInk() = runTest {
    viewModel.applyRecognizedMath("two plus", selectedInk)
    assertTrue(viewModel.state.value.strokes.containsAll(selectedInk))
    assertEquals(EditorMessage.InvalidExpression, viewModel.state.value.message)
}
```

- [ ] **Step 2: Run and verify failure**

```powershell
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.editor.HandwrittenMathTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

- [ ] **Step 3: Implement the direct recognition path**

Build ML Kit `Ink` from the selected strokes in original time order. Set `WritingArea` from the selection bounds. Pass nearby typed math text as pre-context only when present. Take the top candidate and call `applyRecognizedMath(text, sourceStrokes)`. That function normalizes common multiplication and division glyphs, requires a final `=`, then calls `evaluateExpression`.

- [ ] **Step 4: Render an editable result**

Create a math element next to the selection with original expression and formatted result. Keep the source ink. Recalculation replaces the result for the same expression element rather than stacking duplicates.

- [ ] **Step 5: Run math and model checks**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*MathEvaluatorTest*' --console=plain
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.editor.HandwrittenMathTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

- [ ] **Step 6: Commit handwriting math**

```text
feat(math): recognize handwritten arithmetic
```

---

### Task 11: Export every page to PDF safely

**Files:**
- Create: `editor/PdfExporter.kt`
- Create: `editor/PageRenderer.kt`
- Modify: `editor/EditorViewModel.kt`
- Modify: `editor/EditorScreen.kt`
- Test: `app/src/androidTest/java/cz/majkey/perko/editor/PdfExporterTest.kt`

**Interfaces:**
- Produces: `suspend fun PdfExporter.export(notebookId: String, destination: Uri): Result<ExportSummary>`.
- Produces: `ExportSummary(pageCount: Int, byteCount: Long)`.

- [ ] **Step 1: Write complete and interrupted export checks**

```kotlin
@Test fun exportWritesEveryPage() = runTest {
    val result = exporter.export(threePageNotebook, destination).getOrThrow()
    assertEquals(3, result.pageCount)
    context.contentResolver.openFileDescriptor(destination, "r")!!.use { descriptor ->
        PdfRenderer(descriptor).use { renderer -> assertEquals(3, renderer.pageCount) }
    }
}

@Test fun failedExportDoesNotAlterNotebook() = runTest {
    val before = repository.loadNotebook(notebookId)
    assertTrue(exporter.export(notebookId, failingUri).isFailure)
    val after = repository.loadNotebook(notebookId)
    assertEquals(before.notebook, after.notebook)
    assertEquals(before.pages, after.pages)
    assertEquals(before.elements, after.elements)
    before.strokes.zip(after.strokes).forEach { (old, new) ->
        assertEquals(old.id, new.id)
        assertContentEquals(old.inputs, new.inputs)
    }
}
```

- [ ] **Step 2: Run and verify failure**

```powershell
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.editor.PdfExporterTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

- [ ] **Step 3: Implement one shared page renderer**

Extract the finished-page draw order from `PageCanvas` into a renderer used by both the on-screen canvas and `PdfExporter`: paper, images, shapes, text, math, then ink by z-index. Use Android `PdfDocument`, `ContentResolver.openOutputStream`, and `Dispatchers.IO`. Always close the page, document, and stream in `use` or `finally` blocks.

Do not add a PDF dependency. Inspect page count with Android `PdfRenderer`.

- [ ] **Step 4: Use the system destination picker**

Launch `ActivityResultContracts.CreateDocument("application/pdf")` with a sanitized notebook title. Show success with page count. Show failure without changing notebook state.

- [ ] **Step 5: Run export gates**

```powershell
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.editor.PdfExporterTest --console=plain
Remove-Item Env:ANDROID_SERIAL
.\gradlew.bat :app:lintDebug :app:assembleDebug --console=plain
```

- [ ] **Step 6: Commit PDF export**

```text
feat(export): write paginated notebook PDFs
```

---

### Task 12: Add full settings, App details, support, and accessibility

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `settings/AppSettings.kt`
- Create: `settings/SettingsRepository.kt`
- Create: `settings/SettingsScreen.kt`
- Create: `settings/AppDetailsSection.kt`
- Create: `app/src/main/res/drawable/ic_coffee.xml`
- Modify: `PerkoApp.kt`, all screen and theme files, and both `strings.xml` files
- Test: `app/src/test/java/cz/majkey/perko/settings/SupportUrlTest.kt`
- Test: `app/src/androidTest/java/cz/majkey/perko/settings/SettingsRepositoryTest.kt`
- Test: `app/src/androidTest/java/cz/majkey/perko/settings/SettingsFlowTest.kt`
- Test: `app/src/androidTest/java/cz/majkey/perko/AccessibilityTest.kt`

**Interfaces:**
- Produces: `AppSettings`, `SettingsRepository.settings: Flow<AppSettings>`, and `suspend fun update(transform: (AppSettings) -> AppSettings)`.
- Produces: `internal const val SUPPORT_URL = "https://www.buymeacoffee.com/majkey"`.
- Produces: `SettingsScreen(onClose)` available from the library and editor.

- [ ] **Step 1: Add Preferences DataStore and define validated settings**

```kotlin
implementation("androidx.datastore:datastore-preferences:1.2.1")
```

```kotlin
internal data class AppSettings(
    val defaultTool: DrawingTool = DrawingTool.PEN,
    val penWidth: Float = 3f,
    val penColor: Int = 0xFF202124.toInt(),
    val highlighterWidth: Float = 16f,
    val highlighterColor: Int = 0x66FFDD00,
    val fingerDrawing: Boolean = false,
    val stylusButtonEraser: Boolean = true,
    val shapeHoldMillis: Int = 650,
    val haptics: Boolean = true,
    val paper: PaperTemplate = PaperTemplate.RULED,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
    val pageSize: PageSize = PageSize.A4,
    val paperTint: PaperTint = PaperTint.WARM,
    val pageShadow: Boolean = true,
    val includeCoverInPdf: Boolean = true,
    val pageTransition: Boolean = true,
    val handwritingLanguage: HandwritingLanguage = HandwritingLanguage.CZECH,
    val handwrittenMath: Boolean = true,
    val shapeCleanup: Boolean = true,
    val theme: AppTheme = AppTheme.SYSTEM,
    val leftHandedToolbar: Boolean = false,
    val largeControls: Boolean = false,
    val reduceMotion: ReduceMotion = ReduceMotion.SYSTEM,
    val trashRetentionDays: Int? = 30,
)
```

Clamp pen and highlighter widths, shape hold delay, and trash choices while reading DataStore. Invalid enum wires fall back to defaults. Autosave has no switch.

- [ ] **Step 2: Write failing persistence, support, and UI checks**

```kotlin
@Test fun supportUsesThePublishedBuyMeACoffeePage() {
    assertEquals("https://www.buymeacoffee.com/majkey", SUPPORT_URL)
}

@Test fun invalidShapeDelayFallsBackToDefault() = runTest {
    writeRawPreference("shape_hold_ms", 99)
    assertEquals(650, repository.settings.first().shapeHoldMillis)
}

@Test fun appDetailsContainsSupportAndVersion() {
    openSettings()
    rule.onNodeWithText("App details").performClick()
    rule.onNodeWithText("Support this app → Buy Me a Coffee").assertIsDisplayed()
    rule.onNodeWithText("Version 0.1.0-beta.1").assertIsDisplayed()
}
```

- [ ] **Step 3: Run and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SupportUrlTest*' --console=plain
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.settings.SettingsRepositoryTest,cz.majkey.perko.settings.SettingsFlowTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

- [ ] **Step 4: Implement grouped Settings**

Use one `LazyColumn` with expandable Drawing, Paper, Recognition, Export, Interface, Data, and App details groups. Drawing starts expanded. Every setting writes immediately through `SettingsRepository.update`; errors remain visible. Open Settings from both app bars.

Recognition shows independent Czech, English, and Shapes model rows with Download or Remove actions and explicit downloading, ready, missing, offline, and failure states. Data shows private asset and database byte counts, trash retention, and a confirmed Empty trash action.

- [ ] **Step 5: Reuse the exact verified ScanIt support control inside App details**

Use the same URL, notice behavior, colors, border, size, spacing, and coffee vector as ScanIt. Rename only the app-specific string resource.

```kotlin
internal const val SUPPORT_URL = "https://www.buymeacoffee.com/majkey"

Button(
    onClick = {
        Toast.makeText(context, supportNotice, Toast.LENGTH_SHORT).show()
        uriHandler.openUri(SUPPORT_URL)
    },
    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    border = BorderStroke(1.dp, Color(0xFF111111)),
    colors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFFFFDD00),
        contentColor = Color(0xFF111111),
    ),
) {
    Icon(painterResource(R.drawable.ic_coffee), contentDescription = null,
        modifier = Modifier.size(24.dp))
    Spacer(Modifier.width(10.dp))
    Text(stringResource(R.string.support_perko))
}
```

The expanded section also shows installed version, privacy, third-party notices, source code, AndroidX Ink and Google ML Kit disclosures, and the emulator-only beta limit. Support unlocks nothing and changes no state.

- [ ] **Step 6: Apply visual, localization, keyboard, and accessibility requirements**

Use warm paper `#FBF8F1`, workspace `#ECEAE5`, graphite `#202124`, cobalt `#3156D9`, and error `#B3261E`. Use system typography. Keep page corners at 2 dp, controls at 8 to 12 dp, and no gradient or glass effect.

No visible string remains hardcoded. The interface stays English under every system locale. Map Ctrl+Z, Ctrl+Shift+Z, PageUp, and PageDown. Verify visible focus. Respect Android animator scale and the Reduce motion setting.

- [ ] **Step 7: Run settings and accessibility gates**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cz.majkey.perko.settings.SettingsRepositoryTest,cz.majkey.perko.settings.SettingsFlowTest,cz.majkey.perko.AccessibilityTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

- [ ] **Step 8: Commit settings and polish**

```text
feat(settings): add app details and controls
```

---

### Task 13: Run API 29 and API 37 acceptance QA

**Files:**
- Create: `docs/qa/2026-08-24-emulator-acceptance.md`
- Create: `docs/qa/screenshots/*.png`
- Modify tests only if a real product defect requires a root-cause fix.

**Interfaces:**
- Produces: acceptance evidence for every success criterion and release screenshot assets.

- [ ] **Step 1: Start dedicated emulators without visible helper windows**

```powershell
Start-Process -FilePath 'C:\Users\mates\AppData\Local\Android\Sdk\emulator\emulator.exe' -ArgumentList '-avd Perko_Tablet_API_37 -port 5590 -no-snapshot-save' -WindowStyle Hidden
Start-Process -FilePath 'C:\Users\mates\AppData\Local\Android\Sdk\emulator\emulator.exe' -ArgumentList '-avd Perko_Phone_API_29 -port 5592 -no-snapshot-save' -WindowStyle Hidden
```

Wait with bounded ADB polling until both report `sys.boot_completed=1`.

- [ ] **Step 2: Enable official emulator stylus simulation on API 37**

```powershell
adb -s emulator-5590 shell setprop persist.debug.input.simulate_stylus_with_touch true
adb -s emulator-5590 shell stop
adb -s emulator-5590 shell start
```

Expected: touch events report stylus tool type after Android restarts.

- [ ] **Step 3: Install the exact debug build on both targets**

```powershell
adb -s emulator-5590 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5592 install -r app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 4: Execute the four required live scenarios**

On API 37 tablet: create a notebook, draw pressure strokes, add text and a valid image, calculate `2+3=`, add and reorder pages, change drawing and paper settings, verify App details, verify the Buy Me a Coffee external intent, export PDF, force-stop, relaunch, and verify persistence.

On API 29 phone: create a touch-enabled notebook, draw with a finger, use the document-picker fallback, test compact page navigation, corrupt image rejection, invalid math, division by zero, and interrupted export.

Regression: create a second notebook, reopen the first, and continue editing its existing page.

- [ ] **Step 5: Capture tree-based evidence and screenshots**

Use `uiautomator dump` before every coordinate action. Capture library, creation, tablet editor, phone editor, shape preview, and exported-PDF evidence. Save screenshots under `docs/qa/screenshots` and record exact commands and outcomes in the acceptance document.

- [ ] **Step 6: Inspect crashes and app logs**

```powershell
adb -s emulator-5590 logcat -b crash -d
adb -s emulator-5592 logcat -b crash -d
```

Expected: no fresh `cz.majkey.perko` crash. Do not claim physical stylus latency.

- [ ] **Step 7: Run final gates after the last code change**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
$env:ANDROID_SERIAL = 'emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
Remove-Item Env:ANDROID_SERIAL
```

- [ ] **Step 8: Commit QA evidence**

```text
test: verify phone and tablet workflows
```

---

### Task 14: Publish the repository and verified beta release

**Files:**
- Create: `README.md`
- Create: `CHANGELOG.md`
- Create: `PRIVACY.md`
- Create: `THIRD_PARTY_NOTICES.md`
- Create: `LICENSE`
- Create: `.github/workflows/android.yml`
- Create: `.github/workflows/release.yml`
- Create: `docs/release/v0.1.0-beta.1.md`

**Interfaces:**
- Produces: public `Majkey25/Perko`, passing CI, tag `v0.1.0-beta.1`, and a signed APK with a verified SHA-256.

- [ ] **Step 1: Write public documentation from verified behavior**

README sections: product screenshot, exact features, Android 10 or newer requirement, install steps, privacy, known beta limits, development commands, and license. Badges: Android CI, latest release, API 29+, Apache-2.0. Do not claim physical stylus measurements.

`PRIVACY.md` states that notebooks stay on device, imported files are copied privately, and ML Kit downloads models but recognition runs locally.

- [ ] **Step 2: Add CI with exact gates**

`android.yml` uses JDK 17, installs platform 37.0, restores Gradle cache, and runs:

```text
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
```

`release.yml` triggers on `v*`, verifies `versionName`, builds `app-release-unsigned.apk`, and signs it from encrypted GitHub secrets. The signing step uses the Android SDK `apksigner` and writes `Perko-v0.1.0-beta.1.apk`. The workflow calculates `Perko-v0.1.0-beta.1.apk.sha256` and attaches both files to one GitHub release.

```yaml
- name: Restore signing key
  shell: bash
  env:
    KEYSTORE_B64: ${{ secrets.PERKO_KEYSTORE }}
  run: printf '%s' "$KEYSTORE_B64" | base64 --decode > "$RUNNER_TEMP/perko-release.p12"
- name: Sign APK
  shell: bash
  env:
    STORE_PASSWORD: ${{ secrets.PERKO_STORE_PASSWORD }}
    KEY_PASSWORD: ${{ secrets.PERKO_KEY_PASSWORD }}
  run: >-
    "$ANDROID_HOME/build-tools/37.0.0/apksigner" sign
    --ks "$RUNNER_TEMP/perko-release.p12"
    --ks-key-alias perko
    --ks-pass env:STORE_PASSWORD
    --key-pass env:KEY_PASSWORD
    --out Perko-v0.1.0-beta.1.apk
    app/build/outputs/apk/release/app-release-unsigned.apk
```

- [ ] **Step 3: Create the public repository without pushing**

```powershell
gh repo create Majkey25/Perko --public --description 'Offline stylus-first notebooks for Android 10 and newer'
```

Expected: `gh repo view Majkey25/Perko` returns the new empty repository. Do not push before local gates pass.

- [ ] **Step 4: Generate dedicated signing material securely**

Generate one PKCS12 keystore under the task's `work` directory, outside the repository. `keytool` prompts for the store password and key password without echoing them.

```powershell
New-Item -ItemType Directory -Force -Path 'C:\Users\mates\Documents\Codex\2026-08-24\p-ko-j-pot-ebuju-ud\work\perko-signing'
keytool -genkeypair -keystore 'C:\Users\mates\Documents\Codex\2026-08-24\p-ko-j-pot-ebuju-ud\work\perko-signing\perko-release.p12' -storetype PKCS12 -alias perko -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Majkey25, O=Majkey25, C=CZ'
certutil -encodehex 'C:\Users\mates\Documents\Codex\2026-08-24\p-ko-j-pot-ebuju-ud\work\perko-signing\perko-release.p12' 'C:\Users\mates\Documents\Codex\2026-08-24\p-ko-j-pot-ebuju-ud\work\perko-signing\perko-release.b64' 0x40000001
Get-Content -Raw -LiteralPath 'C:\Users\mates\Documents\Codex\2026-08-24\p-ko-j-pot-ebuju-ud\work\perko-signing\perko-release.b64' | gh secret set PERKO_KEYSTORE --repo Majkey25/Perko
gh secret set PERKO_STORE_PASSWORD --repo Majkey25/Perko
gh secret set PERKO_KEY_PASSWORD --repo Majkey25/Perko
```

Enter the same hidden password values used by `keytool` when `gh` prompts. Do not add the external signing directory to Git because it is outside the repository. Confirm that no keystore path or credential appears in the staged diff.

- [ ] **Step 5: Re-run local release checks**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain
& 'C:\Users\mates\AppData\Local\Android\Sdk\build-tools\37.0.0\apksigner.bat' sign --ks 'C:\Users\mates\Documents\Codex\2026-08-24\p-ko-j-pot-ebuju-ud\work\perko-signing\perko-release.p12' --ks-key-alias perko --out 'C:\Users\mates\Documents\Codex\2026-08-24\p-ko-j-pot-ebuju-ud\work\perko-signing\Perko-v0.1.0-beta.1.apk' app\build\outputs\apk\release\app-release-unsigned.apk
& 'C:\Users\mates\AppData\Local\Android\Sdk\build-tools\37.0.0\apksigner.bat' verify --verbose --print-certs 'C:\Users\mates\Documents\Codex\2026-08-24\p-ko-j-pot-ebuju-ud\work\perko-signing\Perko-v0.1.0-beta.1.apk'
Get-FileHash -Algorithm SHA256 'C:\Users\mates\Documents\Codex\2026-08-24\p-ko-j-pot-ebuju-ud\work\perko-signing\Perko-v0.1.0-beta.1.apk'
```

- [ ] **Step 6: Connect and push the verified repository**

```powershell
git remote add origin https://github.com/Majkey25/Perko.git
git push -u origin main
```

Wait for Android CI. If it fails, inspect the real logs, fix the root cause, rerun local gates, commit, and push.

- [ ] **Step 7: Tag only the verified commit**

```powershell
git tag -a v0.1.0-beta.1 -m "Release v0.1.0-beta.1"
git push origin v0.1.0-beta.1
```

- [ ] **Step 8: Download and verify the published assets**

Use `gh release download v0.1.0-beta.1` into a clean directory. Verify package ID, version code, version name, signature, SHA-256 file, and checksum equality. Install that downloaded APK on `emulator-5590`, launch it, reopen the saved acceptance notebook, and confirm no fresh crash.

- [ ] **Step 9: Record immutable release evidence**

Write the tag, commit, workflow URL, artifact names, signature certificate SHA-256, APK SHA-256, install result, launch result, and known emulator-only limit in `docs/release/v0.1.0-beta.1.md`.

- [ ] **Step 10: Commit and push release evidence only if the tag workflow allows post-release documentation**

```text
docs: record beta release evidence
```

If adding evidence would make the documented commit differ from the tag, keep the evidence in the GitHub release body instead. Do not move or recreate the published tag.

## Completion gate

- [ ] Every spec success criterion maps to passing automated or live evidence.
- [ ] API 29 phone and API 37 tablet scenarios pass.
- [ ] The full unit, lint, debug, release, and connected commands exit zero after the final code change.
- [ ] The public CI run passes for the tagged commit.
- [ ] The downloaded release APK matches its checksum and signature, installs, launches, and reopens saved content.
- [ ] `git status --short --branch` is clean.
- [ ] No secrets, signing files, local SDK paths, generated build output, or unrelated files are tracked.
