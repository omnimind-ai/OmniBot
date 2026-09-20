plugins { id("com.android.application") version "9.3.2" }
android {
    namespace = "cn.com.omnimind.runtime.payload"
    compileSdk = 37
    defaultConfig {
        applicationId = "cn.com.omnimind.runtime.payload"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.4-test.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes { release { isMinifyEnabled = false } }
    packaging { jniLibs.useLegacyPackaging = true }
}
dependencies { implementation("io.github.omnimind-ai:omniinfer:0.2.4") }
