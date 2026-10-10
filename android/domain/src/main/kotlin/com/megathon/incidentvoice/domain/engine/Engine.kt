package com.megathon.incidentvoice.domain.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The app's own engine port (PRD §22.1 + Appendix D.5; inference-only, stateless — D-51). Kept in the pure-Kotlin
 * domain module so the orchestrator is JVM-testable. The ML branch's real engines (`:engine`, package
 * `com.megathon.incidentvoice.engine`) are plugged in through `MlEngineAdapter` in :app.
 */

enum class LlmTask { EXTRACT, PATCH, RECOMMEND, REPAIR, WARMUP }
enum class SamplerName { GREEDY, RECOMMEND }
enum class LangMode { AUTO_EN_HI, FORCE_EN, FORCE_HI }

data class GenRequest(val task: LlmTask, val prompt: String, val sampler: SamplerName, val maxTokens: Int)

@Serializable
data class EngineInfo(
    val plan: String = "A",
    val qairt: String = "",
    val soc: String = "",
    @SerialName("htp_arch") val htpArch: String = "",
    val asrModel: String = "",
    val llmModel: String = "",
    val llmVariant: String = "stock",
    val backend: String = "",
    val loadMs: Long = 0,
)

@Serializable
data class AsrChunk(@SerialName("start_ms") val startMs: Long, @SerialName("end_ms") val endMs: Long, val text: String, val lang: String = "en")

@Serializable
data class AsrTimings(
    @SerialName("mel_ms") val melMs: Long = 0, @SerialName("enc_ms") val encMs: Long = 0, @SerialName("dec_ms") val decMs: Long = 0,
    val tokens: Int = 0, @SerialName("total_ms") val totalMs: Long = 0,
)

@Serializable
data class AsrResult(val text: String, val lang: String = "en", val chunks: List<AsrChunk> = emptyList(), val timings: AsrTimings = AsrTimings())

@Serializable
data class GenStats(
    val task: String = "", @SerialName("tokens_in") val tokensIn: Int = 0, @SerialName("tokens_out") val tokensOut: Int = 0,
    @SerialName("ttft_ms") val ttftMs: Long = 0, @SerialName("prefill_tps") val prefillTps: Double = 0.0,
    @SerialName("decode_tps") val decodeTps: Double = 0.0, @SerialName("wall_ms") val wallMs: Long = 0,
    val aborted: Boolean = false, val stop: String = "EOS",
)

@Serializable
data class EngineError(
    val code: String, val message: String, val retryable: Boolean = false,
    @SerialName("native_log_tail") val nativeLogTail: String = "",
)

class EngineException(val error: EngineError) : Exception("${error.code}: ${error.message}")

sealed interface EngineStatus {
    data object Idle : EngineStatus
    data class Loading(val progress: Float, val stage: String = "") : EngineStatus
    data class Ready(val info: EngineInfo) : EngineStatus
    data class Error(val error: EngineError) : EngineStatus
}

sealed interface GenEvent {
    data class Token(val piece: String) : GenEvent
    /** [text] = full output when the engine reports it (used if no tokens were streamed). */
    data class Done(val stats: GenStats, val text: String? = null) : GenEvent
    data class Error(val error: EngineError) : GenEvent
}

interface InferenceEngine {
    val status: StateFlow<EngineStatus>
    suspend fun init(): EngineInfo
    suspend fun warmup(): GenStats
    suspend fun transcribe(pcm: ShortArray, sampleRate: Int = 16_000, mode: LangMode = LangMode.AUTO_EN_HI): AsrResult
    fun generate(req: GenRequest): Flow<GenEvent>
    suspend fun abort()
    suspend fun release()
}
