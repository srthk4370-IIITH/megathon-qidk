package com.megathon.incidentvoice.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Compact tool schemas, verbatim from PRD Appendix D.1–D.3. */
object ToolSchemas {
    const val FILL = """{"name":"fill_incident_form","description":"Fill the incident report form from the transcript. Omit unknown fields.","parameters":{"type":"object","properties":{"lang":{"type":"string","enum":["en","hinglish","hi"]},"incident_type":{"type":"string","enum":["FIRE","MEDICAL","WATER","ELECTRICAL","HAZMAT","EQUIPMENT","SECURITY","STRUCTURAL","ENVIRONMENTAL","VEHICLE","OTHER"]},"secondary_types":{"type":"array","items":{"type":"string"},"maxItems":2},"title":{"type":"string"},"description":{"type":"string"},"severity":{"type":"string","enum":["LOW","MEDIUM","HIGH","CRITICAL"]},"location":{"type":"string"},"time":{"type":"string","description":"time phrase as spoken, in English"},"injuries":{"type":"string","enum":["YES","NO","UNKNOWN"]},"injured_count":{"type":"integer"},"persons":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"role":{"type":"string","enum":["AFFECTED","INVOLVED","RESPONDER","REPORTER","OTHER"]},"injured":{"type":"boolean"},"injury":{"type":"string"}}}},"equipment":{"type":"array","items":{"type":"string"}},"actions_taken":{"type":"array","items":{"type":"string"}},"witnesses":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"contact":{"type":"string"}}}},"near_miss":{"type":"boolean"},"hazard_ongoing":{"type":"string","enum":["YES","NO","UNKNOWN"]},"details":{"type":"object","description":"category detail fields"},"other_incidents":{"type":"array","items":{"type":"string"}}},"required":["incident_type","description"]}}"""
    const val UPDATE = """{"name":"update_incident_fields","description":"Update form fields from the answer.","parameters":{"type":"object","properties":{"lang":{"type":"string","enum":["en","hinglish","hi"]},"set":{"type":"object","description":"field key -> new value (common or category field keys; 'time' for the time phrase)"},"unknown":{"type":"array","items":{"type":"string"}},"skipped":{"type":"boolean"}},"required":["set"]}}"""
    const val PROPOSE = """{"name":"propose_actions","description":"Recommended follow-up actions.","parameters":{"type":"object","properties":{"actions":{"type":"array","maxItems":6,"items":{"type":"object","properties":{"sop":{"type":"string","description":"SOP id from the candidate list, or CUSTOM"},"text":{"type":"string"}},"required":["sop","text"]}}},"required":["actions"]}}"""
}

@Serializable
data class FewShot(val user: String, val assistant: String)

/** Raw template texts from the config/prompts directory. The loader (JVM tests / Android assets) supplies them. */
data class PromptTemplates(
    val extractSystem: String,
    val extractUser: String,
    val fewShots: List<FewShot>,
    val patchSystem: String,
    val patchUser: String,
    val recommendSystem: String,
    val recommendUser: String,
    val repairSystem: String,
    val repairUser: String,
    val warmupUser: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        /** @param read returns the text of `config/prompts/<name>`. */
        fun load(read: (String) -> String) = PromptTemplates(
            extractSystem = read("extract.system.txt").trimEnd('\n'),
            extractUser = read("extract.user.txt").trimEnd('\n'),
            fewShots = read("extract.fewshot.jsonl").lines().filter { it.isNotBlank() }
                .map { json.decodeFromString(FewShot.serializer(), it) },
            patchSystem = read("patch.system.txt").trimEnd('\n'),
            patchUser = read("patch.user.txt").trimEnd('\n'),
            recommendSystem = read("recommend.system.txt").trimEnd('\n'),
            recommendUser = read("recommend.user.txt").trimEnd('\n'),
            repairSystem = read("repair.system.txt").trimEnd('\n'),
            repairUser = read("repair.user.txt").trimEnd('\n'),
            warmupUser = read("warmup.user.txt").trimEnd('\n'),
        )
    }
}

/** PRD §13.3–13.4: renders Llama-3 chat prompts byte-exactly (no BOS; Genie adds it). */
class PromptBuilder(private val tx: Taxonomy, private val t: PromptTemplates) {
    private val json = Json { encodeDefaults = false }

