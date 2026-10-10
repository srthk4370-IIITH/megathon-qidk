"""Shared eval pipeline (host + device runners): render prompts exactly like the app does.

Chain per case: extract -> (repair if unparseable) -> form -> [scripted follow-up] patch -> (repair).
The patch prompt uses the form built from the MODEL's extraction (realistic chain) and the case's scripted
question/asked fields/answer (scripted against the gold form; see gen_cases.py).
"""
from __future__ import annotations

import json
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from ml.common import prompt_builder as pb  # noqa: E402
from ml.common.config import app_config  # noqa: E402
from ml.common.form import Form  # noqa: E402
from ml.common.parser import parse  # noqa: E402

MAX_TOKENS = app_config()["llm"]["max_tokens"]


def load_cases(path, limit=None, ids=None):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                rows.append(json.loads(line))
    if ids:
        s = set(ids)
        rows = [r for r in rows if r["id"] in s]
    return rows[:limit] if limit else rows


def extract_prompt(transcript: str, variant: str = "base") -> str:
    return pb.render_extract(transcript, variant)


def repair_prompt(tool: str, broken: str) -> str:
    return pb.render_repair(tool, broken)


def form_from_output(case: dict, text: str | None, transcript: str):
    """Returns (ParsedCall|None, Form). Unparseable -> MANUAL_FILL form (description = transcript)."""
    now = datetime.fromisoformat(case["now"])
    pc = parse(text or "", "fill_incident_form", transcript=transcript)
    if pc.ok and pc.params.get("incident_type"):
        return pc, Form.from_params(pc.params, now=now)
    return pc, Form.from_params({"incident_type": "OTHER", "description": transcript}, now=now)


def patch_prompt(case: dict, form: Form) -> str | None:
    fu = case.get("followup")
    if not fu:
        return None
    return pb.render_patch(form, fu["asked_fields"], fu["question"], fu["answer"])


def needs_repair(text: str | None, tool: str) -> bool:
    if text is None or not text.strip():
        return False  # empty output = engine error, never a content failure (§13.7)
    return not parse(text, tool).ok
