package com.megathon.incidentvoice.domain.engine

import com.megathon.incidentvoice.core.Taxonomy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Dev/test engine (PRD §22.1). Speech is scripted (the demo utterances); the "LLM" is [RuleBasedLlm], a
 * deterministic keyword extractor that answers from the actual transcript. Latency mimics the real engine (ASR 700 ms, 28 tok/s) and can be set to 0 for tests.
 */
class MockInferenceEngine(
    private val tx: Taxonomy,
    private val asrDelayMs: Long = 700,
    private val tokensPerSecond: Double = 28.0,
    /** Utterances returned by successive transcribe() calls (the demo script). */
    utterances: List<String> = DEMO_UTTERANCES,
    registry: com.megathon.incidentvoice.core.SiteRegistry? = null,
) : InferenceEngine {
    private val rules = RuleBasedLlm(tx, registry)
    private val json = Json { ignoreUnknownKeys = true }
    private val queue = ArrayDeque(utterances)
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    override val status: StateFlow<EngineStatus> = _status
    @Volatile private var aborted = false

    /** Test hook: next generate() fails with this error once. */
    @Volatile var failNextGenerate: EngineError? = null
    @Volatile var emptyNextGenerate = false

    fun enqueue(vararg u: String) { queue.addAll(u) }

    override suspend fun init(): EngineInfo {
        _status.value = EngineStatus.Loading(0.3f, "mock")
        delay(if (asrDelayMs == 0L) 0 else 400)
        val info = EngineInfo(plan = "mock", backend = "MOCK", asrModel = "mock-whisper", llmModel = "mock-llama", llmVariant = "canned")
        _status.value = EngineStatus.Ready(info)
        return info
    }

    override suspend fun warmup() = GenStats(task = "WARMUP", tokensOut = 2)

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, mode: LangMode): AsrResult {
        delay(asrDelayMs)
        val text = queue.removeFirstOrNull() ?: ""
        return AsrResult(text, if (looksHinglish(text)) "hinglish" else "en", timings = AsrTimings(totalMs = asrDelayMs))
    }

    override fun generate(req: GenRequest): Flow<GenEvent> = flow {
        aborted = false
        failNextGenerate?.let { failNextGenerate = null; emit(GenEvent.Error(it)); return@flow }
        if (emptyNextGenerate) { emptyNextGenerate = false; emit(GenEvent.Done(GenStats(task = req.task.name))); return@flow }
        val out = when (req.task) {
            LlmTask.EXTRACT -> extract(between(req.prompt, "Transcript:\n<<<\n", "\n>>>"))
            LlmTask.PATCH -> patch(req.prompt)
            LlmTask.REPAIR -> between(req.prompt, "Broken output:\n<<<\n", "\n>>>")
            LlmTask.RECOMMEND -> """{"name":"propose_actions","parameters":{"actions":[]}}"""
            LlmTask.WARMUP -> "OK"
        }
        val perToken = if (tokensPerSecond <= 0) 0L else (1000 / tokensPerSecond).toLong()
        delay(if (asrDelayMs == 0L) 0 else 600) // prefill
        var i = 0
        while (i < out.length && !aborted) {
            val end = minOf(out.length, i + 4)
            emit(GenEvent.Token(out.substring(i, end)))
            i = end
            if (perToken > 0) delay(perToken)
        }
        emit(GenEvent.Done(GenStats(task = req.task.name, tokensIn = req.prompt.length / 4, tokensOut = out.length / 4, decodeTps = tokensPerSecond, aborted = aborted)))
    }

    override suspend fun abort() { aborted = true }
    override suspend fun release() { _status.value = EngineStatus.Idle }

    // ---- canned behaviour ---------------------------------------------------------------------

    private fun between(s: String, a: String, b: String): String {
        val i = s.lastIndexOf(a); if (i < 0) return s
        val j = s.indexOf(b, i + a.length)
        return s.substring(i + a.length, if (j < 0) s.length else j)
    }

    private fun looksHinglish(t: String) = Regex("\\b(hai|tha|nahi|mein|ke|ko|aur|ne|kya|raha)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)

    private fun extract(transcript: String): String = json.encodeToString(JsonObject.serializer(), rules.extract(transcript))

    private fun patch(prompt: String): String {
        val answer = between(prompt, "Answer transcript:\n<<<\n", "\n>>>").trim()
        val asked = Regex("Asked fields: (.*)").find(prompt)?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return json.encodeToString(JsonObject.serializer(), rules.patch(asked, answer))
    }

    companion object {
        /** Demo scene 1 (PRD §13.5, §38): narration, then the two follow-up answers. */
        val DEMO_UTTERANCES = listOf(
            "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.",
            "No one is hurt, it's minor.",
            "A pipe under the sink burst, it's stopped now.",
        )
        const val SLIDE = """{"name":"fill_incident_form","parameters":{"lang":"en","incident_type":"WATER","title":"Water leak near Conference Room 2","description":"A water leak occurred near Conference Room 2. Maintenance has isolated the area.","location":"near Conference Room 2","time":"around 10 AM","actions_taken":["Maintenance isolated the area"]}}"""
    }
}
