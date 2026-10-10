package com.megathon.incidentvoice.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class FieldStatus { MISSING, FILLED, INFERRED, UNKNOWN, USER_EDITED, DEFAULTED }
enum class FieldSource { LLM_EXTRACT, LLM_PATCH, RULE, USER, PROFILE, SYSTEM }

@Serializable
data class FieldState(
    val key: String,
    val value: JsonElement? = null,
    val status: FieldStatus = FieldStatus.MISSING,
    val source: FieldSource = FieldSource.SYSTEM,
    val turnId: Long? = null,
    val grounded: Boolean = true,
    val askedCount: Int = 0,
) {
    val satisfiedStatus: Boolean
        get() = status != FieldStatus.MISSING
}

/**
 * The incident form. One uniform map `key -> FieldState` for common AND category fields.
 * Value encodings:
 *  - strings / enums: JsonPrimitive(String)
 *  - ints: JsonPrimitive(Number); bools: JsonPrimitive(Boolean)
 *  - lists: JsonArray of JsonPrimitive(String); `persons`, `witnesses`: JsonArray of JsonObject
 *  - `occurred_at`: JsonPrimitive(epoch millis) once resolved; the raw phrase lives in [timeExpression]
 */
@Serializable
data class IncidentForm(
    val lang: String = "en",
    val fields: Map<String, FieldState> = emptyMap(),
    val timeExpression: String? = null,
    val occurredApprox: Boolean = false,
    val otherIncidents: List<String> = emptyList(),
    val locationId: String? = null,
    val severitySource: FieldSource? = null,
    val locationSuggestionId: String? = null,
) {
    fun state(key: String): FieldState? = fields[key]
    fun value(key: String): JsonElement? = fields[key]?.value
    fun str(key: String): String? = (value(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    val incidentType: String? get() = str("incident_type")
    val secondaryTypes: List<String>
        get() = (value("secondary_types") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    val types: List<String> get() = listOfNotNull(incidentType) + secondaryTypes

    fun with(state: FieldState): IncidentForm = copy(fields = fields + (state.key to state))
    fun without(key: String): IncidentForm = copy(fields = fields - key)

    /** Compact `{key: value}` of satisfied fields only (patch prompt `form_compact_json`, §C.5). */
    fun compact(): Map<String, JsonElement> =
        fields.filterValues { it.status != FieldStatus.MISSING && it.value != null }
            .mapValues { it.value.value!! }
}

fun strValue(s: String): JsonElement = JsonPrimitive(s)
fun listValue(items: List<String>): JsonElement = JsonArray(items.map { JsonPrimitive(it) })
