#!/bin/bash
# Llama 3.2 3B Instruct → Genie bundle (w4a16) for Snapdragon 8 Elite / HTP V79 via AI Hub (PRD §25.2).
# Uses a clean venv with the recipe's pinned deps (~/venvs/qaihm_pin). 3 attempts on 3B, then 1B fallback (PRD R1-02).
JOB=export_llama; source $HOME/iv/jobs/common.sh
OUT=$HOME/iv_exports
V=$HOME/venvs/qaihm_pin
if [ ! -x "$V/bin/qai-hub-models" ]; then
  status "creating pinned venv $V"
  "$HOME/.pyenv/versions/3.11.9/bin/python" -m venv "$V" || python3 -m venv "$V"
  "$V/bin/pip" install -q -U pip
  # CPU torch first so the recipe's torch==2.10.0 pin doesn't pull multi-GB CUDA wheels
  "$V/bin/pip" install -q torch==2.10.0 torchvision==0.25.0 --index-url https://download.pytorch.org/whl/cpu
  "$V/bin/pip" install -q "qai-hub-models[llama-v3-2-3b-instruct]==0.63.0" || status "pinned install failed; using ~/venvs/qaihm"
fi
[ -x "$V/bin/qai-hub-models" ] || V=$HOME/venvs/qaihm
# Upload speed: each S3 connection gets ~40 kB/s on this network; qai_hub defaults to 4 × 256 MiB parts (~170 kB/s).
S3T=$(ls "$V"/lib/python3.*/site-packages/qai_hub/s3_transfer.py 2>/dev/null | head -1)
if [ -n "$S3T" ] && ! grep -q "IV patch4" "$S3T"; then
  # 64 MiB parts × 8 connections; 'adaptive' retries throttle to ~0 after S3 SlowDown (seen: upload froze at 51%),
  # so use 'standard' retries with explicit timeouts.
  sed -i -E 's/^_MULTIPART_CHUNK_SIZE = [0-9]+ \* 1024 \* 1024.*/_MULTIPART_CHUNK_SIZE = 8 * 1024 * 1024  # IV patch4/; s/    max_request_concurrency=[0-9]+,.*/    max_request_concurrency=128,  # IV patch4/; s/retries=\{"max_attempts": 10, "mode": "adaptive"\},/retries={"max_attempts": 10, "mode": "standard"}, connect_timeout=30, read_timeout=120,  # IV patch4/' "$S3T"
  status "patched qai_hub S3 upload: 8 MiB × 128 (S3 per-connection can drop to ~6 kB/s), standard retries, timeouts"
fi
# Resumable export: reuse finished uploads / jobs after a crash (ml/export/iv_resume_hub.py → sitecustomize)
cp "$REPO/ml/export/iv_resume_hub.py" "$(ls -d "$V"/lib/python3.*/site-packages)/sitecustomize.py"
status "venv $V: $($V/bin/pip list 2>/dev/null | grep -E '^(transformers|torch) ' | tr -s ' ' | tr '\n' ' ')"
export HF_TOKEN=$(cat ~/.cache/huggingface/token)
export HF_HUB_OFFLINE=1   # weights cached locally; avoids flaky-network HEAD requests
for model in llama_v3_2_3b_instruct llama_v3_2_3b_instruct llama_v3_2_3b_instruct llama_v3_2_1b_instruct; do
  dir=$OUT/${model}_v79
  if find "$dir" -name genie_config.json 2>/dev/null | grep -q .; then
    status "bundle already present: $dir"; echo "$dir" > "$ST/llama_bundle_root"; mark_done; exit 0
  fi
  status "export start: $model (expect 3–5 h: local ONNX export, upload, cloud compile, download)"
  "$V/bin/qai-hub-models" export "$model" --device "Samsung Galaxy S25 (Family)" --target-runtime genie \
      --skip-profiling --output-dir "$dir" 2>&1 | stdbuf -o0 tr '\r' '\n'   # unbuffered: stall detection needs live log
  rc=${PIPESTATUS[0]}
  if find "$dir" -name genie_config.json 2>/dev/null | grep -q .; then
    status "export OK: $model → $dir"; echo "$dir" > "$ST/llama_bundle_root"; mark_done; exit 0
  fi
  status "export FAILED rc=$rc for $model (see ~/iv/logs/export_llama.log); retrying/falling back"
  sleep 30
done
mark_failed "all export attempts failed"
