#!/usr/bin/env bash
# Convert recorded clips (any format) in eval/clips/raw/ to 16 kHz mono PCM16 WAV: eval/clips/clip_NN.wav
# Run from WSL:  bash eval/convert_clips.sh
# ffmpeg source, in order: system ffmpeg -> `sudo -n apt-get install ffmpeg` (only if passwordless) ->
# static ffmpeg from the pip package imageio-ffmpeg in ~/venvs/ml (no sudo needed).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAW="$HERE/clips/raw"
OUT="$HERE/clips"
mkdir -p "$RAW" "$OUT"

FFMPEG="$(command -v ffmpeg || true)"
if [ -z "$FFMPEG" ] && sudo -n true 2>/dev/null; then
  echo "[convert] installing ffmpeg via apt" >&2
  sudo -n apt-get install -y ffmpeg >/dev/null && FFMPEG="$(command -v ffmpeg || true)"
fi
if [ -z "$FFMPEG" ]; then
  VENV="${IV_ML_VENV:-$HOME/venvs/ml}"
  [ -x "$VENV/bin/python" ] || python3 -m venv "$VENV"
  "$VENV/bin/python" -c "import imageio_ffmpeg" 2>/dev/null || "$VENV/bin/pip" install -q imageio-ffmpeg
  FFMPEG="$("$VENV/bin/python" -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())')"
fi
echo "[convert] using $FFMPEG" >&2

n=0
shopt -s nullglob nocaseglob
for f in "$RAW"/clip_*.*; do
  base="$(basename "$f")"
  id="${base%.*}"
  if ! [[ "$id" =~ ^clip_[0-9]{2}$ ]]; then
    echo "[convert] skip $base (name must be clip_NN.<ext>)" >&2
    continue
  fi
  "$FFMPEG" -hide_banner -loglevel error -y -i "$f" -ac 1 -ar 16000 -c:a pcm_s16le -map_metadata -1 "$OUT/$id.wav"
  # `ffmpeg -i` without an output exits 1 by design; don't let pipefail/set -e kill the loop
  dur="$( { "$FFMPEG" -hide_banner -i "$OUT/$id.wav" 2>&1 || true; } | sed -n 's/.*Duration: \([0-9:.]*\).*/\1/p')"
  echo "$id.wav  $dur"
  n=$((n + 1))
done
echo "[convert] $n clip(s) written to $OUT" >&2
