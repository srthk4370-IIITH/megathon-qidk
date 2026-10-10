package com.megathon.incidentvoice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.Instant

data class FieldIssue(val key: String, val reason: String)

data class ValidationResult(
    val complete: Boolean,
    val required: List<String>,
    val missing: List<String>,
    val invalid: List<FieldIssue>,
    val progress: Float,
)

data class FollowUpQuestion(val fields: List<String>, val textEn: String, val textHinglish: String) {
    fun text(lang: String) = if (lang == "en" || lang.isBlank()) textEn else textHinglish
}

/** PRD §14. Pure Kotlin, no Android imports. */
object Validator {
    private val NEVER_UNKNOWN = setOf("incident_type", "description")
    private val FUTURE_SLACK = Duration.ofMinutes(5)
    private val MAX_AGE = Duration.ofDays(30)

    fun validate(form: IncidentForm, tx: Taxonomy, now: Instant): ValidationResult {
        val req = tx.mandatory(form.incidentType, form.secondaryTypes)
        val missing = ArrayList<String>()
        val invalid = ArrayList<FieldIssue>()
        var satisfied = 0
        for (def in req) {
            val st = form.state(def.key)
            val issue = check(def, st, now)
            when {
                issue == null -> satisfied++
                issue.reason == "MISSING" -> missing += def.key
                else -> { invalid += issue; missing += def.key }
            }
        }
        val sorted = sortMissing(missing, tx)
        return ValidationResult(
            complete = sorted.isEmpty(),
            required = req.map { it.key },
            missing = sorted,
            invalid = invalid,
            progress = if (req.isEmpty()) 1f else satisfied.toFloat() / req.size,
        )
    }

    /** null = satisfied. */
    private fun check(def: FieldDef, st: FieldState?, now: Instant): FieldIssue? {
        if (st == null || st.status == FieldStatus.MISSING) return FieldIssue(def.key, "MISSING")
        if (st.status == FieldStatus.UNKNOWN) {
            return if (def.key in NEVER_UNKNOWN) FieldIssue(def.key, "MISSING") else null
        }
        val v = st.value ?: return FieldIssue(def.key, "MISSING")
        if (def.key == "occurred_at") {
            val ms = (v as? JsonPrimitive)?.longOrNull ?: return FieldIssue(def.key, "INVALID")
            val t = Instant.ofEpochMilli(ms)
            return when {
                t.isAfter(now.plus(FUTURE_SLACK)) -> FieldIssue(def.key, "FUTURE_TIME")
                t.isBefore(now.minus(MAX_AGE)) -> FieldIssue(def.key, "TOO_OLD")
                else -> null
            }
        }
        return if (valid(def, v)) null else FieldIssue(def.key, "INVALID")
    }

    private fun valid(def: FieldDef, v: JsonElement, tx: Taxonomy? = null): Boolean = when (def.type) {
        "enum" -> (v as? JsonPrimitive)?.contentOrNull in (def.enum ?: emptyList())
        "enum_category" -> (v as? JsonPrimitive)?.contentOrNull?.let { it.isNotBlank() } == true
        "string", "time_expression" -> visibleLen((v as? JsonPrimitive)?.contentOrNull) >= 2
        "string_list" -> v is JsonArray && v.isNotEmpty() &&
            v.all { visibleLen((it as? JsonPrimitive)?.contentOrNull) >= 2 }
        "person_list", "witness_list", "object_list" -> v is JsonArray && v.isNotEmpty() && v.all { it is JsonObject }
        "int" -> (v as? JsonPrimitive)?.longOrNull != null
        "bool" -> (v as? JsonPrimitive)?.booleanOrNull != null
        else -> true
    }

    private fun visibleLen(s: String?) = s?.count { !it.isWhitespace() } ?: 0

    /** §14.3 sort: (isCommon ? 0 : 1, group order, priority, key). */
    fun sortMissing(keys: List<String>, tx: Taxonomy): List<String> =
        keys.sortedWith(
            compareBy<String>(
                { if (tx.field(it)?.isCommon == true) 0 else 1 },
                { tx.groupOrder(tx.field(it)?.group) },
                { tx.field(it)?.priority ?: 999 },
                { it },
            )
        )

    /** §14.4. Returns null when nothing is askable (all missing fields asked ≥2 times). */
    fun nextQuestion(result: ValidationResult, form: IncidentForm, tx: Taxonomy, emergencyMode: Boolean): FollowUpQuestion? {
        var cands = result.missing.filter { (form.state(it)?.askedCount ?: 0) < 2 }
        if (emergencyMode) cands = cands.filter { tx.field(it)?.isCommon == true }
        val first = cands.firstOrNull() ?: return null
        val firstGroup = tx.field(first)?.group
        val partner = cands.firstOrNull { it != first && firstGroup != null && tx.field(it)?.group == firstGroup }
        val pair = partner?.let { listOf(first, it) } ?: listOf(first)
        return buildQuestion(pair, tx)
    }

    internal fun buildQuestion(fields: List<String>, tx: Taxonomy): FollowUpQuestion {
        if (fields.size == 2) {
            tx.pairTemplates.firstOrNull { it.fields.toSet() == fields.toSet() }?.let {
                return FollowUpQuestion(fields, it.en, it.hinglish)
            }
            val q1 = tx.field(fields[0])?.q
            val q2 = tx.field(fields[1])?.q
            if (q1 != null && q2 != null) {
                val en = q1.en.trimEnd('?', ' ') + ", and " + q2.en.replaceFirstChar { it.lowercase() }
                val hi = q1.hinglish.trimEnd('?', ' ') + ", aur " + q2.hinglish
                return FollowUpQuestion(fields, en, hi)
            }
        }
        val f = fields[0]
        val q = tx.field(f)?.q
        val label = tx.label(f)
        return FollowUpQuestion(
            listOf(f),
            q?.en ?: "Please tell me: $label.",
            q?.hinglish?.ifBlank { null } ?: "Mujhe batayiye: $label.",
        )
    }
}
