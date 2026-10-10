package com.megathon.incidentvoice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

object Tools {
    const val FILL = "fill_incident_form"
    const val UPDATE = "update_incident_fields"
    const val PROPOSE = "propose_actions"
}

data class ProposedAction(val sop: String, val text: String)

data class ParsedCall(
    val tool: String,
    /** taxonomy key -> normalised value (occurred_at carries the raw time phrase as a string). */
    val fields: Map<String, JsonElement>,
    val lang: String? = null,
    val unknown: List<String> = emptyList(),
    val skipped: Boolean = false,
    val otherIncidents: List<String> = emptyList(),
    val actions: List<ProposedAction> = emptyList(),
    val dropped: List<String> = emptyList(),
)

sealed interface ParseOutcome {
    data class Ok(val call: ParsedCall) : ParseOutcome
    data class Fail(val reason: String) : ParseOutcome
}

/** PRD §13.6. Never invents values; invalid fields are dropped one by one. */
class ToolCallParser(private val tx: Taxonomy) {

    fun parse(raw: String, expectedTool: String, activeTypes: List<String>? = null): ParseOutcome {
        val objText = PartialJson.extractObject(raw) ?: return ParseOutcome.Fail("no JSON object")
        val root = LenientParse.parse(objText) as? JsonObject ?: return ParseOutcome.Fail("unparseable JSON")
        var env: JsonObject = root
        (root["function"] as? JsonObject)?.let { env = it }
        val name = (env["name"] as? JsonPrimitive)?.contentOrNull
            ?: (env["function"] as? JsonPrimitive)?.contentOrNull
            ?: (env["tool"] as? JsonPrimitive)?.contentOrNull
        if (name != expectedTool) return ParseOutcome.Fail("wrong tool: $name")
        val params = (env["parameters"] ?: env["arguments"] ?: env["args"]) as? JsonObject
            ?: return ParseOutcome.Fail("no parameters")
        val dropped = ArrayList<String>()
        val call = when (expectedTool) {
            Tools.FILL -> parseFill(params, activeTypes, dropped)
            Tools.UPDATE -> parseUpdate(params, activeTypes, dropped)
            Tools.PROPOSE -> parsePropose(params, dropped)
            else -> return ParseOutcome.Fail("unknown tool $expectedTool")
        }
        return ParseOutcome.Ok(call.copy(dropped = dropped))
    }

