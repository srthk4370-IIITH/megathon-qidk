"""Fine-tuning data for Llama 3.2 3B (PRD §27.2) — template + augmentation only (no cloud LLM available).

Reuses the eval scenario templates (eval/scenarios.py, eval/gen_cases.py) but with
  * different seeds, larger slot pools (names, assets, tags, plates, times, follow-up answers),
  * paraphrase layer (safe sentence swaps, opening/closing chatter, fragment re-ordering),
  * ASR-noise augmentation on ~50 % (gc.noisify + word drops + extra homophones, §27.2.4),
  * patch samples from the real Python Validator follow-up question (§27.2.5),
and de-duplicates against the held-out eval set (eval/text_cases.jsonl, exact + word-5-gram Jaccard).

Every sample is rendered with ml/common/prompt_builder.py, extract variant `ft` (short prompt, no few-shots):
  {"task": "extract"|"patch", "id", "prompt", "completion"}   completion = compact tool JSON (eot added by trainer)

  python ml/data_gen/gen_ft_data.py --out ~/iv_ft/data [--n-extract 2000 --n-patch 750 --n-dev 120]
"""
from __future__ import annotations

import argparse
import json
import random
import re
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "eval"))

import gen_cases as gc  # noqa: E402
from scenarios import ANSWERS, SCENARIOS  # noqa: E402
from ml.common import prompt_builder as pb  # noqa: E402
from ml.common.form import Form  # noqa: E402

SEED = 7_20261011
NOW = gc.NOW

# ---------------------------------------------------------------- slot-pool expansion (new values, not in eval)
gc.NAMES = gc.NAMES + [
    ("Rohit Kulkarni", "m"), ("Meena", "f"), ("Abdul Rahman", "m"), ("Shalini Iyer", "f"), ("Harish", "m"),
    ("Naveen Kumar", "m"), ("Rekha Devi", "f"), ("Joseph Thomas", "m"), ("Bhavna Shah", "f"), ("Prakash Rao", "m"),
    ("Santosh", "m"), ("Divya Menon", "f"), ("Imran Khan", "m"), ("Geeta", "f"), ("Ajay Chauhan", "m"),
    ("Tanvir Ahmed", "m"), ("Swati Desai", "f"), ("Kiran", "f"), ("Mahesh Pawar", "m"), ("Ritu Saxena", "f")]
gc.BODY = gc.BODY + [("arm", "baanh"), ("finger", "ungli"), ("shoulder", "kandhe"), ("foot", "pair ke panje"),
                     ("eye", "aankh"), ("wrist", "kalai")]
gc.ASSETS = gc.ASSETS + ["Boiler B-2", "Pump P-330", "Compressor C-310", "Grinder GR-6", "Extruder EX-1",
                         "Injection moulding machine IM-9", "Cooling tower fan CT-2", "Blower BL-4", "Crusher CR-1"]
gc.AC_TAGS = gc.AC_TAGS + ["AC-21", "AC-5", "AC-14"]
gc.CONVEYOR_TAGS = gc.CONVEYOR_TAGS + ["CV-8", "CV-2", "CV-12"]
gc.FORKLIFT_IDS = gc.FORKLIFT_IDS + ["FL-04", "FL-15", "FL-09"]
gc.PLATES = gc.PLATES + ["GJ 01 RT 6610", "HR 26 DK 3399", "RJ 14 CB 7071", "AP 09 BC 1234"]
for _cat in SCENARIOS.values():
    for _sc in _cat.values():
        pool = _sc.get("asset_pool")
        if pool:
            extra = []
            for a in pool:
                m = re.match(r"(.*?)(\d+)$", a)
                if m:
                    extra.append(f"{m.group(1)}{int(m.group(2)) + 17}")
            pool.extend(x for x in extra if x not in pool)
