# CHANGE.md — cross-branch change log

Purpose: announce any change on one branch that affects another branch (contracts, schemas, file paths, APIs, model files, board setup). Read this before starting work; append before pushing a change that others depend on.

**Branches**
| Branch | Area | Handover file |
|---|---|---|
| the ML branch | ML / NPU: models, export, native inference, prompts, taxonomy, eval | the ML handover file |
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

## 2026-10-10 — ML branch — Project docs baseline
- What changed: `PRD.md` v1.0 is the single source of truth. Shared contracts live in PRD Appendix A (`config/taxonomy.json`), B (`config/sop.json`), C (prompts), D (tool-call schemas + engine DTOs), E (SQL DDL).
- Who is affected: all branches.
- Action needed by others: any change to a field key, enum value, schema or DTO must be announced here **and** made in PRD.md in the same commit.
- Commit: 8ff065e

## 2026-10-10 — ML branch — Decision: dashboard is app-only
- What changed: dashboard stays **inside the Android app** (PRD D-44 and §23 unchanged). No web dashboard, no Web branch. Branches are App + ML only.
- Who is affected: Android branch (owns the dashboard: recency list, FTS search, filters, stats, detail view).
- Action needed: none for ML; Android builds PRD §23 as specified.

## 2026-10-10 21:20 — ML branch — PRD rev 1.1 (affects App)
- What changed (full list PRD §4.0 R1-01…R1-14):
  - **Search:** Room `@Fts4` instead of FTS5/bundled SQLite (PRD §21.4 note).
  - **Time:** if not spoken, `occurred_at` = report time, approximate, status `DEFAULTED` (amber in review), not asked.
  - **Recommendations:** P0 shows SOP templates (`config/sop.json`) only; no LLM call for recommendations in P0.
  - **ASR:** P0 engine is whisper.cpp (CPU) behind `InferenceEngine.transcribe()` — same `AsrResult` contract.
  - **P0 cut:** narration cap 30 s; no draft resume UI, multi-incident flow, status lifecycle beyond SUBMITTED, dashboard charts, PDF/JSON export. **Kept:** Silero VAD, Hinglish follow-up templates.
- Who is affected: App branch.
- Action needed: App follows PRD v1.1 §4.0; nothing else in the contracts (taxonomy, tool schemas, DTOs) changed.
- Commit: (this commit)

## 2026-10-10 22:05 — ML branch — ML deliverables the App branch consumes
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

## 2026-10-11 00:50 — ML branch — App can wire the REAL engine now (ASR live, LLM auto-switches)
- What changed:
  - `JniInferenceEngine(context, llmFallback = MockInferenceEngine())`: on-device **Whisper ASR works now** (whisper.cpp small, ~1.5 s for 11 s audio on the QIDK). Until the Llama V79 bundle is on the device, `generate()` is served by `llmFallback`. When the bundle arrives, call `engine.init()` again (e.g. on app start / a "Reload models" button) → `generate()` runs on the Hexagon NPU. `engine.llmOnNpu()` tells which is active (show it in the debug overlay).
  - ML pushes model files into the app dir automatically (`scripts/jobs/deploy_app_models.sh`): `/sdcard/Android/data/com.megathon.incidentvoice/files/models/{whisper,dsp,llama}`. **App package must be `com.megathon.incidentvoice`** (tell ML in CHANGE.md if different: set `IV_APP_PKG`).
  - Updated `android/engine/src/main/jniLibs/arm64-v8a/libiv_native.so` (fast ASR).
  - Language: pass `LangMode.AUTO_EN_HI` for now (ASR eval on the 30 clips decides the default; will be posted here).
- Who is affected: App branch.
- Action needed: `include(":engine")`, `implementation(project(":engine"))`, construct `JniInferenceEngine(applicationContext, llmFallback = MockInferenceEngine())`, call `init()` off the main thread at startup. Manifest gets `<uses-native-library libcdsprpc.so>` from the engine module automatically.

