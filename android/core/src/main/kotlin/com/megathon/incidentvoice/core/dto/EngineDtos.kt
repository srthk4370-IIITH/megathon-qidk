package com.megathon.incidentvoice.core.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Engine DTOs shared by JNI (Plan A), HTTP (Plan B) and the worker protocol — PRD Appendix D.5. */

@Serializable
data class AsrModelInfo(
    val model: String,
    val loaded: Boolean,
    @SerialName("load_ms") val loadMs: Long = 0,
)

@Serializable
data class LlmModelInfo(
    val model: String,
    val variant: String = "stock",
    @SerialName("prompt_variant") val promptVariant: String = "base",
    val loaded: Boolean,
    @SerialName("load_ms") val loadMs: Long = 0,
    val context: Int = 4096,
)

@Serializable
data class EngineInfo(
    val plan: String,
    val qairt: String? = null,
    val soc: String? = null,
    @SerialName("htp_arch") val htpArch: String? = null,
    val asr: AsrModelInfo? = null,
    val llm: LlmModelInfo? = null,
)

@Serializable
data class AsrChunk(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
    val lang: String? = null,
)

@Serializable
data class AsrTimings(
    @SerialName("mel_ms") val melMs: Long = 0,
    @SerialName("enc_ms") val encMs: Long = 0,
    @SerialName("dec_ms") val decMs: Long = 0,
    val tokens: Int = 0,
    @SerialName("total_ms") val totalMs: Long = 0,
)

@Serializable
data class AsrResult(
    val text: String,
    val lang: String? = null,
    val chunks: List<AsrChunk> = emptyList(),
    val timings: AsrTimings = AsrTimings(),
)

@Serializable
data class GenStats(
    val task: String,
    @SerialName("tokens_in") val tokensIn: Int = 0,
    @SerialName("tokens_out") val tokensOut: Int = 0,
    @SerialName("ttft_ms") val ttftMs: Long = 0,
    @SerialName("prefill_tps") val prefillTps: Double = 0.0,
    @SerialName("decode_tps") val decodeTps: Double = 0.0,
    @SerialName("wall_ms") val wallMs: Long = 0,
    val aborted: Boolean = false,
    /** "EOS" | "MAX_TOKENS" | "ABORT" | "ERROR". */
    val stop: String = "EOS",
)

@Serializable
data class EngineError(
    /** IV-Exxx (PRD §31.1). */
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    @SerialName("native_log_tail") val nativeLogTail: String? = null,
)

/** The Json instance to use for engine DTOs (tolerant to new fields from newer engines). */
val EngineJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}
