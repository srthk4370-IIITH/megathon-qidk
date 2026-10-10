package com.megathon.incidentvoice.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

@Serializable
data class SopStep(
    val id: String,
    val text: String,
    val immediate: Boolean = false,
    val `when`: Map<String, List<String>>? = null,
    @SerialName("done_keywords") val doneKeywords: List<String> = emptyList(),
)

@Serializable
data class SopLibrary(val version: Int = 1, val disclaimer: String = "", val categories: Map<String, List<SopStep>> = emptyMap()) {
    companion object { fun parse(text: String): SopLibrary = Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), text) }
}

data class Recommendation(val sopId: String, val text: String, val immediate: Boolean, val source: String = "SOP_TEMPLATE", val done: Boolean = false)

/**
 * PRD §12.2 step 1 + rev 1.1 R1-06 (SOP templates only in P0). Semantics mirror the ML branch (`ml/common/sop.py`):
 *  - `when` is evaluated for every step; a condition on a MISSING field passes; `"*"` = any satisfied value;
 *    UNKNOWN compares as "UNKNOWN"; list fields pass if any element matches; booleans compare as YES/NO.
 *  - order: primary, secondary types, GENERAL; file order; duplicate ids skipped; then stable immediate-first.
 *  - a step is dropped when a done-keyword phrase overlaps the actions_taken tokens by ≥ 0.6.
 */
class SopSelector(private val lib: SopLibrary, private val maxItems: Int = 6) {
    val disclaimer get() = lib.disclaimer

    private val tok = Regex("[a-z0-9]+")
    private fun tokens(s: String): Set<String> = tok.findAll(s.lowercase()).map { it.value }.toSet()

    private fun condOk(form: IncidentForm, key: String, allowed: List<String>): Boolean {
        val st = form.state(key)
        if (st == null || st.status == FieldStatus.MISSING) return "*" !in allowed
        if ("*" in allowed) return true
        if (st.status == FieldStatus.UNKNOWN) return "UNKNOWN" in allowed
        return when (val v = st.value) {
            is JsonArray -> v.any { (it as? JsonPrimitive)?.contentOrNull in allowed }
            is JsonPrimitive -> (v.booleanOrNull?.takeIf { !v.isString }?.let { if (it) "YES" else "NO" } ?: v.contentOrNull) in allowed
            else -> false
        }
    }

    private fun applies(step: SopStep, form: IncidentForm) = step.`when`?.all { (k, allowed) -> condOk(form, k, allowed) } ?: true

    private fun alreadyDone(step: SopStep, actions: List<String>): Boolean {
        if (step.doneKeywords.isEmpty() || actions.isEmpty()) return false
        val at = actions.flatMap { tokens(it) }.toSet()
        return step.doneKeywords.any { kw ->
            val kt = tokens(kw)
            kt.isNotEmpty() && kt.count { it in at }.toDouble() / kt.size >= 0.6
        }
    }

    fun candidates(form: IncidentForm): List<SopStep> {
        val actions = form.value("actions_taken").asStringList()
        val seen = HashSet<String>()
        val out = ArrayList<SopStep>()
        for (code in form.types.ifEmpty { listOf("OTHER") } + "GENERAL") {
            for (s in lib.categories[code].orEmpty()) {
                if (s.id in seen || !applies(s, form) || alreadyDone(s, actions)) continue
                seen += s.id; out += s
            }
        }
        return out.filter { it.immediate } + out.filter { !it.immediate }
    }

    /** First [max] candidates verbatim, `{location}` substituted when a template uses it. */
    fun recommend(form: IncidentForm, max: Int = maxItems): List<Recommendation> {
        val loc = form.str("location").orEmpty()
        return candidates(form).take(max).map { Recommendation(it.id, it.text.replace("{location}", loc), it.immediate) }
    }

    /** §10.7 emergency banner steps: immediate steps of the given (or primary) category. */
    fun emergencySteps(form: IncidentForm, category: String? = form.incidentType): List<Recommendation> =
        lib.categories[category ?: "OTHER"].orEmpty().filter { it.immediate && applies(it, form) }
            .map { Recommendation(it.id, it.text, true) }
}
