"""Generate the template-based evaluation set (PRD §29.1, rev 1.1 R1-10).

Outputs (deterministic, seed 20261010):
  eval/text_cases.jsonl     ~150 cases: EN 60 (incl. the slide example), Roman Hinglish 60, noisy-ASR 30
  eval/spot_check.md        30 cases for human spot-check
  eval/clips.jsonl          30 scripts for recorded clips (15 EN + 15 Hinglish) with gold forms
  eval/RECORDING_SCRIPT.md  what to read aloud for the clips

Case format (one JSON per line):
  {"id","lang","style":"clean|noisy","category","scenario","now","transcript","gold":{fill_incident_form params},
   "features":{...},"followup":null|{"asked_fields","question","answer","gold_patch"}}

Run:  python eval/gen_cases.py      (from the repo root; any Python 3.10+, no third-party deps)
"""
from __future__ import annotations

import json
import random
import re
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "eval"))

from ml.common.config import taxonomy  # noqa: E402
from ml.common.form import Form  # noqa: E402
from ml.common.validator import next_question, severity_floor, validate, SEVERITY_ORDER  # noqa: E402
from ml.common import make_golden as mg  # noqa: E402
from scenarios import ANSWERS, OTHER_INCIDENTS, SCENARIOS  # noqa: E402

SEED = 20261010
NOW = "2026-10-10T17:30:00+05:30"
EVAL = ROOT / "eval"

NAMES = [("Ramesh", "m"), ("Suresh Kumar", "m"), ("Priya Sharma", "f"), ("Anil Verma", "m"), ("Sunita", "f"),
         ("Vikram Singh", "m"), ("Pooja Patel", "f"), ("Arjun Nair", "m"), ("Deepak Yadav", "m"), ("Kavita Joshi", "f"),
         ("Mohammed Irfan", "m"), ("Lakshmi", "f"), ("Rajesh Gupta", "m"), ("Neha Reddy", "f"), ("Manoj Tiwari", "m"),
         ("Sandeep", "m"), ("Farhan Sheikh", "m"), ("Anjali Mehta", "f"), ("Ravi Shankar", "m"), ("Gurpreet Singh", "m")]
BODY = [("hand", "haath"), ("leg", "pair"), ("ankle", "takhne"), ("head", "sir"), ("back", "kamar"), ("knee", "ghutne")]
ASSETS = ["Pump P-101", "Compressor C-202", "CNC machine M-14", "Packaging machine PK-4", "Hydraulic press HP-2",
          "Lathe L-5", "Chiller CH-1", "Mixer MX-3"]
AC_TAGS = ["AC-7", "AC-12", "AC-3"]
CONVEYOR_TAGS = ["CV-3", "CV-1", "CV-5"]
FORKLIFT_IDS = ["FL-07", "FL-02", "FL-11"]
PLATES = ["MH 12 AB 4521", "KA 05 MN 7788", "TN 09 CX 1123", "DL 3C AK 9090", "UP 16 BT 2345"]
OUTDOOR = {"Main Gate", "Parking Area", "Loading Dock", "Water Tank Area", "Terrace", "Effluent Treatment Plant"}
NO_ARTICLE = ("Bay", "Conference", "Assembly", "Staircase", "Lift", "Electrical Panel", "Washroom", "Block")

# (gold English phrase, spoken English, spoken Hinglish, kind)
TIMES = [
    ("around 10 AM", "around 10 AM", "subah lagbhag 10 baje", "abs"),
    ("around 11:30 in the morning", "at around 11:30 in the morning", "subah saadhe gyarah baje ke aas paas", "abs"),
    ("at 2:15 PM", "at 2:15 PM", "dopahar sawa do baje", "abs"),
    ("around 3 in the afternoon", "around 3 in the afternoon", "dopahar lagbhag teen baje", "abs"),
    ("last night at 11", "last night at 11", "kal raat 11 baje", "abs"),
    ("about half an hour ago", "about half an hour ago", "aadha ghanta pehle", "rel"),
    ("about an hour ago", "about an hour ago", "ek ghanta pehle", "rel"),
    ("15 minutes ago", "15 minutes ago", "15 minute pehle", "rel"),
    ("2 hours ago", "two hours ago", "do ghante pehle", "rel"),
    ("just now", "just now", "abhi abhi", "rel"),
    ("a few minutes ago", "a few minutes ago", "thodi der pehle", "rel"),
    ("this morning", "this morning", "aaj subah", "daypart"),
    ("yesterday evening", "yesterday evening", "kal shaam ko", "daypart"),
]
TIME_W = [6, 4, 4, 4, 3, 7, 6, 6, 5, 4, 4, 5, 3]

