# IncidentVoice evaluation toolkit (PRD §29)

All commands run **in WSL from the repo root** (`/mnt/c/Users/hp/Desktop/Personal-Projects/megathon-qidk`).
From Git Bash on Windows, wrap them as `MSYS_NO_PATHCONV=1 wsl -d Ubuntu -- bash script.sh`.

## 0. Environment (once)

```bash
python3 -m venv ~/venvs/ml
~/venvs/ml/bin/pip install jiwer rapidfuzz pytest imageio-ffmpeg     # light tooling, no torch
# host LLM runs use the existing ~/venvs/train (torch 2.5.1+cu124, transformers 4.46.3, bitsandbytes 0.44.1)
```

## 1. Shared core + golden prompts

```bash
~/venvs/ml/bin/python -m pytest -q ml/common/tests          # renderer, parser corpus, validator V1–V10, time resolver
~/venvs/ml/bin/python -m ml.common.make_golden              # writes config/prompts/golden/*.txt + *.input.json
```
The Kotlin `PromptBuilderGoldenTest` must reproduce the `.txt` bytes from the `.input.json` inputs.

## 2. Datasets (deterministic)

```bash
~/venvs/ml/bin/python eval/gen_cases.py
```
Writes `eval/text_cases.jsonl` (150), `eval/spot_check.md` (30 to hand-check), `eval/clips.jsonl` (30 clip
scripts + gold) and `eval/RECORDING_SCRIPT.md`. Templates live in `eval/scenarios.py` — fix gold there, never
by hand in the JSONL (it is regenerated).

## 3. Recorded clips

1. Teammates read `eval/RECORDING_SCRIPT.md`, save `clip_NN.<any ext>` into `eval/clips/raw/`.
2. `bash eval/convert_clips.sh` → `eval/clips/clip_NN.wav` (16 kHz mono PCM16; uses system ffmpeg, else the
   static one from `imageio-ffmpeg`).
3. If a speaker changed the wording, edit `transcript`/`gold_transcript` (and the gold form if needed) of that
   clip in `eval/clips.jsonl`. Note: re-running `gen_cases.py` overwrites `clips.jsonl`.

## 4. Device runs (identical numerics to the app; needs `iv_cli` deployed, see `native/IV_CLI.md`)

```bash
~/venvs/ml/bin/python eval/run_device.py --run dev_base                    # 150 text cases, 4 llm-batch stages
~/venvs/ml/bin/python eval/score.py --run eval/results/dev_base

~/venvs/ml/bin/python eval/run_device.py --run dev_base_clips --asr --e2e  # ASR on clips, then extraction on ASR text
~/venvs/ml/bin/python eval/score.py --run eval/results/dev_base_clips      # case file picked from run_info.json
```
- Resumable: re-run the same command; finished ids are cached locally (`<run>/s*_*.out.jsonl`, `asr.jsonl`) and
  `iv_cli llm-batch` skips ids already in its remote OUT. Error rows are dropped and retried.
- Busy/slow board: each stage is retried (`IV_STAGE_ATTEMPTS`, default 4) with backoff; adb transport errors
  retry with `wait-for-device`; per-stage timeout `--timeout` (default 6 h), per clip `--asr-timeout` (600 s).
- Exit codes: `0` ok (also partial results — warning printed — and "no clips recorded yet"), `2` board not
  reachable / adb failure, `3` engine produced no results at all, `4` `/data/local/tmp/iv/run.sh` missing.
- Env overrides: `IV_ADB` (default Windows `adb.exe` under `qidk/Code/platform-tools`, local paths passed via
  `wslpath -w`), `IV_SERIAL`, `IV_REMOTE` (default `/data/local/tmp/iv`), `IV_WAIT_DEVICE_S`.
- `--limit N`, `--ids a b c`, `--variant ft`, `--dry-run` (render stage-1 prompts only, no adb).

## 5. Host runs (prompt iteration on the laptop GPU; numerics differ from the NPU)

```bash
~/venvs/train/bin/python eval/run_host.py --run host_base --limit 5      # resumable, appends predictions.jsonl
~/venvs/ml/bin/python eval/score.py --run eval/results/host_base
```
Exactly one `<|begin_of_text|>` is prepended by the runner (prompts carry none; Genie adds its own on device).
Needs ~3 GB VRAM and ~4 GB free RAM while loading — do not run while the Llama export is running.

## 6. Scorer

```bash
~/venvs/ml/bin/python eval/score.py --self-test          # gold-vs-gold must be perfect; perturbed copy = sanity numbers
```
Outputs in the run dir: `report.md` (slide-ready table), `metrics.json`, `per_case.jsonl`.
Metric definitions are in the docstring of `eval/score.py` (PRD §29.3 + §29.2 WER/CER with Hinglish normalisation).
