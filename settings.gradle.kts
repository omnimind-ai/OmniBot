pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // Prefer project repositories to avoid warnings when Flutter and other plugins add repositories.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    val storageUrl: String = System.getenv("FLUTTER_STORAGE_BASE_URL") ?: "https://storage.googleapis.com"

    repositories {
        maven("https://maven.google.com/")
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("$storageUrl/download.flutter.io")
        maven("https://repo1.maven.org/maven2/")

    }
}

rootProject.name = "OmnibotApp"
include(":app")
include(":accessibility")
include(":assists")
include(":androidgui")
include(":omniflow-android")
val filePath = settingsDir.toString() + "/ui/.android/include_flutter.groovy"
apply(from = File(filePath))

include(":baselib")
include(":uikit")
include(":core:main")
project(":core:main").projectDir = File(settingsDir, "ReTerminal/core/main")
include(":core:components")
project(":core:components").projectDir = File(settingsDir, "ReTerminal/core/components")
include(":core:resources")
project(":core:resources").projectDir = File(settingsDir, "ReTerminal/core/resources")
include(":core:terminal-emulator")
project(":core:terminal-emulator").projectDir = File(settingsDir, "ReTerminal/core/terminal-emulator")
include(":core:terminal-view")
project(":core:terminal-view").projectDir = File(settingsDir, "ReTerminal/core/terminal-view")
