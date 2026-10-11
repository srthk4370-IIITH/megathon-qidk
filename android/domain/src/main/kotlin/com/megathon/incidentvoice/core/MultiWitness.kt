package com.megathon.incidentvoice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Extra 3 — multi-witness merging. One submitted report seen as "what this witness said". Pure Kotlin: the
 * duplicate finder and the merge rules have no Android or database dependency.
 */
data class WitnessAccount(
    val id: String, val refNo: String?, val reporter: String, val submittedAt: Long,
    val types: List<String> = emptyList(), val severity: String? = null,
    val locationText: String? = null, val locationId: String? = null, val occurredAt: Long? = null,
    val injuries: String? = null, val injuredCount: Int? = null, val hazardOngoing: String? = null,
    val equipment: List<String> = emptyList(), val actionsTaken: List<String> = emptyList(), val people: List<String> = emptyList(),
    val description: String? = null, val parentId: String? = null,
) {
    /** The case this account belongs to (itself when it is not linked to another report). */
    val rootId: String get() = parentId ?: id
    val who: String get() = reporter.ifBlank { refNo ?: "Witness" }

    companion object {
        fun of(meta: IncidentMeta, form: IncidentForm) = WitnessAccount(
            id = meta.id, refNo = meta.refNo, reporter = meta.reporter.name, submittedAt = meta.submittedAt ?: meta.reportedAt,
            types = form.types, severity = form.str("severity"), locationText = form.str("location"), locationId = form.locationId,
            occurredAt = (form.value("occurred_at") as? JsonPrimitive)?.longOrNull, injuries = form.str("injuries"),
            injuredCount = (form.value("injured_count") as? JsonPrimitive)?.longOrNull?.toInt(), hazardOngoing = form.str("hazard_ongoing"),
            equipment = form.value("equipment").asStringList(), actionsTaken = form.value("actions_taken").asStringList(),
            people = (form.value("persons") as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            description = form.str("description"), parentId = meta.parentId,
        )
    }
}

data class DuplicateCandidate(val account: WitnessAccount, val score: Double, val locationScore: Double, val minutesApart: Long, val minutesAgo: Long)

/** Finds earlier reports that are probably the same event. It only proposes; linking is always a person's decision. */
object DuplicateFinder {
    private val filler = setOf("near", "the", "at", "in", "on", "of", "by", "a", "an", "to", "next", "beside", "ke", "pass", "paas", "mein", "me")

    private fun tokens(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotBlank() && it !in filler }

    /** 1.0 for the same site-registry place, 0.0 for two different registry places, otherwise text similarity. */
    fun locationScore(a: WitnessAccount, b: WitnessAccount): Double {
        if (a.locationId != null && b.locationId != null) return if (a.locationId == b.locationId) 1.0 else 0.0
        val ta = tokens(a.locationText.orEmpty()); val tb = tokens(b.locationText.orEmpty())
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val sa = ta.joinToString(" "); val sb = tb.joinToString(" ")
        val edit = 1.0 - LocationMatcher.levenshtein(sa, sb).toDouble() / maxOf(sa.length, sb.length)
        val common = ta.toSet().intersect(tb.toSet()).size.toDouble()
        val overlap = 0.5 * (common / minOf(ta.toSet().size, tb.toSet().size) + common / ta.toSet().union(tb.toSet()).size)
        return maxOf(edit, overlap)
    }

    fun find(new: WitnessAccount, others: List<WitnessAccount>, cfg: AppConfig.MultiWitnessCfg, nowMs: Long): List<DuplicateCandidate> {
        val window = cfg.timeWindowMin * 60_000L
        val newAt = new.occurredAt ?: new.submittedAt
        return others.mapNotNull { o ->
            if (o.id == new.id || o.rootId == new.rootId) return@mapNotNull null
            if (nowMs - o.submittedAt > cfg.lookbackH * 3_600_000L) return@mapNotNull null
            val loc = locationScore(new, o)
            if (loc < cfg.minLocationScore) return@mapNotNull null
            if (new.types.intersect(o.types.toSet()).isEmpty()) return@mapNotNull null
            val dt = abs(newAt - (o.occurredAt ?: o.submittedAt))
            if (dt > window) return@mapNotNull null
            val cat = if (new.types.firstOrNull() == o.types.firstOrNull()) 1.0 else 0.6
            val score = 0.4 * loc + 0.3 * cat + 0.3 * (1.0 - dt.toDouble() / window)
            if (score < cfg.minScore) null else DuplicateCandidate(o, score, loc, dt / 60_000, (nowMs - o.submittedAt).coerceAtLeast(0) / 60_000)
        }.sortedByDescending { it.score }
    }

    private val prepositions = Regex("^(near|at|in|on|inside|outside|beside|behind|next to|in front of|by|opposite)\\b.*", RegexOption.IGNORE_CASE)
    private fun place(loc: String?) = when { loc.isNullOrBlank() -> "at this location"; prepositions.matches(loc.trim()) -> loc.trim(); else -> "at ${loc.trim()}" }

    fun question(c: DuplicateCandidate, categoryLabel: String): String =
        "This looks like the same ${categoryLabel.lowercase()} ${place(c.account.locationText)} reported ${c.minutesAgo} min ago (${c.account.refNo ?: "no reference"}). Add your report as a witness account?"
}

/** One row of the Field × Witness table. [values] is aligned with [MergedCase.accounts]; "—" = that witness did not say. */
data class MergedField(
    val key: String, val label: String, val values: List<String>, val merged: String, val conflict: Boolean,
    /** The value a supervisor chose to trust for a conflicting field (from the audit log). */
    val resolved: String? = null,
)

data class MergedCase(val accounts: List<WitnessAccount>, val fields: List<MergedField>) {
    /** Conflicts nobody has resolved yet. */
    val openConflicts: Int get() = fields.count { it.conflict && it.resolved == null }
    val conflicts: Int get() = fields.count { it.conflict }
    fun field(key: String) = fields.firstOrNull { it.key == key }
}

/**
 * Field-by-field merge of several witness accounts. Nothing is ever dropped: every account's value stays visible,
 * the merged value follows a fixed rule per field, and a disagreement that matters is marked as a conflict.
 */
object MergeEngine {
    private val sevOrder = listOf("LOW", "MEDIUM", "HIGH", "CRITICAL")
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Kolkata"))
    const val NONE = "—"

    fun merge(accounts: List<WitnessAccount>, resolutions: Map<String, String> = emptyMap(), categoryLabel: (String) -> String = { it }): MergedCase {
        val out = ArrayList<MergedField>()
        fun add(key: String, label: String, values: List<String?>, merged: String?, conflict: Boolean) {
            val r = resolutions[key].takeIf { conflict }
            out += MergedField(key, label, values.map { it?.takeIf { v -> v.isNotBlank() } ?: NONE }, r ?: merged ?: NONE, conflict, r)
        }

        // category: union, never a conflict (the same event is often filed under two headings)
        add("incident_type", "Category", accounts.map { a -> a.types.joinToString(" + ") { categoryLabel(it) } },
            accounts.flatMap { it.types }.distinct().joinToString(" + ") { categoryLabel(it) }, false)

        // severity: the worst reported; a conflict when the reports are two or more levels apart
        val sev = accounts.mapNotNull { a -> a.severity?.uppercase()?.takeIf { it in sevOrder } }
        add("severity", "Severity", accounts.map { it.severity?.uppercase() }, sev.maxByOrNull { sevOrder.indexOf(it) },
            sev.isNotEmpty() && sev.maxOf { sevOrder.indexOf(it) } - sev.minOf { sevOrder.indexOf(it) } >= 2)

        // location: the most reported; a conflict when the witnesses name different site-registry places
        val locKey = { a: WitnessAccount -> a.locationId ?: a.locationText?.lowercase()?.trim() }
        val topLoc = accounts.filter { locKey(it) != null }.groupBy(locKey).maxByOrNull { it.value.size }?.value?.first()?.locationText
        add("location", "Location", accounts.map { it.locationText }, topLoc, accounts.mapNotNull { it.locationId }.distinct().size > 1)

        // time: the earliest, shown as a range; a conflict when the reports are more than 15 minutes apart
        val times = accounts.mapNotNull { it.occurredAt }
        val tMerged = when {
            times.isEmpty() -> null
            times.max() - times.min() < 60_000 -> fmt(times.min())
            else -> fmt(times.min()) + "–" + fmt(times.max())
        }
        add("occurred_at", "Time of incident", accounts.map { a -> a.occurredAt?.let(::fmt) }, tMerged, times.isNotEmpty() && times.max() - times.min() > 15 * 60_000L)

        add("injuries", "Anyone injured", accounts.map { it.injuries?.uppercase() }, ynMerged(accounts.map { it.injuries }), ynConflict(accounts.map { it.injuries }))

        // counts: the highest; a conflict when the witnesses give different numbers
        val counts = accounts.mapNotNull { it.injuredCount }
        add("injured_count", "People injured", accounts.map { it.injuredCount?.toString() }, counts.maxOrNull()?.toString(), counts.distinct().size > 1)

        add("hazard_ongoing", "Hazard still present", accounts.map { it.hazardOngoing?.uppercase() }, ynMerged(accounts.map { it.hazardOngoing }), ynConflict(accounts.map { it.hazardOngoing }))

        // lists: union with near-duplicates folded together, never a conflict
        add("persons", "People involved", accounts.map { it.people.joinToString(", ") }, union(accounts.flatMap { it.people }).joinToString(", "), false)
        add("equipment", "Equipment", accounts.map { it.equipment.joinToString(", ") }, union(accounts.flatMap { it.equipment }).joinToString(", "), false)
        add("actions_taken", "Actions taken", accounts.map { it.actionsTaken.joinToString("; ") }, union(accounts.flatMap { it.actionsTaken }).joinToString("; "), false)

        // description: every account side by side, never merged into one voice
        add("description", "What happened", accounts.map { it.description },
            accounts.filter { !it.description.isNullOrBlank() }.joinToString("\n") { "${it.who}: ${it.description}" }, false)
        return MergedCase(accounts, out.filter { f -> f.key in setOf("severity", "location", "occurred_at", "description") || f.values.any { it != NONE } })
    }

    fun fmt(ms: Long): String = hhmm.format(Instant.ofEpochMilli(ms))

    private fun yn(v: String?) = v?.uppercase()?.takeIf { it == "YES" || it == "NO" || it == "UNKNOWN" }
    /** YES beats NO, anything beats UNKNOWN. */
    private fun ynMerged(vs: List<String?>): String? { val s = vs.mapNotNull(::yn); return when { "YES" in s -> "YES"; "NO" in s -> "NO"; s.isNotEmpty() -> "UNKNOWN"; else -> null } }
    private fun ynConflict(vs: List<String?>): Boolean { val s = vs.mapNotNull(::yn); return "YES" in s && "NO" in s }

    /** Union keeping first spellings; "fire extinguisher" and "Fire extinguishers" are one item. */
    fun union(items: List<String>): List<String> {
        val out = ArrayList<String>()
        for (raw in items) {
            val it = raw.trim(); if (it.isEmpty()) continue
            val n = norm(it)
            if (out.none { o -> val m = norm(o); m == n || 1.0 - LocationMatcher.levenshtein(m, n).toDouble() / maxOf(m.length, n.length, 1) >= 0.8 }) out += it
        }
        return out
    }
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Plain-language summary of the case, built from the merge (no model needed). */
    fun summary(c: MergedCase): String {
        val n = c.accounts.size
        val agree = c.fields.filter { !it.conflict && it.key in setOf("severity", "location", "injuries") && it.merged != NONE }.joinToString(", ") { "${it.label.lowercase()} ${it.merged}" }
        val differ = c.fields.filter { it.conflict }.joinToString("; ") { f -> f.label + ": " + f.values.filter { it != NONE }.distinct().joinToString(" vs ") + (f.resolved?.let { " (trusted: $it)" } ?: "") }
        return "$n accounts (${c.accounts.joinToString(", ") { it.who }})." + (if (agree.isNotBlank()) " Agreed: $agree." else "") +
            (if (differ.isNotBlank()) " They differ on $differ." else " No conflicting facts.")
    }
}
