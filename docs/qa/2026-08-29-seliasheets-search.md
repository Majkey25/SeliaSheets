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

Result: `BUILD SUCCESSFUL` in 3 minutes 36 seconds. Gradle ran 151 tasks, including 85 JVM tests, Android lint, the debug and instrumentation APKs, and signed release APK/AAB output.

The release APK verifies with APK Signature Scheme v2 and one signer. Signing certificate SHA-256:

`2741c19cc8754690dd3b91ffe51dad2e5565afaccd58c6b0667d49bfd716349c`

Release artifact SHA-256:

- APK: `546F110B5E15CAF39A046AFAE8E5D65B55BC7D3CC5DC9FB18E882593D35CD177`
- AAB: `11C6E92E9BF6BDBDF233B27DCEFD1160A5116DB0AFBF9FD9CCEDC6D583B29232`

## Physical acceptance

The isolated QA package `com.majkeylab.seliadocs.qa` preserved the Play-installed production app and its data.

- Repository suite: `OK (27 tests)` in 4.207 seconds.
- Covered Czech case-insensitive search, literal `%`, `_`, `\`, `*`, `?`, `[` and `]`, one result per page, the 100-result cap, deterministic page order, a late match in a 1,200-page notebook, stale fields on non-text/non-math elements, foreign chapter links, and missing queries.
- Nearby page ordering, persistence, PDF-source cleanup, chapter reindexing, element transforms, stroke replacement, and text bounds also passed in the same suite.
- Physical UI check: searching `quantumsearch` returned page 1 with the stored title `QuantumSearch`.

Hardware hover quality and stylus side-button mappings require compatible active-stylus hardware and remain separate follow-up QA.
