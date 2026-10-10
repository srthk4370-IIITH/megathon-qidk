package com.megathon.incidentvoice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

data class MergeResult(val form: IncidentForm, val changed: List<String>, val notes: List<String>)

/**
 * Applies a [ParsedCall] to the form with provenance (PRD §8.3, §13.8, §15, §10.6).
 * Rules: USER_EDITED values are never overwritten; corrections overwrite FILLED/INFERRED/DEFAULTED.
 */
class FormMerger(
    private val tx: Taxonomy,
    private val times: TimeResolver,
    private val locations: LocationMatcher?,
) {
    private val ungroundedDropped = setOf("persons", "witnesses", "driver_name", "vehicle_ids")
    private val groundingExempt = setOf("description", "title", "incident_type", "secondary_types", "lang")

    fun merge(
        form0: IncidentForm,
        call: ParsedCall,
        source: FieldSource,
        turnId: Long?,
        transcript: String,
        now: Instant,
    ): MergeResult {
        var form = form0
        val changed = ArrayList<String>()
        val notes = ArrayList<String>()

        call.lang?.let { form = form.copy(lang = it) }
        if (call.lang == null && form.fields.isEmpty()) form = form.copy(lang = LangDetector.detect(transcript))

        for ((key, value) in call.fields) {
            val existing = form.state(key)
            if (existing?.status == FieldStatus.USER_EDITED) { notes += "kept user edit: $key"; continue }
            val def = tx.field(key) ?: continue
            var v: JsonElement = value
            var status = FieldStatus.FILLED
            var grounded = true

            when (key) {
                "occurred_at" -> {
                    val phrase = (value as? JsonPrimitive)?.contentOrNull ?: continue
                    val r = times.resolve(phrase, now)
                    if (r.defaulted) { notes += "time phrase unresolved: $phrase"; continue }
                    form = form.copy(timeExpression = phrase, occurredApprox = r.approximate)
                    v = JsonPrimitive(r.instant.toEpochMilli())
                }
                "equipment" -> v = JsonArray(PeopleNormaliser.normaliseEquipment(value.asStringList()).map { JsonPrimitive(it) })
                "secondary_types" -> {
                    val primary = (call.fields["incident_type"] as? JsonPrimitive)?.contentOrNull ?: form.incidentType
                    val l = value.asStringList().filter { it != primary }
                    if (l.isEmpty()) continue
                    v = JsonArray(l.map { JsonPrimitive(it) })
                }
                "persons", "witnesses" -> {
                    val newOnes = (value as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                    val kept = newOnes.filter { p ->
                        val n = (p["name"] as? JsonPrimitive)?.contentOrNull
                        n == null || Grounding.check(n, transcript)
                    }
                    if (kept.size < newOnes.size) notes += "dropped ungrounded person name"
                    val prior = (existing?.value as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                    val all = PeopleNormaliser.dedupePersons(prior + kept)
                    if (all.isEmpty()) continue
                    v = JsonArray(all)
                }
                "actions_taken" -> {
                    val prior = existing?.value.asStringList()
                    val all = (prior + value.asStringList()).distinctBy { it.lowercase() }
                    v = JsonArray(all.map { JsonPrimitive(it) })
                }
            }

            // grounding for free-text strings
            if (def.type == "string" || def.type == "string_list") {
                val text = v.asStringList().joinToString(" ")
                if (key in ungroundedDropped) {
                    if (!Grounding.check(text, transcript)) { notes += "dropped ungrounded $key"; continue }
                } else if (key !in groundingExempt && key != "occurred_at" && !Grounding.check(text, transcript)) {
                    grounded = false; status = FieldStatus.INFERRED
                }
            }
            if (source == FieldSource.LLM_PATCH && !grounded) { grounded = true; status = FieldStatus.FILLED } // user just answered

            form = form.with(FieldState(key, v, status, source, turnId, grounded, existing?.askedCount ?: 0))
            changed += key

            if (key == "location") {
                val m = locations?.match((v as JsonPrimitive).content)
                form = when (m?.kind) {
                    MatchKind.AUTO -> form.copy(locationId = m.location!!.id, locationSuggestionId = null)
                    MatchKind.SUGGEST -> form.copy(locationId = null, locationSuggestionId = m.location!!.id)
                    else -> form.copy(locationId = null, locationSuggestionId = null)
                }
            }
        }

        // UNKNOWN answers (patch)
        for (k in call.unknown) {
            val ex = form.state(k)
            if (ex?.status == FieldStatus.USER_EDITED) continue
            if (k == "incident_type" || k == "description") continue
            form = form.with(FieldState(k, null, FieldStatus.UNKNOWN, source, turnId, true, ex?.askedCount ?: 0))
            changed += k
        }

        if (call.otherIncidents.isNotEmpty()) {
            form = form.copy(otherIncidents = (form.otherIncidents + call.otherIncidents).distinctBy { it.lowercase() })
        }

        val post = applyRules(form, now)
        return MergeResult(post.first, (changed + post.second).distinct(), notes)
    }

    /** §15.3 consistency + §10.6 severity floor. Returns form and keys changed by rules. */
    fun applyRules(form0: IncidentForm, now: Instant = Instant.now()): Pair<IncidentForm, List<String>> {
        var form = form0
        val changed = ArrayList<String>()

        // injuries consistency
        val persons = (form.value("persons") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val injuredPersons = PeopleNormaliser.injuredCount(persons)
        val stated = (form.value("injured_count") as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
        val count = maxOf(stated, injuredPersons)
        val inj = form.state("injuries")
        if (count > 0 && inj?.status != FieldStatus.USER_EDITED && form.str("injuries") != "YES") {
            form = form.with(FieldState("injuries", JsonPrimitive("YES"), FieldStatus.FILLED, FieldSource.RULE, inj?.turnId, true, inj?.askedCount ?: 0))
            changed += "injuries"
        }
        if (count > stated) {
            val ic = form.state("injured_count")
            if (ic?.status != FieldStatus.USER_EDITED) {
                form = form.with(FieldState("injured_count", JsonPrimitive(count), FieldStatus.FILLED, FieldSource.RULE, null, true, 0))
                changed += "injured_count"
            }
        }

        // severity floor
        val floor = SeverityRules.floor(form)
        if (floor != null) {
            val st = form.state("severity")
            val cur = form.str("severity")
            if (st?.status != FieldStatus.USER_EDITED && SeverityRules.rank(cur) < SeverityRules.rank(floor)) {
                val status = if (cur == null || st?.status == FieldStatus.MISSING) FieldStatus.INFERRED else FieldStatus.FILLED
                form = form.with(FieldState("severity", JsonPrimitive(floor), status, FieldSource.RULE, null, true, st?.askedCount ?: 0))
                    .copy(severitySource = FieldSource.RULE)
                changed += "severity"
            }
        }
        return form to changed
    }

    /** R1-05 / T10: after extraction, an unspoken time defaults to the report time. */
    fun applyTimeDefault(form: IncidentForm, reportedAt: Instant): IncidentForm {
        val st = form.state("occurred_at")
        if (st != null && st.status != FieldStatus.MISSING) return form
        return form.with(
            FieldState("occurred_at", JsonPrimitive(reportedAt.toEpochMilli()), FieldStatus.DEFAULTED, FieldSource.SYSTEM, null, true, 0)
        ).copy(occurredApprox = true)
    }

    // -------- user / orchestrator helpers -----------------------------------------------------

    fun userEdit(form: IncidentForm, key: String, value: JsonElement?, now: Instant): IncidentForm {
        val ex = form.state(key)
        if (value == null) return form.with(FieldState(key, null, FieldStatus.MISSING, FieldSource.USER, null, true, ex?.askedCount ?: 0))
        var f = form
        var v = value
        if (key == "occurred_at" && value is JsonPrimitive && value.longOrNull == null) {
            val r = times.resolve(value.contentOrNull, now)
            f = f.copy(timeExpression = value.contentOrNull, occurredApprox = r.approximate)
            v = JsonPrimitive(r.instant.toEpochMilli())
        }
        if (key == "occurred_at" && value is JsonPrimitive && value.longOrNull != null) f = f.copy(occurredApprox = false)
        f = f.with(FieldState(key, v, FieldStatus.USER_EDITED, FieldSource.USER, null, true, ex?.askedCount ?: 0))
        if (key == "severity") f = f.copy(severitySource = FieldSource.USER)
        if (key == "location") {
            val m = locations?.match((v as? JsonPrimitive)?.contentOrNull)
            f = when (m?.kind) {
                MatchKind.AUTO -> f.copy(locationId = m.location!!.id, locationSuggestionId = null)
                MatchKind.SUGGEST -> f.copy(locationId = null, locationSuggestionId = m.location!!.id)
                else -> f.copy(locationId = null, locationSuggestionId = null)
            }
        }
        return f
    }

    fun userUnknown(form: IncidentForm, key: String): IncidentForm {
        if (key == "incident_type" || key == "description") return form
        val ex = form.state(key)
        return form.with(FieldState(key, null, FieldStatus.UNKNOWN, FieldSource.USER, null, true, ex?.askedCount ?: 0))
    }

    fun markAsked(form: IncidentForm, keys: List<String>): IncidentForm {
        var f = form
        for (k in keys) {
            val ex = f.state(k) ?: FieldState(k)
            f = f.with(ex.copy(askedCount = ex.askedCount + 1))
        }
        return f
    }
}
