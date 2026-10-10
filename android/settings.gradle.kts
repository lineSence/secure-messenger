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
