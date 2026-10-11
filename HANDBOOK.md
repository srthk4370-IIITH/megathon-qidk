# HANDBOOK — IncidentVoice (Megathon X × Qualcomm)

Running log of what happened, what was decided, and where things stand. `PRD.md` is the spec; this file is the history and the current status. Newest entries at the top of §2.

## 1. Current state (2026-10-10)

| Area | Status |
|---|---|
| Spec | `PRD.md` **v1.1** (rev table §4.0 overrides v1.0). `M1.md` 2-page approach summary. `RA.txt` SRS corrected. |
| Board | SM8750P (Snapdragon 8 Elite, HTP V79), Android 15 userdebug, 15.5 GB RAM. `/data/local/tmp` wiped → 29 GB free. Clock **fixed** 21:03 IST, timezone Asia/Kolkata. |
| Laptop | VHDX compaction done by user → **C: 132 GB free**. QAIRT 2.50 downloading to WSL `~/qairt/` (public URL). |
| Models | **Llama 3.2 3B → V79 Genie export running twice:** laptop WSL (`~/iv_exports/llama_stock`, log `~/iv_exports/llama_export.log`, started 21:03) and Kaggle TPU kernel `<ml-account>/iv-llama-v79-export`. Whisper: whisper.cpp CPU for P0 (not started). |
| Code | None yet. Repo has docs + `qidk/` reference + `_board_runtime_backup/` (QAIRT 2.47 V79 runtime pulled from the board). |
| Architecture | Plan A (in-app JNI) vs Plan B (shell-user server) undecided until the probe APK runs (PRD §26.6, ~H3). |

## 2. Log

### 2026-10-10 ~21:00 — External review, QAIRT blocker, exports started (rev 1.1)

**QAIRT 2.50 blocker solved:** QPM download failed for the team. Found Qualcomm's public no-login URL pattern in `qidk/Tools/qairt_docker/dockerfile` (used there for 2.47). Probed builds with HTTP range requests: `2.50.0.260828` → 206 (public, 2,601,473,189 bytes); `2.50.0.260828221209`, `2.45.0.260326154327`, `2.49.0.260731` → 404; `2.45.0.260326` → 206. Downloading 2.50.0.260828 into WSL `~/qairt/`. Note: AI Hub compile itself never needed the SDK; only device libs + Genie headers do.

**Exports started (none had been started before):**
- Laptop WSL: `qai-hub-models export llama_v3_2_3b_instruct --device "Samsung Galaxy S25 (Family)" --target-runtime genie --skip-profiling` (21:03). Memory warning (80 GB recommended, 72 GB RAM+swap) — expected. **Risk noted:** laptop venv has transformers 4.56.2 while the recipe pins 4.45.0; if published encodings don't match the exported graph, this run fails → Kaggle run is the clean-pin hedge.
- Kaggle: token saved locally only (`~/.kaggle/access_token`, chmod 600; Kaggle CLI 2.2.4 in `~/venvs/kaggle`, user `<ml-account>`). Private dataset `<ml-account>/iv-creds` (HF + AI Hub tokens, **delete after export**). Private kernel `<ml-account>/iv-llama-v79-export` on a **TPU VM** (more RAM than 30 GB CPU sessions), installs `qai-hub-models[llama-v3-2-3b-instruct]==0.63.0` with clean pins, zips bundle to `/kaggle/working`. Tokens never printed.
- WSL quirk: `wsl -- bash -lc '...$VAR...'` from Git Bash loses `$VAR` → write a script file and run `wsl -- bash /mnt/c/.../script.sh`.

**Board clock fixed:** `adb root; setprop persist.sys.timezone Asia/Kolkata; date <laptop time>; adb unroot` → `Sat Oct 10 21:03:47 IST 2026`.

**ASR-LLM-TTS sample analysis:** its Genie JNI (`native-lib.cpp`) is real and reusable (config from JSON → dialog create → setMaxNumTokens → query/reset, `setenv ADSP_LIBRARY_PATH` to nativeLibraryDir). Its Whisper uses a closed QPM jar (`com.qualcomm.qti.voice.assist.whisper.sdk`) → **not copyable**; reviewer's assumption corrected.

**Decisions (AskUserQuestion), recorded as PRD rev 1.1 (§4.0 R1-01…R1-14):** whisper.cpp CPU in P0 + ORT-QNN NPU upgrade; keep App + ML split; accept P0 cut but keep Silero VAD + Hinglish templates; mandatory fields unchanged; time defaults to now/approx; recommendations = SOP templates in P0; Room @Fts4; template-generated eval + 30 clips; all fine-tuning cut; M1.md written.

### 2026-10-10 (later) — Branch workflow + VHDX compaction error

**Branch workflow (from user + updated CLAUDE.md):** all ML work commits go to the ML branch; a separate branch owns the Android app (including the dashboard). Main carries `HANDBOOK.md`, `CHANGE.md` (cross-branch change announcements), `STATUS.md` (what exists now); each branch keeps `handover_<branch>.md`. Created `CHANGE.md`, `STATUS.md`, the ML handover file. PRD v1.0 was pushed to `main` and the ML branch as `8ff065e`.

**Dashboard decision:** user confirmed the dashboard is **app-only** (PRD D-44 stands). Only two branches: App and ML. Logged in CHANGE.md.

**Git rule (user):** local issues and history (laptop, board housekeeping, HANDBOOK) are pushed to the ML branch only; `main` receives only the shared coordination files `CHANGE.md`, `STATUS.md` and handover files.

**Error:** user ran the VHDX compaction (PRD Appendix H); diskpart failed:
`DiskPart has encountered an error: The requested operation could not be completed due to a virtual disk system limitation. Virtual hard disk files must be uncompressed and unencrypted and must not be sparse.`
**Cause:** during cleanup I had enabled sparse VHD (`wsl --manage Ubuntu --set-sparse true`); diskpart cannot compact sparse VHDX files. The sparse flag alone did not return space to Windows (C: stayed ~14 GB free).
**Fix:** `wsl --shutdown; wsl --manage Ubuntu --set-sparse false` (succeeded), then re-run `diskpart /s $env:TEMP\compact.txt` as admin. **Lesson:** use one method — either sparse + fstrim, or non-sparse + diskpart compact; never mix.

### 2026-10-10 — Discovery, clarification, PRD

**Inputs read:** `Problem Statement.pptx` (5 slides + notes: STT → LLM extraction → mandatory-field check → function calling → review/submit, TTS + screen; criteria: on-device Snapdragon, natural speech, ASR accuracy, extraction accuracy, field mapping), `RA.txt`, `problems_setup.md`, `qidk/GenAI-Solutions/ASR-LLM-TTS` and `Solutions/NLPSolution3-…-Whisper` READMEs, `qidk guide.pdf` (HDK8650 hardware quick-start: mics, 3.5 mm jack, speakers, HDMI DIP switches), the previous project's `EXPORT_RESEARCH.md`.

