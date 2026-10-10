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
}
