pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
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
    // The App team adds include(":app") here.
}
