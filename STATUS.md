# STATUS.md — what exists right now

Update this whenever something is built, breaks, or is decided. Feature IDs (F-xx) and milestones (M0–M4) refer to `PRD.md` §5 and §35.

_Last updated: 2026-10-10 21:20 IST (ML branch)_

## Overall

| Milestone | Status |
|---|---|
| M0 — contracts frozen, exports running | **Exports running** (laptop + Kaggle); contracts = PRD App. A–D, not yet committed as `config/` files |
| M1 — approach doc | `M1.md` written |
| M2 — full conversation on board | Not started |
| M3 — all P0 features | Not started |
| M4 — release candidate | Not started |

## Done

- `PRD.md` v1.0 (spec), `HANDBOOK.md` (history), `CHANGE.md`, `STATUS.md`, `.gitignore`.
- Board (SM8750P / HTP V79) inventoried; `/data/local/tmp` wiped → 29 GB free.
- Old QAIRT 2.47 V79 runtime backed up locally to `_board_runtime_backup/` (git-ignored).
- Laptop caches and old WSL artifacts cleaned.

## In progress / blocked

| Item | State | Owner |
|---|---|---|
| Llama 3.2 3B V79 Genie export | Running: laptop (since 21:03) + Kaggle TPU kernel `iv-llama-v79-export` | ML |
| QAIRT 2.50 SDK | Downloading (public URL) to WSL `~/qairt/` | ML |

## By branch

| Branch | Area | Built so far |
|---|---|---|
| the ML branch | ML / NPU | Docs only. No models exported yet. |
| App branch | App + dashboard | **All P0 app features built and run on Android 15 arm64 (emulator)**; 321 tests green (ML `:core` 190, app `:domain` 119, `:app` 12). ML's `:engine` (JNI, prebuilt libs) integrated behind an adapter; its native libs load inside the APK. **Not yet run on the QIDK** (board is on the ML machine) → `scripts/app_device_check.sh`. Details: section "App branch" below. |

## Board / environment

| Item | State |
|---|---|
| Board clock / timezone | Fixed 21:03 IST |
| QAIRT 2.50 SDK | Downloading |
| Llama 3.2 3B V79 bundle | Export running (2 hosts) |
| Whisper | P0 = whisper.cpp CPU (not started); NPU via ORT-QNN is P1 |
| Plan A vs Plan B | Undecided (probe at ~H3) |

## App branch — P0 feature status (2026-10-11 01:00)

Legend: ✅ built + verified by test and by running the APK on an Android 15 arm64 emulator · 🟡 built, needs the QIDK to verify · ⏳ waits for ML.

| ID | Feature | State | Evidence |
|---|---|---|---|
| F-01 | Voice capture, Silero VAD auto-stop, manual stop, 30 s cap | 🟡 | Recorder + Silero (ORT) ran on the emulator: 10 s no-speech timeout fired correctly. Real speech/noise needs the board mics. |
| F-02 | ASR via `transcribe()`; transcript shown, editable, 2 s auto-proceed | ✅ UI / ⏳ real ASR | Orchestrator tests; emulator run with scripted transcript. whisper.cpp comes with ML's `:engine`. |
| F-03 | Extraction via `fill_incident_form` | ✅ app side / ⏳ model | 150/150 gold tool calls from `eval/text_cases.jsonl` pass parser → merger → validator; prompts byte-identical to ML goldens. |
| F-04 | Deterministic validator | ✅ | V1–V10 + eval follow-up parity (39/50 exact, 11 explained by §10.6). |
| F-05 | Spoken follow-ups (EN + Hinglish), patch, ≤3 rounds, UNKNOWN/skip | ✅ logic / 🟡 eSpeak voice | 21 orchestrator tests; emulator run. eSpeak is only on the board (chime fallback otherwise). |
| F-06 | Time resolver, location matcher | ✅ | Agrees with ML `:core` on every time phrase in the eval set; location differs only for bare "Block A/B" (ML suggests Loading Dock). |
| F-07 | SOP template recommendations | ✅ | Candidate ids equal ML golden (`recommend_slide`). |
| F-08 | Review: editable, required fields red, submit gate | ✅ | Emulator; gate blocked an incomplete record. |
| F-09 | Room + FTS4, drafts autosaved | ✅ | 9 Robolectric tests on real SQLite + emulator. |
| F-10 | Dashboard: recency, search, filters, count row, detail (+ edit) | ✅ | Emulator: prefix search with snippet, severity filter, detail, edit keeps ref no. |
| F-11 | Emergency banner + one-tap dial | ✅ | Emulator: amber banner on "fire spreading…", dialer opened. No-dialer case shows a toast. |
| F-12 | Settings: profile, ERT number, engine status | ✅ | Emulator. Engine mode Auto / On-device / Mock. |
| F-13 | Debug overlay | ✅ | Emulator (timings, tok/s, raw tool call). |
| — | On-device engine inside the app (Plan A) | 🟡 | ML engine `d69713a` wired: `JniInferenceEngine(llmFallback = app mock)`. In the APK on Android 15: `libiv_native`, `libGenie`, `libQnnSystem`, `libQnnHtp` load; `libQnnHtpV79Stub` needs the board's `libcdsprpc.so`. Whisper missing → clean `IV-E603`; Whisper present → real ASR, LLM on the fallback until the Llama bundle is pushed (orange pill), then Settings → Engine → Auto reloads onto the NPU. |

