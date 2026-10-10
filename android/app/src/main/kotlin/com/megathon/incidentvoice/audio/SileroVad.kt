package com.megathon.incidentvoice.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Per-window speech probability. 512 samples @ 16 kHz = 32 ms. */
interface Vad {
    val windowSamples: Int
    fun prob(frame: ShortArray): Double
    fun reset()
    fun close() {}
}

/** Silero VAD v5 on ONNX Runtime CPU, 1 intra-op thread (PRD §16.4). Input = 64-sample context + 512 samples. */
class SileroVad private constructor(private val env: OrtEnvironment, private val session: OrtSession) : Vad {
    override val windowSamples = 512
    private val context = FloatArray(64)
    private var state = FloatArray(2 * 1 * 128)
    private val srTensorData = longArrayOf(16_000)

    override fun reset() { context.fill(0f); state = FloatArray(2 * 128) }

    override fun prob(frame: ShortArray): Double {
        val x = FloatArray(64 + 512)
        System.arraycopy(context, 0, x, 0, 64)
        for (i in 0 until 512) x[64 + i] = frame[i] / 32768f
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 576))
        val st = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
        val sr = OnnxTensor.createTensor(env, LongBuffer.wrap(srTensorData), longArrayOf())
        try {
            session.run(mapOf("input" to input, "state" to st, "sr" to sr)).use { out ->
                val p = (out[0].value as Array<FloatArray>)[0][0]
                @Suppress("UNCHECKED_CAST")
                val ns = out[1].value as Array<Array<FloatArray>>
                for (a in 0 until 2) System.arraycopy(ns[a][0], 0, state, a * 128, 128)
                System.arraycopy(x, 512, context, 0, 64)
                return p.toDouble()
            }
        } finally { input.close(); st.close(); sr.close() }
    }

    override fun close() { session.close() }

    companion object {
        /** Null if the model asset or ORT cannot load (caller falls back to the energy VAD, log IV-E102). */
        fun create(context: Context): SileroVad? = try {
            val env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open("models/silero_vad.onnx").use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1); setInterOpNumThreads(1) }
            SileroVad(env, env.createSession(bytes, opts))
        } catch (e: Throwable) { android.util.Log.e("IV-E102", "Silero VAD unavailable: ${e.message}"); null }
    }
}

/** Energy-VAD adapter with the same interface (PRD §16.4 fallback). */
class EnergyVadAdapter : Vad {
    override val windowSamples = 512
    private var v = com.megathon.incidentvoice.core.EnergyVad()
    override fun prob(frame: ShortArray) = v.prob(frame)
    override fun reset() { v = com.megathon.incidentvoice.core.EnergyVad() }
}
