# SeliaSheets Handwriting Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize Czech or English page ink on-device and safely add automatic results for uniquely recognized, supported single-line arithmetic while preserving raw strokes and Undo/Redo.

**Architecture:** A pure Kotlin candidate gate and bounded request model isolate editor logic from ML Kit. An ML Kit adapter and explicit model manager provide downloadable language packs; `EditorViewModel` debounces recent raw strokes, validates request fingerprints before mutation, and stores generated output as the existing `MATH` element without replacing ink.

**Tech Stack:** Kotlin 2.3.10, Android API 29+, Compose 1.12.0, Room 2.8.4, AndroidX Ink 1.0.0, ML Kit Digital Ink Recognition 19.0.0, JUnit 4, AndroidJUnitRunner.

**Spec:** `docs/superpowers/specs/2026-08-26-seliasheets-handwriting-recognition-design.md`

## Global Constraints

- Keep `minSdk = 29`, `compileSdk = 37`, and `targetSdk = 37`.
- Add only `com.google.mlkit:digital-ink-recognition:19.0.0`; do not add Firebase or image OCR.
- Add only `INTERNET` and `ACCESS_NETWORK_STATE` network permissions.
- Never log, upload, replace, or delete source ink during recognition.
- Recognition remains optional and model downloads are explicit user actions.
- Auto-insert only a unique parser-valid single-line arithmetic candidate ending in `=`.
- Ambiguous, invalid, stale, missing-model, and failed requests leave the page unchanged.
- Undo removes a generated result before its source ink; Redo restores the result.
- No database or backup schema migration.
- No commit, push, or PR step may run without separate explicit user approval.

---

### Task 1: Pure Recognition Contract and Candidate Gate

**Files:**
- Create: `app/src/main/java/com/majkeylab/seliadocs/recognition/InkRecognitionModels.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/recognition/InkMathCandidateGate.kt`
- Create: `app/src/test/java/com/majkeylab/seliadocs/recognition/InkMathCandidateGateTest.kt`

**Interfaces:**
- Consumes: `evaluateExpression(String): Result<Double>` and `formatMathResult(Double): String` from `editor/MathEvaluator.kt`.
- Produces: `RecognitionPoint`, `RecognitionStroke`, `RecognitionRequest`, `RecognitionCandidate`, `InkMathCandidate`, `InkMathDecision`, and `decideInkMath(List<RecognitionCandidate>): InkMathDecision`.

- [ ] **Step 1: Write the failing candidate-gate tests**

```kotlin
class InkMathCandidateGateTest {
    @Test fun uniqueCandidateProducesResult() {
        assertEquals(
            InkMathDecision.Unique(InkMathCandidate("2+3=", "5")),
            decideInkMath(listOf(RecognitionCandidate("2 + 3 ="))),
        )
    }

    @Test fun equivalentCandidatesCollapse() {
        assertTrue(
            decideInkMath(
                listOf(RecognitionCandidate("2×3="), RecognitionCandidate("2 * 3 =")),
            ) is InkMathDecision.Unique,
        )
    }

    @Test fun distinctValidCandidatesAreAmbiguous() {
        assertTrue(
            decideInkMath(
                listOf(RecognitionCandidate("2+3="), RecognitionCandidate("2+8=")),
            ) is InkMathDecision.Ambiguous,
        )
    }

    @Test fun invalidCandidatesInsertNothing() {
        listOf("2+=", "1/0=", "hello", "2+2").forEach {
            assertEquals(InkMathDecision.None, decideInkMath(listOf(RecognitionCandidate(it))))
        }
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.majkeylab.seliadocs.recognition.InkMathCandidateGateTest
```

Expected: compilation fails because the recognition contract does not exist.

- [ ] **Step 3: Implement bounded immutable types and deterministic gate**

