package com.megathon.incidentvoice.config

import android.content.Context
import com.megathon.incidentvoice.core.*

object ConfigLoader {
    fun load(context: Context): AppConfigBundle {
        fun read(path: String) = context.assets.open("config/$path").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return AppConfigBundle(
            Taxonomy.parse(read("taxonomy.json")),
            SiteRegistry.parse(read("site_registry.json")),
            SopLibrary.parse(read("sop.json")),
            PromptTemplates.load { read(it) },
            AppConfig.parse(read("app_config.json")),
        )
    }
}

/**
 * On-device model directory (PRD §20.10): `getExternalFilesDir(null)/models` =
 * `/sdcard/Android/data/com.megathon.incidentvoice/files/models/{llama,whisper,dsp}`. The runtime value is
 * `AppContainer.modelsDir`; this constant is for documentation and adb scripts.
 */
object ModelPaths {
    const val BASE = "/sdcard/Android/data/com.megathon.incidentvoice/files/models"
}
