#!/bin/bash
# Prompt-quality check on the laptop GPU while the NPU bundle exports (PRD §29.5 host runner).
# Llama 3.2 3B Instruct, 4-bit NF4, greedy, same rendered prompts as the device. Numerics differ from w4a16 on HTP.
# NOTE: while the prompt-tuning agent is active it owns the GPU; this job is then marked done manually.
JOB=host_eval; source $HOME/iv/jobs/common.sh
cd "$REPO"
TP=$HOME/venvs/train/bin/python; PY=$HOME/venvs/ml/bin/python; [ -x "$PY" ] || PY=python3
status "host eval start (150 cases, laptop GPU 4-bit)"
if $TP eval/run_host.py --run host_base; then
  $PY eval/score.py --run eval/results/host_base > eval/results/host_base/score_stdout.txt 2>&1
  status "host eval scored: $(grep -iE 'type acc|field f1 \(micro|mapping' eval/results/host_base/report.md | head -3 | tr '\n' ' ' | cut -c1-300)"
  mark_done
fi
status "run_host failed (resumable) — retry next tick"; rm -f "$ST/$JOB.pid"; exit 1
