#!/bin/bash
# Unattended ML pipeline (runs detached in WSL; survives the Claude session).
#   1) wait for toolchains (NDK, whisper.cpp, QAIRT 2.50)   2) build native (iv_cli, libiv_native.so)
#   3) push runtime + whisper.cpp model to the board, ASR smoke test
#   4) wait for the Llama V79 Genie bundle (laptop export OR Kaggle kernel, first wins), verify, push, LLM smoke tests
# Progress: ~/iv/logs/pipeline.log and <repo>/ml/status/pipeline_status.md (human-readable, committed).
# Re-runnable: every step is idempotent; just start it again.
set -u
REPO=/mnt/c/Users/hp/Desktop/Personal-Projects/megathon-qidk
ADB=/mnt/c/Users/hp/Desktop/qidk/Code/platform-tools/adb.exe
LOG=$HOME/iv/logs/pipeline.log
STATUS=$REPO/ml/status/pipeline_status.md
DEV=/data/local/tmp/iv
mkdir -p "$(dirname "$STATUS")" "$HOME/iv/logs"
export KAGGLE_API_TOKEN=$(cat ~/.kaggle/access_token 2>/dev/null)
KAGGLE=$HOME/venvs/kaggle/bin/kaggle

log(){ echo "$(date '+%F %T') $*" | tee -a "$LOG"; }
status(){ echo "- $(date '+%H:%M') $*" >> "$STATUS"; log "STATUS $*"; }
w(){ wslpath -w "$1"; }
adbx(){ "$ADB" "$@" 2>&1 | tr -d '\r'; }
ash(){ "$ADB" shell "$@" 2>&1 | tr -d '\r'; }

[ -f "$STATUS" ] || echo "# ML pipeline status (auto-written by scripts/auto_pipeline.sh)" > "$STATUS"
status "pipeline started"

# ---------- 1. toolchains ----------
until [ -d "$HOME/iv/android-ndk-r27c" ] && [ -f "$HOME/iv/whisper.cpp/CMakeLists.txt" ] && ls -d $HOME/qairt/qairt/2.50* >/dev/null 2>&1 && grep -q BG_SETUP_DONE "$HOME/iv/logs/bg_setup.log" 2>/dev/null; do
  sleep 30
done
status "toolchains ready (NDK r27c, whisper.cpp, QAIRT $(ls $HOME/qairt/qairt))"

# ---------- 2. build ----------
if bash "$REPO/native/build_android.sh" >> "$LOG" 2>&1; then
  status "native build OK (iv_cli, libiv_native.so, jniLibs staged)"
else
  status "native build FAILED — see ~/iv/logs/pipeline.log; fix and re-run"
  exit 1
fi

