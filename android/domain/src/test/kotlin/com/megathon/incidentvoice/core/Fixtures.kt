package com.megathon.incidentvoice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object Fx {
    val dir = File(System.getProperty("iv.configDir") ?: error("iv.configDir not set"))
    val tx: Taxonomy by lazy { Taxonomy.parse(File(dir, "taxonomy.json").readText()) }
    val registry: SiteRegistry by lazy { SiteRegistry.parse(File(dir, "site_registry.json").readText()) }
    val sop: SopLibrary by lazy { SopLibrary.parse(File(dir, "sop.json").readText()) }
    val templates: PromptTemplates by lazy { PromptTemplates.load { File(dir, it).readText() } }
    val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    /** Saturday 2026-10-10 10:07 IST */
    val now: Instant = ZonedDateTime.of(2026, 10, 10, 10, 7, 0, 0, zone).toInstant()
    val times = TimeResolver(zone)
    val merger by lazy { FormMerger(tx, times, LocationMatcher(registry)) }
    val parser by lazy { ToolCallParser(tx) }

    fun s(v: String): JsonElement = JsonPrimitive(v)
    fun ms(i: Instant): JsonElement = JsonPrimitive(i.toEpochMilli())

    /** Build a form: key -> value (String → FILLED, null → UNKNOWN). */
    fun form(vararg kv: Pair<String, Any?>, lang: String = "en"): IncidentForm {
        var f = IncidentForm(lang = lang)
        for ((k, v) in kv) {
            val st = when (v) {
                null -> FieldState(k, null, FieldStatus.UNKNOWN, FieldSource.USER)
                is String -> FieldState(k, JsonPrimitive(v), FieldStatus.FILLED, FieldSource.LLM_EXTRACT)
                is Long -> FieldState(k, JsonPrimitive(v), FieldStatus.FILLED, FieldSource.LLM_EXTRACT)
                is List<*> -> FieldState(k, JsonArray(v.map { JsonPrimitive(it as String) }), FieldStatus.FILLED, FieldSource.LLM_EXTRACT)
                is FieldState -> v
                else -> error("bad")
            }
            f = f.with(st)
        }
        return f
    }

    /** Slide example after extraction (PRD §13.5). */
    fun slide(): IncidentForm = form(
        "incident_type" to "WATER", "description" to "A water leak occurred near Conference Room 2.",
        "location" to "near Conference Room 2", "occurred_at" to now.minusSeconds(7 * 60).toEpochMilli(),
    )
}
