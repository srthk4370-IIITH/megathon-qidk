#!/bin/bash
# ASR evaluation on the board with the team's 30 recorded clips (PRD §29.2): en / hi / auto language modes.
# Picks the default language mode (lowest error: WER for EN clips, CER for Hinglish) → ~/iv/state/asr_lang_mode.
JOB=asr_eval; source $HOME/iv/jobs/common.sh
wait_board
cd "$REPO"; PY=$HOME/venvs/ml/bin/python; [ -x "$PY" ] || PY=python3
ls eval/clips/raw/* >/dev/null 2>&1 || mark_failed "no clips in eval/clips/raw"
sed -i 's/\r$//' eval/convert_clips.sh; bash eval/convert_clips.sh || mark_failed "clip conversion failed"
for lang in en hi auto; do
  RUN=asr_$lang
  $PY eval/run_device.py --run "$RUN" --asr --asr-lang $lang || status "run_device asr $lang non-zero"
  $PY eval/score.py --run "eval/results/$RUN" > "eval/results/$RUN/score_stdout.txt" 2>&1 || true
  status "ASR $lang: $(grep -iE 'wer|cer' "eval/results/$RUN/report.md" 2>/dev/null | head -4 | tr '\n' ' ' | cut -c1-300)"
done
# Pick the mode with the lowest mean error (metrics.json keys from eval/score.py)
BEST=$($PY - <<'EOF'
import json, os
best, bv = "auto", 9e9
for m in ("en", "hi", "auto"):
    p = f"eval/results/asr_{m}/metrics.json"
    if not os.path.exists(p): continue
    a = json.load(open(p)).get("asr", {})   # {lang: {n, wer, cer, asr_ms_p50, ...}}
    # English clips judged by WER, Hinglish/Hindi by CER (spelling of Roman Hinglish is not standard)
    vals = [(d.get("wer") if lang == "en" else d.get("cer")) for lang, d in a.items() if isinstance(d, dict)]
    vals = [v for v in vals if isinstance(v, (int, float))]
    if vals and sum(vals) / len(vals) < bv: best, bv = m, sum(vals) / len(vals)
print(best)
EOF
)
echo "$BEST" > "$ST/asr_lang_mode"
status "ASR default language mode chosen: $BEST (see eval/results/asr_*/report.md)"
mark_done
