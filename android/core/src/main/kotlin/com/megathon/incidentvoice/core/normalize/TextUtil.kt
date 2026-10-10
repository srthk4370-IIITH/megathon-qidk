package com.megathon.incidentvoice.core.normalize

/** Number words (English + Roman Hindi). */
object NumberWords {
    val EN: Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fifty" to 50, "a" to 1, "an" to 1, "single" to 1, "couple" to 2,
        "a couple" to 2, "few" to 3, "a few" to 3, "dozen" to 12,
    )

    /** Hinglish numerals. `do`/`das` are English words too, so callers apply these only in numeric context. */
    val HI: Map<String, Int> = mapOf(
        "ek" to 1, "do" to 2, "doh" to 2, "teen" to 3, "tin" to 3, "char" to 4, "chaar" to 4, "paanch" to 5,
        "panch" to 5, "chhe" to 6, "chhah" to 6, "chah" to 6, "che" to 6, "saat" to 7, "sat" to 7, "aath" to 8,
        "ath" to 8, "nau" to 9, "das" to 10, "dus" to 10, "gyarah" to 11, "gyaarah" to 11, "gyara" to 11,
        "barah" to 12, "baarah" to 12, "bara" to 12, "terah" to 13, "chaudah" to 14, "pandrah" to 15,
        "bees" to 20, "tees" to 30,
    )

    private val ZERO_WORDS = listOf("no one", "nobody", "none", "koi nahi", "kisi ko nahi", "zero", "nil")

    /** Parses an integer from digits or number words (EN + Hinglish). Null when nothing numeric. */
    fun parseInt(text: String): Int? {
        val s = text.lowercase().trim()
        Regex("""-?\d+""").find(s)?.let { return it.value.toIntOrNull() }
        if (ZERO_WORDS.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(s) }) return 0
        val tokens = s.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
        for ((i, t) in tokens.withIndex()) {
            if (t == "a" || t == "an") {
                // "a couple", "a few" or bare "a person"
                val next = tokens.getOrNull(i + 1)
                if (next == "couple") return 2
                if (next == "few") return 3
                continue
            }
            EN[t]?.let { return it }
            HI[t]?.let { return it }
        }
        return null
    }

    /** Replaces English number words with digits (word-boundary safe). */
    fun replaceEnglishWords(text: String): String {
        var s = text
        val words = EN.keys.filter { it.length > 2 && !it.contains(' ') && it != "few" && it != "couple" && it != "single" && it != "dozen" }
            .sortedByDescending { it.length }
        for (w in words) s = s.replace(Regex("""\b$w\b""", RegexOption.IGNORE_CASE), EN.getValue(w).toString())
        return s
    }
}

object Stopwords {
    val EN = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "by", "for", "with", "from", "is", "was", "were",
        "be", "been", "are", "it", "its", "this", "that", "there", "has", "have", "had", "near", "some", "any", "very",
        "just", "also", "into", "onto", "as", "so", "but", "not", "no", "yes", "i", "we", "he", "she", "they", "my",
        "our", "his", "her", "their", "me", "us", "them", "about", "around", "up", "down", "out", "over", "under",
        "all", "one", "did", "do", "does", "will", "would", "can", "could", "should", "now", "then", "here",
    )
    val HINGLISH = setOf(
        "hai", "hain", "tha", "thi", "the", "ki", "ka", "ke", "ko", "mein", "me", "nahi", "aur", "kya", "se", "raha",
        "rahi", "rahe", "gaya", "gayi", "gaye", "hua", "hui", "hue", "toh", "to", "bhi", "ek", "ye", "yeh", "woh",
        "wo", "haan", "na", "par", "pe", "ho", "kar", "diya", "di", "liya", "abhi", "matlab", "accha", "acha", "ji",
        "humne", "maine", "usne", "unhone", "wala", "wali", "wale",
    )
    val ALL = EN + HINGLISH
}

object Fuzzy {
    fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }

    /** Indel-based similarity ratio in [0,1] = 2*LCS/(|a|+|b|) (fuzzywuzzy `ratio`). */
    fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1)
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return 2.0 * prev[b.length] / (a.length + b.length)
    }

    /** fuzzywuzzy-style token_set_ratio in [0,1]. */
    fun tokenSetRatio(a: String, b: String): Double {
        val ta = tokens(a).toSortedSet()
        val tb = tokens(b).toSortedSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).sorted()
        val da = (ta - tb).sorted()
        val db = (tb - ta).sorted()
        val t0 = inter.joinToString(" ")
        val t1 = (inter + da).joinToString(" ").trim()
        val t2 = (inter + db).joinToString(" ").trim()
        val scores = mutableListOf(ratio(t1, t2))
        if (t0.isNotEmpty()) {
            scores += ratio(t0, t1)
            scores += ratio(t0, t2)
        }
        return scores.max()
    }

    fun jaro(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val window = maxOf(0, maxOf(a.length, b.length) / 2 - 1)
        val ma = BooleanArray(a.length)
        val mb = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val lo = maxOf(0, i - window)
            val hi = minOf(b.length - 1, i + window)
            for (j in lo..hi) {
                if (!mb[j] && a[i] == b[j]) {
                    ma[i] = true; mb[j] = true; matches++; break
                }
            }
        }
        if (matches == 0) return 0.0
        var t = 0
        var k = 0
        for (i in a.indices) {
            if (!ma[i]) continue
            while (!mb[k]) k++
            if (a[i] != b[k]) t++
            k++
        }
        val m = matches.toDouble()
        return (m / a.length + m / b.length + (m - t / 2.0) / m) / 3.0
    }

    fun jaroWinkler(a: String, b: String, p: Double = 0.1): Double {
        val j = jaro(a, b)
        var l = 0
        while (l < minOf(4, a.length, b.length) && a[l] == b[l]) l++
        return j + l * p * (1 - j)
    }
}
