package com.megathon.incidentvoice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiWitnessTest {
    private val cfg = AppConfig.MultiWitnessCfg()
    private val now = 1_791_000_000_000L
    private fun min(n: Int) = n * 60_000L
    private fun acc(id: String, loc: String? = "near conference room 2", locId: String? = null, types: List<String> = listOf("WATER_LEAK"), sev: String? = "MEDIUM",
                    at: Long? = now - min(30), sub: Long = now - min(20), inj: String? = "NO", n: Int? = null, haz: String? = null,
                    eq: List<String> = emptyList(), act: List<String> = emptyList(), desc: String? = "water on the floor", parent: String? = null) =
        WitnessAccount(id, "INC-$id", "W$id", sub, types, sev, loc, locId, at, inj, n, haz, eq, act, emptyList(), desc, parent)

    // ---- duplicate finder
    @Test fun sameRegistryPlace_sameCategory_closeInTime_isProposed() {
        val c = DuplicateFinder.find(acc("2", locId = "L1", sub = now), listOf(acc("1", loc = "conf room two", locId = "L1")), cfg, now)
        assertEquals(1, c.size); assertEquals("1", c[0].account.id); assertEquals(20, c[0].minutesAgo); assertTrue(c[0].score >= cfg.minScore)
        assertEquals("This looks like the same water leak at conf room two reported 20 min ago (INC-1). Add your report as a witness account?", DuplicateFinder.question(c[0], "Water leak"))
        assertTrue(DuplicateFinder.question(c[0].copy(account = c[0].account.copy(locationText = "near Conference Room 2")), "Water leak").contains("water leak near Conference Room 2 reported"))
    }
    @Test fun fuzzyLocationText_matchesWithoutRegistryIds() {
        assertEquals(1.0, DuplicateFinder.locationScore(acc("a", "Conference Room 2"), acc("b", "near the conference room 2")), 1e-9)
        assertTrue(DuplicateFinder.locationScore(acc("a", "conference room 2"), acc("b", "confrence room 2")) >= cfg.minLocationScore)
        assertTrue(DuplicateFinder.locationScore(acc("a", "bay 4"), acc("b", "pantry")) < 0.5)
        assertEquals(0.0, DuplicateFinder.locationScore(acc("a", "bay 4", "L1"), acc("b", "bay 4", "L2")), 1e-9)   // two different registry places
    }
    @Test fun notProposed_whenPlaceCategoryTimeOrAgeDoNotFit() {
        val new = acc("2", sub = now)
        fun none(o: WitnessAccount) = assertTrue(DuplicateFinder.find(new, listOf(o), cfg, now).isEmpty())
        none(acc("1", loc = "pantry"))
        none(acc("1", types = listOf("FIRE")))
        none(acc("1", at = now - min(30 + 46)))                    // outside ±45 min
        none(acc("1", sub = now - 13 * 3_600_000L, at = now - min(30)))   // submitted more than 12 h ago
        none(acc("2"))                                             // itself
        none(acc("1", parent = "2"))                               // already an account of this case
    }
    @Test fun secondaryCategoryInCommon_isEnough_andBestScoreComesFirst() {
        val new = acc("3", types = listOf("MEDICAL", "VEHICLE"), sub = now)
        val c = DuplicateFinder.find(new, listOf(acc("1", types = listOf("VEHICLE"), at = now - min(60)), acc("2", types = listOf("MEDICAL"))), cfg, now)
        assertEquals(listOf("2", "1"), c.map { it.account.id })
    }

    // ---- merge rules
    @Test fun severity_worstWins_conflictOnlyWhenTwoLevelsApart() {
        val a = MergeEngine.merge(listOf(acc("1", sev = "MEDIUM"), acc("2", sev = "HIGH"))).field("severity")!!
        assertEquals("HIGH", a.merged); assertFalse(a.conflict)
        val b = MergeEngine.merge(listOf(acc("1", sev = "LOW"), acc("2", sev = "HIGH"), acc("3", sev = null))).field("severity")!!
        assertEquals("HIGH", b.merged); assertTrue(b.conflict); assertEquals(listOf("LOW", "HIGH", "—"), b.values)
    }
    @Test fun yesNoUnknown_yesBeatsNo_anythingBeatsUnknown() {
        val yn = MergeEngine.merge(listOf(acc("1", inj = "NO"), acc("2", inj = "YES"))).field("injuries")!!
        assertEquals("YES", yn.merged); assertTrue(yn.conflict)
        val nu = MergeEngine.merge(listOf(acc("1", inj = "UNKNOWN"), acc("2", inj = "NO"))).field("injuries")!!
        assertEquals("NO", nu.merged); assertFalse(nu.conflict)
        val yu = MergeEngine.merge(listOf(acc("1", haz = "UNKNOWN"), acc("2", haz = "YES"))).field("hazard_ongoing")!!
        assertEquals("YES", yu.merged); assertFalse(yu.conflict)
    }
    @Test fun time_earliestShownAsRange_conflictBeyond15Minutes() {
        val t0 = 1_791_000_000_000L
        val close = MergeEngine.merge(listOf(acc("1", at = t0), acc("2", at = t0 + min(10)))).field("occurred_at")!!
        assertEquals(MergeEngine.fmt(t0) + "–" + MergeEngine.fmt(t0 + min(10)), close.merged); assertFalse(close.conflict)
        val far = MergeEngine.merge(listOf(acc("1", at = t0 + min(20)), acc("2", at = t0))).field("occurred_at")!!
        assertTrue(far.merged.startsWith(MergeEngine.fmt(t0))); assertTrue(far.conflict)
        assertEquals(MergeEngine.fmt(t0), MergeEngine.merge(listOf(acc("1", at = t0), acc("2", at = null))).field("occurred_at")!!.merged)
    }
    @Test fun counts_maxWins_conflictWhenTheyDiffer() {
        val f = MergeEngine.merge(listOf(acc("1", n = 1), acc("2", n = 3), acc("3"))).field("injured_count")!!
        assertEquals("3", f.merged); assertTrue(f.conflict)
        assertFalse(MergeEngine.merge(listOf(acc("1", n = 2), acc("2", n = 2))).field("injured_count")!!.conflict)
    }
    @Test fun lists_unionWithFuzzyDedupe_neverConflict() {
        val f = MergeEngine.merge(listOf(acc("1", eq = listOf("Fire extinguisher", "pump")), acc("2", eq = listOf("fire extinguishers", "Ladder")))).field("equipment")!!
        assertEquals("Fire extinguisher, pump, Ladder", f.merged); assertFalse(f.conflict)
    }
    @Test fun location_mostReported_conflictWhenRegistryIdsDiffer() {
        val same = MergeEngine.merge(listOf(acc("1", "conference room 2", "L1"), acc("2", "conf room two", "L1"), acc("3", "pantry", null))).field("location")!!
        assertEquals("conference room 2", same.merged); assertFalse(same.conflict)
        assertTrue(MergeEngine.merge(listOf(acc("1", "conference room 2", "L1"), acc("2", "pantry", "L2"))).field("location")!!.conflict)
    }
    @Test fun description_keepsEveryAccountSideBySide() {
        val f = MergeEngine.merge(listOf(acc("1", desc = "pipe burst"), acc("2", desc = "water everywhere"))).field("description")!!
        assertEquals(listOf("pipe burst", "water everywhere"), f.values); assertEquals("W1: pipe burst\nW2: water everywhere", f.merged); assertFalse(f.conflict)
    }
    @Test fun resolution_replacesTheMergedValue_andClosesTheConflict() {
        val accounts = listOf(acc("1", sev = "LOW", inj = "NO"), acc("2", sev = "CRITICAL", inj = "YES"))
        val open = MergeEngine.merge(accounts)
        assertEquals(2, open.conflicts); assertEquals(2, open.openConflicts)
        val done = MergeEngine.merge(accounts, mapOf("severity" to "LOW", "location" to "ignored: not a conflict"))
        assertEquals("LOW", done.field("severity")!!.merged); assertEquals("LOW", done.field("severity")!!.resolved)
        assertEquals(2, done.conflicts); assertEquals(1, done.openConflicts); assertNull(done.field("location")!!.resolved)
        assertTrue(MergeEngine.summary(done).contains("2 accounts")); assertTrue(MergeEngine.summary(done).contains("trusted: LOW"))
    }
}
