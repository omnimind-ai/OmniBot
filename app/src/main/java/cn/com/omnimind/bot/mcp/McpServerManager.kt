package cn.com.omnimind.bot.mcp

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.webchat.AgentRunService
import cn.com.omnimind.bot.webchat.BrowserMirrorService
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.webchat.WebChatAvatarService
import cn.com.omnimind.bot.webchat.WorkspaceFileService
import com.google.gson.Gson
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Cookie
import io.ktor.serialization.gson.gson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MCP 服务管理器 — 负责生命周期管理、鉴权、状态查询。
 *
 * 路由逻辑已拆分到：
 * - [McpRoutes] — MCP 端点（JSON-RPC、工具发现/调用）
 * - [WebChatRoutes] — WebChat API（对话、事件流、工作区、浏览器）
 * - [WebChatStaticHandler] — React WebChat 静态文件托管
 */
object McpServerManager {
    private const val TAG = "[McpServerManager]"
    private const val PREF_ENABLE = "mcp_server_enabled"
    private const val PREF_HOST = "mcp_server_host"
    private const val PREF_TOKEN_VAULT = "mcp_server_token_v2" // 加密后的 token
    private const val PREF_PORT = "mcp_server_port"
    private const val DEFAULT_PORT = 8899
    private const val PORT_SEARCH_ATTEMPTS = 100
    private const val WEBCHAT_SESSION_COOKIE = "omnibot_webchat_session"
    private const val WEBCHAT_SESSION_TTL_MS = 7L * 24L * 60L * 60L * 1000L

    internal val gson by lazy { Gson() }
    internal val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mmkv by lazy { com.tencent.mmkv.MMKV.defaultMMKV() }
    private val serverLock = Any()
    private val webChatSessionLock = Any()
    private val webChatSessions: MutableMap<String, Long> = mutableMapOf()

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var isRunning: Boolean = false

    @Volatile
    private var activeHost: String? = null

    /** 内存中的明文 token，避免频繁解密 */
    @Volatile
    private var cachedToken: String? = null

    // ==================== 公共 API ====================

    fun restoreIfEnabled(context: Context) {
        if (!mmkv.decodeBool(PREF_ENABLE, false)) return
        val port = mmkv.decodeInt(PREF_PORT, DEFAULT_PORT).takeIf { it > 0 } ?: DEFAULT_PORT
        serverScope.launch {
            runCatching { startServer(context, port) }
                .onFailure { OmniLog.e(TAG, "restoreIfEnabled failed: ${it.message}") }
        }
    }

    fun setEnabled(context: Context, enable: Boolean, port: Int? = null): McpServerState {
        if (enable) {
            val targetPort = port ?: mmkv.decodeInt(PREF_PORT, DEFAULT_PORT).takeIf { it > 0 } ?: DEFAULT_PORT
            return startServer(context, targetPort)
        } else {
            stopServer()
            mmkv.encode(PREF_ENABLE, false)
        }
        return currentState()
    }

    fun refreshToken(context: Context): McpServerState {
        val newToken = generateToken()
        TokenVault.encryptAndStore(mmkv, PREF_TOKEN_VAULT, newToken)
        cachedToken = newToken
        if (isRunning || mmkv.decodeBool(PREF_ENABLE, false)) {
            return restart(context)
        }
        return currentState()
    }

    fun currentState(): McpServerState {
        val resolvedHost = resolveAdvertisedHost()
        return McpServerState(
            enabled = mmkv.decodeBool(PREF_ENABLE, false) && isRunning,
            running = isRunning,
            host = resolvedHost,
            port = mmkv.decodeInt(PREF_PORT, DEFAULT_PORT).takeIf { it > 0 } ?: DEFAULT_PORT,
            token = ensureToken(),
        )
    }

    fun stopServer() {
        synchronized(serverLock) {
            stopServerLocked()
        }
    }

    // ==================== 供路由文件调用的内部方法 ====================

