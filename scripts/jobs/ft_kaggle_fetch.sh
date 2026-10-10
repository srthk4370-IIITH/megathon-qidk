#!/bin/bash
# Poll the Kaggle training kernel; on success download the LoRA adapter → ~/iv_ft/adapter.
# On failure / no adapter / >110 min: touch ~/iv/state/ft_use_laptop (supervisor then starts ft_train_laptop).
JOB=ft_kaggle_fetch; source $HOME/iv/jobs/common.sh
K=$HOME/venvs/kaggle/bin/kaggle; KID=srthk4370/iv-ft-train
T0=$(cat "$ST/ft_kaggle_pushed" 2>/dev/null || date +%s)
sleep 90
while true; do
  [ -f $HOME/iv_ft/adapter/adapter_config.json ] && { status "adapter already present"; mark_done; exit 0; }
  s=$(timeout 300 $K kernels status $KID 2>&1 | tail -1)
  case "$s" in
    *"status \""*COMPLETE*|*"status \""*complete*)
      O=$HOME/iv_ft/kaggle_out; rm -rf "$O"; mkdir -p "$O"
      for i in 1 2 3; do $K kernels output $KID -p "$O" 2>&1 | tail -1; [ -f "$O/adapter/adapter_model.safetensors" ] && break; sleep 30; done
      cat "$O/result.json" 2>/dev/null | head -c 400
      if [ -f "$O/adapter/adapter_model.safetensors" ]; then
        rm -rf $HOME/iv_ft/adapter.tmp; cp -r "$O/adapter" $HOME/iv_ft/adapter.tmp && mv $HOME/iv_ft/adapter.tmp $HOME/iv_ft/adapter
        status "kaggle adapter fetched: $(tr -d '\n ' < "$O/result.json" | cut -c1-200)"
        mark_done; exit 0
      fi
      touch "$ST/ft_use_laptop"
      mark_failed "kaggle kernel finished without adapter: $(tr -d '\n' < "$O/result.json" 2>/dev/null | cut -c1-200) → laptop fallback" ;;
    *"status \""*ERROR*|*"status \""*error*|*CANCEL*)
      touch "$ST/ft_use_laptop"
      mark_failed "kaggle kernel $s → laptop fallback (log: kaggle kernels output $KID)" ;;
    *)
      if [ $(( $(date +%s) - T0 )) -gt 6600 ] && [ ! -f "$ST/ft_use_laptop" ]; then
        touch "$ST/ft_use_laptop"; status "kaggle >110 min — enabling laptop fallback too (keeps polling)"
      fi
      sleep 90 ;;
  esac
done
