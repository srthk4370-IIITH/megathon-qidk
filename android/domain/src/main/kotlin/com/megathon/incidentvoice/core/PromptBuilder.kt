package com.megathon.incidentvoice.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Deterministic compact JSON: `,`/`:` separators, insertion key order, non-ASCII kept
 *  (= Python `json.dumps(x, separators=(",", ":"), ensure_ascii=False)`). */
object CompactJson {
    fun write(e: JsonElement): String = StringBuilder().also { write(e, it) }.toString()

    private fun write(e: JsonElement, sb: StringBuilder) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonObject -> {
                sb.append('{'); var first = true
                for ((k, v) in e) { if (!first) sb.append(','); first = false; string(k, sb); sb.append(':'); write(v, sb) }
                sb.append('}')
            }
            is JsonArray -> { sb.append('['); e.forEachIndexed { i, v -> if (i > 0) sb.append(','); write(v, sb) }; sb.append(']') }
            is JsonPrimitive -> if (e.isString) string(e.content, sb) else sb.append(e.content)
        }
    }

    private fun string(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t"); '\b' -> sb.append("\\b"); '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }
}

@Serializable
data class FewShot(val user: String, val assistant: String)

/** Raw template texts from the config/prompts directory plus the compact tool schemas from config/schemas. */
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
    /** tool name → compact JSON of config/schemas/tool_<name>.json */
    val toolSchemas: Map<String, String>,
    val extractSystemFt: String? = null,
    /** Compact extraction prompt with two in-prompt examples, used for the Qwen family. */
    val extractSystemQwen: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        /** RENDERING.md §1: normalise CRLF, strip exactly one trailing newline. */
        private fun tpl(s: String): String = s.replace("\r\n", "\n").let { if (it.endsWith("\n")) it.dropLast(1) else it }

        /** @param read returns the text of a file relative to the config root (e.g. `prompts/patch.user.txt`). */
        fun load(read: (String) -> String): PromptTemplates {
            fun schema(tool: String) = CompactJson.write(json.parseToJsonElement(read("schemas/tool_$tool.json")))
            return PromptTemplates(
                extractSystem = tpl(read("prompts/extract.system.txt")),
                extractUser = tpl(read("prompts/extract.user.txt")),
                fewShots = read("prompts/extract.fewshot.jsonl").replace("\r\n", "\n").split("\n").filter { it.isNotBlank() }
                    .map { json.decodeFromString(FewShot.serializer(), it) },
                patchSystem = tpl(read("prompts/patch.system.txt")),
                patchUser = tpl(read("prompts/patch.user.txt")),
                recommendSystem = tpl(read("prompts/recommend.system.txt")),
                recommendUser = tpl(read("prompts/recommend.user.txt")),
                repairSystem = tpl(read("prompts/repair.system.txt")),
                repairUser = tpl(read("prompts/repair.user.txt")),
                warmupUser = tpl(read("prompts/warmup.user.txt")),
                toolSchemas = listOf(Tools.FILL, Tools.UPDATE, Tools.PROPOSE).associateWith { schema(it) },
                extractSystemFt = runCatching { tpl(read("prompts/extract.system.ft.txt")) }.getOrNull(),
                extractSystemQwen = runCatching { tpl(read("prompts/extract.system.qwen.txt")) }.getOrNull(),
            )
        }
    }
}

/**
 * Renders prompts exactly as specified in `config/prompts/RENDERING.md` (normative; byte-identical to the ML branch's
 * Kotlin `:core` PromptBuilder and Python `prompt_builder.py` — checked against `config/prompts/golden`).
 * Llama 3.2 chat format without `<|begin_of_text|>` (Genie adds BOS, PRD §13.9).
 */
enum class ModelFamily(val display: String) {
    LLAMA("Llama 3.2 3B"), QWEN("Qwen3-4B");