    /**
     * WebChat 鉴权校验，供 WebChatRoutes / McpRoutes 调用。
     * 返回 true 表示通过，false 表示已自动响应 401/403。
     */
    suspend fun requireWebChatAuth(
        call: io.ktor.server.application.ApplicationCall
    ): Boolean {
        if (!isLanRequest(call)) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
            return false
        }
        val bearerToken = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")
            ?.trim()
        if (!bearerToken.isNullOrBlank() && timingSafeEquals(bearerToken, ensureToken())) {
            return true
        }
        pruneExpiredWebChatSessions()
        val sessionId = call.request.cookies[WEBCHAT_SESSION_COOKIE]
        val valid = synchronized(webChatSessionLock) {
            val expiresAt = sessionId?.let { webChatSessions[it] }
            expiresAt != null && expiresAt > System.currentTimeMillis()
        }
        if (valid) {
            return true
        }
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
        return false
    }

    /**
     * WebChat Session 创建端点处理。
     */
    suspend fun handleWebChatSessionBootstrap(
        call: io.ktor.server.application.ApplicationCall
    ) {
        if (!isLanRequest(call)) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
            return
        }
        val body = runCatching { call.receive<Map<String, Any?>>() }.getOrDefault(emptyMap())
        val token = body["token"]?.toString()
            ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrBlank() || !timingSafeEquals(token, ensureToken())) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Authentication failed"))
            return
        }
        pruneExpiredWebChatSessions()
        val sessionId = UUID.randomUUID().toString()
        synchronized(webChatSessionLock) {
            webChatSessions[sessionId] = System.currentTimeMillis() + WEBCHAT_SESSION_TTL_MS
        }
        call.response.cookies.append(
            Cookie(
                name = WEBCHAT_SESSION_COOKIE,
                value = sessionId,
                httpOnly = true,
                path = "/",
                maxAge = (WEBCHAT_SESSION_TTL_MS / 1000L).toInt()
            )
        )
        call.respond(
            mapOf(
                "success" to true,
                "server" to currentState().toMap()
            )
        )
    }

    /**
     * 文件下载端点处理（支持文件 token 和 Bearer token）。
     */
    suspend fun handleFileDownload(call: io.ktor.server.application.ApplicationCall) {
        val fileId = call.parameters["fileId"]
        if (fileId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
            return
        }

        val record = McpFileInbox.getFile(fileId)
        if (record == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Resource not found"))
            return
        }

        val token = call.request.queryParameters["token"]
        val authHeader = call.request.headers["Authorization"]
        val bearerToken = authHeader?.removePrefix("Bearer ")?.trim()
        val bearerOk = !bearerToken.isNullOrBlank() && timingSafeEquals(bearerToken, ensureToken())
        val tokenOk = McpFileInbox.isTokenValid(record, token)

        if (!tokenOk && !bearerOk) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
            return
        }

        val file = File(record.path)
        if (!file.exists()) {
            McpFileInbox.removeFile(fileId)
            call.respond(HttpStatusCode.Gone, mapOf("error" to "Resource no longer available"))
            return
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, record.fileName).toString()
        )
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondFile(file)
    }

    // ==================== 私有方法 ====================

    private fun restart(context: Context): McpServerState {
        val port = mmkv.decodeInt(PREF_PORT, DEFAULT_PORT).takeIf { it > 0 } ?: DEFAULT_PORT
        synchronized(serverLock) {
            stopServerLocked()
        }
        return startServer(context, port)
    }

    private fun startServer(context: Context, port: Int): McpServerState {
        synchronized(serverLock) {
            try {
                val lanIp = resolveLanIp()
                    ?: throw IllegalStateException("未检测到可用的局域网 IPv4 地址")
                if (isRunning) {
                    val currentPort = mmkv.decodeInt(PREF_PORT, DEFAULT_PORT).takeIf { it > 0 } ?: DEFAULT_PORT
                    if (currentPort == port) {
                        activeHost = lanIp
                        mmkv.encode(PREF_HOST, lanIp)
                        return currentState()
                    }
                    stopServerLocked()
                }
                val resolvedPort = resolveAvailablePort(port)
                val engine = buildServer(context, resolvedPort)
                engine.start(wait = false)

                server = engine
                isRunning = true
                activeHost = lanIp
                mmkv.encode(PREF_ENABLE, true)
                mmkv.encode(PREF_PORT, resolvedPort)
                mmkv.encode(PREF_HOST, lanIp)
                if (resolvedPort != port) {
                    OmniLog.w(TAG, "MCP port $port occupied; switched to $resolvedPort")
                }
                OmniLog.i(TAG, "MCP server started at http://$lanIp:$resolvedPort")
                return currentState()
            } catch (t: Throwable) {
                server = null
                isRunning = false
                activeHost = null
                OmniLog.e(TAG, "startServer failed: ${t.message}")
                throw t
            }
        }
    }

    internal fun isTcpPortAvailable(port: Int): Boolean {
        if (port !in 1..65535) return false
        return runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress("0.0.0.0", port))
            }
        }.isSuccess
    }

    internal fun resolveAvailablePort(
        preferredPort: Int,
        maxAttempts: Int = PORT_SEARCH_ATTEMPTS,
        isAvailable: (Int) -> Boolean = ::isTcpPortAvailable,
    ): Int {
        require(preferredPort in 1..65535) { "无效的 MCP 端口: $preferredPort" }
        require(maxAttempts > 0) { "MCP 端口搜索次数必须大于 0" }
        val lastPort = (preferredPort.toLong() + maxAttempts - 1L).coerceAtMost(65535L).toInt()
        return (preferredPort..lastPort).firstOrNull(isAvailable)
            ?: error("MCP 端口 $preferredPort..$lastPort 均被占用")
    }

    private fun buildServer(
        context: Context,
        port: Int
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        val token = ensureToken()
        val appContext = context.applicationContext
        val conversationService = ConversationDomainService(appContext)
        val workspaceFileService = WorkspaceFileService(appContext)
        val browserMirrorService = BrowserMirrorService(appContext)
        val agentRunService = AgentRunService(appContext)
        val webChatAvatarService = WebChatAvatarService(appContext)

        return embeddedServer(CIO, host = "0.0.0.0", port = port) {
            install(CallLogging)
            install(ContentNegotiation) {
                json(McpJson)
                gson()
            }
            install(Authentication) {
                bearer("bearer-auth") {
                    authenticate { credential ->
                        if (timingSafeEquals(credential.token, token)) {
                            UserIdPrincipal("mcp-client")
                        } else null
                    }
                }
            }
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.path() == "/mcp") {
                    val bearerToken = call.request.headers[HttpHeaders.Authorization]
                        ?.removePrefix("Bearer ")
                        ?.trim()
                    if (bearerToken.isNullOrBlank() || !timingSafeEquals(bearerToken, token)) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                        finish()
                    }
                }
            }
            mcpStreamableHttp(
                path = "/mcp",
                enableDnsRebindingProtection = false,
            ) {
                AndroidDeviceMcpServer.create(appContext, serverScope)
            }
            routing {
                get("/") {
                    call.respondRedirect("/webchat/")
                }

                post("/webchat/api/session/bootstrap") {
                    handleWebChatSessionBootstrap(call)
                }

                // MCP 端点路由
                with(McpRoutes) {
                    registerMcpRoutes(context)
                }

                // WebChat API 路由
                with(WebChatRoutes) {
                    registerWebChatRoutes(
                        conversationService,
                        workspaceFileService,
                        browserMirrorService,
                        agentRunService,
                        webChatAvatarService
                    )
                }

                // WebChat 静态文件
                with(WebChatStaticHandler) {
                    registerWebChatStaticRoutes(appContext)
                }
            }
        }
    }

    private fun stopServerLocked() {
        runCatching {
            server?.stop(500, 1_500)
        }.onFailure {
            OmniLog.e(TAG, "stopServer error: ${it.message}")
        }
        server = null
        isRunning = false
        activeHost = null
        synchronized(webChatSessionLock) {
            webChatSessions.clear()
        }
    }

    private fun resolveLanIp(): String? {
        return runCatching { McpNetworkUtils.currentLanIp() }
            .onFailure { OmniLog.e(TAG, "resolveLanIp failed: ${it.message}") }
            .getOrNull()
    }

    private fun resolveAdvertisedHost(): String? {
        val currentHost = resolveLanIp()
        if (currentHost != null && isRunning) {
            synchronized(serverLock) {
                if (isRunning && activeHost != currentHost) {
                    activeHost = currentHost
                    mmkv.encode(PREF_HOST, currentHost)
                }
            }
        }
        if (currentHost != null) return currentHost
        return if (isRunning) {
            activeHost ?: mmkv.decodeString(PREF_HOST)
        } else {
            null
        }
    }

    private fun pruneExpiredWebChatSessions() {
        val now = System.currentTimeMillis()
        synchronized(webChatSessionLock) {
            webChatSessions.entries.removeIf { (_, expiresAt) -> expiresAt <= now }
        }
    }

    private fun isLanRequest(call: io.ktor.server.application.ApplicationCall): Boolean {
        val remoteHost = call.request.headers["X-Forwarded-For"]
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?: call.request.headers["X-Real-IP"]
            ?: call.request.host()
        return McpNetworkUtils.isLanAddress(remoteHost)
    }

    // ==================== Token 管理 ====================

    /**
     * 确保存在有效 token，优先从内存缓存取，否则从加密存储解密。
     * 首次运行时自动生成并通过 [TokenVault] 加密存储到 MMKV。
     */
    private fun ensureToken(): String {
        cachedToken?.let { return it }
        // 尝试从加密存储解密
        val decrypted = TokenVault.decryptFrom(mmkv, PREF_TOKEN_VAULT)
        if (decrypted != null) {
            cachedToken = decrypted
            return decrypted
        }
        // 兼容旧版：尝试读取明文 token 并迁移到加密存储
        val legacyPlain = mmkv.decodeString("mcp_server_token")
        if (!legacyPlain.isNullOrBlank()) {
            TokenVault.encryptAndStore(mmkv, PREF_TOKEN_VAULT, legacyPlain)
            mmkv.remove("mcp_server_token")
            cachedToken = legacyPlain
            OmniLog.i(TAG, "Migrated legacy plain token to encrypted vault")
            return legacyPlain
        }
        // 全新生成
        val newToken = generateToken()
        TokenVault.encryptAndStore(mmkv, PREF_TOKEN_VAULT, newToken)
        cachedToken = newToken
        return newToken
    }

    private fun generateToken(): String {
        val random = SecureRandom()
        val buffer = ByteArray(32)
        random.nextBytes(buffer)
        return Base64.encodeToString(buffer, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    // ==================== 安全工具方法 ====================

    /**
     * 时序安全比较，防止通过响应时间差异推断 token 内容。
     * 使用 [MessageDigest.isEqual] 保证常量时间比较。
     */
    private fun timingSafeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) {
            // 长度不同时仍做完整比较以保持恒定时间
            MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
            return false
        }
        return MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}

