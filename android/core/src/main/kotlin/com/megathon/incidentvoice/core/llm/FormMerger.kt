package com.megathon.incidentvoice.core.llm

import com.megathon.incidentvoice.core.config.FieldTypes
import com.megathon.incidentvoice.core.config.Taxonomy
import com.megathon.incidentvoice.core.model.FieldSource
import com.megathon.incidentvoice.core.model.FieldState
import com.megathon.incidentvoice.core.model.FieldStatus
import com.megathon.incidentvoice.core.model.IncidentForm
import com.megathon.incidentvoice.core.normalize.LangDetector
import com.megathon.incidentvoice.core.normalize.LocationMatcher
import com.megathon.incidentvoice.core.normalize.PeopleNormaliser
import com.megathon.incidentvoice.core.normalize.TimeResolver
import com.megathon.incidentvoice.core.validation.SeverityRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class MergeResult(
    val form: IncidentForm,
    /** Keys whose value/status changed. */
    val changed: List<String>,
    val issues: List<ParseIssue>,
)

/**
 * Applies parsed tool calls to the draft with provenance (PRD §13.2, §14.1, §15):
 *  - never overwrites USER_EDITED;
 *  - ungrounded strings → INFERRED; enum `UNKNOWN` / patch `unknown[]` → status UNKNOWN (value null);
 *  - `skipped` → asked fields stay MISSING;
 *  - `time` → TimeResolver (T10 default on extraction when absent: report time, approximate, DEFAULTED);
 *  - location → LocationMatcher; persons/equipment → PeopleNormaliser; consistency rules §15.3;
 *  - category fields outside incident_type ∪ secondary_types are dropped (§13.7);
 *  - SeverityRules floor last.
 */
