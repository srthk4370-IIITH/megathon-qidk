package com.megathon.incidentvoice.config

import com.megathon.incidentvoice.core.*

/** Everything read from assets/config (copied from <repo>/config by the `copyConfig` Gradle task). */
class AppConfigBundle(
    val taxonomy: Taxonomy,
    val registry: SiteRegistry,
    val sop: SopLibrary,
    val templates: PromptTemplates,
    val app: AppConfig,
)

