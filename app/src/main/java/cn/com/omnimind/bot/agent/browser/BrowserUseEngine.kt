package cn.com.omnimind.bot.agent

import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest as WebPermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import cn.com.omnimind.baselib.permission.PermissionRequest as RuntimePermissionRequest
import cn.com.omnimind.bot.agent.browser.BrowserToolResultPayloads
import cn.com.omnimind.bot.webchat.FlutterChatSyncBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.json.JSONTokener
import java.io.FileOutputStream
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

private const val NAVIGATION_TIMEOUT_MS = 30_000L
private const val ACTION_SETTLE_DELAY_MS = 250L
private const val LOAD_SETTLE_DELAY_MS = 600L
private const val DESKTOP_VIEWPORT_WIDTH = 1280
private const val DESKTOP_VIEWPORT_HEIGHT = 800
private const val SCREENSHOT_JPEG_QUALITY = 85
private const val READ_IMAGE_JPEG_QUALITY = 75
private const val READ_IMAGE_MAX_WIDTH = 1280
private const val PREVIEW_FRAME_JPEG_QUALITY = 72
private const val PREVIEW_FRAME_MAX_WIDTH = 420
private const val TYPE_INPUT_CHUNK_SIZE = 3
private const val TYPE_INPUT_CHUNK_DELAY_MS = 35L

enum class BrowserUserAgentProfile(
    val wireName: String,
    val userAgentString: String
) {
    DESKTOP_SAFARI(
        wireName = "desktop_safari",
        userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    ),
    MOBILE_SAFARI(
        wireName = "mobile_safari",
        userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    );

    companion object {
        fun fromWire(value: String?): BrowserUserAgentProfile? {
            val normalized = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { it.wireName == normalized }
        }

        fun defaultProfile(): BrowserUserAgentProfile = MOBILE_SAFARI
    }
}

enum class BrowserUseAction(val wireName: String) {
    NAVIGATE("navigate"),
    SCREENSHOT("screenshot"),
    CLICK("click"),
    TYPE("type"),
    GET_TEXT("get_text"),
    SCROLL("scroll"),
    GET_PAGE_INFO("get_page_info"),
    EXECUTE_JS("execute_js"),
    FIND_ELEMENTS("find_elements"),
    HOVER("hover"),
    GET_READABLE("get_readable"),
    SET_USER_AGENT("set_user_agent"),
    GET_BACKBONE("get_backbone"),
    FETCH("fetch"),
    NEW_TAB("new_tab"),
    CLOSE_TAB("close_tab"),
    LIST_TABS("list_tabs"),
    GET_COOKIES("get_cookies"),
    SCROLL_AND_COLLECT("scroll_and_collect"),
    GO_BACK("go_back"),
    GO_FORWARD("go_forward"),
    PRESS_KEY("press_key"),
    WAIT_FOR_SELECTOR("wait_for_selector");

