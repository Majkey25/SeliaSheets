# Release and signing

## Local verification

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease --console=plain
```

## Signing

Release signing is configured when `SELIA_SHEETS_KEYSTORE_PROPERTIES` points to a local properties file with:

```properties
storeFile=C:/absolute/path/selia-sheets-upload.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The keystore and properties file must remain outside the repository and be backed up securely. Losing the upload key can delay future Google Play updates.

Expected artifacts:

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

You can also pass the file path as `-PseliaSheetsKeystoreProperties=C:/path/keystore.properties`. Verify checksums before attaching artifacts to a GitHub release or uploading the AAB to Google Play. The legacy `SELIADOCS_KEYSTORE_PROPERTIES` variable remains supported for existing local setups.
