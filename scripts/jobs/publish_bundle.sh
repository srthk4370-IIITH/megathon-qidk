#!/bin/bash
# After export: verify the Llama V79 bundle, copy it to C:\iv_models\llama (USB/share to any laptop),
# and commit models_dist/llama_meta (small files + AI Hub link-job ids) so ANY laptop can fetch the
# context binaries straight from AI Hub: python scripts/fetch_llama_bundle.py <out_dir>
JOB=publish_bundle; source $HOME/iv/jobs/common.sh
ROOT=$(cat "$ST/llama_bundle_root")
B=$(find "$ROOT" -name genie_config.json -printf '%h\n' | head -1)
[ -n "$B" ] || mark_failed "no genie_config.json under $ROOT"
python3 "$HOME/iv/jobs/verify_bundle.py" "$B" --write-manifest || mark_failed "bundle verification failed"
status "bundle verified: $B ($(du -sh "$B" | cut -f1))"
mkdir -p /mnt/c/iv_models/llama && cp -u "$B"/* /mnt/c/iv_models/llama/ && status "bundle copied to C:\\iv_models\\llama"
META=$REPO/models_dist/llama_meta; mkdir -p "$META"
find "$B" -maxdepth 1 -type f -size -20M -exec cp {} "$META/" \;
python3 - "$B" "$ST/hub_cache.json" "$META/bundle_manifest.json" <<'EOF'
import json, os, sys, hashlib
b, cache, out = sys.argv[1:]
jobs = {k: v for k, v in json.load(open(cache)).get("jobs", {}).items() if k.startswith("submit_link_job")}
files = {f: os.path.getsize(os.path.join(b, f)) for f in sorted(os.listdir(b))}
json.dump({"link_jobs": jobs, "files": files,
           "note": "ctx .bin files = AI Hub link-job target models; small files are in this folder"},
          open(out, "w"), indent=1)
print("link jobs:", len(jobs), "files:", len(files))
EOF
cd "$REPO" && G="/mnt/c/Program Files/Git/cmd/git.exe"
"$G" add models_dist/llama_meta && "$G" commit -q -m "models: Llama V79 bundle metadata + AI Hub link-job ids (fetch_llama_bundle.py)" \
  && { "$G" push -q origin HEAD || "$G" push -q origin HEAD; } && status "bundle metadata pushed to GitHub (branch $("$G" rev-parse --abbrev-ref HEAD))"
mark_done
