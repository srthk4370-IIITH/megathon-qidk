package com.megathon.incidentvoice.core

import com.megathon.incidentvoice.core.llm.ParseResult
import com.megathon.incidentvoice.core.llm.Tools
import com.megathon.incidentvoice.core.model.FieldSource
import com.megathon.incidentvoice.core.model.FieldStatus
import com.megathon.incidentvoice.core.model.IncidentForm
import com.megathon.incidentvoice.core.recommend.SopRecommender
import com.megathon.incidentvoice.core.safety.EmergencyDetector
import com.megathon.incidentvoice.core.validation.Validator
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PRD §13.5 worked example (demo scene 1): parse → merge → validate → ask → patch → ask → patch → complete. */
class SlideEndToEndTest {
    private val tx = T.tx

    @Test fun slideScenario() {
        // Emergency keyword scan on the raw transcript: none.
        assertNull(EmergencyDetector(tx).detect(T.SLIDE_TRANSCRIPT))

        // 1. extraction
        val call = (T.parser.parse(T.SLIDE_OUTPUT, Tools.FILL, listOf(T.SLIDE_TRANSCRIPT)) as ParseResult.Ok).call
        var form = T.merger.applyFill(IncidentForm(reportedAt = T.NOW), call, turnId = 0, transcript = T.SLIDE_TRANSCRIPT).form
        assertEquals("en", form.lang)
        assertEquals("WATER", form.incidentType)
        assertEquals("A-1-CR2", form.locationId)
        assertEquals("around 10 AM", form.timeExpression)
        assertTrue(form.occurredAtApprox)
        assertEquals(LocalDateTime.parse("2026-10-10T10:00"), LocalDateTime.ofInstant(Validator.parseInstant(form.string("occurred_at")!!)!!, T.ZONE))
        assertEquals(FieldStatus.FILLED, form.status("location"))
        assertEquals(FieldSource.LLM_EXTRACT, form.state("location").source)

        // 2. validate (V1)
        var v = Validator.validate(form, tx, T.NOW)
        assertEquals(listOf("severity", "injuries", "leak_source", "leak_status"), v.missing)

        // 3. round 1: injuries + severity pair template
        val q1 = Validator.nextQuestion(v, form, tx, false)!!
        assertEquals(setOf("injuries", "severity"), q1.fields.toSet())
        assertEquals("Was anyone hurt, and how serious is the situation right now?", q1.textEn)
        form = T.merger.markAsked(form, q1.fields)
        val a1 = "No one is hurt, it's minor."
        val p1 = (T.parser.parse("""{"name":"update_incident_fields","parameters":{"set":{"injuries":"NO","severity":"LOW"}}}""", Tools.UPDATE, listOf(T.SLIDE_TRANSCRIPT, a1)) as ParseResult.Ok).call
        form = T.merger.applyPatch(form, p1, turnId = 1, answer = a1).form
        assertEquals(FieldSource.LLM_PATCH, form.state("injuries").source)
        assertEquals(1, form.state("injuries").askedCount)

        // 4. round 2: WATER hazard pair (V2)
        v = Validator.validate(form, tx, T.NOW)
        assertEquals(listOf("leak_source", "leak_status"), v.missing)
        val q2 = Validator.nextQuestion(v, form, tx, false)!!
        assertEquals(setOf("leak_source", "leak_status"), q2.fields.toSet())
        assertEquals(tx.pairTemplate("leak_source", "leak_status")!!.en, q2.textEn)
        form = T.merger.markAsked(form, q2.fields)
        val a2 = "A pipe under the sink burst, it's stopped now."
        val p2 = (T.parser.parse("""{"name":"update_incident_fields","parameters":{"set":{"leak_source":"burst pipe under the sink","leak_status":"stopped"}}}""", Tools.UPDATE, listOf(T.SLIDE_TRANSCRIPT, a1, a2)) as ParseResult.Ok).call
        form = T.merger.applyPatch(form, p2, turnId = 2, answer = a2).form
        assertEquals(FieldStatus.FILLED, form.status("leak_source"))

        // 5. complete → recommendations
        v = Validator.validate(form, tx, T.NOW)
        assertTrue(v.complete, "missing=${v.missing}")
        assertNull(Validator.nextQuestion(v, form, tx, false))
        val recs = SopRecommender(T.sop).recommend(form)
        assertEquals(listOf("WATER-02", "WATER-04", "WATER-05", "WATER-06", "WATER-07", "WATER-08"), recs.map { it.sop })
    }

