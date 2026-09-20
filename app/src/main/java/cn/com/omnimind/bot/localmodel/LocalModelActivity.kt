package cn.com.omnimind.bot.localmodel

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import java.util.concurrent.Executors

class LocalModelActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var models: Spinner
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        }
        layout.addView(TextView(this).apply { text = "使用本地模型服务"; textSize = 20f })
        status = TextView(this).apply {
            text = "可选实验功能 · 不启用不会下载或运行。\n点击下方按钮后下载约 27 MB 推理组件与所选模型；之后复用。\n计算后端自动适配，无需手动选择。Android 11+ ARM64。"; textSize = 14f
            setPadding(0, 24, 0, 24); setTextIsSelectable(true)
        }
        layout.addView(status)
        models = Spinner(this).apply {
            adapter = ArrayAdapter(this@LocalModelActivity, android.R.layout.simple_spinner_dropdown_item,
                DownloadableInferenceRuntime.models.map { it.label })
            setSelection(getPreferences(MODE_PRIVATE).getInt("modelIndex", 0)
                .coerceIn(0, DownloadableInferenceRuntime.models.lastIndex))
        }
        layout.addView(models)
        start = Button(this).apply { text = "下载并启动"; setOnClickListener {
            val index = models.selectedItemPosition
            val spec = DownloadableInferenceRuntime.models[index]
            getPreferences(MODE_PRIVATE).edit().putInt("modelIndex", index).apply()
            execute {
            DownloadableInferenceRuntime.prepare(applicationContext, spec) { update(it) }
            update("正在加载模型……")
            DownloadableInferenceRuntime.start(applicationContext, spec)
        } } }
        stop = Button(this).apply { text = "停止服务"; setOnClickListener { execute {
            DownloadableInferenceRuntime.stop(); "服务已停止，下载文件已保留。"
        } } }
        layout.addView(start); layout.addView(stop)
        layout.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        setContentView(layout)
    }
    private fun update(message: String) = runOnUiThread { if (!isDestroyed) status.text = message }
    private fun execute(operation: () -> String) {
        status.text = "正在准备，请稍候……"
        start.isEnabled = false; stop.isEnabled = false; models.isEnabled = false
        worker.execute {
            val result = runCatching(operation).getOrElse {
                android.util.Log.e("LocalModelRuntime", "Operation failed", it)
                "失败：${it.cause?.message ?: it.message}"
            }
            runOnUiThread { if (!isDestroyed) {
                status.text = result; start.isEnabled = true; stop.isEnabled = true; models.isEnabled = true
            } }
        }
    }
    companion object { private val worker = Executors.newSingleThreadExecutor() }
}
