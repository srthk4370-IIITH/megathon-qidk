package com.megathon.incidentvoice.data

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.megathon.incidentvoice.core.*
import com.megathon.incidentvoice.data.dao.IncidentListRow
import com.megathon.incidentvoice.data.db.IncidentDb
import com.megathon.incidentvoice.data.entity.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class RoomIncidentRepository(
    private val db: IncidentDb,
    private val tx: Taxonomy,
    private val registry: SiteRegistry,
    private val audioRoot: File? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
) : IncidentRepository {
    private val dao = db.dao()
    private val json = Json { ignoreUnknownKeys = true }
    private val listSer = ListSerializer(String.serializer())

    // ---------------------------------------------------------------------------------------
    override suspend fun createDraft(reporter: Reporter, atMs: Long?): String {
        val id = UUID.randomUUID().toString()
        val now = atMs ?: nowMs()
        val meta = IncidentMeta(id, IncidentStatus.DRAFT, null, reporter, createdAt = now, reportedAt = now)
        db.withTransaction {
            dao.upsert(toEntity(FormMapper.toStored(IncidentForm(), meta, now), 0))
            dao.insertAudit(listOf(AuditEntity(incidentId = id, at = now, actor = Actor.System.tag, action = "CREATE", field = null, oldValue = null, newValue = null)))
        }
        return id
    }

    override suspend fun saveForm(id: String, form: IncidentForm, actor: Actor, followupRounds: Int?, emergency: Boolean?) {
        db.withTransaction {
            val old = loadStored(id) ?: error("no incident $id")
            val now = nowMs()
            val oldRow = old.row
            val meta = metaOf(oldRow).copy(
                followupRounds = followupRounds ?: oldRow.followupRounds,
                emergencyFlag = emergency ?: oldRow.emergencyFlag,
            )
            val stored = FormMapper.toStored(form, meta, now)
            val rowid = dao.rowidOf(id) ?: 0
            dao.upsert(toEntity(stored, rowid))
            writeChildren(id, stored)
            dao.insertAudit(auditDiff(id, FormMapper.toForm(old), form, actor, now))
            reindexInTx(id)
        }
    }

    override suspend fun appendTurn(id: String, turn: TurnRecord): Long = db.withTransaction {
        val idx = dao.nextTurnIdx(id)
        val tid = dao.insertTurn(
            TurnEntity(
                incidentId = id, idx = idx, role = turn.role, kind = turn.kind,
                askedFieldsJson = if (turn.askedFields.isEmpty()) null else json.encodeToString(listSer, turn.askedFields),
                textRaw = turn.textRaw, textEdited = turn.textEdited, lang = turn.lang, audioPath = turn.audioPath,
                audioMs = turn.audioMs, asrMs = turn.asrMs, llmMs = turn.llmMs, llmToolJson = turn.llmToolJson,
                llmStatsJson = turn.llmStatsJson, createdAt = nowMs(),
            )
        )
        reindexInTx(id)
        tid
    }

    override suspend fun saveRecommendations(id: String, recs: List<Recommendation>) {
        db.withTransaction {
            dao.clearRecs(id)
            dao.insertRecs(recs.mapIndexed { i, r ->
                RecommendationEntity(incidentId = id, ord = i, sopId = r.sopId, text = r.text, source = r.source, immediate = r.immediate, done = r.done)
            })
            reindexInTx(id)
        }
    }

    override suspend fun setRecommendationDone(recId: Long, done: Boolean) = dao.setRecDone(recId, done)

    override suspend fun submit(id: String, actor: Actor, atMs: Long?): String = db.withTransaction {
        val now = atMs ?: nowMs()
        // not BASIC_ISO_DATE: on a zoned value it appends the offset ("20261011+0530")
        val day = DateTimeFormatter.ofPattern("yyyyMMdd").format(Instant.ofEpochMilli(now).atZone(zone))
        val prefix = "INC-$day-"
        val ref = prefix + (dao.countRefs(prefix) + 1).toString().padStart(4, '0')
        dao.markSubmitted(id, IncidentStatus.SUBMITTED.name, ref, now)
        dao.insertAudit(listOf(
            AuditEntity(incidentId = id, at = now, actor = actor.tag, action = "STATUS_CHANGE", field = "status", oldValue = "DRAFT", newValue = "SUBMITTED"),
            AuditEntity(incidentId = id, at = now, actor = actor.tag, action = "SUBMIT", field = "ref_no", oldValue = null, newValue = ref),
        ))
        reindexInTx(id)
        ref
    }

    override suspend fun setStatus(id: String, status: IncidentStatus, actor: Actor) {
        db.withTransaction {
            val old = dao.get(id)?.status
            dao.setStatus(id, status.name, nowMs())
            dao.insertAudit(listOf(AuditEntity(incidentId = id, at = nowMs(), actor = actor.tag, action = "STATUS_CHANGE", field = "status", oldValue = old, newValue = status.name)))
            reindexInTx(id)
        }
    }

    override suspend fun delete(id: String, actor: Actor) {
        db.withTransaction {
            dao.deleteSearch(id)
            dao.deleteVector(id)
            dao.deleteResponseActions(id)
            dao.delete(id)
            dao.orphanChildren(id)
            dao.insertAudit(listOf(AuditEntity(incidentId = id, at = nowMs(), actor = actor.tag, action = "DELETE", field = null, oldValue = null, newValue = null)))
        }
        audioRoot?.resolve(id)?.deleteRecursively()
    }

    override suspend fun deleteAll(actor: Actor): Int { val ids = dao.allIds(); ids.forEach { delete(it, actor) }; return ids.size }

    override suspend fun latestDraft(maxAgeMs: Long): String? = dao.latestDraftSince(nowMs() - maxAgeMs)

    override suspend fun purgeOldDrafts(maxAgeMs: Long): Int {
        val ids = dao.oldDraftIds(nowMs() - maxAgeMs)
        ids.forEach { delete(it, Actor.System) }
        return ids.size
    }

    override suspend fun countAll(): Int = dao.observeTotal().first()

    // ---- queries ----------------------------------------------------------------------------

    override fun observeList(filter: DashboardFilter, limit: Int): Flow<List<IncidentListItem>> =
        dao.observeList(listQuery(null, filter, limit)).map { rows -> withCases(rows.map(::toItem), rows) }

    override fun search(text: String, filter: DashboardFilter, limit: Int): Flow<List<IncidentListItem>> {
        val q = SearchQueryBuilder.build(text) ?: return observeList(filter, limit)
        return dao.observeList(listQuery(q, filter, limit)).map { rows -> withCases(rows.map(::toItem), rows) }
    }

    override fun observeDetail(id: String): Flow<IncidentDetail?> =
        merge(dao.observe(id), dao.observeRecs(id), dao.observeTurns(id), dao.observeResponseActions(id)).map { load(id) }.distinctUntilChanged()

    override suspend fun load(id: String): IncidentDetail? {
        val st = loadStored(id) ?: return null
        val turns = dao.turns(id).map {
            TurnRecord(it.role, it.kind, it.textRaw, it.textEdited, it.lang,
                it.askedFieldsJson?.let { j -> runCatching { json.decodeFromString(listSer, j) }.getOrNull() } ?: emptyList(),
                it.audioPath, it.audioMs, it.asrMs, it.llmMs, it.llmToolJson, it.llmStatsJson, it.idx, it.createdAt)
        }
        val recs = dao.recs(id).map { RecRow(it.id, it.sopId, it.text, it.immediate, it.source, it.done) }
        val actions = dao.responseActions(id).map { ResponseAction(it.stepId, it.label, it.doneAt, it.source) }
        var form = FormMapper.toForm(st)
        // a report saved before evidence was stored: work the spans out from its transcript (kept once the report is saved again)
        if (st.row.evidenceJson == null) form = Evidence.attach(form, turns.filter { it.role == "USER" }.map { it.text }, tx, Instant.ofEpochMilli(st.row.reportedAt))
        return IncidentDetail(metaOf(st.row), form, turns, recs, actions)
    }

    override fun observeCounts(): Flow<DashboardCounts> = combine(
        dao.observeTotal(), dao.observeCritical(), dao.observeSince(nowMs() - 24L * 3600_000),
    ) { t, c, d -> DashboardCounts(t, c, d) }

    // ---- Extra 1: response clock -----------------------------------------------------------

    override suspend fun setResponseStart(id: String, startedAtMs: Long) = dao.setResponseStart(id, startedAtMs)
    override suspend fun addResponseAction(id: String, action: ResponseAction) =
        dao.insertResponseAction(ResponseActionEntity(incidentId = id, stepId = action.stepId, label = action.label, doneAt = action.doneAtMs, source = action.source))
    override suspend fun activeResponseDraft(sinceMs: Long): String? = dao.activeResponseDraft(sinceMs)

    // ---- Ask tab / vector index (PRD §37 O-2) -----------------------------------------------

    override suspend fun listFiltered(filter: DashboardFilter, limit: Int): List<IncidentListItem> = dao.listOnce(listQuery(null, filter, limit)).map(::toItem)

    override suspend fun keywordIds(ftsQuery: String): Set<String> = runCatching { dao.searchIds(ftsQuery).toSet() }.getOrDefault(emptySet())

    override fun observeChanges(): Flow<Long> = dao.observeMaxUpdated().map { it ?: 0L }.distinctUntilChanged()

    /** What gets embedded: the meaning of the report in a few sentences (not the whole search body). */
    private fun vectorText(d: IncidentDetail): String {
        val f = d.form
        val detailText = f.fields.filterKeys { tx.field(it)?.isCommon == false }.mapNotNull { (k, st) -> (st.value as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { v -> "${tx.label(k)}: ${v.lowercase().replace('_', ' ')}" } }
        val actions = (f.value("actions_taken") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
        return listOfNotNull(
            f.str("title"), f.str("description"), f.str("location")?.let { "Location: $it" },
            f.types.mapNotNull { tx.category(it)?.label }.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "Type: $it" },
            detailText.takeIf { it.isNotEmpty() }?.joinToString(". "), actions.takeIf { it.isNotEmpty() }?.joinToString(". "),
        ).joinToString(". ").take(1200)
    }

    override suspend fun staleVectorSources(modelId: String): List<VectorSource> {
        val have = dao.vectorHashes(modelId).associate { it.incidentId to it.textHash }
        val out = ArrayList<VectorSource>()
        for (id in dao.allIds()) {
            val d = load(id) ?: continue
            val text = vectorText(d)
            if (text.isBlank()) continue
            val hash = Integer.toHexString(text.hashCode())
            if (have[id] != hash) out += VectorSource(id, text, hash)
        }
        return out
    }

    override suspend fun saveVector(incidentId: String, modelId: String, hash: String, vector: FloatArray) =
        dao.upsertVector(IncidentVectorEntity(incidentId, modelId, vector.size, hash, com.megathon.incidentvoice.ask.Vectors.toBytes(vector), nowMs()))

    override suspend fun vectors(modelId: String): Map<String, FloatArray> =
        dao.vectors(modelId).associate { it.incidentId to com.megathon.incidentvoice.ask.Vectors.fromBytes(it.vec) }

    // ---- Extra 3: multi-witness merging ---------------------------------------------------------

    override suspend fun setReporter(id: String, reporter: Reporter) = dao.setReporter(id, reporter.name, reporter.empId, nowMs())

    override suspend fun recentSubmitted(sinceMs: Long, limit: Int): List<IncidentDetail> = dao.submittedSince(sinceMs, limit).mapNotNull { load(it) }

    override suspend fun linkWitness(childId: String, parentId: String, actor: Actor) {
        if (childId == parentId) return
        db.withTransaction {
            val child = dao.get(childId) ?: return@withTransaction
            // always link to the first report of the case, and bring along anything already linked to the child
            val root = dao.get(parentId)?.let { it.parentIncidentId ?: it.id } ?: return@withTransaction
            if (root == childId) return@withTransaction
            val now = nowMs()
            dao.childIds(childId).forEach { dao.setParent(it, root, now) }
            dao.setParent(childId, root, now); dao.touch(root, now)
            dao.insertAudit(listOf(
                AuditEntity(incidentId = childId, at = now, actor = actor.tag, action = "WITNESS_LINK", field = "parent_incident_id", oldValue = child.parentIncidentId, newValue = root),
                AuditEntity(incidentId = root, at = now, actor = actor.tag, action = "WITNESS_ADDED", field = "witness_account", oldValue = null, newValue = child.refNo ?: childId),
            ))
        }
    }

    override suspend fun caseOf(id: String): List<IncidentDetail> {
        val root = dao.get(id)?.let { it.parentIncidentId ?: it.id } ?: return emptyList()
        return (listOf(root) + dao.childIds(root)).mapNotNull { load(it) }
    }

    override suspend fun resolveConflict(rootId: String, field: String, value: String, actor: Actor) {
        val now = nowMs()
        val before = conflictResolutions(rootId)[field]
        dao.insertAudit(listOf(AuditEntity(incidentId = rootId, at = now, actor = actor.tag, action = MERGE_RESOLVE, field = field, oldValue = before, newValue = value)))
        dao.touch(rootId, now)
    }

    override suspend fun conflictResolutions(rootId: String): Map<String, String> =
        dao.audit(rootId).filter { it.action == MERGE_RESOLVE && it.field != null && it.newValue != null }.associate { it.field!! to it.newValue!! } // ordered by time: the latest wins

    /** Adds "N accounts · M conflicts" to cases and the case reference to linked accounts. */
    private suspend fun withCases(items: List<IncidentListItem>, rows: List<IncidentListRow>): List<IncidentListItem> {
        val counts = dao.childCounts().associate { it.pid to it.n }
        if (counts.isEmpty()) return items
        val parentOf = rows.associate { it.id to it.parentIncidentId }
        return items.map { i ->
            val n = counts[i.id]; val p = parentOf[i.id]
            when {
                n != null -> {
                    val case = MergeEngine.merge(caseOf(i.id).map { WitnessAccount.of(it.meta, it.form) }, conflictResolutions(i.id))
                    i.copy(accounts = case.accounts.size, conflicts = case.openConflicts)
                }
                p != null -> i.copy(parentRef = dao.get(p)?.refNo ?: "another report")
                else -> i
            }
        }
    }

    // ---- internals ------------------------------------------------------------------------

    private fun listQuery(match: String?, f: DashboardFilter, limit: Int): SimpleSQLiteQuery {
        val args = ArrayList<Any?>()
        val sb = StringBuilder(
            "SELECT i.id, i.ref_no, i.title, i.description, i.incident_type, i.severity, i.status, i.location_text, " +
                "i.created_at, i.occurred_at, i.emergency_flag, i.parent_incident_id, "
        )
        sb.append(if (match != null) "snippet(incident_search, '[', ']', '…', 0, 10) AS snip " else "NULL AS snip ")
        sb.append("FROM incidents i ")
        if (match != null) sb.append("JOIN incident_search ON incident_search.rowid = i.rowid_pk ")
        sb.append("WHERE i.status != 'DRAFT' ")
        if (match != null) { sb.append("AND incident_search MATCH ? "); args.add(match) }
        fun inClause(col: String, set: Set<String>) {
            if (set.isEmpty()) return
            sb.append("AND $col IN (${set.joinToString(",") { "?" }}) "); args.addAll(set)
        }
        if (f.types.isNotEmpty()) { // primary OR secondary type (a forklift injury is MEDICAL + VEHICLE)
            val ph = f.types.joinToString(",") { "?" }
            sb.append("AND (i.incident_type IN ($ph) OR i.id IN (SELECT incident_id FROM incident_secondary_types WHERE type IN ($ph))) "); args.addAll(f.types); args.addAll(f.types)
        }
        inClause("i.severity", f.severities); inClause("i.status", f.statuses)
        if (f.locationIds.isNotEmpty() || !f.locationText.isNullOrBlank()) {
            val parts = ArrayList<String>()
            if (f.locationIds.isNotEmpty()) { parts += "i.location_id IN (${f.locationIds.joinToString(",") { "?" }})"; args.addAll(f.locationIds) }
            if (!f.locationText.isNullOrBlank()) { parts += "i.location_text LIKE ?"; args.add("%${f.locationText}%") }
            sb.append("AND (${parts.joinToString(" OR ")}) ")
        }
        f.injuries?.let { sb.append("AND i.injuries = ? "); args.add(it) }
        f.fromMs?.let { sb.append("AND i.created_at >= ? "); args.add(it) }
        f.toMs?.let { sb.append("AND i.created_at <= ? "); args.add(it) }
        sb.append("ORDER BY i.created_at DESC LIMIT ?"); args.add(limit)
        return SimpleSQLiteQuery(sb.toString(), args.toTypedArray())
    }

    private fun toItem(r: IncidentListRow): IncidentListItem = IncidentListItem(
        r.id, r.refNo, r.title?.takeIf { it.isNotBlank() } ?: r.description?.take(60) ?: "Untitled incident", r.incidentType, r.severity,
        r.status, r.locationText, r.createdAt, r.emergencyFlag, r.snip,
    )

    private suspend fun loadStored(id: String): StoredIncident? {
        val e = dao.get(id) ?: return null
        return StoredIncident(
            toRow(e), dao.secondary(id).map { it.type },
            dao.persons(id).map { PersonRow(it.name, it.role, it.injured, it.injury, it.contact) },
            dao.equipment(id).map { it.name }, dao.actions(id).map { it.text },
        )
    }

    private suspend fun writeChildren(id: String, st: StoredIncident) {
        dao.clearSecondary(id); dao.clearPersons(id); dao.clearEquipment(id); dao.clearActions(id)
        dao.insertSecondary(st.secondaryTypes.distinct().map { SecondaryTypeEntity(id, it) })
        dao.insertPersons(st.persons.map { PersonEntity(incidentId = id, name = it.name, role = it.role, injured = it.injured, injury = it.injury, contact = it.contact) })
        dao.insertEquipment(st.equipment.map { EquipmentEntity(incidentId = id, name = it) })
        dao.insertActions(st.actionsTaken.mapIndexed { i, t -> ActionTakenEntity(incidentId = id, ord = i, text = t) })
    }

    private suspend fun reindexInTx(id: String) {
        val d = load(id) ?: return
        val rowid = dao.rowidOf(id) ?: return
        val loc = d.form.locationId?.let { lid -> registry.locations.firstOrNull { it.id == lid }?.display }
        val body = SearchBody.build(d.form, d.meta, tx, loc, d.turns.map { it.text }.filter { it.isNotBlank() }, d.recommendations.map { it.text })
        dao.deleteSearch(id) // FTS4 has no reliable upsert: replace the row
        dao.insertSearch(IncidentSearchEntity(rowid.toInt(), body, id))
    }

    private fun auditDiff(id: String, old: IncidentForm, new: IncidentForm, actor: Actor, now: Long): List<AuditEntity> {
        val out = ArrayList<AuditEntity>()
        for (k in (old.fields.keys + new.fields.keys)) {
            val o = old.value(k)?.toString(); val n = new.value(k)?.toString()
            if (o != n) out += AuditEntity(incidentId = id, at = now, actor = actor.tag, action = "FIELD_SET", field = k, oldValue = o, newValue = n)
        }
        return out
    }

    private fun metaOf(r: IncidentRow) = IncidentMeta(
        r.id, runCatching { IncidentStatus.valueOf(r.status) }.getOrDefault(IncidentStatus.DRAFT), r.refNo,
        Reporter(r.reporterName.orEmpty(), r.reporterEmpId.orEmpty()), r.createdAt, r.reportedAt, r.submittedAt, r.emergencyFlag,
        r.followupRounds, r.modelVersionsJson, r.responseStartedAt, r.parentIncidentId,
    )

    private fun toRow(e: IncidentEntity) = IncidentRow(
        e.id, e.refNo, e.status, e.incidentType, e.title, e.description, e.severity, e.severitySource, e.locationText, e.locationId,
        e.occurredAt, e.occurredAtApprox, e.timeExpression, e.injuries, e.injuredCount, e.nearMiss, e.hazardOngoing, e.detailsJson,
        e.fieldMetaJson, e.otherIncidentsJson, e.lang, e.reporterName, e.reporterEmpId, e.emergencyFlag, e.followupRounds, e.createdAt,
        e.updatedAt, e.reportedAt, e.submittedAt, e.modelVersionsJson, e.responseStartedAt, e.parentIncidentId, e.evidenceJson,
    )

    private fun toEntity(st: StoredIncident, rowid: Long): IncidentEntity {
        val r = st.row
        return IncidentEntity(
            rowid, r.id, r.refNo, r.status, r.incidentType, r.title, r.description, r.severity, r.severitySource, r.locationText,
            r.locationId, r.occurredAt, r.occurredAtApprox, r.timeExpression, r.injuries, r.injuredCount, r.nearMiss, r.hazardOngoing,
            r.detailsJson, r.fieldMetaJson, r.otherIncidentsJson, r.lang, r.reporterName, r.reporterEmpId, r.emergencyFlag,
            r.followupRounds, r.createdAt, r.updatedAt, r.reportedAt, r.submittedAt, r.modelVersionsJson, responseStartedAt = r.responseStartedAt, parentIncidentId = r.parentIncidentId, evidenceJson = r.evidenceJson,
        )
    }

    private companion object { const val MERGE_RESOLVE = "MERGE_RESOLVE" }
}
