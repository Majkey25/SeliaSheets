# Spec: Péřko Android notebook

## Assumptions

- The app is an original Android product inspired by proven note-taking interactions. It does not copy Apple or Samsung branding, icons, assets, names, or exact screen layouts.
- The public name is `Péřko`, the repository is `Majkey25/Perko`, and the package ID is `cz.majkey.perko`.
- The first public build is `v0.1.0-beta.1` under Apache-2.0.
- Android emulator QA is the only device QA in scope. No physical phone or tablet is accessed.
- The app is offline-first. Network access is used only to download optional ML Kit handwriting models on explicit user action.
- The interface and public store copy are English only. Optional handwriting recognition supports Czech and English input.

## Objective

Build a tablet-first, phone-capable notebook for Android 10 through Android 17. Users can create notebooks with original covers, manage physical pages, write or draw with a stylus, add text and images, clean simple shapes, solve basic arithmetic, and export a notebook to PDF.

The app must reopen with saved content intact, remain usable without an account, and expose failures instead of discarding edits or inventing results.

## Product behavior

### Library

- Show notebooks as a restrained cover grid on tablets and an adaptive list or grid on phones.
- Search by title and mark notebooks as favorites.
- Create, rename, duplicate, and delete a notebook. Deletion requires confirmation and uses an in-app trash state before permanent removal.
- A new notebook asks for title, cover color, cover pattern, paper template, orientation, and whether finger drawing is enabled.

Folders, tags, cloud sync, accounts, and collaboration are outside the beta. Search and favorites cover the immediate organization need.

### Notebook editor

- Treat the cover as the first visual sheet and content pages as ordered physical pages.
- Show page thumbnails in a left rail on tablets. Use a modal page drawer on compact screens.
- Add, duplicate, reorder, and delete pages.
- Support blank, ruled, grid, and dot paper.
- Change pages with a horizontal paper transition. Disable the transition when Android reduced motion is active.
- Keep the canvas dominant. Use a slim top bar and one compact tool palette. Avoid cards, gradients, glass effects, decorative icons, and excessive rounding.

### Ink and stylus

- Use stable AndroidX Ink 1.0.0 for low-latency inking and stroke serialization.
- Support pen, pencil, and highlighter brushes; color and width; stroke eraser; lasso selection; undo and redo.
- Read stylus pressure, tilt, hover, eraser tool type, and palm classification from `MotionEvent` where hardware supplies them.
- Stylus writes while a finger pans, zooms, or changes pages. Finger drawing is an explicit toggle for devices without a stylus.
- Bound undo history to 100 actions for the open page. Persist committed page state after every debounced edit and when the activity stops.
- Do not use experimental Ink 1.1 partial-stroke erasing because its current API has no stable serialization contract.

### Text, images, and selection

- Insert editable text boxes.
- Import JPEG, PNG, WebP, and HEIF through Android Photo Picker or Storage Access Framework.
- Validate MIME type and decode bounds before copying an image into private app storage.
- Allow move, resize, rotate, delete, and annotate-over-image operations.
- Reject unsupported or corrupt files with an explicit message. Never remove the original source file.

### Shape cleanup

- A draw-and-hold action can replace one selected stroke group with a line, arrow, ellipse, rectangle, or triangle.
- Detect a straight line locally with geometry. A confirmed lasso selection can be replaced deterministically with a line, arrow, ellipse, rectangle, or triangle without a downloaded model.
- Show a preview and allow immediate undo. If the model is unavailable or confidence is ambiguous, keep the original ink.

### Math

- Evaluate a typed expression or a clearly recognized handwritten selection when it ends with `=`.
- Support decimal numbers, parentheses, unary minus, `+`, `-`, `*`, `×`, `/`, `÷`, and `^`.
- Use a small local parser. Never use `eval`, a script engine, a network service, or generated answer text.
- Limit input to 256 characters, reject division by zero and non-finite results, and keep the original expression on failure.
- ML Kit handwriting recognition is optional and downloaded on user request. Typed arithmetic works without a model.
- Variables, symbolic algebra, equation solving, and graphing are outside the beta.

### Export

- Export every notebook page to a paginated PDF through Android `PdfDocument`.
- Render paper, ink, text, shapes, and images in page order.
- Use the system document picker for the destination. Do not request broad storage permission.

### Settings and app details

