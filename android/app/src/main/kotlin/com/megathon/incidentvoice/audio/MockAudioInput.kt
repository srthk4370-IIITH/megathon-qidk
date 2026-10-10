package com.megathon.incidentvoice.audio

import com.megathon.incidentvoice.orchestrator.AudioInput
import com.megathon.incidentvoice.orchestrator.ListenKind
import com.megathon.incidentvoice.orchestrator.ListenResult
import kotlinx.coroutines.delay
import kotlin.math.sin

/** Dev audio source (no microphone): animates the level meter ~1.5 s, then returns 1 s of silence. */
class MockAudioInput(private val durationMs: Long = 1500) : AudioInput {
    @Volatile private var stopped = false
    @Volatile private var cancelled = false

    override suspend fun listen(kind: ListenKind, maxMs: Long, endSilenceMs: Int, incidentId: String, onProgress: (Float, Long) -> Unit): ListenResult {
        stopped = false; cancelled = false
        var t = 0L
        while (t < durationMs && !stopped && !cancelled) {
            onProgress((0.5f + 0.4f * sin(t / 90.0).toFloat()).coerceIn(0f, 1f), t)
            delay(50); t += 50
        }
        if (cancelled) return ListenResult.Cancelled
        return ListenResult.Speech(ShortArray(16_000), t)
    }
    override fun stop() { stopped = true }
    override fun cancel() { cancelled = true }
}
