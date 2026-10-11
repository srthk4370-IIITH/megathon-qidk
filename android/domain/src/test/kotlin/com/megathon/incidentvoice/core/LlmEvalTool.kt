package com.megathon.incidentvoice.core

import com.megathon.incidentvoice.domain.engine.RuleBasedLlm
import kotlinx.serialization.json.*
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.Test

/**
 * Evaluation of the extraction step on `eval/text_cases.jsonl` through the app's own prompt builder, parser and
 * normalisers. Not a unit test: it only does something when one of these environment variables is set.
 *   IV_EVAL_EXPORT=<file>   write the Qwen extraction prompts as JSON lines for `iv_cli llm-batch` on the board
 *   IV_EVAL_SCORE=<file>    score the model outputs pulled from the board (and the rules engine as the baseline);
 *                           IV_EVAL_OUT=<dir> receives results_<date>.json and .md
 */
class LlmEvalTool {
    private val cases by lazy { File(Fx.dir.parentFile, "eval/text_cases.jsonl").readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject } }
    private val skip = setOf("lang", "title", "description", "other_incidents", "details")

    @Test fun export() {
        val out = System.getenv("IV_EVAL_EXPORT") ?: return
        val pb = PromptBuilder(Fx.tx, Fx.templates, "base") { ModelFamily.QWEN }
        val rb = RuleBasedLlm(Fx.tx, Fx.registry); val hint = System.getenv("IV_EVAL_HINT") == "1"
        File(out).writeText(cases.joinToString("\n") { c ->
            val tr = c["transcript"]!!.jsonPrimitive.content
            JsonObject(mapOf("id" to c["id"]!!, "prompt" to JsonPrimitive(pb.extract(tr, if (hint) rb.categoryHint(tr) else null)), "max_tokens" to JsonPrimitive(320), "sampler" to JsonPrimitive("greedy"))).toString()
        } + "\n")
        println("EVAL exported ${cases.size} prompts to $out")
    }

    private fun flat(gold: JsonObject): Map<String, JsonElement> {
        val m = LinkedHashMap<String, JsonElement>()
        for ((k, v) in gold) if (k !in skip) m[if (k == "time") "occurred_at" else (Fx.tx.field(k)?.key ?: k)] = v
        (gold["details"] as? JsonObject)?.forEach { (k, v) -> m[Fx.tx.field(k)?.key ?: k] = v }
        return m
    }

    private fun str(e: JsonElement) = (e as? JsonPrimitive)?.content.orEmpty()
    private fun sameTime(want: JsonElement, got: JsonElement, now: java.time.Instant): Boolean {
        val a = Fx.times.resolve(str(got), now)
        val b = Fx.times.resolve(str(want), now)
        if (a.defaulted) return false
        return kotlin.math.abs(a.instant.toEpochMilli() - b.instant.toEpochMilli()) <= 15 * 60_000
    }
    private fun place(x: String) = x.lowercase().replace(Regex("^(near|behind|in|at|inside|outside|the)\\s+"), "").replace(Regex("^the\\s+"), "").trim()
    private fun samePlace(want: JsonElement, got: JsonElement): Boolean {
        val g = place(str(got)); val w = place(str(want))
        if (g.isEmpty()) return false
        val a = matcher.match(str(got)).location?.id; val b = matcher.match(str(want)).location?.id      // same place in the site registry
        if (a != null && b != null) return a == b
        return g.contains(w) || w.contains(g)
    }
    private fun sameList(key: String, want: JsonArray, got: JsonElement): Boolean {
        val g = (got as? JsonArray) ?: return false
        if (g.map { str(it).lowercase() }.toSet() == want.map { str(it).lowercase() }.toSet()) return true
        return Fx.tx.field(key)?.type != "enum_category_list" && g.isNotEmpty()
    }
    private fun same(key: String, want: JsonElement, got: JsonElement, now: java.time.Instant): Boolean {
        if (key == "occurred_at") return sameTime(want, got, now)
        if (key == "location") return samePlace(want, got)
        if (want is JsonArray) return sameList(key, want, got)
        if (Fx.tx.field(key)?.type in setOf("enum", "enum_category", "bool", "int")) return str(got).equals(str(want), ignoreCase = true)
        return str(got).isNotBlank()
    }

    private val matcher = LocationMatcher(Fx.registry)
    private val guardRules = RuleBasedLlm(Fx.tx, Fx.registry)
    private val guardEmergency = EmergencyDetector(Fx.tx)

    /**
     * The app's own post-processing of one model answer, same code and same order as `ConversationOrchestrator.extract`:
     * spoken self-correction, tool-call parser (rules engine if the answer cannot be parsed), evidence checks, category
     * check, then the merger (normalisation, grounding, location and time resolution). Returns the form's filled values.
     */
    private fun systemFields(c: JsonObject, raw: String?, now: java.time.Instant): Pair<Boolean, Map<String, JsonElement>> {
        val spoken = c["transcript"]!!.jsonPrimitive.content
        val text = SelfCorrection.apply(spoken)
        var call = raw?.let { (Fx.parser.parse(it, Tools.FILL) as? ParseOutcome.Ok)?.call }
        val valid = call != null
        if (call == null) call = (Fx.parser.parse(guardRules.extract(text).toString(), Tools.FILL) as? ParseOutcome.Ok)?.call
        if (call == null) return valid to emptyMap()
        val rp = (guardRules.extract(text)["parameters"] as? JsonObject) ?: JsonObject(emptyMap())
        val f = EvidenceGuard.category(EvidenceGuard.filterUnsaid(EvidenceGuard.filter(call.fields, rp, guardEmergency.scan(text) != null), text), guardRules.categoryHint(text))
        val form = Fx.merger.merge(IncidentForm(), call.copy(fields = f), FieldSource.LLM_EXTRACT, null, spoken, now).form
        return valid to form.fields.filterValues { it.value != null && it.status != FieldStatus.MISSING && it.status != FieldStatus.UNKNOWN }.mapValues { it.value.value!! }
    }

    /** One case, one engine → every number the report needs. Description is scored on its own, never inside the field counts. */
    private fun detail(c: JsonObject, raw: String?, stats: JsonObject?, system: Boolean = false): JsonObject {
        val gold = c["gold"]!!.jsonObject; val now = OffsetDateTime.parse(c["now"]!!.jsonPrimitive.content).toInstant()
        val parsed = raw?.let { (Fx.parser.parse(it, Tools.FILL) as? ParseOutcome.Ok)?.call }
        val sys = if (system) systemFields(c, raw, now) else null
        val got: Map<String, JsonElement> = sys?.second ?: (parsed?.fields ?: emptyMap())
        val validCall = sys?.first ?: (parsed != null)
        val want = flat(gold)
        var tp = 0; val wrong = ArrayList<String>(); val missing = ArrayList<String>(); val madeUp = ArrayList<String>()
        for ((k, w) in want) { val g = got[k]; if (g == null) missing += k else if (if (system && k == "occurred_at") sameResolved(w, g, now) else same(k, w, g, now)) tp++ else wrong += "$k: expected ${str2(w)}, got ${str2(g)}" }
        val predicted = got.keys.filter { it !in skip }
        for (k in predicted) if (k !in want) madeUp += "$k=${str2(got[k]!!)}"
        val gd = (gold["description"] as? JsonPrimitive)?.content; val pd = (got["description"] as? JsonPrimitive)?.content
        val tr = c["transcript"]!!.jsonPrimitive.content
        return JsonObject(mapOf(
            "id" to c["id"]!!, "lang" to c["lang"]!!, "category" to gold["incident_type"]!!, "transcript" to JsonPrimitive(tr),
            "valid" to JsonPrimitive(validCall), "pred_type" to (got["incident_type"] ?: JsonNull),
            "expected" to JsonPrimitive(want.size), "predicted" to JsonPrimitive(predicted.size), "tp" to JsonPrimitive(tp),
            "wrong" to JsonArray(wrong.map { JsonPrimitive(it) }), "missing" to JsonArray(missing.map { JsonPrimitive(it) }), "made_up" to JsonArray(madeUp.map { JsonPrimitive(it) }),
            "desc_present" to JsonPrimitive(!pd.isNullOrBlank()), "desc_expected" to JsonPrimitive(gd != null),
            "desc_overlap_with_transcript" to JsonPrimitive(if (pd.isNullOrBlank()) 0.0 else DescriptionStyle.overlap(pd, tr)),
            "desc_overlap_with_gold" to JsonPrimitive(if (pd.isNullOrBlank() || gd == null) 0.0 else DescriptionStyle.overlap(pd, gd)),
            "raw" to JsonPrimitive(raw.orEmpty().take(600)), "stats" to (stats ?: JsonNull),
        ))
    }
    /** The merged form stores the time as a resolved instant; the reference is a phrase. */
    private fun sameResolved(want: JsonElement, got: JsonElement, now: java.time.Instant): Boolean {
        val g = (got as? JsonPrimitive)?.longOrNull ?: return sameTime(want, got, now)
        val b = Fx.times.resolve(str(want), now)
        return kotlin.math.abs(g - b.instant.toEpochMilli()) <= 15 * 60_000
    }
    private fun str2(e: JsonElement) = if (e is JsonPrimitive) e.content else e.toString()

    @Test fun score() {
        val file = System.getenv("IV_EVAL_SCORE") ?: return
        val out = File(System.getenv("IV_EVAL_DETAIL") ?: error("IV_EVAL_DETAIL not set"))
        val outs = File(file).readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        val rb = RuleBasedLlm(Fx.tx, Fx.registry)
        val only = System.getenv("IV_EVAL_ONLY_SCORED") == "1"
        val rows = cases.filter { !only || outs.containsKey(it["id"]!!.jsonPrimitive.content) }.map { c ->
            val o = outs[c["id"]!!.jsonPrimitive.content]
            val t0 = System.nanoTime(); val r = rb.extract(c["transcript"]!!.jsonPrimitive.content).toString(); val ms = (System.nanoTime() - t0) / 1e6
            val qraw = o?.get("text")?.jsonPrimitive?.contentOrNull
            JsonObject(mapOf("qwen" to detail(c, qraw, o?.get("stats") as? JsonObject), "system" to detail(c, qraw, o?.get("stats") as? JsonObject, system = true),
                "rules" to detail(c, r, JsonObject(mapOf("wall_ms" to JsonPrimitive(ms))))))
        }
        out.writeText(JsonArray(rows).toString())
        println("EVAL detail written: ${rows.size} cases, model outputs ${outs.size}")
    }
}
