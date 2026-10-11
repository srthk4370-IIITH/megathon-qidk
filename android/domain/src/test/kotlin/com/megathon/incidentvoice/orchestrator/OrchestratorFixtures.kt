package com.megathon.incidentvoice.orchestrator

import com.megathon.incidentvoice.core.*
import com.megathon.incidentvoice.data.*
import kotlinx.coroutines.flow.*

class InMemoryRepo(private val now: () -> Long = { Fx.now.toEpochMilli() }) : IncidentRepository {
    class Rec(var meta: IncidentMeta, var form: IncidentForm = IncidentForm(), val turns: MutableList<TurnRecord> = mutableListOf(), var recs: List<Recommendation> = emptyList(), val actions: MutableList<ResponseAction> = mutableListOf())
    override suspend fun setResponseStart(id: String, startedAtMs: Long) { store[id]!!.meta = store[id]!!.meta.copy(responseStartedAt = startedAtMs) }
    override suspend fun addResponseAction(id: String, action: ResponseAction) { store[id]!!.actions += action }
    override suspend fun activeResponseDraft(sinceMs: Long): String? = store.values.lastOrNull { it.meta.status == IncidentStatus.DRAFT && (it.meta.responseStartedAt ?: 0) >= sinceMs }?.meta?.id
    val store = LinkedHashMap<String, Rec>()
    var counter = 0
    override suspend fun createDraft(reporter: Reporter, atMs: Long?): String {
        val id = "id-${++counter}"; store[id] = Rec(IncidentMeta(id, createdAt = now(), reportedAt = now(), reporter = reporter)); return id
    }
    override suspend fun saveForm(id: String, form: IncidentForm, actor: Actor, followupRounds: Int?, emergency: Boolean?) {
        val r = store[id]!!; r.form = form
        r.meta = r.meta.copy(followupRounds = followupRounds ?: r.meta.followupRounds, emergencyFlag = emergency ?: r.meta.emergencyFlag)
    }
    override suspend fun appendTurn(id: String, turn: TurnRecord): Long { store[id]!!.turns += turn.copy(idx = store[id]!!.turns.size); return store[id]!!.turns.size.toLong() }
    override suspend fun saveRecommendations(id: String, recs: List<Recommendation>) { store[id]!!.recs = recs }
    override suspend fun setRecommendationDone(recId: Long, done: Boolean) {}
    override suspend fun submit(id: String, actor: Actor, atMs: Long?): String {
        val ref = "INC-20261010-" + (store.values.count { it.meta.refNo != null } + 1).toString().padStart(4, '0')
        store[id]!!.meta = store[id]!!.meta.copy(status = IncidentStatus.SUBMITTED, refNo = ref, submittedAt = atMs ?: now()); return ref
    }
    override suspend fun setStatus(id: String, status: IncidentStatus, actor: Actor) {}
    override suspend fun delete(id: String, actor: Actor) { store.remove(id) }
    override fun observeList(filter: DashboardFilter, limit: Int): Flow<List<IncidentListItem>> = flowOf(emptyList())
    override fun search(text: String, filter: DashboardFilter, limit: Int): Flow<List<IncidentListItem>> = flowOf(emptyList())
    override fun observeDetail(id: String): Flow<IncidentDetail?> = flowOf(null)
    override suspend fun load(id: String): IncidentDetail? = store[id]?.let { r -> IncidentDetail(r.meta, r.form, r.turns, r.recs.mapIndexed { i, x -> RecRow(i.toLong(), x.sopId, x.text, x.immediate, x.source, x.done) }, r.actions.toList()) }
    override fun observeCounts(): Flow<DashboardCounts> = flowOf(DashboardCounts())
    override suspend fun latestDraft(maxAgeMs: Long): String? = null
    override suspend fun purgeOldDrafts(maxAgeMs: Long): Int = 0
    override suspend fun countAll(): Int = store.size
    override suspend fun setReporter(id: String, reporter: Reporter) { store[id]!!.meta = store[id]!!.meta.copy(reporter = reporter) }
    override suspend fun recentSubmitted(sinceMs: Long, limit: Int): List<IncidentDetail> = store.values.filter { it.meta.status == IncidentStatus.SUBMITTED }.mapNotNull { load(it.meta.id) }
    override suspend fun linkWitness(childId: String, parentId: String, actor: Actor) { store[childId]!!.meta = store[childId]!!.meta.copy(parentId = parentId) }
    override suspend fun caseOf(id: String): List<IncidentDetail> { val root = store[id]?.meta?.let { it.parentId ?: it.id } ?: return emptyList(); return store.values.filter { it.meta.id == root || it.meta.parentId == root }.mapNotNull { load(it.meta.id) } }
}

class FakeTts(ready: Boolean = true) : TtsSpeaker {
    val spoken = mutableListOf<String>()
    override val ready = MutableStateFlow(ready)
    override suspend fun speak(text: String, lang: String): SpeakResult { if (!ready.value) return SpeakResult.SKIPPED; spoken += text; return SpeakResult.DONE }
    override fun stop() {}
}

/** Returns queued results; default = a 1 s speech clip. */
class FakeAudio : AudioInput {
    val results = ArrayDeque<ListenResult>()
    var calls = mutableListOf<ListenKind>()
    override suspend fun listen(kind: ListenKind, maxMs: Long, endSilenceMs: Int, incidentId: String, onProgress: (Float, Long) -> Unit): ListenResult {
        calls += kind; onProgress(0.5f, 500)
        return results.removeFirstOrNull() ?: ListenResult.Speech(ShortArray(16_000), 1000)
    }
    override fun stop() {}
    override fun cancel() {}
}
