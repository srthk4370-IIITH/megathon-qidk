"""Prompt renderer — Python twin of the Kotlin PromptBuilder. Normative spec: config/prompts/RENDERING.md.

Every public render_* returns the full Llama 3.2 chat string WITHOUT <|begin_of_text|> (Genie adds BOS).
"""
from __future__ import annotations

import json
import re
from typing import Any, Iterable

from .config import FieldDef, Taxonomy, fewshots, prompt_template, taxonomy, tool_schema_text
from .form import DEFAULTED, MISSING, UNKNOWN, Form

HDR_START = "<|start_header_id|>"
HDR_END = "<|end_header_id|>"
EOT = "<|eot_id|>"
BOS = "<|begin_of_text|>"

_PLACEHOLDER = re.compile(r"\{\{([a-zA-Z0-9_]+)\}\}")


def compact_json(obj: Any) -> str:
    """RENDERING.md: separators , and : (no spaces), non-ASCII kept as-is, keys in insertion order."""
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False)


def substitute(template: str, values: dict[str, str]) -> str:
    """Plain {{name}} replacement; unknown placeholders are an error (RENDERING.md §2)."""
    def rep(m: re.Match) -> str:
        name = m.group(1)
        if name not in values:
            raise KeyError(f"unknown placeholder {{{{{name}}}}}")
        return values[name]
    return _PLACEHOLDER.sub(rep, template)


def segment(role: str, text: str) -> str:
    return f"{HDR_START}{role}{HDR_END}\n\n{text}{EOT}"


def assemble(system: str | None, user: str, shots: Iterable[dict] = ()) -> str:
    parts = []
    if system is not None:
        parts.append(segment("system", system))
    for fs in shots:
        parts.append(segment("user", fs["user"]))
        parts.append(segment("assistant", fs["assistant"]))
    parts.append(segment("user", user))
    parts.append(f"{HDR_START}assistant{HDR_END}\n\n")
    return "".join(parts)


# ---------------------------------------------------------------------------------------------------
# placeholder values
# ---------------------------------------------------------------------------------------------------
def render_category_list(tx: Taxonomy | None = None) -> str:
    tx = tx or taxonomy()
    return "\n".join(f"{c['code']}: {c['definition']}" for c in tx.categories)


def render_severity_rubric(tx: Taxonomy | None = None) -> str:
    tx = tx or taxonomy()
    return "\n".join(f"{lvl}: {tx.severity_rubric[lvl]}" for lvl in ("CRITICAL", "HIGH", "MEDIUM", "LOW"))


def render_field_spec(f: FieldDef, tx: Taxonomy | None = None) -> str:
    """Field rendering (RENDERING.md §2). Resolutions for types the spec does not list:
    enum_category -> key(FIRE|...|OTHER); enum_category_list -> key:list of (FIRE|...|OTHER);
    person_list / witness_list -> key:list of objects."""
    tx = tx or taxonomy()
    t = f.type
    if f.key == "occurred_at" or t == "time_expression":
        return "time:text (time phrase as spoken)"
    if t == "enum":
        return f"{f.key}({'|'.join(f.enum or [])})"
    if t == "enum_category":
        return f"{f.key}({'|'.join(tx.category_codes)})"
    if t == "enum_category_list":
        return f"{f.key}:list of ({'|'.join(tx.category_codes)})"
    if t == "string":
        return f"{f.key}:text"
    if t == "string_list":
        return f"{f.key}:list of text"
    if t == "bool":
        return f"{f.key}:true/false"
    if t == "int":
        return f"{f.key}:number"
    if t in ("person_list", "witness_list"):
        return f"{f.key}:list of objects"
    raise ValueError(f"no rendering for field type {t} ({f.key})")


def render_field_catalogue(tx: Taxonomy | None = None) -> str:
    tx = tx or taxonomy()
    lines = []
    for c in tx.categories:
        code = c["code"]
        if code == "OTHER":
            continue
        head = code
        extra = c.get("extra_mandatory_common") or []
        if extra:
            head = f"{code} (also fill {', '.join(extra)})"
        specs = ", ".join(render_field_spec(f, tx) for f in tx.cat_fields[code])
        lines.append(f"{head}: {specs}")
    return "\n".join(lines)


def render_asked_field_specs(asked: list[str], tx: Taxonomy | None = None) -> str:
    tx = tx or taxonomy()
    out = []
    for k in asked:
        f = tx.field(k)
        if f is None:
            raise KeyError(f"unknown asked field {k}")
        out.append(render_field_spec(f, tx))
    return "\n".join(out)


