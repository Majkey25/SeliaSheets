# SeliaSheets Ink Engine V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-stroke Pencil tilt approximation with continuous AndroidX Ink pressure, tilt, and orientation behavior while preserving Android 10, stored notebooks, zoom alignment, and eraser routing.

**Architecture:** Keep `InkCanvasView`, Room entities, stroke input serialization, and existing stock Pen/Highlighter families. Pin all AndroidX Ink modules to `1.1.0-alpha07`, add one deterministic SeliaSheets Pencil family, store it as a new `BrushKind.PENCIL`, and remove the one-time brush mutation from pointer down. Existing stroke rows continue decoding through their original brush kinds.

**Tech Stack:** Kotlin, Android Views inside Compose, AndroidX Ink 1.1.0-alpha07, AndroidX Input Motion Prediction, JUnit4 instrumentation, Huawei API 29.

---

### Task 1: Prove AndroidX Ink 1.1 compatibility

**Files:**
- Modify: `app/build.gradle.kts:105-109`
- Test: existing JVM and Android instrumentation suites

- [ ] **Step 1: Record the clean baseline**

Run:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Pin all Ink modules to alpha07**

Replace the five `1.0.0` versions with one local value:

```kotlin
val inkVersion = "1.1.0-alpha07"

dependencies {
    implementation("androidx.ink:ink-authoring:$inkVersion")
    implementation("androidx.ink:ink-brush:$inkVersion")
    implementation("androidx.ink:ink-rendering:$inkVersion")
    implementation("androidx.ink:ink-storage:$inkVersion")
    implementation("androidx.ink:ink-strokes:$inkVersion")
}
```

- [ ] **Step 3: Compile before behavior changes**

Run the Step 1 command again.

Expected: all existing code compiles and the local gate passes. If it does not, revert only the dependency version change and record the exact incompatibility. Do not adapt production code until the dependency-only result is understood.

- [ ] **Step 4: Run existing Huawei stylus compatibility tests**

```powershell
adb -s BQLDU19927002646 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s BQLDU19927002646 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s BQLDU19927002646 shell am instrument -w -r -e class com.majkeylab.seliadocs.editor.StylusRoutingTest,com.majkeylab.seliadocs.editor.PageViewportFlowTest com.majkeylab.seliadocs.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: current tests pass; external-pen cases may skip because `ExternalStylusConnected: false`.

- [ ] **Step 5: Commit only after approval**

```text
build(ink): update AndroidX Ink compatibility
```

### Task 2: Add a continuous Pencil brush family

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/editor/InkBrushes.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/InkCodec.kt`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/InkCodecTest.kt`

- [ ] **Step 1: Write the failing brush-family test**

Add an instrumentation test that expects a persisted Pencil family:

```kotlin
@Test
fun pencilRoundTripKeepsDynamicFamilyAndInputs() {
    val inputs =
        MutableStrokeInputBatch().apply {
            add(InputToolType.STYLUS, 10f, 20f, 0L, 0.01f, 0.2f, 0.1f, 0f)
            add(InputToolType.STYLUS, 30f, 40f, 16L, 0.01f, 0.9f, 1.1f, 1.4f)
        }
    val original = Stroke(InkCodec.createBrush(BrushKind.PENCIL, 0xFF202124.toInt(), 4f), inputs)

    val restored = InkCodec.decode(InkCodec.encode(original))

    assertEquals(BrushKind.PENCIL, InkCodec.encode(restored).brushKind)
    assertEquals(SeliaInkBrushes.pencil, restored.brush.family)
    assertEquals(0.2f, restored.inputs[0].pressure, 0.01f)
    assertEquals(0.9f, restored.inputs[1].pressure, 0.01f)
    assertEquals(1.1f, restored.inputs[1].tiltRadians, 0.01f)
    assertEquals(1.4f, restored.inputs[1].orientationRadians, 0.01f)
}
```

- [ ] **Step 2: Run the test and confirm RED**

Run the single `InkCodecTest` class on the pinned Huawei.

Expected: compile failure because `BrushKind.PENCIL` and `SeliaInkBrushes` do not exist.

- [ ] **Step 3: Implement the Pencil family**

Create `InkBrushes.kt` with one lazy family. Reuse the stock pressure-pen paint and input model:

```kotlin
internal object SeliaInkBrushes {
    val pencil: BrushFamily by lazy {
        val base = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        val tip =
            BrushTip(
                scaleX = 1f,
                scaleY = 0.55f,
                cornerRounding = 0.35f,
                behaviors =
                    listOf(
                        behavior(Source.NORMALIZED_PRESSURE, 0f, 1f, Target.SIZE_MULTIPLIER, 0.45f, 1.2f),
                        behavior(Source.TILT_IN_RADIANS, 0f, HALF_PI, Target.WIDTH_MULTIPLIER, 1f, 2.4f),
                        behavior(Source.TILT_IN_RADIANS, 0f, HALF_PI, Target.OPACITY_MULTIPLIER, 1f, 0.58f),
                        behavior(
                            Source.ORIENTATION_ABOUT_ZERO_IN_RADIANS,
                            -PI,
                            PI,
                            Target.ROTATION_OFFSET_IN_RADIANS,
                            -PI,
                            PI,
                        ),
                    ),
            )
        BrushFamily.builder()
            .setCoat(BrushCoat(tip, base.coats.single().paintPreferences))
            .setInputModel(base.inputModel)
            .setDeveloperComment("Pressure controls size; tilt controls width and opacity; orientation rotates the tip.")
            .build()
    }

