# SeliaDocs implementation roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver SeliaDocs in five ordered implementation plans, with a releasable and verified state after each plan.

**Architecture:** Foundation establishes identity, data safety, and page-scale performance. Organization and search add the notebook hierarchy and FTS. Intelligence adds derived local recognition and deterministic editing. Materials and study add PDF and review workflows. Publication runs only after the first four plans pass their exit gates.

**Tech Stack:** Kotlin, Jetpack Compose, Room, AndroidX Ink, ML Kit, Android PDF APIs, PDFBox Android, Gradle, GitHub Actions, GitHub Pages, Google Play Console

**Spec:** `docs/superpowers/specs/2026-08-24-seliadocs-smart-study-notebook-design.md`

## Global constraints

- Execute plans in the listed order.
- Finish and verify each plan before starting the next plan.
- Keep Room migrations linear: 1 to 2, 2 to 3, 3 to 4, and 4 to 5.
- Update `.seliadocs` backup compatibility in every plan that adds source entities.
- Run emulator QA only on verified SeliaDocs AVD serials.
- Keep the user-facing app usable without an account, cloud sync, or a recognition model.
- Do not merge, tag, release, upload to Play, or delete AVDs before the publication plan authorizes the action.

---

### Task 1: Execute the foundation plan

**Plan:** `docs/superpowers/plans/2026-08-24-seliadocs-foundation.md`

**Entry gate:** Current release branch and current dirty changes are inspected and preserved.

**Exit gate:** SeliaDocs identity is consistent, API 29 and API 37 suites pass, editable backup round-trips, failed restore preserves the library, a 500-page notebook loads selected-page content only, and histories and caches stay bounded.

- [ ] Complete every foundation checkbox.
- [ ] Review every foundation commit.
- [ ] Record the foundation acceptance document.

### Task 2: Execute the organization and search plan

**Plan:** `docs/superpowers/plans/2026-08-24-seliadocs-organization-search.md`

**Entry gate:** Foundation backup and selected-page loading are green.

**Exit gate:** Room schema 3 validates, chapters and flow pages survive migration and backup, Quick Note uses one Inbox, Type provides direct full-page writing without a default text box, finger and stylus modes work, editor and Settings adapt to phone and tablet, page turning works, FTS scopes work, and restore rebuilds search from source.

- [ ] Complete every organization and search checkbox.
- [ ] Review migrations 1 to 2 and 2 to 3.
- [ ] Record the organization and search acceptance document.

### Task 3: Execute the intelligence plan

**Plan:** `docs/superpowers/plans/2026-08-24-seliadocs-intelligence.md`

**Entry gate:** Search schema and source index maintenance are green.

**Exit gate:** Room schema 4 validates, OCR and installed handwriting packs create derived search rows, raw content stays unchanged, held shapes are reversible, math rejects ambiguity, tables remain editable, grouping persists, and current privacy documents match the merged manifest.

- [ ] Complete every intelligence checkbox.
- [ ] Review migration 3 to 4 and model-download privacy.
- [ ] Record the intelligence acceptance document.

### Task 4: Execute the materials and study plan

**Plan:** `docs/superpowers/plans/2026-08-24-seliadocs-materials-study.md`

**Entry gate:** Schema 4, search, OCR, math, tables, and current backup are green.

**Exit gate:** Room schema 5 validates, PDF import is atomic, visible tiles stay bounded, exported source text and vector annotations remain inspectable, references survive deletion rules, masks do not alter notes, flashcards schedule deterministically, and schema 5 backup restores on a fresh emulator.

- [ ] Complete every materials and study checkbox.
- [ ] Review migration 4 to 5 and PDFBox memory boundaries.
- [ ] Record the materials and study acceptance document.

### Task 5: Execute the publication plan

**Plan:** `docs/superpowers/plans/2026-08-24-seliadocs-publication.md`

**Entry gate:** The first four plans are complete, committed, locally green, and hostile-review findings are resolved.

**Exit gate:** PR is merged with green GitHub Actions, Pages and privacy URLs are live, GitHub prerelease assets match hashes, Google Play setup and available testing track are complete, external gates are reported exactly, and only verified SeliaDocs AVD and build resources are removed.

- [ ] Complete every publication checkbox.
- [ ] Verify remote artifacts independently from local files.
- [ ] Report final GitHub, Pages, Play, local signing, and cleanup state.