FILLERS_EN = ["uh", "umm", "so", "like", "you know", "basically"]
FILLERS_HI = ["haan toh", "matlab", "woh", "acha", "umm", "toh"]
HOMOPHONES_EN = {"there": "their", "leak": "leek", "here": "hear", "whole": "hole", "break": "brake",
                 "right": "write", "four": "for", "fire": "fyre", "wire": "wyre", "hurt": "heart",
                 "minor": "miner", "pipe": "pype", "panel": "panal", "sparking": "sparkling"}
HI_VARIANTS = {"hai": "he", "nahi": "nahin", "kya": "kia", "gaya": "gya", "raha": "rha", "mein": "me",
               "gayi": "gyi", "kar": "kr", "hain": "he"}
DIGIT_WORDS = {"1": "one", "2": "two", "3": "three", "4": "four", "5": "five", "10": "ten", "11": "eleven", "15": "fifteen"}


# ---------------------------------------------------------------------------------------------------
def fmt(obj, slots):
    if isinstance(obj, str):
        return obj.format(**slots)
    if isinstance(obj, list):
        return [fmt(x, slots) for x in obj]
    if isinstance(obj, dict):
        return {k: fmt(v, slots) for k, v in obj.items()}
    return obj


def clean_text(s: str) -> str:
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"\s+([.,!?])", r"\1", s)
    s = re.sub(r"(^|[.!?] )([a-z])", lambda m: m.group(1) + m.group(2).upper(), s)
    return s


def make_loc(rng, name, raw=False):
    if raw:
        return name, name, name
    art = "" if name.startswith(NO_ARTICLE) else "the "
    q = rng.choice(["near", "in", "in", "at", "outside", "behind", "near"])
    if q == "in" and name in OUTDOOR:
        q = "at"
    if name.startswith(("Lift", "Electrical Panel", "Staircase")) and q in ("behind", "outside"):
        q = "near"
    en = f"{q} {art}{name}"
    hi = {"near": f"{name} ke paas", "in": f"{name} mein", "at": f"{name} par", "outside": f"{name} ke bahar",
          "behind": f"{name} ke peeche"}[q]
    gold = name if q in ("in", "at") else f"{q} {name}"
    return en, hi, gold


def merge_gold(g: dict, patch: dict):
    for k, v in patch.items():
        if k == "_sec":
            for t in v:
                if t not in g.setdefault("secondary_types", []):
                    g["secondary_types"].append(t)
        elif k == "persons":
            ps = g.setdefault("persons", [])
            for p in v:
                ex = next((x for x in ps if x.get("name") and x.get("name") == p.get("name")), None)
                if ex:
                    ex.update(p)
                else:
                    ps.append(dict(p))
        elif isinstance(v, list):
            lst = g.setdefault(k, [])
            for x in v:
                if x not in lst:
                    lst.append(x)
        else:
            g[k] = v


def route_gold(flat: dict, lang: str, title: str, desc: str) -> dict:
    tx = taxonomy()
    out = {"lang": lang, "incident_type": flat["incident_type"]}
    if flat.get("secondary_types"):
        out["secondary_types"] = flat["secondary_types"]
    out["title"] = title
    out["description"] = desc
    details = {}
    order = ["location", "time", "severity", "injuries", "injured_count", "persons", "equipment", "actions_taken",
             "witnesses", "near_miss", "hazard_ongoing", "other_incidents"]
    for k in order:
        if k in flat:
            out[k] = flat[k]
    for c in tx.categories:
        for f in tx.cat_fields[c["code"]]:
            if f.key in flat:
                if f.category in [out["incident_type"]] + out.get("secondary_types", []):
                    details[f.key] = flat[f.key]
                else:
                    raise ValueError(f"gold field {f.key} outside chosen categories")
    for k in flat:
        if k not in order and k not in ("incident_type", "secondary_types") and tx.field(k) is None:
            raise ValueError(f"unknown gold key {k}")
    if details:
        out["details"] = details
    return out


