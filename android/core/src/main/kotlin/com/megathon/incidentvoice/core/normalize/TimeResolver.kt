package com.megathon.incidentvoice.core.normalize

import com.megathon.incidentvoice.core.model.ResolvedTime
import com.megathon.incidentvoice.core.model.TimeGranularity
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Deterministic time resolver, PRD §15.1 (rules T1–T10, rev 1.1 T10 = default to report time).
 * Input is the LLM's English `time` phrase (may still contain Hinglish). Zone is injectable (default Asia/Kolkata).
 *
 * Implementation notes (where the spec is silent):
 *  - T3 is checked before T2 so "half an hour ago" is not read as "an hour ago".
 *  - T1 only fires when the whole phrase (minus modifiers) is a "now" phrase, so "abhi 5 minute pehle" is T2.
 *  - Day-only phrases ("yesterday", "Monday") resolve to 12:00 that day, approximate, DAY granularity
 *    ("today" alone resolves to now).
 *  - A weekday equal to today's means one week ago.
 *  - "last night" = yesterday 22:00; with a clock hour < 06:00 the date becomes today (after midnight).
 */
class TimeResolver(val zone: ZoneId = ZoneId.of("Asia/Kolkata")) {

    private enum class DayPart(val time: LocalTime) {
        EARLY_MORNING(LocalTime.of(6, 0)), MORNING(LocalTime.of(9, 0)), NOON(LocalTime.of(13, 0)),
        AFTERNOON(LocalTime.of(15, 0)), EVENING(LocalTime.of(18, 30)), NIGHT(LocalTime.of(22, 0)),
        MIDNIGHT(LocalTime.of(0, 0)), LATE_NIGHT(LocalTime.of(1, 0)),
    }

    private data class Clock(val hour: Int, val minute: Int, val ampm: String?, val minutesGiven: Boolean)

