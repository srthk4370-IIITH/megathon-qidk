# CHANGE.md — cross-branch change log

Purpose: announce any change on one branch that affects another branch (contracts, schemas, file paths, APIs, model files, board setup). Read this before starting work; append before pushing a change that others depend on.

**Branches**
| Branch | Area | Handover file |
|---|---|---|
| `Saarthak` | ML / NPU: models, export, native inference, prompts, taxonomy, eval | `handover_Saarthak.md` |
| (Android branch, name TBD) | Android app, including the dashboard | `handover_<branch>.md` |

Only two branches: **App** and **ML**. No separate web/dashboard branch.

**Entry format**
```
## YYYY-MM-DD HH:MM — <branch> — <short title>
- What changed:
- Who is affected (branches / PRD sections):
- Action needed by others:
- Commit:
```

---

## 2026-10-10 — Saarthak — Project docs baseline
- What changed: `PRD.md` v1.0 is the single source of truth. Shared contracts live in PRD Appendix A (`config/taxonomy.json`), B (`config/sop.json`), C (prompts), D (tool-call schemas + engine DTOs), E (SQL DDL).
- Who is affected: all branches.
- Action needed by others: any change to a field key, enum value, schema or DTO must be announced here **and** made in PRD.md in the same commit.
- Commit: 8ff065e

## 2026-10-10 — Saarthak — Decision: dashboard is app-only
- What changed: dashboard stays **inside the Android app** (PRD D-44 and §23 unchanged). No web dashboard, no Web branch. Branches are App + ML only.
- Who is affected: Android branch (owns the dashboard: recency list, FTS search, filters, stats, detail view).
- Action needed: none for ML; Android builds PRD §23 as specified.

## 2026-10-10 21:20 — Saarthak — PRD rev 1.1 (affects App)
- What changed (full list PRD §4.0 R1-01…R1-14):
  - **Search:** Room `@Fts4` instead of FTS5/bundled SQLite (PRD §21.4 note).
  - **Time:** if not spoken, `occurred_at` = report time, approximate, status `DEFAULTED` (amber in review), not asked.
  - **Recommendations:** P0 shows SOP templates (`config/sop.json`) only; no LLM call for recommendations in P0.
  - **ASR:** P0 engine is whisper.cpp (CPU) behind `InferenceEngine.transcribe()` — same `AsrResult` contract.
  - **P0 cut:** narration cap 30 s; no draft resume UI, multi-incident flow, status lifecycle beyond SUBMITTED, dashboard charts, PDF/JSON export. **Kept:** Silero VAD, Hinglish follow-up templates.
- Who is affected: App branch.
- Action needed: App follows PRD v1.1 §4.0; nothing else in the contracts (taxonomy, tool schemas, DTOs) changed.
- Commit: (this commit)

