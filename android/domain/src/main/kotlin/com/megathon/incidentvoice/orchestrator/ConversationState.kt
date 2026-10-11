package com.megathon.incidentvoice.orchestrator

import com.megathon.incidentvoice.core.FollowUpQuestion
import com.megathon.incidentvoice.core.IncidentForm
import com.megathon.incidentvoice.core.Recommendation
import com.megathon.incidentvoice.core.ValidationResult

/** PRD §8.2 */
enum class ConvState {
    IDLE, LISTENING, TRANSCRIBING, TRANSCRIPT_REVIEW, EXTRACTING, VALIDATING, ASKING, TYPING, PATCHING,
    MANUAL_FILL, RECOMMENDING, REVIEW, SUBMITTED, ERROR_AUDIO, ERROR_ENGINE, ERROR_PARSE,
}

enum class BubbleRole { USER, ASSISTANT, SYSTEM }
data class Bubble(val id: Long, val role: BubbleRole, val text: String, val speaking: Boolean = false)

enum class EmergencyLevel { AMBER, RED }
data class EmergencyBanner(val level: EmergencyLevel, val steps: List<String>, val ertNumber: String, val categoryHint: String? = null)

data class DebugInfo(
    val asrMs: Long = 0, val llmMs: Long = 0, val tokensOut: Int = 0, val decodeTps: Double = 0.0, val backend: String = "",
    val lastToolJson: String = "", val lastTask: String = "",
)

data class ConversationUiState(
    val state: ConvState = ConvState.IDLE,
    val incidentId: String? = null,
    val timeline: List<Bubble> = emptyList(),
    val form: IncidentForm = IncidentForm(),
    val validation: ValidationResult? = null,
    /** Keys currently streaming in from the LLM (shimmer in the live preview). */
    val streamingFields: Set<String> = emptySet(),
    val transcriptDraft: String = "",
    /** What the reporter said, one entry per utterance (review screen: transcript with evidence highlights). */
    val userTurns: List<String> = emptyList(),
    val countdownMs: Int = 0,
    val level: Float = 0f,
    val elapsedMs: Long = 0,
    val listeningFor: ListenKind = ListenKind.NARRATION,
    val question: FollowUpQuestion? = null,
    val round: Int = 0,
    val recommendations: List<Recommendation> = emptyList(),
    val emergency: EmergencyBanner? = null,
    val error: String? = null,
    val retryable: Boolean = false,
    val submittedRef: String? = null,
    val otherIncidents: List<String> = emptyList(),
    val notice: String? = null,
    /** True when the review screen is editing an already submitted incident (Detail → Edit). */
    val editingExisting: Boolean = false,
    /** Extra 1: non-null while the response clock is running for this incident. */
    val response: ResponseClockUi? = null,
    /** Set when the reporter introduced themselves by voice ("this is Ravi"); shown instead of the device profile name. */
    val reporterName: String? = null,
    /** Extra 3: after submit, an earlier report that looks like the same event; the reporter decides. */
    val duplicate: DuplicatePrompt? = null,
    /** Extra 3: set once this report was linked to a case. */
    val linkedTo: String? = null,
    val debug: DebugInfo = DebugInfo(),
) {
    val canSubmit: Boolean get() = validation?.complete == true && (state == ConvState.REVIEW || state == ConvState.MANUAL_FILL)
}

data class DuplicatePrompt(val incidentId: String, val parentId: String, val parentRef: String?, val question: String, val score: Double)

/** Response clock card state. The UI derives the mm:ss text from [startedAtMs]. */
data class ResponseClockUi(val startedAtMs: Long, val steps: List<com.megathon.incidentvoice.core.ResponseStep>, val reminder: Boolean = false, val ertNumber: String = "112")

sealed interface UiEvent {
    data class Dial(val number: String) : UiEvent
    data class Toast(val text: String) : UiEvent
    /** Someone said who they are; the app saves it to the device profile if that is still empty. */
    data class ReporterName(val name: String) : UiEvent
}
