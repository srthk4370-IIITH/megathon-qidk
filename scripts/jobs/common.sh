# Shared helpers for scripts/jobs/*.sh (sourced).
export TZ=Asia/Kolkata
REPO=/mnt/c/Users/hp/Desktop/Personal-Projects/megathon-qidk
ADB=/mnt/c/Users/hp/Desktop/qidk/Code/platform-tools/adb.exe
ST=$HOME/iv/state
DEV=/data/local/tmp/iv
STATUS=$REPO/ml/status/pipeline_status.md
mkdir -p "$ST" "$(dirname "$STATUS")"
status(){ echo "- $(date '+%d %H:%M') [$JOB] $*" >> "$STATUS"; echo "$(date '+%F %T') STATUS $*"; }
w(){ wslpath -w "$1"; }
# Windows adb.exe via WSL interop hangs on stdin when detached: always feed /dev/null, use `shell -n`, and time out.
adbx(){ timeout 1800 "$ADB" "$@" < /dev/null 2>&1 | tr -d '\r'; }
ash(){ timeout 600 "$ADB" shell -n "$@" < /dev/null 2>&1 | tr -d '\r'; }
board_ok(){ timeout 20 "$ADB" get-state < /dev/null 2>/dev/null | tr -d '\r' | grep -q device; }
wait_board(){ until board_ok; do sleep 20; done; }
push_if_changed(){ # local remote_dir
  local f=$1 d=$2 n; n=$(basename "$f")
  # size check first (cheap for multi-GB model files), md5 for small files (rebuilt binaries keep their size)
  local ls rs; ls=$(stat -c %s "$f"); rs=$(ash stat -c %s "$d/$n" 2>/dev/null)
  if [ "$ls" = "$rs" ] && [ "$ls" -gt 100000000 ]; then return 0; fi
  if [ "$ls" = "$rs" ] && [ "$(md5sum "$f" | cut -c1-32)" = "$(ash md5sum "$d/$n" | cut -c1-32)" ]; then return 0; fi
  # timeout scaled to size (≥5 MB/s over USB) + one retry: interop adb occasionally hangs
  local to=$(( 60 + ls / 5000000 ))
  for try in 1 2; do
    if timeout "$to" "$ADB" push "$(w "$f")" "$d/" < /dev/null 2>&1 | tr -d '\r' | tail -1 | grep -q "pushed"; then
      ash chmod 755 "$d/$n" >/dev/null; return 0
    fi
    echo "push retry $try: $n"
  done
  status "WARNING push failed twice: $n"; return 1
}
mark_done(){ touch "$ST/$JOB.done"; rm -f "$ST/$JOB.pid"; status "DONE"; }
mark_failed(){ touch "$ST/$JOB.failed"; rm -f "$ST/$JOB.pid"; status "GAVE UP: $*  (rm ~/iv/state/$JOB.failed to retry)"; exit 1; }
