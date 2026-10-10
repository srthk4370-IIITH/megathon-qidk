package com.megathon.incidentvoice

import com.megathon.incidentvoice.config.AppConfigBundle
import com.megathon.incidentvoice.core.*
import com.megathon.incidentvoice.data.*
import com.megathon.incidentvoice.domain.engine.EngineStatus
import com.megathon.incidentvoice.domain.engine.GenEvent
import com.megathon.incidentvoice.domain.engine.GenRequest
import com.megathon.incidentvoice.domain.engine.LlmTask
import com.megathon.incidentvoice.domain.engine.SamplerName
import com.megathon.incidentvoice.engineglue.MlEngineAdapter
import com.megathon.incidentvoice.engineglue.SwitchableEngine
import com.megathon.incidentvoice.orchestrator.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import com.megathon.incidentvoice.engine.MockInferenceEngine as MlMock

/**
 * The app's orchestrator driven through the ML branch's own engine classes (`:engine` MockInferenceEngine, which
 * emits the same event/DTO types as JniInferenceEngine) via [MlEngineAdapter]. Proves the two branches' types fit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MlEngineAdapterTest {
    private val dir = File(System.getProperty("iv.configDir")!!)
    private val cfg = AppConfigBundle(
        Taxonomy.parse(File(dir, "taxonomy.json").readText()), SiteRegistry.parse(File(dir, "site_registry.json").readText()),
        SopLibrary.parse(File(dir, "sop.json").readText()), PromptTemplates.load { File(dir, it).readText() },
        AppConfig.parse(File(dir, "app_config.json").readText()),
    )
    private val now = ZonedDateTime.of(2026, 10, 10, 10, 7, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant()

    private class Repo : IncidentRepository {
        var form = IncidentForm(); var submitted = false; val turns = mutableListOf<TurnRecord>()
        override suspend fun createDraft(reporter: Reporter, atMs: Long?) = "id-1"
        override suspend fun saveForm(id: String, form: IncidentForm, actor: Actor, followupRounds: Int?, emergency: Boolean?) { this.form = form }
        override suspend fun appendTurn(id: String, turn: TurnRecord): Long { turns += turn; return turns.size.toLong() }
        override suspend fun saveRecommendations(id: String, recs: List<Recommendation>) {}
        override suspend fun setRecommendationDone(recId: Long, done: Boolean) {}
        override suspend fun submit(id: String, actor: Actor, atMs: Long?): String { submitted = true; return "INC-20261010-0001" }
        override suspend fun setStatus(id: String, status: IncidentStatus, actor: Actor) {}
        override suspend fun delete(id: String, actor: Actor) {}
        override fun observeList(filter: DashboardFilter, limit: Int): Flow<List<IncidentListItem>> = flowOf(emptyList())
        override fun search(text: String, filter: DashboardFilter, limit: Int): Flow<List<IncidentListItem>> = flowOf(emptyList())
        override fun observeDetail(id: String): Flow<IncidentDetail?> = flowOf(null)
        override suspend fun load(id: String): IncidentDetail? = null
        override fun observeCounts(): Flow<DashboardCounts> = flowOf(DashboardCounts())
        override suspend fun latestDraft(maxAgeMs: Long): String? = null
        override suspend fun purgeOldDrafts(maxAgeMs: Long) = 0
        override suspend fun countAll() = 0
    }
    private class Tts : TtsSpeaker {
        val spoken = mutableListOf<String>()
        override val ready = MutableStateFlow(true)
        override suspend fun speak(text: String, lang: String): SpeakResult { spoken += text; return SpeakResult.DONE }
        override fun stop() {}
    }
    private class Audio : AudioInput {
        override suspend fun listen(kind: ListenKind, maxMs: Long, endSilenceMs: Int, incidentId: String, onProgress: (Float, Long) -> Unit): ListenResult =
            ListenResult.Speech(ShortArray(16_000), 1000)
        override fun stop() {}
        override fun cancel() {}
    }

    @Test fun slideConversation_throughMlEngineTypes() = runTest {
        val ml = MlMock(asrDelayMs = 0, tokensPerSecond = 1000)
        val engine = SwitchableEngine(backgroundScope, MlEngineAdapter(ml, backgroundScope, "ml-mock"))
        val info = engine.init()
        assertEquals("MOCK", info.plan)
        val repo = Repo(); val tts = Tts()
        val o = ConversationOrchestrator(this, cfg, engine, repo, tts, Audio(), { OrchestratorSettings(Reporter("Asha", "E1")) }, clock = { now }, retryDelaysMs = emptyList())
        o.startNew(); o.onMicTapped(); advanceUntilIdle()
        val ui = o.ui.value
        assertEquals(ConvState.REVIEW, ui.state); assertTrue(ui.validation!!.complete)
        assertEquals("WATER", ui.form.incidentType); assertEquals("STOPPED", ui.form.str("leak_status")); assertEquals("LOW", ui.form.str("severity"))
        assertEquals("Was anyone hurt, and how serious is the situation right now?", tts.spoken[0])
        assertEquals("Where is the water coming from, and is it still leaking?", tts.spoken[1])
        o.onSubmit(); advanceUntilIdle()
        assertTrue(repo.submitted); assertEquals("INC-20261010-0001", o.ui.value.submittedRef)
    }

    @Test fun eventsAndStatsAreMapped_andDoneTextIsCarried() = runTest {
        val a = MlEngineAdapter(MlMock(asrDelayMs = 0, tokensPerSecond = 1000), backgroundScope, "ml-mock")
        val ev = a.generate(GenRequest(LlmTask.EXTRACT, "p", SamplerName.GREEDY, 320)).toList()
        val done = ev.last() as GenEvent.Done
        assertTrue(ev.first() is GenEvent.Token)
        assertEquals(ev.filterIsInstance<GenEvent.Token>().joinToString("") { it.piece }, done.text)
        assertEquals("EXTRACT", done.stats.task); assertTrue(done.stats.tokensOut > 0)
        assertEquals("There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.", a.transcribe(ShortArray(10)).text)
    }

    @Test fun statusIsMapped() = runTest {
        val ml = MlMock(asrDelayMs = 0)
        val a = MlEngineAdapter(ml, backgroundScope, "ml-mock")
        assertEquals(EngineStatus.Idle, a.status.value)
        a.init(); testScheduler.runCurrent()
        assertTrue(a.status.value is EngineStatus.Ready)
    }
}
