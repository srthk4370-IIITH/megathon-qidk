# HANDBOOK — IncidentVoice (Megathon X × Qualcomm)

Running log of what happened, what was decided, and where things stand. `PRD.md` is the spec; this file is the history and the current status. Newest entries at the top of §2.

## 1. Current state (2026-10-10)

| Area | Status |
|---|---|
| Spec | `PRD.md` **v1.1** (rev table §4.0 overrides v1.0). `M1.md` 2-page approach summary. `RA.txt` SRS corrected. |
| Board | SM8750P (Snapdragon 8 Elite, HTP V79), Android 15 userdebug, 15.5 GB RAM. `/data/local/tmp` wiped → 29 GB free. Clock **fixed** 21:03 IST, timezone Asia/Kolkata. |
| Laptop | VHDX compaction done by user → **C: 132 GB free**. QAIRT 2.50 downloading to WSL `~/qairt/` (public URL). |
| Models | **Llama 3.2 3B → V79 Genie export running twice:** laptop WSL (`~/iv_exports/llama_stock`, log `~/iv_exports/llama_export.log`, started 21:03) and Kaggle TPU kernel `srthk4370/iv-llama-v79-export`. Whisper: whisper.cpp CPU for P0 (not started). |
| Code | None yet. Repo has docs + `qidk/` reference + `_board_runtime_backup/` (QAIRT 2.47 V79 runtime pulled from the board). |
| Architecture | Plan A (in-app JNI) vs Plan B (shell-user server) undecided until the probe APK runs (PRD §26.6, ~H3). |

## 2. Log

### 2026-10-10 ~21:00 — External review, QAIRT blocker, exports started (rev 1.1)

**QAIRT 2.50 blocker solved:** QPM download failed for the team. Found Qualcomm's public no-login URL pattern in `qidk/Tools/qairt_docker/dockerfile` (used there for 2.47). Probed builds with HTTP range requests: `2.50.0.260828` → 206 (public, 2,601,473,189 bytes); `2.50.0.260828221209`, `2.45.0.260326154327`, `2.49.0.260731` → 404; `2.45.0.260326` → 206. Downloading 2.50.0.260828 into WSL `~/qairt/`. Note: AI Hub compile itself never needed the SDK; only device libs + Genie headers do.

**Exports started (none had been started before):**
- Laptop WSL: `qai-hub-models export llama_v3_2_3b_instruct --device "Samsung Galaxy S25 (Family)" --target-runtime genie --skip-profiling` (21:03). Memory warning (80 GB recommended, 72 GB RAM+swap) — expected. **Risk noted:** laptop venv has transformers 4.56.2 while the recipe pins 4.45.0; if published encodings don't match the exported graph, this run fails → Kaggle run is the clean-pin hedge.
- Kaggle: token saved locally only (`~/.kaggle/access_token`, chmod 600; Kaggle CLI 2.2.4 in `~/venvs/kaggle`, user `srthk4370`). Private dataset `srthk4370/iv-creds` (HF + AI Hub tokens, **delete after export**). Private kernel `srthk4370/iv-llama-v79-export` on a **TPU VM** (more RAM than 30 GB CPU sessions), installs `qai-hub-models[llama-v3-2-3b-instruct]==0.63.0` with clean pins, zips bundle to `/kaggle/working`. Tokens never printed.
- WSL quirk: `wsl -- bash -lc '...$VAR...'` from Git Bash loses `$VAR` → write a script file and run `wsl -- bash /mnt/c/.../script.sh`.

**Board clock fixed:** `adb root; setprop persist.sys.timezone Asia/Kolkata; date <laptop time>; adb unroot` → `Sat Oct 10 21:03:47 IST 2026`.

**ASR-LLM-TTS sample analysis:** its Genie JNI (`native-lib.cpp`) is real and reusable (config from JSON → dialog create → setMaxNumTokens → query/reset, `setenv ADSP_LIBRARY_PATH` to nativeLibraryDir). Its Whisper uses a closed QPM jar (`com.qualcomm.qti.voice.assist.whisper.sdk`) → **not copyable**; reviewer's assumption corrected.

