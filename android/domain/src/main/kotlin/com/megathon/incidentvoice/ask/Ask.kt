package com.megathon.incidentvoice.ask

import com.megathon.incidentvoice.core.SiteRegistry
import com.megathon.incidentvoice.core.Taxonomy
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * PRD §37 O-2: conversational query over the local incident database.
 *
 * A question becomes an [IncidentQuery] (structured filters + free text for meaning-based search); the repository
 * runs it (SQL filters + FTS4 + vector similarity); [AnswerComposer] words the result. The parser here is rule-based;
 * when the Llama bundle is on the board the same [IncidentQuery] can be produced by a `search_incidents` tool call
 * instead (see [QueryUnderstanding]) — nothing downstream changes.
 */
enum class AskIntent { COUNT, LIST, LATEST, DETAIL }

data class IncidentQuery(
    val raw: String,
    val intent: AskIntent = AskIntent.LIST,
    val types: Set<String> = emptySet(),
    val severities: Set<String> = emptySet(),
    val injuries: String? = null,
    val locationIds: Set<String> = emptySet(),
    val locationLabel: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val timeLabel: String? = null,
    /** What is left of the question after the filters were taken out; drives the semantic (vector) ranking. */
    val freeText: String = "",
    val limit: Int = 5,
) {
    val hasFilters: Boolean get() = types.isNotEmpty() || severities.isNotEmpty() || injuries != null || locationIds.isNotEmpty() || fromMs != null
}

fun interface QueryUnderstanding { fun parse(question: String, now: Instant): IncidentQuery }

/** One retrieved incident, already flattened for display and for the spoken answer. */
data class AskHit(
    val id: String, val refNo: String?, val title: String, val type: String?, val severity: String?, val status: String,
    val location: String?, val whenMs: Long, val description: String? = null,
    val actionsTaken: List<String> = emptyList(), val recommendations: List<String> = emptyList(),
    /** 0..1 combined relevance; null when the result is ordered by recency only. */
    val score: Float? = null, val semantic: Float? = null, val keyword: Boolean = false,
)

data class AskAnswer(val query: IncidentQuery, val text: String, val hits: List<AskHit>, val total: Int, val tookMs: Long = 0, val mode: String = "") {
    /** What is read aloud: the first sentences up to ~220 characters (the full text stays on screen). */
    val spoken: String get() {
        if (text.length <= 220) return text
        val parts = text.split(Regex("(?<=[.!?])\\s+"))
        val sb = StringBuilder()
        for (p in parts) { if (sb.isNotEmpty() && sb.length + p.length > 220) break; sb.append(p).append(' ') }
        return sb.toString().trim().ifEmpty { text.take(220) }
    }
}

