package com.megathon.incidentvoice.data.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import com.megathon.incidentvoice.data.entity.*
import kotlinx.coroutines.flow.Flow

data class VectorHashRow(@ColumnInfo(name = "incident_id") val incidentId: String, @ColumnInfo(name = "text_hash") val textHash: String)

/** Row returned by the dashboard list queries. */
data class IncidentListRow(
    val id: String,
    @ColumnInfo(name = "ref_no") val refNo: String?,
    val title: String?,
    val description: String?,
    @ColumnInfo(name = "incident_type") val incidentType: String?,
    val severity: String?,
    val status: String,
    @ColumnInfo(name = "location_text") val locationText: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long?,
    @ColumnInfo(name = "emergency_flag") val emergencyFlag: Boolean,
    val snip: String? = null,
    @ColumnInfo(name = "parent_incident_id") val parentIncidentId: String? = null,
)

data class ChildCountRow(val pid: String, val n: Int)

@Dao
interface IncidentDao {
    @Query("SELECT rowid_pk FROM incidents WHERE id = :id") suspend fun rowidOf(id: String): Long?
    @Upsert suspend fun upsert(e: IncidentEntity): Long
    @Query("SELECT * FROM incidents WHERE id = :id") suspend fun get(id: String): IncidentEntity?
    @Query("SELECT * FROM incidents WHERE id = :id") fun observe(id: String): Flow<IncidentEntity?>
    @Query("DELETE FROM incidents WHERE id = :id") suspend fun delete(id: String)

