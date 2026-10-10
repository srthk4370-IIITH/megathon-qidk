"""Python port of the deterministic time resolver (PRD §15.1, rules T1–T10, rev 1.1 T10 = DEFAULTED).

resolve(phrase, now) -> ResolvedTime. `now` must be timezone-aware (Asia/Kolkata on device).
Used by the validator (validity window) and by eval/score.py (time comparison ±15 min / same day-part).
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))

NUM_WORDS = {
    "a": 1, "an": 1, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7,
    "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "fifteen": 15, "twenty": 20,
    "thirty": 30, "forty": 40, "forty five": 45, "fortyfive": 45, "fifty": 50,
    "ek": 1, "do": 2, "teen": 3, "char": 4, "chaar": 4, "paanch": 5, "panch": 5, "chhe": 6, "chhah": 6,
    "saat": 7, "aath": 8, "nau": 9, "das": 10, "gyarah": 11, "gyaarah": 11, "barah": 12, "baarah": 12,
    "pandrah": 15, "bees": 20, "tees": 30, "chalis": 40, "pachas": 50,
}
_NUM_ALT = "|".join(sorted((re.escape(k) for k in NUM_WORDS), key=len, reverse=True))

DAYPARTS = [  # (regex, hour, minute, label)
    (r"late night", 1, 0, "late night"),
    (r"midnight|aadhi raat", 0, 0, "midnight"),
    (r"afternoon", 15, 0, "afternoon"),
    (r"noon|dopahar|dopehar", 13, 0, "noon"),
    (r"evening|shaam|sham", 18, 30, "evening"),
    (r"morning|subah|subha", 9, 0, "morning"),
    (r"night|raat|tonight", 22, 0, "night"),
]
WEEKDAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
MONTHS = ["january", "february", "march", "april", "may", "june", "july", "august", "september",
          "october", "november", "december"]
APPROX_RE = re.compile(r"\b(around|about|approximately|approx|roughly|lagbhag|kareeb|karib|takriban|takreeban|or so|ish)\b")


@dataclass
class ResolvedTime:
    instant: datetime
    approximate: bool
    granularity: str      # MINUTE | HOUR | DAYPART | DAY
    rule: str             # T1..T10
    defaulted: bool = False


def _norm(phrase: str) -> str:
    p = phrase.lower().strip()
    p = p.replace("a.m.", "am").replace("p.m.", "pm").replace("o'clock", "").replace("o clock", "")
    p = re.sub(r"[^\w:./\- ]", " ", p)
    p = re.sub(r"\s+", " ", p).strip()
    return p


def _num(tok: str) -> int | None:
    tok = tok.strip()
    if tok.isdigit():
        return int(tok)
    return NUM_WORDS.get(tok)


def daypart_of(dt: datetime) -> str:
    h = dt.hour
    if 5 <= h < 12:
        return "morning"
    if 12 <= h < 17:
        return "afternoon"
    if 17 <= h < 21:
        return "evening"
    return "night"


def resolve(phrase: str | None, now: datetime) -> ResolvedTime:
    if now.tzinfo is None:
        now = now.replace(tzinfo=IST)
    if not phrase or not phrase.strip():
        return ResolvedTime(now, True, "MINUTE", "T10", defaulted=True)
    p = _norm(phrase)
    approx_mod = bool(APPROX_RE.search(p))

    # T1
    if re.fullmatch(r"(just now|right now|abhi abhi|abhi|now|just abhi|abhi just)", p) or re.search(r"\b(just now|right now|abhi abhi)\b", p):
        return ResolvedTime(now, approx_mod, "MINUTE", "T1")
    # T3 (before T2 so that "half an hour ago" is not read as "an hour ago")
    if re.search(r"\b(half an hour|half hour|aadha ghanta|adha ghanta|30 min\w*) (ago|back|pehle|pahle)", p):
        return ResolvedTime(now - timedelta(minutes=30), True, "MINUTE", "T3")
    if re.search(r"\b(an hour and a half|one and a half hours?|1\.5 hours?|dedh ghant[ae]) (ago|back|pehle|pahle)", p):
        return ResolvedTime(now - timedelta(minutes=90), True, "MINUTE", "T3")
    if re.search(r"\b(thodi der|thori der|a while|a few minutes|few minutes|kuch der|kuch minute)\w* (ago|back|pehle|pahle)", p):
        return ResolvedTime(now - timedelta(minutes=15), True, "MINUTE", "T3")
    # T2
    m = re.search(rf"\b(\d+|{_NUM_ALT}) (minutes?|mins?|hours?|hrs?|ghanta|ghante|ghanton) (ago|back|pehle|pahle)", p)
    if m:
        n = _num(m.group(1)) or 1
        unit = m.group(2)
        if unit.startswith(("min",)):
            return ResolvedTime(now - timedelta(minutes=n), approx_mod, "MINUTE", "T2")
        return ResolvedTime(now - timedelta(hours=n), True, "HOUR", "T2")

    # T6 day
    explicit_date = False
    day = now.date()
    day_set = False
    last_night = False
    if re.search(r"\b(day before yesterday|parson|parso)\b", p):
        day = (now - timedelta(days=2)).date(); day_set = explicit_date = True
    elif re.search(r"\blast night\b|\bkal raat\b|\byesterday night\b", p):
        last_night = True; day_set = explicit_date = True
    elif re.search(r"\b(yesterday|kal)\b", p):
        day = (now - timedelta(days=1)).date(); day_set = explicit_date = True
    elif re.search(r"\b(today|aaj|this morning|this afternoon|this evening)\b", p):
        day = now.date(); day_set = True
    else:
        for i, wd in enumerate(WEEKDAYS):
            if re.search(rf"\b(last )?{wd}\b", p):
                delta = (now.weekday() - i) % 7 or 7
                day = (now - timedelta(days=delta)).date(); day_set = explicit_date = True
                break
        if not day_set:
            md = re.search(r"\b(\d{1,2})(st|nd|rd|th)? (of )?(" + "|".join(MONTHS) + r")\b", p)
            md2 = re.search(r"\b(" + "|".join(MONTHS) + r") (\d{1,2})(st|nd|rd|th)?\b", p)
            dn = re.search(r"\b(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b", p)
            try:
                if md:
                    day = now.date().replace(month=MONTHS.index(md.group(4)) + 1, day=int(md.group(1)))
                    day_set = explicit_date = True
                elif md2:
                    day = now.date().replace(month=MONTHS.index(md2.group(1)) + 1, day=int(md2.group(2)))
                    day_set = explicit_date = True
                elif dn:
                    y = int(dn.group(3)) if dn.group(3) else now.year
                    if y < 100:
                        y += 2000
                    day = now.date().replace(year=y, month=int(dn.group(2)), day=int(dn.group(1)))
                    day_set = explicit_date = True
            except ValueError:
                pass
            if day_set and day > now.date() and not (dn and dn.group(3)):
                # dates without a year are always in the past for incidents
                try:
                    day = day.replace(year=day.year - 1)
                except ValueError:
                    pass
    # strip date expressions so their numbers are not read as clock times
    p_clock = re.sub(r"\b\d{1,2}[/-]\d{1,2}(?:[/-]\d{2,4})?\b", " ", p)
    p_clock = re.sub(r"\b\d{1,2}(st|nd|rd|th)\b", " ", p_clock)
    p_clock = re.sub(r"\b(" + "|".join(MONTHS) + r") \d{1,2}\b", " ", p_clock)

    # T5 day-part
    dpart = None
    for rx, hh, mm, label in DAYPARTS:
        if re.search(rf"\b({rx})\b", p):
            dpart = (hh, mm, label)
            break
    if last_night and dpart is None:
        dpart = (22, 0, "night")

    # T4 clock
    hour = minute = None
    ampm = None
    m = re.search(r"\b(quarter past|half past|quarter to) (\d{1,2}|" + _NUM_ALT + r")\b", p_clock)
    if m:
        h = _num(m.group(2))
        if h is not None:
            if m.group(1) == "quarter past":
                hour, minute = h, 15
            elif m.group(1) == "half past":
                hour, minute = h, 30
            else:
                hour, minute = (h - 1) % 12 or 12, 45
    if hour is None:
        m = re.search(r"\b(saadhe|sadhe|sawa|sava|paune|pone) (\d{1,2}|" + _NUM_ALT + r")\b", p_clock)
        if m:
            h = _num(m.group(2))
            if h is not None:
                if m.group(1) in ("saadhe", "sadhe"):
                    hour, minute = h, 30
                elif m.group(1) in ("sawa", "sava"):
                    hour, minute = h, 15
                else:
                    hour, minute = (h - 1) % 12 or 12, 45
    if hour is None:
        m = re.search(r"\b(dedh|dhai|dhaai|derh)\b", p_clock)
        if m:
            hour, minute = (1, 30) if m.group(1) in ("dedh", "derh") else (2, 30)
    if hour is None:
        m = re.search(r"\b(\d{1,2})(?:[:.](\d{2}))? ?(am|pm)\b", p_clock)
        if m:
            hour, minute, ampm = int(m.group(1)), int(m.group(2) or 0), m.group(3)
    if hour is None:
        m = re.search(r"\b(\d{1,2})[:.](\d{2})\b", p_clock)
        if m:
            hour, minute = int(m.group(1)), int(m.group(2))
    if hour is None:
        m = re.search(rf"\b(?:at|around|about|by|lagbhag|kareeb|approximately|roughly)? ?(\d{{1,2}}|{_NUM_ALT}) ?(baje|bje|o clock)\b", p_clock)
        if not m:
            m = re.search(rf"\b(?:at|around|about|by|approximately|roughly) (\d{{1,2}}|{_NUM_ALT})\b(?! (minutes?|mins?|hours?|hrs?|days?))", p_clock)
        if not m and dpart is not None:
            m = re.search(r"\b(\d{1,2})\b(?! (minutes?|mins?|hours?|hrs?|days?))", p_clock)
        if not m and re.fullmatch(r"\d{1,2}", p_clock.strip()):
            m = re.match(r"(\d{1,2})", p_clock.strip())
        if m:
            h = _num(m.group(1))
            if h is not None and 0 <= h <= 23 and m.group(1) not in ("a", "an"):
                hour, minute = h, 0
    if hour is not None and not (0 <= hour <= 23 and 0 <= (minute or 0) <= 59):
        hour = minute = None

    if hour is None and dpart is None and not day_set:
        # T10: nothing matched
        return ResolvedTime(now, True, "MINUTE", "T10", defaulted=True)

    approx = approx_mod
    if hour is not None:
        minute = minute or 0
        h24 = hour
        if ampm == "pm" and hour < 12:
            h24 = hour + 12
        elif ampm == "am" and hour == 12:
            h24 = 0
        elif ampm is None and hour <= 12 and dpart is not None:
            label = dpart[2]
            if label == "morning":
                h24 = 0 if hour == 12 else hour
            elif label in ("afternoon", "evening"):
                h24 = hour if hour == 12 else hour + 12
            elif label == "noon":
                h24 = hour if hour == 12 else (hour + 12 if hour <= 6 else hour)
            elif label in ("night", "late night", "midnight"):
                h24 = hour + 12 if 6 <= hour <= 11 else (0 if hour == 12 else hour)
        gran = "MINUTE" if minute else "HOUR"
        rule = "T4"
        if last_night:
            day = now.date() if (h24 < 6) else (now - timedelta(days=1)).date()
            if h24 < 6 and now.hour >= 6:
                day = now.date()
        if ampm is None and dpart is None and hour <= 12:
            # T8: 12-hour ambiguity resolved backwards (most recent past occurrence)
            cands = []
            for hh in {hour % 12, hour % 12 + 12}:
                dt = datetime(day.year, day.month, day.day, hh, minute, tzinfo=now.tzinfo)
                cands.append(dt)
            past = [c for c in cands if c <= now + timedelta(minutes=5)]
            inst = max(past) if past else min(cands)
            rule = "T8"
        else:
            inst = datetime(day.year, day.month, day.day, h24, minute, tzinfo=now.tzinfo)
    elif dpart is not None:
        hh, mm, label = dpart
        if last_night:
            day = now.date() if now.hour < 6 else (now - timedelta(days=1)).date()
        if label == "late night" and not explicit_date and not last_night:
            pass
        inst = datetime(day.year, day.month, day.day, hh, mm, tzinfo=now.tzinfo)
        approx = True
        gran = "DAYPART"
        rule = "T5"
    else:
        inst = datetime(day.year, day.month, day.day, 12, 0, tzinfo=now.tzinfo)
        approx = True
        gran = "DAY"
        rule = "T6"

    # T9
    if inst > now + timedelta(minutes=5) and not explicit_date:
        inst = inst - timedelta(days=1)
        rule += "+T9"
    if gran in ("DAYPART", "DAY"):
        approx = True
    return ResolvedTime(inst, approx, gran, rule)


def times_match(a: ResolvedTime, b: ResolvedTime, tol_min: int = 15) -> bool:
    """§29.3: resolved instants within ±15 min, or same day-part (and date) when either is approximate."""
    if abs((a.instant - b.instant).total_seconds()) <= tol_min * 60:
        return True
    if (a.approximate or b.approximate) and (a.granularity in ("DAYPART", "DAY") or b.granularity in ("DAYPART", "DAY")):
        da = a.instant - timedelta(hours=5)  # night spans midnight: shift so 00-05 belongs to the previous evening/night
        db = b.instant - timedelta(hours=5)
        if da.date() == db.date() and daypart_of(a.instant) == daypart_of(b.instant):
            return True
        if a.granularity == "DAY" or b.granularity == "DAY":
            return a.instant.date() == b.instant.date()
    return False
