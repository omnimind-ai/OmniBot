import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

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

fun verifyHttpsProductionEndpoint(name: String, value: String) {
    val parsed = runCatching { URI(value) }.getOrNull()
    require(
        parsed != null &&
            parsed.scheme.equals("https", ignoreCase = true) &&
            !parsed.host.isNullOrBlank() &&
            parsed.userInfo == null &&
            parsed.rawQuery == null &&
            parsed.rawFragment == null
    ) {
        "$name must be an HTTPS base URL without credentials, query parameters, or fragments for production builds"
    }
}

val omnibotBaseUrl = prop("OMNIBOT_BASE_URL")
val omnibotAiGatewayUrl = prop("OMNIBOT_AI_GATEWAY_URL")
val omnibotUpdateWorkerUrl = prop("OMNIBOT_UPDATE_WORKER_URL")
val resolvedOmnibotBaseUrl = omnibotBaseUrl
    .ifBlank { "https://account.omnimind.com.cn" }
val resolvedOmnibotAiGatewayUrl = omnibotAiGatewayUrl
    .ifBlank { "https://model-api.omnimind.com.cn" }

val developmentVersionCode = 2
val releaseVersionCodePropertyName = "OMNI_RELEASE_VERSION_CODE"
val releaseVersionCodeRaw = prop(releaseVersionCodePropertyName)
val configuredReleaseVersionCode = releaseVersionCodeRaw
    .takeIf { it.matches(Regex("[1-9][0-9]*")) }
    ?.toIntOrNull()

fun requireProductionReleaseVersionCode(): Int {
    return requireNotNull(configuredReleaseVersionCode) {
        "$releaseVersionCodePropertyName must be provided as a positive 32-bit integer " +
            "for production release builds (for example, " +
            "-P$releaseVersionCodePropertyName=123)."
    }
}

val verifyProductionEndpoints by tasks.registering {
    group = "verification"
    description = "Fail production builds if official account, AI, or update endpoints are not HTTPS."
    inputs.property("accountBaseUrl", resolvedOmnibotBaseUrl)
    inputs.property("aiGatewayUrl", resolvedOmnibotAiGatewayUrl)
    inputs.property("updateWorkerUrl", omnibotUpdateWorkerUrl.ifBlank { "<missing>" })
    doLast {
        verifyHttpsProductionEndpoint("OMNIBOT_BASE_URL", resolvedOmnibotBaseUrl)
        verifyHttpsProductionEndpoint("OMNIBOT_AI_GATEWAY_URL", resolvedOmnibotAiGatewayUrl)
        verifyHttpsProductionEndpoint("OMNIBOT_UPDATE_WORKER_URL", omnibotUpdateWorkerUrl)
    }
}

val verifyProductionReleaseVersionCode by tasks.registering {
    group = "verification"
    description = "Fail production release builds unless an explicit positive versionCode is provided."
    inputs.property(
        "releaseVersionCode",
        releaseVersionCodeRaw.ifBlank { "<missing>" }
    )
    doLast {
        requireProductionReleaseVersionCode()
    }
}

val webChatSourceDir = rootProject.file("webchat")
val webChatDistDir = File(webChatSourceDir, "dist")
val webChatAssetsRootDir = layout.buildDirectory.dir("generated/omnibot_assets").get().asFile
val webChatAssetsDir = File(webChatAssetsRootDir, "webchat")
val webChatPackageJson = File(webChatSourceDir, "package.json")
val webChatLockFile = File(webChatSourceDir, "pnpm-lock.yaml")
val webChatInstallMarker = File(webChatSourceDir, "node_modules/.modules.yaml")
val hostOs = System.getProperty("os.name").lowercase()

val verifyAgentRuntimeSupplyChain by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fail production release builds unless Agent runtime packages pass the offline lock audit."
    workingDir(rootProject.projectDir)
    val script = rootProject.file("scripts/verify-agent-runtime-supply-chain.py")
    commandLine(
        if (hostOs.contains("windows")) {
            listOf("py", "-3", script.absolutePath)
        } else {
            listOf("python3", script.absolutePath)
        }
    )
    inputs.files(
        script,
        rootProject.file("app/src/main/assets/agent_runtime/acp-adapters/package.json"),
        rootProject.file("app/src/main/assets/agent_runtime/acp-adapters/package-lock.json"),
        rootProject.file("app/src/main/assets/agent_runtime/acp-adapters/installed-manifest.json"),
        rootProject.file("app/src/main/java/cn/com/omnimind/bot/agent/runtime/ManagedAcpAdapterInstall.kt"),
        rootProject.file("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeManager.kt")
    )
    outputs.upToDateWhen { false }
}

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

