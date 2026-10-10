// Android-клиент (ADR-45). Ядро на Rust подключается через UniFFI (ADR-46):
// scripts/build-android-core.sh собирает libsm_ffi.so и привязки Kotlin до запуска Gradle.
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
rootProject.name = "secure-messenger"
include(":app")

// Транзитивные зависимости инструментов сборки (AGP) с известными уязвимостями (OSV-Scanner в CI)
// поднимаются до исправленных версий — в classpath сборки и в служебных конфигурациях AGP
// (подпись, R8, aapt). В APK они не попадают. Убрать строку, когда AGP сам перейдёт на версию
// не ниже указанной.
val fixedBuildDeps = mapOf(
    "org.jetbrains.kotlin:kotlin-gradle-plugin" to "2.4.21", // AGP 9.4.1 тянет 2.2.10
    "org.bouncycastle:bcprov-jdk18on" to "1.86",
    "org.bouncycastle:bcpkix-jdk18on" to "1.86",
    "org.bouncycastle:bcutil-jdk18on" to "1.86",
    "org.apache.commons:commons-lang3" to "3.21.0",
    "org.apache.httpcomponents:httpclient" to "4.5.14",
    "org.bitbucket.b_c:jose4j" to "0.9.7",
    "org.jdom:jdom2" to "2.0.6.1",
)

fun Configuration.pinFixed() = resolutionStrategy.eachDependency {
    fixedBuildDeps["${requested.group}:${requested.name}"]?.let {
        useVersion(it)
        because("OSV: в версии ниже есть известная уязвимость")
    }
}

gradle.beforeProject {
    buildscript.configurations.configureEach { pinFixed() }
    configurations.configureEach { pinFixed() }
}
