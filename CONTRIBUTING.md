# Contributing

SeliaSheets is in beta. Open an issue before a large change so maintainers can agree on scope and interaction behavior.

## Development

1. Use JDK 17 and Android SDK platforms 29 and 37.
2. Create a focused branch from `main`.
3. Keep the interface English and preserve the offline/privacy boundary.
4. Run `./gradlew testDebugUnitTest lintDebug assembleDebug`.
5. Add emulator evidence for changes involving input, persistence, rendering, or Android-version behavior.

Do not commit signing keys, credentials, `local.properties`, build outputs, exported notebooks, or user data.
