package com.megathon.incidentvoice.core

/** PRD §13.8: is a free-text value supported by what the speaker said? */
object Grounding {
    private val stop = setOf(
        "the", "a", "an", "is", "was", "were", "are", "in", "on", "at", "of", "to", "and", "or", "near", "by", "with", "for", "from",
        "it", "this", "that", "has", "have", "had", "be", "been", "hai", "tha", "thi", "ki", "ka", "ke", "ko", "mein", "se", "par",
        "aur", "ne", "bhi", "toh", "to", "ek", "raha", "gaya", "hua", "hain", "kya", "ye", "yeh", "wo", "woh",
    )
    /** Small Hinglish→English lexicon (extend freely; PRD target ~120 entries). */
    private val lexicon = mapOf(
        "seedhi" to "stairs", "seedhiyan" to "stairs", "paani" to "water", "pani" to "water", "naala" to "drain", "nala" to "drain",
        "taar" to "wire", "aag" to "fire", "dhuan" to "smoke", "chot" to "injury", "khoon" to "blood", "gir" to "fall", "gira" to "fall",
        "giri" to "fall", "girna" to "fall", "pipe" to "pipe", "chhat" to "ceiling", "darar" to "crack", "sheesha" to "glass",
        "toota" to "broken", "tuta" to "broken", "mashin" to "machine", "machine" to "machine", "gaadi" to "vehicle", "gadi" to "vehicle",
        "chori" to "theft", "bijli" to "electric", "current" to "electric", "tel" to "oil", "rasta" to "road", "darwaza" to "door",
        "kamra" to "room", "mazdoor" to "worker", "driver" to "driver", "rack" to "rack", "tanki" to "tank", "valve" to "valve",
        "gas" to "gas", "dhamaka" to "explosion", "leak" to "leak", "tapak" to "drip", "tapakna" to "drip", "bhar" to "flood",
        "lift" to "lift", "atak" to "stuck", "atki" to "stuck", "phas" to "trapped", "phasa" to "trapped", "behosh" to "unconscious",
    )

    fun tokens(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            .flatMap { listOf(it, lexicon[it] ?: it) }.toSet()

    fun check(value: String, transcripts: String): Boolean {
        val vt = tokens(value).filter { it.length >= 3 && it !in stop }
        if (vt.isEmpty()) return true
        val tt = tokens(transcripts)
        if (vt.any { v -> v in tt || tt.any { t -> t.length >= 4 && v.length >= 4 && (t.startsWith(v) || v.startsWith(t)) } }) return true
        val a = vt.sorted().joinToString(" "); val b = tt.filter { it.length >= 3 && it !in stop }.sorted().joinToString(" ")
        return LocationMatcher.tokenSetRatio(a, b) >= 0.8
    }

    // ---- where in the text a value is supported ("show your evidence") ----------------------

    /** Character ranges of [text] that support a value, and how well: 1.0 = said word for word, else the share of the value's words found. */
    data class Located(val ranges: List<IntRange>, val score: Double)

    private val none = Located(emptyList(), 0.0)
    private val word = Regex("[\\p{L}\\p{N}]+")
    private val numWords = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12", "ek" to "1", "do" to "2", "teen" to "3", "char" to "4", "paanch" to "5")
    /** A number as the speech recogniser often writes it ("conference room to"); only used when the value has that number. */
    private val soundsLike = mapOf("to" to "2", "too" to "2", "for" to "4", "won" to "1", "ate" to "8")

    private fun norm(w: String): String = w.lowercase().let { numWords[it] ?: lexicon[it] ?: it }

    private fun same(want: String, heard: String): Boolean {
        val h = norm(heard)
        if (want == h) return true
        if (want.all { it.isDigit() }) return soundsLike[heard.lowercase()] == want
        if (want.any { it.isDigit() } || h.any { it.isDigit() }) return false
        val (a, b) = if (want.length <= h.length) want to h else h to want
        return b.startsWith(a) && (a.length >= 4 || (a.length == 3 && b.length == 4))
    }

    /**
     * Same notion of "supported" as [check], but returns the place. First the value word for word (every occurrence);
     * otherwise the tightest run of words that covers most of the value's words (at least half, and every number in it).
     */
    fun locate(value: String, text: String): Located {
        val v = value.trim().trim('.', ',', '!', '?', ';', ':')
        if (v.isEmpty() || text.isEmpty()) return none
        val exact = Regex("(?<![\\p{L}\\p{N}])" + v.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) } + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.range }.toList()
        if (exact.isNotEmpty()) return Located(exact, 1.0)

        val want = word.findAll(v).map { norm(it.value) }.filter { w -> w.any { it.isDigit() } || (w.length >= 2 && w !in stop) }.distinct().toList()
        if (want.isEmpty()) return none
        val numbers = want.indices.filter { want[it].all { c -> c.isDigit() } }.toSet()
        val toks = word.findAll(text).toList()
        val hit = toks.map { t -> want.indexOfFirst { same(it, t.value) } }
        // one candidate window per starting word: extend while the next matching word is close and in the same sentence
        class Win(val from: Int, val to: Int, val covered: Set<Int>)
        val wins = ArrayList<Win>()
        for (i in toks.indices) {
            if (hit[i] < 0) continue
            val covered = linkedSetOf(hit[i]); var last = i; var end = i
            for (j in i + 1 until toks.size) {
                if (hit[j] < 0) continue
                if (j - last > 3 || Regex("[.!?;]\\s").containsMatchIn(text.substring(toks[last].range.last + 1, toks[j].range.first))) break
                last = j
                if (covered.add(hit[j])) end = j
            }
            if (covered.containsAll(numbers)) wins += Win(i, end, covered)
        }
        val best = wins.maxOfOrNull { it.covered.size } ?: return none
        if (best * 2 < want.size) return none
        val picked = ArrayList<Win>()
        for (w in wins.filter { it.covered.size == best }.sortedBy { it.to - it.from }) if (picked.none { w.from <= it.to && it.from <= w.to }) picked += w
        return Located(picked.sortedBy { it.from }.map { toks[it.from].range.first..toks[it.to].range.last }, best.toDouble() / want.size * 0.99)
    }

    /** Words of [text] that the lexicon translates to one of [meanings] ("paani" for "water"). */
    fun translated(meanings: Set<String>, text: String): List<IntRange> =
        word.findAll(text).filter { m -> lexicon[m.value.lowercase()]?.let { it in meanings && it != m.value.lowercase() } == true }.map { it.range }.toList()
}
