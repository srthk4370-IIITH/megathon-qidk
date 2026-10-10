"""SOP candidate filter (PRD §12.2 step 1) and P0 template recommendations (rev 1.1 R1-06).

Resolutions of spec ambiguities (mirror these in Kotlin):
  * `when` is evaluated for EVERY step, immediate ones included ("always candidates" = not subject to
    LLM selection / never dropped by the post-check). Condition key -> list of allowed values; the step
    passes when every condition passes. A condition on a field that is MISSING passes (§12.1).
    Value "*" means "the field has any satisfied value" (MISSING -> fails, UNKNOWN -> passes).
    UNKNOWN fields compare as the string "UNKNOWN". List-valued fields pass if any element matches.
  * Categories: primary, then secondary types (form order), then GENERAL; steps in file order;
    duplicate ids skipped. The list is then stable-sorted immediate-first.
  * done-keyword removal: tokens = lower-case [a-z0-9]+ runs. For each done keyword phrase,
    overlap = |kw_tokens ∩ action_tokens| / |kw_tokens| where action_tokens is the union over all
    actions_taken; the step is removed when max overlap >= 0.6.
  * P0 recommendations = first 6 candidates (`app_config.recommend.max_items`), verbatim, with
    "{location}" replaced by the form location when present (sop.json currently has none).
"""
from __future__ import annotations

import re

from .config import Taxonomy, app_config, sop, taxonomy
from .form import MISSING, UNKNOWN, Form

_TOK = re.compile(r"[a-z0-9]+")


def _tokens(s: str) -> set[str]:
    return set(_TOK.findall(s.lower()))


def _cond_ok(form: Form, key: str, allowed: list[str]) -> bool:
    fs = form.fields.get(key)
    if fs is None or fs.status == MISSING:
        return "*" not in allowed
    if "*" in allowed:
        return True
    v = "UNKNOWN" if fs.status == UNKNOWN else fs.value
    if isinstance(v, list):
        return any(str(x) in allowed for x in v)
    if isinstance(v, bool):
        v = "YES" if v else "NO"
    return str(v) in allowed


def step_applies(form: Form, step: dict) -> bool:
    for k, allowed in (step.get("when") or {}).items():
        if not _cond_ok(form, k, allowed):
            return False
    return True


def already_done(step: dict, actions_taken: list[str], threshold: float = 0.6) -> bool:
    kws = step.get("done_keywords") or []
    if not kws or not actions_taken:
        return False
    at = set()
    for a in actions_taken:
        at |= _tokens(a)
    best = 0.0
    for kw in kws:
        kt = _tokens(kw)
        if kt:
            best = max(best, len(kt & at) / len(kt))
    return best >= threshold


def candidates(form: Form, tx: Taxonomy | None = None) -> list[dict]:
    lib = sop()["categories"]
    types = form.types() or ["OTHER"]
    seen: set[str] = set()
    out: list[dict] = []
    fs = form.fields.get("actions_taken")
    actions = list(fs.value) if fs is not None and fs.status not in (MISSING, UNKNOWN) and fs.value else []
    for cat in types + ["GENERAL"]:
        for st in lib.get(cat, []):
            if st["id"] in seen:
                continue
            if not step_applies(form, st):
                continue
            if already_done(st, actions):
                continue
            seen.add(st["id"])
            out.append(st)
    # stable sort: immediate first
    out.sort(key=lambda s: 0 if s.get("immediate") else 1)
    return out


def p0_recommendations(form: Form, max_items: int | None = None) -> list[dict]:
    n = max_items or app_config().get("recommend", {}).get("max_items", 6)
    loc = form.value("location")
    res = []
    for st in candidates(form)[:n]:
        text = st["text"]
        if "{location}" in text:
            text = text.replace("{location}", loc or "the area")
        res.append({"sop": st["id"], "text": text, "immediate": bool(st.get("immediate")), "source": "SOP_TEMPLATE"})
    return res
