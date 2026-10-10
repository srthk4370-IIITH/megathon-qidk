# handbook_saarthak.md — ML branch runbook & live log

**Purpose:** anyone (human or agent) can continue the ML work from this file alone. Spec = `PRD.md` v1.1 (rev table §4.0 wins). Deadline **2026-10-11 09:00 IST**. Branch `Saarthak`. Ask the user only via AskUserQuestion.

## 0. Where everything lives

| Thing | Location |
|---|---|
| Repo (Windows) | `C:\Users\hp\Desktop\Personal-Projects\megathon-qidk` (= WSL `/mnt/c/Users/hp/Desktop/Personal-Projects/megathon-qidk`) |
| WSL workspace | `~/iv/` (`dl/`, `logs/`, `models/`, `android-ndk-r27c/`, `whisper.cpp/`, `gradle-8.10.2/`) |
| QAIRT 2.50 | `~/qairt/v2.50.0.260828.zip` → unzipped `~/qairt/qairt/2.50.0.260828/` (public URL, PRD R1-01) |
| Llama export (laptop) | `~/iv_exports/llama_stock/`, log `~/iv_exports/llama_export.log` (started 21:03) |
| Llama export (Kaggle) | private kernel `srthk4370/iv-llama-v79-export` (TPU VM), output `llama32_3b_genie_w4a16_v79.zip`; creds dataset `srthk4370/iv-creds` (**delete after export**) |
| Kaggle CLI | `~/venvs/kaggle/bin/kaggle`, token `~/.kaggle/access_token` → `export KAGGLE_API_TOKEN=$(cat ~/.kaggle/access_token)` |
| AI Hub venv | `~/venvs/qaihm` (qai-hub-models 0.63.0); HF token `~/.cache/huggingface/token` |
| adb (Windows) | `C:\Users\hp\Desktop\qidk\Code\platform-tools\adb.exe`; from WSL `/mnt/c/Users/hp/Desktop/qidk/Code/platform-tools/adb.exe` |
| Board | SM8750P (HTP V79), serial `a8d3cefb`; device dir `/data/local/tmp/iv/` |
| Background jobs | `~/iv/logs/*.log`; WSL kept alive by a hidden `wsl.exe -- sleep infinity` |

## 1. ML deliverables (PRD v1.1 scope) and status

| # | Deliverable | Path | Status |
|---|---|---|---|
| 1 | Shared contracts | `config/` (taxonomy, sop, site_registry, app_config, prompts, schemas), `config/prompts/RENDERING.md` | ✅ committed |
| 2 | Llama 3.2 3B V79 Genie bundle | `~/iv_exports/…` → board `/data/local/tmp/iv/models/llama/` | ⏳ exporting (laptop + Kaggle) |
| 3 | QAIRT 2.50 runtime on board | board `/data/local/tmp/iv/{lib,dsp}` | ⏳ downloading |
| 4 | ASR: whisper.cpp (Whisper-small q5_1) arm64 | `native/` + board | ⏳ toolchain downloading |
| 5 | Native core + `iv_cli` + JNI `libiv_native.so` | `native/` (contract `native/IV_CLI.md`) | ☐ |
| 6 | Kotlin `:core` (validator, parser, normalisers, prompt builder, SOP templates, emergency) | `android/core/` | ☐ (agent) |
| 7 | Kotlin engine API (`InferenceEngine`, `JniInferenceEngine`, `MockInferenceEngine`, DTOs) | `android/engine/` | ☐ |
| 8 | Plan A probe → decision | `android-probe/` | ☐ |
| 9 | Python twins + eval (gen cases, host/device runners, scorer) | `ml/common/`, `eval/` | ☐ (agent) |
| 10 | Board scripts (push, smoke, restore) | `scripts/` | ☐ |
| 11 | Eval numbers for slides | `eval/results/*/report.md` | ☐ |
| 12 | P1: NPU Whisper via ORT-QNN; LLM-tailored recs; prefix cache | — | later |

## 2. Recovery: how to resume if the session dies

1. `wsl -d Ubuntu` → `tail ~/iv/logs/*.log ~/iv_exports/llama_export.log`.
2. Kaggle: `kaggle kernels status srthk4370/iv-llama-v79-export`; output: `kaggle kernels output srthk4370/iv-llama-v79-export -p ~/iv/kaggle_out`.
3. Bundle verify + deploy: `scripts/` (see §1 row 10) — or by hand per PRD §25.3 / §26.4–26.5.
4. Read §3 log below for the last state.

## 3. Live log (newest first)

### 2026-10-10 21:35
- Contracts generated into `config/` from PRD appendices (`app_config.json` carries rev 1.1: whisper.cpp engine, 30 s cap, templates for recs, time default).
- Detached `~/iv/bg_setup.sh` (log `~/iv/logs/bg_setup.log`): NDK r27c, whisper.cpp clone, `ggml-small-q5_1.bin` + `q8_0`, Gradle 8.10.2, QAIRT unzip when download completes.
- WSL keep-alive started (hidden `wsl.exe -- sleep infinity`).
- WSL already has JDK 17, cmake, ninja, Android SDK platform/build-tools 34 (`~/android-sdk`), Go at `~/go-sdk/go/bin`.

### 2026-10-10 21:03–21:20
- Llama export started on laptop and Kaggle; board clock fixed; QAIRT 2.50 public URL found. Details in `HANDBOOK.md`.
