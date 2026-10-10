package com.megathon.incidentvoice.core

import com.megathon.incidentvoice.core.model.TimeGranularity
import com.megathon.incidentvoice.core.normalize.TimeResolver
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PRD §15.1. Reference now = Sat 2026-10-10 10:07:12 IST. */
class TimeResolverTest {
    private val r = TimeResolver(T.ZONE)
    private val now = T.NOW

    /** (phrase, expected local date-time "yyyy-MM-ddTHH:mm[:ss]", expected approx or null = don't care) */
    private val cases: List<Triple<String, String, Boolean?>> = listOf(
        // T1
        Triple("just now", "2026-10-10T10:07:12", false),
        Triple("abhi", "2026-10-10T10:07:12", false),
        Triple("right now", "2026-10-10T10:07:12", false),
        // T2
        Triple("10 minutes ago", "2026-10-10T09:57:12", false),
        Triple("5 min pehle", "2026-10-10T10:02:12", false),
        Triple("ten minutes back", "2026-10-10T09:57:12", false),
        Triple("an hour ago", "2026-10-10T09:07:12", true),
        Triple("2 hours ago", "2026-10-10T08:07:12", true),
        Triple("do ghante pehle", "2026-10-10T08:07:12", true),
        Triple("teen ghante pehle", "2026-10-10T07:07:12", true),
        Triple("dedh ghanta pehle", "2026-10-10T08:37:12", true),
        Triple("an hour and a half ago", "2026-10-10T08:37:12", true),
        Triple("a few minutes ago", "2026-10-10T09:52:12", true),
        // T3
        Triple("half an hour ago", "2026-10-10T09:37:12", true),
        Triple("about half an hour ago", "2026-10-10T09:37:12", true),
        Triple("aadha ghanta pehle", "2026-10-10T09:37:12", true),
        Triple("thodi der pehle", "2026-10-10T09:52:12", true),
        Triple("a while ago", "2026-10-10T09:52:12", true),
        // T4 + T7
        Triple("around 10 AM", "2026-10-10T10:00", true),
        Triple("at 10 am", "2026-10-10T10:00", false),
        Triple("10 a.m.", "2026-10-10T10:00", false),
        Triple("ten am", "2026-10-10T10:00", false),
        Triple("9:30 am", "2026-10-10T09:30", false),
        Triple("9.15 am", "2026-10-10T09:15", false),
        Triple("at 10:05", "2026-10-10T10:05", false),
        Triple("quarter past 9", "2026-10-10T09:15", false),
        Triple("half past 8", "2026-10-10T08:30", false),
        Triple("quarter to 10", "2026-10-10T09:45", false),
        Triple("lagbhag 9 baje", "2026-10-10T09:00", true),
        Triple("aaj subah 8 baje", "2026-10-10T08:00", false),
        // Hinglish numerals
        Triple("saadhe nau baje", "2026-10-10T09:30", false),
        Triple("sawa nau", "2026-10-10T09:15", false),
        Triple("paune das", "2026-10-10T09:45", false),
        Triple("dedh baje", "2026-10-10T01:30", false),
        Triple("dhai baje", "2026-10-10T02:30", false),
        Triple("kareeb das baje", "2026-10-10T10:00", true),
        // T8 ambiguity resolved backwards
        Triple("around 10", "2026-10-10T10:00", true),
        Triple("at 9", "2026-10-10T09:00", false),
        Triple("at 2", "2026-10-10T02:00", false),
        Triple("at 11", "2026-10-09T23:00", false),
        Triple("at 10:10", "2026-10-10T10:10", false),
        // T9 future → previous day
        Triple("at 3 pm", "2026-10-09T15:00", false),
        Triple("14:30", "2026-10-09T14:30", false),
        Triple("12 noon", "2026-10-09T12:00", null),
        Triple("raat 10 baje", "2026-10-09T22:00", false),
        Triple("10 baje raat ko", "2026-10-09T22:00", false),
        // T5 day parts
        Triple("this morning", "2026-10-10T09:00", true),
        Triple("subah", "2026-10-10T09:00", true),
        Triple("in the evening", "2026-10-09T18:30", true),
        Triple("noon", "2026-10-09T13:00", true),
        Triple("midnight", "2026-10-10T00:00", true),
        // T6 days
        Triple("yesterday evening", "2026-10-09T18:30", true),
        Triple("kal shaam 7 baje", "2026-10-09T19:00", false),
        Triple("last night at 11", "2026-10-09T23:00", false),
        Triple("kal raat", "2026-10-09T22:00", true),
        Triple("last night at 2", "2026-10-10T02:00", false),
        Triple("yesterday", "2026-10-09T12:00", true),
        Triple("day before yesterday", "2026-10-08T12:00", true),
        Triple("parson subah", "2026-10-08T09:00", true),
        Triple("on Monday", "2026-10-05T12:00", true),
        Triple("Saturday", "2026-10-03T12:00", true),
        Triple("last Friday at 4 pm", "2026-10-09T16:00", false),
        Triple("5th October at 4 pm", "2026-10-05T16:00", false),
        Triple("October 5", "2026-10-05T12:00", true),
        Triple("10-10-2026 9:15 am", "2026-10-10T09:15", false),
        Triple("05/10", "2026-10-05T12:00", true),
        Triple("2 days ago", "2026-10-08T12:00", true),
        Triple("do din pehle", "2026-10-08T12:00", true),
        Triple("25 December", "2025-12-25T12:00", true),
    )

    @TestFactory
    fun table(): List<DynamicTest> = cases.map { (phrase, expected, approx) ->
        DynamicTest.dynamicTest("'$phrase' -> $expected") {
            val res = r.resolve(phrase, now)
            val got = LocalDateTime.ofInstant(res.instant!!, T.ZONE)
            assertEquals(LocalDateTime.parse(expected), got, "phrase='$phrase' rule=${res.matchedRule}")
            if (approx != null) assertEquals(approx, res.approximate, "approx for '$phrase' rule=${res.matchedRule}")
            assertTrue(!res.defaulted, "should not default: '$phrase'")
        }
    }

    @Test fun t10_noPhraseDefaultsToReportTime() {
        for (p in listOf(null, "", "   ", "sometime", "I don't know")) {
            val res = r.resolve(p, now)
            assertEquals(now, res.instant, "phrase=$p")
            assertTrue(res.approximate)
            assertTrue(res.defaulted)
            assertEquals("T10", res.matchedRule)
        }
    }

    @Test fun granularity() {
        assertEquals(TimeGranularity.HOUR, r.resolve("at 10 am", now).granularity)
        assertEquals(TimeGranularity.MINUTE, r.resolve("9:30 am", now).granularity)
        assertEquals(TimeGranularity.DAYPART, r.resolve("this morning", now).granularity)
        assertEquals(TimeGranularity.DAY, r.resolve("yesterday", now).granularity)
    }

    @Test fun neverInFutureWithoutExplicitDate() {
        for (p in listOf("at 11", "evening", "11 pm", "noon", "at 10:30", "afternoon", "raat")) {
            val res = r.resolve(p, now)
            assertTrue(!res.instant!!.isAfter(now.plusSeconds(300)), "'$p' resolved to future ${res.instant}")
        }
    }

    @Test fun injectableZone() {
        val utc = TimeResolver(java.time.ZoneOffset.UTC)
        val res = utc.resolve("at 3 am", T.NOW) // now = 04:37:12 UTC
        assertEquals(LocalDateTime.parse("2026-10-10T03:00"), LocalDateTime.ofInstant(res.instant!!, java.time.ZoneOffset.UTC))
    }
}
