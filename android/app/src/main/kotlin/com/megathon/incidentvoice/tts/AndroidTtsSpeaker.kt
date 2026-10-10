package com.megathon.incidentvoice.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.megathon.incidentvoice.orchestrator.SpeakResult
import com.megathon.incidentvoice.orchestrator.TtsSpeaker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** PRD §17: TextToSpeech pinned to the eSpeak NG engine on the QIDK (falls back to the default engine). */
class AndroidTtsSpeaker(context: Context, private val enginePackage: String = "com.reecedunn.espeak") : TtsSpeaker {
    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready
    private val pending = ConcurrentHashMap<String, CompletableDeferred<SpeakResult>>()
    private var tts: TextToSpeech? = null

    init {
        val installed = runCatching { context.packageManager.getPackageInfo(enginePackage, 0) }.isSuccess
        val onInit = TextToSpeech.OnInitListener { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                val loc = listOf(Locale("en", "IN"), Locale.UK, Locale.US).firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
                loc?.let { t.language = it }
                t.setSpeechRate(0.95f); t.setPitch(1.0f)
                t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { pending.remove(id)?.complete(SpeakResult.DONE) }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { pending.remove(id)?.complete(SpeakResult.ERROR) }
                    override fun onError(id: String?, errorCode: Int) { pending.remove(id)?.complete(SpeakResult.ERROR) }
                })
                _ready.value = true
            }
        }
        tts = if (installed) TextToSpeech(context, onInit, enginePackage) else TextToSpeech(context, onInit)
    }

    override suspend fun speak(text: String, lang: String): SpeakResult {
        val t = tts
        if (!_ready.value || t == null || text.isBlank()) return SpeakResult.SKIPPED
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<SpeakResult>()
        pending[id] = d
        if (t.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) { pending.remove(id); return SpeakResult.ERROR }
        return withTimeoutOrNull(text.length * 90L + 3_000) { d.await() } ?: SpeakResult.TIMEOUT.also { pending.remove(id) }
    }

    override fun stop() { tts?.stop(); pending.values.forEach { it.complete(SpeakResult.SKIPPED) }; pending.clear() }
    fun shutdown() { tts?.shutdown() }
}