gc.TIMES = gc.TIMES + [
    ("around 9 AM", "around 9 AM", "subah lagbhag 9 baje", "abs"),
    ("at 4:45 PM", "at 4:45 PM", "shaam paune paanch baje", "abs"),
    ("around 1 in the afternoon", "around 1 in the afternoon", "dopahar ek baje ke aas paas", "abs"),
    ("about 10 minutes ago", "about ten minutes ago", "das minute pehle", "rel"),
    ("about 45 minutes ago", "about 45 minutes ago", "paun ghanta pehle", "rel"),
    ("3 hours ago", "three hours ago", "teen ghante pehle", "rel"),
    ("during the night shift", "during the night shift", "night shift ke dauraan", "daypart"),
    ("this afternoon", "this afternoon", "aaj dopahar", "daypart"),
    ("early this morning", "early this morning", "aaj subah subah", "daypart"),
]
gc.TIME_W = gc.TIME_W + [3, 3, 3, 4, 3, 3, 3, 4, 3]
gc.FILLERS_EN = gc.FILLERS_EN + ["actually", "I mean", "okay", "hmm"]
gc.FILLERS_HI = gc.FILLERS_HI + ["yaar", "bhai", "dekho", "haan"]

_EXTRA_ANSWERS = {
    "severity": [("Not serious, small thing.", "Serious nahi hai, chhoti si baat hai.", "LOW"),
                 ("Medium level I would say.", "Medium level ka hai.", "MEDIUM"),
                 ("It is serious, please hurry.", "Serious hai, jaldi karo.", "HIGH")],
    "injuries": [("Nobody got injured.", "Koi injured nahi hua.", "NO"),
                 ("Not sure, I haven't checked.", "Maloom nahi, maine check nahi kiya.", "UNKNOWN")],
    "occurred_at": [("Around 9 in the morning.", "Subah 9 baje ke aas paas.", "around 9 in the morning"),
                    ("About an hour ago.", "Ek ghanta pehle.", "about an hour ago"),
                    ("Just now.", "Abhi abhi.", "just now")],
    "fire_status": [("Still burning.", "Abhi bhi jal rahi hai.", "ACTIVE"),
                    ("Only smoke now.", "Ab sirf dhuan hai.", "SMOKE_ONLY")],
    "fire_brigade_called": [("I don't know.", "Mujhe nahi pata.", "UNKNOWN")],
    "first_aid_given": [("Yes, we bandaged it.", "Haan, patti kar di.", "YES")],
    "ambulance_called": [("Not yet.", "Abhi nahi.", "NO")],
    "leak_status": [("Still dripping.", "Abhi bhi tapak raha hai.", "ONGOING")],
    "power_isolated": [("No, power is still on.", "Nahi, power abhi bhi on hai.", "NO")],
    "release_status": [("Not sure.", "Pata nahi.", "UNKNOWN")],
    "operational_status": [("It's running but slow.", "Chal rahi hai par slow.", "DEGRADED")],
    "security_informed": [("Yes, I told the guard.", "Haan, guard ko bata diya.", "YES")],
    "area_cordoned": [("Don't know.", "Pata nahi.", "UNKNOWN")],
}
for k, v in _EXTRA_ANSWERS.items():
    if k in ANSWERS:
        for x in v:
            if x not in ANSWERS[k]:
                ANSWERS[k].append(x)

# ---------------------------------------------------------------- paraphrase layer (gold-neutral)
SWAP_EN = {
    "Nobody is hurt.": ["No one got hurt.", "Thankfully nobody was injured.", "Nobody got injured."],
    "No one was injured.": ["Nobody was hurt.", "No injuries.", "No one is injured."],
    "Everyone has been evacuated.": ["We have evacuated everyone.", "All the people have been evacuated."],
}
SWAP_HI = {
    "Kisi ko chot nahi lagi.": ["Kisi ko kuch nahi hua.", "Koi injured nahi hai.", "Kisi ko chot vot nahi aayi."],
    "Koi ghayal nahi hua.": ["Koi zakhmi nahi hua.", "Kisi ko chot nahi aayi."],
}
OPEN_EN = ["Hello, I want to report an incident.", "Hi, this is from the plant floor.", "Okay so,", "Please note,",
           "I need to report something.", "Hello?", "Yes, listen,"]
