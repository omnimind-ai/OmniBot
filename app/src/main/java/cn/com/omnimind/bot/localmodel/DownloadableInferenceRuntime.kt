package cn.com.omnimind.bot.localmodel

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import java.util.concurrent.FutureTask
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import dalvik.system.DexClassLoader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Optional, version-pinned code container. The main APK has no OmniInfer dependency. */
object DownloadableInferenceRuntime {
    const val SERVICE = "com.omniinfer.server.OmniInferService"
    const val MODEL = "Qwen3-0.6B-Q8_0.gguf"
    const val PAYLOAD_SHA = "b317a12bda248f9197024fd90d7067e8d6038394c7a6fde81dee2a17a02f9006"
    const val MODEL_SHA = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
    private const val PAYLOAD_URL = "https://github.com/omnimind-ai/OmniBot/releases/download/omniinfer-runtime-v0.2.4-test.1/omniinfer-runtime-0.2.4-arm64.apk"
    data class ModelSpec(val file: String, val label: String, val url: String, val sha: String, val contextSize: Int = 16384)
    val models = listOf(
        ModelSpec(MODEL, "Qwen3 0.6B Q8_0 · 610 MB",
            "https://modelscope.cn/models/Qwen/Qwen3-0.6B-GGUF/resolve/6abe20cd0aed577f4d0b267935868ecae190aee9/Qwen3-0.6B-Q8_0.gguf", MODEL_SHA),
        ModelSpec("Qwen3.5-0.8B-Q4_0.gguf", "Qwen3.5 0.8B Q4_0 · 484 MB",
            "https://modelscope.cn/models/unsloth/Qwen3.5-0.8B-GGUF/resolve/master/Qwen3.5-0.8B-Q4_0.gguf",
            "444406ddd926550c724ec18d5120a9d40ded44908a063b0e66e9a7e5464c652c"),
        // Pinned metadata from the official OmniInfer 0.2.4 Android catalog.
        ModelSpec("gemma-4-E2B-it.litertlm", "Gemma 4 E2B · 2.4 GB · 实验版",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", 8192)
    )
    @Volatile private var loader: ClassLoader? = null
    private var sdk: Any? = null
    private var runtimeContext: Context? = null
    fun root(context: Context) = File(context.filesDir, "omniinfer-runtime/0.2.4").apply { mkdirs() }
    fun model(context: Context, spec: ModelSpec) = File(root(context), spec.file)
    fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(1024 * 1024); while (true) {
            val n = input.read(bytes); if (n < 0) break; sha.update(bytes, 0, n)
        } }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    private fun fetch(url: String, target: File, sha: String, status: (String) -> Unit) {
        if (target.isFile) {
            check(digest(target) == sha) { "缓存校验失败：${target.name}；请删除该组件后重试" }
            status("已校验 ${target.name}"); return
        }
        val part = File(target.parentFile, target.name + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000; connection.readTimeout = 60_000
        try {
            check(connection.responseCode == 200) { "下载失败 HTTP ${connection.responseCode}" }
            val size = connection.contentLengthLong
            connection.inputStream.use { input -> part.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024); var total = 0L; var last = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    output.write(buffer, 0, n); total += n
                    if (System.currentTimeMillis() - last > 500) {
                        status("下载 ${target.name}：${total / 1048576} / ${size / 1048576} MB")
                        last = System.currentTimeMillis()
                    }
                }
            } }
            check(digest(part) == sha) { "下载校验失败：${target.name}" }
            check(part.setReadOnly()) { "无法保护下载文件" }
            check(part.renameTo(target)) { "无法保存下载文件" }
        } finally { connection.disconnect(); if (part.exists()) part.delete() }
    }
    fun prepare(context: Context, spec: ModelSpec, status: (String) -> Unit) {
        require(Build.VERSION.SDK_INT >= 30 && Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            "此实验组件需要 Android 11+ ARM64"
        }
        fetch(PAYLOAD_URL, File(root(context), "runtime.apk"), PAYLOAD_SHA, status)
        fetch(spec.url, model(context, spec), spec.sha, status)
    }
    @Synchronized fun classLoader(context: Context): ClassLoader {
        loader?.let { return it }
        require(Build.VERSION.SDK_INT >= 30)
        val apk = File(root(context), "runtime.apk")
        check(apk.isFile && digest(apk) == PAYLOAD_SHA) { "推理组件未安装或校验失败" }
        check(apk.setReadOnly())
        val libs = File(root(context), "lib").apply { mkdirs() }
        ZipFile(apk).use { zip -> zip.entries().asSequence().filter {
            it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so")
        }.forEach { entry ->
            val name = entry.name.removePrefix("lib/arm64-v8a/")
            check(!name.contains('/'))
            val file = File(libs, name)
            // Extract from the verified archive, replacing any stale extraction.
            if (file.exists()) check(file.delete())
            zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
            check(file.setReadOnly())
        } }
        return DexClassLoader(apk.path, context.codeCacheDir.path, libs.path,
            ClassLoader.getSystemClassLoader().parent).also { loader = it }
    }
    fun start(context: Context, spec: ModelSpec): String {
        val cl = classLoader(context)
        if (sdk == null) {
            val resources = context.createConfigurationContext(Configuration(context.resources.configuration)).resources
            val resourceLoader = ResourcesLoader()
            ParcelFileDescriptor.open(File(root(context), "runtime.apk"), ParcelFileDescriptor.MODE_READ_ONLY).use {
                resourceLoader.addProvider(ResourcesProvider.loadFromApk(it))
            }
            val attach = FutureTask { resources.addLoaders(resourceLoader) }
            Handler(Looper.getMainLooper()).post(attach)
            attach.get()
            runtimeContext = object : ContextWrapper(context.applicationContext) {
                override fun getApplicationContext(): Context = this
                override fun getClassLoader(): ClassLoader = cl
                override fun getResources(): Resources = resources
                override fun getAssets() = resources.assets
                override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(baseContext.applicationInfo).apply {
                    nativeLibraryDir = File(root(baseContext), "lib").path
                }
            }
            val type = cl.loadClass("com.omniinfer.server.OmniInferServer")
            sdk = type.getField("INSTANCE").get(null)
            type.getMethod("init", Context::class.java).invoke(sdk, runtimeContext)
        }
        val instance = requireNotNull(sdk)
        val ready = instance.javaClass.getMethod("isReady").invoke(instance) as Boolean
        if (!ready) {
            val portInUse = runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 9099), 300) }
            }.isSuccess
            check(!portInUse) { "9099 端口已被其他服务使用，请先停止旧的本地模型宿主" }
        }
        val preferences = context.getSharedPreferences("local_inference_backend", Context.MODE_PRIVATE)
        // Invalidate successful selection when the engine, model or system changes.
        val selectionKey = "$PAYLOAD_SHA:${spec.sha}:${Build.FINGERPRINT}"
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        val supportsHtp = manufacturer.contains("qualcomm", ignoreCase = true) ||
            Build.HARDWARE.lowercase().let { it.startsWith("qcom") || it.startsWith("msm") }
        val load = instance.javaClass.getMethod("loadModel", String::class.java, String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Map::class.java)
        val backend = LocalInferenceBackend.select(
            supportsHtp = supportsHtp,
            liteRt = spec.file.endsWith(".litertlm"),
            previous = preferences.getString(selectionKey, null),
            load = { candidate ->
                load.invoke(instance, model(context, spec).path, candidate, 9099, 4, spec.contextSize,
                    emptyMap<String, String>()) as Boolean
            },
            reset = { instance.javaClass.getMethod("stop").invoke(instance) },
            lastError = { instance.javaClass.getMethod("getLastError").invoke(instance).toString() },
            remember = { preferences.edit().putString(selectionKey, it).apply() },
        )
        android.util.Log.i("LocalModelRuntime", "Startup backend selected: $backend")
        return "本地模型已就绪，计算后端已自动适配。\nhttp://127.0.0.1:9099/v1\n模型：${instance.javaClass.getMethod("getLoadedModels").invoke(instance)}\n请在 Provider 中使用 Chat Completions，API Key 留空。"
    }
    fun stop() { sdk?.let { it.javaClass.getMethod("stop").invoke(it) } }
}
