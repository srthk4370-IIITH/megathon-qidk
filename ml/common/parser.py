"""Lenient tool-call parser + normaliser (PRD §13.6, §13.7). Python twin of the Kotlin ToolCallParser.

parse(text, tool, transcript=None) -> ParsedCall
  1. extract JSON (strip code fences, first '{' to matching '}', auto-close if truncated)
  2. lenient fixes (trailing commas, single quotes, True/False/None, unquoted words)
  3. parse
  4. envelope check (name|function; parameters|arguments|args)
  5. field routing via taxonomy (unknown keys dropped + logged)
  6. value normalisation (enum synonyms, bool, int words, strings, arrays)
  7. schema checks field by field
  8. grounding (optional, needs transcript) — names/IDs dropped, other strings flagged INFERRED
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any

from .config import Taxonomy, taxonomy
from .timeres import NUM_WORDS

TOOLS = ("fill_incident_form", "update_incident_fields", "propose_actions")
JUNK_STRINGS = {"", "n/a", "na", "none", "null", "not mentioned", "unknown", "not specified", "not stated",
                "not known", "nil", "-", "?", "not available", "unspecified"}
UNKNOWN_PHRASES = {"unknown", "not sure", "dont know", "don't know", "do not know", "pata nahi", "maloom nahi",
                   "unsure", "no idea", "not known", "nahi pata", "pata nahin"}
LANG_MAP = {"en": "en", "english": "en", "eng": "en", "hinglish": "hinglish", "hi-en": "hinglish",
            "hindi-english": "hinglish", "hi": "hi", "hindi": "hi"}
ROLE_SYNONYMS = {"VICTIM": "AFFECTED", "INJURED": "AFFECTED", "PATIENT": "AFFECTED", "DRIVER": "INVOLVED",
                 "OPERATOR": "INVOLVED", "WORKER": "INVOLVED", "FIRST_AIDER": "RESPONDER", "RESCUER": "RESPONDER",
                 "HELPER": "RESPONDER", "SECURITY": "RESPONDER", "WITNESS": "OTHER", "SPEAKER": "REPORTER",
                 "CALLER": "REPORTER"}
MAX_STR = 300
NO_INCIDENT = "NO_INCIDENT"   # sentinel description: {"incident_type":"OTHER","description":"NO_INCIDENT","lang":..}


def is_no_incident(params: dict | None) -> bool:
    """True if extraction params carry the NO_INCIDENT sentinel (lenient: case / space / dash variants)."""
    d = (params or {}).get("description")
    return isinstance(d, str) and re.sub(r"[\s\-]+", "_", d.strip().strip(".").upper()) == NO_INCIDENT


@dataclass
class Issue:
    code: str
    key: str = ""
    detail: str = ""

    def __repr__(self) -> str:
        return f"{self.code}:{self.key}:{self.detail}"


@dataclass
class ParsedCall:
    ok: bool
    tool: str
    params: dict = field(default_factory=dict)
    issues: list[Issue] = field(default_factory=list)
    lenient_fixed: bool = False      # step 2 changed the text
    autoclosed: bool = False         # output was truncated and auto-closed
    strict_json: bool = False        # the stripped raw text was valid JSON as-is
    inferred: set[str] = field(default_factory=set)   # ungrounded strings kept as INFERRED
    raw_obj: Any = None
    no_incident: bool = False        # NO_INCIDENT sentinel: speech had no incident (small talk / mic test)


# ---------------------------------------------------------------------------------------------------
# 1. JSON extraction
# ---------------------------------------------------------------------------------------------------
_FENCE = re.compile(r"```[a-zA-Z]*")


def _scan(s: str, start: int) -> tuple[int, list[str], bool]:
    """Scan from s[start]=='{'. Returns (end_index_exclusive or -1, open stack, in_string)."""
    stack: list[str] = []
    in_str = False
    quote = ""
    esc = False
    i = start
    while i < len(s):
        ch = s[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == quote:
                in_str = False
        else:
            if ch == '"':
                in_str, quote = True, '"'
            elif ch in "{[":
                stack.append("}" if ch == "{" else "]")
            elif ch in "}]":
                if stack and stack[-1] == ch:
                    stack.pop()
                if not stack:
                    return i + 1, [], False
        i += 1
    return -1, stack, in_str


def _close(fragment: str, stack: list[str], in_str: bool) -> str:
    out = fragment
    if in_str:
        if out.endswith("\\"):
            out = out[:-1]
        out += '"'
    out = re.sub(r"[\s,]*$", "", out)
    if out.endswith(":"):
        out += "null"
    return out + "".join(reversed(stack))


def extract_json(text: str) -> tuple[str | None, bool]:
    """Returns (json_text, autoclosed)."""
    s = _FENCE.sub("", text)
    i = s.find("{")
    if i < 0:
        return None, False
    end, stack, in_str = _scan(s, i)
    if end > 0:
        return s[i:end], False
    return _close(s[i:], stack, in_str), True


def _truncation_candidates(fragment: str) -> list[str]:
    """For a truncated object: progressively cut back to the last top-level-ish comma and re-close."""
    out = []
    s = fragment
    for _ in range(12):
        # cut at the last comma outside strings
        in_str, esc, last = False, False, -1
        for j, ch in enumerate(s):
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
            elif ch == '"':
                in_str = True
            elif ch == ",":
                last = j
        if last <= 0:
            break
        s = s[:last]
        _, stack, ins = _scan(s + "\x00", 0) if s.startswith("{") else (-1, [], False)
        out.append(_close(s, stack, ins))
    return out


# ---------------------------------------------------------------------------------------------------
# 2. lenient fixes (token-aware rewrite)
# ---------------------------------------------------------------------------------------------------
def lenient_fix(s: str) -> str:
    out: list[str] = []
    i, n = 0, len(s)
    while i < n:
        ch = s[i]
        if ch == '"':
            j = i + 1
            esc = False
            while j < n:
                c = s[j]
                if esc:
                    esc = False
                elif c == "\\":
                    esc = True
                elif c == '"':
                    break
                elif c == "\n":
                    pass
                j += 1
            chunk = s[i:j + 1].replace("\n", "\\n").replace("\t", "\\t")
            out.append(chunk)
            i = j + 1
            continue
        if ch == "'":
            j = i + 1
            buf = []
            while j < n and s[j] != "'":
                if s[j] == "\\" and j + 1 < n:
                    buf.append(s[j:j + 2]); j += 2; continue
                # apostrophe inside a word ("don't") is not a closing quote
                if s[j] == '"':
                    buf.append('\\"')
                else:
                    buf.append(s[j])
                j += 1
            out.append('"' + "".join(buf) + '"')
            i = j + 1
            continue
        if ch.isalpha() or ch == "_":
            j = i
            while j < n and (s[j].isalnum() or s[j] in "_-"):
                j += 1
            # allow multi-word bare values: "UNDER CONTROL" up to , } ] :
            k = j
            while k < n and s[k] not in ",}]:\n\"'":
                k += 1
            word = s[i:j]
            if word in ("true", "false", "null"):
                out.append(word); i = j; continue
            if word == "True":
                out.append("true"); i = j; continue
            if word == "False":
                out.append("false"); i = j; continue
            if word == "None":
                out.append("null"); i = j; continue
            phrase = s[i:k].rstrip()
            out.append(json.dumps(phrase))
            i = i + len(phrase)
            continue
        if ch == ",":
            j = i + 1
            while j < n and s[j] in " \t\r\n":
                j += 1
            if j < n and s[j] in "}]":
                i += 1
                continue
        out.append(ch)
        i += 1
    return "".join(out)


def _loads(s: str) -> Any:
    return json.loads(s)


def load_lenient(text: str) -> tuple[Any, dict]:
    """Returns (obj or None, info dict {strict, fixed, autoclosed})."""
    info = {"strict": False, "fixed": False, "autoclosed": False}
    stripped = text.strip()
    try:
        obj = _loads(stripped)
        if isinstance(obj, dict):
            info["strict"] = True
            return obj, info
    except Exception:
        pass
    frag, auto = extract_json(text)
    if frag is None:
        return None, info
    info["autoclosed"] = auto
    for cand_i, cand in enumerate([frag] + (_truncation_candidates(frag) if auto else [])):
        try:
            obj = _loads(cand)
            if isinstance(obj, dict):
                return obj, info
        except Exception:
            pass
        try:
            obj = _loads(lenient_fix(cand))
            if isinstance(obj, dict):
                info["fixed"] = True
                return obj, info
        except Exception:
            pass
    return None, info


# ---------------------------------------------------------------------------------------------------
# value normalisation
# ---------------------------------------------------------------------------------------------------
def _clean_str(v: Any) -> str | None:
    if v is None or isinstance(v, (dict, list)):
        return None
    if isinstance(v, bool):
        return None
    s = re.sub(r"\s+", " ", str(v)).strip()
    if s.lower().strip(" .") in JUNK_STRINGS:
        return None
    return s[:MAX_STR]


def norm_enum(raw: Any, allowed: list[str], tx: Taxonomy | None = None) -> str | None:
    tx = tx or taxonomy()
    if raw is None:
        return None
    if isinstance(raw, bool):
        cand = "YES" if raw else "NO"
        return cand if cand in allowed else None
    if isinstance(raw, list):
        raw = raw[0] if raw else None
        if raw is None:
            return None
    s = str(raw).strip().strip(".!")
    up = re.sub(r"[\s\-]+", "_", s.upper())
    if up in allowed:
        return up
    low = re.sub(r"\s+", " ", s.lower().replace("_", " ")).strip()
    low2 = low.replace("'", "")
    for canon, syns in tx.synonyms.items():
        if canon not in allowed:
            continue
        for syn in syns:
            if low == syn or low2 == syn.replace("'", ""):
                return canon
    # category codes: accept the label's first word ("Medical emergency" -> MEDICAL)
    first = up.split("_")[0]
    if first in allowed and allowed is not None and len(first) >= 4:
        return first
    return None


def norm_bool(raw: Any) -> bool | None:
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, (int, float)):
        return bool(raw)
    s = str(raw).strip().lower()
    if s in ("yes", "true", "haan", "han", "ha", "1", "y", "ji", "haa"):
        return True
    if s in ("no", "false", "nahi", "nahin", "nai", "0", "n"):
        return False
    return None


def norm_int(raw: Any) -> int | None:
    if isinstance(raw, bool):
        return None
    if isinstance(raw, int):
        return raw if raw >= 0 else None
    if isinstance(raw, float):
        return int(raw) if raw >= 0 else None
    s = str(raw).strip().lower()
    m = re.search(r"\d+", s)
    if m:
        return int(m.group(0))
    if s in ("zero", "none", "no one", "nobody", "koi nahi"):
        return 0
    for w in s.split():
        if w in NUM_WORDS and w not in ("a", "an"):
            return NUM_WORDS[w]
    return None


_TAG = re.compile(r"\b([a-zA-Z]{1,4})-(\d{1,5}[a-zA-Z]?)\b")


def norm_str_list(raw: Any, asset_tags: bool = False) -> list[str] | None:
    if raw is None:
        return None
    items = raw if isinstance(raw, list) else [raw]
    out: list[str] = []
    seen: set[str] = set()
    for it in items:
        if isinstance(it, dict):
            it = it.get("name") or it.get("text") or it.get("value")
        s = _clean_str(it)
        if s is None:
            continue
        if asset_tags:
            s = _TAG.sub(lambda m: f"{m.group(1).upper()}-{m.group(2).upper()}", s)
        if s.lower() in seen:
            continue
        seen.add(s.lower())
        out.append(s)
    return out or None


def norm_persons(raw: Any, issues: list[Issue]) -> list[dict] | None:
    if raw is None:
        return None
    items = raw if isinstance(raw, list) else [raw]
    out, seen = [], set()
    for it in items:
        if isinstance(it, str):
            it = {"name": it}
        if not isinstance(it, dict):
            continue
        p: dict[str, Any] = {}
        name = _clean_str(it.get("name"))
        if name:
            p["name"] = name
        role = it.get("role")
        if role is not None:
            r = re.sub(r"[\s\-]+", "_", str(role).strip().upper())
            r = ROLE_SYNONYMS.get(r, r)
            if r in ("AFFECTED", "INVOLVED", "RESPONDER", "REPORTER", "OTHER"):
                p["role"] = r
            else:
                issues.append(Issue("BAD_ENUM", "persons.role", str(role)))
        if "injured" in it:
            b = norm_bool(it.get("injured"))
            if b is not None:
                p["injured"] = b
        inj = _clean_str(it.get("injury"))
        if inj:
            p["injury"] = inj
        if not p:
            continue
        k = (p.get("name") or "").lower()
        if k and k in seen:
            continue
        if k:
            seen.add(k)
        out.append(p)
    return out or None


def norm_witnesses(raw: Any) -> list[dict] | None:
    if raw is None:
        return None
    items = raw if isinstance(raw, list) else [raw]
    out, seen = [], set()
    for it in items:
        if isinstance(it, str):
            it = {"name": it}
        if not isinstance(it, dict):
            continue
        w = {}
        for k in ("name", "contact"):
            s = _clean_str(it.get(k))
            if s:
                w[k] = s
        if not w:
            continue
        key = (w.get("name") or "").lower()
        if key and key in seen:
            continue
        seen.add(key)
        out.append(w)
    return out or None


def norm_value(key: str, raw: Any, tx: Taxonomy, issues: list[Issue]) -> Any:
    """Normalise one field value by its taxonomy type. Returns None when it must be dropped."""
    if key == "time":
        return _clean_str(raw)
    if key == "lang":
        s = str(raw).strip().lower() if raw is not None else ""
        return LANG_MAP.get(s)
    f = tx.field(key)
    if f is None:
        return None
    t = f.type
    if t in ("enum", "enum_category"):
        v = norm_enum(raw, tx.enum_values(key) or [], tx)
        if v is None and raw not in (None, ""):
            issues.append(Issue("BAD_ENUM", key, str(raw)[:60]))
        return v
    if t == "enum_category_list":
        items = raw if isinstance(raw, list) else [raw]
        out = []
        for it in items:
            v = norm_enum(it, tx.category_codes, tx)
            if v and v not in out:
                out.append(v)
            elif v is None and it not in (None, ""):
                issues.append(Issue("BAD_ENUM", key, str(it)[:60]))
        return out or None   # max_items applied after removing the primary (_route_fill)
    if t == "string":
        v = _clean_str(raw)
        if v is not None and f.raw.get("max_len"):
            v = v[: f.raw["max_len"]]
        return v
    if t == "string_list":
        return norm_str_list(raw, asset_tags=(key in ("equipment", "vehicle_ids")))
    if t == "bool":
        return norm_bool(raw)
    if t == "int":
        return norm_int(raw)
    if t == "person_list":
        return norm_persons(raw, issues)
    if t == "witness_list":
        return norm_witnesses(raw)
    if t == "time_expression":
        return _clean_str(raw)
    return None


# ---------------------------------------------------------------------------------------------------
# envelope + routing
# ---------------------------------------------------------------------------------------------------
def _envelope(obj: dict, tool: str, issues: list[Issue]) -> tuple[bool, dict | None]:
    name = obj.get("name")
    if name is None and isinstance(obj.get("function"), str):
        name = obj["function"]
    if name is None and isinstance(obj.get("function"), dict):
        inner = obj["function"]
        return _envelope(inner, tool, issues)
    params = None
    for k in ("parameters", "arguments", "args"):
        if k in obj:
            params = obj[k]
            break
    if isinstance(params, str):
        try:
            params, _ = load_lenient(params)
        except Exception:
            params = None
    if name is None:
        # no envelope: accept a bare parameters object if it looks like the tool's parameters
        markers = {"fill_incident_form": ("incident_type", "description"),
                   "update_incident_fields": ("set",), "propose_actions": ("actions",)}[tool]
        if any(m in obj for m in markers):
            issues.append(Issue("ENVELOPE_MISSING", "", ""))
            return True, obj
        return False, None
    if str(name).strip() != tool:
        issues.append(Issue("WRONG_TOOL", "name", str(name)))
        return False, None
    if not isinstance(params, dict):
        issues.append(Issue("NO_PARAMETERS", "", ""))
        return False, None
    return True, params


def _route_fill(params: dict, tx: Taxonomy, issues: list[Issue]) -> dict:
    common_keys = set(tx.common_keys()) | {"time"}
    common_keys.discard("occurred_at")
    top: dict[str, Any] = {}
    details_raw: dict[str, Any] = {}
    for k, v in params.items():
        kk = "time" if k == "occurred_at" else k
        if kk == "details":
            if isinstance(v, dict):
                for dk, dv in v.items():
                    dkk = "time" if dk == "occurred_at" else dk
                    if dkk in common_keys:
                        top.setdefault(dkk, dv)
                        issues.append(Issue("MOVED_TO_COMMON", dkk))
                    elif tx.field(dk) is not None:
                        details_raw[dk] = dv
                    else:
                        issues.append(Issue("UNKNOWN_KEY", f"details.{dk}"))
            continue
        if kk in common_keys:
            top[kk] = v
        elif tx.field(kk) is not None:
            details_raw[kk] = v
            issues.append(Issue("MOVED_TO_DETAILS", kk))
        else:
            issues.append(Issue("UNKNOWN_KEY", kk))
    out: dict[str, Any] = {}
    for k, v in top.items():
        nv = norm_value(k, v, tx, issues)
        if nv is None:
            if v not in (None, "", [], {}):
                issues.append(Issue("DROPPED", k, str(v)[:60]))
            continue
        out[k] = nv
    # secondary types: never the primary
    if "secondary_types" in out and out.get("incident_type"):
        out["secondary_types"] = [t for t in out["secondary_types"] if t != out["incident_type"]][: tx.field("secondary_types").raw.get("max_items", 2)]
        if not out["secondary_types"]:
            del out["secondary_types"]
    types = [out.get("incident_type")] + list(out.get("secondary_types") or [])
    details: dict[str, Any] = {}
    for k, v in details_raw.items():
        cat = tx.category_of(k)
        if cat not in types:
            issues.append(Issue("OFF_CATEGORY", k, str(cat)))
            continue
        nv = norm_value(k, v, tx, issues)
        if nv is None:
            if v not in (None, "", [], {}):
                issues.append(Issue("DROPPED", k, str(v)[:60]))
            continue
        details[k] = nv
    if details:
        out["details"] = details
    return out


def _route_patch(params: dict, tx: Taxonomy, issues: list[Issue]) -> dict:
    out: dict[str, Any] = {}
    raw_set = params.get("set")
    if not isinstance(raw_set, dict):
        raw_set = {}
        # model put fields at top level
        for k, v in params.items():
            if k not in ("lang", "set", "unknown", "skipped") and (tx.field(k) or k == "time"):
                raw_set[k] = v
        if raw_set:
            issues.append(Issue("SET_MISSING", ""))
    flat: dict[str, Any] = {}
    for k, v in raw_set.items():
        if k == "details" and isinstance(v, dict):
            flat.update(v)
        else:
            flat[k] = v
    st: dict[str, Any] = {}
    unknown: list[str] = []
    for k, v in flat.items():
        kk = "time" if k == "occurred_at" else k
        if kk in ("lang", "title"):
            continue
        if kk != "time" and tx.field(kk) is None:
            issues.append(Issue("UNKNOWN_KEY", kk))
            continue
        if isinstance(v, str) and v.strip().lower().strip(".") in UNKNOWN_PHRASES:
            fd = tx.field(kk)
            if fd is None or fd.type not in ("enum", "enum_category") or "UNKNOWN" not in (tx.enum_values(kk) or []):
                unknown.append(kk)
                continue
        nv = norm_value(kk, v, tx, issues)
        if nv is None:
            if v not in (None, "", [], {}):
                issues.append(Issue("DROPPED", kk, str(v)[:60]))
            continue
        st[kk] = nv
    for k in params.get("unknown") or []:
        if not isinstance(k, str):
            continue
        kk = "time" if k.strip() == "occurred_at" else k.strip()
        if kk == "time" or tx.field(kk) is not None:
            if kk not in unknown and kk not in st:
                unknown.append(kk)
        else:
            issues.append(Issue("UNKNOWN_KEY", f"unknown.{kk}"))
    out["set"] = st
    if unknown:
        out["unknown"] = unknown
    if "skipped" in params:
        b = norm_bool(params["skipped"])
        if b:
            out["skipped"] = True
    if params.get("lang") is not None:
        lg = norm_value("lang", params["lang"], tx, issues)
        if lg:
            out["lang"] = lg
    return out


def _route_actions(params: dict, issues: list[Issue], max_items: int = 6, max_len: int = 160) -> dict:
    acts = params.get("actions")
    if isinstance(acts, dict):
        acts = [acts]
    out = []
    custom = 0
    for a in acts or []:
        if isinstance(a, str):
            issues.append(Issue("ACTION_NO_SOP", "", a[:40]))
            continue
        if not isinstance(a, dict):
            continue
        sid = _clean_str(a.get("sop") or a.get("id"))
        text = _clean_str(a.get("text") or a.get("action"))
        if not sid or not text:
            issues.append(Issue("ACTION_INCOMPLETE", "", str(a)[:60]))
            continue
        sid = sid.upper()
        if sid == "CUSTOM":
            custom += 1
            if custom > 1:
                issues.append(Issue("EXTRA_CUSTOM", ""))
                continue
        out.append({"sop": sid, "text": text[:max_len]})
    return {"actions": out[:max_items]}


# ---------------------------------------------------------------------------------------------------
# public API
# ---------------------------------------------------------------------------------------------------
def parse(text: str, tool: str, transcript: str | list[str] | None = None, tx: Taxonomy | None = None) -> ParsedCall:
    tx = tx or taxonomy()
    if tool not in TOOLS:
        raise ValueError(tool)
    pc = ParsedCall(ok=False, tool=tool)
    if text is None or not text.strip():
        pc.issues.append(Issue("EMPTY", ""))
        return pc
    obj, info = load_lenient(text)
    pc.strict_json = info["strict"]
    pc.lenient_fixed = info["fixed"]
    pc.autoclosed = info["autoclosed"]
    if obj is None:
        pc.issues.append(Issue("UNPARSEABLE", ""))
        return pc
    pc.raw_obj = obj
    ok, params = _envelope(obj, tool, pc.issues)
    if not ok or params is None:
        return pc
    if tool == "fill_incident_form" and is_no_incident(params):
        # small talk / mic test: canonical sentinel only, anything else the model added is dropped
        pc.no_incident = True
        lang = norm_value("lang", params.get("lang"), tx, pc.issues) if "lang" in params else None
        pc.params = {"incident_type": "OTHER", "description": NO_INCIDENT, **({"lang": lang} if lang else {})}
        pc.ok = True
        return pc
    if tool == "fill_incident_form":
        out = _route_fill(params, tx, pc.issues)
        if "incident_type" not in out:
            pc.issues.append(Issue("MISSING_REQUIRED", "incident_type"))
    elif tool == "update_incident_fields":
        out = _route_patch(params, tx, pc.issues)
    else:
        out = _route_actions(params, pc.issues)
    if transcript is not None and tool in ("fill_incident_form", "update_incident_fields"):
        from .grounding import apply_grounding
        texts = [transcript] if isinstance(transcript, str) else list(transcript)
        out, inferred, dropped = apply_grounding(out, texts, tool)
        pc.inferred = inferred
        pc.issues.extend(Issue("UNGROUNDED_DROPPED", k) for k in dropped)
    pc.params = out
    pc.ok = True
    return pc