def build_case(rng, cat, scen_name, lang, *, want_unknown=False, two_incident=False, want_critical=False,
               max_optional=None, no_time=None):
    sc = SCENARIOS[cat][scen_name]
    n1, n2 = rng.sample(NAMES, 2)
    body = rng.choice(BODY)
    loc_name = rng.choice(sc["locs"])
    loc_en, loc_hi, loc_gold = make_loc(rng, loc_name, sc.get("raw_loc", False))
    if no_time is None:
        no_time = rng.random() < 0.15
    if sc.get("need_time"):
        no_time = False
    t = None if no_time else rng.choices(TIMES, weights=TIME_W)[0]
    male = n1[1] == "m"
    asset = rng.choice(sc.get("asset_pool", ASSETS))
    slots = {
        "loc_en": loc_en, "loc_hi": loc_hi, "time_en": t[1] if t else "", "time_hi": t[2] if t else "",
        "n1": n1[0], "n2": n2[0], "he": "he" if male else "she", "He": "He" if male else "She",
        "his": "his" if male else "her", "His": "His" if male else "Her", "him": "him" if male else "her",
        "ga": "gaya" if male else "gayi", "tha": "tha" if male else "thi", "raha": "raha" if male else "rahi",
        "body": body[0], "body_hi": body[1], "asset": asset, "asset_name": rng.choice(AC_TAGS),
        "tag": rng.choice(CONVEYOR_TAGS), "vid": rng.choice(FORKLIFT_IDS), "vplate": rng.choice(PLATES),
        "qty": str(rng.randint(2, 5)),
    }
    for var, opts in sc.get("vars", {}).items():
        en, hi = rng.choice(opts)
        slots[var] = en
        slots[var + "_hi"] = hi
        slots[var + "_cap"] = en[:1].upper() + en[1:]
    if "items" in slots:
        slots["items_cap"] = slots["items"][:1].upper() + slots["items"][1:]
    if "chem" in slots:
        pass
    # n2 role in "faint": responder; keep slots for both languages
    sent_en, sent_hi = [], []
    flat: dict = {}
    used_unknown = False
    n_opt = 0
    frags = sc["frags"]
    for i, fr in enumerate(frags):
        alts = fr["alts"]
        crit_alt = next((a for a in alts if a[2].get("severity") == "CRITICAL"), None)
        if want_unknown and fr["u"] and not used_unknown:
            choice = fr["u"]
            used_unknown = True
        elif want_critical and crit_alt is not None:
            choice = crit_alt
        else:
            if i > 0 and fr["p"] < 1.0:
                if max_optional is not None and n_opt >= max_optional:
                    continue
                if rng.random() >= fr["p"]:
                    continue
                n_opt += 1
            choice = rng.choice(alts)
            if not want_critical and choice[2].get("severity") == "CRITICAL" and len(alts) > 1 and rng.random() < 0.5:
                choice = rng.choice([a for a in alts if a is not choice])
        en, hi, g = choice
        sent_en.append(fmt(en, slots))
        sent_hi.append(fmt(hi, slots))
        merge_gold(flat, fmt(g, slots))
    if "location" not in flat:
        flat["location"] = loc_gold if "{loc_en}" in json.dumps(sc["frags"][0]["alts"][0][0]) else loc_name
    if t:
        flat["time"] = t[0]
    other = None
    if two_incident:
        other = rng.choice(OTHER_INCIDENTS)
        sent_en.append(f"Also, separately, {other[0]}.")
        sent_hi.append(f"Aur ek alag baat, {other[1]}.")
        flat["other_incidents"] = [other[2]]
    # severity: never below the deterministic floor (keeps gold self-consistent)
    text_en = clean_text(" ".join(sent_en))
    text_hi = clean_text(" ".join(sent_hi))
    title = clean_text(fmt(sc["title"], {**slots, "loc_en": loc_en}))
    title = title[:1].upper() + title[1:]
    desc = clean_text(fmt(sc["desc"], slots))
    gold = route_gold(flat, "en" if lang == "en" else "hinglish", title.rstrip("."), desc)
    feats = {
        "secondary": bool(gold.get("secondary_types")),
        "relative_time": bool(t and t[3] == "rel"),
        "time_kind": t[3] if t else "none",
        "dont_know": used_unknown or _has_unknown(gold),
        "two_incidents": two_incident,
        "severity_stated": "severity" in gold,
        "critical": gold.get("severity") == "CRITICAL",
    }
    return (text_en if lang == "en" else text_hi), gold, feats, slots, loc_gold