## 2026-10-11 01:15 — ML branch — ASR language decision: English mode
- What changed: measured on the team's 30 recorded clips on the QIDK (whisper.cpp small, CPU): **`en` mode → English WER 7.0 %, ~1.9 s per clip**; `auto` is 3× slower with no English gain; Hinglish is weak in every mode (Whisper-small translates/garbles code-mix). `config/app_config.json` → `asr.language_mode = force_en`.
- Who is affected: App.
- Action needed: call `transcribe(pcm, mode = LangMode.FORCE_EN)`. Hinglish speech still works end-to-end (the LLM gets an English-ish transcript), just less accurately.

## 2026-10-11 03:45 — App branch — QIDK session: app on the board, ASR fix for ML to apply, shared signing key
- **Board state (app side):** app reinstalled from this laptop (the ML-laptop build could not be updated: different debug key). Models were moved aside and restored; `models/{whisper,dsp,llama,vad}` are now app-owned folders. On the board: native libs load in-app incl. `libQnnHtpV79Stub.so` (so `libcdsprpc.so` is reachable → Plan A prerequisite OK), whisper.cpp loads in ~120 ms, mic + Silero VAD + eSpeak work, full spoken conversation completes.
- **Shared debug key:** `android/app/debug.keystore` is committed and used by the debug build. **Every laptop now produces the same signature → `adb install -r` works across laptops; never uninstall.** (One-time: the ML laptop's old build is already replaced on the board.)
- **ACTION FOR ML — ASR accuracy (ready patch):** on a live board recording (speech/noise ≈ 11 dB) greedy `small-q5_1` heard "virtual leap" for "water leak" and (offline, same audio) "it's my love" for "it's minor". A short Whisper `initial_prompt` fixes both at no decoding cost (beam 5 also fixes the first, beam 2 does not, peak-normalising does not).
  - Apply `docs/proposals/asr_initial_prompt.patch` (`git apply docs/proposals/asr_initial_prompt.patch`; touches only `native/src/asr_engine.{h,cpp}`): the engine reads an optional `<models>/whisper/prompt.txt` when it loads the model and passes it as `initial_prompt` (`IV_ASR_PROMPT=0` disables). Then rebuild + commit `libiv_native.so`.
  - The app already writes that file on start (`AsrPrompt.build(taxonomy)`, 385 chars: incident topics + typical answer words). No Kotlin/API change needed on the ML side.
  - Worth re-running the 30-clip ASR eval with the prompt file in place.
- **Stand-in LLM is now rule-based** (`android/domain/.../domain/engine/RuleBasedLlm.kt`, used as `llmFallback` and by the Mock engine). On `eval/text_cases.jsonl` through the real prompts/parser: incident type 99 % EN / 96 % all, injuries 92 %, location 99 %, time 100 %, follow-up patches 100 % (tuned on this set → treat as an upper bound). It is the safety net if the Llama bundle is late.
- **Earlier App entries were dropped from this file by a merge** (config provenance, FTS4 `tok*` syntax, no forced portrait, module layout `android/domain`, `-PwithAndroid=true`). They are in git history (`7173767:CHANGE.md`) and summarised in `handover_app.md`.
- `adb logcat -s IV IVNative` now traces every conversation (state changes, transcripts, timings).

## 2026-10-11 04:00 — App branch — Ask tab (O-2): conversational query + vector index; ASR hint v2
- **New (app only, optional feature O-2):** fourth tab "Ask". A typed or spoken question → `IncidentQuery` (rules: type, severity, place from the site registry, date range, "how many", "latest", "what happened") → SQL filters ∩ (vector similarity ∪ FTS4 keyword) → short spoken answer + result cards. "Similar incidents" on the detail screen uses the same index.
- **Vector index:** `all-MiniLM-L6-v2` (quantized ONNX, 23 MB, 384-d) bundled in `android/app/src/main/assets/models/minilm/`, run with the ONNX Runtime already in the APK. Vectors live in a new Room table `incident_vectors` (DB **version 2**, migration 1→2 adds the table only; tested on the board's existing database). No SQLite extension. On the QIDK: 10 reports embedded in ~200 ms, a question takes ~50 ms. APK is now ~97 MB.
- **Hook for Llama:** `QueryUnderstanding` is the single interface that produces `IncidentQuery`; today `RuleQueryParser`. When ML adds a `search_incidents` tool/prompt (PRD §37 O-2), an LLM implementation plugs in there and nothing else changes. Nothing is needed from ML for the current version.
- **ASR hint v2:** the prompt the app writes to `models/whisper/prompt.txt` now also lists site places (≈ 560 chars). On 9 live board recordings (offline, same model/settings): 8 correct with the hint vs 5 without. The patch (`docs/proposals/asr_initial_prompt.patch`) now reads up to 800 chars. **Still needs ML to apply + rebuild `libiv_native.so`.**
- Board DB check: `PRAGMA integrity_check` ok; incidents, turns (audio/ASR timings), audit log, recommendations, FTS rows and vectors all written on the QIDK.

## 2026-10-11 05:10 — App branch — Extra 1: response clock (schema + config change)
- **Config (`config/app_config.json`, additive only):** new `features` object (`response_clock`, `quick_launch`, `wake_word`, `multi_witness`, all `true`), `response_clock.ert_reminder_s` (120), `wake_display`, `wake_variants`, `wake_min_conf`, and `multi_witness` thresholds. No existing key changed; loaders that ignore unknown keys are unaffected (ML `:core` tests still pass).
- **Database v3 (not in PRD Appendix E):** `ALTER TABLE incidents ADD COLUMN response_started_at INTEGER` and new table `response_actions(id INTEGER PK AUTOINCREMENT, incident_id TEXT NOT NULL, step_id TEXT NOT NULL, label TEXT NOT NULL, done_at INTEGER NOT NULL, source TEXT NOT NULL)` with index `idx_response_incident(incident_id)`. Room migration 2→3; ran on the board's existing data.
- **Behaviour:** see HANDBOOK "Extra optional work → Extra 1". Steps come only from `config/sop.json` (`immediate: true`) plus a fixed "Call ERT". Switch off with `features.response_clock = false`.
- Who is affected: nobody on the ML side; noted for whoever edits `app_config.json` or the schema next.

## 2026-10-11 05:55 — App branch — Extras 2 and 3: wake word, multi-witness merging (schema change)
- **Database v4 (not in PRD Appendix E):** `ALTER TABLE incidents ADD COLUMN parent_incident_id TEXT` + `CREATE INDEX idx_incidents_parent ON incidents(parent_incident_id)`. Room migration 3→4; existing reports are kept (checked on the board). Null = the report stands alone; otherwise the id of the first report of the case it was linked to as a witness account. No foreign key: deleting the first report sets the column back to null on the others.
- **Audit log, new `action` values:** `WITNESS_LINK`, `WITNESS_ADDED`, `MERGE_RESOLVE` (field = form key, new_value = the value a supervisor trusts).
- **`IncidentRepository` (app-side interface), additive with defaults:** `recentSubmitted`, `linkWitness`, `caseOf`, `resolveConflict`, `conflictResolutions`; `IncidentListItem` gained `accounts`, `conflicts`, `parentRef`.
- **APK:** + Vosk (`com.alphacephei:vosk-android:0.3.47`, JNA 5.13.0) and the model `assets/model-en-in` (about 54 MB). New permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW`. Still no `INTERNET`.
- **Microphone:** the wake-word listener and the report flow never hold it together; the report flow makes the listener release first. If ML code ever records audio itself, it must do the same (`AppContainer.wake.release()`).
- Who is affected: nobody on the ML side. Switch off with `features.wake_word` / `features.multi_witness` in `config/app_config.json`.

## 2026-10-11 06:15 — App branch — App renamed to "Qalam", UI restyle
- App label, launcher icon and splash are now **Qalam** (package name, Gradle modules and code names are unchanged: still `com.megathon.incidentvoice`). Logo sources in `branding/`.
- Visual change only; no logic, state, database or engine change. New bundled fonts (Inter, JetBrains Mono; SIL OFL, licences in `android/app/src/main/assets/licenses`), about 1 MB.
- New folder `shots/` with board screenshots for the slides.
- Who is affected: anyone writing slides or docs should call the app "Qalam".
## 2026-10-11 06:40 — ML branch — NO_INCIDENT sentinel
- **New extraction contract (schema-valid, no schema change):** for speech with no incident (greetings, mic tests "hello testing 1 2 3", "can you hear me", questions to the assistant, jokes, weather, "just checking the app", "kaise ho", "haan hello sun rahe ho", "kuch nahi bas check kar raha tha", filler-only / ASR noise like "Thank you."), `fill_incident_form` returns exactly `{"incident_type":"OTHER","description":"NO_INCIDENT","lang":"en"|"hinglish"}`. Small talk mixed with a real incident → normal extraction (the chatter is ignored).
- **Prompts changed:** `config/prompts/extract.system.txt` (rule 11 + example; stock model uses it now) and `extract.system.ft.txt` (one sentence, ~35 tokens). Golden files regenerated (`config/prompts/golden/extract_slide*.txt`); Kotlin `:core:test` 190/190 green against them.
- **App must:** when the extraction's `description == "NO_INCIDENT"` (case-insensitive; `incident_type` OTHER), **do not create a draft / incident row and do not ask follow-ups**; say/show *"I didn't catch an incident. Please tell me what happened."* (Hinglish: *"Mujhe koi incident samajh nahi aaya. Batayiye kya hua?"*) and keep listening. Apply the same in `RuleBasedLlm` (mock stand-in): return the sentinel when the transcript has no incident keyword. Treat a sentinel answer in a follow-up/patch turn as "no new info", never as a description.
- **Python twins:** `parser.parse()` sets `ParsedCall.no_incident` and canonicalises params to the sentinel (extra keys dropped); `validator.is_no_incident(form)`; `validate()` returns no missing fields for it (→ no follow-up question). `eval/score.py`: gold-NI case correct iff prediction is NI; real incidents predicted NI counted as false rejections (`no_incident` block in metrics/report). New eval set `eval/smalltalk_cases.jsonl` (15 cases, 10 en / 5 hinglish).
- **Fine-tune:** v2 small-talk retrain was cancelled (user decision); the shipped fine-tune is v1 and small talk is handled by the prompt rule. The NO_INCIDENT training data (200 + 150 mixed) is in `gen_ft_data.py` for a later run.

## 2026-10-11 06:50 — App branch — NO_INCIDENT sentinel handled; reply to the ML entry of 06:40
- **Done in the app:** `description == "NO_INCIDENT"` (case-insensitive) from `fill_incident_form` → no form fill, no follow-ups; the app says "I didn't catch an incident. What happened?" (Hinglish: "Mujhe koi incident samajh nahi aaya. Kya hua tha?") and listens again, three tries at most, then the empty draft is deleted. In an `update_incident_fields` answer the sentinel is dropped (no new info). `RuleBasedLlm` returns the sentinel too.
- **One difference from the ML note:** a draft row exists while the app is listening (the recording path needs its id). It is deleted if nothing was reported; it never reaches the dashboard, the Ask tab or the counts, which exclude drafts.
- **Also changed on the app side this morning (no action needed by ML):** in an emergency only the place is asked; a model failure after the retries falls back to the rules engine for that turn ("Using offline fallback"); the follow-up language is taken from the reporter's words (`LangDetector.ofSpeech`), not from the model's `lang`; a model description that shares less than 70 % of its words with the narration is replaced by the lightly cleaned narration. The last two override model output on purpose; say so if the fine-tuned model should be trusted more.
- **For ML:** ASR benchmark of tiny.en / base.en / small with and without the hint is in `eval/asr_benchmark.md` (written by the App branch into `eval/`; nothing of yours was modified).

## 2026-10-11 08:50 — App branch — Qwen3-4B on the NPU wired into the app (touches engine packaging and config/prompts)
- **Model:** Qualcomm's pre-compiled Qwen3-4B Genie bundle (w4a16, SM8750 / V79, QAIRT 2.45 export) pushed to the app's `models/llama/` folder with a `model.json` (`{"family":"qwen"}`). Not in the repository; download and push commands are in HANDBOOK.
- **New file in `config/prompts/`:** `extract.system.qwen.txt` (additive; the Llama prompts and their golden files are untouched and their tests pass).
- **New prebuilt library in the APK:** `android/app/src/main/jniLibs/arm64-v8a/libQnnHtpNetRunExtensions.so` (QAIRT 2.50, copied from the board's `/data/local/tmp/iv/lib`). Without it `GenieDialog_create` crashes in the app for any bundle whose `genie_config.json` names a backend extension file. **ML: the Llama bundle will need it too.**
- **Genie session bug to know about:** with this bundle and Genie 1.20, a prompt longer than about 500 tokens breaks the dialog for every later query (`GenieDialog_query status=-1`, "batch dispatch failed"), even after `GenieDialog_reset`. `iv_cli llm-batch` is affected (first case works, the rest fail); the eval was run with one `iv_cli llm` process per case. The app re-creates the session after each call (`MlEngineAdapter`). A proper fix belongs in `native/src/llm_engine.cpp` (re-create the dialog instead of resetting it) and needs a native rebuild.
- **Prompt builder:** chat template is chosen by model family (`ModelFamily.LLAMA` unchanged, `QWEN` = ChatML with an empty think block). `PromptBuilder.extract` takes an optional keyword hint, used only for Qwen.
- **Not touched:** `native/`, `android/core`, `android/engine` sources, the Llama prompts, schemas, taxonomy.

## 2026-10-11 10:10 — ML (Saarthak) — ASR latency fix: Genie "poll" was starving whisper.cpp (22 s → ~2.5 s)
- **Root cause (measured on the QIDK):** the LLM bundle's `genie_config.json` has `"poll": true` with `"cpu-mask": "0xe0"` → 3 Genie worker threads (libGenie.so) busy-spin on the prime cores 5–7 all the time, even when idle (app at 100–200 % CPU idle). whisper.cpp's 6 threads then wait on those cores: in-app ASR 22.8 s for a 5 s utterance; `iv_cli` with the app running 11 s; app stopped 2.5 s. Prompt hint costs nothing (2.50 vs 2.51 s).
- **Fixed now on the board** (no rebuild): `poll` → `false` in `models/llama/genie_config.json` (backup `genie_config.poll_true.bak`). App idle CPU 11 %, ASR with the app running **2.6 s** (11 s clip).
- **Permanent:** native loader forces `poll:false` for any bundle (`IV_GENIE_POLL=1` restores); `libiv_native.so` rebuilt in `android/engine/src/main/jniLibs`. `apply.py` also patches the board's config on every run.
- **Action (App):** rebuild + `install -r` from main (or just `python apply.py`). If you re-push a Genie bundle, keep `poll:false`.

## 2026-10-11 10:25 — ML (Saarthak) — LLM: real stop at max_tokens + stop when the tool-call JSON closes
- Measured in the app after the poll fix: **ASR 1.5–1.7 s per utterance** (was 22 s), PATCH 1.7 s (33 tok), but **EXTRACT 16.2 s = 320 tokens = the max_tokens cap**.
- Native bug: at the cap the token callback only *ignored* further tokens; Genie kept generating. Now it signals Genie to stop. Also stops the moment the top-level JSON object closes (`IV_JSON_EARLY_STOP=0` disables). Verified on the QIDK with the Qwen bundle: 23.6 tok/s decode, TTFT 71 ms, 73-token tool call in 3.1 s.
- **Action (App):** rebuild + `install -r` (new `libiv_native.so` in jniLibs). Please log the raw EXTRACT output once (logcat) — if Qwen really writes ~320 tokens for the form, trim the extract prompt / omit-empty-fields rule (fewer fields emitted = proportionally faster: ~20 tok/s).
