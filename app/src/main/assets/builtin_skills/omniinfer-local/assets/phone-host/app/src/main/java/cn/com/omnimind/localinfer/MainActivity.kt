package cn.com.omnimind.localinfer

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.omniinfer.server.OmniInferServer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Thin Android host. All inference and serving belongs to upstream OmniInfer. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OmniInferServer.init(applicationContext)
        status = TextView(this).apply { textSize = 17f; setPadding(24, 24, 24, 24) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(status)
        layout.addView(Button(this).apply { text = "启动本机模型"; setOnClickListener { startModel() } })
        layout.addView(Button(this).apply { text = "停止服务"; setOnClickListener { stopModel() } })
        setContentView(layout)
        update(if (OmniInferServer.isReady()) readyText() else "尚未启动\n首次启动下载 Qwen3 0.6B Q8_0（约 640 MB）")
        if (savedInstanceState == null && intent.getStringExtra("operation") == "start") startModel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("operation") == "start") startModel()
    }

    private fun update(value: String) {
        runOnUiThread { if (!isDestroyed) status.text = value }
        // Only status and public model identity, never prompt/response content.
        android.util.Log.i("OmniInferPhone", value)
    }

    private fun startModel() {
        if (OmniInferServer.isReady()) { update(readyText()); return }
        if (!busy.compareAndSet(false, true)) { update("已有加载任务进行中"); return }
        worker.execute {
            try {
                val model = File(filesDir, "models/$MODEL_FILE")
                model.parentFile!!.mkdirs()
                if (!model.isFile || sha256(model) != MODEL_SHA256) downloadModel(model)
                update("正在加载 Qwen3 0.6B…")
                check(OmniInferServer.loadModel(
                    modelPath = model.absolutePath, backend = "llama.cpp", port = PORT,
                    nThreads = 4, nCtx = 16384, extraConfig = emptyMap(),
                )) { OmniInferServer.getLastError() }
                update(readyText())
            } catch (error: Exception) {
                update("启动失败：${error.javaClass.simpleName}: ${error.message}")
            } finally { busy.set(false) }
        }
    }

    private fun stopModel() {
        if (!busy.compareAndSet(false, true)) { update("请等待加载任务结束"); return }
        worker.execute {
            try { OmniInferServer.stop(); update("服务已停止，模型文件保留") }
            finally { busy.set(false) }
        }
    }

    private fun downloadModel(target: File) {
        val partial = File(target.parentFile, target.name + ".part")
        val existing = if (partial.isFile) partial.length() else 0L
        update("正在下载 Qwen3 0.6B：${existing / 1_000_000} / 640 MB")
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 90_000
        if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
        try {
            val code = connection.responseCode
            check(code == 200 || code == 206) { "模型下载 HTTP $code" }
            val append = code == 206 && existing > 0
            if (append) check(connection.getHeaderField("Content-Range")?.startsWith("bytes $existing-") == true) {
                "下载续传范围不匹配"
            }
            var total = if (append) existing else 0L
            var last = total
            connection.inputStream.use { input ->
                java.io.FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                        if (total - last >= 32_000_000) {
                            update("正在下载 Qwen3 0.6B：${total / 1_000_000} / 640 MB")
                            last = total
                        }
                    }
                }
            }
            check(sha256(partial) == MODEL_SHA256) { "模型 SHA-256 校验失败" }
            check(partial.renameTo(target)) { "无法保存校验后的模型" }
        } finally { connection.disconnect() }
    }

    private fun readyText() = "本机模型已就绪\nQwen3 0.6B · llama.cpp CPU\nhttp://127.0.0.1:$PORT/v1"

    companion object {
        private val worker = Executors.newSingleThreadExecutor()
        private val busy = AtomicBoolean(false)
        const val PORT = 9099
        const val MODEL_FILE = "Qwen3-0.6B-Q8_0.gguf"
        const val MODEL_SHA256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
        const val MODEL_URL = "https://modelscope.cn/models/Qwen/Qwen3-0.6B-GGUF/resolve/6abe20cd0aed577f4d0b267935868ecae190aee9/Qwen3-0.6B-Q8_0.gguf"
        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
