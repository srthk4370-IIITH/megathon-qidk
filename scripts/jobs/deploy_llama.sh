#!/bin/bash
# Verify the Llama V79 bundle, push to the board, smoke-test with genie-t2t-run and iv_cli (PRD §25.3, §26.5 S1–S3).
JOB=deploy_llama; source $HOME/iv/jobs/common.sh
ROOT=$(cat "$ST/llama_bundle_root")
B=$(find "$ROOT" -name genie_config.json -printf '%h\n' | head -1)
[ -n "$B" ] || mark_failed "no genie_config.json under $ROOT"
python3 "$HOME/iv/jobs/verify_bundle.py" "$B" --write-manifest || mark_failed "bundle verification failed (truncated download? wrong arch?)"
status "bundle verified: $B"
L=$HOME/iv/models/llama; rm -rf "$L"; mkdir -p "$L"; cp -r "$B"/. "$L/"
wait_board
ash "mkdir -p $DEV/models/llama $DEV/test"
for f in "$L"/*; do push_if_changed "$f" "$DEV/models/llama"; done
status "bundle pushed to $DEV/models/llama"

# S1: Genie's own runner, independent of our code
python3 - "$L/genie_config.json" "$DEV/models/llama" > /tmp/genie_abs.json <<'EOF'
import json, sys
cfg = json.load(open(sys.argv[1])); d = sys.argv[2]
def fix(n):
    if isinstance(n, dict):
        for k, v in list(n.items()):
            if k in ("path", "extensions") and isinstance(v, str) and not v.startswith("/"): n[k] = d + "/" + v
            elif k == "ctx-bins": n[k] = [x if x.startswith("/") else d + "/" + x for x in v]
            else: fix(v)
    elif isinstance(n, list):
        for x in n: fix(x)
fix(cfg)
if "sampler" in cfg.get("dialog", {}): cfg["dialog"]["sampler"].update({"top-k": 1, "temp": 1.0})
print(json.dumps(cfg))
EOF
printf '<|start_header_id|>user<|end_header_id|>\n\nSay OK.<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n' > /tmp/say_ok.txt
adbx push "$(w /tmp/genie_abs.json)" $DEV/test/genie_abs.json >/dev/null
adbx push "$(w /tmp/say_ok.txt)" $DEV/test/say_ok.txt >/dev/null
G=$(ash "cd $DEV && LD_LIBRARY_PATH=$DEV/lib ADSP_LIBRARY_PATH='$DEV/dsp;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp' $DEV/bin/genie-t2t-run -c $DEV/test/genie_abs.json --prompt_file $DEV/test/say_ok.txt 2>&1 | tail -4" | tr '\n' ' ')
status "S1 genie-t2t-run Say OK: ${G:0:300}"

# S2/S3: our engine with the golden extraction prompt, twice (greedy ⇒ identical)
adbx push "$(w "$REPO/config/prompts/golden/extract_slide.txt")" $DEV/test/extract_slide.txt >/dev/null
R1=$(ash "$DEV/run.sh llm --prompt-file $DEV/test/extract_slide.txt --task EXTRACT --max-tokens 320 2>/dev/null" | tail -1)
R2=$(ash "$DEV/run.sh llm --prompt-file $DEV/test/extract_slide.txt --task EXTRACT --max-tokens 320 2>/dev/null" | tail -1)
status "S2 iv_cli extract: ${R1:0:700}"
[ "$(echo "$R1" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("text",""))' 2>/dev/null)" = "$(echo "$R2" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("text",""))' 2>/dev/null)" ] && status "S3 greedy determinism OK" || status "S3 WARNING outputs differ"
if echo "$R1" | grep -q fill_incident_form; then mark_done; else
  status "S2 failed (no tool call) — retrying in 5 min"; sleep 300; exit 1
fi
