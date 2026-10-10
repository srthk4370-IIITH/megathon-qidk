package com.megathon.incidentvoice.core

/**
 * Short domain hint for the speech recogniser (Whisper `initial_prompt`). Measured on a QIDK recording (SNR ~11 dB):
 * greedy Whisper-small heard "virtual leap" for "water leak" and "it's my love" for "it's minor"; with a hint that
 * names the incident topics **and** typical answer words both come out right, at no extra decoding cost.
 * A topics-only hint fixed the narration but not the short answers, so the answer words are part of it.
 * Generated from the taxonomy so it follows the configured categories. Kept under 400 characters.
 */
object AsrPrompt {
    private const val ANSWERS = "Answers: no one is hurt, someone is injured, it is minor, serious, stopped, still leaking, under control, I don't know."

    fun build(tx: Taxonomy, maxChars: Int = 400): String {
        val topics = LinkedHashSet<String>()
        for (c in tx.categories) {
            if (c.code == "OTHER") continue
            c.label.split("/").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.take(2).forEach { topics += it }
        }
        val head = "Workplace safety incident report: "
        val sb = StringBuilder(head)
        for (t in topics) {
            val add = (if (sb.length > head.length) ", " else "") + t
            if (sb.length + add.length + 2 + ANSWERS.length > maxChars) break
            sb.append(add)
        }
        return sb.append(". ").append(ANSWERS).toString()
    }
}
