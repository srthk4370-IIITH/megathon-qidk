from datetime import datetime

import pytest

from ml.common.timeres import resolve, times_match

NOW = datetime.fromisoformat("2026-10-10T17:30:00+05:30")


def at(s):
    return datetime.fromisoformat(s)


@pytest.mark.parametrize("phrase,expected,approx", [
    ("just now", "2026-10-10T17:30:00+05:30", False),
    ("abhi abhi", "2026-10-10T17:30:00+05:30", False),
    ("about half an hour ago", "2026-10-10T17:00:00+05:30", True),
    ("aadha ghanta pehle", "2026-10-10T17:00:00+05:30", True),
    ("an hour ago", "2026-10-10T16:30:00+05:30", True),
    ("about an hour ago", "2026-10-10T16:30:00+05:30", True),
    ("2 hours ago", "2026-10-10T15:30:00+05:30", True),
    ("two hours ago", "2026-10-10T15:30:00+05:30", True),
    ("do ghante pehle", "2026-10-10T15:30:00+05:30", True),
    ("15 minutes ago", "2026-10-10T17:15:00+05:30", False),
    ("a few minutes ago", "2026-10-10T17:15:00+05:30", True),
    ("thodi der pehle", "2026-10-10T17:15:00+05:30", True),
    ("an hour and a half ago", "2026-10-10T16:00:00+05:30", True),
    ("around 10 AM", "2026-10-10T10:00:00+05:30", True),
    ("at 10:30 am", "2026-10-10T10:30:00+05:30", False),
    ("10.30 pm", "2026-10-09T22:30:00+05:30", False),
    ("at 3:30 PM", "2026-10-10T15:30:00+05:30", False),
    ("around 2", "2026-10-10T14:00:00+05:30", True),
    ("at 6", "2026-10-10T06:00:00+05:30", False),
    ("quarter past 10 in the morning", "2026-10-10T10:15:00+05:30", False),
    ("half past 2", "2026-10-10T14:30:00+05:30", False),
    ("saadhe das baje", "2026-10-10T10:30:00+05:30", False),
    ("sawa das", "2026-10-10T10:15:00+05:30", False),
    ("paune das", "2026-10-10T09:45:00+05:30", False),
    ("this morning", "2026-10-10T09:00:00+05:30", True),
    ("in the afternoon", "2026-10-10T15:00:00+05:30", True),
    ("yesterday evening", "2026-10-09T18:30:00+05:30", True),
    ("last night", "2026-10-09T22:00:00+05:30", True),
    ("last night at 11", "2026-10-09T23:00:00+05:30", False),
    ("last night around 2", "2026-10-10T02:00:00+05:30", True),
    ("yesterday at 4 pm", "2026-10-09T16:00:00+05:30", False),
    ("day before yesterday", "2026-10-08T12:00:00+05:30", True),
    ("this morning around 9", "2026-10-10T09:00:00+05:30", True),
    ("at 7 in the evening", "2026-10-09T19:00:00+05:30", False),
    ("5th October", "2026-10-05T12:00:00+05:30", True),
    ("on Monday", "2026-10-05T12:00:00+05:30", True),
    ("at noon", "2026-10-10T13:00:00+05:30", True),
])
def test_resolve(phrase, expected, approx):
    r = resolve(phrase, NOW)
    assert r.instant == at(expected), (phrase, r)
    assert r.approximate == approx, (phrase, r)


def test_t10_default():
    r = resolve("", NOW)
    assert r.defaulted and r.instant == NOW and r.approximate
    r = resolve("I am not sure", NOW)
    assert r.rule == "T10"


def test_times_match():
    assert times_match(resolve("around 10 AM", NOW), resolve("10:10 am", NOW))
    assert not times_match(resolve("around 10 AM", NOW), resolve("11 am", NOW))
    assert times_match(resolve("this morning", NOW), resolve("around 10 AM", NOW))
    assert times_match(resolve("about an hour ago", NOW), resolve("an hour ago", NOW))