class RuleQueryParser(private val tx: Taxonomy, private val registry: SiteRegistry?, private val zone: ZoneId = ZoneId.of("Asia/Kolkata")) : QueryUnderstanding {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /**
     * Only generic category nouns become a hard type filter. Specific things ("ammonia", "forklift", "ceiling") stay in
     * the free text and are matched by meaning/keyword instead: a forklift report may be filed as MEDICAL or VEHICLE.
     */
    private val typeWords = listOf(
        "FIRE" to "fires?|explosions?|aag",
        "MEDICAL" to "medical|injur(y|ies)|first aid",
        "WATER" to "water leaks?|leaks?|flood\\w*|paani",
        "ELECTRICAL" to "electrical|electric|short circuits?|power (cut|outage)s?|bijli",
        "HAZMAT" to "gas leaks?|gas|chemicals?|hazmat|hazardous",
        "EQUIPMENT" to "equipment|machines?|machinery|breakdowns?",
        "SECURITY" to "security|thefts?|break[- ]?ins?|intrusions?|chori",
        "STRUCTURAL" to "structural|building damage",
        "ENVIRONMENTAL" to "environmental|pollution|spills?|emissions?",
        "VEHICLE" to "vehicles?|collisions?|traffic",
    )
    private val numWords = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "ten" to 10, "a" to 1, "an" to 1)
    private val stop = setOf(
        "show", "me", "all", "the", "a", "an", "of", "in", "on", "at", "to", "for", "from", "and", "or", "any", "there", "were", "was", "is", "are", "be", "been", "have", "has", "had", "do", "did", "does",
        "what", "which", "who", "when", "where", "how", "many", "much", "list", "find", "give", "tell", "about", "incident", "incidents", "report", "reports", "reported", "happened", "happen", "we", "us", "i",
        "please", "can", "you", "that", "this", "with", "by", "it", "its", "number", "count", "total", "recent", "latest", "last", "most", "similar", "like", "related", "involving", "near", "around", "during", "something",
        "someone", "somebody", "anyone", "anybody", "people", "person", "their", "they", "his", "her", "him", "she", "our", "your", "been", "being", "into", "onto", "over", "under", "after", "before", "then", "than", "but", "not", "one", "got", "get", "kya", "hai", "hua", "mein", "ke", "ka", "ki", "batao", "dikhao", "kitne", "kitni", "sab", "any", "ever", "anything", "everything", "them", "those", "these", "some", "get", "see", "search", "look", "up", "details", "detail", "done", "taken", "actions", "action",
    )

    override fun parse(question: String, now: Instant): IncidentQuery {
        val q = " " + question.lowercase().replace(Regex("[^\\p{L}\\p{N}\\- ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        var rest = q
        fun take(re: Regex): MatchResult? = re.find(rest)?.also { rest = rest.replaceRange(it.range, " ") }

        // ---- intent
        val intent = when {
            rx("\\b(how many|count|number of|total number|kitne|kitni)\\b").containsMatchIn(q) -> AskIntent.COUNT
            rx("\\b(latest|most recent|last incident|last report|newest)\\b").containsMatchIn(q) -> AskIntent.LATEST
            rx("\\b(what happened|tell me about|details? (of|about|on)|what was done|what actions?|describe|explain|kya hua)\\b").containsMatchIn(q) -> AskIntent.DETAIL
            else -> AskIntent.LIST
        }

        // ---- time range (taken out of the text before anything else: "last week" is not a topic)
        val z = now.atZone(zone)
        val startToday = z.truncatedTo(ChronoUnit.DAYS)
        var from: ZonedDateTime? = null; var to: ZonedDateTime? = null; var label: String? = null
        fun set(f: ZonedDateTime, t: ZonedDateTime?, l: String) { from = f; to = t; label = l }
        when {
            take(rx("\\b(today|aaj)\\b")) != null -> set(startToday, null, "today")
            take(rx("\\b(yesterday|kal)\\b")) != null -> set(startToday.minusDays(1), startToday, "yesterday")
            take(rx("\\blast night\\b")) != null -> set(startToday.minusDays(1).withHour(18), startToday.withHour(6), "last night")
            take(rx("\\bthis week\\b")) != null -> set(startToday.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), null, "this week")
            take(rx("\\blast week\\b")) != null -> { val mon = startToday.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); set(mon.minusWeeks(1), mon, "last week") }
            take(rx("\\bthis month\\b")) != null -> set(startToday.withDayOfMonth(1), null, "this month")
            take(rx("\\blast month\\b")) != null -> { val f = startToday.withDayOfMonth(1); set(f.minusMonths(1), f, "last month") }
            else -> take(rx("\\b(?:in |over |during |for )?(?:the )?(?:last|past|previous) (\\d+|one|two|three|four|five|six|seven|ten|a|an)? ?(hours?|days?|weeks?|months?)\\b"))?.let { m ->
                val n = m.groupValues[1].let { it.toIntOrNull() ?: numWords[it] ?: 1 }
                val unit = m.groupValues[2]
                val f = when { unit.startsWith("hour") -> z.minusHours(n.toLong()); unit.startsWith("day") -> z.minusDays(n.toLong()); unit.startsWith("week") -> z.minusWeeks(n.toLong()); else -> z.minusMonths(n.toLong()) }
                set(f, null, "in the last $n ${unit.trimEnd('s')}${if (n > 1) "s" else ""}")
            }
        }

        // ---- severity
        val sev = LinkedHashSet<String>()
        if (take(rx("\\bcritical\\b")) != null) sev += "CRITICAL"
        if (take(rx("\\bhigh( severity| priority)?\\b")) != null) sev += "HIGH"
        if (take(rx("\\b(medium|moderate)( severity)?\\b")) != null) sev += "MEDIUM"
        if (take(rx("\\b(low|minor)( severity)?\\b")) != null) sev += "LOW"
        if (take(rx("\\b(serious|severe|major|emergenc(y|ies))\\b")) != null) { sev += "HIGH"; sev += "CRITICAL" }
        take(rx("\\bseverity\\b"))

        // ---- injuries
        var injuries: String? = null
        if (take(rx("\\b(with|involving|where) (an? )?(injur\\w+|someone (was |got )?hurt|people (were |got )?hurt|casualt\\w+)\\b|\\b(someone|anyone|people|workers?) (was|were|got) (hurt|injured)\\b")) != null) injuries = "YES"
        else if (take(rx("\\b(without|no) injur\\w+\\b|\\bnobody (was )?hurt\\b")) != null) injuries = "NO"

        // ---- location (site registry names and aliases, longest first; then building names)
        val locIds = LinkedHashSet<String>(); var locLabel: String? = null
        var norm = SpokenPlaces.normalise(rest, registry)
        val names = registry?.locations.orEmpty().flatMap { l -> (listOf(l.name) + l.aliases).map { it.lowercase() to l } }.filter { it.first.length >= 4 }.sortedByDescending { it.first.length }
        for ((n, l) in names) {
            val i = norm.indexOf(" $n ")
            if (i >= 0) { locIds += l.id; locLabel = l.name; norm = norm.replaceRange(i, i + n.length + 1, " "); break }
        }
        if (locIds.isEmpty()) SpokenPlaces.nearMisses(norm, registry).singleOrNull()?.let { name ->
            registry!!.locations.firstOrNull { it.name == name }?.let { l -> locIds += l.id; locLabel = l.name; norm = norm.replace(Regex("\\b[a-z]{2,6} ${l.name.substringAfterLast(' ')}\\b"), " ") }
        }
        if (locIds.isEmpty()) {
            for (b in registry?.locations.orEmpty().map { it.building }.filter { it.isNotBlank() }.distinct()) {
                val i = norm.indexOf(" ${b.lowercase()} ")
                if (i >= 0) { registry!!.locations.filter { it.building == b }.forEach { locIds += it.id }; locLabel = b; norm = norm.replaceRange(i, i + b.length + 1, " "); break }
            }
        }
        rest = norm

        // ---- incident types
        val types = LinkedHashSet<String>()
        for ((code, words) in typeWords) if (take(rx("\\b($words)\\b")) != null) { types += code; while (take(rx("\\b($words)\\b")) != null) { /* drop repeats */ } }
        // "leak" alone is ambiguous: a gas/chemical question should not also filter on WATER
        if ("HAZMAT" in types && "WATER" in types && !rx("\\bwater\\b").containsMatchIn(q)) types -= "WATER"

        // keep short tokens that carry a digit ("p-101", "b4"): they are asset tags / place codes worth a keyword search
        val free = rest.split(' ').filter { (it.length >= 3 || (it.length == 2 && it.any(Char::isDigit) && it.any(Char::isLetter))) && it !in stop && !it.all(Char::isDigit) }.joinToString(" ")
        return IncidentQuery(
            raw = question.trim(), intent = intent, types = types, severities = sev, injuries = injuries, locationIds = locIds, locationLabel = locLabel,
            fromMs = from?.toInstant()?.toEpochMilli(), toMs = to?.toInstant()?.toEpochMilli(), timeLabel = label, freeText = free,
            limit = if (intent == AskIntent.LATEST || intent == AskIntent.DETAIL) 3 else 5,
        )
    }
}

