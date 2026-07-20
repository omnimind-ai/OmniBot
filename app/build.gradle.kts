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

val webChatSourceDir = rootProject.file("webchat")
val webChatDistDir = File(webChatSourceDir, "dist")
val webChatAssetsRootDir = layout.buildDirectory.dir("generated/omnibot_assets").get().asFile
val webChatAssetsDir = File(webChatAssetsRootDir, "webchat")
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

android {
    namespace = "cn.com.omnimind.bot"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.com.omnimind.bot"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.5.6.8"
        buildConfigField("String", "IMAGE_BASE_URL", buildConfigString(omnibotImageBaseUrl))
        buildConfigField("String", "IMAGE_MODEL", buildConfigString(omnibotImageModel))
        buildConfigField("String", "IMAGE_API_KEY", buildConfigString(omnibotImageApiKey))


        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

    }
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    // 添加 flavor 维度
    flavorDimensions += listOf("version", "edition")

    productFlavors {
        create("develop") {
            dimension = "version"
            buildConfigField("String", "BASE_URL", "\"${prop("OMNIBOT_BASE_URL")}\"")
            buildConfigField("String", "APP_UPDATE_WORKER_URL", "\"${prop("OMNIBOT_UPDATE_WORKER_URL")}\"")
        }

        create("production") {
            dimension = "version"
            buildConfigField("String", "BASE_URL", "\"${prop("OMNIBOT_BASE_URL")}\"")
            buildConfigField("String", "APP_UPDATE_WORKER_URL", "\"${prop("OMNIBOT_UPDATE_WORKER_URL")}\"")
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
                "META-INF/NOTICE.txt"
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
        abortOnError = false
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
dependencies {
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
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest )
}
