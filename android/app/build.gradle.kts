plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Версия — единственный источник; она же в имени APK: uncensored-<версия>-<тип>.apk (ADR-78).
val appVersion = "0.0.1-alpha"

base { archivesName = "uncensored-$appVersion" }

android {
    namespace = "dev.sm.messenger"
    compileSdk = 37 // Compose BOM 2026.09 требует API 37+

    defaultConfig {
        applicationId = "app.uncensored" // ADR-77: до выдачи людям; namespace (пакет кода) прежний
        minSdk = 31 // Android 12 (ADR-45)
        targetSdk = 36
        versionCode = 1
        versionName = appVersion
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
    // QR (ADR-62 п.1): показ и распознавание — ZXing core, камера — CameraX; без Google Play services и ML Kit.
    implementation("com.google.zxing:core:3.5.4")
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // UniFFI вызывает ядро через JNA.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    // Argon2id для кода к ссылке-приглашению (ADR-142): обёртка Signal над эталонной реализацией PHC, GPL-3.0.
    implementation("org.signal:argon2:13.1@aar")
    // Тесты JVM логики без Android и ядра (ADR-79 п.1); JUnit 4 — без новых издателей ключей.
    testImplementation("junit:junit:4.13.2")
}
