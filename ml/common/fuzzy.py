"""Token-set ratio in [0, 1] (rapidfuzz if installed, else a difflib-based equivalent)."""
from __future__ import annotations

import re

try:  # pragma: no cover - depends on environment
    from rapidfuzz import fuzz as _rf

    def token_set_ratio(a: str, b: str) -> float:
        return _rf.token_set_ratio(a, b) / 100.0

    HAVE_RAPIDFUZZ = True
except Exception:  # pragma: no cover
    import difflib

    HAVE_RAPIDFUZZ = False

    def _ratio(a: str, b: str) -> float:
        if not a and not b:
            return 1.0
        return difflib.SequenceMatcher(None, a, b).ratio()

    def token_set_ratio(a: str, b: str) -> float:
        ta = set(re.findall(r"\w+", a.lower()))
        tb = set(re.findall(r"\w+", b.lower()))
        if not ta or not tb:
            return 0.0
        inter = " ".join(sorted(ta & tb))
        da = " ".join(sorted(ta - tb))
        db = " ".join(sorted(tb - ta))
        s1 = (inter + " " + da).strip()
        s2 = (inter + " " + db).strip()
        cands = [_ratio(s1, s2)]
        if inter:
            cands += [_ratio(inter, s1), _ratio(inter, s2)]
        return max(cands)


def norm_text(s: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s\-]", " ", s.lower())).strip()


def similar(a: str, b: str, threshold: float = 0.8) -> bool:
    return token_set_ratio(norm_text(a), norm_text(b)) >= threshold
