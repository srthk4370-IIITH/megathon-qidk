package com.megathon.incidentvoice.core.validation

import com.megathon.incidentvoice.core.config.FieldDef
import com.megathon.incidentvoice.core.config.FieldTypes
import com.megathon.incidentvoice.core.config.Taxonomy
import com.megathon.incidentvoice.core.model.FieldStatus
import com.megathon.incidentvoice.core.model.IncidentForm
import com.megathon.incidentvoice.core.model.Severity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

data class FieldIssue(val key: String, val reason: String)

/** PRD §14.1. */
data class ValidationResult(
    val complete: Boolean,
    val required: List<String>,
    val missing: List<String>,
    val invalid: List<FieldIssue>,
    val progress: Float,
)

/** PRD §14.1. [fields] are taxonomy keys; [lang] is the language [text] should be spoken in. */
data class FollowUpQuestion(val fields: List<String>, val textEn: String, val textHinglish: String) {
    fun text(lang: String): String = if (lang == "en") textEn else textHinglish
}

/** Reasons used in [FieldIssue.reason]. */
object IssueReasons {
    const val FUTURE_TIME = "FUTURE_TIME"
    const val TOO_OLD = "TOO_OLD"
    const val BAD_TIME = "BAD_TIME"
    const val NOT_IN_ENUM = "NOT_IN_ENUM"
    const val TOO_SHORT = "TOO_SHORT"
    const val EMPTY_LIST = "EMPTY_LIST"
    const val BAD_TYPE = "BAD_TYPE"
    const val UNKNOWN_NOT_ALLOWED = "UNKNOWN_NOT_ALLOWED"
}

/** Rule-based validator, PRD §14. Pure function of (form, taxonomy, now). */
object Validator {
    /** occurred_at window (§14.2). */
    val FUTURE_TOLERANCE: Duration = Duration.ofMinutes(5)
    val MAX_AGE: Duration = Duration.ofDays(30)
    const val MAX_ASKS = 2

    private val SATISFYING = setOf(
        FieldStatus.FILLED, FieldStatus.INFERRED, FieldStatus.UNKNOWN, FieldStatus.USER_EDITED, FieldStatus.DEFAULTED,
    )
    /** Fields for which UNKNOWN does not satisfy (§14.2). */
    private val UNKNOWN_NOT_SATISFYING = setOf("incident_type", "description")

    fun validate(form: IncidentForm, tx: Taxonomy, now: Instant): ValidationResult {
        val required = tx.mandatoryKeys(form.types.filter { tx.isCategory(it) })
        val invalid = mutableListOf<FieldIssue>()
        // Validity of every present field we know about (required or not).
        for ((key, st) in form.fields) {
            if (st.status == FieldStatus.MISSING || st.status == FieldStatus.UNKNOWN) continue
            val def = tx.field(key) ?: continue
            checkValue(def, st.value, tx, now)?.let { invalid += FieldIssue(key, it) }
        }
        val invalidKeys = invalid.map { it.key }.toSet()
        val missing = mutableListOf<String>()
        for (key in required) {
            val st = form.state(key)
            val ok = when {
                st.status !in SATISFYING -> false
                st.status == FieldStatus.UNKNOWN -> key !in UNKNOWN_NOT_SATISFYING
                else -> key !in invalidKeys && st.value != null
            }
            if (!ok) missing += key
        }
        // UNKNOWN on incident_type/description is reported as an issue for the review screen.
        for (k in UNKNOWN_NOT_SATISFYING) if (k in required && form.status(k) == FieldStatus.UNKNOWN) {
            invalid += FieldIssue(k, IssueReasons.UNKNOWN_NOT_ALLOWED)
        }
        val sorted = sortMissing(missing, tx)
        val satisfied = required.size - sorted.size
        return ValidationResult(
            complete = sorted.isEmpty(),
            required = required,
            missing = sorted,
            invalid = invalid,
            progress = if (required.isEmpty()) 1f else satisfied.toFloat() / required.size,
        )
    }

    /** §14.3 sort key: (isCommon ? 0 : 1, group order, priority, key). */
    fun sortMissing(keys: List<String>, tx: Taxonomy): List<String> = keys.sortedWith(
        compareBy<String>(
            { if (tx.field(it)?.isCommon != false) 0 else 1 },
            { tx.groupIndex(tx.field(it)?.group) },
            { tx.field(it)?.priority ?: Int.MAX_VALUE },
            { it },
        ),
    )

