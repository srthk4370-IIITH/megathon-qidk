package com.megathon.incidentvoice.core.llm

import com.megathon.incidentvoice.core.config.FieldDef
import com.megathon.incidentvoice.core.config.FieldTypes
import com.megathon.incidentvoice.core.config.Taxonomy
import com.megathon.incidentvoice.core.normalize.NumberWords
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

object Tools {
    const val FILL = "fill_incident_form"
    const val UPDATE = "update_incident_fields"
    const val PROPOSE = "propose_actions"
    val ALL = listOf(FILL, UPDATE, PROPOSE)
}

/** A normalised value plus its grounding verdict (§13.8). */
data class ParsedValue(val value: JsonElement, val grounded: Boolean = true)

data class ParseIssue(val key: String?, val code: String, val detail: String = "")

data class ProposedAction(val sop: String, val text: String)

/**
 * Result of a successful parse (§13.6 step 9). [fields] are keyed by **taxonomy key** (the LLM's `time` is
 * returned as `occurred_at` holding the spoken phrase). Category fields are flattened (keys are unique).
 */
data class ParsedCall(
    val tool: String,
    val fields: Map<String, ParsedValue>,
    val lang: String?,
    val unknown: List<String> = emptyList(),
    val skipped: Boolean = false,
    val actions: List<ProposedAction> = emptyList(),
    val issues: List<ParseIssue> = emptyList(),
    val truncated: Boolean = false,
    val raw: JsonObject? = null,
)

enum class ParseFailure { EMPTY_OUTPUT, NO_JSON, WRONG_TOOL, BAD_ENVELOPE }

sealed class ParseResult {
    data class Ok(val call: ParsedCall) : ParseResult()
    data class Failed(val reason: ParseFailure, val message: String, val tool: String? = null) : ParseResult()

    val callOrNull: ParsedCall? get() = (this as? Ok)?.call
}

/**
 * PRD §13.6 pipeline: extract JSON → lenient fixes → parse → envelope check → field routing via taxonomy →
 * value normalisation (enum synonyms, number words, junk strings) → per-field schema checks → grounding.
 * Never fails the whole call because of one bad field.
 */
class ToolCallParser(private val tx: Taxonomy) {

    companion object {
        const val MAX_STRING = 300
        const val MAX_ACTION_TEXT = 160
        const val MAX_ACTIONS = 6
        val JUNK_STRINGS = setOf(
            "", "n/a", "na", "none", "not mentioned", "unknown", "null", "nil", "not specified", "not stated",
            "not known", "-", "?", "unspecified", "not provided", "not available", "undefined", "pata nahi",
        )
        private val ENVELOPE_PARAM_KEYS = listOf("parameters", "arguments", "args", "params", "input")
        private val ENVELOPE_NAME_KEYS = listOf("name", "function", "tool", "tool_name", "function_name")
        private val PATCH_META = setOf("set", "unknown", "skipped", "lang")
        private val ROLE_SYNONYMS = mapOf(
            "victim" to "AFFECTED", "injured" to "AFFECTED", "patient" to "AFFECTED", "casualty" to "AFFECTED",
            "driver" to "INVOLVED", "operator" to "INVOLVED", "worker" to "INVOLVED", "suspect" to "INVOLVED",
            "first aider" to "RESPONDER", "firstaider" to "RESPONDER", "helper" to "RESPONDER", "rescuer" to "RESPONDER",
            "security" to "RESPONDER", "guard" to "RESPONDER", "me" to "REPORTER", "self" to "REPORTER",
            "witness" to "OTHER",
        )
    }

    private val unknownSynonyms: Set<String> = (tx.enumSynonyms["UNKNOWN"].orEmpty() + listOf("unknown")).toSet()

    /** Full parse of a finished generation. [transcripts] enables grounding; empty = skip grounding. */
    fun parse(raw: String, expectedTool: String, transcripts: List<String> = emptyList()): ParseResult {
        if (raw.isBlank()) return ParseResult.Failed(ParseFailure.EMPTY_OUTPUT, "empty output (engine error, not a content failure)")
        val (obj, truncated) = PartialJson.parseObject(raw)
            ?: return ParseResult.Failed(ParseFailure.NO_JSON, "no parseable JSON object")
        return parseObject(obj, expectedTool, transcripts, truncated)
    }

    /** Streaming parse (steps 1–6, no grounding): fields parsed so far, or null if nothing usable yet. */
    fun parsePartial(rawSoFar: String, expectedTool: String): ParsedCall? {
        val (obj, truncated) = PartialJson.parseObject(rawSoFar) ?: return null
        val r = parseObject(obj, expectedTool, emptyList(), truncated)
        return (r as? ParseResult.Ok)?.call
    }