android {
    namespace = "cn.com.omnimind.bot"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.com.omnimind.bot"
        minSdk = 29
        targetSdk = 36
        versionCode = developmentVersionCode
        versionName = "0.5.6.16"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

    }
    // 添加 flavor 维度
    flavorDimensions += listOf("version", "edition")

    productFlavors {
        create("develop") {
            dimension = "version"
            buildConfigField("String", "BASE_URL", buildConfigString(resolvedOmnibotBaseUrl))
            buildConfigField("String", "AI_GATEWAY_URL", buildConfigString(resolvedOmnibotAiGatewayUrl))
            // Keep the generated literal escaped even if a developer supplies a malformed value.
            buildConfigField("String", "APP_UPDATE_WORKER_URL", buildConfigString(omnibotUpdateWorkerUrl))
        }

        create("production") {
            dimension = "version"
            // Development/debug variants remain buildable without release inputs. The
            // production release gate below rejects this fallback before packaging.
            versionCode = configuredReleaseVersionCode ?: developmentVersionCode
            buildConfigField("String", "BASE_URL", buildConfigString(resolvedOmnibotBaseUrl))
            buildConfigField("String", "AI_GATEWAY_URL", buildConfigString(resolvedOmnibotAiGatewayUrl))
            // Production validation above requires this public endpoint to be a clean HTTPS base URL.
            buildConfigField("String", "APP_UPDATE_WORKER_URL", buildConfigString(omnibotUpdateWorkerUrl))
        }

        create("standard") {
            dimension = "edition"
            manifestPlaceholders["omnibotEditionMarker"] =
                "cn.com.omnimind.bot.EDITION.standard"
            buildConfigField("String", "APP_EDITION", "\"standard\"")
            buildConfigField("boolean", "APP_SELF_UPDATE_ENABLED", "true")
            buildConfigField("boolean", "APP_CAN_QUERY_INSTALLED_APPS", "true")
            buildConfigField("boolean", "APP_CAN_MANAGE_PUBLIC_STORAGE", "true")
        }

        create("play") {
            dimension = "edition"
            manifestPlaceholders["omnibotEditionMarker"] =
                "cn.com.omnimind.bot.EDITION.play"
            buildConfigField("String", "APP_EDITION", "\"play\"")
            buildConfigField("boolean", "APP_SELF_UPDATE_ENABLED", "false")
            buildConfigField("boolean", "APP_CAN_QUERY_INSTALLED_APPS", "false")
            buildConfigField("boolean", "APP_CAN_MANAGE_PUBLIC_STORAGE", "false")
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
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
            // AGP 8.5.1+ aligns uncompressed JNI libraries for 16 KB page-size devices.
            useLegacyPackaging = false
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
            assets.srcDirs("src/main/assets", "../skills", webChatAssetsRootDir)
        }
    }

    lint {
        // 使用项目根目录的 lint.xml 配置
        lintConfig = file("../lint.xml")
        // 将错误视为警告继续构建
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("preBuild").configure {
    dependsOn(syncWebChatBundle)
}

tasks.configureEach {
    if (name.startsWith("preProduction") && name.endsWith("Build")) {
        dependsOn(verifyProductionEndpoints)
    }
    if (name.startsWith("preProduction") && name.endsWith("ReleaseBuild")) {
        dependsOn(verifyProductionReleaseVersionCode)
        dependsOn(verifyAgentRuntimeSupplyChain)
    }
}
dependencies {
    implementation(libs.agent.client.protocol)
    implementation(project(":flutter"))
    implementation(project(":uikit"))
    implementation(project(":baselib"))
    implementation(project(":core:main"))
    implementation(project(":core:terminal-view"))
    implementation(project(":core:terminal-emulator"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar","*.jar"))))
    implementation(project(":assists"))
//    implementation(project(":lib"))

    implementation(libs.androidx.core.ktx)
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Android's local JVM stub returns null from JSONObject.put(). Use the
    // real, offline-pinned implementation for update-contract unit tests.
    testImplementation("org.json:json:20180813")
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest )
}
