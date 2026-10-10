#!/bin/bash
# Host pre-gate (PRD §27.5) on the dev subset (eval/dev_ids.txt): ft (variant ft, merged fp16 → NF4 on GPU) vs base
# (eval/results/prompt_final, same ids). Pass: field F1 >= base+0.05 and JSON validity (with repair) >= base.
JOB=ft_gate; source $HOME/iv/jobs/common.sh
cd "$REPO"; export HF_HUB_OFFLINE=1
TP=$HOME/venvs/train/bin/python; PY=$HOME/venvs/ml/bin/python
status "gate: ft on dev subset ($(wc -l < eval/dev_ids.txt) cases)"
$TP eval/run_host.py --run ft_dev --variant ft --model $HOME/iv_ft/merged --ids $(tr -d '\r' < eval/dev_ids.txt) \
  || { status "run_host failed — retry next tick (resumable)"; rm -f "$ST/$JOB.pid"; exit 1; }
$PY ml/ft/gate.py > $HOME/iv_ft/gate.txt 2>&1; rc=$?
status "gate: $(tail -1 $HOME/iv_ft/gate.txt)"
[ $rc -eq 0 ] && mark_done
mark_failed "gate not passed (ft not better enough) — see ~/iv_ft/gate.txt; rm ~/iv/state/ft_gate.failed + touch ~/iv/state/ft_gate.done to force export"
