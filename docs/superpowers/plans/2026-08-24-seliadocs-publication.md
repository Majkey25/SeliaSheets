# SeliaDocs publication implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify, merge, sign, release, deploy, prepare Google Play, and clean only SeliaDocs build and emulator resources.

**Architecture:** Publication consumes completed source and tests. Local signing material stays outside Git. GitHub Actions independently verifies the release branch, GitHub Pages hosts the privacy policy, and Google Play receives the signed AAB through its dashboard. Cleanup starts only after remote artifacts and live pages match local hashes and content.

**Tech Stack:** Gradle, Android SDK 37 build tools, `apksigner`, `jarsigner`, Git, GitHub CLI, GitHub Actions, GitHub Pages, Google Play Console, emulator-only ADB QA

**Spec:** `docs/superpowers/specs/2026-08-24-seliadocs-smart-study-notebook-design.md`

## Global constraints

- Public name and repository: `SeliaDocs` and `Majkey25/SeliaDocs`.
- Android application ID: `com.majkeylab.seliadocs`.
- Release version: `0.1.0-beta.1`, version code 1.
- Android support: API 29 through API 37.
- UI and store listing language: English.
- Never print signing passwords, tokens, or private keys.
- Never install, uninstall, clear, test, or clean a physical Android device.
- Do not claim Google Play production availability while a live account gate remains.
- Preserve source, Git history, release files, screenshots, Play assets, and signing material.

---

### Task 1: Run hostile pre-release review

**Files:**
- Review: all source and tests changed since `main`
- Review: `README.md`, `PRIVACY.md`, `CHANGELOG.md`, Play documents, Pages site, and workflows
- Modify: only files required by confirmed findings

**Interfaces:**
- Consumes: complete implementation branch.
- Produces: a reviewed diff with no unresolved correctness, data-loss, privacy, package, or release blocker.

- [ ] **Step 1: Verify repository and branch state**

```powershell
git status --short --branch
git remote -v
git log --oneline --decorate --graph --max-count=30
git diff --check main...HEAD
git diff --stat main...HEAD
```

Expected: remote is `https://github.com/Majkey25/SeliaDocs.git`; the feature branch contains only intentional changes.

- [ ] **Step 2: Scan public identity and permissions**

```powershell
git grep -n -I -E 'Péřko|Perko|SheetNotes|cz\.majkey\.perko|com\.majkeylab\.sheetnotes|Majkey25/(Perko|SheetNotes)'
```

Expected: no stale public identity. Inspect the merged manifest and match every permission to privacy and Play data safety documents.

- [ ] **Step 3: Review data-loss boundaries**

Inspect backup staging, restore transactions, page and chapter deletion, asset reference cleanup, schema migrations, PDF import, and search rebuild. Fix every confirmed unsafe path before continuing.

- [ ] **Step 4: Review performance boundaries**

Verify selected-page content loading, bounded history, bounded thumbnail and PDF tile caches, canceled stale recognition, streamed backup, and released bitmaps.

- [ ] **Step 5: Review UI and accessibility**

Inspect phone and tablet screenshots for hierarchy, clipped content, tool crowding, touch targets, contrast, selected states, reduced motion, and screen-reader descriptions.

- [ ] **Step 6: Commit only confirmed review fixes**

Use one Conventional Commit per independent fix. Do not combine documentation cleanup with behavior fixes.

### Task 2: Run complete local verification

**Files:**
- Update: `docs/qa/` with final evidence
- Update: Play screenshots when visible UI changed

**Interfaces:**
- Consumes: reviewed source.
- Produces: current unit, lint, build, emulator, performance, backup, PDF, and live release evidence.

- [ ] **Step 1: Run clean unit, lint, debug, and unsigned bundle checks**

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:bundleRelease --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Confirm emulator identity before each test run**

```powershell
adb -s emulator-5590 emu avd name
adb -s emulator-5594 emu avd name
```

Expected: the configured SeliaDocs tablet API 37 and phone API 29 AVD names. Stop if either serial names another AVD.

- [ ] **Step 3: Run full instrumentation on API 37**

```powershell
$env:ANDROID_SERIAL='emulator-5590'
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
$code=$LASTEXITCODE
Remove-Item Env:ANDROID_SERIAL
exit $code
```

- [ ] **Step 4: Run full instrumentation on API 29**

Use the same command with `emulator-5594`. Never omit `ANDROID_SERIAL` while a physical device appears in `adb devices`.

- [ ] **Step 5: Run live acceptance workflows**

Verify:

1. Quick Note and classic text/checklist note;
2. subject notebook, chapters, 500 pages, page titles, bookmarks, reorder, and Back;
3. stylus ink, eraser, lasso, shape hold, math, graph, table, undo, and redo;
4. image OCR, handwriting search, model missing, model installed, and offline behavior;
5. PDF import, vector annotation export, reference link, mask, and flashcard review;
6. complete backup, fresh-install restore, corrupt backup rejection, and existing-data preservation;
7. reduced motion, large controls, phone, tablet, and rotation.