    companion object {
        fun fromWire(value: String?): BrowserUseAction? {
            val normalized = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

data class BrowserUseRequest(
    val toolTitle: String,
    val action: BrowserUseAction,
    val text: String? = null,
    val url: String? = null,
    val userAgent: BrowserUserAgentProfile? = null,
    val script: String? = null,
    val coordinateX: Int? = null,
    val coordinateY: Int? = null,
    val amount: Int? = null,
    val keywords: List<String> = emptyList(),
    val itemSelector: String? = null,
    val direction: String? = null,
    val tabId: Int? = null,
    val selector: String? = null,
    val fuzzy: Boolean = true,
    val readImage: Boolean = false,
    val key: String? = null,
    val timeoutMs: Long? = null,
    val maxDepth: Int? = null,
    val scrollCount: Int? = null
) {
    companion object {
        fun fromJson(args: JsonObject): BrowserUseRequest {
            val action = BrowserUseAction.fromWire(
                args["action"]?.jsonPrimitive?.contentOrNull
            ) ?: throw IllegalArgumentException("browser_use action 非法或缺失")
            val toolTitle = args["tool_title"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: action.wireName

            val coordinateX = args["coordinate_x"]?.jsonPrimitive?.intOrNull
            val coordinateY = args["coordinate_y"]?.jsonPrimitive?.intOrNull
            if ((coordinateX == null) != (coordinateY == null)) {
                throw IllegalArgumentException("coordinate_x 和 coordinate_y 必须同时提供")
            }

            val amount = args["amount"]?.jsonPrimitive?.intOrNull
            val maxDepth = args["max_depth"]?.jsonPrimitive?.intOrNull
            val scrollCount = args["scroll_count"]?.jsonPrimitive?.intOrNull
            val fuzzy = args["fuzzy"]?.jsonPrimitive?.booleanOrNull ?: true
            val readImage = args["read_image"]?.jsonPrimitive?.booleanOrNull ?: false
            val key = args["key"]?.jsonPrimitive?.contentOrNull
            val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull
                ?.takeIf { it > 0L }

            val request = BrowserUseRequest(
                toolTitle = toolTitle,
                action = action,
                text = args["text"]?.jsonPrimitive?.contentOrNull,
                url = args["url"]?.jsonPrimitive?.contentOrNull,
                userAgent = BrowserUserAgentProfile.fromWire(
                    args["user_agent"]?.jsonPrimitive?.contentOrNull
                ),
                script = args["script"]?.jsonPrimitive?.contentOrNull,
                coordinateX = coordinateX,
                coordinateY = coordinateY,
                amount = amount,
                keywords = BrowserUseSupport.normalizeKeywords(args["keywords"]),
                itemSelector = args["item_selector"]?.jsonPrimitive?.contentOrNull,
                direction = args["direction"]?.jsonPrimitive?.contentOrNull,
                tabId = args["tab_id"]?.jsonPrimitive?.intOrNull,
                selector = args["selector"]?.jsonPrimitive?.contentOrNull,
                fuzzy = fuzzy,
                readImage = readImage,
                key = key,
                timeoutMs = timeoutMs,
                maxDepth = maxDepth,
                scrollCount = scrollCount
            )
            request.validate()
            return request
        }
    }

    private fun validate() {
        when (action) {
            BrowserUseAction.SCROLL -> require(amount != null && amount > 0) {
                "scroll 需要提供正数 amount"
            }
            BrowserUseAction.SCROLL_AND_COLLECT -> {
                require(scrollCount != null && scrollCount > 0) {
                    "scroll_and_collect 需要提供正数 scroll_count"
                }
                require(amount != null && amount > 0) {
                    "scroll_and_collect 需要提供正数 amount"
                }
            }
            BrowserUseAction.GET_BACKBONE -> require(maxDepth != null && maxDepth > 0) {
                "get_backbone 需要提供正数 max_depth"
            }
            BrowserUseAction.NAVIGATE -> require(!url.isNullOrBlank()) { "navigate 缺少 url" }
            BrowserUseAction.CLICK,
            BrowserUseAction.HOVER -> require(hasSelectorOrCoordinates()) {
                "${action.wireName} 需要 selector 或 coordinate_x/coordinate_y"
            }
            BrowserUseAction.TYPE -> {
                require(!text.isNullOrBlank()) { "type 缺少 text" }
                require(hasSelectorOrCoordinates()) {
                    "type 需要 selector 或 coordinate_x/coordinate_y"
                }
            }
            BrowserUseAction.EXECUTE_JS -> require(!script.isNullOrBlank()) { "execute_js 缺少 script" }
            BrowserUseAction.SET_USER_AGENT -> require(userAgent != null) { "set_user_agent 缺少 user_agent" }
            BrowserUseAction.FETCH -> require(!url.isNullOrBlank()) { "fetch 缺少 url" }
            BrowserUseAction.PRESS_KEY -> require(!key.isNullOrBlank()) { "press_key 缺少 key" }
            BrowserUseAction.WAIT_FOR_SELECTOR -> {
                require(!selector.isNullOrBlank()) { "wait_for_selector 缺少 selector" }
                require(timeoutMs != null && timeoutMs > 0) { "wait_for_selector 需要提供正数 timeout_ms" }
            }
            else -> Unit
        }
    }

    fun hasCoordinates(): Boolean = coordinateX != null && coordinateY != null

    fun hasSelectorOrCoordinates(): Boolean {
        return !selector.isNullOrBlank() || hasCoordinates()
    }
}

data class BrowserUseOutcome(
    val summaryText: String,
    val payload: Map<String, Any?>,
    val artifacts: List<ArtifactRef> = emptyList(),
    val actions: List<ArtifactAction> = emptyList(),
    val imageDataUrl: String? = null
)

object BrowserUseSupport {
    fun normalizeKeywords(element: JsonElement?): List<String> {
        return when (element) {
            null,
            JsonNull -> emptyList()
            is JsonArray -> element.mapNotNull { entry ->
                (entry as? JsonPrimitive)?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            is JsonPrimitive -> element.contentOrNull
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.mapNotNull { value -> value.trim().takeIf { it.isNotEmpty() } }
                ?: emptyList()
            else -> emptyList()
        }
    }

    fun filterCookieNames(
        names: Collection<String>,
        keywords: List<String>,
        fuzzy: Boolean
    ): List<String> {
        if (keywords.isEmpty()) return names.sortedBy { it.lowercase(Locale.ROOT) }
        val normalizedKeywords = keywords.map { it.lowercase(Locale.ROOT) }
        return names.filter { name ->
            val normalizedName = name.lowercase(Locale.ROOT)
            if (fuzzy) {
                normalizedKeywords.all { keyword -> normalizedName.contains(keyword) }
            } else {
                normalizedKeywords.any { keyword -> normalizedName == keyword }
            }
        }.sortedBy { it.lowercase(Locale.ROOT) }
    }

    /**
     * Select only explicitly requested cookies. A non-empty filter that
     * matches nothing is a safe failure, never permission to broaden the
     * export to every cookie in the current browser session.
     */
    fun selectCookieNames(
        names: Collection<String>,
        keywords: List<String>,
        fuzzy: Boolean
    ): List<String> {
        val selected = filterCookieNames(names, keywords, fuzzy)
        require(keywords.isEmpty() || selected.isNotEmpty() || names.isEmpty()) {
            "没有匹配的 Cookie，已拒绝扩大导出范围"
        }
        return selected
    }

    fun sanitizeCookieEnvName(cookieName: String): String {
        val normalized = cookieName.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9_]"), "_")
            .trim('_')
        return if (normalized.isBlank()) {
            "COOKIE_VALUE"
        } else {
            "COOKIE_$normalized"
        }
    }

    fun escapeShellValue(value: String): String {
        return value.replace("'", "'\"'\"'")
    }
}

class BrowserUseEngine(
    private val context: Context,
    private val workspaceManager: AgentWorkspaceManager,
    agentRunId: String,
    workspace: AgentWorkspaceDescriptor
) : AgentBrowserLiveSessionHandle {
    private data class LoadSnapshot(
        val url: String?,
        val title: String?,
        val errorMessage: String? = null,
        val reusedExistingPage: Boolean = false,
        val throttleDelayMs: Long = 0L
    )

    private data class BrowserTab(
        val tabId: Int,
        val contextWrapper: MutableContextWrapper,
        val webView: WebView,
        var userAgentProfile: BrowserUserAgentProfile,
        var currentUrl: String? = null,
        var title: String? = null,
        var lastError: String? = null,
        var isLoading: Boolean = false,
        var loadWaiter: CompletableDeferred<LoadSnapshot>? = null,
        var helpersInjected: Boolean = false,
        var downloadHelperInjected: Boolean = false,
        var hasSslError: Boolean = false,
        var lastHttpStatusCode: Int? = null,
        var riskChallengeDetected: Boolean = false,
        var riskChallengeKind: String? = null,
        var recommendedNextAction: String? = null,
        var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null,
        val pageMenuCommands: LinkedHashMap<String, BrowserUserscriptMenuCommand> = linkedMapOf()
    )

    private data class PendingExternalOpen(
        val requestId: String,
        val title: String,
        val target: String
    )

    private data class PendingDialog(
        val requestId: String,
        val type: String,
        val message: String,
        val url: String? = null,
        val defaultValue: String? = null,
        val jsResult: JsResult? = null,
        val jsPromptResult: JsPromptResult? = null
    )

    private data class PendingPermissionPrompt(
        val requestId: String,
        val kind: String,
        val origin: String,
        val resources: List<String>,
        val webPermissionRequest: WebPermissionRequest? = null,
        val geolocationCallback: GeolocationPermissions.Callback? = null
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }
    companion object {
        fun unavailableSnapshot(workspaceId: String = ""): Map<String, Any?> {
            return linkedMapOf(
                "available" to false,
                "workspaceId" to workspaceId,
                "activeTabId" to null,
                "currentUrl" to "",
                "title" to "",
                "userAgentProfile" to null,
                "canGoBack" to false,
                "canGoForward" to false,
                "isLoading" to false,
                "hasSslError" to false,
                "isDesktopMode" to false,
                "riskChallengeDetected" to false,
                "riskChallengeKind" to null,
                "recommendedNextAction" to null,
                "throttleDelayMs" to null,
                "tabs" to emptyList<Map<String, Any?>>(),
                "bookmarks" to emptyList<Map<String, Any?>>(),
                "history" to emptyList<Map<String, Any?>>(),
                "downloads" to emptyList<Map<String, Any?>>(),
                "downloadSummary" to emptyMap<String, Any?>(),
                "sessionHistory" to emptyList<Map<String, Any?>>(),
                "externalOpenPrompt" to null,
                "pendingDialog" to null,
                "permissionPrompt" to null,
                "userscriptSummary" to mapOf(
                    "installedScripts" to emptyList<Map<String, Any?>>(),
                    "currentPageMenuCommands" to emptyList<Map<String, Any?>>(),
                    "pendingInstall" to null
                )
            )
        }

        private const val JS_HELPERS_FULL = """
                function normalizeText(value) {
                    return String(value || '').replace(/\s+/g, ' ').trim();
                }
                function isVisible(node) {
                    if (!node || !(node instanceof Element)) return false;
                    const style = window.getComputedStyle(node);
                    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
                    const rect = node.getBoundingClientRect();
                    return rect.width > 0 && rect.height > 0;
                }
                function isInteractive(node) {
                    if (!node || !(node instanceof Element)) return false;
                    const tag = (node.tagName || '').toLowerCase();
                    return ['a', 'button', 'input', 'textarea', 'select', 'summary'].includes(tag) ||
                        !!node.getAttribute('onclick') ||
                        !!node.getAttribute('role') && ['button', 'link', 'textbox', 'tab'].includes(node.getAttribute('role'));
                }
                function selectorForElement(node) {
                    if (!node || !(node instanceof Element)) return null;
                    if (node.id) return '#' + node.id;
                    const classes = Array.from(node.classList || []).join('.');
                    return node.tagName.toLowerCase() + (classes ? '.' + classes : '');
                }
                function describeElement(node) {
                    if (!node || !(node instanceof Element)) return null;
                    const rect = node.getBoundingClientRect();
                    return {
                        tag: (node.tagName || '').toLowerCase(),
                        id: node.id || null,
                        classes: Array.from(node.classList || []),
                        text: normalizeText(node.innerText || node.textContent || ''),
                        href: node.getAttribute('href'),
                        role: node.getAttribute('role'),
                        visible: isVisible(node),
                        selector: selectorForElement(node),
                        bounds: {
                            x: Math.round(rect.left),
                            y: Math.round(rect.top),
                            width: Math.round(rect.width),
                            height: Math.round(rect.height)
                        }
                    };
                }
                function resolveTarget(selector, coordinateX, coordinateY) {
                    if (selector) {
                        return Array.from(document.querySelectorAll(selector)).find(isVisible) || document.querySelector(selector);
                    }
                    if (coordinateX !== null && coordinateY !== null) {
                        return document.elementFromPoint(coordinateX, coordinateY);
                    }
                    return null;
                }
                function resolveScrollable(explicitNode, explicitSelector) {
                    if (explicitNode) {
                        if (!(explicitNode instanceof Element)) {
                            throw new Error('Scrollable target not found: ' + explicitSelector);
                        }
                        return explicitNode;
                    }
                    const scrollingElement = document.scrollingElement || document.documentElement || document.body;
                    const candidates = Array.from(document.querySelectorAll('*'))
                        .filter(function(el) {
                            const style = window.getComputedStyle(el);
                            const overflowY = style.overflowY || '';
                            return isVisible(el) &&
                                el.scrollHeight > el.clientHeight + 40 &&
                                (overflowY.includes('auto') || overflowY.includes('scroll'));
                        })
                        .sort(function(a, b) { return (b.scrollHeight * Math.max(b.clientWidth, 1)) - (a.scrollHeight * Math.max(a.clientWidth, 1)); });
                    return candidates[0] || scrollingElement;
                }
                function scrollTopOf(target) {
                    if (target === document.body || target === document.documentElement || target === document.scrollingElement) {
                        return window.scrollY || 0;
                    }
                    return target.scrollTop || 0;
                }
                function scrollTarget(target, delta) {
                    if (target === document.body || target === document.documentElement || target === document.scrollingElement) {
                        window.scrollBy(0, delta);
                        return;
                    }
                    target.scrollBy(0, delta);
                }
                function detectCollectionSelector() {
                    var candidates = ['article','[role="article"]','[role="listitem"]','[data-testid]','li','.item','.card','main article','main li'];
                    var best = null;
                    var bestCount = 0;
                    candidates.forEach(function(sel) {
                        var count = Array.from(document.querySelectorAll(sel)).filter(isVisible).length;
                        if (count > bestCount) { best = sel; bestCount = count; }
                    });
                    if (bestCount >= 3) return best;
                    return 'body > *';
                }
                function safeSerialize(value, seen) {
                    if (value === null || value === undefined) return null;
                    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return value;
                    if (value instanceof Element) return describeElement(value);
                    var visited = seen || new WeakSet();
                    if (visited.has(value)) return '[Circular]';
                    visited.add(value);
                    if (Array.isArray(value)) {
                        var arrayResult = value.map(function(item) { return safeSerialize(item, visited); });
                        visited.delete(value);
                        return arrayResult;
                    }
                    if (typeof value === 'object') {
                        var result = {};
                        Object.keys(value).forEach(function(key) {
                            try { result[key] = safeSerialize(value[key], visited); } catch(_) {}
                        });
                        visited.delete(value);
                        return result;
                    }
                    return String(value);
                }
                if (!window.__omni) {
                    window.__omni = {
                        normalizeText: normalizeText,
                        isVisible: isVisible,
                        isInteractive: isInteractive,
                        selectorForElement: selectorForElement,
                        describeElement: describeElement,
                        resolveTarget: resolveTarget,
                        resolveScrollable: resolveScrollable,
                        scrollTopOf: scrollTopOf,
                        scrollTarget: scrollTarget,
                        detectCollectionSelector: detectCollectionSelector,
                        safeSerialize: safeSerialize
                    };
                }
        """
    }
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // A WebView has one mutable tab set and one active tab. Keep operations on
    // a workspace ordered, while allowing different workspace engines to run
    // independently.
    private val actionMutex = Mutex()
    private val tabs = linkedMapOf<Int, BrowserTab>()
    private var nextTabId = 0
    private var activeTabId: Int? = null
    private val appContext = context.applicationContext
    private val viewportWidth = appContext.resources.displayMetrics.widthPixels.coerceAtLeast(1080)
    private val viewportHeight = appContext.resources.displayMetrics.heightPixels.coerceAtLeast(1920)
    private val density = appContext.resources.displayMetrics.density
    private var currentAgentRunId: String = agentRunId
    @Volatile
    private var activeAgentRunId: String? = null
    private var currentWorkspace: AgentWorkspaceDescriptor = workspace
    private val hostStore = BrowserHostStore.create(workspace.id)
    private var defaultUserAgentProfile =
        if (hostStore.getDesktopModeEnabled(defaultValue = false)) {
            BrowserUserAgentProfile.DESKTOP_SAFARI
        } else {
            BrowserUserAgentProfile.MOBILE_SAFARI
        }
    private val hostActionTimestampsMs = linkedMapOf<String, Long>()
    private val downloadManager = BrowserDownloadManager(
        context = appContext,
        workspaceId = workspace.id,
        store = hostStore,
        onChanged = ::publishSnapshotUpdate
    )
    @Volatile
    private var attachedContainer: ViewGroup? = null
    @Volatile
    private var pendingExternalOpen: PendingExternalOpen? = null
    @Volatile
    private var pendingDialog: PendingDialog? = null
    @Volatile
    private var pendingPermissionPrompt: PendingPermissionPrompt? = null

    override val workspaceId: String
        get() = currentWorkspace.id

    suspend fun handleHostCall(
        method: String,
        arguments: Map<String, Any?> = emptyMap()
    ): Any? {
        return actionMutex.withLock {
            handleHostCallInternal(method = method, arguments = arguments)
        }
    }

    private suspend fun handleHostCallInternal(
        method: String,
        arguments: Map<String, Any?> = emptyMap()
    ): Any? {
        return when (method) {
            "getSnapshot",
            "getLiveBrowserSessionSnapshot" -> liveSessionSnapshot()
            "capturePreviewFrame" -> {
                captureActivePreviewFrame(
                    maxWidth = arguments["maxWidth"].asInt()?.coerceIn(120, 960)
                        ?: PREVIEW_FRAME_MAX_WIDTH,
                    quality = arguments["quality"].asInt()?.coerceIn(40, 92)
                        ?: PREVIEW_FRAME_JPEG_QUALITY
                )
            }
            "navigate" -> {
                hostNavigate(
                    url = arguments["url"]?.toString(),
                    tabId = arguments["tabId"].asInt()
                )
                liveSessionSnapshot()
            }
            "reload" -> {
                hostReload(arguments["tabId"].asInt())
                liveSessionSnapshot()
            }
            "stopLoading" -> {
                hostStopLoading(arguments["tabId"].asInt())
                liveSessionSnapshot()
            }
            "newTab" -> {
                hostNewTab(arguments["url"]?.toString())
                liveSessionSnapshot()
            }
            "selectTab" -> {
                hostSelectTab(arguments["tabId"].asInt())
                liveSessionSnapshot()
            }
            "closeTab" -> {
                hostCloseTab(arguments["tabId"].asInt())
                liveSessionSnapshot()
            }
            "goBack" -> {
                hostGoBack(arguments["tabId"].asInt())
                liveSessionSnapshot()
            }
            "goForward" -> {
                hostGoForward(arguments["tabId"].asInt())
                liveSessionSnapshot()
            }
            "toggleDesktopMode" -> {
                hostToggleDesktopMode(arguments["tabId"].asInt())
                liveSessionSnapshot()
            }
            "toggleBookmark" -> {
                hostToggleBookmark()
                liveSessionSnapshot()
            }
            "removeBookmark" -> {
                hostStore.removeBookmark(arguments["url"]?.toString())
                publishSnapshotUpdate()
                liveSessionSnapshot()
            }
            "openHistoryEntry" -> {
                hostNavigate(url = arguments["url"]?.toString(), tabId = arguments["tabId"].asInt())
                liveSessionSnapshot()
            }
            "clearHistory" -> {
                hostStore.clearHistory()
                publishSnapshotUpdate()
                liveSessionSnapshot()
            }
            "clearCurrentSiteSession" -> {
                clearCurrentSiteSession(arguments["tabId"].asInt())
                liveSessionSnapshot()
            }
            "installUserscriptFromUrl" -> {
                prepareUserscriptInstallFromUrl(arguments["url"]?.toString())
                liveSessionSnapshot()
            }
            "importUserscriptSource" -> {
                prepareUserscriptInstallFromSource(
                    source = arguments["source"]?.toString(),
                    sourceName = arguments["sourceName"]?.toString(),
                    sourceUrl = arguments["sourceUrl"]?.toString()
                )
                liveSessionSnapshot()
            }
            "confirmUserscriptInstall" -> {
                confirmPendingUserscriptInstall()
                liveSessionSnapshot()
            }
            "cancelUserscriptInstall" -> {
                hostStore.writePendingUserscript(null)
                publishSnapshotUpdate()
                liveSessionSnapshot()
            }
            "setUserscriptEnabled" -> {
                setUserscriptEnabled(
                    scriptId = arguments["scriptId"].asLong(),
                    enabled = arguments["enabled"] == true
                )
                liveSessionSnapshot()
            }
            "deleteUserscript" -> {
                deleteUserscript(arguments["scriptId"].asLong())
                liveSessionSnapshot()
            }
            "checkUserscriptUpdate" -> {
                prepareUserscriptUpdate(arguments["scriptId"].asLong())
                liveSessionSnapshot()
            }
            "invokeUserscriptMenuCommand" -> {
                invokeUserscriptMenu(arguments["commandId"]?.toString())
                liveSessionSnapshot()
            }
            "pauseDownload" -> {
                downloadManager.pause(arguments["taskId"]?.toString().orEmpty())
                liveSessionSnapshot()
            }
            "resumeDownload" -> {
                downloadManager.resume(arguments["taskId"]?.toString().orEmpty())
                liveSessionSnapshot()
            }
            "cancelDownload" -> {
                downloadManager.cancel(arguments["taskId"]?.toString().orEmpty())
                liveSessionSnapshot()
            }
            "retryDownload" -> {
                downloadManager.retry(arguments["taskId"]?.toString().orEmpty())
                liveSessionSnapshot()
            }
            "deleteDownload" -> {
                downloadManager.delete(
                    taskId = arguments["taskId"]?.toString().orEmpty(),
                    deleteFile = arguments["deleteFile"] == true
                )
                liveSessionSnapshot()
            }
            "openDownloadedFile" -> {
                openDownloadedFile(arguments["taskId"]?.toString().orEmpty())
                liveSessionSnapshot()
            }
            "openDownloadLocation" -> {
                openDownloadedLocation(arguments["taskId"]?.toString().orEmpty())
                liveSessionSnapshot()
            }
            "confirmExternalOpen" -> {
                confirmExternalOpen(arguments["requestId"]?.toString())
                liveSessionSnapshot()
            }
            "cancelExternalOpen" -> {
                cancelExternalOpen(arguments["requestId"]?.toString())
                liveSessionSnapshot()
            }
            "resolveDialog" -> {
                resolveDialog(
                    requestId = arguments["requestId"]?.toString(),
                    accept = arguments["accept"] == true,
                    promptValue = arguments["promptValue"]?.toString()
                )
                liveSessionSnapshot()
            }
            "grantPermission" -> {
                grantPendingPermission(arguments["requestId"]?.toString())
                liveSessionSnapshot()
            }
            "denyPermission" -> {
                denyPendingPermission(arguments["requestId"]?.toString())
                liveSessionSnapshot()
            }
            else -> throw IllegalArgumentException("Unsupported browser host call: $method")
        }
    }

    suspend fun execute(request: BrowserUseRequest): BrowserUseOutcome {
        return actionMutex.withLock { executeInternal(request) }
    }

    suspend fun execute(
        request: BrowserUseRequest,
        agentRunId: String,
        workspace: AgentWorkspaceDescriptor,
    ): BrowserUseOutcome {
        return actionMutex.withLock {
            bindRunContext(agentRunId = agentRunId, workspace = workspace)
            activeAgentRunId = agentRunId
            try {
                executeInternal(request)
            } finally {
                activeAgentRunId = null
            }
        }
    }

    private suspend fun executeInternal(request: BrowserUseRequest): BrowserUseOutcome {
        return when (request.action) {
            BrowserUseAction.NEW_TAB -> executeNewTab(request)
            BrowserUseAction.LIST_TABS -> simpleOutcome(request, listTabsPayload())
            BrowserUseAction.CLOSE_TAB -> executeCloseTab(request)
            BrowserUseAction.NAVIGATE -> executeNavigate(request)
            BrowserUseAction.SCREENSHOT -> executeScreenshot(request)
            BrowserUseAction.CLICK -> executeClick(request)
            BrowserUseAction.HOVER -> executeHover(request)
            BrowserUseAction.TYPE -> executeType(request)
            BrowserUseAction.GET_TEXT -> executeGetText(request)
            BrowserUseAction.GET_READABLE -> executeGetReadable(request)
            BrowserUseAction.SCROLL -> executeScroll(request)
            BrowserUseAction.SCROLL_AND_COLLECT -> executeScrollAndCollect(request)
            BrowserUseAction.FIND_ELEMENTS -> executeFindElements(request)
            BrowserUseAction.GET_PAGE_INFO -> executeGetPageInfo(request)
            BrowserUseAction.GET_BACKBONE -> executeGetBackbone(request)
            BrowserUseAction.EXECUTE_JS -> executeCustomScript(request)
            BrowserUseAction.SET_USER_AGENT -> executeSetUserAgent(request)
            BrowserUseAction.FETCH -> executeFetch(request)
            BrowserUseAction.GET_COOKIES -> executeGetCookies(request)
            BrowserUseAction.GO_BACK -> executeGoBack(request)
            BrowserUseAction.GO_FORWARD -> executeGoForward(request)
            BrowserUseAction.PRESS_KEY -> executePressKey(request)
            BrowserUseAction.WAIT_FOR_SELECTOR -> executeWaitForSelector(request)
        }
    }

    fun bindRunContext(
        agentRunId: String,
        workspace: AgentWorkspaceDescriptor
    ) {
        currentAgentRunId = agentRunId
        currentWorkspace = workspace
    }

    fun liveSessionSnapshot(): Map<String, Any?> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return liveSessionSnapshotOnMain()
        }
        return runBlocking(Dispatchers.Main.immediate) {
            liveSessionSnapshotOnMain()
        }
    }

    private fun liveSessionSnapshotOnMain(): Map<String, Any?> {
        val tab = activeTabId?.let { tabs[it] } ?: tabs.values.lastOrNull()
        if (tab == null) {
            return unavailableSnapshot(workspaceId = workspaceId)
        }
        activeTabId = tab.tabId
        val sessionHistory = buildSessionHistory(tab)
        val downloads = downloadManager.snapshot()
        val pendingInstall = hostStore.readPendingUserscript()
        return linkedMapOf(
            "available" to true,
            "workspaceId" to workspaceId,
            "activeTabId" to tab.tabId,
            "currentUrl" to (tab.currentUrl ?: ""),
            "title" to (tab.title ?: ""),
            "userAgentProfile" to tab.userAgentProfile.wireName,
            "isBookmarked" to hostStore.isBookmarked(tab.currentUrl),
            "canGoBack" to tab.webView.canGoBack(),
            "canGoForward" to tab.webView.canGoForward(),
            "isLoading" to tab.isLoading,
            "hasSslError" to tab.hasSslError,
            "isDesktopMode" to (tab.userAgentProfile == BrowserUserAgentProfile.DESKTOP_SAFARI),
            "riskChallengeDetected" to tab.riskChallengeDetected,
            "riskChallengeKind" to tab.riskChallengeKind,
            "recommendedNextAction" to tab.recommendedNextAction,
            "throttleDelayMs" to null,
            "tabs" to tabs.values.map { item ->
                linkedMapOf(
                    "tabId" to item.tabId,
                    "url" to item.currentUrl,
                    "title" to item.title,
                    "userAgentProfile" to item.userAgentProfile.wireName,
                    "isActive" to (item.tabId == activeTabId),
                    "isLoading" to item.isLoading,
                    "hasSslError" to item.hasSslError,
                    "riskChallengeDetected" to item.riskChallengeDetected,
                    "riskChallengeKind" to item.riskChallengeKind,
                    "recommendedNextAction" to item.recommendedNextAction
                )
            },
            "bookmarks" to hostStore.listBookmarks().map { item ->
                linkedMapOf(
                    "url" to item.url,
                    "title" to item.title,
                    "createdAt" to item.createdAt,
                    "updatedAt" to item.updatedAt
                )
            },
            "history" to hostStore.listHistory().map { item ->
                linkedMapOf(
                    "url" to item.url,
                    "title" to item.title,
                    "visitedAt" to item.visitedAt
                )
            },
            "activeDownloadCount" to downloadManager.activeCount(),
            "downloads" to downloads.map { item ->
                buildDownloadPayload(item)
            },
            "downloadSummary" to linkedMapOf(
                "activeCount" to downloadManager.activeCount(),
                "failedCount" to downloadManager.failedCount(),
                "overallProgress" to downloadManager.overallProgress(),
                "latestCompletedFileName" to downloadManager.latestCompletedFileName()
            ),
            "sessionHistory" to sessionHistory,
            "externalOpenPrompt" to pendingExternalOpen?.let { prompt ->
                linkedMapOf(
                    "requestId" to prompt.requestId,
                    "title" to prompt.title,
                    "target" to prompt.target
                )
            },
            "pendingDialog" to pendingDialog?.let { prompt ->
                linkedMapOf(
                    "requestId" to prompt.requestId,
                    "type" to prompt.type,
                    "message" to prompt.message,
                    "url" to prompt.url,
                    "defaultValue" to prompt.defaultValue
                )
            },
            "permissionPrompt" to pendingPermissionPrompt?.let { prompt ->
                linkedMapOf(
                    "requestId" to prompt.requestId,
                    "kind" to prompt.kind,
                    "origin" to prompt.origin,
                    "resources" to prompt.resources
                )
            },
            "userscriptSummary" to linkedMapOf(
                "installedScripts" to hostStore.listUserscripts().map { item ->
                    linkedMapOf(
                        "id" to item.id,
                        "name" to item.name,
                        "description" to item.description,
                        "version" to item.version,
                        "enabled" to item.enabled,
                        "blockedGrants" to item.blockedGrants,
                        "grants" to item.grants,
                        "sourceUrl" to item.sourceUrl,
                        "matches" to item.matches,
                        "includes" to item.includes,
                        "excludes" to item.excludes,
                        "runAt" to item.runAt
                    )
                },
                "currentPageMenuCommands" to tab.pageMenuCommands.values.map { command ->
                    linkedMapOf(
                        "commandId" to command.commandId,
                        "scriptId" to command.scriptId,
                        "title" to command.title
                    )
                },
                "pendingInstall" to pendingInstall?.let { item ->
                    linkedMapOf(
                        "id" to item.id,
                        "name" to item.name,
                        "description" to item.description,
                        "version" to item.version,
                        "blockedGrants" to item.blockedGrants,
                        "grants" to item.grants,
                        "sourceUrl" to item.sourceUrl,
                        "matches" to item.matches,
                        "includes" to item.includes,
                        "excludes" to item.excludes,
                        "runAt" to item.runAt,
                        "isUpdate" to hostStore.listUserscripts().any { it.id == item.id }
                    )
                }
            )
        )
    }

    private fun buildDownloadPayload(item: BrowserDownloadTaskRecord): Map<String, Any?> {
        val total = item.totalBytes
        val progress = if (total > 0L) {
            item.downloadedBytes.toDouble() / total.toDouble()
        } else {
            null
        }
        return linkedMapOf(
            "id" to item.id,
            "fileName" to item.fileName,
            "url" to item.url,
            "mimeType" to item.mimeType,
            "destinationPath" to item.destinationPath,
            "status" to item.status,
            "progress" to progress,
            "downloadedBytes" to item.downloadedBytes,
            "totalBytes" to item.totalBytes,
            "errorMessage" to item.errorMessage,
            "canPause" to (item.status == BrowserDownloadManager.STATUS_QUEUED || item.status == BrowserDownloadManager.STATUS_DOWNLOADING),
            "canResume" to (item.status == BrowserDownloadManager.STATUS_PAUSED || item.status == BrowserDownloadManager.STATUS_CANCELED),
            "canCancel" to (item.status == BrowserDownloadManager.STATUS_QUEUED || item.status == BrowserDownloadManager.STATUS_DOWNLOADING),
            "canRetry" to (item.status == BrowserDownloadManager.STATUS_FAILED),
            "canDelete" to true,
            "canDeleteFile" to true,
            "canOpenFile" to (item.status == BrowserDownloadManager.STATUS_COMPLETED),
            "canOpenLocation" to (item.status == BrowserDownloadManager.STATUS_COMPLETED),
            "supportsResume" to item.supportsResume
        )
    }

    private fun buildSessionHistory(tab: BrowserTab): List<Map<String, Any?>> {
        val list = tab.webView.copyBackForwardList()
        if (list.size == 0) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until list.size) {
                val item = list.getItemAtIndex(index)
                add(
                    linkedMapOf(
                        "index" to index,
                        "title" to item.title,
                        "url" to item.url,
                        "isCurrent" to (index == list.currentIndex)
                    )
                )
            }
        }
    }

