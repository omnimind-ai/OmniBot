import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun prop(name: String): String = (project.findProperty(name) as String?)?.trim()
    ?: System.getenv(name)?.trim()
    ?: ""

fun buildConfigString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

val omnibotImageBaseUrl = prop("OMNIBOT_IMAGE_BASE_URL")
    .ifBlank { "https://cloud.omnimind.com.cn" }
val omnibotImageModel = prop("OMNIBOT_IMAGE_MODEL")
    .ifBlank { "gpt-image-2" }
val omnibotImageApiKey = prop("OMNIBOT_IMAGE_API_KEY")
val omnibotBaseUrl = prop("OMNIBOT_BASE_URL")
val appUpdateWorkerUrl = prop("OMNIBOT_UPDATE_WORKER_URL")
val llmThuApiBase = prop("LLMTHU_API_BASE")
    .ifBlank { "https://llmapi.paratera.com" }
val llmThuApiKey = prop("LLMTHU_API_KEY")
val llmThuModel = prop("LLMTHU_MODEL")
    .ifBlank { "GLM-5.1" }
val bundleLlmThuProvider = prop("OOB_BUNDLE_LLMTHU_PROVIDER") == "1"
val omnibotProfile = prop("OMNIBOT_PROFILE").ifBlank { "main" }
require(omnibotProfile in setOf("main", "investor")) {
    "OMNIBOT_PROFILE must be main or investor: $omnibotProfile"
}
val isInvestorProfile = omnibotProfile == "investor"
val preferPackagedOmniFlowRuntime =
    prop("OOB_PREFER_PACKAGED_OMNIFLOW_RUNTIME") == "1"
val omnibotAiGatewayUrl = prop("OMNIBOT_AI_GATEWAY_URL")
val resolvedOmnibotBaseUrl = omnibotBaseUrl
    .ifBlank { "https://account.omnimind.com.cn" }
val resolvedOmnibotAiGatewayUrl = omnibotAiGatewayUrl
    .ifBlank { "https://model-api.omnimind.com.cn" }

val webChatSourceDir = rootProject.file("webchat")
val webChatDistDir = File(webChatSourceDir, "dist")
val webChatAssetsRootDir = layout.buildDirectory.dir("generated/omnibot_assets").get().asFile
val webChatAssetsDir = File(webChatAssetsRootDir, "webchat")
val pluginSourceDir = rootProject.file("plugins")
val pluginCatalogFile = File(pluginSourceDir, "catalog.v1.json")
@Suppress("UNCHECKED_CAST")
val pluginCatalog = JsonSlurper().parse(pluginCatalogFile) as Map<String, Any?>
@Suppress("UNCHECKED_CAST")
val pluginCatalogEntries = pluginCatalog.getValue("plugins") as List<Map<String, Any?>>
val omniFlowCatalogEntry = pluginCatalogEntries.first {
    it["id"] == "com.omnimind.omni-vlm-lite"
}
@Suppress("UNCHECKED_CAST")
val omniFlowRuntimeSkill = omniFlowCatalogEntry.getValue("runtimeSkill") as Map<String, Any?>
val omniFlowPackagedArchivePath = omniFlowRuntimeSkill.getValue("packagedArchivePath").toString()
require(omniFlowPackagedArchivePath.startsWith("runtime-components/")) {
    "OmniFlow packagedArchivePath must be under runtime-components: $omniFlowPackagedArchivePath"
}
val omniFlowBaselineArchive = rootProject.file(
    "artifacts/${File(omniFlowPackagedArchivePath).name}",
)
val pluginAssetsRootDir = layout.buildDirectory.dir("generated/plugin_assets/$omnibotProfile")
    .get().asFile
val webChatPackageJson = File(webChatSourceDir, "package.json")
val webChatLockFile = File(webChatSourceDir, "pnpm-lock.yaml")
val webChatInstallMarker = File(webChatSourceDir, "node_modules/.modules.yaml")
val hostOs = System.getProperty("os.name").lowercase()

fun webChatPnpmCommand(arguments: String): List<String> = when {
    hostOs.contains("windows") -> listOf("cmd", "/c", "pnpm $arguments")
    hostOs.contains("mac") -> listOf("zsh", "-lc", "pnpm $arguments")
    else -> listOf("pnpm") + arguments.split(" ")
}

