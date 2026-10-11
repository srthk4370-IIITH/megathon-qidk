package com.megathon.incidentvoice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotspotsTest {
    private val day = HotspotRules.DAY_MS
    private val now = Fx.now.toEpochMilli()
    private val keys = LocationKeys(Fx.registry)
    private var seq = 0

    /** A report [daysAgo] days before now, the place as it was said. */
    private fun occ(type: String, place: String?, daysAgo: Double, locationId: String? = null): Occurrence {
        val k = keys.of(locationId, place)
        return Occurrence("r${seq++}", type, k?.first, k?.second, now - (daysAgo * day).toLong())
    }

    // ---- location normalisation

    @Test fun samePlaceHoweverItWasSaid() {
        val id = "A-1-CR2"
        for (said in listOf("Conference Room 2", "conf room 2", "near conference room two", "CONFERENCE ROOM 2", "the meeting room 2", "CR2"))
            assertEquals(id, keys.of(null, said)?.first, said)
        assertEquals(id, keys.of(id, "somewhere on floor 1")?.first)            // a stored registry id wins over the words
        assertEquals("Conference Room 2", keys.of(null, "conf room 2")?.second)
        assertNotEquals(id, keys.of(null, "Conference Room 1")?.first)
    }

    @Test fun placeOutsideTheRegistry_isKeyedByItsWords() {
        val a = keys.of(null, "Workshop"); val b = keys.of(null, "near the workshop"); val c = keys.of(null, "in the WORKSHOP")
        assertEquals("text:workshop", a?.first); assertEquals(a, b); assertEquals(a, c)
        assertEquals("Workshop", a?.second)
        assertNull(keys.of(null, "  ")); assertNull(keys.of(null, null))
    }

    // ---- repeat incidents

    @Test fun thirdLeakAtTheSamePlaceInSevenDays_isARepeat() {
        val a = occ("WATER", "Conference Room 2", 5.0); val b = occ("WATER", "conf room 2", 2.0)
        val c = occ("WATER", "near conference room two", 0.0)
        val all = listOf(a, b, c)
        assertEquals(RepeatTag(3, "WATER", 7), Hotspots.repeat(c, all))
        assertNull(Hotspots.repeat(b, all))   // it was only the 2nd when it was reported
        assertNull(Hotspots.repeat(a, all))
        assertEquals("3rd water leak here in 7 days", Hotspots.chip(Hotspots.repeat(c, all)!!, Fx.tx.category("WATER")!!.label))
        assertEquals(
            "Recurring hazard: 3 water leak reports at Conference Room 2 in 7 days. Recommend inspection of Conference Room 2.",
            Hotspots.recurring(Hotspots.repeat(c, all)!!, Fx.tx.category("WATER")!!.label, c.location!!),
        )
    }

    @Test fun repeat_needsTheSameTypeAndTheSamePlace() {
        val c = occ("WATER", "Conference Room 2", 0.0)
        assertNull(Hotspots.repeat(c, listOf(occ("WATER", "Conference Room 2", 1.0), occ("ELECTRICAL", "Conference Room 2", 2.0), c)))
        assertNull(Hotspots.repeat(c, listOf(occ("WATER", "Conference Room 2", 1.0), occ("WATER", "Conference Room 1", 2.0), c)))
        // no place or no type: never a repeat
        val nowhere = occ("WATER", null, 0.0)
        assertNull(Hotspots.repeat(nowhere, listOf(occ("WATER", null, 1.0), occ("WATER", null, 2.0), nowhere)))
    }

    @Test fun repeat_sevenDayWindowEdge() {
        val c = occ("WATER", "Conference Room 2", 0.0)
        val mid = occ("WATER", "Conference Room 2", 3.0)
        val onEdge = Occurrence("edge", "WATER", c.locationKey, c.location, c.atMs - 7 * day)          // exactly 7 days earlier: inside
        val justOut = Occurrence("out", "WATER", c.locationKey, c.location, c.atMs - 7 * day - 1)      // 1 ms older: outside
        assertEquals(3, Hotspots.repeat(c, listOf(onEdge, mid, c))?.n)
        assertNull(Hotspots.repeat(c, listOf(justOut, mid, c)))
        // later reports do not count towards an earlier one
        val later = Occurrence("later", "WATER", c.locationKey, c.location, c.atMs + 1)
        assertNull(Hotspots.repeat(c, listOf(mid, c, later)))
        assertEquals(3, Hotspots.repeat(later, listOf(mid, c, later))?.n)
    }

    @Test fun ordinals() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "103rd"), listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 103).map(Hotspots::ordinal))
    }

    // ---- hotspots

    @Test fun fiveReportsInThirtyDays_makeAHotspot_whateverTheType() {
        val shop = listOf(occ("EQUIPMENT", "Workshop", 1.0), occ("ELECTRICAL", "workshop", 3.0), occ("EQUIPMENT", "near the workshop", 10.0), occ("MEDICAL", "Workshop", 20.0))
        val four = shop + occ("FIRE", "Pantry", 2.0) + occ("FIRE", "Bay 1", 2.0) + occ("FIRE", "Bay 2", 2.0) + occ("WATER", "Bay 1", 4.0) + occ("WATER", "Bay 2", 4.0) + occ("WATER", "Pantry", 4.0) + occ("OTHER", "Pantry", 5.0) + occ("OTHER", "Bay 1", 5.0) + occ("OTHER", "Bay 2", 5.0)
        // 4 at the workshop, three other places with 3 each: the workshop is in the top 3 anyway; one of the 3-report places is not
        assertEquals(3, Hotspots.hotspots(four, now).size)
        val five = shop + occ("EQUIPMENT", "Workshop", 29.0)
        val h = Hotspots.hotspots(five, now).single()
        assertEquals("text:workshop", h.key); assertEquals(5, h.total); assertEquals("EQUIPMENT", h.topType)
        // a report older than 30 days does not count
        assertEquals(4, Hotspots.stats(shop + occ("EQUIPMENT", "Workshop", 30.5), now).single().total)
    }

    @Test fun topThreeByCount_needAtLeastTwoReports() {
        val all = listOf(
            occ("WATER", "Conference Room 2", 1.0), occ("WATER", "conf room 2", 2.0),
            occ("VEHICLE", "Loading Dock", 3.0), occ("ENVIRONMENTAL", "loading dock", 12.0), occ("VEHICLE", "Loading Dock", 13.0),
            occ("SECURITY", "Server Room", 4.0), occ("FIRE", "Pantry", 5.0),
        )
        assertEquals(listOf("Y-LD", "A-1-CR2"), Hotspots.top(all, now).map { it.key })
        val tags = Hotspots.tags(all, now)
        assertTrue(tags[all[0].id]!!.hotspot && tags[all[2].id]!!.hotspot)
        assertFalse(tags[all[5].id]!!.hotspot)
        assertEquals("Conference Room 2", tags[all[1].id]!!.location)
    }

    @Test fun trend_lastSevenDaysAgainstTheSevenBefore() {
        fun trendOf(vararg daysAgo: Double) = Hotspots.stats(daysAgo.map { occ("EQUIPMENT", "Workshop", it) }, now).single()
        trendOf(1.0, 2.0, 3.0, 9.0, 20.0).let { assertEquals(3, it.last7); assertEquals(1, it.prev7); assertEquals(Trend.MORE, it.trend) }
        assertEquals(Trend.FEWER, trendOf(1.0, 8.0, 9.0).trend)
        assertEquals(Trend.SAME, trendOf(1.0, 8.0, 25.0).trend)
        assertEquals("more", Hotspots.trendWord(Trend.MORE))
    }

    @Test fun tagsSurviveJson() {
        val t = ReportTags(RepeatTag(3, "WATER", 7), true, "Conference Room 2")
        assertEquals(t, ReportTags.parse(t.toJson()))
        assertNull(ReportTags.parse(null)); assertNull(ReportTags.parse("not json"))
    }
}
