"""Validator (PRD §14) — Python port of the Kotlin Validator, plus the severity floor (§10.6).

validate(form, now) -> ValidationResult
next_question(result, form, emergency_mode=None) -> FollowUpQuestion | None
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta

from .config import Taxonomy, taxonomy
from .form import DEFAULTED, FILLED, INFERRED, MISSING, SATISFYING, UNKNOWN, Form

SEVERITY_ORDER = ["LOW", "MEDIUM", "HIGH", "CRITICAL"]


@dataclass
class FieldIssue:
    key: str
    reason: str


@dataclass
class ValidationResult:
    complete: bool
    required: list[str]
    missing: list[str]
    invalid: list[FieldIssue] = field(default_factory=list)
    progress: float = 0.0


@dataclass
class FollowUpQuestion:
    fields: list[str]
    text_en: str
    text_hinglish: str

    def text(self, lang: str | None) -> str:
        return self.text_en if (lang or "en") == "en" else self.text_hinglish


def _valid(key: str, form: Form, tx: Taxonomy, now: datetime) -> str | None:
    """Return None if valid, else a reason code."""
    fs = form.fields.get(key)
    if fs is None or fs.status not in SATISFYING:
        return "MISSING"
    if fs.status == UNKNOWN:
        if key in ("incident_type", "description"):
            return "UNKNOWN_NOT_ALLOWED"
        return None
    if key == "occurred_at":
        rt = form.resolved_time(now)
        if rt is None:
            return "MISSING"
        if rt.instant > now + timedelta(minutes=5):
            return "FUTURE_TIME"
        if rt.instant < now - timedelta(days=30):
            return "TOO_OLD"
        return None
    f = tx.field(key)
    v = fs.value
    if f is None:
        return None
    t = f.type
    if t in ("enum", "enum_category"):
        allowed = tx.enum_values(key) or []
        return None if v in allowed else "BAD_ENUM"
    if t == "string":
        return None if isinstance(v, str) and len("".join(v.split())) >= 2 else "TOO_SHORT"
    if t in ("string_list", "person_list", "witness_list", "enum_category_list"):
        if not isinstance(v, list) or not v:
            return "EMPTY_LIST"
        for x in v:
            if isinstance(x, str) and len("".join(x.split())) < 2:
                return "BAD_ELEMENT"
            if isinstance(x, dict) and not x:
                return "BAD_ELEMENT"
        return None
    if t == "bool":
        return None if isinstance(v, bool) else "BAD_BOOL"
    if t == "int":
        return None if isinstance(v, int) and v >= 0 else "BAD_INT"
    return None


def required_fields(form: Form, tx: Taxonomy | None = None) -> list[str]:
    tx = tx or taxonomy()
    return tx.mandatory_for(form.types())


def validate(form: Form, now: datetime, tx: Taxonomy | None = None) -> ValidationResult:
    tx = tx or taxonomy()
    if is_no_incident(form):   # nothing to complete: the app discards it and keeps listening
        return ValidationResult(complete=False, required=[], missing=[], progress=0.0)
    req = required_fields(form, tx)
    missing, invalid = [], []
    for k in req:
        r = _valid(k, form, tx, now)
        if r is None:
            continue
        if r != "MISSING":
            invalid.append(FieldIssue(k, r))
        missing.append(k)   # invalid fields are not satisfied -> asked again (V5)
    missing = sort_missing(missing, tx)
    sat = len(req) - len(missing)
    return ValidationResult(complete=not missing, required=req, missing=missing, invalid=invalid,
                            progress=(sat / len(req)) if req else 1.0)


def sort_key(key: str, tx: Taxonomy):
    f = tx.field(key)
    return (0 if f.is_common else 1, tx.group_index(f.group), f.priority if f.priority is not None else 999, key)


def sort_missing(keys: list[str], tx: Taxonomy | None = None) -> list[str]:
    tx = tx or taxonomy()
    return sorted(keys, key=lambda k: sort_key(k, tx))


def _q(key: str, tx: Taxonomy) -> dict:
    f = tx.field(key)
    if f.q:
        return f.q
    return {"en": f"What is the {f.label.lower()}?", "hinglish": f"{f.label} kya hai?"}


def _pair_template(a: str, b: str, tx: Taxonomy) -> dict | None:
    for p in tx.pair_templates:
        if set(p["fields"]) == {a, b}:
            return p
    return None


def _strip_q(s: str) -> str:
    s = s.rstrip()
    return s[:-1] if s.endswith("?") else s


def group_rule(a: str, b: str, tx: Taxonomy) -> dict:
    qa, qb = _q(a, tx), _q(b, tx)
    en_b = qb["en"]
    en = f"{_strip_q(qa['en'])}, and {en_b[:1].lower() + en_b[1:]}"
    hi = f"{_strip_q(qa['hinglish'])}, aur {qb['hinglish']}"
    return {"en": en, "hinglish": hi}


def is_no_incident(form: Form) -> bool:
    """NO_INCIDENT sentinel form (small talk / mic test): no draft, no follow-up questions."""
    from .parser import is_no_incident as _ni
    return _ni({"description": form.value("description")})


def is_emergency(form: Form) -> bool:
    return form.value("severity") == "CRITICAL"


def next_question(result: ValidationResult, form: Form, tx: Taxonomy | None = None,
                  emergency_mode: bool | None = None) -> FollowUpQuestion | None:
    tx = tx or taxonomy()
    if emergency_mode is None:
        emergency_mode = is_emergency(form)
    def asked(k: str) -> int:
        fs = form.fields.get(k)
        return fs.asked_count if fs else 0

    cand = [k for k in result.missing if asked(k) < 2]
    if emergency_mode:
        cand = [k for k in cand if tx.field(k).is_common]
    if not cand:
        return None
    first = cand[0]
    g = tx.field(first).group
    partner = next((k for k in cand[1:] if tx.field(k).group == g), None)
    if partner is None:
        q = _q(first, tx)
        return FollowUpQuestion([first], q["en"], q["hinglish"])
    pt = _pair_template(first, partner, tx)
    if pt is not None:
        return FollowUpQuestion([first, partner], pt["en"], pt["hinglish"])
    gr = group_rule(first, partner, tx)
    return FollowUpQuestion([first, partner], gr["en"], gr["hinglish"])


# ---------------------------------------------------------------------------------------------------
# severity floor (§10.6) and consistency rules (§15.3)
# ---------------------------------------------------------------------------------------------------
def severity_floor(form: Form) -> str | None:
    v = form.value
    floor = None

    def raise_to(level: str):
        nonlocal floor
        if floor is None or SEVERITY_ORDER.index(level) > SEVERITY_ORDER.index(floor):
            floor = level

    if v("patient_condition") in ("UNCONSCIOUS", "NOT_BREATHING", "DECEASED"):
        raise_to("CRITICAL")
    if v("people_trapped") == "YES":
        raise_to("CRITICAL")
    if v("fire_status") == "ACTIVE":
        raise_to("CRITICAL" if v("evacuation_status") in ("NOT_STARTED", "IN_PROGRESS") else "HIGH")
    if "HAZMAT" in form.types() and v("release_status") == "ONGOING":
        raise_to("HIGH")
    if v("electrical_hazard") == "SHOCK" and v("injuries") == "YES":
        raise_to("CRITICAL")
    if v("injuries") == "YES":
        raise_to("MEDIUM")
    if v("collision_type") == "VEHICLE_PERSON":
        raise_to("HIGH")
    return floor


def apply_rules(form: Form) -> list[str]:
    """Consistency + severity floor. Raises, never lowers. Returns list of rule notes."""
    notes = []
    persons = form.value("persons") or []
    cnt = form.value("injured_count")
    if form.value("injuries") == "NO" and (any(p.get("injured") for p in persons) or (isinstance(cnt, int) and cnt > 0)):
        form.set("injuries", "YES", FILLED, "RULE")
        notes.append("injuries->YES (consistency)")
    if form.status("injuries") == MISSING and isinstance(cnt, int) and cnt > 0:
        form.set("injuries", "YES", INFERRED, "RULE")
        notes.append("injuries inferred from injured_count")
    fl = severity_floor(form)
    if fl:
        cur = form.value("severity")
        if cur is None or cur == "UNKNOWN":
            form.set("severity", fl, INFERRED, "RULE")
            notes.append(f"severity inferred {fl}")
        elif cur in SEVERITY_ORDER and SEVERITY_ORDER.index(fl) > SEVERITY_ORDER.index(cur):
            form.set("severity", fl, FILLED, "RULE")
            notes.append(f"severity raised {cur}->{fl}")
    return notes
