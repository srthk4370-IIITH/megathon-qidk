#!/bin/bash
# Fallback: QLoRA on the RTX 4050 (only when Kaggle failed / is late: ~/iv/state/ft_use_laptop). Resumable (ckpt every
# 20 steps), thermal guard 83 °C. Started by the supervisor only after the stock export finished converting (RAM).
JOB=ft_train_laptop; source $HOME/iv/jobs/common.sh
[ -f $HOME/iv_ft/adapter/adapter_config.json ] && { status "adapter already present (kaggle)"; mark_done; exit 0; }
cd "$REPO"; export HF_HUB_OFFLINE=1
status "laptop QLoRA start/resume"
$HOME/venvs/train/bin/python ml/ft/train_lora.py --qlora --data $HOME/iv_ft/data --out $HOME/iv_ft/laptop_run \
  --max-minutes 80 --save-every 20 --eval-every 0 || { status "train crashed — resumes next tick"; rm -f "$ST/$JOB.pid"; exit 1; }
[ -f $HOME/iv_ft/laptop_run/adapter/adapter_config.json ] || mark_failed "no adapter written"
if [ ! -f $HOME/iv_ft/adapter/adapter_config.json ]; then
  rm -rf $HOME/iv_ft/adapter.tmp; cp -r $HOME/iv_ft/laptop_run/adapter $HOME/iv_ft/adapter.tmp && mv $HOME/iv_ft/adapter.tmp $HOME/iv_ft/adapter
fi
status "laptop adapter ready: $(tr -d '\n ' < $HOME/iv_ft/laptop_run/result.json | cut -c1-200)"
mark_done
