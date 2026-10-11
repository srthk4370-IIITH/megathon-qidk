package com.megathon.incidentvoice.core

/** One tappable step on the response clock card. [doneAtMs] is null until it is tapped. */
data class ResponseStep(val id: String, val label: String, val doneAtMs: Long? = null) {
    val done: Boolean get() = doneAtMs != null
}

/** A step that was carried out, as stored in `response_actions`. */
data class ResponseAction(val stepId: String, val label: String, val doneAtMs: Long, val source: String = "TAP")

/**
 * Extra 1 — response clock (HANDBOOK "Extra optional work"). Pure rules:
 * when it starts, which steps it shows (only `immediate` steps of the category from sop.json, plus the fixed
 * "Call ERT" step), and how the timeline is worded. Nothing here comes from the LLM.
 */
object ResponseClock {
    const val ERT = "ERT"
    const val REMINDER = "Reminder. Call the emergency team now if anyone is in danger."
    const val DISCLAIMER = "Follow your site's emergency procedures. This checklist is guidance, not medical advice."
    private val ALWAYS = setOf("FIRE", "MEDICAL", "HAZMAT")

    /** Banner fired (keyword or CRITICAL), or FIRE / MEDICAL / HAZMAT, or ELECTRICAL with a shock. */
    fun shouldStart(form: IncidentForm, emergencyBanner: Boolean): Boolean {
        if (emergencyBanner || form.str("severity") == "CRITICAL") return true
        val types = form.types
        if (types.any { it in ALWAYS }) return true
        return "ELECTRICAL" in types && form.str("electrical_hazard") == "SHOCK"
    }

    /**
     * "Call ERT" first, then the immediate SOP steps of [category] (falls back to the banner's hint category while the
     * form has no type yet). Steps already done keep their time when the list is rebuilt for a new category.
     */
    fun steps(sop: SopLibrary, category: String?, ertNumber: String, done: List<ResponseAction> = emptyList()): List<ResponseStep> {
        val doneAt = done.associate { it.stepId to it.doneAtMs }
        val out = ArrayList<ResponseStep>()
        out += ResponseStep(ERT, "Call ERT ($ertNumber)", doneAt[ERT])
        sop.categories[category ?: ""].orEmpty().filter { it.immediate }.forEach { out += ResponseStep(it.id, it.text, doneAt[it.id]) }
        // keep done steps of a previous category visible
        done.filter { d -> out.none { it.id == d.stepId } }.forEach { out += ResponseStep(it.stepId, it.label, it.doneAtMs) }
        return out
    }

    fun mmss(elapsedMs: Long): String {
        val s = (elapsedMs.coerceAtLeast(0) / 1000)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    /** "Raise the fire alarm and call the emergency team / fire brigade." → "Raise the fire alarm". */
    fun shortLabel(stepId: String, label: String): String {
        if (stepId == ERT) return "ERT called"
        val head = label.split(Regex("(?i)\\s+(and|by|if|until|unless|from|to the)\\s+|[,;.(]")).first().trim()
        return head.split(' ').take(5).joinToString(" ").ifBlank { label.take(30) }
    }

    /** "Alarm 00:00 → ERT called 01:12 → Evacuate everyone 03:40" */
    fun timeline(startMs: Long, actions: List<ResponseAction>): String =
        (listOf("Alarm 00:00") + actions.sortedBy { it.doneAtMs }.map { "${shortLabel(it.stepId, it.label)} ${mmss(it.doneAtMs - startMs)}" }).joinToString(" → ")
}
