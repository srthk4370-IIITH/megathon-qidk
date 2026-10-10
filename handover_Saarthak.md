# handover_Saarthak.md — ML / NPU branch

Read this to pick up the `Saarthak` branch cold. Spec: `PRD.md`. History: `HANDBOOK.md`. Cross-branch changes: `CHANGE.md`.

## Scope of this branch

Everything model- and NPU-side (PRD roles P1 + P3):
- Model acquisition/export: QAIRT 2.50, Llama 3.2 3B Genie bundle (Kaggle), Whisper-small context binaries (PRD §25).
- Native inference core: `native/` C++ (Genie wrapper, QNN Whisper, mel, scheduler), JNI lib `libiv_native.so`, Plan B worker `iv_npud` + Go `server-go/` (PRD §18–19).
- Shared contracts: `config/` (taxonomy, SOPs, site registry, app_config, prompts) and the tool-call schemas / engine DTOs (PRD Appendix A–D).
- `:core`-equivalent logic that the app consumes (validator, parser, normalisers) — coordinate with the Android branch on where the Kotlin lives.
- Data generation, fine-tuning (LLM + Whisper), evaluation harness (PRD §27–29).
- Board setup and scripts (`scripts/`, PRD §26).

## What this branch exposes to other branches

| Interface | Defined in | Consumer |
|---|---|---|
| `InferenceEngine` (transcribe / generate / abort) | PRD §22.1 | Android |
| JNI `NativeBridge` | PRD §18.6 | Android |
| Plan B HTTP API (`127.0.0.1:8765`) | PRD §19.2 | Android |
| `config/*.json`, prompts | PRD §22.5, App. A–C | Android |
| Model files on device | PRD §20.10, §25.5 | Android |

## Current state (2026-10-10 21:20)

- PRD v1.1, `M1.md`. Llama V79 export running on laptop (`~/iv_exports/llama_export.log`) and Kaggle (`kaggle kernels status srthk4370/iv-llama-v79-export`, output zip `llama32_3b_genie_w4a16_v79.zip`). QAIRT 2.50 downloading to `~/qairt/` from the public URL (PRD R1-01). After export: delete private dataset `srthk4370/iv-creds`.
- Laptop: WSL venvs ready (`~/venvs/qaihm` with qai-hub-models 0.63.0 + AI Hub token; `~/venvs/train`), HF Llama 3.2 3B weights cached. C: low on space until VHDX compaction.
- Board backup runtime (QAIRT 2.47) in `_board_runtime_backup/` (local only).

## First steps when the clock starts (PRD §35.2, P1/P3 rows)

1. ~~Start exports; download QAIRT; fix board clock~~ (done 21:03). Next: verify bundle (`verify_bundle.py`), push QAIRT 2.50 V79 runtime, smoke tests S0–S3 (§26.5).
2. whisper.cpp (Whisper-small) Android JNI build behind `InferenceEngine` (P0 ASR, R1-03).
3. Commit `config/` contracts from PRD Appendix A–D; announce in `CHANGE.md`.
4. Build `android-probe` (§26.6) → decide Plan A/B → record in `CHANGE.md` + PRD D-39a.

## Gotchas (from problems_setup.md and this board)

- Board is **V79**, not V75 — never reuse the old smart-city bundle.
- Device QAIRT libs must be ≥ compile version (2.50).
- `ADSP_LIBRARY_PATH` uses semicolons; keep ARM and Hexagon `libQnnSystem.so` in separate dirs.
- No double BOS; greedy sampler for extraction; one NPU session at a time.
- Git Bash mangles `/data/...` paths for adb → `MSYS_NO_PATHCONV=1`.
