package com.megathon.incidentvoice.core.safety

import com.megathon.incidentvoice.core.config.Taxonomy
import com.megathon.incidentvoice.core.model.Severity

enum class EmergencyLevel { AMBER, RED }

data class EmergencyAlert(
    val level: EmergencyLevel,
    /** Category for the banner's SOP steps (keyword hint, or the extracted type for RED). */
    val category: String,
    val matched: String?,
)

/**
 * PRD §10.7 (D-57).
 *  1. Keyword trigger on the raw transcript (before the LLM, < 1 ms): regexes from `taxonomy.emergency.keywords`,
 *     case-insensitive, after masking `exclude` phrases ("fire drill", "mock drill", …) → AMBER + category hint.
 *  2. Severity trigger: severity = CRITICAL → RED with the extracted category.
 */
class EmergencyDetector(private val tx: Taxonomy) {
    private val opts = setOf(RegexOption.IGNORE_CASE)
    private val keywords = tx.emergency.keywords.map { Regex(it, opts) }
    private val excludes = tx.emergency.exclude.map { Regex(it, opts) }
    private val hints = tx.emergency.categoryHint.map { (re, cat) -> Regex(re, opts) to cat }

    /** Transcript with excluded phrases blanked out. */
    fun mask(text: String): String = excludes.fold(text) { acc, re -> re.replace(acc, " ") }

    /** Keyword trigger → AMBER, or null. */
    fun detect(transcript: String): EmergencyAlert? {
        val t = mask(transcript)
        val hit = keywords.firstNotNullOfOrNull { it.find(t) } ?: return null
        return EmergencyAlert(EmergencyLevel.AMBER, categoryHint(t) ?: "OTHER", hit.value)
    }

    fun categoryHint(text: String): String? =
        hints.firstOrNull { (re, cat) -> re.containsMatchIn(text) && tx.isCategory(cat) }?.second

    /** Combined: RED when severity is CRITICAL (category = extracted type), else the keyword result. */
    fun evaluate(transcript: String, severity: Severity?, incidentType: String?): EmergencyAlert? {
        val kw = detect(transcript)
        if (severity == Severity.CRITICAL) {
            val cat = incidentType?.takeIf { tx.isCategory(it) } ?: kw?.category ?: "OTHER"
            return EmergencyAlert(EmergencyLevel.RED, cat, kw?.matched)
        }
        return kw
    }
}
