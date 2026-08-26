# SeliaSheets 0.2.1 release hardening

Date: August 26, 2026

## Result

The release-hardening and mobile Material changes passed the signed clean build and physical
Android 10 acceptance. The test app used the isolated package `com.majkeylab.seliadocs.qa`; the
existing release and debug app data were not cleared or replaced.

## Build and static checks

The signed clean gate ran JVM tests, Android lint, debug and instrumentation APK assembly, release
APK assembly, and release AAB assembly.

- Result: `BUILD SUCCESSFUL` in 1 minute 42 seconds, 151 tasks.
- JVM tests: 59 passed, zero failures or errors.
- Lint: zero errors.
- Release identity: `com.majkeylab.seliadocs`, version code 3, `0.2.1-beta.1`.
- SDK range: minimum 29, target 37.

## Physical Android 10 acceptance

An authorized Huawei Android 10 test device ran the complete QA-package suite except the one test
that intentionally asserts the unsuffixed release package name.

- Result: `OK (163 tests)` in 115.32 seconds.
- The release-package identity test was verified separately from the signed APK with `aapt`.
- Focused Back, pending text save, Activity recreation, and reopen test: `OK (1 test)`.
- Focused rerun of the four corrected lifecycle and variant-aware harness tests: `OK (4 tests)`.
- Final editor close, session reset, and deleted-page regression classes: `OK (21 tests)`.
- Focused PDF destination, cleanup, history, and decode-ceiling JVM tests: 15 passed.

Coverage includes backup export and replacement races, record limits, cancellation, large notebook
snapshots, PDF import/export failures, Undo/Redo rollback, stale text echoes, configuration changes,
stylus takeover, one- and two-finger page turns, pinch ownership, smart shapes, adaptive layouts,
200 percent font scaling, settings, and support links.

## Signed artifacts

```text
32725662EF0B2586D53244C763242A82E2F1D702897DE0558133AD4CC75EE5A1  app-release.apk
9DDE89469821955EFF05D0DFEA40D84D6C6F3492046401423B06F1A3BAF94A57  app-release.aab
```

- APK: Signature Scheme v2, one signer, certificate SHA-256
  `2741c19cc8754690dd3b91ffe51dad2e5565afaccd58c6b0667d49bfd716349c`.
- AAB: `jarsigner -verify` returned `jar verified`.
- Recorded AAB warnings: self-signed certificate, invalid certificate chain, no timestamp, unprotected
  POSIX or symlink attributes, and JDK JarFile/JarInputStream consistency warnings.

## Resource and device cleanup

- Both temporary AVDs were deleted.
- The Android emulator package and API 29/API 37 system images were uninstalled.
- Verification found no emulator processes or AVD/image paths.
- ADB showed only the authorized Huawei device.
- The QA app remains installed for manual progress checks; instrumentation packages were removed.

The existing production app, production data, and legacy debug app were not removed.

## Release state

This report verifies the artifacts. GitHub and Google Play publication are separate release-controller
steps and must use the AAB hash above.
