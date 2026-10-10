package com.megathon.incidentvoice.core.llm

import com.megathon.incidentvoice.core.config.ConfigLoader
import com.megathon.incidentvoice.core.config.FieldDef
import com.megathon.incidentvoice.core.config.FieldTypes
import com.megathon.incidentvoice.core.config.SopStep
import com.megathon.incidentvoice.core.config.Taxonomy
import com.megathon.incidentvoice.core.model.FieldStatus
import com.megathon.incidentvoice.core.model.IncidentForm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Deterministic compact JSON (`,`/`:` separators, file key order, non-ASCII kept) — matches Python
 *  `json.dumps(x, separators=(",", ":"), ensure_ascii=False)`. */
object CompactJson {
    fun write(e: JsonElement): String = StringBuilder().also { write(e, it) }.toString()

    private fun write(e: JsonElement, sb: StringBuilder) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, v) in e) {
                    if (!first) sb.append(',')
                    first = false
                    string(k, sb); sb.append(':'); write(v, sb)
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, v -> if (i > 0) sb.append(','); write(v, sb) }
                sb.append(']')
            }
            is JsonPrimitive -> if (e.isString) string(e.content, sb) else sb.append(e.content)
        }
    }

    fun string(s: String, sb: StringBuilder = StringBuilder()): StringBuilder {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
        return sb
    }
}

/**
 * Renders prompts exactly as specified in `config/prompts/RENDERING.md` (normative; byte-identical to
 * `ml/common/prompt_builder.py`). Llama 3.2 chat format without `<|begin_of_text|>` (Genie adds BOS).
 *
 * @param reader reads files relative to the config root (same reader as [ConfigLoader]).
 */
class PromptBuilder(private val tx: Taxonomy, private val reader: (String) -> String) {

    companion object {
        const val VARIANT_BASE = "base"
        const val VARIANT_FT = "ft"
        private val PLACEHOLDER = Regex("""\{\{([A-Za-z0-9_]+)\}\}""")

        fun header(role: String) = "<|start_header_id|>$role<|end_header_id|>\n\n"
        fun segment(role: String, text: String) = header(role) + text + "<|eot_id|>"

        /** §3 chat assembly. [system] null = no system message (warmup). */
        fun chat(system: String?, fewShots: List<Pair<String, String>>, user: String): String = buildString {
            if (system != null) append(segment("system", system))
            for ((u, a) in fewShots) { append(segment("user", u)); append(segment("assistant", a)) }
            append(segment("user", user))
            append(header("assistant"))
        }

        /** Plain `{{name}}` replacement in a single pass (substituted text is never re-scanned). */
        fun substitute(template: String, values: Map<String, String>): String =
            PLACEHOLDER.replace(template) { m ->
                val name = m.groupValues[1]
                values[name] ?: throw IllegalArgumentException("Unknown placeholder {{$name}}")
            }
    }

    private val templateCache = HashMap<String, String>()

    /** Reads `prompts/<name>`, normalises CRLF, strips exactly one trailing `\n`. */
    fun template(name: String): String = templateCache.getOrPut(name) {
        val t = reader("prompts/$name").replace("\r\n", "\n")
        if (t.endsWith("\n")) t.dropLast(1) else t
    }

    fun fewShots(): List<Pair<String, String>> {
        val text = reader("prompts/extract.fewshot.jsonl").replace("\r\n", "\n")
        return text.split("\n").filter { it.isNotBlank() }.map { line ->
            val o = ConfigLoader.parseObject(line)
            (o["user"] as JsonPrimitive).content to (o["assistant"] as JsonPrimitive).content
        }
    }

    // ------------------------------------------------------------------ static placeholders

    fun categoryList(): String = tx.categories.joinToString("\n") { "${it.code}: ${it.definition}" }

    fun severityRubric(): String =
        listOf("CRITICAL", "HIGH", "MEDIUM", "LOW").joinToString("\n") { "$it: ${tx.severityRubric[it] ?: ""}" }

    fun renderField(f: FieldDef): String = when (f.type) {
        FieldTypes.ENUM -> "${f.key}(${f.enumValues.orEmpty().joinToString("|")})"
        FieldTypes.ENUM_CATEGORY -> "${f.key}(${tx.categoryCodes.joinToString("|")})"
        FieldTypes.ENUM_CATEGORY_LIST -> "${f.key}:list of category codes"
        FieldTypes.STRING -> "${f.key}:text"
        FieldTypes.STRING_LIST -> "${f.key}:list of text"
        FieldTypes.BOOL -> "${f.key}:true/false"
        FieldTypes.INT -> "${f.key}:number"
        FieldTypes.TIME_EXPRESSION -> "${f.promptKey}:text (time phrase as spoken)"
        FieldTypes.PERSON_LIST -> "${f.key}:list of {name, role, injured, injury}"
        FieldTypes.WITNESS_LIST -> "${f.key}:list of {name, contact}"
        else -> "${f.key}:text"
    }