    @Test fun userEditIsNeverOverwritten() {
        val call = (T.parser.parse(T.SLIDE_OUTPUT, Tools.FILL) as ParseResult.Ok).call
        var form = T.merger.applyFill(IncidentForm(reportedAt = T.NOW), call, 0).form
        form = T.merger.applyUserEdit(form, "location", JsonPrimitive("Pantry"))
        val p = (T.parser.parse("""{"name":"update_incident_fields","parameters":{"set":{"location":"Bay 4","injuries":"yes"}}}""", Tools.UPDATE) as ParseResult.Ok).call
        val r = T.merger.applyPatch(form, p, 1)
        assertEquals("Pantry", r.form.string("location"))
        assertEquals("A-1-PAN", r.form.locationId)
        assertTrue(r.issues.any { it.code == "USER_EDITED_KEPT" })
        assertEquals("YES", r.form.string("injuries"))
        assertEquals("MEDIUM", r.form.string("severity")) // severity floor: injuries=YES
        assertEquals(FieldSource.RULE, r.form.state("severity").source)
    }

    @Test fun noTimeDefaultsToReportTime() {
        val raw = """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"Leak in pantry","location":"pantry"}}"""
        val form = T.merger.applyFill(IncidentForm(reportedAt = T.NOW), (T.parser.parse(raw, Tools.FILL) as ParseResult.Ok).call, 0).form
        assertEquals(FieldStatus.DEFAULTED, form.status("occurred_at"))
        assertEquals(T.NOW, Validator.parseInstant(form.string("occurred_at")!!))
        assertTrue(form.occurredAtApprox)
        assertTrue("occurred_at" !in Validator.validate(form, tx, T.NOW).missing)
        assertEquals("Water Leak / Flooding at pantry", form.string("title"))
    }

    @Test fun unknownAndSkippedPatch() {
        val call = (T.parser.parse(T.SLIDE_OUTPUT, Tools.FILL) as ParseResult.Ok).call
        var form = T.merger.applyFill(IncidentForm(reportedAt = T.NOW), call, 0).form
        val skip = (T.parser.parse("""{"name":"update_incident_fields","parameters":{"set":{"injuries":"NO"},"skipped":true}}""", Tools.UPDATE) as ParseResult.Ok).call
        form = T.merger.applyPatch(form, skip, 1).form
        assertEquals(FieldStatus.MISSING, form.status("injuries"))
        val unk = (T.parser.parse("""{"name":"update_incident_fields","parameters":{"set":{},"unknown":["injuries","severity"]}}""", Tools.UPDATE) as ParseResult.Ok).call
        form = T.merger.applyPatch(form, unk, 2).form
        assertEquals(FieldStatus.UNKNOWN, form.status("injuries"))
        assertNull(form.value("injuries"))
        assertEquals(listOf("leak_source", "leak_status"), Validator.validate(form, tx, T.NOW).missing)
    }

    @Test fun consistencyRulesAndCategoryFilter() {
        val raw = """{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"x","injuries":"NO",
            "persons":[{"name":"Ravi","injured":true},{"name":"ravi","role":"AFFECTED"}],"details":{"fire_status":"ACTIVE","leak_status":"ONGOING"}}}"""
        val r = T.merger.applyFill(IncidentForm(reportedAt = T.NOW), (T.parser.parse(raw, Tools.FILL) as ParseResult.Ok).call, 0)
        val f = r.form
        assertEquals("YES", f.string("injuries"))
        assertEquals(FieldSource.RULE, f.state("injuries").source)
        assertTrue("INJURIES_RAISED_BY_RULE" in f.flags)
        assertEquals("1", f.string("injured_count"))
        assertEquals(1, (f.value("persons") as kotlinx.serialization.json.JsonArray).size)
        assertNull(f.value("fire_status"), "FIRE field dropped: FIRE not chosen")
        assertTrue(r.issues.any { it.key == "fire_status" && it.code == "CATEGORY_NOT_CHOSEN" })
        assertEquals("ONGOING", f.string("leak_status"))
    }
}
