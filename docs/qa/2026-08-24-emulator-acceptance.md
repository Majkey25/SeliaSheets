# Péřko v0.1.0-beta.1 emulator acceptance

Date: 2026-08-24

## Targets

| Target | AVD | Serial | Android | Result |
| --- | --- | --- | --- | --- |
| Tablet | `Perko_Tablet_API_37` | `emulator-5590` | 17 / API 37 | Pass |
| Phone | `Perko_Phone_API_29` | `emulator-5594` | 10 / API 29 | Pass |

No physical phone or tablet was accessed. The initially observed `emulator-5592` belonged to `TuneItAll_API_29`; its results were discarded after concurrent foreign instrumentation was confirmed in logcat. API 29 acceptance was repeated on the isolated Péřko AVD at port 5594.

## Automated evidence

- JVM parser, title, history, geometry, and support URL checks: pass.
- Android lint: pass with no blocking finding.
- Debug APK and release APK assembly: pass.
- API 37 instrumentation after the visual configurator update: `OK (28 tests)`, 66.611 seconds.
- API 29 instrumentation after the visual configurator update: `OK (28 tests)`, 33.772 seconds.
- Covered flows: launch, Room transactions, library create/rename/trash/delete, ordered pages, AndroidX Ink serialization and routing, palm cancellation, hardware eraser, lasso, image validation, text/math undo and redo, PDF rendering, DataStore validation, and App details.

## Live verification

- A real `adb input stylus` stroke rendered on the API 37 page and remained after a cold app restart.
- Stroke erasing, undo, redo, lasso selection, bounded movement, and page switching worked in the rendered editor.
- Photo Picker import copied media to private storage; text and image elements remained after restart and could be annotated with ink.
- Shape cleanup replaced selected ink with a clean arrow in one transaction; undo restored the original ink.
- `18/3+1=` produced the persisted local result `18/3+1 = 7` without a network request.
- The system document picker saved an A4 PDF. Poppler reported one valid 595 × 842 pt page, and the rendered PNG contained the ruled paper and ink without clipping.
- Settings were reachable from library and editor. App details displayed the version, legal links, disclosures, and the ScanIt-matched Buy Me a Coffee control.
- The creation modal showed four illustrated templates and a live cover/paper preview on both phone and tablet. Selecting Grid notebook updated the real grid-paper and portrait controls.
- Settings showed the same persisted notebook preview plus real pen/highlighter width samples instead of abstract numeric-only controls.

## Screenshots

- [Tablet editor](screenshots/tablet-editor.png)
- [Lasso selection](screenshots/tablet-lasso.png)
- [Text and image](screenshots/tablet-text-image.png)
- [Shape cleanup](screenshots/tablet-shape-cleanup.png)
- [Local math](screenshots/tablet-math.png)
- [App details and support](screenshots/tablet-app-details.png)
- [Android 10 library](screenshots/phone-api29-library.png)
- [Android 10 editor](screenshots/phone-api29-editor.png)
- [Tablet notebook creator](screenshots/tablet-new-notebook.png)
- [Tablet visual settings](screenshots/tablet-settings-defaults.png)
- [Phone notebook creator](screenshots/phone-new-notebook.png)
- [Phone visual settings](screenshots/phone-settings.png)

## Beta limits

- No account, cloud sync, collaboration, or background upload exists.
- Handwriting-to-text recognition is not included in this beta; typed arithmetic and deterministic shape cleanup work fully offline.
- QA covers Android emulators only. Hardware-specific hover and button behavior still requires later physical-device testing with explicit authorization.
