plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.sm.messenger"
    compileSdk = 37 // Compose BOM 2026.09 требует API 37+

    defaultConfig {
        applicationId = "dev.sm.messenger"
        minSdk = 31 // Android 12 (ADR-45)
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // Привязки Kotlin и libsm_ffi.so кладёт scripts/build-android-core.sh (в git не хранятся).
    sourceSets["main"].kotlin.directories += "build/generated/uniffi"
    sourceSets["main"].jniLibs.directories += "build/generated/jniLibs"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    // UniFFI вызывает ядро через JNA.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
}