val installWebChatDependencies by tasks.registering(Exec::class) {
    group = "web chat"
    description = "Install the locked React WebChat build dependencies."
    workingDir(webChatSourceDir)
    commandLine(webChatPnpmCommand("install --frozen-lockfile"))
    inputs.files(webChatPackageJson, webChatLockFile)
    outputs.file(webChatInstallMarker)
}

val buildWebChatBundle by tasks.registering(Exec::class) {
    group = "web chat"
    description = "Build the React WebChat into a static Vite bundle."
    dependsOn(installWebChatDependencies)
    workingDir(webChatSourceDir)
    commandLine(webChatPnpmCommand("run build"))
    inputs.files(
        webChatPackageJson,
        webChatLockFile,
        File(webChatSourceDir, "tsconfig.json"),
        File(webChatSourceDir, "vite.config.ts"),
        File(webChatSourceDir, "index.html"),
        File(webChatSourceDir, "styles.css")
    )
    inputs.dir(File(webChatSourceDir, "src"))
    outputs.dir(webChatDistDir)
}

val syncWebChatBundle by tasks.registering(Copy::class) {
    group = "web chat"
    description = "Copy only the built React WebChat static files into Android assets."
    dependsOn(buildWebChatBundle)
    from(webChatDistDir)
    into(webChatAssetsDir)
    outputs.upToDateWhen { false }
    doFirst {
        // Always clear the dedicated generated root so an incremental build
        // cannot retain the removed Flutter Web/CanvasKit bundle.
        delete(webChatAssetsRootDir)
    }
}

val syncPluginAssets by tasks.registering(Sync::class) {
    group = "plugin packaging"
    description = "Generate the packaged plugin catalog for the selected build profile."
    inputs.file(pluginCatalogFile)
    inputs.file(omniFlowBaselineArchive)
    inputs.property("omnibotProfile", omnibotProfile)
    from(pluginSourceDir)
    from(omniFlowBaselineArchive) {
        into("runtime-components")
    }
    into(pluginAssetsRootDir)
    exclude("catalog.v1.json")
    if (!isInvestorProfile) {
        exclude("omni-vlm-lite/**", "vibe-project/**", "omnilink-agent/**")
    }
    doFirst {
        delete(pluginAssetsRootDir)
    }
    doLast {
        @Suppress("UNCHECKED_CAST")
        val source = JsonSlurper().parse(pluginCatalogFile)
            as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val plugins = source.getValue("plugins") as List<Map<String, Any?>>
        val filteredPlugins = plugins.filter { plugin ->
            @Suppress("UNCHECKED_CAST")
            val profiles = plugin["profiles"] as? List<String>
                ?: listOf("main", "investor")
            omnibotProfile in profiles
        }
        val profileCatalog = LinkedHashMap(source).apply {
            this["plugins"] = filteredPlugins
        }
        File(pluginAssetsRootDir, "catalog.v1.json").writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(profileCatalog)) + "\n"
        )
    }
}