```kotlin
internal data class RecognitionPoint(val x: Float, val y: Float, val timeMillis: Long)
internal data class RecognitionStroke(val points: List<RecognitionPoint>)
internal data class RecognitionFingerprint(val strokeId: String, val payloadHash: Int)
internal data class RecognitionRequest(
    val pageId: String,
    val pageWidth: Float,
    val pageHeight: Float,
    val fingerprints: List<RecognitionFingerprint>,
    val strokes: List<RecognitionStroke>,
)
internal data class RecognitionCandidate(val text: String)
internal data class InkMathCandidate(val expression: String, val result: String)
internal sealed interface InkMathDecision {
    data class Unique(val candidate: InkMathCandidate) : InkMathDecision
    data class Ambiguous(val candidates: List<InkMathCandidate>) : InkMathDecision
    data object None : InkMathDecision
}
```

Normalize whitespace and `×`/`÷`, require the final `=`, call the existing parser, deduplicate by normalized expression and result, and return candidates in input order. Reject requests beyond 32 strokes or 4,096 points in a separate `boundedRecognitionRequest(...)` factory returning `null`.

- [ ] **Step 4: Run GREEN and all JVM tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 5: Commit gate**

Skip unless explicit commit approval is received. Intended message: `feat: add safe ink math candidate gate`.

### Task 2: ML Kit Mapping and Explicit Model Management

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/majkeylab/seliadocs/recognition/InkTextRecognizer.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/recognition/MlKitInkTextRecognizer.kt`
- Create: `app/src/main/java/com/majkeylab/seliadocs/recognition/RecognitionModelManager.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/recognition/InkRecognitionMapperTest.kt`

**Interfaces:**
- Consumes: `RecognitionRequest` and `RecognitionCandidate` from Task 1.
- Produces:

```kotlin
internal enum class RecognitionLanguage(val tag: String) { CZECH("cs"), ENGLISH("en-US") }
internal sealed interface RecognitionModelStatus {
    data object NotDownloaded : RecognitionModelStatus
    data object Downloading : RecognitionModelStatus
    data object Ready : RecognitionModelStatus
    data class Failed(val message: String) : RecognitionModelStatus
}
internal interface InkTextRecognizer : AutoCloseable {
    suspend fun recognize(request: RecognitionRequest): List<RecognitionCandidate>
}
```

- [ ] **Step 1: Add failing mapper tests**

The tests create two `RecognitionStroke` values with overlapping elapsed times and assert the ML Kit adapter emits strokes in request order and strictly nondecreasing absolute point times. Empty strokes, non-finite points, more than 32 strokes, and more than 4,096 points must be rejected before ML Kit invocation.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

Expected: compilation fails because mapper and ML Kit dependency are absent.

- [ ] **Step 3: Add the dependency and permissions**

```kotlin
implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
```

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

- [ ] **Step 4: Implement the ML Kit adapter**

Convert each request stroke to `Ink.Stroke`, offset stroke-local times into one monotonic timeline, and call `recognizer.recognize(ink, context)`. Implement Google `Task<T>` suspension with `suspendCancellableCoroutine`; cancellation must stop delivering results even when the underlying task cannot be canceled. Supply `WritingArea(pageWidth, estimatedSingleLineHeight)` and no note-content logs.

- [ ] **Step 5: Implement model status/download/delete**

Use `RemoteModelManager.isModelDownloaded`, `download`, and `deleteDownloadedModel`. Status begins from an actual model query, not a saved preference. Download changes `NotDownloaded -> Downloading -> Ready` or `Failed`; drawing does not depend on status.

- [ ] **Step 6: Run GREEN**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin :app:lintDebug
```

Expected: `BUILD SUCCESSFUL`, zero lint errors.

- [ ] **Step 7: Commit adapter**

Skip unless explicit commit approval is received. Intended message: `feat: add offline digital ink model manager`.

