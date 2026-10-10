package com.megathon.incidentvoice.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

enum class Granularity { MINUTE, HOUR, DAYPART, DAY }

data class ResolvedTime(
    val instant: Instant,
    val approximate: Boolean,
    val granularity: Granularity,
    val matchedRule: String,
    /** True only for rule T10: nothing spoken, defaulted to report time (rev 1.1 R1-05). */
    val defaulted: Boolean = false,
)

/** PRD §15.1. Deterministic; no model involved. */
class TimeResolver(private val zone: ZoneId = ZoneId.of("Asia/Kolkata")) {

    private val numWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "paanch" to 5, "panch" to 5,
        "chhe" to 6, "che" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "gyarah" to 11, "barah" to 12,
        "pandrah" to 15, "bees" to 20, "tees" to 30, "chalis" to 40,
    )
    private val months = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
    )
    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )
    private val numAlt = numWords.keys.sortedByDescending { it.length }.joinToString("|")

    fun resolve(phrase: String?, nowInstant: Instant): ResolvedTime {
        val now = nowInstant.atZone(zone)
        val s = phrase?.lowercase()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (s.isEmpty()) return default(nowInstant)
        val approxMod = Regex("\\b(around|about|approximately|approx|roughly|lagbhag|kareeb|takriban|almost|nearly)\\b").containsMatchIn(s)

        // T1
        if (Regex("\\b(just now|right now|abhi abhi|abhi)\\b").containsMatchIn(s))
            return ResolvedTime(nowInstant, approxMod, Granularity.MINUTE, "T1")
        // T3 (before T2: "half an hour ago" contains "an hour ago")
        if (Regex("half an hour|half hour|aadha ghanta|adha ghanta").containsMatchIn(s))
            return ResolvedTime(nowInstant.minus(30, ChronoUnit.MINUTES), true, Granularity.MINUTE, "T3")
        if (Regex("thodi der (pehle|back)|few minutes (ago|back)").containsMatchIn(s))
            return ResolvedTime(nowInstant.minus(15, ChronoUnit.MINUTES), true, Granularity.MINUTE, "T3")
        if (Regex("a while (ago|back)|kuch der pehle").containsMatchIn(s))
            return ResolvedTime(nowInstant.minus(30, ChronoUnit.MINUTES), true, Granularity.MINUTE, "T3")
        // T2
        Regex("\\b(\\d+|$numAlt)\\s*(minutes?|mins?|hours?|hrs?|ghanta|ghante|ghanton)\\s*(ago|back|pehle)\\b").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: numWords[m.groupValues[1]] ?: 1
            val unit = m.groupValues[2]
            val isHour = unit.startsWith("h") || unit.startsWith("g")
            val delta = if (isHour) ChronoUnit.HOURS else ChronoUnit.MINUTES
            return ResolvedTime(nowInstant.minus(n.toLong(), delta), approxMod || isHour,
                if (isHour) Granularity.HOUR else Granularity.MINUTE, "T2")
        }

        val date = parseDate(s, now)               // explicit date or relative day, else null
        val lastNight = Regex("\\blast night\\b|\\bkal raat\\b").containsMatchIn(s)
        val clock = parseClock(s)
        val part = parseDayPart(s)

        if (clock == null && part == null && date == null) return default(nowInstant)

        var approx = approxMod
        val baseDate: LocalDate
        var explicitDate = date != null
        baseDate = when {
            lastNight -> { explicitDate = true; if (now.hour < 6 && clock == null) now.toLocalDate() else now.toLocalDate().minusDays(1) }
            date != null -> date
            else -> now.toLocalDate()
        }

        val time: LocalTime
        val gran: Granularity
        val rule: String
        if (clock != null) {
            val (h0, min, ampm) = clock
            val hour24 = when {
                ampm == "am" -> if (h0 == 12) 0 else h0
                ampm == "pm" -> if (h0 == 12) 12 else h0 + 12
                h0 > 12 -> h0
                part != null -> applyPart(h0, part)
                else -> -1
            }
            if (hour24 >= 0) {
                time = LocalTime.of(hour24 % 24, min)
            } else {
                // T8: ambiguous 12-hour clock
                time = if (explicitDate && baseDate != now.toLocalDate()) {
                    LocalTime.of(if (h0 in 1..6) h0 + 12 else h0, min)
                } else {
                    val cands = listOf(h0 % 12, h0 % 12 + 12).map { LocalTime.of(it, min) }
                    val past = cands.filter { !ZonedDateTime.of(baseDate, it, zone).toInstant().isAfter(nowInstant.plus(5, ChronoUnit.MINUTES)) }
                    // most recent past occurrence today; else the later one (will be pushed back a day by T9)
                    past.maxOrNull() ?: cands.max()
                }
            }
            gran = if (min != 0) Granularity.MINUTE else Granularity.HOUR
            rule = "T4"
            if (part != null && ampm == null) approx = true
        } else if (part != null) {
            time = part.second; gran = Granularity.DAYPART; rule = "T5"; approx = true
        } else {
            // date only
            time = if (baseDate == now.toLocalDate()) now.toLocalTime().truncatedTo(ChronoUnit.MINUTES) else LocalTime.NOON
            gran = Granularity.DAY; rule = "T6"; approx = true
        }

        var result = ZonedDateTime.of(baseDate, time, zone)
        // T9: future by > 5 min without an explicit date → previous day
        if (!explicitDate && result.toInstant().isAfter(nowInstant.plus(5, ChronoUnit.MINUTES))) {
            result = result.minusDays(1)
        }
        // an explicit weekday/"kal" date can never be in the future either
        if (explicitDate && result.toInstant().isAfter(nowInstant.plus(5, ChronoUnit.MINUTES)) && date != null) {
            result = result.minusYears(if (date.year == now.year && Regex("\\d").containsMatchIn(s)) 1 else 0)
        }
        return ResolvedTime(result.toInstant(), approx, gran, rule)
    }

    private fun default(now: Instant) = ResolvedTime(now, true, Granularity.MINUTE, "T10", defaulted = true)

    private fun applyPart(h: Int, part: Pair<String, LocalTime>): Int = when (part.first) {
        "morning" -> if (h == 12) 0 else h
        "noon" -> if (h in 1..5) h + 12 else h
        "afternoon", "evening" -> if (h < 12) h + 12 else h
        "night" -> when { h == 12 -> 0; h in 6..11 -> h + 12; else -> h }
        "midnight" -> if (h == 12) 0 else h
        else -> h
    }

    private fun parseDayPart(s: String): Pair<String, LocalTime>? = when {
        Regex("\\b(late night)\\b").containsMatchIn(s) -> "night" to LocalTime.of(1, 0)
        Regex("\\bmidnight\\b").containsMatchIn(s) -> "midnight" to LocalTime.MIDNIGHT
        Regex("\\b(morning|subah|subha)\\b").containsMatchIn(s) -> "morning" to LocalTime.of(9, 0)
        Regex("\\b(noon|dopahar|dopaher)\\b").containsMatchIn(s) -> "noon" to LocalTime.of(13, 0)
        Regex("\\bafternoon\\b").containsMatchIn(s) -> "afternoon" to LocalTime.of(15, 0)
        Regex("\\b(evening|shaam|sham)\\b").containsMatchIn(s) -> "evening" to LocalTime.of(18, 30)
        Regex("\\b(night|raat)\\b").containsMatchIn(s) -> "night" to LocalTime.of(22, 0)
        else -> null
    }

    /** (hour, minute, am/pm or null) */
    private fun parseClock(s: String): Triple<Int, Int, String?>? {
        // Hinglish fractions
        Regex("\\bsaadhe\\s+($numAlt|\\d+)\\b").find(s)?.let { return Triple(numOf(it.groupValues[1]), 30, ampmAfter(s, it.range.last)) }
        Regex("\\bsawa\\s+($numAlt|\\d+)\\b").find(s)?.let { return Triple(numOf(it.groupValues[1]), 15, ampmAfter(s, it.range.last)) }
        Regex("\\bpaune\\s+($numAlt|\\d+)\\b").find(s)?.let {
            val h = numOf(it.groupValues[1]); return Triple(if (h == 1) 12 else h - 1, 45, ampmAfter(s, it.range.last))
        }
        if (Regex("\\bdedh\\b").containsMatchIn(s)) return Triple(1, 30, null)
        if (Regex("\\bdhai\\b").containsMatchIn(s)) return Triple(2, 30, null)
        Regex("quarter past\\s+(\\d{1,2}|$numAlt)").find(s)?.let { return Triple(numOf(it.groupValues[1]), 15, ampmAfter(s, it.range.last)) }
        Regex("half past\\s+(\\d{1,2}|$numAlt)").find(s)?.let { return Triple(numOf(it.groupValues[1]), 30, ampmAfter(s, it.range.last)) }
        Regex("quarter to\\s+(\\d{1,2}|$numAlt)").find(s)?.let {
            val h = numOf(it.groupValues[1]); return Triple(if (h == 1) 12 else h - 1, 45, ampmAfter(s, it.range.last))
        }
        // digits with am/pm or colon
        Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])").find(s)?.let {
            val ap = if (it.groupValues[3].startsWith("a")) "am" else "pm"
            return Triple(it.groupValues[1].toInt(), it.groupValues[2].ifEmpty { "0" }.toInt(), ap)
        }
        Regex("\\b(\\d{1,2})[:.](\\d{2})\\b").find(s)?.let {
            val h = it.groupValues[1].toInt(); val m = it.groupValues[2].toInt()
            if (h in 0..23 && m in 0..59 && !Regex("[/-]").containsMatchIn(s.substring(maxOf(0, it.range.first - 1), minOf(s.length, it.range.last + 2)))) return Triple(h, m, null)
        }
        // "2 in the afternoon", "8 baje raat"
        Regex("\\b(\\d{1,2})\\s*(?:in the|ki|ke|baje)?\\s*(?:morning|afternoon|evening|night|noon|subah|shaam|raat|dopahar)\\b").find(s)?.let {
            return Triple(it.groupValues[1].toInt(), 0, null)
        }
        // bare hour: only with a cue (at/around/by/… or baje/o'clock)
        Regex("\\b(?:at|around|about|by|near|lagbhag|kareeb|till)\\s+(\\d{1,2}|$numAlt)\\b(?!\\s*(?:st|nd|rd|th|minutes?|mins?|hours?|/|-))").find(s)?.let {
            val h = numOf(it.groupValues[1]); if (h in 1..12 || h in 13..23) return Triple(h, 0, null)
        }
        Regex("\\b(\\d{1,2}|$numAlt)\\s*(?:baje|bje|o'clock|oclock)\\b").find(s)?.let {
            return Triple(numOf(it.groupValues[1]), 0, null)
        }
        return null
    }

    private fun ampmAfter(s: String, from: Int): String? =
        Regex("^\\s*(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])").find(s.substring(minOf(s.length, from + 1)))?.let { if (it.groupValues[1].startsWith("a")) "am" else "pm" }

    private fun numOf(t: String): Int = t.toIntOrNull() ?: numWords[t] ?: 0

    private fun parseDate(s: String, now: ZonedDateTime): LocalDate? {
        val today = now.toLocalDate()
        if (Regex("day before yesterday|\\bparson\\b").containsMatchIn(s)) return today.minusDays(2)
        if (Regex("\\byesterday\\b|\\bkal\\b").containsMatchIn(s)) return today.minusDays(1)
        if (Regex("\\btoday\\b|\\baaj\\b").containsMatchIn(s)) return today
        // weekdays
        for ((name, dow) in weekdays) if (Regex("\\b$name\\b").containsMatchIn(s)) {
            val back = (today.dayOfWeek.value - dow.value + 7) % 7
            return today.minusDays(back.toLong())
        }
        // 5th October / October 5
        val monAlt = months.joinToString("|") { it.take(3) + "[a-z]*" }
        Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?($monAlt)\\b(?:\\s+(\\d{4}))?").find(s)?.let {
            return mkDate(it.groupValues[1].toInt(), monthIdx(it.groupValues[2]), it.groupValues[3].toIntOrNull(), today)
        }
        Regex("\\b($monAlt)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(\\d{4}))?").find(s)?.let {
            return mkDate(it.groupValues[2].toInt(), monthIdx(it.groupValues[1]), it.groupValues[3].toIntOrNull(), today)
        }
        // 10-10-2026, 05/10
        Regex("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b").find(s)?.let {
            val y = it.groupValues[3].toIntOrNull()?.let { yy -> if (yy < 100) 2000 + yy else yy }
            return mkDate(it.groupValues[1].toInt(), it.groupValues[2].toInt(), y, today)
        }
        return null
    }

    private fun monthIdx(t: String) = months.indexOfFirst { it.startsWith(t.take(3)) } + 1

    private fun mkDate(d: Int, m: Int, y: Int?, today: LocalDate): LocalDate? = try {
        var out = LocalDate.of(y ?: today.year, m, d)
        if (y == null && out.isAfter(today)) out = out.minusYears(1)
        out
    } catch (e: Exception) { null }
}
