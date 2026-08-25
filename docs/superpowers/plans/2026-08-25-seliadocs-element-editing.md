# SeliaDocs Element Editing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users select, move, resize, rotate, duplicate, reorder, and delete page elements while
preserving page bounds, undo history, backups, PDF output, and accessibility.

**Architecture:** Keep Room `ElementEntity` as the saved object. `EditorViewModel` owns the selected
element ID and commits one validated transform per finished gesture. `ElementSelectionOverlay`
keeps a local preview during a gesture so pointer movement does not write to Room every frame.
Pure geometry functions convert and clamp transforms in page coordinates.

**Tech Stack:** Kotlin, Jetpack Compose, Room, StateFlow, AndroidX Ink, JUnit 4, Android Compose UI
tests.

---

## File map

- Create `app/src/main/java/com/majkeylab/seliadocs/editor/ElementTransform.kt` for pure selection
  and transform geometry.
- Create `app/src/main/java/com/majkeylab/seliadocs/editor/ElementSelectionOverlay.kt` for the
  selection border and gesture handles.
- Modify `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt` for selection state,
  persistence, undo, duplicate, layer order, and delete.
- Modify `app/src/main/java/com/majkeylab/seliadocs/editor/PageCanvas.kt` to render a preview
  transform and the overlay above `InkCanvasView`.
- Modify `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt` to wire selection and
  show the contextual actions.
- Modify `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt` only for duplicate
  and z-order operations that need one Room transaction.
- Modify `app/src/main/res/values/strings.xml` for visible labels and content descriptions.
- Create `app/src/test/java/com/majkeylab/seliadocs/editor/ElementTransformTest.kt` for geometry.
- Modify `app/src/androidTest/java/com/majkeylab/seliadocs/editor/ElementFlowTest.kt` for saved
  transforms, undo, duplicate, layer order, and delete.
- Create `app/src/androidTest/java/com/majkeylab/seliadocs/editor/ElementSelectionFlowTest.kt` for
  overlay touch targets and contextual actions.

### Task 1: Define selection and transform geometry

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/ElementTransform.kt`
- Create: `app/src/test/java/com/majkeylab/seliadocs/editor/ElementTransformTest.kt`

- [ ] **Step 1: Write the failing transform tests**

```kotlin
package com.majkeylab.seliadocs.editor

