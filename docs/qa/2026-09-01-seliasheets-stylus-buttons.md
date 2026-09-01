# SeliaSheets 0.5.2 stylus-button acceptance

- Version: `0.5.2-beta.1` (`versionCode 11`)
- Package: `com.majkeylab.seliadocs`
- Minimum Android: 10 (API 29)

## Scope

Android's primary and secondary stylus barrel-button states now start a temporary erase gesture. The selected pen, pencil, highlighter, or lasso tool does not change. Inverted hardware eraser tips retain their existing behavior.

## Verification

The regression test failed before the production change and passed afterward on Huawei `BQLDU19927002646`. The combined `StylusRoutingTest`, `PageViewportFlowTest`, and `PageNavigationFlowTest` run passed 44 tests. Three external active-pen tests skipped because this Huawei reports `ExternalStylusConnected: false`.

The full local gate ran the JVM suite, Android lint, debug APK assembly, and instrumentation APK assembly successfully. Physical barrel-button reporting remains unverified until a compatible active-stylus tablet is connected.
