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
        RecommendationEntity::class, TurnEntity::class, AuditEntity::class, IncidentSearchEntity::class,
    ],
    version = 1,
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
                .build()

        fun inMemory(context: Context): IncidentDb = Room.inMemoryDatabaseBuilder(context, IncidentDb::class.java).build()
    }
}
