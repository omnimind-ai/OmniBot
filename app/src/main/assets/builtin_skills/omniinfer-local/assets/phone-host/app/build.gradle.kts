import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "cn.com.omnimind.localinfer"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.com.omnimind.localinfer"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Upstream discovers ggml CPU backends in applicationInfo.nativeLibraryDir.
    packaging { jniLibs.useLegacyPackaging = true }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies { implementation(project(":omniinfer-server")) }