**Board findings (adb, verified):**
- `ro.soc.model=SM8750P` → **V79**, not the V75 board from `problems_setup.md`. The previous project's fine-tuned bundle (`Desktop\qidk\qidk_backup\genie_bundle_ft`, soc_model 57/v75) **cannot** run here.
- `/dev/fastrpc-cdsp` exists (old board had none) → in-app NPU likely works; Qualcomm's own ASR-LLM-TTS sample runs Genie in-app on SM8750.
- Leftover runtime on board was QAIRT **2.47** → too old for AI Hub's 2.50 compiles → download QAIRT 2.50 at H0.
- Only TTS engine: eSpeak NG (`com.reecedunn.espeak`).
- Clock 2024-08-14, timezone GMT.
- `/data` was 97 % full (other project's 27 GB in `/data/local/tmp`).

**Measured/published speeds (qai-hub-models 0.63 `perf.yaml`, Galaxy S25 = same SoC):** Llama 3.2 3B w4a16 Genie ≈ 28 tok/s decode, ≈ 730 tok/s prefill; Whisper-small encoder ≈ 66 ms, decoder ≈ 8.4 ms/token.

**Actions taken (user-approved):**
- Pulled board runtime (QAIRT 2.47 V79 libs, genie-t2t-run, qnn-net-run, validator) → `_board_runtime_backup/`.
- Deleted everything in board `/data/local/tmp` (kept installed apps).
- Laptop: deleted WSL `~/gptq_out`, `~/qaihm_work/ftgptq16_ckpt`, `~/smartcity_runs`, go-build cache, pip/npm/uv/pnpm/puppeteer caches, Windows `%TEMP%`; enabled sparse VHD + fstrim. Kept HF Llama weights, venvs, AI Hub token, `Desktop\qidk`, Docker.
- CLI NPU probe via `run-as` + `qnn-platform-validator`: **inconclusive** (exec'd binaries lack the app linker namespace for `libcdsprpc.so`). Real probe must be an APK.

**Clarification:** 60 questions asked across 15 rounds; answers recorded as decisions D-01…D-64 in PRD §4. Key ones: 15-hour hackathon, 3 people, nothing before the clock; stock Llama 3.2 3B + Whisper-small on NPU, fine-tunes as gated stretch; EHS domain with 10 categories; validator rule-based; templated voice follow-ups (≤2 fields, ≤3 rounds); SOP-grounded recommendations from the same Llama; Kotlin/Compose/Room+FTS5; Plan A/B decided by probe; Llama export on Kaggle at H0.

**Output:** `PRD.md` v1.0, `.gitignore`, this handbook.

## 3. Open items before / at H0

1. User runs the VHDX compaction (PRD Appendix H).
2. Qualcomm ID ready for QAIRT 2.50 download; Kaggle account with phone verification (Internet ON) + secrets `HF_TOKEN`, `QAIHUB_TOKEN`.
3. Llama 3.2 licence accepted on Hugging Face for the account whose token Kaggle uses.
4. Wired headset mic + fan + HDMI cable for the demo (PRD §6.4).
5. Branch names (team to decide, PRD §34.2).

---

## App branch log — 2026-10-10 night

**Toolchain (this Mac):** JDK 17 (Temurin), Android SDK 35 / build-tools 35.0.0 / platform-tools via cmdline-tools, Gradle 8.10.2 (wrapper committed; the wrapper's own download times out on this network, so a local copy lives in `~/tools/gradle-8.10.2` — run `~/tools/gradle-8.10.2/bin/gradle`). Robolectric android-all jars are pre-downloaded with curl into `~/tools/robolectric-jars` (the JVM downloader hangs); `app/build.gradle.kts` uses them automatically when the folder exists.

**Stage A (done):** `android/` Gradle project (`:core` JVM, `:app`). `config/` created verbatim from PRD App. A–C (extracted by script, JSON-validated). `:core`: Taxonomy loader, `IncidentForm`/`FieldState`, `Validator` (V1–V10), `SeverityRules`, `TimeResolver` (T1–T10), `LocationMatcher`, `PeopleNormaliser`, `LangDetector`, `EmergencyDetector`, `ToolCallParser`+`PartialJson`+`Grounding`, `FormMerger`, `PromptBuilder` (+ tool schemas D.1–D.3), `SopSelector` (P0 templates), `FormMapper`/`SearchQueryBuilder`/`SearchBody`, `AppConfig`.

**Stage B (done):** Room entities per App. E, `@Fts4` search entity, DAOs, `RoomIncidentRepository` (one transaction per save: incident + children + audit diff + FTS reindex), dashboard (recency list, FTS search with snippets, type/severity/date filters, count row), detail view (basic), `DemoSeeder` (10 realistic incidents through the real parse→merge path). Verified with 8 Robolectric tests against a real in-memory SQLite.

**Stage C (done on Mock engine):** `InferenceEngine` interface + DTOs (`core/engine`), `MockInferenceEngine` (water-leak demo scene + keyword classifier + heuristic answerer), `ConversationOrchestrator` (§8 state machine, in `:core`, 20 JVM tests incl. the water-leak demo end to end), Home/Conversation/Review/Submitted screens, `AndroidTtsSpeaker` (eSpeak). Debug APK builds (78 MB, ORT included).

**Problems met and fixes**
- FTS4 query syntax is `tok*` — the PRD's `"tok"*` is FTS5 syntax and returned nothing on FTS4. Fixed in `SearchQueryBuilder`.
- Room `@Upsert` on an FTS4 table throws a generic constraint error (can't detect "already exists"); replaced by delete + insert.
- Local `put()` helper shadowed the `buildMap` `put` in `FormMapper.toForm` → persons restored as `{}`; caught by the round-trip test.
- `JaroWinkler` alone matched "somewhere on the moon" to a room (>0.7); now only counts ≥ 0.88 and digit mismatches ("room 7" vs "room 2") cap the score at 0.69.
- Mock "hurt" was in the YES word list → "no one is hurt" answered YES; fixed.
- Gradle's JVM DNS fails intermittently here; re-running works.

## App branch log — 2026-10-11 00:10–01:00 (integration + verification)

**Trigger:** user asked to pull main, make the whole app side of the PRD work, and verify it will run on the QIDK (board is attached to the ML machine, not this Mac).

**What I found after `git fetch`:** `main` only had doc changes (merged; CHANGE/STATUS conflicts resolved by keeping both). the ML branch however already contains its own `android/core` (190 tests), `android/engine` (`InferenceEngine`, `JniInferenceEngine`, prebuilt `libiv_native.so` + QNN/Genie libs), `config/` (superset `app_config.json`, `schemas/`, golden prompts) and `eval/` — same path and same package as my engine port. Left alone, the two branches would not merge and the APK would have duplicate classes.

**Decisions (reasoning)**
- Keep ML's trees untouched and identical (`git checkout the ML branch -- android/core android/engine config`) so the merge is conflict-free; move my layer to `android/domain`. Rewriting the app onto ML's `:core` API would have cost hours with no user-visible gain; instead I *prove equivalence* with tests (below).
- Adapter instead of adopting ML's engine types in the orchestrator: the orchestrator stays pure Kotlin (fast JVM tests) and ML can change DTOs with a one-file fix on my side.
- Removed `screenOrientation="portrait"` (PRD §20.9): the QIDK shows on an HDMI monitor (landscape, `qidk guide.pdf`); forced portrait would be sideways/pillar-boxed. Content is centred with a 720 dp max width; verified in both orientations.

**Verification done**
1. *Prompt parity:* my `PromptBuilder` reproduces ML's golden files (extract base/ft, patch, recommend, warm-up) byte-for-byte.
2. *Eval cross-check:* all 150 gold tool calls survive my parser/merger/time resolver/validator with nothing dropped; follow-up question text equals the eval's in 39/50, the other 11 are the §10.6 rule-severity case.
3. *Differential tests vs ML `:core`:* time resolver identical on every eval phrase; location matcher identical except bare "Block A/B".
4. *Type bridge:* orchestrator driven through ML's own `MockInferenceEngine` via `MlEngineAdapter` completes the slide conversation.
5. *Real Android run:* installed `emulator` + `system-images;android-35;google_apis;arm64-v8a`, AVD `iv_test`; installed the debug APK and walked through every screen with `adb`/`uiautomator`.

**Bugs found only by running the APK (all fixed)**
- Reference number was `INC-20261011+0530-0001` (`BASIC_ISO_DATE` on a zoned value appends the offset) → `yyyyMMdd`; test now checks the exact pattern.
- On-screen keyboard covered the text field and Send button (Android 15 edge-to-edge ignores `adjustResize`) → `imePadding()`.
- "I didn't hear anything" notice stayed on screen for the rest of the conversation → cleared on the next input.
- White status-bar icons on a white background → `enableEdgeToEdge()`.
- User and assistant bubbles had near-identical colours; detail screen had a doubled top inset; enum codes shown raw (`IN_PROGRESS`); dashboard header left room for one card in landscape → all fixed.
- Demo seed data used an enum value that is not in the taxonomy (`UNAUTHORISED_ENTRY`) and missed a mandatory field; a new test asserts every demo incident validates.

**Still unverified (needs the board):** real microphones + noise with the VAD thresholds, eSpeak voice, `libcdsprpc.so` visibility from the app (Plan A), Genie/whisper.cpp load inside the app, end-to-end latency. `scripts/app_device_check.sh` automates the checkable parts.

### 2026-10-11 02:35 IST — ML branch: board handoff, export speed-up
- Hotspot upload: one S3 connection gets ~30 kB/s, 12 parallel connections 1.2 MB/s aggregate (latency-bound, not bandwidth-bound). qai_hub patched to 16 MiB parts × 48 connections → Part 1 (820 MB) uploaded in ~5 min (was ~200 kB/s, ETA 6 AM). Export restarted with resume hook keyed by part name (`part:Llama3_2_3B_PartN_Of_3_w4a16.aimet`), so finished parts are never re-uploaded.
- Kaggle export attempt failed: CPU box 31 GB RAM, no swap allowed → torch export OOM-killed; TPU v3-8 shape is retired (falls back to CPU). Kaggle path abandoned; private dataset `<ml-account>/iv-secrets` (tokens) to be deleted.
- Local fine-tuning rejected for now: it does not remove the export/upload bottleneck.
- ASR decision: force_en (EN WER 7.0 %, 1.9 s/clip on QIDK; Hinglish CER 70 %+ in every mode).
- QIDK moves to the second laptop for app testing with real Whisper + mock LLM; see STATUS.md "QIDK handoff".

## App branch log — 2026-10-11 03:00–04:00 (first session on the QIDK, Ask tab)

**Getting onto the board.** The app on the QIDK had been installed from the ML laptop; my build was rejected (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, different debug key). With the user's OK: moved `models/` to `/sdcard/iv_models_backup`, uninstalled, installed, let the app create its model folders (now app-owned), moved the files back. Added a shared `android/app/debug.keystore` so this cannot recur.

**Live speech tests with the user** (trace: `adb logcat -s IV IVNative`): mic, VAD auto-stop, Whisper, eSpeak questions, patching, review, emergency banner all worked. Problems seen and what they turned out to be:
- "water leak" → "virtual leap", "forklift … Bay 4" → "fourth lip … bed for": ASR, not the app. Pulled the WAVs off the board (`run-as`), measured ≈ 11 dB speech-to-noise, and reproduced the exact errors offline with whisper.cpp + the same model and greedy settings. Peak normalisation: no effect. Beam 5: fixes some. A domain `initial_prompt` (topics + site places + answer words): 8/9 utterances correct vs 5/9. The fix is in ML's native code → patch in `docs/proposals/`, app already writes the prompt file.
- "Second question not asked" / "patient condition still required": the validator was right; the old mock LLM classified by a few keywords ("hurt" → MEDICAL) and could not map "Yes" to an enum. Replaced by `RuleBasedLlm` (negation-aware classification, per-field enum rules, yes/no semantics, clause-level "don't know"), scored on the ML eval set through the real prompts/parser: type 49 % → 99 % (EN), injuries 40 % → 92 %, follow-up patches 74 % → 100 %. Tuned on that set, so it is an upper bound.
- Transcript review: the 2 s auto-proceed gave no time to fix a word → the countdown now holds while the field has focus.

**Decisions (asked the user):** Ask feature = a tab in the same app (not a second APK); bundle MiniLM for embeddings; rule-based question understanding now, Llama tool call later.

**Ask tab design.** Brute-force cosine over float32 BLOBs in SQLite instead of `sqlite-vec` (the platform SQLite cannot load extensions; at this scale the scan is ~1 ms). Only generic category nouns become hard type filters; specific things ("forklift", "ammonia") are matched by meaning/keyword because the same event can be filed under different types; type filters match primary **or** secondary type. Relevance: cosine ≥ 0.28, or all question words present, or one word + cosine ≥ 0.18 (calibrated on the quantized model: true matches 0.28–0.6, unrelated < 0.25). A whole question embeds better than its content words, so the raw question is embedded when no filter was extracted.

**Not done / open:** Llama bundle (ML); ASR patch applied + rebuilt (ML); Ask by voice tried only via the shared audio path, not yet spoken live; drafts on the board from the live tests were never submitted (they are not shown in Dashboard/Ask by design).

---

## Extra optional work (App branch) — three extras beyond the PRD's P0/optional list

Scope decided 2026-10-11 ~05:00 IST. Preconditions checked first: the core loop (record → transcript → extract → validate → follow-up → review → submit → dashboard) builds, passes all tests (353 at the time) and runs on the QIDK with real on-device speech; the Mock engine path is untouched.

**Rules these extras follow**
- Each extra sits behind a flag under `features` in `config/app_config.json` (default ON) so it can be switched off instantly before a demo. Nothing about them is hard-coded elsewhere.
- Pure logic lives in the pure-Kotlin module with JVM unit tests; screens and Android services live in the app module. The Mock engine path keeps working.
- Any schema change is a Room migration plus an entry in `CHANGE.md` (noted against PRD Appendix E). `STATUS.md` and `handover_app.md` are updated per feature.
- Emergency steps always come from `config/sop.json`, never from the LLM. Nothing is merged or decided automatically where a person's safety or a record's integrity is involved.
- One feature per commit on the App branch; after it builds and passes tests it is merged to `main` with a neutral message. No force pushes. Feature merges stop at 10:15 IST; bug fixes only until the 11:00 IST freeze.

Status key: PLANNED → BUILT (tests pass) → ON BOARD (verified on the QIDK).

### Extra 1 — Response clock (flag `features.response_clock`) — status: ON BOARD (verified on the QIDK 2026-10-11 05:05)

- **What:** when an incident looks like an emergency, a pinned card appears at the top of the conversation and review screens with a large `mm:ss` timer counting up from the moment of detection and the category's *immediate* steps as large tap targets.
- **Why:** in the first minutes of a fire, injury or gas leak the reporter should be acting, not filling a form. The form keeps filling in the background; the card keeps the next physical step and the elapsed time in front of them, and records what was done and when without any typing.
- **Trigger:** the emergency banner fired (keyword hit or severity CRITICAL), or the category is FIRE, MEDICAL or HAZMAT, or ELECTRICAL with `electrical_hazard = SHOCK`.
- **How:** steps are the `immediate: true` entries of the category in `config/sop.json` plus a fixed "Call ERT" step. Tapping a step writes a `response_actions` row (`id, incident_id, step_id, label, done_at, source = TAP`) and appends the label to the incident's `actions_taken`. "Call ERT" opens the dialler (`ACTION_DIAL`) with the configured number and logs the time automatically. If "Call ERT" has not been done after 120 s (`response_clock.ert_reminder_s`), the card pulses amber and one short reminder is spoken. The start time is stored with the incident so the clock survives an app restart. The clock never blocks reporting; both run in parallel. The detail screen shows the timeline ("Alarm 00:00 → ERT 01:12 → Evacuated 03:40").
- **Always shown:** "Follow your site's emergency procedures. This checklist is guidance, not medical advice."
- **Data:** new table `response_actions`; new column `incidents.response_started_at` (migration).
- **Where the code is:** rules `android/domain/.../core/ResponseClock.kt`; state machine hooks in `ConversationOrchestrator` (`maybeStartResponseClock`, `onResponseStep`, `resumeActiveResponse`); card `android/app/.../ui/components/ResponseClockCard.kt`; storage `response_actions` + `incidents.response_started_at` (Room migration 2→3).
- **Verified:** 7 JVM tests (start rules, SOP-only steps in SOP order, tap logging, single reminder after 120 s and none once ERT is called, restart, flag off) + 1 database test. On the QIDK: spoken "fire spreading … unconscious" → card with a running timer and the four steps; taps logged with offsets (00:18, 00:20, 00:21, ERT 00:24, dialler opened); after force-stopping and reopening the app the clock continued (00:46) with every step and time intact.
- **Known limitations:** the card is tall on a phone-sized screen (it can be collapsed with the arrow); the timeline uses a shortened form of the SOP sentence as its label, not a hand-written short name; the reminder is spoken once only; a response clock is resumed after a restart only for a draft started in the last 60 minutes; when no dialler app exists the "Call ERT" tap still logs the time and a message shows the number.
- **30-second demo:** say "There is a fire spreading in the server room and someone is unconscious" → the card appears with the timer running → tap "Call ERT" (dialler opens, time logged) → tap "Evacuate" → open the saved report: the timeline shows each step with its offset.

### Extra 2 — One-tap launch and "Hey Qalam" wake word (flags `features.quick_launch`, `features.wake_word`) — status: Level A and Level B ON BOARD (2026-10-11)

- **What (Level A, `quick_launch`):** three ways to start a report without opening the app: a Quick Settings tile "Report incident", a long-press app shortcut, and a home-screen widget with one large red REPORT button. Each opens the conversation screen with the microphone already listening.
- **What (Level B, `wake_word`):** the assistant is called **Qalam** (a play on "kalam", pen; Q for Qualcomm). Saying "Hey Qalam" starts a report hands-free.
- **Why:** a person reporting an incident may have gloves on, dirty hands or a casualty in front of them. Every tap removed shortens the time to a usable report.
- **How (Level B):** a foreground service (type `microphone`) runs the Vosk small Indian-English model on the CPU, fully offline. Strategy is chosen at start-up and logged: (1) if the wake words are in the model's vocabulary, grammar mode with only those phrases plus `[unk]`; (2) otherwise free recognition on short windows, triggering when the heard text fuzzy-matches any configured variant (normalised edit distance ≤ 0.25) with confidence ≥ `wake_min_conf`. On detection: a short beep, Vosk stops and **releases the microphone**, then the normal report flow starts; listening resumes only when the report flow is back to idle. The two never hold the microphone at the same time.
- **Level B, strategy actually chosen on the QIDK:** the model's vocabulary was queried at start-up (`vosk_model_find_word`): "kalam", "qalam", "calum" and "kallam" are **not** known words; "hey", "column" and "calm" are. Free recognition was tried first and was poor for an unknown name (it heard "carla", "boulder"). So a third, middle strategy was added and is the one in use: **grammar mode on the sound-alike variants the model does know** (`["hey column","hey calm","[unk]"]`). In grammar modes only a final result with confidence ≥ `wake_min_conf` counts; the name on its own ("column", the soft "hey" is sometimes dropped) counts only when it is the whole utterance and the confidence is ≥ 0.93 (raised after room noise with nobody speaking was once decoded as "column" at 0.83 and started a report), so "the column near the lift is cracked" (decoded as `[unk] column [unk] …`) does not trigger. Order of preference in code: grammar on the name → grammar on sound-alikes → free recognition with fuzzy match.
- **Level B, verified on the QIDK:** with a synthetic Indian-English voice played from a laptop speaker about half a metre away: "Hey Kalam" detected with confidence 1.0 in 3 of about 4 attempts made while the listener was idle (small sample); two ordinary sentences containing "column", "calm" and "call" did not trigger. After detection the report flow recorded and Whisper transcribed normally (microphone handed over cleanly), listening resumed after the flow returned to idle, and from the home screen the app came to the front already listening. Not yet measured with several human speakers or in a noisy hall.
- **Level B, known limitations:** the trigger is a sound-alike, so a person who says exactly "hey column" or "hey calm" as a whole utterance will trigger it; the app must have been opened once after boot (Android only lets a microphone service start from the foreground); opening over another app needs the "display over other apps" permission (button in Settings; without it the wake word works while the app is on screen); listening costs CPU continuously, so there is a Settings toggle; the Vosk model adds about 54 MB to the APK.
- **Level B, code:** pure logic `android/domain/.../core/WakeWord.kt` (`WakeStrategy`, `WakeMatcher`, unit-tested); Android part `android/app/.../wake/WakeWord.kt` (`WakeEngine`, `WakeWordService`); model in `android/app/src/main/assets/model-en-in`. Still no INTERNET permission.
- **Config (`app_config.json`):** `wake_display` ("Hey Qalam"), `wake_variants` (spellings the recogniser may produce), `wake_min_conf` (0.6).
- **UI:** a Settings toggle, an on-screen "Listening for 'Hey Qalam'" indicator, and a debug readout of the last heard text for tuning the variants live.
- **Level A, how it is built:** all three entry points send one intent (`com.megathon.incidentvoice.REPORT`) to the single activity (`launchMode=singleTop`); the activity bumps a counter in the app container, the Report tab comes forward and starts the microphone through the normal permission-aware path. Tile: `TileService` using `startActivityAndCollapse(PendingIntent)` on Android 14+. Shortcut: static `res/xml/shortcuts.xml`. Widget: Jetpack Glance, one red REPORT button. With the flag off the tile and widget components are disabled and the intent only opens the app. Code: `android/app/.../quick/QuickLaunch.kt`.
- **Level A, verified on the QIDK:** from the home screen, the tile and the shortcut intent both bring the app up already in the listening state; the widget provider is registered with the launcher. If a report is already in progress a quick-launch request does not interrupt it.
- **Level A, known limitations:** the tile has to be added to the Quick Settings panel once by the user (edit tiles), and the widget placed once from the launcher's widget picker; that is how Android works.
- **30-second demo:** pull down Quick Settings → tap "Report incident" → the app opens already listening. Then, from the home screen, say "Hey Qalam" → beep → the report flow starts listening.

### Extra 3 — Multi-witness merging (flag `multi_witness`) — status: ON BOARD (2026-10-11)

- **What:** when a second person reports what looks like the same incident, the app offers to link the new report as a *witness account* of the existing case, shows where the accounts disagree, and lets a supervisor pick the trusted value.
- **Why:** real incidents are reported by several people with different details. Today that produces duplicate records with conflicting severities and times; nobody sees the conflict.
- **How (candidates):** on submit, look for reports with the same registry location (or fuzzy location ≥ 0.85), at least one category in common (primary or secondary), `occurred_at` within ±45 min, submitted in the last 12 h. Above a score threshold ask: "This looks like the same <category> at <location> reported <N> min ago (<ref>). Add your report as a witness account?" [Link] [Separate]. **Never merged automatically.**
- **How (data):** `incidents.parent_incident_id` (nullable, migration). Every account stays an intact record; the parent is the case.
- **How (merge rules, pure Kotlin `MergeEngine`):** severity = worst, conflict if accounts differ by ≥ 2 levels · YES/NO/UNKNOWN = YES beats NO, anything beats UNKNOWN, conflict on YES vs NO · `occurred_at` = earliest, shown as a range, conflict if > 15 min apart · counts = max, conflict if they differ · lists (persons, witnesses, actions taken, equipment) = union with fuzzy de-duplication, never a conflict · location = most-reported value, conflict if registry ids differ · description = all accounts side by side, never merged.
- **UI:** case detail shows a Field × Witness table with conflicting cells highlighted; tapping the trusted value records the decision in the audit log. The dashboard card shows "N accounts · M conflicts". Optional if time remains: a "Summarise accounts" button using the same on-device LLM, off the critical path, failing gracefully on the Mock engine.
- **As built:** pure logic in `android/domain/.../core/MultiWitness.kt` (`WitnessAccount`, `DuplicateFinder`, `MergeEngine`, 12 unit tests); orchestrator `proposeDuplicate` / `onLinkWitness` / `onKeepSeparate`; storage in `RoomIncidentRepository` (`linkWitness`, `caseOf`, `resolveConflict`, `conflictResolutions`); DB v4 (`incidents.parent_incident_id` + index, migration 3→4). Score = 0.4 × location + 0.3 × category (1.0 same primary, 0.6 shared secondary) + 0.3 × closeness in time; proposed only at ≥ `multi_witness.min_score`. Two different site-registry places never match, whatever the text says. Linking to an already linked account lands on the first report of the case. **The app never links on its own:** it shows the question with [Link] and [Separate], and dismissing the dialog means Separate.
- **Resolutions** are audit-log rows (`MERGE_RESOLVE`, field, previous choice, new choice, actor) on the first report of the case; the latest row per field wins, so a supervisor can change their mind and the history stays. Links are logged as `WITNESS_LINK` / `WITNESS_ADDED`. Deleting the first report unlinks the other accounts; they are kept.
- **"Summarise accounts"** is built from the merge result in plain language (who reported, what they agree on, where they differ, what was trusted). It does not call the language model, so it works the same on the Mock engine; an LLM-written summary was left out for time.
- **Verified on the QIDK:** the same water-leak report submitted twice by voice (injected recordings, real Whisper) → after the second submit the dialog "This looks like the same water leak / flooding near Conference Room 2 reported 2 min ago (INC-…-0006). Add your report as a witness account?" → Link → dashboard shows "2 accounts · 0 conflicts" on the case and "↳ witness account of INC-…-0006" on the new report. Second account edited to HIGH against LOW → "2 accounts · 1 conflict", Severity row amber → tapped LOW → "0 conflicts (1 resolved)", merged value "LOW (trusted)", audit row `MERGE_RESOLVE severity → LOW`. The existing board database migrated 3→4 without loss.
- **Known limitations:** only the best candidate is offered; category-specific fields (for example leak source) are not in the table, only the common ones; the trusted value is recorded for the case and shown as merged, it does not rewrite any witness's own report (deliberate); with no reporter name set the columns read "Witness 1", "Witness 2"; there is no un-link button (delete or re-submit instead); linked accounts still count as separate rows in the dashboard totals.
- **30-second demo:** submit the water-leak report → report it again as a second person ("…it is serious, water is still coming") → the app offers to link → open the case: severity and leak status are highlighted as conflicts → tap the trusted value → the audit log shows the decision.

## ML branch log — 2026-10-11 03:00–05:10 (export, fine-tune, apply.py)
- **Stock Llama 3.2 3B → V79 export (critical path):** hotspot S3 uploads are latency-bound (~30 kB/s per connection, aggregate scales) → qai_hub patched to many parallel parts; Part 1 (0.8 GB) + Part 2 (2.7 GB) uploaded at ~1.7–2.5 MB/s and are cached in AI Hub (`~/iv/state/hub_cache.json`, resume hook `ml/export/iv_resume_hub.py` keyed by part name). WSL crashed twice ("Catastrophic failure", during network/RAM stress); each time the supervisor relaunched the export and it reused the uploaded parts. WSL RAM raised 9 → 12 GB (`.wslconfig`) to cut swap thrash. Remaining: Part 3 conversion + upload, AI Hub compile/link, download → `publish_bundle` pushes `models_dist/llama_meta` → `python apply.py --llama` on any laptop.
- **Kaggle:** export on Kaggle impossible (31 GB RAM, no swap → OOM; TPU v3-8 retired). Fine-tune on Kaggle T4 blocked: the Kaggle account has not accepted the Llama 3.2 licence (model files 403). Tokens dataset deleted.
- **apply.py (repo root):** one-shot QIDK updater for the app laptop — pull, whisper/dsp (+ `--llama` bundle from AI Hub) → app models dir, build + `install -r`, restart. ASR domain-hint patch (`docs/proposals/asr_initial_prompt.patch`) applied to native, `libiv_native.so` rebuilt.
- **Prompt tuning (host, 4-bit 3B):** field F1 0.611 → 0.640, hallucination 35.5 → 28.2 % on the same 63 cases; full 150: F1 0.612, type acc 76 %. Extract prompt 2266 tokens (base); short `ft` prompt 694 tokens.
- **Fine-tune (PRD §27), unattended jobs `scripts/jobs/ft_*.sh`:** data `ml/data_gen/gen_ft_data.py` (regenerated 05:05 with **70 % English / 30 % Hinglish**, more CRITICAL cases and negated-danger sentences → `injuries: NO`, not CRITICAL — both feed the App's Extra 1 *Response clock* trigger), QLoRA on the RTX 4050 (starts when the stock export's Part 3 is uploaded), merge, gate (dev F1 ≥ base + 0.05), `build_ft_ckpt.py` (published w4a16 encodings + merged weights), ft export with its own resume cache. Extra 3's optional LLM summary not trained (latency; optional).
- **Latency levers:** ft prompt 694 vs 2266 tokens (≈ 3× less prefill); training omits absent fields (shorter outputs); ASR audio_ctx scaling 3.4 → 1.5 s.
- **ETA:** stock bundle ~06:15; fine-tuned bundle after 09:00 unless Kaggle training is unblocked (accept licence at kaggle.com/models/metaresearch/llama-3.2, then `rm ~/iv/state/ft_kaggle_push.done ~/iv/state/ft_kaggle_fetch.failed`).


## Design system (App branch, 2026-10-11)

The app is called **Qalam** ("kalam" = pen; Q for Qualcomm). No tagline anywhere. The look is calm and utilitarian: one accent, neutral surfaces, lists instead of cards, no decoration. This was a visual change only: no screen was added, removed or restructured, and no logic, state, database or engine code was touched.

**Logo.** Source files are in `branding/` (`qalam_icon`, `qalam_mark`, `qalam_mark_white`, `qalam_wordmark`; SVG, plus PNG for the icon and wordmark). The app uses VectorDrawables generated from those SVGs with the shapes unchanged (`res/drawable/ic_qalam_*.xml`). App icon: adaptive, white mark on solid `#E5484D`, scaled so the whole mark sits inside the 66 dp safe zone, plus a single-colour themed-icon layer in which the coral outline around the nib becomes a gap. Splash: the coral icon centred on the background colour, then the same icon with "Qalam" under it, one 200 ms fade.

**Colour.** One accent, coral `#E5484D` (pressed `#C93A3F`), used only for the report button, the selected tab, the primary button, "Required" and critical states.

| Role | Light | Dark |
|---|---|---|
| Background | `#F6F7F9` | `#0F1115` |
| Surface | `#FFFFFF` | `#181B21` |
| Divider / border | `#E5E7EB` | `#262A33` |
| Text | `#111827` | `#F3F4F6` |
| Secondary text | `#6B7280` | `#9CA3AF` |
| Accent | `#E5484D` | `#FF6B70` |

Severity is a small dot plus a word, never a filled pill: Low `#16A34A`, Medium `#D97706`, High `#EA580C`, Critical `#B91C1C`. The colour is only in the dot, so the word always has full contrast. Emergency banner and response clock: flat `#C62828`, white text, plain white buttons.

**Type.** Inter for everything, JetBrains Mono only for reference numbers, timings and the proof line. Both are bundled in `res/font` (SIL OFL, licence texts in `assets/licenses`); nothing is downloaded at run time. Hierarchy comes from size and weight, not colour: screen title 22 sp semibold · section header 13 sp medium, secondary grey, sentence case · body 15 sp · caption 13 sp · mono 13 sp (12 sp for the proof line).

**Shape and spacing.** 12 dp radius on cards, 10 dp on buttons, fields and chips. Surfaces are flat with a 1 dp border or divider; no drop shadows. 16 dp screen padding, 8 dp spacing grid. Text and content are left-aligned; only the report button is centred.

**Components** (`android/app/.../ui/components`, tokens in `ui/theme/Theme.kt`):
- `QalamTopBar`: mark + "Qalam" on the left; "On-device" with a dot (green when the engine is ready, grey otherwise) and the settings icon on the right.
- `MicButton`: solid coral circle with a mic and one thin ring that follows the voice level while listening; static when idle. Caption "Tap to report · or say 'Hey Qalam'" (the second half only when the wake word is enabled).
- `PrimaryButton`: solid coral, full width, 52 dp, at most one per screen. `SecondaryButton`: outlined. `PlainButton`: text only, in the text colour.
- `FlatCard` + `QDivider`: a bordered white group; repeated items are rows inside it separated by dividers, not one card per row.
- `IncidentRow`: severity dot + title, a secondary line (location · relative time), the reference number in mono, a chevron.
- Form and review fields: label (secondary) above value (primary); "Required" in coral for an empty mandatory field; "Check" in amber for an inferred or assumed value; an edit icon on the right.
- `Stat`: a big semibold number with a small caption underneath.
- `SectionHeader`, `ScreenTitle`, `SeverityLabel`, `StatusText`, `BusyRow`, `EmergencyBannerView`, `ResponseClockCard`.
- `ProofLine` (bottom of Home and Conversation, mono 12 sp): `Hexagon NPU · <tok/s> tok/s · <N> B sent`, or `CPU · <engine> · <N> B sent` when the language model is not on the NPU. Bytes come from `TrafficStats.getUidTxBytes(myUid)`; the app has no INTERNET permission.
- Icons: Material outlined set, one weight, no tinted circles behind them.

**Rules.**
- No gradients, glows, glass effects, neon or purple palettes; no sparkle or robot imagery; no emoji in the UI.
- The only moving element is the ring of the report button while listening. State changes animate for at most 200 ms; the system "remove animations" setting is honoured.
- Touch targets are at least 48 dp (main actions 52 dp and above). Text contrast is at least 4.5:1; white on coral is used only at 19 sp semibold (large-text rule).
- Copy is plain and specific ("Report incident", "Needs details", "Saved on device", "Ask about reports"); empty states are one sentence plus one action.
- Screenshots of every screen taken on the QIDK are in `shots/`.

**Known gaps.** The dark theme is defined but was only checked in light mode on the board. The wide witness table still scrolls sideways on a phone-width screen. The Settings tab stays in the bottom bar and the top-bar settings icon opens the same screen.


## ASR benchmark (App branch, 2026-10-11)

Full table, method and caveats: `eval/asr_benchmark.md` (+ `eval/asr_benchmark.csv`). Report only; the app was not changed.

- **Sample:** 39 clips — 15 scripted English, 15 scripted Hinglish, 9 live English recordings from the board microphone (their references were reconstructed from the demo sentences). Small sample; read the numbers as "clearly better / clearly worse", not to the decimal.
- **Scripted English WER, no hint:** tiny.en 10.3 %, base.en 6.8 %, small q5_1 (the shipped model) 4.4 %. **With the domain hint:** 8.2 % / 4.9 % / 2.8 %.
- **Live English WER (noisy, about 11 dB):** no hint 27.9 % / 14.0 % / 9.3 %; with hint 10.5 % / 5.8 % / 2.3 %. The hint was tuned after seeing errors on some of these clips, so this column is optimistic.
- **Speed on the QIDK, 10.2 s clip, median of 5:** tiny.en 253 ms, base.en 411 ms, small q5_1 1815 ms. The hint costs nothing measurable.
- **Conclusion:** the shipped choice (small + hint) is the most accurate of the three and still runs about 5.6 times faster than real time. base.en + hint is the fallback if latency ever matters more than accuracy.
- **Hinglish:** 91–111 % WER in every configuration. Literal Hinglish transcription does not work with these models; this is a known limitation, not something the hint fixes.


## Emergency: questions are cut to the minimum (App branch, 2026-10-11)

- **Before:** in an emergency the follow-up loop still asked for every missing common field (severity, injuries, time, location) and only left the category details for review, and it did so only when severity was already CRITICAL.
- **Now:** an emergency is any of: severity CRITICAL, the red banner, or a running response clock (which the keyword banner starts). In that state the app asks **one** question at most, where it is, and only if the place is missing. Whatever the answer, it then goes to review, where the response clock and Call ERT are pinned on top and every other required field is listed as "Required". Submit stays disabled until those are filled or marked unknown.
- No new model fields, prompts or config. Change is in `ConversationOrchestrator.validateAndAdvance` / `ask`; two unit tests (`emergency_locationKnown_…`, `emergency_locationMissing_…`).
- **20-second demo:** Type instead → "There is a fire in the server room and someone is unconscious" → no questions; the review screen opens with the clock running and "Call ERT" on top.


## Small talk and "no incident" (App branch, 2026-10-11)

- **What:** if what was said is not an incident ("hi wassup this is Ravi", a mic check, a joke, an off-topic question, a sentence that was cut off), the form is **not** filled. The app says and shows "I didn't catch an incident. What happened?" (Hinglish: "Mujhe koi incident samajh nahi aaya. Kya hua tha?") and listens again; when the report was typed it waits for typing instead.
- **Self-introduction:** "this is X", "my name is X", "X here", "main X bol raha hoon", "mera naam X hai" set the reporter name for this report and nothing else. The device profile is filled with it only if it had no name yet. The name is also picked up when it comes with a real report ("this is Ravi, there is a fire in bay 4").
- **How it decides (deterministic, `core/SmallTalk.kt`):** not an incident when the words contain no category or emergency keyword and no general "something went wrong" word (happened, broke, leak, hurt, stuck, hua, gir gaya …), or when the speaker says so ("nothing happened, just testing"). The same path is taken if the model returns neither a type nor a description. Safety valve so the rule can never block a real report: a second attempt of six words or more is filed as it is. After three empty tries the app goes back to the start with "No incident was reported".
- **What is kept:** the words are still stored as a turn of the draft; they are not used for the description or for grounding.
- **Tests:** `SmallTalkTest` (12 messy inputs that must not be filed, 8 messy real reports that must, name extraction), two orchestrator tests.
- **Also fixed here:** a report with a running response clock that the reporter closed is no longer reopened at the next launch.
- **Known limits:** keyword-based, so a real incident described with none of the known words gets one "What happened?" before it is accepted; a joke that contains an incident word ("my code is on fire") is filed.
- **20-second demo:** Type instead → "hi wassup this is Ravi" → the reply is "Hi Ravi. I didn't catch an incident. What happened?" and the form stays empty → type the water-leak sentence → the report is filled, reporter "Ravi".


## Error handling: every case and what the user sees (App branch, 2026-10-11)

Rule: a failure never loses the report and never leaves a dead end. The draft (form, every turn, the recording path) is written to the database after each step and again when an error is raised.

| # | What goes wrong | What the app does | What the user sees or hears |
|---|---|---|---|
| 1 | Language model fails on a turn (NPU busy, session lost, empty output) | Retries after 3, 7 and 15 s if the error is retryable. If it still fails, or is not retryable, the keyword rules engine (`RuleBasedLlm`) produces the same tool call **for that one turn**; the next turn tries the model again | "Engine busy — retrying (n/3)…" while retrying, then a small plain line "Using offline fallback". It disappears when the model answers again |
| 2 | Model output cannot be parsed (extraction) | One repair request to the model. If that fails too: the transcript becomes the description and the form opens for manual filling | "Couldn't understand fully — please check the form." and the review screen with the gaps marked "Required" |
| 3 | Model output cannot be parsed (a follow-up answer) | One repair request; if that fails the answer is kept in the transcript, the fields stay missing and the loop moves on | "Couldn't read that answer" in the timeline; the field is asked again or left for review |
| 4 | No speech at the start | Says it once and listens once more; a second silence returns to the start | Spoken and shown: "Sorry, I didn't catch that." Then: "Sorry, I didn't catch that. Tap the button and try again." |
| 5 | No speech after a question | Repeats the question once with the same phrase; a second silence counts as skipped | "Sorry, I didn't catch that. <question>" |
| 6 | Speech with no recognisable words | Treated like no speech (4 or 5) | "I couldn't make out any words. Please try again." at the start |
| 7 | Not an incident (small talk, joke, cut-off sentence) | Form untouched; asks again, up to three times | "I didn't catch an incident. What happened?" |
| 8 | Speech recognition fails (engine error on transcribe) | Same retries as 1; there is no fallback for speech, so the error card is shown | Error card with Retry, Fill manually, and Type instead |
| 9 | Microphone unavailable or permission denied | Error card | "Try mic again", "Open settings", "Fill manually" |
| 10 | Text-to-speech not ready | A short chime replaces the voice; the question stays on screen and the microphone still opens | The question as text |
| 11 | Follow-up limit reached (3 rounds) or nothing left that can be asked | Goes to manual fill | "Please check the highlighted fields on the screen." |
| 12 | Required fields still empty at submit | Submit is refused | "Fill the highlighted fields first: …"; each field can also be marked Unknown |
| 13 | Engine fails to load at start | App stays usable for typing and manual filling | Home shows the error with "Retry engine" and "Use mock engine"; the top-bar dot is grey |
| 14 | App killed during an emergency | The draft and its response clock are picked up at the next launch (within an hour), unless the reporter had closed it | "Picked up the report that was in progress." |
| 15 | No dialler on the device | The call button does not crash | Toast with the number to call from a phone |
| 16 | Device clock wrong | Relative times would be wrong | Red strip on Home: "Device clock is wrong." |

Changed for this task: case 1 (the rules engine now takes over automatically; before, the error card appeared and the user had to choose), case 4 (spoken once and one more try; before, straight back to the start), the draft is saved explicitly on every error path, and status notices are shown in plain text instead of red. Cases 2, 3, 5–7 and 9–16 already behaved this way. Tests: `modelFailure_usesRulesForThatTurn_…`, `modelFailure_onAnAnswer_alsoFallsBack`, `silenceAtTheStart_…`.

**20-second demo:** tap the report button and stay silent for about ten seconds → "Sorry, I didn't catch that." is spoken once and it listens again; stay silent again → back to the start with the notice. The model-failure fallback cannot be triggered by hand on the board; it is covered by the unit tests.


## Language matching and the reporter's own words (App branch, 2026-10-11)

- **Follow-up language:** the language of the questions now follows the reporter's own words, not the model's label. After the narration the app runs `LangDetector.ofSpeech` on the text (Hinglish marker words such as hai, tha, nahi, ke, mein, paani; or Devanagari) and uses the Hinglish question templates from `taxonomy.json` when it is Hinglish, the English ones otherwise. A later answer of three words or more can switch the language; "yes" or "haan" does not.
- **Description:** the model may tidy the sentence, but if less than 70 % of the words in its description were actually said, the description is replaced by the narration with light cleanup only (`DescriptionStyle`: fillers and a leading greeting removed, spacing, capital letter, full stop). A description the reporter edited by hand is never touched.
- **Verbatim transcript:** every turn is stored exactly as heard (`turns.text_raw`); a correction made on the "Heard this" screen is stored next to it, not over it. The detail screen shows "Transcript, word for word" with the heard text and, if there was one, the correction.
- **Limit that matters:** speech recognition runs with English forced (the benchmark shows literal Hinglish transcription does not work with these models), so *spoken* Hinglish mostly arrives as English text and gets English questions. Hinglish questions appear when the text itself is Hinglish: typed reports, or the Hindi words Whisper happens to keep. The marker-word list is short and can misjudge a very short sentence.
- **Tests:** `followUps_matchTheReportersLanguage_andTheTranscriptIsKeptVerbatim`, `language_followsTheReportersWords`, `description_lightCleanupOnly`, `description_keepsTheReportersWords_unlessTheModelStayedClose`.
- **20-second demo:** Type instead → "Pantry ke paas paani leak ho raha hai" → the next question is in Hinglish and the description keeps "paani leak ho raha hai". Open any report → "Transcript, word for word".
### 2026-10-11 06:40 — ML: NO_INCIDENT sentinel (small-talk rejection)
- Small talk / mic tests / noise now extract to `{"incident_type":"OTHER","description":"NO_INCIDENT","lang":..}` (prompt rule in base + ft prompts; parser/validator/scorer aware; eval `eval/smalltalk_cases.jsonl`); App must not draft on it (CHANGE.md).
- FT data now has +200 NO_INCIDENT (70/30 en/hinglish) + 150 mixed samples, but the v2 Kaggle continuation run was CANCELLED by user decision; v1 adapter ships, small talk relies on the prompt rule. v2 tooling (continue-from-adapter in train_lora.py, versioned fetch) kept for later.
- Supervisor: ft_export waits until ft_kaggle_fetch is done/failed (already done → v1 proceeds); optional `~/iv/state/ft_v2_deadline` guard (not set).

## One-tap entry points and wake word: re-check after the restyle (App branch, 2026-10-11 06:45)

Checked on the QIDK with the current build, starting from the launcher each time:

| Entry point | App not running | App in the background | Result |
|---|---|---|---|
| Quick Settings tile "Report incident" | opens and is listening after the cold start | opens listening | works |
| App shortcut (long-press the icon → Report incident), same intent as the widget | opens listening | opens listening | works |
| Home-screen widget (one REPORT button) | one widget is placed on the board; its button sends the same intent as the shortcut | | not tapped by hand in this check |

If a report is already in progress, a quick-launch request does not interrupt it (by design). No fix was needed.

**"Hey Qalam"** was already implemented before this check (Extra 2, Level B above) and is still running: the foreground service is up and Settings shows "Listening · grammar on sound-alikes". Its hit rate could **not** be re-measured in this session because the test relies on the laptop speaker, which was muted; the last measurement (about 3 of 4 with a synthetic voice) predates the stricter rule for the name said without "hey". It needs a few tries by a person before the demo.


### Small talk: aligned with the ML branch's NO_INCIDENT sentinel (App branch, 2026-10-11 06:50)

- The model now answers small talk with `{"incident_type":"OTHER","description":"NO_INCIDENT"}` (CHANGE.md, ML entry of 06:40). The app treats that description (case-insensitive) as "nothing to file": the form is cleared, "I didn't catch an incident. What happened?" is said and shown, and it listens again. The sentinel is never shown as a description, not even while it streams in. In a follow-up answer it means "no new information".
- The stand-in rules engine returns the same sentinel when the words carry no incident.
- The app's own keyword check still runs first, so small talk is caught even when the model is unavailable.
- After three empty tries the empty draft is deleted, so nothing is left in the database.
- **Measured on the ML branch's sets with the app's keyword check alone:** 15 of 15 small-talk cases (`eval/smalltalk_cases.jsonl`) rejected; 0 of 150 real incident cases (`eval/text_cases.jsonl`) rejected. One Hinglish case ("lunch ka time ho gaya kya") was first let through; the general verbs "hua / ho gaya" were removed from the incident-word list to fix it. The check is now an assertion in `SmallTalkTest`. These are the same 165 sentences the rule was adjusted on, so this is a regression guard, not an accuracy estimate.
- Wording: the app says "I didn't catch an incident. What happened?" / "Mujhe koi incident samajh nahi aaya. Kya hua tha?" (the ML note suggests a slightly longer sentence; the shorter one was specified for the app).


## On-device LLM: Qwen3-4B on the Hexagon NPU (App branch, 2026-10-11)

**What runs now.** The language model in the app is Qualcomm's pre-compiled **Qwen3-4B** (Genie runtime, w4a16 quantisation, 4096-token context), running on the QIDK's Hexagon NPU inside the app process. It replaces the rules stand-in whenever the bundle is on the device: Settings → Engine → Auto prefers the NPU model when `models/llama/genie_config.json` exists, and falls back to the rules engine otherwise. The rules engine also answers a single turn if the model fails, times out (20 s) or returns something that cannot be parsed twice; the screen then shows a "Fallback" chip.

**Where the model comes from.** Qualcomm's model page https://huggingface.co/qualcomm/Qwen3-4B → table "Download Pre-Exported Models" → row GENIE / w4a16 / "Snapdragon 8 Elite For Galaxy Mobile" (QAIRT 2.45). Licence of the model card: Apache-2.0. The file is `qwen3_4b-genie-w4a16-qualcomm_snapdragon_8_elite_for_galaxy.zip` (2.5 GB; four context binaries `part1_of_4.bin` … `part4_of_4.bin`, 3.2 GB unpacked). `htp_backend_ext_config.json` in it targets `soc_model 69`, `dsp_arch v79`, which is the QIDK's SM8750 / Hexagon V79. The bundle was compiled with QAIRT 2.45; the board runs it with the QAIRT 2.50 libraries already in use (Genie 1.20).

**Weights are not in the repository** (`*.bin` and `iv_exports/` are in `.gitignore`). To reproduce:

```
mkdir -p ~/iv_exports/qwen3_4b && cd ~/iv_exports/qwen3_4b
curl -L -O https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/qwen3_4b/releases/v0.64.0/qwen3_4b-genie-w4a16-qualcomm_snapdragon_8_elite_for_galaxy.zip
unzip qwen3_4b-genie-w4a16-qualcomm_snapdragon_8_elite_for_galaxy.zip && cd qwen3_4b-genie-w4a16-qualcomm_snapdragon_8_elite_for_galaxy
# greedy decoding: in genie_config.json set dialog.sampler to {"version":1,"seed":42,"temp":1.0,"top-k":1,"top-p":1.0}; leave context.size at 4096
# (the app's engine applies the same sampler itself when it loads the bundle)
echo '{"family":"qwen","name":"Qwen3-4B"}' > model.json
M=/sdcard/Android/data/com.megathon.incidentvoice/files/models/llama        # the folder the app's Genie engine reads
adb shell am force-stop com.megathon.incidentvoice
for f in genie_config.json htp_backend_ext_config.json tokenizer.json model.json part1_of_4.bin part2_of_4.bin part3_of_4.bin part4_of_4.bin; do adb push $f $M/$f; done
```

Smoke test from the shell before starting the app (uses the QAIRT tools the ML branch keeps in `/data/local/tmp/iv`):

```
adb push smoke_prompt.txt /data/local/tmp/qwen_smoke_prompt.txt
adb shell "cd $M && export LD_LIBRARY_PATH=/data/local/tmp/iv/lib && export ADSP_LIBRARY_PATH='/data/local/tmp/iv/dsp;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp' && /data/local/tmp/iv/bin/genie-t2t-run -c genie_config.json --prompt_file /data/local/tmp/qwen_smoke_prompt.txt"
# timings as JSON: adb shell "/data/local/tmp/iv/run.sh llm --prompt-file /data/local/tmp/qwen_smoke_prompt.txt --max-tokens 200 --models /sdcard/Android/data/com.megathon.incidentvoice/files/models"
```

**Smoke-test result on the board** (prompt: a one-line system message asking for one JSON object, the water-leak sentence, empty think block):

```
{"name":"fill_incident_form","arguments":{"incident_type":"water_leak","location":"Conference Room 2","time":"10 AM","action_taken":"maintenance_isolated_area"}}
```
Valid JSON, no `<think>` text. Decode speed 23.0 tok/s, time to first token 69 ms, 1.7 s in total for 38 tokens. With the app's real extraction prompt (about 800 tokens): time to first token about 0.5 s, 22–23 tok/s, 3.7 s for 73 tokens. Model load: 3.4 s.

**Chat template (selectable by model family).** `ModelFamily` (`core/PromptBuilder.kt`) is detected from the bundle folder: a `model.json` that says "qwen", the word "qwen" in `genie_config.json`, or Qwen's end-of-turn token id (`"eos-token": 151645`); anything else is treated as Llama and that path is unchanged (its golden-prompt tests still pass).
- Qwen messages are wrapped as `<|im_start|>role\n…<|im_end|>\n`; no BOS token is added.
- Every assistant prefix ends with `<|im_start|>assistant\n<think>\n\n</think>\n\n`. The empty think block switches Qwen3's thinking mode off, so the answer starts immediately.
- Generation stops on `<|im_end|>` (the bundle's EOS); the app also cuts the text at `<|im_end|>` and removes any `<think>…</think>` block before parsing, then takes the first balanced `{…}`.
- Extraction uses a compact prompt written for Qwen, `config/prompts/extract.system.qwen.txt` (about 3,800 characters, roughly 950 tokens; the target was 800) with the categories one per line, a short Hinglish glossary for time words, and two format examples inside it (an English fire and a Hinglish fall). Follow-up (patch) and repair prompts are the existing ones in the Qwen wrapper. Tool schema, validator, grounding, follow-up logic and the NO_INCIDENT contract are unchanged.
- **Keyword hint.** The user message ends with `Keyword hint for incident_type: <CODE>` when the keyword rules recognise a category; the prompt tells the model to use it unless the transcript clearly says otherwise. Without the hint the first version of the prompt got the incident type right in 53 % of the eval cases; see `eval/RESULTS.md`.
- **Evidence checks on the model's output (`EvidenceGuard`, deterministic).** The model tends to fill fields from habit, so before its answer reaches the form: "injuries", "injured_count" and "severity" are kept only if the words themselves say so (the keyword rules find them too, or the emergency keywords fire); a location none of whose words were spoken and a time when no time was mentioned are dropped; life-at-risk wording (unconscious, not breathing, fire spreading, trapped) forces CRITICAL. Dropped fields are simply asked for.
- **Self-correction.** "at 10, no, 11" is rewritten to "at 11" before the text goes to the model (`SelfCorrection`); the stored transcript keeps the words as spoken.

**Two things that had to be fixed to make it run in the app.**
1. *Crash on load.* The first in-app load crashed inside `GenieDialog_create` (null pointer in `libGenie.so`). The bundle's `genie_config.json` names a backend extension file, and reading it needs `libQnnHtpNetRunExtensions.so`, which the shell tools had and the APK did not. The QAIRT 2.50 copy from the board is now packaged in `android/app/src/main/jniLibs/arm64-v8a/`. (No model had been loaded in-app on the NPU before this, so the gap had never shown.)
2. *Session breaks after a long prompt.* With this bundle and Genie 1.20, once a prompt goes past roughly 500 tokens (310 was fine, 460 was not), every later query in the same Genie session fails with `GenieDialog_query status=-1` ("batch dispatch failed"), even after `GenieDialog_reset`. Short prompts can be repeated without limit. The native library could not be rebuilt on the App laptop, so the app works around it in `MlEngineAdapter`: after every model call it re-creates the session in the background (3.4 s), while the question is spoken and the reporter answers; the next call waits for that reload if it is still running. Still one NPU session at a time.

**Limits and behaviour to know.**
- The reload after each call also reloads Whisper and briefly shows "Loading models" on Home. If the reporter answers within about 3 s of a question, that answer waits for the reload.
- If a reload fails, the next turn is answered by the rules engine (fallback chip), not by the model.
- A call that takes longer than 20 s is aborted and answered by the rules engine.
- Category detail fields (for example leak source) are not in the compact extraction prompt; they are collected by the follow-up questions.
- The folder is still called `models/llama` (that name is fixed in the native engine and `app_config.json`), and the engine info line in Settings still reports the native default model name; the proof line and the "Active" row show the detected family.
- Only the extraction step was measured on the eval set; follow-up answers were checked by hand on the board.
- Known wrong behaviour, from the on-board cases (`eval/RESULTS.md` section 2): a second incident in the same sentence is not captured; a long rambling forklift report and "fire is spreading, someone is unconscious" were both typed MEDICAL; any mention of fire starts the emergency clock, including a small controlled one.

**Stretch (not done): base versus fine-tuned comparison.** If the fine-tuned model arrives as merged weights, do not start an NPU export. Convert it to GGUF Q4_0 and run it with llama.cpp on the CPU next to the base model for a base-versus-fine-tuned table on the same eval set.

**Measured (details and caveats in `eval/RESULTS.md`).**
- Speed on the QIDK: 22–23 tok/s; time to first token 0.5–0.6 s with the app's prompt; extraction 4.4 s median (5.8 s p90) and a follow-up answer 1.8 s median inside the app; model load 3.4 s.
- Text eval, 150 cases, first prompt, raw parsed output: incident type 53.3 % for Qwen against 96.0 % for the rules engine (which was tuned on this set); field F1 0.39 against 0.73; valid tool calls 98 % against 100 %; made-up fields 26 % against 2 %.
- After the prompt rewrite and the keyword hint, on a hand-picked 30-case subset: incident type 30 of 30 (11 of 30 before). The full set was not re-run.
- On-board cases: 6 pass, 3 partial, 1 fail. Soak: 18 consecutive reports, no crash, latency flat, 42–49 °C.
- Unit tests: 404 of 404 pass (`:core` 190, `:domain`, `:app`; two of them are the eval tool, which does nothing without its environment variables).

**Honest summary.** The model runs reliably on the NPU and the pipeline around it works, but on this eval set the rules engine is still the more accurate extractor; Qwen is the default because the bundle is present and Auto prefers it, with the rules engine giving the category hint, checking the model's claims and taking over a turn when the model fails. To make the rules engine the default instead, choose Settings → Engine → Mock, or remove `genie_config.json` from the model folder.
