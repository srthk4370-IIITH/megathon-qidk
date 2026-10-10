package com.megathon.incidentvoice.core

import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeResolverTest {
    // now = Sat 2026-10-10 10:07 IST
    private fun r(p: String?) = Fx.times.resolve(p, Fx.now)
    private fun at(p: String?): ZonedDateTime = r(p).instant.atZone(Fx.zone)

    private fun assertAt(p: String, y: Int, mo: Int, d: Int, h: Int, mi: Int) {
        val z = at(p)
        assertEquals(listOf(y, mo, d, h, mi), listOf(z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute), p)
    }

    @Test fun t1_now() { assertEquals(Fx.now, r("just now").instant); assertEquals(Fx.now, r("abhi abhi").instant); assertFalse(r("right now").approximate) }
    @Test fun t2_minutesAgo() { assertAt("5 minutes ago", 2026, 10, 10, 10, 2); assertAt("ten minutes back", 2026, 10, 10, 9, 57) }
    @Test fun t2_hoursAgo() { assertAt("about an hour ago", 2026, 10, 10, 9, 7); assertTrue(r("two hours ago").approximate); assertAt("2 hours ago", 2026, 10, 10, 8, 7) }
    @Test fun t2_hinglish() { assertAt("ek ghanta pehle", 2026, 10, 10, 9, 7); assertAt("teen ghante pehle", 2026, 10, 10, 7, 7) }
    @Test fun t3_halfHour() { assertAt("half an hour ago", 2026, 10, 10, 9, 37); assertAt("aadha ghanta pehle", 2026, 10, 10, 9, 37); assertAt("about half an hour ago", 2026, 10, 10, 9, 37) }
    @Test fun t3_whileAgo() { assertAt("thodi der pehle", 2026, 10, 10, 9, 52); assertAt("a while ago", 2026, 10, 10, 9, 37) }
    @Test fun t4_amPm() { assertAt("around 10 AM", 2026, 10, 10, 10, 0); assertAt("at 9:30 am", 2026, 10, 10, 9, 30); assertAt("8 pm", 2026, 10, 9, 20, 0) }
    @Test fun t4_dotted() { assertAt("10.05 am", 2026, 10, 10, 10, 5); assertAt("9.30", 2026, 10, 10, 9, 30) }
    @Test fun t4_fractions() { assertAt("half past 9", 2026, 10, 10, 9, 30); assertAt("quarter past 9", 2026, 10, 10, 9, 15); assertAt("saadhe nau baje", 2026, 10, 10, 9, 30); assertAt("sawa nau baje", 2026, 10, 10, 9, 15); assertAt("paune das baje", 2026, 10, 10, 9, 45) }
    @Test fun t4_hinglishBaje() { assertAt("9 baje", 2026, 10, 10, 9, 0); assertAt("das baje", 2026, 10, 10, 10, 0) }
    @Test fun t5_dayparts() { assertAt("this morning", 2026, 10, 10, 9, 0); assertTrue(r("this morning").approximate); assertAt("subah", 2026, 10, 10, 9, 0); assertAt("dopahar", 2026, 10, 9, 13, 0) }
    @Test fun t5_evening_night() { assertAt("shaam ko", 2026, 10, 9, 18, 30); assertAt("last night", 2026, 10, 9, 22, 0); assertAt("midnight", 2026, 10, 10, 0, 0) }
    @Test fun t6_yesterday() { assertAt("yesterday at 3 pm", 2026, 10, 9, 15, 0); assertAt("kal subah 8 baje", 2026, 10, 9, 8, 0); assertAt("day before yesterday evening", 2026, 10, 8, 18, 30) }
    @Test fun t6_today() { assertAt("today at 9 am", 2026, 10, 10, 9, 0); assertAt("aaj subah", 2026, 10, 10, 9, 0) }
    @Test fun t6_lastNightClock() { assertAt("last night at 11", 2026, 10, 9, 23, 0) }
    @Test fun t6_weekday() { val z = at("on Thursday at 2 pm"); assertEquals(8, z.dayOfMonth); assertEquals(14, z.hour) }
    @Test fun t6_dates() { assertAt("5th October at 2 pm", 2026, 10, 5, 14, 0); assertAt("on 05/10 at 2 pm", 2026, 10, 5, 14, 0); assertAt("10-10-2026 9 am", 2026, 10, 10, 9, 0); assertAt("october 3 at 4 pm", 2026, 10, 3, 16, 0) }
    @Test fun t6_dateInFutureMeansLastYear() { assertAt("25th December 10 am", 2025, 12, 25, 10, 0) }
    @Test fun t7_approx() { assertTrue(r("around 9 am").approximate); assertTrue(r("lagbhag 9 baje").approximate); assertFalse(r("at 9 am").approximate) }
    @Test fun t8_ambiguousHour_backwards() {
        assertAt("at 9", 2026, 10, 10, 9, 0)      // 9 ≤ now → today 09:00
        assertAt("at 11", 2026, 10, 9, 23, 0)     // 11:00 would be future; 23:00 yesterday is the latest past
        assertAt("around 2", 2026, 10, 10, 2, 0) // 02:00 today (latest past of 02:00/14:00)
    }
    @Test fun t9_futureSubtractsDay() { assertAt("at 3 pm", 2026, 10, 9, 15, 0) }
    @Test fun t10_default() {
        for (p in listOf(null, "", "   ", "sometime")) {
            val x = r(p); assertTrue(x.defaulted, p.toString()); assertEquals(Fx.now, x.instant); assertTrue(x.approximate); assertEquals("T10", x.matchedRule)
        }
    }
    @Test fun granularity() { assertEquals(Granularity.HOUR, r("at 9 am").granularity); assertEquals(Granularity.MINUTE, r("9:30 am").granularity); assertEquals(Granularity.DAYPART, r("this morning").granularity); assertEquals(Granularity.DAY, r("on 5th october").granularity) }
    @Test fun noonAfternoon() { assertAt("2 in the afternoon", 2026, 10, 9, 14, 0); assertAt("12 noon", 2026, 10, 9, 12, 0) }
    @Test fun resultNeverFarFuture() {
        for (p in listOf("at 11", "at 3 pm", "evening", "night", "tomorrow", "at 10:30")) {
            val x = r(p); assertTrue(!x.instant.isAfter(Fx.now.plusSeconds(300)), p)
        }
    }
}
