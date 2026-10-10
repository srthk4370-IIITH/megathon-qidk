#!/bin/bash
# IncidentVoice ML supervisor — idempotent; run every 5 min by Windows Scheduled Task "IV_Supervisor"
# (scripts/install_supervisor.ps1). Survives Claude sessions ending.
# Each job: skip if ~/iv/state/<job>.done or .failed, skip if pid alive, else (re)start detached when deps are met.
# Human status: <repo>/ml/status/pipeline_status.md  · logs: ~/iv/logs/<job>.log  · retry a job: rm ~/iv/state/<job>.{done,failed}
export TZ=Asia/Kolkata
REPO=/mnt/c/Users/hp/Desktop/Personal-Projects/megathon-qidk
ST=$HOME/iv/state; JD=$HOME/iv/jobs
mkdir -p "$ST" "$HOME/iv/logs" "$JD"
exec 9>"$ST/supervisor.lock"; flock -n 9 || exit 0
# Refresh job scripts from the repo when present (repo may be on another branch: keep last good copy).
if [ -f "$REPO/scripts/jobs/common.sh" ]; then
  cp "$REPO"/scripts/jobs/*.sh "$JD/" && cp "$REPO/ml/scripts/verify_bundle.py" "$JD/" 2>/dev/null
  sed -i 's/\r$//' "$JD"/*.sh
fi

alive(){ [ -f "$ST/$1.pid" ] && kill -0 "$(cat "$ST/$1.pid")" 2>/dev/null; }
done_(){ [ -f "$ST/$1.done" ]; }
start(){
  local job=$1
  done_ "$job" || alive "$job" || [ -f "$ST/$job.failed" ] || {
    setsid nohup bash "$JD/$job.sh" >> "$HOME/iv/logs/$job.log" 2>&1 < /dev/null 9>&- &
    echo $! > "$ST/$job.pid"
    echo "$(date '+%F %T') started $job pid $!" >> "$HOME/iv/logs/supervisor.log"
  }
}

start export_llama
start build_native
start host_eval
done_ build_native && start deploy_runtime
done_ deploy_runtime && start asr_eval
{ done_ export_llama && done_ deploy_runtime; } && start deploy_llama
done_ deploy_llama && start probe_planA
{ done_ deploy_llama && done_ probe_planA; } && start eval_device
exit 0
