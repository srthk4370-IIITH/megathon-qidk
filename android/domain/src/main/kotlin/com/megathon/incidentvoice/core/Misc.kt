package com.megathon.incidentvoice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** PRD §15.4 */
object LangDetector {
    private val hinglishWords = setOf("hai", "hain", "tha", "thi", "ki", "ka", "ke", "ko", "mein", "nahi", "nahin", "aur", "kya", "se", "raha", "rahi", "gaya", "gayi", "hua", "hui",
        "hoon", "yahan", "wahan", "abhi", "kuch", "koi", "bahut", "paas", "paani", "aag", "kal", "aaj", "diya", "liya", "karo", "wala", "bhi", "toh")
    fun detect(text: String, llmLang: String? = null): String {
        if (llmLang in setOf("en", "hinglish", "hi")) return llmLang!!
        val letters = text.count { it.isLetter() }
        if (letters == 0) return "en"
        val deva = text.count { it in 'ऀ'..'ॿ' }
        if (deva.toDouble() / letters > 0.2) return "hi"
        val toks = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (toks.isEmpty()) return "en"
        return if (toks.count { it in hinglishWords }.toDouble() / toks.size >= 0.08) "hinglish" else "en"
    }

    /** The language to answer in, from the reporter's own words: "hinglish" (also for Devanagari) or "en". */
    fun ofSpeech(text: String): String = if (detect(text) == "en") "en" else "hinglish"
}

/**
 * Keeps the description in the reporter's own words. The model may tidy a sentence, but if its description has
 * drifted away from what was said (formal rewrite, added detail), the lightly cleaned narration is used instead.
 */
object DescriptionStyle {
    private val fillers = Regex("(?i)(^|[\\s,])(um+|uh+|er+|hmm+|you know|i mean|matlab|woh)(?=[\\s,.!?]|$)[,]?")
    private val greeting = Regex("(?i)^\\s*(hi|hello|hey|namaste|good (morning|afternoon|evening))[\\s,!.]+")

    /** Light cleanup only: fillers and a leading greeting removed, spacing fixed, first letter capitalised, full stop added. */
    fun light(narration: String, max: Int = 300): String {
        var t = greeting.replace(narration.trim(), "")
        t = fillers.replace(t, "$1").replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.!?])"), "$1").replace(Regex("^[,\\s]+"), "").trim()
        if (t.isEmpty()) return ""
        t = t.take(max).trim().replaceFirstChar { it.uppercase() }
        return if (t.last() in ".!?") t else "$t."
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }

    /** Share of the description's words that the reporter actually said. */
    fun overlap(description: String, narration: String): Double {
        val d = words(description); if (d.isEmpty()) return 0.0
        val n = words(narration).toSet()
        return d.count { it in n }.toDouble() / d.size
    }

    /** The model's description if it stays close to the reporter's words (at least [minOverlap] of them), else the cleaned narration. */
    fun keep(modelDescription: String?, narration: String, minOverlap: Double = 0.7): String {
        val own = light(narration)
        if (modelDescription.isNullOrBlank()) return own
        return if (own.isEmpty() || overlap(modelDescription, narration) >= minOverlap) modelDescription else own
    }
}

/** PRD §15.3 */
object PeopleNormaliser {
    fun dedupePersons(persons: List<JsonObject>): List<JsonObject> {
        val seen = HashSet<String>()
        val out = ArrayList<JsonObject>()
        for (p in persons) {
            val name = (p["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            if (name.isNullOrEmpty()) { out += p; continue }
            if (seen.add(name)) out += p
        }
        return out
    }

    fun injuredCount(persons: List<JsonObject>): Int =
        persons.count { (it["injured"] as? JsonPrimitive)?.booleanOrNull == true }

    private val assetTag = Regex("^[a-z]{1,4}-\\d{1,5}[a-z]?$", RegexOption.IGNORE_CASE)
    fun normaliseEquipment(items: List<String>): List<String> {
        val seen = HashSet<String>()
        return items.map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (assetTag.matches(it)) it.uppercase() else it }
            .filter { seen.add(it.lowercase()) }
    }
}

data class EmergencyHit(val keyword: String, val categoryHint: String?)

/** PRD §10.7 keyword trigger. */
class EmergencyDetector(tx: Taxonomy) {
    private val keywords = tx.emergency.keywords.map { Regex(it, RegexOption.IGNORE_CASE) }
    private val excludes = tx.emergency.exclude.map { Regex(it, RegexOption.IGNORE_CASE) }
    private val hints = tx.emergency.categoryHint.map { Regex(it.key, RegexOption.IGNORE_CASE) to it.value }

    fun scan(text: String): EmergencyHit? {
        if (text.isBlank()) return null
        var t = text
        excludes.forEach { t = it.replace(t, " ") }
        for (k in keywords) {
            val m = k.find(t) ?: continue
            val hint = hints.firstOrNull { it.first.containsMatchIn(m.value) }?.second
                ?: hints.firstOrNull { it.first.containsMatchIn(t) }?.second
            return EmergencyHit(m.value, hint)
        }
        return null
    }
}

internal fun JsonElement?.asStringList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(contentOrNull)
    else -> emptyList()
}
