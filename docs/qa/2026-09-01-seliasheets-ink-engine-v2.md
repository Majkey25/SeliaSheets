# SeliaSheets Ink Engine V2 evidence

## Scope

This development slice upgrades AndroidX Ink from `1.0.0` to `1.1.0-alpha07`, adds a SeliaSheets Pencil brush with per-input pressure, tilt, opacity, and orientation behavior, removes pointer-down-only Pencil mutation, adds a non-persistent stylus hover preview, replaces the non-compact text toolbar with a fixed Material icon palette, and adds undoable duplicate, recolor, and delete actions for lasso-selected ink.

The slice does not add reusable brush presets, bump the application version, create a release, or submit Google Play changes.

## Root cause

The old Pencil implementation read `MotionEvent.AXIS_TILT` only during pointer down and created one wider, lighter immutable brush for the whole stroke. Tilt and orientation changes during the stroke could not affect its mesh.

AndroidX Ink `1.1.0-alpha07` exposes custom `BrushBehavior` sources and targets for pressure, tilt, orientation, size, width, rotation, and opacity. Its MotionEvent conversion also validates optional axes against the physical input device's declared motion ranges.

The existing synthetic stylus events use device ID `0`. On alpha07, their optional fields are correctly stored as absent because `MotionEvent.device` is null. Explicit `StrokeInput` batches now test pressure, tilt, and orientation persistence. External active-stylus tests remain the hardware gate.

## Evidence

- Dependency-only local gate passed: JVM tests, Android lint, debug APK, and instrumentation APK.
- The first alpha07 Huawei run executed 31 focused tests. Two old synthetic-axis assertions failed because alpha07 returned `NO_PRESSURE`, `NO_TILT`, and `NO_ORIENTATION` for a device-less MotionEvent. The other routing and viewport tests passed.
- The new Pencil codec test failed before production implementation because `BrushKind.PENCIL` and `SeliaInkBrushes` did not exist.
- Pencil codec GREEN: 4 tests passed on Huawei, including old Pen compatibility and Pencil pressure/tilt/orientation round-trip.
- Pointer-down mutation RED: the tilted synthetic stroke changed base brush size from `2.2` to `5.2252173`.
- Pointer-down mutation GREEN: both strokes retain the same dynamic Pencil family, base size, and color.
- Affected regression run passed 37 tests covering Ink codec, stylus routing, zoom/viewport routing, and smart shapes.
- Hover lifecycle tests failed before the preview property and implementation existed, then passed after implementation.
- Complete `StylusRoutingTest` passed 19 tests with zero failures on Huawei API 29.
- A fresh clean gate passed `testDebugUnitTest`, `lintDebug`, debug and instrumentation APK assembly, release APK assembly, and release AAB assembly: `BUILD SUCCESSFUL` with 150 tasks.
- The first complete Huawei run exposed five test-harness failures: one off-screen backup action, three physical-density assumptions inside 360 dp test overrides, and one nested notebook-template scroll. The affected tests passed individually after switching to semantic scrolling, override-derived density, and one bounded parent scroll.
- The final fresh Huawei run completed all 280 instrumentation tests in 202.228 seconds: `OK (280 tests)`. Four external-stylus-only cases were reported as assumption skips because no external stylus was connected; they were not counted as physical-pen acceptance.
- Toolbar TDD RED: the old medium/tablet toolbar had no fixed palette node and always exposed brush options inside a horizontally scrolling text row.
- Toolbar GREEN: two Huawei tests verify icon descriptions, radio selection, a non-scrollable primary palette, distinct Pen/Pencil tools, and anchored brush/eraser options opened by a second tap.
- The post-toolbar clean gate again passed all 150 tasks, including JVM tests, lint, debug/test APKs, release APK, and release AAB.
- A post-toolbar 281-test run was externally interrupted while another package took the foreground. The resulting failures were `Activity destroyed` or `No compose hierarchies found`, not failed SeliaSheets assertions. All new toolbar and stylus tests had passed before that interruption.
- The four interrupted library/settings cases were rerun after a pause and passed: `OK (4 tests)` in 11.083 seconds.
- Selection TDD RED: `EditorViewModel` exposed no duplicate, recolor, or delete operation for selected strokes, and the editor exposed no ink context bar.
- Selection GREEN: duplicate copies only selected strokes with new IDs and top z-order; delete removes only selected strokes; recolor preserves raw input bytes, brush kind, size, and per-stroke alpha. All three operations use the existing page history for Undo.
- The current active-stylus/viewport/codec regression set passed on Huawei: `OK (38 tests)`. Three external-stylus-only tests were assumption skips.
- The final clean local gate passed all 150 tasks. A fresh uninterrupted Huawei run completed in 180.895 seconds: `OK (286 tests)`.
- After consolidating the shared Pen/selection color palette, the current code passed JVM tests, lint, debug/test APK assembly, release APK assembly, and release AAB assembly: `BUILD SUCCESSFUL` with 149 tasks. The affected selection, compact, and tablet-toolbar set then passed on Huawei: `OK (32 tests)`.
- Selected-ink transform TDD RED: `EditorViewModel` and `InkContextBar` exposed no scale or rotation operation.
- Selected-ink transform GREEN: the current Huawei run passed `OK (14 tests)`. It covers proportional scale, page-space rotation, orientation normalization, pressure and tilt preservation, invalid input rejection, page clamping, the Transform menu, and one-step Undo.
- The wider selection, navigation, viewport, codec, smart-shape, and stylus routing set passed on Huawei: `OK (73 tests)` in 52.253 seconds. Three external-stylus-only cases remained assumption skips.
- The final current-code Huawei run completed without interruption in 190.681 seconds: `OK (288 tests)`. The temporary USB stay-awake value was restored to `0` after the run.
- Direct-handle TDD RED: selected ink had no group bounds, scale handle, rotation handle, live preview, or gesture-owner cleanup.
- Direct-handle GREEN: five focused tests cover selected-only bounds, 48 dp scale and rotation targets, live drag preview, one commit on release, accessibility rotation, identity reset between commits, and ownership release on disposal.
- The clean post-handle local gate passed all 150 tasks. The Huawei aggregate ran 293 tests with 292 passes and one early `NoActivityResumedException` in `NavigationBackTest`; that exact test passed alone after a pause. The aggregate is not recorded as `OK (293 tests)` because the original run contained one environmental failure.

