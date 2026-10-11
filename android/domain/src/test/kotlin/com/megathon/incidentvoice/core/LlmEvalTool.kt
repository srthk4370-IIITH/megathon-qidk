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
    private val skip = setOf("lang", "title", "description", "actions_taken", "persons", "witnesses", "other_incidents", "details")

    @Test fun export() {
        val out = System.getenv("IV_EVAL_EXPORT") ?: return
        val pb = PromptBuilder(Fx.tx, Fx.templates, "base") { ModelFamily.QWEN }
        File(out).writeText(cases.joinToString("\n") { c ->
            JsonObject(mapOf("id" to c["id"]!!, "prompt" to JsonPrimitive(pb.extract(c["transcript"]!!.jsonPrimitive.content)), "max_tokens" to JsonPrimitive(320), "sampler" to JsonPrimitive("greedy"))).toString()
        } + "\n")
        println("EVAL exported ${cases.size} prompts to $out")
    }

    private class Score { var n = 0; var valid = 0; var typeOk = 0; var tp = 0; var fp = 0; var fn = 0; val ms = ArrayList<Double>(); val tps = ArrayList<Double>(); val ttft = ArrayList<Double>(); val byLang = HashMap<String, IntArray>() }

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
        return kotlin.math.abs(a.instant.toEpochMilli() - b.instant.toEpochMilli()) <= 60_000
    }
    private fun place(x: String) = x.lowercase().replace(Regex("^(near|behind|in|at|inside|outside|the)\\s+"), "").replace(Regex("^the\\s+"), "").trim()
    private fun samePlace(want: JsonElement, got: JsonElement): Boolean {
        val g = place(str(got)); val w = place(str(want))
        return g.isNotEmpty() && (g.contains(w) || w.contains(g))
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

    private fun scoreOne(sc: Score, c: JsonObject, raw: String?) {
        val gold = c["gold"]!!.jsonObject; val now = OffsetDateTime.parse(c["now"]!!.jsonPrimitive.content).toInstant(); val lang = c["lang"]!!.jsonPrimitive.content
        val call = raw?.let { (Fx.parser.parse(it, Tools.FILL) as? ParseOutcome.Ok)?.call }
        sc.n++; val l = sc.byLang.getOrPut(lang) { IntArray(2) }; l[0]++
        if (call != null) sc.valid++
        val got = call?.fields ?: emptyMap()
        if ((got["incident_type"] as? JsonPrimitive)?.content == gold["incident_type"]!!.jsonPrimitive.content) { sc.typeOk++; l[1]++ }
        val want = flat(gold)
        for ((k, w) in want) { val g = got[k]; if (g != null && same(k, w, g, now)) sc.tp++ else { sc.fn++; if (g != null) sc.fp++ } }
        for (k in got.keys) if (k !in want && k !in skip) sc.fp++
    }

    private fun pct(xs: List<Double>, p: Double) = if (xs.isEmpty()) 0.0 else xs.sorted()[((xs.size - 1) * p).toInt()]
    private fun summary(name: String, sc: Score): JsonObject {
        val p = if (sc.tp + sc.fp == 0) 0.0 else sc.tp.toDouble() / (sc.tp + sc.fp); val r = if (sc.tp + sc.fn == 0) 0.0 else sc.tp.toDouble() / (sc.tp + sc.fn)
        fun r1(x: Double) = JsonPrimitive(Math.round(x * 10) / 10.0)
        return JsonObject(mapOf(
            "engine" to JsonPrimitive(name), "cases" to JsonPrimitive(sc.n),
            "incident_type_accuracy_pct" to r1(100.0 * sc.typeOk / sc.n),
            "incident_type_accuracy_en_pct" to r1(sc.byLang["en"]?.let { 100.0 * it[1] / it[0] } ?: 0.0),
            "incident_type_accuracy_hinglish_pct" to r1(sc.byLang["hinglish"]?.let { 100.0 * it[1] / it[0] } ?: 0.0),
            "field_precision" to JsonPrimitive(Math.round(p * 1000) / 1000.0), "field_recall" to JsonPrimitive(Math.round(r * 1000) / 1000.0),
            "field_f1" to JsonPrimitive(if (p + r == 0.0) 0.0 else Math.round(2 * p * r / (p + r) * 1000) / 1000.0),
            "valid_tool_call_pct" to r1(100.0 * sc.valid / sc.n),
            "latency_p50_ms" to r1(pct(sc.ms, 0.5)), "latency_p90_ms" to r1(pct(sc.ms, 0.9)), "ttft_p50_ms" to r1(pct(sc.ttft, 0.5)), "decode_tok_per_s_p50" to r1(pct(sc.tps, 0.5)),
        ))
    }

    @Test fun score() {
        val file = System.getenv("IV_EVAL_SCORE") ?: return
        val outDir = File(System.getenv("IV_EVAL_OUT") ?: File(Fx.dir.parentFile, "eval").path)
        val outs = File(file).readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        val qwen = Score(); val rules = Score(); val rb = RuleBasedLlm(Fx.tx, Fx.registry)
        for (c in cases) {
            val o = outs[c["id"]!!.jsonPrimitive.content]
            scoreOne(qwen, c, o?.get("text")?.jsonPrimitive?.contentOrNull)
            (o?.get("stats") as? JsonObject)?.let { st ->
                fun d(vararg k: String) = k.firstNotNullOfOrNull { (st[it] as? JsonPrimitive)?.doubleOrNull }
                d("wall_ms", "wallMs")?.let { qwen.ms += it }; d("decode_tps", "decodeTps")?.takeIf { it > 0 }?.let { qwen.tps += it }; d("ttft_ms", "ttftMs")?.let { qwen.ttft += it }
            }
            val t0 = System.nanoTime(); val r = rb.extract(c["transcript"]!!.jsonPrimitive.content).toString(); rules.ms += (System.nanoTime() - t0) / 1e6
            scoreOne(rules, c, r)
        }
        val date = System.getenv("IV_EVAL_DATE") ?: java.time.LocalDate.now().toString()
        val res = JsonObject(mapOf("date" to JsonPrimitive(date), "set" to JsonPrimitive("eval/text_cases.jsonl (150 cases: 75 English, 75 Hinglish)"), "model_outputs_received" to JsonPrimitive(outs.size),
            "results" to JsonArray(listOf(summary(System.getenv("IV_EVAL_NAME") ?: "Qwen3-4B (Genie, Hexagon NPU, on the QIDK)", qwen), summary("Rules engine (stand-in, JVM on the laptop)", rules)))))
        File(outDir, "results_$date.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), res) + "\n")
        println("EVAL " + res)
    }
}
