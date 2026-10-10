package com.megathon.incidentvoice.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Mirrors config/taxonomy.json (PRD Appendix A). Pure data; loaded once from assets. */
@Serializable
data class Question(val en: String = "", val hinglish: String = "") {
    fun text(lang: String): String = if (lang == "en" || lang.isBlank()) en else hinglish.ifBlank { en }
}

@Serializable
data class FieldDef(
    val key: String,
    val type: String,
    val mandatory: Boolean = false,
    val group: String? = null,
    val priority: Int = 100,
    val label: String = "",
    val q: Question? = null,
    val enum: List<String>? = null,
    @SerialName("llm_key") val llmKey: String? = null,
    @SerialName("max_items") val maxItems: Int? = null,
    @SerialName("max_len") val maxLen: Int? = null,
    val min: Int? = null,
    val system: Boolean = false,
    val item: JsonElement? = null,
) {
    /** Filled in by [Taxonomy] after load: true for common fields. */
    @kotlinx.serialization.Transient var isCommon: Boolean = false
    @kotlinx.serialization.Transient var category: String? = null
}

@Serializable
data class PairTemplate(val fields: List<String>, val en: String, val hinglish: String)

@Serializable
data class Category(
    val code: String,
    val label: String,
    val definition: String = "",
    @SerialName("search_cues") val searchCues: List<String> = emptyList(),
    @SerialName("extra_mandatory_common") val extraMandatoryCommon: List<String> = emptyList(),
    val fields: List<FieldDef> = emptyList(),
)

@Serializable
data class EmergencyConfig(
    val keywords: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    @SerialName("category_hint") val categoryHint: Map<String, String> = emptyMap(),
)

@Serializable
data class TaxonomyData(
    val version: Int,
    @SerialName("groups_order") val groupsOrder: List<String>,
    @SerialName("common_fields") val commonFields: List<FieldDef>,
    @SerialName("pair_templates") val pairTemplates: List<PairTemplate> = emptyList(),
    val categories: List<Category>,
    @SerialName("enum_synonyms") val enumSynonyms: Map<String, List<String>> = emptyMap(),
    @SerialName("severity_rubric") val severityRubric: Map<String, String> = emptyMap(),
    val emergency: EmergencyConfig = EmergencyConfig(),
    val reprompts: Map<String, Question> = emptyMap(),
)

class Taxonomy(val data: TaxonomyData) {
    val version get() = data.version
    val commonFields: List<FieldDef> = data.commonFields.onEach { it.isCommon = true }
    val categories: List<Category> = data.categories.onEach { c -> c.fields.forEach { it.category = c.code } }
    val categoryCodes: List<String> = categories.map { it.code }
    val pairTemplates get() = data.pairTemplates
    val emergency get() = data.emergency
    val reprompts get() = data.reprompts
    val severityRubric get() = data.severityRubric

    private val byKey: Map<String, FieldDef> =
        (commonFields + categories.flatMap { it.fields }).associateBy { it.key }
    private val byCode: Map<String, Category> = categories.associateBy { it.code }
    private val byLlmKey: Map<String, FieldDef> =
        commonFields.filter { it.llmKey != null }.associateBy { it.llmKey!! }

    fun field(key: String): FieldDef? = byKey[key] ?: byLlmKey[key]
    fun category(code: String): Category? = byCode[code]
    fun groupOrder(g: String?): Int = data.groupsOrder.indexOf(g).let { if (it < 0) data.groupsOrder.size else it }
    fun label(key: String): String = field(key)?.label?.ifBlank { null } ?: key

    /** §10.5 mandatory set resolution. */
    fun mandatory(primary: String?, secondary: List<String>): List<FieldDef> {
        val out = LinkedHashMap<String, FieldDef>()
        commonFields.filter { it.mandatory }.forEach { out[it.key] = it }
        val types = listOfNotNull(primary) + secondary
        for (t in types) {
            val c = byCode[t] ?: continue
            c.fields.filter { it.mandatory }.forEach { out[it.key] = it }
            c.extraMandatoryCommon.forEach { k -> byKey[k]?.let { out[k] = it } }
        }
        return out.values.toList()
    }

    /** Category detail fields of the chosen types only. */
    fun detailFields(types: List<String>): List<FieldDef> =
        types.distinct().mapNotNull { byCode[it] }.flatMap { it.fields }

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        fun parse(text: String): Taxonomy = Taxonomy(json.decodeFromString(TaxonomyData.serializer(), text))
    }
}