/**
 * Speech recognisers write places the way they sound: "B4" or "bay for" for "Bay 4", "conference room to" for
 * "Conference Room 2". This rewrites such forms to the site-registry spelling before matching.
 */
object SpokenPlaces {
    private val digits = mapOf("one" to "1", "won" to "1", "two" to "2", "to" to "2", "too" to "2", "three" to "3", "tree" to "3", "four" to "4", "for" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "ate" to "8", "nine" to "9", "ten" to "10")

    fun normalise(text: String, registry: SiteRegistry?): String {
        var t = " " + text.lowercase().trim() + " "
        val names = registry?.locations.orEmpty().map { it.name.lowercase() }
        // numbered places: the word before the number in a registry name ("bay", "room", "line", …)
        val heads = names.mapNotNull { Regex("^(.*\\b([a-z]+)) (\\d+)$").find(it)?.groupValues?.get(2) }.toSet()
        for (h in heads) for ((w, d) in digits) t = t.replace(Regex("\\b$h $w\\b"), "$h $d")
        for ((w, d) in digits) if (w.length > 3 || w in setOf("one", "two", "six", "ten")) t = t.replace(Regex("\\b$w\\b"), d)
        // letter+number codes: "b4" → the registry place whose words start with that letter and end with that number
        t = Regex("\\b([a-z]) ?(\\d{1,2})\\b").replace(t) { m ->
            val (l, n) = m.destructured
            val cands = names.filter { it.startsWith(l) && it.endsWith(" $n") && it.split(' ').size == 2 }
            if (cands.size == 1) cands.single() else m.value
        }
        return t
    }

