# SeliaSheets 0.4.0 search acceptance

## Scope

- Version: `0.4.0-beta.1` (`versionCode 6`)
- Package: `com.majkeylab.seliadocs`
- Physical target: Huawei YAL-L21, Android 10 / API 29, serial `BQLDU19927002646`
- Search sources: page titles, same-notebook chapter titles, full-page text, movable text, math expressions, and math results

## Source and release gate

```powershell
.\gradlew.bat --no-daemon -PseliaSheetsKeystoreProperties=<external-properties-file> clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease --console=plain
```

Result: `BUILD SUCCESSFUL` in 2 minutes 31 seconds. Gradle ran 151 tasks, including 85 JVM tests, Android lint, the debug and instrumentation APKs, and signed release APK/AAB output.

The release APK verifies with APK Signature Scheme v2 and one signer. Signing certificate SHA-256:

`2741c19cc8754690dd3b91ffe51dad2e5565afaccd58c6b0667d49bfd716349c`

Release artifact SHA-256:

- APK: `24D97AC4D8C2FC23A212043F6129203517804EFC937A90987CD523CE776F1653`
- AAB: `7436974A34AD4A6CFA674771BA1E924BD4218D9D6DE3C26A7919FFF242469051`

## Physical acceptance

The isolated QA package `com.majkeylab.seliadocs.qa` preserved the Play-installed production app and its data.

- Repository suite: `OK (27 tests)` in 5.37 seconds.
- Focused editor search flow: `OK (2 tests)` for pending-draft flush and dismiss-before-debounce cancellation.
- Covered Czech case-insensitive search, literal `%`, `_`, `\`, `*`, `?`, `[` and `]`, one result per page, the 100-result cap, deterministic page order, a late match in a 1,200-page notebook, stale fields on non-text/non-math elements, foreign chapter links, and missing queries.
- Nearby page ordering, persistence, PDF-source cleanup, chapter reindexing, element transforms, stroke replacement, and text bounds also passed in the same suite.
- Physical UI check: searching `quantumsearch` returned page 1 with the stored title `QuantumSearch`.

Hardware hover quality and stylus side-button mappings require compatible active-stylus hardware and remain separate follow-up QA.
