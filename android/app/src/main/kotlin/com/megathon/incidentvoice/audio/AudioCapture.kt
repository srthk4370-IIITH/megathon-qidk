package com.megathon.incidentvoice.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import androidx.core.content.ContextCompat
import com.megathon.incidentvoice.core.AppConfig
import com.megathon.incidentvoice.core.Endpointer
import com.megathon.incidentvoice.core.HighPass
import com.megathon.incidentvoice.core.Wav
import com.megathon.incidentvoice.orchestrator.AudioInput
import com.megathon.incidentvoice.orchestrator.ListenKind
import com.megathon.incidentvoice.orchestrator.ListenResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * PRD §16: VOICE_RECOGNITION @16 kHz mono → NoiseSuppressor/AEC → 80 Hz high-pass → Silero VAD (32 ms windows)
 * → hysteresis endpointer. Whole session PCM is kept (≤ [maxMs]) and written as a WAV (D-17).
 */
class AudioCapture(
    private val context: Context,
    private val audioRoot: File,
    private val vadConfig: () -> AppConfig.Vad,
    private val vadFactory: () -> Vad,
) : AudioInput {
    @Volatile private var stopReq = false
    @Volatile private var cancelReq = false

    override fun stop() { stopReq = true }
    override fun cancel() { cancelReq = true }

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun listen(kind: ListenKind, maxMs: Long, endSilenceMs: Int, incidentId: String, onProgress: (Float, Long) -> Unit): ListenResult {
        if (!hasPermission()) return ListenResult.Failure("Microphone permission is needed. Allow it in Settings, or use Type instead.")
        stopReq = false; cancelReq = false
        val done = CompletableDeferred<ListenResult>()
        // dedicated URGENT_AUDIO thread (PRD §7.4)
        thread(name = "audioThread", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            done.complete(try { record(kind, maxMs, endSilenceMs, incidentId, onProgress) } catch (e: Throwable) { ListenResult.Failure("Audio error: ${e.message}") })
        }
        return withContext(Dispatchers.Default) { done.await() }.also { if (it is ListenResult.Cancelled) Unit }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun record(kind: ListenKind, maxMs: Long, endSilenceMs: Int, incidentId: String, onProgress: (Float, Long) -> Unit): ListenResult {
        val rate = 16_000
        val vad = vadFactory().also { it.reset() }
        val win = vad.windowSamples
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 12_800) * 2)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return ListenResult.Failure("Could not open the microphone.") }
        val sid = rec.audioSessionId
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sid) else null
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sid) else null
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sid)?.enabled = false // AGC off (D-16)
        val cfg = vadConfig()
        val ep = Endpointer(
            cfg.startThreshold, cfg.endThreshold, 250, endSilenceMs,
            if (kind == ListenKind.NARRATION) cfg.noSpeechTimeoutMs else cfg.followupNoSpeechTimeoutMs, win * 1000 / rate,
        )
        val hp = HighPass(rate)
        val maxSamples = (maxMs * rate / 1000).toInt()
        val pcm = ShortArray(maxSamples)
        var n = 0
        val frame = ShortArray(win)
        var result: ListenResult? = null
        try {
            rec.startRecording()
            val t0 = System.nanoTime()
            while (result == null) {
                if (cancelReq) { result = ListenResult.Cancelled; break }
                var got = 0
                while (got < win) { val r = rec.read(frame, got, win - got); if (r <= 0) { result = ListenResult.Failure("Microphone read failed ($r)"); break }; got += r }
                if (result != null) break
                hp.process(frame, win)
                if (n + win > maxSamples) { break } // hit the cap
                System.arraycopy(frame, 0, pcm, n, win); n += win
                var sum = 0.0; for (i in 0 until win) { val v = frame[i] / 32768.0; sum += v * v }
                val rms = sqrt(sum / win)
                val level = (rms * 12).coerceIn(0.0, 1.0).toFloat()
                val ms = n * 1000L / rate
                onProgress(level, ms)
                when (ep.feed(vad.prob(frame))) {
                    Endpointer.Event.SPEECH_ENDED -> break
                    Endpointer.Event.NO_SPEECH_TIMEOUT -> { result = ListenResult.NoSpeech }
                    else -> {}
                }
                if (stopReq) break
                if ((System.nanoTime() - t0) / 1_000_000 > maxMs + 500) break
            }
        } finally {
            runCatching { rec.stop() }; rec.release(); ns?.release(); aec?.release(); vad.close()
        }
        result?.let { return it }
        if (ep.speechStartMs < 0 && !ep.inSpeech) return ListenResult.NoSpeech
        // trim leading silence to the 300 ms pre-roll
        val startMs = (maxOf(0, ep.speechStartMs - cfg.prerollMs))
        val startSample = (startMs.toLong() * rate / 1000).toInt().coerceIn(0, n)
        val out = pcm.copyOfRange(startSample, n)
        // PRD §16.2: peak-normalise to 0.9 before ASR (the board mic is quiet: speech peaks around -8…-10 dBFS)
        var peak = 1
        for (v in out) { val a = if (v < 0) -v.toInt() else v.toInt(); if (a > peak) peak = a }
        val gain = (0.9 * 32767 / peak).coerceIn(1.0, 8.0)
        if (gain > 1.05) for (i in out.indices) out[i] = (out[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        if (out.size < rate * 300 / 1000) return ListenResult.NoSpeech
        val file = File(File(audioRoot, incidentId).apply { mkdirs() }, "${System.currentTimeMillis()}.wav")
        runCatching { file.writeBytes(Wav.encode(out, rate)) }
        return ListenResult.Speech(out, out.size * 1000L / rate, file.absolutePath)
    }
}

