package com.megathon.incidentvoice.engine

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
 * Dev/test engine (PRD §22.1): canned scene for the water-leak demo plus a small keyword classifier so typed
 * reports work too. Latency mimics the real engine (ASR 700 ms, 28 tok/s) and can be set to 0 for tests.
 */
class MockInferenceEngine(
    private val tx: Taxonomy,
    private val asrDelayMs: Long = 700,
    private val tokensPerSecond: Double = 28.0,
    /** Utterances returned by successive transcribe() calls (the demo script). */
    utterances: List<String> = DEMO_UTTERANCES,
) : InferenceEngine {
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

    private fun extract(transcript: String): String {
        val low = transcript.lowercase()
        // exact demo scenes first (PRD §13.5 / §38)
        if ("water leak" in low && "conference room 2" in low) return SLIDE
        val type = classify(low)
        val lang = if (looksHinglish(transcript)) "hinglish" else "en"
        val loc = Regex("(?:near|at|in|by|beside)\\s+(?:the\\s+)?([a-z0-9 ]{3,30}?)(?:\\s+(?:around|about|at|since|and|\\.|,)|[.,]|$)").find(low)?.groupValues?.get(1)?.trim()
        val time = Regex("(around|about|at)\\s+\\d{1,2}(:\\d{2})?\\s*(am|pm)?|just now|\\d+ (minutes?|hours?) ago|half an hour ago|an hour ago|this morning|yesterday|last night").find(low)?.value
        val obj = buildJsonObject {
            put("lang", lang); put("incident_type", type)
            put("title", transcript.replaceFirstChar { it.uppercase() }.take(60).trimEnd('.'))
            put("description", transcript.trim().take(240))
            loc?.let { put("location", it) }
            time?.let { put("time", it) }
            if (Regex("\\b(not hurt|no one (is )?hurt|nobody (is )?hurt|no injur|kisi ko chot nahi)").containsMatchIn(low)) put("injuries", "NO")
            else if (Regex("\\b(hurt|injur|bleed|fell|chot)").containsMatchIn(low)) put("injuries", "YES")
        }
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("name", "fill_incident_form"); put("parameters", obj)
        })
    }

    private fun classify(low: String): String {
        var best = "OTHER"; var bestScore = 0
        for (c in tx.categories) {
            val score = c.searchCues.count { it.lowercase().let { cue -> cue.length >= 3 && low.contains(cue) } }
            if (score > bestScore) { bestScore = score; best = c.code }
        }
        return best
    }

    private fun patch(prompt: String): String {
        val answer = between(prompt, "Answer transcript:\n<<<\n", "\n>>>").trim()
        val low = answer.lowercase()
        val asked = Regex("Asked fields: (.*)").find(prompt)?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val set = LinkedHashMap<String, String>()
        val unknown = ArrayList<String>()
        val skipped = Regex("\\b(skip|baad mein|chhodo|later)\\b").containsMatchIn(low)
        val dontKnow = Regex("(don't know|dont know|not sure|pata nahi|no idea|maloom nahi)").containsMatchIn(low)
        var textUsed = false
        for (key in asked) {
            val def = tx.field(if (key == "time") "occurred_at" else key) ?: continue
            if (dontKnow) { unknown += key; continue }
            if (skipped) continue
            when {
                key == "severity" -> sevOf(low)?.let { set[key] = it }
                def.type == "enum" -> enumOf(def.enum ?: emptyList(), low)?.let { set[key] = it }
                def.type == "string" || def.key == "occurred_at" -> if (!textUsed) { set[key] = stripFiller(answer); textUsed = true }
            }
        }
        val obj = buildJsonObject {
            put("lang", if (looksHinglish(answer)) "hinglish" else "en")
            put("set", buildJsonObject { set.forEach { (k, v) -> put(k, v) } })
            putJsonArray("unknown") { unknown.forEach { add(JsonPrimitive(it)) } }
            put("skipped", skipped)
        }
        return json.encodeToString(JsonObject.serializer(), buildJsonObject { put("name", "update_incident_fields"); put("parameters", obj) })
    }

    private fun stripFiller(a: String) = a.replace(Regex("^(it is|it's|its|the|a|an)\\s+", RegexOption.IGNORE_CASE), "").trim().trimEnd('.')

    private fun sevOf(low: String): String? = when {
        Regex("critical|danger|life|jaan").containsMatchIn(low) -> "CRITICAL"
        Regex("serious|major|bad|badi|high").containsMatchIn(low) -> "HIGH"
        Regex("medium|moderate|significant").containsMatchIn(low) -> "MEDIUM"
        Regex("minor|small|low|chhoti|not serious").containsMatchIn(low) -> "LOW"
        else -> null
    }

    private fun enumOf(allowed: List<String>, low: String): String? {
        val yes = Regex("\\b(yes|yeah|haan|han|ji|done|called|informed)\\b").containsMatchIn(low)
        val no = Regex("\\b(no|nope|nahi|nahin|not|nobody|none|hasn't|haven't)\\b").containsMatchIn(low)
        if (allowed.containsAll(listOf("YES", "NO"))) return when { no && !yes -> "NO"; yes -> "YES"; else -> null }
        // exact words first
        allowed.firstOrNull { low.contains(it.lowercase().replace('_', ' ')) }?.let { return it }
        return when {
            Regex("stopped|ruk gaya|ruk gayi|bandh|has stopped|no longer").containsMatchIn(low) && "STOPPED" in allowed -> "STOPPED"
            Regex("still|abhi bhi|ongoing|continu").containsMatchIn(low) && "ONGOING" in allowed -> "ONGOING"
            Regex("under control|controlled|contained").containsMatchIn(low) && "CONTAINED" in allowed -> "CONTAINED"
            Regex("controlled").containsMatchIn(low) && "CONTROLLED" in allowed -> "CONTROLLED"
            Regex("out|extinguished|bujh").containsMatchIn(low) && "EXTINGUISHED" in allowed -> "EXTINGUISHED"
            Regex("burning|spreading|lagi hai").containsMatchIn(low) && "ACTIVE" in allowed -> "ACTIVE"
            Regex("stopped|off|band").containsMatchIn(low) && "STOPPED" in allowed -> "STOPPED"
            else -> null
        }
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
