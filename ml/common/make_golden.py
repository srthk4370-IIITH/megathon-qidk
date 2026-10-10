"""Write the golden prompt files of RENDERING.md §4 to config/prompts/golden/.

For every <name>.txt also writes <name>.input.json with the exact inputs (and the intermediate
placeholder values) so the Kotlin PromptBuilderGoldenTest can reproduce the bytes.

Run:  python -m ml.common.make_golden      (from the repo root)
"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from . import prompt_builder as pb
from . import sop as soplib
from .config import CONFIG_DIR
from .form import FILLED, Form

GOLDEN_DIR = CONFIG_DIR / "prompts" / "golden"
SLIDE_TRANSCRIPT = ("There was a water leak near Conference Room 2 around 10 AM. "
                    "Maintenance has already isolated the area.")
SLIDE_QUESTION = "Was anyone hurt, and how serious is the situation right now?"
SLIDE_ANSWER = "No one is hurt, it's minor."
REPORTED_AT = datetime.fromisoformat("2026-10-10T10:07:12+05:30")


def slide_extraction_params() -> dict:
    """The slide example's extraction output (PRD §13.5 worked example)."""
    return {
        "lang": "en", "incident_type": "WATER", "title": "Water leak near Conference Room 2",
        "description": "A water leak occurred near Conference Room 2. Maintenance has isolated the area.",
        "location": "near Conference Room 2", "time": "around 10 AM",
        "actions_taken": ["Maintenance isolated the area"],
    }


def slide_form() -> Form:
    return Form.from_params(slide_extraction_params(), now=REPORTED_AT)


def slide_form_complete() -> Form:
    f = slide_form()
    f.set("injuries", "NO", FILLED, "LLM_PATCH", 1)
    f.set("severity", "LOW", FILLED, "LLM_PATCH", 1)
    f.set("leak_source", "burst pipe under the sink", FILLED, "LLM_PATCH", 2)
    f.set("leak_status", "STOPPED", FILLED, "LLM_PATCH", 2)
    return f


def _write(name: str, text: str, inputs: dict) -> None:
    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    with open(GOLDEN_DIR / f"{name}.txt", "wb") as f:
        f.write(text.encode("utf-8"))
    with open(GOLDEN_DIR / f"{name}.input.json", "w", encoding="utf-8", newline="\n") as f:
        json.dump(inputs, f, ensure_ascii=False, indent=2)
        f.write("\n")


def main() -> None:
    note = ("Form fields are flat (category fields are NOT nested); occurred_at.value is the spoken time "
            "expression. Status MISSING fields are absent. Compare the .txt bytes exactly (UTF-8, no BOS, "
            "no trailing newline).")
    # extract (base + ft)
    _write("extract_slide", pb.render_extract(SLIDE_TRANSCRIPT, "base"),
           {"task": "extract", "variant": "base", "transcript": SLIDE_TRANSCRIPT,
            "placeholders": {"category_list": pb.render_category_list(),
                             "severity_rubric": pb.render_severity_rubric(),
                             "field_catalogue": pb.render_field_catalogue()}})
    _write("extract_slide_ft", pb.render_extract(SLIDE_TRANSCRIPT, "ft"),
           {"task": "extract", "variant": "ft", "transcript": SLIDE_TRANSCRIPT})
    # patch
    form = slide_form()
    asked = ["injuries", "severity"]
    _write("patch_slide", pb.render_patch(form, asked, SLIDE_QUESTION, SLIDE_ANSWER),
           {"task": "patch", "form": form.to_json(), "asked_fields": asked, "question": SLIDE_QUESTION,
            "answer": SLIDE_ANSWER, "note": note,
            "placeholders": {"form_compact_json": pb.render_form_compact_json(form),
                             "asked_field_specs": pb.render_asked_field_specs(asked),
                             "asked_fields": pb.render_asked_fields(asked)}})
    # recommend
    form2 = slide_form_complete()
    cands = soplib.candidates(form2)
    _write("recommend_slide", pb.render_recommend(form2, cands),
           {"task": "recommend", "form": form2.to_json(), "candidate_ids": [c["id"] for c in cands],
            "note": note + " Candidates come from the SOP P0 filter (ml/common/sop.py docstring).",
            "placeholders": {"form_compact_json": pb.render_form_compact_json(form2),
                             "actions_taken_list": pb.render_actions_taken_list(form2),
                             "sop_candidates": pb.render_sop_candidates(cands)}})
    # warmup
    _write("warmup", pb.render_warmup(), {"task": "warmup"})
    for p in sorted(GOLDEN_DIR.glob("*")):
        print(f"{p.relative_to(CONFIG_DIR.parent)}  {p.stat().st_size} bytes")


if __name__ == "__main__":
    main()
