// Root build file. Plugins are declared here with `apply false` and applied per module.
// The App team adds e.g. id("com.android.application") version "8.x" apply false and
// id("org.jetbrains.kotlin.android") version "2.0.21" apply false when :app lands.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