    /**
     * "Bed 4" for "Bay 4": a numbered place whose first word is one or two letters off a registry word with the same
     * initial. Returns the registry names this could be (used as a *suggestion* in the report, as a filter in Ask).
     */
    fun nearMisses(text: String, registry: SiteRegistry?): List<String> {
        val names = registry?.locations.orEmpty().map { it.name }
        val out = ArrayList<String>()
        for (m in Regex("\\b([a-z]{2,6}) (\\d{1,2})\\b").findAll(text.lowercase())) {
            val (w, n) = m.destructured
            for (name in names) {
                val parts = name.lowercase().split(' ')
                if (parts.size != 2 || parts[1] != n) continue
                val head = parts[0]
                if (head == w || head[0] != w[0] || kotlin.math.abs(head.length - w.length) > 1) continue
                if (com.megathon.incidentvoice.core.LocationMatcher.levenshtein(head, w) <= 2) out += name
            }
        }
        return out.distinct()
    }
}

/** Words the answer. Short enough to be spoken; the matching reports are shown as cards under it. */
class AnswerComposer(private val tx: Taxonomy, private val zone: ZoneId = ZoneId.of("Asia/Kolkata")) {
    private val dayFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
    private fun whenText(ms: Long) = dayFmt.format(Instant.ofEpochMilli(ms).atZone(zone))
    private fun typeLabel(code: String?) = tx.category(code ?: "")?.label?.substringBefore(" /")?.lowercase() ?: "incident"

    private fun describe(q: IncidentQuery, plural: Boolean): String {
        val sev = q.severities.takeIf { it.isNotEmpty() }?.joinToString(" or ") { it.lowercase() }
        val type = q.types.takeIf { it.isNotEmpty() }?.joinToString(" or ") { typeLabel(it) }
        val noun = (if (type != null) "$type incident" else "incident") + if (plural) "s" else ""
        return listOfNotNull(sev, noun, if (q.injuries == "YES") "with injuries" else if (q.injuries == "NO") "without injuries" else null,
            q.locationLabel?.let { "at $it" }, q.timeLabel?.let { if (it.startsWith("in ")) it else if (it == "today" || it == "yesterday" || it == "last night") it else it }).joinToString(" ")
    }

    private fun oneLine(h: AskHit) = buildString {
        append(h.title.trimEnd('.'))
        // skip the place when the title already names it ("Fire at DB-3 panel" + "DB-3 panel Block B floor 2")
        h.location?.takeIf { loc ->
            val head = loc.trim().split(' ').filter { it.lowercase() !in setOf("near", "behind", "outside", "the", "in", "at") }.take(2).joinToString(" ")
            loc.isNotBlank() && head.isNotBlank() && !h.title.contains(head, ignoreCase = true)
        }?.let { append(" at ").append(it) }
        append(", ").append(whenText(h.whenMs))
        h.severity?.let { append(", severity ").append(it.lowercase()) }
    }