Cut per rev 1.1 (not built): draft-resume UI, charts, status lifecycle beyond SUBMITTED, PDF/JSON export, 3-min chunking, LLM-tailored recommendations, per-field mic.

## QIDK handoff — 2026-10-11 02:35 (App team works on the board from the second laptop)
**What is on the QIDK now (persists when moved to another laptop):**
- App `com.megathon.incidentvoice` (debug APK installed from this laptop; latest APK also in `deliverables/app-debug.apk`). Reinstall with `adb install -r` only — **never uninstall** (Android wipes the model folder).
- Models in `/sdcard/Android/data/com.megathon.incidentvoice/files/models/`: `whisper/ggml-small-q5_1.bin` (Whisper small q5_1, whisper.cpp CPU) + `dsp/` (12 QAIRT 2.50 Hexagon V79 libs). `llama/` is **empty** until the Llama NPU bundle is exported.
- TTS: eSpeak NG (`com.reecedunn.espeak`) installed and set as default (`settings put secure tts_default_synth com.reecedunn.espeak`).
**How the app runs without the Llama bundle:** `JniInferenceEngine(llmFallback = mock)` → speech-to-text is REAL (on-device Whisper, English mode, ~1.5–2 s per 10–15 s narration, EN WER 7 % on our 30 clips); form extraction / follow-ups / recommendations come from the mock LLM. Settings → Engine shows which LLM is active. Use English narration (Hinglish ASR is weak: CER ~70 %).
**When the Llama bundle is ready (ETA ~04:30–05:00, announced in CHANGE.md + here):**
- Option A (preferred): reconnect the QIDK to the ML laptop for ~15 min; the ML supervisor pushes `llama/` automatically (`deploy_app_models` job) and runs the NPU smoke test.
- Option B: copy the bundle folder to the second laptop, then `bash scripts/push_models_to_board.sh <dir-with-llama/>` (needs adb in PATH or `ADB=...`).
- Then in the app: Settings → Engine → Reload (or force-stop + reopen) → LLM runs on the Hexagon NPU.
**Building the app on the second laptop:** `cd android && ./gradlew -PwithAndroid=true :app:assembleDebug` (JDK 17, Android SDK 35); native libs are prebuilt in `android/engine/src/main/jniLibs/arm64-v8a` (no NDK/QAIRT needed). See `handover_app.md`.
**ML laptop keeps working on:** Llama 3.2 3B V79 export (upload now ~2.5 MB/s with 48 S3 connections; resumable), prompt accuracy (English first), device eval once the bundle is on the board.

### 02:45 — App installed on QIDK; app work moves to the second laptop
- Debug APK built on the ML laptop and installed on the QIDK at 02:38 (`com.megathon.incidentvoice` v0.1, launches to MainActivity). Same APK: `deliverables/app-debug.apk`.
- Models are on the board (whisper + dsp); eSpeak default TTS. Llama `llama/` still empty → app uses the mock LLM.
- ML side stopped all app testing/fixes; the in-progress engine/app fixes made during on-device testing are committed (see git log "handoff:"). One last uncommitted edit (`AppContainer.kt`) is committed with this note. App team continues on the second laptop from `main`.
- ML laptop now works only on the Llama V79 export, prompt accuracy and device eval.

