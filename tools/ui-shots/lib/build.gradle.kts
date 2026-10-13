// Модуль собирает копию исходников приложения (build-in/src, где private снят — см. run.sh) и привязки ядра
// (build-in/uniffi) и рисует экраны в PNG через Robolectric (Native Graphics) и Roborazzi.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildIn = rootProject.layout.projectDirectory.dir("build-in")
val outDir = rootProject.layout.projectDirectory.dir("out")

android {
    namespace = "dev.sm.messenger"
    compileSdk = 37
    defaultConfig {
        applicationId = "shots.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.2-alpha"
        buildConfigField("String", "SOURCE_SHA", "\"unknown\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].kotlin.directories += buildIn.dir("src").asFile.path
    sourceSets["main"].kotlin.directories += buildIn.dir("uniffi").asFile.path
    sourceSets["main"].res.directories += buildIn.dir("res").asFile.path
    sourceSets["main"].manifest.srcFile(buildIn.file("AndroidManifest.xml").asFile.path)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("shots.out", outDir.asFile.path)
            }
        }
    }
}

dependencies {
    // Те же библиотеки и версии, что в android/app/build.gradle.kts.
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.google.zxing:core:3.5.4")
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("org.signal:argon2:13.1@aar")
    // Только для рендера (ADR-148).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
}