### Task 3: Persisted Recognition Settings and Model UI

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/AppSettings.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/settings/SettingsRepositoryTest.kt`
- Modify: `app/src/androidTest/java/com/majkeylab/seliadocs/settings/SettingsFlowTest.kt`

**Interfaces:**
- Consumes: `RecognitionLanguage` and `RecognitionModelStatus` from Task 2.
- Produces: `AppSettings.handwritingRecognition: Boolean`, `AppSettings.recognitionLanguage: RecognitionLanguage`, and Settings callbacks for `downloadModel(language)` / `deleteModel(language)`.

- [ ] **Step 1: Write failing persistence and UI tests**

Assert the switch defaults off, Czech is the default language, both values survive a newly created DataStore instance, and the Settings group displays explicit Download/Delete actions from real model status. Assert the download description says approximately 20 MB and that drawing settings remain independently preserved during rapid updates.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

Expected: missing fields and UI actions fail compilation.

- [ ] **Step 3: Add validated settings and atomic transform updates**

Persist `handwriting_recognition` as Boolean and `recognition_language` as enum name. Decode unknown language values as `CZECH`. Keep all Settings writes as repository transforms so a model toggle cannot overwrite brush color, width, or theme.

- [ ] **Step 4: Add model UI**

Add a Recognition subsection under the existing Drawing group. Download is enabled only for `NotDownloaded`/`Failed`; Delete only for `Ready`; the switch can remain enabled while the model is missing but shows a non-blocking explanation. Do not start downloads from composition or startup.

- [ ] **Step 5: Run GREEN on Huawei and locally**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
```

Then run `SettingsRepositoryTest` and `SettingsFlowTest` on the exact authorized Huawei ADB serial with the isolated QA package.

- [ ] **Step 6: Commit settings**

Skip unless explicit commit approval is received. Intended message: `feat: add handwriting model settings`.

### Task 4: Editor Recognition Pipeline and History Safety

**Files:**
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/seliadocs/editor/EditorScreen.kt`
- Create: `app/src/androidTest/java/com/majkeylab/seliadocs/editor/HandwrittenMathFlowTest.kt`

**Interfaces:**
- Consumes: `InkTextRecognizer`, `RecognitionRequest`, `decideInkMath`, existing `ElementKind.MATH`, `PageHistory`, and `LibraryMutationGate`.
- Produces: `EditorUiState.recognitionStatus`, `EditorUiState.ambiguousMathCandidates`, `chooseMathCandidate(InkMathCandidate)`, and `dismissMathCandidates()`.

- [ ] **Step 1: Write failing flow tests with an injected recognizer**

Cover:

```text
raw stroke -> unique candidate -> raw stroke remains + one MATH element
Undo -> MATH removed, raw stroke remains
Redo -> MATH restored
ambiguous candidates -> no element until explicit choice
new stroke/erase/Undo/page switch before completion -> stale result discarded
missing model/failure -> stroke still persisted, editor failure flag unchanged
shape-recognized stroke -> handwriting recognition not scheduled
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

Expected: editor constructor and UI state do not expose recognition dependencies or state.

- [ ] **Step 3: Inject bounded recognition dependencies**

Add optional constructor dependencies with production defaults created by the factory. Keep test injection deterministic. Store one `recognitionJob`, one generation counter, and at most one pending ambiguity list.

- [ ] **Step 4: Debounce and recognize outside mutation lock**

After persisting a non-shape pen/pencil stroke, capture at most the newest 32 strokes and 4,096 points, delay one second, then recognize outside `LibraryMutationGate`. Cancel on new ink, erase, selection mutation, Undo, Redo, page/tool change, close, and `onCleared`.

- [ ] **Step 5: Revalidate and insert one history unit**

Inside `mutate`, load current strokes and compare IDs plus payload hashes with the request. On a match, add one existing `MATH` element beside clamped source bounds and push exactly one new history snapshot. Do not call `replaceStrokesWithElement`.

- [ ] **Step 6: Render ambiguity UI**

Show a Material bottom sheet with parser-valid candidate expressions and Dismiss. Selecting a candidate revalidates the request before insertion. Rotation/recreation may dismiss transient ambiguity; it must never insert automatically after state loss.

