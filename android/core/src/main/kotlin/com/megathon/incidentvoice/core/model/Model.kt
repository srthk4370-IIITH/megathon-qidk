package com.megathon.incidentvoice.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** PRD §14.1. */
enum class FieldStatus { MISSING, FILLED, INFERRED, UNKNOWN, USER_EDITED, DEFAULTED }

/** PRD §14.1. */
enum class FieldSource { LLM_EXTRACT, LLM_PATCH, RULE, USER, PROFILE, SYSTEM }

/** PRD §14.1. [value] is null when MISSING or UNKNOWN. */
data class FieldState(
    val key: String,
    val value: JsonElement?,
    val status: FieldStatus,
    val source: FieldSource,
    val turnId: Long?,
    val grounded: Boolean = true,
    val askedCount: Int = 0,
) {
    companion object {
        fun missing(key: String, askedCount: Int = 0) =
            FieldState(key, null, FieldStatus.MISSING, FieldSource.SYSTEM, null, true, askedCount)
    }
}

enum class Severity {
    LOW, MEDIUM, HIGH, CRITICAL;

    companion object {
        fun parse(s: String?): Severity? = s?.let { v -> entries.firstOrNull { it.name == v.uppercase() } }
    }
}

enum class TimeGranularity { MINUTE, HOUR, DAYPART, DAY }

/** PRD §15.1 output. [defaulted] = rule T10 (no usable time phrase → report time). */
data class ResolvedTime(
    val instant: Instant?,
    val approximate: Boolean,
    val granularity: TimeGranularity,
    val matchedRule: String,
    val defaulted: Boolean = false,
)

enum class TurnKind { NARRATION, FOLLOWUP_ANSWER, FIELD_EDIT, TYPED }

/** One conversational turn (narration or follow-up answer) — mirrors the `turns` table (§21). */
data class Turn(
    val idx: Int,
    val kind: TurnKind,
    val text: String,
    val lang: String? = null,
    val question: String? = null,
    val askedFields: List<String> = emptyList(),
    val llmRaw: String? = null,
    val asrMs: Long? = null,
    val llmMs: Long? = null,
    val at: Instant? = null,
)

enum class RecommendationSource { SOP_TEMPLATE, SOP_TAILORED, CUSTOM }

@Serializable
data class Recommendation(
    val sop: String,
    val text: String,
    val source: RecommendationSource = RecommendationSource.SOP_TEMPLATE,
    val immediate: Boolean = false,
    val done: Boolean = false,
)

/**
 * The incident draft. All fields (common and category `details`) live in [fields], keyed by taxonomy key —
 * keys are globally unique (§10.1.3). `occurred_at` holds an ISO-8601 offset date-time string; the spoken phrase is
 * [timeExpression]. Use the taxonomy to split common vs details ([details]).
 */
data class IncidentForm(
    val reportedAt: Instant,
    val lang: String = "en",
    val fields: Map<String, FieldState> = emptyMap(),
    val timeExpression: String? = null,
    val occurredAtApprox: Boolean = false,
    val locationId: String? = null,
    /** Registry id offered as "Did you mean …?" (score 0.70–0.85). */
    val locationSuggestionId: String? = null,
    val emergencyFlag: Boolean = false,
    /** Rule / consistency flags for the review screen, e.g. "INJURIES_RAISED_BY_RULE". */
    val flags: List<String> = emptyList(),
) {
    fun state(key: String): FieldState = fields[key] ?: FieldState.missing(key)
    fun value(key: String): JsonElement? = fields[key]?.value
    fun status(key: String): FieldStatus = fields[key]?.status ?: FieldStatus.MISSING

    /** String content of a primitive value (enum/string/number/bool) or null. */
    fun string(key: String): String? = (value(key) as? JsonPrimitive)?.content

    /** Enum value; `UNKNOWN` status maps to "UNKNOWN". */
    fun enumValue(key: String): String? =
        if (status(key) == FieldStatus.UNKNOWN) "UNKNOWN" else string(key)

    fun stringList(key: String): List<String> = when (val v = value(key)) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> listOf(v.content)
        else -> emptyList()
    }

    val incidentType: String? get() = string("incident_type")
    val secondaryTypes: List<String> get() = stringList("secondary_types")
    /** Primary + secondary category codes (deduped). */
    val types: List<String> get() = (listOfNotNull(incidentType) + secondaryTypes).distinct()
    val severity: Severity? get() = Severity.parse(string("severity"))

    fun with(state: FieldState): IncidentForm = copy(fields = fields + (state.key to state))
    fun withFlag(flag: String): IncidentForm = if (flag in flags) this else copy(flags = flags + flag)

    /** Category `details` object (category fields with values), keyed in insertion order. */
    fun details(categoryKeys: Set<String>): JsonObject =
        JsonObject(fields.filter { it.key in categoryKeys && it.value.value != null }.mapValues { it.value.value!! })
}
