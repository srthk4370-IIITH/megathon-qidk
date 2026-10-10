package com.megathon.incidentvoice.core

import com.megathon.incidentvoice.domain.engine.GenEvent
import com.megathon.incidentvoice.domain.engine.GenRequest
import com.megathon.incidentvoice.domain.engine.LlmTask
import com.megathon.incidentvoice.domain.engine.MockInferenceEngine
import com.megathon.incidentvoice.domain.engine.SamplerName
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How good is the rule-based stand-in LLM (used on the board until the Llama bundle arrives, and as a safety net)?
 * Scored on the ML branch's eval set through the real prompts → parser → merger, exactly like the app does.
 */
class MockLlmEvalTest {
    private val file = File(Fx.dir.parentFile, "eval/text_cases.jsonl")
    private val cases by lazy { file.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject } }
    private val engine = MockInferenceEngine(Fx.tx, asrDelayMs = 0, tokensPerSecond = 0.0, registry = Fx.registry)
    private val pb = PromptBuilder(Fx.tx, Fx.templates)

    private fun gen(task: LlmTask, prompt: String): String = runBlocking {
        engine.generate(GenRequest(task, prompt, SamplerName.GREEDY, 320)).toList().filterIsInstance<GenEvent.Token>().joinToString("") { it.piece }
    }

    @Test fun scoreOnEvalSet() {
        if (!file.exists()) return
        var n = 0; var typeOk = 0; var enN = 0; var enTypeOk = 0; var injN = 0; var injOk = 0; var locN = 0; var locOk = 0; var timeN = 0; var timeOk = 0
        var pN = 0; var pOk = 0; var pFieldN = 0; var pFieldOk = 0
        val typeErr = ArrayList<String>(); val patchErr = ArrayList<String>()
        for (c in cases) {
            val id = c["id"]!!.jsonPrimitive.content; val lang = c["lang"]!!.jsonPrimitive.content
            val now = OffsetDateTime.parse(c["now"]!!.jsonPrimitive.content).toInstant()
            val tr = c["transcript"]!!.jsonPrimitive.content; val gold = c["gold"]!!.jsonObject
            val call = (Fx.parser.parse(gen(LlmTask.EXTRACT, pb.extract(tr)), Tools.FILL) as? ParseOutcome.Ok)?.call
            n++; if (lang == "en") enN++
            val gotType = (call?.fields?.get("incident_type") as? JsonPrimitive)?.content
            if (gotType == gold["incident_type"]!!.jsonPrimitive.content) { typeOk++; if (lang == "en") enTypeOk++ } else if (lang == "en") typeErr += "$id want ${gold["incident_type"]} got $gotType :: ${tr.take(110)}"
            gold["injuries"]?.let { g -> injN++; if ((call?.fields?.get("injuries") as? JsonPrimitive)?.content == g.jsonPrimitive.content) injOk++ }
            if (lang == "en") {
                gold["location"]?.let { g ->
                    locN++
                    val got = (call?.fields?.get("location") as? JsonPrimitive)?.content.orEmpty().lowercase()
                    val want = g.jsonPrimitive.content.lowercase().removePrefix("near ").removePrefix("behind ").removePrefix("in ").removePrefix("at ").removePrefix("the ")
                    if (got.contains(want) || (got.length > 3 && want.contains(got.removePrefix("near ").removePrefix("the ")))) locOk++
                }
                gold["time"]?.let { g ->
                    timeN++
                    val got = (call?.fields?.get("occurred_at") as? JsonPrimitive)?.content
                    val a = Fx.times.resolve(got, now); val b = Fx.times.resolve(g.jsonPrimitive.content, now)
                    if (!a.defaulted && kotlin.math.abs(a.instant.toEpochMilli() - b.instant.toEpochMilli()) <= 60_000) timeOk++
                }
            }
            // follow-up: gold form + asked fields + answer → patch
            val fu = c["followup"]?.takeIf { it !is JsonNull }?.jsonObject ?: continue
            if (lang != "en") continue
            val goldCall = (Fx.parser.parse(JsonObject(mapOf("name" to JsonPrimitive(Tools.FILL), "parameters" to gold)).toString(), Tools.FILL) as ParseOutcome.Ok).call
            val form = Fx.merger.applyTimeDefault(Fx.merger.merge(IncidentForm(), goldCall, FieldSource.LLM_EXTRACT, 0, tr, now).form, now)
            val asked = fu["asked_fields"]!!.jsonArray.map { it.jsonPrimitive.content }
            val ans = fu["answer"]!!.jsonPrimitive.content
            val out = gen(LlmTask.PATCH, pb.patch(form, fu["question"]!!.jsonPrimitive.content, asked, ans))
            val pc = (Fx.parser.parse(out, Tools.UPDATE, form.types) as? ParseOutcome.Ok)?.call
            val gp = fu["gold_patch"]!!.jsonObject
            val gset = gp["set"]?.jsonObject ?: JsonObject(emptyMap()); val gunk = gp["unknown"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            pN++; var all = true
            for ((k, v) in gset) {
                val key = Fx.tx.field(k)?.key ?: k; val def = Fx.tx.field(k)
                pFieldN++
                val got = pc?.fields?.get(key)
                val ok = if (def?.type == "enum") (got as? JsonPrimitive)?.content == v.jsonPrimitive.content else got != null
                if (ok) pFieldOk++ else { all = false; patchErr += "$id $k want $v got $got :: Q=${asked} A=$ans" }
            }
            for (k in gunk) { pFieldN++; if (pc?.unknown?.contains(Fx.tx.field(k)?.key ?: k) == true) pFieldOk++ else { all = false; patchErr += "$id unknown $k missing :: A=$ans" } }
            if (all) pOk++
        }
        fun pct(a: Int, b: Int) = if (b == 0) "n/a" else "%.0f%% (%d/%d)".format(100.0 * a / b, a, b)
        println("MOCK-LLM EVAL  type all=${pct(typeOk, n)}  type EN=${pct(enTypeOk, enN)}  injuries=${pct(injOk, injN)}  location EN=${pct(locOk, locN)}  time EN=${pct(timeOk, timeN)}  patch cases EN=${pct(pOk, pN)}  patch fields EN=${pct(pFieldOk, pFieldN)}")
        typeErr.take(40).forEach { println("  TYPE  $it") }
        patchErr.take(60).forEach { println("  PATCH $it") }
        assertTrue(n > 0)
        // Floors, not targets (the rules were tuned on this set, so real-world accuracy is lower).
        assertTrue(enTypeOk >= enN * 0.9, "type EN ${pct(enTypeOk, enN)}"); assertTrue(injOk >= injN * 0.85, "injuries ${pct(injOk, injN)}")
        assertTrue(pFieldOk >= pFieldN * 0.9, "patch ${pct(pFieldOk, pFieldN)}")
    }

    private val rules = com.megathon.incidentvoice.domain.engine.RuleBasedLlm(Fx.tx, Fx.registry)
    private fun params(o: JsonObject) = o["parameters"]!!.jsonObject
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.content

    /** The two things said to the board in the first live session (2026-10-11). */
    @Test fun liveSession_forkliftIsAVehicleIncident_notMedical() {
        val p = params(rules.extract("A forklift hit the rack in Bay 4 around half an hour ago, nobody was hurt."))
        kotlin.test.assertEquals("VEHICLE", str(p, "incident_type")); kotlin.test.assertEquals("NO", str(p, "injuries"))
        kotlin.test.assertEquals("Bay 4", str(p, "location")); kotlin.test.assertEquals("around half an hour ago", str(p, "time"))
        kotlin.test.assertEquals("VEHICLE_OBJECT", str(p["details"]!!.jsonObject, "collision_type"))
    }
    @Test fun liveSession_bareYesAnswersAreUnderstood() {
        fun set(asked: List<String>, a: String) = params(rules.patch(asked, a))["set"]!!.jsonObject
        kotlin.test.assertEquals("CONSCIOUS_STABLE", str(set(listOf("patient_condition"), "Yes."), "patient_condition"))
        kotlin.test.assertEquals("CONSCIOUS_STABLE", str(set(listOf("patient_condition"), "Yes, it is conscious and breathing."), "patient_condition"))
        kotlin.test.assertEquals("UNCONSCIOUS", str(set(listOf("patient_condition"), "No, he is unconscious"), "patient_condition"))
        val both = set(listOf("first_aid_given", "ambulance_called"), "Yes, the ambulance has been caught.")
        kotlin.test.assertEquals("YES", str(both, "ambulance_called"))
        kotlin.test.assertEquals("STOPPED", str(set(listOf("leak_source", "leak_status"), "A pipe under the sink burst, it's stopped now."), "leak_status"))
        kotlin.test.assertEquals("NO", str(set(listOf("injuries", "severity"), "No one is hurt, it's minor."), "injuries"))
        kotlin.test.assertEquals("LOW", str(set(listOf("injuries", "severity"), "No one is hurt, it's minor."), "severity"))
    }
    /** Third live utterance, as the recogniser wrote it. */
    @Test fun liveSession_misheardForkliftStillAVehicle_andNobodyInDangerIsNotCritical() {
        val p = params(rules.extract("A fourth lift hit a rack in Bed 4 about half an hour ago, nobody was hurt."))
        kotlin.test.assertEquals("VEHICLE", str(p, "incident_type")); kotlin.test.assertEquals("NO", str(p, "injuries"))
        fun sev(a: String) = str(params(rules.patch(listOf("severity"), a))["set"]!!.jsonObject, "severity")
        kotlin.test.assertEquals("MEDIUM", sev("No, nobody is in danger.")); kotlin.test.assertEquals("CRITICAL", sev("Yes, people are in danger"))
        kotlin.test.assertEquals("LOW", sev("It's minor, nobody is in danger")); kotlin.test.assertEquals("HIGH", sev("it is quite serious"))
    }
    @Test fun slideSentence() {
        val p = params(rules.extract("There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area."))
        kotlin.test.assertEquals("WATER", str(p, "incident_type")); kotlin.test.assertEquals("near Conference Room 2", str(p, "location")); kotlin.test.assertEquals("around 10 am", str(p, "time"))
    }
}
