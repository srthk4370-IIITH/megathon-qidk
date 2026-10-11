package com.megathon.incidentvoice.core

/**
 * Evidence check for the three fields a language model likes to fill from habit: whether anyone was hurt, how many,
 * and how serious it is. A value from the model is kept only when the words themselves say so (the keyword rules
 * find it too, or the emergency keywords fire for a severity). Otherwise the field is dropped and the app asks.
 */
object EvidenceGuard {
    fun filter(fields: Map<String, kotlinx.serialization.json.JsonElement>, rulesParameters: kotlinx.serialization.json.JsonObject, emergencyWords: Boolean): Map<String, kotlinx.serialization.json.JsonElement> {
        val out = LinkedHashMap(fields)
        if (rulesParameters["injuries"] == null) { out.remove("injuries"); out.remove("injured_count") }
        if (rulesParameters["severity"] == null && !emergencyWords) out.remove("severity")
        return out
    }

    /**
     * Category check: when the model could not decide (no type, or OTHER) but the keyword rules recognise a category,
     * the rules' category is used. A category the model did choose is never overruled here.
     */
    fun category(fields: Map<String, kotlinx.serialization.json.JsonElement>, keywordCategory: String?): Map<String, kotlinx.serialization.json.JsonElement> {
        val t = (fields["incident_type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (keywordCategory == null || keywordCategory == "OTHER" || (t != null && t != "OTHER")) return fields
        return LinkedHashMap(fields).also { it["incident_type"] = kotlinx.serialization.json.JsonPrimitive(keywordCategory) }
    }

    private val timeWords = Regex("\\d|\\b(morning|afternoon|evening|night|noon|midnight|today|yesterday|tonight|ago|just now|minute|minutes|hour|hours|o'clock|am|pm|subah|dopahar|shaam|raat|kal|aaj|abhi|baje|ghanta|ghante|pehle|der)\\b", RegexOption.IGNORE_CASE)
    private val lifeAtRisk = Regex("\\b(unconscious|not breathing|no pulse|behosh|saans nahi|trapped|phans\\w*|(fire|aag)\\b.{0,30}\\b(spreading|out of control|fail\\w*|badh\\w*))\\b", RegexOption.IGNORE_CASE)
    private val placeStop = setOf("the", "near", "behind", "inside", "outside", "next", "floor", "room", "area", "side", "building", "block", "site", "office", "plant", "and", "for")

    /**
     * Same idea for the place and the time: a place none of whose words were said ("building" for "there's a leak")
     * and a time when the speaker gave none ("today") are dropped, so the app asks or marks the time as assumed.
     */
    fun filterUnsaid(fields: Map<String, kotlinx.serialization.json.JsonElement>, transcript: String): Map<String, kotlinx.serialization.json.JsonElement> {
        val out = LinkedHashMap(fields); val said = transcript.lowercase()
        (out["location"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { loc ->
            val words = loc.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            val strong = words.filter { (it.length >= 3 && it !in placeStop) || it.any(Char::isDigit) }
            val heard = (if (strong.isEmpty()) words else strong).any { Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(it)).containsMatchIn(said) }
            if (!heard) out.remove("location")
        }
        if (out.containsKey("occurred_at") && !timeWords.containsMatchIn(transcript)) out.remove("occurred_at")
        // life-at-risk wording is CRITICAL whatever the model said: raise, never lower
        if (lifeAtRisk.containsMatchIn(transcript)) out["severity"] = kotlinx.serialization.json.JsonPrimitive("CRITICAL")
        return out
    }
}

/** "at 10, no, 11" → "at 11": the speaker's own correction is applied before the text goes to the model. The stored transcript is untouched. */
object SelfCorrection {
    private val re = Regex("\\b(\\d{1,2}(?::\\d{2})?)(\\s*(?:am|pm|a\\.m\\.|p\\.m\\.|baje|o'clock))?\\s*[,.]?\\s*(?:no|nahi|nahin|sorry|i mean|actually|wait)\\b[,.]?\\s*(?:no[,.]?\\s*)?(?:not \\S+[,.]?\\s*)?(?:it was |at |around |make that )?(\\d{1,2}(?::\\d{2})?)", RegexOption.IGNORE_CASE)
    fun apply(text: String): String = re.replace(text) { m -> m.groupValues[3] }
}

/**
 * Decides when what was said is not an incident at all ("hi wassup this is Ravi", a joke, a sentence that was cut
 * off), so the form is not filled with it, and picks a self-introduction out of the words. Pure Kotlin.
 */
object SmallTalk {
    private val I = RegexOption.IGNORE_CASE
    private val notNames = setOf(
        "a", "an", "the", "not", "no", "so", "very", "just", "here", "there", "hurt", "injured", "fine", "ok", "okay", "calling", "reporting", "speaking",
        "sorry", "sure", "at", "in", "on", "near", "from", "going", "about", "regarding", "urgent", "emergency", "security", "maintenance", "reception",
        "bleeding", "stuck", "trapped", "person", "someone", "somebody", "nobody", "anyone", "everyone", "calling", "speaking", "xyz", "safe", "worried", "scared", "afraid", "telling", "saying", "your", "my", "it", "that", "this", "what", "to", "really",
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
            "emergency|danger\\w*|crack\\w*|spill\\w*|sparks?|sparking|not working|stopped working|kuch hua|haadsa|durghatna|gir\\w*|toot\\w*|chot|kharab|lag gayi|dikkat|samasya|khatra)\\b", I)
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

    /** ML contract (CHANGE.md 2026-10-11 06:40): the model answers small talk with description "NO_INCIDENT". */
    const val SENTINEL = "NO_INCIDENT"
    fun isSentinel(description: String?): Boolean = description?.trim()?.trim('.', '"')?.equals(SENTINEL, ignoreCase = true) == true

    fun prompt(lang: String, name: String? = null): String {
        val en = lang == "en" || lang.isBlank()
        val hello = name?.let { if (en) "Hi $it. " else "Namaste $it. " }.orEmpty()
        return hello + if (en) "I didn't catch an incident. What happened?" else "Mujhe koi incident samajh nahi aaya. Kya hua tha?"
    }
}
