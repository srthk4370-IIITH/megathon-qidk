plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.megathon.incidentvoice"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.megathon.incidentvoice"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    // One shared debug key (committed) so every laptop can `adb install -r` over another laptop's build.
    // Without it each machine signs with its own ~/.android/debug.keystore and the board rejects the update
    // (INSTALL_FAILED_UPDATE_INCOMPATIBLE) — and uninstalling wipes the model folder.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("debug") } }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // QNN stubs are located through ADSP_LIBRARY_PATH = nativeLibraryDir, so the .so files must be extracted (PRD §18.2)
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/config-assets"))
}

// Single source of truth: <repo>/config -> assets/config (PRD §20.10)
val copyConfig by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile.resolve("config")) {
        exclude("prompts/golden/**", "**/*.md") // test fixtures / docs are not needed on the device
    }
    into(layout.buildDirectory.dir("generated/config-assets/config"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyConfig) }

dependencies {
    implementation(project(":domain"))
    implementation(project(":engine")) // ML branch: JNI/HTTP engines + prebuilt arm64 native libs
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.19.2") // JVM natives so the embedder runs in unit tests
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.withType<Test>().configureEach {
    systemProperty("iv.configDir", rootProject.projectDir.parentFile.resolve("config").absolutePath)
    // optional offline Robolectric android-all jars (pre-downloaded with curl; the JVM downloader hangs on some networks)
    val jars = File(System.getProperty("user.home"), "tools/robolectric-jars")
    if (jars.isDirectory && (jars.listFiles()?.any { it.name.endsWith(".jar") } == true)) {
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", jars.absolutePath)
    }
}
