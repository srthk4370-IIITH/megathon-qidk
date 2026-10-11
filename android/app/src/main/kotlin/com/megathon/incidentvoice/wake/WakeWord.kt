package com.megathon.incidentvoice.wake

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.megathon.incidentvoice.AppContainer
import com.megathon.incidentvoice.IncidentVoiceApp
import com.megathon.incidentvoice.R
import com.megathon.incidentvoice.core.AppConfig
import com.megathon.incidentvoice.core.WakeMatcher
import com.megathon.incidentvoice.core.WakeStrategy
import com.megathon.incidentvoice.orchestrator.ConvState
import com.megathon.incidentvoice.quick.QuickLaunch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/** The Java binding of this Vosk version does not expose the vocabulary lookup; the native library does. */
private object VoskVocab {
    init { com.sun.jna.Native.register(VoskVocab::class.java, "vosk") }
    @JvmStatic external fun vosk_model_find_word(model: com.sun.jna.Pointer, word: String): Int
}

data class WakeUi(
    val loaded: Boolean = false, val listening: Boolean = false, val strategy: String = "",
    val lastHeard: String = "", val lastMatch: String = "", val detections: Int = 0, val error: String? = null,
)

/**
 * Extra 2, Level B — "Hey Qalam". Owns the Vosk model and recogniser (CPU, offline). It is the only other user of
 * the microphone besides the report flow, and it always lets go first: [release] is called before any recording
 * starts, and listening resumes only when the report flow is idle again.
 */
class WakeEngine(private val context: Context, private val cfg: AppConfig, private val onDetected: () -> Unit) {
    val ui = MutableStateFlow(WakeUi())
    private val matcher = WakeMatcher(cfg.wakeVariants, cfg.wakeMinConf)
    private var model: Model? = null
    private var strategy: WakeStrategy = WakeStrategy.Free
    private var speech: SpeechService? = null
    private var recognizer: Recognizer? = null
    @Volatile private var loading = false

    fun load() {
        if (model != null || loading) return
        loading = true
        StorageService.unpack(context, "model-en-in", "vosk-model", { m ->
            model = m; loading = false
            strategy = runCatching {
                WakeStrategy.choose(cfg.wakeVariants) { w -> (VoskVocab.vosk_model_find_word(m.pointer, w) >= 0).also { android.util.Log.i("IV", "wake word: \"$w\" in vocabulary = $it") } }
            }.onFailure { android.util.Log.w("IV", "wake word: vocabulary lookup failed (${it.message}); using free recognition") }.getOrDefault(WakeStrategy.Free)
            val desc = when (val s = strategy) { is WakeStrategy.Grammar -> (if (s.standIn) "grammar on sound-alikes " else "grammar ") + s.phrases; WakeStrategy.Free -> "free recognition + fuzzy match" }
            android.util.Log.i("IV", "wake word: model loaded, strategy = $desc")
            ui.update { it.copy(loaded = true, strategy = desc, error = null) }
        }, { e -> loading = false; android.util.Log.e("IV", "wake word: model failed: ${e.message}"); ui.update { it.copy(error = e.message ?: "model failed to load") } })
    }

    @Synchronized fun start() {
        val m = model ?: return
        if (speech != null) return
        try {
            val rec = when (val s = strategy) { is WakeStrategy.Grammar -> Recognizer(m, 16_000f, WakeStrategy.grammarJson(s)); WakeStrategy.Free -> Recognizer(m, 16_000f) }
            rec.setWords(true)
            recognizer = rec
            speech = SpeechService(rec, 16_000f).also { it.startListening(listener) }
            ui.update { it.copy(listening = true, error = null) }
        } catch (e: Throwable) {
            ui.update { it.copy(listening = false, error = e.message) }
        }
    }

    /** Stops recognition and gives the microphone back. Safe to call when not listening. */
    @Synchronized fun release() {
        speech?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        speech = null
        runCatching { recognizer?.close() }; recognizer = null
        if (ui.value.listening) ui.update { it.copy(listening = false) }
    }

    private fun heard(text: String, conf: Double?, final: Boolean) {
        if (text.isBlank() || text.split(' ').all { it == "[unk]" }) return
        ui.update { it.copy(lastHeard = text + (conf?.let { c -> "  (%.2f)".format(c) } ?: if (final) "" else " …")) }
        if (final) android.util.Log.d("IV", "wake heard: \"$text\" conf=$conf")
        // only a final result carries a confidence, and every strategy is gated on it
        val m = if (!final) null else if (strategy is WakeStrategy.Grammar) matcher.matchAlone(text, conf) else matcher.match(text, conf)
        if (m != null) {
            android.util.Log.i("IV", "wake word detected: \"$text\" ~ \"$m\" conf=$conf")
            ui.update { it.copy(lastMatch = m, detections = it.detections + 1) }
            release()                                   // mic is free before anything else happens
            runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).apply { startTone(ToneGenerator.TONE_PROP_BEEP, 150) } }
            onDetected()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) { heard(runCatching { JSONObject(hypothesis ?: "{}").optString("partial") }.getOrDefault(""), null, false) }
        override fun onResult(hypothesis: String?) { final(hypothesis) }
        override fun onFinalResult(hypothesis: String?) { final(hypothesis) }
        override fun onError(e: Exception?) { ui.update { it.copy(error = e?.message, listening = false) } }
        override fun onTimeout() {}
        private fun final(h: String?) {
            val o = runCatching { JSONObject(h ?: "{}") }.getOrNull() ?: return
            val arr = o.optJSONArray("result")
            val conf = if (arr != null && arr.length() > 0) (0 until arr.length()).map { arr.getJSONObject(it).optDouble("conf", 1.0) }.average() else null
            heard(o.optString("text"), conf, true)
        }
    }
}

/**
 * Foreground service (type microphone) that keeps the wake word alive while the app is not on screen, and decides
 * when it may listen: toggle on + permission + report flow idle + Ask tab not recording.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = (application as IncidentVoiceApp).container
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.wake_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 2, QuickLaunch.intent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_report_mic)
            .setContentTitle("Listening for \"${c.config.app.wakeDisplay}\"").setContentText("Say it, or tap here, to report an incident. Audio stays on this device.")
            .setContentIntent(open).setOngoing(true).build()
        try {
            startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) { // not allowed from the background, or no mic permission
            android.util.Log.w("IV", "wake word: cannot start foreground service: ${e.message}"); stopSelf(); return START_NOT_STICKY
        }
        if (job == null) job = scope.launch { run(c) }
        return START_STICKY
    }

    private suspend fun run(c: AppContainer) {
        val wake = c.wake
        wake.load()
        combine(c.settingsSnapshot, c.orchestrator.ui, c.askController.ui, wake.ui, c.wakeHold) { s, o, a, w, hold ->
            s.wakeEnabled && w.loaded && !hold && o.state == ConvState.IDLE && !a.listening && !a.transcribing
        }.distinctUntilChanged().collect { may ->
            if (may) { delay(400); wake.start() } else wake.release()     // small gap so the previous recorder has closed
        }
    }

    override fun onDestroy() { job?.cancel(); scope.cancel(); (application as IncidentVoiceApp).container.wake.release(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "wake"
        fun canRun(ctx: Context, c: AppContainer) = c.config.app.features.wakeWord &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Call while the app is on screen (Android only lets a microphone service start from the foreground). */
        fun sync(ctx: Context, c: AppContainer, enabled: Boolean) {
            val i = Intent(ctx, WakeWordService::class.java)
            if (enabled && canRun(ctx, c)) runCatching { ctx.startForegroundService(i) } else { ctx.stopService(i); c.wake.release() }
        }
    }
}
