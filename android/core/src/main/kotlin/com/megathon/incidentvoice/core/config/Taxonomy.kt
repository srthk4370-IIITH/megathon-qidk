package com.megathon.incidentvoice.core.config

import kotlinx.serialization.json.JsonObject

/** Field types used in `taxonomy.json` (`type`). */
object FieldTypes {
    const val ENUM = "enum"
    const val ENUM_CATEGORY = "enum_category"
    const val ENUM_CATEGORY_LIST = "enum_category_list"
    const val STRING = "string"
    const val STRING_LIST = "string_list"
    const val INT = "int"
    const val BOOL = "bool"
    const val TIME_EXPRESSION = "time_expression"
    const val PERSON_LIST = "person_list"
    const val WITNESS_LIST = "witness_list"
}

/**
 * One field from `taxonomy.json` (common or category). [category] is null for common fields.
 * Item specs of `person_list` / `witness_list` are kept raw in [item].
 */
data class FieldDef(
    val key: String,
    val type: String,
    val mandatory: Boolean,
    val group: String?,
    val priority: Int?,
    val label: String,
    val enumValues: List<String>?,
    val qEn: String?,
    val qHinglish: String?,
    val llmKey: String?,
    val maxLen: Int?,
    val maxItems: Int?,
    val min: Int?,
    val system: Boolean,
    val category: String?,
    val item: JsonObject?,
) {
    val isCommon: Boolean get() = category == null
    /** Key the LLM uses for this field (`time` for `occurred_at`). */
    val promptKey: String get() = llmKey ?: key
}

data class CategoryDef(
    val code: String,
    val label: String,
    val definition: String,
    val searchCues: List<String>,
    val extraMandatoryCommon: List<String>,
    val fields: List<FieldDef>,
)

data class PairTemplate(val fields: List<String>, val en: String, val hinglish: String)

data class EmergencyConfig(
    val keywords: List<String>,
    val exclude: List<String>,
    /** Ordered (regex, category) pairs. */
    val categoryHint: List<Pair<String, String>>,
)

/** The loaded taxonomy. Nothing outside this class hard-codes field keys, enums or categories. */
class Taxonomy(
    val version: Int,
    val groupsOrder: List<String>,
    val commonFields: List<FieldDef>,
    val pairTemplates: List<PairTemplate>,
    val categories: List<CategoryDef>,
    /** Canonical enum value -> synonyms (lower-case phrases), in file order. */
    val enumSynonyms: Map<String, List<String>>,
    /** Level -> definition, in file order. */
    val severityRubric: Map<String, String>,
    val emergency: EmergencyConfig,
    /** reprompt id -> (lang -> text). */
    val reprompts: Map<String, Map<String, String>>,
) {
    private val byKey: Map<String, FieldDef> = buildMap {
        commonFields.forEach { put(it.key, it) }
        categories.forEach { c -> c.fields.forEach { put(it.key, it) } }
    }
    private val byPromptKey: Map<String, FieldDef> = buildMap {
        byKey.values.forEach { f -> f.llmKey?.let { put(it, f) } }
    }
    private val byCode: Map<String, CategoryDef> = categories.associateBy { it.code }

    val categoryCodes: List<String> = categories.map { it.code }
    val commonMandatoryKeys: List<String> = commonFields.filter { it.mandatory }.map { it.key }
    val allFields: List<FieldDef> get() = commonFields + categories.flatMap { it.fields }

    /** Look up a field by taxonomy key or by its LLM key (`time` -> `occurred_at`). */
    fun field(key: String): FieldDef? = byKey[key] ?: byPromptKey[key]

    fun category(code: String?): CategoryDef? = code?.let { byCode[it] }

    fun isCategory(code: String?): Boolean = code != null && byCode.containsKey(code)

    fun groupIndex(group: String?): Int = groupsOrder.indexOf(group).let { if (it < 0) groupsOrder.size else it }

    /** PRD §10.5: common mandatory ∪ mandatory fields of every type ∪ extra_mandatory_common, in stable order. */
    fun mandatoryKeys(types: List<String>): List<String> {
        val out = LinkedHashSet<String>(commonMandatoryKeys)
        for (t in types) {
            val c = byCode[t] ?: continue
            c.extraMandatoryCommon.forEach { out.add(it) }
            c.fields.filter { it.mandatory }.forEach { out.add(it.key) }
        }
        return out.toList()
    }

    /** Pair template whose field set equals {a, b} (order-insensitive). */
    fun pairTemplate(a: String, b: String): PairTemplate? =
        pairTemplates.firstOrNull { it.fields.size == 2 && it.fields.toSet() == setOf(a, b) }
}
