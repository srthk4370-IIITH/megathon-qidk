package com.megathon.incidentvoice.core

import com.megathon.incidentvoice.core.config.ConfigLoader
import com.megathon.incidentvoice.core.dto.AsrResult
import com.megathon.incidentvoice.core.dto.EngineError
import com.megathon.incidentvoice.core.dto.EngineInfo
import com.megathon.incidentvoice.core.dto.EngineJson
import com.megathon.incidentvoice.core.dto.GenStats
import com.megathon.incidentvoice.core.llm.PromptBuilder
import com.megathon.incidentvoice.core.model.FieldSource
import com.megathon.incidentvoice.core.model.FieldState
import com.megathon.incidentvoice.core.model.FieldStatus
import com.megathon.incidentvoice.core.model.IncidentForm
import com.megathon.incidentvoice.core.recommend.SopRecommender
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RENDERING.md: golden byte comparison with `config/prompts/golden/` (skipped when absent) + structure checks. */
class PromptBuilderTest {
    private val pb = T.prompts
    private val goldenDir = File(T.configDir, "prompts/golden")

    /** Builds the form described by a golden `*.input.json` (flat fields; occurred_at.value = spoken phrase). */
    private fun formFrom(o: JsonObject): IncidentForm {
        val reported = OffsetDateTime.parse((o["reported_at"] as JsonPrimitive).content).toInstant()
        var f = IncidentForm(reportedAt = reported, lang = (o["lang"] as? JsonPrimitive)?.content ?: "en")
        (o["title"] as? JsonPrimitive)?.let { f = f.with(FieldState("title", it, FieldStatus.FILLED, FieldSource.LLM_EXTRACT, 0)) }
        for ((k, v) in o["fields"] as JsonObject) {
            v as JsonObject
            val status = FieldStatus.valueOf((v["status"] as JsonPrimitive).content)
            val source = (v["source"] as? JsonPrimitive)?.let { FieldSource.valueOf(it.content) } ?: FieldSource.LLM_EXTRACT
            val value = v["value"]
            if (k == "occurred_at") {
                val expr = (value as JsonPrimitive).content
                val r = T.timeResolver.resolve(expr, reported)
                f = f.with(FieldState(k, JsonPrimitive(T.merger.iso(r.instant!!)), status, source, 0))
                    .copy(timeExpression = expr, occurredAtApprox = r.approximate)
            } else {
                f = f.with(FieldState(k, if (status == FieldStatus.UNKNOWN) null else value, status, source, 0))
            }
        }
        return f
    }

    private fun str(o: JsonObject, k: String) = (o[k] as JsonPrimitive).content

    private fun render(input: JsonObject): String = when (str(input, "task")) {
        "extract" -> pb.extract(str(input, "transcript"), (input["variant"] as? JsonPrimitive)?.content ?: "base")
        "patch" -> pb.patch(
            formFrom(input["form"] as JsonObject),
            (input["asked_fields"] as JsonArray).map { (it as JsonPrimitive).content },
            str(input, "question"),
            str(input, "answer"),
        )
        "recommend" -> {
            val form = formFrom(input["form"] as JsonObject)
            val cands = SopRecommender(T.sop).candidates(form)
            (input["candidate_ids"] as? JsonArray)?.let { ids ->
                assertEquals(ids.map { (it as JsonPrimitive).content }, cands.map { it.id }, "SOP P0 candidate list differs from Python twin")
            }
            pb.recommend(form, cands)
        }
        "warmup" -> pb.warmup()
        else -> error("unknown task")
    }

    /** Placeholder-level comparison gives a readable diff before the byte comparison. */
    private fun checkPlaceholders(input: JsonObject) {
        val ph = input["placeholders"] as? JsonObject ?: return
        val form = (input["form"] as? JsonObject)?.let { formFrom(it) }
        val asked = (input["asked_fields"] as? JsonArray)?.map { (it as JsonPrimitive).content }
        for ((k, v) in ph) {
            val expected = (v as JsonPrimitive).content
            val actual = when (k) {
                "category_list" -> pb.categoryList()
                "severity_rubric" -> pb.severityRubric()
                "field_catalogue" -> pb.fieldCatalogue()
                "form_compact_json" -> pb.formCompactJson(form!!)
                "asked_field_specs" -> pb.askedFieldSpecs(asked!!)
                "asked_fields" -> pb.askedFields(asked!!)
                "actions_taken_list" -> com.megathon.incidentvoice.core.llm.CompactJson.write(form!!.value("actions_taken") ?: JsonArray(emptyList()))
                "sop_candidates" -> SopRecommender(T.sop).candidates(form!!).joinToString("\n") { "${it.id}: ${it.text}" }
                else -> continue
            }
            assertEquals(expected, actual, "placeholder {{$k}}")
        }
    }

