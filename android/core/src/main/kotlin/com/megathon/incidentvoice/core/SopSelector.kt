package com.megathon.incidentvoice.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

/** PRD §12.2 step 1 + rev 1.1 R1-06: SOP templates only in P0. */
class SopSelector(private val lib: SopLibrary) {
    val disclaimer get() = lib.disclaimer

    private fun whenOk(step: SopStep, form: IncidentForm): Boolean =
        step.`when`?.all { (k, allowed) -> form.str(k)?.let { it in allowed } ?: true } ?: true

    private fun alreadyDone(step: SopStep, actions: List<String>): Boolean {
        if (step.doneKeywords.isEmpty() || actions.isEmpty()) return false
        val joined = actions.joinToString(" ").lowercase()
        val hits = step.doneKeywords.count { joined.contains(it.lowercase()) }
        return hits.toDouble() / step.doneKeywords.size >= 0.5 && hits >= 1 || hits == step.doneKeywords.size
    }

    fun candidates(form: IncidentForm): List<SopStep> {
        val out = ArrayList<SopStep>()
        val actions = form.value("actions_taken").asStringList()
        for (code in form.types.ifEmpty { listOf("OTHER") } + "GENERAL") {
            lib.categories[code].orEmpty().filter { whenOk(it, form) && !alreadyDone(it, actions) }.forEach { out += it }
        }
        return out.distinctBy { it.id }
    }

    /** Top 6, `immediate` first, `{location}` substituted when a template uses it. */
    fun recommend(form: IncidentForm, max: Int = 6): List<Recommendation> {
        val loc = form.str("location").orEmpty()
        val c = candidates(form)
        val ordered = c.filter { it.immediate } + c.filter { !it.immediate }
        return ordered.take(max).map {
            Recommendation(it.id, it.text.replace("{location}", loc), it.immediate)
        }
    }

    /** §10.7 emergency banner steps: immediate steps of the primary category. */
    fun emergencySteps(form: IncidentForm, category: String? = form.incidentType): List<Recommendation> =
        lib.categories[category ?: "OTHER"].orEmpty().filter { it.immediate && whenOk(it, form) }
            .map { Recommendation(it.id, it.text, true) }
}
