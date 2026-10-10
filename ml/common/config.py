"""Load the shared contracts in `config/` (taxonomy, SOP, site registry, app config, prompts, schemas).

Nothing in ml/ or eval/ hard-codes a field name, enum value or category: everything is looked up here.
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
CONFIG_DIR = Path(os.environ.get("IV_CONFIG_DIR", REPO_ROOT / "config"))
PROMPTS_DIR = CONFIG_DIR / "prompts"
SCHEMAS_DIR = CONFIG_DIR / "schemas"

COMMON_MANDATORY_EXCLUDED_FROM_UNKNOWN = ("incident_type", "description")


def _read_json(path: Path) -> Any:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


@dataclass
class FieldDef:
    key: str
    type: str                    # enum, enum_category, enum_category_list, string, string_list, bool, int,
                                 # time_expression, person_list, witness_list
    mandatory: bool = False
    group: str | None = None
    priority: int | None = None
    label: str = ""
    enum: list[str] | None = None
    q: dict[str, str] | None = None
    category: str | None = None  # None for common fields
    system: bool = False
    llm_key: str | None = None
    raw: dict = field(default_factory=dict)

    @property
    def is_common(self) -> bool:
        return self.category is None

    @property
    def out_key(self) -> str:
        """Key used in LLM tool calls (occurred_at -> time)."""
        return self.llm_key or self.key


class Taxonomy:
    def __init__(self, data: dict):
        self.data = data
        self.groups_order: list[str] = data["groups_order"]
        self.common: list[FieldDef] = []
        for f in data["common_fields"]:
            self.common.append(self._mk(f, None))
        self.categories: list[dict] = data["categories"]
        self.category_codes: list[str] = [c["code"] for c in self.categories]
        self.cat_fields: dict[str, list[FieldDef]] = {}
        for c in self.categories:
            self.cat_fields[c["code"]] = [self._mk(f, c["code"]) for f in c.get("fields", [])]
        self.by_key: dict[str, FieldDef] = {}
        for f in self.common:
            self.by_key[f.key] = f
        for code, fl in self.cat_fields.items():
            for f in fl:
                if f.key in self.by_key:
                    raise ValueError(f"duplicate field key {f.key}")
                self.by_key[f.key] = f
        self.pair_templates: list[dict] = data.get("pair_templates", [])
        self.synonyms: dict[str, list[str]] = data.get("enum_synonyms", {})
        self.severity_rubric: dict[str, str] = data["severity_rubric"]
        self.emergency: dict = data.get("emergency", {})
        self.reprompts: dict = data.get("reprompts", {})

    @staticmethod
    def _mk(f: dict, cat: str | None) -> FieldDef:
        return FieldDef(
            key=f["key"], type=f["type"], mandatory=bool(f.get("mandatory", False)),
            group=f.get("group"), priority=f.get("priority"), label=f.get("label", f["key"]),
            enum=f.get("enum"), q=f.get("q"), category=cat, system=bool(f.get("system", False)),
            llm_key=f.get("llm_key"), raw=f,
        )

    def category(self, code: str) -> dict | None:
        for c in self.categories:
            if c["code"] == code:
                return c
        return None

    def field(self, key: str) -> FieldDef | None:
        if key == "time":
            key = "occurred_at"
        return self.by_key.get(key)

    def common_keys(self) -> list[str]:
        return [f.key for f in self.common]

    def is_common(self, key: str) -> bool:
        f = self.field(key)
        return f is not None and f.is_common

    def category_of(self, key: str) -> str | None:
        f = self.field(key)
        return f.category if f else None

    def enum_values(self, key: str) -> list[str] | None:
        f = self.field(key)
        if f is None:
            return None
        if f.type in ("enum_category", "enum_category_list"):
            return list(self.category_codes)
        return f.enum

    def common_mandatory(self) -> list[str]:
        return [f.key for f in self.common if f.mandatory]

    def mandatory_for(self, types: list[str]) -> list[str]:
        """PRD §10.5 mandatory set, de-duplicated, stable order (common, then per type)."""
        out = list(self.common_mandatory())
        for t in types:
            c = self.category(t)
            if not c:
                continue
            for k in c.get("extra_mandatory_common", []):
                if k not in out:
                    out.append(k)
            for f in self.cat_fields.get(t, []):
                if f.mandatory and f.key not in out:
                    out.append(f.key)
        return out

    def group_index(self, group: str | None) -> int:
        if group in self.groups_order:
            return self.groups_order.index(group)
        return len(self.groups_order)


@lru_cache(maxsize=1)
def taxonomy() -> Taxonomy:
    return Taxonomy(_read_json(CONFIG_DIR / "taxonomy.json"))


@lru_cache(maxsize=1)
def sop() -> dict:
    return _read_json(CONFIG_DIR / "sop.json")


@lru_cache(maxsize=1)
def site_registry() -> dict:
    return _read_json(CONFIG_DIR / "site_registry.json")


@lru_cache(maxsize=1)
def app_config() -> dict:
    return _read_json(CONFIG_DIR / "app_config.json")


@lru_cache(maxsize=None)
def tool_schema(name: str) -> dict:
    return _read_json(SCHEMAS_DIR / f"tool_{name}.json")


@lru_cache(maxsize=None)
def tool_schema_text(name: str) -> str:
    """Compact JSON, keys in file order, separators , and :, non-ASCII kept (RENDERING.md §2)."""
    return json.dumps(tool_schema(name), separators=(",", ":"), ensure_ascii=False)


@lru_cache(maxsize=None)
def prompt_template(filename: str) -> str:
    """Read a template: UTF-8, strip exactly one trailing \\n (RENDERING.md §1)."""
    with open(PROMPTS_DIR / filename, "rb") as f:
        text = f.read().decode("utf-8")
    if text.endswith("\n"):
        text = text[:-1]
    return text


@lru_cache(maxsize=1)
def fewshots() -> list[dict]:
    out = []
    with open(PROMPTS_DIR / "extract.fewshot.jsonl", "r", encoding="utf-8", newline="") as f:
        for line in f:
            line = line.strip("\r\n")
            if line.strip():
                out.append(json.loads(line))
    return out
