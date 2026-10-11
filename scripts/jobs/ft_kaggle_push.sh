#!/bin/bash
# Push training data as a PRIVATE Kaggle dataset + start the T4 training kernel (no tokens uploaded; weights come
# from Kaggle Models metaresearch/llama-3.2/transformers/3b-instruct/1). Idempotent; network errors → retried next tick.
JOB=ft_kaggle_push; source $HOME/iv/jobs/common.sh
K=$HOME/venvs/kaggle/bin/kaggle; DS=srthk4370/iv-ft-data; KID=srthk4370/iv-ft-train
retry_later(){ status "kaggle network error ($*) — retry next tick"; rm -f "$ST/$JOB.pid"; exit 1; }
kg(){ local out i; for i in 1 2 3 4 5 6 7 8; do
    out=$(timeout 300 $K "$@" 2>&1)
    echo "$out" | grep -qE "SSLError|ConnectionError|ReadTimeout|Max retries|50[234] " || { echo "$out"; return 0; }
    sleep 20; done; echo "$out" | tail -1; return 1; }
D=$HOME/iv_ft/kaggle_ds
# re-upload whenever the data changed (ft_dataset_ok holds the md5 of the uploaded train.jsonl)
H=$(cat $HOME/iv_ft/data/train.jsonl $HOME/iv_ft/adapter/adapter_model.safetensors 2>/dev/null | md5sum | cut -c1-32)
if [ "$(cat "$ST/ft_dataset_ok" 2>/dev/null)" != "$H" ]; then
  rm -rf "$D"; mkdir -p "$D"
  cp $HOME/iv_ft/data/{train,dev}.jsonl $HOME/iv_ft/data/stats.json "$D/" || mark_failed "no data"
  if [ -f $HOME/iv_ft/adapter/adapter_model.safetensors ]; then
    # continuation run (v2+): train only on small talk (NO_INCIDENT) + mixed + 600-sample replay, from the current
    # adapter (train_lora.py: half LR, 24 min incl. load). Adapter files go flat (datasets drop sub-dirs).
    $HOME/venvs/ml/bin/python - "$D/train.jsonl" <<'PY' || mark_failed "v2 subset"
import json, random, sys
p = sys.argv[1]
rows = [json.loads(l) for l in open(p, encoding="utf-8")]
new = [r for r in rows if r.get("category") == "NO_INCIDENT" or r.get("mixed_small_talk")]
old = [r for r in rows if not (r.get("category") == "NO_INCIDENT" or r.get("mixed_small_talk"))]
rng = random.Random(11); sub = new + rng.sample(old, min(600, len(old))); rng.shuffle(sub)
open(p, "w", encoding="utf-8", newline="\n").writelines(json.dumps(r, ensure_ascii=False) + "\n" for r in sub)
print("v2 subset:", len(new), "new +", len(sub) - len(new), "replay")
PY
    for f in adapter_config.json adapter_model.safetensors; do cp $HOME/iv_ft/adapter/$f "$D/init_adapter__$f"; done
  fi
  echo "{\"title\":\"iv-ft-data\",\"id\":\"$DS\",\"licenses\":[{\"name\":\"other\"}]}" > "$D/dataset-metadata.json"
  st=$(kg datasets status $DS) || retry_later "status"
  if echo "$st" | grep -qi ready; then o=$(kg datasets version -p "$D" -m "regen $(date +%F_%T)") || retry_later version
  else o=$(kg datasets create -p "$D") || retry_later create; fi
  echo "$o" | tail -2
  sleep 60   # a new version needs processing; "ready" right away may still be the previous version
  for i in $(seq 1 40); do sleep 15; kg datasets status $DS | grep -qi ready && break; done
  kg datasets status $DS | grep -qi ready || retry_later "dataset not ready"
  echo "$H" > "$ST/ft_dataset_ok"; status "kaggle dataset $DS ready (private, train md5 $H)"
fi
KD=$HOME/iv_ft/kaggle_kernel; rm -rf "$KD"; mkdir -p "$KD"
cp "$REPO/ml/ft/train_lora.py" "$KD/"; sed -i 's/\r$//' "$KD/train_lora.py"
cat > "$KD/kernel-metadata.json" <<J
{"id":"$KID","title":"iv-ft-train","code_file":"train_lora.py","language":"python","kernel_type":"script",
 "is_private":true,"enable_gpu":true,"enable_tpu":false,"machine_shape":"NvidiaTeslaT4","enable_internet":true,
 "dataset_sources":["$DS"],"model_sources":["metaresearch/llama-3.2/transformers/3b-instruct/1"],
 "competition_sources":[],"kernel_sources":[]}
J
out=$(kg kernels push -p "$KD") || retry_later "push"
echo "$out"
echo "$out" | grep -qiE "successfully|pushed|version" || mark_failed "kernel push: $(echo $out | cut -c1-200)"
date +%s > "$ST/ft_kaggle_pushed"; wc -l < "$D/train.jsonl" | tr -d ' ' > "$ST/ft_kaggle_n_train"
rm -f "$ST/ft_kaggle_fetch.failed" "$ST/ft_kaggle_fetch.done"
status "kaggle training kernel pushed: https://www.kaggle.com/code/$KID"
mark_done