def _has_unknown(gold):
    vals = list(gold.values()) + list((gold.get("details") or {}).values())
    return any(v == "UNKNOWN" for v in vals)


def noisify(rng, text: str, base_lang: str) -> str:
    s = text.lower()
    s = re.sub(r"\b(\d+(?::\d+)?) ?([ap])m\b", r"\1 \2 m", s)
    s = re.sub(r"[^\w\s:\-]", " ", s)
    words = s.split()
    out = []
    fillers = FILLERS_EN if base_lang == "en" else FILLERS_HI
    if rng.random() < 0.7:
        out.append(rng.choice(fillers))
    for i, w in enumerate(words):
        if base_lang == "en" and w in HOMOPHONES_EN and rng.random() < 0.35:
            w = HOMOPHONES_EN[w]
        if base_lang != "en" and w in HI_VARIANTS and rng.random() < 0.4:
            w = HI_VARIANTS[w]
        if w in DIGIT_WORDS and rng.random() < 0.5 and not re.search(r"-", w):
            w = DIGIT_WORDS[w]
        out.append(w)
        if rng.random() < 0.04:
            out.append(w)  # stutter
        if rng.random() < 0.06:
            out.append(rng.choice(fillers))
    return " ".join(out)


def make_followup(rng, gold, lang, slots, loc_gold, now):
    form = Form.from_params(gold, now=now)
    res = validate(form, now)
    q = next_question(res, form)
    if q is None:
        return None
    floor = severity_floor(form)
    en_parts, hi_parts, st, unknown = [], [], {}, []
    for f in q.fields:
        opts = ANSWERS.get(f)
        if not opts:
            return None
        if f == "severity" and floor:
            opts = [o for o in opts if SEVERITY_ORDER.index(o[2]) >= SEVERITY_ORDER.index(floor)] or \
                   [("It's serious.", "Serious hai.", floor)]
        if f == "equipment" and not re.search(r"[A-Z]+-\d", slots["asset"]):
            return None
        en, hi, val = rng.choice(opts)
        en_parts.append(fmt(en, {**slots, "loc_gold": loc_gold}))
        hi_parts.append(fmt(hi, {**slots, "loc_gold": loc_gold}))
        val = fmt(val, {**slots, "loc_gold": loc_gold})
        key = "time" if f == "occurred_at" else f
        enum_vals = taxonomy().enum_values(f)
        if val == "UNKNOWN" and not (enum_vals and "UNKNOWN" in enum_vals):
            unknown.append(key)
        elif val == "UNKNOWN":
            # explicit don't-know for an enum: per patch rule 3 it goes to "unknown"
            unknown.append(key)
        else:
            st[key] = val
    skipped = False
    if rng.random() < 0.06:
        en_parts, hi_parts, st, unknown, skipped = ["Skip this, I'll tell you later."], ["Abhi chhodo, baad mein batata hoon."], {}, [], True
    gp = {"lang": "en" if lang == "en" else "hinglish", "set": st}
    if unknown:
        gp["unknown"] = unknown
    if skipped:
        gp["skipped"] = True
    return {"asked_fields": q.fields, "question": q.text(gold.get("lang")),
            "answer": " ".join(en_parts) if lang == "en" else " ".join(hi_parts), "gold_patch": gp}


def slide_case():
    gold = dict(mg.slide_extraction_params())
    gold = {"lang": "en", "incident_type": "WATER", "title": gold["title"], "description": gold["description"],
            "location": gold["location"], "time": gold["time"], "actions_taken": gold["actions_taken"]}
    return {"id": "slide-000", "lang": "en", "style": "clean", "category": "WATER", "scenario": "WATER/slide", "now": NOW,
            "transcript": mg.SLIDE_TRANSCRIPT, "gold": gold,
            "features": {"secondary": False, "relative_time": False, "time_kind": "abs", "dont_know": False,
                         "two_incidents": False, "severity_stated": False, "critical": False, "slide": True},
            "followup": {"asked_fields": ["severity", "injuries"], "question": mg.SLIDE_QUESTION, "answer": mg.SLIDE_ANSWER,
                         "gold_patch": {"lang": "en", "set": {"injuries": "NO", "severity": "LOW"}}}}


