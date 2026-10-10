#!/bin/bash
# Plan A probe: can the app sandbox open the NPU? Builds + runs android-probe (PRD §26.6). Verdict → ~/iv/state/planA_verdict
JOB=probe_planA; source $HOME/iv/jobs/common.sh
wait_board
sed -i 's/\r$//' "$REPO/android-probe/build_and_run.sh"
OUT=$(bash "$REPO/android-probe/build_and_run.sh" 2>&1 | tail -60)
echo "$OUT"
V=$(echo "$OUT" | grep -oE 'VERDICT [A-Z_]+[^"]{0,200}' | tail -1)
[ -n "$V" ] || V="VERDICT UNKNOWN (no result file; see ~/iv/logs/probe_planA.log)"
echo "$V" > "$ST/planA_verdict"
status "$V"
case "$V" in *PLAN_A_OK*|*PLAN_A_FAIL*) mark_done ;; *) sleep 300; exit 1 ;; esac