    fun compose(q: IncidentQuery, hits: List<AskHit>, total: Int): String {
        if (total == 0 || hits.isEmpty()) {
            val what = describe(q, plural = true)
            return if (q.freeText.isNotBlank() && !q.hasFilters) "I couldn't find any report about \"${q.freeText}\"."
            else "I couldn't find any $what" + (if (q.freeText.isNotBlank()) " about \"${q.freeText}\"" else "") + "."
        }
        val top = hits.first()
        // Nothing specific was understood ("what happened?", or a mis-heard place): say so instead of implying a match.
        if (!q.hasFilters && q.freeText.isBlank() && q.intent != AskIntent.COUNT && q.intent != AskIntent.LATEST)
            return "I didn't catch a specific place, type or time, so here are the $total most recent report${if (total != 1) "s" else ""}. The latest is: ${oneLine(top)}."
        return when (q.intent) {
            AskIntent.COUNT -> {
                val what = describe(q, plural = total != 1)
                (if (total == 1) "There is 1 $what" else "There are $total $what") + (if (q.freeText.isNotBlank()) " matching \"${q.freeText}\"" else "") + ". " +
                    (if (total == 1) "It is: " else "The most recent is: ") + oneLine(top) + "."
            }
            AskIntent.LATEST -> "The most recent ${describe(q, plural = false)} is: ${oneLine(top)}." + (top.description?.let { " ${it.trimEnd('.')}." } ?: "")
            AskIntent.DETAIL -> buildString {
                append(oneLine(top)).append(". ")
                top.description?.let { append(it.trimEnd('.')).append(". ") }
                if (top.actionsTaken.isNotEmpty()) append("Actions taken: ").append(top.actionsTaken.joinToString("; ") { it.trimEnd('.') }).append(". ")
                if (top.recommendations.isNotEmpty()) append("Recommended next: ").append(top.recommendations.take(2).joinToString("; ") { it.trimEnd('.') }).append(".")
                if (total > 1) append(" ${total - 1} other report${if (total > 2) "s" else ""} also match${if (total == 2) "es" else ""}.")
            }.trim()
            AskIntent.LIST -> {
                val what = describe(q, plural = total != 1)
                val lead = if (q.freeText.isNotBlank() && !q.hasFilters) "I found $total report${if (total != 1) "s" else ""} related to \"${q.freeText}\"."
                else "I found $total $what" + (if (q.freeText.isNotBlank()) " related to \"${q.freeText}\"" else "") + "."
                lead + " " + (if (total == 1) "It is: " else "The closest match is: ".takeIf { q.freeText.isNotBlank() } ?: "The most recent is: ") + oneLine(top) + "."
            }
        }
    }
}

/** Hybrid ranking (PRD O-2): cosine similarity of the question and incident vectors, boosted by a keyword (FTS) hit. */
object HybridRank {
    /**
     * Calibrated on the quantized MiniLM with the demo reports: a report that answers the question scores 0.28–0.6,
     * unrelated ones stay below 0.25.
     */
    const val MIN_SEMANTIC = 0.28f
    const val MIN_SEMANTIC_WITH_KEYWORD = 0.18f

    /** @param allWords every content word of the question occurs in the report; @param anyWord at least one does. */
    fun score(semantic: Float?, allWords: Boolean, anyWord: Boolean = allWords): Float =
        ((semantic ?: 0f).coerceIn(0f, 1f) + if (allWords) 0.25f else if (anyWord) 0.08f else 0f).coerceIn(0f, 1f)

    /** Keep a hit when it matches by meaning, contains all the question's words, or is a weaker mix of both. */
    fun relevant(semantic: Float?, allWords: Boolean, anyWord: Boolean = allWords): Boolean {
        val s = semantic ?: 0f
        return s >= MIN_SEMANTIC || allWords || (anyWord && s >= MIN_SEMANTIC_WITH_KEYWORD)
    }
}
