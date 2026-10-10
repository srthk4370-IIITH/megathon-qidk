# Model files for the QIDK (app external files dir)
Target on the board: `/sdcard/Android/data/com.megathon.incidentvoice/files/models/{whisper,dsp,llama}`

- `dsp/` — QAIRT 2.50 Hexagon V79 DSP libs (committed here).
- `whisper/ggml-small-q5_1.bin` — 190 MB, too big for GitHub. Download:
  `curl -L -o whisper/ggml-small-q5_1.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin`
- `llama/` — Llama 3.2 3B Instruct Genie bundle (w4a16, V79), ~2.4 GB, produced by the ML export
  (`scripts/jobs/export_llama.sh`); not in git.

Push (any laptop with adb): `bash scripts/push_models_to_board.sh models_dist` (after downloading whisper/).
Never uninstall the app (wipes this folder); reinstall with `adb install -r`.
