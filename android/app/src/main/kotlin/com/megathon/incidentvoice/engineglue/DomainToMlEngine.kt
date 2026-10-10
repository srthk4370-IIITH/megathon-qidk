package com.megathon.incidentvoice.engineglue

import com.megathon.incidentvoice.domain.engine.GenEvent
import com.megathon.incidentvoice.domain.engine.GenRequest
import com.megathon.incidentvoice.domain.engine.InferenceEngine
import com.megathon.incidentvoice.domain.engine.LlmTask
import com.megathon.incidentvoice.domain.engine.SamplerName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import com.megathon.incidentvoice.engine.AsrResultDto
import com.megathon.incidentvoice.engine.EngineErrorDto
import com.megathon.incidentvoice.engine.EngineStatus as MlStatus
import com.megathon.incidentvoice.engine.GenEvent as MlGenEvent
import com.megathon.incidentvoice.engine.GenRequest as MlGenRequest
import com.megathon.incidentvoice.engine.GenStatsDto
import com.megathon.incidentvoice.engine.InferenceEngine as MlEngine
import com.megathon.incidentvoice.engine.LangMode as MlLangMode
import com.megathon.incidentvoice.engine.LlmTask as MlTask
import com.megathon.incidentvoice.engine.SamplerName as MlSampler

/**
 * Exposes one of the app's engines through the ML branch's interface, so it can be passed as
 * `JniInferenceEngine(llmFallback = …)`: real Whisper ASR on the board, LLM answers from the app's mock until the
 * Llama bundle is pushed. Only generate() matters for a fallback; transcribe() is never called on it.
 */
class DomainToMlEngine(private val inner: InferenceEngine) : MlEngine {
    private val _status = MutableStateFlow<MlStatus>(MlStatus.NotStarted)
    override val status: StateFlow<MlStatus> = _status

    override suspend fun init(): MlStatus {
        runCatching { inner.init() }
        return MlStatus.Ready("""{"plan":"FALLBACK","asr":{"loaded":false},"llm":{"loaded":true,"backend":"mock"}}""").also { _status.value = it }
    }

    override suspend fun warmup(): GenStatsDto? = GenStatsDto(task = "WARMUP")

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, mode: MlLangMode): AsrResultDto = AsrResultDto(text = "")

    override fun generate(req: MlGenRequest): Flow<MlGenEvent> {
        val task = when (req.task) { MlTask.EXTRACT -> LlmTask.EXTRACT; MlTask.PATCH -> LlmTask.PATCH; MlTask.RECOMMEND -> LlmTask.RECOMMEND; MlTask.REPAIR -> LlmTask.REPAIR; MlTask.WARMUP -> LlmTask.WARMUP }
        val sb = StringBuilder()
        return inner.generate(GenRequest(task, req.prompt, if (req.sampler == MlSampler.RECOMMEND) SamplerName.RECOMMEND else SamplerName.GREEDY, req.maxTokens)).map { ev ->
            when (ev) {
                is GenEvent.Token -> { sb.append(ev.piece); MlGenEvent.Token(ev.piece) }
                is GenEvent.Done -> MlGenEvent.Done(ev.text ?: sb.toString(), GenStatsDto(task = ev.stats.task, tokensIn = ev.stats.tokensIn, tokensOut = ev.stats.tokensOut,
                    decodeTps = ev.stats.decodeTps, wallMs = ev.stats.wallMs.toDouble(), stop = "FALLBACK"))
                is GenEvent.Error -> MlGenEvent.Error(EngineErrorDto(ev.error.code, ev.error.message, ev.error.retryable))
            }
        }
    }

    override suspend fun abort() = inner.abort()
    override suspend fun release() {}
}
