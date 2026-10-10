package com.megathon.incidentvoice.core.recommend

import com.megathon.incidentvoice.core.config.SopLibrary
import com.megathon.incidentvoice.core.config.SopStep
import com.megathon.incidentvoice.core.model.FieldStatus
import com.megathon.incidentvoice.core.model.IncidentForm
import com.megathon.incidentvoice.core.model.Recommendation
import com.megathon.incidentvoice.core.model.RecommendationSource
import com.megathon.incidentvoice.core.normalize.Fuzzy
import com.megathon.incidentvoice.core.normalize.Stopwords
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * P0 recommendations = SOP templates (PRD §12.2 step 1 + rev 1.1 R1-06):
 *  candidates = steps of primary + secondary categories whose `when` holds (missing field ⇒ satisfied, "*" ⇒ any
 *  present value), + GENERAL; minus steps already done (done_keywords overlap ≥ 0.6 with actions_taken);
 *  ordered immediate first (category order), then the rest (category order), GENERAL last.
 *  `when` applies to immediate steps too (e.g. WATER-01 only while the leak is ONGOING/UNKNOWN).
 */
class SopRecommender(private val sop: SopLibrary, private val defaultMax: Int = 6) {

    companion object {
        const val DONE_OVERLAP = 0.6
    }

    fun candidates(form: IncidentForm): List<SopStep> {
        val types = form.types.ifEmpty { listOf("OTHER") }
        val actionTokens = form.stringList("actions_taken").map { contentTokens(it) }
        val all = types.flatMap { sop.steps(it) }.filter { whenHolds(it, form) }
        val general = sop.steps(SopLibrary.GENERAL).filter { whenHolds(it, form) }
        val ordered = all.filter { it.immediate } + all.filterNot { it.immediate } + general
        return ordered.distinctBy { it.id }.filterNot { isDone(it, actionTokens) }
    }

    /** Top-N candidates verbatim with `{location}` substituted. */
    fun recommend(form: IncidentForm, max: Int = defaultMax): List<Recommendation> =
        candidates(form).take(max).map {
            Recommendation(it.id, render(it.text, form), RecommendationSource.SOP_TEMPLATE, it.immediate)
        }

    /** Emergency banner steps (§10.7): every `immediate` step of the category (no `when` filter), else OTHER's. */
    fun emergencySteps(category: String?): List<SopStep> {
        val steps = sop.steps(category ?: "OTHER").filter { it.immediate }
        return steps.ifEmpty { sop.steps("OTHER").filter { it.immediate } }
    }

    fun render(text: String, form: IncidentForm): String =
        if (!text.contains("{location}")) text else text.replace("{location}", form.string("location") ?: "the area")

    fun whenHolds(step: SopStep, form: IncidentForm): Boolean = step.whenConditions.all { (field, allowed) ->
        val st = form.state(field)
        when {
            st.status == FieldStatus.MISSING -> true
            st.status == FieldStatus.UNKNOWN -> "UNKNOWN" in allowed || "*" in allowed
            st.value == null -> true
            "*" in allowed -> true
            else -> {
                val values = when (val v = st.value) {
                    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
                    is JsonPrimitive -> listOf(v.content)
                    else -> emptyList()
                }
                values.any { it.uppercase() in allowed.map { a -> a.uppercase() } }
            }
        }
    }

    private fun contentTokens(s: String): Set<String> =
        Fuzzy.tokens(s).filter { it !in Stopwords.ALL }.toSet()

    private fun tokenMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val (s, l) = if (a.length <= b.length) a to b else b to a
        return s.length >= 4 && l.startsWith(s)
    }

    fun isDone(step: SopStep, actionTokens: List<Set<String>>): Boolean {
        if (step.doneKeywords.isEmpty() || actionTokens.isEmpty()) return false
        return step.doneKeywords.any { kw ->
            val kt = contentTokens(kw)
            if (kt.isEmpty()) return@any false
            actionTokens.any { at -> kt.count { k -> at.any { tokenMatch(k, it) } }.toDouble() / kt.size >= DONE_OVERLAP }
        }
    }
}