def gen_text_cases():
    rng = random.Random(SEED)
    now = datetime.fromisoformat(NOW)
    cats = [c for c in SCENARIOS if c != "OTHER"]
    plan = []
    for c in cats:
        names = list(SCENARIOS[c])
        rng.shuffle(names)
        for i in range(14):
            plan.append((c, names[i % len(names)]))
    onames = list(SCENARIOS["OTHER"])
    for i in range(10):
        plan.append(("OTHER", onames[i % len(onames)]))
    plan.remove(("WATER", plan[[p[0] for p in plan].index("WATER")][1]))  # slide is the 15th WATER case -> 150 total
    rng.shuffle(plan)
    styles = ["en"] * 59 + ["hinglish"] * 60 + ["noisy"] * 30
    rng.shuffle(styles)
    noisy_base = ["en", "hinglish"] * 15
    two_idx = set(rng.sample(range(len(plan)), 5))
    cases = [slide_case()]
    nb = 0
    for i, ((cat, scen), style) in enumerate(zip(plan, styles)):
        if style == "noisy":
            lang = noisy_base[nb]; nb += 1
        else:
            lang = style
        want_unknown = (i % 6 == 3)
        text, gold, feats, slots, loc_gold = build_case(rng, cat, scen, lang, want_unknown=want_unknown,
                                                        two_incident=(i in two_idx))
        if style == "noisy":
            text = noisify(rng, text, lang)
        cases.append({"id": f"tc-{i + 1:03d}", "lang": gold["lang"], "style": "noisy" if style == "noisy" else "clean",
                      "category": cat, "scenario": f"{cat}/{scen}", "now": NOW, "transcript": text, "gold": gold,
                      "features": feats, "followup": None, "_slots": slots, "_loc_gold": loc_gold})
    # scripted follow-ups for ~50 cases (slide already has one)
    order = list(range(1, len(cases)))
    rng.shuffle(order)
    n_fu = 1
    for j in order:
        if n_fu >= 50:
            break
        c = cases[j]
        fu = make_followup(rng, c["gold"], c["lang"], c["_slots"], c["_loc_gold"], now)
        if fu:
            c["followup"] = fu
            n_fu += 1
    for c in cases:
        c.pop("_slots", None)
        c.pop("_loc_gold", None)
    return cases


def gen_clips():
    rng = random.Random(SEED + 30)
    cats = list(SCENARIOS)
    # 8 categories x3, 2 categories x2, OTHER x2 = 30
    plan = []
    for c in [x for x in cats if x != "OTHER"]:
        names = list(SCENARIOS[c])
        rng.shuffle(names)
        k = 2 if c in ("STRUCTURAL", "ENVIRONMENTAL") else 3
        plan += [(c, names[i % len(names)]) for i in range(k)]
    plan += [("OTHER", "snake"), ("OTHER", "false_alarm")]
    rng.shuffle(plan)
    # 2 CRITICAL emergencies: one EN, one Hinglish, on scenarios that have a CRITICAL alternative
    crit_scen = [("MEDICAL", "faint"), ("HAZMAT", "ammonia")]
    plan = [p for p in plan if p not in crit_scen]
    while len(plan) < 28:
        plan.append(("WATER", "burst_pipe"))
    plan = plan[:28]
    plan.insert(3, crit_scen[0])     # EN (clip_04)
    plan.insert(20, crit_scen[1])    # Hinglish (clip_21)
    clips = []
    for i, (cat, scen) in enumerate(plan):
        lang = "en" if i < 15 else "hinglish"
        text, gold, feats, _, _ = build_case(rng, cat, scen, lang, want_unknown=(i in (6, 24)),
                                             want_critical=((cat, scen) in crit_scen), max_optional=2,
                                             no_time=(i % 7 == 5))
        if i % 4 == 1:
            text = ("So, " if lang == "en" else "Haan toh, ") + text[:1].lower() + text[1:]
        if i % 5 == 3:
            text = re.sub(r"\. (\w)", lambda m: ", umm, " + m.group(1).lower(), text, count=1)
        clips.append({"id": f"clip_{i + 1:02d}", "lang": gold["lang"], "style": "clip", "category": cat,
                      "scenario": f"{cat}/{scen}", "now": NOW, "transcript": text, "gold_transcript": text,
                      "gold": gold, "features": feats, "followup": None,
                      "wav": f"eval/clips/clip_{i + 1:02d}.wav"})
    return clips