    fun parseObject(obj: JsonObject, expectedTool: String, transcripts: List<String>, truncated: Boolean): ParseResult {
        val issues = mutableListOf<ParseIssue>()
        val (name, params) = unwrap(obj)
        val toolName = name?.let { normalizeToolName(it) }
        if (toolName != null && toolName != expectedTool) {
            return ParseResult.Failed(ParseFailure.WRONG_TOOL, "expected $expectedTool, got $name", toolName)
        }
        if (params == null) return ParseResult.Failed(ParseFailure.BAD_ENVELOPE, "no parameters object", toolName)
        if (toolName == null) issues += ParseIssue(null, "NO_TOOL_NAME", "accepted bare parameters as $expectedTool")
        val call = when (expectedTool) {
            Tools.FILL -> parseFill(params, issues)
            Tools.UPDATE -> parsePatch(params, issues)
            Tools.PROPOSE -> parsePropose(params, issues)
            else -> return ParseResult.Failed(ParseFailure.WRONG_TOOL, "unsupported tool $expectedTool")
        }
        val grounded = if (transcripts.isEmpty()) call.fields else ground(call.fields, transcripts, issues)
        return ParseResult.Ok(call.copy(fields = grounded, issues = issues.toList(), truncated = truncated, raw = obj))
    }

    private fun normalizeToolName(n: String): String =
        n.trim().lowercase().removePrefix("functions.").removePrefix("function.").replace(' ', '_').replace('-', '_')

    /** Envelope: {name, parameters|arguments|args}, OpenAI-style {function:{name, arguments:"<json>"}}, tool_calls[]. */
    private fun unwrap(o: JsonObject): Pair<String?, JsonObject?> {
        (o["tool_calls"] as? JsonArray)?.firstOrNull()?.let { if (it is JsonObject) return unwrap(it) }
        (o["function"] as? JsonObject)?.let { inner ->
            val r = unwrap(inner)
            if (r.first != null || r.second != null) return r
        }
        val name = ENVELOPE_NAME_KEYS.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        var params: JsonObject? = null
        for (k in ENVELOPE_PARAM_KEYS) {
            val p = o[k]
            if (p is JsonObject) { params = p; break }
            if (p is JsonPrimitive && p.isString) {
                // "arguments": "{\"incident_type\": ...}" (stringified JSON)
                val parsed = PartialJson.parseObject(p.content)?.first
                if (parsed != null) { params = parsed; break }
            }
        }
        if (params == null && name == null) {
            // Bare parameters object (model forgot the envelope).
            if (o.keys.any { tx.field(it) != null || it in PATCH_META || it == "details" || it == "actions" }) params = o
        } else if (params == null && name != null) {
            // {"name": "...", "incident_type": ...} — parameters inlined next to the name.
            val rest = o.filterKeys { it !in ENVELOPE_NAME_KEYS && it != "type" }
            if (rest.isNotEmpty()) params = JsonObject(rest)
        }
        return name to params
    }

    // ---------------------------------------------------------------- fill
    private fun parseFill(p: JsonObject, issues: MutableList<ParseIssue>): ParsedCall {
        val out = LinkedHashMap<String, ParsedValue>()
        var lang: String? = null
        for ((k, v) in p) {
            if (v is JsonNull) continue
            when (k) {
                "lang", "language" -> lang = normalizeLang(v).also { if (it == null) issues += ParseIssue(k, "INVALID_VALUE", v.toString()) }
                "details" -> {
                    val d = v as? JsonObject
                    if (d == null) { issues += ParseIssue(k, "BAD_TYPE", "details is not an object"); continue }
                    for ((dk, dv) in d) route(dk, dv, out, issues)
                }
                else -> route(k, v, out, issues)
            }
        }
        // secondary_types must not repeat the primary.
        val primary = (out["incident_type"]?.value as? JsonPrimitive)?.content
        out["secondary_types"]?.let { sv ->
            val list = (sv.value as JsonArray).filter { (it as JsonPrimitive).content != primary }
            if (list.isEmpty()) out.remove("secondary_types") else out["secondary_types"] = sv.copy(value = JsonArray(list))
        }
        return ParsedCall(Tools.FILL, out, lang)
    }

