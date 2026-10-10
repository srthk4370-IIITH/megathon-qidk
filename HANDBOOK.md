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

### Extra 1 — Response clock (flag `response_clock`) — status: PLANNED

- **What:** when an incident looks like an emergency, a pinned card appears at the top of the conversation and review screens with a large `mm:ss` timer counting up from the moment of detection and the category's *immediate* steps as large tap targets.
- **Why:** in the first minutes of a fire, injury or gas leak the reporter should be acting, not filling a form. The form keeps filling in the background; the card keeps the next physical step and the elapsed time in front of them, and records what was done and when without any typing.
- **Trigger:** the emergency banner fired (keyword hit or severity CRITICAL), or the category is FIRE, MEDICAL or HAZMAT, or ELECTRICAL with `electrical_hazard = SHOCK`.
- **How:** steps are the `immediate: true` entries of the category in `config/sop.json` plus a fixed "Call ERT" step. Tapping a step writes a `response_actions` row (`id, incident_id, step_id, label, done_at, source = TAP`) and appends the label to the incident's `actions_taken`. "Call ERT" opens the dialler (`ACTION_DIAL`) with the configured number and logs the time automatically. If "Call ERT" has not been done after 120 s (`response_clock.ert_reminder_s`), the card pulses amber and one short reminder is spoken. The start time is stored with the incident so the clock survives an app restart. The clock never blocks reporting; both run in parallel. The detail screen shows the timeline ("Alarm 00:00 → ERT 01:12 → Evacuated 03:40").
- **Always shown:** "Follow your site's emergency procedures. This checklist is guidance, not medical advice."
- **Data:** new table `response_actions`; new column `incidents.response_started_at` (migration).
- **30-second demo:** say "There is a fire spreading in the server room and someone is unconscious" → the card appears with the timer running → tap "Call ERT" (dialler opens, time logged) → tap "Evacuate" → open the saved report: the timeline shows each step with its offset.

### Extra 2 — One-tap launch and "Hey Qalam" wake word (flags `quick_launch`, `wake_word`) — status: PLANNED

- **What (Level A, `quick_launch`):** three ways to start a report without opening the app: a Quick Settings tile "Report incident", a long-press app shortcut, and a home-screen widget with one large red REPORT button. Each opens the conversation screen with the microphone already listening.
- **What (Level B, `wake_word`):** the assistant is called **Qalam** (a play on "kalam", pen; Q for Qualcomm). Saying "Hey Qalam" starts a report hands-free.
- **Why:** a person reporting an incident may have gloves on, dirty hands or a casualty in front of them. Every tap removed shortens the time to a usable report.
- **How (Level B):** a foreground service (type `microphone`) runs the Vosk small Indian-English model on the CPU, fully offline. Strategy is chosen at start-up and logged: (1) if the wake words are in the model's vocabulary, grammar mode with only those phrases plus `[unk]`; (2) otherwise free recognition on short windows, triggering when the heard text fuzzy-matches any configured variant (normalised edit distance ≤ 0.25) with confidence ≥ `wake_min_conf`. On detection: a short beep, Vosk stops and **releases the microphone**, then the normal report flow starts; listening resumes only when the report flow is back to idle. The two never hold the microphone at the same time.
- **Config (`app_config.json`):** `wake_display` ("Hey Qalam"), `wake_variants` (spellings the recogniser may produce), `wake_min_conf` (0.6).
- **UI:** a Settings toggle, an on-screen "Listening for 'Hey Qalam'" indicator, and a debug readout of the last heard text for tuning the variants live.
- **30-second demo:** pull down Quick Settings → tap "Report incident" → the app opens already listening. Then, from the home screen, say "Hey Qalam" → beep → the report flow starts listening.

### Extra 3 — Multi-witness merging (flag `multi_witness`) — status: PLANNED

- **What:** when a second person reports what looks like the same incident, the app offers to link the new report as a *witness account* of the existing case, shows where the accounts disagree, and lets a supervisor pick the trusted value.
- **Why:** real incidents are reported by several people with different details. Today that produces duplicate records with conflicting severities and times; nobody sees the conflict.
- **How (candidates):** on submit, look for reports with the same registry location (or fuzzy location ≥ 0.85), at least one category in common (primary or secondary), `occurred_at` within ±45 min, submitted in the last 12 h. Above a score threshold ask: "This looks like the same <category> at <location> reported <N> min ago (<ref>). Add your report as a witness account?" [Link] [Separate]. **Never merged automatically.**
- **How (data):** `incidents.parent_incident_id` (nullable, migration). Every account stays an intact record; the parent is the case.
- **How (merge rules, pure Kotlin `MergeEngine`):** severity = worst, conflict if accounts differ by ≥ 2 levels · YES/NO/UNKNOWN = YES beats NO, anything beats UNKNOWN, conflict on YES vs NO · `occurred_at` = earliest, shown as a range, conflict if > 15 min apart · counts = max, conflict if they differ · lists (persons, witnesses, actions taken, equipment) = union with fuzzy de-duplication, never a conflict · location = most-reported value, conflict if registry ids differ · description = all accounts side by side, never merged.
- **UI:** case detail shows a Field × Witness table with conflicting cells highlighted; tapping the trusted value records the decision in the audit log. The dashboard card shows "N accounts · M conflicts". Optional if time remains: a "Summarise accounts" button using the same on-device LLM, off the critical path, failing gracefully on the Mock engine.
- **30-second demo:** submit the water-leak report → report it again as a second person ("…it is serious, water is still coming") → the app offers to link → open the case: severity and leak status are highlighted as conflicts → tap the trusted value → the audit log shows the decision.
