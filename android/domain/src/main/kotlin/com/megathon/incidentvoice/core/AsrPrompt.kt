package com.megathon.incidentvoice.core

/**
 * Short domain hint for the speech recogniser (Whisper `initial_prompt`). Measured on nine live QIDK recordings
 * (speech/noise ≈ 11 dB): greedy Whisper-small heard "virtual leap" for "water leak", "it's my love" for "it's minor",
 * "Bedford" for "Bay 4" and "caught" for "called". A hint that names incident topics, the site's places and typical
 * answer words fixes all four at no decoding cost; topics alone fix only the first.
 * Built from the taxonomy and the site registry so it follows the configuration. Kept under [maxChars].
 */
object AsrPrompt {
    private const val HEAD = "Workplace safety incident report. Topics: "
    private const val ANSWERS = "Answers: no one is hurt, someone is injured, it is minor, serious, stopped, still leaking, under control, called, I don't know."
    /** Generic EHS vocabulary that is easy to mis-hear; category words from the taxonomy are added in front. */
    private val VOCAB = listOf("gas leak", "chemical spill", "electric shock", "forklift", "truck", "conveyor", "compressor", "theft", "ceiling", "drain")

    fun build(tx: Taxonomy, registry: SiteRegistry? = null, maxChars: Int = 600): String {
        val topics = LinkedHashSet<String>()
        for (c in tx.categories) if (c.code != "OTHER") c.label.split("/").firstOrNull()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { topics += it }
        topics += VOCAB
        val places = registry?.locations.orEmpty().map { it.name }.distinct()
        val sb = StringBuilder(HEAD).append(topics.joinToString(", ")).append('.')
        if (places.isNotEmpty()) {
            val budget = maxChars - sb.length - ANSWERS.length - " Places: ".length - 2
            val chosen = ArrayList<String>(); var used = 0
            for (p in places) { if (used + p.length + 2 > budget) break; chosen += p; used += p.length + 2 }
            if (chosen.isNotEmpty()) sb.append(" Places: ").append(chosen.joinToString(", ")).append('.')
        }
        return sb.append(' ').append(ANSWERS).toString().take(maxChars)
    }
}
