#!/bin/bash
# Fine-tune data (PRD §27.2): template + augmentation, ft prompt variant, deduped vs eval. CPU-light, ~5 s.
JOB=ft_data; source $HOME/iv/jobs/common.sh
PY=$HOME/venvs/ml/bin/python; [ -x "$PY" ] || PY=python3
rm -rf $HOME/iv_ft/data   # clean overwrite (no stale files from an older generator)
cd "$REPO" && $PY ml/data_gen/gen_ft_data.py --out $HOME/iv_ft/data > $HOME/iv_ft/data_stats.txt 2>&1 \
  || mark_failed "gen_ft_data.py failed (see ~/iv_ft/data_stats.txt)"
status "data: $(tail -1 $HOME/iv_ft/data_stats.txt | cut -c1-160)"
mark_done
