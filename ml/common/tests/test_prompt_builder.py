import re

import pytest

from ml.common import prompt_builder as pb
from ml.common import make_golden as mg
from ml.common import sop as soplib
from ml.common.config import fewshots, prompt_template, taxonomy
from ml.common.form import DEFAULTED, UNKNOWN, Form

HDR = re.compile(r"<\|start_header_id\|>(\w+)<\|end_header_id\|>\n\n")


def roles(prompt):
    return HDR.findall(prompt)


def test_extract_structure_base():
    p = pb.render_extract(mg.SLIDE_TRANSCRIPT, "base")
    assert not p.startswith("<|begin_of_text|>")
    assert "<|begin_of_text|>" not in p
    assert roles(p) == ["system", "user", "assistant", "user", "assistant", "user", "assistant"]
    assert p.endswith("<|start_header_id|>assistant<|end_header_id|>\n\n")
    assert "<|eot_id|>\n" not in p  # no newline between eot and next header
    assert not re.search(r"{{[a-z_]+}}", p)  # no unreplaced placeholder
    assert mg.SLIDE_TRANSCRIPT in p
    assert p.count("<|eot_id|>") == 6


def test_extract_structure_ft_has_no_fewshots():
    p = pb.render_extract("hello", "ft")
    assert roles(p) == ["system", "user", "assistant"]


def test_template_strip_one_newline():
    t = prompt_template("extract.user.txt")
    assert t.endswith(">>>") and not t.endswith("\n")


def test_field_catalogue_lines():
    cat = pb.render_field_catalogue().split("\n")
    tx = taxonomy()
    assert len(cat) == len(tx.categories) - 1  # no OTHER
    assert cat[0].startswith("FIRE: fire_status(ACTIVE|CONTAINED|EXTINGUISHED|SMOKE_ONLY|UNKNOWN), ")
    eq = [l for l in cat if l.startswith("EQUIPMENT")][0]
    assert eq.startswith("EQUIPMENT (also fill equipment): failure_description:text, operational_status(")
    assert "explosion_occurred:true/false" in cat[0]
    assert "items_affected:list of text" in [l for l in cat if l.startswith("SECURITY")][0]


def test_tool_schema_compact():
    p = pb.render_extract("x", "base")
    assert '{"name":"fill_incident_form","description":"Fill the incident report form from the transcript. Omit unknown fields.","parameters":{"type":"object"' in p


def test_unknown_placeholder_is_error():
    with pytest.raises(KeyError):
        pb.substitute("a {{nope}} b", {})


def test_form_compact_json_rules():
    f = Form.from_params({"incident_type": "FIRE", "description": "d", "location": "Bay 1",
                          "details": {"fire_status": "ACTIVE"}}, now=mg.REPORTED_AT)
    f.set("injuries", None, UNKNOWN)
    f.set("ignition_source", None, UNKNOWN)       # UNKNOWN non-enum -> omitted
    f.set("evacuation_status", None, UNKNOWN)     # UNKNOWN enum -> "UNKNOWN"
    s = pb.render_form_compact_json(f)
    assert s == ('{"incident_type":"FIRE","description":"d","location":"Bay 1","time":"now (assumed)",'
                 '"injuries":"UNKNOWN","details":{"fire_status":"ACTIVE","evacuation_status":"UNKNOWN"}}')
    assert f.status("occurred_at") == DEFAULTED


def test_patch_slide_matches_golden_inputs():
    form = mg.slide_form()
    p = pb.render_patch(form, ["injuries", "severity"], mg.SLIDE_QUESTION, mg.SLIDE_ANSWER)
    assert 'Current form: {"incident_type":"WATER","description":"A water leak occurred near Conference Room 2. Maintenance has isolated the area.","location":"near Conference Room 2","time":"around 10 AM","actions_taken":["Maintenance isolated the area"]}' in p
    assert "Asked fields: injuries, severity\n" in p
    assert "injuries(YES|NO|UNKNOWN)\nseverity(LOW|MEDIUM|HIGH|CRITICAL)" in p
    assert roles(p) == ["system", "user", "assistant"]


def test_asked_fields_time_rendering():
    assert pb.render_asked_fields(["location", "occurred_at"]) == "location, time"
    assert pb.render_asked_field_specs(["location", "occurred_at"]) == "location:text\ntime:text (time phrase as spoken)"


def test_recommend_slide_candidates():
    f = mg.slide_form_complete()
    ids = [c["id"] for c in soplib.candidates(f)]
    assert "WATER-01" not in ids          # leak_status STOPPED
    assert "WATER-03" not in ids          # done: "isolated the area"
    assert ids[0] == "WATER-02"           # immediate first
    assert ids[-4:] == ["GEN-01", "GEN-02", "GEN-03", "GEN-04"]
    p = pb.render_recommend(f, soplib.candidates(f))
    assert 'Already done: ["Maintenance isolated the area"]' in p


def test_warmup():
    assert pb.render_warmup() == "<|start_header_id|>user<|end_header_id|>\n\nSay OK.<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"


def test_golden_files_up_to_date():
    gd = mg.GOLDEN_DIR
    if not (gd / "extract_slide.txt").exists():
        pytest.skip("golden not generated")
    cases = {
        "extract_slide": pb.render_extract(mg.SLIDE_TRANSCRIPT, "base"),
        "extract_slide_ft": pb.render_extract(mg.SLIDE_TRANSCRIPT, "ft"),
        "patch_slide": pb.render_patch(mg.slide_form(), ["injuries", "severity"], mg.SLIDE_QUESTION, mg.SLIDE_ANSWER),
        "recommend_slide": pb.render_recommend(mg.slide_form_complete(), soplib.candidates(mg.slide_form_complete())),
        "warmup": pb.render_warmup(),
    }
    for name, text in cases.items():
        assert (gd / f"{name}.txt").read_bytes() == text.encode("utf-8"), name


def test_fewshots_parse_as_tool_calls():
    from ml.common.parser import parse
    for fs in fewshots():
        pc = parse(fs["assistant"], "fill_incident_form")
        assert pc.ok and pc.strict_json and not pc.issues, pc.issues