## Verified scenarios

1. Happy path: Pencil strokes keep one dynamic brush family and preserve changing pressure, tilt, and orientation inputs through storage round-trip.
2. Edge case: device-less synthetic MotionEvents do not invent pressure, tilt, or orientation; Pencil keeps a valid fallback brush.
3. Failure path: finger and canceled palm input cannot steal or commit a stylus-owned stroke; hover never writes ink.
4. Regression path: Pen, eraser, lasso, image transform, page text, page turns, pinch zoom, smart-shape undo, backup, and search flows passed in the complete Huawei suite.
5. Selection path: lasso-selected ink moves, duplicates, recolors, deletes, and restores through Undo without changing stored pressure, tilt, orientation, or highlighter opacity.

## Hardware boundary

Huawei `BQLDU19927002646` is the only connected Android target and reports:

```text
ExternalStylusConnected: false
External Stylus ID: -1
```

The device verifies Android 10 compatibility, routing, persistence, hover state logic, zoom mapping, eraser behavior, and fallback behavior. It cannot verify physical pressure, tilt, orientation, hover distance, latency, or barrel buttons.

## Emulator boundary on 2026-09-04

The existing `SeliaSheets_Tablet_QA` AVD is a Pixel Tablet image on Android 16/API 36 with x86_64 ABI. Emulator 37.1.11 stopped before ADB registration with:

```text
x86_64 emulation currently requires hardware acceleration
Virtualization Enabled In Firmware: No
Android Emulator hypervisor driver is not installed on this machine
```

`aehd` is installed but stopped with Windows exit code 31. No ARM system image is installed. Therefore no current emulator stylus test was executed, and no emulator process remains running. Enabling CPU virtualization in firmware is the required host-side prerequisite; this QA task did not change BIOS, Windows optional features, or kernel drivers.