    // ---------------------------------------------------------------- patch
    private fun parsePatch(p: JsonObject, issues: MutableList<ParseIssue>): ParsedCall {
        val out = LinkedHashMap<String, ParsedValue>()
        val unknown = LinkedHashSet<String>()
        var lang: String? = null
        var skipped = false
        val setObj = p["set"] as? JsonObject
        if (p["set"] != null && setObj == null) issues += ParseIssue("set", "BAD_TYPE", "set is not an object")
        fun routePatch(k: String, v: JsonElement) {
            if (v is JsonNull) return
            val def = tx.field(k)
            if (def != null && isUnknownWord(v)) { unknown += def.key; return }
            route(k, v, out, issues)
        }
        setObj?.forEach { (k, v) ->
            if (k == "details" && v is JsonObject) v.forEach { (dk, dv) -> routePatch(dk, dv) } else routePatch(k, v)
        }
        for ((k, v) in p) {
            when (k) {
                "set" -> {}
                "lang", "language" -> lang = normalizeLang(v)
                "skipped", "skip" -> skipped = normalizeBool(v) ?: false
                "unknown" -> {
                    val keys = when (v) {
                        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
                        is JsonPrimitive -> listOf(v.content)
                        else -> emptyList()
                    }
                    for (uk in keys) {
                        val def = tx.field(uk.trim())
                        if (def == null) issues += ParseIssue(uk, "UNKNOWN_KEY", "in unknown[]") else unknown += def.key
                    }
                }
                else -> {
                    issues += ParseIssue(k, "LOOSE_KEY", "field outside set{}")
                    routePatch(k, v)
                }
            }
        }
        unknown.removeAll(out.keys)
        return ParsedCall(Tools.UPDATE, out, lang, unknown.toList(), skipped)
    }

