// :engine — on-device inference API for the app (ML branch). Ships prebuilt native libs in
// src/main/jniLibs/arm64-v8a (built by native/build_android.sh), so app builders need no QAIRT/NDK.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.megathon.incidentvoice.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        ndk { abiFilters += "arm64-v8a" }
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs { useLegacyPackaging = true } // extract .so to nativeLibraryDir (stubs are found via ADSP_LIBRARY_PATH)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
