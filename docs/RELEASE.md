# Release and signing

## Local verification

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease --console=plain
```

## Signing

Release signing is configured only when `PERKO_KEYSTORE_PROPERTIES` points to a local properties file with:

```properties
storeFile=C:/absolute/path/perko-upload.jks
storePassword=...
keyAlias=perko-upload
keyPassword=...
```

The keystore and properties file must remain outside the repository and be backed up securely. Losing the upload key can delay future Google Play updates.

Expected artifacts:

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

Verify checksums before attaching artifacts to a GitHub release or uploading the AAB to Google Play.
