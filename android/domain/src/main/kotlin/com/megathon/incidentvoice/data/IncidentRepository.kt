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
)

data class IncidentListItem(
    val id: String, val refNo: String?, val title: String, val type: String?, val severity: String?, val status: String,
    val location: String?, val createdAt: Long, val emergency: Boolean, val snippet: String? = null,
)

data class TurnRecord(
    val role: String, val kind: String, val textRaw: String? = null, val textEdited: String? = null, val lang: String? = null,
    val askedFields: List<String> = emptyList(), val audioPath: String? = null, val audioMs: Long? = null,
    val asrMs: Long? = null, val llmMs: Long? = null, val llmToolJson: String? = null, val llmStatsJson: String? = null,
    val idx: Int = -1, val createdAt: Long = 0,
) { val text: String get() = textEdited ?: textRaw.orEmpty() }

data class RecRow(val id: Long, val sopId: String, val text: String, val immediate: Boolean, val source: String, val done: Boolean)

data class IncidentDetail(val meta: IncidentMeta, val form: IncidentForm, val turns: List<TurnRecord>, val recommendations: List<RecRow>)

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
}
