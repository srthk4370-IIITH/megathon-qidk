package com.megathon.incidentvoice.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Canned engine for UI development and tests (PRD §22.1). Mimics real latency: ASR ~700 ms, LLM ~28 tok/s.
 * Responses follow the PRD §13.5 slide example so the App branch can build the full follow-up loop.
 * Pass custom responders to script other conversations.
 */
class MockInferenceEngine(
    private val transcripts: ArrayDeque<String> = ArrayDeque(SLIDE_TRANSCRIPTS),
    private val responder: (GenRequest) -> String = ::slideResponder,
    private val asrDelayMs: Long = 700,
    private val tokensPerSecond: Int = 28,
) : InferenceEngine {
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.NotStarted)
    override val status: StateFlow<EngineStatus> = _status

    override suspend fun init(): EngineStatus {
        _status.value = EngineStatus.Loading("mock", 0.5f)
        delay(300)
        return EngineStatus.Ready("""{"plan":"MOCK","asr":{"loaded":true},"llm":{"loaded":true}}""").also { _status.value = it }
    }

    override suspend fun warmup(): GenStatsDto = GenStatsDto(task = "WARMUP", tokensOut = 2)

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, mode: LangMode): AsrResultDto {
        delay(asrDelayMs)
        val text = transcripts.removeFirstOrNull() ?: "I don't know."
        return AsrResultDto(text = text, lang = "en", backend = "mock", timings = AsrTimingsDto(totalMs = asrDelayMs.toDouble()))
    }

    override fun generate(req: GenRequest): Flow<GenEvent> = flow {
        val out = responder(req)
        val start = System.currentTimeMillis()
        delay(400) // prefill
        val pieces = out.chunked(4)
        for (p in pieces) {
            emit(GenEvent.Token(p))
            delay(1000L / tokensPerSecond)
        }
        emit(GenEvent.Done(out, GenStatsDto(task = req.task.name, tokensOut = pieces.size, ttftMs = 400.0,
            decodeTps = tokensPerSecond.toDouble(), wallMs = (System.currentTimeMillis() - start).toDouble())))
    }

    override suspend fun abort() {}
    override suspend fun release() {}

    companion object {
        val SLIDE_TRANSCRIPTS = listOf(
            "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.",
            "No one is hurt, it's minor.",
            "A pipe under the sink burst, it's stopped now.",
        )

        /** Picks a canned tool call by task and by what the prompt contains. */
        fun slideResponder(req: GenRequest): String = when (req.task) {
            LlmTask.EXTRACT ->
                """{"name":"fill_incident_form","parameters":{"lang":"en","incident_type":"WATER","title":"Water leak near Conference Room 2","description":"A water leak occurred near Conference Room 2. Maintenance has isolated the area.","location":"near Conference Room 2","time":"around 10 AM","actions_taken":["Maintenance isolated the area"]}}"""
            LlmTask.PATCH ->
                if (req.prompt.contains("injuries") && !req.prompt.contains("\"injuries\""))
                    """{"name":"update_incident_fields","parameters":{"lang":"en","set":{"injuries":"NO","severity":"LOW"},"unknown":[],"skipped":false}}"""
                else
                    """{"name":"update_incident_fields","parameters":{"lang":"en","set":{"leak_source":"burst pipe under the sink","leak_status":"STOPPED"},"unknown":[],"skipped":false}}"""
            LlmTask.RECOMMEND ->
                """{"name":"propose_actions","parameters":{"actions":[{"sop":"WATER-03","text":"Keep the wet area near Conference Room 2 cordoned with wet-floor signs."},{"sop":"WATER-06","text":"Ask maintenance to replace the burst pipe under the sink."}]}}"""
            LlmTask.REPAIR, LlmTask.WARMUP -> "OK"
        }
    }
}
