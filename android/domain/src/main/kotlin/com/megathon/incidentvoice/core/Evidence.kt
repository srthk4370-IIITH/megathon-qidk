package com.megathon.incidentvoice.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import kotlin.math.abs

/**
 * Where a form value came from: characters [start, end) of the reporter's utterance number [turn]
 * (0 = the narration, then each follow-up answer, in the order they were said).
 */
@Serializable
data class EvidenceSpan(val turn: Int, val start: Int, val end: Int)

/**
 * "Show your evidence": for each short field, the words of the transcript that support it. Built on [Grounding];
 * no model involved. A field with a value and no span is shown as "needs review".
 */
object Evidence {
    /** The short fields that carry evidence. Description and SOP steps do not. */
    val FIELDS = listOf("incident_type", "location", "occurred_at", "injuries", "persons", "equipment", "actions_taken", "witnesses")
    private const val MAX_SPANS = 6
    private const val TIME_TOLERANCE_MS = 5 * 60_000L

    /** True when the field has a value that nothing in the transcript supports (values typed by the reporter are theirs). */
    fun needsReview(form: IncidentForm, key: String): Boolean {
        if (key !in FIELDS || !hasValue(form, key)) return false
        return form.evidence[key].isNullOrEmpty()
    }

    private fun hasValue(form: IncidentForm, key: String): Boolean {
        val st = form.state(key) ?: return false
        return st.value != null && st.status != FieldStatus.MISSING && st.status != FieldStatus.UNKNOWN && st.status != FieldStatus.USER_EDITED
    }

    /**
     * Recomputes the spans. [turns] = what the reporter said, in order. After a follow-up answer pass the keys it
     * [changed] and its index as [answerTurn]: those values point to the answer (the whole answer when no single
     * word carries them, e.g. "No."), every other field keeps the span it had.
     */
    fun attach(
        form: IncidentForm, turns: List<String>, tx: Taxonomy, now: Instant, times: TimeResolver = TimeResolver(),
        changed: Collection<String>? = null, answerTurn: Int? = null,
    ): IncidentForm {
        val out = LinkedHashMap<String, List<EvidenceSpan>>()
        val answer = answerTurn?.takeIf { it in turns.indices }
        for (key in FIELDS) {
            if (!hasValue(form, key)) continue
            if (changed != null && key !in changed) { form.evidence[key]?.let { out[key] = it }; continue }
            var spans = find(key, form, turns, tx, now, times, answer)
            if (spans.isEmpty() && answer != null && changed != null && form.state(key)?.status != FieldStatus.DEFAULTED) spans = whole(answer, turns[answer])
            if (spans.isNotEmpty()) out[key] = spans.distinct().sortedWith(compareBy({ it.turn }, { it.start })).take(MAX_SPANS)
        }
        return form.copy(evidence = out)
    }

    private fun whole(turn: Int, text: String): List<EvidenceSpan> {
        val s = text.indexOfFirst { !it.isWhitespace() }
        return if (s < 0) emptyList() else listOf(EvidenceSpan(turn, s, text.indexOfLast { !it.isWhitespace() } + 1))
    }

    private fun find(key: String, form: IncidentForm, turns: List<String>, tx: Taxonomy, now: Instant, times: TimeResolver, answer: Int?): List<EvidenceSpan> {
        val v = form.value(key)
        return when (key) {
            "incident_type" -> cues(tx.category(form.incidentType.orEmpty())?.searchCues.orEmpty(), turns)
            "occurred_at" -> time(form, turns, now, times, answer)
            "injuries" -> injuries(form, turns, answer)
            "persons", "witnesses" -> (v as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("name") as? JsonPrimitive }
                .mapNotNull { it.contentOrNull }.flatMap { said(it, turns, answer) }
            else -> v.asStringList().flatMap { said(it, turns, answer) }
        }
    }

    /** The value in the answer if it is there, otherwise wherever it is best supported. */
    private fun said(value: String, turns: List<String>, answer: Int?): List<EvidenceSpan> {
        fun at(i: Int) = Grounding.locate(value, turns[i])
        if (answer != null) at(answer).takeIf { it.ranges.isNotEmpty() }?.let { l -> return l.ranges.map { EvidenceSpan(answer, it.first, it.last + 1) } }
        val all = turns.indices.map { it to at(it) }.filter { it.second.ranges.isNotEmpty() }
        val best = all.maxOfOrNull { it.second.score } ?: return emptyList()
        return all.filter { it.second.score == best }.flatMap { (i, l) -> l.ranges.map { EvidenceSpan(i, it.first, it.last + 1) } }
    }