    companion object {
        /** From the bundle folder: a `model.json` with `"family"`, else the file names in `genie_config.json`. */
        fun detect(modelJson: String?, genieConfig: String?): ModelFamily {
            val hint = (modelJson.orEmpty() + " " + genieConfig.orEmpty()).lowercase()
            // Qualcomm's Qwen bundle names its parts part1_of_4.bin, so also recognise it by Qwen's end-of-turn token id
            return if ("qwen" in hint || Regex("\"eos-token\"\\s*:\\s*151645").containsMatchIn(genieConfig.orEmpty())) QWEN else LLAMA
        }
    }
}

/** Removes what a chat model may wrap around the tool call: Qwen3 `<think>` blocks and end-of-turn markers. */
object ModelOutput {
    private val think = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    fun clean(raw: String): String {
        var s = think.replace(raw, "")
        s.indexOf("<think>").let { if (it >= 0) s = s.substring(0, it) }          // an unfinished think block: nothing after it is an answer
        for (stop in listOf("<|im_end|>", "<|endoftext|>", "<|eot_id|>")) s.indexOf(stop).let { if (it >= 0) s = s.substring(0, it) }
        return s.trim()
    }
}

class PromptBuilder(private val tx: Taxonomy, private val t: PromptTemplates, private val variant: String = "base", private val family: () -> ModelFamily = { ModelFamily.LLAMA }) {

    private val statics: Map<String, String> by lazy {
        mapOf(
            "category_list" to categoryList(),
            "severity_rubric" to severityRubric(),
            "field_catalogue" to fieldCatalogue(),
            "tool_schema_fill_incident_form" to schema(Tools.FILL),
            "tool_schema_update_incident_fields" to schema(Tools.UPDATE),
            "tool_schema_propose_actions" to schema(Tools.PROPOSE),
        )
    }

    fun schema(tool: String): String = t.toolSchemas[tool] ?: error("no schema for $tool")

    fun chat(system: String?, shots: List<FewShot>, user: String): String = if (family() == ModelFamily.QWEN) chatQwen(system, shots, user) else chatLlama(system, shots, user)

    /**
     * Qwen (ChatML): `<|im_start|>role\n…<|im_end|>\n`, no BOS. The assistant prefix ends with an empty think block,
     * which switches Qwen3's thinking mode off so the answer starts at once.
     */
    private fun chatQwen(system: String?, shots: List<FewShot>, user: String): String = buildString {
        fun seg(role: String, text: String) { append("<|im_start|>").append(role).append('\n').append(text).append("<|im_end|>\n") }
        if (system != null) seg("system", system)
        for (s in shots) { seg("user", s.user); append("<|im_start|>assistant\n<think>\n\n</think>\n\n").append(s.assistant).append("<|im_end|>\n") }
        seg("user", user)
        append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
    }

