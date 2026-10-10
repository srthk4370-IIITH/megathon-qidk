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
if [ ! -f "$ST/ft_dataset_ok" ]; then
  rm -rf "$D"; mkdir -p "$D"
  cp $HOME/iv_ft/data/{train,dev}.jsonl $HOME/iv_ft/data/stats.json "$D/" || mark_failed "no data"
  echo "{\"title\":\"iv-ft-data\",\"id\":\"$DS\",\"licenses\":[{\"name\":\"other\"}]}" > "$D/dataset-metadata.json"
  st=$(kg datasets status $DS) || retry_later "status"
  if echo "$st" | grep -qi ready; then o=$(kg datasets version -p "$D" -m "regen $(date +%F_%T)") || retry_later version
  else o=$(kg datasets create -p "$D") || retry_later create; fi
  echo "$o" | tail -2
  for i in $(seq 1 40); do sleep 15; kg datasets status $DS | grep -qi ready && break; done
  kg datasets status $DS | grep -qi ready || retry_later "dataset not ready"
  touch "$ST/ft_dataset_ok"; status "kaggle dataset $DS ready (private)"
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
date +%s > "$ST/ft_kaggle_pushed"; rm -f "$ST/ft_kaggle_fetch.failed" "$ST/ft_kaggle_fetch.done"
status "kaggle training kernel pushed: https://www.kaggle.com/code/$KID"
mark_done