    fun chat(system: String?, turns: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        if (system != null) sb.append("<|start_header_id|>system<|end_header_id|>\n\n").append(system).append("<|eot_id|>")
        for ((role, text) in turns) sb.append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n").append(text).append("<|eot_id|>")
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    fun extract(transcript: String): String {
        val turns = ArrayList<Pair<String, String>>()
        t.fewShots.forEach { turns += "user" to it.user; turns += "assistant" to it.assistant }
        turns += "user" to fill(t.extractUser, "transcript" to transcript)
        return chat(extractSystemText(), turns)
    }

    fun extractSystemText(): String = fill(
        t.extractSystem,
        "category_list" to categoryList(),
        "severity_rubric" to severityRubric(),
        "field_catalogue" to fieldCatalogue(),
        "tool_schema_fill_incident_form" to ToolSchemas.FILL,
    )

    fun patch(form: IncidentForm, question: String, askedFields: List<String>, answer: String): String {
        val sys = fill(
            t.patchSystem,
            "asked_field_specs" to askedFields.joinToString("\n") { spec(it) },
            "tool_schema_update_incident_fields" to ToolSchemas.UPDATE,
        )
        val user = fill(
            t.patchUser,
            "form_compact_json" to compactJson(form),
            "question_text" to question,
            "asked_fields" to askedFields.joinToString(", ") { tx.field(it)?.llmKey ?: it },
            "answer" to answer,
        )
        return chat(sys, listOf("user" to user))
    }

    fun recommend(form: IncidentForm, actionsTaken: List<String>, sopCandidates: String): String {
        val sys = fill(t.recommendSystem, "tool_schema_propose_actions" to ToolSchemas.PROPOSE)
        val user = fill(
            t.recommendUser,
            "form_compact_json" to compactJson(form),
            "actions_taken_list" to (if (actionsTaken.isEmpty()) "none" else actionsTaken.joinToString("; ")),
            "sop_candidates" to sopCandidates,
        )
        return chat(sys, listOf("user" to user))
    }

    fun repair(toolName: String, toolSchema: String, broken: String): String {
        val sys = fill(t.repairSystem, "tool_name" to toolName)
        val user = fill(t.repairUser, "tool_schema" to toolSchema, "broken" to broken)
        return chat(sys, listOf("user" to user))
    }

    fun warmup(): String = chat(null, listOf("user" to t.warmupUser))

    // ---- renderers (generated from taxonomy so they cannot drift) ------------------------------

    fun categoryList(): String = tx.categories.joinToString("\n") { "${it.code}: ${it.definition}" }

    fun severityRubric(): String =
        listOf("CRITICAL", "HIGH", "MEDIUM", "LOW").mapNotNull { k -> tx.severityRubric[k]?.let { "$k: $it" } }.joinToString("\n")

    fun fieldCatalogue(): String = tx.categories.filter { it.fields.isNotEmpty() }.joinToString("\n") { c ->
        val prefix = if (c.extraMandatoryCommon.contains("equipment")) "(also fill equipment) " else ""
        c.code + ": " + prefix + c.fields.joinToString(", ") { spec(it.key) }
    }

    private fun spec(key: String): String {
        val d = tx.field(key) ?: return key
        val k = d.llmKey ?: d.key
        return when (d.type) {
            "enum" -> "$k(${d.enum!!.joinToString("|")})"
            "bool" -> "$k:true/false"
            "int" -> "$k:integer"
            "string_list" -> "$k:list of text"
            else -> "$k:text"
        }
    }

    /** Satisfied fields only, LLM key names, time shown as the spoken phrase. */
    fun compactJson(form: IncidentForm): String {
        val m = LinkedHashMap<String, JsonElement>()
        for ((k, v) in form.compact()) {
            if (k == "occurred_at") { form.timeExpression?.let { m["time"] = JsonPrimitive(it) }; continue }
            m[tx.field(k)?.llmKey ?: k] = v
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(m))
    }

    private fun fill(template: String, vararg kv: Pair<String, String>): String {
        var s = template
        for ((k, v) in kv) s = s.replace("{{$k}}", v)
        return s
    }
}
