# Visual Notebook Creation and Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make notebook creation and stored defaults immediately understandable through reusable live illustrations and a clearer settings hierarchy.

**Architecture:** Add one pure template mapping and one reusable Compose illustration file backed by existing cover, paper, and orientation enums. The creation dialog and Settings reuse those components; Room and DataStore formats remain unchanged. Existing behavior stays accessible through labeled visual choices.

**Tech Stack:** Kotlin 2.3.20, Jetpack Compose UI 1.12.0, Material 3 1.4.0, Preferences DataStore 1.2.1, JUnit 4, Compose UI Test.

---

### Task 1: Define notebook templates as pure mappings

**Files:**
- Create: `app/src/main/java/cz/majkey/perko/library/NotebookTemplate.kt`
- Create: `app/src/test/java/cz/majkey/perko/library/NotebookTemplateTest.kt`

- [ ] **Step 1: Write the failing mapping test**

```kotlin
@Test fun templatesMapToRealNotebookFields() {
    assertEquals(PaperTemplate.RULED, NotebookTemplate.RULED_NOTES.paper)
    assertEquals(CoverColor.SAGE, NotebookTemplate.GRID_NOTEBOOK.coverColor)
    assertEquals(PaperTemplate.DOT, NotebookTemplate.DOTTED_JOURNAL.paper)
    assertEquals(PageOrientation.LANDSCAPE, NotebookTemplate.BLANK_SKETCHBOOK.orientation)
}

@Test fun customDetectionUsesAllMappedFields() {
    val template = NotebookTemplate.RULED_NOTES
    assertTrue(template.matches(template.coverColor, template.coverPattern, template.paper, template.orientation))
    assertFalse(template.matches(template.coverColor, template.coverPattern, PaperTemplate.GRID, template.orientation))
}
```

- [ ] **Step 2: Run the test and verify red**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*NotebookTemplateTest*' --console=plain
```

Expected: compilation fails because `NotebookTemplate` does not exist.

- [ ] **Step 3: Implement the four immutable templates**

```kotlin
internal enum class NotebookTemplate(
    val coverColor: CoverColor,
    val coverPattern: CoverPattern,
    val paper: PaperTemplate,
    val orientation: PageOrientation,
) {
    RULED_NOTES(CoverColor.PERIWINKLE, CoverPattern.SOLID, PaperTemplate.RULED, PageOrientation.PORTRAIT),
    GRID_NOTEBOOK(CoverColor.SAGE, CoverPattern.GRID, PaperTemplate.GRID, PageOrientation.PORTRAIT),
    DOTTED_JOURNAL(CoverColor.SAND, CoverPattern.CORNERS, PaperTemplate.DOT, PageOrientation.PORTRAIT),
    BLANK_SKETCHBOOK(CoverColor.SALMON, CoverPattern.BAND, PaperTemplate.BLANK, PageOrientation.LANDSCAPE);

    fun matches(
        coverColor: CoverColor,
        coverPattern: CoverPattern,
        paper: PaperTemplate,
        orientation: PageOrientation,
    ): Boolean =
        this.coverColor == coverColor &&
            this.coverPattern == coverPattern &&
            this.paper == paper &&
            this.orientation == orientation
}
```

- [ ] **Step 4: Run the focused tests and commit**

Expected: all `NotebookTemplateTest` methods pass.

Commit: `feat(library): define visual notebook templates`

---

### Task 2: Build reusable notebook and paper illustrations

**Files:**
- Create: `app/src/main/java/cz/majkey/perko/ui/NotebookPreview.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/cz/majkey/perko/library/NotebookPreviewTest.kt`

- [ ] **Step 1: Write a Compose semantics test**

```kotlin
@Test fun paperPreviewsExposeLabelsAndSelection() {
    rule.setContent {
        PerkoTheme {
            PaperPreview(PaperTemplate.GRID, selected = true, onClick = {})
        }
    }
    rule.onNodeWithText("Grid").assertIsDisplayed().assertIsSelected()
}
```

- [ ] **Step 2: Run the focused test and verify red**

Run `:app:compileDebugAndroidTestKotlin`; expect unresolved `PaperPreview`.

- [ ] **Step 3: Implement code-native illustrations**

`NotebookPreview.kt` produces:

```kotlin
@Composable
internal fun NotebookPreview(
    coverColor: CoverColor,
    coverPattern: CoverPattern,
    paper: PaperTemplate,
    orientation: PageOrientation,
    title: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
)

