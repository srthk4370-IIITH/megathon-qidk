package com.megathon.incidentvoice.engine

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Plan A: models run inside the app process via JNI (PRD §7.3).
 *
 * Model files are pushed by `scripts/push_models.ps1` to
 * `/sdcard/Android/data/<pkg>/files/models/{llama,whisper,dsp}` (= context.getExternalFilesDir("models")).
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class JniInferenceEngine(
    private val context: Context,
    private val modelsDir: File = File(context.getExternalFilesDir(null), "models"),
    private val asrThreads: Int = 6,
    private val whisperModel: String = "ggml-small-q5_1.bin",
    /** All native calls run on this single thread (PRD §7.4 npuDispatcher). */
    private val npu: CoroutineDispatcher = newSingleThreadContext("npu"),
    /**
     * Used for generate() while the Llama bundle is not on the device yet (e.g. MockInferenceEngine()).
     * ASR is still real. When llama/genie_config.json appears, call init() again → NPU LLM is used.
     */
    private val llmFallback: InferenceEngine? = null,
) : InferenceEngine {

    @Volatile private var llmLoaded = false
    @Volatile private var nativeInited = false

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.NotStarted)
    override val status: StateFlow<EngineStatus> = _status

    override suspend fun init(): EngineStatus = withContext(npu) {
        _status.value = EngineStatus.Loading("loading models", 0.1f)
        val err = runCatching { NativeBridge.load() }.exceptionOrNull()
        if (err != null) {
            return@withContext EngineStatus.Error("IV-E601", "native libs: ${err.message}", false).also { _status.value = it }
        }
        if (!File(modelsDir, "whisper/$whisperModel").exists()) {
            return@withContext EngineStatus.Error("IV-E603", "whisper model missing under $modelsDir/whisper", false).also { _status.value = it }
        }
        val hasLlm = File(modelsDir, "llama/genie_config.json").exists()
        if (!hasLlm && llmFallback != null) llmFallback.init()
        if (nativeInited) { llmLoaded = false; NativeBridge.release() } // re-init (e.g. bundle just arrived): free whisper + Genie first
        nativeInited = true
        val cfg = """{"asr_threads":$asrThreads,"whisper_model":"$whisperModel","load_llm":$hasLlm}"""
        val raw = NativeBridge.init(modelsDir.absolutePath, context.applicationInfo.nativeLibraryDir, cfg)
        val e = parseError(raw)
        llmLoaded = e == null && hasLlm
        _status.value = if (e != null) EngineStatus.Error(e.code, e.message, e.retryable) else EngineStatus.Ready(raw)
        _status.value
    }

    /** True when generate() runs on the NPU; false when it uses [llmFallback] (bundle not pushed yet). */
    fun llmOnNpu(): Boolean = llmLoaded

    override suspend fun warmup(): GenStatsDto? {
        var stats: GenStatsDto? = null
        generate(GenRequest(LlmTask.WARMUP, WARMUP_PROMPT, maxTokens = 8)).collect { if (it is GenEvent.Done) stats = it.stats }
        return stats
    }

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, mode: LangMode): AsrResultDto {
        NativeBridge.llmAbort() // ASR has priority (PRD §7.5)
        val raw = withContext(npu) { NativeBridge.asrTranscribe(pcm, sampleRate, mode.wire) }
        parseError(raw)?.let { throw EngineException(it) }
        return wireJson.decodeFromString(AsrResultDto.serializer(), raw)
    }

    override fun generate(req: GenRequest): Flow<GenEvent> =
        if (!llmLoaded) llmFallback?.generate(req)
            ?: kotlinx.coroutines.flow.flowOf(GenEvent.Error(EngineErrorDto("IV-E603", "Llama bundle not on device yet", true)))
        else npuGenerate(req)

    private fun npuGenerate(req: GenRequest): Flow<GenEvent> = callbackFlow {
        launch(npu) {
            val raw = NativeBridge.llmGenerate(req.prompt, req.task.name, req.sampler.wire, req.maxTokens) { piece ->
                trySend(GenEvent.Token(piece))
            }
            val err = parseError(raw)
            if (err != null) {
                send(GenEvent.Error(err))
            } else {
                val obj = wireJson.decodeFromString(JsonObject.serializer(), raw)
                val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
                send(GenEvent.Done(text, wireJson.decodeFromJsonElement(GenStatsDto.serializer(), obj)))
            }
            close()
        }
        awaitClose { }
    }

    override suspend fun abort() = NativeBridge.llmAbort()

    override suspend fun release() = withContext(npu) { NativeBridge.release() }

    /** Same as init(), with the PRD §31.3 back-off for transient DSP errors (1002/5005 after boot). */
    suspend fun initWithRetry(): EngineStatus {
        var s = init()
        for (wait in longArrayOf(3_000, 7_000, 15_000)) {
            if (s !is EngineStatus.Error || !s.retryable) break
            _status.value = EngineStatus.Loading("retrying after ${s.code}", 0.1f)
            delay(wait)
            s = init()
        }
        return s
    }

    companion object {
        const val WARMUP_PROMPT =
            "<|start_header_id|>user<|end_header_id|>\n\nSay OK.<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
    }
}
