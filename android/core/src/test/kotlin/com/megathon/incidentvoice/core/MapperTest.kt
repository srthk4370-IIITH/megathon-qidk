package com.megathon.incidentvoice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapperTest {
    private fun filled(): IncidentForm {
        val c = (Fx.parser.parse("""{"name":"fill_incident_form","parameters":{"lang":"hinglish","incident_type":"MEDICAL","secondary_types":["WATER"],"title":"Fall","description":"Anil slipped on wet floor","location":"near pantry","time":"around 10 AM","injuries":"YES","severity":"MEDIUM","persons":[{"name":"Anil","role":"AFFECTED","injured":true,"injury":"sprained ankle"}],"witnesses":[{"name":"Rina","contact":"x123"}],"equipment":["mop p-2"],"actions_taken":["First aid given"],"near_miss":false,"details":{"patient_condition":"conscious stable","leak_source":"pantry tap"},"other_incidents":["door stuck"]}}""", Tools.FILL) as ParseOutcome.Ok).call
        var f = Fx.merger.merge(IncidentForm(), c, FieldSource.LLM_EXTRACT, 0, "anil slipped on wet floor near pantry around 10 AM, rina saw. pantry tap mop p-2 first aid door stuck", Fx.now).form
        f = Fx.merger.markAsked(f, listOf("ambulance_called"))
        return Fx.merger.userUnknown(f, "first_aid_given")
    }
    private val meta = IncidentMeta("id-1", createdAt = 1000, reportedAt = 2000, reporter = Reporter("Raj", "E1"))

    @Test fun roundTrip_preservesValuesStatusAndMeta() {
        val f = filled()
        val back = FormMapper.toForm(FormMapper.toStored(f, meta, 3000))
        for ((k, st) in f.fields) {
            val b = back.state(k)!!
            assertEquals(st.value, b.value, k); assertEquals(st.status, b.status, k); assertEquals(st.askedCount, b.askedCount, k)
        }
        assertEquals(f.lang, back.lang); assertEquals(f.timeExpression, back.timeExpression); assertEquals(f.otherIncidents, back.otherIncidents)
        assertEquals(f.locationId, back.locationId)
    }
    @Test fun columns() {
        val st = FormMapper.toStored(filled(), meta, 3000)
        assertEquals("MEDICAL", st.row.incidentType); assertEquals(listOf("WATER"), st.secondaryTypes)
        assertEquals(listOf("AFFECTED", "WITNESS"), st.persons.map { it.role }); assertTrue(st.row.detailsJson.contains("leak_source"))
        assertEquals("MEDIUM", st.row.severity); assertEquals("DRAFT", st.row.status); assertEquals("Raj", st.row.reporterName)
        assertTrue(st.row.fieldMetaJson.contains("ambulance_called"))
    }
    @Test fun searchQuery() {
        assertEquals("\"water\"* \"conf\"*", SearchQueryBuilder.build("water conf")); assertNull(SearchQueryBuilder.build("a ! ?"))
        assertEquals("\"aag\"*", SearchQueryBuilder.build("aag!"))
    }
    @Test fun searchBodyContainsCues() {
        val b = SearchBody.build(filled(), meta.copy(refNo = "INC-1"), Fx.tx, "Pantry · Block A", listOf("turn text"), listOf("rec"))
        assertTrue(b.contains("INC-1") && b.contains("Pantry") && b.contains("turn text") && b.contains("Anil") && b.contains("pantry tap"))
    }
}
