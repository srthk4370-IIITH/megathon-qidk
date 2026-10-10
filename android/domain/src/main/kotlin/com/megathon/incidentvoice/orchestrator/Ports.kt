package com.megathon.incidentvoice.orchestrator

import com.megathon.incidentvoice.core.Reporter
import kotlinx.coroutines.flow.StateFlow

/** Ports the orchestrator depends on; Android implementations live in :app (audio/, tts/). */

enum class ListenKind { NARRATION, FOLLOWUP }

sealed interface ListenResult {
    class Speech(val pcm: ShortArray, val durationMs: Long, val audioPath: String? = null) : ListenResult
    data object NoSpeech : ListenResult
    data object Cancelled : ListenResult
    data class Failure(val message: String) : ListenResult
}

interface AudioInput {
    /** Records until VAD end-of-speech, [maxMs], [stop] or [cancel]. */
    suspend fun listen(kind: ListenKind, maxMs: Long, endSilenceMs: Int, incidentId: String, onProgress: (level: Float, elapsedMs: Long) -> Unit): ListenResult
    /** Manual stop: finish and return what was captured. */
    fun stop()
    fun cancel()
}

enum class SpeakResult { DONE, ERROR, TIMEOUT, SKIPPED }

interface TtsSpeaker {
    val ready: StateFlow<Boolean>
    /** Suspends until the utterance is finished (or errored / timed out). Never throws. */
    suspend fun speak(text: String, lang: String): SpeakResult
    fun stop()
}

data class OrchestratorSettings(
    val reporter: Reporter = Reporter(),
    val ertNumber: String = "112",
    val speakQuestions: Boolean = true,
    val endSilenceMs: Int = 1200,
    val followupEndSilenceMs: Int = 1000,
)
