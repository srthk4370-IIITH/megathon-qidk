"""Download the Llama 3.2 3B V79 Genie bundle from AI Hub on any laptop (no export needed).

    pip install qai-hub && qai-hub configure --api_token <token>     # same AI Hub account
    python scripts/fetch_llama_bundle.py models_dist/llama
    bash scripts/push_models_to_board.sh models_dist                  # → QIDK, then app: Settings → Engine → Reload

Small files (genie_config.json, tokenizer.json, ...) come from models_dist/llama_meta (git);
context binaries are the target models of the link jobs listed in bundle_manifest.json.
"""
import json
import os
import re
import shutil
import sys

import qai_hub as hub

here = os.path.dirname(os.path.abspath(__file__))
meta = os.path.join(here, "..", "models_dist", "llama_meta")
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "models_dist", "llama")
os.makedirs(out, exist_ok=True)
man = json.load(open(os.path.join(meta, "bundle_manifest.json")))
for f in os.listdir(meta):
    if f != "bundle_manifest.json":
        shutil.copy(os.path.join(meta, f), out)
bins = sorted(f for f in man["files"] if f.endswith(".bin"))


def part_no(s):
    m = re.search(r"part_?(\d+)_of_?(\d+)", s, re.I)
    return int(m.group(1)) if m else None


for key, jid in man["link_jobs"].items():
    n = part_no(key)
    target = next((b for b in bins if part_no(b) == n), None)
    if target is None:
        print(f"skip {key}: no matching .bin in manifest")
        continue
    dst = os.path.join(out, target)
    if os.path.exists(dst) and os.path.getsize(dst) == man["files"][target]:
        print(f"have {target}")
        continue
    print(f"downloading {target} from link job {jid} ...", flush=True)
    hub.get_job(jid).get_target_model().download(dst)
    ok = os.path.getsize(dst) == man["files"][target]
    print(f"  {target}: {os.path.getsize(dst)} bytes {'OK' if ok else 'SIZE MISMATCH'}")
missing = [f for f in man["files"] if not os.path.exists(os.path.join(out, f))]
print("bundle complete" if not missing else f"MISSING: {missing}")
