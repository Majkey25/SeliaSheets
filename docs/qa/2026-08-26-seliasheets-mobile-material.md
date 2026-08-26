# SeliaSheets mobile Material acceptance

Date: August 26, 2026

Commit under test: `4ff248c` plus review fix `6d0eda9`.

## Result

The mobile Material and page-gesture slice passed the final clean build and both complete instrumentation suites. Live checks covered the compact, medium, and expanded layouts. The four phone Play screenshots were recaptured at 1080 x 2280 and inspected before replacement.

The Huawei performance capture found frame spikes. This report does not claim that the flow is optimized.

## Clean quality gate

The final command used the external signing-properties file without printing its contents:

```powershell
.\gradlew.bat -PseliaSheetsKeystoreProperties=<external-properties-file> clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease --console=plain
```

Result: `BUILD SUCCESSFUL` in 2 minutes 52 seconds. Gradle ran 151 tasks, including the JVM tests, Android lint, the debug APK, the instrumentation APK, the signed release APK, and the signed release AAB.

Release verification:

- `app-release.apk`: `apksigner verify` exited 0, APK Signature Scheme v2, one signer.
- `app-release.aab`: `jarsigner -verify` exited 0 and returned `jar verified`.
- Package: `com.majkeylab.seliadocs`.
- Version: code 3, `0.2.1-beta.1`.
- SDK range: minimum 29, target 37.

SHA-256:

```text
D97222479C7F407E30C7EF79A2D2BFA24E3BD71939C4967150D9FF8C19567C25  app-release.apk
5571F68A474B35D8921095FB826A360289FAA105BC9FF24498C2DB865EF99295  app-release.aab
```

`jarsigner` also reported an invalid certificate chain, a self-signed signer certificate, a missing timestamp, and unprotected POSIX or symlink attributes. The current JDK reported JarFile and JarInputStream consistency warnings for the AAB. Publication was outside this task, so those warnings remain recorded for the release controller.

## Instrumentation

Both temporary AVDs ran the complete suite from explicit serials.

| Target | AVD | Serial | Display | Final result |
| --- | --- | --- | --- | --- |
| Android 10, API 29 | `SeliaSheets_Task5_API29_20260826_1128` | `emulator-5610` | 1080 x 2280 at 480 dpi, 360 dp wide | `OK (147 tests)` in 126.504 s |
| Android 17, API 37 | `SeliaSheets_Task5_API37_20260826_1128` | `emulator-5612` | 2560 x 1600 at 320 dpi, 1280 dp wide | `OK (147 tests)` in 185.57 s |

The first API 29 run had two timing-only failures. Both tests passed targeted without a timeout change, and the final full run passed. The first API 37 run exposed three reproducible test-harness failures. The helper sent finger Eraser and Lasso events from 85 percent to 15 percent of the outer viewport. Those coordinates missed the centered paper on a wide display. The first fix used 60 percent to 40 percent. Review found that this traveled only 20 percent of the phone viewport. The final helper uses 65 percent to 35 percent only for InkCanvasView gestures. Both endpoints remain inside the centered paper, and the 30 percent travel exceeds the phone page-turn threshold. The separate page-turn threshold helpers did not change.

`PageNavigationFlowTest` passed all 13 tests on API 37 in 26.429 seconds and on API 29 in 18.372 seconds before the final full runs.

Review fix verification used fresh temporary AVDs. API 29 passed all 13 tests in 38.362 seconds. API 37 passed all 13 tests in 24.855 seconds after a headless System UI ANR dialog was dismissed. A targeted mutation replaced Eraser ownership with Pen ownership while keeping the 65 percent to 35 percent gesture. The test failed with `ERASER must not turn a page expected:<0> but was:<1>`. This proves that the helper crosses the page threshold and detects a missing ownership block.

The final suites cover one-finger and two-finger page turns, stylus ownership blocking, finger-ink cancellation, page bounds, action cancellation, smart arrow and ellipse conversion, and Undo restoring raw ink.

## Live UI matrix

