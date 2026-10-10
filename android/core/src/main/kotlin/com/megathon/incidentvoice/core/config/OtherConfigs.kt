package com.megathon.incidentvoice.core.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

data class SopStep(
    val id: String,
    val text: String,
    val immediate: Boolean,
    /** field -> allowed values; "*" = any present value. Empty = always. */
    val whenConditions: Map<String, List<String>>,
    val doneKeywords: List<String>,
    val category: String,
)

class SopLibrary(
    val version: Int,
    val disclaimer: String,
    /** Category code (incl. GENERAL) -> steps, in file order. */
    val categories: Map<String, List<SopStep>>,
) {
    fun steps(code: String): List<SopStep> = categories[code].orEmpty()
    fun step(id: String): SopStep? = categories.values.asSequence().flatten().firstOrNull { it.id == id }

    companion object {
        const val GENERAL = "GENERAL"
    }
}

data class SiteLocation(
    val id: String,
    val name: String,
    val building: String?,
    val floor: String?,
    val aliases: List<String>,
)

class SiteRegistry(val version: Int, val site: String, val locations: List<SiteLocation>) {
    fun byId(id: String?): SiteLocation? = locations.firstOrNull { it.id == id }
}

/**
 * `app_config.json`, kept raw ([raw]) with typed accessors for the values :core needs.
 * The app/engine read anything else directly from [raw].
 */
class AppConfig(val raw: JsonObject) {
    private fun path(vararg keys: String): JsonElement? {
        var cur: JsonElement = raw
        for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
        return cur
    }

    fun int(vararg keys: String, default: Int): Int = (path(*keys) as? JsonPrimitive)?.intOrNull ?: default
    fun double(vararg keys: String, default: Double): Double = (path(*keys) as? JsonPrimitive)?.doubleOrNull ?: default
    fun bool(vararg keys: String, default: Boolean): Boolean = (path(*keys) as? JsonPrimitive)?.booleanOrNull ?: default
    fun string(vararg keys: String, default: String): String =
        (path(*keys) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default
    fun obj(vararg keys: String): JsonObject? = path(*keys)?.let { it as? JsonObject }

    val version: Int get() = int("version", default = 1)
    fun maxTokens(task: String): Int = int("llm", "max_tokens", task.lowercase(), default = 320)
    val maxFollowupRounds: Int get() = int("conversation", "max_followup_rounds", default = 3)
    val recommendMaxItems: Int get() = int("recommend", "max_items", default = 6)
    val recommendMode: String get() = string("recommend", "mode", default = "templates")
    val promptVariant: String get() = string("models", "prompt_variant", default = "base")
    val maxNarrationSeconds: Int get() = int("asr", "max_narration_s", default = 30)
    fun sampler(name: String): JsonObject? = obj("llm", "samplers", name)
}

/** Everything :core needs, loaded once. */
class CoreConfig(
    val taxonomy: Taxonomy,
    val sop: SopLibrary,
    val sites: SiteRegistry,
    val app: AppConfig,
    /** Reader for prompt templates and other files under the config root. */
    val reader: (String) -> String,
)

internal fun JsonObject.objOrNull(k: String): JsonObject? = this[k]?.let { runCatching { it.jsonObject }.getOrNull() }