import com.majkeylab.seliadocs.data.ElementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElementTransformTest {
    private val element =
        ElementEntity(
            id = "element",
            pageId = "page",
            zIndex = 2,
            kind = "TEXT",
            x = 20f,
            y = 30f,
            width = 100f,
            height = 60f,
            rotation = 0f,
            text = "Physics",
            assetId = null,
            shapeKind = null,
            expression = null,
            resultText = null,
        )

    @Test
    fun moveAndResizeStayInsidePage() {
        assertEquals(
            ElementTransform(495f, 782f, 100f, 60f, 0f),
            clampElementTransform(
                proposed = ElementTransform(900f, 900f, 100f, 60f, 0f),
                pageWidth = 595f,
                pageHeight = 842f,
            ),
        )
        assertEquals(
            ElementTransform(20f, 30f, 24f, 24f, 0f),
            clampElementTransform(
                proposed = ElementTransform(20f, 30f, 1f, 0f, 0f),
                pageWidth = 595f,
                pageHeight = 842f,
            ),
        )
    }

    @Test
    fun invalidTransformIsRejected() {
        assertNull(
            validElementTransform(
                ElementTransform(Float.NaN, 0f, 10f, 10f, 0f),
            ),
        )
    }

    @Test
    fun tapSelectsTopmostElement() {
        val top = element.copy(id = "top", zIndex = 3)
        assertEquals(
            "top",
            selectElementAt(CanvasPoint(40f, 50f), listOf(element, top)),
        )
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ElementTransformTest*' --console=plain
```

Expected: compilation fails because `ElementTransform`, `clampElementTransform`,
`validElementTransform`, and `selectElementAt` do not exist.

- [ ] **Step 3: Add the minimal pure geometry**

```kotlin
package com.majkeylab.seliadocs.editor

import com.majkeylab.seliadocs.data.ElementEntity

internal data class ElementTransform(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
)

internal fun validElementTransform(value: ElementTransform): ElementTransform? =
    value.takeIf {
        it.x.isFinite() &&
            it.y.isFinite() &&
            it.width.isFinite() &&
            it.height.isFinite() &&
            it.rotation.isFinite() &&
            it.width > 0f &&
            it.height > 0f
    }

internal fun clampElementTransform(
    proposed: ElementTransform,
    pageWidth: Float,
    pageHeight: Float,
    minimumSize: Float = 24f,
): ElementTransform? {
    val valid = validElementTransform(proposed) ?: return null
    val width = valid.width.coerceIn(minimumSize, pageWidth)
    val height = valid.height.coerceIn(minimumSize, pageHeight)
    return valid.copy(
        x = valid.x.coerceIn(0f, pageWidth - width),
        y = valid.y.coerceIn(0f, pageHeight - height),
        width = width,
        height = height,
        rotation = ((valid.rotation % 360f) + 360f) % 360f,
    )
}

internal fun selectElementAt(point: CanvasPoint, elements: List<ElementEntity>): String? =
    elements
        .asSequence()
        .filter { point.x in it.x..(it.x + it.width) && point.y in it.y..(it.y + it.height) }
        .maxByOrNull(ElementEntity::zIndex)
        ?.id

internal fun ElementEntity.transform() = ElementTransform(x, y, width, height, rotation)
```

- [ ] **Step 4: Run the tests and verify GREEN**

Run the Step 2 command.

Expected: `BUILD SUCCESSFUL` and all `ElementTransformTest` cases pass.

- [ ] **Step 5: Commit the geometry slice**

```powershell
git add app/src/main/java/com/majkeylab/seliadocs/editor/ElementTransform.kt app/src/test/java/com/majkeylab/seliadocs/editor/ElementTransformTest.kt
git commit -m "feat(editor): add element transform geometry"
```

### Task 2: Persist transforms and element actions

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/ElementFlowTest.kt`

- [ ] **Step 1: Add failing ViewModel behavior to `ElementFlowTest`**

After the existing text and math assertions, add:

```kotlin
val textElement = math.elements.single { it.text != null }
onMain { viewModel.selectElement(textElement.id) }
onMain {
    viewModel.updateSelectedElement(
        ElementTransform(40f, 50f, 180f, 90f, 25f),
    )
}
val moved = viewModel.awaitState("element transform") {
    it.selectedElement?.x == 40f && it.canUndo
}
assertEquals(25f, moved.selectedElement?.rotation)

onMain(viewModel::undo)
val undone = viewModel.awaitState("element transform undo") {
    it.elements.single { element -> element.id == textElement.id }.x == textElement.x
}
assertEquals(textElement.width, undone.elements.single { it.id == textElement.id }.width)

onMain(viewModel::duplicateSelectedElement)
viewModel.awaitState("element duplicate") { it.elements.size == 3 }
onMain(viewModel::bringSelectedElementForward)
onMain(viewModel::deleteSelectedElement)
viewModel.awaitState("element delete") { it.elements.size == 2 && it.selectedElement == null }
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest --console=plain
adb -s emulator-5590 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5590 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5590 shell am instrument -w -r -e class com.majkeylab.seliadocs.editor.ElementFlowTest com.majkeylab.seliadocs.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: compilation fails on the new selection methods and `selectedElement` state.

- [ ] **Step 3: Add selection state to `EditorUiState` and `EditorControls`**

Add `selectedElementId` immediately after `selectedStrokeIds` in both existing data classes:

```kotlin
val selectedElementId: String? = null,
```

Add this computed property to the existing `EditorUiState` body:

```kotlin
val selectedElement: ElementEntity?
    get() = elements.firstOrNull { it.id == selectedElementId }
```

Copy `selectedElementId = editorControls.selectedElementId` into the state mapper. Clear the element
selection when the user leaves `EditorTool.LASSO` or changes pages.

- [ ] **Step 4: Add validated ViewModel actions**

```kotlin
fun selectElement(id: String?) {
    controls.value =
        controls.value.copy(
            selectedElementId = id?.takeIf { candidate ->
                state.value.elements.any { it.id == candidate }
            },
            selectedStrokeIds = emptySet(),
        )
}

fun updateSelectedElement(transform: ElementTransform) = mutate {
    val element = state.value.selectedElement ?: return@mutate
    val page = state.value.selectedPage ?: return@mutate
    val clamped =
        clampElementTransform(transform, page.widthPoints.toFloat(), page.heightPoints.toFloat())
            ?: return@mutate
    val history = history(page.id)
    repository.updateElement(
        element.copy(
            x = clamped.x,
            y = clamped.y,
            width = clamped.width,
            height = clamped.height,
            rotation = clamped.rotation,
        ),
    )
    history.push(snapshot(page.id))
    updateHistoryControls(history)
}

fun deleteSelectedElement() = mutate {
    val element = state.value.selectedElement ?: return@mutate
    val history = history(element.pageId)
    repository.deleteElement(element.id)
    element.assetId?.let { assetId ->
        if (repository.getAssetReferenceCount(assetId) == 0) assets.file(assetId).delete()
    }
    history.push(snapshot(element.pageId))
    controls.value = controls.value.copy(selectedElementId = null)
    updateHistoryControls(history)
}
```

- [ ] **Step 5: Add atomic duplicate and layer-order repository methods**

Add `duplicateElement(id: String): String` and `moveElementForward(id: String)` to
`SeliaDocsRepository`. Run both inside `database.withTransaction`. A duplicate receives a new ID,
an offset of 12 page points, a clamped position, and the next z-index. Moving forward swaps the
selected element's z-index with the next element. Do not modify the source asset file because both
image elements reference the same immutable asset.

Use these ViewModel methods:

```kotlin
fun duplicateSelectedElement() = mutate {
    val selected = state.value.selectedElement ?: return@mutate
    val id = repository.duplicateElement(selected.id)
    controls.value = controls.value.copy(selectedElementId = id)
    history(selected.pageId).also { history ->
        history.push(snapshot(selected.pageId))
        updateHistoryControls(history)
    }
}

fun bringSelectedElementForward() = mutate {
    val selected = state.value.selectedElement ?: return@mutate
    repository.moveElementForward(selected.id)
    history(selected.pageId).also { history ->
        history.push(snapshot(selected.pageId))
        updateHistoryControls(history)
    }
}
```

- [ ] **Step 6: Run `ElementFlowTest` and verify GREEN**

Run the Step 2 commands.

Expected: `OK (1 test)` and every transform, undo, duplicate, layer-order, and delete assertion
passes.

- [ ] **Step 7: Commit persistence**

```powershell
git add app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt app/src/main/java/com/majkeylab/seliadocs/data/SeliaDocsRepository.kt app/src/androidTest/java/com/majkeylab/seliadocs/editor/ElementFlowTest.kt
git commit -m "feat(editor): persist element editing"
```

### Task 3: Add the selection overlay

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/ElementSelectionOverlay.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PageCanvas.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/ElementSelectionFlowTest.kt`

- [ ] **Step 1: Write the failing overlay semantics test**

Create a notebook with one text element, open the editor, choose **Lasso**, select the element, and
assert these tags are displayed:

```kotlin
rule.onNodeWithTag("element-selection").assertIsDisplayed()
rule.onNodeWithTag("element-move-handle").assertTouchWidthIsEqualTo(48.dp)
rule.onNodeWithTag("element-resize-handle").assertTouchWidthIsEqualTo(48.dp)
rule.onNodeWithTag("element-rotate-handle").assertTouchWidthIsEqualTo(48.dp)
```

Use `performTouchInput` on each handle and assert the saved Room element changes only after the
gesture ends.

- [ ] **Step 2: Run the overlay test and verify RED**

Run:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb -s emulator-5590 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5590 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5590 shell am instrument -w -r -e class com.majkeylab.seliadocs.editor.ElementSelectionFlowTest com.majkeylab.seliadocs.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: compilation fails because the overlay and tags do not exist.

- [ ] **Step 3: Add `ElementSelectionOverlay`**

The composable receives the page, selected element, screen-to-page scales, and two callbacks:

```kotlin
@Composable
internal fun ElementSelectionOverlay(
    page: PageEntity,
    element: ElementEntity,
    scaleX: Float,
    scaleY: Float,
    onPreview: (ElementTransform?) -> Unit,
    onCommit: (ElementTransform) -> Unit,
)
```

Render a rotated border that matches the selected element. Add three 48 dp gesture targets:

- the border body moves the element;
- the bottom-right handle resizes width and height;
- the top handle rotates around the element center.

Each drag updates local `ElementTransform` state and calls `onPreview`. `onCommit` runs once in
`onDragEnd`. `onPreview(null)` runs after commit or cancellation.

- [ ] **Step 4: Render preview geometry in `PageCanvas`**

Add these parameters:

```kotlin
selectedElementId: String?,
onSelectContent: (List<CanvasPoint>) -> Unit,
onCommitElementTransform: (ElementTransform) -> Unit,
```

Keep `previewTransform` in `Paper` with `remember(selectedElementId)`. Pass the preview into
`ElementLayer`; only the selected element uses it. Render `ElementSelectionOverlay` after
`AndroidView` so the handles receive touch before the ink canvas.

For a lasso path shorter than 12 page points, call `selectElementAt` with the last point. For a
larger lasso, keep current ink selection and select the topmost element whose center falls inside
the lasso bounds.

- [ ] **Step 5: Run the overlay test and verify GREEN**

Expected: the overlay has one selected element, all handles expose 48 dp touch targets, preview
does not write Room, and gesture end writes one transform.

- [ ] **Step 6: Commit the overlay**

```powershell
git add app/src/main/java/com/majkeylab/seliadocs/editor/ElementSelectionOverlay.kt app/src/main/java/com/majkeylab/seliadocs/editor/PageCanvas.kt app/src/androidTest/java/com/majkeylab/seliadocs/editor/ElementSelectionFlowTest.kt
git commit -m "feat(editor): add element selection handles"
```

### Task 4: Add contextual element actions

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/ElementSelectionFlowTest.kt`

- [ ] **Step 1: Add failing contextual-action assertions**

After selecting an element, assert that **Duplicate**, **Bring forward**, and **Delete element** are
visible. Press **Delete element**, confirm the element disappears, then press Undo and confirm that
it returns.

- [ ] **Step 2: Run the test and verify RED**

Expected: the action labels do not exist.

- [ ] **Step 3: Wire the overlay in both editor layouts**

Both `PageCanvas` calls receive:

```kotlin
selectedElementId = state.selectedElementId,
onSelectContent = { points ->
    state.selectedPage?.let { page -> viewModel.selectContent(page.id, points) }
},
onCommitElementTransform = viewModel::updateSelectedElement,
```

Replace the current `selectStrokes` callback with `selectContent`; that method selects either the
topmost matching element or the matching ink strokes and clears the other selection type.

- [ ] **Step 4: Add one contextual action row**

When `state.selectedElement != null`, show one flat action row below the main toolbar:

```kotlin
ElementContextBar(
    onDuplicate = viewModel::duplicateSelectedElement,
    onBringForward = viewModel::bringSelectedElementForward,
    onDelete = viewModel::deleteSelectedElement,
)
```

Use `TextButton` controls with visible labels. Give the row the `element-context-bar` test tag. Do
not add a floating card or another permanent toolbar.

- [ ] **Step 5: Add English strings**

```xml
<string name="element_selected">Object selected</string>
<string name="duplicate_element">Duplicate</string>
<string name="bring_forward">Bring forward</string>
<string name="delete_element">Delete element</string>
<string name="move_element">Move selected element</string>
<string name="resize_element">Resize selected element</string>
<string name="rotate_element">Rotate selected element</string>
```

- [ ] **Step 6: Run the contextual-action test and verify GREEN**

Expected: selection actions are reachable on phone and tablet layouts, Delete is reversible, and
the action row disappears when selection clears.

- [ ] **Step 7: Commit the contextual actions**

```powershell
git add app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt app/src/main/res/values/strings.xml app/src/androidTest/java/com/majkeylab/seliadocs/editor/ElementSelectionFlowTest.kt
git commit -m "feat(editor): add element context actions"
```

### Task 5: Run compatibility and storage gates

**Files:**
- Modify only if a failing gate exposes a root-cause defect.

- [ ] **Step 1: Run the focused unit and instrumentation suites**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ElementTransformTest*' --tests '*PageHistory*' --console=plain
adb -s emulator-5590 shell am instrument -w -r -e class com.majkeylab.seliadocs.editor.ElementFlowTest,com.majkeylab.seliadocs.editor.ElementSelectionFlowTest,com.majkeylab.seliadocs.editor.PageFlowTest com.majkeylab.seliadocs.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: every test passes with zero skipped tests.

- [ ] **Step 2: Run build and lint**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Expected: `BUILD SUCCESSFUL` with no Kotlin opt-in warning or Android lint error.

- [ ] **Step 3: Verify backup and PDF compatibility**

```powershell
adb -s emulator-5590 shell am instrument -w -r -e class com.majkeylab.seliadocs.backup.BackupJsonTest,com.majkeylab.seliadocs.backup.BackupExporterTest,com.majkeylab.seliadocs.backup.BackupImporterTest,com.majkeylab.seliadocs.editor.PdfExporterTest com.majkeylab.seliadocs.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: transformed and duplicated elements preserve every field through backup restore and PDF
rendering.

- [ ] **Step 4: Run live API 29 and API 37 scenarios**

On `emulator-5590` and `emulator-5594`, verify:

1. select and move a text object;
2. resize an image to each page boundary;
3. rotate a shape;
4. reject a non-finite transform through the instrumentation test;
5. duplicate and bring forward an overlapping object;
6. delete and Undo;
7. switch pages and return with the correct history;
8. rotate the device while an element stays selected.

Do not clear `emulator-5594`; it is the visible progress tablet. Never target a physical serial.

- [ ] **Step 5: Commit only a root-cause gate fix if one was required**

```powershell
git status --short
git diff --check
```

If no gate fix was needed, do not create an empty commit.

## Next Package A plans

After this plan passes, create and execute these independent plans in order:

1. `2026-08-25-seliadocs-full-page-text.md`;
2. `2026-08-25-seliadocs-navigation-erasers.md`;
3. `2026-08-25-seliadocs-smart-shapes.md`;
4. `2026-08-25-seliadocs-page-thumbnails.md`.

Each plan must use the capability matrix and the acceptance gates in
`2026-08-25-seliadocs-research-refresh-design.md`.
