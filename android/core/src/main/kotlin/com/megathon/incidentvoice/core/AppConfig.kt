package com.megathon.incidentvoice.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mirrors config/app_config.json (PRD §22.5). */
@Serializable
data class AppConfig(
    val version: Int = 1,
    val asr: Asr = Asr(),
    val vad: Vad = Vad(),
    val llm: Llm = Llm(),
    val conversation: Conversation = Conversation(),
    val thermal: Thermal = Thermal(),
    val models: Models = Models(),
) {
    @Serializable data class Asr(
        @SerialName("language_mode") val languageMode: String = "auto_en_hi",
        @SerialName("max_chunk_s") val maxChunkS: Int = 28,
        @SerialName("min_speech_ratio") val minSpeechRatio: Double = 0.1,
    )
    @Serializable data class Vad(
        @SerialName("start_threshold") val startThreshold: Double = 0.6,
        @SerialName("end_threshold") val endThreshold: Double = 0.45,
        @SerialName("end_silence_ms") val endSilenceMs: Int = 1200,
        @SerialName("followup_end_silence_ms") val followupEndSilenceMs: Int = 1000,
        @SerialName("preroll_ms") val prerollMs: Int = 300,
        @SerialName("no_speech_timeout_ms") val noSpeechTimeoutMs: Int = 10000,
        @SerialName("followup_no_speech_timeout_ms") val followupNoSpeechTimeoutMs: Int = 8000,
        @SerialName("max_narration_s") val maxNarrationS: Int = 180,
    )
    @Serializable data class MaxTokens(val extract: Int = 320, val patch: Int = 120, val recommend: Int = 260, val repair: Int = 320)
    @Serializable data class Llm(
        @SerialName("prefix_cache") val prefixCache: Boolean = false,
        @SerialName("max_tokens") val maxTokens: MaxTokens = MaxTokens(),
        @SerialName("recommend_timeout_ms") val recommendTimeoutMs: Int = 12000,
    )
    @Serializable data class Conversation(
        @SerialName("max_followup_rounds") val maxFollowupRounds: Int = 3,
        @SerialName("transcript_autoproceed_ms") val transcriptAutoproceedMs: Int = 2000,
        @SerialName("tts_post_gap_ms") val ttsPostGapMs: Int = 300,
    )
    @Serializable data class Thermal(
        @SerialName("defer_background_above_c") val deferBackgroundAboveC: Int = 88,
        @SerialName("skin_warn_above_c") val skinWarnAboveC: Int = 60,
    )
    @Serializable data class Models(
        @SerialName("llama_dir") val llamaDir: String = "llama",
        @SerialName("whisper_dir") val whisperDir: String = "whisper",
        @SerialName("dsp_dir") val dspDir: String = "dsp",
    )

    companion object {
        /** rev 1.1 R1-08: P0 narration cap is one Whisper window. */
        const val P0_MAX_NARRATION_S = 30
        fun parse(text: String): AppConfig = Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), text)
    }
}