    companion object {
        val FUTURE_TOLERANCE: Duration = Duration.ofMinutes(5)

        private const val HI_NUM =
            "ek|do|doh|teen|tin|char|chaar|paanch|panch|chhe|chhah|chah|saat|aath|nau|das|dus|gyarah|gyaarah|gyara|barah|baarah|bara"
        private const val UNIT_LOOKAHEAD =
            """(?=\s*(baje|bajke|bajey|bje|ghant\w*|minute\w*|mins?\b|mint\b|minat\b|din\b|hours?\b|hrs?\b|days?\b|am\b|pm\b))"""
        private const val HALF_WORDS = "saadhe|sadhe|saade|sawa|sava|savaa|paune|pone|paone"

        private val MODIFIER = Regex(
            """\b(around|about|approx|approximately|roughly|nearly|almost|lagbhag|lag bhag|kareeb|karib|qareeb|takriban|takreeban|sometime|maybe|shayad|probably|or so)\b|\b\d{1,2}\s*-?ish\b""",
        )
        private val NOW_PHRASE = Regex(
            """^(just now|right now|abhi abhi|abhi|abhi just|now|just|just happened|a moment ago|moments ago|this moment|turant|abhi turant|abhi kuch der pehle)$""",
        )
        private val T3_HALF = Regex("""\b(half an hour|half hour|half an hr|aadha ghanta|adha ghanta|aadhe ghante|aadha ghante)\s*(ago|back|pehle|pahle|before|earlier)\b""")
        private val T3_WHILE = Regex("""\b(thodi der|thori der|thodi si der|kuch der|a while|a little while|little while|a short while|short while|some time|sometime)\s*(ago|back|pehle|pahle|before|earlier)\b""")
        private val T2 = Regex(
            """\b(\d+(?:\.\d+)?|an|a|couple of|a couple of|few|a few|dedh|dhai|dhaai|adhai)\s*(minutes?|mins?|mint|minat|hours?|hrs?|hr|ghanta|ghante|ghanton)\s*(and a half\s*)?(ago|back|pehle|pahle|before|earlier|hue|huye|ho gaye)\b""",
        )
        private val DAYS_AGO = Regex("""\b(\d+|a|one|couple of|a couple of|few|a few)\s*(days?|din)\s*(ago|back|pehle|pahle|before)\b""")
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val DATE_DM = Regex("""\b(\d{1,2})(st|nd|rd|th)?\s+(of\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?(\s+(\d{4}))?\b""")
        private val DATE_MD = Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(st|nd|rd|th)?\b(,?\s+(\d{4}))?""")
        private val DATE_ISO = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
        private val DATE_NUM = Regex("""\b(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b""")
        private val DATE_NUM_DOT = Regex("""\b(\d{1,2})\.(\d{1,2})\.(\d{2,4})\b""")
        private val WEEKDAYS: List<Pair<Regex, DayOfWeek>> = listOf(
            "monday|somvar|somwar|somvaar" to DayOfWeek.MONDAY,
            "tuesday|mangalvar|mangalwar|mangalvaar" to DayOfWeek.TUESDAY,
            "wednesday|budhvar|budhwar|budhvaar" to DayOfWeek.WEDNESDAY,
            "thursday|guruvar|guruwar|brihaspativar|veervar" to DayOfWeek.THURSDAY,
            "friday|shukravar|shukrawar|shukravaar" to DayOfWeek.FRIDAY,
            "saturday|shanivar|shaniwar|shanivaar" to DayOfWeek.SATURDAY,
            "sunday|ravivar|raviwar|itwar|itvaar|itwaar" to DayOfWeek.SUNDAY,
        ).map { (re, d) -> Regex("""\b($re)\b""") to d }

        private val CLOCK_HM = Regex("""\b(\d{1,2})[:.](\d{2})\s*(am|pm)?\b""")
        private val CLOCK_AMPM = Regex("""\b(\d{1,2})\s*(am|pm)\b""")
        private val QUARTER_PAST = Regex("""\bquarter past (\d{1,2})\b""")
        private val HALF_PAST = Regex("""\bhalf past (\d{1,2})\b""")
        private val QUARTER_TO = Regex("""\bquarter to (\d{1,2})\b""")
        private val SAADHE = Regex("""\b(saadhe|sadhe|saade)\s+(\d{1,2})\b""")
        private val SAWA = Regex("""\b(sawa|sava|savaa)\s+(\d{1,2})\b""")
        private val PAUNE = Regex("""\b(paune|pone|paone)\s+(\d{1,2})\b""")
        private val DEDH = Regex("""\bdedh\b""")
        private val DHAI = Regex("""\b(dhai|dhaai|adhai|dhaayi)\b""")
        private val BAJE = Regex("""\b(\d{1,2})\s*(baje|bajke|bajey|bje|oclock)\b""")
        private val AT_N = Regex("""\b(at|by|around|about|approx|approximately|lagbhag|kareeb|since|till|until)\s+(\d{1,2})\b(?!\s*(minutes?|mins?|hours?|hrs?|days?|din|ghant\w*|[/-]\d))""")
        private val N_DAYPART = Regex("""\b(\d{1,2})\s+(in the\s+|at\s+|ko\s+)?(morning|evening|night|afternoon|subah|shaam|sham|raat|dopahar)\b""")
        private val DAYPART_N = Regex("""\b(subah|shaam|sham|raat|dopahar|morning|evening|night|afternoon)\s+(ke\s+|ko\s+|at\s+)?(\d{1,2})\b""")
        private val ONLY_N = Regex("""^(\d{1,2})$""")
    }

    /** Resolves [phrase] relative to [now]. Never returns a null instant (T10 falls back to [now]). */
    fun resolve(phrase: String?, now: Instant): ResolvedTime {
        val nowZ = now.atZone(zone)
        if (phrase.isNullOrBlank()) return defaulted(now)
        val s = preprocess(phrase)
        if (s.isBlank()) return defaulted(now)
        val modifier = MODIFIER.containsMatchIn(s)
        val core = MODIFIER.replace(s, " ").replace(Regex("""\s+"""), " ").trim()

        // T1
        if (NOW_PHRASE.matches(core)) return ResolvedTime(now, modifier, TimeGranularity.MINUTE, "T1")
        // T3 (before T2, see class doc)
        if (T3_HALF.containsMatchIn(s)) return ResolvedTime(now.minus(Duration.ofMinutes(30)), true, TimeGranularity.MINUTE, "T3")
        if (T3_WHILE.containsMatchIn(s)) return ResolvedTime(now.minus(Duration.ofMinutes(15)), true, TimeGranularity.MINUTE, "T3")
        // T2
        T2.find(s)?.let { m ->
            val amountWord = m.groupValues[1]
            var amount = when (amountWord) {
                "a", "an" -> 1.0
                "couple of", "a couple of" -> 2.0
                "few", "a few" -> 3.0
                "dedh" -> 1.5
                "dhai", "dhaai", "adhai" -> 2.5
                else -> amountWord.toDoubleOrNull() ?: 1.0
            }
            if (m.groupValues[3].isNotBlank()) amount += 0.5
            val unit = m.groupValues[2]
            val isHour = unit.startsWith("h") || unit.startsWith("ghant")
            val minutes = if (isHour) amount * 60 else amount
            val fuzzyAmount = amountWord in setOf("couple of", "a couple of", "few", "a few") || amount != Math.floor(amount)
            return ResolvedTime(
                now.minus(Duration.ofSeconds((minutes * 60).toLong())),
                approximate = isHour || modifier || fuzzyAmount,
                granularity = if (isHour) TimeGranularity.HOUR else TimeGranularity.MINUTE,
                matchedRule = "T2",
            )
        }

        val rules = mutableListOf<String>()
        // ---- T6 day
        val today = nowZ.toLocalDate()
        var explicitDate: LocalDate? = null
        var dayOffset: Int? = null
        var lastNight = false
        var weekday = false
        when {
            Regex("""\b(last night|kal raat|kal ki raat|yesterday night|kal rat)\b""").containsMatchIn(s) -> { dayOffset = -1; lastNight = true }
            Regex("""\b(day before yesterday|parson|parso|parsoon)\b""").containsMatchIn(s) -> dayOffset = -2
            Regex("""\b(yesterday|kal)\b""").containsMatchIn(s) -> dayOffset = -1
            Regex("""\b(last week|pichle hafte|pichhle hafte)\b""").containsMatchIn(s) -> dayOffset = -7
            Regex("""\b(today|aaj|tonight|this morning|this afternoon|this evening)\b""").containsMatchIn(s) -> dayOffset = 0
        }
        DAYS_AGO.find(s)?.let { m ->
            val n = when (m.groupValues[1]) {
                "a", "one" -> 1; "couple of", "a couple of" -> 2; "few", "a few" -> 3
                else -> m.groupValues[1].toIntOrNull() ?: 1
            }
            dayOffset = -n
        }
        if (dayOffset == null) {
            for ((re, dow) in WEEKDAYS) if (re.containsMatchIn(s)) {
                var diff = (today.dayOfWeek.value - dow.value + 7) % 7
                if (diff == 0) diff = 7
                dayOffset = -diff; weekday = true; break
            }
        }
        explicitDate = parseDate(core, today)
        if (explicitDate != null || dayOffset != null) rules += "T6"

        // ---- T5 day part
        val dayPart: DayPart? = when {
            Regex("""\b(late night|der raat|late at night)\b""").containsMatchIn(s) -> DayPart.LATE_NIGHT
            Regex("""\b(midnight|aadhi raat|adhi raat)\b""").containsMatchIn(s) -> DayPart.MIDNIGHT
            Regex("""\b(early morning|subah subah|tadke|savere savere)\b""").containsMatchIn(s) -> DayPart.EARLY_MORNING
            Regex("""\b(morning|subah|savere|savera|am in the morning)\b""").containsMatchIn(s) -> DayPart.MORNING
            Regex("""\b(afternoon)\b""").containsMatchIn(s) -> DayPart.AFTERNOON
            Regex("""\b(noon|dopahar|dopehar|dupahar|midday)\b""").containsMatchIn(s) -> DayPart.NOON
            Regex("""\b(evening|shaam|sham)\b""").containsMatchIn(s) -> DayPart.EVENING
            lastNight || Regex("""\b(night|raat|rat|tonight)\b""").containsMatchIn(s) -> DayPart.NIGHT
            else -> null
        }
        // ---- T4 clock
        val clock = parseClock(s, core)
        if (clock == null && dayPart == null && explicitDate == null && dayOffset == null) return defaulted(now)

        var date: LocalDate = explicitDate ?: today.plusDays((dayOffset ?: 0).toLong())
        val hasExplicitDay = explicitDate != null || dayOffset != null
        var approx = modifier
        val granularity: TimeGranularity
        val time: LocalTime

        if (clock != null) {
            rules += "T4"
            var h = clock.hour
            var ambiguous = false
            when {
                clock.ampm == "pm" -> if (h < 12) h += 12
                clock.ampm == "am" -> if (h == 12) h = 0
                dayPart != null && h <= 12 -> h = applyDayPart(h, dayPart)
                h in 1..12 -> ambiguous = true
            }
            granularity = if (clock.minutesGiven && clock.minute != 0) TimeGranularity.MINUTE else TimeGranularity.HOUR
            if (lastNight && h < 6) date = today
            if (ambiguous) {
                rules += "T8"
                val dates = if (hasExplicitDay) listOf(date) else listOf(date, date.minusDays(1))
                val limit = nowZ.plus(FUTURE_TOLERANCE)
                val cands = dates.flatMap { d ->
                    listOf(h % 12, h % 12 + 12).map { hh -> ZonedDateTime.of(d, LocalTime.of(hh, clock.minute), zone) }
                }
                val past = cands.filter { !it.isAfter(limit) }
                val pick = past.maxOrNull() ?: cands.min()
                if (modifier) rules += "T7"
                return ResolvedTime(pick.toInstant(), approx, granularity, rules.joinToString("+"))
            }
            time = LocalTime.of(h.coerceIn(0, 23), clock.minute)
        } else if (dayPart != null) {
            rules += "T5"
            approx = true
            granularity = TimeGranularity.DAYPART
            time = dayPart.time
            if (lastNight && time.hour < 6) date = today
        } else {
            // Day only.
            approx = true
            if (!weekday && explicitDate == null && dayOffset == 0) {
                return ResolvedTime(now, true, TimeGranularity.DAY, rules.joinToString("+"))
            }
            granularity = TimeGranularity.DAY
            time = LocalTime.NOON
        }
        if (modifier) rules += "T7"
        var dt = ZonedDateTime.of(date, time, zone)
        // T9: future by > 5 min and no explicit date → previous day.
        if (!hasExplicitDay && dt.isAfter(nowZ.plus(FUTURE_TOLERANCE))) {
            dt = dt.minusDays(1); rules += "T9"
        }
        return ResolvedTime(dt.toInstant(), approx, granularity, rules.joinToString("+"))
    }

    private fun defaulted(now: Instant) = ResolvedTime(now, true, TimeGranularity.MINUTE, "T10", defaulted = true)

    private fun applyDayPart(h: Int, dp: DayPart): Int = when (dp) {
        DayPart.EARLY_MORNING, DayPart.MORNING -> if (h == 12) 0 else h
        DayPart.NOON, DayPart.AFTERNOON -> if (h < 12 && h != 0) (if (h <= 6 || dp == DayPart.AFTERNOON) h + 12 else h) else h
        DayPart.EVENING -> if (h < 12) h + 12 else h
        DayPart.NIGHT -> when (h) { 12 -> 0; in 1..4 -> h; else -> if (h < 12) h + 12 else h }
        DayPart.MIDNIGHT, DayPart.LATE_NIGHT -> if (h == 12) 0 else h
    }

    fun preprocess(phrase: String): String {
        var s = phrase.lowercase().trim()
        s = s.replace(Regex("""\b([ap])\.\s?m\b\.?"""), "$1m")
        s = s.replace("o'clock", " oclock").replace("o clock", " oclock").replace("o’clock", " oclock")
        s = s.replace(Regex("""[,;!?"()\[\]{}]"""), " ")
        // sentence dots (not between digits)
        s = s.replace(Regex("""(?<!\d)\.|\.(?!\d)"""), " ")
        s = s.replace(Regex("""(\d)(am|pm)\b"""), "$1 $2")
        s = NumberWords.replaceEnglishWords(s)
        // Hinglish numerals in numeric context.
        s = s.replace(Regex("""\b($HALF_WORDS)\s+($HI_NUM)\b""")) { m ->
            m.groupValues[1] + " " + (NumberWords.HI[m.groupValues[2]] ?: m.groupValues[2])
        }
        s = s.replace(Regex("""\b($HI_NUM)\b$UNIT_LOOKAHEAD""")) { m -> (NumberWords.HI[m.groupValues[1]] ?: m.value).toString() }
        s = s.replace(Regex("""\b(subah|shaam|sham|raat|dopahar)\s+(ke\s+)?($HI_NUM)\b""")) { m ->
            m.groupValues[1] + " " + m.groupValues[2] + (NumberWords.HI[m.groupValues[3]] ?: m.groupValues[3])
        }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    private fun parseClock(s: String, core: String): Clock? {
        fun ok(h: Int, m: Int) = h in 0..23 && m in 0..59
        CLOCK_HM.findAll(s).firstOrNull { m ->
            // skip dd.mm.yyyy dates
            val after = s.substring(m.range.last + 1)
            !after.startsWith(".") && !after.startsWith("/")
        }?.let { m ->
            val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
            if (ok(h, mi)) return Clock(h, mi, m.groupValues[3].ifEmpty { null }, true)
        }
        CLOCK_AMPM.find(s)?.let { m -> val h = m.groupValues[1].toInt(); if (ok(h, 0)) return Clock(h, 0, m.groupValues[2], false) }
        QUARTER_PAST.find(s)?.let { return Clock(it.groupValues[1].toInt(), 15, null, true) }
        HALF_PAST.find(s)?.let { return Clock(it.groupValues[1].toInt(), 30, null, true) }
        QUARTER_TO.find(s)?.let { val h = it.groupValues[1].toInt(); return Clock(if (h == 1) 12 else h - 1, 45, null, true) }
        SAADHE.find(s)?.let { return Clock(it.groupValues[2].toInt(), 30, null, true) }
        SAWA.find(s)?.let { return Clock(it.groupValues[2].toInt(), 15, null, true) }
        PAUNE.find(s)?.let { val h = it.groupValues[2].toInt(); return Clock(if (h == 1) 12 else h - 1, 45, null, true) }
        if (DEDH.containsMatchIn(s)) return Clock(1, 30, null, true)
        if (DHAI.containsMatchIn(s)) return Clock(2, 30, null, true)
        BAJE.find(s)?.let { val h = it.groupValues[1].toInt(); if (ok(h, 0)) return Clock(h, 0, null, false) }
        N_DAYPART.find(s)?.let { val h = it.groupValues[1].toInt(); if (ok(h, 0)) return Clock(h, 0, null, false) }
        DAYPART_N.find(s)?.let { val h = it.groupValues[3].toInt(); if (ok(h, 0)) return Clock(h, 0, null, false) }
        AT_N.find(s)?.let { val h = it.groupValues[2].toInt(); if (ok(h, 0)) return Clock(h, 0, null, false) }
        ONLY_N.find(core)?.let { val h = it.groupValues[1].toInt(); if (ok(h, 0)) return Clock(h, 0, null, false) }
        return null
    }

    private fun parseDate(s: String, today: LocalDate): LocalDate? {
        fun build(y: Int?, m: Int, d: Int): LocalDate? {
            val year = when {
                y == null -> today.year
                y < 100 -> 2000 + y
                else -> y
            }
            val date = runCatching { LocalDate.of(year, m, d) }.getOrNull() ?: return null
            return if (y == null && date.isAfter(today)) date.minusYears(1) else date
        }
        fun month(name: String) = MONTHS.indexOf(name.take(3)) + 1
        DATE_ISO.find(s)?.let { m -> return build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        DATE_DM.find(s)?.let { m -> return build(m.groupValues[6].toIntOrNull(), month(m.groupValues[4]), m.groupValues[1].toInt()) }
        DATE_MD.find(s)?.let { m -> return build(m.groupValues[5].toIntOrNull(), month(m.groupValues[1]), m.groupValues[2].toInt()) }
        DATE_NUM_DOT.find(s)?.let { m -> return build(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
        DATE_NUM.find(s)?.let { m -> return build(m.groupValues[3].toIntOrNull(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
        return null
    }
}
