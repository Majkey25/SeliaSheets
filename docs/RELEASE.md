# Release and signing

## Local verification

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease bundleRelease --console=plain
```

## Signing

Release signing is configured when `SELIA_SHEETS_KEYSTORE_PROPERTIES` points to a local properties file with:

```properties
storeFile=C:/absolute/path/selia-sheets-upload.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The keystore and properties file must remain outside the repository and be backed up securely. Losing the upload key can delay future Google Play updates. If a signing-properties path is supplied but missing, Gradle fails during configuration. Without a supplied path, Gradle creates unsigned release outputs that must not be published.

Expected artifacts:

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

You can also pass the file path as `-PseliaSheetsKeystoreProperties=C:/path/keystore.properties`. The legacy `SELIADOCS_KEYSTORE_PROPERTIES` variable remains supported for existing local setups.

## Artifact verification

Run these checks against the outputs from the signed build. These commands use installed Build Tools 36.0.0; the target Android platform is separate.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt.exe" dump badging app\build\outputs\apk\release\app-release.apk
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\zipalign.exe" -c -P 16 4 app\build\outputs\apk\release\app-release.apk
jarsigner -verify -verbose -certs app\build\outputs\bundle\release\app-release.aab
Get-FileHash -Algorithm SHA256 app\build\outputs\apk\release\app-release.apk, app\build\outputs\bundle\release\app-release.aab
```

Verify the package, version code, version name, target SDK, one expected signer, and SHA-256 hashes. Create beta tags from the merged `main` commit and publish them as GitHub prereleases.

## Distribution channels

Release builds default to `-PdistributionChannel=play`, which removes the external donation button and action. Upload only this channel's signed `app-release.aab` to Google Play. A GitHub-channel bundle is rejected by the build.

Build the signed GitHub installation APK separately with `:app:assembleRelease -PdistributionChannel=github` and the same signing properties. Attach that APK and its checksum to GitHub Releases so the optional support button remains available there. The APK output path is shared, so copy and hash each channel's output before the next build.

Debug builds retain donations by default. Pass `-PdistributionChannel=play` to debug builds and instrumentation tests when verifying the Play settings screen. `SettingsFlowTest.appDetailsShowsVersionAndSupport` checks the selected channel while keeping version and license access covered.
