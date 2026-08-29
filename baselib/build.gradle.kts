import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-kapt") // 添加kapt插件以支持注解处理
}

android {
    namespace = "cn.com.omnimind.baselib"
    compileSdk = 37

    defaultConfig {
        minSdk = 25
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        aidl = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    api(libs.okhttp.sse)
    api(libs.mmkv)
    api(libs.gson)
    api(libs.glide)
    kapt(libs.compiler) // Glide 注解处理器需用 kapt
//    api(libs.search)
//    api(libs.location)
    api(libs.logging.interceptor)
    // 修改Room相关依赖的引入方式
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.paging.common) {
        exclude(group = "com.intellij", module = "annotations")
    }
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.compose.material.core)

    // 将Room编译器移到kapt作用域
    kapt(libs.androidx.room.compiler)
    kapt(libs.kotlin.metadata.jvm)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.shizuku.api)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