    private fun matches(re: Regex, turns: List<String>): List<EvidenceSpan> =
        turns.flatMapIndexed { i, t -> re.findAll(t).filter { it.value.isNotBlank() }.map { EvidenceSpan(i, it.range.first, it.range.last + 1) } }

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** The category's own cue words ("leak", "aag"), also through the Hinglish lexicon ("paani" for "water"). */
    private fun cues(cues: List<String>, turns: List<String>): List<EvidenceSpan> {
        if (cues.isEmpty()) return emptyList()
        val re = rx("(?<![\\p{L}\\p{N}])(?:" + cues.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\p{L}*")
        val set = cues.map { it.lowercase() }.toSet()
        return matches(re, turns) + turns.flatMapIndexed { i, t -> Grounding.translated(set, t).map { EvidenceSpan(i, it.first, it.last + 1) } }
    }

    private val NO_INJURY = rx(
        "\\b(?:no ?one|nobody|none|no|not|never|nahi|nahin|kisi ko|koi)\\b[^.;!?]{0,30}?\\b(?:hurt|injur\\w*|harm\\w*|chot(?: nahi\\w*)?(?: lag\\w*| aa\\w*)?|ghayal)" +
            "|\\bno injur\\w*|\\bchot nahi\\w*(?: lag\\w*| aa\\w*)?|\\bsab (?:theek|safe)(?: hai\\w*)?|\\beveryone is (?:safe|fine|okay|ok)\\b",
    )
    private val INJURY = rx(
        "\\b(?:hurt\\w*|injur\\w*|chot(?: lag\\w*| aa\\w*)?|ghayal|bleed\\w*|fractur\\w*|burn(?:t|ed|s)?|unconscious|behosh|sprain\\w*|wound\\w*|khoon|" +
            "broke (?:his|her|my|their) \\w+|cut (?:his|her|my|their) \\w+|twisted (?:his|her|my|their) \\w+|in pain|electric shock|shock laga|fainted|collapsed)",
    )

    private fun injuries(form: IncidentForm, turns: List<String>, answer: Int?): List<EvidenceSpan> {
        val re = when (form.str("injuries")) { "NO" -> NO_INJURY; "YES" -> INJURY; else -> return emptyList() }
        val scope = if (answer != null) matches(re, turns).filter { it.turn == answer } else emptyList()
        if (scope.isNotEmpty()) return scope
        val all = matches(re, turns)
        // "nobody was hurt" also contains "hurt": a YES must not rest on words inside a denial
        if (form.str("injuries") == "YES") {
            val denied = matches(NO_INJURY, turns)
            val kept = all.filter { s -> denied.none { it.turn == s.turn && s.start >= it.start && s.end <= it.end } }
            if (kept.isNotEmpty()) return kept
            // set by a rule from an injured person: point to how that person was hurt
            return (form.value("persons") as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("injury") as? JsonPrimitive)?.contentOrNull }.flatMap { said(it, turns, answer) }
        }
        return all
    }

    private val N = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty"
    private val HN = "ek|do|teen|char|paanch|panch|chhe|saat|aath|nau|das|gyarah|barah|pandrah|bees|tees"
    private val TIME_PHRASES = listOf(
        "just now|right now|abhi abhi",
        "(?:about |around |almost )?(?:half an hour|an hour|a few minutes|a while|\\d+ ?(?:minutes?|mins?|hours?|hrs?)|(?:$N) (?:minutes?|hours?)) (?:ago|back)",
        "(?:lagbhag |kareeb )?(?:aadha ghanta|adha ghanta|thodi der|kuch der|\\d+ ?(?:minute|ghante|ghanta)|(?:$HN) (?:minute|ghante|ghanta)) pehle",
        "(?:last night|yesterday|today|kal raat|kal|aaj)(?: (?:morning|afternoon|evening|night|subah|shaam|raat|dopahar))?(?: (?:ko )?(?:at |around |about |lagbhag |kareeb )?\\d{1,2}(?:[:.]\\d{2})?(?: ?(?:am|pm|a\\.m\\.|p\\.m\\.|baje))?)?",
        "(?:at |around |about |at around |lagbhag |kareeb )?\\d{1,2}(?:[:.]\\d{2})? ?(?:am\\b|pm\\b|a\\.m\\.|p\\.m\\.|baje|o'?clock)(?: in the (?:morning|afternoon|evening))?",
        "(?:at|around|about) (?:\\d{1,2}(?:[:.]\\d{2})?|$N)(?: in the (?:morning|afternoon|evening)| at night)?",
        "(?:subah|shaam|raat|dopahar)(?: ko)?(?: lagbhag| kareeb)? (?:saadhe |sawa |paune )?(?:\\d{1,2}|$HN)(?: baje)?",
        "this (?:morning|afternoon|evening)|aaj (?:subah|shaam|dopahar)|since (?:this )?morning",
    ).map { rx("(?<![\\p{L}\\p{N}])(?:$it)") }

    /**
     * The phrase the time was resolved from. The stored phrase is searched first (so "at 10, no, 11" points to "11");
     * when the model paraphrased it ("half an hour ago" for "aadha ghanta pehle"), the time phrases in the transcript
     * are resolved by the same rules and the one that gives the stored time is taken.
     */
    private fun time(form: IncidentForm, turns: List<String>, now: Instant, times: TimeResolver, answer: Int?): List<EvidenceSpan> {
        if (form.state("occurred_at")?.status == FieldStatus.DEFAULTED) return emptyList()
        form.timeExpression?.takeIf { it.isNotBlank() }?.let { p -> said(p, turns, answer).takeIf { it.isNotEmpty() }?.let { return it } }
        val target = (form.value("occurred_at") as? JsonPrimitive)?.longOrNull ?: return emptyList()
        val cands = TIME_PHRASES.flatMap { matches(it, turns) }.mapNotNull { s ->
            val r = times.resolve(turns[s.turn].substring(s.start, s.end), now)
            if (r.defaulted) null else s to abs(r.instant.toEpochMilli() - target)
        }.filter { it.second <= TIME_TOLERANCE_MS }
        val best = cands.minOfOrNull { it.second } ?: return emptyList()
        val hits = cands.filter { it.second == best }.map { it.first }
        // the longest phrase at each place ("yesterday at 5 pm", not just "5 pm")
        return hits.filter { a -> hits.none { b -> b !== a && b.turn == a.turn && b.start <= a.start && b.end >= a.end && (b.end - b.start) > (a.end - a.start) } }
    }
}
