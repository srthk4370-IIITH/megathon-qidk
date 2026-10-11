package com.megathon.incidentvoice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceTest {
    private fun fill(body: String) = (Fx.parser.parse("""{"name":"fill_incident_form","parameters":{$body}}""", Tools.FILL) as ParseOutcome.Ok).call
    private fun upd(set: String, f: IncidentForm) = (Fx.parser.parse("""{"name":"update_incident_fields","parameters":{"set":{$set}}}""", Tools.UPDATE, f.types) as ParseOutcome.Ok).call

    private fun extract(transcript: String, body: String): IncidentForm {
        val f = Fx.merger.merge(IncidentForm(), fill(body), FieldSource.LLM_EXTRACT, 0, transcript, Fx.now).form
        return Evidence.attach(Fx.merger.applyTimeDefault(f, Fx.now), listOf(transcript), Fx.tx, Fx.now, Fx.times)
    }

    /** The highlighted words of a field, in order. */
    private fun IncidentForm.words(key: String, turns: List<String>) = evidence[key].orEmpty().map { turns[it.turn].substring(it.start, it.end) }

    @Test fun english_everyShortFieldPointsToItsWords() {
        val t = "There was a water leak near Conference Room 2 around 10 AM. Ramesh slipped and hurt his ankle, Suresh saw it. The pump P-101 was switched off and maintenance has already isolated the area."
        val f = extract(t, """"incident_type":"WATER","description":"A water leak near Conference Room 2.","location":"near Conference Room 2","time":"around 10 AM","injuries":"YES",
            "persons":[{"name":"Ramesh","role":"AFFECTED","injured":true,"injury":"ankle injury"}],"witnesses":[{"name":"Suresh"}],"equipment":["P-101"],"actions_taken":["Maintenance isolated the area"]""")
        val turns = listOf(t)
        assertEquals(listOf("water", "leak"), f.words("incident_type", turns))
        assertEquals(listOf("near Conference Room 2"), f.words("location", turns))
        assertEquals(listOf("around 10 AM"), f.words("occurred_at", turns))
        assertEquals(listOf("hurt"), f.words("injuries", turns))
        assertEquals(listOf("Ramesh"), f.words("persons", turns))
        assertEquals(listOf("Suresh"), f.words("witnesses", turns))
        assertEquals(listOf("P-101"), f.words("equipment", turns))
        assertEquals(listOf("maintenance has already isolated the area"), f.words("actions_taken", turns))
        assertTrue(Evidence.FIELDS.none { Evidence.needsReview(f, it) })
        // description is never highlighted
        assertFalse("description" in f.evidence)
    }

    @Test fun hinglish_ruleResolvedTimePointsToThePhraseItCameFrom() {
        // the model answered in English; the words that were actually said are found through the lexicon and the time rules
        val t = "Bay 4 mein paani bhar gaya hai aadha ghanta pehle, kisi ko chot nahi lagi"
        val f = extract(t, """"lang":"hinglish","incident_type":"WATER","description":"Water has flooded Bay 4.","location":"Bay 4","time":"half an hour ago","injuries":"NO"""")
        val turns = listOf(t)
        assertEquals(listOf("paani", "bhar"), f.words("incident_type", turns))
        assertEquals(listOf("Bay 4"), f.words("location", turns))
        assertEquals(listOf("aadha ghanta pehle"), f.words("occurred_at", turns))
        assertEquals(listOf("kisi ko chot nahi lagi"), f.words("injuries", turns))
        assertEquals(Fx.now.minusSeconds(30 * 60).toEpochMilli(), (f.value("occurred_at") as kotlinx.serialization.json.JsonPrimitive).content.toLong())
    }

    @Test fun selfCorrection_highlightsTheCorrectedTimeOnly() {
        val t = "The compressor in the compressor room tripped at 10, no, 11 this morning"
        for (phrase in listOf("at 11", "11 am", "11 this morning")) {
            val f = extract(t, """"incident_type":"EQUIPMENT","description":"The compressor tripped.","location":"Compressor Room","time":"$phrase"""")
            val words = f.words("occurred_at", listOf(t))
            assertTrue(words.isNotEmpty() && words.all { "11" in it && "10" !in it }, "$phrase -> $words")
        }
    }

    @Test fun noEvidence_fieldNeedsReview() {
        val t = "There is a water leak in the pantry"
        // a location the reporter never said, and a time that was assumed
        val f = extract(t, """"incident_type":"WATER","description":"A water leak in the pantry.","location":"Server Room","equipment":["chiller"]""")
        assertTrue(Evidence.needsReview(f, "location"))
        assertTrue(Evidence.needsReview(f, "equipment"))
        assertEquals(FieldStatus.DEFAULTED, f.state("occurred_at")!!.status)
        assertTrue(Evidence.needsReview(f, "occurred_at"))
        assertFalse(Evidence.needsReview(f, "incident_type"))
        // nothing to review where there is no value, or where the reporter typed the value
        assertFalse(Evidence.needsReview(f, "witnesses"))
        val edited = Fx.merger.userEdit(f, "location", Fx.s("Pantry"), Fx.now).let { it.copy(evidence = it.evidence - "location") }
        assertFalse(Evidence.needsReview(edited, "location"))
        assertFalse(Evidence.needsReview(f, "description"))
    }

    @Test fun followUpAnswer_pointsToTheAnswerNotTheNarration() {
        val t0 = "Water is leaking from the ceiling, people are worried somebody may get hurt"
        var f = extract(t0, """"incident_type":"WATER","description":"Water is leaking from the ceiling."""")
        assertTrue("location" !in f.evidence && "injuries" !in f.evidence)
        // follow-up 1: the place is said in the answer
        val t1 = "It is near conference room 2"
        var m = Fx.merger.merge(f, upd(""""location":"Conference Room 2"""", f), FieldSource.LLM_PATCH, 1, "$t0 $t1", Fx.now)
        f = Evidence.attach(m.form, listOf(t0, t1), Fx.tx, Fx.now, Fx.times, changed = m.changed, answerTurn = 1)
        assertEquals(listOf(EvidenceSpan(1, 11, 28)), f.evidence["location"])
        assertEquals("conference room 2", t1.substring(11, 28))
        // follow-up 2: a bare "No." carries the value as a whole
        val t2 = "No."
        m = Fx.merger.merge(f, upd(""""injuries":"NO"""", f), FieldSource.LLM_PATCH, 2, "$t0 $t1 $t2", Fx.now)
        val turns = listOf(t0, t1, t2)
        f = Evidence.attach(m.form, turns, Fx.tx, Fx.now, Fx.times, changed = m.changed, answerTurn = 2)
        assertEquals(listOf("No."), f.words("injuries", turns))
        // the earlier fields keep what they had
        assertEquals(listOf(EvidenceSpan(1, 11, 28)), f.evidence["location"])
        assertEquals(listOf("Water", "leaking"), f.words("incident_type", turns))
    }

    @Test fun repeatedValue_everyMentionIsHighlighted() {
        val t = "Smoke is coming from Bay 4. I repeat, the fire is in bay 4, near the forklift. The forklift is damaged."
        val f = extract(t, """"incident_type":"FIRE","description":"Smoke and fire in Bay 4.","location":"Bay 4","equipment":["forklift"]""")
        val turns = listOf(t)
        assertEquals(listOf("Bay 4", "bay 4"), f.words("location", turns))
        assertEquals(listOf("forklift", "forklift"), f.words("equipment", turns))
        assertEquals(listOf("Smoke", "fire"), f.words("incident_type", turns))
    }

    @Test fun asHeardByTheRecogniser_abbreviationsAndNumberWords() {
        // "conf room two" for Conference Room 2; a different room number is not evidence
        assertEquals("conf room two", "leak in conf room two today".let { t -> Grounding.locate("Conference Room 2", t).ranges.single().let { t.substring(it) } })
        assertTrue(Grounding.locate("Conference Room 2", "we were in conference room 1").ranges.isEmpty())
        assertEquals("Absorbent pads were placed", "Absorbent pads were placed near the drain".let { t -> Grounding.locate("Absorbent pads placed", t).ranges.single().let { t.substring(it) } })
    }

    @Test fun injuriesYes_isNotSupportedByADenial() {
        val t = "Nobody was hurt at first, but later Ravi fractured his wrist"
        val f = extract(t, """"incident_type":"MEDICAL","description":"Ravi fractured his wrist.","injuries":"YES","persons":[{"name":"Ravi","role":"AFFECTED","injured":true,"injury":"fractured wrist"}]""")
        assertEquals(listOf("fractured"), f.words("injuries", listOf(t)))
    }

    @Test fun spansSurviveStorage() {
        val t = "There was a water leak near Conference Room 2 around 10 AM."
        val f = extract(t, """"incident_type":"WATER","description":"A water leak.","location":"near Conference Room 2","time":"around 10 AM"""")
        val meta = IncidentMeta("id-1", createdAt = 1, reportedAt = 1)
        val stored = FormMapper.toStored(f, meta, 2)
        assertEquals(f.evidence, FormMapper.toForm(stored).evidence)
        // a report saved before evidence existed loads with none
        assertTrue(FormMapper.toForm(stored.copy(row = stored.row.copy(evidenceJson = null))).evidence.isEmpty())
    }
}
