package com.megathon.incidentvoice.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull

enum class IncidentStatus { DRAFT, SUBMITTED, ACKNOWLEDGED, RESOLVED, CLOSED }

data class Reporter(val name: String = "", val empId: String = "")

/** Mutable-per-save facts that are not part of the form (PRD §21.3 columns). */
data class IncidentMeta(
    val id: String,
    val status: IncidentStatus = IncidentStatus.DRAFT,
    val refNo: String? = null,
    val reporter: Reporter = Reporter(),
    val createdAt: Long,
    val reportedAt: Long,
    val submittedAt: Long? = null,
    val emergencyFlag: Boolean = false,
    val followupRounds: Int = 0,
    val modelVersionsJson: String = "{}",
    /** Extra 1: when the response clock started for this incident (null = never). */
    val responseStartedAt: Long? = null,
    /** Extra 3: the report this one was linked to as a further witness account (null = stands alone). */
    val parentId: String? = null,
)

/** Flat mirror of the `incidents` table (Appendix E, minus rowid_pk). */
data class IncidentRow(
    val id: String, val refNo: String?, val status: String, val incidentType: String?, val title: String?, val description: String?,
    val severity: String?, val severitySource: String?, val locationText: String?, val locationId: String?,
    val occurredAt: Long?, val occurredAtApprox: Boolean, val timeExpression: String?, val injuries: String?, val injuredCount: Int?,
    val nearMiss: Boolean, val hazardOngoing: String?, val detailsJson: String, val fieldMetaJson: String, val otherIncidentsJson: String,
    val lang: String?, val reporterName: String?, val reporterEmpId: String?, val emergencyFlag: Boolean, val followupRounds: Int,
    val createdAt: Long, val updatedAt: Long, val reportedAt: Long, val submittedAt: Long?, val modelVersionsJson: String,
    val responseStartedAt: Long? = null, val parentIncidentId: String? = null,
)

data class PersonRow(val name: String?, val role: String, val injured: Boolean, val injury: String?, val contact: String?)

data class StoredIncident(
    val row: IncidentRow,
    val secondaryTypes: List<String>,
    val persons: List<PersonRow>,
    val equipment: List<String>,
    val actionsTaken: List<String>,
)

@Serializable
private data class FieldMetaDto(val status: String, val source: String, val turnId: Long? = null, val grounded: Boolean = true, val askedCount: Int = 0)

/** IncidentForm ⇄ table rows. Pure, JVM-tested. */
object FormMapper {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val metaSer = MapSerializer(String.serializer(), FieldMetaDto.serializer())
    private val listSer = ListSerializer(String.serializer())

    /** Keys stored in their own columns / child tables; everything else goes to details_json. */
    private val columnKeys = setOf(
        "incident_type", "secondary_types", "title", "description", "severity", "location", "occurred_at", "injuries",
        "injured_count", "persons", "witnesses", "equipment", "actions_taken", "near_miss", "hazard_ongoing", "other_incidents", "lang",
    )

