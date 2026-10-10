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
import com.megathon.incidentvoice.engine.InferenceEngine
import com.megathon.incidentvoice.engine.MockInferenceEngine
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

    /** Stage C: Mock engine only. Real JNI/HTTP engines plug in here behind the same interface. */
    val engine: InferenceEngine by lazy { MockInferenceEngine(config.taxonomy) }
    val tts: TtsSpeaker by lazy { AndroidTtsSpeaker(context) }
    val micCapture: AudioCapture by lazy {
        AudioCapture(context, audioRoot, { config.app.vad }) { SileroVad.create(context) ?: EnergyVadAdapter() }
    }
    val audio: AudioInput by lazy { SwitchableAudioInput(micCapture, MockAudioInput()) { settingsSnapshot.value.useMic } }

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

    fun startEngine() { appScope.launch { runCatching { engine.init() } } }
}
