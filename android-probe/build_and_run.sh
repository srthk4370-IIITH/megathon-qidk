#!/bin/bash
# Build the Plan A probe APK without Gradle (aapt2 → javac → d8 → zip → zipalign → apksigner), install, push
# the Llama bundle + DSP libs into the app's external files dir, run, and print the verdict.
# Needs: ~/android-sdk (platform 34, build-tools 34.0.0), ~/iv/stage (from native/build_android.sh),
# ~/iv/models/llama (verified bundle). Usage: bash android-probe/build_and_run.sh [--no-push-models]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK=$HOME/android-sdk; BT=$SDK/build-tools/34.0.0; JAR=$SDK/platforms/android-34/android.jar
ADB=/mnt/c/Users/hp/Desktop/qidk/Code/platform-tools/adb.exe
W=$HOME/iv/probe_build; rm -rf "$W"; mkdir -p "$W"/{classes,dex,apk/lib/arm64-v8a}
PKG=com.megathon.incidentvoice.probe
w(){ wslpath -w "$1"; }

"$BT/aapt2" link -o "$W/base.apk" --manifest "$HERE/AndroidManifest.xml" -I "$JAR"
javac -source 11 -target 11 -cp "$JAR" -d "$W/classes" $(find "$HERE/src" -name '*.java')
"$BT/d8" --min-api 31 --output "$W/dex" $(find "$W/classes" -name '*.class')
cp "$W/base.apk" "$W/unsigned.apk"
cp "$W/dex/classes.dex" "$W/apk/"
for f in libiv_native.so libc++_shared.so libGenie.so libQnnHtp.so libQnnHtpV79Stub.so libQnnSystem.so; do
  [ -f "$HOME/iv/stage/lib/$f" ] && cp "$HOME/iv/stage/lib/$f" "$W/apk/lib/arm64-v8a/"
done
(cd "$W/apk" && zip -q -r "$W/unsigned.apk" classes.dex lib)
"$BT/zipalign" -f -p 4 "$W/unsigned.apk" "$W/aligned.apk"
KS=$HOME/iv/probe.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias probe -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=probe" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$W/probe.apk" "$W/aligned.apk"
"$ADB" install -r -t "$(w "$W/probe.apk")" | tr -d '\r'

EXT=/sdcard/Android/data/$PKG/files
"$ADB" shell "mkdir -p $EXT/models/llama $EXT/models/dsp" | tr -d '\r'
if [ "${1:-}" != "--no-push-models" ]; then
  for f in "$HOME"/iv/models/llama/*; do "$ADB" push "$(w "$f")" $EXT/models/llama/ | tr -d '\r' | tail -1; done
  for f in "$HOME"/iv/stage/dsp/*; do "$ADB" push "$(w "$f")" $EXT/models/dsp/ | tr -d '\r' | tail -1; done
fi
"$ADB" shell "am force-stop $PKG; rm -f $EXT/probe_result.txt; logcat -c; am start -n $PKG/.MainActivity" | tr -d '\r'
for i in $(seq 1 60); do
  sleep 3
  R=$("$ADB" shell "cat $EXT/probe_result.txt 2>/dev/null" | tr -d '\r')
  [ -n "$R" ] && break
done
echo "$R"
"$ADB" logcat -d | tr -d '\r' | grep -iE "IVPROBE|IVNative|fastrpc|open_device_node|Permission denied|QnnHtp" | tail -40
