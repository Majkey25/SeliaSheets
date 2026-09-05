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

Run these checks against the outputs from the signed clean build:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\aapt.exe" dump badging app\build\outputs\apk\release\app-release.apk
jarsigner -verify -verbose -certs app\build\outputs\bundle\release\app-release.aab
Get-FileHash -Algorithm SHA256 app\build\outputs\apk\release\app-release.apk, app\build\outputs\bundle\release\app-release.aab
```

Verify the package, version code, version name, target SDK, one expected signer, and SHA-256 hashes. Create beta tags from the merged `main` commit and publish them as GitHub prereleases. Attach `app-release.apk`, `app-release.aab`, and `SHA256SUMS`.
