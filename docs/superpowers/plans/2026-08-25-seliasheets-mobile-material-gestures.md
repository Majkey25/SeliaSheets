# SeliaSheets mobile Material and page gestures implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make SeliaSheets compact-phone UI clear, inset-safe, and page-swipe capable without changing the expanded notebook editor or ink persistence.

**Architecture:** Use existing Compose Material 3 components and current width constraints. Keep UI tasks separate from a pure `PageGestureArbiter`, then connect the reducer to existing page-selection and notebook-setting APIs.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Room, AndroidX Ink, JUnit 4, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-08-25-seliasheets-mobile-material-gestures-design.md`

## Global Constraints

- Package stays `com.majkeylab.seliadocs` and display name stays `SeliaSheets`.
- `minSdk` stays 29 and `targetSdk` stays 37.
- Use existing dependencies only.
- No `INTERNET`, recognition model, analytics, account, or Data Safety change in this slice.
- Compact is `<600dp`, medium is `600..839dp`, and expanded is `>=840dp`.
- Primary compact tools never require horizontal scroll.
- Every interactive control has a 48 dp minimum target and an accessible name.
- Expanded editor behavior and page persistence remain unchanged.
- Write a failing test before production code.

---

### Task 1: Use local width and inset-safe Material top bars

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/library/LibraryScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/backup/BackupScreen.kt`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/AdaptiveLayoutTest.kt`

**Interfaces:**
- Produces: `internal enum class SeliaWindowClass { COMPACT, MEDIUM, EXPANDED }` and `internal fun seliaWindowClass(widthDp: Int): SeliaWindowClass`.
- Keeps all screen callback signatures.

- [ ] **Step 1: Add exact breakpoint and top-inset tests**

Assert `599 -> COMPACT`, `600 -> MEDIUM`, `839 -> MEDIUM`, and `840 -> EXPANDED`. Render each root screen under a non-zero safe top inset. Assert that the Material top-bar root stays edge-to-edge at `y = 0`. Its height must equal one safe top inset plus `TopAppBarDefaults.TopAppBarExpandedHeight`. Its tagged title must stay inside that inset-adjusted content slot.

- [ ] **Step 2: Verify RED**

Run the local breakpoint unit test and targeted `AdaptiveLayoutTest` on the isolated Huawei debug package. Expected: the helper is unresolved and custom top bars overlap the injected inset.

- [ ] **Step 3: Add the width classifier and Material bars**

Use `BoxWithConstraints` at the screen content boundary. Replace custom top `Surface` rows with Material 3 `TopAppBar`, keeping existing text and callbacks. Remove `LocalConfiguration.current.screenWidthDp` from editor layout decisions.

- [ ] **Step 4: Verify GREEN and commit**

Run breakpoint, adaptive layout, navigation Back, and backup Back tests. Commit as `fix(ui): respect window width and system insets`.

### Task 2: Make the compact library and settings scanable

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/library/LibraryScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/SettingsScreen.kt`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/library/LibraryFlowTest.kt`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/settings/SettingsFlowTest.kt`

**Interfaces:**
- Keeps existing notebook action callbacks and settings update callbacks.

- [ ] **Step 1: Add compact density and semantics tests**

At 360 dp and normal font scale, assert two notebook cards have distinct horizontal positions. At 200 percent font scale, assert one column. Open notebook actions and assert a modal sheet. Assert every settings group starts collapsed and exposes expanded/collapsed state.

- [ ] **Step 2: Verify RED**

Run `LibraryFlowTest` and `SettingsFlowTest`. Expected: one-column normal layout, custom action overlay, and initially expanded notebook defaults fail the new assertions.

- [ ] **Step 3: Apply minimal Material changes**

Set compact adaptive grid minimum to 148 dp, outer padding to 16 dp, gaps to 12 dp, and bottom content padding to 96 dp. Replace the custom notebook action overlay with `ModalBottomSheet`. Remove the settings introduction and initialize all group states to collapsed. Add Button role and state description to group rows.

- [ ] **Step 4: Verify GREEN and commit**

Run both covering classes plus NavigationBackTest. Commit as `fix(ui): simplify phone library and settings`.

### Task 3: Add the fixed compact editor palette

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/ContentsPanel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/EditorCompactUiTest.kt`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/ContentsFlowTest.kt`

**Interfaces:**
- Keeps `EditorViewModel` tool and action methods.
- Produces compact More and Insert menus only; medium/expanded keep the current full toolbar.

- [ ] **Step 1: Add compact reachability and state tests**

At 360 dp, assert Type, Pen, Highlighter, Eraser, Lasso, Insert, Undo, Redo, page position, and More are reachable without scroll. Assert one selected tool. Assert bookmark action and state descriptions.

