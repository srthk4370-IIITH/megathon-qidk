"""Score predictions against gold (PRD §29.2–§29.4). Writes report.md + metrics.json into the run dir.

  python eval/score.py --run eval/results/<run>                    # uses <run>/predictions.jsonl (+ asr.jsonl)
  python eval/score.py --cases eval/clips.jsonl --run <dir>          # clip set (end-to-end: transcript = ASR output)
  python eval/score.py --self-test                                   # gold-vs-gold (perfect) + perturbed copy

predictions.jsonl, one line per case:
  {"id": "...", "extract": {"text": raw_llm_output, "stats": GenStats?}, "extract_repair": {...}?,
   "patch": {"text": ..., "stats": ...}?, "patch_repair": {...}?, "transcript": "<ASR text, clips only>"?}
asr.jsonl (clips): {"id": "clip_01", "text": "...", "lang": "en", "timings": {...}}

Metric definitions (§29.3):
  type accuracy        incident_type exact
  secondary F1         micro set-F1 over secondary_types
  per-field F1         all fields except incident_type/secondary_types/title/description/lang. enums/bool/int exact;
                       strings fuzzy token-set >= 0.8 (lower-case, number words -> digits); lists: set-F1 with fuzzy
                       element match (persons/witnesses by name); time: resolved instants within ±15 min or same
                       day-part when approximate (Python port of the TimeResolver). Micro over all units + macro per field.
  severity             exact and within ±1 level (cases where both present)
  mapping accuracy     over predicted *text* values that match some gold text value: fraction found under the
                       correct key (enums are excluded: YES/NO match too many keys to attribute)
  hallucination rate   predicted field keys absent from gold / predicted field keys, split by field kind
  mandatory completeness  satisfied/required (required from the GOLD types) after extraction, and after the one
                       scripted follow-up round (fraction fully complete)
  JSON validity        parsed without repair (lenient parser counts) / with repair; strict = raw text was JSON as-is
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from ml.common.config import taxonomy  # noqa: E402
from ml.common.form import Form  # noqa: E402
from ml.common.fuzzy import token_set_ratio  # noqa: E402
from ml.common.parser import is_no_incident, parse  # noqa: E402
from ml.common.timeres import resolve, times_match  # noqa: E402
from ml.common.validator import SEVERITY_ORDER, _valid  # noqa: E402

SKIP_FIELDS = {"incident_type", "secondary_types", "title", "description", "lang"}
NUMW = {"zero": "0", "one": "1", "two": "2", "three": "3", "four": "4", "five": "5", "six": "6", "seven": "7",
        "eight": "8", "nine": "9", "ten": "10", "eleven": "11", "twelve": "12", "fifteen": "15", "twenty": "20",
        "ek": "1", "do": "2", "teen": "3", "char": "4", "paanch": "5", "das": "10"}


# ---------------------------------------------------------------------------------------------------
def load_jsonl(p):
    with open(p, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


def norm_s(s: str) -> str:
    s = str(s).lower()
    s = re.sub(r"[^\w\s\-]", " ", s)
    toks = [NUMW.get(t, t) for t in s.replace("-", " - ").split()]
    return re.sub(r"\s*-\s*", "-", " ".join(toks)).strip()


def str_match(a, b) -> bool:
    a, b = norm_s(a), norm_s(b)
    if not a or not b:
        return False
    return a == b or token_set_ratio(a, b) >= 0.8


def kind_of(key: str) -> str:
    tx = taxonomy()
    if key == "time":
        return "time"
    f = tx.field(key)
    if f is None:
        return "other"
    return {"enum": "enum", "enum_category": "enum", "bool": "bool", "int": "int", "string": "string",
            "string_list": "list", "person_list": "persons", "witness_list": "persons",
            "enum_category_list": "enum"}.get(f.type, "other")


def flatten(params: dict) -> dict:
    out = {k: v for k, v in params.items() if k != "details" and k not in SKIP_FIELDS}
    out.update(params.get("details") or {})
    return out


def list_items(key, v):
    if kind_of(key) == "persons":
        return [p.get("name") or ("<" + p.get("role", "?") + ">") for p in (v or []) if isinstance(p, dict)]
    return list(v) if isinstance(v, list) else [v]


def list_prf(gl, pl):
    used = set()
    tp = 0
    for p in pl:
        for j, g in enumerate(gl):
            if j not in used and str_match(p, g):
                used.add(j)
                tp += 1
                break
    return tp, len(pl) - tp, len(gl) - tp


def compare_field(key, g, p, now) -> tuple[int, int, int]:
    """(tp, fp, fn) for one field present in gold and/or prediction."""
    k = kind_of(key)
    if g is None and p is None:
        return 0, 0, 0
    if k in ("list", "persons"):
        gl = list_items(key, g) if g is not None else []
        pl = list_items(key, p) if p is not None else []
        return list_prf(gl, pl)
    if g is None:
        return 0, 1, 0
    if p is None:
        return 0, 0, 1
    if k == "time":
        ok = times_match(resolve(g, now), resolve(p, now))
    elif k == "string":
        ok = str_match(g, p)
    else:
        ok = (g == p)
    return (1, 0, 0) if ok else (0, 1, 1)


def f1(tp, fp, fn):
    p = tp / (tp + fp) if tp + fp else (1.0 if fn == 0 else 0.0)
    r = tp / (tp + fn) if tp + fn else 1.0
    return (2 * p * r / (p + r)) if p + r else 0.0, p, r


def pct(xs, q):
    xs = sorted(x for x in xs if x is not None)
    if not xs:
        return None
    i = (len(xs) - 1) * q
    lo, hi = math.floor(i), math.ceil(i)
    return xs[lo] + (xs[hi] - xs[lo]) * (i - lo)


def text_values(flat):
    out = []
    for k, v in flat.items():
        kd = kind_of(k)
        if kd == "string":
            out.append((k, v))
        elif kd in ("list", "persons"):
            out += [(k, x) for x in list_items(k, v) if not str(x).startswith("<")]
    return out


# ---------------------------------------------------------------------------------------------------
# ASR
# ---------------------------------------------------------------------------------------------------
HI_UNIFY = [(r"\bhe\b", "hai"), (r"\bhain\b", "hai"), (r"\bnahin\b", "nahi"), (r"\bnai\b", "nahi"), (r"\bkia\b", "kya"),
            (r"\bmein\b", "me"), (r"\bmai\b", "me"), (r"\bgya\b", "gaya"), (r"\bgyi\b", "gayi"), (r"\brha\b", "raha"),
            (r"\bkr\b", "kar"), (r"\bh\b", "hai")]


def asr_norm(s: str, lang: str) -> str:
    s = s.lower()
    s = re.sub(r"[^\w\s]", " ", s)
    s = " ".join(NUMW.get(t, t) for t in s.split())
    if lang != "en":
        for a, b in HI_UNIFY:
            s = re.sub(a, b, s)
    return re.sub(r"\s+", " ", s).strip()


def asr_metrics(cases, asr_rows):
    try:
        import jiwer
    except Exception:
        jiwer = None
    by = {r["id"]: r for r in asr_rows}
    res = {}
    for lang in ("en", "hinglish"):
        refs, hyps, lat = [], [], []
        for c in cases:
            if c["lang"] != lang or c["id"] not in by:
                continue
            refs.append(asr_norm(c.get("gold_transcript", c["transcript"]), lang))
            hyps.append(asr_norm(by[c["id"]].get("text", ""), lang))
            lat.append((by[c["id"]].get("timings") or {}).get("total_ms"))
        if not refs:
            continue
        if jiwer:
            wer, cer = jiwer.wer(refs, hyps), jiwer.cer(refs, hyps)
        else:
            wer = cer = None
        res[lang] = {"n": len(refs), "wer": wer, "cer": cer, "asr_ms_p50": pct(lat, 0.5), "asr_ms_p90": pct(lat, 0.9)}
    return res


# ---------------------------------------------------------------------------------------------------
def parse_with_repair(pred_entry, repair_entry, tool, transcript):
    first = parse((pred_entry or {}).get("text") or "", tool, transcript=transcript)
    info = {"present": pred_entry is not None, "ok_first": first.ok, "strict": first.strict_json,
            "lenient": first.lenient_fixed, "autoclosed": first.autoclosed, "ok_repair": first.ok}
    pc = first
    if not first.ok and repair_entry is not None:
        rep = parse(repair_entry.get("text") or "", tool, transcript=transcript)
        info["ok_repair"] = rep.ok
        if rep.ok:
            pc = rep
    return pc, info


def score(cases, preds, asr_rows=None):
    tx = taxonomy()
    P = {p["id"]: p for p in preds}
    agg = defaultdict(lambda: [0, 0, 0])            # per-field tp/fp/fn
    micro = [0, 0, 0]
    sec = [0, 0, 0]
    halluc = Counter(); predicted_kinds = Counter()
    mapping = [0, 0]
    sev_exact = sev_pm1 = sev_n = 0
    type_ok = 0
    n = 0
    lang_ok = 0
    json_stats = Counter()
    complete_extract, complete_after = [], []
    patch_micro = [0, 0, 0]; patch_unknown = [0, 0, 0]; patch_skip_ok = 0; patch_n = 0; patch_json = Counter()
    by_group = defaultdict(lambda: {"n": 0, "type_ok": 0, "f": [0, 0, 0]})
    per_case = []
    latency = defaultdict(lambda: defaultdict(list))
    missing_preds = 0
    ni_n = ni_ok = ni_false_reject = 0
    for c in cases:
        p = P.get(c["id"])
        if p is None:
            missing_preds += 1
            continue
        n += 1
        now = datetime.fromisoformat(c["now"])
        transcript = p.get("transcript") or c["transcript"]
        pc, info = parse_with_repair(p.get("extract"), p.get("extract_repair"), "fill_incident_form", transcript)
        for k, v in info.items():
            json_stats[k] += int(bool(v))
        for task in ("extract", "extract_repair", "patch", "patch_repair"):
            st = (p.get(task) or {}).get("stats") or {}
            for m in ("ttft_ms", "wall_ms", "decode_tps", "prefill_tps", "tokens_in", "tokens_out"):
                if st.get(m) is not None:
                    latency[task][m].append(st[m])
        gold = c["gold"]
        pred = pc.params if pc.ok else {}
        # NO_INCIDENT sentinel (small talk / mic test): gold-NI case is correct iff the prediction is NI too;
        # a real incident predicted as NI is a false rejection (scored as an empty prediction below)
        g_ni, p_ni = is_no_incident(gold), pc.ok and is_no_incident(pred)
        if g_ni:
            ni_n += 1; ni_ok += p_ni; type_ok += p_ni; lang_ok += p_ni and pred.get("lang") == gold.get("lang")
            g = by_group[f"lang={c['lang']}"]; g["n"] += 1; g["type_ok"] += p_ni
            g = by_group["cat=NO_INCIDENT"]; g["n"] += 1; g["type_ok"] += p_ni
            per_case.append({"id": c["id"], "type_ok": bool(p_ni), "f1": 1.0 if p_ni else 0.0, "json_ok": info["ok_first"],
                             "no_incident": True, "pred_type": pred.get("incident_type"),
                             "issues": [repr(i) for i in pc.issues][:8]})
            continue
        if p_ni:
            ni_false_reject += 1
            pred = {}
        t_ok = pred.get("incident_type") == gold.get("incident_type")
        type_ok += t_ok
        lang_ok += pred.get("lang") == gold.get("lang")
        gs, ps = set(gold.get("secondary_types") or []), set(pred.get("secondary_types") or [])
        sec[0] += len(gs & ps); sec[1] += len(ps - gs); sec[2] += len(gs - ps)
        gf, pf = flatten(gold), flatten(pred)
        cf = [0, 0, 0]
        for key in sorted(set(gf) | set(pf)):
            tp, fp, fn = compare_field(key, gf.get(key), pf.get(key), now)
            for i, x in enumerate((tp, fp, fn)):
                agg[key][i] += x; micro[i] += x; cf[i] += x
            if key in pf:
                predicted_kinds[kind_of(key)] += 1
                if key not in gf:
                    halluc[kind_of(key)] += 1
        # severity
        if "severity" in gold and "severity" in pred:
            sev_n += 1
            gi, pi = SEVERITY_ORDER.index(gold["severity"]), SEVERITY_ORDER.index(pred["severity"])
            sev_exact += gi == pi
            sev_pm1 += abs(gi - pi) <= 1
        # mapping
        gtv = text_values(gf)
        for k, v in text_values(pf):
            if any(gk == k and str_match(v, gv) for gk, gv in gtv):
                mapping[0] += 1
            elif any(str_match(v, gv) for gk, gv in gtv):
                mapping[1] += 1
        # completeness (required from gold types)
        gold_form = Form.from_params(gold, now=now)
        req = tx.mandatory_for(gold_form.types())
        form = Form.from_params(pred, now=now) if pc.ok else Form.from_params(
            {"incident_type": "OTHER", "description": transcript}, now=now)
        sat = sum(1 for k in req if _valid(k, form, tx, now) is None)
        complete_extract.append(sat / len(req))
        # follow-up
        fu = c.get("followup")
        if fu:
            patch_n += 1
            ppc, pinfo = parse_with_repair(p.get("patch"), p.get("patch_repair"), "update_incident_fields",
                                           [transcript, fu["answer"]])
            for k, v in pinfo.items():
                patch_json[k] += int(bool(v))
            gp = fu["gold_patch"]
            pp = ppc.params if ppc.ok else {"set": {}}
            gset, pset = gp.get("set", {}), pp.get("set", {})
            for key in set(gset) | set(pset):
                tp, fp, fn = compare_field(key, gset.get(key), pset.get(key), now)
                patch_micro[0] += tp; patch_micro[1] += fp; patch_micro[2] += fn
            gu, pu = set(gp.get("unknown", [])), set(pp.get("unknown", []))
            patch_unknown[0] += len(gu & pu); patch_unknown[1] += len(pu - gu); patch_unknown[2] += len(gu - pu)
            patch_skip_ok += bool(gp.get("skipped")) == bool(pp.get("skipped"))
            form2 = form.copy()
            form2.apply_patch(pp)
            complete_after.append(all(_valid(k, form2, tx, now) is None for k in req))
        for gname in (f"lang={c['lang']}", f"style={c['style']}", f"cat={c['category']}"):
            g = by_group[gname]
            g["n"] += 1; g["type_ok"] += t_ok
            for i in range(3):
                g["f"][i] += cf[i]
        per_case.append({"id": c["id"], "type_ok": t_ok, "f1": round(f1(*cf)[0], 3), "json_ok": info["ok_first"],
                         "issues": [repr(i) for i in pc.issues][:8]})
    mf1, mp, mr = f1(*micro)
    macro = {k: round(f1(*v)[0], 3) for k, v in sorted(agg.items())}
    m = {
        "n_cases": n, "missing_predictions": missing_preds,
        "type_accuracy": type_ok / n if n else None,
        "lang_accuracy": lang_ok / n if n else None,
        "secondary_f1": f1(*sec)[0], "secondary_counts": sec,
        "field_f1_micro": mf1, "field_precision_micro": mp, "field_recall_micro": mr,
        "field_f1_macro": (sum(macro.values()) / len(macro)) if macro else None,
        "field_f1_per_field": macro, "field_counts": {k: v for k, v in sorted(agg.items())},
        "severity_exact": sev_exact / sev_n if sev_n else None, "severity_within_1": sev_pm1 / sev_n if sev_n else None,
        "severity_n": sev_n,
        "mapping_accuracy": mapping[0] / sum(mapping) if sum(mapping) else None, "mapping_counts": mapping,
        "hallucination_rate": sum(halluc.values()) / sum(predicted_kinds.values()) if predicted_kinds else None,
        "hallucination_by_kind": {k: {"halluc": halluc[k], "predicted": predicted_kinds[k],
                                      "rate": halluc[k] / predicted_kinds[k]} for k in predicted_kinds},
        "mandatory_completeness_after_extract": sum(complete_extract) / len(complete_extract) if complete_extract else None,
        "complete_after_followup_rate": sum(complete_after) / len(complete_after) if complete_after else None,
        "json_valid_without_repair": json_stats["ok_first"] / n if n else None,
        "json_valid_with_repair": json_stats["ok_repair"] / n if n else None,
        "json_strict": json_stats["strict"] / n if n else None,
        "json_lenient_fixed": json_stats["lenient"] / n if n else None,
        "json_autoclosed": json_stats["autoclosed"] / n if n else None,
        "patch": {"n": patch_n, "set_f1": f1(*patch_micro)[0], "set_counts": patch_micro,
                  "unknown_f1": f1(*patch_unknown)[0], "skipped_accuracy": patch_skip_ok / patch_n if patch_n else None,
                  "json_valid_without_repair": patch_json["ok_first"] / patch_n if patch_n else None,
                  "json_valid_with_repair": patch_json["ok_repair"] / patch_n if patch_n else None},
        "groups": {k: {"n": v["n"], "type_accuracy": v["type_ok"] / v["n"], "field_f1": f1(*v["f"])[0]}
                   for k, v in sorted(by_group.items())},
        "no_incident": {"n": ni_n, "accuracy": ni_ok / ni_n if ni_n else None,
                        "false_reject_n": ni_false_reject,
                        "false_reject_rate": ni_false_reject / (n - ni_n) if n - ni_n else None},
        "latency": {t: {m_: {"p50": pct(v, 0.5), "p90": pct(v, 0.9), "n": len(v)} for m_, v in ms.items()}
                    for t, ms in latency.items()},
    }
    if asr_rows:
        m["asr"] = asr_metrics(cases, asr_rows)
    return m, per_case


def fmtv(x, p=True):
    if x is None:
        return "—"
    if isinstance(x, float):
        return f"{100 * x:.1f} %" if p else f"{x:.3f}"
    return str(x)


def fmtn(x):
    if x is None:
        return "—"
    return f"{x:.0f}" if abs(x) >= 100 else f"{x:.2f}"


def report_md(m, title):
    L = [f"# {title}", "", f"Cases scored: **{m['n_cases']}** (missing predictions: {m['missing_predictions']})", "",
         "| Metric | Value | Target (§29.6) |", "|---|---|---|",
         f"| Type accuracy | {fmtv(m['type_accuracy'])} | ≥ 90 % |",
         f"| Secondary-type F1 | {fmtv(m['secondary_f1'], False)} | — |",
         f"| Per-field F1 (micro) | {fmtv(m['field_f1_micro'], False)} (P {fmtv(m['field_precision_micro'], False)} / R {fmtv(m['field_recall_micro'], False)}) | ≥ 0.80 |",
         f"| Per-field F1 (macro) | {fmtv(m['field_f1_macro'], False)} | — |",
         f"| Mapping accuracy | {fmtv(m['mapping_accuracy'])} | ≥ 95 % |",
         f"| Hallucination rate (fields) | {fmtv(m['hallucination_rate'])} | — |",
         f"| Severity exact / ±1 (n={m['severity_n']}) | {fmtv(m['severity_exact'])} / {fmtv(m['severity_within_1'])} | — |",
         f"| Language accuracy | {fmtv(m['lang_accuracy'])} | — |",
         f"| Mandatory completeness after extraction | {fmtv(m['mandatory_completeness_after_extract'])} | — |",
         f"| Complete after 1 scripted follow-up (n={m['patch']['n']}) | {fmtv(m['complete_after_followup_rate'])} | — |",
         f"| JSON valid without repair | {fmtv(m['json_valid_without_repair'])} | ≥ 97 % |",
         f"| JSON valid with repair | {fmtv(m['json_valid_with_repair'])} | — |",
         f"| JSON strict (raw output is pure JSON) | {fmtv(m['json_strict'])} | — |",
         f"| NO_INCIDENT accuracy (small talk rejected, n={m['no_incident']['n']}) | {fmtv(m['no_incident']['accuracy'])} | — |",
         f"| False NO_INCIDENT on real incidents (n={m['no_incident']['false_reject_n']}) | {fmtv(m['no_incident']['false_reject_rate'])} | 0 % |",
         f"| Patch `set` F1 / `unknown` F1 | {fmtv(m['patch']['set_f1'], False)} / {fmtv(m['patch']['unknown_f1'], False)} | — |",
         ""]
    if m.get("asr"):
        L += ["## ASR", "", "| Lang | n | WER | CER | ASR p50 ms | p90 ms |", "|---|---|---|---|---|---|"]
        for lang, a in m["asr"].items():
            L.append(f"| {lang} | {a['n']} | {fmtv(a['wer'])} | {fmtv(a['cer'])} | {fmtn(a['asr_ms_p50'])} | {fmtn(a['asr_ms_p90'])} |")
        L.append("")
    if m["latency"]:
        L += ["## Latency (from GenStats)", "", "| Task | metric | p50 | p90 | n |", "|---|---|---|---|---|"]
        for t, ms in m["latency"].items():
            for k, v in ms.items():
                L.append(f"| {t} | {k} | {fmtn(v['p50'])} | {fmtn(v['p90'])} | {v['n']} |")
        L.append("")
    L += ["## Breakdown", "", "| Group | n | Type acc | Field F1 |", "|---|---|---|---|"]
    for g, v in m["groups"].items():
        L.append(f"| {g} | {v['n']} | {fmtv(v['type_accuracy'])} | {fmtv(v['field_f1'], False)} |")
    L += ["", "## Per-field F1", "", "| Field | F1 | tp/fp/fn |", "|---|---|---|"]
    for k, v in m["field_f1_per_field"].items():
        L.append(f"| {k} | {v:.3f} | {'/'.join(map(str, m['field_counts'][k]))} |")
    L += ["", "## Hallucination by field kind", "", "| Kind | hallucinated / predicted |", "|---|---|"]
    for k, v in m["hallucination_by_kind"].items():
        L.append(f"| {k} | {v['halluc']} / {v['predicted']} ({100 * v['rate']:.1f} %) |")
    return "\n".join(L) + "\n"


def write_outputs(run_dir: Path, m, per_case, title):
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "metrics.json").write_text(json.dumps(m, indent=1, default=str), encoding="utf-8")
    (run_dir / "report.md").write_text(report_md(m, title), encoding="utf-8")
    with open(run_dir / "per_case.jsonl", "w", encoding="utf-8") as f:
        for r in per_case:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


# ---------------------------------------------------------------------------------------------------
def gold_predictions(cases):
    out = []
    for c in cases:
        p = {"id": c["id"], "extract": {"text": json.dumps({"name": "fill_incident_form", "parameters": c["gold"]},
                                                           ensure_ascii=False)}}
        if c.get("followup"):
            p["patch"] = {"text": json.dumps({"name": "update_incident_fields", "parameters": c["followup"]["gold_patch"]},
                                             ensure_ascii=False)}
        out.append(p)
    return out


def perturb(cases, seed=7):
    import random
    rng = random.Random(seed)
    preds = []
    cats = taxonomy().category_codes
    for c in cases:
        g = copy.deepcopy(c["gold"])
        r = rng.random()
        if r < 0.10:
            g["incident_type"] = rng.choice([x for x in cats if x != g["incident_type"]])
            g.pop("details", None)
        d = g.get("details") or {}
        if d and rng.random() < 0.3:
            d.pop(rng.choice(list(d)))                    # missed field
        if rng.random() < 0.2:
            g["equipment"] = ["Generator G-9"]           # hallucinated
        if "location" in g and rng.random() < 0.2:
            g["equipment"] = g.get("equipment", []) + [g.pop("location")]   # mapping error
        if "severity" in g and rng.random() < 0.3:
            i = SEVERITY_ORDER.index(g["severity"])
            g["severity"] = SEVERITY_ORDER[min(3, i + 1)]
        if "time" in g and rng.random() < 0.2:
            g["time"] = "5 hours ago"
        text = json.dumps({"name": "fill_incident_form", "parameters": g}, ensure_ascii=False)
        rr = rng.random()
        p = {"id": c["id"], "extract": {"text": text, "stats": {"ttft_ms": rng.randint(800, 2000), "wall_ms": rng.randint(3000, 7000)}}}
        if rr < 0.05:
            p["extract"]["text"] = "Sure! " + text[:-30]                          # truncated -> auto-close
        elif rr < 0.08:
            p["extract"]["text"] = "I'm sorry, I cannot do that."               # unparseable
            p["extract_repair"] = {"text": text}
        if c.get("followup"):
            gp = copy.deepcopy(c["followup"]["gold_patch"])
            if gp["set"] and rng.random() < 0.2:
                gp["set"].pop(next(iter(gp["set"])))
            p["patch"] = {"text": json.dumps({"name": "update_incident_fields", "parameters": gp})}
        preds.append(p)
    return preds


def self_test(cases_path: Path, out_dir: Path):
    cases = load_jsonl(cases_path)
    m, pc = score(cases, gold_predictions(cases))
    write_outputs(out_dir / "selftest_gold", m, pc, "Self-test: gold vs gold")
    checks = {k: m[k] for k in ("type_accuracy", "secondary_f1", "field_f1_micro", "mapping_accuracy",
                                "hallucination_rate", "json_valid_without_repair", "severity_exact")}
    checks["patch_set_f1"] = m["patch"]["set_f1"]
    checks["patch_unknown_f1"] = m["patch"]["unknown_f1"]
    print("gold-vs-gold:", json.dumps(checks))
    bad = {k: v for k, v in checks.items() if k != "hallucination_rate" and v is not None and v < 0.999}
    if bad or (m["hallucination_rate"] or 0) > 0.001:
        print("SELF-TEST FAILED", bad)
        for r in pc:
            if r["f1"] < 1 or not r["type_ok"]:
                print(r)
        return 1
    m2, pc2 = score(cases, perturb(cases))
    write_outputs(out_dir / "selftest_perturbed", m2, pc2, "Self-test: perturbed predictions")
    print("perturbed:", json.dumps({k: (round(v, 3) if isinstance(v, float) else v) for k, v in m2.items()
                                    if k in ("type_accuracy", "secondary_f1", "field_f1_micro", "mapping_accuracy",
                                             "hallucination_rate", "severity_exact", "severity_within_1",
                                             "json_valid_without_repair", "json_valid_with_repair",
                                             "mandatory_completeness_after_extract", "complete_after_followup_rate")}))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", help="default: <run>/run_info.json 'cases', else eval/text_cases.jsonl")
    ap.add_argument("--run", help="run dir containing predictions.jsonl (and optionally asr.jsonl)")
    ap.add_argument("--pred", help="predictions.jsonl (default <run>/predictions.jsonl)")
    ap.add_argument("--asr", help="asr.jsonl (default <run>/asr.jsonl if present)")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        sys.exit(self_test(Path(a.cases or ROOT / "eval" / "text_cases.jsonl"), ROOT / "eval" / "results"))
    if not a.run:
        ap.error("--run is required")
    run = Path(a.run)
    cases_path = a.cases
    if not cases_path and (run / "run_info.json").exists():
        try:
            cp = json.loads((run / "run_info.json").read_text(encoding="utf-8")).get("cases")
            if cp:
                cases_path = str(cp if Path(cp).is_absolute() else ROOT / cp)
        except Exception:
            pass
    cases_path = cases_path or str(ROOT / "eval" / "text_cases.jsonl")
    print(f"[score] cases: {cases_path}", file=sys.stderr)
    cases = load_jsonl(cases_path)
    pred_path = Path(a.pred) if a.pred else run / "predictions.jsonl"
    preds = load_jsonl(pred_path) if pred_path.exists() else []
    asr_path = Path(a.asr) if a.asr else run / "asr.jsonl"
    asr_rows = load_jsonl(asr_path) if asr_path.exists() else None
    ids = {p["id"] for p in preds} | {r["id"] for r in (asr_rows or [])}
    cases = [c for c in cases if c["id"] in ids]
    m, pc = score(cases, preds, asr_rows)
    write_outputs(run, m, pc, f"IncidentVoice eval — {run.name}")
    print((run / "report.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
