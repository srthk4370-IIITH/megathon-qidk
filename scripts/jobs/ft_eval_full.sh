#!/bin/bash
# Full 150-case host eval of the fine-tuned model (ft variant), for the report. Runs while the ft export uploads/compiles.
JOB=ft_eval_full; source $HOME/iv/jobs/common.sh
cd "$REPO"; export HF_HUB_OFFLINE=1
$HOME/venvs/train/bin/python eval/run_host.py --run ft_full --variant ft --model $HOME/iv_ft/merged \
  || { status "run_host failed — retry next tick"; rm -f "$ST/$JOB.pid"; exit 1; }
$HOME/venvs/ml/bin/python eval/score.py --run eval/results/ft_full > eval/results/ft_full/score_stdout.txt 2>&1
status "ft full-150: $(grep -iE 'type acc|field f1 \(micro|halluc' eval/results/ft_full/report.md | head -3 | tr '\n' ' ' | cut -c1-300)"
mark_done
