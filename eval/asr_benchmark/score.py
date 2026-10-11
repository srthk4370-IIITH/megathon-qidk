#!/usr/bin/env python3
"""Scores whisper.cpp outputs against reference transcripts. usage: score.py <dir with out/<config>/<clip>.txt>
Word error rate = (substitutions + deletions + insertions) / reference words, summed over the clips of a set.
Normalisation: lower-case, punctuation removed, digits split from letters ("10am" -> "10 am"), nothing else
(so "11" vs "eleven" counts as an error)."""
import json, re, sys, os, csv, statistics
root = sys.argv[1]; here = os.path.dirname(os.path.abspath(__file__))
def norm(s):
    s = s.lower(); s = re.sub(r"(\d)([a-z])", r"\1 \2", s); s = re.sub(r"([a-z])(\d)", r"\1 \2", s)
    return re.sub(r"[^a-z0-9 ]+", " ", s.replace("'", "")).split()
def edits(r, h):
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        p, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            p, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, p + (r[i - 1] != h[j - 1]))
    return d[len(h)]
refs = {}
for l in open(os.path.join(here, "..", "clips.jsonl")):
    o = json.loads(l); refs[o["id"]] = ("scripted " + ("English" if o["lang"] == "en" else "Hinglish"), o["gold_transcript"])
for l in open(os.path.join(here, "live_refs.jsonl")):
    o = json.loads(l); refs[o["id"]] = ("live English (board mic)", o["ref"])
configs = [("tiny.en", "tiny"), ("base.en", "base"), ("small q5_1 (multilingual, forced English)", "small")]
rows = []
for name, key in configs:
    for hint in (0, 1):
        tag = f"{key}_{'hint' if hint else 'nohint'}"; agg = {}
        for cid, (grp, ref) in refs.items():
            f = os.path.join(root, "out", tag, cid + ".txt")
            hyp = open(f).read() if os.path.exists(f) else ""
            r = norm(ref); e = edits(r, norm(hyp))
            a = agg.setdefault(grp, [0, 0, 0, 0]); a[0] += e; a[1] += len(r); a[2] += 1; a[3] += (e == 0)
        for grp, (e, n, c, exact) in sorted(agg.items()):
            rows.append({"model": name, "domain_hint": "yes" if hint else "no", "set": grp, "clips": c, "ref_words": n, "word_errors": e, "wer_pct": round(100 * e / n, 1), "clips_exact": exact})
w = csv.DictWriter(open(os.path.join(here, "..", "asr_benchmark.csv"), "w", newline=""), fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)
t = {}
for l in open(os.path.join(here, "board_timing.jsonl")):
    o = json.loads(l); t.setdefault((o["model"], o["hint"]), []).append(o["timings"]["total_ms"])
json.dump({"rows": rows, "timing": {f"{k[0]}_{k[1]}": [round(statistics.median(v)), round(min(v)), round(max(v)), len(v)] for k, v in t.items()}}, sys.stdout)
