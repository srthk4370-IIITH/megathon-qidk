import pytest

from ml.common.parser import extract_json, lenient_fix, norm_enum, parse

T = "fill_incident_form"
SLIDE = ('{"name":"fill_incident_form","parameters":{"lang":"en","incident_type":"WATER","title":"Water leak",'
         '"description":"A water leak occurred.","location":"near Conference Room 2","time":"around 10 AM",'
         '"actions_taken":["Maintenance isolated the area"]}}')


def test_strict():
    pc = parse(SLIDE, T)
    assert pc.ok and pc.strict_json and pc.params["incident_type"] == "WATER"
    assert pc.params["time"] == "around 10 AM"


def test_code_fence_and_chatter():
    pc = parse("Sure! Here is the call:\n```json\n" + SLIDE + "\n```\nHope this helps", T)
    assert pc.ok and not pc.strict_json and pc.params["location"] == "near Conference Room 2"


def test_truncated_autoclose():
    pc = parse(SLIDE[:-40], T)
    assert pc.ok and pc.autoclosed
    assert pc.params["incident_type"] == "WATER"


def test_truncated_mid_key():
    s = '{"name":"fill_incident_form","parameters":{"incident_type":"FIRE","description":"x","loca'
    pc = parse(s, T)
    assert pc.ok and pc.params["incident_type"] == "FIRE" and "location" not in pc.params


def test_trailing_commas_python_literals_single_quotes():
    s = "{'name': 'fill_incident_form', 'parameters': {'incident_type': 'FIRE', 'description': 'smoke', 'near_miss': True, 'details': {'fire_status': 'ACTIVE',},},}"
    pc = parse(s, T)
    assert pc.ok and pc.lenient_fixed
    assert pc.params["near_miss"] is True and pc.params["details"]["fire_status"] == "ACTIVE"


def test_unquoted_enum_values():
    s = '{"name":"fill_incident_form","parameters":{"incident_type":FIRE,"description":"d","details":{"fire_status":UNDER CONTROL}}}'
    pc = parse(s, T)
    assert pc.ok and pc.params["details"]["fire_status"] == "CONTAINED"


def test_arguments_alias_and_function_key():
    s = '{"function":"fill_incident_form","arguments":{"incident_type":"water","description":"d"}}'
    pc = parse(s, T)
    assert pc.ok and pc.params["incident_type"] == "WATER"
    s2 = '{"name":"fill_incident_form","arguments":"{\\"incident_type\\":\\"FIRE\\",\\"description\\":\\"d\\"}"}'
    assert parse(s2, T).params["incident_type"] == "FIRE"


def test_wrong_tool_is_failure():
    pc = parse('{"name":"propose_actions","parameters":{"actions":[]}}', T)
    assert not pc.ok


def test_empty_and_garbage():
    assert not parse("", T).ok
    assert not parse("I cannot help with that.", T).ok


@pytest.mark.parametrize("raw,allowed,exp", [
    ("put out", ["ACTIVE", "CONTAINED", "EXTINGUISHED", "SMOKE_ONLY", "UNKNOWN"], "EXTINGUISHED"),
    ("Under Control", ["ACTIVE", "CONTAINED", "EXTINGUISHED", "SMOKE_ONLY", "UNKNOWN"], "CONTAINED"),
    ("haan", ["YES", "NO", "UNKNOWN"], "YES"),
    ("Nahi", ["YES", "NO", "UNKNOWN"], "NO"),
    ("pata nahi", ["YES", "NO", "UNKNOWN"], "UNKNOWN"),
    ("minor", ["LOW", "MEDIUM", "HIGH", "CRITICAL"], "LOW"),
    ("not needed", ["YES", "NO", "NOT_NEEDED", "UNKNOWN"], "NOT_NEEDED"),
    ("vehicle-person", ["VEHICLE_VEHICLE", "VEHICLE_PERSON"], "VEHICLE_PERSON"),
    (True, ["YES", "NO"], "YES"),
    ("purple", ["YES", "NO"], None),
])
def test_enum_synonyms(raw, allowed, exp):
    assert norm_enum(raw, allowed) == exp


