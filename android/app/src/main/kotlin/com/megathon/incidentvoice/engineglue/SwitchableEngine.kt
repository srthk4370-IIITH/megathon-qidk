package com.megathon.incidentvoice.engineglue

import com.megathon.incidentvoice.domain.engine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** One stable engine reference for the orchestrator; the real delegate can be swapped at runtime (Settings → Engine). */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableEngine(scope: CoroutineScope, initial: InferenceEngine) : InferenceEngine {
    private val current = MutableStateFlow(initial)
    val delegate: InferenceEngine get() = current.value

    override val status: StateFlow<EngineStatus> =
        current.flatMapLatest { it.status }.stateIn(scope, SharingStarted.Eagerly, initial.status.value)

    fun switchTo(engine: InferenceEngine) { current.value = engine }

    override suspend fun init() = current.value.init()
    override suspend fun warmup() = current.value.warmup()
    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, mode: LangMode) = current.value.transcribe(pcm, sampleRate, mode)
    override fun generate(req: GenRequest): Flow<GenEvent> = current.value.generate(req)
    override suspend fun abort() = current.value.abort()
    override suspend fun release() = current.value.release()
}
