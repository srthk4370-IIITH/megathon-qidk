package com.megathon.incidentvoice.ui

import com.megathon.incidentvoice.core.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

data class DisplayRow(val key: String, val label: String, val value: String, val status: FieldStatus, val required: Boolean = false)

/** Human-readable view of a form (detail + review). */
object FormDisplay {
    fun valueText(key: String, v: JsonElement?, form: IncidentForm, tx: Taxonomy): String {
        if (v == null) return "—"
        if (key == "occurred_at") {
            val ms = (v as? JsonPrimitive)?.longOrNull ?: return "—"
            return (if (form.occurredApprox) "≈ " else "") + fmtDateTime(ms) + (form.timeExpression?.let { " (\"$it\")" } ?: "")
        }
        return when (v) {
            is JsonArray -> v.joinToString(", ") { e ->
                if (e is JsonObject) personText(e) else (e as? JsonPrimitive)?.contentOrNull.orEmpty()
            }
            is JsonPrimitive -> v.booleanOrNull?.let { if (it) "Yes" else "No" } ?: v.contentOrNull.orEmpty()
            else -> v.toString()
        }
    }

    private fun personText(o: JsonObject): String {
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: "Unnamed"
        val role = (o["role"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        val inj = (o["injury"] as? JsonPrimitive)?.contentOrNull
        val contact = (o["contact"] as? JsonPrimitive)?.contentOrNull
        return buildString { append(name); if (role != null) append(" ($role)"); if (inj != null) append(" – $inj"); if (contact != null) append(" · $contact") }
    }

    /** Rows for every field that has a value or UNKNOWN status, common first. */
    fun rows(form: IncidentForm, tx: Taxonomy, hide: Set<String> = setOf("lang", "title", "description")): List<DisplayRow> {
        val order = tx.commonFields.map { it.key } + tx.detailFields(form.types).map { it.key }
        return order.mapNotNull { k ->
            if (k in hide) return@mapNotNull null
            val st = form.state(k) ?: return@mapNotNull null
            if (st.status == FieldStatus.MISSING) return@mapNotNull null
            val text = if (st.status == FieldStatus.UNKNOWN) "Unknown" else valueText(k, st.value, form, tx)
            DisplayRow(k, tx.label(k), text, st.status)
        }
    }
}
