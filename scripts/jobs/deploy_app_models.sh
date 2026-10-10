#!/bin/bash
# Push model files into the APP's external files dir (Plan A, PRD §20.10):
#   /sdcard/Android/data/com.megathon.incidentvoice/files/models/{whisper,dsp,llama}
# Re-runs every supervisor tick (unchanged files skipped); marks done only once the Llama bundle is there too.
JOB=deploy_app_models; source $HOME/iv/jobs/common.sh
PKG=${IV_APP_PKG:-com.megathon.incidentvoice}
M=/sdcard/Android/data/$PKG/files/models
wait_board
ash "mkdir -p $M/whisper $M/dsp $M/llama"
push_if_changed "$HOME/iv/models/whisper/ggml-small-q5_1.bin" "$M/whisper"
for f in "$HOME"/iv/stage/dsp/*; do push_if_changed "$f" "$M/dsp"; done
if [ -f "$ST/deploy_llama.done" ] && [ -f "$HOME/iv/models/llama/genie_config.json" ]; then
  for f in "$HOME"/iv/models/llama/*; do push_if_changed "$f" "$M/llama"; done
  status "app models complete in $M (whisper + dsp + llama)"
  mark_done
fi
grep -q "app models: whisper+dsp" "$STATUS" || status "app models: whisper+dsp pushed to $M (llama follows when exported)"
rm -f "$ST/$JOB.pid"
