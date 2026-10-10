package com.megathon.incidentvoice.core.normalize

import com.megathon.incidentvoice.core.config.SiteLocation
import com.megathon.incidentvoice.core.config.SiteRegistry

/**
 * Fuzzy match of the spoken location against the site registry, PRD §15.2.
 * Score = max(tokenSetRatio, jaroWinkler) over the name and each alias; ≥ [autoThreshold] auto-match,
 * ≥ [suggestThreshold] suggestion, else free text only. The spoken text is always kept by the caller.
 *
 * Guards added on top of the spec (token-set ratio is 1.0 for any subset):
 *  - number tokens must agree ("conference room 5" never matches "Conference Room 2");
 *  - a match carried only by generic words ("room", "area", …) does not count as a subset match.
 */
class LocationMatcher(
    private val registry: SiteRegistry,
    val autoThreshold: Double = 0.85,
    val suggestThreshold: Double = 0.70,
) {
    enum class Kind { AUTO, SUGGESTION, NONE }

    data class Match(
        val kind: Kind,
        val location: SiteLocation?,
        val score: Double,
        /** First qualifier removed from the phrase ("near", "ke paas", …), for display "(near)". */
        val qualifier: String?,
        val normalized: String,
    ) {
        val locationId: String? get() = location?.id

        /** "Conference Room 2 · Block A · Floor 1 (near)". */
        val display: String?
            get() = location?.let { l ->
                buildString {
                    append(l.name)
                    l.building?.let { append(" · ").append(it) }
                    l.floor?.let { append(" · Floor ").append(it) }
                    qualifier?.let { append(" (").append(it).append(")") }
                }
            }
    }

    companion object {
        /** Multi-word first so "next to" wins over "to". */
        val QUALIFIERS = listOf(
            "in front of", "next to", "close to", "opposite to", "ke paas", "ke pass", "ke bahar", "ke andar",
            "ke saamne", "ke samne", "ke peeche", "ke piche", "ke upar", "ke neeche", "ke bagal", "ke beech",
            "near", "nearby", "beside", "behind", "outside", "inside", "opposite", "at", "the", "mein", "main", "par",
            "pe", "in", "on", "by", "of", "area of", "around",
        )
        private val DISPLAY_QUALIFIERS = mapOf(
            "ke paas" to "near", "ke pass" to "near", "ke bahar" to "outside", "ke andar" to "inside",
            "ke saamne" to "in front of", "ke samne" to "in front of", "ke peeche" to "behind", "ke piche" to "behind",
            "nearby" to "near", "close to" to "near", "ke bagal" to "beside",
        )
        private val IGNORED_FOR_QUALIFIER = setOf("the", "at", "of", "mein", "main", "par", "pe", "in", "on", "by")
        val ABBREVIATIONS = mapOf(
            "conf" to "conference", "confrence" to "conference", "rm" to "room", "blk" to "block", "flr" to "floor",
            "bldg" to "building", "bld" to "building", "dept" to "department", "lab" to "lab", "wh" to "warehouse",
            "rec" to "reception", "gnd" to "ground", "grd" to "ground", "no" to "number",
        )
        val GENERIC = setOf("room", "area", "block", "floor", "building", "hall", "office", "zone", "side", "number", "main")
    }

    private data class Entry(val loc: SiteLocation, val text: String, val tokens: Set<String>)

    private val entries: List<Entry> = registry.locations.flatMap { l ->
        (listOf(l.name) + l.aliases).map { t -> val n = normalize(t).first; Entry(l, n, Fuzzy.tokens(n).toSet()) }
    }

    /** Returns (normalised text, first qualifier). */
    fun normalize(raw: String): Pair<String, String?> {
        var s = " " + raw.lowercase().replace(Regex("""[^\p{L}\p{N}\s-]"""), " ").replace('-', ' ') + " "
        s = s.replace(Regex("""\s+"""), " ")
        var firstQualifier: String? = null
        var firstPos = Int.MAX_VALUE
        for (q in QUALIFIERS) {
            val re = Regex("""(?<=\s)${Regex.escape(q)}(?=\s)""")
            val m = re.find(s)
            if (m != null && q !in IGNORED_FOR_QUALIFIER && m.range.first < firstPos) {
                firstPos = m.range.first; firstQualifier = DISPLAY_QUALIFIERS[q] ?: q
            }
            s = re.replace(s, " ").replace(Regex("""\s+"""), " ")
            if (!s.startsWith(" ")) s = " $s"
            if (!s.endsWith(" ")) s = "$s "
        }
        s = NumberWords.replaceEnglishWords(s)
        val tokens = s.trim().split(' ').filter { it.isNotEmpty() }.map { ABBREVIATIONS[it] ?: it }
        // Hinglish place words → English (seedhi → stairs …) are added by the alias list; keep tokens as spoken.
        return tokens.joinToString(" ") to firstQualifier
    }

    private fun digits(tokens: Set<String>) = tokens.filter { t -> t.any { it.isDigit() } }.toSet()

    fun score(query: String, candidate: String): Double {
        val qt = Fuzzy.tokens(query).toSet()
        val ct = Fuzzy.tokens(candidate).toSet()
        if (qt.isEmpty() || ct.isEmpty()) return 0.0
        val qd = digits(qt)
        val cd = digits(ct)
        if (qd.isNotEmpty() && cd.isNotEmpty() && qd.intersect(cd).isEmpty()) return minOf(0.5, Fuzzy.ratio(query, candidate))
        val inter = qt.intersect(ct)
        val tsr = if (inter.isNotEmpty() && inter.all { it in GENERIC }) {
            Fuzzy.ratio(query, candidate) // generic-only overlap: no subset bonus
        } else Fuzzy.tokenSetRatio(query, candidate)
        val jw = Fuzzy.jaroWinkler(query, candidate)
        return maxOf(tsr, jw)
    }

    fun match(spoken: String?): Match {
        if (spoken.isNullOrBlank()) return Match(Kind.NONE, null, 0.0, null, "")
        val (norm, qualifier) = normalize(spoken)
        if (norm.isBlank()) return Match(Kind.NONE, null, 0.0, qualifier, norm)
        var best: Entry? = null
        var bestScore = 0.0
        for (e in entries) {
            val sc = score(norm, e.text)
            if (sc > bestScore) { bestScore = sc; best = e }
        }
        val kind = when {
            best == null -> Kind.NONE
            bestScore >= autoThreshold -> Kind.AUTO
            bestScore >= suggestThreshold -> Kind.SUGGESTION
            else -> Kind.NONE
        }
        return Match(kind, if (kind == Kind.NONE) null else best!!.loc, bestScore, qualifier, norm)
    }
}
