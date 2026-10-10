#!/bin/bash
# Push IncidentVoice model files into the app's external files dir on the QIDK (any laptop with adb).
#   bash scripts/push_models_to_board.sh <models_src_dir>
# <models_src_dir> contains any of: whisper/ggml-small-q5_1.bin  dsp/*.so  llama/{genie_config.json,*.bin,tokenizer.json,...}
# Then in the app: Settings → Engine → Reload (or force-stop + reopen) → LLM switches from mock to NPU.
# NEVER uninstall the app (Android deletes this dir); reinstall with `adb install -r`.
set -e
SRC=${1:?models source dir}
ADB=${ADB:-adb}
M=/sdcard/Android/data/com.megathon.incidentvoice/files/models
$ADB shell "mkdir -p $M/whisper $M/dsp $M/llama"
for sub in whisper dsp llama; do
  [ -d "$SRC/$sub" ] || continue
  for f in "$SRC/$sub"/*; do
    n=$(basename "$f"); ls=$(stat -c %s "$f" 2>/dev/null || stat -f %z "$f")
    rs=$($ADB shell "stat -c %s $M/$sub/$n 2>/dev/null" | tr -d '\r')
    if [ "$ls" = "$rs" ]; then echo "skip $sub/$n"; continue; fi
    echo "push $sub/$n ($ls bytes)"; $ADB push "$f" "$M/$sub/"
  done
done
$ADB shell "ls -la $M/whisper $M/llama 2>/dev/null; ls $M/dsp | wc -l"
echo "done → in the app: Settings → Engine → Reload"