def write_jsonl(path, rows):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def stats(cases):
    from collections import Counter
    s = {"total": len(cases), "by_category": dict(Counter(c["category"] for c in cases)),
         "by_lang": dict(Counter(c["lang"] for c in cases)), "by_style": dict(Counter(c["style"] for c in cases)),
         "by_lang_style": dict(Counter(f'{c["lang"]}/{c["style"]}' for c in cases))}
    for k in ("secondary", "relative_time", "dont_know", "two_incidents", "severity_stated", "critical"):
        s[k] = sum(1 for c in cases if c["features"].get(k))
    s["followups"] = sum(1 for c in cases if c.get("followup"))
    s["time_kind"] = dict(Counter(c["features"]["time_kind"] for c in cases))
    return s


def write_spot_check(cases):
    rng = random.Random(SEED + 1)
    picks = [cases[0]]
    by_cat = {}
    for c in cases[1:]:
        by_cat.setdefault(c["category"], []).append(c)
    for cat in sorted(by_cat):
        picks += rng.sample(by_cat[cat], min(2, len(by_cat[cat])))
    rest = [c for c in cases if c not in picks]
    extra = [c for c in rest if c["features"]["two_incidents"] or c["style"] == "noisy"]
    picks += rng.sample(extra, min(30 - len(picks), len(extra)))
    picks = picks[:30]
    lines = ["# Spot-check list (30 cases)", "",
             "Read each transcript and check the gold form against the extraction rules (PRD §13.5): only stated "
             "fields, English values, names/tags as spoken, `time` = spoken phrase. Tick the box or fix "
             "`eval/scenarios.py` and re-run `python eval/gen_cases.py`.", ""]
    for c in picks:
        lines += [f"## [ ] {c['id']} — {c['category']} · {c['lang']} · {c['style']}", "",
                  f"> {c['transcript']}", "", "```json", json.dumps(c["gold"], ensure_ascii=False, indent=1), "```"]
        if c.get("followup"):
            fu = c["followup"]
            lines += [f"Follow-up — Q: *{fu['question']}*  A: *{fu['answer']}*", "", "```json",
                      json.dumps(fu["gold_patch"], ensure_ascii=False), "```"]
        lines.append("")
    (EVAL / "spot_check.md").write_text("\n".join(lines), encoding="utf-8")


def write_recording_script(clips):
    L = ["# Recording script — 30 evaluation clips", "",
         "**How to record**", "",
         "- Record in the actual hackathon hall — background noise is good, do not look for a quiet corner.",
         "- Hold the phone about **20 cm** from your mouth, speak naturally (as if reporting to a supervisor). "
         "Small hesitations, \"umm\", restarts are fine — do not read robotically.",
         "- Any format is fine (m4a, wav, mp3, ogg). Keep each clip **under 30 seconds** (P0 cap).",
         "- File name **exactly** `clip_NN.<ext>` (e.g. `clip_07.m4a`) and drop the file into `eval/clips/raw/`.",
         "- Clips 01–15 are English, 16–30 are Roman Hinglish (say them the way you would naturally speak Hinglish).",
         "- If you change the wording while speaking, note it next to the clip number in this file so the gold "
         "transcript can be fixed in `eval/clips.jsonl`.",
         "- Then run `bash eval/convert_clips.sh` (WSL) to produce 16 kHz mono WAVs in `eval/clips/`.", "",
         "| # | Lang | Category | Notes |", "|---|---|---|---|"]
    for c in clips:
        note = []
        if c["features"]["critical"]:
            note.append("**emergency — sound urgent**")
        if c["features"]["dont_know"]:
            note.append("contains \"don't know\"")
        if c["features"]["relative_time"]:
            note.append("relative time")
        L.append(f"| {c['id'][-2:]} | {c['lang']} | {c['category']} | {', '.join(note)} |")
    L.append("")
    for c in clips:
        L += [f"### {c['id']}  ({c['lang']}, {c['category']})", "", f"> {c['transcript']}", ""]
    (EVAL / "RECORDING_SCRIPT.md").write_text("\n".join(L), encoding="utf-8")


def main():
    cases = gen_text_cases()
    write_jsonl(EVAL / "text_cases.jsonl", cases)
    write_spot_check(cases)
    clips = gen_clips()
    write_jsonl(EVAL / "clips.jsonl", clips)
    write_recording_script(clips)
    print(json.dumps({"text_cases": stats(cases), "clips": stats(clips)}, indent=1))


if __name__ == "__main__":
    main()
