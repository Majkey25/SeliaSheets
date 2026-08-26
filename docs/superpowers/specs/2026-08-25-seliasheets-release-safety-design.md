# SeliaSheets release safety design

## Goal

Prepare SeliaSheets 0.2.1 beta for closed testing without known silent data-loss, self-incompatible backup, Android 10 scale, or unbounded undo-history defects.

## Scope

This release fixes seven verified defects:

1. A delayed Room echo can replace newer page text.
2. An editor ViewModel can retain pre-restore Undo state and write it into a replaced library.
3. Deleting a middle chapter creates a backup that the restore validator rejects.
4. Duplicating a rotated element can create geometry that the restore validator rejects.
5. Loading a notebook with more than SQLite's bind-variable limit fails on Android 10.
6. A valid-sized `records.jsonl` can contain an unbounded number of records.
7. A stylus that joins an active finger stroke does not cancel the accidental finger stroke.

The release also bounds Undo memory and samples images during PDF export. These fixes prevent a valid dense notebook from exhausting the Huawei YAL-L21 heap.

## Non-goals

This release does not add OCR, handwriting recognition, audio, cloud sync, accounts, collaboration, tables, or generative AI. It does not redesign the editor toolbar or library.

## Data-preservation rules

- Page text keeps the newest local draft until Room acknowledges that draft. An older Room value never replaces newer typing.
- `RestoreMode.REPLACE` cannot overlap an editor mutation. The backup screen consumes system Back while any backup operation runs.
- A successful replacement closes Backup, returns to Library, and increments a library generation. Every later editor uses a generation-specific ViewModel key, so pre-restore Undo state is unreachable.
- Backup validation rejects a page that references a chapter or PDF source from another notebook.
- Backup validation accepts only bounded record counts. The validator rejects the next record before storing it in an in-memory index.
- Remaining chapter indexes are contiguous after deletion.
- Every new or transformed element uses the same rotation-aware page-bound clamp.

## Scale rules

- Room notebook-content queries take `notebookId`. They use a subquery against `pages` and never expand a page list into SQL bind parameters.
- Undo history uses both a step limit and a byte budget. One oversized current snapshot remains usable, but the history retains no older states beyond the budget.
- PDF export groups strokes, elements, and blocks by page once. Image decode uses bounds plus `inSampleSize` for the target output size.

## Stylus rule

If a stylus or hardware eraser arrives while finger-originated ink is active, SeliaSheets cancels the finger ink before starting the stylus interaction. Existing stylus-first palm rejection remains unchanged.

## Release rules

- Package name stays `com.majkeylab.seliadocs`.
- Display name stays `SeliaSheets`.
- `minSdk` stays 29 and `targetSdk` stays 37.
- Version becomes `versionCode 3` and `versionName 0.2.1-beta.1`.
- The app keeps no `INTERNET`, ads, analytics, accounts, or telemetry.
- Public copy must not claim a visible tilt-sensitive brush until the renderer uses tilt.
- Closed testing is the only Google Play target for this release.

## Verification

The release requires unit tests, lint, Android instrumentation on API 29 and API 37, a signed APK and AAB, Huawei YAL-L21 runtime smoke testing, backup round trips, and a closed-track Play Console upload. No production-track submission is part of this release.
