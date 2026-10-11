#!/bin/bash
# Poll the Kaggle training kernel; on success download the LoRA adapter → ~/iv_ft/adapter.
# On failure / no adapter / >110 min: touch ~/iv/state/ft_use_laptop (supervisor then starts ft_train_laptop;
# it only runs when NO adapter exists at all, so a previous adapter stays the fallback).
# Re-training (v2+): the adapter dir carries PUSHED_AT (= ~/iv/state/ft_kaggle_pushed of its run). A newer push
# replaces it: old adapter/merged/ckpt -> *_prev, downstream ft_merge/ft_gate/ft_export/ft_eval_full state cleared
# so they rerun on the new weights. Output from a stale kernel version (n_train mismatch) is ignored.
JOB=ft_kaggle_fetch; source $HOME/iv/jobs/common.sh
K=$HOME/venvs/kaggle/bin/kaggle; KID=srthk4370/iv-ft-train
T0=$(cat "$ST/ft_kaggle_pushed" 2>/dev/null || date +%s)
sleep 90
A=$HOME/iv_ft/adapter; PUSHED=$(cat "$ST/ft_kaggle_pushed" 2>/dev/null)
install_adapter(){ # $1 = downloaded adapter dir
  rm -rf $A.tmp; cp -r "$1" $A.tmp || return 1; echo "$PUSHED" > $A.tmp/PUSHED_AT
  if [ -d $A ]; then
    rm -rf $A.prev; mv $A $A.prev
    # new weights: everything derived from the old adapter is stale
    for j in ft_merge ft_gate ft_export ft_eval_full; do
      if [ -f "$ST/$j.pid" ] && kill -0 "$(cat "$ST/$j.pid")" 2>/dev/null; then
        kill -TERM -"$(ps -o pgid= -p "$(cat "$ST/$j.pid")" | tr -d ' ')" 2>/dev/null; status "stopped stale $j (old adapter)"
      fi
      rm -f "$ST/$j.done" "$ST/$j.failed" "$ST/$j.pid"
    done
    rm -f "$ST/ft_export.tries" "$ST/llama_ft_bundle_root"
    for d in merged ft_ckpt_w4a16; do [ -d $HOME/iv_ft/$d ] && { rm -rf $HOME/iv_ft/$d.prev; mv $HOME/iv_ft/$d $HOME/iv_ft/$d.prev; }; done
    [ -d $HOME/iv_exports/llama_ft_v79 ] && { rm -rf $HOME/iv_exports/llama_ft_v79.prev; mv $HOME/iv_exports/llama_ft_v79 $HOME/iv_exports/llama_ft_v79.prev; }
    rm -f $HOME/iv/state/hub_cache_ft.json
    for r in ft_dev ft_full; do [ -d "$REPO/eval/results/$r" ] && { rm -rf "$REPO/eval/results/$r.prev"; mv "$REPO/eval/results/$r" "$REPO/eval/results/$r.prev"; }; done
    status "previous adapter -> $A.prev; merged/ckpt/export/gate results -> *.prev; ft_merge..ft_export will rerun"
  fi
  mv $A.tmp $A
}
while true; do
  DL=$(cat "$ST/ft_v2_deadline" 2>/dev/null)
  if [ -n "$DL" ] && [ "$(date +%s)" -gt "$DL" ] && [ -f $A/adapter_config.json ] && [ "$(cat $A/PUSHED_AT 2>/dev/null)" != "$PUSHED" ]; then
    mark_failed "new Kaggle run not fetched by deadline $(date -d @$DL +%H:%M) — keeping current adapter (pipeline proceeds)"
  fi
  [ -f $A/adapter_config.json ] && [ "$(cat $A/PUSHED_AT 2>/dev/null)" = "$PUSHED" ] && { status "adapter for this push already present"; mark_done; exit 0; }
  s=$(timeout 300 $K kernels status $KID 2>&1 | tail -1)
  case "$s" in
    *"status \""*COMPLETE*|*"status \""*complete*)
      O=$HOME/iv_ft/kaggle_out; rm -rf "$O"; mkdir -p "$O"
      for i in 1 2 3; do $K kernels output $KID -p "$O" 2>&1 | tail -1; [ -f "$O/adapter/adapter_model.safetensors" ] && break; sleep 30; done
      cat "$O/result.json" 2>/dev/null | head -c 400
      NT=$(grep -o '"n_train": *[0-9]*' "$O/result.json" 2>/dev/null | grep -o '[0-9]*$')
      if [ -n "$(cat "$ST/ft_kaggle_n_train" 2>/dev/null)" ] && [ "$NT" != "$(cat "$ST/ft_kaggle_n_train")" ]; then
        if [ $(( $(date +%s) - T0 )) -gt 10800 ]; then mark_failed "kernel output n_train=$NT != pushed $(cat "$ST/ft_kaggle_n_train") after 3 h (stale version?) — previous adapter kept"; fi
        status "kernel output is from an older version (n_train=$NT) — waiting for the new run"; sleep 120; continue
      fi
      if [ -f "$O/adapter/adapter_model.safetensors" ]; then
        install_adapter "$O/adapter" || { status "adapter install failed — retry"; sleep 60; continue; }
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