    private fun Any?.asInt(): Int? {
        return when (this) {
            is Int -> this
            is Long -> toInt()
            is Double -> toInt()
            is Float -> toInt()
            is Number -> toInt()
            else -> toString().toIntOrNull()
        }
    }

    private fun Any?.asLong(): Long? {
        return when (this) {
            is Long -> this
            is Int -> toLong()
            is Double -> toLong()
            is Float -> toLong()
            is Number -> toLong()
            else -> toString().toLongOrNull()
        }
    }

    private fun publishSnapshotUpdate() {
        FlutterChatSyncBridge.dispatchBrowserSnapshotUpdated(liveSessionSnapshot())
    }

    private suspend fun hostNavigate(
        url: String?,
        tabId: Int?
    ) {
        val tab = tabId?.let { requireExistingTab(it) } ?: tabs[activeTabId] ?: createTab()
        navigateTab(tab, url)
        publishSnapshotUpdate()
    }

    private suspend fun hostReload(tabId: Int?) {
        val tab = tabId?.let { requireExistingTab(it) } ?: requirePageTab(
            BrowserUseRequest(toolTitle = "reload", action = BrowserUseAction.GET_PAGE_INFO)
        )
        withContext(Dispatchers.Main.immediate) {
            tab.webView.reload()
        }
        publishSnapshotUpdate()
    }

    private suspend fun hostStopLoading(tabId: Int?) {
        val tab = tabId?.let { requireExistingTab(it) } ?: requireTabForMutation(
            BrowserUseRequest(toolTitle = "stop", action = BrowserUseAction.GET_PAGE_INFO)
        )
        withContext(Dispatchers.Main.immediate) {
            tab.webView.stopLoading()
            tab.isLoading = false
        }
        publishSnapshotUpdate()
    }

    private suspend fun hostNewTab(url: String?) {
        val tab = createTab()
        if (!url.isNullOrBlank()) {
            navigateTab(tab, url)
        }
        reattachActiveTabIfNeeded()
        publishSnapshotUpdate()
    }

    private suspend fun hostSelectTab(tabId: Int?) {
        val resolvedTabId = tabId ?: throw IllegalArgumentException("缺少 tabId")
        requireExistingTab(resolvedTabId)
        activeTabId = resolvedTabId
        reattachActiveTabIfNeeded()
        publishSnapshotUpdate()
    }

    private suspend fun hostCloseTab(tabId: Int?) {
        val resolvedTabId = tabId ?: activeTabId ?: throw IllegalArgumentException("当前没有活动标签页")
        val tab = requireExistingTab(resolvedTabId)
        withContext(Dispatchers.Main.immediate) {
            tabs.remove(tab.tabId)
            if (activeTabId == tab.tabId) {
                activeTabId = tabs.keys.lastOrNull()
            }
            runCatching {
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                tab.webView.stopLoading()
                tab.webView.destroy()
            }
        }
        reattachActiveTabIfNeeded()
        publishSnapshotUpdate()
    }

    private suspend fun hostGoBack(tabId: Int?) {
        val tab = tabId?.let { requireExistingTab(it) } ?: requirePageTab(
            BrowserUseRequest(toolTitle = "back", action = BrowserUseAction.GET_PAGE_INFO)
        )
        withContext(Dispatchers.Main.immediate) {
            require(tab.webView.canGoBack()) { "当前页面无法后退" }
            tab.webView.goBack()
        }
        publishSnapshotUpdate()
    }

    private suspend fun hostGoForward(tabId: Int?) {
        val tab = tabId?.let { requireExistingTab(it) } ?: requirePageTab(
            BrowserUseRequest(toolTitle = "forward", action = BrowserUseAction.GET_PAGE_INFO)
        )
        withContext(Dispatchers.Main.immediate) {
            require(tab.webView.canGoForward()) { "当前页面无法前进" }
            tab.webView.goForward()
        }
        publishSnapshotUpdate()
    }

    private suspend fun hostToggleDesktopMode(tabId: Int?) {
        val tab = tabId?.let { requireExistingTab(it) } ?: tabs[activeTabId] ?: createTab()
        defaultUserAgentProfile = if (tab.userAgentProfile == BrowserUserAgentProfile.DESKTOP_SAFARI) {
            BrowserUserAgentProfile.MOBILE_SAFARI
        } else {
            BrowserUserAgentProfile.DESKTOP_SAFARI
        }
        hostStore.setDesktopModeEnabled(defaultUserAgentProfile == BrowserUserAgentProfile.DESKTOP_SAFARI)
        withContext(Dispatchers.Main.immediate) {
            tab.userAgentProfile = defaultUserAgentProfile
            tab.webView.settings.userAgentString = defaultUserAgentProfile.userAgentString
        }
        if (!tab.currentUrl.isNullOrBlank() && tab.currentUrl != "about:blank") {
            navigateTab(tab, tab.currentUrl, allowReuse = false)
        }
        publishSnapshotUpdate()
    }

    private fun hostToggleBookmark() {
        val tab = activeTabId?.let { tabs[it] } ?: tabs.values.lastOrNull() ?: return
        hostStore.toggleBookmark(tab.currentUrl, tab.title)
        publishSnapshotUpdate()
    }

