from datetime import datetime, timedelta

from ml.common import make_golden as mg
from ml.common.form import DEFAULTED, FILLED, UNKNOWN, Form
from ml.common.validator import apply_rules, next_question, validate

NOW = datetime.fromisoformat("2026-10-10T10:07:12+05:30")


def test_v1_slide_after_extraction():
    r = validate(mg.slide_form(), NOW)
    assert r.missing == ["severity", "injuries", "leak_source", "leak_status"]
    q = next_question(r, mg.slide_form())
    assert q.fields == ["severity", "injuries"]
    assert q.text_en == mg.SLIDE_QUESTION


def test_v2_after_patch():
    f = mg.slide_form()
    f.apply_patch({"set": {"injuries": "NO", "severity": "LOW"}})
    r = validate(f, NOW)
    assert r.missing == ["leak_source", "leak_status"]
    q = next_question(r, f)
    assert q.fields == ["leak_source", "leak_status"]
    assert q.text_en == "Where is the water coming from, and is it still leaking?"


def test_v2b_complete_after_second_patch():
    f = mg.slide_form()
    f.apply_patch({"set": {"injuries": "NO", "severity": "LOW"}})
    f.apply_patch({"set": {"leak_source": "burst pipe under the sink", "leak_status": "STOPPED"}})
    r = validate(f, NOW)
    assert r.complete and r.progress == 1.0 and next_question(r, f) is None


def test_v3_fire_plus_medical():
    f = Form.from_params({"incident_type": "FIRE", "secondary_types": ["MEDICAL"], "description": "something happened"}, now=NOW)
    r = validate(f, NOW)
    for k in ["fire_status", "evacuation_status", "fire_brigade_called", "patient_condition", "first_aid_given",
              "ambulance_called"]:
        assert k in r.required
    assert len(r.required) == 12


def test_v4_equipment_requires_equipment():
    f = Form.from_params({"incident_type": "EQUIPMENT", "description": "something happened"}, now=NOW)
    r = validate(f, NOW)
    assert "equipment" in r.required and "equipment" in r.missing


def test_v5_future_time():
    f = mg.slide_form()
    f.state("occurred_at").resolved = NOW + timedelta(hours=3)
    r = validate(f, NOW)
    assert any(i.key == "occurred_at" and i.reason == "FUTURE_TIME" for i in r.invalid)
    assert "occurred_at" in r.missing
    # explicit future date in the phrase
    f2 = Form.from_params({"incident_type": "OTHER", "description": "something happened", "time": "on 25-12-2026"}, now=NOW)
    r2 = validate(f2, NOW)
    assert any(i.reason == "FUTURE_TIME" for i in r2.invalid)
    old = Form.from_params({"incident_type": "OTHER", "description": "something happened", "time": "on 5th August"}, now=NOW)
    assert any(i.reason == "TOO_OLD" for i in validate(old, NOW).invalid)


def test_v6_all_unknown_complete():
    f = Form.from_params({"incident_type": "FIRE", "description": "something happened"}, now=NOW)
    for k in ["location", "occurred_at", "severity", "injuries", "fire_status", "evacuation_status", "fire_brigade_called"]:
        f.set(k, None, UNKNOWN, "USER")
    assert validate(f, NOW).complete
    g = Form.from_params({"incident_type": "OTHER", "description": "something happened"}, now=NOW)
    g.set("description", None, UNKNOWN)
    assert not validate(g, NOW).complete


def test_v7_asked_twice_skipped():
    f = mg.slide_form()
    f.state("severity").asked_count = 2
    r = validate(f, NOW)
    q = next_question(r, f)
    assert q.fields == ["injuries"] and q.text_en == "Was anyone hurt?"


def test_v8_critical_only_common():
    f = Form.from_params({"incident_type": "FIRE", "description": "something happened", "severity": "CRITICAL", "location": "Bay 1",
                          "injuries": "NO"}, now=NOW)
    r = validate(f, NOW)
    assert not r.complete
    assert next_question(r, f) is None  # only category fields missing -> nothing asked in emergency mode
    f.fields.pop("location")
    q = next_question(validate(f, NOW), f)
    assert q.fields == ["location"]


def test_v9_other_only_common():
    f = Form.from_params({"incident_type": "OTHER", "description": "something happened"}, now=NOW)
    r = validate(f, NOW)
    assert r.required == ["incident_type", "description", "location", "occurred_at", "severity", "injuries"]
    assert r.missing == ["location", "severity", "injuries"]   # time DEFAULTED (rev 1.1)


def test_v10_hinglish():
    f = mg.slide_form()
    f.lang = "hinglish"
    q = next_question(validate(f, NOW), f)
    assert q.text(f.lang) == "Kya kisi ko chot lagi, aur abhi situation kitni serious hai?"


def test_time_defaulted_rev11():
    f = Form.from_params({"incident_type": "OTHER", "description": "something happened"}, now=NOW)
    assert f.status("occurred_at") == DEFAULTED
    assert "occurred_at" not in validate(f, NOW).missing


def test_group_rule_and_pairs():
    f = Form.from_params({"incident_type": "SECURITY", "description": "something happened", "location": "Main Gate",
                          "severity": "LOW", "injuries": "NO"}, now=NOW)
    q = next_question(validate(f, NOW), f)
    assert q.fields == ["security_event"]  # hazard group alone
    f.set("security_event", "THEFT")
    q = next_question(validate(f, NOW), f)
    assert q.fields == ["security_informed"]
    h = Form.from_params({"incident_type": "HAZMAT", "description": "something happened", "location": "x y", "severity": "LOW",
                          "injuries": "NO"}, now=NOW)
    q = next_question(validate(h, NOW), h)
    assert q.fields == ["substance", "release_status"]
    assert q.text_en == "What gas or chemical is it, and is it still leaking?"
    m = Form.from_params({"incident_type": "ENVIRONMENTAL", "description": "something happened", "location": "x y", "severity": "LOW",
                          "injuries": "NO"}, now=NOW)
    q = next_question(validate(m, NOW), m)
    assert q.fields == ["pollutant", "release_medium"]
    assert q.text_en == "What was released, and did it go into a drain, the soil, water, or the air?"
    assert q.text_hinglish == "Kya cheez release hui ya phaili, aur Kya yeh naale, mitti, paani ya hawa mein gaya?"


def test_severity_rules():
    f = Form.from_params({"incident_type": "MEDICAL", "description": "something happened", "severity": "LOW",
                          "details": {"patient_condition": "UNCONSCIOUS"}}, now=NOW)
    apply_rules(f)
    assert f.value("severity") == "CRITICAL"
    g = Form.from_params({"incident_type": "OTHER", "description": "something happened", "injuries": "NO",
                          "persons": [{"name": "A", "injured": True}]}, now=NOW)
    apply_rules(g)
    assert g.value("injuries") == "YES" and g.value("severity") == "MEDIUM"


def test_no_incident_form_asks_nothing():
    from ml.common.validator import is_no_incident
    f = Form.from_params({"incident_type": "OTHER", "description": "NO_INCIDENT", "lang": "en"}, now=NOW)
    r = validate(f, NOW)
    assert is_no_incident(f) and not r.complete and r.missing == [] and next_question(r, f) is None
    assert not is_no_incident(mg.slide_form())
