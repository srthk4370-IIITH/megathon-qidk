package com.megathon.incidentvoice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.megathon.incidentvoice.core.Fx.form
import com.megathon.incidentvoice.core.Fx.now
import com.megathon.incidentvoice.core.Fx.tx

class ValidatorTest {
    private fun v(f: IncidentForm) = Validator.validate(f, tx, now)

    @Test fun v1_slideExample() {
        val r = v(Fx.slide())
        assertEquals(listOf("severity", "injuries", "leak_source", "leak_status"), r.missing)
        assertTrue(!r.complete)
    }

    @Test fun v2_afterPatch_nextIsWaterPair() {
        val f = Fx.slide().with(FieldState("injuries", Fx.s("NO"), FieldStatus.FILLED)).with(FieldState("severity", Fx.s("LOW"), FieldStatus.FILLED))
        val r = v(f)
        assertEquals(listOf("leak_source", "leak_status"), r.missing)
        val q = Validator.nextQuestion(r, f, tx, false)!!
        assertEquals(listOf("leak_source", "leak_status"), q.fields)
        assertEquals("Where is the water coming from, and is it still leaking?", q.textEn)
    }

    @Test fun pairTemplate_injuriesSeverity() {
        val q = Validator.nextQuestion(v(Fx.slide()), Fx.slide(), tx, false)!!
        assertEquals("Was anyone hurt, and how serious is the situation right now?", q.textEn)
        assertEquals("Kya kisi ko chot lagi, aur abhi situation kitni serious hai?", q.textHinglish)
    }

    @Test fun v3_fireMedicalSecondary() {
        val f = form("incident_type" to "FIRE", "secondary_types" to listOf("MEDICAL"))
        val req = v(f).required
        for (k in listOf("fire_status", "evacuation_status", "fire_brigade_called", "patient_condition", "first_aid_given", "ambulance_called"))
            assertTrue(k in req, k)
    }

    @Test fun v4_equipmentRequired() {
        val f = form("incident_type" to "EQUIPMENT")
        val r = v(f)
        assertTrue("equipment" in r.required && "equipment" in r.missing)
    }

    @Test fun v5_futureTimeInvalid() {
        val f = Fx.slide().with(FieldState("occurred_at", Fx.ms(now.plusSeconds(3600)), FieldStatus.FILLED))
        val r = v(f)
        assertTrue(r.invalid.any { it.key == "occurred_at" && it.reason == "FUTURE_TIME" })
        assertTrue("occurred_at" in r.missing)
        val old = Fx.slide().with(FieldState("occurred_at", Fx.ms(now.minusSeconds(40L * 86400)), FieldStatus.FILLED))
        assertTrue(v(old).invalid.any { it.reason == "TOO_OLD" })
    }

    @Test fun v6_allUnknownExceptTypeDescription_complete() {
        var f = form("incident_type" to "WATER", "description" to "pipe burst")
        for (k in v(f).required) if (k != "incident_type" && k != "description") f = f.with(FieldState(k, null, FieldStatus.UNKNOWN))
        assertTrue(v(f).complete)
        // but UNKNOWN does not satisfy type/description
        val g = f.with(FieldState("description", null, FieldStatus.UNKNOWN))
        assertTrue("description" in v(g).missing)
    }

    @Test fun v7_askedTwiceSkipped() {
        var f = Fx.slide()
        f = f.with(FieldState("severity", null, FieldStatus.MISSING, askedCount = 2))
            .with(FieldState("injuries", null, FieldStatus.MISSING, askedCount = 2))
        val q = Validator.nextQuestion(v(f), f, tx, false)!!
        assertEquals(listOf("leak_source", "leak_status"), q.fields)
        // all asked twice → nothing to ask
        val all = listOf("leak_source", "leak_status").fold(f) { acc, k -> acc.with(FieldState(k, null, FieldStatus.MISSING, askedCount = 2)) }
        assertNull(Validator.nextQuestion(v(all), all, tx, false))
    }

    @Test fun v8_criticalOnlyCommon() {
        val f = Fx.slide().with(FieldState("severity", Fx.s("CRITICAL"), FieldStatus.FILLED))
        val q = Validator.nextQuestion(v(f), f, tx, emergencyMode = true)!!
        assertEquals(listOf("injuries"), q.fields)
        val g = f.with(FieldState("injuries", Fx.s("YES"), FieldStatus.FILLED))
        assertNull(Validator.nextQuestion(v(g), g, tx, emergencyMode = true))
    }

    @Test fun v9_otherOnlyCommon() {
        val f = form("incident_type" to "OTHER")
        assertEquals(listOf("incident_type", "description", "location", "occurred_at", "severity", "injuries"), v(f).required)
    }

    @Test fun v10_hinglishTemplate() {
        val f = Fx.slide().copy(lang = "hinglish")
        val q = Validator.nextQuestion(v(f), f, tx, false)!!
        assertEquals(q.textHinglish, q.text(f.lang))
        assertTrue(q.text(f.lang).startsWith("Kya kisi ko chot"))
    }

    @Test fun defaultedTimeSatisfies() {
        val f = Fx.slide().with(FieldState("occurred_at", Fx.ms(now), FieldStatus.DEFAULTED))
        assertTrue("occurred_at" !in v(f).missing)
    }

    @Test fun groupRulePairWithoutTemplate() {
        val f = form("incident_type" to "MEDICAL", "description" to "x y", "location" to "reception", "occurred_at" to now.toEpochMilli(),
            "severity" to "LOW", "injuries" to "YES")
        val q = Validator.nextQuestion(v(f), f, tx, false)!!
        // patient_condition (victim) has no partner in same group? first_aid_given/ambulance share 'response'
        assertTrue(q.fields.isNotEmpty())
        assertTrue(q.textEn.endsWith("?"))
    }

    @Test fun progressMonotonic() {
        val a = v(Fx.slide()).progress
        val b = v(Fx.slide().with(FieldState("severity", Fx.s("LOW"), FieldStatus.FILLED))).progress
        assertTrue(b > a)
    }
}
