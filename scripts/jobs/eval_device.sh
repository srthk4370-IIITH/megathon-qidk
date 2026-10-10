#!/bin/bash
# Device evaluation on the NPU (PRD §29): text cases → score; clips (if recorded) → ASR + end-to-end → score.
JOB=eval_device; source $HOME/iv/jobs/common.sh
wait_board
cd "$REPO"; PY=$HOME/venvs/ml/bin/python; [ -x "$PY" ] || PY=python3
RUN=device_$(date +%m%d_%H%M)
status "text eval start ($RUN)"
$PY eval/run_device.py --run "$RUN" || status "run_device text returned non-zero"
$PY eval/score.py --run "eval/results/$RUN" && status "text eval scored → eval/results/$RUN/report.md"
if ls eval/clips/clip_*.wav >/dev/null 2>&1 || ls eval/clips/raw/* >/dev/null 2>&1; then
  ls eval/clips/raw/* >/dev/null 2>&1 && bash eval/convert_clips.sh
  AL=$(cat "$ST/asr_lang_mode" 2>/dev/null || echo auto)
  $PY eval/run_device.py --run "${RUN}_clips" --asr --asr-lang "$AL" --e2e && $PY eval/score.py --run "eval/results/${RUN}_clips" \
    && status "clip eval scored → eval/results/${RUN}_clips/report.md"
else
  status "no clips yet (eval/clips/raw empty) — re-run later: rm ~/iv/state/eval_device.done"
fi
mark_done
