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

// :core = pure-Kotlin domain library (validator, parser, prompt builder, normalisers, SOPs).
// The App team adds include(":app") (and AGP in the root build file) on their branch.
include(":core")
