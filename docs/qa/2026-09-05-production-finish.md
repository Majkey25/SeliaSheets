# SeliaSheets 0.6 beta verification

Candidate: version code 13, `0.6.0-beta.1`, package `com.majkeylab.seliadocs`.

## Physical Android 10

Huawei USB test runs on September 5 used an exclusive device window. No app data was cleared.

- The broad instrumentation run at 18:52 reported `OK (311 tests)`, with one external-stylus assumption skip.
- The separate viewport/input run at 18:57 reported `OK (40 tests)`, with three API/hardware assumption skips.
- These runs cover inline text persistence and recreation, backup validation and migrations, image transforms and OCR history, PDF retry, ink selection, pressure-event routing, palm/eraser transitions, and zoomed coordinates.
- Those results precede the subsequent same-text input callback fix and ink-duplicate test synchronization change. Their rerun is pending.

Injected Android `MotionEvent` tests verify application routing. They do not verify a physical pen digitizer. The attached phone has no detected active stylus, so hardware pressure, tilt, hover, barrel buttons, and palm rejection remain unverified.

## Build and CI

The local gate passed again after the same-text callback and ink-duplicate test fixes: JVM tests, Android lint, debug/test APKs, and externally signed release APK/AAB builds, 150 tasks in 3m 8s. The JVM report contains 97 tests. Lint reports no errors, 61 warnings, and two hints.

The candidate APK passes APK v2 signature verification with one expected RSA-4096 signer and 16 KB ZIP alignment. The AAB reports `jar verified`, with self-signed/no-timestamp and JAR stream-order warnings. These are candidate checks; final release hashes must be taken from the merged commit's signed build.

The live Play Console still serves code 12 in closed `alpha`. Its saved data-safety preview declares Diagnostics, App interactions, and Device or other IDs; no sharing, encrypted transit, no publisher deletion request, and the expected GitHub Pages privacy URL. No declaration was changed during this check.

[CI run 33978858120](https://github.com/Majkey25/SeliaSheets/actions/runs/33978858120) passed the build job but failed runtime checks:

- Android 10 completed the broad suite with three failures. Two rejected-paste tests exposed same-text focus/selection callbacks being forwarded as draft changes. The ink-copy test inspected Undo before the combined state finished updating.
- Android 17 could not resolve the test activity and then reported `INSTRUMENTATION_ABORTED: System has crashed` after 11 of 311 tests. The native emulator pressure/pinch stage did not run.

The release remains gated on corrected runtime checks, signed release smoke testing, artifact verification, and Play acceptance. A green local build is not release acceptance.
