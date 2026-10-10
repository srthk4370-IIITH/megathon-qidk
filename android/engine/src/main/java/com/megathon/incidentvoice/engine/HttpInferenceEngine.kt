package com.megathon.incidentvoice.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plan B: talks to the shell-user inference server on 127.0.0.1:8765 (PRD §19.2). Only used if the probe
 * shows the app sandbox cannot open the NPU. Needs the INTERNET permission (planB flavour only).
 * Uses HttpURLConnection to avoid extra dependencies; streaming is newline-delimited JSON (`?stream=1`).
 */
class HttpInferenceEngine(
    private val base: String = "http://127.0.0.1:8765",
    private val token: String = "",
) : InferenceEngine {
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.NotStarted)
    override val status: StateFlow<EngineStatus> = _status

    private fun open(path: String, method: String = "GET"): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_000
            readTimeout = 120_000
            if (token.isNotEmpty()) setRequestProperty("X-IV-Token", token)
        }

    override suspend fun init(): EngineStatus = withContext(Dispatchers.IO) {
        val s = runCatching {
            val c = open("/v1/health")
            val body = c.inputStream.bufferedReader().readText()
            if (c.responseCode == 200) EngineStatus.Ready(body) else EngineStatus.Error("IV-E605", body, true)
        }.getOrElse { EngineStatus.Error("IV-E605", "inference service not running: ${it.message}", true) }
        _status.value = s
        s
    }

    override suspend fun warmup(): GenStatsDto? = null

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, mode: LangMode): AsrResultDto = withContext(Dispatchers.IO) {
        val c = open("/v1/asr/transcribe?lang_mode=${mode.wire}&sr=$sampleRate", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bb.putShort(it) }
        c.outputStream.use { it.write(bb.array()) }
        val raw = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        parseError(raw)?.let { throw EngineException(it) }
        wireJson.decodeFromString(AsrResultDto.serializer(), raw)
    }

    override fun generate(req: GenRequest): Flow<GenEvent> = flow {
        val c = open("/v1/llm/generate?stream=1", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val body = wireJson.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf(
                "prompt" to kotlinx.serialization.json.JsonPrimitive(req.prompt),
                "task" to kotlinx.serialization.json.JsonPrimitive(req.task.name),
                "sampler" to kotlinx.serialization.json.JsonPrimitive(req.sampler.wire),
                "max_tokens" to kotlinx.serialization.json.JsonPrimitive(req.maxTokens),
            )),
        )
        c.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                parseError(line)?.let { emit(GenEvent.Error(it)); return@useLines }
                val o = wireJson.decodeFromString(JsonObject.serializer(), line)
                when {
                    o.containsKey("tok") -> emit(GenEvent.Token(o["tok"]!!.jsonPrimitive.content))
                    o.containsKey("done") -> emit(GenEvent.Done(o["text"]?.jsonPrimitive?.content.orEmpty(),
                        wireJson.decodeFromJsonElement(GenStatsDto.serializer(), o["stats"]!!)))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun abort() {
        withContext(Dispatchers.IO) { runCatching { open("/v1/llm/abort", "POST").responseCode } }
    }

    override suspend fun release() {}
}