def render_asked_fields(asked: list[str]) -> str:
    return ", ".join("time" if k == "occurred_at" else k for k in asked)


def form_compact_obj(form: Form, tx: Taxonomy | None = None) -> dict:
    """Satisfied fields only (status != MISSING). UNKNOWN: enums as "UNKNOWN", others omitted.
    Common fields in taxonomy order (lang, title omitted; occurred_at -> "time"), then "details"
    with category fields in taxonomy order (category order, then field order)."""
    tx = tx or taxonomy()
    out: dict[str, Any] = {}
    for f in tx.common:
        if f.key in ("lang", "title"):
            continue
        fs = form.fields.get(f.key)
        if fs is None or fs.status == MISSING:
            continue
        if f.key == "occurred_at":
            if fs.status == DEFAULTED:
                out["time"] = "now (assumed)"
            elif fs.status == UNKNOWN or fs.value is None:
                continue
            else:
                out["time"] = fs.value
            continue
        if fs.status == UNKNOWN:
            if f.type in ("enum", "enum_category"):
                out[f.key] = "UNKNOWN"
            continue
        if fs.value is None:
            continue
        out[f.key] = fs.value
    details: dict[str, Any] = {}
    for c in tx.categories:
        for f in tx.cat_fields[c["code"]]:
            fs = form.fields.get(f.key)
            if fs is None or fs.status == MISSING:
                continue
            if fs.status == UNKNOWN:
                if f.type == "enum":
                    details[f.key] = "UNKNOWN"
                continue
            if fs.value is None:
                continue
            details[f.key] = fs.value
    if details:
        out["details"] = details
    return out


def render_form_compact_json(form: Form, tx: Taxonomy | None = None) -> str:
    return compact_json(form_compact_obj(form, tx))


def render_actions_taken_list(form: Form) -> str:
    fs = form.fields.get("actions_taken")
    vals = []
    if fs is not None and fs.status not in (MISSING, UNKNOWN) and fs.value:
        vals = list(fs.value)
    return compact_json(vals)


def render_sop_candidates(candidates: list[dict]) -> str:
    return "\n".join(f"{c['id']}: {c['text']}" for c in candidates)


# ---------------------------------------------------------------------------------------------------
# tasks
# ---------------------------------------------------------------------------------------------------
def extract_system(variant: str = "base", tx: Taxonomy | None = None) -> str:
    tpl = prompt_template("extract.system.txt" if variant == "base" else "extract.system.ft.txt")
    return substitute(tpl, {
        "category_list": render_category_list(tx),
        "severity_rubric": render_severity_rubric(tx),
        "field_catalogue": render_field_catalogue(tx),
        "tool_schema_fill_incident_form": tool_schema_text("fill_incident_form"),
    })


def extract_user(transcript: str) -> str:
    return substitute(prompt_template("extract.user.txt"), {"transcript": transcript.strip()})


def render_extract(transcript: str, variant: str = "base") -> str:
    if variant not in ("base", "ft"):
        raise ValueError(variant)
    shots = fewshots() if variant == "base" else []
    return assemble(extract_system(variant), extract_user(transcript), shots)


def render_patch(form: Form, asked_fields: list[str], question: str, answer: str) -> str:
    system = substitute(prompt_template("patch.system.txt"), {
        "asked_field_specs": render_asked_field_specs(asked_fields),
        "tool_schema_update_incident_fields": tool_schema_text("update_incident_fields"),
    })
    user = substitute(prompt_template("patch.user.txt"), {
        "form_compact_json": render_form_compact_json(form),
        "question_text": question,
        "asked_fields": render_asked_fields(asked_fields),
        "answer": answer.strip(),
    })
    return assemble(system, user)


def render_recommend(form: Form, candidates: list[dict]) -> str:
    system = substitute(prompt_template("recommend.system.txt"), {
        "tool_schema_propose_actions": tool_schema_text("propose_actions"),
    })
    user = substitute(prompt_template("recommend.user.txt"), {
        "form_compact_json": render_form_compact_json(form),
        "actions_taken_list": render_actions_taken_list(form),
        "sop_candidates": render_sop_candidates(candidates),
    })
    return assemble(system, user)


def render_repair(tool: str, broken: str) -> str:
    system = substitute(prompt_template("repair.system.txt"), {"tool_name": tool})
    user = substitute(prompt_template("repair.user.txt"), {
        "tool_schema": tool_schema_text(tool),
        "broken": broken.strip(),
    })
    return assemble(system, user)


def render_warmup() -> str:
    return assemble(None, prompt_template("warmup.user.txt"))