    @TestFactory
    fun golden(): List<DynamicTest> {
        val inputs = goldenDir.listFiles { f -> f.name.endsWith(".input.json") }?.sortedBy { it.name }.orEmpty()
        if (inputs.isEmpty()) {
            return listOf(DynamicTest.dynamicTest("golden files absent (skipped)") { assumeTrue(false, "no golden files") })
        }
        return inputs.map { inFile ->
            val name = inFile.name.removeSuffix(".input.json")
            DynamicTest.dynamicTest(name) {
                val txt = File(goldenDir, "$name.txt")
                assumeTrue(txt.isFile, "missing $name.txt")
                val input = ConfigLoader.parseObject(inFile.readText(Charsets.UTF_8))
                checkPlaceholders(input)
                val expected = txt.readBytes().toString(Charsets.UTF_8)
                val actual = render(input)
                if (expected != actual) {
                    val i = expected.zip(actual).indexOfFirst { (a, b) -> a != b }.let { if (it < 0) minOf(expected.length, actual.length) else it }
                    error("golden $name differs at char $i (len exp=${expected.length} act=${actual.length}):\nexp: …${expected.substring(maxOf(0, i - 60), minOf(expected.length, i + 60))}\nact: …${actual.substring(maxOf(0, i - 60), minOf(actual.length, i + 60))}")
                }
            }
        }
    }

    @Test fun goldenFilesPresent() {
        // The golden set exists in the repo now; this guards against silently skipping.
        assertTrue(File(goldenDir, "extract_slide.txt").isFile, "golden files expected in $goldenDir")
    }

    @Test fun structure() {
        val p = pb.extract(T.SLIDE_TRANSCRIPT)
        assertFalse(p.contains("<|begin_of_text|>"))
        assertTrue(p.startsWith("<|start_header_id|>system<|end_header_id|>\n\n"))
        assertTrue(p.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
        assertFalse(p.contains("<|eot_id|>\n"), "no newline between eot and next header")
        assertFalse(p.contains("{{"), "all placeholders substituted")
        assertTrue(p.contains("fire_status(ACTIVE|CONTAINED|EXTINGUISHED|SMOKE_ONLY|UNKNOWN)"))
        assertTrue(p.contains("EQUIPMENT (also fill equipment): "))
        // few-shots only in base extract
        val shots = Regex("<\\|start_header_id\\|>assistant<\\|end_header_id\\|>").findAll(p).count()
        assertEquals(pb.fewShots().size + 1, shots)
        val ft = pb.extract(T.SLIDE_TRANSCRIPT, PromptBuilder.VARIANT_FT)
        assertEquals(1, Regex("<\\|start_header_id\\|>assistant").findAll(ft).count())
        val f = T.form("incident_type" to "WATER", "description" to "Leak")
        for (q in listOf(pb.patch(f, listOf("injuries", "severity"), "Was anyone hurt?", "no"), pb.recommend(f, emptyList()), pb.repair("fill_incident_form", "{bad"))) {
            assertEquals(1, Regex("<\\|start_header_id\\|>assistant").findAll(q).count())
            assertTrue(q.endsWith(PromptBuilder.header("assistant")))
            assertFalse(q.contains("<|begin_of_text|>"))
        }
        assertEquals("<|start_header_id|>user<|end_header_id|>\n\nSay OK.<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n", pb.warmup())
    }

    @Test fun formCompactJsonRules() {
        val f = T.form(
            "incident_type" to "WATER", "description" to "Leak", "injuries" to null, "location" to null,
            "occurred_at" to FieldState("occurred_at", JsonPrimitive("2026-10-10T10:07:12+05:30"), FieldStatus.DEFAULTED, FieldSource.SYSTEM, 0),
            "leak_status" to "ONGOING", "title" to "x",
        )
        assertEquals("""{"incident_type":"WATER","description":"Leak","time":"now (assumed)","injuries":"UNKNOWN","details":{"leak_status":"ONGOING"}}""", pb.formCompactJson(f))
    }

    @Test fun unknownPlaceholderIsError() {
        assertFailsWith<IllegalArgumentException> { PromptBuilder.substitute("a {{nope}} b", emptyMap()) }
        assertEquals("x {{y}}", PromptBuilder.substitute("x {{t}}", mapOf("t" to "{{y}}")))
    }

    @Test fun engineDtosRoundTrip() {
        val info = EngineJson.decodeFromString<EngineInfo>(
            """{"plan":"A","qairt":"2.50.x","soc":"SM8750P","htp_arch":"v79","asr":{"model":"whisper_small","loaded":true,"load_ms":820},"llm":{"model":"llama_v3_2_3b_instruct","variant":"stock","prompt_variant":"base","loaded":true,"load_ms":2950,"context":4096}}""",
        )
        assertEquals("v79", info.htpArch)
        assertEquals(2950, info.llm!!.loadMs)
        val asr = EngineJson.decodeFromString<AsrResult>(
            """{"text":"hi","lang":"en","chunks":[{"start_ms":0,"end_ms":19800,"text":"hi","lang":"en"}],"timings":{"mel_ms":38,"enc_ms":71,"dec_ms":512,"tokens":61,"total_ms":640}}""",
        )
        assertEquals(19800, asr.chunks[0].endMs)
        assertEquals(640, asr.timings.totalMs)
        val gs = EngineJson.decodeFromString<GenStats>(
            """{"task":"EXTRACT","tokens_in":1032,"tokens_out":97,"ttft_ms":1410,"prefill_tps":731.5,"decode_tps":27.9,"wall_ms":4890,"aborted":false,"stop":"EOS","extra":1}""",
        )
        assertEquals(27.9, gs.decodeTps)
        val err = EngineError("IV-E602", "Could not create context", true, "log")
        val s = EngineJson.encodeToString(EngineError.serializer(), err)
        assertTrue(s.contains("\"native_log_tail\":\"log\""))
        assertEquals(err, EngineJson.decodeFromString<EngineError>(s))
    }
}
