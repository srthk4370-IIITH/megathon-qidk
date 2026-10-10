package com.megathon.incidentvoice.ask

import com.megathon.incidentvoice.data.DashboardFilter
import com.megathon.incidentvoice.data.IncidentListItem
import com.megathon.incidentvoice.data.IncidentRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

/**
 * The Ask pipeline (PRD §37 O-2): question → [IncidentQuery] → SQL filters ∩ (vector similarity ∪ keyword match)
 * → ranked hits → worded answer. Pure Kotlin; the embedder and the repository are injected.
 * [embedder] may be null (model missing): the service then falls back to keyword search only.
 */
class AskService(
    private val repo: IncidentRepository,
    private val understanding: QueryUnderstanding,
    private val composer: AnswerComposer,
    private val embedder: Embedder?,
    private val clock: () -> Instant = { Instant.now() },
) {
    private val indexLock = Mutex()

    /** Embeds incidents that are new or whose text changed. Cheap to call often. Returns how many were (re)indexed. */
    suspend fun syncIndex(): Int {
        val e = embedder ?: return 0
        return indexLock.withLock {
            val stale = repo.staleVectorSources(e.modelId)
            for (s in stale) runCatching { repo.saveVector(s.incidentId, e.modelId, s.hash, e.embed(s.text)) }
            stale.size
        }
    }

    suspend fun ask(question: String): AskAnswer {
        val t0 = System.nanoTime()
        val q = understanding.parse(question, clock())
        val filter = DashboardFilter(
            types = q.types, severities = q.severities, fromMs = q.fromMs, toMs = q.toMs,
            locationIds = q.locationIds, locationText = q.locationLabel?.lowercase(), injuries = q.injuries,
        )
        val candidates = repo.listFiltered(filter, 500)
        val ranked: List<Pair<IncidentListItem, Triple<Float?, Float?, Boolean>>>
        val mode: String
        if (q.freeText.isBlank()) {
            ranked = candidates.map { it to Triple<Float?, Float?, Boolean>(null, null, false) }       // already newest first
            mode = "filters"
        } else {
            syncIndex()
            // A whole sentence embeds better than its content words ("somebody twisted their foot" 0.36 vs "twisted foot" 0.28),
            // so when nothing was taken out as a filter the question itself is embedded.
            val qv = embedder?.let { runCatching { it.embed(if (q.hasFilters) q.freeText else q.raw) }.getOrNull() }
            val vecs = if (qv != null) repo.vectors(embedder!!.modelId) else emptyMap()
            val words = q.freeText.split(' ').map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }.filter { it.length >= 3 }
            val all = if (words.isEmpty()) emptySet() else repo.keywordIds(words.joinToString(" ") { "$it*" })            // every word (FTS4 implicit AND)
            val any = if (words.isEmpty()) emptySet() else repo.keywordIds(words.joinToString(" OR ") { "$it*" })
            ranked = candidates.mapNotNull { c ->
                val sem = qv?.let { v -> vecs[c.id]?.let { Vectors.dot(v, it) } }
                val a = c.id in all; val o = a || c.id in any
                if (!HybridRank.relevant(sem, a, o)) null else c to Triple<Float?, Float?, Boolean>(HybridRank.score(sem, a, o), sem, o)
            }.sortedByDescending { it.second.first ?: 0f }
            mode = if (qv != null) "filters + vector + keyword" else "filters + keyword"
        }
        val hits = ranked.take(q.limit).mapIndexed { i, (c, s) -> toHit(c, s, withDetail = i < 2) }
        return AskAnswer(q, composer.compose(q, hits, ranked.size), hits, ranked.size, (System.nanoTime() - t0) / 1_000_000, mode)
    }

    /** "Similar incidents" for the detail screen: nearest neighbours of an incident's own vector. */
    suspend fun similar(incidentId: String, k: Int = 3): List<AskHit> {
        val e = embedder ?: return emptyList()
        syncIndex()
        val vecs = repo.vectors(e.modelId)
        val me = vecs[incidentId] ?: return emptyList()
        val byId = repo.listFiltered(DashboardFilter(), 1000).associateBy { it.id }
        val top = vecs.entries.filter { it.key != incidentId && it.key in byId }
            .map { it.key to Vectors.dot(me, it.value) }.filter { it.second >= 0.35f }.sortedByDescending { it.second }.take(k)
        val out = ArrayList<AskHit>()
        for ((id, s) in top) out += toHit(byId[id]!!, Triple(s, s, false), withDetail = false)
        return out
    }

    private suspend fun toHit(c: IncidentListItem, s: Triple<Float?, Float?, Boolean>, withDetail: Boolean): AskHit {
        val d = if (withDetail) repo.load(c.id) else null
        return AskHit(
            id = c.id, refNo = c.refNo, title = c.title, type = c.type, severity = c.severity, status = c.status, location = c.location, whenMs = c.createdAt,
            description = d?.form?.str("description"),
            actionsTaken = (d?.form?.value("actions_taken") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            recommendations = d?.recommendations?.map { it.text }.orEmpty(),
            score = s.first, semantic = s.second, keyword = s.third,
        )
    }
}