| Scenario | Evidence |
| --- | --- |
| 360 dp portrait | Two notebook columns, the fixed six-action compact palette, More, Insert, and inset-safe top and bottom controls rendered. |
| 600 dp portrait | The medium library rendered two readable columns without horizontal overflow. |
| 840 dp landscape | The expanded editor rendered the Contents pane and the full toolbar. |
| 1280 dp landscape | The expanded editor rendered at the API 37 AVD's physical 2560 x 1600 display. |
| 200 percent font | The 360 dp library changed to one column. Notebook controls stayed reachable. |
| Dark theme | The 360 dp library rendered in system night mode. |
| Phone landscape | The 2280 x 1080 layout kept search, settings, notebook cards, and the create action reachable. |
| Insets | Automated geometry checks passed on both APIs. Live title bounds stayed below the status inset, and the compact palette stayed above the bottom system area. |

On the API 37 phone layout, a live one-finger swipe changed `Page 2 of 2` to `Page 1 of 2`, then back to `Page 2 of 2`. A stylus-source input produced persisted ink. The More menu exposed Add page, Search, Pencil, Draw with finger, Export PDF, and Settings. The Insert menu exposed Text object, Image, PDF, and Math.

## Phone Play screenshots

The accepted captures are 1080 x 2280 PNG files:

- [Library](../play-store/assets/phone-01-library.png): two notebook covers and the create action.
- [Editor](../play-store/assets/phone-02-editor.png): compact palette, page position, and stylus ink.
- [New notebook](../play-store/assets/phone-03-new-notebook.png): Grid preview, selected template, notebook name, and Create notebook action.
- [Settings](../play-store/assets/phone-04-settings.png): four collapsed settings groups.

Dated QA copies preserve the accepted state:

- [Library QA evidence](screenshots/2026-08-26-phone-library.png)
- [Editor QA evidence](screenshots/2026-08-26-phone-editor.png)
- [New notebook QA evidence](screenshots/2026-08-26-phone-new-notebook.png)
- [Settings QA evidence](screenshots/2026-08-26-phone-settings.png)

The first editor capture had a malformed status clock after repeated emulator resizing. It was rejected and replaced after the phone display was reset. Review recaptured the editor again with a normal status clock. The New notebook screen was scrolled so that the selected Grid notebook caption and description sit fully above the footer. All final images were inspected with their original pixels. Older undated QA screenshots were not changed.

## Huawei smoke and performance

Authorized target: an authorized Huawei Android 10 test device, API 29, 1080 x 2340 at 480 dpi.

The smoke used the disposable package `com.majkeylab.seliadocs.task5debug`. The existing release and `.debug` packages were not cleared, uninstalled, or used. The disposable package was removed after the run.

Focused flow: library, notebook creation, editor, second page creation, and six alternating page swipes.

`gfxinfo` summary:

- Total frames: 110.
- Janky frames: 46, or 41.82 percent.
- Frame percentiles: p50 9 ms, p90 61 ms, p95 93 ms, p99 450 ms.
- High input latency: 83.
- Slow UI thread: 22.
- Frame deadline missed: 23.

`meminfo` after the flow:

- Total PSS: 123,125 KB.
- Java heap: 23,844 KB.
- Native heap: 18,544 KB.
- Graphics: 27,720 KB.
- One Activity and 33 Views.

The capture used a debuggable build, first-run navigation, and ADB-generated swipes. Those conditions add compilation, transition, and input latency noise. A warm page-only repeat returned zero tracked frames, so it was discarded and is not evidence of smooth rendering.

Follow-up: capture a Perfetto system trace for warm page-only swipes with a profileable build on representative physical hardware. Inspect the frame timeline, the main thread, the render thread, and input dispatch before making a performance claim. No trace processor was available in this run, and no raw Perfetto trace was committed.

## Cleanup and publication state

The controller deleted both review AVDs and uninstalled the emulator plus API 29 and API 37 system
images. Final verification found no remaining AVD/image paths or emulator processes. Later release
hardening moved runtime acceptance to an authorized physical Android 10 device; see the
[release-hardening report](2026-08-26-seliasheets-release-hardening.md).

This report did not publish artifacts.
