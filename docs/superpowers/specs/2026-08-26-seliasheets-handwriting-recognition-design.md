# SeliaSheets handwriting recognition design

Date: 2026-08-26

## Goal

Add production-grade, on-device recognition for handwritten page ink on Android 10 and newer. The first supported automatic workflow is single-line arithmetic written with the pen. Recognition must never delete source ink, guess through ambiguity, block normal editing, or corrupt Undo/Redo history.

## Product contract

- Handwriting recognition is optional and disabled until a language model is downloaded.
- English and Czech language packs are supported initially.
- A language pack is downloaded only after an explicit user action.
- Recognition runs on-device after download.
- Original ink remains the source of truth.
- A uniquely recognized, parser-valid expression ending in `=` can add a linked math result beside the ink.
- Ambiguous, invalid, stale, or unsupported recognition never inserts a result automatically.
- The first Undo removes the generated result and keeps the raw ink. Redo restores the result.
- Normal drawing remains available when the model is missing, downloading, offline, or failing.
- General two-dimensional mathematics is not claimed. Fractions, roots, matrices, graphs, superscripts, LaTeX, and MathML require a separate specialized model.

## Dependencies and platform support

- Add `com.google.mlkit:digital-ink-recognition:19.0.0`.
- Add `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE` for model management.
- Keep `minSdk = 29`; ML Kit requires API 23 or newer.
- Use dynamically downloaded `cs` and `en-US` models. Each model requires approximately 20 MB.
- Do not add Firebase, an API key, image OCR, or Google Play Services-specific recognition dependencies.

## Architecture

### `recognition/InkRecognitionModels.kt`

Defines bounded, framework-independent types:

- `RecognitionStroke`: ordered points and timestamps.
- `RecognitionRequest`: page ID, writing-area dimensions, captured stroke fingerprints, and ordered strokes.
- `RecognitionCandidate`: candidate text only.
- `InkMathDecision`: `Unique`, `Ambiguous`, or `None`.

No Android or ML Kit type crosses this boundary.

### `recognition/InkMathCandidateGate.kt`

Normalizes mechanical glyph variants such as multiplication and division symbols, then evaluates only candidates that:

1. end in `=`;
2. pass the existing bounded arithmetic parser;
3. produce a finite value;
4. collapse to one unique normalized expression and result.

Distinct valid expressions or results are ambiguous. Invalid candidates produce `None`. The gate never uses regex or keyword classification for semantic intent.

### `recognition/DigitalInkRecognizer.kt`

An interface isolates asynchronous recognition from the editor. The ML Kit implementation:

- converts ordered SeliaSheets stroke inputs to ML Kit `Ink`;
- preserves stroke and point order;
- converts elapsed stroke timing to a monotonic request timeline;
- supplies a one-line writing area;
- returns ordered candidate strings;
- never logs stroke data or recognized text;
- closes recognizer clients when their owner is cleared.

Tests use a deterministic fake implementation at this boundary only.

### `recognition/RecognitionModelManager.kt`

Owns model status, explicit download, deletion, and recognizer creation. Status is one of:

- `NotDownloaded`
- `Downloading`
- `Ready`
- `Failed`

The manager exposes failures without disabling drawing. Downloads are user initiated and never run silently at startup.

### Editor integration

`EditorViewModel` records non-shape pen/pencil strokes normally, then schedules recognition for the recent bounded stroke burst after approximately one second of inactivity.

Recognition work runs outside `LibraryMutationGate`. Before inserting any result, the editor reacquires the mutation gate and verifies:

- the page still exists and is selected;
- captured stroke IDs still exist;
- stroke payload fingerprints still match;
- no erase, Undo, Redo, page switch, tool change, or newer burst invalidated the request.

Stale results are discarded silently.

A unique result is stored as the existing `ElementKind.MATH` beside the union bounds of its source strokes. It gets its own history snapshot. Source strokes are never replaced. Existing Room entities, backup format, and database schema already support this, so no migration is required.

Ambiguous candidates are transient `EditorUiState`; choosing one validates it again before insertion. Dismissal keeps the ink unchanged.

## Bounds and lifecycle

- Maximum 32 strokes per recognition burst.
- Maximum 4,096 points per burst.
- Maximum 10 seconds of captured stroke time.
- Only one pending job per editor session.
- New ink cancels and replaces the pending debounce.
- Erase, Undo, Redo, page change, tool change, close, and ViewModel clear cancel the request.
- Recognition exceptions map to UI status and never fail the editor mutation state.

## UI

Settings > Recognition and smart tools gains:

- Handwriting recognition switch.
- Recognition language: Czech or English.
- Download model / Delete model action.
- Download progress and model size disclosure.
- Clear statement that input stays on-device while Google ML Kit may transmit SDK diagnostics and usage metrics.

The editor gains:

- a small non-blocking model-unavailable status when recognition is enabled without a model;
- an ambiguity bottom sheet listing parser-valid candidates;
- generated math displayed beside the source ink;
- no modal equation text box.

## Privacy and Google Play

Before publishing this version:

- update `PRIVACY.md` and the GitHub Pages privacy page;
- update `README.md` zero-network wording;
- update `docs/play-store/DATA_SAFETY.md`, `PLAY_CONSOLE.md`, and `STORE_LISTING.md`;
- update `THIRD_PARTY_NOTICES.md` and release notes;
- declare Google ML Kit diagnostics and usage metrics, device or other IDs, app interactions, and diagnostics according to the SDK disclosure;
- declare encrypted transport and no data sharing;
- verify the final merged manifest contains only the intended network permissions;
- increment version code above 3 and create a new release. Do not replace `v0.2.1-beta.1`.

## Verification

### JVM tests

- unique valid equation;
- equivalent candidates collapse to one result;
- distinct valid candidates remain ambiguous;
- missing `=`, division by zero, malformed input, and non-finite results insert nothing;
- normalization and burst bounds.

### Instrumentation tests

- stroke order and monotonic timestamp mapping;
- model missing/download failure does not block drawing;
- raw strokes remain after generated result;
- stale recognition is discarded after edit, erase, Undo, and page change;
- Undo removes result before ink; Redo restores it;
- ambiguity inserts nothing until selection;
- existing typed math and smart-shape behavior remain intact.

### Physical Huawei acceptance

- download Czech and English models;
- recognize at least three supported single-line arithmetic examples;
- test ambiguous and invalid input;
- disable networking and recognize again;
- restart the app offline and recognize again;
- verify Undo/Redo and raw-ink preservation;
- verify model deletion returns the app to `NotDownloaded` without affecting notes.

## Non-goals for this slice

- image or PDF OCR;
- general 2-D handwritten mathematics;
- cloud recognition;
- destructive handwriting-to-text conversion;
- silent model download;
- table generation, graphs, LaTeX, or MathML.

Those require separate specs and acceptance criteria after this recognition foundation is verified.
