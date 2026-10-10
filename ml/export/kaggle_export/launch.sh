#!/bin/bash
# Run by a human (needs to upload the AI Hub + HF tokens to a PRIVATE Kaggle dataset):
#   wsl -d Ubuntu -- bash /mnt/c/Users/hp/Desktop/Personal-Projects/megathon-qidk/ml/export/kaggle_export/launch.sh
# Creates srthk4370/iv-secrets (private), then pushes + starts the export kernel.
# Delete afterwards: ~/venvs/kaggle/bin/kaggle datasets delete srthk4370/iv-secrets
K=~/venvs/kaggle/bin/kaggle
HERE=/mnt/c/Users/hp/Desktop/Personal-Projects/megathon-qidk/ml/export/kaggle_export
S=~/iv/kaggle_secrets; rm -rf "$S"; mkdir -p "$S"; chmod 700 "$S"
cp ~/.qai_hub/client.ini "$S/"; cp ~/.cache/huggingface/token "$S/hf_token"
echo '{"title":"iv-secrets","id":"srthk4370/iv-secrets","licenses":[{"name":"other"}]}' > "$S/dataset-metadata.json"
$K datasets create -p "$S" 2>&1 | tail -2
rm -f "$S/client.ini" "$S/hf_token"
for i in $(seq 1 12); do
  sleep 10; st=$($K datasets status srthk4370/iv-secrets 2>&1); echo "dataset: $st"
  echo "$st" | grep -qi ready && break
done
K2=~/iv/kaggle_kernel; rm -rf "$K2"; mkdir -p "$K2"
cp "$HERE/export.py" "$HERE/kernel-metadata.json" "$K2/"; sed -i 's/\r$//' "$K2"/*
$K kernels push -p "$K2"
echo "started: https://www.kaggle.com/code/srthk4370/iv-llama-v79-export"
