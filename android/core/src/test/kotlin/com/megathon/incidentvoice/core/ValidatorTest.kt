package com.megathon.incidentvoice.core

import com.megathon.incidentvoice.core.T.form
import com.megathon.incidentvoice.core.T.str
import com.megathon.incidentvoice.core.model.FieldSource
import com.megathon.incidentvoice.core.model.FieldState
import com.megathon.incidentvoice.core.model.FieldStatus
import com.megathon.incidentvoice.core.validation.IssueReasons
import com.megathon.incidentvoice.core.validation.SeverityRules
import com.megathon.incidentvoice.core.validation.Validator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PRD §14.6 matrix V1–V10 plus extras. */
class ValidatorTest {
    private val tx = T.tx
    private val tenAm = "2026-10-10T10:00:00+05:30"

    private fun slideForm(vararg extra: Pair<String, Any?>) = form(
        "incident_type" to "WATER",
        "description" to "A water leak occurred near Conference Room 2.",
        "location" to "near Conference Room 2",
        "occurred_at" to tenAm,
        "actions_taken" to listOf("Maintenance isolated the area"),
        *extra,
    )

    @Test fun v1_slideAfterExtraction() {
        val r = Validator.validate(slideForm(), tx, T.NOW)
        assertEquals(listOf("severity", "injuries", "leak_source", "leak_status"), r.missing)
        assertFalse(r.complete)
    }

    @Test fun v2_afterPatchInjuriesSeverity_nextIsWaterHazardPair() {
        val f = slideForm("injuries" to "NO", "severity" to "LOW")
        val r = Validator.validate(f, tx, T.NOW)
        assertEquals(listOf("leak_source", "leak_status"), r.missing)
        val q = Validator.nextQuestion(r, f, tx, false)!!
        assertEquals(setOf("leak_source", "leak_status"), q.fields.toSet())
        assertEquals(tx.pairTemplate("leak_source", "leak_status")!!.en, q.textEn)
    }

    @Test fun v3_fireWithMedicalSecondary() {
        val f = form("incident_type" to "FIRE", "secondary_types" to listOf("MEDICAL"), "description" to "Fire in pantry, one person burnt")
        val r = Validator.validate(f, tx, T.NOW)
        assertTrue(r.required.containsAll(listOf("fire_status", "evacuation_status", "fire_brigade_called", "patient_condition", "first_aid_given", "ambulance_called")))
    }

    @Test fun v4_equipmentRequiresEquipment() {
        val f = form("incident_type" to "EQUIPMENT", "description" to "Conveyor jammed")
        val r = Validator.validate(f, tx, T.NOW)
        assertTrue("equipment" in r.required)
        assertTrue("equipment" in r.missing)
    }

    @Test fun v5_futureTimeInvalidAndAskedAgain() {
        val f = slideForm("occurred_at" to "2026-10-10T13:00:00+05:30", "injuries" to "NO", "severity" to "LOW",
            "leak_source" to "pipe", "leak_status" to "STOPPED")
        val r = Validator.validate(f, tx, T.NOW)
        assertTrue(r.invalid.any { it.key == "occurred_at" && it.reason == IssueReasons.FUTURE_TIME })
        assertEquals(listOf("occurred_at"), r.missing)
        val q = Validator.nextQuestion(r, f, tx, false)!!
        assertEquals(listOf("occurred_at"), q.fields)
    }

    @Test fun v5b_tooOld() {
        val f = slideForm("occurred_at" to "2026-08-01T10:00:00+05:30")
        val r = Validator.validate(f, tx, T.NOW)
        assertTrue(r.invalid.any { it.key == "occurred_at" && it.reason == IssueReasons.TOO_OLD })
    }

    @Test fun v6_allUnknownExceptTypeDescription_complete() {
        val f = form("incident_type" to "FIRE", "description" to "Smoke from panel",
            "location" to null, "occurred_at" to null, "severity" to null, "injuries" to null,
            "fire_status" to null, "evacuation_status" to null, "fire_brigade_called" to null)
        val r = Validator.validate(f, tx, T.NOW)
        assertTrue(r.complete, "missing=${r.missing}")
        assertEquals(1f, r.progress)
    }

    @Test fun v6b_unknownDoesNotSatisfyTypeOrDescription() {
        val f = form("incident_type" to "OTHER", "description" to null, "location" to null, "occurred_at" to null,
            "severity" to null, "injuries" to null)
        val r = Validator.validate(f, tx, T.NOW)
        assertEquals(listOf("description"), r.missing)
    }

    @Test fun v7_askedTwiceSkipped() {
        val f = slideForm().with(FieldState.missing("severity", askedCount = 2))
        val r = Validator.validate(f, tx, T.NOW)
        val q = Validator.nextQuestion(r, f, tx, false)!!
        assertFalse("severity" in q.fields)
        assertEquals(listOf("injuries"), q.fields) // partner severity skipped → single question
    }

