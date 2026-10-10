package com.megathon.incidentvoice.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-check against the ML branch's evaluation set (`eval/text_cases.jsonl`, 150 cases with gold tool calls):
 * a correct model output must survive the app's parser → merger → time resolver → validator untouched, and the
 * app must ask the same follow-up question the eval expects.
 */
class EvalCasesTest {
    private val file = File(Fx.dir.parentFile, "eval/text_cases.jsonl")
    private val cases by lazy { file.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject } }
    private fun wrap(name: String, params: JsonObject) = JsonObject(mapOf("name" to JsonPrimitive(name), "parameters" to params)).toString()

    private class Run(val form: IncidentForm, val now: Instant, val problems: List<String>)

    private fun extract(c: JsonObject): Run {
        val id = c["id"]!!.jsonPrimitive.content
        val now = OffsetDateTime.parse(c["now"]!!.jsonPrimitive.content).toInstant()
        val gold = c["gold"]!!.jsonObject
        val problems = ArrayList<String>()
        val out = Fx.parser.parse(wrap(Tools.FILL, gold), Tools.FILL)
        val call = (out as? ParseOutcome.Ok)?.call ?: return Run(IncidentForm(), now, listOf("$id: parse failed $out"))
        if (call.dropped.isNotEmpty()) problems += "$id: dropped ${call.dropped}"
        val m = Fx.merger.merge(IncidentForm(), call, FieldSource.LLM_EXTRACT, 0, c["transcript"]!!.jsonPrimitive.content, now)
        var f = m.form
        // every gold key must be present in the form
        for (k in gold.keys) {
            when (k) {
                "lang", "other_incidents" -> {}
                "time" -> if (f.state("occurred_at") == null) problems += "$id: time '${gold[k]}' not resolved (${m.notes})"
                "details" -> gold[k]!!.jsonObject.keys.forEach { dk -> if (f.state(dk) == null) problems += "$id: detail $dk=${gold[k]!!.jsonObject[dk]} lost (${m.notes})" }
                else -> if (f.state(k) == null) problems += "$id: $k=${gold[k]} lost (${m.notes})"
            }
        }
        if (f.incidentType != gold["incident_type"]!!.jsonPrimitive.content) problems += "$id: type ${f.incidentType}"
        f = Fx.merger.applyTimeDefault(f, now)
        val v = Validator.validate(f, Fx.tx, now)
        v.invalid.forEach { problems += "$id: invalid ${it.key} ${it.reason} (time='${gold["time"]}' → ${(f.value("occurred_at") as? JsonPrimitive)?.longOrNull?.let(Instant::ofEpochMilli)})" }
        return Run(f, now, problems)
    }

    @Test fun goldExtractionsSurviveTheAppPipeline() {
        if (!file.exists()) return
        val problems = cases.flatMap { extract(it).problems }
        if (problems.isNotEmpty()) fail("${problems.size} problems:\n" + problems.joinToString("\n"))
    }

    @Test fun followUpQuestionMatchesEval_andGoldPatchSatisfiesAskedFields() {
        if (!file.exists()) return
        val problems = ArrayList<String>()
        var n = 0; var ruleSeverity = 0
        for (c in cases) {
            val fu = c["followup"]?.takeIf { it !is JsonNull }?.jsonObject ?: continue
            n++
            val id = c["id"]!!.jsonPrimitive.content
            val r = extract(c)
            var f = r.form
            val v = Validator.validate(f, Fx.tx, r.now)
            val q = Validator.nextQuestion(v, f, Fx.tx, emergencyMode = f.str("severity") == "CRITICAL")
            val asked = fu["asked_fields"]!!.jsonArray.map { it.jsonPrimitive.content }
            // PRD §10.6: when the deterministic floor already produced a severity (INFERRED, source RULE) the app does
            // not ask for it. The eval generator asks anyway (it scores the patch call, not the dialogue) — not a mismatch.
            if (asked == listOf("severity") && f.state("severity")?.source == FieldSource.RULE) { ruleSeverity++; continue }
            if (q == null) { problems += "$id: app asks nothing, eval asks $asked (missing=${v.missing})"; continue }
            if (q.fields.toSet() != asked.toSet()) problems += "$id: app asks ${q.fields}, eval asks $asked"
            else if (q.text(f.lang) != fu["question"]!!.jsonPrimitive.content) problems += "$id: question text differs: '${q.text(f.lang)}' vs '${fu["question"]!!.jsonPrimitive.content}'"
            // apply the gold patch
            val p = Fx.parser.parse(wrap(Tools.UPDATE, fu["gold_patch"]!!.jsonObject), Tools.UPDATE, f.types)
            val call = (p as? ParseOutcome.Ok)?.call
            if (call == null) { problems += "$id: patch parse failed"; continue }
            if (call.dropped.isNotEmpty()) problems += "$id: patch dropped ${call.dropped}"
            f = Fx.merger.merge(f, call, FieldSource.LLM_PATCH, 1, fu["answer"]!!.jsonPrimitive.content, r.now).form
            val after = Validator.validate(f, Fx.tx, r.now)
            val goldSet = fu["gold_patch"]!!.jsonObject["set"]?.jsonObject?.keys.orEmpty() + (fu["gold_patch"]!!.jsonObject["unknown"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
            for (k in goldSet) { val key = Fx.tx.field(k)?.key ?: k; if (key in after.missing) problems += "$id: $key still missing after gold patch" }
        }
        assertTrue(n > 0); assertTrue(ruleSeverity < n / 3, "too many rule-severity skips: $ruleSeverity")
        if (problems.isNotEmpty()) fail("${problems.size} problems in $n follow-ups:\n" + problems.joinToString("\n"))
    }
}