OPEN_HI = ["Hello, ek incident report karna hai.", "Sir,", "Haan ji,", "Suniye,", "Ek problem hai,", "Hello, sun rahe ho?"]
CLOSE_EN = ["That's all.", "Please send someone.", "Please check.", "That's it for now.", "Over."]
CLOSE_HI = ["Bas itna hi.", "Jaldi kisi ko bhejo.", "Dekh lo please.", "Bas yahi tha."]
EXTRA_HOMO = {"fire": "fair", "leak": "leek", "room": "rum", "smoke": "smock", "gas": "gaas", "floor": "flour",
              "hurt": "hut", "sparks": "sparkes", "pump": "pomp", "water": "vater"}


NEG_EN = ["Thankfully nobody is hurt and nobody is unconscious.", "No one was injured, nobody fainted.",
          "Nobody got hurt, no one needs an ambulance.", "No injuries, everyone is fine and conscious."]


def paraphrase(rng: random.Random, text: str, lang: str) -> str:
    sw = SWAP_EN if lang == "en" else SWAP_HI
    for a, alts in sw.items():
        if a in text and rng.random() < 0.6:
            text = text.replace(a, rng.choice(alts))
    sents = re.split(r"(?<=[.!?])\s+", text)
    if len(sents) > 3 and rng.random() < 0.25:  # keep the opening (incident) sentence, permute the rest a little
        rest = sents[1:]
        i = rng.randrange(len(rest) - 1)
        rest[i], rest[i + 1] = rest[i + 1], rest[i]
        sents = [sents[0]] + rest
    text = " ".join(sents)
    if rng.random() < 0.3:
        o = rng.choice(OPEN_EN if lang == "en" else OPEN_HI)
        text = o + " " + text
    if rng.random() < 0.2:
        text = text + " " + rng.choice(CLOSE_EN if lang == "en" else CLOSE_HI)
    return text


def asr_noise(rng: random.Random, text: str, lang: str) -> str:
    s = gc.noisify(rng, text, lang)
    out = []
    for w in s.split():
        if rng.random() < 0.03 and not re.search(r"\d|-", w):
            continue  # 3 % word drops (never tags/numbers: gold keeps them)
        if lang == "en" and w in EXTRA_HOMO and rng.random() < 0.2:
            w = EXTRA_HOMO[w]
        out.append(w)
    return " ".join(out)


# ---------------------------------------------------------------- dedupe vs eval
def norm(s: str) -> str:
    return re.sub(r"[^a-z0-9 ]", " ", s.lower()).split().__str__()


def grams(s: str, n=5) -> set:
    w = re.sub(r"[^a-z0-9 ]", " ", s.lower()).split()
    return {" ".join(w[i:i + n]) for i in range(max(1, len(w) - n + 1))}


def jacc(a: set, b: set) -> float:
    return len(a & b) / max(1, len(a | b))


