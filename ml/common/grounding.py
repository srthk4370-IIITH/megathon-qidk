"""Grounding check (PRD §13.8): is a free-text value supported by the transcript(s)?

grounded(value, transcripts) == True iff any content token (>= 3 chars, not a stopword) of the value
appears in the (lexicon-expanded) transcript tokens, or token-set fuzzy ratio >= 0.8.

Field policy:
  DROP when ungrounded : persons[].name, witnesses[].name, witnesses[].contact, driver_name, vehicle_ids
  INFERRED (kept)      : location, equipment, substance, leak_source, pollutant, damage_type, other strings
  exempt               : description, title, enums, bools, ints, time, actions_taken, other_incidents
"""
from __future__ import annotations

import re
from typing import Any, Iterable

from .config import taxonomy
from .fuzzy import token_set_ratio

STOP_EN = set("""a an the and or but of to in on at by for from with near into onto over under is are was were be been
being it its this that these those there here he she they them his her their we our you your i me my mine
has have had do does did not no yes so very just also already some any all one about around approximately
then than when where what which who whom how why as if while after before up down out off again still
area side floor near next behind front inside outside""".split())
STOP_HI = set("""hai hain tha thi the ho hua hui hue gaya gayi gaye ki ka ke ko mein me se par pe aur ya bhi toh to
na nahi nahin kya koi kuch ek yeh ye woh wo vo is us iske uske unke hamare humne maine mujhe hum main
abhi raha rahi rahe wala wali wale diya di kar kiya kiye ne ji haan han lag laga lagi bahut thoda thodi
jaise jab tab ab bas sab""".split())
STOPWORDS = STOP_EN | STOP_HI

# Hinglish -> English lexicon applied to transcript tokens (≈120 entries)
LEXICON = {
    "aag": "fire", "dhuan": "smoke", "dhuaan": "smoke", "dhamaka": "explosion", "jal": "burning", "jalna": "burning",
    "paani": "water", "pani": "water", "naala": "drain", "nala": "drain", "naale": "drain", "naali": "drain",
    "taar": "wire", "tar": "wire", "bijli": "power", "current": "current", "jhatka": "shock", "chingari": "spark",
    "seedhi": "stairs", "sidhi": "stairs", "seedhiyan": "stairs", "chhat": "roof", "chhath": "ceiling",
    "deewar": "wall", "diwar": "wall", "darwaza": "door", "darwaja": "door", "khidki": "window", "sheesha": "glass",
    "shisha": "glass", "farsh": "floor", "zameen": "ground", "mitti": "soil", "darar": "crack",
    "gaadi": "vehicle", "gadi": "vehicle", "gaadiyan": "vehicles", "truck": "truck", "tempo": "tempo",
    "chot": "injury", "ghayal": "injured", "khoon": "blood", "behosh": "unconscious", "haddi": "bone",
    "haath": "hand", "hath": "hand", "pair": "leg", "pairon": "legs", "sir": "head", "sar": "head", "aankh": "eye",
    "aankhon": "eyes", "ungli": "finger", "ungliyan": "fingers", "kamar": "back", "ghutna": "knee", "kandha": "shoulder",
    "chori": "theft", "chor": "thief", "ladai": "fight", "jhagda": "fight", "dhamki": "threat",
    "machine": "machine", "mashin": "machine", "kharab": "broken", "toota": "broken", "tuta": "broken", "tooti": "broken",
    "band": "stopped", "ruk": "stopped", "atak": "stuck", "phas": "trapped", "fasa": "trapped",
    "tel": "oil", "kachra": "waste", "dawai": "medicine", "gas": "gas", "badbu": "smell", "boo": "smell",
    "tanki": "tank", "nal": "tap", "pipe": "pipe", "chhed": "hole", "geela": "wet", "gila": "wet",
    "rasoi": "kitchen", "godown": "warehouse", "gate": "gate", "darwaze": "door", "kamra": "room", "kamre": "room",
    "bahar": "outside", "andar": "inside", "upar": "upstairs", "neeche": "downstairs", "paas": "near",
    "subah": "morning", "shaam": "evening", "raat": "night", "dopahar": "afternoon", "kal": "yesterday",
    "aadmi": "man", "aurat": "woman", "ladka": "boy", "ladki": "girl", "mazdoor": "worker", "chowkidar": "guard",
    "guard": "guard", "driver": "driver", "supervisor": "supervisor", "saheb": "sir",
    "sanp": "snake", "saanp": "snake", "keeda": "insect", "kutta": "dog", "bandar": "monkey",
    "garmi": "heat", "thand": "cold", "dhoop": "sun", "baarish": "rain", "barish": "rain",
    "leak": "leak", "tapak": "dripping", "bhar": "flooded", "behna": "flowing", "beh": "flowing",
    "rack": "rack", "shelf": "shelf", "maal": "goods", "saman": "items", "samaan": "items", "box": "box",
    "laptop": "laptop", "batua": "wallet", "purse": "purse", "phone": "phone", "mobile": "phone",
    "cylinder": "cylinder", "chemical": "chemical", "tezaab": "acid", "tezab": "acid",
    "kaanch": "glass", "kanch": "glass", "lift": "lift", "panka": "fan", "pankha": "fan",
}

