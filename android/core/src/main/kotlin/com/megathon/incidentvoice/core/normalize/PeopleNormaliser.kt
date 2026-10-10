package com.megathon.incidentvoice.core.normalize

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** PRD §15.3: persons dedupe, injured count, equipment dedupe + asset-tag upper-casing. */
object PeopleNormaliser {
    private val ASSET_TAG = Regex("""\b([A-Za-z]{1,4})-?(\d{1,5})([A-Za-z]?)\b""")

    /** Dedupe persons by case-insensitive name (merging attributes; injured = OR). Unnamed persons are kept. */
    fun persons(list: JsonArray): JsonArray {
        val named = LinkedHashMap<String, MutableMap<String, JsonElement>>()
        val out = mutableListOf<Any>() // String (named key) or JsonObject (unnamed)
        for (el in list) {
            val o = el as? JsonObject ?: continue
            val name = (o["name"] as? JsonPrimitive)?.content?.trim()
            if (name.isNullOrEmpty()) { out += o; continue }
            val key = name.lowercase()
            val existing = named[key]
            if (existing == null) {
                named[key] = LinkedHashMap(o)
                out += key
            } else {
                for ((k, v) in o) {
                    if (k == "injured") {
                        val merged = ((existing[k] as? JsonPrimitive)?.booleanOrNull ?: false) || ((v as? JsonPrimitive)?.booleanOrNull ?: false)
                        existing[k] = JsonPrimitive(merged)
                    } else if (!existing.containsKey(k)) existing[k] = v
                }
            }
        }
        return JsonArray(out.map { if (it is String) JsonObject(named.getValue(it)) else it as JsonObject })
    }

    fun injuredPersons(list: JsonArray?): Int =
        list?.count { ((it as? JsonObject)?.get("injured") as? JsonPrimitive)?.booleanOrNull == true } ?: 0

    /** `injured_count = max(stated, count(persons.injured))`. */
    fun injuredCount(stated: Int?, persons: JsonArray?): Int = maxOf(stated ?: 0, injuredPersons(persons))

    /** Upper-cases recognisable asset tags: `p-101` → `P-101`, `db3` → `DB3` (only token-shaped tags). */
    fun normaliseAssetTags(s: String): String = ASSET_TAG.replace(s) { m ->
        val letters = m.groupValues[1]
        // Avoid shouting ordinary words followed by numbers, e.g. "line 2": require hyphen or no space.
        if (m.value.contains('-') || (m.value.length <= 8 && letters.length <= 3 && !m.value.contains(' '))) m.value.uppercase() else m.value
    }

    fun equipment(list: List<String>): List<String> =
        list.map { normaliseAssetTags(it.trim()) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
}
