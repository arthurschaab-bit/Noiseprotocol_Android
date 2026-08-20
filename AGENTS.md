# AGENTS.md — Lärmprotokoll (Noiseprotocol_Android)

Instructions for every coding agent working in this repository (Claude Code, Codex,
Antigravity/Gemini). Read this file completely before touching any code. It is the single
source of truth for working rules; the *content* of the work is defined in `docs/`.

## 1. What this project is

Android app that documents noise events: records an audio clip with pre-roll when a level
threshold is exceeded, classifies the sound with YAMNet (TFLite), and stores everything as a
searchable log in Room. **Current initiative:** connect an external sound level meter
**PCE-323 via Bluetooth LE** to replace the uncalibrated microphone level with calibrated dBA
values. Later: alerting on connection loss (SMS + push), Google Drive sync.

## 2. Where the truth lives — read in this order

1. `README.md` — status overview, what works, what is open.
2. `docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md` — **the decided plan** (~1,230 lines).
   Sections 0 (inventory, findings B-1..B-11), 2 (device protocol), 4 (target architecture),
   11 (test strategy), 12 (milestones), 13 (open decisions).
3. `docs/PROMPT_UMSETZUNG.md`, `docs/PROMPT_M1.md` — concrete task briefs per milestone.
4. `docs/PROMPT_REVIEW.md` — the review checklist a separate reviewer session runs.

The plan is decided. Your job is to execute it, not to redesign it. If your task
contradicts the plan, follow the plan and report the contradiction. If you hit a decision
marked open in plan section 13, **do not decide — ask the owner.**

Docs are in German. Keep them in German. Code identifiers are English, UI strings are German.

## 3. Stack

Kotlin 2.2 · Jetpack Compose · Room 2.8 (KSP, exported schemas in `app/schemas/`) ·
Navigation-Compose · TFLite Task Audio (to be replaced, see B-11) · Robolectric for JVM tests.
AGP 9.2 · Gradle 9.4 · Java toolchain 21, `jvmTarget` 17 · compileSdk/targetSdk 36 ·
minSdk 29 (→ 31 in M1). Single Gradle module `app`, package `com.example.lrmprotokoll`.
No DI framework — a manual `AppContainer` (plan 4.2). No Hilt.

## 4. Build & test commands

```bash
./gradlew assembleDebug          # must pass after every change
./gradlew test                   # JVM unit tests incl. Room migration tests — must be green
./gradlew connectedAndroidTest   # only if a device/emulator is available
./gradlew installDebug
```

If `JAVA_HOME` is missing:
- Windows: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
- Linux/macOS: `export JAVA_HOME=/opt/android-studio/jbr` (or any JDK 21)

If no Android SDK is present (cloud sandboxes):
`sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"` and point
`local.properties` (`sdk.dir=...`) at it. `local.properties` is git-ignored — never commit it.

## 5. Working rules

- **Scope:** Work only the milestone / task you were given. No look-ahead into later
  milestones, no drive-by refactoring, no "while I'm here" cleanups.
- **Branches:** New branch from `main`, name `feature/m<N>-<short-description>` (or
  `fix/<short-description>`). **Never commit or push directly to `main`.**
- **Commits:** Small, one per completed sub-step. Commit messages in **German**.
- **Style:** Match the existing code — comment density, naming, formatting. English
  identifiers, German UI strings.
- **Room:** Table names, column names and `identityHash` must never change accidentally.
  Migration tests are the proof. `fallbackToDestructiveMigration()` is forbidden.
- **Do not touch:** `.idea/`, `manifest_error.txt`, `gradle/wrapper/*`, `app/schemas/*.json`
  contents (only rename per plan when a class moves), unless the task explicitly says so.
- **Crypto/BLE security code (M6):** implement exactly as the plan specifies; flag any
  deviation explicitly in the PR — the owner reviews these parts personally.

## 6. Verification — non-negotiable

- After every change `./gradlew assembleDebug` must pass. If it doesn't, the step is not done.
- New logic gets unit tests. `./gradlew test` must be green.
- **Never claim something works without having run it.** If you could not verify something
  (no device, no SDK, no hardware), write exactly that.
- Show command output in the PR, not a summary of it.

## 7. Definition of Done for any task

1. `assembleDebug` and `test` green — output shown.
2. Both existing Room migration tests still green.
3. Acceptance criteria of the task brief met, each one addressed.
4. Branch pushed, **Draft PR** against `main` opened. PR body contains:
   *what changed · what was verified (command + result) · what was deliberately left open ·
   any plan contradiction or open decision encountered.*
5. Short summary to the owner: done / not done / noticed.

## 8. Which agent does what (owner's convention)

- **Codex:** implementation of well-specified, hardware-free milestones (M1, B-11, later
  M4/M7/M7b), automatic PR review.
- **Antigravity:** UI-heavy work with emulator/visual feedback; second-opinion review using
  `docs/PROMPT_REVIEW.md` in a fresh session (different model than the implementer).
- **Claude Code:** GATT-dump interpretation after M0, M2 (BLE transport / state machine),
  open decisions (plan §13), tie-breaks when reviews disagree.

A reviewer never reviews its own implementation.
