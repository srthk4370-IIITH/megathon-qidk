package com.megathon.incidentvoice.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.megathon.incidentvoice.core.Reporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val reporterName: String = "",
    val reporterEmpId: String = "",
    val ertNumber: String = "112",
    val vadEndSilenceMs: Int = 1200,
    val speakQuestions: Boolean = true,
    val showDebug: Boolean = false,
    val ttsEngine: String = "com.reecedunn.espeak",
    val useMic: Boolean = true,
) { val reporter get() = Reporter(reporterName, reporterEmpId) }

class SettingsStore(private val context: Context) {
    private object K {
        val name = stringPreferencesKey("reporter_name"); val emp = stringPreferencesKey("reporter_emp")
        val ert = stringPreferencesKey("ert"); val vad = intPreferencesKey("vad_end_ms")
        val useMic = booleanPreferencesKey("use_mic"); val speak = booleanPreferencesKey("speak"); val debug = booleanPreferencesKey("debug"); val tts = stringPreferencesKey("tts")
    }
    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(p[K.name].orEmpty(), p[K.emp].orEmpty(), p[K.ert] ?: "112", p[K.vad] ?: 1200, p[K.speak] ?: true, p[K.debug] ?: false,
            p[K.tts] ?: "com.reecedunn.espeak", p[K.useMic] ?: true)
    }
    suspend fun update(f: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val cur = AppSettings(p[K.name].orEmpty(), p[K.emp].orEmpty(), p[K.ert] ?: "112", p[K.vad] ?: 1200, p[K.speak] ?: true, p[K.debug] ?: false,
                p[K.tts] ?: "com.reecedunn.espeak", p[K.useMic] ?: true)
            val n = f(cur)
            p[K.name] = n.reporterName; p[K.emp] = n.reporterEmpId; p[K.ert] = n.ertNumber; p[K.vad] = n.vadEndSilenceMs
            p[K.speak] = n.speakQuestions; p[K.debug] = n.showDebug; p[K.tts] = n.ttsEngine; p[K.useMic] = n.useMic
        }
    }
}
