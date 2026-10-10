package com.megathon.incidentvoice.core.normalize

/** PRD §15.4 fallback language detection (primary source is the LLM's `lang`). */
object LangDetector {
    val FUNCTION_WORDS = setOf(
        "hai", "tha", "thi", "ki", "ka", "ke", "ko", "mein", "nahi", "aur", "kya", "se", "raha", "gaya", "hua",
    )

    fun detect(text: String): String {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return "en"
        val deva = text.count { it in 'ऀ'..'ॿ' }
        if (deva.toDouble() / letters > 0.20) return "hi"
        val tokens = Fuzzy.tokens(text)
        if (tokens.isEmpty()) return "en"
        val hits = tokens.count { it in FUNCTION_WORDS }
        return if (hits.toDouble() / tokens.size >= 0.08) "hinglish" else "en"
    }
}
