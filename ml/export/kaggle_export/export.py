# Kaggle kernel: Llama 3.2 3B Instruct -> Genie bundle (w4a16, HTP V79) via AI Hub.
# Runs where the uplink is fast; the laptop downloads the finished bundle (kernel output).
# Secrets come from the private dataset <user>/iv-secrets (client.ini, hf_token).
import glob
import os
import shutil
import subprocess
import sys
import time


def sh(c):
    print(f"$ {c}", flush=True)
    r = subprocess.run(c, shell=True)
    if r.returncode:
        print(f"rc={r.returncode}", flush=True)
    return r.returncode


S = glob.glob("/kaggle/input/**/client.ini", recursive=True)[0]
D = os.path.dirname(S)
os.makedirs(os.path.expanduser("~/.qai_hub"), exist_ok=True)
shutil.copy(S, os.path.expanduser("~/.qai_hub/client.ini"))
os.environ["HF_TOKEN"] = open(os.path.join(D, "hf_token")).read().strip()
sh("free -g; nproc; df -h / /tmp /kaggle/working; python -V")
sh("pip install -q torch==2.10.0 torchvision==0.25.0 --index-url https://download.pytorch.org/whl/cpu")
sh("pip install -q 'qai-hub-models[llama-v3-2-3b-instruct]==0.63.0'")
sh("pip list 2>/dev/null | grep -E '^(torch|transformers|qai.hub) '")
# TPU VM (~330 GB RAM): no swap needed; the CPU box (31 GB, no swap allowed) OOM-kills the export
W = "/kaggle/temp" if os.path.isdir("/kaggle/temp") else "/tmp/iv"
os.makedirs(W, exist_ok=True)
os.environ["TMPDIR"] = f"{W}/tmp"
os.makedirs(f"{W}/tmp", exist_ok=True)
out = f"{W}/genie"
t = time.time()
rc = sh("qai-hub-models export llama_v3_2_3b_instruct --device 'Samsung Galaxy S25 (Family)' "
        f"--target-runtime genie --skip-profiling --output-dir {out} 2>&1 "
        "| tr '\\r' '\\n' | grep -v W1010 | grep -v 'it/s]$'")
print(f"export rc={rc} in {time.time() - t:.0f}s", flush=True)
sh(f"find {out} -maxdepth 3 -type f -exec ls -la {{}} \\;")
cfg = glob.glob(f"{out}/**/genie_config.json", recursive=True)
if not cfg:
    print("NO BUNDLE", flush=True)
    sys.exit(1)
sh(f"cd {os.path.dirname(cfg[0])} && tar cf /kaggle/working/llama_v79_bundle.tar . && ls -la /kaggle/working")
