package com.megathon.incidentvoice.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only door from the app to on-device AI (PRD §22.1). Inference-only and stateless (D-51):
 * no incident state, no prompt building, no DB. Prompts come from :core PromptBuilder.
 *
 * Implementations: [JniInferenceEngine] (Plan A, in-process), [HttpInferenceEngine] (Plan B, shell-user
 * server on 127.0.0.1:8765), [MockInferenceEngine] (dev/tests).
 */
interface InferenceEngine {
    val status: StateFlow<EngineStatus>

    /** Loads Whisper then Llama. Safe to call again after an error (retries internally 3/7/15 s). */
    suspend fun init(): EngineStatus

    /** Short "Say OK." generation to warm caches (PRD §9.4 O1). */
    suspend fun warmup(): GenStatsDto?

    /** 16 kHz mono PCM16 → transcript. ASR always pre-empts a running background generation. */
    suspend fun transcribe(pcm: ShortArray, sampleRate: Int = 16_000, mode: LangMode = LangMode.AUTO_EN_HI): AsrResultDto

    /** Streams token pieces, then exactly one [GenEvent.Done] or [GenEvent.Error]. */
    fun generate(req: GenRequest): Flow<GenEvent>

    suspend fun abort()
    suspend fun release()
}

enum class LangMode(val wire: String) { AUTO_EN_HI("auto"), FORCE_EN("en"), FORCE_HI("hi") }
enum class LlmTask { EXTRACT, PATCH, RECOMMEND, REPAIR, WARMUP }
enum class SamplerName(val wire: String) { GREEDY("greedy"), RECOMMEND("recommend") }

data class GenRequest(
    val task: LlmTask,
    val prompt: String,
    val sampler: SamplerName = SamplerName.GREEDY,
    val maxTokens: Int = defaultMaxTokens(task),
) {
    companion object {
        /** PRD §13.1 / config/app_config.json llm.max_tokens */
        fun defaultMaxTokens(task: LlmTask) = when (task) {
            LlmTask.EXTRACT -> 320
            LlmTask.PATCH -> 120
            LlmTask.RECOMMEND -> 260
            LlmTask.REPAIR -> 320
            LlmTask.WARMUP -> 8
        }
    }
}

sealed interface GenEvent {
    data class Token(val piece: String) : GenEvent
    data class Done(val text: String, val stats: GenStatsDto) : GenEvent
    data class Error(val error: EngineErrorDto) : GenEvent
}

sealed interface EngineStatus {
    data class Loading(val stage: String, val progress: Float) : EngineStatus
    data class Ready(val info: String /* EngineInfo JSON */) : EngineStatus
    data class Error(val code: String, val message: String, val retryable: Boolean) : EngineStatus
    data object NotStarted : EngineStatus
}

/** Thrown by suspend calls; codes are PRD §31.1 (IV-E2xx ASR, IV-E3xx LLM, IV-E6xx NPU/hardware). */
class EngineException(val error: EngineErrorDto) : RuntimeException("${error.code}: ${error.message}")
