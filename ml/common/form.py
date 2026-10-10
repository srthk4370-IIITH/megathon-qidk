"""In-memory incident form (mirror of the Kotlin IncidentForm / FieldState, PRD §14.1).

Category detail fields are stored flat (keys are globally unique, §10.1). `occurred_at.value` holds the
spoken time *expression*; the resolved instant is computed by `timeres` (or set explicitly in `resolved`).
"""
from __future__ import annotations

import copy
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from .config import Taxonomy, taxonomy
from . import timeres

MISSING, FILLED, INFERRED, UNKNOWN, USER_EDITED, DEFAULTED = (
    "MISSING", "FILLED", "INFERRED", "UNKNOWN", "USER_EDITED", "DEFAULTED")
SATISFYING = {FILLED, INFERRED, UNKNOWN, USER_EDITED, DEFAULTED}


@dataclass
class FieldState:
    key: str
    value: Any = None
    status: str = MISSING
    source: str = "LLM_EXTRACT"
    turn: int = 0
    grounded: bool = True
    asked_count: int = 0
    resolved: datetime | None = None   # occurred_at only: explicit instant (USER_EDITED etc.)

    def to_json(self) -> dict:
        d = {"value": self.value, "status": self.status}
        if self.source != "LLM_EXTRACT":
            d["source"] = self.source
        if self.asked_count:
            d["asked_count"] = self.asked_count
        if not self.grounded:
            d["grounded"] = False
        if self.resolved is not None:
            d["resolved"] = self.resolved.isoformat()
        return d


@dataclass
class Form:
    fields: dict[str, FieldState] = field(default_factory=dict)
    lang: str | None = None
    title: str | None = None
    reported_at: datetime | None = None

    # ---- access -------------------------------------------------------------------------------
    def state(self, key: str) -> FieldState:
        if key not in self.fields:
            self.fields[key] = FieldState(key)
        return self.fields[key]

    def value(self, key: str) -> Any:
        fs = self.fields.get(key)
        if fs is None or fs.status == MISSING:
            return None
        if fs.status == UNKNOWN:
            return "UNKNOWN"
        return fs.value

    def status(self, key: str) -> str:
        fs = self.fields.get(key)
        return fs.status if fs else MISSING

    def types(self) -> list[str]:
        out = []
        t = self.value("incident_type")
        if t:
            out.append(t)
        for s in self.value("secondary_types") or []:
            if s not in out:
                out.append(s)
        return out

    def set(self, key: str, value: Any, status: str = FILLED, source: str = "LLM_EXTRACT", turn: int = 0) -> None:
        fs = self.state(key)
        fs.value, fs.status, fs.source, fs.turn = value, status, source, turn

    def copy(self) -> "Form":
        return copy.deepcopy(self)

    # ---- construction -------------------------------------------------------------------------
    @classmethod
    def from_params(cls, params: dict, now: datetime | None = None, tx: Taxonomy | None = None,
                    source: str = "LLM_EXTRACT") -> "Form":
        """Build a form from *normalised* fill_incident_form parameters (parser output or gold)."""
        tx = tx or taxonomy()
        f = cls(reported_at=now)
        f.lang = params.get("lang")
        f.title = params.get("title")
        f._merge(params, tx, source, turn=0)
        if f.status("occurred_at") == MISSING:
            # rev 1.1 (R1-05): no time stated -> report time, approximate, DEFAULTED (not asked)
            f.set("occurred_at", None, DEFAULTED, "SYSTEM")
        return f

    def _merge(self, params: dict, tx: Taxonomy, source: str, turn: int) -> list[str]:
        changed = []
        flat = {k: v for k, v in params.items() if k not in ("details", "lang", "title")}
        flat.update(params.get("details") or {})
        for k, v in flat.items():
            key = "occurred_at" if k == "time" else k
            fd = tx.field(key)
            if fd is None or v is None:
                continue
            if isinstance(v, str) and v.upper() == "UNKNOWN" and fd.type in ("enum", "enum_category"):
                self.set(key, None, UNKNOWN, source, turn)
            else:
                self.set(key, v, FILLED, source, turn)
            changed.append(key)
        return changed

    def apply_patch(self, patch: dict, tx: Taxonomy | None = None, turn: int = 1) -> list[str]:
        """Apply normalised update_incident_fields parameters (set / unknown / skipped / lang)."""
        tx = tx or taxonomy()
        changed = self._merge(patch.get("set") or {}, tx, "LLM_PATCH", turn)
        for k in patch.get("unknown") or []:
            key = "occurred_at" if k == "time" else k
            if tx.field(key) is None:
                continue
            self.set(key, None, UNKNOWN, "LLM_PATCH", turn)
            changed.append(key)
        if patch.get("lang"):
            self.lang = patch["lang"]
        return changed

    def resolved_time(self, now: datetime) -> timeres.ResolvedTime | None:
        fs = self.fields.get("occurred_at")
        if fs is None or fs.status == MISSING:
            return None
        if fs.status == UNKNOWN:
            return None
        if fs.resolved is not None:
            return timeres.ResolvedTime(fs.resolved, False, "MINUTE", "EXPLICIT")
        if fs.status == DEFAULTED:
            return timeres.ResolvedTime(self.reported_at or now, True, "MINUTE", "T10", defaulted=True)
        return timeres.resolve(fs.value, self.reported_at or now)

    def to_params(self, tx: Taxonomy | None = None) -> dict:
        """Back to fill_incident_form shape (satisfied fields; UNKNOWN enums as "UNKNOWN")."""
        tx = tx or taxonomy()
        out: dict[str, Any] = {}
        if self.lang:
            out["lang"] = self.lang
        details: dict[str, Any] = {}
        for key, fs in self.fields.items():
            if fs.status in (MISSING,):
                continue
            fd = tx.field(key)
            if fd is None:
                continue
            if key == "occurred_at":
                if fs.status == DEFAULTED or fs.value is None:
                    continue
                v = fs.value
            elif fs.status == UNKNOWN:
                if fd.type not in ("enum", "enum_category"):
                    continue
                v = "UNKNOWN"
            else:
                v = fs.value
            if fd.is_common:
                out[fd.out_key] = v
            else:
                details[key] = v
        if self.title:
            out["title"] = self.title
        if details:
            out["details"] = details
        return out

    # ---- portable JSON (golden inputs, Kotlin twin) -------------------------------------------------
    def to_json(self) -> dict:
        return {"lang": self.lang, "title": self.title,
                "reported_at": self.reported_at.isoformat() if self.reported_at else None,
                "fields": {k: fs.to_json() for k, fs in self.fields.items() if fs.status != MISSING or fs.asked_count}}

    @classmethod
    def from_json(cls, d: dict) -> "Form":
        f = cls(lang=d.get("lang"), title=d.get("title"),
                reported_at=datetime.fromisoformat(d["reported_at"]) if d.get("reported_at") else None)
        for k, s in d.get("fields", {}).items():
            fs = FieldState(k, s.get("value"), s.get("status", FILLED), s.get("source", "LLM_EXTRACT"),
                            asked_count=s.get("asked_count", 0), grounded=s.get("grounded", True))
            if s.get("resolved"):
                fs.resolved = datetime.fromisoformat(s["resolved"])
            f.fields[k] = fs
        return f