# ---------- 3. board runtime + ASR ----------
ash "mkdir -p $DEV/bin $DEV/lib $DEV/dsp $DEV/models/whisper $DEV/models/llama $DEV/test $DEV/run"
for sub in bin lib dsp; do
  for f in "$HOME"/iv/stage/$sub/*; do adbx push "$(w "$f")" $DEV/$sub/ >> "$LOG"; done
done
WM=$HOME/iv/models/whisper/ggml-small-q5_1.bin
[ "$(ash stat -c %s $DEV/models/whisper/ggml-small-q5_1.bin)" = "$(stat -c %s $WM)" ] || adbx push "$(w $WM)" $DEV/models/whisper/ >> "$LOG"
adbx push "$(w $HOME/iv/whisper.cpp/samples/jfk.wav)" $DEV/test/ >> "$LOG"
cat > /tmp/iv_run.sh <<'EOF'
#!/system/bin/sh
D=/data/local/tmp/iv
export LD_LIBRARY_PATH=$D/lib
export ADSP_LIBRARY_PATH="$D/dsp;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp"
exec $D/bin/iv_cli "$@"
EOF
adbx push "$(w /tmp/iv_run.sh)" $DEV/run.sh >> "$LOG"
ash "chmod 755 $DEV/run.sh $DEV/bin/*"
ASR=$(ash "$DEV/run.sh asr --wav $DEV/test/jfk.wav --lang en --threads 6 2>/dev/null")
status "ASR smoke (jfk.wav, whisper.cpp small q5_1, CPU): $ASR"

# ---------- 4. Llama bundle ----------
find_laptop_bundle(){ find "$HOME/iv_exports/llama_stock" -name genie_config.json -printf '%h\n' 2>/dev/null | head -1; }
BUNDLE=""
while [ -z "$BUNDLE" ]; do
  B=$(find_laptop_bundle)
  if [ -n "$B" ]; then BUNDLE=$B; status "bundle from LAPTOP export: $B"; break; fi
  KS=$($KAGGLE kernels status srthk4370/iv-llama-v79-export 2>&1)
  if echo "$KS" | grep -q COMPLETE; then
    mkdir -p "$HOME/iv/kaggle_out" && $KAGGLE kernels output srthk4370/iv-llama-v79-export -p "$HOME/iv/kaggle_out" >> "$LOG" 2>&1
    Z=$(ls $HOME/iv/kaggle_out/*.zip 2>/dev/null | head -1)
    if [ -n "$Z" ]; then
      rm -rf "$HOME/iv/kaggle_bundle" && mkdir -p "$HOME/iv/kaggle_bundle" && unzip -q -o "$Z" -d "$HOME/iv/kaggle_bundle"
      B=$(find "$HOME/iv/kaggle_bundle" -name genie_config.json -printf '%h\n' | head -1)
      [ -n "$B" ] && { BUNDLE=$B; status "bundle from KAGGLE: $B"; break; }
    fi
    status "Kaggle COMPLETE but no bundle zip found — check kernel log"; sleep 600
  elif echo "$KS" | grep -qiE "ERROR|CANCEL"; then
    grep -q "kaggle failed" "$STATUS" || status "Kaggle kernel failed ($KS); waiting on laptop export"
  fi
  if ! pgrep -f "qai-hub-models export" >/dev/null && [ -z "$(find_laptop_bundle)" ]; then
    grep -q "laptop export not running" "$STATUS" || status "laptop export not running and no bundle — check ~/iv_exports/llama_export.log"
  fi
  sleep 120
done

if python3 "$REPO/ml/scripts/verify_bundle.py" "$BUNDLE" --write-manifest >> "$LOG" 2>&1; then
  status "bundle verified (BUNDLE_OK)"
else
  status "bundle verification FAILED — see log; NOT pushing"; exit 1
fi
rm -rf "$HOME/iv/models/llama" && mkdir -p "$HOME/iv/models/llama" && cp -r "$BUNDLE"/. "$HOME/iv/models/llama/"
for f in "$HOME"/iv/models/llama/*; do
  n=$(basename "$f")
  [ "$(ash stat -c %s $DEV/models/llama/$n)" = "$(stat -c %s "$f")" ] || adbx push "$(w "$f")" $DEV/models/llama/ >> "$LOG"
done
status "bundle pushed to $DEV/models/llama"

# Smoke 1: Genie's own runner (independent of our code)
printf '<|start_header_id|>user<|end_header_id|>\n\nSay OK.<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n' > /tmp/say_ok.txt
adbx push "$(w /tmp/say_ok.txt)" $DEV/test/say_ok.txt >> "$LOG"
python3 - "$HOME/iv/models/llama/genie_config.json" "$DEV/models/llama" > /tmp/genie_abs.json <<'EOF'
import json, sys
cfg = json.load(open(sys.argv[1])); d = sys.argv[2]
def fix(n):
    if isinstance(n, dict):
        for k, v in n.items():
            if k in ("path", "extensions") and isinstance(v, str) and not v.startswith("/"): n[k] = d + "/" + v
            elif k == "ctx-bins": n[k] = [x if x.startswith("/") else d + "/" + x for x in v]
            else: fix(v)
    elif isinstance(n, list):
        for x in n: fix(x)
fix(cfg)
for k in ("sampler",):
    if k in cfg.get("dialog", {}): cfg["dialog"][k].update({"top-k": 1})
print(json.dumps(cfg))
EOF
adbx push "$(w /tmp/genie_abs.json)" $DEV/test/genie_abs.json >> "$LOG"
G=$(ash "cd $DEV && LD_LIBRARY_PATH=$DEV/lib ADSP_LIBRARY_PATH='$DEV/dsp;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp' $DEV/bin/genie-t2t-run -c $DEV/test/genie_abs.json --prompt_file $DEV/test/say_ok.txt --profile $DEV/test/profile.json 2>&1 | tail -5")
status "genie-t2t-run Say OK: $(echo "$G" | tr '\n' ' ' | cut -c1-300)"
ash "cat $DEV/test/profile.json" >> "$LOG"

# Smoke 2: our iv_cli with the real extraction prompt (golden render if available)
P=$REPO/config/prompts/golden/extract_slide.txt
[ -f "$P" ] || P=/tmp/say_ok.txt
adbx push "$(w "$P")" $DEV/test/extract_slide.txt >> "$LOG"
for i in 1 2; do
  L=$(ash "$DEV/run.sh llm --prompt-file $DEV/test/extract_slide.txt --task EXTRACT --max-tokens 320 2>/dev/null | tail -1")
  status "iv_cli llm extract run $i: $(echo "$L" | cut -c1-600)"
done
status "PIPELINE DONE — board ready for eval and app (Plan A probe next)"
