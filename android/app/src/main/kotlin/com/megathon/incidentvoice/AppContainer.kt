package com.megathon.incidentvoice

import android.content.Context
import com.megathon.incidentvoice.config.AppConfigBundle
import com.megathon.incidentvoice.config.ConfigLoader
import com.megathon.incidentvoice.data.IncidentRepository
import com.megathon.incidentvoice.data.RoomIncidentRepository
import com.megathon.incidentvoice.data.SettingsStore
import com.megathon.incidentvoice.data.db.IncidentDb
import java.io.File

/** Hand-written DI (PRD §20.1: no Hilt). */
class AppContainer(val context: Context) {
    val config: AppConfigBundle by lazy { ConfigLoader.load(context) }
    val db: IncidentDb by lazy { IncidentDb.open(context) }
    val audioRoot: File by lazy { File(context.filesDir, "audio").also { it.mkdirs() } }
    val repository: IncidentRepository by lazy { RoomIncidentRepository(db, config.taxonomy, config.registry, audioRoot) }
    val settings: SettingsStore by lazy { SettingsStore(context) }
}