    private fun parseFill(p: JsonObject, active: List<String>?, dropped: MutableList<String>): ParsedCall {
        val fields = LinkedHashMap<String, JsonElement>()
        val lang = (p["lang"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf { it in setOf("en", "hinglish", "hi") }
        val others = ArrayList<String>()
        // top-level fields first (incident_type / secondary_types decide which details are allowed)
        for ((k, v) in p) {
            when (k) {
                "lang", "details", "other_incidents" -> {}
                else -> routeField(k, v, fields, dropped)
            }
        }
        p["other_incidents"]?.let { others += it.asStringList().map { s -> clean(s) }.filter { s -> s.isNotEmpty() } }
        val types = active ?: (listOfNotNull((fields["incident_type"] as? JsonPrimitive)?.contentOrNull) +
            fields["secondary_types"].asStringList())
        (p["details"] as? JsonObject)?.forEach { (k, v) -> routeField(k, v, fields, dropped, types) }
        // drop category fields whose category is not chosen
        val it = fields.keys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            val d = tx.field(k) ?: continue
            if (!d.isCommon && d.category !in types) { dropped += "$k (category ${d.category} not chosen)"; it.remove() }
        }
        return ParsedCall(Tools.FILL, fields, lang, otherIncidents = others)
    }

    private fun parseUpdate(p: JsonObject, active: List<String>?, dropped: MutableList<String>): ParsedCall {
        val fields = LinkedHashMap<String, JsonElement>()
        val lang = (p["lang"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf { it in setOf("en", "hinglish", "hi") }
        (p["set"] as? JsonObject)?.forEach { (k, v) -> routeField(k, v, fields, dropped, active) }
        // patches may add/alter incident_type; allow its category fields when type is in this call
        val types = (active ?: emptyList()) + listOfNotNull((fields["incident_type"] as? JsonPrimitive)?.contentOrNull) +
            fields["secondary_types"].asStringList()
        if (active != null) {
            val it = fields.keys.iterator()
            while (it.hasNext()) {
                val k = it.next(); val d = tx.field(k) ?: continue
                if (!d.isCommon && d.category !in types) { dropped += "$k (category ${d.category} not chosen)"; it.remove() }
            }
        }
        val unknown = p["unknown"].asStringList().mapNotNull { k ->
            val key = tx.field(k.trim())?.key
            if (key == null) { dropped += "unknown:$k"; null } else key
        }.distinct()
        val skipped = (p["skipped"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false
        return ParsedCall(Tools.UPDATE, fields, lang, unknown, skipped)
    }

    private fun parsePropose(p: JsonObject, dropped: MutableList<String>): ParsedCall {
        val acts = (p["actions"] as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val sop = (o["sop"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase() ?: return@mapNotNull null
            val text = clean((o["text"] as? JsonPrimitive)?.contentOrNull ?: "")
            if (text.isEmpty()) null else ProposedAction(sop, text.take(160))
        } ?: emptyList()
        return ParsedCall(Tools.PROPOSE, emptyMap(), actions = acts, dropped = dropped)
    }

    private fun routeField(
        rawKey: String, v: JsonElement, out: MutableMap<String, JsonElement>, dropped: MutableList<String>,
        @Suppress("UNUSED_PARAMETER") types: List<String>? = null,
    ) {
        val def = tx.field(rawKey)
        if (def == null) { dropped += "unknown key $rawKey"; return }
        if (def.system && def.key != "title") { return }
        val norm = normalise(def, v)
        if (norm == null) { dropped += "${def.key}: invalid value"; return }
        out[def.key] = norm
    }

    // -------- value normalisation --------------------------------------------------------

    private val junk = setOf("", "n/a", "na", "none", "not mentioned", "unknown", "null", "not specified", "-")

    internal fun clean(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(300)

    fun normalise(def: FieldDef, v: JsonElement): JsonElement? = when (def.type) {
        "enum" -> enumValue(def, v)?.let { JsonPrimitive(it) }
        "enum_category" -> catCode(v)?.let { JsonPrimitive(it) }
        "enum_category_list" -> {
            val l = v.asStringList().mapNotNull { catCode(JsonPrimitive(it)) }.distinct().take(def.maxItems ?: 2)
            if (l.isEmpty()) null else JsonArray(l.map { JsonPrimitive(it) })
        }
        "string", "time_expression" -> str(v)?.let { JsonPrimitive(if (def.maxLen != null) it.take(def.maxLen) else it) }
        "string_list" -> {
            val l = v.asStringList().map { clean(it) }.filter { it.lowercase() !in junk }
                .distinctBy { it.lowercase() }
            if (l.isEmpty()) null else JsonArray(l.map { JsonPrimitive(it) })
        }
        "int" -> intValue(v)?.let { JsonPrimitive(it) }
        "bool" -> boolValue(v)?.let { JsonPrimitive(it) }
        "person_list" -> persons(v, withRole = true)
        "witness_list" -> persons(v, withRole = false)
        else -> null
    }

    private fun str(v: JsonElement): String? {
        val s = (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.let { clean(it) } ?: return null
        return if (s.lowercase() in junk) null else s
    }

    private fun catCode(v: JsonElement): String? {
        val s = (v as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()?.replace(Regex("[\\s-]+"), "_") ?: return null
        return s.takeIf { it in tx.categoryCodes }
    }

    fun enumValue(def: FieldDef, v: JsonElement): String? {
        val allowed = def.enum ?: return null
        val prim = (v as? JsonPrimitive) ?: return null
        val rawS = prim.contentOrNull?.trim() ?: return null
        val up = rawS.uppercase().replace(Regex("[\\s-]+"), "_")
        if (up in allowed) return up
        val low = rawS.lowercase()
        // synonyms: map of ENUM_VALUE -> phrases
        for ((value, phrases) in tx.data.enumSynonyms) {
            if (value in allowed && (low in phrases || phrases.any { it.equals(low, ignoreCase = true) })) return value
        }
        if (prim.booleanOrNull != null && allowed.containsAll(listOf("YES", "NO"))) return if (prim.booleanOrNull == true) "YES" else "NO"
        return null
    }

    private val numWords = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "paanch" to 5, "chhe" to 6, "saat" to 7,
        "aath" to 8, "nau" to 9, "das" to 10, "no" to 0, "none" to 0,
    )

    private fun intValue(v: JsonElement): Long? {
        val p = v as? JsonPrimitive ?: return null
        p.longOrNull?.let { return it }
        val s = p.contentOrNull?.trim()?.lowercase() ?: return null
        s.toLongOrNull()?.let { return it }
        Regex("\\d+").find(s)?.value?.toLongOrNull()?.let { return it }
        return numWords[s]?.toLong()
    }

    private fun boolValue(v: JsonElement): Boolean? {
        val p = v as? JsonPrimitive ?: return null
        p.booleanOrNull?.let { return it }
        return when (p.contentOrNull?.trim()?.lowercase()) {
            "yes", "true", "haan", "han", "ha", "1", "y" -> true
            "no", "false", "nahi", "nahin", "0", "n" -> false
            else -> null
        }
    }

    private val roles = setOf("AFFECTED", "INVOLVED", "RESPONDER", "REPORTER", "OTHER")

    private fun persons(v: JsonElement, withRole: Boolean): JsonElement? {
        val arr = when (v) {
            is JsonArray -> v
            is JsonObject -> JsonArray(listOf(v))
            is JsonPrimitive -> JsonArray(listOf(buildJsonName(v)))
            else -> return null
        }
        val out = ArrayList<JsonObject>()
        for (e in arr) {
            val o = (e as? JsonObject) ?: (e as? JsonPrimitive)?.let { buildJsonName(it) } ?: continue
            val name = str(o["name"] ?: JsonNull)
            val m = LinkedHashMap<String, JsonElement>()
            if (name != null) m["name"] = JsonPrimitive(name)
            if (withRole) {
                val r = (o["role"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()
                m["role"] = JsonPrimitive(if (r in roles) r!! else "INVOLVED")
                boolValue(o["injured"] ?: JsonNull)?.let { m["injured"] = JsonPrimitive(it) }
                str(o["injury"] ?: JsonNull)?.let { m["injury"] = JsonPrimitive(it) }
            } else {
                str(o["contact"] ?: JsonNull)?.let { m["contact"] = JsonPrimitive(it) }
            }
            if (name != null || m.size > 1 && !(withRole && m.keys == setOf("role"))) out += JsonObject(m)
        }
        val ded = PeopleNormaliser.dedupePersons(out)
        return if (ded.isEmpty()) null else JsonArray(ded)
    }

    private fun buildJsonName(p: JsonPrimitive) = JsonObject(mapOf("name" to p))
}
