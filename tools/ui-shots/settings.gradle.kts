// Рендер экранов приложения без телефона (правило [UI-RENDER-CHECK], ADR-148): только для разработки, в APK и CI не входит.
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "ui-shots"
include(":lib")
