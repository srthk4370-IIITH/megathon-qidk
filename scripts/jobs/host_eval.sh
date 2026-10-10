#!/bin/bash
# Prompt-quality check on the laptop GPU while the NPU bundle exports (PRD §29.5 host runner).
# Llama 3.2 3B Instruct, 4-bit NF4, greedy, same rendered prompts as the device. Numerics differ from w4a16 on HTP.
JOB=host_eval; source $HOME/iv/jobs/common.sh
cd "$REPO"
TP=$HOME/venvs/train/bin/python; PY=$HOME/venvs/ml/bin/python; [ -x "$PY" ] || PY=python3
nvidia-smi >/dev/null 2>&1 || status "WARNING: no GPU visible in WSL; host eval will be slow"
status "host eval start (150 cases, laptop GPU 4-bit)"
$TP eval/run_host.py --run host_base || status "run_host non-zero (resumable; supervisor will retry)"
$PY eval/score.py --run eval/results/host_base > eval/results/host_base/score_stdout.txt 2>&1 \
  && status "host eval scored: $(grep -iE 'type acc|field f1|mapping|json valid' eval/results/host_base/report.md | head -4 | tr '\n' ' ' | cut -c1-300)" \
  && mark_done
sleep 120; exit 1
