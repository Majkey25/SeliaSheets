# SeliaSheets 0.5.1 OCR highlight acceptance

- Version: `0.5.1-beta.1` (`versionCode 10`)
- Package: `com.majkeylab.seliadocs`
- Minimum Android: 10 (API 29)

## Scope

Search results backed by image OCR now navigate to the page and highlight the exact matching OCR line. Normalized line regions are bounded, stored with the image element, included in editable backups, and rendered correctly when an image is resized or letterboxed. Older notebooks regenerate missing or unusable regions once from the local image asset.

## Verification

The clean signed gate ran the full JVM suite, Android lint, debug and instrumentation APK assembly, release APK assembly, and release AAB assembly. Gradle completed 151 tasks successfully. Huawei `BQLDU19927002646` then passed 69 affected instrumentation tests covering repository search, real ML Kit image OCR, region regeneration, Compose overlay rendering, backup import/export/validation, and Room migrations 1→2, 2→3, and 3→4.

`apksigner` verified APK Signature Scheme v2 with one signer. `jarsigner` returned `jar verified` for the AAB; its self-signed certificate, missing timestamp, POSIX attributes, and JarFile/JarInputStream consistency warnings are unchanged from earlier accepted releases. Final hashes, GitHub Actions, GitHub release assets, and Google Play Alpha status are recorded after publication from the merged release commit.
