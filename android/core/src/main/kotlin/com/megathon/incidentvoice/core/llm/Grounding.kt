package com.megathon.incidentvoice.core.llm

import com.megathon.incidentvoice.core.normalize.Fuzzy
import com.megathon.incidentvoice.core.normalize.NumberWords
import com.megathon.incidentvoice.core.normalize.Stopwords

/**
 * Anti-hallucination check, PRD §13.8: a free-text value is grounded if any content token (≥3 chars) appears in
 * the transcripts (after mapping Hinglish words to English with [LEXICON]), or token-set ratio ≥ 0.8.
 */
object Grounding {
    /** ~120-entry Hinglish → English lexicon applied to transcripts (value: space-separated English tokens). */
    val LEXICON: Map<String, String> = mapOf(
        // places / building
        "seedhi" to "stairs staircase", "seedhiyan" to "stairs staircase", "sidhi" to "stairs staircase",
        "chhat" to "roof ceiling terrace", "chat" to "roof ceiling", "deewar" to "wall", "diwar" to "wall",
        "darwaza" to "door", "darwaja" to "door", "khidki" to "window", "farsh" to "floor", "zameen" to "floor ground",
        "kamra" to "room", "kamre" to "room", "rasoi" to "kitchen", "godam" to "warehouse store godown",
        "godown" to "warehouse store", "gali" to "corridor lane", "bahar" to "outside", "andar" to "inside",
        "upar" to "upstairs above", "neeche" to "downstairs below", "paas" to "near", "saamne" to "front",
        "peeche" to "behind", "naala" to "drain", "naali" to "drain", "naale" to "drain", "gate" to "gate",
        "manzil" to "floor", "tanki" to "tank", "nal" to "tap pipe", "nalka" to "tap", "pipe" to "pipe",
        "sheesha" to "glass", "sheeshe" to "glass", "kaanch" to "glass", "darar" to "crack", "rack" to "rack",
        // substances / hazards
        "paani" to "water", "pani" to "water", "aag" to "fire", "dhuan" to "smoke", "dhuaan" to "smoke",
        "dhamaka" to "explosion blast", "bijli" to "electricity power", "current" to "electric shock",
        "taar" to "wire cable", "tar" to "wire", "tel" to "oil", "kachra" to "waste garbage", "mitti" to "soil",
        "hawa" to "air", "badbu" to "smell odour", "gandh" to "smell", "tezaab" to "acid", "teizab" to "acid",
        "khoon" to "blood bleeding", "chot" to "injury hurt", "ghayal" to "injured", "zakhmi" to "injured",
        "jal" to "burn burning", "jali" to "burn burnt", "jala" to "burn burnt", "gas" to "gas",
        "machine" to "machine", "gaadi" to "vehicle car", "gadi" to "vehicle car", "truck" to "truck",
        "chori" to "theft stolen", "chor" to "thief", "ladai" to "fight assault", "maar" to "hit assault",
        "dhamki" to "threat", "takkar" to "collision hit", "thok" to "hit", "toota" to "broken", "tuta" to "broken",
        "tooti" to "broken", "tuti" to "broken", "gir" to "fell fall", "gira" to "fell", "giri" to "fell",
        "phisal" to "slip slipped", "phisla" to "slipped", "phisli" to "slipped", "atak" to "stuck jammed",
        "atka" to "stuck", "atki" to "stuck", "phas" to "trapped stuck", "phasa" to "trapped", "phase" to "trapped",
        "kharab" to "broken faulty failure", "band" to "stopped closed off", "behosh" to "unconscious",
        "saans" to "breath breathing", "chakkar" to "dizzy", "bukhar" to "fever", "haath" to "hand arm",
        "pair" to "leg foot", "sir" to "head", "sar" to "head", "aankh" to "eye", "ungli" to "finger",
        "peeth" to "back", "kamar" to "back waist", "tapak" to "leak drip dripping", "risaav" to "leak",
        "bhar" to "flooded filled", "geela" to "wet", "gila" to "wet",
        // people / roles
        "aadmi" to "man person", "admi" to "man person", "aurat" to "woman", "mazdoor" to "worker",
        "karmachari" to "employee", "sahab" to "sir manager", "chowkidar" to "guard security", "guard" to "guard",
        "driver" to "driver", "log" to "people", "logon" to "people", "bachcha" to "child",
        // actions / response
        "bujh" to "extinguished out", "bujha" to "extinguished", "bujhai" to "extinguished",
        "bulaya" to "called", "bulayi" to "called", "bulaaya" to "called", "khaali" to "evacuated empty",
        "khali" to "evacuated empty", "ghera" to "cordoned", "roka" to "stopped", "ruk" to "stopped",
        "bataya" to "informed told", "police" to "police", "ambulance" to "ambulance", "dawai" to "medicine",
        // time words
        "subah" to "morning", "shaam" to "evening", "raat" to "night", "dopahar" to "afternoon noon",
        "kal" to "yesterday", "aaj" to "today", "parson" to "day before yesterday",
    )

    private fun normTokens(s: String): List<String> =
        Fuzzy.tokens(NumberWords.replaceEnglishWords(s))

    /** Content tokens of a value (stopwords removed, ≥3 chars). */
    fun contentTokens(s: String): Set<String> =
        normTokens(s).filter { it.length >= 3 && it !in Stopwords.ALL }.toSet()

    /** Transcript tokens expanded with lexicon translations. */
    fun transcriptTokens(transcripts: List<String>): Set<String> {
        val out = HashSet<String>()
        for (t in transcripts) for (tok in normTokens(t)) {
            out += tok
            LEXICON[tok]?.split(' ')?.forEach { out += it }
        }
        return out
    }

    private fun tokenMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val (s, l) = if (a.length <= b.length) a to b else b to a
        return s.length >= 4 && l.startsWith(s)
    }

    fun isGrounded(value: String, transcripts: List<String>): Boolean {
        if (transcripts.isEmpty()) return true
        val vt = contentTokens(value)
        val tt = transcriptTokens(transcripts)
        if (vt.any { v -> tt.any { tokenMatch(v, it) } }) return true
        // IDs / phone numbers: compare with separators removed.
        val compactV = value.lowercase().filter { it.isLetterOrDigit() }
        val compactT = transcripts.joinToString(" ").lowercase().filter { it.isLetterOrDigit() }
        if (compactV.length >= 3 && compactT.contains(compactV)) return true
        val joined = transcripts.joinToString(" ")
        return Fuzzy.tokenSetRatio(value, joined) >= 0.8
    }

    /** Field kinds (§13.8 table). */
    enum class Policy { DROP_IF_UNGROUNDED, INFER_IF_UNGROUNDED, EXEMPT }

    /** Keys whose ungrounded values are dropped (never invent people or IDs). */
    val DROP_KEYS = setOf("driver_name", "vehicle_ids")
    /** Keys exempt from grounding. */
    val EXEMPT_KEYS = setOf("description", "title", "occurred_at", "time", "other_incidents", "lang")
}
