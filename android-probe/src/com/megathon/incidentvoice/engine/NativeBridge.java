package com.megathon.incidentvoice.engine;

/** Java twin of the Kotlin NativeBridge so the probe can call the real libiv_native.so (same JNI symbols). */
public final class NativeBridge {
    private NativeBridge() {}
    public static native String init(String modelsDir, String nativeLibDir, String cfgJson);
    public static native String status();
    public static native String asrTranscribe(short[] pcm16, int sampleRate, String langMode);
    public static native String llmGenerate(String prompt, String task, String sampler, int maxTokens, Object cb);
    public static native void llmAbort();
    public static native void release();
}
