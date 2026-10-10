pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Versions for the plugins only :app applies (kept here so the root build.gradle.kts stays as the ML branch wrote it).
    plugins {
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
        id("com.google.devtools.ksp") version "2.0.21-1.0.28"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "IncidentVoice"

// :core = pure-Kotlin domain library (validator, parser, prompt builder, normalisers, SOPs). JVM-only, always built.
include(":core")

// Android modules need the Android SDK (local.properties / ANDROID_HOME). They are only included when the build is
// run with -PwithAndroid=true (or withAndroid=true in gradle.properties / ~/.gradle/gradle.properties), so a
// JVM-only machine can still run `gradle :core:test`.
val withAndroid = providers.gradleProperty("withAndroid").orNull == "true"
if (withAndroid) {
    include(":engine")
    // App branch: :domain = app's pure-Kotlin layer (form, validator, orchestrator; JVM tests), :app = Compose UI.
    include(":domain", ":app")
}