def compact(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def patch_completion(gp: dict) -> str:
    p = {"lang": gp["lang"], "set": gp.get("set", {})}
    if gp.get("unknown"):
        p["unknown"] = gp["unknown"]
    p["skipped"] = bool(gp.get("skipped"))
    return compact({"name": "update_incident_fields", "parameters": p})


def gen(n_target: int, seed: int, eval_grams: list[set], eval_norm: set, tag: str):
    rng = random.Random(seed)
    now = datetime.fromisoformat(NOW)
    cats = list(SCENARIOS)
    weights = [0.3 if c == "OTHER" else 1.0 for c in cats]
    ext, pat, seen = [], [], set()
    rejected = Counter()
    attempts = 0
    while len(ext) < n_target and attempts < n_target * 6:
        attempts += 1
        cat = rng.choices(cats, weights=weights)[0]
        scen = rng.choice(list(SCENARIOS[cat]))
        r = rng.random()
        lang = "en" if r < 0.7 else "hinglish"   # user: 70/30, English first
        kw = dict(want_unknown=rng.random() < 0.12, two_incident=rng.random() < 0.05,
                  want_critical=rng.random() < 0.15,   # response-clock trigger (HANDBOOK extra 1)
                  max_optional=rng.choice([None, None, 0, 1, 2, 3]))
        try:
            text, gold, feats, slots, loc_gold = gc.build_case(rng, cat, scen, lang, **kw)
        except Exception as e:  # template/gold inconsistency -> reject (checker, §27.2.3)
            rejected[type(e).__name__] += 1
            continue
        # negated danger must NOT read as CRITICAL / injured (app live-session fix; response-clock trigger)
        if lang == "en" and "injuries" not in gold and gold.get("severity") != "CRITICAL" and rng.random() < 0.12:
            text = text.rstrip() + " " + rng.choice(NEG_EN)
            gold["injuries"] = "NO"
        text = paraphrase(rng, text, lang)
        noisy = rng.random() < 0.45
        if noisy:
            text = asr_noise(rng, text, lang)
        key = norm(text)
        if key in seen or key in eval_norm:
            rejected["dup"] += 1
            continue
        g = grams(text)
        if any(jacc(g, e) >= 0.8 for e in eval_grams):
            rejected["near_eval"] += 1
            continue
        seen.add(key)
        sid = f"{tag}-{len(ext):05d}"
        ext.append({"task": "extract", "id": sid, "lang": gold["lang"], "noisy": noisy, "category": cat, "transcript": text.strip(),
                    "prompt": pb.render_extract(text.strip(), "ft"),
                    "completion": compact({"name": "fill_incident_form", "parameters": gold})})
        if rng.random() < 0.42:
            fu = gc.make_followup(rng, gold, gold["lang"], slots, loc_gold, now)
            if fu:
                ans = fu["answer"]
                if rng.random() < 0.3:
                    ans = asr_noise(rng, ans, lang) or ans
                form = Form.from_params(gold, now=now)
                pat.append({"task": "patch", "id": sid + "p", "lang": gold["lang"], "noisy": noisy, "category": cat,
                            "prompt": pb.render_patch(form, fu["asked_fields"], fu["question"], ans.strip()),
                            "completion": patch_completion(fu["gold_patch"])})
    return ext, pat, rejected


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--n-extract", type=int, default=2000)
    ap.add_argument("--n-dev", type=int, default=120)
    a = ap.parse_args()
    out = Path(a.out).expanduser()
    out.mkdir(parents=True, exist_ok=True)
    ev = gc.load_cases(ROOT / "eval" / "text_cases.jsonl") if hasattr(gc, "load_cases") else \
        [json.loads(l) for l in open(ROOT / "eval" / "text_cases.jsonl", encoding="utf-8") if l.strip()]
    eval_grams = [grams(c["transcript"]) for c in ev]
    eval_norm = {norm(c["transcript"]) for c in ev}
    tr_e, tr_p, rej = gen(a.n_extract, SEED, eval_grams, eval_norm, "tr")
    dv_e, dv_p, _ = gen(a.n_dev, SEED + 999, eval_grams, eval_norm | {norm(x["transcript"]) for x in tr_e}, "dv")
    train = tr_e + tr_p
    random.Random(SEED).shuffle(train)
    dev = dv_e + dv_p[: max(10, a.n_dev // 3)]
    for name, rows in (("train", train), ("dev", dev)):
        with open(out / f"{name}.jsonl", "w", encoding="utf-8", newline="\n") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
    st = {"train_extract": len(tr_e), "train_patch": len(tr_p), "dev": len(dev),
          "rejected": dict(rej), "lang": dict(Counter(r["lang"] for r in tr_e)),
          "noisy_frac": round(sum(r["noisy"] for r in tr_e) / max(1, len(tr_e)), 3),
          "category": dict(Counter(r["category"] for r in tr_e)),
          "prompt_chars_max": max(len(r["prompt"]) for r in train)}
    (out / "stats.json").write_text(json.dumps(st, indent=1), encoding="utf-8")
    print(json.dumps(st))


if __name__ == "__main__":
    main()
