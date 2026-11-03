package cn.com.omnimind.bot.activity

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.resource.ResourceFileIntents
import cn.com.omnimind.bot.ui.components.FlClashPopupMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** File preview only: no Flutter engine, platform view, or JavaScript/native bridge. */
class HtmlPreviewActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SOURCE_PATH = "source_path"
        private const val EXTRA_TITLE = "preview_title"
        private const val WEB_STATE = "web_state"

        fun createIntent(context: Context, sourcePath: String, title: String?): Intent =
            Intent(context, HtmlPreviewActivity::class.java)
                .putExtra(EXTRA_SOURCE_PATH, sourcePath)
                .putExtra(EXTRA_TITLE, title)
    }

    private var webView: WebView? = null
    private lateinit var source: File
    private lateinit var content: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView
    private var failed = false
    private var dark = false
    private var actionMenu: FlClashPopupMenu? = null
    private val english get() = AppLocaleManager.isEnglish(this)
    private fun text(zh: String, en: String) = if (english) en else zh

    private val saveDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        if (uri != null && ::source.isInitialized) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri, "w")?.use { output ->
                            source.inputStream().use { it.copyTo(output) }
                        } ?: error("Cannot open destination")
                    }
                    toast(text("已保存到设备", "Saved to device"))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    toast(text("保存失败", "Save failed"))
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = when (StartupThemeResolver.readStoredThemeMode(this)) {
            StartupThemeResolver.StartupThemeMode.DARK -> true
            StartupThemeResolver.StartupThemeMode.LIGHT -> false
            StartupThemeResolver.StartupThemeMode.SYSTEM ->
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_SOURCE_PATH)
        val file = path?.takeIf { it.isNotBlank() }?.let(::File)
        if (file == null || !file.isFile || !file.canRead() ||
            file.extension.lowercase() !in setOf("html", "htm")) {
            toast(text("HTML 文件不存在或不可读", "HTML file is missing or unreadable"))
            finish()
            return
        }
        source = file
        buildContent(intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: file.name)
        createWebView(savedInstanceState?.getBundle(WEB_STATE))
    }

    private fun buildContent(title: String) {
        val background = if (dark) Color.rgb(21, 21, 21) else Color.rgb(246, 246, 246)
        val foreground = if (dark) Color.rgb(243, 243, 243) else Color.rgb(22, 22, 22)
        window.setBackgroundDrawable(ColorDrawable(background))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = FrameLayout(this)
        toolbar.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(foreground)
            gravity = Gravity.CENTER
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, FrameLayout.LayoutParams(-1, -1).apply {
            marginStart = dp(56); marginEnd = dp(56)
        })
        toolbar.addView(iconButton(R.drawable.ic_html_preview_back,
            text("返回", "Back"), foreground) { finish() },
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
        val more = iconButton(R.drawable.ic_html_preview_more,
            text("更多操作", "More actions"), foreground) { showMenu(it) }
        more.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (dark) Color.rgb(33, 33, 33) else Color.WHITE)
            cornerRadius = dp(22).toFloat()
        }
        toolbar.addView(more,
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(16)
            })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        errorView = TextView(this).apply {
            setTextColor(foreground)
            setBackgroundColor(background)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
            setOnClickListener { reload() }
        }
        content.addView(errorView, FrameLayout.LayoutParams(-1, -1))
        content.addView(progress, FrameLayout.LayoutParams(-1, dp(2), Gravity.TOP))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun iconButton(icon: Int, label: String, color: Int, action: (View) -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(color)
            contentDescription = label
            val attribute = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, attribute, true)
            setBackgroundResource(attribute.resourceId)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener(action)
        }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createWebView(savedState: Bundle? = null) {
        failed = false
        errorView.visibility = View.GONE
        progress.visibility = View.VISIBLE
        val view = WebView(this)
        webView = view
        content.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Match the former webview_flutter viewport so existing HTML keeps its layout.
            useWideViewPort = false
            loadWithOverviewMode = true
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== webView) return
                progress.progress = newProgress
                progress.visibility = if (failed || newProgress == 100) View.GONE else View.VISIBLE
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (view !== webView) return
                failed = false
                errorView.visibility = View.GONE
                progress.visibility = View.VISIBLE
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Never dispatch arbitrary intent:/custom-scheme links into app components.
                return request.url.scheme?.lowercase() !in setOf("file", "http", "https", "about", "data", "blob")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (view === webView && request.isForMainFrame) {
                    showError(text("HTML 预览失败，点击重试", "HTML preview failed. Tap to retry."))
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === webView) {
                    webView = null
                    content.removeView(view)
                    view.destroy()
                    showError(text("网页进程已退出，点击重新加载", "Web renderer stopped. Tap to reload."))
                }
                return true
            }
        }
        if (savedState == null || view.restoreState(savedState) == null) {
            view.loadUrl(Uri.fromFile(source).toString())
        }
    }

    private fun showError(message: String) {
        failed = true
        progress.visibility = View.GONE
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    private fun reload() {
        if (webView == null) createWebView() else webView?.loadUrl(Uri.fromFile(source).toString())
    }

    private fun showMenu(anchor: View) {
        actionMenu?.dispose()
        actionMenu = FlClashPopupMenu(anchor, dark, listOf(
            FlClashPopupMenu.Item(text("重新加载", "Reload")) { reload() },
            FlClashPopupMenu.Item(text("在浏览器打开", "Open in browser")) { exportFile(2) },
            FlClashPopupMenu.Item(text("保存到设备", "Save to device")) { saveDocument.launch(source.name) },
            FlClashPopupMenu.Item(text("系统打开", "Open with system")) { exportFile(4) },
            FlClashPopupMenu.Item(text("分享文件", "Share file")) { exportFile(5) },
        )).also { it.show() }
    }

    private fun exportFile(action: Int) {
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { ResourceFileIntents.contentUri(this@HtmlPreviewActivity, source) }
                val target = Intent(if (action == 5) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                    if (action == 5) {
                        type = "text/html"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TITLE, source.name)
                    } else setDataAndType(uri, "text/html")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(contentResolver, source.name, uri)
                }
                if (action == 2) {
                    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                    }
                    val browser = packageManager.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                        ?.activityInfo?.packageName ?: error("No browser available")
                    target.setPackage(browser)
                    startActivity(target)
                } else {
                    startActivity(Intent.createChooser(target, if (action == 5)
                        text("分享文件", "Share file") else text("打开文件", "Open file")))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                toast(text("无法打开或分享文件", "Unable to open or share file"))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        actionMenu?.dispose()
        actionMenu = null
        webView?.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.let { view -> outState.putBundle(WEB_STATE, Bundle().also { view.saveState(it) }) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        actionMenu?.dispose()
        actionMenu = null
        webView?.let { view ->
            webView = null
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.webChromeClient = null
            view.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
