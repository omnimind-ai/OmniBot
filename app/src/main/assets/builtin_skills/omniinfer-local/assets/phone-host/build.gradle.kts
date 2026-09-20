plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
}
// Only build configuration adapts the upstream module to the ARM64 host NDK.
// Its inference implementation and HTTP protocol remain upstream-owned.
subprojects {
    afterEvaluate {
        extensions.findByType<com.android.build.gradle.BaseExtension>()?.apply {
            ndkVersion = "29.0.14206865"
            ndkPath = rootProject.providers.gradleProperty("omniinfer.ndk").get()
            buildToolsVersion = "37.0.0"
            externalNativeBuild.cmake.version = "4.2.1"
            defaultConfig.externalNativeBuild.cmake.arguments.addAll(listOf(
                "-DCMAKE_JOB_POOLS=compile_pool=2;link_pool=1",
                "-DCMAKE_JOB_POOL_COMPILE=compile_pool",
                "-DCMAKE_JOB_POOL_LINK=link_pool",
                "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--threads=1",
                "-DCMAKE_EXE_LINKER_FLAGS=-Wl,--threads=1",
            ))
        }
    }
}
