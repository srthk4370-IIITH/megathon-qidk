package com.megathon.incidentvoice.core

/**
 * Decides when what was said is not an incident at all ("hi wassup this is Ravi", a joke, a sentence that was cut
 * off), so the form is not filled with it, and picks a self-introduction out of the words. Pure Kotlin.
 */
object SmallTalk {
    private val I = RegexOption.IGNORE_CASE
    private val notNames = setOf(
        "a", "an", "the", "not", "no", "so", "very", "just", "here", "there", "hurt", "injured", "fine", "ok", "okay", "calling", "reporting", "speaking",
        "sorry", "sure", "at", "in", "on", "near", "from", "going", "about", "regarding", "urgent", "emergency", "security", "maintenance", "reception",
        "bleeding", "stuck", "trapped", "safe", "worried", "scared", "afraid", "telling", "saying", "your", "my", "it", "that", "this", "what", "to", "really",
        "main", "mai", "hum", "yahan", "wahan", "kaun", "koi", "abhi",
    )
    private val intros = listOf(
        Regex("\\b(?:this is|my name is|myself|i am|i'm|im)\\s+([\\p{L}]{2,})", I),
        Regex("\\bmera\\s+naa?m\\s+([\\p{L}]{2,})", I),
        Regex("\\b(?:main|mai|mein)\\s+([\\p{L}]{2,})\\s+bol\\s+rah[aie]\\s+h(?:oo|u)n?\\b", I),
        Regex("\\b([\\p{L}]{2,})\\s+bol\\s+rah[aie]\\s+h(?:oo|u)n?\\b", I),
        Regex("\\b([\\p{L}]{2,})\\s+(?:here|speaking)\\b", I),
    )

    /** "this is Ravi", "my name is Asha", "main Ravi bol raha hoon", "Ravi here" → the name; null when nobody introduced themselves. */
    fun reporterName(text: String): String? {
        for (re in intros) for (m in re.findAll(text)) {
            val w = m.groupValues[1]
            if (w.lowercase() !in notNames && w.none { it.isDigit() }) return w.lowercase().replaceFirstChar { it.uppercase() }
        }
        return null
    }

    // words that say "something went wrong" without naming a category
    private val generic = Regex(
        "\\b(happen\\w*|accident\\w*|incident|problem|issue|broke|broken|break\\w*|fell|fallen|falling|leak\\w*|hurt|injur\\w*|damage\\w*|stuck|smell\\w*|smoke|" +
            "emergency|danger\\w*|crack\\w*|spill\\w*|sparks?|sparking|not working|stopped working|hua|hui|ho gaya|ho gayi|gir\\w*|toot\\w*|chot|kharab|lag gayi|dikkat|samasya|khatra)\\b", I)
    private val nothing = Regex("\\b(nothing (happened|to report|much|really)|just (testing|checking|kidding)|only testing|mic (test|check)|kuch nahi|kuch nahin|aise hi|mazaak)\\b", I)

    /**
     * True when the words carry no incident: no category cue ([hasDomainCue]: taxonomy / emergency keywords) and no
     * general "something went wrong" word, or the speaker says so themselves ("nothing happened, just testing").
     */
    fun isNoIncident(text: String, hasDomainCue: (String) -> Boolean): Boolean {
        if (text.isBlank()) return true
        if (nothing.containsMatchIn(text) && !hasDomainCue(nothing.replace(text, " "))) return true
        if (hasDomainCue(text)) return false
        return !generic.containsMatchIn(text)
    }

    fun prompt(lang: String, name: String? = null): String {
        val en = lang == "en" || lang.isBlank()
        val hello = name?.let { if (en) "Hi $it. " else "Namaste $it. " }.orEmpty()
        return hello + if (en) "I didn't catch an incident. What happened?" else "Mujhe koi incident samajh nahi aaya. Kya hua tha?"
    }
}