- [ ] **Step 7: Run GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
```

Run `HandwrittenMathFlowTest`, `SmartShapeFlowTest`, `PageTextFlowTest`, and `ElementFlowTest` on Huawei.

- [ ] **Step 8: Commit editor pipeline**

Skip unless explicit commit approval is received. Intended message: `feat: recognize supported handwritten equations`.

### Task 5: Privacy, Store Metadata, and Release Identity

**Files:**
- Modify: `PRIVACY.md`
- Modify: `site/privacy/index.html`
- Modify: `README.md`
- Modify: `THIRD_PARTY_NOTICES.md`
- Modify: `docs/play-store/DATA_SAFETY.md`
- Modify: `docs/play-store/PLAY_CONSOLE.md`
- Modify: `docs/play-store/STORE_LISTING.md`
- Modify: `CHANGELOG.md`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: final merged manifest and verified ML Kit behavior from Tasks 2-4.
- Produces: version code 4, next beta version name, accurate hosted privacy text, and exact Play declarations.

- [ ] **Step 1: Update truthful privacy text**

State that notebook ink and recognition results remain on-device, models download from Google, and ML Kit may send device/app information, installation identifiers, app interactions, diagnostics, configured language, errors, and performance/usage metrics to Google over encrypted transport. State no first-party ads, accounts, cloud sync, or analytics.

- [ ] **Step 2: Update Data Safety working answers**

Record `Collects data: Yes`, `Shares data: No`, encrypted in transit, and required categories `Device or other IDs`, `App interactions`, and `Diagnostics` for Analytics according to Google’s SDK disclosure. Do not claim ephemerality or publisher-controlled deletion of Google-held metrics.

- [ ] **Step 3: Update release identity**

Increment `versionCode` from 3 to 4 and choose the next beta version name only when packaging begins. Keep `v0.2.1-beta.1` immutable.

- [ ] **Step 4: Verify documentation and manifest consistency**

```powershell
.\gradlew.bat :app:processReleaseMainManifest
rg -n "no INTERNET|no network|zero-network|handwriting recognition omitted" README.md PRIVACY.md site docs
```

Expected: merged manifest includes only intended network permissions; no stale zero-network or omitted-recognition promise remains.

- [ ] **Step 5: Commit documentation**

Skip unless explicit commit approval is received. Intended message: `docs: disclose on-device handwriting models`.

### Task 6: Physical Huawei Acceptance and Final Gates

**Files:**
- Create: `docs/qa/2026-08-26-seliasheets-handwriting-recognition.md`
- Create temporary evidence only under: `.reference/tmp/handwriting-recognition/`

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces: reproducible acceptance evidence and release blockers.

- [ ] **Step 1: Build and install an isolated QA package**

Build `com.majkeylab.seliadocs.qa`; install app and test APK with every ADB command pinned to the authorized Huawei serial. Preserve production and debug app data.

- [ ] **Step 2: Test model lifecycle**

Download Czech and English models, verify `Ready`, delete one, verify `NotDownloaded`, redownload, and record actual bytes/time/errors. Do not print identifiers or note content.

- [ ] **Step 3: Test supported handwriting online and offline**

Use three simple single-line arithmetic samples, one ambiguous sample, and one invalid sample. Disable networking after download, repeat recognition, restart the app offline, and repeat. Record candidate text only from synthetic/non-sensitive fixtures.

- [ ] **Step 4: Test editor safety**

Verify raw ink remains, result position is clamped, ambiguity requires choice, model failure leaves ink, Undo/Redo order is correct, page switch discards stale output, and smart shapes still restore raw ink.

- [ ] **Step 5: Run all gates**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Run the complete instrumentation suite on Huawei excluding only release-identity assertions for the isolated package, then run those identity assertions against the production artifact. Retry only confirmed Compose harness flakes individually and report them separately.

- [ ] **Step 6: Clean test-only resources**

Remove the instrumentation package and temporary remote screenshots/models only when owned by QA. Leave the QA app installed for user testing unless the user requests removal. Do not delete note data.

- [ ] **Step 7: Commit acceptance evidence**

Skip unless explicit commit approval is received. Intended message: `test: verify handwriting recognition on Huawei`.
