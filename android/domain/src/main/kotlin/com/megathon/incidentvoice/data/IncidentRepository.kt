package com.megathon.incidentvoice.data

import com.megathon.incidentvoice.core.IncidentForm
import com.megathon.incidentvoice.core.IncidentMeta
import com.megathon.incidentvoice.core.IncidentStatus
import com.megathon.incidentvoice.core.Recommendation
import com.megathon.incidentvoice.core.Reporter
import kotlinx.coroutines.flow.Flow

/** PRD §22.3 (P0 subset; stats are the plain count row per rev 1.1). */

sealed class Actor(val tag: String) {
    data object System : Actor("SYSTEM")
    data object Llm : Actor("LLM")
    data class User(val empId: String) : Actor("USER:$empId")
}

data class DashboardFilter(
    val types: Set<String> = emptySet(),
    val severities: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val fromMs: Long? = null,
    val toMs: Long? = null,
    /** Ask tab (O-2): site-registry ids and/or a spoken place; either may match. */
    val locationIds: Set<String> = emptySet(),
    val locationText: String? = null,
    val injuries: String? = null,
    /** Dashboard hotspot tapped: only reports at this place ([com.megathon.incidentvoice.core.LocationKeys] key). */
    val locationKey: String? = null,
)

/** Text that is embedded for one incident, with a hash so unchanged incidents are not re-embedded. */
data class VectorSource(val incidentId: String, val text: String, val hash: String)

data class IncidentListItem(
    val id: String, val refNo: String?, val title: String, val type: String?, val severity: String?, val status: String,
    val location: String?, val createdAt: Long, val emergency: Boolean, val snippet: String? = null,
    /** Extra 3: witness accounts in this case (1 = a single report), unresolved conflicts between them, and for a linked account the case it belongs to. */
    val accounts: Int = 1, val conflicts: Int = 0, val parentRef: String? = null,
    /** Repeat incident / hotspot chips, and whether the report is seeded demo data. */
    val tags: com.megathon.incidentvoice.core.ReportTags = com.megathon.incidentvoice.core.ReportTags(), val demo: Boolean = false,
)

data class TurnRecord(
    val role: String, val kind: String, val textRaw: String? = null, val textEdited: String? = null, val lang: String? = null,
    val askedFields: List<String> = emptyList(), val audioPath: String? = null, val audioMs: Long? = null,
    val asrMs: Long? = null, val llmMs: Long? = null, val llmToolJson: String? = null, val llmStatsJson: String? = null,
    val idx: Int = -1, val createdAt: Long = 0,
) { val text: String get() = textEdited ?: textRaw.orEmpty() }

data class RecRow(val id: Long, val sopId: String, val text: String, val immediate: Boolean, val source: String, val done: Boolean)

data class IncidentDetail(
    val meta: IncidentMeta, val form: IncidentForm, val turns: List<TurnRecord>, val recommendations: List<RecRow>,
    val responseActions: List<com.megathon.incidentvoice.core.ResponseAction> = emptyList(),
)

data class DashboardCounts(val total: Int = 0, val critical: Int = 0, val last24h: Int = 0)

interface IncidentRepository {
    suspend fun createDraft(reporter: Reporter, atMs: Long? = null): String
    /** One transaction: incident + children + audit + search reindex. */
    suspend fun saveForm(id: String, form: IncidentForm, actor: Actor, followupRounds: Int? = null, emergency: Boolean? = null)
    suspend fun appendTurn(id: String, turn: TurnRecord): Long
    suspend fun saveRecommendations(id: String, recs: List<Recommendation>)
    suspend fun setRecommendationDone(recId: Long, done: Boolean)
    /** Assigns ref_no `INC-YYYYMMDD-NNNN`, status SUBMITTED. */
    suspend fun submit(id: String, actor: Actor = Actor.System, atMs: Long? = null): String
    suspend fun setStatus(id: String, status: IncidentStatus, actor: Actor)
    suspend fun delete(id: String, actor: Actor)
    fun observeList(filter: DashboardFilter, limit: Int = 100): Flow<List<IncidentListItem>>
    fun search(text: String, filter: DashboardFilter, limit: Int = 100): Flow<List<IncidentListItem>>
    fun observeDetail(id: String): Flow<IncidentDetail?>
    suspend fun load(id: String): IncidentDetail?
    fun observeCounts(): Flow<DashboardCounts>
    suspend fun latestDraft(maxAgeMs: Long): String?
    suspend fun purgeOldDrafts(maxAgeMs: Long): Int
    suspend fun countAll(): Int

    // ---- Ask tab / vector index (PRD §37 O-2). Defaults keep simple fakes valid.
    /** One-shot filtered list (not DRAFT), newest first. */
    suspend fun listFiltered(filter: DashboardFilter, limit: Int = 500): List<IncidentListItem> = emptyList()
    /** Ids of incidents whose search text matches the FTS expression. */
    suspend fun keywordIds(ftsQuery: String): Set<String> = emptySet()
    /** Incidents whose embedding is missing or stale for [modelId]. */
    suspend fun staleVectorSources(modelId: String): List<VectorSource> = emptyList()
    suspend fun saveVector(incidentId: String, modelId: String, hash: String, vector: FloatArray) {}
    suspend fun vectors(modelId: String): Map<String, FloatArray> = emptyMap()
    /** Emits whenever any incident changes (used to keep the vector index fresh). */
    fun observeChanges(): Flow<Long> = kotlinx.coroutines.flow.emptyFlow()
    // ---- Extra 1: response clock
    suspend fun setResponseStart(id: String, startedAtMs: Long) {}
    suspend fun addResponseAction(id: String, action: com.megathon.incidentvoice.core.ResponseAction) {}
    /** A draft whose response clock started after [sinceMs] (the app was closed mid-emergency), newest first. */
    suspend fun activeResponseDraft(sinceMs: Long): String? = null
    /** The reporter of one report (someone introduced themselves by voice); the device profile is not touched. */
    suspend fun setReporter(id: String, reporter: Reporter) {}
    // ---- Extra 3: multi-witness merging
    /** Submitted reports since [sinceMs], newest first (candidates for "is this the same event?"). */
    suspend fun recentSubmitted(sinceMs: Long, limit: Int = 60): List<IncidentDetail> = emptyList()
    /** Links [childId] to the case of [parentId] as a further witness account. Never automatic: a person chose it. */
    suspend fun linkWitness(childId: String, parentId: String, actor: Actor) {}
    /** Every account of the case [id] belongs to: the first report, then the linked ones in order of submission. */
    suspend fun caseOf(id: String): List<IncidentDetail> = emptyList()
    /** A supervisor trusts [value] for a conflicting field of the case; written to the audit log. */
    suspend fun resolveConflict(rootId: String, field: String, value: String, actor: Actor) {}
    suspend fun conflictResolutions(rootId: String): Map<String, String> = emptyMap()
    // ---- Repeat incidents and hotspots (rules on the local database, no model)
    /** Repeat / hotspot tags of one submitted report, as of now. */
    suspend fun reportTags(id: String): com.megathon.incidentvoice.core.ReportTags? = null
    /** The top hotspot locations, refreshed whenever a report changes. */
    fun observeHotspots(): Flow<List<com.megathon.incidentvoice.core.HotspotStat>> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun markDemo(id: String) {}
    /** Deletes only the reports marked as demo data. Returns how many. */
    suspend fun clearDemo(actor: Actor): Int = 0
    /** Demo reset: removes every report, draft and recording. Returns how many were deleted. */
    suspend fun deleteAll(actor: Actor): Int = 0
}
