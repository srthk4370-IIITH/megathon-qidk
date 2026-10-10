package com.megathon.incidentvoice.core

import com.megathon.incidentvoice.core.config.ConfigLoader
import com.megathon.incidentvoice.core.config.CoreConfig
import com.megathon.incidentvoice.core.llm.FormMerger
import com.megathon.incidentvoice.core.llm.PromptBuilder
import com.megathon.incidentvoice.core.llm.ToolCallParser
import com.megathon.incidentvoice.core.model.FieldSource
import com.megathon.incidentvoice.core.model.FieldState
import com.megathon.incidentvoice.core.model.FieldStatus
import com.megathon.incidentvoice.core.model.IncidentForm
import com.megathon.incidentvoice.core.normalize.LocationMatcher
import com.megathon.incidentvoice.core.normalize.TimeResolver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Shared fixtures. The repo `config/` dir is found by walking up from the working directory. */
object T {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    val configDir: File by lazy {
        var d: File? = File("").absoluteFile
        while (d != null) {
            val c = File(d, "config/taxonomy.json")
            if (c.isFile) return@lazy File(d, "config")
            d = d.parentFile
        }
        error("config/ not found above ${File("").absolutePath}")
    }
    val loader: ConfigLoader by lazy { ConfigLoader.fromDirectory(configDir) }
    val cfg: CoreConfig by lazy { loader.loadAll() }
    val tx get() = cfg.taxonomy
    val sop get() = cfg.sop
    val sites get() = cfg.sites

    val timeResolver = TimeResolver(ZONE)
    val parser by lazy { ToolCallParser(tx) }
    val matcher by lazy { LocationMatcher(sites) }
    val merger by lazy { FormMerger(tx, timeResolver, matcher) }
    val prompts by lazy { PromptBuilder(tx, loader::read) }

    /** 2026-10-10 10:07:12 IST (slide demo report time; a Saturday). */
    val NOW: Instant = ZonedDateTime.of(2026, 10, 10, 10, 7, 12, 0, ZONE).toInstant()

    fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Instant = ZonedDateTime.of(y, mo, d, h, mi, s, 0, ZONE).toInstant()

    fun str(s: String) = JsonPrimitive(s)
    fun list(vararg s: String) = JsonArray(s.map { JsonPrimitive(it) })

    fun filled(key: String, v: JsonElement, status: FieldStatus = FieldStatus.FILLED, asked: Int = 0) =
        FieldState(key, v, status, FieldSource.LLM_EXTRACT, 0, true, asked)

    fun form(vararg fields: Pair<String, Any?>, lang: String = "en", now: Instant = NOW): IncidentForm {
        var f = IncidentForm(reportedAt = now, lang = lang)
        for ((k, v) in fields) {
            f = f.with(
                when (v) {
                    null -> FieldState(k, null, FieldStatus.UNKNOWN, FieldSource.USER, null)
                    is FieldState -> v
                    is JsonElement -> filled(k, v)
                    is List<*> -> filled(k, JsonArray(v.map { JsonPrimitive(it as String) }))
                    else -> filled(k, JsonPrimitive(v.toString()))
                },
            )
        }
        return f
    }

    const val SLIDE_TRANSCRIPT =
        "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area."
    const val SLIDE_OUTPUT =
        """{"name":"fill_incident_form","parameters":{"lang":"en","incident_type":"WATER","title":"Water leak near Conference Room 2","description":"A water leak occurred near Conference Room 2. Maintenance has isolated the area.","location":"near Conference Room 2","time":"around 10 AM","actions_taken":["Maintenance isolated the area"]}}"""
}
