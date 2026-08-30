# SeliaSheets 0.4.1 launcher icon acceptance

## Scope

- Version: `0.4.1-beta.1` (`versionCode 7`)
- Package: `com.majkeylab.seliadocs`
- Physical target: Huawei YAL-L21, Android 10 / API 29, serial `BQLDU19927002646`
- Change: adaptive and monochrome foreground inset reduced from 28% to 24%

The artwork, colors, transparent foreground, beige adaptive background, and legacy density fallbacks are unchanged.

## TDD evidence

The icon contract was changed to require a 24% foreground inset before the production resources changed. Huawei result: two expected failures reported `expected:<0.24> but was:<0.27999996>`.

After the two resource changes, `LauncherIconResourceTest` passed all four tests on Huawei in 3.328 seconds. The checks cover adaptive and monochrome inset parity, transparent foreground artwork, mask bounds, centered meaningful content, background separation, and every legacy density fallback.

The Huawei App info rendering showed the complete notebook and stylus centered inside the rounded-square launcher mask without clipping.

## Signed release gate

```powershell
.\gradlew.bat --no-daemon -PseliaSheetsKeystoreProperties=<external-properties-file> clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease --console=plain
```

Result: `BUILD SUCCESSFUL` in 3 minutes 10 seconds. Gradle ran 151 tasks, including 85 JVM tests, Android lint, debug and instrumentation APK assembly, and signed release APK/AAB output.

The release APK verifies with APK Signature Scheme v2 and one signer. Signing certificate SHA-256:

`2741c19cc8754690dd3b91ffe51dad2e5565afaccd58c6b0667d49bfd716349c`

Release artifact SHA-256:

- APK: `7764491034A773C7EFA0932CF7F3754CB612BA8F870A3702DC8AB4ED15DF9795`
- AAB: `A1AEA7358DFC7CD6C78B99C3F6D769C3E6AEB34EBD6F8DD73039788FA8CE727C`