    private suspend fun clearCurrentSiteSession(tabId: Int?) {
        val tab = tabId?.let { requireExistingTab(it) } ?: requirePageTab(
            BrowserUseRequest(toolTitle = "clear site", action = BrowserUseAction.GET_PAGE_INFO)
        )
        val currentUrl = tab.currentUrl?.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        } ?: return
        val cookieNames = collectCookiesForUrl(currentUrl).keys
        withContext(Dispatchers.Main.immediate) {
            val cookieManager = CookieManager.getInstance()
            buildCookieLookupUrls(currentUrl).forEach { cookieUrl ->
                cookieNames.forEach { name ->
                    cookieManager.setCookie(
                        cookieUrl,
                        "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/"
                    )
                }
            }
            cookieManager.flush()
        }
        runCatching {
            evaluateJavascriptRaw(
                tab,
                """
                    (function() {
                        try { window.localStorage && window.localStorage.clear(); } catch (_) {}
                        try { window.sessionStorage && window.sessionStorage.clear(); } catch (_) {}
                        return 'cleared';
                    })();
                """.trimIndent()
            )
        }
        clearRiskChallengeState(tab)
        publishSnapshotUpdate()
    }

    private suspend fun reattachActiveTabIfNeeded() {
        val container = attachedContainer ?: return
        val tab = activeTabId?.let { tabs[it] } ?: return
        withContext(Dispatchers.Main.immediate) {
            tab.contextWrapper.baseContext = container.context
            val currentParent = tab.webView.parent as? ViewGroup
            if (currentParent !== container) {
                currentParent?.removeView(tab.webView)
                container.removeAllViews()
                container.addView(
                    tab.webView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            layoutWebView(tab.webView)
        }
    }

    suspend fun requestInterruptCurrentAction(agentRunId: String? = null) {
        if (agentRunId != null && activeAgentRunId != null && activeAgentRunId != agentRunId) {
            return
        }
        withContext(Dispatchers.Main.immediate) {
            tabs.values.forEach { tab ->
                runCatching { tab.webView.stopLoading() }
                tab.isLoading = false
                tab.loadWaiter?.cancel(ManualToolStopCancellationException())
                tab.loadWaiter = null
            }
        }
    }

    suspend fun captureActiveFramePng(): ByteArray? {
        val tab = activeTabId?.let { tabs[it] } ?: tabs.values.lastOrNull() ?: return null
        activeTabId = tab.tabId
        val (vpWidth, vpHeight) = viewportDimensionsForProfile(tab.userAgentProfile)
        return withContext(Dispatchers.Main.immediate) {
            val (captureWidth, captureHeight) = layoutWebView(tab.webView, vpWidth, vpHeight)
            if (tab.webView.windowToken == null) {
                tab.webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            val bitmap = Bitmap.createBitmap(
                captureWidth,
                captureHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            tab.webView.draw(canvas)
            val bytes = ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
            bitmap.recycle()
            bytes
        }
    }

    private suspend fun captureActivePreviewFrame(
        maxWidth: Int,
        quality: Int
    ): Map<String, Any?> {
        val tab = activeTabId?.let { tabs[it] } ?: tabs.values.lastOrNull()
            ?: return unavailableSnapshot(workspaceId = workspaceId)
        activeTabId = tab.tabId
        val (vpWidth, vpHeight) = viewportDimensionsForProfile(tab.userAgentProfile)
        return withContext(Dispatchers.Main.immediate) {
            val (captureWidth, captureHeight) = layoutWebView(tab.webView, vpWidth, vpHeight)
            if (tab.webView.windowToken == null) {
                tab.webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            val bitmap = Bitmap.createBitmap(
                captureWidth,
                captureHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            tab.webView.draw(canvas)
            val imageDataUrl = bitmapToDataUrl(bitmap, maxWidth, quality)
            bitmap.recycle()
            linkedMapOf(
                "available" to true,
                "workspaceId" to workspaceId,
                "activeTabId" to tab.tabId,
                "currentUrl" to (tab.currentUrl ?: ""),
                "title" to (tab.title ?: ""),
                "isLoading" to tab.isLoading,
                "imageDataUrl" to imageDataUrl,
                "imageWidth" to captureWidth,
                "imageHeight" to captureHeight
            )
        }
    }

    fun attachActiveTabTo(
        container: ViewGroup,
        hostContext: Context
    ): Boolean {
        attachedContainer = container
        val tab = activeTabId?.let { tabs[it] } ?: tabs.values.lastOrNull() ?: return false
        activeTabId = tab.tabId
        tab.contextWrapper.baseContext = hostContext
        val currentParent = tab.webView.parent as? ViewGroup
        if (currentParent !== container) {
            currentParent?.removeView(tab.webView)
            container.removeAllViews()
            container.addView(
                tab.webView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        tab.webView.post { layoutWebView(tab.webView) }
        return true
    }

    fun detachActiveTabFrom(container: ViewGroup? = null) {
        if (container != null && attachedContainer === container) {
            attachedContainer = null
        }
        val tab = activeTabId?.let { tabs[it] } ?: tabs.values.lastOrNull() ?: return
        val parent = tab.webView.parent as? ViewGroup ?: return
        if (container == null || parent === container) {
            parent.removeView(tab.webView)
        }
        tab.contextWrapper.baseContext = appContext
    }

    override fun closeSession() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyOnMain()
            return
        }
        runBlocking(Dispatchers.Main.immediate) {
            destroyOnMain()
        }
    }

    private fun destroyOnMain() {
        tabs.values.forEach { tab ->
            runCatching {
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                tab.webView.stopLoading()
                tab.webView.destroy()
            }
        }
        tabs.clear()
        activeTabId = null
        attachedContainer = null
        downloadManager.shutdown()
        mainScope.cancel()
    }

    private suspend fun executeNewTab(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = createTab()
        var snapshot: LoadSnapshot? = null
        if (!request.url.isNullOrBlank()) {
            snapshot = navigateTab(tab, request.url)
        }
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf(
                    "tabId" to tab.tabId,
                    "currentUrl" to tab.currentUrl,
                    "pageTitle" to tab.title,
                    "tabs" to listTabsPayload()["tabs"],
                    "activeTabId" to activeTabId,
                    "navigationReused" to (snapshot?.reusedExistingPage ?: false)
                ),
                throttleDelayMs = snapshot?.throttleDelayMs
            )
        )
    }

    private suspend fun executeCloseTab(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requireTabForMutation(request)
        withContext(Dispatchers.Main.immediate) {
            tabs.remove(tab.tabId)
            if (activeTabId == tab.tabId) {
                activeTabId = tabs.keys.lastOrNull()
            }
            runCatching {
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                tab.webView.stopLoading()
                tab.webView.destroy()
            }
        }
        return simpleOutcome(
            request,
            mapOf(
                "closedTabId" to tab.tabId,
                "activeTabId" to activeTabId,
                "tabs" to listTabsPayload()["tabs"]
            )
        )
    }

    private suspend fun executeNavigate(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = request.tabId?.let { requireExistingTab(it) } ?: tabs[activeTabId]
            ?: createTab()
        val snapshot = navigateTab(tab, request.url)
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf(
                    "finalUrl" to snapshot.url,
                    "pageTitle" to snapshot.title,
                    "navigationReused" to snapshot.reusedExistingPage,
                    "pageInfo" to pageInfoMap(tab)
                ),
                throttleDelayMs = snapshot.throttleDelayMs
            )
        )
    }

    private suspend fun executeScreenshot(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        val (vpWidth, vpHeight) = viewportDimensionsForProfile(tab.userAgentProfile)
        val screenshotFile = workspaceManager.newBrowserFile(
            agentRunId = currentAgentRunId,
            prefix = "screenshot_tab_${tab.tabId}",
            extension = "jpg"
        )
        var imageDataUrl: String? = null
        var captureWidth = vpWidth
        var captureHeight = vpHeight
        withContext(Dispatchers.Main.immediate) {
            val layoutSize = layoutWebView(tab.webView, vpWidth, vpHeight)
            captureWidth = layoutSize.first
            captureHeight = layoutSize.second
            if (tab.webView.windowToken == null) {
                tab.webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            val bitmap = Bitmap.createBitmap(
                captureWidth,
                captureHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            tab.webView.draw(canvas)
            FileOutputStream(screenshotFile).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, stream)
            }
            if (request.readImage) {
                imageDataUrl = bitmapToDataUrl(bitmap, READ_IMAGE_MAX_WIDTH, READ_IMAGE_JPEG_QUALITY)
            }
            bitmap.recycle()
        }
        val artifact = workspaceManager.buildArtifactForFile(screenshotFile, "browser_use")
        return BrowserUseOutcome(
            summaryText = request.toolTitle,
            payload = buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf(
                    "artifactUri" to artifact.uri,
                    "pageTitle" to tab.title,
                    "imageWidth" to captureWidth,
                    "imageHeight" to captureHeight
                )
            ),
            artifacts = listOf(artifact),
            imageDataUrl = imageDataUrl
        )
    }

    private suspend fun executeClick(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        riskBlockedOutcomeIfNeeded(request, tab)?.let { return it }
        val throttleDelayMs = applyHostThrottle(BrowserUseAction.CLICK, tab.currentUrl)
        val value = evaluateValue(
            tab,
            buildTargetedScript(
                request = request,
                requireVisibleTarget = true,
                command = """
                    target.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: centerX, clientY: centerY }));
                    target.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, clientX: centerX, clientY: centerY }));
                    target.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, clientX: centerX, clientY: centerY }));
                    target.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: centerX, clientY: centerY }));
                    target.click();
                    return describeElement(target);
                """.trimIndent()
            )
        )
        waitForPostActionSettle(tab)
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf("matchedElement" to jsonElementToAny(value)),
                throttleDelayMs = throttleDelayMs
            )
        )
    }

    private suspend fun executeHover(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        riskBlockedOutcomeIfNeeded(request, tab)?.let { return it }
        val value = evaluateValue(
            tab,
            buildTargetedScript(
                request = request,
                requireVisibleTarget = true,
                command = """
                    target.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: centerX, clientY: centerY }));
                    target.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, clientX: centerX, clientY: centerY }));
                    target.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true, clientX: centerX, clientY: centerY }));
                    return describeElement(target);
                """.trimIndent()
            )
        )
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf("matchedElement" to jsonElementToAny(value))
            )
        )
    }

    private suspend fun executeType(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        riskBlockedOutcomeIfNeeded(request, tab)?.let { return it }
        val throttleDelayMs = applyHostThrottle(BrowserUseAction.TYPE, tab.currentUrl)
        val textToType = request.text.orEmpty()
        val chunks = if (textToType.isEmpty()) {
            listOf("")
        } else {
            textToType.chunked(TYPE_INPUT_CHUNK_SIZE)
        }
        var value: JsonElement = JsonNull
        chunks.forEachIndexed { index, chunk ->
            value = evaluateValue(
                tab,
                buildTargetedScript(
                    request = request,
                    requireVisibleTarget = true,
                    command = buildTypingChunkCommand(
                        chunk = chunk,
                        reset = index == 0,
                        finalChunk = index == chunks.lastIndex
                    )
                )
            )
            if (index != chunks.lastIndex) {
                delay(TYPE_INPUT_CHUNK_DELAY_MS)
            }
        }
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf(
                    "typedTextLength" to request.text?.length,
                    "typedChunkCount" to chunks.size,
                    "matchedElement" to jsonElementToAny(value)
                ),
                throttleDelayMs = throttleDelayMs
            )
        )
    }

    private suspend fun executeGetText(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        val value = evaluateValue(
            tab,
            """
                const selector = ${request.selector?.let { JSONObject.quote(it) } ?: "null"};
                let target = null;
                if (selector) {
                    target = Array.from(document.querySelectorAll(selector)).find(isVisible) || document.querySelector(selector);
                    if (!target) {
                        throw new Error('Element not found for selector: ' + selector);
                    }
                }
                const text = normalizeText((target ? (target.innerText || target.textContent) : document.body.innerText) || '');
                return {
                    selectorUsed: selector,
                    text: text,
                    textLength: text.length
                };
            """.trimIndent()
        ).jsonObject
        return buildTextOutcome(
            request = request,
            tab = tab,
            text = value["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            extra = mapOf("selectorUsed" to value["selectorUsed"]?.jsonPrimitive?.contentOrNull)
        )
    }

    private suspend fun executeGetReadable(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        val value = evaluateValue(
            tab,
            """
                const candidates = Array.from(document.querySelectorAll('main, article, [role="main"], .article, .content, .post, .entry-content'))
                    .filter(isVisible);
                const target = candidates.sort((a, b) => normalizeText((b.innerText || b.textContent || '')).length - normalizeText((a.innerText || a.textContent || '')).length)[0] || document.body;
                const text = normalizeText((target.innerText || target.textContent || ''));
                return {
                    selectorUsed: selectorForElement(target),
                    text: text,
                    textLength: text.length
                };
            """.trimIndent()
        ).jsonObject
        return buildTextOutcome(
            request = request,
            tab = tab,
            text = value["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            extra = mapOf("selectorUsed" to value["selectorUsed"]?.jsonPrimitive?.contentOrNull)
        )
    }

    private suspend fun executeScroll(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        riskBlockedOutcomeIfNeeded(request, tab)?.let { return it }
        val amount = requireNotNull(request.amount) { "scroll 缺少 amount" }
        val direction = request.direction?.takeIf { it == "up" || it == "down" } ?: "down"
        val selectorLiteral = request.selector?.let { JSONObject.quote(it) } ?: "null"
        val value = evaluateValue(
            tab,
            """
                const selector = $selectorLiteral;
                const amount = $amount;
                const direction = ${JSONObject.quote(direction)};
                const delta = direction === 'up' ? -amount : amount;
                const target = selector ? resolveScrollable(document.querySelector(selector), selector) : resolveScrollable(null, null);
                const beforeTop = scrollTopOf(target);
                scrollTarget(target, delta);
                const afterTop = scrollTopOf(target);
                return {
                    selectorUsed: selector || selectorForElement(target),
                    beforeTop: beforeTop,
                    afterTop: afterTop,
                    direction: direction,
                    amount: amount
                };
            """.trimIndent()
        )
        delay(ACTION_SETTLE_DELAY_MS)
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf("scroll" to jsonElementToAny(value))
            )
        )
    }

    private suspend fun executeScrollAndCollect(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        riskBlockedOutcomeIfNeeded(request, tab)?.let { return it }
        val throttleDelayMs = applyHostThrottle(BrowserUseAction.SCROLL_AND_COLLECT, tab.currentUrl)
        val scrollCount = requireNotNull(request.scrollCount) { "scroll_and_collect 缺少 scroll_count" }
        val seen = linkedSetOf<String>()
        var selectorUsed: String? = request.itemSelector
        repeat(scrollCount) { index ->
            val value = evaluateValue(
                tab,
                BrowserObservationScripts.scrollAndCollect(
                    explicitSelectorLiteral = request.itemSelector
                        ?.let { JSONObject.quote(it) }
                        ?: "null"
                )
            ).jsonObject
            selectorUsed = value["selectorUsed"]?.jsonPrimitive?.contentOrNull ?: selectorUsed
            value["items"]?.jsonArray?.forEach { item ->
                item.jsonPrimitive.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { seen.add(it) }
            }
            if (index != scrollCount - 1) {
                executeScroll(
                    request.copy(
                        direction = request.direction ?: "down",
                        selector = request.selector,
                        toolTitle = request.toolTitle
                    )
                )
                delay(ACTION_SETTLE_DELAY_MS)
            }
        }
        return buildCollectionOutcome(
            request = request,
            tab = tab,
            selectorUsed = selectorUsed,
            items = seen.toList(),
            throttleDelayMs = throttleDelayMs
        )
    }

    private suspend fun executeFindElements(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        val selectorLiteral = request.selector?.let { JSONObject.quote(it) }
            ?: JSONObject.quote("a, button, input, textarea, select, [role=\"button\"], [onclick], [tabindex]")
        val value = evaluateValue(
            tab,
            BrowserObservationScripts.findElements(selectorLiteral)
        )
        val elements = (jsonElementToAny(value) as? List<*>) ?: emptyList<Any?>()
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf(
                    "selectorUsed" to (request.selector ?: "interactive_default"),
                    "elementCount" to elements.size,
                    "elements" to elements
                )
            )
        )
    }

    private suspend fun executeGetPageInfo(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        inspectPageRiskChallenge(tab)
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf("pageInfo" to pageInfoMap(tab))
            )
        )
    }

    private suspend fun executeGetBackbone(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        val maxDepth = requireNotNull(request.maxDepth) { "get_backbone 缺少 max_depth" }
        val value = evaluateValue(
            tab,
            BrowserObservationScripts.backbone(maxDepth)
        )
        return buildStructuredOutcome(
            request = request,
            tab = tab,
            label = "backbone",
            value = value,
            extension = "json"
        )
    }

    private suspend fun executeCustomScript(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        riskBlockedOutcomeIfNeeded(request, tab)?.let { return it }
        val value = evaluateValue(tab, request.script)
        return buildStructuredOutcome(
            request = request,
            tab = tab,
            label = "result",
            value = value,
            extension = "json"
        )
    }

    private suspend fun executeSetUserAgent(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = request.tabId?.let { requireExistingTab(it) } ?: tabs[activeTabId]
            ?: createTab()
        var snapshot: LoadSnapshot? = null
        withContext(Dispatchers.Main.immediate) {
            tab.userAgentProfile = request.userAgent ?: BrowserUserAgentProfile.defaultProfile()
            tab.webView.settings.userAgentString = tab.userAgentProfile.userAgentString
        }
        if (!tab.currentUrl.isNullOrBlank() && tab.currentUrl != "about:blank") {
            snapshot = navigateTab(tab, tab.currentUrl, allowReuse = false)
        }
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf("userAgentProfile" to tab.userAgentProfile.wireName),
                throttleDelayMs = snapshot?.throttleDelayMs
            )
        )
    }

    private suspend fun executeFetch(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        riskBlockedOutcomeIfNeeded(request, tab)?.let { return it }
        val targetUrl = request.url?.trim().orEmpty()
        require(targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
            "fetch 仅支持 http(s) 资源"
        }
        val cookieHeader = CookieManager.getInstance().getCookie(targetUrl).orEmpty()
        val connection = withContext(Dispatchers.IO) {
            (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", tab.userAgentProfile.userAgentString)
                if (cookieHeader.isNotBlank()) {
                    setRequestProperty("Cookie", cookieHeader)
                }
                setRequestProperty("Accept", "*/*")
            }
        }
        val responseCode = withContext(Dispatchers.IO) { connection.responseCode }
        if (responseCode >= 400) {
            val message = withContext(Dispatchers.IO) {
                runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }.orEmpty()
            val challenge = BrowserRiskControl.detectChallenge(
                statusCode = responseCode,
                bodyText = message,
                currentUrl = targetUrl
            )
            if (challenge != null) {
                connection.disconnect()
                if (applyRiskChallengeState(tab, challenge)) {
                    publishSnapshotUpdate()
                }
                return BrowserUseOutcome(
                    summaryText = request.toolTitle,
                    payload = buildCommonPayload(
                        tab = tab,
                        action = request.action,
                        extra = mapOf(
                            "statusCode" to responseCode,
                            "responseSnippet" to message,
                            "blockedByRiskChallenge" to true
                        )
                    )
                )
            }
            connection.disconnect()
            throw IllegalStateException("fetch 失败：HTTP $responseCode ${message.ifBlank { "" }}".trim())
        }
        val fileName = resolveDownloadFileName(connection, targetUrl)
        val extension = fileName.substringAfterLast('.', "").ifBlank {
            extensionFromContentType(connection.contentType)
        }
        val targetFile = workspaceManager.newBrowserFile(
            agentRunId = currentAgentRunId,
            prefix = fileName.substringBeforeLast('.').ifBlank { "fetch" },
            extension = extension.ifBlank { "bin" }
        )
        withContext(Dispatchers.IO) {
            connection.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            connection.disconnect()
        }
        val artifact = workspaceManager.buildArtifactForFile(targetFile, "browser_use")
        return BrowserUseOutcome(
            summaryText = request.toolTitle,
            payload = buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf(
                    "statusCode" to responseCode,
                    "artifactUri" to artifact.uri,
                    "mimeType" to workspaceManager.guessMimeType(targetFile),
                    "size" to targetFile.length()
                )
            ),
            artifacts = listOf(artifact)
        )
    }

    private suspend fun executeGetCookies(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        val currentUrl = tab.currentUrl?.takeIf { it.isNotBlank() && it != "about:blank" }
            ?: throw IllegalStateException("当前标签页没有可用页面，无法读取 cookies")
        val cookiePairs = collectCookiesForUrl(currentUrl).toList()
        val matchedNames = BrowserUseSupport.selectCookieNames(
            names = cookiePairs.map { it.first },
            keywords = request.keywords,
            fuzzy = request.fuzzy
        )
        val exportedPairs = cookiePairs.filter { (name, _) -> matchedNames.contains(name) }
        val exportedNames = exportedPairs.map { it.first }
        val envFile = workspaceManager.newOffloadFile(
            agentRunId = currentAgentRunId,
            prefix = "env_cookies",
            extension = "sh"
        )
        val filteredHeader = exportedPairs.joinToString("; ") { (name, value) -> "$name=$value" }
        val script = buildString {
            appendLine("#!/bin/sh")
            appendLine("export BROWSER_COOKIE_HEADER='${BrowserUseSupport.escapeShellValue(filteredHeader)}'")
            appendLine("export BROWSER_COOKIE_COUNT='${exportedPairs.size}'")
            exportedPairs.forEach { (name, value) ->
                appendLine(
                    "export ${BrowserUseSupport.sanitizeCookieEnvName(name)}='${BrowserUseSupport.escapeShellValue(value)}'"
                )
            }
        }
        envFile.writeText(script)
        val envShellPath = workspaceManager.shellPathForAndroid(envFile) ?: envFile.absolutePath
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf(
                    "siteRoot" to siteRootFromUrl(currentUrl),
                    "matchedCount" to exportedPairs.size,
                    "cookieNames" to matchedNames,
                    "availableCookieNames" to cookiePairs.map { it.first },
                    "exportedCookieNames" to exportedNames,
                    "envShellPath" to envShellPath,
                    "envAndroidPath" to envFile.absolutePath,
                    "cookieLookupUrls" to buildCookieLookupUrls(currentUrl),
                    "fuzzy" to request.fuzzy,
                    "keywords" to request.keywords
                )
            )
        )
    }

    private suspend fun executeGoBack(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        val canGoBack = withContext(Dispatchers.Main.immediate) { tab.webView.canGoBack() }
        if (!canGoBack) {
            throw IllegalStateException("当前页面无法后退")
        }
        val waiter = CompletableDeferred<LoadSnapshot>()
        tab.loadWaiter = waiter
        withContext(Dispatchers.Main.immediate) { tab.webView.goBack() }
        try {
            withTimeout(NAVIGATION_TIMEOUT_MS) { waiter.await() }
        } catch (_: TimeoutCancellationException) {
            withContext(Dispatchers.Main.immediate) { tab.webView.stopLoading(); tab.isLoading = false }
            throw IllegalStateException("页面后退加载超时（${NAVIGATION_TIMEOUT_MS / 1000}秒）")
        }
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf("pageTitle" to tab.title, "currentUrl" to tab.currentUrl)
            )
        )
    }

    private suspend fun executeGoForward(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        val canGoForward = withContext(Dispatchers.Main.immediate) { tab.webView.canGoForward() }
        if (!canGoForward) {
            throw IllegalStateException("当前页面无法前进")
        }
        val waiter = CompletableDeferred<LoadSnapshot>()
        tab.loadWaiter = waiter
        withContext(Dispatchers.Main.immediate) { tab.webView.goForward() }
        try {
            withTimeout(NAVIGATION_TIMEOUT_MS) { waiter.await() }
        } catch (_: TimeoutCancellationException) {
            withContext(Dispatchers.Main.immediate) { tab.webView.stopLoading(); tab.isLoading = false }
            throw IllegalStateException("页面前进加载超时（${NAVIGATION_TIMEOUT_MS / 1000}秒）")
        }
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf("pageTitle" to tab.title, "currentUrl" to tab.currentUrl)
            )
        )
    }

    private suspend fun executePressKey(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        riskBlockedOutcomeIfNeeded(request, tab)?.let { return it }
        val throttleDelayMs = applyHostThrottle(BrowserUseAction.PRESS_KEY, tab.currentUrl)
        val keyLiteral = JSONObject.quote(request.key)
        evaluateValue(
            tab,
            """
                const keyName = $keyLiteral;
                const target = document.activeElement || document.body;
                const opts = { key: keyName, code: keyName, bubbles: true, cancelable: true };
                target.dispatchEvent(new KeyboardEvent('keydown', opts));
                target.dispatchEvent(new KeyboardEvent('keypress', opts));
                target.dispatchEvent(new KeyboardEvent('keyup', opts));
                if (keyName === 'Enter') {
                    const form = target.closest && target.closest('form');
                    if (form) { form.requestSubmit ? form.requestSubmit() : form.submit(); }
                }
                return { key: keyName, targetTag: (target.tagName || '').toLowerCase() };
            """.trimIndent()
        )
        waitForPostActionSettle(tab)
        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf("key" to request.key),
                throttleDelayMs = throttleDelayMs
            )
        )
    }

    private suspend fun executeWaitForSelector(request: BrowserUseRequest): BrowserUseOutcome {
        val tab = requirePageTab(request)
        val timeoutMs = requireNotNull(request.timeoutMs) { "wait_for_selector 缺少 timeout_ms" }
        val selectorLiteral = JSONObject.quote(request.selector)
        val deadline = System.currentTimeMillis() + timeoutMs
        val pollInterval = 250L
        var found = false
        var visible = false

        while (System.currentTimeMillis() < deadline) {
            val result = evaluateValue(
                tab,
                """
                    const el = document.querySelector($selectorLiteral);
                    return { found: !!el, visible: el ? isVisible(el) : false };
                """.trimIndent()
            ).jsonObject
            found = result["found"]?.jsonPrimitive?.booleanOrNull == true
            visible = result["visible"]?.jsonPrimitive?.booleanOrNull == true
            if (found) break
            delay(pollInterval)
        }

        return simpleOutcome(
            request,
            buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf(
                    "selector" to request.selector,
                    "found" to found,
                    "visible" to visible,
                    "timeoutMs" to timeoutMs
                )
            )
        )
    }

    private fun bitmapToDataUrl(
        bitmap: Bitmap,
        maxWidth: Int,
        quality: Int
    ): String {
        val finalBitmap = if (bitmap.width > maxWidth) {
            val scale = maxWidth.toFloat() / bitmap.width
            val scaledHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, maxWidth, scaledHeight, true)
        } else {
            bitmap
        }
        val bytes = ByteArrayOutputStream().use { stream ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }
        if (finalBitmap !== bitmap) {
            finalBitmap.recycle()
        }
        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,$encoded"
    }

    private suspend fun simpleOutcome(
        request: BrowserUseRequest,
        payload: Map<String, Any?>
    ): BrowserUseOutcome {
        return BrowserUseOutcome(
            summaryText = request.toolTitle,
            payload = payload
        )
    }

    private suspend fun buildTextOutcome(
        request: BrowserUseRequest,
        tab: BrowserTab,
        text: String,
        extra: Map<String, Any?> = emptyMap()
    ): BrowserUseOutcome {
        return BrowserUseOutcome(
            summaryText = request.toolTitle,
            payload = buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = BrowserToolResultPayloads.text(text = text, extra = extra)
            )
        )
    }

    private suspend fun buildCollectionOutcome(
        request: BrowserUseRequest,
        tab: BrowserTab,
        selectorUsed: String?,
        items: List<String>,
        throttleDelayMs: Long? = null
    ): BrowserUseOutcome {
        return BrowserUseOutcome(
            summaryText = request.toolTitle,
            payload = buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = BrowserToolResultPayloads.collection(
                    selectorUsed = selectorUsed,
                    items = items
                ),
                throttleDelayMs = throttleDelayMs
            )
        )
    }

    private suspend fun buildStructuredOutcome(
        request: BrowserUseRequest,
        tab: BrowserTab,
        label: String,
        value: JsonElement,
        extension: String
    ): BrowserUseOutcome {
        val extra = linkedMapOf<String, Any?>(label to jsonElementToAny(value))
        return BrowserUseOutcome(
            summaryText = request.toolTitle,
            payload = buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = extra
            )
        )
    }

    private fun buildCommonPayload(
        tab: BrowserTab,
        action: BrowserUseAction,
        extra: Map<String, Any?> = emptyMap(),
        throttleDelayMs: Long? = null
    ): Map<String, Any?> {
        return linkedMapOf<String, Any?>(
            "workspaceId" to workspaceId,
            "action" to action.wireName,
            "tabId" to tab.tabId,
            "currentUrl" to tab.currentUrl,
            "pageTitle" to tab.title,
            "userAgentProfile" to tab.userAgentProfile.wireName,
            "activeTabId" to activeTabId,
            "riskChallengeDetected" to tab.riskChallengeDetected,
            "riskChallengeKind" to tab.riskChallengeKind,
            "recommendedNextAction" to tab.recommendedNextAction,
            "throttleDelayMs" to throttleDelayMs
        ).apply {
            putAll(extra)
        }
    }

    private fun listTabsPayload(): Map<String, Any?> {
        return mapOf(
            "activeTabId" to activeTabId,
            "tabs" to tabs.values.map { tab ->
                mapOf(
                    "tabId" to tab.tabId,
                    "url" to tab.currentUrl,
                    "title" to tab.title,
                    "userAgentProfile" to tab.userAgentProfile.wireName,
                    "isActive" to (tab.tabId == activeTabId),
                    "riskChallengeDetected" to tab.riskChallengeDetected,
                    "riskChallengeKind" to tab.riskChallengeKind,
                    "recommendedNextAction" to tab.recommendedNextAction
                )
            }
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun createTab(
        profile: BrowserUserAgentProfile = defaultUserAgentProfile
    ): BrowserTab {
        return withContext(Dispatchers.Main.immediate) {
            val tab = createTabOnMain(profile)
            publishSnapshotUpdate()
            tab
        }
    }

    @Suppress("DEPRECATION")
    private fun createTabOnMain(
        profile: BrowserUserAgentProfile = defaultUserAgentProfile
    ): BrowserTab {
        require(Looper.myLooper() == Looper.getMainLooper()) { "createTabOnMain must run on main thread" }
        val tabId = ++nextTabId
        val contextWrapper = MutableContextWrapper(appContext)
        val webView = WebView(contextWrapper).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.allowContentAccess = true
            settings.allowFileAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = profile.userAgentString
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
        }
        val tab = BrowserTab(
            tabId = tabId,
            contextWrapper = contextWrapper,
            webView = webView,
            userAgentProfile = profile,
            currentUrl = "about:blank",
            title = "Blank"
        )
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(BrowserUserscriptBridge(tab), "OmniBrowserUserscriptBridge")
        webView.addJavascriptInterface(BrowserDownloadBridge(tab), "OmniBrowserDownloadBridge")
        webView.webChromeClient = BrowserTabChromeClient(tab)
        webView.webViewClient = BrowserTabClient(tab)
        webView.setDownloadListener(createDownloadListener(tab))
        val (vpWidth, vpHeight) = viewportDimensionsForProfile(profile)
        layoutWebView(webView, vpWidth, vpHeight)
        tabs[tabId] = tab
        activeTabId = tabId
        return tab
    }

    private suspend fun requireExistingTab(tabId: Int): BrowserTab {
        return tabs[tabId] ?: throw IllegalArgumentException("未找到 tab_id=$tabId")
    }

    private suspend fun requireTabForMutation(request: BrowserUseRequest): BrowserTab {
        val tabId = request.tabId ?: activeTabId
        require(tabId != null) { "当前没有活动标签页" }
        return requireExistingTab(tabId)
    }

    private suspend fun requirePageTab(request: BrowserUseRequest): BrowserTab {
        val tab = requireTabForMutation(request)
        val currentUrl = tab.currentUrl.orEmpty()
        require(currentUrl.isNotBlank() && currentUrl != "about:blank") {
            "当前标签页没有已打开的页面，请先 navigate"
        }
        activeTabId = tab.tabId
        return tab
    }

    private fun normalizedHttpNavigationUrl(rawUrl: String?): String? {
        return BrowserUrlNormalizer.normalizeHttpUrl(rawUrl)
    }

    private fun shouldReuseCurrentNavigation(
        tab: BrowserTab,
        resolvedUrl: String
    ): Boolean {
        if (tab.isLoading) return false
        val current = normalizedHttpNavigationUrl(tab.currentUrl) ?: return false
        val target = normalizedHttpNavigationUrl(resolvedUrl) ?: return false
        return current == target
    }

    private suspend fun applyHostThrottle(
        action: BrowserUseAction,
        rawUrl: String?
    ): Long {
        if (!BrowserRiskControl.shouldThrottle(action)) return 0L
        val host = BrowserRiskControl.normalizedHost(rawUrl) ?: return 0L
        val baseDelayMs = BrowserRiskControl.baseThrottleDelayMs(action, rawUrl)
        if (baseDelayMs <= 0L) return 0L
        val now = System.currentTimeMillis()
        val elapsed = hostActionTimestampsMs[host]?.let { now - it }
        val delayMs = BrowserRiskControl.computeThrottleDelayMs(
            baseDelayMs = baseDelayMs,
            elapsedSinceLastActionMs = elapsed,
            jitterMs = Random.nextLong(35L, 115L)
        )
        if (delayMs > 0L) {
            delay(delayMs)
        }
        hostActionTimestampsMs[host] = System.currentTimeMillis()
        return delayMs
    }

    private fun applyRiskChallengeState(
        tab: BrowserTab,
        challenge: BrowserRiskChallenge?
    ): Boolean {
        val detected = challenge != null
        val changed = tab.riskChallengeDetected != detected ||
            tab.riskChallengeKind != challenge?.kind ||
            tab.recommendedNextAction != challenge?.recommendedNextAction
        tab.riskChallengeDetected = detected
        tab.riskChallengeKind = challenge?.kind
        tab.recommendedNextAction = challenge?.recommendedNextAction
        return changed
    }

    private fun clearRiskChallengeState(tab: BrowserTab) {
        tab.lastHttpStatusCode = null
        applyRiskChallengeState(tab, null)
    }

    private suspend fun inspectPageRiskChallenge(tab: BrowserTab): BrowserRiskChallenge? {
        val currentUrl = tab.currentUrl.orEmpty()
        val pageChallenge = if (currentUrl.startsWith("http://") || currentUrl.startsWith("https://")) {
            runCatching {
                val value = evaluateValue(
                    tab,
                    """
                        const pageText = [
                            document.title || '',
                            document.body ? (document.body.innerText || document.body.textContent || '') : '',
                            document.documentElement ? (document.documentElement.innerText || '') : ''
                        ].join(' ').replace(/\s+/g, ' ').trim().slice(0, 8000);
                        return {
                            url: String(location.href || ''),
                            title: String(document.title || ''),
                            text: pageText
                        };
                    """.trimIndent()
                ).jsonObject
                BrowserRiskControl.detectChallenge(
                    title = value["title"]?.jsonPrimitive?.contentOrNull ?: tab.title,
                    bodyText = value["text"]?.jsonPrimitive?.contentOrNull,
                    currentUrl = value["url"]?.jsonPrimitive?.contentOrNull ?: tab.currentUrl
                )
            }.getOrNull()
        } else {
            null
        }
        val statusChallenge = BrowserRiskControl.detectChallenge(
            statusCode = tab.lastHttpStatusCode,
            title = tab.title,
            currentUrl = tab.currentUrl
        )
        val challenge = pageChallenge ?: statusChallenge
        if (applyRiskChallengeState(tab, challenge)) {
            publishSnapshotUpdate()
        }
        return challenge
    }

    private fun shouldBlockForRiskChallenge(action: BrowserUseAction): Boolean {
        return when (action) {
            BrowserUseAction.CLICK,
            BrowserUseAction.TYPE,
            BrowserUseAction.SCROLL,
            BrowserUseAction.SCROLL_AND_COLLECT,
            BrowserUseAction.EXECUTE_JS,
            BrowserUseAction.FETCH,
            BrowserUseAction.HOVER,
            BrowserUseAction.PRESS_KEY -> true
            else -> false
        }
    }

    private fun riskBlockedOutcomeIfNeeded(
        request: BrowserUseRequest,
        tab: BrowserTab
    ): BrowserUseOutcome? {
        if (!tab.riskChallengeDetected || !shouldBlockForRiskChallenge(request.action)) {
            return null
        }
        return BrowserUseOutcome(
            summaryText = request.toolTitle,
            payload = buildCommonPayload(
                tab = tab,
                action = request.action,
                extra = mapOf(
                    "blockedByRiskChallenge" to true,
                    "blockedAction" to request.action.wireName
                )
            )
        )
    }

    private suspend fun navigateTab(
        tab: BrowserTab,
        rawUrl: String?,
        allowReuse: Boolean = true
    ): LoadSnapshot {
        val resolvedUrl = resolveNavigateUrl(rawUrl)
        activeTabId = tab.tabId
        if (allowReuse && shouldReuseCurrentNavigation(tab, resolvedUrl)) {
            inspectPageRiskChallenge(tab)
            return LoadSnapshot(
                url = tab.currentUrl,
                title = tab.title,
                errorMessage = tab.lastError,
                reusedExistingPage = true
            )
        }
        val throttleDelayMs = applyHostThrottle(BrowserUseAction.NAVIGATE, resolvedUrl)
        val waiter = CompletableDeferred<LoadSnapshot>()
        tab.loadWaiter = waiter
        tab.lastError = null
        withContext(Dispatchers.Main.immediate) {
            val (vpWidth, vpHeight) = viewportDimensionsForProfile(tab.userAgentProfile)
            layoutWebView(tab.webView, vpWidth, vpHeight)
            tab.webView.loadUrl(resolvedUrl)
        }
        val snapshot = try {
            withTimeout(NAVIGATION_TIMEOUT_MS) { waiter.await() }
        } catch (_: TimeoutCancellationException) {
            withContext(Dispatchers.Main.immediate) {
                tab.webView.stopLoading()
                tab.isLoading = false
            }
            val challenge = if (tab.riskChallengeDetected) {
                BrowserRiskChallenge(
                    kind = tab.riskChallengeKind ?: "risk_challenge",
                    recommendedNextAction = tab.recommendedNextAction
                        ?: "ask_user_to_complete_verification_manually"
                )
            } else {
                inspectPageRiskChallenge(tab)
            }
            if (challenge != null) {
                LoadSnapshot(
                    url = tab.currentUrl ?: resolvedUrl,
                    title = tab.title,
                    errorMessage = null,
                    throttleDelayMs = throttleDelayMs
                )
            } else {
                LoadSnapshot(
                    url = resolvedUrl,
                    title = tab.title,
                    errorMessage = "页面加载超时（${NAVIGATION_TIMEOUT_MS / 1000}秒），请检查网络连接或稍后重试",
                    throttleDelayMs = throttleDelayMs
                )
            }
        }
        if (snapshot.errorMessage.isNullOrBlank()) {
            inspectPageRiskChallenge(tab)
        }
        if (!snapshot.errorMessage.isNullOrBlank()) {
            throw IllegalStateException(snapshot.errorMessage)
        }
        return snapshot.copy(throttleDelayMs = snapshot.throttleDelayMs.coerceAtLeast(throttleDelayMs))
    }

    private suspend fun waitForPostActionSettle(tab: BrowserTab) {
        delay(ACTION_SETTLE_DELAY_MS)
        val waiter = tab.loadWaiter
        if (tab.isLoading && waiter != null && !waiter.isCompleted) {
            try {
                withTimeout(NAVIGATION_TIMEOUT_MS) { waiter.await() }
            } catch (_: TimeoutCancellationException) {
                withContext(Dispatchers.Main.immediate) { tab.webView.stopLoading(); tab.isLoading = false }
            }
        }
    }

    private fun resolveNavigateUrl(rawUrl: String?): String {
        val trimmed = rawUrl?.trim().orEmpty()
        require(trimmed.isNotEmpty()) { "url 不能为空" }
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("omnibot://browser/") -> {
                val file = workspaceManager.resolvePath(
                    inputPath = trimmed,
                    workspace = currentWorkspace,
                    allowRootDirectories = true
                )
                require(file.exists()) { "本地浏览器资源不存在：$trimmed" }
                file.toURI().toString()
            }
            else -> throw IllegalArgumentException("browser_use 仅支持 http(s) 或 omnibot://browser/... 资源")
        }
    }

    private suspend fun pageInfoMap(tab: BrowserTab): Map<String, Any?> {
        val js = evaluateValue(
            tab,
            """
                return {
                    viewportWidth: window.innerWidth,
                    viewportHeight: window.innerHeight,
                    scrollX: window.scrollX || 0,
                    scrollY: window.scrollY || 0,
                    contentWidth: Math.max(document.body.scrollWidth, document.documentElement.scrollWidth),
                    contentHeight: Math.max(document.body.scrollHeight, document.documentElement.scrollHeight)
                };
            """.trimIndent()
        )
        val payload = (jsonElementToAny(js) as? Map<*, *>)?.mapKeys { it.key.toString() }?.toMutableMap()
            ?: linkedMapOf()
        payload["url"] = tab.currentUrl
        payload["title"] = tab.title
        payload["canGoBack"] = withContext(Dispatchers.Main.immediate) { tab.webView.canGoBack() }
        payload["canGoForward"] = withContext(Dispatchers.Main.immediate) { tab.webView.canGoForward() }
        payload["userAgentProfile"] = tab.userAgentProfile.wireName
        payload["riskChallengeDetected"] = tab.riskChallengeDetected
        payload["riskChallengeKind"] = tab.riskChallengeKind
        payload["recommendedNextAction"] = tab.recommendedNextAction
        return payload
    }

    private suspend fun evaluateJavascriptRaw(
        tab: BrowserTab,
        script: String
    ): String {
        return withContext(Dispatchers.Main.immediate) {
            val (vpWidth, vpHeight) = viewportDimensionsForProfile(tab.userAgentProfile)
            layoutWebView(tab.webView, vpWidth, vpHeight)
            suspendCancellableCoroutine { continuation ->
                val callback = ValueCallback<String> { value ->
                    if (continuation.isActive) {
                        continuation.resume(value ?: "null")
                    }
                }
                try {
                    tab.webView.evaluateJavascript(script, callback)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }
        }
    }

    private suspend fun evaluateValue(
        tab: BrowserTab,
        body: String?
    ): JsonElement {
        val helpersBlock = if (tab.helpersInjected) {
            """
                const normalizeText = window.__omni.normalizeText;
                const isVisible = window.__omni.isVisible;
                const isInteractive = window.__omni.isInteractive;
                const selectorForElement = window.__omni.selectorForElement;
                const describeElement = window.__omni.describeElement;
                const resolveTarget = window.__omni.resolveTarget;
                const resolveScrollable = window.__omni.resolveScrollable;
                const scrollTopOf = window.__omni.scrollTopOf;
                const scrollTarget = window.__omni.scrollTarget;
                const detectCollectionSelector = window.__omni.detectCollectionSelector;
                const safeSerialize = window.__omni.safeSerialize;
            """.trimIndent()
        } else {
            JS_HELPERS_FULL
        }
        val wrappedScript = """
            (function() {
                $helpersBlock
                try {
                    const __result = (function() {
                        ${body.orEmpty()}
                    })();
                    return JSON.stringify({ ok: true, value: safeSerialize(__result) });
                } catch (error) {
                    return JSON.stringify({
                        ok: false,
                        error: String(error && error.message ? error.message : error)
                    });
                }
            })();
        """.trimIndent()
        val raw = evaluateJavascriptRaw(tab, wrappedScript)
        tab.helpersInjected = true
        val decoded = decodeJavascriptString(raw)
        val envelope = json.parseToJsonElement(decoded).jsonObject
        val ok = envelope["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (!ok) {
            val message = envelope["error"]?.jsonPrimitive?.contentOrNull ?: "JavaScript 执行失败"
            throw IllegalStateException(message)
        }
        return envelope["value"] ?: JsonNull
    }

    private fun viewportDimensionsForProfile(
        profile: BrowserUserAgentProfile
    ): Pair<Int, Int> {
        return when (profile) {
            BrowserUserAgentProfile.DESKTOP_SAFARI -> {
                val w = (DESKTOP_VIEWPORT_WIDTH * density).toInt().coerceAtLeast(DESKTOP_VIEWPORT_WIDTH)
                val h = (DESKTOP_VIEWPORT_HEIGHT * density).toInt().coerceAtLeast(DESKTOP_VIEWPORT_HEIGHT)
                w to h
            }
            BrowserUserAgentProfile.MOBILE_SAFARI -> {
                viewportWidth to viewportHeight
            }
        }
    }

    private fun layoutWebView(
        webView: WebView,
        targetWidth: Int = viewportWidth,
        targetHeight: Int = viewportHeight
    ): Pair<Int, Int> {
        val parent = webView.parent as? View
        val measuredWidth = listOf(
            webView.width,
            webView.measuredWidth,
            parent?.width ?: 0,
            targetWidth
        )
            .firstOrNull { it > 0 } ?: targetWidth
        val measuredHeight = listOf(
            webView.height,
            webView.measuredHeight,
            parent?.height ?: 0,
            targetHeight
        )
            .firstOrNull { it > 0 } ?: targetHeight
        val widthSpec = View.MeasureSpec.makeMeasureSpec(measuredWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(measuredHeight, View.MeasureSpec.EXACTLY)
        webView.measure(widthSpec, heightSpec)
        webView.layout(0, 0, measuredWidth, measuredHeight)
        return measuredWidth to measuredHeight
    }

    private fun decodeJavascriptString(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return try {
            when (val parsed = JSONTokener(text).nextValue()) {
                is String -> parsed
                else -> text
            }
        } catch (_: Exception) {
            text
        }
    }

    private fun jsonElementToAny(element: JsonElement): Any? {
        return when (element) {
            JsonNull -> null
            is JsonPrimitive -> {
                element.booleanOrNull
                    ?: element.intOrNull
                    ?: element.contentOrNull
            }
            is JsonArray -> element.map { jsonElementToAny(it) }
            is JsonObject -> element.entries.associate { (key, value) ->
                key to jsonElementToAny(value)
            }
        }
    }

    private fun resolveDownloadFileName(connection: HttpURLConnection, url: String): String {
        val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
        Regex("filename\\*=UTF-8''([^;]+)").find(disposition)?.groupValues?.getOrNull(1)?.let { encoded ->
            return sanitizeFileName(URLDecoder.decode(encoded, "UTF-8"))
        }
        Regex("filename=\"?([^\";]+)\"?").find(disposition)?.groupValues?.getOrNull(1)?.let { fileName ->
            return sanitizeFileName(fileName)
        }
        val fromUrl = URL(url).path.substringAfterLast('/').substringBefore('?')
        if (fromUrl.isNotBlank()) {
            return sanitizeFileName(fromUrl)
        }
        val extension = extensionFromContentType(connection.contentType)
        return "fetch_${UUID.randomUUID().toString().take(8)}.${extension.ifBlank { "bin" }}"
    }

    private fun sanitizeFileName(value: String): String {
        return value.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "download.bin" }
    }

    private fun extensionFromContentType(contentType: String?): String {
        return when (contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
            "text/html" -> "html"
            "application/json" -> "json"
            "text/plain" -> "txt"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "application/pdf" -> "pdf"
            else -> "bin"
        }
    }

    private fun siteRootFromUrl(rawUrl: String): String {
        val parsed = URL(rawUrl)
        val portPart = if (parsed.port > 0 && parsed.port != parsed.defaultPort) ":${parsed.port}" else ""
        return "${parsed.protocol}://${parsed.host}$portPart/"
    }

    private suspend fun collectCookiesForUrl(rawUrl: String): LinkedHashMap<String, String> {
        withContext(Dispatchers.Main.immediate) {
            CookieManager.getInstance().flush()
        }
        val cookieManager = CookieManager.getInstance()
        val cookies = linkedMapOf<String, String>()
        buildCookieLookupUrls(rawUrl).forEach { candidateUrl ->
            parseCookieHeader(cookieManager.getCookie(candidateUrl).orEmpty()).forEach { (name, value) ->
                if (!cookies.containsKey(name)) {
                    cookies[name] = value
                }
            }
        }
        return cookies
    }

    private fun buildCookieLookupUrls(rawUrl: String): List<String> {
        return runCatching {
            val parsed = URL(rawUrl)
            val normalizedUri = URI(rawUrl)
            val portPart =
                if (parsed.port > 0 && parsed.port != parsed.defaultPort) ":${parsed.port}" else ""
            val origin = "${parsed.protocol}://${parsed.host}$portPart"
            linkedSetOf(
                rawUrl,
                normalizedUri.run {
                    val path = path?.takeIf { it.isNotBlank() } ?: "/"
                    "$origin$path"
                },
                origin,
                "$origin/"
            ).toList()
        }.getOrElse {
            listOf(rawUrl)
        }
    }

    private fun parseCookieHeader(rawCookieHeader: String): List<Pair<String, String>> {
        return rawCookieHeader.split(';')
            .mapNotNull { pair ->
                val parts = pair.trim().split("=", limit = 2)
                if (parts.size != 2) {
                    null
                } else {
                    parts[0].trim() to parts[1]
                }
            }
    }

    private fun buildTypingChunkCommand(
        chunk: String,
        reset: Boolean,
        finalChunk: Boolean
    ): String {
        val chunkLiteral = JSONObject.quote(chunk)
        val resetLiteral = if (reset) "true" else "false"
        val finalLiteral = if (finalChunk) "true" else "false"
        return """
            target.focus();
            const chunk = $chunkLiteral;
            const shouldReset = $resetLiteral;
            const finalChunk = $finalLiteral;
            function setNativeValue(node, value) {
                var descriptor = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                if (!descriptor || !descriptor.set) {
                    descriptor = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value');
                }
                if (descriptor && descriptor.set) {
                    descriptor.set.call(node, value);
                } else {
                    node.value = value;
                }
            }
            function dispatchInput(kind, data) {
                try {
                    target.dispatchEvent(new InputEvent(kind, {
                        bubbles: true,
                        cancelable: true,
                        inputType: data ? 'insertText' : 'deleteContentBackward',
                        data: data || null
                    }));
                } catch (_) {
                    target.dispatchEvent(new Event(kind, { bubbles: true, cancelable: true }));
                }
            }
            function dispatchKeys(data) {
                Array.from(String(data || '')).forEach(function(ch) {
                    const opts = { key: ch, bubbles: true, cancelable: true };
                    target.dispatchEvent(new KeyboardEvent('keydown', opts));
                    target.dispatchEvent(new KeyboardEvent('keypress', opts));
                    target.dispatchEvent(new KeyboardEvent('keyup', opts));
                });
            }
            if (target.isContentEditable) {
                if (shouldReset) {
                    target.textContent = '';
                    dispatchInput('input', '');
                }
                dispatchKeys(chunk);
                if (document.execCommand) {
                    document.execCommand('insertText', false, chunk);
                } else {
                    target.textContent = String(target.textContent || '') + chunk;
                }
                dispatchInput('input', chunk);
            } else if ('value' in target) {
                if (shouldReset) {
                    setNativeValue(target, '');
                    if (target.setSelectionRange) {
                        target.setSelectionRange(0, 0);
                    }
                    dispatchInput('input', '');
                }
                dispatchKeys(chunk);
                const current = String(target.value || '');
                const start = typeof target.selectionStart === 'number' ? target.selectionStart : current.length;
                const end = typeof target.selectionEnd === 'number' ? target.selectionEnd : start;
                const nextValue = current.slice(0, start) + chunk + current.slice(end);
                setNativeValue(target, nextValue);
                const caret = start + chunk.length;
                if (target.setSelectionRange) {
                    target.setSelectionRange(caret, caret);
                }
                dispatchInput('input', chunk);
            } else {
                throw new Error('Target element is not editable');
            }
            if (finalChunk) {
                target.dispatchEvent(new Event('change', { bubbles: true }));
            }
            return describeElement(target);
        """.trimIndent()
    }

    private fun buildTargetedScript(
        request: BrowserUseRequest,
        command: String,
        requireVisibleTarget: Boolean = false
    ): String {
        val selectorLiteral = request.selector?.let { JSONObject.quote(it) } ?: "null"
        val xLiteral = request.coordinateX?.toString() ?: "null"
        val yLiteral = request.coordinateY?.toString() ?: "null"
        val visibleCheck = if (requireVisibleTarget) {
            """
            if (!isVisible(target)) {
                throw new Error('Target element is not visible');
            }
            """.trimIndent()
        } else {
            ""
        }
        return """
            const selector = $selectorLiteral;
            const coordinateX = $xLiteral;
            const coordinateY = $yLiteral;
            const target = resolveTarget(selector, coordinateX, coordinateY);
            if (!target) {
                throw new Error('Target element not found');
            }
            $visibleCheck
            const rect = target.getBoundingClientRect();
            const centerX = Math.round(rect.left + rect.width / 2);
            const centerY = Math.round(rect.top + rect.height / 2);
            $command
        """.trimIndent()
    }

    private suspend fun prepareUserscriptInstallFromUrl(url: String?) {
        val sourceUrl = url?.trim().orEmpty()
        require(sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")) {
            "Userscript 安装地址仅支持 http(s)"
        }
        val source = withContext(Dispatchers.IO) {
            URL(sourceUrl).openStream().bufferedReader().use { it.readText() }
        }
        prepareUserscriptInstallFromSource(
            source = source,
            sourceName = sourceUrl.substringAfterLast('/'),
            sourceUrl = sourceUrl
        )
    }

    private suspend fun prepareUserscriptInstallFromSource(
        source: String?,
        sourceName: String?,
        sourceUrl: String?
    ) {
        val scriptSource = source?.trim().orEmpty()
        require(scriptSource.isNotBlank()) { "Userscript 内容不能为空" }
        val preview = BrowserUserscriptSupport.parseSource(
            source = scriptSource,
            sourceUrl = sourceUrl?.takeIf { it.isNotBlank() }
        )
        val existing = hostStore.listUserscripts().firstOrNull { item ->
            sourceUrl?.takeIf { it.isNotBlank() }?.let { item.sourceUrl == it } == true ||
                item.name == preview.metadata.name
        }
        val name = preview.metadata.name.ifBlank {
            sourceName?.takeIf { it.isNotBlank() } ?: "Userscript"
        }
        val now = System.currentTimeMillis()
        val pendingRecord = BrowserUserscriptSupport.toRecord(
            preview = preview.copy(
                metadata = preview.metadata.copy(name = name)
            ),
            scriptId = existing?.id ?: hostStore.nextUserscriptId(),
            now = now,
            enabled = existing?.enabled ?: true
        )
        hostStore.writePendingUserscript(pendingRecord)
        publishSnapshotUpdate()
    }

    private suspend fun confirmPendingUserscriptInstall() {
        val pending = hostStore.readPendingUserscript()
            ?: throw IllegalStateException("当前没有待安装的 userscript")
        require(pending.blockedGrants.isEmpty()) {
            "当前脚本包含未实现的 grants：${pending.blockedGrants.joinToString(", ")}"
        }
        require(BrowserUserscriptSupport.isSupportedRunAt(pending.runAt)) {
            "当前仅支持 document-end 注入"
        }
        hostStore.writePendingUserscript(null)
        hostStore.upsertUserscript(pending)
        refreshUserscriptState()
        publishSnapshotUpdate()
    }

    private suspend fun setUserscriptEnabled(
        scriptId: Long?,
        enabled: Boolean
    ) {
        val resolvedScriptId = scriptId ?: throw IllegalArgumentException("缺少 scriptId")
        val current = hostStore.listUserscripts().firstOrNull { it.id == resolvedScriptId }
            ?: throw IllegalArgumentException("未找到 userscript=$resolvedScriptId")
        hostStore.upsertUserscript(current.copy(enabled = enabled))
        refreshUserscriptState()
        publishSnapshotUpdate()
    }

    private suspend fun deleteUserscript(scriptId: Long?) {
        val resolvedScriptId = scriptId ?: throw IllegalArgumentException("缺少 scriptId")
        hostStore.removeUserscript(resolvedScriptId)
        refreshUserscriptState()
        publishSnapshotUpdate()
    }

    private suspend fun prepareUserscriptUpdate(scriptId: Long?) {
        val resolvedScriptId = scriptId ?: throw IllegalArgumentException("缺少 scriptId")
        val current = hostStore.listUserscripts().firstOrNull { it.id == resolvedScriptId }
            ?: throw IllegalArgumentException("未找到 userscript=$resolvedScriptId")
        val updateUrl = BrowserUserscriptSupport.downloadUrlForUpdate(current)
            ?: throw IllegalStateException("当前脚本没有可用的 updateURL/downloadURL/sourceUrl")
        val source = withContext(Dispatchers.IO) {
            URL(updateUrl).openStream().bufferedReader().use { it.readText() }
        }
        val preview = BrowserUserscriptSupport.parseSource(source = source, sourceUrl = updateUrl)
        val pendingRecord = BrowserUserscriptSupport.toRecord(
            preview = preview,
            scriptId = current.id,
            now = System.currentTimeMillis(),
            enabled = current.enabled
        )
        hostStore.writePendingUserscript(pendingRecord)
        publishSnapshotUpdate()
    }

    private suspend fun invokeUserscriptMenu(commandId: String?) {
        val resolvedCommandId = commandId?.trim().orEmpty()
        require(resolvedCommandId.isNotEmpty()) { "缺少 commandId" }
        val targetTab = tabs.values.firstOrNull { it.pageMenuCommands.containsKey(resolvedCommandId) }
            ?: throw IllegalArgumentException("未找到 userscript 菜单项")
        val result = evaluateValue(
            targetTab,
            """
                return !!(
                    window.__omniInvokeUserscriptMenu &&
                    window.__omniInvokeUserscriptMenu(${JSONObject.quote(resolvedCommandId)})
                );
            """.trimIndent()
        ).jsonPrimitive.booleanOrNull
        require(result == true) { "执行 userscript 菜单项失败" }
    }

    private suspend fun refreshUserscriptState() {
        withContext(Dispatchers.Main.immediate) {
            tabs.values.forEach { tab ->
                tab.pageMenuCommands.clear()
            }
        }
        tabs.values.forEach { tab ->
            tab.currentUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let {
                runCatching { injectUserscriptsIfNeeded(tab) }
            }
        }
    }

    private suspend fun injectUserscriptsIfNeeded(tab: BrowserTab) {
        val currentUrl = tab.currentUrl?.trim().orEmpty()
        if (!(currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))) {
            withContext(Dispatchers.Main.immediate) {
                tab.pageMenuCommands.clear()
            }
            return
        }
        withContext(Dispatchers.Main.immediate) {
            tab.pageMenuCommands.clear()
        }
        val matchedScripts = hostStore.listUserscripts().filter { script ->
            script.enabled &&
                script.blockedGrants.isEmpty() &&
                BrowserUserscriptSupport.isSupportedRunAt(script.runAt) &&
                BrowserUserscriptSupport.matchesUrl(script, currentUrl)
        }
        matchedScripts.forEach { script ->
            runCatching {
                evaluateJavascriptRaw(tab, BrowserUserscriptSupport.buildWrapperScript(script))
            }
        }
        publishSnapshotUpdate()
    }

    private suspend fun injectDownloadHelperIfNeeded(tab: BrowserTab) {
        if (tab.downloadHelperInjected) {
            return
        }
        evaluateJavascriptRaw(
            tab,
            """
                (function() {
                    if (window.__omniDownloadBridgeInstalled) {
                        return 'installed';
                    }
                    window.__omniDownloadBridgeInstalled = true;
                    const bridge = window.OmniBrowserDownloadBridge;
                    if (!bridge) {
                        return 'missing_bridge';
                    }
                    async function saveUrl(url, fileName, mimeType) {
                        try {
                            const response = await fetch(url);
                            const blob = await response.blob();
                            const reader = new FileReader();
                            reader.onloadend = function() {
                                bridge.saveDataUrl(
                                    String(fileName || 'download'),
                                    String(mimeType || blob.type || ''),
                                    String(reader.result || ''),
                                    String(url || location.href)
                                );
                            };
                            reader.readAsDataURL(blob);
                        } catch (error) {
                            bridge.log(String(error && error.message ? error.message : error));
                        }
                    }
                    window.__omniSaveDownloadFromUrl = saveUrl;
                    document.addEventListener('click', function(event) {
                        const anchor = event.target && event.target.closest ? event.target.closest('a[href]') : null;
                        if (!anchor) return;
                        const href = String(anchor.href || '');
                        if (!(href.startsWith('blob:') || href.startsWith('data:'))) return;
                        event.preventDefault();
                        const fileName = anchor.getAttribute('download') || document.title || 'download';
                        if (href.startsWith('data:')) {
                            bridge.saveDataUrl(String(fileName), '', href, String(location.href));
                            return;
                        }
                        saveUrl(href, fileName, '');
                    }, true);
                    return 'ok';
                })();
            """.trimIndent()
        )
        tab.downloadHelperInjected = true
    }

    private fun createDownloadListener(tab: BrowserTab): DownloadListener {
        return DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val targetUrl = url?.trim().orEmpty()
            if (targetUrl.isBlank()) {
                return@DownloadListener
            }
            if (!(targetUrl.startsWith("http://") || targetUrl.startsWith("https://"))) {
                pendingExternalOpen = PendingExternalOpen(
                    requestId = UUID.randomUUID().toString(),
                    title = tab.title ?: "下载链接",
                    target = targetUrl
                )
                publishSnapshotUpdate()
                return@DownloadListener
            }
            val guessedName = sanitizeFileName(
                URLUtil.guessFileName(targetUrl, contentDisposition, mimeType).ifBlank {
                    "download_${UUID.randomUUID().toString().take(8)}"
                }
            )
            val headers = linkedMapOf<String, String>()
            CookieManager.getInstance().getCookie(targetUrl)
                ?.takeIf { it.isNotBlank() }
                ?.let { headers["Cookie"] = it }
            val resolvedUserAgent = userAgent?.takeIf { it.isNotBlank() } ?: tab.userAgentProfile.userAgentString
            headers["User-Agent"] = resolvedUserAgent
            tab.currentUrl?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
            downloadManager.enqueueHttpDownload(
                url = targetUrl,
                fileName = guessedName,
                mimeType = mimeType,
                headers = headers
            )
            publishSnapshotUpdate()
        }
    }

    private fun openDownloadedFile(taskId: String) {
        val task = downloadManager.snapshot().firstOrNull { it.id == taskId }
            ?: throw IllegalArgumentException("未找到下载任务")
        val targetFile = File(task.destinationPath)
        require(targetFile.exists()) { "下载文件不存在" }
        val contentUri = buildShareableContentUri(targetFile)
        val mimeType = task.mimeType?.substringBefore(';')?.trim()?.ifBlank { "*/*" } ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newUri(appContext.contentResolver, targetFile.name, contentUri)
        }
        runCatching {
            grantUriPermissionToResolvers(intent, contentUri)
            appContext.startActivity(Intent.createChooser(intent, "打开下载文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.getOrElse { error ->
            if (mimeType != "*/*") {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    clipData = ClipData.newUri(appContext.contentResolver, targetFile.name, contentUri)
                }
                grantUriPermissionToResolvers(fallbackIntent, contentUri)
                appContext.startActivity(Intent.createChooser(fallbackIntent, "打开下载文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                throw error
            }
        }
    }

    private fun openDownloadedLocation(taskId: String) {
        val task = downloadManager.snapshot().firstOrNull { it.id == taskId }
            ?: throw IllegalArgumentException("未找到下载任务")
        val targetFile = File(task.destinationPath)
        if (!targetFile.exists()) {
            throw IllegalStateException("下载文件不存在")
        }
        val parent = targetFile.parentFile
        if (parent == null || !parent.exists()) {
            openDownloadedFile(taskId)
            return
        }
        val parentUri = buildShareableContentUri(parent)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(parentUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newUri(appContext.contentResolver, parent.name, parentUri)
        }
        val opened = runCatching {
            grantUriPermissionToResolvers(intent, parentUri)
            appContext.startActivity(Intent.createChooser(intent, "打开下载目录").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (!opened) {
            openDownloadedFile(taskId)
        }
    }

    private fun buildShareableContentUri(sourceFile: File): Uri {
        return try {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", sourceFile)
        } catch (_: IllegalArgumentException) {
            val exportDir = File(appContext.cacheDir, "browser_shared_exports").apply { mkdirs() }
            val staged = File(exportDir, "${System.currentTimeMillis()}_${sourceFile.name}")
            if (sourceFile.isDirectory) {
                return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", sourceFile)
            }
            sourceFile.inputStream().use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", staged)
        }
    }

    private fun grantUriPermissionToResolvers(
        intent: Intent,
        uri: Uri
    ) {
        val resolvers = appContext.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        resolvers.forEach { info ->
            appContext.grantUriPermission(
                info.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun confirmExternalOpen(requestId: String?) {
        val prompt = pendingExternalOpen ?: return
        if (!requestId.isNullOrBlank() && prompt.requestId != requestId) {
            return
        }
        pendingExternalOpen = null
        val opened = runCatching {
            val target = prompt.target
            val intent = if (target.startsWith("intent:")) {
                Intent.parseUri(target, Intent.URI_INTENT_SCHEME).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            appContext.startActivity(intent)
            true
        }.getOrElse { error ->
            if (error is ActivityNotFoundException) {
                false
            } else {
                throw error
            }
        }
        if (!opened) {
            throw IllegalStateException("没有可用应用可打开该链接")
        }
        publishSnapshotUpdate()
    }

    private fun cancelExternalOpen(requestId: String?) {
        val prompt = pendingExternalOpen ?: return
        if (!requestId.isNullOrBlank() && prompt.requestId != requestId) {
            return
        }
        pendingExternalOpen = null
        publishSnapshotUpdate()
    }

    private fun resolveDialog(
        requestId: String?,
        accept: Boolean,
        promptValue: String?
    ) {
        val prompt = pendingDialog ?: return
        if (!requestId.isNullOrBlank() && prompt.requestId != requestId) {
            return
        }
        pendingDialog = null
        when {
            prompt.jsPromptResult != null -> {
                if (accept) {
                    prompt.jsPromptResult.confirm(promptValue ?: "")
                } else {
                    prompt.jsPromptResult.cancel()
                }
            }
            prompt.jsResult != null -> {
                if (accept) {
                    prompt.jsResult.confirm()
                } else {
                    prompt.jsResult.cancel()
                }
            }
        }
        publishSnapshotUpdate()
    }

    private fun grantPendingPermission(requestId: String?) {
        val prompt = pendingPermissionPrompt ?: return
        if (!requestId.isNullOrBlank() && prompt.requestId != requestId) {
            return
        }
        if (prompt.resources.any { it == WebPermissionRequest.RESOURCE_AUDIO_CAPTURE }) {
            completePermissionDeny(prompt)
            return
        }
        val permissions = runtimePermissionsForPrompt(prompt)
        if (permissions.isEmpty()) {
            completePermissionGrant(prompt)
            return
        }
        RuntimePermissionRequest.requestPermissions(appContext, permissions) { resultMap ->
            val granted = resultMap.values.all { it }
            if (granted) {
                completePermissionGrant(prompt)
            } else {
                completePermissionDeny(prompt)
            }
        }
    }

    private fun denyPendingPermission(requestId: String?) {
        val prompt = pendingPermissionPrompt ?: return
        if (!requestId.isNullOrBlank() && prompt.requestId != requestId) {
            return
        }
        completePermissionDeny(prompt)
    }

    private fun runtimePermissionsForPrompt(prompt: PendingPermissionPrompt): Array<String> {
        if (prompt.kind == "geolocation") {
            return arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        val mapped = prompt.resources.mapNotNull { resource ->
            when (resource) {
                WebPermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                else -> null
            }
        }.distinct()
        return mapped.toTypedArray()
    }

    private fun completePermissionGrant(prompt: PendingPermissionPrompt) {
        pendingPermissionPrompt = null
        prompt.webPermissionRequest?.grant(prompt.resources.toTypedArray())
        prompt.geolocationCallback?.invoke(prompt.origin, true, false)
        publishSnapshotUpdate()
    }

    private fun completePermissionDeny(prompt: PendingPermissionPrompt) {
        pendingPermissionPrompt = null
        prompt.webPermissionRequest?.deny()
        prompt.geolocationCallback?.invoke(prompt.origin, false, false)
        publishSnapshotUpdate()
    }

    private inner class BrowserTabClient(
        private val tab: BrowserTab
    ) : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            if (request?.isForMainFrame == false) {
                return false
            }
            val target = request?.url?.toString()?.trim().orEmpty()
            if (target.isBlank()) {
                return false
            }
            val lower = target.lowercase(Locale.ROOT)
            return if (
                lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("file://") ||
                lower.startsWith("about:") ||
                lower.startsWith("data:") ||
                lower.startsWith("blob:")
            ) {
                false
            } else {
                pendingExternalOpen = PendingExternalOpen(
                    requestId = UUID.randomUUID().toString(),
                    title = tab.title ?: "外部链接",
                    target = target
                )
                publishSnapshotUpdate()
                true
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            tab.isLoading = true
            tab.currentUrl = url
            tab.title = view?.title ?: tab.title
            tab.lastError = null
            tab.helpersInjected = false
            tab.downloadHelperInjected = false
            tab.hasSslError = false
            clearRiskChallengeState(tab)
            tab.pageMenuCommands.clear()
            if (tab.loadWaiter?.isActive != true) {
                tab.loadWaiter = CompletableDeferred()
            }
            publishSnapshotUpdate()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            tab.isLoading = false
            tab.currentUrl = url
            tab.title = view?.title ?: tab.title
            val waiter = tab.loadWaiter
            mainScope.launch {
                delay(LOAD_SETTLE_DELAY_MS)
                val challenge = runCatching { inspectPageRiskChallenge(tab) }.getOrNull()
                if (challenge == null) {
                    runCatching { injectDownloadHelperIfNeeded(tab) }
                    runCatching { injectUserscriptsIfNeeded(tab) }
                }
                hostStore.recordVisit(tab.currentUrl, tab.title, isReload = false)
                hostStore.updateTitle(tab.currentUrl, tab.title)
                publishSnapshotUpdate()
                if (waiter?.isActive == true) {
                    waiter.complete(
                        LoadSnapshot(
                            url = tab.currentUrl,
                            title = tab.title,
                            errorMessage = tab.lastError
                        )
                    )
                }
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == false) return
            tab.isLoading = false
            tab.currentUrl = request?.url?.toString() ?: tab.currentUrl
            tab.lastError = error?.description?.toString() ?: "页面加载失败"
            tab.loadWaiter?.takeIf { it.isActive }?.complete(
                LoadSnapshot(
                    url = tab.currentUrl,
                    title = tab.title,
                    errorMessage = tab.lastError
                )
            )
            publishSnapshotUpdate()
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            if (request?.isForMainFrame == false) return
            val statusCode = errorResponse?.statusCode ?: return
            tab.lastHttpStatusCode = statusCode
            BrowserRiskControl.detectChallenge(
                statusCode = statusCode,
                title = tab.title,
                currentUrl = request?.url?.toString() ?: tab.currentUrl
            )?.let { challenge ->
                if (applyRiskChallengeState(tab, challenge)) {
                    publishSnapshotUpdate()
                }
            }
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: android.net.http.SslError?
        ) {
            tab.isLoading = false
            tab.hasSslError = true
            tab.lastError = error?.url?.let { "SSL error: $it" } ?: "SSL error"
            handler?.cancel()
            tab.loadWaiter?.takeIf { it.isActive }?.complete(
                LoadSnapshot(
                    url = error?.url ?: tab.currentUrl,
                    title = tab.title,
                    errorMessage = tab.lastError
                )
            )
            publishSnapshotUpdate()
        }
    }

    private inner class BrowserTabChromeClient(
        private val tab: BrowserTab
    ) : WebChromeClient() {
        override fun onReceivedTitle(
            view: WebView?,
            title: String?
        ) {
            super.onReceivedTitle(view, title)
            tab.title = title ?: tab.title
            hostStore.updateTitle(tab.currentUrl, tab.title)
            publishSnapshotUpdate()
        }

        override fun onProgressChanged(
            view: WebView?,
            newProgress: Int
        ) {
            super.onProgressChanged(view, newProgress)
            tab.isLoading = newProgress in 0..99
            publishSnapshotUpdate()
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            return super.onConsoleMessage(consoleMessage)
        }

        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            pendingDialog?.jsResult?.cancel()
            pendingDialog = PendingDialog(
                requestId = UUID.randomUUID().toString(),
                type = "alert",
                message = message.orEmpty(),
                url = url,
                jsResult = result
            )
            publishSnapshotUpdate()
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            pendingDialog?.jsResult?.cancel()
            pendingDialog = PendingDialog(
                requestId = UUID.randomUUID().toString(),
                type = "confirm",
                message = message.orEmpty(),
                url = url,
                jsResult = result
            )
            publishSnapshotUpdate()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?
        ): Boolean {
            pendingDialog?.jsPromptResult?.cancel()
            pendingDialog = PendingDialog(
                requestId = UUID.randomUUID().toString(),
                type = "prompt",
                message = message.orEmpty(),
                url = url,
                defaultValue = defaultValue,
                jsPromptResult = result
            )
            publishSnapshotUpdate()
            return true
        }

        override fun onPermissionRequest(request: WebPermissionRequest?) {
            if (request == null) {
                return
            }
            pendingPermissionPrompt?.webPermissionRequest?.deny()
            pendingPermissionPrompt = PendingPermissionPrompt(
                requestId = UUID.randomUUID().toString(),
                kind = "web",
                origin = request.origin?.toString().orEmpty(),
                resources = request.resources?.toList().orEmpty(),
                webPermissionRequest = request
            )
            publishSnapshotUpdate()
        }

        override fun onPermissionRequestCanceled(request: WebPermissionRequest?) {
            if (pendingPermissionPrompt?.webPermissionRequest == request) {
                pendingPermissionPrompt = null
                publishSnapshotUpdate()
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            pendingPermissionPrompt?.geolocationCallback?.invoke(
                pendingPermissionPrompt?.origin.orEmpty(),
                false,
                false
            )
            pendingPermissionPrompt = PendingPermissionPrompt(
                requestId = UUID.randomUUID().toString(),
                kind = "geolocation",
                origin = origin.orEmpty(),
                resources = listOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                geolocationCallback = callback
            )
            publishSnapshotUpdate()
        }

        override fun onGeolocationPermissionsHidePrompt() {
            if (pendingPermissionPrompt?.kind == "geolocation") {
                pendingPermissionPrompt = null
                publishSnapshotUpdate()
            }
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            tab.pendingFileChooserCallback?.onReceiveValue(null)
            tab.pendingFileChooserCallback = filePathCallback
            BrowserFileChooserCoordinator.requestFiles(
                context = appContext,
                allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
                mimeTypes = fileChooserParams?.acceptTypes?.filter { it.isNotBlank() }?.toTypedArray()
            ) { uris ->
                tab.pendingFileChooserCallback?.onReceiveValue(uris)
                tab.pendingFileChooserCallback = null
            }
            return true
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?
        ): Boolean {
            if (resultMsg == null) {
                return false
            }
            val popupTab = createTabOnMain(tab.userAgentProfile)
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = popupTab.webView
            resultMsg.sendToTarget()
            activeTabId = popupTab.tabId
            popupTab.currentUrl = "about:blank"
            popupTab.title = "New Tab"
            popupTab.webView.post {
                runCatching { attachActiveTabTo(attachedContainer ?: return@post, attachedContainer?.context ?: appContext) }
                publishSnapshotUpdate()
            }
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            val closeTab = tabs.values.firstOrNull { it.webView == window } ?: return
            tabs.remove(closeTab.tabId)
            if (activeTabId == closeTab.tabId) {
                activeTabId = tabs.keys.lastOrNull()
            }
            closeTab.pendingFileChooserCallback?.onReceiveValue(null)
            runCatching {
                (closeTab.webView.parent as? ViewGroup)?.removeView(closeTab.webView)
                closeTab.webView.destroy()
            }
            mainScope.launch {
                reattachActiveTabIfNeeded()
                publishSnapshotUpdate()
            }
        }
    }

    private inner class BrowserUserscriptBridge(
        private val tab: BrowserTab
    ) {
        @JavascriptInterface
        fun getValue(
            scriptId: String,
            key: String
        ): String? {
            val resolvedScriptId = scriptId.toLongOrNull() ?: return null
            return hostStore.getUserscriptValueMap(resolvedScriptId)[key]
        }

        @JavascriptInterface
        fun setValue(
            scriptId: String,
            key: String,
            value: String?
        ) {
            val resolvedScriptId = scriptId.toLongOrNull() ?: return
            hostStore.putUserscriptValue(resolvedScriptId, key, value)
        }

        @JavascriptInterface
        fun deleteValue(
            scriptId: String,
            key: String
        ) {
            val resolvedScriptId = scriptId.toLongOrNull() ?: return
            hostStore.putUserscriptValue(resolvedScriptId, key, null)
        }

        @JavascriptInterface
        fun listValues(scriptId: String): String {
            val resolvedScriptId = scriptId.toLongOrNull() ?: return "[]"
            return org.json.JSONArray(
                hostStore.getUserscriptValueMap(resolvedScriptId).keys.toList()
            ).toString()
        }

        @JavascriptInterface
        fun registerMenuCommand(
            scriptId: String,
            title: String
        ): String {
            val resolvedScriptId = scriptId.toLongOrNull() ?: return ""
            val commandId = UUID.randomUUID().toString()
            synchronized(tab.pageMenuCommands) {
                tab.pageMenuCommands[commandId] = BrowserUserscriptMenuCommand(
                    commandId = commandId,
                    scriptId = resolvedScriptId,
                    title = title.ifBlank { "Menu" }
                )
            }
            return commandId
        }

        @JavascriptInterface
        fun log(
            scriptId: String,
            message: String
        ) {
            scriptId.length
            message.length
        }
    }

    private inner class BrowserDownloadBridge(
        private val tab: BrowserTab
    ) {
        @JavascriptInterface
        fun saveDataUrl(
            fileName: String?,
            mimeType: String?,
            dataUrl: String?,
            sourceUrl: String?
        ) {
            val payload = dataUrl?.trim().orEmpty()
            if (!payload.startsWith("data:")) {
                return
            }
            val commaIndex = payload.indexOf(',')
            if (commaIndex <= 0) {
                return
            }
            val metadata = payload.substring(5, commaIndex)
            val encodedBody = payload.substring(commaIndex + 1)
            val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() }
                ?: metadata.substringBefore(';').takeIf { it.isNotBlank() }
            val bytes = if (metadata.contains(";base64")) {
                android.util.Base64.decode(encodedBody, android.util.Base64.DEFAULT)
            } else {
                URLDecoder.decode(encodedBody, "UTF-8").toByteArray()
            }
            downloadManager.saveInlineDownload(
                sourceUrl = sourceUrl?.takeIf { it.isNotBlank() } ?: tab.currentUrl.orEmpty(),
                fileName = sanitizeFileName(fileName ?: "download"),
                mimeType = resolvedMimeType,
                bytes = bytes
            )
            publishSnapshotUpdate()
        }

        @JavascriptInterface
        fun log(message: String) {
            message.length
        }
    }
}
