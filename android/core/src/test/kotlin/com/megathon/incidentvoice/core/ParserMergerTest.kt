package com.megathon.incidentvoice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ParserMergerTest {
    private val p = Fx.parser
    private val slideJson = """{"name":"fill_incident_form","parameters":{"lang":"en","incident_type":"WATER","title":"Water leak near Conference Room 2","description":"A water leak occurred near Conference Room 2. Maintenance has isolated the area.","location":"near Conference Room 2","time":"around 10 AM","actions_taken":["Maintenance isolated the area"]}}"""
    private val slideTranscript = "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area."

    private fun ok(raw: String, tool: String = Tools.FILL, active: List<String>? = null): ParsedCall =
        (p.parse(raw, tool, active) as? ParseOutcome.Ok)?.call ?: fail("parse failed: $raw")

    @Test fun parse_slideExample() {
        val c = ok(slideJson)
        assertEquals("WATER", (c.fields["incident_type"] as JsonPrimitive).content)
        assertEquals("around 10 AM", (c.fields["occurred_at"] as JsonPrimitive).content)
        assertTrue("actions_taken" in c.fields)
    }

    @Test fun parse_fencesAndTrailingText() { assertTrue(p.parse("```json\n$slideJson\n```\nHope this helps", Tools.FILL) is ParseOutcome.Ok) }
    @Test fun parse_truncatedOutputIsAutoClosed() {
        val cut = slideJson.substring(0, slideJson.indexOf("\"time\"") + 12) // ends inside the time string
        val c = ok(cut); assertEquals("WATER", (c.fields["incident_type"] as JsonPrimitive).content)
    }
    @Test fun parse_truncatedDanglingKey() {
        val c = ok("""{"name":"fill_incident_form","parameters":{"incident_type":"FIRE","description":"Fire in bay 2","location":""")
        assertEquals("FIRE", (c.fields["incident_type"] as JsonPrimitive).content); assertFalse("location" in c.fields)
    }
    @Test fun parse_lenientQuotesLiteralsTrailingComma() {
        val c = ok("{'name': 'fill_incident_form', 'parameters': {'incident_type': 'WATER', 'description': 'pipe burst', 'near_miss': False, }}")
        assertEquals(false, (c.fields["near_miss"] as JsonPrimitive).content.toBoolean())
    }
    @Test fun parse_envelopeAliases() {
        assertTrue(p.parse("""{"function":{"name":"fill_incident_form","arguments":{"incident_type":"WATER","description":"leak"}}}""", Tools.FILL) is ParseOutcome.Ok)
        assertTrue(p.parse("""{"name":"update_incident_fields","parameters":{"set":{}}}""", Tools.FILL) is ParseOutcome.Fail)
        assertTrue(p.parse("no json here", Tools.FILL) is ParseOutcome.Fail)
    }
    @Test fun normalise_enumsSynonymsAndInvalid() {
        val c = ok("""{"name":"update_incident_fields","parameters":{"set":{"injuries":"nahi","leak_status":"stopped","severity":"extreme","fire_status":"put out"}}}""",
            Tools.UPDATE, listOf("WATER"))
        assertEquals("NO", (c.fields["injuries"] as JsonPrimitive).content)
        assertEquals("STOPPED", (c.fields["leak_status"] as JsonPrimitive).content)
        assertFalse("severity" in c.fields); assertFalse("fire_status" in c.fields) // invalid / category not chosen
    }
    @Test fun normalise_stringsJunkDropped() {
        val c = ok("""{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"leak","location":"N/A","title":"  x  y "}}""")
        assertFalse("location" in c.fields); assertEquals("x y", (c.fields["title"] as JsonPrimitive).content)
    }
    @Test fun details_onlyChosenCategories() {
        val c = ok("""{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"leak","details":{"leak_source":"pipe","fire_status":"ACTIVE"}}}""")
        assertTrue("leak_source" in c.fields); assertFalse("fire_status" in c.fields)
    }
    @Test fun ints_boolsAndLists() {
        val c = ok("""{"name":"fill_incident_form","parameters":{"incident_type":"MEDICAL","description":"hurt","injured_count":"two","near_miss":"haan","equipment":"press p-1"}}""")
        assertEquals("2", (c.fields["injured_count"] as JsonPrimitive).content)
        assertEquals("true", (c.fields["near_miss"] as JsonPrimitive).content)
        assertTrue(c.fields["equipment"] is JsonArray)
    }
    @Test fun update_unknownAndSkipped() {
        val c = ok("""{"name":"update_incident_fields","parameters":{"set":{},"unknown":["leak_source","bogus"],"skipped":true}}""", Tools.UPDATE, listOf("WATER"))
        assertEquals(listOf("leak_source"), c.unknown); assertTrue(c.skipped)
    }
    @Test fun propose() {
        val c = ok("""{"name":"propose_actions","parameters":{"actions":[{"sop":"water-01","text":"Isolate the valve"},{"sop":"X"}]}}""", Tools.PROPOSE)
        assertEquals(listOf(ProposedAction("WATER-01", "Isolate the valve")), c.actions)
    }

    // ---- merger ---------------------------------------------------------------------------

    private fun extract(): IncidentForm {
        val c = ok(slideJson)
        val f = Fx.merger.merge(IncidentForm(), c, FieldSource.LLM_EXTRACT, 0, slideTranscript, Fx.now).form
        return Fx.merger.applyTimeDefault(f, Fx.now)
    }

    @Test fun merge_slideExample_endToEndToValidator() {
        val f = extract()
        assertEquals("WATER", f.incidentType); assertEquals("A-1-CR2", f.locationId)
        val tz = Fx.zone
        val t = java.time.Instant.ofEpochMilli((f.value("occurred_at") as JsonPrimitive).content.toLong()).atZone(tz)
        assertEquals(10, t.hour)
        assertEquals(listOf("severity", "injuries", "leak_source", "leak_status"), Validator.validate(f, Fx.tx, Fx.now).missing)
    }

    @Test fun merge_patchRound1and2_completesForm() {
        var f = extract()
        val a = ok("""{"name":"update_incident_fields","parameters":{"lang":"en","set":{"injuries":"NO","severity":"LOW"},"unknown":[],"skipped":false}}""", Tools.UPDATE, f.types)
        f = Fx.merger.merge(f, a, FieldSource.LLM_PATCH, 1, "No one is hurt, it's minor.", Fx.now).form
        val b = ok("""{"name":"update_incident_fields","parameters":{"set":{"leak_source":"burst pipe under the sink","leak_status":"STOPPED"}}}""", Tools.UPDATE, f.types)
        f = Fx.merger.merge(f, b, FieldSource.LLM_PATCH, 2, "A pipe under the sink burst, it's stopped now.", Fx.now).form
        assertTrue(Validator.validate(f, Fx.tx, Fx.now).complete)
        assertEquals(FieldStatus.FILLED, f.state("leak_source")!!.status)
    }

    @Test fun merge_userEditNeverOverwritten() {
        var f = extract()
        f = Fx.merger.userEdit(f, "location", JsonPrimitive("Pantry"), Fx.now)
        val patch = ok("""{"name":"update_incident_fields","parameters":{"set":{"location":"Server Room"}}}""", Tools.UPDATE, f.types)
        val r = Fx.merger.merge(f, patch, FieldSource.LLM_PATCH, 3, "actually it was server room", Fx.now)
        assertEquals("Pantry", r.form.str("location")); assertEquals(FieldStatus.USER_EDITED, r.form.state("location")!!.status)
    }

    @Test fun merge_correctionOverwritesLlmValue() {
        val f = extract()
        val patch = ok("""{"name":"update_incident_fields","parameters":{"set":{"location":"Server Room"}}}""", Tools.UPDATE, f.types)
        val r = Fx.merger.merge(f, patch, FieldSource.LLM_PATCH, 3, "actually it was the server room", Fx.now)
        assertEquals("Server Room", r.form.str("location")); assertEquals("A-2-SRV", r.form.locationId)
    }

    @Test fun merge_unknownAnswer() {
        val f = extract()
        val patch = ok("""{"name":"update_incident_fields","parameters":{"set":{},"unknown":["leak_source"]}}""", Tools.UPDATE, f.types)
        val r = Fx.merger.merge(f, patch, FieldSource.LLM_PATCH, 3, "pata nahi", Fx.now)
        assertEquals(FieldStatus.UNKNOWN, r.form.state("leak_source")!!.status)
        assertFalse("leak_source" in Validator.validate(r.form, Fx.tx, Fx.now).missing)
    }

    @Test fun merge_skipLeavesMissing() {
        val f = extract()
        val patch = ok("""{"name":"update_incident_fields","parameters":{"set":{},"skipped":true}}""", Tools.UPDATE, f.types)
        val r = Fx.merger.merge(f, patch, FieldSource.LLM_PATCH, 3, "chhodo", Fx.now)
        assertTrue("leak_source" in Validator.validate(r.form, Fx.tx, Fx.now).missing)
    }

    @Test fun merge_timeDefaulted_whenNotSpoken() {
        val c = ok("""{"name":"fill_incident_form","parameters":{"incident_type":"WATER","description":"Water leak at pantry","location":"pantry"}}""")
        var f = Fx.merger.merge(IncidentForm(), c, FieldSource.LLM_EXTRACT, 0, "water leak at pantry", Fx.now).form
        f = Fx.merger.applyTimeDefault(f, Fx.now)
        assertEquals(FieldStatus.DEFAULTED, f.state("occurred_at")!!.status); assertTrue(f.occurredApprox)
        assertFalse("occurred_at" in Validator.validate(f, Fx.tx, Fx.now).missing)
    }

    @Test fun merge_ungroundedPersonDropped_ungroundedLocationInferred() {
        val c = ok("""{"name":"fill_incident_form","parameters":{"incident_type":"VEHICLE","description":"Forklift hit rack","location":"Warehouse Zeta","persons":[{"name":"Suresh","role":"INVOLVED"}]}}""")
        val f = Fx.merger.merge(IncidentForm(), c, FieldSource.LLM_EXTRACT, 0, "forklift ne rack ko thok diya bay 4 mein", Fx.now).form
        assertFalse("persons" in f.fields); assertEquals(FieldStatus.INFERRED, f.state("location")!!.status)
    }

    @Test fun merge_rules_injuriesAndSeverityFloor() {
        val c = ok("""{"name":"fill_incident_form","parameters":{"incident_type":"MEDICAL","description":"Worker fainted","injuries":"NO","severity":"LOW","persons":[{"name":"Anil","role":"AFFECTED","injured":true}],"details":{"patient_condition":"unconscious"}}}""")
        val f = Fx.merger.merge(IncidentForm(), c, FieldSource.LLM_EXTRACT, 0, "anil fainted he is unconscious", Fx.now).form
        assertEquals("YES", f.str("injuries")); assertEquals("CRITICAL", f.str("severity")); assertEquals(FieldSource.RULE, f.severitySource)
    }

    @Test fun markAsked_incrementsCounts() {
        val f = Fx.merger.markAsked(extract(), listOf("severity", "injuries"))
        assertEquals(1, f.state("severity")!!.askedCount)
        assertEquals(2, Fx.merger.markAsked(f, listOf("severity")).state("severity")!!.askedCount)
    }

    // ---- prompt builder --------------------------------------------------------------------

    private val pb = PromptBuilder(Fx.tx, Fx.templates)

    @Test fun prompt_extractFormat() {
        val s = pb.extract("hello world")
        assertTrue(s.startsWith("<|start_header_id|>system<|end_header_id|>\n\nYou are IncidentVoice"))
        assertTrue(s.endsWith("Transcript:\n<<<\nhello world\n>>><|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"))
        assertFalse(s.contains("<|begin_of_text|>")); assertFalse(s.contains("{{"))
        assertEquals(3, "<\\|start_header_id\\|>user".toRegex().findAll(s).count()) // 2 few-shot + 1 final user
    }
    @Test fun prompt_catalogueFromTaxonomy() {
        val c = pb.fieldCatalogue()
        assertTrue(c.contains("WATER: leak_source:text, leak_status(ONGOING|CONTROLLED|STOPPED|UNKNOWN)"))
        assertTrue(c.contains("EQUIPMENT: (also fill equipment) "))
    }
    @Test fun prompt_patchAndCompact() {
        val f = extract()
        val s = pb.patch(f, "Was anyone hurt?", listOf("injuries", "severity"), "no one")
        assertTrue(s.contains("Asked fields: injuries, severity")); assertTrue(s.contains("\"time\":\"around 10 AM\""))
        assertFalse(s.contains("{{"))
    }
    @Test fun prompt_warmup() { assertEquals("<|start_header_id|>user<|end_header_id|>\n\nSay OK.<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n", pb.warmup()) }
}
