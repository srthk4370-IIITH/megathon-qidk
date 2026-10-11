package com.megathon.incidentvoice.core

/**
 * Extra 2, Level B — "Hey Qalam" wake word. Pure decision logic; the Android service only feeds it text from the
 * recogniser. Variants (how a recogniser may spell the phrase) and thresholds come from `config/app_config.json`.
 */
sealed interface WakeStrategy {
    /** The wake words exist in the model's vocabulary: restrict the recogniser to these phrases plus `[unk]`. */
    /**
     * [standIn] = the name itself is not a known word, so the grammar holds only the sound-alike variants that are
     * ("hey column", "hey calm"). Still far more reliable than free decoding of an unknown word.
     */
    data class Grammar(val phrases: List<String>, val standIn: Boolean = false) : WakeStrategy
    /** They do not: recognise freely on short windows and fuzzy-match the text against the variants. */
    data object Free : WakeStrategy

    companion object {
        /**
         * Grammar on the name if "kalam" or "qalam" is a known word (plus every other variant the model knows);
         * else grammar on the sound-alike variants the model knows; else free recognition.
         */
        fun choose(variants: List<String>, inVocabulary: (String) -> Boolean): WakeStrategy {
            val norm = variants.map { WakeMatcher.normalise(it) }.filter { it.isNotBlank() }.distinct()
            val usable = norm.filter { v -> v.split(' ').all(inVocabulary) }
            val core = usable.filter { it.contains("kalam") || it.contains("qalam") }
            return when {
                core.isNotEmpty() -> Grammar((core + usable).distinct())
                usable.isNotEmpty() -> Grammar(usable, standIn = true)
                else -> Free
            }
        }

        fun grammarJson(g: Grammar): String = (g.phrases + "[unk]").joinToString(",", "[", "]") { "\"" + it.replace("\"", "") + "\"" }
    }
}

class WakeMatcher(variants: List<String>, private val minConf: Double = 0.6, private val maxDistance: Double = 0.25) {
    private val variants = variants.map { normalise(it) }.filter { it.isNotBlank() }.distinct()

    /**
     * The matched variant, or null. A window of as many words as the variant is slid over the heard text and
     * compared by normalised edit distance, so "okay hey calum please" matches "hey calum" and "hey kalaam" matches
     * "hey kalam". [confidence] is the recogniser's average word confidence when it reports one.
     */
    fun match(heard: String, confidence: Double? = null): String? {
        if (confidence != null && confidence < minConf) return null
        val words = normalise(heard).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        var best: String? = null; var bestD = Double.MAX_VALUE
        for (v in variants) {
            val vw = v.split(' ')
            for (i in 0..words.size - vw.size) {
                val win = words.subList(i, i + vw.size)
                // every word must itself be close: "the column" is 2 edits from "hey column" overall, but "the" is not "hey",
                // and "hey can" is 2 edits from "hey calm", but "can" is not "calm"
                val wordsClose = win.indices.all { k -> LocationMatcher.levenshtein(win[k], vw[k]).toDouble() / maxOf(win[k].length, vw[k].length) <= 0.34 }
                if (!wordsClose) continue
                val w = win.joinToString(" ")
                val d = LocationMatcher.levenshtein(w, v).toDouble() / maxOf(w.length, v.length)
                if (d < bestD) { bestD = d; best = v }
            }
        }
        return if (bestD <= maxDistance) best else null
    }

    /**
     * Grammar modes only: the recogniser can output nothing but the wake phrases and `[unk]`, and it often drops the
     * soft "hey". So an utterance that is just the name ("column"), with no unknown words around it, also counts;
     * "the column is cracked" comes out as "[unk] column [unk] [unk]" and does not.
     */
    fun matchAlone(heard: String, confidence: Double? = null): String? {
        match(heard, confidence)?.let { if (!heard.contains("[unk]")) return it }
        // the name without "hey" is weaker evidence (room noise was once decoded as "column" at 0.83 on the board): ask for more
        if (confidence == null || confidence < maxOf(minConf, ALONE_MIN_CONF)) return null
        if (heard.contains("[unk]")) return null
        val words = normalise(heard).split(' ').filter { it.isNotEmpty() }
        if (words.size != 1) return null
        return variants.firstOrNull { v -> v.split(' ').let { it.size > 1 && it.last().length >= 4 && it.last() == words[0] } }
    }

    companion object {
        const val ALONE_MIN_CONF = 0.93
        fun normalise(s: String): String = s.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
