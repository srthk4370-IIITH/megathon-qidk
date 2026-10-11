#!/bin/bash
# usage: run.sh <tag> <model> <hint:0|1>
cd "$(dirname "$0")"
tag=$1; model=$2; hint=$3
mkdir -p out/$tag
for f in clips/*.wav live/*.wav; do
  b=$(basename "$f" .wav)
  if [ "$hint" = "1" ]; then whisper-cli -m "$model" -f "$f" -l en -nt -np -bs 1 -bo 1 -t 4 --prompt "$(cat prompt.txt)" 2>/dev/null | tr '\n' ' ' > out/$tag/$b.txt
  else whisper-cli -m "$model" -f "$f" -l en -nt -np -bs 1 -bo 1 -t 4 2>/dev/null | tr '\n' ' ' > out/$tag/$b.txt; fi
done
echo done > out/$tag/.done