class FormMerger(
    private val tx: Taxonomy,
    private val timeResolver: TimeResolver = TimeResolver(),
    private val locationMatcher: LocationMatcher? = null,
) {
    fun applyFill(form: IncidentForm, call: ParsedCall, turnId: Long, transcript: String? = null): MergeResult =
        apply(form, call, turnId, FieldSource.LLM_EXTRACT, transcript, isFill = true)

    fun applyPatch(form: IncidentForm, call: ParsedCall, turnId: Long, answer: String? = null): MergeResult =
        apply(form, call, turnId, FieldSource.LLM_PATCH, answer, isFill = false)

    private fun apply(
        form0: IncidentForm,
        call: ParsedCall,
        turnId: Long,
        source: FieldSource,
        text: String?,
        isFill: Boolean,
    ): MergeResult {
        var form = form0
        val changed = mutableListOf<String>()
        val issues = mutableListOf<ParseIssue>()

        // Language: LLM's lang, else detector on the text (fill only; patches keep the conversation language).
        val lang = call.lang ?: if (isFill && text != null) LangDetector.detect(text) else null
        if (lang != null) form = form.copy(lang = lang)

        if (!call.skipped) {
            // Types first so the category filter sees the new ones.
            val ordered = call.fields.entries.sortedBy { if (it.key == "incident_type" || it.key == "secondary_types") 0 else 1 }
            for ((key, pv) in ordered) {
                val cur = form.state(key)
                if (cur.status == FieldStatus.USER_EDITED) {
                    issues += ParseIssue(key, "USER_EDITED_KEPT"); continue
                }
                val def = tx.field(key) ?: continue
                if (!def.isCommon) {
                    val types = (listOfNotNull((call.fields["incident_type"]?.value as? JsonPrimitive)?.content ?: form.incidentType) +
                        ((call.fields["secondary_types"]?.value as? JsonArray)?.map { (it as JsonPrimitive).content } ?: form.secondaryTypes))
                    if (def.category !in types) {
                        issues += ParseIssue(key, "CATEGORY_NOT_CHOSEN", "${def.category}"); continue
                    }
                }
                form = setField(form, key, pv, cur, turnId, source, def.type)
                changed += key
            }
            for (key in call.unknown) {
                val cur = form.state(key)
                if (cur.status == FieldStatus.USER_EDITED) continue
                form = form.with(FieldState(key, null, FieldStatus.UNKNOWN, source, turnId, true, cur.askedCount))
                if (key == "occurred_at") form = form.copy(timeExpression = null)
                changed += key
            }
        }

        if (isFill) {
            // T10 (rev 1.1): no time phrase → report time, approximate, DEFAULTED.
            if (form.status("occurred_at") == FieldStatus.MISSING) {
                val r = timeResolver.resolve(null, form.reportedAt)
                form = form.with(
                    FieldState("occurred_at", JsonPrimitive(iso(r.instant ?: form.reportedAt)), FieldStatus.DEFAULTED, FieldSource.SYSTEM, turnId),
                ).copy(occurredAtApprox = true, timeExpression = null)
                changed += "occurred_at"
            }
            // Title fallback: "<Category label> at <location>" (§10.2).
            if (form.status("title") == FieldStatus.MISSING) {
                tx.category(form.incidentType)?.let { c ->
                    val loc = form.string("location")
                    val title = if (loc != null) "${c.label} at $loc" else c.label
                    form = form.with(FieldState("title", JsonPrimitive(title.take(80)), FieldStatus.DEFAULTED, FieldSource.SYSTEM, turnId))
                }
            }
        }
        form = consistency(form, turnId)
        form = SeverityRules.apply(form, turnId)
        return MergeResult(form, changed.distinct(), issues)
    }

    private fun setField(
        form0: IncidentForm,
        key: String,
        pv: ParsedValue,
        cur: FieldState,
        turnId: Long,
        source: FieldSource,
        type: String,
    ): IncidentForm {
        var form = form0
        val status = if (pv.grounded) FieldStatus.FILLED else FieldStatus.INFERRED
        // Enum "UNKNOWN" → UNKNOWN status (value null, §14.1).
        if ((type == FieldTypes.ENUM) && (pv.value as? JsonPrimitive)?.content == "UNKNOWN") {
            return form.with(FieldState(key, null, FieldStatus.UNKNOWN, source, turnId, true, cur.askedCount))
        }
        var value: JsonElement = pv.value
        when (type) {
            FieldTypes.TIME_EXPRESSION -> {
                val expr = (value as JsonPrimitive).content
                val r = timeResolver.resolve(expr, form.reportedAt)
                val st = if (r.defaulted) FieldStatus.DEFAULTED else status
                return form.with(
                    FieldState(key, JsonPrimitive(iso(r.instant ?: form.reportedAt)), st, if (r.defaulted) FieldSource.SYSTEM else source, turnId, true, cur.askedCount),
                ).copy(timeExpression = expr, occurredAtApprox = r.approximate)
            }
            FieldTypes.PERSON_LIST -> value = PeopleNormaliser.persons(value as JsonArray)
            FieldTypes.STRING_LIST -> if (key == "equipment") {
                value = JsonArray(PeopleNormaliser.equipment((value as JsonArray).map { (it as JsonPrimitive).content }).map { JsonPrimitive(it) })
            }
        }
        form = form.with(FieldState(key, value, status, source, turnId, pv.grounded, cur.askedCount))
        if (key == "location" && locationMatcher != null) {
            val m = locationMatcher.match((value as JsonPrimitive).content)
            form = when (m.kind) {
                LocationMatcher.Kind.AUTO -> form.copy(locationId = m.locationId, locationSuggestionId = null)
                LocationMatcher.Kind.SUGGESTION -> form.copy(locationId = null, locationSuggestionId = m.locationId)
                LocationMatcher.Kind.NONE -> form.copy(locationId = null, locationSuggestionId = null)
            }
        }
        return form
    }

    /** §15.3 consistency rules (source RULE). */
    fun consistency(form0: IncidentForm, turnId: Long?): IncidentForm {
        var form = form0
        val persons = form.value("persons") as? JsonArray
        val stated = (form.value("injured_count") as? JsonPrimitive)?.intOrNull
        val injuredPersons = PeopleNormaliser.injuredPersons(persons)
        if (injuredPersons > (stated ?: 0) && form.status("injured_count") != FieldStatus.USER_EDITED) {
            form = form.with(FieldState("injured_count", JsonPrimitive(injuredPersons), FieldStatus.INFERRED, FieldSource.RULE, turnId))
        }
        val count = (form.value("injured_count") as? JsonPrimitive)?.intOrNull ?: 0
        val inj = form.state("injuries")
        if ((count > 0 || injuredPersons > 0) && form.enumValue("injuries") != "YES" && inj.status != FieldStatus.USER_EDITED) {
            val wasNo = form.enumValue("injuries") == "NO"
            form = form.with(FieldState("injuries", JsonPrimitive("YES"), FieldStatus.INFERRED, FieldSource.RULE, turnId, true, inj.askedCount))
            if (wasNo) form = form.withFlag("INJURIES_RAISED_BY_RULE")
        }
        return form
    }

    // ------------------------------------------------------------ helpers for the app

    /** Increments askedCount for the fields of a question that was just spoken (§14.3). */
    fun markAsked(form: IncidentForm, keys: List<String>): IncidentForm =
        keys.fold(form) { f, k -> val s = f.state(k); f.with(s.copy(askedCount = s.askedCount + 1)) }

    /** Review-screen edit (§14.5): status USER_EDITED, source USER. Re-runs time/location handling. */
    fun applyUserEdit(form: IncidentForm, key: String, value: JsonElement?): IncidentForm {
        val cur = form.state(key)
        if (value == null) return form.with(FieldState.missing(key, cur.askedCount))
        var f = form.with(FieldState(key, value, FieldStatus.USER_EDITED, FieldSource.USER, null, true, cur.askedCount))
        if (key == "location" && locationMatcher != null && value is JsonPrimitive) {
            val m = locationMatcher.match(value.content)
            f = f.copy(locationId = if (m.kind == LocationMatcher.Kind.AUTO) m.locationId else null)
        }
        if (key == "occurred_at") f = f.copy(occurredAtApprox = false)
        return SeverityRules.apply(consistency(f, null))
    }

    /** "Unknown" quick action (§14.5): status UNKNOWN, source USER. */
    fun markUnknown(form: IncidentForm, key: String): IncidentForm {
        val cur = form.state(key)
        return form.with(FieldState(key, null, FieldStatus.UNKNOWN, FieldSource.USER, null, true, cur.askedCount))
    }

    /** Accept a location suggestion chip → USER_EDITED registry match. */
    fun acceptLocationSuggestion(form: IncidentForm): IncidentForm {
        val id = form.locationSuggestionId ?: return form
        val st = form.state("location")
        return form.copy(locationId = id, locationSuggestionId = null)
            .with(st.copy(status = FieldStatus.USER_EDITED, source = FieldSource.USER))
    }

    /** §13.7 parse failure → MANUAL_FILL: description = transcript (DEFAULTED), time default. */
    fun manualFillFallback(form: IncidentForm, transcript: String, turnId: Long): IncidentForm {
        var f = form
        if (f.status("description") == FieldStatus.MISSING) {
            f = f.with(FieldState("description", JsonPrimitive(transcript.trim()), FieldStatus.DEFAULTED, FieldSource.SYSTEM, turnId))
        }
        if (f.status("occurred_at") == FieldStatus.MISSING) {
            f = f.with(FieldState("occurred_at", JsonPrimitive(iso(f.reportedAt)), FieldStatus.DEFAULTED, FieldSource.SYSTEM, turnId))
                .copy(occurredAtApprox = true)
        }
        return f.copy(lang = LangDetector.detect(transcript))
    }

    fun iso(i: Instant): String =
        OffsetDateTime.ofInstant(i, timeResolver.zone).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** Builds the flattened `details` object for export (category fields with values, taxonomy order). */
    fun details(form: IncidentForm): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        for (c in tx.categories) for (f in c.fields) form.value(f.key)?.let { m[f.key] = it }
        return JsonObject(m)
    }
}