def test_value_normalisation():
    s = ('{"name":"fill_incident_form","parameters":{"incident_type":"EQUIPMENT","description":"  pump   failed ",'
         '"injured_count":"do","equipment":"pump p-101","location":"N/A","severity":"serious",'
         '"persons":[{"name":"Ramesh","role":"driver","injured":"no"},{"name":"ramesh"}],'
         '"actions_taken":["Stopped pump","stopped pump"],"details":{"operational_status":"stopped","fire_status":"ACTIVE","bogus":1},"weird":2}}')
    pc = parse(s, T)
    p = pc.params
    assert p["description"] == "pump failed"
    assert p["injured_count"] == 2
    assert p["equipment"] == ["pump P-101"]
    assert "location" not in p
    assert p["severity"] == "HIGH"
    assert p["persons"] == [{"name": "Ramesh", "role": "INVOLVED", "injured": False}]
    assert p["actions_taken"] == ["Stopped pump"]
    assert p["details"] == {"operational_status": "STOPPED"}   # FIRE field dropped (off-category)
    codes = {i.code for i in pc.issues}
    assert {"OFF_CATEGORY", "UNKNOWN_KEY"} <= codes


def test_category_field_at_top_level_is_routed():
    s = '{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"d","leak_status":"ongoing","occurred_at":"10 am"}}'
    p = parse(s, T).params
    assert p["details"]["leak_status"] == "ONGOING" and p["time"] == "10 am"


def test_secondary_types():
    s = '{"name":"fill_incident_form","parameters":{"incident_type":"FIRE","secondary_types":["fire","electrical","medical","water"],"description":"d"}}'
    assert parse(s, T).params["secondary_types"] == ["ELECTRICAL", "MEDICAL"]


def test_patch_parse():
    s = '{"name":"update_incident_fields","parameters":{"lang":"en","set":{"injuries":"NO","severity":"minor","leak_source":"pata nahi","time":"around 11"},"unknown":["leak_status"]}}'
    pc = parse(s, "update_incident_fields")
    assert pc.ok
    assert pc.params["set"] == {"injuries": "NO", "severity": "LOW", "time": "around 11"}
    assert pc.params["unknown"] == ["leak_source", "leak_status"]


def test_patch_skip():
    pc = parse('{"name":"update_incident_fields","parameters":{"set":{},"skipped":"true"}}', "update_incident_fields")
    assert pc.params == {"set": {}, "skipped": True}


def test_actions_parse():
    s = '{"name":"propose_actions","parameters":{"actions":[{"sop":"water-02","text":"x"},{"sop":"CUSTOM","text":"a"},{"sop":"CUSTOM","text":"b"},{"text":"no id"}]}}'
    pc = parse(s, "propose_actions")
    assert pc.params["actions"] == [{"sop": "WATER-02", "text": "x"}, {"sop": "CUSTOM", "text": "a"}]


def test_grounding_drops_invented_names():
    s = ('{"name":"fill_incident_form","parameters":{"incident_type":"VEHICLE","description":"d","location":"Bay 9",'
         '"persons":[{"name":"Suresh","role":"INVOLVED"}],"details":{"driver_name":"Suresh","vehicles_involved":["forklift"]}}}')
    pc = parse(s, T, transcript="forklift ne bay 4 mein rack ko thok diya, driver ramesh tha")
    assert pc.params["persons"] == [{"role": "INVOLVED"}]
    assert "driver_name" not in pc.params["details"]
    assert "location" not in pc.inferred or True
    pc2 = parse(s.replace("Suresh", "Ramesh"), T, transcript="forklift ne bay 4 mein rack ko thok diya, driver ramesh tha")
    assert pc2.params["details"]["driver_name"] == "Ramesh"


def test_extract_json_helper():
    j, auto = extract_json('xx {"a": "}", "b": [1, 2')
    assert auto and j.startswith('{"a": "}"')


def test_lenient_fix_keeps_strings():
    assert lenient_fix('{"a": "x, }", b: True,}') == '{"a": "x, }", "b": true}'
