import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.kotlin.dsl.configure
import java.util.Base64

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // Make FlutterExtension visible to Kotlin DSL scripts of embedded plugins.
    id("dev.flutter.flutter-gradle-plugin") apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
}

val forcedCoroutinesVersion = libs.versions.kotlinxCoroutines.get()
val androidNdkVersion = providers.gradleProperty("OMNI_NDK_VERSION").getOrElse("28.2.13676358")

// Add-to-app has one shared Flutter library per Gradle invocation. Select its
// edition from the explicit Android variant, and reject mixed-edition builds.
val requestedCes = gradle.startParameter.taskNames.any { it.contains("JackeryCes", true) } ||
    providers.gradleProperty("target").orNull == "lib/main_jackery_ces.dart"
val suppliedDefines = providers.gradleProperty("dart-defines").orNull.orEmpty()
    .split(',').filter { it.isNotBlank() }.filterNot {
        runCatching { String(Base64.getDecoder().decode(it)) }.getOrDefault("")
            .startsWith("JACKERY_CES=")
    }
val editionDefine = Base64.getEncoder().encodeToString("JACKERY_CES=$requestedCes".toByteArray())
extra["dart-defines"] = (suppliedDefines + editionDefine).joinToString(",")
extra["target"] = if (requestedCes) "lib/main_jackery_ces.dart" else
    providers.gradleProperty("target").orNull ?: "lib/main_standard.dart"
gradle.taskGraph.whenReady {
    val appAssemblies = allTasks.filter { it.project.path == ":app" &&
        (it.name.startsWith("assemble") || it.name.startsWith("bundle")) }
    val editions = appAssemblies.mapNotNull {
        when {
            it.name.contains("JackeryCes", true) -> true
            it.name.contains("Standard", true) -> false
            else -> null
        }
    }.toSet()
    require(editions.size <= 1 && (editions.isEmpty() || editions.single() == requestedCes)) {
        "Build one edition at a time: assembleDevelopJackeryCesDebug or assembleDevelopStandardDebug."
    }
}

subprojects {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        val storageUrl: String =
            System.getenv("FLUTTER_STORAGE_BASE_URL") ?: "https://storage.googleapis.com"
        maven(url = "$storageUrl/download.flutter.io")
    }

    configurations.configureEach {
        resolutionStrategy.force(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:$forcedCoroutinesVersion",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:$forcedCoroutinesVersion",
        )
    }

    if (path == ":app") {
        plugins.withId("com.android.application") {
            extensions.configure<ApplicationExtension> {
                ndkVersion = androidNdkVersion
            }
        }
    }

    // Flutter's generated module still defaults to SDK 36, while the latest
    // Android dependencies in this host require SDK 37. Keep the generated
    // project out of source control and override it from the host build.
    if (path == ":flutter") {
        extra["dart-defines"] = rootProject.extra["dart-defines"]
        extra["target"] = rootProject.extra["target"]
        pluginManager.withPlugin("com.android.library") {
            extensions.configure<LibraryAndroidComponentsExtension> {
                finalizeDsl { extension ->
                    extension.compileSdk = 37
                }
            }
        }
    }
}
