package com.megathon.incidentvoice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NormaliserTest {
    private val lm = LocationMatcher(Fx.registry)

    @Test fun location_autoMatchWithQualifier() {
        val m = lm.match("near Conference Room 2")
        assertEquals(MatchKind.AUTO, m.kind); assertEquals("A-1-CR2", m.location!!.id); assertEquals("near", m.qualifier)
    }
    @Test fun location_aliasAndAbbrev() {
        assertEquals("A-1-CR2", lm.match("conf rm two").location?.id)
        assertEquals("A-1-CR2", lm.match("CR2").location?.id)
    }
    @Test fun location_wrongNumberNotAutoMatched() {
        val m = lm.match("conference room 7")
        assertTrue(m.kind != MatchKind.AUTO, m.toString())
    }
    @Test fun location_unknownIsNone() { assertEquals(MatchKind.NONE, lm.match("somewhere on the moon").kind); assertEquals(MatchKind.NONE, lm.match("").kind) }
    @Test fun jaroWinkler_basics() {
        assertEquals(1.0, LocationMatcher.jaroWinkler("abc", "abc")); assertTrue(LocationMatcher.jaroWinkler("martha", "marhta") > 0.95)
    }

    @Test fun lang_detect() {
        assertEquals("en", LangDetector.detect("There was a water leak near the pantry"))
        assertEquals("hinglish", LangDetector.detect("pantry ke paas paani leak ho raha hai aur kisi ko chot nahi lagi"))
        assertEquals("hi", LangDetector.detect("कैंटीन में आग लग गई है"))
        assertEquals("hi", LangDetector.detect("anything", "hi"))
    }

    @Test fun emergency_keywords() {
        val d = EmergencyDetector(Fx.tx)
        assertEquals("FIRE", d.scan("there is an explosion in bay 3")?.categoryHint)
        assertNotNull(d.scan("gas leak near the tank"))
        assertNotNull(d.scan("woh behosh ho gaya"))
        assertNull(d.scan("we had a fire drill this morning"))
        assertNull(d.scan("fire extinguisher inspection done"))
        assertNull(d.scan("water leak near conference room 2"))
    }

    @Test fun people_dedupe_and_equipment() {
        val p = JsonObject(mapOf("name" to JsonPrimitive("Ramesh")))
        assertEquals(1, PeopleNormaliser.dedupePersons(listOf(p, JsonObject(mapOf("name" to JsonPrimitive("ramesh"))))).size)
        assertEquals(listOf("P-101", "Forklift"), PeopleNormaliser.normaliseEquipment(listOf("p-101", "P-101", "Forklift")))
    }

    @Test fun severityFloor() {
        assertEquals("CRITICAL", SeverityRules.floor(Fx.form("patient_condition" to "UNCONSCIOUS")))
        assertEquals("HIGH", SeverityRules.floor(Fx.form("fire_status" to "ACTIVE", "evacuation_status" to "COMPLETED")))
        assertEquals("CRITICAL", SeverityRules.floor(Fx.form("fire_status" to "ACTIVE", "evacuation_status" to "IN_PROGRESS")))
        assertEquals("MEDIUM", SeverityRules.floor(Fx.form("injuries" to "YES")))
        assertEquals("CRITICAL", SeverityRules.floor(Fx.form("electrical_hazard" to "SHOCK", "injuries" to "YES")))
        assertEquals("HIGH", SeverityRules.floor(Fx.form("collision_type" to "VEHICLE_PERSON")))
        assertNull(SeverityRules.floor(Fx.form("injuries" to "NO")))
    }

    @Test fun sop_waterTemplates() {
        val sel = SopSelector(Fx.sop)
        val recs = sel.recommend(Fx.form("incident_type" to "WATER", "leak_status" to "ONGOING", "electrical_risk" to "YES"))
        assertTrue(recs.size in 1..6); assertTrue(recs.first().immediate)
        assertTrue(recs.any { it.sopId == "WATER-01" })
        val stopped = sel.recommend(Fx.form("incident_type" to "WATER", "leak_status" to "STOPPED"))
        assertTrue(stopped.none { it.sopId == "WATER-01" }) // when: leak_status ONGOING/UNKNOWN
        assertTrue(sel.emergencySteps(Fx.form("incident_type" to "FIRE")).isNotEmpty())
    }

    @Test fun asrPrompt_isShortAndNamesTopicsAndAnswers() {
        val p = AsrPrompt.build(Fx.tx)
        assertTrue(p.length <= 400, p.length.toString())
        for (w in listOf("water leak", "fire", "injury", "vehicle", "it is minor", "stopped")) assertTrue(p.contains(w), "$w missing in: $p")
        println(p)
    }
}