- [ ] **Step 6: Record evidence without invented numbers**

Store exact commands, test counts, device API levels, build duration, file sizes, hashes, and observed memory snapshots.

### Task 3: Build and verify signed release artifacts

**Files:**
- Generate ignored: `dist/SeliaDocs-v0.1.0-beta.1.apk`
- Generate ignored: `dist/SeliaDocs-v0.1.0-beta.1.aab`
- Preserve: `dist/seliadocs-upload-certificate.pem`
- Update ignored: `dist/SHA256SUMS.txt`

**Interfaces:**
- Consumes: signing properties outside the repository.
- Produces: signed APK and AAB with one upload certificate and verified checksums.

- [ ] **Step 1: Verify signing files exist without printing their contents**

```powershell
Test-Path '<external-properties-file>'
```

Expected: `True`.

- [ ] **Step 2: Run the signed clean build**

```powershell
$env:SELIADOCS_KEYSTORE_PROPERTIES='<external-properties-file>'
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --console=plain
$code=$LASTEXITCODE
Remove-Item Env:SELIADOCS_KEYSTORE_PROPERTIES
exit $code
```

- [ ] **Step 3: Verify APK identity and signature**

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\aapt.exe" dump badging app\build\outputs\apk\release\app-release.apk
```

Verify one signer, application ID, label, version, min SDK, target SDK, and intended permissions.

- [ ] **Step 4: Verify AAB signature**

```powershell
jarsigner -verify -verbose -certs app\build\outputs\bundle\release\app-release.aab
```

Expected: `jar verified` and the same certificate subject.

- [ ] **Step 5: Copy exact artifacts and calculate SHA-256**

Copy only the just-verified files. Hash the APK, AAB, and public certificate. Write uppercase hashes and exact filenames to `SHA256SUMS.txt`, then recalculate once to compare.

- [ ] **Step 6: Install and cold-launch the exact signed APK on API 29 and API 37**

Uninstall only `com.majkeylab.seliadocs` on those emulator serials. Install the `dist` APK, launch `com.majkeylab.seliadocs/.MainActivity`, and verify version information with `dumpsys package`.

### Task 4: Push, validate PR, and merge

**Files:**
- Remote: `Majkey25/SeliaDocs`
- PR: current release PR targeting `main`

**Interfaces:**
- Consumes: committed release branch and local green evidence.
- Produces: merged `main` with green GitHub Actions.

- [ ] **Step 1: Push the current feature branch**

```powershell
git push -u origin feat/release/24-08-2026
```

- [ ] **Step 2: Update the existing PR title and body**

Use title `Release SeliaDocs 0.1.0 beta`. Summarize real features, privacy, API support, and test evidence. Do not claim Google Play production status.

- [ ] **Step 3: Wait for every GitHub Actions check**

```powershell
gh pr checks 1 --watch --interval 10
```

If a check fails, inspect the GitHub Actions log, fix its root cause, rerun local relevant checks, push, and wait again.

- [ ] **Step 4: Review the remote PR diff**

```powershell
gh pr diff 1 --stat
gh pr view 1 --json mergeable,reviewDecision,statusCheckRollup,url
```

- [ ] **Step 5: Merge only after green checks and clean review**

Use a merge commit so feature history remains readable. Delete the remote feature branch after merge only if GitHub confirms the merge.

- [ ] **Step 6: Update local main without discarding work**

Fetch and fast-forward local `main`. Never use `git reset --hard`.

### Task 5: Deploy and verify GitHub Pages

**Files:**
- Remote workflow: `.github/workflows/pages.yml`
- Site: `site/`

**Interfaces:**
- Consumes: merged `main`.
- Produces: live landing page and privacy policy under `https://majkey25.github.io/SeliaDocs/`.

- [ ] **Step 1: Wait for Pages workflow**

```powershell
gh run list --workflow pages.yml --limit 5
```

Wait for the run tied to the merged commit.

- [ ] **Step 2: Verify live HTTP responses**

Open:

```text
https://majkey25.github.io/SeliaDocs/
https://majkey25.github.io/SeliaDocs/privacy/
```

Expected: status 200, SeliaDocs copy, current privacy disclosure, source link, and contact email.

- [ ] **Step 3: Test desktop and phone layouts in a real browser**

Inspect DOM, console, network failures, links, screenshots, and horizontal overflow.

- [ ] **Step 4: Record the live privacy URL for Play Console**

Do not use a README blob URL as the Play privacy URL.

### Task 6: Create and verify GitHub prerelease

**Files:**
- Remote release tag: `v0.1.0-beta.1`
- Assets: APK, AAB, `SHA256SUMS.txt`

**Interfaces:**
- Consumes: merged and verified commit plus signed local artifacts.
- Produces: one public GitHub prerelease with downloadable verified assets.

- [ ] **Step 1: Verify version and tag do not conflict**

```powershell
gh release view v0.1.0-beta.1 --repo Majkey25/SeliaDocs
```

