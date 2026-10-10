package com.megathon.incidentvoice.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Byte-exact parity with the ML branch's prompts (config/prompts/RENDERING.md). The golden files are produced by
 * `ml/common/make_golden.py`; the device model was evaluated on exactly these bytes.
 */
class PromptGoldenTest {
    private val golden = File(Fx.dir, "prompts/golden")
    private fun input(name: String) = Json.parseToJsonElement(File(golden, "$name.input.json").readText()).jsonObject
    private fun expected(name: String) = File(golden, "$name.txt").readText()

    /** Golden forms are flat `{key: {value, status}}`; occurred_at.value is the spoken phrase. */
    private fun form(o: JsonObject): IncidentForm {
        var f = IncidentForm(lang = o["lang"]!!.jsonPrimitive.content)
        for ((k, v) in o["fields"]!!.jsonObject) {
            val value = v.jsonObject["value"]!!
            val status = FieldStatus.valueOf(v.jsonObject["status"]!!.jsonPrimitive.content)
            f = if (k == "occurred_at") f.with(FieldState(k, JsonPrimitive(Fx.now.toEpochMilli()), status)).copy(timeExpression = value.jsonPrimitive.content)
            else f.with(FieldState(k, value, status))
        }
        return f
    }

    @Test fun extract_base() {
        val i = input("extract_slide")
        assertEquals(expected("extract_slide"), PromptBuilder(Fx.tx, Fx.templates).extract(i["transcript"]!!.jsonPrimitive.content))
    }
    @Test fun extract_ft() {
        val i = input("extract_slide_ft")
        assertEquals(expected("extract_slide_ft"), PromptBuilder(Fx.tx, Fx.templates, "ft").extract(i["transcript"]!!.jsonPrimitive.content))
    }
    @Test fun patch() {
        val i = input("patch_slide")
        val got = PromptBuilder(Fx.tx, Fx.templates).patch(form(i["form"]!!.jsonObject), i["question"]!!.jsonPrimitive.content,
            i["asked_fields"]!!.jsonArray.map { it.jsonPrimitive.content }, i["answer"]!!.jsonPrimitive.content)
        assertEquals(expected("patch_slide"), got)
    }
    @Test fun recommend_andSopCandidateFilterMatchesMl() {
        val i = input("recommend_slide")
        val f = form(i["form"]!!.jsonObject)
        val cands = SopSelector(Fx.sop).candidates(f)
        assertEquals(i["candidate_ids"]!!.jsonArray.map { it.jsonPrimitive.content }, cands.map { it.id })
        assertEquals(expected("recommend_slide"), PromptBuilder(Fx.tx, Fx.templates).recommend(f, cands))
    }
    @Test fun warmup() = assertEquals(expected("warmup"), PromptBuilder(Fx.tx, Fx.templates).warmup())

    @Test fun compactForm_unknownEnumAndDefaultedTime() {
        val f = Fx.form("incident_type" to "WATER", "injuries" to null, "leak_source" to null)
            .with(FieldState("occurred_at", JsonPrimitive(Fx.now.toEpochMilli()), FieldStatus.DEFAULTED))
        assertEquals("""{"incident_type":"WATER","time":"now (assumed)","injuries":"UNKNOWN"}""", PromptBuilder(Fx.tx, Fx.templates).compactJson(f))
    }
}
