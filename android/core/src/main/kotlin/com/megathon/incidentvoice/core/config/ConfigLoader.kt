package com.megathon.incidentvoice.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Loads the shared contracts from `config/`.
 *
 * @param reader reads a file given its path relative to the config root (e.g. `"taxonomy.json"`,
 *   `"prompts/extract.system.txt"`). Android: `{ p -> context.assets.open("config/$p").bufferedReader().readText() }`.
 *   Tests/JVM: `ConfigLoader.fromDirectory(File("../../config"))`.
 */
class ConfigLoader(private val reader: (String) -> String) {

    fun read(path: String): String = reader(path)

    fun loadAll(): CoreConfig = CoreConfig(loadTaxonomy(), loadSop(), loadSiteRegistry(), loadAppConfig(), reader)

    fun loadTaxonomy(): Taxonomy = parseTaxonomy(parseObject(reader("taxonomy.json")))
    fun loadSop(): SopLibrary = parseSop(parseObject(reader("sop.json")))
    fun loadSiteRegistry(): SiteRegistry = parseSites(parseObject(reader("site_registry.json")))
    fun loadAppConfig(): AppConfig = AppConfig(parseObject(reader("app_config.json")))

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** JVM convenience: reads from a directory on disk. */
        fun fromDirectory(dir: java.io.File): ConfigLoader =
            ConfigLoader { p -> java.io.File(dir, p).readText(Charsets.UTF_8) }

        fun parseObject(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

        private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
        private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
        private fun JsonElement?.strList(): List<String> =
            (this as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()

        private fun parseField(o: JsonObject, category: String?): FieldDef {
            val q = o["q"] as? JsonObject
            return FieldDef(
                key = o["key"].str() ?: error("field without key"),
                type = o["type"].str() ?: FieldTypes.STRING,
                mandatory = o["mandatory"].bool() ?: false,
                group = o["group"].str(),
                priority = o["priority"].int(),
                label = o["label"].str() ?: o["key"].str()!!,
                enumValues = (o["enum"] as? JsonArray)?.mapNotNull { it.str() },
                qEn = q?.get("en").str(),
                qHinglish = q?.get("hinglish").str(),
                llmKey = o["llm_key"].str(),
                maxLen = o["max_len"].int(),
                maxItems = o["max_items"].int(),
                min = o["min"].int(),
                system = o["system"].bool() ?: false,
                category = category,
                item = o["item"] as? JsonObject,
            )
        }

        fun parseTaxonomy(root: JsonObject): Taxonomy {
            val common = (root["common_fields"] as JsonArray).map { parseField(it.jsonObject, null) }
            val cats = (root["categories"] as JsonArray).map { el ->
                val c = el.jsonObject
                val code = c["code"].str()!!
                CategoryDef(
                    code = code,
                    label = c["label"].str() ?: code,
                    definition = c["definition"].str() ?: "",
                    searchCues = c["search_cues"].strList(),
                    extraMandatoryCommon = c["extra_mandatory_common"].strList(),
                    fields = (c["fields"] as? JsonArray)?.map { parseField(it.jsonObject, code) } ?: emptyList(),
                )
            }
            val pairs = (root["pair_templates"] as? JsonArray)?.map {
                val p = it.jsonObject
                PairTemplate(p["fields"].strList(), p["en"].str() ?: "", p["hinglish"].str() ?: p["en"].str() ?: "")
            } ?: emptyList()
            val syn = (root["enum_synonyms"] as? JsonObject)?.mapValues { (_, v) -> v.strList().map { it.lowercase() } }
                ?: emptyMap()
            val rubric = (root["severity_rubric"] as? JsonObject)?.mapValues { (_, v) -> v.str() ?: "" } ?: emptyMap()
            val em = root["emergency"] as? JsonObject
            val emergency = EmergencyConfig(
                keywords = em?.get("keywords").strList(),
                exclude = em?.get("exclude").strList(),
                categoryHint = (em?.get("category_hint") as? JsonObject)?.map { (k, v) -> k to (v.str() ?: "OTHER") }
                    ?: emptyList(),
            )
            val reprompts = (root["reprompts"] as? JsonObject)?.mapValues { (_, v) ->
                (v as? JsonObject)?.mapValues { (_, t) -> t.str() ?: "" } ?: emptyMap()
            } ?: emptyMap()
            return Taxonomy(
                version = root["version"].int() ?: 1,
                groupsOrder = root["groups_order"].strList(),
                commonFields = common,
                pairTemplates = pairs,
                categories = cats,
                enumSynonyms = syn,
                severityRubric = rubric,
                emergency = emergency,
                reprompts = reprompts,
            )
        }

        fun parseSop(root: JsonObject): SopLibrary {
            val cats = LinkedHashMap<String, List<SopStep>>()
            (root["categories"] as JsonObject).forEach { (code, arr) ->
                cats[code] = (arr as JsonArray).map {
                    val s = it.jsonObject
                    SopStep(
                        id = s["id"].str()!!,
                        text = s["text"].str() ?: "",
                        immediate = s["immediate"].bool() ?: false,
                        whenConditions = (s["when"] as? JsonObject)?.mapValues { (_, v) -> v.strList() } ?: emptyMap(),
                        doneKeywords = s["done_keywords"].strList(),
                        category = code,
                    )
                }
            }
            return SopLibrary(root["version"].int() ?: 1, root["disclaimer"].str() ?: "", cats)
        }

        fun parseSites(root: JsonObject): SiteRegistry = SiteRegistry(
            version = root["version"].int() ?: 1,
            site = root["site"].str() ?: "",
            locations = (root["locations"] as? JsonArray)?.map {
                val l = it.jsonObject
                SiteLocation(
                    id = l["id"].str()!!,
                    name = l["name"].str() ?: l["id"].str()!!,
                    building = l["building"].str(),
                    floor = l["floor"].str(),
                    aliases = l["aliases"].strList(),
                )
            } ?: emptyList(),
        )
    }
}