/**
 * Token 加密保险箱 — AES-256-GCM 加解密，密钥由应用签名派生。
 *
 * 存储格式：Base64(IV[12] + ciphertext + GCM tag[16])
 */
private object TokenVault {
    private const val AES_KEY_SIZE = 32
    private const val GCM_IV_SIZE = 12
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private var cachedKey: SecretKeySpec? = null

    /**
     * 从应用签名证书的 SHA-256 摘要派生 AES 密钥。
     * 同一 APK 签名 → 同一密钥；换签名后旧密文无法解密（需重新生成 token）。
     */
    private fun deriveKey(context: Context): SecretKeySpec {
        cachedKey?.let { return it }
        val pm = context.packageManager
        val packageName = context.packageName
        val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signatures = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        check(signatures.isNotEmpty()) { "No APK signing certificates found" }
        val digest = MessageDigest.getInstance("SHA-256")
        // 取所有签名做摘要，保证同一 APK 一致
        for (sig in signatures) {
            digest.update(sig.toByteArray())
        }
        val keyBytes = digest.digest().copyOf(AES_KEY_SIZE)
        val key = SecretKeySpec(keyBytes, "AES")
        cachedKey = key
        return key
    }

    /**
     * 加密明文 token 并 Base64 编码后存入 MMKV。
     */
    fun encryptAndStore(mmkv: com.tencent.mmkv.MMKV, key: String, plainText: String) {
        try {
            val context = cn.com.omnimind.bot.App.instance
            val secretKey = deriveKey(context)
            val iv = ByteArray(GCM_IV_SIZE).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            // IV + ciphertext 拼接
            val combined = iv + encrypted
            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP or Base64.URL_SAFE)
            mmkv.encode(key, encoded)
        } catch (e: Exception) {
            OmniLog.e("TokenVault", "encryptAndStore failed: ${e.message}")
            // 降级：加密失败时仍存储明文（保底可用）
            mmkv.encode(key, plainText)
        }
    }

    /**
     * 从 MMKV 读取并解密 token。返回 null 表示不存在或解密失败。
     */
    fun decryptFrom(mmkv: com.tencent.mmkv.MMKV, key: String): String? {
        val stored = mmkv.decodeString(key) ?: return null
        try {
            val context = cn.com.omnimind.bot.App.instance
            val secretKey = deriveKey(context)
            val combined = Base64.decode(stored, Base64.NO_WRAP or Base64.URL_SAFE)
            if (combined.size < GCM_IV_SIZE + 16) return null // 数据太短，不合法
            val iv = combined.copyOfRange(0, GCM_IV_SIZE)
            val encrypted = combined.copyOfRange(GCM_IV_SIZE, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val decrypted = cipher.doFinal(encrypted)
            return String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            OmniLog.e("TokenVault", "decryptFrom failed: ${e.message}")
            // 降级：如果存储的就是明文（首次加密失败的场景），直接返回
            if (stored.length in 32..128 && stored.all { it.isLetterOrDigit() || it in "_-=" }) {
                return stored
            }
            return null
        }
    }
}