- Open Settings from both the library and the editor.
- Group settings into Drawing, Paper, Recognition, Export, Interface, Data, and App details. Keep groups collapsed except Drawing on first open.
- Drawing settings: default tool, pen width and color, highlighter width and color, finger drawing, stylus-button eraser, shape hold delay from 400 to 1,200 ms, and haptics.
- Paper settings: default template, orientation, page size, paper tint, page shadow, include cover in PDF, and page transition.
- Recognition settings: Czech or English handwriting language, handwritten math, shape cleanup, and explicit download or removal of each local ML Kit model.
- Interface settings: system, light, or dark theme; left-handed toolbar; large controls; and system or always-reduced motion.
- Data settings: trash retention of 7 days, 30 days, or manual deletion; storage usage; and a confirmed Empty trash action. Autosave cannot be disabled.
- App details show version, privacy policy, third-party notices, source code, Android and ML Kit disclosures, and the emulator-only beta limit.
- Reuse the verified ScanIt support control exactly: full-width 56 dp yellow `#FFDD00` button, black `#111111` border and content, 24 dp coffee icon, and `Support this app → Buy Me a Coffee` label. It opens `https://www.buymeacoffee.com/majkey` after a short localized notice.
- Support is optional. It does not unlock features, change support priority, or send notebook data.

## Visual system

- Mood: quiet paper workspace, not a dashboard.
- Base colors: warm paper, neutral gray workspace, graphite text, one cobalt action color. Covers use a small curated palette.
- Typography: Android system type. No downloaded font.
- Spacing: 4 dp base scale. Canvas controls use at least 48 dp touch targets.
- Shape: 8 to 12 dp control radius, square paper corners with a subtle 2 dp radius, sparse elevation.
- Motion: 180 to 240 ms standard easing. No decorative looping motion.
- Accessibility: WCAG AA contrast, content descriptions, visible keyboard focus, keyboard shortcuts for undo, redo, and page navigation, and reduced-motion support.

## Architecture

- One Android application module.
- Compose owns screens, navigation, and adaptive layout.
- AndroidX Ink owns in-progress and finished strokes.
- Room 2.8.4 stores notebooks, pages, serialized strokes, and versioned element records.
- Imported images live under private app storage and are referenced by generated IDs, never external absolute paths.
- A repository class coordinates Room transactions and image files. Do not add a service, factory, or interface layer with one implementation.
- A page-scoped state holder owns selection and the bounded undo stack.
- Preferences DataStore 1.2.1 stores validated settings. Autosave safety settings are not user-configurable.
- ML Kit Digital Ink Recognition 19.0.0 owns optional handwriting recognition models. Shape cleanup remains deterministic and offline.
- PDF export runs on `Dispatchers.IO`. No blocking file or database work runs on the main thread.

### Data model

- `Notebook`: UUID, title, cover settings, favorite flag, timestamps, trash timestamp.
- `Page`: UUID, notebook UUID, page index, size, orientation, paper template.
- `Stroke`: UUID, page UUID, z-index, brush metadata, AndroidX Ink encoded bytes.
- `Element`: UUID, page UUID, z-index, kind, transform, and one validated payload for text, image, shape, or math result.

Room transactions keep page ordering and deletion consistent. Deleting a notebook removes its private image files only after the database transaction succeeds.

## Tech stack

- JDK 17.
- Android Gradle Plugin 9.1.1.
- Gradle 9.3.1.
- Kotlin 2.3.20.
- `compileSdk = 37`, `targetSdk = 37`, `minSdk = 29`.
- Jetpack Compose UI 1.12.0 and Material 3 1.4.0.
- AndroidX Ink 1.0.0.
- Room 2.8.4.
- Preferences DataStore 1.2.1.
- ML Kit Digital Ink Recognition 19.0.0.
- JUnit and AndroidX Compose testing already supplied by the Android toolchain. No extra formatting or dependency-injection framework.

## Commands

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:lintDebug --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```

Runtime installation and launch use the selected emulator serial explicitly:

```powershell
.\gradlew.bat :app:installDebug --console=plain
adb -s <emulator-serial> shell am start -n cz.majkey.perko/.MainActivity
```

## Project structure

```text
app/src/main/java/cz/majkey/perko/
  MainActivity.kt          Android entry point
  PerkoApp.kt              navigation and adaptive app shell
  data/                    Room entities, DAO, database, repository
  editor/                  page state, ink, elements, math, export
  library/                 notebook library and creation flow
  settings/                validated preferences and app details
  ui/                      reusable controls and theme
