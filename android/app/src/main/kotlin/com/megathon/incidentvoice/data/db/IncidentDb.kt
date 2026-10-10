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
        RecommendationEntity::class, TurnEntity::class, AuditEntity::class, IncidentSearchEntity::class, IncidentVectorEntity::class,
    ],
    version = 2,
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
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2 adds the vector index table (O-2). Existing reports are kept; their vectors are computed on next start. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `incident_vectors` (`incident_id` TEXT NOT NULL, `model` TEXT NOT NULL, `dim` INTEGER NOT NULL, `text_hash` TEXT NOT NULL, `vec` BLOB NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`incident_id`))")
            }
        }

        fun inMemory(context: Context): IncidentDb = Room.inMemoryDatabaseBuilder(context, IncidentDb::class.java).build()
    }
}
