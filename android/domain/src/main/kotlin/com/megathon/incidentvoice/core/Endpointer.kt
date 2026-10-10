package com.megathon.incidentvoice.core

/** Speech endpointing with hysteresis (PRD §16.3–16.4). Pure: fed one VAD probability per window. */
class Endpointer(
    private val startThreshold: Double = 0.6,
    private val endThreshold: Double = 0.45,
    private val minSpeechMs: Int = 250,
    private val endSilenceMs: Int = 1200,
    private val noSpeechTimeoutMs: Int = 10_000,
    private val windowMs: Int = 32,
) {
    enum class Event { NONE, SPEECH_STARTED, SPEECH_ENDED, NO_SPEECH_TIMEOUT }

    private var elapsedMs = 0
    private var speechRunMs = 0
    private var silenceRunMs = 0
    private var timedOut = false
    var inSpeech = false; private set
    /** Elapsed ms (start of the window) at which the current/last speech began; -1 if none. */
    var speechStartMs = -1; private set
    var lastSpeechMs = -1; private set

    fun feed(p: Double): Event {
        val at = elapsedMs
        elapsedMs += windowMs
        if (!inSpeech) {
            if (p >= startThreshold) {
                speechRunMs += windowMs
                if (speechRunMs >= minSpeechMs) {
                    inSpeech = true; silenceRunMs = 0
                    speechStartMs = at - (speechRunMs - windowMs); lastSpeechMs = elapsedMs
                    return Event.SPEECH_STARTED
                }
            } else speechRunMs = 0
            if (speechStartMs < 0 && !timedOut && elapsedMs >= noSpeechTimeoutMs) { timedOut = true; return Event.NO_SPEECH_TIMEOUT }
            return Event.NONE
        }
        // in speech: hysteresis — only p below endThreshold counts as silence
        if (p < endThreshold) {
            silenceRunMs += windowMs
            if (silenceRunMs >= endSilenceMs) { inSpeech = false; return Event.SPEECH_ENDED }
        } else { silenceRunMs = 0; lastSpeechMs = elapsedMs }
        return Event.NONE
    }
}

/** Simple energy VAD fallback (PRD §16.4): RMS above noise floor + 9 dB; floor from the first 500 ms. */
class EnergyVad(private val sampleRate: Int = 16_000) {
    private var floorDb = Double.NaN
    private var calibMs = 0
    private var floorAcc = 0.0
    private var floorN = 0

    /** Returns a pseudo-probability 0.0 / 1.0 (hard decision). */
    fun prob(frame: ShortArray, n: Int = frame.size): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = frame[i] / 32768.0; sum += v * v }
        val db = 10 * Math.log10(sum / n + 1e-12)
        val ms = n * 1000 / sampleRate
        if (calibMs < 500) { calibMs += ms; floorAcc += db; floorN++; floorDb = floorAcc / floorN; return 0.0 }
        return if (db > floorDb + 9) 1.0 else 0.0
    }
}

/** 2nd-order Butterworth high-pass (80 Hz default) removing machine rumble / handling noise (PRD §16.2). */
class HighPass(sampleRate: Int = 16_000, cutoffHz: Double = 80.0) {
    private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    init {
        val w0 = 2 * Math.PI * cutoffHz / sampleRate
        val cosw = Math.cos(w0); val alpha = Math.sin(w0) / (2 * Math.sqrt(0.5))
        val a0 = 1 + alpha
        b0 = (1 + cosw) / 2 / a0; b1 = -(1 + cosw) / a0; b2 = (1 + cosw) / 2 / a0
        a1 = -2 * cosw / a0; a2 = (1 - alpha) / a0
    }
    fun process(buf: ShortArray, n: Int = buf.size) {
        for (i in 0 until n) {
            val x = buf[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            buf[i] = y.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }
}

/** RIFF/WAV PCM16 mono writer helper (PRD §16.2, D-17). */
object Wav {
    fun header(pcmBytes: Int, sampleRate: Int = 16_000): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        bb.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(pcmBytes)
        return bb.array()
    }
    fun encode(pcm: ShortArray, sampleRate: Int = 16_000): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bb.putShort(it) }
        return header(pcm.size * 2, sampleRate) + bb.array()
    }
}
