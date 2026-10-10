#!/bin/bash
# Push QAIRT 2.50 runtime + iv_cli + whisper.cpp model to the board; ASR smoke test on jfk.wav (PRD §26.4–26.5).
JOB=deploy_runtime; source $HOME/iv/jobs/common.sh
wait_board
ash "mkdir -p $DEV/bin $DEV/lib $DEV/dsp $DEV/models/whisper $DEV/models/llama $DEV/test $DEV/run"
for sub in bin lib dsp; do for f in "$HOME"/iv/stage/$sub/*; do push_if_changed "$f" "$DEV/$sub"; done; done
push_if_changed "$HOME/iv/models/whisper/ggml-small-q5_1.bin" "$DEV/models/whisper"
push_if_changed "$HOME/iv/whisper.cpp/samples/jfk.wav" "$DEV/test"
printf '#!/system/bin/sh\nD=/data/local/tmp/iv\nexport LD_LIBRARY_PATH=$D/lib\nexport ADSP_LIBRARY_PATH="$D/dsp;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp"\nexec $D/bin/iv_cli "$@"\n' > /tmp/iv_run.sh
adbx push "$(w /tmp/iv_run.sh)" $DEV/run.sh >/dev/null
ash "chmod 755 $DEV/run.sh $DEV/bin/*"
R=$(ash "$DEV/run.sh asr --wav $DEV/test/jfk.wav --lang en --threads 6 2>/dev/null" | tail -1)
status "ASR smoke jfk.wav: $R"
if echo "$R" | grep -qi "ask not what your country"; then mark_done; else
  status "ASR smoke did not match expected text"; sleep 60; exit 1   # supervisor retries
fi
