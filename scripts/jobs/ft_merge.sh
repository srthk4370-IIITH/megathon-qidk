#!/bin/bash
# Merge LoRA → fp16 safetensors on CPU (PRD §27.4) → ~/iv_ft/merged (MERGE_OK marker; idempotent).
JOB=ft_merge; source $HOME/iv/jobs/common.sh
cd "$REPO"; export HF_HUB_OFFLINE=1 CUDA_VISIBLE_DEVICES=
status "merge start"
$HOME/venvs/train/bin/python ml/ft/merge.py --adapter $HOME/iv_ft/adapter --out $HOME/iv_ft/merged \
  || { status "merge failed — retry next tick"; rm -f "$ST/$JOB.pid"; exit 1; }
status "merged: $(cat $HOME/iv_ft/merged/MERGE_OK)"
mark_done
