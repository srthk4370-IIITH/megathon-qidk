#!/usr/bin/env python3
"""One-shot: bring the QIDK up to date from this repo (run on any laptop with the board on USB).

    python apply.py                 # pull, push models, build + install app, restart it
    python apply.py --llama         # also fetch the Llama NPU bundle from AI Hub (needs `qai-hub configure`) and push it
    python apply.py --no-build      # models only
    python apply.py --no-pull       # use the working tree as is

What it applies:
  * native fixes ship inside the APK (android/engine/src/main/jniLibs/arm64-v8a/libiv_native.so,
    e.g. the ASR domain-hint prompt: whisper reads <models>/whisper/prompt.txt, written by the app)
  * models → /sdcard/Android/data/com.megathon.incidentvoice/files/models/{whisper,dsp,llama}
The app is reinstalled with `install -r` (never uninstall: Android would wipe the model folder).
"""
import argparse
import os
import shutil
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
PKG = "com.megathon.incidentvoice"
DEV_MODELS = f"/sdcard/Android/data/{PKG}/files/models"
WHISPER_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"
WIN = os.name == "nt"


def run(cmd, **kw):
    print("$", " ".join(cmd), flush=True)
    return subprocess.run(cmd, check=kw.pop("check", True), **kw)


def find_adb():
    for c in [shutil.which("adb"),
              os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb" + (".exe" if WIN else "")),
              os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"),
              os.path.expanduser("~/Android/Sdk/platform-tools/adb"),
              r"C:\Users\hp\Desktop\qidk\Code\platform-tools\adb.exe"]:
        if c and os.path.isfile(c):
            return c
    sys.exit("adb not found: put platform-tools in PATH or set ANDROID_HOME")


def adb(*a, check=True, capture=False):
    r = subprocess.run([ADB, *a], check=check, capture_output=capture, text=True)
    return (r.stdout or "").replace("\r", "") if capture else r


def push_dir(local, remote):
    if not os.path.isdir(local):
        return
    adb("shell", f"mkdir -p {remote}")
    for f in sorted(os.listdir(local)):
        p = os.path.join(local, f)
        if not os.path.isfile(p):
            continue
        rs = adb("shell", f"stat -c %s {remote}/{f} 2>/dev/null", check=False, capture=True).strip()
        if rs == str(os.path.getsize(p)):
            print(f"  ok   {remote}/{f}")
            continue
        print(f"  push {remote}/{f} ({os.path.getsize(p) / 1e6:.1f} MB)", flush=True)
        adb("push", p, f"{remote}/")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--llama", action="store_true", help="fetch + push the Llama NPU bundle")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--no-pull", action="store_true")
    a = ap.parse_args()
    os.chdir(ROOT)
    if not a.no_pull:
        run(["git", "pull", "--ff-only"], check=False)
    if "device" not in adb("get-state", check=False, capture=True):
        sys.exit("QIDK not connected (adb get-state != device)")

    md = os.path.join(ROOT, "models_dist")
    w = os.path.join(md, "whisper", "ggml-small-q5_1.bin")
    if not os.path.exists(w):
        os.makedirs(os.path.dirname(w), exist_ok=True)
        print("downloading whisper small q5_1 (190 MB) ...", flush=True)
        urllib.request.urlretrieve(WHISPER_URL, w + ".part")
        os.replace(w + ".part", w)
    if a.llama:
        if not os.path.exists(os.path.join(md, "llama_meta", "bundle_manifest.json")):
            sys.exit("no models_dist/llama_meta/bundle_manifest.json yet: the ML export has not published the bundle")
        run([sys.executable, os.path.join(ROOT, "scripts", "fetch_llama_bundle.py"), os.path.join(md, "llama")])

    print("== models → QIDK")
    for sub in ("whisper", "dsp", "llama"):
        push_dir(os.path.join(md, sub), f"{DEV_MODELS}/{sub}")

    if not a.no_build:
        print("== build + install app")
        gw = os.path.join(ROOT, "android", "gradlew.bat" if WIN else "gradlew")
        if not WIN:
            os.chmod(gw, 0o755)
        run([gw, "-PwithAndroid=true", ":app:assembleDebug"], cwd=os.path.join(ROOT, "android"))
        apk = os.path.join(ROOT, "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
        adb("install", "-r", "-g", apk)

    adb("shell", "settings put secure tts_default_synth com.reecedunn.espeak", check=False)
    adb("shell", f"am force-stop {PKG}", check=False)
    adb("shell", f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1", check=False, capture=True)
    print("\n== done. In the app: Settings → Engine shows ASR + LLM backend "
          "(LLM = NPU once llama/ is on the board, else the rule-based stand-in).")
    print(adb("shell", f"ls {DEV_MODELS}/whisper {DEV_MODELS}/llama 2>/dev/null; ls {DEV_MODELS}/dsp | wc -l",
              check=False, capture=True))


if __name__ == "__main__":
    ADB = find_adb()
    main()