    // ---------------------------------------------------------------- propose
    private fun parsePropose(p: JsonObject, issues: MutableList<ParseIssue>): ParsedCall {
        val arr = when (val a = p["actions"]) {
            is JsonArray -> a
            is JsonObject -> JsonArray(listOf(a))
            else -> JsonArray(emptyList())
        }
        val actions = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val sop = (o["sop"] as? JsonPrimitive)?.content?.trim()?.uppercase()
            val text = (o["text"] as? JsonPrimitive)?.content?.let { cleanString(it) }
            if (sop.isNullOrEmpty() || text.isNullOrEmpty()) {
                issues += ParseIssue("actions", "INVALID_ACTION", o.toString()); null
            } else ProposedAction(sop, text.take(MAX_ACTION_TEXT))
        }.distinctBy { it.sop to it.text.lowercase() }
        if (actions.size > MAX_ACTIONS) issues += ParseIssue("actions", "TOO_MANY", "${actions.size}")
        return ParsedCall(Tools.PROPOSE, emptyMap(), null, actions = actions.take(MAX_ACTIONS))
    }

    // ---------------------------------------------------------------- routing + normalisation
    private fun route(k: String, v: JsonElement, out: MutableMap<String, ParsedValue>, issues: MutableList<ParseIssue>) {
        if (v is JsonNull) return
        val def = tx.field(k.trim())
        if (def == null) { issues += ParseIssue(k, "UNKNOWN_KEY"); return }
        if (def.key == "lang") return
        val nv = normalizeValue(def, v)
        if (nv == null) { issues += ParseIssue(def.key, "INVALID_VALUE", v.toString().take(80)); return }
        out[def.key] = ParsedValue(nv)
    }

    fun normalizeLang(v: JsonElement): String? {
        val s = (v as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return null
        return when (s) {
            "en", "english", "eng", "en-in" -> "en"
            "hinglish", "hi-en", "hi_en", "mixed", "code-mix", "roman hindi" -> "hinglish"
            "hi", "hindi", "hi-in" -> "hi"
            else -> null
        }
    }

    private fun isUnknownWord(v: JsonElement): Boolean {
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()?.trimEnd('.', '!') ?: return false
        return s in unknownSynonyms
    }

    /** Value normalisation per field type (§13.6 step 6–7). Null = invalid → dropped. */
    fun normalizeValue(def: FieldDef, v: JsonElement): JsonElement? = when (def.type) {
        FieldTypes.ENUM -> normalizeEnum(v, def.enumValues.orEmpty())?.let { JsonPrimitive(it) }
        FieldTypes.ENUM_CATEGORY -> normalizeCategory(v)?.let { JsonPrimitive(it) }
        FieldTypes.ENUM_CATEGORY_LIST -> {
            val items = asList(v).mapNotNull { normalizeCategory(it) }.distinct().take(def.maxItems ?: 2)
            if (items.isEmpty()) null else JsonArray(items.map { JsonPrimitive(it) })
        }
        FieldTypes.STRING, FieldTypes.TIME_EXPRESSION -> normalizeString(v, def.maxLen ?: MAX_STRING)?.let { JsonPrimitive(it) }
        FieldTypes.STRING_LIST -> {
            val items = asList(v).mapNotNull { normalizeString(it, MAX_STRING) }.distinctBy { it.lowercase() }
            if (items.isEmpty()) null else JsonArray(items.map { JsonPrimitive(it) })
        }
        FieldTypes.INT -> normalizeInt(v)?.takeIf { n -> def.min == null || n >= def.min }?.let { JsonPrimitive(it) }
        FieldTypes.BOOL -> normalizeBool(v)?.let { JsonPrimitive(it) }
        FieldTypes.PERSON_LIST -> normalizePersons(v, def)
        FieldTypes.WITNESS_LIST -> normalizeWitnesses(v)
        else -> v
    }

    private fun asList(v: JsonElement): List<JsonElement> = when (v) {
        is JsonArray -> v.toList()
        is JsonNull -> emptyList()
        else -> listOf(v)
    }

    fun cleanString(s: String): String = s.trim().trim('"').replace(Regex("""\s+"""), " ").trim()

    fun normalizeString(v: JsonElement, maxLen: Int): String? {
        val s = when (v) {
            is JsonPrimitive -> v.content
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(", ")
            else -> return null
        }
        val c = cleanString(s)
        if (c.lowercase().trimEnd('.') in JUNK_STRINGS) return null
        return c.take(maxLen).trimEnd()
    }

    fun normalizeInt(v: JsonElement): Int? {
        val p = v as? JsonPrimitive ?: return null
        p.longOrNull?.let { return it.toInt() }
        p.doubleOrNull?.let { d -> if (d == Math.floor(d)) return d.toInt() }
        if (p.booleanOrNull != null && !p.isString) return null
        return NumberWords.parseInt(p.content)
    }

    fun normalizeBool(v: JsonElement): Boolean? {
        val p = v as? JsonPrimitive ?: return null
        if (!p.isString) p.booleanOrNull?.let { return it }
        val s = p.content.trim().lowercase().trimEnd('.', '!')
        return when (s) {
            "yes", "true", "haan", "han", "ha", "1", "y", "ji", "ji haan", "sahi" -> true
            "no", "false", "nahi", "nahin", "nai", "0", "n", "na", "ji nahi" -> false
            else -> when {
                tx.enumSynonyms["YES"].orEmpty().contains(s) -> true
                tx.enumSynonyms["NO"].orEmpty().contains(s) -> false
                else -> null
            }
        }
    }

    private fun phrase(s: String): String =
        s.lowercase().replace('_', ' ').replace(Regex("""[^\p{L}\p{N}' ]+"""), " ").replace(Regex("""\s+"""), " ").trim()

    /** Enum: upper-case/underscore match, then taxonomy synonyms (exact, then longest contained phrase). */
    fun normalizeEnum(v: JsonElement, allowed: List<String>): String? {
        val p = when (v) {
            is JsonPrimitive -> v
            is JsonArray -> v.firstOrNull() as? JsonPrimitive ?: return null
            else -> return null
        }
        if (!p.isString) {
            p.booleanOrNull?.let { b -> val c = if (b) "YES" else "NO"; return c.takeIf { it in allowed } }
        }
        val s = p.content.trim()
        if (s.isEmpty()) return null
        val up = s.uppercase().replace(Regex("""[\s\-/]+"""), "_").trim('_', '.', '!', '?')
        if (up in allowed) return up
        val low = phrase(s)
        val candidates = ArrayList<Pair<String, String>>() // phrase -> canonical
        for ((canon, syns) in tx.enumSynonyms) if (canon in allowed) syns.forEach { candidates += phrase(it) to canon }
        for (a in allowed) candidates += phrase(a) to a
        candidates.firstOrNull { it.first == low }?.let { return it.second }
        return candidates.filter { it.first.isNotEmpty() }
            .sortedByDescending { it.first.length }
            .firstOrNull { Regex("""(^|\s)${Regex.escape(it.first)}($|\s)""").containsMatchIn(low) }
            ?.second
    }

    fun normalizeCategory(v: JsonElement): String? {
        val s = (v as? JsonPrimitive)?.content?.trim() ?: return null
        if (s.isEmpty()) return null
        val up = s.uppercase().replace(Regex("""[\s\-/]+"""), "_")
        if (tx.isCategory(up)) return up
        val low = phrase(s)
        tx.categories.firstOrNull { phrase(it.label) == low }?.let { return it.code }
        for (tok in low.split(' ')) if (tx.isCategory(tok.uppercase())) return tok.uppercase()
        // search cues (whole words), e.g. "gas leak" → HAZMAT if "gas" is a cue
        val toks = low.split(' ').toSet()
        return tx.categories.firstOrNull { c -> c.code != "OTHER" && c.searchCues.any { phrase(it) in toks } }?.code
    }

    private fun normalizePersons(v: JsonElement, def: FieldDef): JsonElement? {
        val roles = (def.item?.get("role") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val items = asList(v).mapNotNull { el ->
            val o: JsonObject = when (el) {
                is JsonObject -> el
                is JsonPrimitive -> JsonObject(mapOf("name" to el))
                else -> return@mapNotNull null
            }
            val m = LinkedHashMap<String, JsonElement>()
            o["name"]?.let { normalizeString(it, 80) }?.let { m["name"] = JsonPrimitive(it) }
            o["role"]?.let { r ->
                val rs = (r as? JsonPrimitive)?.content?.trim()
                val norm = normalizeEnum(r, roles) ?: rs?.lowercase()?.let { ROLE_SYNONYMS[it] }
                norm?.takeIf { it in roles }?.let { m["role"] = JsonPrimitive(it) }
            }
            (o["injured"] ?: o["is_injured"])?.let { normalizeBool(it) }?.let { m["injured"] = JsonPrimitive(it) }
            o["injury"]?.let { normalizeString(it, 120) }?.let { m["injury"] = JsonPrimitive(it) }
            if (m.isEmpty()) null else JsonObject(m)
        }
        return if (items.isEmpty()) null else JsonArray(items)
    }

    private fun normalizeWitnesses(v: JsonElement): JsonElement? {
        val items = asList(v).mapNotNull { el ->
            val o: JsonObject = when (el) {
                is JsonObject -> el
                is JsonPrimitive -> JsonObject(mapOf("name" to el))
                else -> return@mapNotNull null
            }
            val m = LinkedHashMap<String, JsonElement>()
            o["name"]?.let { normalizeString(it, 80) }?.let { m["name"] = JsonPrimitive(it) }
            (o["contact"] ?: o["phone"])?.let { normalizeString(it, 80) }?.let { m["contact"] = JsonPrimitive(it) }
            if (m.isEmpty()) null else JsonObject(m)
        }
        return if (items.isEmpty()) null else JsonArray(items)
    }

    // ---------------------------------------------------------------- grounding (§13.8)
    private fun ground(fields: Map<String, ParsedValue>, transcripts: List<String>, issues: MutableList<ParseIssue>): Map<String, ParsedValue> {
        val out = LinkedHashMap<String, ParsedValue>()
        for ((k, pv) in fields) {
            val def = tx.field(k)
            if (def == null || k in Grounding.EXEMPT_KEYS) { out[k] = pv; continue }
            when (def.type) {
                FieldTypes.PERSON_LIST, FieldTypes.WITNESS_LIST -> {
                    val dropKeys = if (def.type == FieldTypes.PERSON_LIST) setOf("name") else setOf("name", "contact")
                    val items = (pv.value as JsonArray).mapNotNull { el ->
                        val o = el as JsonObject
                        val kept = o.filter { (ik, iv) ->
                            if (ik !in dropKeys) true
                            else Grounding.isGrounded((iv as JsonPrimitive).content, transcripts).also {
                                if (!it) issues += ParseIssue("$k.$ik", "UNGROUNDED_DROPPED", iv.content)
                            }
                        }
                        if (kept.isEmpty()) null else JsonObject(kept)
                    }
                    if (items.isNotEmpty()) out[k] = ParsedValue(JsonArray(items))
                }
                FieldTypes.STRING, FieldTypes.STRING_LIST -> {
                    val values = when (val v = pv.value) {
                        is JsonArray -> v.map { (it as JsonPrimitive).content }
                        else -> listOf((v as JsonPrimitive).content)
                    }
                    if (k in Grounding.DROP_KEYS) {
                        val kept = values.filter { Grounding.isGrounded(it, transcripts) }
                        if (kept.size < values.size) issues += ParseIssue(k, "UNGROUNDED_DROPPED", (values - kept.toSet()).joinToString())
                        if (kept.isEmpty()) continue
                        out[k] = ParsedValue(if (pv.value is JsonArray) JsonArray(kept.map { JsonPrimitive(it) }) else JsonPrimitive(kept.first()))
                    } else {
                        val ok = values.all { Grounding.isGrounded(it, transcripts) }
                        if (!ok) issues += ParseIssue(k, "UNGROUNDED_INFERRED", values.joinToString())
                        out[k] = pv.copy(grounded = ok)
                    }
                }
                else -> out[k] = pv
            }
        }
        return out
    }
}