Expected before creation: release not found.

- [ ] **Step 2: Create the prerelease from merged main**

Use release title `SeliaDocs 0.1.0 beta 1`. Release notes cover notebook hierarchy, writing, search, recognition, backup, PDF, study tools, privacy, Android support, and beta limits.

- [ ] **Step 3: Upload exact local assets**

Attach `SeliaDocs-v0.1.0-beta.1.apk`, `SeliaDocs-v0.1.0-beta.1.aab`, and `SHA256SUMS.txt`.

- [ ] **Step 4: Download release assets to a new temporary directory**

Hash downloaded APK and AAB. Compare them with the published checksum file and local verified hashes.

- [ ] **Step 5: Verify public release URLs and prerelease state**

### Task 7: Complete Google Play setup and upload

**Files:**
- Local listing source: `docs/play-store/`
- Upload: signed AAB and store graphics
- External: Google Play Console

**Interfaces:**
- Consumes: live privacy URL, signed AAB, app assets, listing copy, and account state.
- Produces: complete Play app setup and at least one valid testing-track release unless an account gate blocks it.

- [ ] **Step 1: Open the signed-in Google Play Console**

Select an existing SeliaDocs app only if its package is `com.majkeylab.seliadocs`. Otherwise create a new free Productivity app named SeliaDocs with English United States as the default language.

- [ ] **Step 2: Complete main store listing**

Enter the short and full descriptions from `STORE_LISTING.md`. Upload the 512 by 512 icon, 1024 by 500 feature graphic, at least two phone screenshots, and current tablet screenshots.

- [ ] **Step 3: Complete app content declarations**

Set:

```text
Ads: No
App access: No restrictions
Category: Productivity
Target audience: 13 and older
News: No
Health: No
Financial features: No
Government: No
```

Answer content rating from actual app behavior.

- [ ] **Step 4: Complete Data safety from current merged manifest and SDKs**

Do not copy an old answer blindly. Confirm network permissions, model delivery, user-selected exports, local note storage, OCR, recognition, and any SDK-declared collection. State no collection or sharing only when the final dependency and runtime evidence supports it.

- [ ] **Step 5: Add contact and privacy information**

```text
Email: majkeylab@gmail.com
Website: https://github.com/Majkey25/SeliaDocs
Privacy: https://majkey25.github.io/SeliaDocs/privacy/
```

- [ ] **Step 6: Enroll in Play App Signing and upload the signed AAB**

Use version code 1 and release name `0.1.0-beta.1`. Add concise release notes.

- [ ] **Step 7: Start with Internal testing**

Resolve every dashboard error. If the account requires a closed test or production-access period, configure the available testing track and report the exact requirement, tester count, duration, and dashboard URL. Do not claim production release.

- [ ] **Step 8: Verify Play processing result**

Confirm package, version code, target SDK, supported devices, warnings, and release status.

### Task 8: Clean only verified SeliaDocs resources

**Files:**
- Remove regenerable: project build directories and temporary research or QA files
- Preserve: repository, `dist`, store assets, screenshots, release secrets, and chat workspace

**Interfaces:**
- Consumes: verified GitHub release, live Pages, and completed Play upload or recorded external gate.
- Produces: stopped SeliaDocs services and reclaimed AVD and build storage without touching unrelated resources.

- [ ] **Step 1: Verify exact emulator targets**

Resolve `emulator-5590` and `emulator-5594` to their AVD names. Stop if either is not a SeliaDocs AVD. List physical devices and mark them out of scope.

- [ ] **Step 2: Stop only SeliaDocs emulators**

```powershell
adb -s emulator-5590 emu kill
adb -s emulator-5594 emu kill
```

- [ ] **Step 3: Delete only the two dedicated SeliaDocs AVDs**

Use `avdmanager delete avd -n <verified-name>`. Preserve shared Android system images.

- [ ] **Step 4: Stop only the SeliaDocs local site server**

Verify the process owning port 8877 belongs to the SeliaDocs site command before stopping it. Preserve the unrelated service on port 8765.

- [ ] **Step 5: Verify exact local cleanup paths**

Resolve every target under the repository or this task's `work` directory before deletion. Remove only:

```text
<repo>/.gradle
<repo>/.kotlin
<repo>/build
<repo>/app/build
<repo>/.repo_graph
<repo>/.reference/tmp
<task>/work/perko-visual-qa
<task>/work/perko-editor-qa
<task>/work/perko-api29-qa
<task>/work/tmp/pdfs
```

Preserve the external signing-material directory until it is moved to the user's chosen permanent secure location.

- [ ] **Step 6: Verify preserved deliverables**

Check that source, `.git`, `dist`, Play assets, QA screenshots, privacy site, and signing material remain.

- [ ] **Step 7: Report exact final remote and local state**

Include GitHub repository, PR, release, Pages, privacy URL, Play track, external gates, artifact hashes, local source path, signing-material path, and what cleanup removed.
