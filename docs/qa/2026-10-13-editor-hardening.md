# Editor hardening checks

Release: `0.10.0-beta.2`, version code 25, package `com.majkeylab.seliadocs`.

The full debug phone suite passed. This beta records the remaining Play-runtime and physical active-pen limits rather than claiming complete verification.

## Changed behavior

- Finished pen and highlighter strokes remain visible until a transactional page snapshot acknowledges their saved revision. Revision rollover and stale snapshots are covered.
- Stored ink stays above text while the native text input remains usable. Inline drafts, selected page, and workspace recover after process recreation; successful insertions are not replayed.
- Moving pinch coordinates, gradual stylus selection, rotated image movement, and batched barrel-button handoff retain their intended coordinates and input.
- Corrected shapes erase by their painted outline, including rotated arrowheads and miter corners. Mixed ink and shapes share an atomic Undo/Redo step. Shapes are erased as whole objects, not partially cut.
- Contents moves pages by stable ID after saving drafts. Page deletion selects a neighbor and preserves image assets needed by Undo.
- Typed arithmetic completes after IME composition; overflow does not prevent saving the original text. Unsupported Word equations fail before notebook mutation. PDF export preserves image proportions.
- Backup validation rejects unknown paths and directory payloads. Validation, restore, staging creation, and orphan cleanup share the mutation gate; cleanup preserves unknown files and symlinks.
- OCR bitmap lifetime is cancellation-safe. Native PDF operations have an independent 30-second isolated-process deadline. Rejected or permission-denied service bindings are released.
- Appearance adds 13 choices, custom light/dark colors, interface fonts, text size, and corners. Compact layout uses combined interface/system text size; document text retains its stored/export font geometry.

## Local checks

