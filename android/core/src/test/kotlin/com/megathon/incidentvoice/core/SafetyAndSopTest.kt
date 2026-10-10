package com.megathon.incidentvoice.core

import com.megathon.incidentvoice.core.T.form
import com.megathon.incidentvoice.core.model.Severity
import com.megathon.incidentvoice.core.recommend.SopRecommender
import com.megathon.incidentvoice.core.safety.EmergencyDetector
import com.megathon.incidentvoice.core.safety.EmergencyLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmergencyDetectorTest {
    private val d = EmergencyDetector(T.tx)

    @Test fun fireDrillExcluded() {
        assertNull(d.detect("There was a fire drill in Block A at 11, everything went fine"))
        assertNull(d.detect("Mock drill ho gaya, sab theek hai"))
        assertNull(d.detect("Fire extinguisher inspection done in the store"))
    }

    @Test fun aagLagGayiFireHint() {
        val a = d.detect("Store room mein aag lag gayi hai, jaldi aao")
        assertNotNull(a)
        assertEquals(EmergencyLevel.AMBER, a.level) // keyword trigger = amber (PRD §10.7)
        assertEquals("FIRE", a.category)
        val red = d.evaluate("Store room mein aag lag gayi hai", Severity.CRITICAL, "FIRE")!!
        assertEquals(EmergencyLevel.RED, red.level)
        assertEquals("FIRE", red.category)
    }

    @Test fun otherKeywordsAndHints() {
        assertEquals("MEDICAL", d.detect("a worker is unconscious near line 2")!!.category)
        assertEquals("MEDICAL", d.detect("woh behosh ho gaya")!!.category)
        assertEquals("HAZMAT", d.detect("strong gas ki smell in the kitchen")!!.category)
        assertEquals("ELECTRICAL", d.detect("he got electrocuted at panel DB-3")!!.category)
        assertEquals("STRUCTURAL", d.detect("do log lift mein phase hue hain")!!.category)
        assertEquals("FIRE", d.detect("आग लगी है")!!.category)
        assertNotNull(d.detect("During the fire drill a man fainted on the stairs"), "drill masking must not hide a real emergency")
    }

    @Test fun noFalsePositive() {
        assertNull(d.detect("There was a water leak near Conference Room 2 around 10 AM."))
        assertNull(d.evaluate("minor leak", Severity.LOW, "WATER"))
    }

    @Test fun criticalWithoutKeywordIsRed() {
        val r = d.evaluate("the person is not responding", Severity.CRITICAL, "MEDICAL")!!
        assertEquals(EmergencyLevel.RED, r.level)
        assertEquals("MEDICAL", r.category)
    }
}

class SopRecommenderTest {
    private val rec = SopRecommender(T.sop)
    private fun slide(vararg extra: Pair<String, Any?>) = form(
        "incident_type" to "WATER", "description" to "Leak", "location" to "near Conference Room 2",
        "actions_taken" to listOf("Maintenance isolated the area"), *extra,
    )

    @Test fun waterSlideExample() {
        val f = slide("injuries" to "NO", "severity" to "LOW", "leak_source" to "burst pipe under the sink", "leak_status" to "STOPPED")
        val ids = rec.candidates(f).map { it.id }
        assertEquals(listOf("WATER-02", "WATER-04", "WATER-05", "WATER-06", "WATER-07", "WATER-08", "GEN-01", "GEN-02", "GEN-03", "GEN-04"), ids)
        assertTrue("WATER-03" !in ids, "already done: area isolated")
        assertTrue("WATER-01" !in ids, "leak stopped")
        val top = rec.recommend(f)
        assertEquals(6, top.size)
        assertTrue(top.first().immediate)
    }

    @Test fun ongoingLeakIncludesWater01First() {
        val f = slide("leak_status" to "ONGOING")
        val top = rec.recommend(f)
        assertEquals("WATER-01", top.first().sop)
        assertTrue("WATER-05" !in top.map { it.sop }) // mop-up only once stopped
    }

    @Test fun missingFieldSatisfiesWhen() {
        val f = form("incident_type" to "WATER", "description" to "Leak")
        val ids = rec.candidates(f).map { it.id }
        assertTrue(ids.containsAll(listOf("WATER-01", "WATER-02", "WATER-03", "WATER-05")))
    }

    @Test fun starConditionAndSecondary() {
        val f = form("incident_type" to "FIRE", "secondary_types" to listOf("MEDICAL"), "description" to "x", "ignition_source" to "panel")
        val ids = rec.candidates(f).map { it.id }
        assertTrue("FIRE-08" in ids)
        // immediate steps of both categories come before non-immediate ones
        val immediateFlags = rec.candidates(f).map { it.immediate }
        assertEquals(immediateFlags.sortedDescending(), immediateFlags)
        assertTrue(ids.indexOf("MED-03") < ids.indexOf("FIRE-05"))
    }

    @Test fun emergencyStepsAreImmediateIgnoringWhen() {
        val steps = rec.emergencySteps("WATER").map { it.id }
        assertEquals(listOf("WATER-01", "WATER-02"), steps)
        assertTrue(rec.emergencySteps("NOPE").isNotEmpty())
    }
}
