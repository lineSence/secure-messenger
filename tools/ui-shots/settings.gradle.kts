// Рендер экранов приложения без телефона (правило [UI-RENDER-CHECK], ADR-148): в APK не входит; verify выполняется в Android CI.
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "ui-shots"
include(":lib")
