import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Версия — единственный источник; она же в имени APK: uncensored-<версия>-<тип>.apk (ADR-78).
val appVersion = "0.0.1-alpha"

base { archivesName = "uncensored-$appVersion" }

// Ключ debug хранится в git текстом (base64) и перед сборкой распаковывается в build/ (ADR-147).
val debugKeystoreFile = layout.buildDirectory.file("debug-signing/debug.p12")
val decodeDebugKeystore = tasks.register("decodeDebugKeystore") {
    val source = layout.projectDirectory.file("debug-signing.p12.b64")
    val target = debugKeystoreFile
    inputs.file(source)
    outputs.file(target)
    doLast {
        val bytes = Base64.getMimeDecoder().decode(source.asFile.readText())
        target.get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }
}
tasks.configureEach {
    if (name == "preBuild" || name == "validateSigningDebug") dependsOn(decodeDebugKeystore)
}

// ADR-147: versionCode debug растёт с номером запуска CI; сборка с меньшим номером поверх не встанет.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        variant.outputs.forEach { it.versionCode.set(runNumber) }
    }
}

android {
    namespace = "dev.sm.messenger"
    compileSdk = 37 // Compose BOM 2026.09 требует API 37+

    defaultConfig {
        applicationId = "app.uncensored" // ADR-77: до выдачи людям; namespace (пакет кода) прежний
        minSdk = 31 // Android 12 (ADR-45)
        targetSdk = 36
        // ADR-78: +1 с каждым выпуском (0.0.1-alpha = 1, 0.0.2-alpha = 2), иначе обновление поверх не встанет.
        // Для debug код задаётся ниже из номера запуска CI (ADR-147).
        versionCode = 2
        versionName = appVersion
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // ADR-147: постоянный публичный ключ только для debug — обновления debug встают поверх друг друга.
    // Release подписывает release.yml секретным ключом; этот ключ к нему не относится.
    signingConfigs {
        getByName("debug") {
            storeFile = debugKeystoreFile.get().asFile
            storePassword = "android"
            keyAlias = "uncensoreddebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // ADR-147: другой пакет — debug и release стоят на телефоне рядом (данные раздельные).
            applicationIdSuffix = ".debug"
        }
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