_TOKEN = re.compile(r"[a-z0-9\-]+")


def tokens(s: str) -> list[str]:
    return _TOKEN.findall(s.lower())


def content_tokens(s: str) -> list[str]:
    return [t for t in tokens(s) if len(t) >= 3 and t not in STOPWORDS]


def transcript_tokens(texts: Iterable[str]) -> set[str]:
    out: set[str] = set()
    for t in texts:
        for tok in tokens(t):
            out.add(tok)
            if tok in LEXICON:
                out.update(tokens(LEXICON[tok]))
            # simple plural/stem forms
            if tok.endswith("s") and len(tok) > 3:
                out.add(tok[:-1])
            for part in tok.split("-"):
                out.add(part)
    return out


def grounded(value: str, texts: Iterable[str]) -> bool:
    texts = list(texts)
    ct = content_tokens(value)
    if not ct:
        return True  # nothing to check (all stopwords/short)
    tt = transcript_tokens(texts)
    for tok in ct:
        if tok in tt or tok.rstrip("s") in tt or tok.replace("-", "") in {x.replace("-", "") for x in tt}:
            return True
    joined = " ".join(texts)
    return token_set_ratio(value.lower(), joined.lower()) >= 0.8


DROP_KEYS = ("driver_name", "vehicle_ids")
EXEMPT_KEYS = {"description", "title", "time", "actions_taken", "other_incidents", "lang"}


def apply_grounding(params: dict, texts: list[str], tool: str) -> tuple[dict, set[str], list[str]]:
    """Returns (params, inferred_keys, dropped_paths). Works on fill params or patch params ("set")."""
    tx = taxonomy()
    inferred: set[str] = set()
    dropped: list[str] = []
    if tool == "update_incident_fields":
        st = dict(params.get("set") or {})
        st2, inf, dr = _ground_flat(st, texts, tx)
        params = dict(params)
        params["set"] = st2
        return params, inf, dr
    flat = {k: v for k, v in params.items() if k != "details"}
    det = dict(params.get("details") or {})
    flat2, inf1, dr1 = _ground_flat(flat, texts, tx)
    det2, inf2, dr2 = _ground_flat(det, texts, tx)
    out = dict(flat2)
    if det2:
        out["details"] = det2
    return out, inf1 | inf2, dr1 + dr2


def _ground_flat(d: dict, texts: list[str], tx) -> tuple[dict, set[str], list[str]]:
    out: dict[str, Any] = {}
    inferred: set[str] = set()
    dropped: list[str] = []
    for k, v in d.items():
        if k in EXEMPT_KEYS:
            out[k] = v
            continue
        f = tx.field(k)
        if k == "persons" and isinstance(v, list):
            ps = []
            for p in v:
                p = dict(p)
                if p.get("name") and not grounded(p["name"], texts):
                    dropped.append(f"persons.name:{p['name']}")
                    del p["name"]
                if p:
                    ps.append(p)
            if ps:
                out[k] = ps
            continue
        if k == "witnesses" and isinstance(v, list):
            ws = []
            for w in v:
                w = {kk: vv for kk, vv in w.items() if grounded(vv, texts) or not dropped.append(f"witnesses.{kk}:{vv}")}
                if w:
                    ws.append(w)
            if ws:
                out[k] = ws
            continue
        if k in DROP_KEYS:
            if isinstance(v, list):
                keep = [x for x in v if grounded(x, texts)]
                dropped += [f"{k}:{x}" for x in v if x not in keep]
                if keep:
                    out[k] = keep
            elif isinstance(v, str):
                if grounded(v, texts):
                    out[k] = v
                else:
                    dropped.append(f"{k}:{v}")
            continue
        if f is not None and f.type in ("string", "string_list") and v is not None:
            vals = v if isinstance(v, list) else [v]
            if not all(grounded(x, texts) for x in vals):
                inferred.add(k)
        out[k] = v
    return out, inferred, dropped