    @Test fun v8_criticalOnlyCommonAsked() {
        val f = form("incident_type" to "FIRE", "description" to "Fire spreading in store", "severity" to "CRITICAL",
            "location" to "store room", "occurred_at" to tenAm, "injuries" to "NO")
        val r = Validator.validate(f, tx, T.NOW)
        assertTrue(r.missing.containsAll(listOf("fire_status", "evacuation_status", "fire_brigade_called")))
        assertNull(Validator.nextQuestion(r, f, tx, emergencyMode = false))
        // with a common field missing, only that one is asked
        val f2 = f.with(FieldState.missing("injuries"))
        val q = Validator.nextQuestion(Validator.validate(f2, tx, T.NOW), f2, tx, true)!!
        assertEquals(listOf("injuries"), q.fields)
    }

    @Test fun v9_otherOnlyCommonMandatory() {
        val f = form("incident_type" to "OTHER", "description" to "Something odd")
        val r = Validator.validate(f, tx, T.NOW)
        assertEquals(tx.commonMandatoryKeys.toSet(), r.required.toSet())
    }

    @Test fun v10_hinglishTemplate() {
        val f = slideForm().copy(lang = "hinglish")
        val r = Validator.validate(f, tx, T.NOW)
        val q = Validator.nextQuestion(r, f, tx, false)!!
        assertEquals("Kya kisi ko chot lagi, aur abhi situation kitni serious hai?", q.text(f.lang))
    }

    @Test fun defaultedTimeSatisfies() {
        val f = slideForm("occurred_at" to FieldState("occurred_at", str(tenAm), FieldStatus.DEFAULTED, FieldSource.SYSTEM, 0))
        assertFalse("occurred_at" in Validator.validate(f, tx, T.NOW).missing)
    }

    @Test fun invalidEnumIsMissing() {
        val f = slideForm("leak_status" to "SPLASHING")
        val r = Validator.validate(f, tx, T.NOW)
        assertTrue("leak_status" in r.missing)
        assertTrue(r.invalid.any { it.key == "leak_status" && it.reason == IssueReasons.NOT_IN_ENUM })
    }

    @Test fun groupRuleWhenNoTemplate() {
        // evacuation_status + fire_brigade_called (both response, no explicit template)
        val f = form("incident_type" to "FIRE", "description" to "Fire", "location" to "Pantry", "occurred_at" to tenAm,
            "severity" to "HIGH", "injuries" to "NO", "fire_status" to "CONTAINED")
        val q = Validator.nextQuestion(Validator.validate(f, tx, T.NOW), f, tx, false)!!
        assertEquals(listOf("evacuation_status", "fire_brigade_called"), q.fields)
        assertEquals("Has the area been evacuated, and has the fire brigade been called?", q.textEn)
        assertEquals("Kya area khaali karwa diya gaya hai, aur Kya fire brigade ko call kiya gaya?", q.textHinglish)
    }

    @Test fun orderingCommonFirstThenGroupOrder() {
        val f = form("incident_type" to "MEDICAL", "description" to "Worker fell")
        val r = Validator.validate(f, tx, T.NOW)
        assertEquals(listOf("location", "occurred_at", "severity", "injuries", "patient_condition", "first_aid_given", "ambulance_called"), r.missing)
        val q = Validator.nextQuestion(r, f, tx, false)!!
        assertEquals(listOf("location", "occurred_at"), q.fields) // explicit pair template
    }

    @Test fun severityRulesRaiseNeverLower() {
        val f = form("incident_type" to "MEDICAL", "description" to "x", "severity" to "LOW", "patient_condition" to "UNCONSCIOUS")
        val r = SeverityRules.apply(f)
        assertEquals("CRITICAL", r.string("severity"))
        assertEquals(FieldSource.RULE, r.state("severity").source)
        val g = form("incident_type" to "MEDICAL", "description" to "x", "severity" to "CRITICAL", "injuries" to "YES")
        assertEquals("CRITICAL", SeverityRules.apply(g).string("severity"))
        val h = form("incident_type" to "VEHICLE", "description" to "x", "collision_type" to "VEHICLE_PERSON")
        val hr = SeverityRules.apply(h)
        assertEquals("HIGH", hr.string("severity"))
        assertEquals(FieldStatus.INFERRED, hr.status("severity"))
        val fire = form("incident_type" to "FIRE", "description" to "x", "fire_status" to "ACTIVE", "evacuation_status" to "IN_PROGRESS")
        assertEquals("CRITICAL", SeverityRules.apply(fire).string("severity"))
        val user = form("incident_type" to "MEDICAL", "description" to "x", "injuries" to "YES",
            "severity" to FieldState("severity", str("LOW"), FieldStatus.USER_EDITED, FieldSource.USER, null))
        assertEquals("LOW", SeverityRules.apply(user).string("severity"))
        assertNotNull(SeverityRules.floor(user))
    }
}