    /** Returns an issue reason, or null when valid (§14.2). */
    fun checkValue(def: FieldDef, value: JsonElement?, tx: Taxonomy, now: Instant): String? {
        if (value == null) return IssueReasons.BAD_TYPE
        return when (def.type) {
            FieldTypes.ENUM -> {
                val s = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return IssueReasons.BAD_TYPE
                if (def.enumValues == null || s in def.enumValues) null else IssueReasons.NOT_IN_ENUM
            }
            FieldTypes.ENUM_CATEGORY -> {
                val s = (value as? JsonPrimitive)?.content ?: return IssueReasons.BAD_TYPE
                if (tx.isCategory(s)) null else IssueReasons.NOT_IN_ENUM
            }
            FieldTypes.ENUM_CATEGORY_LIST -> {
                val arr = value as? JsonArray ?: return IssueReasons.BAD_TYPE
                if (arr.all { tx.isCategory((it as? JsonPrimitive)?.content) }) null else IssueReasons.NOT_IN_ENUM
            }
            FieldTypes.STRING -> {
                val s = (value as? JsonPrimitive)?.content ?: return IssueReasons.BAD_TYPE
                if (visibleLength(s) >= 2) null else IssueReasons.TOO_SHORT
            }
            FieldTypes.STRING_LIST -> {
                val arr = value as? JsonArray ?: return IssueReasons.BAD_TYPE
                when {
                    arr.isEmpty() -> IssueReasons.EMPTY_LIST
                    arr.any { visibleLength((it as? JsonPrimitive)?.content ?: "") < 2 } -> IssueReasons.TOO_SHORT
                    else -> null
                }
            }
            FieldTypes.PERSON_LIST, FieldTypes.WITNESS_LIST -> {
                val arr = value as? JsonArray ?: return IssueReasons.BAD_TYPE
                when {
                    arr.isEmpty() -> IssueReasons.EMPTY_LIST
                    arr.any { it !is JsonObject || it.isEmpty() } -> IssueReasons.BAD_TYPE
                    else -> null
                }
            }
            FieldTypes.INT -> {
                val n = (value as? JsonPrimitive)?.longOrNull ?: return IssueReasons.BAD_TYPE
                if (def.min != null && n < def.min) IssueReasons.BAD_TYPE else null
            }
            FieldTypes.BOOL -> if ((value as? JsonPrimitive)?.booleanOrNull != null) null else IssueReasons.BAD_TYPE
            FieldTypes.TIME_EXPRESSION -> {
                val s = (value as? JsonPrimitive)?.content ?: return IssueReasons.BAD_TIME
                val t = parseInstant(s) ?: return IssueReasons.BAD_TIME
                when {
                    t.isAfter(now.plus(FUTURE_TOLERANCE)) -> IssueReasons.FUTURE_TIME
                    t.isBefore(now.minus(MAX_AGE)) -> IssueReasons.TOO_OLD
                    else -> null
                }
            }
            else -> null
        }
    }

    fun parseInstant(s: String): Instant? =
        runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull() ?: runCatching { Instant.parse(s) }.getOrNull()

    private fun visibleLength(s: String): Int = s.count { !it.isWhitespace() && !it.isISOControl() }

    /**
     * PRD §14.3–§14.4. Picks the first askable missing field and, if possible, a partner of the same group.
     * Emergency mode (explicit flag or severity CRITICAL, D-57) only considers common mandatory fields.
     * Returns null when nothing is askable (complete, or everything left was asked [MAX_ASKS] times).
     */
    fun nextQuestion(result: ValidationResult, form: IncidentForm, tx: Taxonomy, emergencyMode: Boolean): FollowUpQuestion? {
        val emergency = emergencyMode || form.severity == Severity.CRITICAL
        val candidates = result.missing.filter { key ->
            val def = tx.field(key) ?: return@filter false
            form.state(key).askedCount < MAX_ASKS &&
                def.qEn != null &&
                (!emergency || (def.isCommon && key in tx.commonMandatoryKeys))
        }
        val first = candidates.firstOrNull() ?: return null
        val firstGroup = tx.field(first)?.group
        val partner = candidates.firstOrNull { it != first && firstGroup != null && tx.field(it)?.group == firstGroup }
        if (partner != null) {
            tx.pairTemplate(first, partner)?.let { return FollowUpQuestion(it.fields, it.en, it.hinglish) }
            return groupRule(first, partner, tx)
        }
        val d = tx.field(first)!!
        return FollowUpQuestion(listOf(first), d.qEn!!, d.qHinglish ?: d.qEn)
    }

    /** §11.2 group pair rule. */
    fun groupRule(a: String, b: String, tx: Taxonomy): FollowUpQuestion {
        val da = tx.field(a)!!
        val db = tx.field(b)!!
        val en = da.qEn!!.trimEnd().removeSuffix("?") + ", and " + db.qEn!!.trim().replaceFirstChar { it.lowercaseChar() }
        val hiA = da.qHinglish ?: da.qEn
        val hiB = db.qHinglish ?: db.qEn
        val hi = hiA.trimEnd().removeSuffix("?") + ", aur " + hiB.trim()
        return FollowUpQuestion(listOf(a, b), en, hi)
    }
}