- Full signed build passed in 6m24s: debug APK, test APK, minified release APK, AAB, and lint.
- 156 JVM tests across 29 suites: zero failures, errors, or skips.
- Lint: zero errors, 85 warnings, 5 hints. This is not a zero-warning or complete accessibility claim.
- [GitHub CI 38446938836](https://github.com/Majkey25/SeliaSheets/actions/runs/38446938836) passed the current `77dbae1` build in 11m00s. Both emulator jobs were skipped by the explicit manual opt-in condition, not counted as passing tests.
- [GitHub CI 38452607846](https://github.com/Majkey25/SeliaSheets/actions/runs/38452607846) passed final test-only commit `65b4d05` in 10m09s. Emulator jobs remained skipped.
- [Merged-main CI 38455715989](https://github.com/Majkey25/SeliaSheets/actions/runs/38455715989) passed `e0c2a91` in 8m41s. Emulator jobs remained skipped.
- Presentation tests: five passed. Rebuilding the renderer reproduced SHA-256 `e521a425d945696bfcdb2ada6502b59fe1dcf63103e0239a7c888a762d02c136`; no renderer or notice diff.
- Static policy site: five pages passed navigation, local-asset, semantic, and privacy checks. Small header text contrast: 4.85:1. Public privacy, terms, refunds, and cookies pages returned HTTP 200.

## Physical test history

Device: Huawei YAL-L21, Android 10 / API 29. Short exclusive USB windows were coordinated with the other projects. No emulator, shared ADB reset, unrelated package removal, or global service restart was used.

- The initial implementation reproduced 19 distinct regressions on the phone. A later focused run completed 274 scheduled cases: 271 passed, three platform-assumption skips, zero failures.
- An interrupted full run completed only 584 of 660 scheduled cases: 578 passed, five assumption skips, one failure. It is not a passing full run.
- A legacy/canonical shape fixture compared pixels at different screen positions. Switching styles at the same position retained exact pixel equality and visible-ink checks; all eight focused shape cases then passed.
- The following full run completed 660 cases in 581.924s: 647 passed, ten assumption skips, three failed. Those tests incorrectly bound the isolated PDF service directly in the main process after a new isolation guard. The guard remains in production.
- Replacement fixtures use the real private Binder. They check rejected requests, client FD ownership, server FD closure through pipe errors/EOF, and a healthy inspection through the original proxy afterward. Bounded `Os.poll` accounts for older Binder transport descriptors being released after the reply. Independent Astra review accepted these corrections and the production binding cleanup.
- The first real-IPC rerun completed 26 cases: 20 passed, five API-assumption skips, one fixture failure. `Os.fcntlInt` was unavailable on this API 29 phone. The redundant nonblocking flag was removed; bounded `POLLHUP` already proves the pipe writers are closed before the EOF assertion. No production code or assertion changed.
- The test-only rebuild and lint passed in 3m16s. Final focused PDF rerun completed 26 cases in 20.657s: 21 passed, five API-assumption skips, zero failures. These cases overlap the full suite.
- Final full-suite rerun completed 661 scheduled cases in 591.634s: 651 passed, ten expected platform/hardware/opt-in assumption skips, zero failures or errors. The focused PDF run overlaps this suite and is not added to its count.
- Protected minified Play release installation and native-window ink smoke: pending normal Play Store availability.
- At 19:43 UTC, opening `market://details?id=com.majkeylab.seliadocs` in the phone's Play Store offered Open, not Update. Package inspection confirmed code 24 / `0.10.0-beta.1`, installed by `com.android.vending`. The phone was returned to Home and released to the next project; code 25 runtime remains unverified.
- Installed stable code 24 has Play app-signing certificate SHA-256 `3f774a1787e1daeef4b266297f5552f57fc181bc20281618e9b52a6719241130`. The locally signed APK uses the separate registered upload key; replacing the installed app with it would require uninstalling. No uninstall or data clear was attempted. Play-generated code 25 is needed for the preserving-data release smoke.
- Live Console confirms automatic installer protection is enabled. Installed Play code 24 includes `com.pairip.application.Application`, its license activity, and `com.android.vending.CHECK_LICENSE`. Protected APK sideloading is not a substitute for the normal Play Store update. No installer spoofing, protection opt-out, account switch, GMS change, or stable data wipe was used.
- Final read-only GPT-6.1 Sol xhigh review found no confirmed pen-pipeline blocker. It checked zoom/pan coordinates, axis preservation, detach-time commit, late-handoff deduplication, and revision/save/Undo handling. It identified an API 29 lifecycle fixture that created a new canvas instead of reattaching the original. The reviewer ran no builds or device tests.
- A test-only follow-up removed that replacement, asserted actual attachment/detachment, and recycled the injected events. The rebuilt test APK and lint passed in 2m19s. The same-instance reattachment test and three nearby resume/detach/commit cases passed on the Huawei in 6.320s, without skips. These cases overlap the earlier full suite; production APK/AAB bytes remain unchanged.
- The entire updated `StylusRoutingTest` class then passed 41 cases in 46.794s on the same phone, with no failures, errors, or skips. This includes the corrected reattachment test and overlaps the four-case run and earlier full suite; it is not added to the 651 count. The phone was returned to Home and released at 19:54 UTC with no pending instrumentation.

## Artifact checks

Both signed artifacts use the registered Play upload certificate SHA-256 `2741c19cc8754690dd3b91ffe51dad2e5565afaccd58c6b0667d49bfd716349c`. Exactly one RSA-4096 APK signer was verified. APK version/package/minimum/target were checked: code 25, `0.10.0-beta.2`, API 29 / 37, not debuggable.

| Artifact | SHA-256 |
| --- | --- |
| Debug APK | `b1b777b8a023ac0f0a22954338c999a16fefae5541d172f6b859607f78e5f725` |
| Full-suite test APK | `a5d81725184c70fb9f33e15dfc8b6810308ba5fb30cd3c6c96f2ad31d0736c4b` |
| Lifecycle follow-up test APK | `683f4fd38979763a99b28b3db3cb647aea6a31c2fed13adf998907b6249df0d1` |
| Signed APK | `43d16e579392451cce513f760e8f5110f2edc1a95f65b45c6847fcf047b4c11f` |
| Signed AAB | `05491dc08641389f31954c30d38fcc6442c16137b76bd4107f0b9cb0d2e532aa` |

APK signature and 16 KiB ZIP alignment passed. Jarsigner verified the AAB; keytool independently read the matching signer. JDK 21 reported self-signed/no-timestamp warnings and a JarInputStream manifest-order warning for AGP bundle packaging. Archives were not repacked.

The APK and AAB each contain 12 arm64/x86_64 libraries with 16 KiB LOAD alignment and offset/address congruence. A strict RELRO-end modulo diagnostic initially rejected eight libraries; that condition alone is not an Android linker rejection. Header/section review found no live writable data in the rounded protected range. This is static evidence, not a 16 KiB runtime test.

Compiled production source tree: `a6486216077b85e9798cd4e0be4d0859d61fffa5` (`app/src/main`), with app build-script blob `62671b943649cddbda501d238036aac69eee995b`. Both are identical at `77dbae1`, subsequent test-only commit `65b4d05`, and merged main `e0c2a91`. Signed APK/AAB bytes did not change after that test correction. AGP reports `NO_VALID_GIT_FOUND` for the managed worktree; no embedded revision claim is made.

## Delivery

[PR 33](https://github.com/Majkey25/SeliaSheets/pull/33) merged normally after its checks passed. Release tag `v0.10.0-beta.2` points to merged commit `e0c2a9194cfc298ef1d8652f8ff7e2b58c2dde0e`.

The [GitHub prerelease](https://github.com/Majkey25/SeliaSheets/releases/tag/v0.10.0-beta.2) was published at 19:37:36 UTC on October 13, 2026. APK, AAB, and checksum-file upload states and GitHub SHA-256 digests match the local files. The APK retains the existing GitHub upload-key signer; it cannot update a Google-signed installation without uninstalling. Stay on the same distribution channel to preserve local data.

Google Play accepted code 25 into the existing closed Alpha track. The saved English release notes were read back, and the publishing overview confirmed the submitted change with checks in progress at 19:43 UTC. By 19:50 UTC the quick checks had completed and the change was under review. Managed publishing remains off; no production release was requested or enabled. Review completion and tester availability are not claimed. The only artifact warning was missing native debug symbols; ReTrace was attached, and the supported-device comparison showed no lost devices. Native crash symbolication remains limited for the bundled libraries.

## Limits

Injected MotionEvents are not physical active-stylus certification. Pressure, tilt, hover, palm rejection, hardware buttons/erasers, and vendor handwriting keyboards need a compatible tablet and active pen. API 35 native PDF search/selection and Android 14+ handwriting could not run on this API 29 phone. Emulator-only and explicitly opt-in checks are not claimed as passed.

No measured long-page latency or repeated-image-decoding improvement is claimed. Native PDF cancellation is bounded by the remaining 30-second deadline, and a timeout disconnects concurrent renderer operations. Revision comparison cannot order arbitrary forward jumps of 2^62 or more. Raw-ink erasure retains the existing centerline/radius contract.

PPTX becomes static PDF-backed annotatable pages; supported DOCX conversion does not retain full Word layout. This beta is not complete Flexcil parity, a PDF source-text/redaction editor, or a professional graphics suite. See [import limits](../IMPORT_FORMATS.md) and [scope](../../README.md#scope).

Primary references: [Android service binding contract](https://developer.android.com/reference/android/content/Context#bindService(android.content.Intent,%20android.content.ServiceConnection,%20int)), [older Binder reply/FD cleanup](https://android.googlesource.com/platform/frameworks/native/+/26b98f4271/libs/binder/IPCThreadState.cpp), [Android page sizes](https://developer.android.com/guide/practices/page-sizes), and [AOSP RELRO protection](https://android.googlesource.com/platform/bionic/+/android16-release/linker/linker_phdr.cpp).
