package com.megathon.incidentvoice.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndpointerTest {
    private fun run(e: Endpointer, probs: List<Double>): List<Endpointer.Event> = probs.map { e.feed(it) }.filter { it != Endpointer.Event.NONE }
    private fun n(ms: Int, p: Double) = List(ms / 32) { p }

    @Test fun speechThenSilenceEnds() {
        val ev = run(Endpointer(), n(500, 0.1) + n(2000, 0.9) + n(1500, 0.1))
        assertEquals(listOf(Endpointer.Event.SPEECH_STARTED, Endpointer.Event.SPEECH_ENDED), ev)
    }
    @Test fun shortBlipIsNotSpeech() {
        assertTrue(run(Endpointer(), n(160, 0.9) + n(3000, 0.1)).isEmpty())
    }
    @Test fun hysteresis_midLevelKeepsSpeechAlive() {
        // 0.5 is above end threshold 0.45 → never counts as silence
        assertTrue(Endpointer.Event.SPEECH_ENDED !in run(Endpointer(), n(1000, 0.9) + n(3000, 0.5)))
    }
    @Test fun noSpeechTimeout() {
        assertEquals(listOf(Endpointer.Event.NO_SPEECH_TIMEOUT), run(Endpointer(noSpeechTimeoutMs = 2000), n(2500, 0.1)))
    }
    @Test fun speechStartAccountsForRun() {
        val e = Endpointer(); n(320, 0.1).forEach { e.feed(it) }; n(320, 0.9).forEach { e.feed(it) }
        assertTrue(e.inSpeech); assertTrue(e.speechStartMs in 320..352, e.speechStartMs.toString())
    }
    @Test fun customEndSilence_followup() {
        val ev = run(Endpointer(endSilenceMs = 1000), n(1000, 0.9) + n(1100, 0.1))
        assertTrue(Endpointer.Event.SPEECH_ENDED in ev)
    }
    @Test fun energyVad_detectsLoudAfterCalibration() {
        val v = EnergyVad()
        val quiet = ShortArray(512) { (sin(it * 0.1) * 30).toInt().toShort() }
        val loud = ShortArray(512) { (sin(it * 0.1) * 8000).toInt().toShort() }
        repeat(20) { v.prob(quiet) }
        assertEquals(0.0, v.prob(quiet)); assertEquals(1.0, v.prob(loud))
    }
    @Test fun highPass_removesDc() {
        val hp = HighPass(); val buf = ShortArray(16_000) { 5000 }
        hp.process(buf)
        assertTrue(kotlin.math.abs(buf.last().toInt()) < 50)
        val tone = ShortArray(16_000) { (sin(2 * PI * 1000 * it / 16000) * 10000).toInt().toShort() }
        hp.process(tone); assertTrue(tone.takeLast(100).maxOf { kotlin.math.abs(it.toInt()) } > 9000)
    }
    @Test fun wavHeader() {
        val w = Wav.encode(ShortArray(100)); assertEquals(244, w.size); assertEquals("RIFF", String(w.copyOfRange(0, 4))); assertEquals("WAVE", String(w.copyOfRange(8, 12)))
    }
}
