package com.megathon.incidentvoice.engine

/**
 * JNI surface of `libiv_native.so` (native/jni/iv_jni.cpp). All methods return JSON strings; errors are
 * `{"error":{"code","message","retryable"}}`. Call only from a single thread ([JniInferenceEngine] uses a
 * single-thread dispatcher) — the native side also serialises NPU work with a mutex.
 *
 * Deviation from PRD §18.6 (documented in CHANGE.md): llmGenerate takes an explicit `task` and returns
 * GenStats JSON with an extra "text" field.
 */
object NativeBridge {
    @Volatile private var loaded = false

    fun load() {
        if (loaded) return
        // Order matters: Genie + QNN stubs must resolve before our library.
        listOf("c++_shared", "QnnSystem", "QnnHtp", "QnnHtpV79Stub", "Genie").forEach {
            runCatching { System.loadLibrary(it) }
        }
        System.loadLibrary("iv_native")
        loaded = true
    }

    /** modelsDir contains llama/, whisper/, dsp/. cfgJson keys: asr_threads, llm_max_tokens, whisper_model, load_asr, load_llm. */
    external fun init(modelsDir: String, nativeLibDir: String, cfgJson: String): String
    external fun status(): String
    external fun asrTranscribe(pcm16: ShortArray, sampleRate: Int, langMode: String): String
    external fun llmGenerate(prompt: String, task: String, sampler: String, maxTokens: Int, cb: TokenCallback?): String
    external fun llmAbort()
    external fun release()
}

fun interface TokenCallback {
    fun onToken(piece: String)
}