### 02:58 — QIDK can leave the ML laptop (green light)
- The Llama export needs NO board: it runs unattended under the `IV_Supervisor` scheduled task (every 5 min), restarts on crash, resumes without re-uploading finished parts, and has a hang guard.
- When it finishes, `publish_bundle` (automatic) verifies the bundle, copies it to `C:\iv_models\llama`, and pushes `models_dist/llama_meta/` (genie_config, tokenizer, AI Hub link-job ids) to GitHub. Watch for the commit "models: Llama V79 bundle metadata".
- **Get the bundle on any laptop:** `pip install qai-hub`, `qai-hub configure --api_token <same AI Hub token>`, `python scripts/fetch_llama_bundle.py models_dist/llama` (downloads ~2.4 GB from AI Hub), then `bash scripts/push_models_to_board.sh models_dist` → app Settings → Engine → Reload.
- Or reconnect the QIDK to the ML laptop: `deploy_llama` → `deploy_app_models` → NPU smoke test → device eval run automatically.
- Progress: `ml/status/pipeline_status.md` (ML laptop) ; jobs/logs `~/iv/state`, `~/iv/logs` in WSL.

## App branch — QIDK session results (2026-10-11 03:00–04:00)

Run on the real board (SM8750P, Android 15) from the App laptop. Legend as above; ✅ here means verified **on the QIDK**.

| Area | State on the QIDK | Notes |
|---|---|---|
| Install / signing | ✅ | Shared debug key in the repo; `adb install -r` works from any laptop. Models survive. |
| Native engine in-app | ✅ (ASR) / ⏳ (LLM) | `libiv_native`, Genie and QNN libs incl. `libQnnHtpV79Stub` load; whisper.cpp loads in ~120 ms. Llama bundle not on the board yet → rule-based stand-in LLM. |
| Mic + Silero VAD | ✅ | Auto-stop works; platform noise suppressor + echo canceller active. Recordings have ≈ 11 dB speech-to-noise. |
| ASR accuracy | 🟡 | 5/9 live utterances exact with the current native lib; 8/9 with the domain hint (needs the ML patch + rebuild). ~0.8–1.7 s per utterance. |
| Spoken follow-ups (eSpeak) | ✅ | Questions and the emergency warning are spoken; answers are heard and applied. |
| Extraction / follow-ups without Llama | ✅ (stand-in) | `RuleBasedLlm`: 99 % type accuracy on the EN eval cases (tuned on them). Real accuracy comes with Llama. |
| Emergency banner | ✅ | "fire spreading … unconscious" → banner, spoken warning, CRITICAL by rule. |
| Database | ✅ | integrity ok; drafts, turns, audit, recommendations, FTS, vectors; v1→v2 migration ran on real data. |
| Dashboard / detail / edit | ✅ | Demo data loaded on the board; search, filters, detail. |
| Ask tab (O-2) | ✅ | Counts, place and time questions, meaning-based search ("toxic fumes" → ammonia leak), ~50 ms per question. Voice questions use the same mic/Whisper path. |
| End-to-end latency (mock LLM) | ✅ | speech end → form ≈ 1.7 s ASR + 2 s review + mock LLM; real LLM timing pending the bundle. |

## Extras (HANDBOOK "Extra optional work") — status

| Extra | Flag (`config/app_config.json` → `features`) | State | Evidence |
|---|---|---|---|
| 1. Response clock | `response_clock` | ON BOARD | 8 tests; on the QIDK: timer, SOP steps, taps logged with offsets, dialler, survives force-stop. DB v3. |
| 2. One-tap launch + wake word | `quick_launch`, `wake_word` | Level A ON BOARD · Level B ON BOARD | Tile and shortcut start the app already listening. "Hey Qalam": Vosk on CPU, grammar on sound-alike variants (the name is not in the model vocabulary); detected about 3 of 4 with a synthetic voice (small sample), no false trigger on 2 test sentences; microphone handed to the report flow and taken back at idle. |
| 3. Multi-witness merging | `multi_witness` | ON BOARD | After submit the app asks whether to link to an earlier report of the same event (never automatic). Case detail: Field × Witness table, conflicts in amber, tap the trusted value (audit-logged). Dashboard: "N accounts · M conflicts". DB v4. Verified by voice on the QIDK. |