app/src/main/res/          strings, icons, XML configuration
app/src/test/              deterministic JVM tests
app/src/androidTest/       emulator UI and stylus-event checks
docs/                      design, architecture, privacy, release evidence
```

## Code style

Use immutable state, explicit types at boundaries, coroutines for asynchronous work, and direct names. Keep logic in the smallest class that owns it.

```kotlin
internal fun evaluateExpression(source: String): Result<Double> = runCatching {
    require(source.length <= MAX_EXPRESSION_LENGTH)
    val value = ExpressionParser(source.removeSuffix("=")).parse()
    require(value.isFinite())
    value
}
```

No `Any`, unchecked casts, duplicate serialization, hidden `null` results, global mutable caches, or blocking I/O in coroutine or UI code.

## Testing strategy

### JVM checks

- Math parser: valid arithmetic, precedence, unary values, malformed input, size limit, division by zero, and non-finite output.
- Page ordering and trash transitions.
- Element serialization round trips exactly once.
- Shape geometry for straight-line acceptance and rejection.

### Emulator checks

- API 29 phone-sized emulator: compact navigation, touch drawing toggle, system document-picker fallback, import failure, autosave, relaunch, and PDF export.
- API 37 tablet emulator: library grid, creation flow, page rail, page transition, text and image transforms, drawing, erase, undo, redo, reorder, and restart persistence.
- Inject `MotionEvent` fixtures with stylus, eraser, pressure, tilt, and palm flags to verify input routing. Emulator QA does not claim physical pen latency.
- Test optional-model success after download and explicit unavailable and offline behavior when the model is absent.
- Verify settings persistence, model download and removal states, the fixed support URL, external URI launch, App details content, and confirmed trash deletion.

### Required live scenarios

1. Happy path: create a notebook, draw, add text and an image, calculate, add a page, export a PDF, restart, and verify persistence.
2. Edge path: normalize an empty notebook title, reject excessive math input, rotate and resize an image near page bounds, and enforce the 100-action undo limit.
3. Negative path: corrupt image, unavailable ML model, invalid expression, division by zero, and interrupted export.
4. Regression path: reopen and continue editing an older saved page after adding a second notebook.

## Boundaries

### Always

- Preserve user content on every failed recognition, calculation, import, or export.
- Use explicit emulator serials.
- Run unit tests, Android lint, build, connected tests, and live flows before release.
- Keep release signing material out of Git and logs.
- Verify the downloaded GitHub APK signature, package, version, and SHA-256 before publishing completion.

### Require new approval

- Physical device access.
- Play Store publication or paid services.
- Cloud sync, accounts, analytics, telemetry, or collaboration.
- Destructive removal of emulator or user data outside the app package.

### Never

- Copy Apple or Samsung icons, assets, trademarks, screenshots, or exact layouts into the app.
- Request all-files storage access.
- Upload notebook content or recognition input.
- Hide failures or claim physical stylus performance from emulator evidence.

## Success criteria

- App installs and launches on API 29 and API 37 emulators.
- A user can create, rename, duplicate, favorite, trash, and restore multiple notebooks.
- Covers and all four page templates render on phone and tablet layouts.
- Pages can be added, reordered, duplicated, deleted, and changed with accessible motion.
- Pen, highlighter, stroke eraser, lasso, undo, and redo work and persist after process restart.
- Stylus routing consumes pressure, tilt, hover, eraser, and palm data in instrumented checks.
- Text and valid images can be inserted, transformed, persisted, and drawn over.
- Supported shapes clean up locally and ambiguous input remains unchanged.
- Typed arithmetic works offline. Optional handwriting recognition has success and missing-model states.
- PDF export contains every page. An interrupted or failed export does not lose content.
- Settings persist across process restart and affect new notebooks or editor presentation as documented.
- App details show the installed version, privacy, notices, source link, and ML Kit disclosure.
- The ScanIt-style support button opens only `https://www.buymeacoffee.com/majkey` and unlocks nothing.
- Unit tests, Android lint, debug build, release build, and connected tests pass.
- GitHub Actions passes and `v0.1.0-beta.1` contains a verified signed APK, SHA-256, README, screenshots, privacy statement, and changelog.

## Open questions

None. The approved defaults above define the beta.
