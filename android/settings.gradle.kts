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

// :core   = ML branch's pure-Kotlin library (validator, parser, prompt builder, …) + golden prompt tests.
// :domain = App branch's pure-Kotlin domain layer (form model, validator, merger, prompts, orchestrator state machine).
// Both are JVM-only and always built.
include(":core", ":domain")

// Android modules need the Android SDK. They are included when -PwithAndroid=true is passed, or automatically when an
// SDK is configured (local.properties or ANDROID_HOME), so a JVM-only machine can still run `gradle :core:test :domain:test`.
val withAndroid = providers.gradleProperty("withAndroid").orNull == "true" ||
    file("local.properties").exists() || System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
if (withAndroid) {
    include(":engine") // ML branch: InferenceEngine + JNI/HTTP engines + prebuilt native libs
    include(":app")    // App branch: Compose UI, Room, audio, TTS
}
