# SeliaSheets Ink, Media, and Brush Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix zoomed stylus coordinates, expose existing image transforms immediately after import, and add continuous brush width controls with pressure regression coverage.

**Architecture:** Keep `InkCanvasView`, AndroidX Ink, `ElementSelectionOverlay`, Room entities, and page history. Give the platform ink view the measured zoomed hit region, select the existing element after import, and reuse Material Slider behavior already present in Settings.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, AndroidX Ink 1.0.0, Room, JUnit 4, Android instrumentation, Huawei YAL-L21.

**Spec:** `docs/superpowers/specs/2026-08-30-seliasheets-ink-media-controls-design.md`

---

## Constraints

- Production package remains `com.majkeylab.seliadocs`; display name remains `SeliaSheets`.
- `minSdk` remains 29 and `targetSdk` remains 37.
- Use existing dependencies only.
- Preserve the Play-installed app and all local notebook data.
- Write or identify a failing regression before each production behavior change.
- Do not commit, push, open a PR, or publish without a separate explicit approval.

### Task 1: Isolate debug instrumentation

**Files:**
- Modify: `app/build.gradle.kts`

- [x] **Step 1: Add a debug-only application ID**

```kotlin
buildTypes {
    debug {
        applicationIdSuffix = ".debug"
        versionNameSuffix = "-debug"
    }
    release {
        // Existing release configuration remains unchanged.
    }
}
```

- [x] **Step 2: Build and prove package isolation**

Run:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb -s BQLDU19927002646 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s BQLDU19927002646 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s BQLDU19927002646 shell pm list packages com.majkeylab.seliadocs
```

Expected: both `com.majkeylab.seliadocs` and `com.majkeylab.seliadocs.debug` exist; the Play app is not replaced.

### Task 2: Repair the zoom transform contract

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PageCanvas.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/PageViewportFlowTest.kt`

- [x] **Step 1: Run the existing Compose integration regression**

`rootStylusWithKnownZoomAndPanCommitsAtVisiblePaperPoint` passes when the page starts at 2x, proving the static coordinate transform is valid.

- [x] **Step 2: Add the failing live-pinch regression**

`rootStylusAfterPinchCommitsAtVisiblePaperPoint` pinches the real Compose page and sends a root stylus event to an off-center visible paper point. Before the fix it times out because the stroke never reaches the Android view.

- [x] **Step 3: Replace visual-only scaling with measured scaling**

Scale the `Surface` layout and move it through placement offset:

```kotlin
Modifier
    .requiredWidth(paperWidth * viewportZoom)
    .requiredHeight(paperHeight * viewportZoom)
    .offset { IntOffset(viewportPanX.roundToInt(), viewportPanY.roundToInt()) }
```

Do not call `setViewportTransform` from Compose; that would double-transform events already mapped into the measured Android view.

- [x] **Step 4: Add 4x and lasso/eraser coverage, then run the complete classes**

Use the existing root-event helpers. Expected: every `PageViewportFlowTest` and `StylusRoutingTest` test passes.

### Task 3: Select and transform imported images

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/ElementFlowTest.kt`

- [x] **Step 1: Add a failing import-selection test**

Create a small PNG in the test cache, import it through `EditorViewModel.importImage`, and assert:

```kotlin
val imported = viewModel.awaitState("image import") {
    it.elements.singleOrNull()?.kind == ElementKind.IMAGE.name
}
assertEquals(EditorTool.LASSO, imported.tool)
assertEquals(imported.elements.single().id, imported.selectedElementId)
```

Then call `updateSelectedElement`, Undo, and Redo and assert the saved image transform changes and returns.

- [x] **Step 2: Verify RED**

Expected: the image exists, but `tool` and `selectedElementId` remain unchanged.

- [x] **Step 3: Keep and select the new element ID**

```kotlin
val elementId = repository.addElement(pageId, draft)
history.push(snapshot(pageId))
controls.value =
    controls.value.copy(
        tool = EditorTool.LASSO,
        selectedStrokeIds = emptySet(),
        selectedElementId = elementId,
    )
updateHistoryControls(history)
```

Keep existing asset cleanup on repository failure.

- [x] **Step 4: Run element unit and instrumentation coverage**

Expected: import selection, tap reselection, move, resize, rotate, bounds, Undo, Redo, duplicate, delete, and asset preservation pass.

### Task 4: Add continuous width controls and pressure coverage

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/AppSettings.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/settings/SettingsRepositoryTest.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/EditorCompactUiTest.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/StylusRoutingTest.kt`

- [x] **Step 1: Add range and UI regressions**

Define expected validation at pen `1..32` and highlighter `4..64`. Replace assertions for `brush-width-2/4/8` with one `brush-width-slider` node whose range covers the active tool and whose value can change without leaving the editor.

- [x] **Step 2: Add pressure variance regression**

Allow `stylusEvent` to receive a pressure argument. Dispatch DOWN at `0.15f`, MOVE and UP at `0.9f`, then assert the finished stroke inputs contain both pressure levels and the brush family equals `StockBrushes.pressurePen(V1)`.

- [x] **Step 3: Verify RED**

Expected: old settings clamp at 12/40, fixed buttons remain, and the event helper cannot vary pressure.

- [x] **Step 4: Share the width ranges**

```kotlin
internal val PEN_WIDTH_RANGE = 1f..32f
internal val HIGHLIGHTER_WIDTH_RANGE = 4f..64f
```

Use these ranges in `AppSettings.validated`, Settings, and the editor palette.

- [x] **Step 5: Replace width presets with one compact slider**

Keep local slider state with `remember(currentWidth)`. Render a live round line sample, a value label, and a Material `Slider` tagged `brush-width-slider`. Update local preview during drag and persist through the existing settings callback in `onValueChangeFinished`.

- [x] **Step 6: Run settings, compact editor, Ink codec, and stylus tests**

Expected: complete ranges persist, editor preview updates, existing colors and Smart shapes remain reachable, and low/high pressure samples survive the pipeline.

### Task 5: Full verification and physical QA

**Files:**
- Create: `docs/qa/2026-08-30-seliasheets-ink-media-controls.md`
- Modify only if a failing gate exposes a root-cause defect.

- [x] **Step 1: Run repository gates**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease --console=plain
```

- [x] **Step 2: Run focused Huawei instrumentation**

Run `PageViewportFlowTest`, `StylusRoutingTest`, `ElementFlowTest`, `ElementSelectionFlowTest`, `EditorCompactUiTest`, and `SettingsRepositoryTest` against `com.majkeylab.seliadocs.debug.test` on `BQLDU19927002646`.

- [x] **Step 3: Verify live flows**

On the debug app: create a notebook, insert an image, move/resize/rotate it, Undo/Redo, change pen/highlighter width to both extremes, pinch/pan the page, and draw at visible points. Verify one nearby old flow: type text, draw at 1x, turn pages, and reopen the notebook.

- [x] **Step 4: Preserve evidence and remove the debug app**

Record commands/results/screenshots in the QA document. Then run pinned uninstalls for the debug test package and debug app only. Confirm the Play package remains installed and launchable.

- [x] **Step 5: Review the diff harshly**

Run `git diff --check`, inspect every changed file, confirm no dependency/schema/permission change, and report exact remaining hardware limits. Do not commit or publish without separate approval.