    @Query("UPDATE incidents SET status = :status, ref_no = :ref, submitted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun markSubmitted(id: String, status: String, ref: String, at: Long)
    @Query("UPDATE incidents SET status = :status, updated_at = :at WHERE id = :id")
    suspend fun setStatus(id: String, status: String, at: Long)
    @Query("SELECT COUNT(*) FROM incidents WHERE ref_no LIKE :prefix || '%'") suspend fun countRefs(prefix: String): Int

    @Query("SELECT id FROM incidents WHERE status = 'DRAFT' AND updated_at >= :since ORDER BY updated_at DESC LIMIT 1")
    suspend fun latestDraftSince(since: Long): String?
    @Query("SELECT id FROM incidents WHERE status = 'DRAFT' AND updated_at < :before") suspend fun oldDraftIds(before: Long): List<String>

    // children
    @Query("DELETE FROM incident_secondary_types WHERE incident_id = :id") suspend fun clearSecondary(id: String)
    @Query("DELETE FROM persons WHERE incident_id = :id") suspend fun clearPersons(id: String)
    @Query("DELETE FROM equipment WHERE incident_id = :id") suspend fun clearEquipment(id: String)
    @Query("DELETE FROM actions_taken WHERE incident_id = :id") suspend fun clearActions(id: String)
    @Insert suspend fun insertSecondary(l: List<SecondaryTypeEntity>)
    @Insert suspend fun insertPersons(l: List<PersonEntity>)
    @Insert suspend fun insertEquipment(l: List<EquipmentEntity>)
    @Insert suspend fun insertActions(l: List<ActionTakenEntity>)
    @Query("SELECT * FROM incident_secondary_types WHERE incident_id = :id") suspend fun secondary(id: String): List<SecondaryTypeEntity>
    @Query("SELECT * FROM persons WHERE incident_id = :id ORDER BY id") suspend fun persons(id: String): List<PersonEntity>
    @Query("SELECT * FROM equipment WHERE incident_id = :id ORDER BY id") suspend fun equipment(id: String): List<EquipmentEntity>
    @Query("SELECT * FROM actions_taken WHERE incident_id = :id ORDER BY ord") suspend fun actions(id: String): List<ActionTakenEntity>

    // recommendations
    @Query("DELETE FROM recommendations WHERE incident_id = :id") suspend fun clearRecs(id: String)
    @Insert suspend fun insertRecs(l: List<RecommendationEntity>)
    @Query("SELECT * FROM recommendations WHERE incident_id = :id ORDER BY ord") suspend fun recs(id: String): List<RecommendationEntity>
    @Query("SELECT * FROM recommendations WHERE incident_id = :id ORDER BY ord") fun observeRecs(id: String): Flow<List<RecommendationEntity>>
    @Query("UPDATE recommendations SET done = :done WHERE id = :recId") suspend fun setRecDone(recId: Long, done: Boolean)

    // turns
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTurn(t: TurnEntity): Long
    @Query("SELECT COALESCE(MAX(idx), -1) + 1 FROM turns WHERE incident_id = :id") suspend fun nextTurnIdx(id: String): Int
    @Query("SELECT * FROM turns WHERE incident_id = :id ORDER BY idx") suspend fun turns(id: String): List<TurnEntity>
    @Query("SELECT * FROM turns WHERE incident_id = :id ORDER BY idx") fun observeTurns(id: String): Flow<List<TurnEntity>>

    // audit
    @Insert suspend fun insertAudit(a: List<AuditEntity>)
    @Query("SELECT * FROM audit_log WHERE incident_id = :id ORDER BY at") suspend fun audit(id: String): List<AuditEntity>

    // search index
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSearch(s: IncidentSearchEntity)
    @Query("DELETE FROM incident_search WHERE incident_id = :id") suspend fun deleteSearch(id: String)

    // response clock (Extra 1)
    @Query("UPDATE incidents SET response_started_at = :at WHERE id = :id AND response_started_at IS NULL") suspend fun setResponseStart(id: String, at: Long)
    @Insert suspend fun insertResponseAction(a: ResponseActionEntity)
    @Query("SELECT * FROM response_actions WHERE incident_id = :id ORDER BY done_at") suspend fun responseActions(id: String): List<ResponseActionEntity>
    @Query("SELECT * FROM response_actions WHERE incident_id = :id ORDER BY done_at") fun observeResponseActions(id: String): Flow<List<ResponseActionEntity>>
    @Query("DELETE FROM response_actions WHERE incident_id = :id") suspend fun deleteResponseActions(id: String)
    @Query("SELECT id FROM incidents WHERE status = 'DRAFT' AND response_started_at >= :since ORDER BY response_started_at DESC LIMIT 1") suspend fun activeResponseDraft(since: Long): String?

    @Query("UPDATE incidents SET reporter_name = :name, reporter_emp_id = :emp, updated_at = :at WHERE id = :id") suspend fun setReporter(id: String, name: String, emp: String, at: Long)

    // multi-witness merging (Extra 3)
    @Query("UPDATE incidents SET parent_incident_id = :parent, updated_at = :at WHERE id = :id") suspend fun setParent(id: String, parent: String?, at: Long)
    @Query("UPDATE incidents SET parent_incident_id = NULL WHERE parent_incident_id = :parent") suspend fun orphanChildren(parent: String)
    @Query("UPDATE incidents SET updated_at = :at WHERE id = :id") suspend fun touch(id: String, at: Long)
    @Query("SELECT id FROM incidents WHERE parent_incident_id = :parent AND status != 'DRAFT' ORDER BY submitted_at") suspend fun childIds(parent: String): List<String>
    @Query("SELECT parent_incident_id AS pid, COUNT(*) AS n FROM incidents WHERE parent_incident_id IS NOT NULL AND status != 'DRAFT' GROUP BY parent_incident_id") suspend fun childCounts(): List<ChildCountRow>
    @Query("SELECT id FROM incidents WHERE status != 'DRAFT' AND submitted_at >= :since ORDER BY submitted_at DESC LIMIT :limit") suspend fun submittedSince(since: Long, limit: Int): List<String>

    // vector index
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertVector(v: IncidentVectorEntity)
    @Query("SELECT * FROM incident_vectors WHERE model = :model") suspend fun vectors(model: String): List<IncidentVectorEntity>
    @Query("SELECT incident_id, text_hash FROM incident_vectors WHERE model = :model") suspend fun vectorHashes(model: String): List<VectorHashRow>
    @Query("DELETE FROM incident_vectors WHERE incident_id = :id") suspend fun deleteVector(id: String)
    @Query("SELECT id FROM incidents") suspend fun allIds(): List<String>
    @Query("SELECT MAX(updated_at) FROM incidents") fun observeMaxUpdated(): Flow<Long?>
    @RawQuery suspend fun listOnce(q: SupportSQLiteQuery): List<IncidentListRow>
    @Query("SELECT incident_id FROM incident_search WHERE incident_search MATCH :q") suspend fun searchIds(q: String): List<String>

    // dashboard (RawQuery for dynamic filters; observe both tables so the list refreshes on any change)
    @RawQuery(observedEntities = [IncidentEntity::class, IncidentSearchEntity::class, AuditEntity::class])
    fun observeList(q: SupportSQLiteQuery): Flow<List<IncidentListRow>>

    // counts (plain-text count row, rev 1.1 P0)
    @Query("SELECT COUNT(*) FROM incidents WHERE status != 'DRAFT'") fun observeTotal(): Flow<Int>
    @Query("SELECT COUNT(*) FROM incidents WHERE status != 'DRAFT' AND severity = 'CRITICAL'") fun observeCritical(): Flow<Int>
    @Query("SELECT COUNT(*) FROM incidents WHERE status != 'DRAFT' AND created_at >= :since") fun observeSince(since: Long): Flow<Int>
}
