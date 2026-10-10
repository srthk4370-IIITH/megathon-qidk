package com.megathon.incidentvoice.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SiteLocation(
    val id: String,
    val name: String,
    val building: String = "",
    val floor: String = "",
    val aliases: List<String> = emptyList(),
) {
    val display: String get() = listOf(name, building, if (floor.isNotBlank()) "Floor $floor" else "").filter { it.isNotBlank() }.joinToString(" · ")
}

@Serializable
data class SiteRegistry(val version: Int = 1, val site: String = "", val locations: List<SiteLocation> = emptyList()) {
    companion object {
        fun parse(text: String): SiteRegistry =
            Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), text)
    }
}

enum class MatchKind { AUTO, SUGGEST, NONE }

data class LocationMatch(
    val kind: MatchKind,
    val location: SiteLocation? = null,
    val score: Double = 0.0,
    val qualifier: String? = null,
)

/** PRD §15.2: fuzzy match of the spoken location to the site registry. */
class LocationMatcher(private val registry: SiteRegistry) {
    private val qualifiers = listOf(
        "in front of", "ke paas", "ke bahar", "ke andar", "next to", "near", "beside", "behind", "outside", "inside",
        "at", "the", "mein", "par",
    )
    private val numWords = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
        "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "ek" to "1", "do" to "2", "teen" to "3", "char" to "4")
    private val abbrev = mapOf("conf" to "conference", "rm" to "room", "blk" to "block", "flr" to "floor", "bldg" to "building")

    fun match(spoken: String?): LocationMatch {
        if (spoken.isNullOrBlank()) return LocationMatch(MatchKind.NONE)
        val low = spoken.lowercase()
        val qualifier = qualifiers.firstOrNull { Regex("(^|\\s)${Regex.escape(it)}(\\s|$)").containsMatchIn(low) }
            ?.takeIf { it != "the" && it != "at" }
        // "B4", "bay for", "conference room to" → registry spelling (as heard by the speech recogniser)
        val norm = normalise(com.megathon.incidentvoice.ask.SpokenPlaces.normalise(spoken, registry).trim())
        if (norm.isEmpty()) return LocationMatch(MatchKind.NONE)
        var best: SiteLocation? = null
        var bestScore = 0.0
        for (loc in registry.locations) {
            for (cand in listOf(loc.name) + loc.aliases) {
                val c = normalise(cand)
                if (c.isEmpty()) continue
                val jw = jaroWinkler(norm, c).let { if (it >= 0.88) it else 0.0 } // JW alone is too lenient on long strings
                var sc = maxOf(tokenSetRatio(norm, c), jw)
                val nn = digits(norm); val cn = digits(c)
                if (nn.isNotEmpty() && cn.isNotEmpty() && nn != cn) sc = minOf(sc, 0.69)
                if (sc > bestScore) { bestScore = sc; best = loc }
            }
        }
        return when {
            best != null && bestScore >= 0.85 -> LocationMatch(MatchKind.AUTO, best, bestScore, qualifier)
            best != null && bestScore >= 0.70 -> LocationMatch(MatchKind.SUGGEST, best, bestScore, qualifier)
            else -> LocationMatch(MatchKind.NONE, null, bestScore, qualifier)
        }
    }

    private fun digits(s: String) = Regex("\\d+").findAll(s).map { it.value }.toSet()

    internal fun normalise(s: String): String {
        var t = " " + s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ") + " "
        for (q in qualifiers) t = t.replace(Regex("\\s${Regex.escape(q)}(?=\\s)"), " ")
        return t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .map { abbrev[it] ?: numWords[it] ?: it }
            .joinToString(" ")
    }

    companion object {
        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]; dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }

        fun ratio(a: String, b: String): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            val m = maxOf(a.length, b.length)
            return 1.0 - levenshtein(a, b).toDouble() / m
        }

        fun tokenSetRatio(a: String, b: String): Double {
            val ta = a.split(' ').filter { it.isNotEmpty() }.toSet()
            val tb = b.split(' ').filter { it.isNotEmpty() }.toSet()
            val inter = (ta intersect tb).sorted().joinToString(" ")
            val d1 = (ta - tb).sorted().joinToString(" ")
            val d2 = (tb - ta).sorted().joinToString(" ")
            val t1 = (inter + " " + d1).trim()
            val t2 = (inter + " " + d2).trim()
            return maxOf(if (inter.isEmpty()) 0.0 else ratio(inter, t1), if (inter.isEmpty()) 0.0 else ratio(inter, t2), ratio(t1, t2))
        }

        fun jaroWinkler(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val range = maxOf(a.length, b.length) / 2 - 1
            val am = BooleanArray(a.length); val bm = BooleanArray(b.length)
            var matches = 0
            for (i in a.indices) {
                val lo = maxOf(0, i - range); val hi = minOf(b.length - 1, i + range)
                for (j in lo..hi) if (!bm[j] && a[i] == b[j]) { am[i] = true; bm[j] = true; matches++; break }
            }
            if (matches == 0) return 0.0
            var k = 0; var trans = 0
            for (i in a.indices) if (am[i]) { while (!bm[k]) k++; if (a[i] != b[k]) trans++; k++ }
            val m = matches.toDouble()
            val jaro = (m / a.length + m / b.length + (m - trans / 2.0) / m) / 3.0
            var prefix = 0
            while (prefix < minOf(4, a.length, b.length) && a[prefix] == b[prefix]) prefix++
            return jaro + prefix * 0.1 * (1 - jaro)
        }
    }
}