    fun fieldCatalogue(): String = tx.categories.filter { it.code != "OTHER" }.joinToString("\n") { c ->
        val prefix = if (c.code == "EQUIPMENT") "EQUIPMENT (also fill equipment)" else c.code
        "$prefix: " + c.fields.joinToString(", ") { renderField(it) }
    }

    fun toolSchema(tool: String): String = CompactJson.write(ConfigLoader.parseObject(reader("schemas/tool_$tool.json")))

    private fun staticValues(): MutableMap<String, String> = mutableMapOf(
        "category_list" to categoryList(),
        "severity_rubric" to severityRubric(),
        "field_catalogue" to fieldCatalogue(),
        "tool_schema_fill_incident_form" to toolSchema(Tools.FILL),
        "tool_schema_update_incident_fields" to toolSchema(Tools.UPDATE),
        "tool_schema_propose_actions" to toolSchema(Tools.PROPOSE),
    )

    private val statics: Map<String, String> by lazy { staticValues() }

    fun render(templateName: String, values: Map<String, String>): String = substitute(template(templateName), statics + values)

    // ------------------------------------------------------------------ dynamic placeholders

    fun askedFieldSpecs(keys: List<String>): String = keys.mapNotNull { tx.field(it) }.joinToString("\n") { renderField(it) }

    fun askedFields(keys: List<String>): String = keys.joinToString(", ") { tx.field(it)?.promptKey ?: it }

    /** `{{form_compact_json}}` — satisfied fields only, RENDERING.md §2. */
    fun formCompactJson(form: IncidentForm): String {
        val top = LinkedHashMap<String, JsonElement>()
        for (f in tx.commonFields) {
            if (f.key == "lang" || f.key == "title") continue
            val v = compactValue(form, f) ?: continue
            top[if (f.key == "occurred_at") f.promptKey else f.key] = v
        }
        val details = LinkedHashMap<String, JsonElement>()
        for (c in tx.categories) for (f in c.fields) compactValue(form, f)?.let { details[f.key] = it }
        if (details.isNotEmpty()) top["details"] = JsonObject(details)
        return CompactJson.write(JsonObject(top))
    }

    private fun compactValue(form: IncidentForm, f: FieldDef): JsonElement? {
        val st = form.state(f.key)
        return when (st.status) {
            FieldStatus.MISSING -> null
            FieldStatus.UNKNOWN ->
                if (f.type == FieldTypes.ENUM || f.type == FieldTypes.ENUM_CATEGORY) JsonPrimitive("UNKNOWN") else null
            else -> if (f.type == FieldTypes.TIME_EXPRESSION) {
                when {
                    st.status == FieldStatus.DEFAULTED -> JsonPrimitive("now (assumed)")
                    form.timeExpression != null -> JsonPrimitive(form.timeExpression)
                    else -> st.value
                }
            } else st.value
        }
    }

    // ------------------------------------------------------------------ tasks

    fun extractSystem(variant: String = VARIANT_BASE): String =
        render(if (variant == VARIANT_FT) "extract.system.ft.txt" else "extract.system.txt", emptyMap())

    /** `extract` task: base = system + few-shots + user; ft = system + user. */
    fun extract(transcript: String, variant: String = VARIANT_BASE): String {
        val user = render("extract.user.txt", mapOf("transcript" to transcript.trim()))
        val shots = if (variant == VARIANT_FT) emptyList() else fewShots()
        return chat(extractSystem(variant), shots, user)
    }

    fun patch(form: IncidentForm, askedFields: List<String>, questionText: String, answer: String): String {
        val system = render("patch.system.txt", mapOf("asked_field_specs" to askedFieldSpecs(askedFields)))
        val user = render(
            "patch.user.txt",
            mapOf(
                "form_compact_json" to formCompactJson(form),
                "question_text" to questionText,
                "asked_fields" to askedFields(askedFields),
                "answer" to answer.trim(),
            ),
        )
        return chat(system, emptyList(), user)
    }

    fun recommend(form: IncidentForm, candidates: List<SopStep>): String {
        val system = render("recommend.system.txt", emptyMap())
        val actions = form.stringList("actions_taken").takeIf { form.status("actions_taken") != FieldStatus.MISSING }.orEmpty()
        val user = render(
            "recommend.user.txt",
            mapOf(
                "form_compact_json" to formCompactJson(form),
                "actions_taken_list" to CompactJson.write(JsonArray(actions.map { JsonPrimitive(it) })),
                "sop_candidates" to candidates.joinToString("\n") { "${it.id}: ${it.text}" },
            ),
        )
        return chat(system, emptyList(), user)
    }

    fun repair(toolName: String, broken: String): String {
        val system = render("repair.system.txt", mapOf("tool_name" to toolName))
        val user = render("repair.user.txt", mapOf("tool_schema" to toolSchema(toolName), "broken" to broken.trim()))
        return chat(system, emptyList(), user)
    }

    fun warmup(): String = chat(null, emptyList(), render("warmup.user.txt", emptyMap()))
}