- [ ] **Step 2: Verify RED**

Run `EditorCompactUiTest` and `ContentsFlowTest`. Expected: hidden primary tools and missing selection/bookmark semantics fail.

- [ ] **Step 3: Build the compact top and bottom bars**

Use `Scaffold.bottomBar` for the six primary actions. Put Add page, Search, Pencil, Export, and Settings in More. Put Text object, Image, PDF, selection-based Shape, and Math in Insert. Merge the compact page position into the top bar and remove the separate compact `PageLocationBar`. Keep the Contents bottom sheet. Task 4 adds **Draw with finger** only after its repository and ViewModel behavior exists.

- [ ] **Step 4: Verify GREEN and commit**

Run compact editor, Contents, page, export, and NavigationBack tests. Commit as `fix(editor): expose phone tools without scrolling`.

### Task 4: Add page swipe arbitration and current-notebook finger mode

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/PageGestureArbiter.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PageCanvas.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/InkCanvasView.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/majkeylab/seliadocs/editor/PageGestureArbiterTest.kt`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/PageNavigationFlowTest.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/StylusRoutingTest.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/SmartShapeFlowTest.kt`

**Interfaces:**
- Produces: `PageGestureArbiter.onGesture(...) : PageTurn` where `PageTurn` is `NONE`, `PREVIOUS`, or `NEXT`.
- Produces: `SeliaDocsRepository.setFingerDrawing(notebookId: String, enabled: Boolean)`.
- Produces: `EditorViewModel.setFingerDrawing(enabled: Boolean)`.
- Adds `onPreviousPage` and `onNextPage` callbacks to `PageCanvas`.

- [ ] **Step 1: Add the pure arbitration table**

Use literal tests for one-finger next/previous, below-threshold, vertical dominance, zoomed pan, scale change, two-finger navigation with finger drawing, one-page maximum, first/last bounds, cancellation, and stylus ownership.

- [ ] **Step 2: Verify reducer RED**

Run `PageGestureArbiterTest`. Expected: unresolved reducer types.

- [ ] **Step 3: Implement the pure reducer**

Use the spec thresholds: 25 percent width and horizontal movement at least 1.4 times vertical movement. Lock one owner per gesture and emit one page turn maximum.

- [ ] **Step 4: Add integration tests and verify RED**

Render the real ink AndroidView. Verify one-finger page turn with finger drawing off, two-finger page turn after canceling uncommitted finger ink, no page turn while zoomed, no page turn during stylus input, and bounds behavior. Verify Draw with finger updates the current notebook and survives reopen. Verify held arrow and ellipse convert automatically and Undo restores raw ink.

- [ ] **Step 5: Wire UI, repository, and ink cancellation**

Connect PageCanvas gestures to existing previous/next ViewModel methods. Update the current notebook setting in Room and add **Draw with finger** to the compact More menu. Preserve Task 6 finger-to-stylus cancellation and all hardware eraser behavior. Rename the visible ellipse option to **Circle / ellipse**.

- [ ] **Step 6: Verify GREEN and commit**

Run reducer tests plus PageNavigationFlowTest, StylusRoutingTest, SmartShapeFlowTest, PageViewportFlowTest, and SettingsFlowTest. Commit as `feat(editor): turn notebook pages with touch`.

### Task 5: Verify mobile acceptance and refresh screenshots

**Files:**
- Create: `docs/qa/2026-08-26-seliasheets-mobile-material.md`
- Modify: `docs/play-store/assets/phone-01-library.png`
- Modify: `docs/play-store/assets/phone-02-editor.png`
- Modify: `docs/play-store/assets/phone-03-new-notebook.png`
- Modify: `docs/play-store/assets/phone-04-settings.png`

**Interfaces:**
- Consumes all prior tasks.

- [ ] **Step 1: Run the full quality gate**

Run unit tests, lint, debug builds, instrumentation builds, release APK, and release AAB from a clean build. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run API 29 and API 37 instrumentation**

Run the complete suite at 360 dp API 29 and expanded API 37. Expected: zero failures.

- [ ] **Step 3: Run live UI scenarios**

Verify 360, 600, 840, and 1280 dp; portrait and landscape; 200 percent font; dark theme; system inset bounds; one- and two-finger page turns; stylus drawing; Undo raw shape; compact More and Insert menus.

- [ ] **Step 4: Capture and inspect screenshots**

Regenerate only the four changed phone store assets and dated QA screenshots. Inspect every image before committing. Keep older QA evidence unchanged.

- [ ] **Step 5: Commit acceptance evidence**

Commit as `docs: record mobile Material acceptance`.
