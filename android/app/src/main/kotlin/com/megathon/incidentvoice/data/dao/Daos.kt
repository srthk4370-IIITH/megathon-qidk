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
)

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
    @Upsert suspend fun upsertSearch(s: IncidentSearchEntity)
    @Query("DELETE FROM incident_search WHERE incident_id = :id") suspend fun deleteSearch(id: String)

    // dashboard (RawQuery for dynamic filters; observe both tables so the list refreshes on any change)
    @RawQuery(observedEntities = [IncidentEntity::class, IncidentSearchEntity::class])
    fun observeList(q: SupportSQLiteQuery): Flow<List<IncidentListRow>>

    // counts (plain-text count row, rev 1.1 P0)
    @Query("SELECT COUNT(*) FROM incidents WHERE status != 'DRAFT'") fun observeTotal(): Flow<Int>
    @Query("SELECT COUNT(*) FROM incidents WHERE status != 'DRAFT' AND severity = 'CRITICAL'") fun observeCritical(): Flow<Int>
    @Query("SELECT COUNT(*) FROM incidents WHERE status != 'DRAFT' AND created_at >= :since") fun observeSince(since: Long): Flow<Int>
}
