package com.megathon.incidentvoice.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase.Callback
import com.megathon.incidentvoice.data.dao.IncidentDao
import com.megathon.incidentvoice.data.entity.*

@Database(
    entities = [
        IncidentEntity::class, SecondaryTypeEntity::class, PersonEntity::class, EquipmentEntity::class, ActionTakenEntity::class,
        RecommendationEntity::class, TurnEntity::class, AuditEntity::class, IncidentSearchEntity::class, IncidentVectorEntity::class, ResponseActionEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class IncidentDb : RoomDatabase() {
    abstract fun dao(): IncidentDao

    companion object {
        fun open(context: Context): IncidentDb =
            Room.databaseBuilder(context, IncidentDb::class.java, "incidents.db")
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) { db.execSQL("PRAGMA foreign_keys = ON") }
                })
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()

        /** v2 adds the vector index table (O-2). Existing reports are kept; their vectors are computed on next start. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `incident_vectors` (`incident_id` TEXT NOT NULL, `model` TEXT NOT NULL, `dim` INTEGER NOT NULL, `text_hash` TEXT NOT NULL, `vec` BLOB NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`incident_id`))")
            }
        }

        /** v3 (Extra 1, response clock): `incidents.response_started_at` + table `response_actions`. Not in PRD Appendix E. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `incidents` ADD COLUMN `response_started_at` INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS `response_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `incident_id` TEXT NOT NULL, `step_id` TEXT NOT NULL, `label` TEXT NOT NULL, `done_at` INTEGER NOT NULL, `source` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_response_incident` ON `response_actions` (`incident_id`)")
            }
        }

        /** v4 (Extra 3, multi-witness merging): `incidents.parent_incident_id`. Not in PRD Appendix E. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `incidents` ADD COLUMN `parent_incident_id` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_incidents_parent` ON `incidents` (`parent_incident_id`)")
            }
        }

        /** v5 ("show your evidence"): `incidents.evidence_json`. Existing reports keep NULL and get their spans computed when opened. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `incidents` ADD COLUMN `evidence_json` TEXT")
            }
        }

        /** v6 (repeat incidents and hotspots): `incidents.is_demo` marks seeded demo rows, `incidents.tags_json` keeps the tags computed on save. */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `incidents` ADD COLUMN `is_demo` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `incidents` ADD COLUMN `tags_json` TEXT")
            }
        }

        fun inMemory(context: Context): IncidentDb = Room.inMemoryDatabaseBuilder(context, IncidentDb::class.java).build()
    }
}