## 2026-10-10 22:05 — Saarthak — ML deliverables the App branch consumes
- What changed:
  - **`config/`** is real now (taxonomy, sop, site_registry, app_config, prompts, schemas). Copy `config/` into app assets (PRD §20.10); never hard-code field names.
  - **`android/engine`** (Android library, namespace `com.megathon.incidentvoice.engine`): `InferenceEngine` interface + `JniInferenceEngine` (Plan A), `HttpInferenceEngine` (Plan B), `MockInferenceEngine` (slide-example script for UI work). Prebuilt `.so` files land in `android/engine/src/main/jniLibs/arm64-v8a/` once the native build finishes. Include with `include(":engine")` and `implementation(project(":engine"))`. Requires plugins `com.android.library`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.serialization` declared in the root build (App branch owns AGP version; ML assumes AGP 8.7.x, Kotlin 2.0.21).
  - **`android/core`** (pure Kotlin, in progress): validator, parser, prompt builder, normalisers, SOP templates, emergency detector. API in `android/core/README.md` when done.
  - **Deviations from PRD §18.6 / §19.2:** `NativeBridge.llmGenerate(prompt, task, sampler, maxTokens, cb)` has an extra `task` arg; Plan B streaming is **NDJSON** (`?stream=1`, lines `{"tok"}` then `{"done","text","stats"}`), not SSE.
  - **Model files (Plan A):** `getExternalFilesDir(null)/models/{llama,whisper,dsp}` = `/sdcard/Android/data/com.megathon.incidentvoice/files/models/`. ML pushes them; app only reads.
  - **ASR in P0 is whisper.cpp on CPU** inside `libiv_native.so` — same `transcribe()` API; later NPU upgrade is invisible to the app.
- Who is affected: App branch.
- Action needed: build UI against `MockInferenceEngine` now; switch to `JniInferenceEngine` when STATUS.md says the board is ready.
- Commit: c20214b

## 2026-10-11 00:50 — Saarthak — App can wire the REAL engine now (ASR live, LLM auto-switches)
- What changed:
  - `JniInferenceEngine(context, llmFallback = MockInferenceEngine())`: on-device **Whisper ASR works now** (whisper.cpp small, ~1.5 s for 11 s audio on the QIDK). Until the Llama V79 bundle is on the device, `generate()` is served by `llmFallback`. When the bundle arrives, call `engine.init()` again (e.g. on app start / a "Reload models" button) → `generate()` runs on the Hexagon NPU. `engine.llmOnNpu()` tells which is active (show it in the debug overlay).
  - ML pushes model files into the app dir automatically (`scripts/jobs/deploy_app_models.sh`): `/sdcard/Android/data/com.megathon.incidentvoice/files/models/{whisper,dsp,llama}`. **App package must be `com.megathon.incidentvoice`** (tell ML in CHANGE.md if different: set `IV_APP_PKG`).
  - Updated `android/engine/src/main/jniLibs/arm64-v8a/libiv_native.so` (fast ASR).
  - Language: pass `LangMode.AUTO_EN_HI` for now (ASR eval on the 30 clips decides the default; will be posted here).
- Who is affected: App branch.
- Action needed: `include(":engine")`, `implementation(project(":engine"))`, construct `JniInferenceEngine(applicationContext, llmFallback = MockInferenceEngine())`, call `init()` off the main thread at startup. Manifest gets `<uses-native-library libcdsprpc.so>` from the engine module automatically.


## 2026-10-10 — renny (App) — `config/` created verbatim from PRD
- What changed: App branch created `config/` straight from the PRD with no edits to keys/enums: `taxonomy.json` (App. A), `site_registry.json` (A.3), `sop.json` (App. B), `app_config.json` (§22.5), `prompts/*` (App. C.1–C.9; `warmup.user.txt` = C.9). Extracted by script and JSON-validated.
- Who is affected: ML branch (`Saarthak`) — PRD §22.5 / §34.1.
- Action needed by others: **use this copy of `config/`; do not create a second one.** Any change to keys/enums/prompts goes in PRD.md and here in the same commit.
- Commit: renny

## 2026-10-10 — renny (App) — decisions affecting the PRD text
- R1-07 followed everywhere: Room `@Fts4` on platform SQLite. PRD §20.12 (`sqlite-bundled`), §21.1 (BundledSQLiteDriver) and App. E FTS5 DDL are stale; App branch ignores them.
- P0 detail view has **no** Acknowledge/Resolve/Close (F-36 is P1, contradicts §23.2 "P0"). Status stays SUBMITTED.
- P0 dashboard: no charts or stat cards; one plain-text count row (total · critical · last 24 h) instead of §23.1/§23.3 stats.
- Draft resume UI cut (F-35); drafts still auto-saved.
- Single build flavour with Mock engine for now (planA/planB split from §20.1 deferred); real engines plug in via `InferenceEngine`.
- Model dir constant: `/sdcard/Android/data/com.megathon.incidentvoice/files/models/` (PRD §20.10), defined once in the app.

## 2026-10-10 night — renny (App) — what ML needs to know for integration
- **Interface to implement:** `com.megathon.incidentvoice.engine.InferenceEngine` (`android/core/src/main/kotlin/.../engine/Engine.kt`) — exactly PRD §22.1; DTOs (`EngineInfo`, `AsrResult`, `GenStats`, `EngineError`) carry the Appendix D.5 JSON field names via `@SerialName`. It lives in `:core` (pure Kotlin) so the orchestrator is JVM-tested; `JniInferenceEngine`/`HttpInferenceEngine` go in `:app` (`AppContainer.engine` is the one swap point).
- **Prompts:** app renders prompts itself (`PromptBuilder`, PRD §13.3 byte format, no BOS) from `config/prompts/*`; engine receives the finished string in `GenRequest.prompt`. `LlmTask`/`SamplerName` per §22.1; app passes `max_tokens` from `config/app_config.json`.
- **Empty generation** (0 tokens) is treated by the app as a retryable *engine* error, never a content failure (PRD §13.7).
- **FTS:** `FTS5` query syntax in the PRD (`"tok"*`) does not work on FTS4; app uses `tok*`. PRD §21.4/§21.5 should say so.
- **Model dir** constant: `ModelPaths.BASE` = `/sdcard/Android/data/com.megathon.incidentvoice/files/models` (PRD §20.10) in `android/app/.../config/ConfigLoader.kt`.
- Build/test: `cd android && ~/tools/gradle-8.10.2/bin/gradle :core:test :app:testDebugUnitTest`; `:app:assembleDebug` for the APK (package `com.megathon.incidentvoice`, minSdk 31, arm64-v8a only, **no INTERNET permission**).

## 2026-10-11 01:00 — renny (App) — App now consumes ML's `:engine` + `config/`; module layout to avoid merge conflicts
- **What changed**
  - `android/core`, `android/engine`, `config/`, the Gradle wrapper files and `.gitignore` rules on `renny` are **the `origin/Saarthak` versions** (re-synced at `d69713a`, no edits) → merging the two branches produces no conflicts in those trees. `eval/text_cases.jsonl` is also taken verbatim (used by app tests).
  - The App branch's own pure-Kotlin layer moved from `android/core` to **`android/domain` (module `:domain`)**. Its engine port lives in package `com.megathon.incidentvoice.domain.engine` so it does not clash with ML's `com.megathon.incidentvoice.engine`.
  - `:app` depends on `:domain` + `:engine`. `MlEngineAdapter` (`android/app/.../engineglue/`) is the **only** file that knows both type families; it wraps `JniInferenceEngine` (or any ML `InferenceEngine`). Settings → Engine: Auto / On-device / Mock. Auto = on-device when `llama/genie_config.json` and `whisper/<asr.whisper_cpp_model>` exist under `getExternalFilesDir(null)/models`, else Mock.
  - Root Gradle files are a superset of ML's: `settings.gradle.kts` includes `:core`, `:domain` always and `:engine`, `:app` when `-PwithAndroid=true` **or** an SDK is configured (`local.properties` / `ANDROID_HOME`). Root `build.gradle.kts` adds the Compose and KSP plugins. **When merging, take renny's `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`.**
  - The app's `PromptBuilder` now follows `config/prompts/RENDERING.md` and passes ML's five golden files byte-for-byte; SOP candidate filter mirrors `ml/common/sop.py` (golden candidate ids match).
- **Who is affected:** ML branch (merge), anyone building `android/`.
- **Findings for ML (no action taken on ML files)**
  1. `libiv_native.so` is linked with **4 KB** segment alignment (the QAIRT libs are 16 KB). Fine if the board runs 4 KB pages (`adb shell getconf PAGE_SIZE` → 4096); if it is 16384 the lib will not load → rebuild with `-Wl,-z,max-page-size=16384`. `scripts/app_device_check.sh` checks this.
  2. (Updated 01:15, after ML's `d69713a`.) The app constructs `JniInferenceEngine(context, modelsDir, whisperModel = app_config.asr.whisper_cpp_model, llmFallback = <app mock>)`. Auto mode picks the on-device engine as soon as the **Whisper** model is present; the Home pill is orange ("On-device ASR ready · LLM mock") until `llmOnNpu()` is true. After the Llama bundle is pushed: Settings → Engine → **Auto** (or "Retry engine") calls `init()` again. The fallback is the app's own mock (it classifies the real transcript) rather than ML's slide-only mock.
  3. The app treats `IV-E3xx` / `IV-E6xx` generation errors (incl. `IV-E301 empty LLM output`) as retryable (3/7/15 s) before showing ERROR_ENGINE.
  4. Eval set: in 11 of the 50 follow-up cases the eval asks `severity` although PRD §10.6 says a rule-derived severity (`INFERRED`) counts as filled — the app (and ML's Kotlin `:core`) do not ask. Not a bug; just a difference between the eval generator and the dialogue.
  5. PRD text to fix when convenient: FTS4 query syntax is `tok*` (not `"tok"*`), manifest must not force portrait (HDMI monitor is landscape), no `@Upsert` on FTS tables.
- **Action needed by others:** run `bash scripts/app_device_check.sh` on the machine with the board (see `handover_app.md`); `MOCK=1` keeps the app off the NPU.

## 2026-10-11 01:20 — renny (App) — `Saarthak` merged into `main` (ML files untouched)
- What changed: `main` = `renny` + `origin/Saarthak` (`f24f674`). Every ML-owned path (`android/core`, `android/engine`, `config/`, `native/`, `ml/`, `eval/`, `scripts/` (ML jobs), `server-go/`, `android-probe/`, `android/build.gradle.kts`, `android/gradle.properties`, Gradle wrapper) is **byte-identical to `Saarthak`**.
- Conflict resolutions: `android/build.gradle.kts` and `android/gradle.properties` → ML's version. `android/settings.gradle.kts` → ML's version plus, at the hook ML left ("The App team adds include(":app") here"), `include(":domain", ":app")` and the Compose/KSP plugin versions in `pluginManagement`. `.gitignore` → ML's plus the Silero asset exception, `.DS_Store`, `.kotlin/`. `CHANGE.md` → both sides kept.
- **This supersedes the earlier note "take renny's root Gradle files".** The App build now uses ML's gate: `gradle -PwithAndroid=true :app:assembleDebug` (without the flag only `:core` is configured, exactly as before for ML).
- Verified on the merged tree: `:core` 190, `:domain` 119, `:app` 12 tests green; debug APK builds.
- Action needed by ML: none. Keep merging `Saarthak` → `main`; no App file lives under an ML path.