    private fun chatLlama(system: String?, shots: List<FewShot>, user: String): String = buildString {
        fun seg(role: String, text: String) { append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n").append(text).append("<|eot_id|>") }
        if (system != null) seg("system", system)
        for (s in shots) { seg("user", s.user); seg("assistant", s.assistant) }
        seg("user", user)
        append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    private fun render(template: String, values: Map<String, String> = emptyMap()): String {
        var s = template
        for ((k, v) in statics + values) s = s.replace("{{$k}}", v)
        return s
    }

    // ---- tasks ---------------------------------------------------------------------------------

    fun extractSystemText(): String = render(if (variant == "ft" && t.extractSystemFt != null) t.extractSystemFt else t.extractSystem)

    fun extract(transcript: String): String =
        if (family() == ModelFamily.QWEN && t.extractSystemQwen != null) chat(t.extractSystemQwen, emptyList(), render(t.extractUser, mapOf("transcript" to transcript.trim())))
        else chat(extractSystemText(), if (variant == "ft") emptyList() else t.fewShots, render(t.extractUser, mapOf("transcript" to transcript.trim())))

    fun patch(form: IncidentForm, question: String, askedFields: List<String>, answer: String): String = chat(
        render(t.patchSystem, mapOf("asked_field_specs" to askedFieldSpecs(askedFields))), emptyList(),
        render(t.patchUser, mapOf(
            "form_compact_json" to compactJson(form), "question_text" to question,
            "asked_fields" to askedFields.joinToString(", ") { promptKey(it) }, "answer" to answer.trim(),
        )),
    )

    fun recommend(form: IncidentForm, candidates: List<SopStep>): String = chat(
        render(t.recommendSystem), emptyList(),
        render(t.recommendUser, mapOf(
            "form_compact_json" to compactJson(form),
            "actions_taken_list" to CompactJson.write(JsonArray(form.value("actions_taken").asStringList().map { JsonPrimitive(it) })),
            "sop_candidates" to candidates.joinToString("\n") { "${it.id}: ${it.text}" },
        )),
    )

    fun repair(toolName: String, broken: String): String = chat(
        render(t.repairSystem, mapOf("tool_name" to toolName)), emptyList(),
        render(t.repairUser, mapOf("tool_schema" to schema(toolName), "broken" to broken.trim())),
    )

    fun warmup(): String = chat(null, emptyList(), render(t.warmupUser))

    // ---- renderers (generated from taxonomy so they cannot drift) ------------------------------

    fun categoryList(): String = tx.categories.joinToString("\n") { "${it.code}: ${it.definition}" }

    fun severityRubric(): String = listOf("CRITICAL", "HIGH", "MEDIUM", "LOW").joinToString("\n") { "$it: ${tx.severityRubric[it] ?: ""}" }

    fun fieldCatalogue(): String = tx.categories.filter { it.code != "OTHER" }.joinToString("\n") { c ->
        val prefix = if (c.extraMandatoryCommon.isEmpty()) c.code else "${c.code} (also fill ${c.extraMandatoryCommon.joinToString(", ")})"
        "$prefix: " + c.fields.joinToString(", ") { renderField(it) }
    }

    fun askedFieldSpecs(keys: List<String>): String = keys.mapNotNull { tx.field(it) }.joinToString("\n") { renderField(it) }

    private fun promptKey(key: String) = tx.field(key)?.let { it.llmKey ?: it.key } ?: key

    private fun renderField(f: FieldDef): String = when (f.type) {
        "enum" -> "${f.key}(${f.enum.orEmpty().joinToString("|")})"
        "enum_category" -> "${f.key}(${tx.categoryCodes.joinToString("|")})"
        "enum_category_list" -> "${f.key}:list of (${tx.categoryCodes.joinToString("|")})"
        "string" -> "${f.key}:text"
        "string_list" -> "${f.key}:list of text"
        "bool" -> "${f.key}:true/false"
        "int" -> "${f.key}:number"
        "time_expression" -> "${f.llmKey ?: f.key}:text (time phrase as spoken)"
        "person_list", "witness_list" -> "${f.key}:list of objects"
        else -> "${f.key}:text"
    }

    /** `{{form_compact_json}}`: satisfied fields only; common fields in taxonomy order, then nested `details`. */
    fun compactJson(form: IncidentForm): String {
        val top = LinkedHashMap<String, JsonElement>()
        for (f in tx.commonFields) {
            if (f.key == "lang" || f.key == "title") continue
            val v = compactValue(form, f) ?: continue
            top[if (f.key == "occurred_at") (f.llmKey ?: f.key) else f.key] = v
        }
        val details = LinkedHashMap<String, JsonElement>()
        for (c in tx.categories) for (f in c.fields) compactValue(form, f)?.let { details[f.key] = it }
        if (details.isNotEmpty()) top["details"] = JsonObject(details)
        return CompactJson.write(JsonObject(top))
    }

    private fun compactValue(form: IncidentForm, f: FieldDef): JsonElement? {
        if (f.key == "other_incidents") return form.otherIncidents.takeIf { it.isNotEmpty() }?.let { listValue(it) }
        val st = form.state(f.key) ?: return null
        return when (st.status) {
            FieldStatus.MISSING -> null
            FieldStatus.UNKNOWN -> if (f.type == "enum" || f.type == "enum_category") JsonPrimitive("UNKNOWN") else null
            else -> if (f.type == "time_expression") when {
                st.status == FieldStatus.DEFAULTED -> JsonPrimitive("now (assumed)")
                form.timeExpression != null -> JsonPrimitive(form.timeExpression)
                else -> null
            } else st.value
        }
    }
}
