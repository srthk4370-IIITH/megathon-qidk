package com.megathon.incidentvoice.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

// Mirrors PRD Appendix E. CHECK constraints are enforced in Kotlin (Room cannot declare them).

@Entity(
    tableName = "incidents",
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["ref_no"], unique = true),
        Index(value = ["status", "created_at"], name = "idx_incidents_status_created"),
        Index(value = ["incident_type"], name = "idx_incidents_type"),
        Index(value = ["severity"], name = "idx_incidents_severity"),
        Index(value = ["occurred_at"], name = "idx_incidents_occurred"),
        Index(value = ["parent_incident_id"], name = "idx_incidents_parent"),
    ],
)
data class IncidentEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid_pk") val rowidPk: Long = 0,
    val id: String,
    @ColumnInfo(name = "ref_no") val refNo: String?,
    val status: String,
    @ColumnInfo(name = "incident_type") val incidentType: String?,
    val title: String?,
    val description: String?,
    val severity: String?,
    @ColumnInfo(name = "severity_source") val severitySource: String?,
    @ColumnInfo(name = "location_text") val locationText: String?,
    @ColumnInfo(name = "location_id") val locationId: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long?,
    @ColumnInfo(name = "occurred_at_approx") val occurredAtApprox: Boolean,
    @ColumnInfo(name = "time_expression") val timeExpression: String?,
    val injuries: String?,
    @ColumnInfo(name = "injured_count") val injuredCount: Int?,
    @ColumnInfo(name = "near_miss") val nearMiss: Boolean,
    @ColumnInfo(name = "hazard_ongoing") val hazardOngoing: String?,
    @ColumnInfo(name = "details_json") val detailsJson: String,
    @ColumnInfo(name = "field_meta_json") val fieldMetaJson: String,
    @ColumnInfo(name = "other_incidents_json") val otherIncidentsJson: String,
    val lang: String?,
    @ColumnInfo(name = "reporter_name") val reporterName: String?,
    @ColumnInfo(name = "reporter_emp_id") val reporterEmpId: String?,
    @ColumnInfo(name = "emergency_flag") val emergencyFlag: Boolean,
    @ColumnInfo(name = "followup_rounds") val followupRounds: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "reported_at") val reportedAt: Long,
    @ColumnInfo(name = "submitted_at") val submittedAt: Long?,
    @ColumnInfo(name = "model_versions_json") val modelVersionsJson: String,
    @ColumnInfo(name = "sync_state") val syncState: String = "LOCAL_ONLY",
    /** Extra 1 (DB v3): when the response clock started; null = never. */
    @ColumnInfo(name = "response_started_at") val responseStartedAt: Long? = null,
    /** Extra 3 (DB v4): the report this one is a further witness account of; null = stands alone. */
    @ColumnInfo(name = "parent_incident_id") val parentIncidentId: String? = null,
)

private const val FK = "incident_id"

@Entity(
    tableName = "incident_secondary_types", primaryKeys = ["incident_id", "type"],
    foreignKeys = [ForeignKey(IncidentEntity::class, ["id"], [FK], onDelete = ForeignKey.CASCADE)],
)
data class SecondaryTypeEntity(@ColumnInfo(name = "incident_id") val incidentId: String, val type: String)

@Entity(
    tableName = "persons", indices = [Index(value = [FK], name = "idx_persons_incident")],
    foreignKeys = [ForeignKey(IncidentEntity::class, ["id"], [FK], onDelete = ForeignKey.CASCADE)],
)
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "incident_id") val incidentId: String,
    val name: String?, val role: String, val injured: Boolean, val injury: String?, val contact: String?,
)

@Entity(
    tableName = "equipment", indices = [Index(value = [FK], name = "idx_equipment_incident")],
    foreignKeys = [ForeignKey(IncidentEntity::class, ["id"], [FK], onDelete = ForeignKey.CASCADE)],
)
data class EquipmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "incident_id") val incidentId: String,
    val name: String,
    @ColumnInfo(name = "asset_tag") val assetTag: String? = null,
)

@Entity(
    tableName = "actions_taken", indices = [Index(value = [FK])],
    foreignKeys = [ForeignKey(IncidentEntity::class, ["id"], [FK], onDelete = ForeignKey.CASCADE)],
)
data class ActionTakenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "incident_id") val incidentId: String,
    val ord: Int, val text: String,
)

@Entity(
    tableName = "recommendations", indices = [Index(value = [FK], name = "idx_recs_incident")],
    foreignKeys = [ForeignKey(IncidentEntity::class, ["id"], [FK], onDelete = ForeignKey.CASCADE)],
)
data class RecommendationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "incident_id") val incidentId: String,
    val ord: Int,
    @ColumnInfo(name = "sop_id") val sopId: String,
    val text: String,
    val source: String,
    val immediate: Boolean,
    val done: Boolean,
)

@Entity(
    tableName = "turns", indices = [Index(value = [FK, "idx"], unique = true)],
    foreignKeys = [ForeignKey(IncidentEntity::class, ["id"], [FK], onDelete = ForeignKey.CASCADE)],
)
data class TurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "incident_id") val incidentId: String,
    val idx: Int, val role: String, val kind: String,
    @ColumnInfo(name = "asked_fields_json") val askedFieldsJson: String?,
    @ColumnInfo(name = "text_raw") val textRaw: String?,
    @ColumnInfo(name = "text_edited") val textEdited: String?,
    val lang: String?,
    @ColumnInfo(name = "audio_path") val audioPath: String?,
    @ColumnInfo(name = "audio_ms") val audioMs: Long?,
    @ColumnInfo(name = "asr_ms") val asrMs: Long?,
    @ColumnInfo(name = "llm_ms") val llmMs: Long?,
    @ColumnInfo(name = "llm_tool_json") val llmToolJson: String?,
    @ColumnInfo(name = "llm_stats_json") val llmStatsJson: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** No FK on purpose: audit entries survive deletion (App. E). */
@Entity(tableName = "audit_log", indices = [Index(value = ["incident_id", "at"], name = "idx_audit_incident")])
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "incident_id") val incidentId: String,
    val at: Long, val actor: String, val action: String,
    val field: String?,
    @ColumnInfo(name = "old_value") val oldValue: String?,
    @ColumnInfo(name = "new_value") val newValue: String?,
)

/** Extra 1 (DB v3): a response-clock step that was carried out, with its time. Survives report deletion like the audit log does not need to. */
@Entity(tableName = "response_actions", indices = [Index(value = ["incident_id"], name = "idx_response_incident")])
data class ResponseActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "incident_id") val incidentId: String,
    @ColumnInfo(name = "step_id") val stepId: String,
    val label: String,
    @ColumnInfo(name = "done_at") val doneAt: Long,
    val source: String,
)

/**
 * Vector index (PRD §37 O-2): one embedding per incident, stored as little-endian float32 in the same SQLite file.
 * Similarity is a brute-force dot product in Kotlin (unit vectors); at this scale (thousands × 384) that is ~1 ms and
 * needs no SQLite extension, which the platform SQLite on the board cannot load anyway.
 */
@Entity(tableName = "incident_vectors")
data class IncidentVectorEntity(
    @PrimaryKey @ColumnInfo(name = "incident_id") val incidentId: String,
    val model: String,
    val dim: Int,
    @ColumnInfo(name = "text_hash") val textHash: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vec: ByteArray,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** R1-07: platform-SQLite FTS4. rowid = incidents.rowid_pk. */
@Fts4
@Entity(tableName = "incident_search")
data class IncidentSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int,
    val body: String,
    @ColumnInfo(name = "incident_id") val incidentId: String,
)
