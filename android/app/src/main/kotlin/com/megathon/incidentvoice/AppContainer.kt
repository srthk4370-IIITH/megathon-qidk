package com.megathon.incidentvoice

import android.content.Context
import com.megathon.incidentvoice.config.AppConfigBundle
import com.megathon.incidentvoice.config.ConfigLoader
import com.megathon.incidentvoice.data.IncidentRepository
import com.megathon.incidentvoice.data.RoomIncidentRepository
import com.megathon.incidentvoice.data.SettingsStore
import com.megathon.incidentvoice.data.db.IncidentDb
import com.megathon.incidentvoice.audio.AudioCapture
import com.megathon.incidentvoice.audio.EnergyVadAdapter
import com.megathon.incidentvoice.audio.MockAudioInput
import com.megathon.incidentvoice.audio.SileroVad
import com.megathon.incidentvoice.audio.SwitchableAudioInput
import com.megathon.incidentvoice.domain.engine.InferenceEngine
import com.megathon.incidentvoice.domain.engine.MockInferenceEngine
import com.megathon.incidentvoice.engine.JniInferenceEngine
import com.megathon.incidentvoice.engineglue.DomainToMlEngine
import com.megathon.incidentvoice.engineglue.MlEngineAdapter
import com.megathon.incidentvoice.engineglue.SwitchableEngine
import kotlinx.coroutines.flow.first
import com.megathon.incidentvoice.orchestrator.AudioInput
import com.megathon.incidentvoice.orchestrator.ConversationOrchestrator
import com.megathon.incidentvoice.orchestrator.OrchestratorSettings
import com.megathon.incidentvoice.orchestrator.TtsSpeaker
import com.megathon.incidentvoice.tts.AndroidTtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Hand-written DI (PRD §20.1: no Hilt). */
class AppContainer(val context: Context) {
    val config: AppConfigBundle by lazy { ConfigLoader.load(context) }
    val db: IncidentDb by lazy { IncidentDb.open(context) }
    val audioRoot: File by lazy { File(context.filesDir, "audio").also { it.mkdirs() } }
    val repository: IncidentRepository by lazy { RoomIncidentRepository(db, config.taxonomy, config.registry, audioRoot) }
    val settings: SettingsStore by lazy { SettingsStore(context) }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val modelsDir: File by lazy { File(context.getExternalFilesDir(null), "models") }

    private val mockEngine: InferenceEngine by lazy { MockInferenceEngine(config.taxonomy) }

    /** ML branch's in-process engine (Plan A): whisper.cpp + Genie on the HTP, via libiv_native.so. */
    private val jni: JniInferenceEngine by lazy {
        // Until the Llama bundle is on the board, ML's engine serves generate() from this fallback while ASR is
        // already real (whisper.cpp). The app's own mock is used because it classifies/answers from the actual
        // transcript instead of replaying one canned scene.
        JniInferenceEngine(context, modelsDir, whisperModel = config.app.asr.whisperCppModel, llmFallback = DomainToMlEngine(mockEngine))
    }
    private val jniEngine: InferenceEngine by lazy { MlEngineAdapter(jni, appScope, "on-device") }

    /** "NPU" when Llama runs on the Hexagon NPU, "FALLBACK" when ASR is real but the LLM is the mock, "" for Mock engine. */
    val llmBackend = MutableStateFlow("")

    /** The single engine reference everything uses; the delegate is chosen by [selectEngine]. */
    val engine: SwitchableEngine by lazy { SwitchableEngine(appScope, mockEngine) }
    val engineKind = MutableStateFlow("MOCK")

    /** Model files the JNI engine needs (same check as JniInferenceEngine.init). */
    fun whisperPresent(): Boolean = File(modelsDir, "${config.app.models.whisperDir}/${config.app.asr.whisperCppModel}").exists()
    fun llamaPresent(): Boolean = File(modelsDir, "${config.app.models.llamaDir}/genie_config.json").exists()

    /** The on-device engine can start as soon as the Whisper model is there (LLM falls back until the bundle arrives). */
    fun modelsPresent(): Boolean = whisperPresent()

    /** AUTO → on-device engine when its model files are on the board, else Mock. */
    fun selectEngine(mode: String) {
        // `adb shell touch <externalFilesDir>/force_mock` keeps AUTO on the Mock engine (so installing the app
        // never grabs the NPU while the ML owner is running jobs on the board).
        val forceMock = File(context.getExternalFilesDir(null), "force_mock").exists()
        val useJni = when (mode) { "JNI" -> true; "MOCK" -> false; else -> modelsPresent() && !forceMock }
        engine.switchTo(if (useJni) jniEngine else mockEngine)
        engineKind.value = if (useJni) "JNI" else "MOCK"
        llmBackend.value = ""
        appScope.launch {
            val ok = runCatching { engine.init() }.isSuccess
            if (useJni && ok) llmBackend.value = if (jni.llmOnNpu()) "NPU" else "FALLBACK"
            runCatching { engine.warmup() }
            if (useJni && ok && !jni.llmOnNpu()) watchForLlama()
        }
    }

    private var llamaWatcher: kotlinx.coroutines.Job? = null

    /** FALLBACK mode: poll for the Llama bundle (pushed later by adb) and re-init the on-device engine when it lands. */
    private fun watchForLlama() {
        if (llamaWatcher?.isActive == true) return
        llamaWatcher = appScope.launch {
            while (engineKind.value == "JNI" && !jni.llmOnNpu()) {
                kotlinx.coroutines.delay(15_000)
                if (!llamaPresent() || engineKind.value != "JNI" || engine.delegate !== jniEngine) continue
                kotlinx.coroutines.delay(10_000) // let an in-progress adb push finish
                android.util.Log.i("IV", "llama bundle appeared -> re-init on-device engine")
                runCatching { engine.init() }
                llmBackend.value = if (jni.llmOnNpu()) "NPU" else "FALLBACK"
                if (jni.llmOnNpu()) runCatching { engine.warmup() } else break
            }
        }
    }

    val tts: TtsSpeaker by lazy { AndroidTtsSpeaker(context) }
    val micCapture: AudioCapture by lazy {
        AudioCapture(context, audioRoot, { config.app.vad }) { SileroVad.create(context) ?: EnergyVadAdapter() }
    }
    val audio: AudioInput by lazy { SwitchableAudioInput(micCapture, MockAudioInput(), File(context.getExternalFilesDir(null), "inject")) { settingsSnapshot.value.useMic } }

    /** Latest settings snapshot for the orchestrator (kept fresh by a collector). */
    val settingsSnapshot = MutableStateFlow(com.megathon.incidentvoice.data.AppSettings())

    val orchestrator: ConversationOrchestrator by lazy {
        appScope.launch { settings.flow.collect { settingsSnapshot.value = it } }
        ConversationOrchestrator(
            appScope, config, engine, repository, tts, audio,
            settings = {
                val s = settingsSnapshot.value
                OrchestratorSettings(s.reporter, s.ertNumber, s.speakQuestions, s.vadEndSilenceMs)
            },
        )
    }

    fun startEngine() { appScope.launch { selectEngine(settings.flow.first().engineMode) } }

    /** PRD §21.6: drafts older than 7 days are purged on startup (audio included). */
    fun housekeeping() { appScope.launch { runCatching { repository.purgeOldDrafts(7L * 24 * 3600_000) } } }
}
