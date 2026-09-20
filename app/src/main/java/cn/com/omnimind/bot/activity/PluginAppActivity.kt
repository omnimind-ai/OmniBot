package cn.com.omnimind.bot.activity

import android.app.ActivityManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import cn.com.omnimind.bot.plugin.sandbox.SandboxPluginAppLaunchSpec
import cn.com.omnimind.bot.plugin.sandbox.SandboxPluginBridgeRuntime
import cn.com.omnimind.bot.plugin.sandbox.SandboxPluginPool
import cn.com.omnimind.bot.plugin.sandbox.SandboxSvgIconRenderer
import cn.com.omnimind.bot.plugin.OmniPluginHost
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class PluginAppActivity : ComponentActivity() {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var webView: WebView
    private lateinit var pluginId: String
    private lateinit var pluginRoot: File
    private lateinit var bridgeRuntime: SandboxPluginBridgeRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolvedPluginId = SandboxPluginAppLaunchSpec.pluginId(intent?.dataString)
        if (resolvedPluginId == null) {
            showError("Invalid Vibe App link")
            return
        }
        pluginId = resolvedPluginId
        bridgeRuntime = SandboxPluginBridgeRuntime(this)
        onBackPressedDispatcher.addCallback(this) {
            if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else finish()
        }
        lifecycleScope.launch {
            runCatching { loadPluginApp() }
                .onFailure { showError(it.message ?: "Unable to open this Vibe App") }
        }
    }

    private suspend fun loadPluginApp() {
        val state = OmniPluginHost.get(this).list()
            .firstOrNull { it.descriptor.id == pluginId }
            ?: throw IllegalArgumentException("This Vibe App plugin is not installed")
        require(state.installed && state.enabled) { "This Vibe App plugin is disabled" }
        val dashboard = SandboxPluginPool(this).dashboard(pluginId)
        val title = dashboard.getValue("title") as String
        val entry = File(dashboard.getValue("entryPath") as String).canonicalFile
        val icon = File(dashboard.getValue("iconPath") as String).canonicalFile
        pluginRoot = File(dashboard.getValue("rootPath") as String).canonicalFile
        require(isInsidePlugin(entry)) { "Vibe App entry escapes its plugin directory" }
        val taskIcon = SandboxSvgIconRenderer.render(this, icon)
        setTaskDescription(ActivityManager.TaskDescription(title, taskIcon, Color.BLACK))
        setTitle(title)

        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = false
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                mediaPlaybackRequiresUserGesture = true
                setGeolocationEnabled(false)
            }
            addJavascriptInterface(PluginJavascriptBridge(), BRIDGE_NAME)
            webViewClient = PluginWebViewClient()
        }
        setContentView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        webView.loadUrl(entry.toURI().toString())
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(BRIDGE_NAME)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun showError(message: String) {
        if (isFinishing || isDestroyed) return
        val text = TextView(this).apply {
            this.text = message
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)
                addView(
                    text,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            },
        )
    }

    private fun isInsidePlugin(file: File): Boolean =
        file == pluginRoot || file.path.startsWith(pluginRoot.path + File.separator)

    private inner class PluginJavascriptBridge {
        @JavascriptInterface
        fun postMessage(message: String) {
            lifecycleScope.launch {
                val response = runCatching {
                    val request = json.decodeFromString<SandboxBridgeRequest>(message)
                    require(request.id.isNotBlank() && request.method.isNotBlank()) {
                        "Bridge request is incomplete"
                    }
                    val result = bridgeRuntime.invoke(
                        pluginId = pluginId,
                        method = request.method,
                        params = request.params.toBridgeMap(),
                    )
                    buildJsonObject {
                        put("id", request.id)
                        put("ok", true)
                        put("result", result.toJsonElement())
                    }
                }.getOrElse { error ->
                    val requestId = runCatching {
                        json.decodeFromString<SandboxBridgeRequest>(message).id
                    }.getOrDefault("")
                    buildJsonObject {
                        put("id", requestId)
                        put("ok", false)
                        put("error", error.message ?: error.javaClass.simpleName)
                    }
                }
                if (::webView.isInitialized) {
                    webView.evaluateJavascript(
                        "window.__omniSandboxResolve && " +
                            "window.__omniSandboxResolve($response);",
                        null,
                    )
                }
            }
        }
    }

    private inner class PluginWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val file = request?.url?.takeIf { it.scheme == "file" }
                ?.path
                ?.let(::File)
                ?.let { file -> runCatching { file.canonicalFile }.getOrNull() }
                ?: return true
            return !isInsidePlugin(file) || file.extension.lowercase() !in HTML_EXTENSIONS
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val uri = request?.url ?: return blockedResponse()
            if (uri.scheme in INLINE_SCHEMES) return null
            if (uri.scheme != "file") return blockedResponse()
            val file = uri.path?.let(::File)
                ?.let { candidate -> runCatching { candidate.canonicalFile }.getOrNull() }
                ?: return blockedResponse()
            return if (isInsidePlugin(file)) null else blockedResponse()
        }

        private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    private fun JsonObject.toBridgeMap(): Map<String, Any?> =
        entries.associate { (key, value) -> key to value.toBridgeValue() }

    private fun JsonElement.toBridgeValue(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> toBridgeMap()
        is JsonArray -> map { element -> element.toBridgeValue() }
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> content
        }
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(
            entries.associate { (key, value) -> key.toString() to value.toJsonElement() },
        )
        is Iterable<*> -> JsonArray(map { value -> value.toJsonElement() })
        is Array<*> -> JsonArray(map { value -> value.toJsonElement() })
        else -> JsonPrimitive(toString())
    }

    @Serializable
    private data class SandboxBridgeRequest(
        val id: String = "",
        val method: String = "",
        val params: JsonObject = JsonObject(emptyMap()),
    )

    private companion object {
        const val BRIDGE_NAME = "OmniSandboxBridge"
        val HTML_EXTENSIONS = setOf("html", "htm")
        val INLINE_SCHEMES = setOf("data", "blob")
    }
}
