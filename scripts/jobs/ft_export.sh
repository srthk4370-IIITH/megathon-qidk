#!/bin/bash
# Fine-tuned model → Genie w4a16 bundle for V79 via AI Hub (PRD §27.6). Steps (idempotent, restart-safe):
#  1. ml/ft/build_ft_ckpt.py: published w4a16 encodings + ONNX exported from OUR merged weights (stock code path),
#     then widen 4-bit per-channel weight scales the fine-tune outgrew → ~/iv_ft/ft_ckpt_w4a16 (AIMET_ONNX_EXPORT ckpt)
#  2. qai-hub-models export --checkpoint <that dir> → ~/iv_exports/llama_ft_v79 (separate resume cache: no reuse of
#     the stock model's uploads/jobs, but a crash here resumes our own uploads)
JOB=ft_export; source $HOME/iv/jobs/common.sh
V=$HOME/venvs/qaihm_pin; CK=$HOME/iv_ft/ft_ckpt_w4a16; OUT=$HOME/iv_exports/llama_ft_v79
cp "$REPO/ml/export/iv_resume_hub.py" "$(ls -d "$V"/lib/python3.*/site-packages)/sitecustomize.py"
export HF_TOKEN=$(cat ~/.cache/huggingface/token) HF_HUB_OFFLINE=1 IV_HUB_CACHE=$HOME/iv/state/hub_cache_ft.json
if find "$OUT" -name genie_config.json 2>/dev/null | grep -q .; then status "ft bundle present: $OUT"; mark_done; exit 0; fi
if [ ! -f "$CK/SCALES_OK" ]; then
  status "building ft AIMET-ONNX checkpoint (ONNX export from merged weights, ~30–60 min)"
  "$V/bin/python" "$REPO/ml/ft/build_ft_ckpt.py" --merged $HOME/iv_ft/merged --out "$CK" 2>&1 | stdbuf -o0 tr '\r' '\n' \
    || true
  [ -f "$CK/SCALES_OK" ] || { status "ft ckpt build failed — retry next tick"; rm -f "$ST/$JOB.pid"; exit 1; }
  status "ft ckpt ready; scale refresh: $(cat "$CK/SCALES_OK")"
fi
status "ft export start → $OUT"
"$V/bin/qai-hub-models" export llama_v3_2_3b_instruct --checkpoint "$CK" --device "Samsung Galaxy S25 (Family)" \
    --target-runtime genie --skip-profiling --model-cache-mode disable --output-dir "$OUT" 2>&1 | stdbuf -o0 tr '\r' '\n'
if find "$OUT" -name genie_config.json 2>/dev/null | grep -q .; then
  B=$(dirname "$(find "$OUT" -name genie_config.json | head -1)")
  printf '{"model":"llama_v3_2_3b_instruct_ft","prompt_variant":"ft","source":"%s"}\n' "$CK" > "$B/MANIFEST.json"
  echo "$B" > "$ST/llama_ft_bundle_root"; status "ft export OK → $B (MANIFEST prompt_variant=ft)"; mark_done
fi
n=$(cat "$ST/ft_export.tries" 2>/dev/null || echo 0); echo $((n+1)) > "$ST/ft_export.tries"
[ "$n" -ge 2 ] && mark_failed "ft export failed 3x (see ~/iv/logs/ft_export.log)"
status "ft export attempt $((n+1)) failed — retry next tick"; rm -f "$ST/$JOB.pid"; exit 1
