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

## 2026-10-11 01:15 — Saarthak — ASR language decision: English mode
- What changed: measured on the team's 30 recorded clips on the QIDK (whisper.cpp small, CPU): **`en` mode → English WER 7.0 %, ~1.9 s per clip**; `auto` is 3× slower with no English gain; Hinglish is weak in every mode (Whisper-small translates/garbles code-mix). `config/app_config.json` → `asr.language_mode = force_en`.
- Who is affected: App.
- Action needed: call `transcribe(pcm, mode = LangMode.FORCE_EN)`. Hinglish speech still works end-to-end (the LLM gets an English-ish transcript), just less accurately.

## 2026-10-11 03:45 — renny (App) — QIDK session: app on the board, ASR fix for ML to apply, shared signing key
- **Board state (app side):** app reinstalled from this laptop (the ML-laptop build could not be updated: different debug key). Models were moved aside and restored; `models/{whisper,dsp,llama,vad}` are now app-owned folders. On the board: native libs load in-app incl. `libQnnHtpV79Stub.so` (so `libcdsprpc.so` is reachable → Plan A prerequisite OK), whisper.cpp loads in ~120 ms, mic + Silero VAD + eSpeak work, full spoken conversation completes.
- **Shared debug key:** `android/app/debug.keystore` is committed and used by the debug build. **Every laptop now produces the same signature → `adb install -r` works across laptops; never uninstall.** (One-time: the ML laptop's old build is already replaced on the board.)
- **ACTION FOR ML — ASR accuracy (ready patch):** on a live board recording (speech/noise ≈ 11 dB) greedy `small-q5_1` heard "virtual leap" for "water leak" and (offline, same audio) "it's my love" for "it's minor". A short Whisper `initial_prompt` fixes both at no decoding cost (beam 5 also fixes the first, beam 2 does not, peak-normalising does not).
  - Apply `docs/proposals/asr_initial_prompt.patch` (`git apply docs/proposals/asr_initial_prompt.patch`; touches only `native/src/asr_engine.{h,cpp}`): the engine reads an optional `<models>/whisper/prompt.txt` when it loads the model and passes it as `initial_prompt` (`IV_ASR_PROMPT=0` disables). Then rebuild + commit `libiv_native.so`.
  - The app already writes that file on start (`AsrPrompt.build(taxonomy)`, 385 chars: incident topics + typical answer words). No Kotlin/API change needed on the ML side.
  - Worth re-running the 30-clip ASR eval with the prompt file in place.
- **Stand-in LLM is now rule-based** (`android/domain/.../domain/engine/RuleBasedLlm.kt`, used as `llmFallback` and by the Mock engine). On `eval/text_cases.jsonl` through the real prompts/parser: incident type 99 % EN / 96 % all, injuries 92 %, location 99 %, time 100 %, follow-up patches 100 % (tuned on this set → treat as an upper bound). It is the safety net if the Llama bundle is late.
- **Earlier App entries were dropped from this file by a merge** (config provenance, FTS4 `tok*` syntax, no forced portrait, module layout `android/domain`, `-PwithAndroid=true`). They are in git history (`7173767:CHANGE.md`) and summarised in `handover_app.md`.
- `adb logcat -s IV IVNative` now traces every conversation (state changes, transcripts, timings).

## 2026-10-11 04:00 — renny (App) — Ask tab (O-2): conversational query + vector index; ASR hint v2
- **New (app only, optional feature O-2):** fourth tab "Ask". A typed or spoken question → `IncidentQuery` (rules: type, severity, place from the site registry, date range, "how many", "latest", "what happened") → SQL filters ∩ (vector similarity ∪ FTS4 keyword) → short spoken answer + result cards. "Similar incidents" on the detail screen uses the same index.
- **Vector index:** `all-MiniLM-L6-v2` (quantized ONNX, 23 MB, 384-d) bundled in `android/app/src/main/assets/models/minilm/`, run with the ONNX Runtime already in the APK. Vectors live in a new Room table `incident_vectors` (DB **version 2**, migration 1→2 adds the table only; tested on the board's existing database). No SQLite extension. On the QIDK: 10 reports embedded in ~200 ms, a question takes ~50 ms. APK is now ~97 MB.
- **Hook for Llama:** `QueryUnderstanding` is the single interface that produces `IncidentQuery`; today `RuleQueryParser`. When ML adds a `search_incidents` tool/prompt (PRD §37 O-2), an LLM implementation plugs in there and nothing else changes. Nothing is needed from ML for the current version.
- **ASR hint v2:** the prompt the app writes to `models/whisper/prompt.txt` now also lists site places (≈ 560 chars). On 9 live board recordings (offline, same model/settings): 8 correct with the hint vs 5 without. The patch (`docs/proposals/asr_initial_prompt.patch`) now reads up to 800 chars. **Still needs ML to apply + rebuild `libiv_native.so`.**
- Board DB check: `PRAGMA integrity_check` ok; incidents, turns (audio/ASR timings), audit log, recommendations, FTS rows and vectors all written on the QIDK.
