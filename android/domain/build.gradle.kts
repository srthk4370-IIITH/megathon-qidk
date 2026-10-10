plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation(project(":core")) // ML branch's implementation, for differential tests only
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    // tests read the shared contracts straight from <repo>/config
    systemProperty("iv.configDir", rootProject.projectDir.parentFile.resolve("config").absolutePath)
}
