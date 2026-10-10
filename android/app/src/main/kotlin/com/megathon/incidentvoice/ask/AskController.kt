package com.megathon.incidentvoice.ask

import com.megathon.incidentvoice.domain.engine.InferenceEngine
import com.megathon.incidentvoice.domain.engine.LangMode
import com.megathon.incidentvoice.orchestrator.AudioInput
import com.megathon.incidentvoice.orchestrator.ListenKind
import com.megathon.incidentvoice.orchestrator.ListenResult
import com.megathon.incidentvoice.orchestrator.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AskExchange(val id: Long, val question: String, val answer: AskAnswer? = null, val error: String? = null)

data class AskUiState(
    val exchanges: List<AskExchange> = emptyList(),
    val listening: Boolean = false, val level: Float = 0f, val transcribing: Boolean = false, val thinking: Boolean = false,
    val notice: String? = null, val indexed: Int = 0, val vectorSearch: Boolean = false,
)

/** State holder for the Ask tab (lives in AppContainer so the conversation survives tab switches and rotation). */
class AskController(
    private val scope: CoroutineScope,
    private val service: AskService,
    private val engine: InferenceEngine,
    private val audio: AudioInput,
    private val tts: TtsSpeaker,
    private val speak: () -> Boolean,
    hasEmbedder: Boolean,
) {
    private val _ui = MutableStateFlow(AskUiState(vectorSearch = hasEmbedder))
    val ui: StateFlow<AskUiState> = _ui
    private var seq = 0L
    private var job: Job? = null

    fun ask(question: String) {
        val q = question.trim(); if (q.isEmpty()) return
        val id = ++seq
        _ui.update { it.copy(exchanges = it.exchanges + AskExchange(id, q), thinking = true, notice = null) }
        job?.cancel()
        job = scope.launch {
            val r = runCatching { service.ask(q) }
            _ui.update { u -> u.copy(thinking = false, exchanges = u.exchanges.map { if (it.id == id) it.copy(answer = r.getOrNull(), error = r.exceptionOrNull()?.message) else it }) }
            android.util.Log.i("IV", "ask \"$q\" -> ${r.getOrNull()?.let { "${it.total} hits in ${it.tookMs} ms [${it.mode}] ${it.query.copy(raw = "")}" } ?: r.exceptionOrNull()}")
            r.getOrNull()?.let { if (speak()) { tts.stop(); tts.speak(it.text, "en") } }
        }
    }

    /** Speak the question instead of typing it: same microphone, VAD and Whisper as the report flow. */
    fun askByVoice() {
        if (_ui.value.listening) { audio.stop(); return }
        tts.stop(); job?.cancel()
        job = scope.launch {
            _ui.update { it.copy(listening = true, level = 0f, notice = null) }
            val r = audio.listen(ListenKind.FOLLOWUP, 15_000, 1000, "ask") { lvl, _ -> _ui.update { u -> u.copy(level = lvl) } }
            _ui.update { it.copy(listening = false, level = 0f) }
            when (r) {
                is ListenResult.Speech -> {
                    _ui.update { it.copy(transcribing = true) }
                    val text = runCatching { engine.transcribe(r.pcm, 16_000, LangMode.FORCE_EN).text.trim() }
                    _ui.update { it.copy(transcribing = false) }
                    val t = text.getOrNull()
                    if (t.isNullOrBlank()) _ui.update { it.copy(notice = text.exceptionOrNull()?.message ?: "I couldn't make out the question. Try again or type it.") } else ask(t)
                }
                ListenResult.NoSpeech -> _ui.update { it.copy(notice = "I didn't hear a question.") }
                is ListenResult.Failure -> _ui.update { it.copy(notice = r.message) }
                ListenResult.Cancelled -> {}
            }
        }
    }

    fun refreshIndex() { scope.launch { runCatching { service.syncIndex() }; } }
    fun clear() { job?.cancel(); tts.stop(); _ui.update { it.copy(exchanges = emptyList(), thinking = false, notice = null) } }
    suspend fun similar(id: String) = runCatching { service.similar(id) }.getOrDefault(emptyList())
}