    private fun behavior(
        source: Source,
        sourceStart: Float,
        sourceEnd: Float,
        target: Target,
        targetStart: Float,
        targetEnd: Float,
    ) =
        BrushBehavior(
            TargetNode(
                target,
                targetStart,
                targetEnd,
                DampingNode(
                    ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE,
                    0.35f,
                    SourceNode(source, sourceStart, sourceEnd),
                ),
            ),
        )

    private const val PI = 3.1415927f
    private const val HALF_PI = PI / 2f
}
```

- [ ] **Step 4: Add the persisted brush kind**

In `InkCodec.kt`:

```kotlin
internal enum class BrushKind { PRESSURE_PEN, PENCIL, MARKER, HIGHLIGHTER }
```

Map `BrushKind.PENCIL` to `SeliaInkBrushes.pencil`. No schema migration is required because Room already stores the enum name as text.

- [ ] **Step 5: Run codec and backup compatibility tests**

Run `InkCodecTest`, `BackupJsonTest`, `BackupExporterTest`, and `BackupImporterTest` on Huawei.

Expected: new Pencil strokes round-trip; old stock brush records and backups still decode.

- [ ] **Step 6: Commit only after approval**

```text
feat(ink): add dynamic pencil brush family
```

### Task 3: Route Pencil without per-stroke mutation

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/InkCanvasView.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/PageCanvas.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/StylusRoutingTest.kt`

- [ ] **Step 1: Replace the old tilt test with a continuous-input test**

Extend the existing `stylusEvent` helper with `orientation: Float = 0.3f`, then dispatch one Pencil stroke whose move event changes pressure, tilt, and orientation:

```kotlin
view.tool = EditorTool.PENCIL
view.brush = InkCodec.createBrush(BrushKind.PENCIL, 0xFF202124.toInt(), 4f)
view.dispatchTouchEvent(
    stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 40f, 50f, pressure = 0.2f, tilt = 0.1f, orientation = 0f),
)
view.dispatchTouchEvent(
    stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_MOVE, 80f, 90f, pressure = 0.9f, tilt = 1.1f, orientation = 1.4f),
)
view.dispatchTouchEvent(
    stylusEvent(downTime, downTime + 32, MotionEvent.ACTION_UP, 100f, 120f, pressure = 0.9f, tilt = 1.1f, orientation = 1.4f),
)
```

Assert:

```kotlin
assertEquals(SeliaInkBrushes.pencil, finished.single().brush.family)
assertEquals(0.2f, finished.single().inputs.first().pressure, 0.01f)
assertEquals(0.9f, finished.single().inputs.last().pressure, 0.01f)
assertEquals(0.1f, finished.single().inputs.first().tiltRadians, 0.01f)
assertEquals(1.1f, finished.single().inputs.last().tiltRadians, 0.01f)
assertEquals(1.4f, finished.single().inputs.last().orientationRadians, 0.01f)
```

Also assert that finger input with missing optional axes completes without changing the Pencil family.

- [ ] **Step 2: Confirm the updated test fails against the current implementation**

Expected: the current per-stroke `pencilBrush()` produces a new static brush whose family is the stock pressure pen.

- [ ] **Step 3: Remove start-only Pencil mutation**

In `InkCanvasView.startInteraction`, always pass the selected `brush` to `startStroke`. Delete `pencilBrush`, `roundToInt`, and the three `PENCIL_*` constants.

- [ ] **Step 4: Select the dynamic family in `PageCanvas`**

Change the Pencil branch:

```kotlin
EditorTool.PENCIL -> InkCodec.createBrush(BrushKind.PENCIL, penColorArgb, penWidth * 0.55f)
```

- [ ] **Step 5: Run focused Huawei tests**

Run `StylusRoutingTest`, `PageViewportFlowTest`, `InkCodecTest`, and `SmartShapeFlowTest`.

Expected: continuous axes persist, zoom coordinates remain aligned, and draw-and-hold shapes still replace Pencil ink with undoable shapes.

- [ ] **Step 6: Commit only after approval**

```text
fix(ink): apply pencil dynamics per input
```

### Task 4: Add hover preview without stored state

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/InkCanvasView.kt`
- Test: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/StylusRoutingTest.kt`

- [ ] **Step 1: Write failing hover lifecycle tests**

Expose a read-only `internal val hoverPreviewVisible` on `InkCanvasView` for instrumentation. Add this test using a `stylusHoverEvent` helper built like the existing `stylusEvent` helper but with `buttonState = 0`, `pressure = 0f`, and the requested hover action:

```kotlin
@Test
fun stylusHoverPreviewFollowsHoverLifecycleWithoutCommittingInk() {
    val finished = mutableListOf<Stroke>()
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            val view = InkCanvasView(activity)
            activity.setContentView(view)
            view.listener =
                object : InkCanvasView.Listener {
                    override fun onStrokeFinished(stroke: Stroke) {
                        finished += stroke
                    }

                    override fun onStrokeCanceled(pointerId: Int) = Unit
                }
            view.measure(exactly(500), exactly(500))
            view.layout(0, 0, 500, 500)

            view.dispatchHoverEvent(stylusHoverEvent(MotionEvent.ACTION_HOVER_ENTER, 100f, 120f))
            assertTrue(view.hoverPreviewVisible)
            view.dispatchHoverEvent(stylusHoverEvent(MotionEvent.ACTION_HOVER_MOVE, 180f, 220f))
            assertTrue(view.hoverPreviewVisible)
            view.dispatchHoverEvent(stylusHoverEvent(MotionEvent.ACTION_HOVER_EXIT, 180f, 220f))
            assertFalse(view.hoverPreviewVisible)
            assertTrue(finished.isEmpty())
        }
    }
}
```

Add a second test that sends finger hover and expects `hoverPreviewVisible == false`. Existing detach and cancel tests must also assert the preview is cleared.

- [ ] **Step 2: Implement one lightweight overlay**

Extend `GestureOverlayView` with an optional hover point and draw a ring using the active brush size. Do not store hover data in Room or history.

```kotlin
fun setHover(point: CanvasPoint?, radius: Float) {
    hoverPoint = point
    hoverRadius = radius.coerceAtLeast(1f)
    invalidate()
}
```

`handleHoverEvent` updates the preview for `ACTION_HOVER_ENTER` and `ACTION_HOVER_MOVE`, then clears it for `ACTION_HOVER_EXIT`. `startInteraction`, `cancelAll`, and `onDetachedFromWindow` also clear it.

- [ ] **Step 3: Run focused tests**

Expected: hover tests pass; no stroke or history entry is produced.

- [ ] **Step 4: Commit only after approval**

```text
feat(ink): add stylus hover preview
```

### Task 5: Full regression and hardware boundary

**Files:**
- Create: `docs/qa/2026-09-01-seliasheets-ink-engine-v2.md`
- Modify: `README.md`

- [ ] **Step 1: Run the clean local gate**

```powershell
.\gradlew.bat --no-daemon clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run the full Huawei suite**

Install fresh debug/test APKs with serial `BQLDU19927002646`, then run the complete instrumentation runner.

Expected: zero failures; external active-pen tests remain explicit skips.

- [ ] **Step 3: Test four live scenarios**

1. Happy path: Pencil input with changing pressure/tilt/orientation in one stroke.
2. Edge case: missing optional axes and zero pressure fallback.
3. Failure path: palm/finger cannot steal a stylus-owned stroke.
4. Regression path: Pen, eraser, lasso, and page zoom remain aligned.

- [ ] **Step 4: Record honest evidence**

The QA report must separate synthetic input results, Huawei API 29 results, Android 17 availability, and physical active-stylus results. Do not mark physical pressure, tilt, hover, latency, or barrel buttons as accepted while Huawei reports `ExternalStylusConnected: false`.

- [ ] **Step 5: Stop before release**

Do not bump version, create a release, or submit Play changes in this slice. Review the diff and test evidence first.
