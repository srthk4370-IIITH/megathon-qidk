package com.megathon.incidentvoice.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire DTOs produced by the native core / Plan B server (PRD Appendix D.5). */

@Serializable
data class AsrChunkDto(
    @SerialName("start_ms") val startMs: Long = 0,
    @SerialName("end_ms") val endMs: Long = 0,
    val text: String = "",
    val lang: String = "",
)

@Serializable
data class AsrTimingsDto(
    @SerialName("mel_ms") val melMs: Double = 0.0,
    @SerialName("enc_ms") val encMs: Double = 0.0,
    @SerialName("dec_ms") val decMs: Double = 0.0,
    val tokens: Int = 0,
    @SerialName("total_ms") val totalMs: Double = 0.0,
)

@Serializable
data class AsrResultDto(
    val text: String = "",
    val lang: String = "en",
    val chunks: List<AsrChunkDto> = emptyList(),
    val backend: String = "",
    val timings: AsrTimingsDto = AsrTimingsDto(),
)

@Serializable
data class GenStatsDto(
    val task: String = "",
    @SerialName("tokens_in") val tokensIn: Int = -1,
    @SerialName("tokens_out") val tokensOut: Int = 0,
    @SerialName("ttft_ms") val ttftMs: Double = 0.0,
    @SerialName("prefill_tps") val prefillTps: Double = 0.0,
    @SerialName("decode_tps") val decodeTps: Double = 0.0,
    @SerialName("wall_ms") val wallMs: Double = 0.0,
    val aborted: Boolean = false,
    val stop: String = "EOS",
)

@Serializable
data class EngineErrorDto(
    val code: String = "IV-E600",
    val message: String = "",
    val retryable: Boolean = false,
)

@Serializable
internal data class ErrorEnvelope(val error: EngineErrorDto)

internal val wireJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/** Returns the error if [raw] is `{"error":{...}}`, else null. */
internal fun parseError(raw: String): EngineErrorDto? =
    if (raw.trimStart().startsWith("{\"error\"")) runCatching { wireJson.decodeFromString<ErrorEnvelope>(raw).error }.getOrNull() else null
