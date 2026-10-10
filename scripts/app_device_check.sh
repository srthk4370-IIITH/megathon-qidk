#!/usr/bin/env bash
# App-side board check for IncidentVoice (App branch). Run on the machine the QIDK is attached to.
#
#   bash scripts/app_device_check.sh [path/to/app-debug.apk]
#
# What it does (read-only except installing the APK and granting the mic permission):
#   1. board facts the app depends on (SoC, Android, page size, clock, TTS engine, vendor FastRPC lib)
#   2. installs the APK, grants RECORD_AUDIO, starts the app
#   3. checks that the native libs were extracted, whether model files are present, and what the engine reported
#   4. prints a PASS/WARN/FAIL summary
#
# It never touches /data/local/tmp or running NPU jobs. Ask the ML owner before running if a job is using the NPU:
# launching the app in "Auto" engine mode with model files present loads Whisper + Llama in the app process.
# To avoid that, pass MOCK=1 (the app is started with the Mock engine forced).
#
# Works in WSL, Git Bash (MSYS path conversion is disabled below) and macOS/Linux.
set -u
export MSYS_NO_PATHCONV=1
PKG=com.megathon.incidentvoice
APK="${1:-android/app/build/outputs/apk/debug/app-debug.apk}"
ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB=adb.exe

pass=0; warn=0; fail=0
ok()   { echo "  PASS  $*"; pass=$((pass+1)); }
wn()   { echo "  WARN  $*"; warn=$((warn+1)); }
bad()  { echo "  FAIL  $*"; fail=$((fail+1)); }
sh_()  { "$ADB" shell "$@" </dev/null 2>/dev/null | tr -d '\r'; }

echo "== 0. device =="
if ! "$ADB" get-state </dev/null >/dev/null 2>&1; then echo "No device. Check 'adb devices'."; exit 2; fi
soc=$(sh_ getprop ro.soc.model); rel=$(sh_ getprop ro.build.version.release); sdk=$(sh_ getprop ro.build.version.sdk)
abi=$(sh_ getprop ro.product.cpu.abi); page=$(sh_ getconf PAGE_SIZE); year=$(sh_ date +%Y); tz=$(sh_ getprop persist.sys.timezone)
echo "  SoC=$soc Android=$rel (API $sdk) ABI=$abi page=$page clock-year=$year tz=$tz"
[ "$abi" = "arm64-v8a" ] && ok "arm64-v8a" || bad "ABI is $abi; the APK only ships arm64-v8a"
[ "${sdk:-0}" -ge 31 ] 2>/dev/null && ok "API $sdk >= minSdk 31" || bad "API $sdk < 31"
case "$soc" in SM8750*) ok "SoC $soc (HTP V79 libs in the APK match)";; *) wn "SoC is '$soc', APK ships QnnHtpV79Stub (SM8750)";; esac
if [ "$page" = "4096" ]; then ok "4 KB pages (libiv_native.so is 4 KB-aligned)"
else bad "page size $page: libiv_native.so is linked with 4 KB alignment and will not load; rebuild with -Wl,-z,max-page-size=16384"; fi
[ "${year:-0}" -ge 2026 ] 2>/dev/null && ok "clock year $year" || wn "clock year $year: relative times ('an hour ago') will be wrong; fix the clock"
[ "$tz" = "Asia/Kolkata" ] && ok "timezone Asia/Kolkata" || wn "timezone '$tz' (the app computes in Asia/Kolkata regardless)"

echo "== 1. platform pieces the app uses =="
sh_ pm list packages | grep -q com.reecedunn.espeak && ok "eSpeak NG TTS engine installed" || wn "eSpeak not installed: questions are shown + a chime plays, no speech"
if sh_ ls /vendor/lib64/libcdsprpc.so | grep -q libcdsprpc; then ok "/vendor/lib64/libcdsprpc.so present"; else wn "libcdsprpc.so not found in /vendor/lib64 (NPU from the app needs it)"; fi
if sh_ cat /vendor/etc/public.libraries.txt | grep -q libcdsprpc; then ok "libcdsprpc.so is a vendor public library (apps may load it)"
else wn "libcdsprpc.so not listed in /vendor/etc/public.libraries.txt: in-app NPU (Plan A) may fail; see android-probe verdict"; fi
sh_ ls /dev/fastrpc-cdsp | grep -q fastrpc && ok "/dev/fastrpc-cdsp exists" || wn "/dev/fastrpc-cdsp missing"
sh_ pm list packages | grep -q -E 'dialer|com.android.phone' && ok "a dialer/phone package exists (Call ERT opens it)" || wn "no dialer: 'Call ERT' shows a toast with the number instead"

