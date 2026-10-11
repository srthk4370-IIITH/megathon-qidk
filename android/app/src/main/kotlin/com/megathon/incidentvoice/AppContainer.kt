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
import kotlinx.coroutines.flow.debounce
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

    /** Extra 2: bumped by the tile / shortcut / widget / wake word; the Report tab then starts listening. */
    val quickReport = MutableStateFlow(0)
    // Extra 2 Level B: wake word. `wakeHold` keeps the listener off between a detection and the report flow taking the mic.
    val wakeHold = MutableStateFlow(false)
    val wake: com.megathon.incidentvoice.wake.WakeEngine by lazy {
        com.megathon.incidentvoice.wake.WakeEngine(context, config.app) {
            wakeHold.value = true
            requestQuickReport()
            runCatching { context.startActivity(com.megathon.incidentvoice.quick.QuickLaunch.intent(context)) }      // brings the app forward (needs "display over other apps" when in the background)
            appScope.launch {
                // hold until the report flow has left idle and come back, or 12 s if it never started (e.g. a permission dialog)
                val left = kotlinx.coroutines.withTimeoutOrNull(12_000) { orchestrator.ui.first { it.state != com.megathon.incidentvoice.orchestrator.ConvState.IDLE } } != null
                if (left) orchestrator.ui.first { it.state == com.megathon.incidentvoice.orchestrator.ConvState.IDLE }
                wakeHold.value = false
            }
        }
    }

    fun requestQuickReport() { if (config.app.features.quickLaunch || config.app.features.wakeWord) quickReport.value += 1 }

    // The app creates the model folders itself so they are owned by the app uid: folders created by `adb push`
    // are owned by `shell` (drwxrws--- shell:ext_data_rw) and the app process cannot even list them (Android 15).
    // adb then only pushes FILES into these folders (files land as shell-owned 0666/0755, which the app can read).
    val modelsDir: File by lazy {
        File(context.getExternalFilesDir(null), "models").also { m ->
            listOf("whisper", "dsp", "llama", "vad").forEach { runCatching { File(m, it).mkdirs() } }
            runCatching { File(context.getExternalFilesDir(null), "inject").mkdirs() }
            android.util.Log.i("IV", "modelsDir=$m whisper=${File(m, "whisper").list()?.toList()} canRead=${File(m, "whisper").canRead()}")
        }
    }

    private val mockEngine: InferenceEngine by lazy { MockInferenceEngine(config.taxonomy, registry = config.registry) }

    /** ML branch's in-process engine (Plan A): whisper.cpp + Genie on the HTP, via libiv_native.so. */
    private val jni: JniInferenceEngine by lazy {
        // Until the Llama bundle is on the board, ML's engine serves generate() from this fallback while ASR is
        // already real (whisper.cpp). The app's own mock is used because it classifies/answers from the actual
        // transcript instead of replaying one canned scene.
        JniInferenceEngine(context, modelsDir, whisperModel = config.app.asr.whisperCppModel, llmFallback = DomainToMlEngine(mockEngine))
    }
    private val jniEngine: InferenceEngine by lazy { MlEngineAdapter(jni, appScope, "on-device") { modelFamily.value == com.megathon.incidentvoice.core.ModelFamily.QWEN } }

    /** "NPU" when Llama runs on the Hexagon NPU, "FALLBACK" when ASR is real but the LLM is the mock, "" for Mock engine. */
    val llmBackend = MutableStateFlow("")

    /** The single engine reference everything uses; the delegate is chosen by [selectEngine]. */
    val engine: SwitchableEngine by lazy { SwitchableEngine(appScope, mockEngine) }
    val engineKind = MutableStateFlow("MOCK")

    /** Model files the JNI engine needs (same check as JniInferenceEngine.init). */
    fun whisperPresent(): Boolean = File(modelsDir, "${config.app.models.whisperDir}/${config.app.asr.whisperCppModel}").exists()
    /** Chat template of the bundle in the model folder (Llama or Qwen); re-read whenever the engine is (re)selected. */
    val modelFamily = MutableStateFlow(com.megathon.incidentvoice.core.ModelFamily.LLAMA)
    fun detectModelFamily() {
        val dir = File(modelsDir, config.app.models.llamaDir)
        fun read(n: String) = runCatching { File(dir, n).takeIf { it.exists() }?.readText() }.getOrNull()
        modelFamily.value = com.megathon.incidentvoice.core.ModelFamily.detect(read("model.json"), read("genie_config.json"))
    }
    /** What is filling forms right now, in plain words (goes into the offline QR payload). */
    fun engineLabel(): String = when {
        engineKind.value != "JNI" -> "Mock engine"
        llmBackend.value == "NPU" -> "${modelFamily.value.display} on Hexagon NPU + Whisper small (CPU)"
        else -> "Rules engine + Whisper small (CPU)"
    }
    fun llamaPresent(): Boolean = File(modelsDir, "${config.app.models.llamaDir}/genie_config.json").exists()

    /** The on-device engine can start as soon as the Whisper model is there (LLM falls back until the bundle arrives). */
    fun modelsPresent(): Boolean = whisperPresent()

    /** AUTO → on-device engine when its model files are on the board, else Mock. */
    fun selectEngine(mode: String) {
        writeAsrPrompt()
        detectModelFamily()
        // `adb shell touch <externalFilesDir>/force_mock` keeps AUTO on the Mock engine (so installing the app
        // never grabs the NPU while the ML owner is running jobs on the board).
        val forceMock = File(context.getExternalFilesDir(null), "force_mock").exists()
        val useJni = when (mode) { "JNI" -> true; "MOCK" -> false; else -> modelsPresent() && !forceMock }
        engine.switchTo(if (useJni) jniEngine else mockEngine)
        engineKind.value = if (useJni) "JNI" else "MOCK"
        llmBackend.value = ""
        appScope.launch {
            val r = runCatching { engine.init() }
            val ok = r.isSuccess
            android.util.Log.i("IV", "selectEngine family=${modelFamily.value} mode=$mode useJni=$useJni forceMock=$forceMock init=${r.getOrNull() ?: r.exceptionOrNull()} status=${engine.status.value}")
            if (useJni && ok) llmBackend.value = if (jni.llmOnNpu()) "NPU" else "FALLBACK"
            runCatching { engine.warmup() }
            if (useJni && ok && !jni.llmOnNpu()) watchForLlama()
        }
    }

    /**
     * Writes the speech-recogniser hint next to the Whisper model (`models/whisper/prompt.txt`). The native engine
     * reads it when it loads the model (see docs/proposals/asr_initial_prompt.patch); older native builds ignore it.
     */
    private fun writeAsrPrompt() {
        runCatching {
            val f = File(modelsDir, "${config.app.models.whisperDir}/prompt.txt")
            val text = com.megathon.incidentvoice.core.AsrPrompt.build(config.taxonomy, config.registry)
            if (!f.exists() || f.readText() != text) f.writeText(text)
        }.onFailure { android.util.Log.w("IV", "could not write ASR prompt: ${it.message}") }
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
                detectModelFamily()
                android.util.Log.i("IV", "LLM bundle appeared (${modelFamily.value.display}) -> re-init on-device engine")
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
    val audio: AudioInput by lazy {
        // the wake-word listener must let go of the microphone before any recording starts
        SwitchableAudioInput(micCapture, MockAudioInput(), File(context.getExternalFilesDir(null), "inject"), beforeListen = { if (config.app.features.wakeWord) { wake.release(); kotlinx.coroutines.delay(150) } }) { settingsSnapshot.value.useMic }
    }

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
            modelFamily = { modelFamily.value },
        )
    }

    // ---- Ask tab (PRD §37 O-2): vector index + conversational query over the local database
    val embedder: com.megathon.incidentvoice.ask.Embedder? by lazy { com.megathon.incidentvoice.ask.OnnxMiniLmEmbedder.fromAssets(context) }
    val askService: com.megathon.incidentvoice.ask.AskService by lazy {
        com.megathon.incidentvoice.ask.AskService(
            repository, com.megathon.incidentvoice.ask.RuleQueryParser(config.taxonomy, config.registry),
            com.megathon.incidentvoice.ask.AnswerComposer(config.taxonomy), embedder,
        )
    }
    val askController: com.megathon.incidentvoice.ask.AskController by lazy {
        com.megathon.incidentvoice.ask.AskController(appScope, askService, engine, audio, tts, { settingsSnapshot.value.speakQuestions }, embedder != null)
    }

    /** Keeps the vector index in step with the reports: runs at start and ~2 s after any report changes. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun startIndexing() {
        appScope.launch {
            repository.observeChanges().debounce(2000).collect {
                val t0 = System.currentTimeMillis()
                val n = runCatching { askService.syncIndex() }.getOrDefault(0)
                if (n > 0) android.util.Log.i("IV", "vector index: embedded $n report(s) in ${System.currentTimeMillis() - t0} ms")
            }
        }
    }

    /** logcat trace of the conversation (tag IV): state changes, every bubble, timings. `adb logcat -s IV` during tests/demo. */
    fun traceConversation() {
        appScope.launch {
            var lastState: Any? = null; var seen = 0; var lastDebug = ""
            orchestrator.ui.collect { u ->
                if (u.state != lastState) { android.util.Log.i("IV", "state ${lastState} -> ${u.state} round=${u.round} missing=${u.validation?.missing}"); lastState = u.state }
                if (u.timeline.size < seen) seen = 0
                u.timeline.drop(seen).forEach { android.util.Log.i("IV", "bubble ${it.role}: ${it.text}") }
                seen = u.timeline.size
                val d = "asr=${u.debug.asrMs}ms llm=${u.debug.llmMs}ms tok=${u.debug.tokensOut} task=${u.debug.lastTask}"
                if (d != lastDebug && u.debug.lastTask.isNotBlank()) { android.util.Log.i("IV", "timing $d"); lastDebug = d }
                u.error?.let { android.util.Log.w("IV", "error: $it") }
                u.notice?.let { android.util.Log.i("IV", "notice: $it") }
            }
        }
    }

    fun startEngine() { appScope.launch { selectEngine(settings.flow.first().engineMode) } }

    /** Extra 1: if the app was closed mid-emergency, bring the running response clock (and its draft) back. */
    fun resumeResponseClock() { orchestrator.resumeActiveResponse() }

    /** PRD §21.6: drafts older than 7 days are purged on startup (audio included). */
    fun housekeeping() { appScope.launch { runCatching { repository.purgeOldDrafts(7L * 24 * 3600_000) } } }
}