**Decisions (AskUserQuestion), recorded as PRD rev 1.1 (§4.0 R1-01…R1-14):** whisper.cpp CPU in P0 + ORT-QNN NPU upgrade; keep App + ML split; accept P0 cut but keep Silero VAD + Hinglish templates; mandatory fields unchanged; time defaults to now/approx; recommendations = SOP templates in P0; Room @Fts4; template-generated eval + 30 clips; all fine-tuning cut; M1.md written.

### 2026-10-10 (later) — Branch workflow + VHDX compaction error

**Branch workflow (from user + updated CLAUDE.md):** all ML work commits go to `Saarthak`; a separate branch owns the Android app (including the dashboard). Main carries `HANDBOOK.md`, `CHANGE.md` (cross-branch change announcements), `STATUS.md` (what exists now); each branch keeps `handover_<branch>.md`. Created `CHANGE.md`, `STATUS.md`, `handover_Saarthak.md`. PRD v1.0 was pushed to `main` and `Saarthak` as `8ff065e`.

**Dashboard decision:** user confirmed the dashboard is **app-only** (PRD D-44 stands). Only two branches: App and ML. Logged in CHANGE.md.

**Git rule (user):** local issues and history (laptop, board housekeeping, HANDBOOK) are pushed to `Saarthak` only; `main` receives only the shared coordination files `CHANGE.md`, `STATUS.md` and handover files.

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

## App branch (`renny`) log — 2026-10-10 night

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

## App branch (`renny`) log — 2026-10-11 00:10–01:00 (integration + verification)

**Trigger:** user asked to pull main, make the whole app side of the PRD work, and verify it will run on the QIDK (board is attached to the ML machine, not this Mac).

**What I found after `git fetch`:** `main` only had doc changes (merged; CHANGE/STATUS conflicts resolved by keeping both). `origin/Saarthak` however already contains its own `android/core` (190 tests), `android/engine` (`InferenceEngine`, `JniInferenceEngine`, prebuilt `libiv_native.so` + QNN/Genie libs), `config/` (superset `app_config.json`, `schemas/`, golden prompts) and `eval/` — same path and same package as my engine port. Left alone, the two branches would not merge and the APK would have duplicate classes.

**Decisions (reasoning)**
- Keep ML's trees untouched and identical (`git checkout origin/Saarthak -- android/core android/engine config`) so the merge is conflict-free; move my layer to `android/domain`. Rewriting the app onto ML's `:core` API would have cost hours with no user-visible gain; instead I *prove equivalence* with tests (below).
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

### 2026-10-11 02:35 IST — Saarthak/ML: board handoff, export speed-up
- Hotspot upload: one S3 connection gets ~30 kB/s, 12 parallel connections 1.2 MB/s aggregate (latency-bound, not bandwidth-bound). qai_hub patched to 16 MiB parts × 48 connections → Part 1 (820 MB) uploaded in ~5 min (was ~200 kB/s, ETA 6 AM). Export restarted with resume hook keyed by part name (`part:Llama3_2_3B_PartN_Of_3_w4a16.aimet`), so finished parts are never re-uploaded.
- Kaggle export attempt failed: CPU box 31 GB RAM, no swap allowed → torch export OOM-killed; TPU v3-8 shape is retired (falls back to CPU). Kaggle path abandoned; private dataset `srthk4370/iv-secrets` (tokens) to be deleted.
- Local fine-tuning rejected for now: it does not remove the export/upload bottleneck.
- ASR decision: force_en (EN WER 7.0 %, 1.9 s/clip on QIDK; Hinglish CER 70 %+ in every mode).
- QIDK moves to the second laptop for app testing with real Whisper + mock LLM; see STATUS.md "QIDK handoff".