echo "== 2. install + start =="
if [ ! -f "$APK" ]; then bad "APK not found at $APK (build: cd android && gradle :app:assembleDebug)"; else
  if "$ADB" install -r -g "$APK" </dev/null 2>&1 | tr -d '\r' | tail -1 | grep -q Success; then ok "installed $APK"; else bad "adb install failed"; fi
fi
sh_ pm grant $PKG android.permission.RECORD_AUDIO
sh_ dumpsys package $PKG | grep -q "android.permission.RECORD_AUDIO: granted=true" && ok "RECORD_AUDIO granted" || wn "RECORD_AUDIO not granted (the app asks on first mic tap)"
sh_ dumpsys package $PKG | grep -q "android.permission.INTERNET" && bad "APK requests INTERNET (must not, D-54)" || ok "no INTERNET permission (fully offline)"
F=/sdcard/Android/data/$PKG/files
if [ "${MOCK:-0}" = "1" ]; then sh_ mkdir -p $F; sh_ touch $F/force_mock; echo "  MOCK=1: engine forced to Mock (marker $F/force_mock; delete it to allow the on-device engine)"
else sh_ rm -f $F/force_mock; fi
"$ADB" logcat -c </dev/null >/dev/null 2>&1
sh_ am force-stop $PKG
sh_ am start -W -n $PKG/.MainActivity >/dev/null
sleep "${WAIT:-25}"
pid=$(sh_ pidof $PKG)
[ -n "$pid" ] && ok "app is running (pid $pid)" || bad "app is not running after start (crash?)"
"$ADB" logcat -d </dev/null 2>/dev/null | tr -d '\r' > /tmp/iv_app_logcat.txt
grep -q "FATAL EXCEPTION" /tmp/iv_app_logcat.txt && { bad "FATAL EXCEPTION in logcat:"; grep -A12 "FATAL EXCEPTION" /tmp/iv_app_logcat.txt | head -30; } || ok "no FATAL EXCEPTION in logcat"

echo "== 3. native libs + models + engine =="
base=$(sh_ pm path $PKG | head -1 | sed 's/^package://'); libdir="${base%/*}/lib/arm64"
libs=$(sh_ ls "$libdir")
for l in libiv_native.so libGenie.so libQnnHtp.so libQnnHtpV79Stub.so libQnnSystem.so libc++_shared.so libonnxruntime.so; do
  echo "$libs" | grep -q "^$l$" && ok "extracted $l" || bad "$l not extracted to $libdir (extractNativeLibs)"
done
M=/sdcard/Android/data/$PKG/files/models
echo "  model dir: $M"
sh_ ls $M/llama/genie_config.json | grep -q genie_config && ok "llama/genie_config.json present" || wn "Llama bundle not pushed yet → with Whisper present the app runs real ASR + mock LLM (orange pill); tap Settings → Engine → Auto after the bundle arrives"
sh_ ls $M/whisper | grep -q -E 'ggml-.*\.bin' && ok "whisper ggml model present" || wn "whisper model not pushed yet → Auto mode uses the Mock engine (scripted transcript)"
sh_ ls $M/dsp | grep -q -i skel && ok "dsp/ skel libs present" || wn "dsp/ (HTP V79 skel libs) not pushed yet"
grep -E "iv_native|IV-E|Genie|whisper" /tmp/iv_app_logcat.txt | tail -15 | sed 's/^/    /'
if grep -q "Genie loaded" /tmp/iv_app_logcat.txt; then ok "Genie (Llama on HTP) loaded inside the app process → Plan A works"; fi
if grep -q "whisper.cpp loaded" /tmp/iv_app_logcat.txt; then ok "whisper.cpp loaded inside the app process"; fi
if grep -q -E "IV-E60[0-9]|dlopen failed|UnsatisfiedLinkError" /tmp/iv_app_logcat.txt; then bad "engine/native error in logcat (see lines above; full log: /tmp/iv_app_logcat.txt)"; fi
grep -q "IV-E102" /tmp/iv_app_logcat.txt && wn "Silero VAD failed to load → energy VAD fallback in use"

echo
echo "== summary: $pass pass, $warn warn, $fail fail =="
echo "Manual pass (2 min): Settings → 'Load demo incidents' → Dashboard search 'water' → Report tab → tap mic, say the"
echo "water-leak sentence, answer the two questions, Submit. With the Mock engine the transcript is scripted."
[ "$fail" -eq 0 ]