/** Chooses mic vs. mock audio at call time (Settings → "Use microphone"). */
class SwitchableAudioInput(
    private val mic: AudioCapture,
    private val mock: AudioInput,
    private val injectDir: File? = null,
    /** Runs before every recording (used to make the wake-word listener release the microphone first). */
    private val beforeListen: suspend () -> Unit = {},
    private val useMic: () -> Boolean,
) : AudioInput {
    private var active: AudioInput = mock
    override suspend fun listen(kind: ListenKind, maxMs: Long, endSilenceMs: Int, incidentId: String, onProgress: (Float, Long) -> Unit): ListenResult {
        // Test/demo hook: `adb push clip.wav <externalFilesDir>/inject/01.wav` -> the next listen() returns that clip
        // (16-bit PCM WAV) instead of the mic/mock; the file is consumed (renamed *.used). Lowest name first.
        val f = injectDir?.listFiles { x -> x.isFile && x.name.endsWith(".wav") }?.minByOrNull { it.name }
        if (f != null) {
            val pcm = withContext(Dispatchers.IO) { runCatching { readWav16k(f) }.getOrNull().also { f.renameTo(File(f.parentFile, f.name + ".used")) } }
            if (pcm != null && pcm.isNotEmpty()) {
                android.util.Log.i("IV", "inject: ${f.name} ${pcm.size} samples")
                onProgress(0.6f, pcm.size / 16L)
                return ListenResult.Speech(pcm, pcm.size / 16L)
            }
        }
        beforeListen()
        active = if (useMic()) mic else mock
        return active.listen(kind, maxMs, endSilenceMs, incidentId, onProgress)
    }

    private fun readWav16k(f: File): ShortArray {
        val b = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var pos = 12; var channels = 1; var rate = 16_000
        while (pos + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(pos + it) }, Charsets.US_ASCII); val len = b.getInt(pos + 4)
            if (id == "fmt ") { channels = b.getShort(pos + 10).toInt().coerceAtLeast(1); rate = b.getInt(pos + 12) }
            if (id == "data") {
                val n = minOf(len, b.limit() - pos - 8) / 2 / channels
                val raw = ShortArray(n) { b.getShort(pos + 8 + it * 2 * channels) }
                if (rate == 16_000 || n == 0) return raw
                return ShortArray((n.toLong() * 16_000 / rate).toInt()) { raw[(it.toLong() * rate / 16_000).toInt().coerceAtMost(n - 1)] }
            }
            pos += 8 + len + (len and 1)
        }
        return ShortArray(0)
    }
    override fun stop() = active.stop()
    override fun cancel() = active.cancel()
}
