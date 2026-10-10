#!/bin/bash
# Poll the Kaggle export kernel; when complete, download the V79 bundle into the export dir.
# export_llama then sees genie_config.json and marks itself done (laptop export is stopped).
JOB=kaggle_fetch; source $HOME/iv/jobs/common.sh
K=$HOME/venvs/kaggle/bin/kaggle; KID=srthk4370/iv-llama-v79-export
DST=$HOME/iv_exports/llama_v3_2_3b_instruct_v79/kaggle
while true; do
  [ -f "$ST/export_llama.done" ] && { status "export already done elsewhere; stop"; mark_done; exit 0; }
  s=$($K kernels status $KID 2>&1 | tail -1)
  case "$s" in
    *COMPLETE*|*complete*)
      status "kaggle kernel complete; downloading output"
      T=$HOME/iv_exports/kaggle_dl; rm -rf "$T"; mkdir -p "$T"
      for i in 1 2 3; do $K kernels output $KID -p "$T" 2>&1 | tail -2; [ -f "$T/llama_v79_bundle.tar" ] && break; sleep 30; done
      grep -aE "export rc=|NO BUNDLE|Error" "$T"/*.log 2>/dev/null | tail -3
      [ -f "$T/llama_v79_bundle.tar" ] || { status "kaggle output has no bundle — see $T"; mark_failed "no bundle in kaggle output"; }
      mkdir -p "$DST" && tar xf "$T/llama_v79_bundle.tar" -C "$DST" || mark_failed "tar extract failed"
      status "kaggle bundle extracted: $(du -sh "$DST" | cut -f1) → stopping laptop export"
      P=$(cat "$ST/export_llama.pid" 2>/dev/null); [ -n "$P" ] && kill -TERM -"$(ps -o pgid= -p "$P" | tr -d ' ')" 2>/dev/null
      rm -f "$ST/export_llama.pid"; mark_done; exit 0 ;;
    *404*|*"not found"*|*Not Found*) sleep 60 ;;  # kernel not pushed yet
    *ERROR*|*CANCEL*)
      status "kaggle kernel failed: $s (logs: kaggle kernels output $KID)"; mark_failed "kernel $s" ;;
    *) sleep 120 ;;
  esac
done