android {
    namespace = "cn.com.omnimind.bot"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.com.omnimind.bot"
        minSdk = 29
        targetSdk = 35
        // Release 0.6.1. Keep the Android version code monotonic so the APK
        // can be installed as an update over the previously tested build.
        versionCode = 11
        versionName = "0.6.1"
        buildConfigField("String", "IMAGE_BASE_URL", buildConfigString(omnibotImageBaseUrl))
        buildConfigField("String", "IMAGE_MODEL", buildConfigString(omnibotImageModel))
        buildConfigField("String", "IMAGE_API_KEY", buildConfigString(omnibotImageApiKey))
        buildConfigField("String", "DEBUG_OMNIMIND_API_BASE", buildConfigString(""))
        buildConfigField("String", "DEBUG_OMNIMIND_API_KEY", buildConfigString(""))
        buildConfigField("String", "DEBUG_OMNIMIND_MODEL", buildConfigString(""))
        buildConfigField("String", "DEBUG_LLMTHU_API_BASE", buildConfigString(""))
        buildConfigField("String", "DEBUG_LLMTHU_API_KEY", buildConfigString(""))
        buildConfigField("String", "DEBUG_LLMTHU_MODEL", buildConfigString(""))
        buildConfigField("boolean", "ENABLE_LLMTHU_BOOTSTRAP", "false")
        buildConfigField("String", "OMNIBOT_PROFILE", buildConfigString(omnibotProfile))
        buildConfigField("boolean", "ALLOW_PACKAGED_PLUGIN_FALLBACK", "true")
        buildConfigField(
            "boolean",
            "PREFER_PACKAGED_OMNIFLOW_RUNTIME",
            preferPackagedOmniFlowRuntime.toString(),
        )
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }

    }
    // 添加 flavor 维度
    flavorDimensions += listOf("version", "edition")

    productFlavors {
        create("develop") {
            dimension = "version"
            buildConfigField("String", "BASE_URL", buildConfigString(resolvedOmnibotBaseUrl))
            buildConfigField("String", "AI_GATEWAY_URL", buildConfigString(resolvedOmnibotAiGatewayUrl))
            buildConfigField("String", "APP_UPDATE_WORKER_URL", buildConfigString(appUpdateWorkerUrl))
        }

        create("production") {
            dimension = "version"
            buildConfigField("String", "BASE_URL", buildConfigString(resolvedOmnibotBaseUrl))
            buildConfigField("String", "AI_GATEWAY_URL", buildConfigString(resolvedOmnibotAiGatewayUrl))
            buildConfigField("String", "APP_UPDATE_WORKER_URL", buildConfigString(appUpdateWorkerUrl))
        }

        create("standard") {
            dimension = "edition"
            buildConfigField("String", "APP_EDITION", "\"standard\"")
        }
    }
    signingConfigs {
        create("release") {
            // 引用全局gradle.properties中的变量
            storeFile = project.findProperty("OMNI_RELEASE_STORE_FILE")?.let { file(it) }
            storePassword = project.findProperty("OMNI_RELEASE_STORE_PWD") as String?
            keyAlias = project.findProperty("OMNI_RELEASE_KEY_ALIAS") as String?
            keyPassword = project.findProperty("OMNI_RELEASE_KEY_PWD") as String?

            // V2/V3签名配置（minSdk=30）
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            buildConfigField(
                "boolean",
                "ENABLE_LLMTHU_BOOTSTRAP",
                bundleLlmThuProvider.toString(),
            )
            if (bundleLlmThuProvider) {
                buildConfigField(
                    "String",
                    "DEBUG_LLMTHU_API_BASE",
                    buildConfigString(llmThuApiBase),
                )
                buildConfigField(
                    "String",
                    "DEBUG_LLMTHU_API_KEY",
                    buildConfigString(llmThuApiKey),
                )
                buildConfigField(
                    "String",
                    "DEBUG_LLMTHU_MODEL",
                    buildConfigString(llmThuModel),
                )
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // There is one installable debug app for device validation.  A
            // suffix here creates a second launcher entry beside the normal
            // package, which makes users switch between two identical APKs.
            applicationIdSuffix = ""
            isMinifyEnabled = false
            buildConfigField("boolean", "ENABLE_LLMTHU_BOOTSTRAP", "true")
            buildConfigField(
                "String",
                "DEBUG_LLMTHU_API_BASE",
                buildConfigString(llmThuApiBase)
            )
            buildConfigField(
                "String",
                "DEBUG_LLMTHU_API_KEY",
                buildConfigString(llmThuApiKey)
            )
            buildConfigField(
                "String",
                "DEBUG_LLMTHU_MODEL",
                buildConfigString(llmThuModel)
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf(
                "**/libc++_shared.so"
            )
        }
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "DebugProbesKt.bin"
            )
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs(
                "src/main/assets",
                "../skills",
                pluginAssetsRootDir,
                webChatAssetsRootDir
            )
        }
    }

    lint {
        // 使用项目根目录的 lint.xml 配置
        lintConfig = file("../lint.xml")
        // 将错误视为警告继续构建
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("preBuild").configure {
    dependsOn(syncWebChatBundle, syncPluginAssets)
}
dependencies {
    implementation(libs.agent.client.protocol)
    implementation(project(":flutter"))
    implementation(project(":uikit"))
    implementation(project(":baselib"))
    implementation(project(":androidgui"))
    implementation(project(":omniflow-android"))
    implementation(project(":core:main"))
    implementation(project(":core:terminal-view"))
    implementation(project(":core:terminal-emulator"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar","*.jar"))))
    implementation(project(":assists"))
//    implementation(project(":lib"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidsvg)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.work.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.shizuku.provider)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.mcp.kotlin.sdk.server)
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest )
}