    fun toStored(form: IncidentForm, meta: IncidentMeta, now: Long): StoredIncident {
        fun s(k: String) = form.str(k)
        fun i(k: String) = (form.value(k) as? JsonPrimitive)?.longOrNull?.toInt()
        val details = JsonObject(form.fields.filter { (k, v) -> k !in columnKeys && v.value != null && v.status != FieldStatus.MISSING && v.status != FieldStatus.UNKNOWN }
            .mapValues { it.value.value!! })
        val fm = form.fields.filterValues { it.status != FieldStatus.MISSING || it.askedCount > 0 }.mapValues {
            FieldMetaDto(it.value.status.name, it.value.source.name, it.value.turnId, it.value.grounded, it.value.askedCount)
        }
        val persons = ArrayList<PersonRow>()
        (form.value("persons") as? JsonArray)?.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            persons += PersonRow(o.s("name"), o.s("role") ?: "INVOLVED", o.b("injured"), o.s("injury"), null)
        }
        (form.value("witnesses") as? JsonArray)?.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            persons += PersonRow(o.s("name"), "WITNESS", false, null, o.s("contact"))
        }
        val row = IncidentRow(
            id = meta.id, refNo = meta.refNo, status = meta.status.name, incidentType = s("incident_type"), title = s("title"),
            description = s("description"), severity = s("severity"), severitySource = (form.severitySource
                ?: form.state("severity")?.source)?.let { sev(it) },
            locationText = s("location"), locationId = form.locationId,
            occurredAt = (form.value("occurred_at") as? JsonPrimitive)?.longOrNull, occurredAtApprox = form.occurredApprox,
            timeExpression = form.timeExpression, injuries = s("injuries"), injuredCount = i("injured_count"),
            nearMiss = (form.value("near_miss") as? JsonPrimitive)?.booleanOrNull ?: false, hazardOngoing = s("hazard_ongoing"),
            detailsJson = json.encodeToString(JsonObject.serializer(), details),
            fieldMetaJson = json.encodeToString(metaSer, fm),
            otherIncidentsJson = json.encodeToString(listSer, form.otherIncidents),
            lang = form.lang, reporterName = meta.reporter.name, reporterEmpId = meta.reporter.empId,
            emergencyFlag = meta.emergencyFlag, followupRounds = meta.followupRounds, createdAt = meta.createdAt, updatedAt = now,
            reportedAt = meta.reportedAt, submittedAt = meta.submittedAt, modelVersionsJson = meta.modelVersionsJson,
            responseStartedAt = meta.responseStartedAt, parentIncidentId = meta.parentId,
        )
        return StoredIncident(row, form.secondaryTypes, persons, form.value("equipment").asStringList(), form.value("actions_taken").asStringList())
    }

    private fun sev(src: FieldSource) = when (src) { FieldSource.USER -> "USER"; FieldSource.RULE -> "RULE"; else -> "LLM" }

    fun toForm(st: StoredIncident): IncidentForm {
        val r = st.row
        val meta = runCatching { json.decodeFromString(metaSer, r.fieldMetaJson) }.getOrDefault(emptyMap())
        val values = LinkedHashMap<String, JsonElement>()
        fun setV(k: String, v: JsonElement?) { if (v != null && v !is JsonNull) values[k] = v }
        setV("incident_type", r.incidentType?.let { JsonPrimitive(it) })
        if (st.secondaryTypes.isNotEmpty()) setV("secondary_types", listValue(st.secondaryTypes))
        setV("title", r.title?.let { JsonPrimitive(it) }); setV("description", r.description?.let { JsonPrimitive(it) })
        setV("severity", r.severity?.let { JsonPrimitive(it) }); setV("location", r.locationText?.let { JsonPrimitive(it) })
        setV("occurred_at", r.occurredAt?.let { JsonPrimitive(it) }); setV("injuries", r.injuries?.let { JsonPrimitive(it) })
        setV("injured_count", r.injuredCount?.let { JsonPrimitive(it) })
        if (meta.containsKey("near_miss") || r.nearMiss) setV("near_miss", JsonPrimitive(r.nearMiss))
        setV("hazard_ongoing", r.hazardOngoing?.let { JsonPrimitive(it) })
        if (st.equipment.isNotEmpty()) setV("equipment", listValue(st.equipment))
        if (st.actionsTaken.isNotEmpty()) setV("actions_taken", listValue(st.actionsTaken))
        val ps = st.persons.filter { it.role != "WITNESS" }.map { p ->
            JsonObject(buildMap {
                p.name?.let { put("name", JsonPrimitive(it)) }; put("role", JsonPrimitive(p.role)); put("injured", JsonPrimitive(p.injured))
                p.injury?.let { put("injury", JsonPrimitive(it)) }
            })
        }
        if (ps.isNotEmpty()) setV("persons", JsonArray(ps))
        val ws = st.persons.filter { it.role == "WITNESS" }.map { p ->
            JsonObject(buildMap { p.name?.let { put("name", JsonPrimitive(it)) }; p.contact?.let { put("contact", JsonPrimitive(it)) } })
        }
        if (ws.isNotEmpty()) setV("witnesses", JsonArray(ws))
        (runCatching { json.parseToJsonElement(r.detailsJson) }.getOrNull() as? JsonObject)?.forEach { (k, v) -> setV(k, v) }

        val fields = LinkedHashMap<String, FieldState>()
        for (k in values.keys + meta.keys) {
            val m = meta[k]
            val v = values[k]
            val status = m?.let { runCatching { FieldStatus.valueOf(it.status) }.getOrNull() } ?: if (v != null) FieldStatus.FILLED else FieldStatus.MISSING
            val source = m?.let { runCatching { FieldSource.valueOf(it.source) }.getOrNull() } ?: FieldSource.SYSTEM
            fields[k] = FieldState(k, v, status, source, m?.turnId, m?.grounded ?: true, m?.askedCount ?: 0)
        }
        return IncidentForm(
            lang = r.lang ?: "en", fields = fields, timeExpression = r.timeExpression, occurredApprox = r.occurredAtApprox,
            otherIncidents = runCatching { json.decodeFromString(listSer, r.otherIncidentsJson) }.getOrDefault(emptyList()),
            locationId = r.locationId,
            severitySource = when (r.severitySource) { "USER" -> FieldSource.USER; "RULE" -> FieldSource.RULE; "LLM" -> FieldSource.LLM_EXTRACT; else -> null },
        )
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
}

/** PRD §21.4/§21.5 */
object SearchQueryBuilder {
    /** `water conf` → `water* conf*` (FTS4 prefix syntax; tokens lower-cased so AND/OR/NOT are never operators); null when nothing searchable. */
    fun build(text: String): String? {
        val toks = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        if (toks.isEmpty()) return null
        return toks.joinToString(" ") { it.lowercase() + "*" }
    }
}

/** Body text indexed for dashboard search (PRD §21.4). */
object SearchBody {
    fun build(form: IncidentForm, meta: IncidentMeta, tx: Taxonomy, locationDisplay: String?, turnTexts: List<String>, recs: List<String>): String {
        val parts = ArrayList<String>()
        meta.refNo?.let { parts += it }
        form.str("title")?.let { parts += it }; form.str("description")?.let { parts += it }
        form.str("location")?.let { parts += it }; locationDisplay?.let { parts += it }
        for (t in form.types) tx.category(t)?.let { parts += it.label; parts += it.searchCues.joinToString(" ") }
        form.str("severity")?.let { parts += it }; parts += meta.status.name
        parts += turnTexts
        (form.value("persons") as? JsonArray)?.forEach { (it as? JsonObject)?.get("name")?.let { n -> parts += (n as JsonPrimitive).content } }
        parts += form.value("equipment").asStringList(); parts += form.value("actions_taken").asStringList()
        parts += recs
        form.fields.filterKeys { tx.field(it)?.isCommon == false }.values.forEach { parts += it.value.asStringList() }
        if (meta.reporter.name.isNotBlank()) parts += meta.reporter.name
        return parts.joinToString(" ")
    }
}
