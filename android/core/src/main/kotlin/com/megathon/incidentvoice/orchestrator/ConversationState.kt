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
    val debug: DebugInfo = DebugInfo(),
) {
    val canSubmit: Boolean get() = validation?.complete == true && (state == ConvState.REVIEW || state == ConvState.MANUAL_FILL)
}

sealed interface UiEvent {
    data class Dial(val number: String) : UiEvent
    data class Toast(val text: String) : UiEvent
}
