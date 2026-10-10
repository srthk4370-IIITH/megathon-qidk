package com.megathon.incidentvoice.engineglue

import com.megathon.incidentvoice.domain.engine.AsrChunk
import com.megathon.incidentvoice.domain.engine.AsrResult
import com.megathon.incidentvoice.domain.engine.AsrTimings
import com.megathon.incidentvoice.domain.engine.EngineError
import com.megathon.incidentvoice.domain.engine.EngineException
import com.megathon.incidentvoice.domain.engine.EngineInfo
import com.megathon.incidentvoice.domain.engine.EngineStatus
import com.megathon.incidentvoice.domain.engine.GenEvent
import com.megathon.incidentvoice.domain.engine.GenRequest
import com.megathon.incidentvoice.domain.engine.GenStats
import com.megathon.incidentvoice.domain.engine.InferenceEngine
import com.megathon.incidentvoice.domain.engine.LangMode
import com.megathon.incidentvoice.domain.engine.LlmTask
import com.megathon.incidentvoice.domain.engine.SamplerName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import com.megathon.incidentvoice.engine.EngineException as MlEngineException
import com.megathon.incidentvoice.engine.EngineStatus as MlStatus
import com.megathon.incidentvoice.engine.GenEvent as MlGenEvent
import com.megathon.incidentvoice.engine.GenRequest as MlGenRequest
import com.megathon.incidentvoice.engine.InferenceEngine as MlEngine
import com.megathon.incidentvoice.engine.JniInferenceEngine
import com.megathon.incidentvoice.engine.LangMode as MlLangMode
import com.megathon.incidentvoice.engine.LlmTask as MlTask
import com.megathon.incidentvoice.engine.SamplerName as MlSampler

/**
 * Adapts the ML branch's `:engine` API (`com.megathon.incidentvoice.engine.InferenceEngine`: JNI / HTTP / their mock)
 * to the app's engine port. This file is the only place that knows both type families, so a change in the ML
 * DTOs is a one-file fix.
 */
class MlEngineAdapter(private val ml: MlEngine, scope: CoroutineScope, private val label: String) : InferenceEngine {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val status: StateFlow<EngineStatus> =
        ml.status.map(::mapStatus).stateIn(scope, SharingStarted.Eagerly, mapStatus(ml.status.value))

    private fun mapStatus(s: MlStatus): EngineStatus = when (s) {
        MlStatus.NotStarted -> EngineStatus.Idle
        is MlStatus.Loading -> EngineStatus.Loading(s.progress, s.stage)
        is MlStatus.Ready -> EngineStatus.Ready(parseInfo(s.info))
        is MlStatus.Error -> EngineStatus.Error(EngineError(s.code, s.message, s.retryable))
    }

    /** Native info JSON: {"plan","qairt","soc","htp_arch","asr":{model,backend,loaded,load_ms},"llm":{…}}. */
    private fun parseInfo(raw: String): EngineInfo {
        val o = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return EngineInfo(backend = label)
        fun s(obj: JsonObject?, k: String) = (obj?.get(k) as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun d(obj: JsonObject?, k: String) = (obj?.get(k) as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val asr = o["asr"] as? JsonObject; val llm = o["llm"] as? JsonObject
        val backend = listOf(s(llm, "backend"), s(asr, "backend")).filter { it.isNotBlank() && it != "none" }.joinToString(" + ").ifBlank { label }
        return EngineInfo(
            plan = s(o, "plan").ifBlank { "A" }, qairt = s(o, "qairt"), soc = s(o, "soc"), htpArch = s(o, "htp_arch"),
            asrModel = s(asr, "model"), llmModel = s(llm, "model"), llmVariant = s(llm, "variant").ifBlank { "stock" },
            backend = backend, loadMs = (d(asr, "load_ms") + d(llm, "load_ms")).toLong(),
        )
    }

    override suspend fun init(): EngineInfo {
        // JNI: PRD §31.3 back-off for transient DSP errors right after boot
        val st = if (ml is JniInferenceEngine) ml.initWithRetry() else ml.init()
        return when (st) {
            is MlStatus.Ready -> parseInfo(st.info)
            is MlStatus.Error -> throw EngineException(EngineError(st.code, st.message, st.retryable))
            else -> throw EngineException(EngineError("IV-E600", "engine did not become ready", true))
        }
    }

    override suspend fun warmup(): GenStats = wrap { ml.warmup() }?.let { GenStats("WARMUP", it.tokensIn, it.tokensOut, it.ttftMs.toLong(), it.prefillTps, it.decodeTps, it.wallMs.toLong(), it.aborted, it.stop) }
        ?: GenStats(task = "WARMUP")

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, mode: LangMode): AsrResult {
        val r = wrap { ml.transcribe(pcm, sampleRate, when (mode) { LangMode.AUTO_EN_HI -> MlLangMode.AUTO_EN_HI; LangMode.FORCE_EN -> MlLangMode.FORCE_EN; LangMode.FORCE_HI -> MlLangMode.FORCE_HI }) }
        return AsrResult(
            r.text, r.lang.ifBlank { "en" }, r.chunks.map { AsrChunk(it.startMs, it.endMs, it.text, it.lang) },
            AsrTimings(r.timings.melMs.toLong(), r.timings.encMs.toLong(), r.timings.decMs.toLong(), r.timings.tokens, r.timings.totalMs.toLong()),
        )
    }

    override fun generate(req: GenRequest): Flow<GenEvent> {
        val task = when (req.task) { LlmTask.EXTRACT -> MlTask.EXTRACT; LlmTask.PATCH -> MlTask.PATCH; LlmTask.RECOMMEND -> MlTask.RECOMMEND; LlmTask.REPAIR -> MlTask.REPAIR; LlmTask.WARMUP -> MlTask.WARMUP }
        val sampler = if (req.sampler == SamplerName.RECOMMEND) MlSampler.RECOMMEND else MlSampler.GREEDY
        return ml.generate(MlGenRequest(task, req.prompt, sampler, req.maxTokens)).map { ev ->
            when (ev) {
                is MlGenEvent.Token -> GenEvent.Token(ev.piece)
                is MlGenEvent.Done -> GenEvent.Done(
                    ev.stats.let { GenStats(it.task, it.tokensIn, it.tokensOut, it.ttftMs.toLong(), it.prefillTps, it.decodeTps, it.wallMs.toLong(), it.aborted, it.stop) },
                    ev.text,
                )
                // "IV-E301 empty LLM output" and DSP hiccups are engine problems → let the orchestrator retry (PRD §13.7)
                is MlGenEvent.Error -> GenEvent.Error(EngineError(ev.error.code, ev.error.message, ev.error.retryable || ev.error.code.startsWith("IV-E3") || ev.error.code.startsWith("IV-E6")))
            }
        }
    }

    override suspend fun abort() = ml.abort()
    override suspend fun release() = ml.release()

    private suspend fun <T> wrap(block: suspend () -> T): T = try { block() } catch (e: MlEngineException) {
        throw EngineException(EngineError(e.error.code, e.error.message, e.error.retryable))
    }
}