@Composable
internal fun TemplatePreview(
    template: NotebookTemplate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
internal fun PaperPreview(
    paper: PaperTemplate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Reuse the exact project palette and draw binding rings, cover patterns, ruled/grid/dot paper, and portrait/landscape proportions with `Canvas`. Add labels and `selected` semantics; do not add raster dependencies.

- [ ] **Step 4: Verify the component test and compile both form factors**

Run unit tests, `compileDebugAndroidTestKotlin`, lint, and debug assembly.

Commit: `feat(ui): add notebook illustrations`

---

### Task 3: Replace the abstract creation form with a visual configurator

**Files:**
- Modify: `app/src/main/java/cz/majkey/perko/library/CreateNotebookDialog.kt`
- Modify: `app/src/main/java/cz/majkey/perko/library/LibraryScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/cz/majkey/perko/library/LibraryFlowTest.kt`

- [ ] **Step 1: Add a failing template-selection flow**

```kotlin
@Test fun gridTemplateCreatesGridPortraitNotebook() {
    rule.onNodeWithContentDescription("New notebook").performClick()
    rule.onNodeWithText("Grid notebook").performClick()
    rule.onNodeWithText("Grid paper").assertIsSelected()
    rule.onNodeWithText("Portrait").assertIsSelected()
}
```

- [ ] **Step 2: Verify the test fails against the chip-only dialog**

Expected: `Grid notebook` is absent.

- [ ] **Step 3: Implement responsive modal structure**

- Use `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))`.
- Constrain the Surface to `fillMaxWidth(0.94f)`, `fillMaxHeight(0.88f)`, and `widthIn(max = 920.dp)`.
- Use `BoxWithConstraints`: a two-pane `Row` from 720 dp and one scrolling `Column` below it.
- Keep title input, template cards, cover colors, cover patterns, paper previews, orientation previews, and finger drawing.
- Template selection writes the four mapped enum values. Any manual change recomputes the visible template label or `Custom notebook`.
- Keep Cancel/Create outside the scrolling area and retain the normalized title boundary.

- [ ] **Step 4: Run library flow and visual smoke on API 37 and API 29**

Expected: template selection and existing create/rename/trash/delete flows pass.

Commit: `feat(library): add visual notebook creator`

---

### Task 4: Reorganize Settings around visual defaults

**Files:**
- Modify: `app/src/main/java/cz/majkey/perko/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/cz/majkey/perko/settings/AppDetailsSection.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/cz/majkey/perko/settings/SettingsFlowTest.kt`

- [ ] **Step 1: Write failing Settings hierarchy assertions**

```kotlin
@Test fun settingsShowsVisualNotebookDefaultsAndDrawingSamples() {
    rule.onNodeWithText("Settings").performClick()
    rule.onNodeWithText("Notebook defaults").assertIsDisplayed()
    rule.onNodeWithText("Ruled notes").assertIsDisplayed()
    rule.onNodeWithText("Pen sample").assertIsDisplayed()
}
```

- [ ] **Step 2: Verify failure against the seven-row accordion**

Expected: `Notebook defaults` and `Pen sample` are absent.

- [ ] **Step 3: Implement four purposeful groups**

- Add the large current-default `NotebookPreview` at the start of Notebook defaults.
- Reuse the four `TemplatePreview` cards; write template values through `SettingsRepository.update` as one `AppSettings` value.
- Keep paper, orientation, and finger-drawing controls in this group.
- Drawing renders real pen and highlighter sample lines immediately above locally buffered sliders; persist only on slider release.
- Interface & export contains illustrated System/Light/Dark previews and the page-transition switch.
- App & privacy contains recognition disclosure, storage, autosave, version, privacy, source, notices, and the unchanged yellow support control.
- Each collapsed header has a one-line summary and a visible chevron/expanded state.

- [ ] **Step 4: Run Settings persistence and flow tests**

Expected: DataStore test, Settings flow, version, privacy, and support assertions pass on API 29 and API 37.

Commit: `feat(settings): add visual defaults and previews`

---

### Task 5: Visual acceptance, release refresh, and cleanup

**Files:**
- Modify: `docs/qa/2026-08-24-emulator-acceptance.md`
- Replace: `docs/qa/screenshots/tablet-app-details.png`
- Create: `docs/qa/screenshots/tablet-new-notebook.png`
- Create: `docs/qa/screenshots/tablet-settings-defaults.png`
- Create: `docs/qa/screenshots/phone-new-notebook.png`
- Create: `docs/qa/screenshots/phone-settings.png`
- Update: `docs/play-store/assets/*`

- [ ] **Step 1: Run all static and build gates**

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease --console=plain
```

Expected: build and lint succeed; signed release tasks succeed when `PERKO_KEYSTORE_PROPERTIES` is set.

- [ ] **Step 2: Run complete isolated emulator suites**

- API 37: `Perko_Tablet_API_37` on `emulator-5590`.
- API 29: `Perko_Phone_API_29` on a confirmed Péřko-only serial.
- Run the complete instrumentation class list and require zero failures.

- [ ] **Step 3: Capture and inspect creation/settings screenshots**

Check hierarchy, labels, selection clarity, no clipping at 1080 × 2280 and 2560 × 1600, and no horizontal overflow. Replace Play screenshots only with inspected final captures.

- [ ] **Step 4: Rebuild, verify, and hash release artifacts**

Verify APK signature, package/version, no `INTERNET` permission, AAB signature, install exact release APK, and update `dist/SHA256SUMS.txt`.

- [ ] **Step 5: Resume GitHub PR, Pages, release, and Play Console work**

Push `main`, push `feat/release/24-08-2026`, open and merge the PR after checks, deploy Pages, create `v0.1.0-beta.1`, attach APK/AAB/checksums, and complete every accessible Play Console field.

- [ ] **Step 6: Stop and clean only Péřko resources**

- Stop `Perko_Tablet_API_37`, `Perko_Phone_API_29`, and locally started Péřko HTTP servers.
- Preserve repository, `dist`, QA screenshots, Play assets, signing key/properties, and chat outputs.
- Remove project `.gradle`, `.kotlin`, `build`, `app/build`, temporary rendered PDFs/screenshots, and Péřko AVD data only after the final artifacts and remote release are verified.
- Do not stop or delete unrelated emulators, physical-device data, or other projects.

Commit: `test(ui): verify visual notebook flows`
