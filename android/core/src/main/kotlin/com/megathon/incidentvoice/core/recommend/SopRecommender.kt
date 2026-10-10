package com.megathon.incidentvoice.core.recommend

import com.megathon.incidentvoice.core.config.SopLibrary
import com.megathon.incidentvoice.core.config.SopStep
import com.megathon.incidentvoice.core.model.FieldStatus
import com.megathon.incidentvoice.core.model.IncidentForm
import com.megathon.incidentvoice.core.model.Recommendation
import com.megathon.incidentvoice.core.model.RecommendationSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * P0 recommendations = SOP templates (PRD §12.2 step 1 + rev 1.1 R1-06). Mirrors `ml/common/sop.py` exactly:
 *  - `when` is evaluated for EVERY step (immediate ones too). A condition on a MISSING field passes, except
 *    `"*"` which means "has a value" (MISSING fails, UNKNOWN passes). UNKNOWN compares as "UNKNOWN";
 *    list values pass if any element matches; booleans compare as YES/NO.
 *  - Categories: primary, secondary types (form order), then GENERAL; steps in file order; duplicate ids skipped;
 *    then a stable sort immediate-first.
 *  - Done removal: tokens = lower-case `[a-z0-9]+`; for each done keyword, overlap = |kw ∩ union(actions)| / |kw|;
 *    the step is removed when the max overlap ≥ 0.6.
 *  - P0 list = first `recommend.max_items` (6) candidates, `{location}` substituted.
 */
class SopRecommender(private val sop: SopLibrary, private val defaultMax: Int = 6) {

    companion object {
        const val DONE_OVERLAP = 0.6
        private val TOK = Regex("[a-z0-9]+")
        fun tokens(s: String): Set<String> = TOK.findAll(s.lowercase()).map { it.value }.toSet()
    }

    fun candidates(form: IncidentForm): List<SopStep> {
        val types = form.types.ifEmpty { listOf("OTHER") }
        val st = form.state("actions_taken")
        val actions = if (st.status == FieldStatus.MISSING || st.status == FieldStatus.UNKNOWN) emptyList() else form.stringList("actions_taken")
        val seen = HashSet<String>()
        val out = mutableListOf<SopStep>()
        for (cat in types + SopLibrary.GENERAL) for (step in sop.steps(cat)) {
            if (step.id in seen) continue
            if (!whenHolds(step, form)) continue
            if (isDone(step, actions)) continue
            seen += step.id
            out += step
        }
        return out.sortedBy { if (it.immediate) 0 else 1 } // stable
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
            st.status == FieldStatus.MISSING -> "*" !in allowed
            "*" in allowed -> true
            st.status == FieldStatus.UNKNOWN -> "UNKNOWN" in allowed
            else -> when (val v = st.value) {
                null -> "*" !in allowed
                is JsonArray -> v.any { (it as? JsonPrimitive)?.content in allowed }
                is JsonPrimitive -> {
                    val s = if (!v.isString && v.booleanOrNull != null) (if (v.booleanOrNull == true) "YES" else "NO") else v.content
                    s in allowed
                }
                else -> false
            }
        }
    }

    fun isDone(step: SopStep, actionsTaken: List<String>): Boolean {
        if (step.doneKeywords.isEmpty() || actionsTaken.isEmpty()) return false
        val at = actionsTaken.flatMap { tokens(it) }.toSet()
        var best = 0.0
        for (kw in step.doneKeywords) {
            val kt = tokens(kw)
            if (kt.isNotEmpty()) best = maxOf(best, kt.count { it in at }.toDouble() / kt.size)
        }
        return best >= DONE_OVERLAP
    }
}
