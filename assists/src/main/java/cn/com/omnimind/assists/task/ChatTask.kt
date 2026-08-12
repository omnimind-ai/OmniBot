package cn.com.omnimind.assists.task

import cn.com.omnimind.assists.TaskManager
import cn.com.omnimind.assists.api.enums.TaskFinishType
import cn.com.omnimind.assists.api.enums.TaskType
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.openclaw.OpenClawDeviceIdentity
import cn.com.omnimind.assists.openclaw.OpenClawTokenStore
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationStore
import cn.com.omnimind.baselib.http.Http429Exception
import cn.com.omnimind.baselib.http.OkHttpManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.ContentEndpointSecurity
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import java.util.concurrent.TimeUnit

/**
 * 创建聊天任务
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatTask(override val taskChangeListener: TaskChangeListener,
               taskManager: TaskManager
) : Task(taskChangeListener, taskManager),
    FlowCollector<String> {
    // 仅使用单线程调度器并不足以保证顺序：
    // 每个 chunk 的处理过程会 suspend（例如切到 Main 派发给 Flutter），
    // 后续 chunk 可能在前一个恢复前继续执行，导致 UI 看到乱序文本。
    // 因此这里额外用 Mutex 把整段监听器回调串行化，保证“收到顺序 == 派发顺序”。
    private val singleThreadDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val controllerScope = CoroutineScope(SupervisorJob() + singleThreadDispatcher)
    private val streamDispatchMutex = Mutex()

    private lateinit var content: List<Map<String, Any>>
    private var onMessagePushListener: OnMessagePushListener? = null
    private lateinit var taskID: String
    private lateinit var eventSource: EventSource
    private var isManualCancel = false // 标记是否为主动取消
    @Volatile
    private var provider: String? = null
    private var openClawConfig: TaskParams.OpenClawConfig? = null
    private var modelOverride: TaskParams.ChatModelOverride? = null
    private var reasoningEffort: String? = null
    @Volatile
    private var openClawFinished = false
    private var openClawLoggedFirstEvent = false
    @Volatile
    private var openClawWebSocket: WebSocket? = null
    @Volatile
    private var identityResetStopRequested = false
    private val openClawLifecycleLock = Any()
    private val openClawBuffers = mutableMapOf<String, String>()
    private val openClawAttachmentSent = mutableSetOf<String>()
    private var openClawHeartbeatJob: Job? = null
    private val openClawHandshakeTimeoutMs = 10_000L
    private val openClawMinHeartbeatIntervalMs = 1_000L
    private val tag = "ChatTask"

    private fun launchOrderedStreamDispatch(block: suspend () -> Unit) {
        controllerScope.launch {
            streamDispatchMutex.withLock {
                block()
            }
        }
    }
    override fun getTaskType(): TaskType {
        return TaskType.CHAT
    }

    fun start(
        taskID: String,
        content: List<Map<String, Any>>,
        onMessagePushListener: OnMessagePushListener,
        provider: String? = null,
        openClawConfig: TaskParams.OpenClawConfig? = null,
        modelOverride: TaskParams.ChatModelOverride? = null,
        reasoningEffort: String? = null,
        promptCacheKey: String? = null
    ) {
        synchronized(openClawLifecycleLock) {
            this.content = content
            this.taskID = taskID
            this.onMessagePushListener = onMessagePushListener
            this.provider = provider?.trim()?.lowercase()
            this.openClawConfig = openClawConfig
            this.modelOverride = modelOverride
            this.reasoningEffort = reasoningEffort?.trim()?.lowercase()
            this.identityResetStopRequested = false
        }
        super.start taskBlock@{
            try {
                val mayStart = synchronized(openClawLifecycleLock) {
                    if (identityResetStopRequested) {
                        false
                    } else {
                        this@ChatTask.openClawFinished = false
                        this@ChatTask.openClawLoggedFirstEvent = false
                        this@ChatTask.openClawWebSocket = null
                        this@ChatTask.openClawBuffers.clear()
                        this@ChatTask.openClawAttachmentSent.clear()
                        this@ChatTask.openClawHeartbeatJob?.cancel()
                        this@ChatTask.openClawHeartbeatJob = null
                        true
                    }
                }
                if (!mayStart) {
                    onTaskDestroy()
                    return@taskBlock
                }
                OmniLog.i(tag, "start chat task=$taskID provider=${this@ChatTask.provider} messages=${content.size}")
                if (this@ChatTask.provider == "openclaw" && openClawConfig != null) {
                    if (!OpenClawConfigurationStore.isAuthorized(openClawConfig)) {
                        onMessagePushListener.onChatMessage(
                            taskID,
                            "OpenClaw authorization required",
                            "error",
                        )
                        onMessagePushListener.onChatMessageEnd(taskID)
                        onTaskStop(TaskFinishType.ERROR, "OpenClaw authorization required")
                        onTaskDestroy()
                        taskManager.unregisterChatTask(taskID)
                        return@taskBlock
                    }
                    OmniLog.i(
                        tag,
                        "openclaw enabled hasBaseUrl=${openClawConfig.baseUrl.isNotBlank()} token=${OpenClawTokenStore.hasAnyAuthToken()} sessionKey=${!openClawConfig.sessionKey.isNullOrBlank()} hasUser=${!openClawConfig.userId.isNullOrBlank()}"
                    )
                    val createdWebSocket = startOpenClawGatewayChat(
                        taskID = taskID,
                        content = content,
                        openClawConfig = openClawConfig,
                        onMessagePushListener = onMessagePushListener
                    )
                    synchronized(openClawLifecycleLock) {
                        if (identityResetStopRequested) {
                            createdWebSocket?.cancel()
                        } else {
                            openClawWebSocket = createdWebSocket
                        }
                    }
                } else {
                    eventSource = HttpController.postLLMStreamRequestWithContextAsFlow(
                        model = "scene.dispatch.model",
                        messages = content,
                        event = object : EventSourceListener() {
                            override fun onEvent(
                                eventSource: EventSource, id: String?, type: String?, data: String
                            ) {
                                launchOrderedStreamDispatch {
                                    onMessagePushListener.onChatMessage(taskID, data, type)
                                }
                            }

                            override fun onClosed(eventSource: EventSource) {
                                launchOrderedStreamDispatch {
                                    onMessagePushListener.onChatMessageEnd(taskID)
                                    onTaskStop(TaskFinishType.FINISH, "")
                                    onTaskDestroy()
                                    taskManager.unregisterChatTask(taskID)
                                }

                            }

                            override fun onFailure(
                                eventSource: EventSource,
                                t: Throwable?,
                                response: okhttp3.Response?
                            ) {
                                launchOrderedStreamDispatch {
                                    // 如果是主动取消，不发送错误消息，只结束对话
                                    if (isManualCancel) {
                                        onMessagePushListener.onChatMessageEnd(taskID)
                                        onTaskStop(TaskFinishType.FINISH, "")
                                    } else {
                                        val errorType = if (response?.code == 429) {
                                            "rate_limited"
                                        } else {
                                            "error"
                                        }
                                        onMessagePushListener.onChatMessage(
                                            taskID,
                                            buildErrorPayload(t, response),
                                            errorType
                                        )
                                        onMessagePushListener.onChatMessageEnd(taskID)
                                        onTaskStop(TaskFinishType.ERROR, "AI transport request failed")
                                    }
                                    onTaskDestroy()
                                    taskManager.unregisterChatTask(taskID)
                                }
                            }
                        },
                        explicitApiBase = modelOverride?.apiBase,
                        explicitApiKey = modelOverride?.apiKey,
                        explicitCustomHeaders = modelOverride?.customHeaders,
                        explicitModel = modelOverride?.modelId,
                        explicitProtocolType = modelOverride?.protocolType,
                        explicitWireApi = modelOverride?.wireApi,
                        reasoningEffort = this@ChatTask.reasoningEffort,
                        promptCacheKey = promptCacheKey
                    )
                }
            } catch (e: Http429Exception){
                launchOrderedStreamDispatch {
                    OmniLog.e(tag, "openclaw rate limited task=$taskID type=${e.javaClass.simpleName}")
                    val errorType = "rate_limited"
                    onMessagePushListener.onChatMessage(
                        taskID,
                        org.json.JSONObject().put("message", "Rate limited").put("statusCode", 429).toString(),
                        errorType
                    )
                    onMessagePushListener.onChatMessageEnd(taskID)
                    onTaskStop(TaskFinishType.ERROR, "Rate limited")
                    onTaskDestroy()
                    taskManager.unregisterChatTask(taskID)
                }
            } catch (e: Exception) {
                launchOrderedStreamDispatch {
                    OmniLog.e(tag, "openclaw exception task=$taskID type=${e.javaClass.simpleName}")
                    onMessagePushListener.onChatMessage(
                        taskID,
                        org.json.JSONObject()
                            .put("message", "AI request failed")
                            .put("exception", e.javaClass.simpleName)
                            .toString(),
                        "error"
                    )
                    onMessagePushListener.onChatMessageEnd(taskID)
                    onTaskStop(TaskFinishType.ERROR, "AI request failed")
                    onTaskDestroy()
                    taskManager.unregisterChatTask(taskID)
                }
            }
        }


    }

    private suspend fun handleOpenClawEvent(
        taskID: String,
        data: String,
        onMessagePushListener: OnMessagePushListener
    ) {
        if (data == "[DONE]") {
            OmniLog.i(tag, "openclaw done task=$taskID")
            if (!openClawFinished) {
                openClawFinished = true
                onMessagePushListener.onChatMessageEnd(taskID)
            }
            return
        }
        try {
            val json = org.json.JSONObject(data)
            val choices = json.optJSONArray("choices")
            val delta = choices?.optJSONObject(0)?.optJSONObject("delta")
            val text = delta?.optString("content", "") ?: ""
            if (text.isNotEmpty()) {
                val payload = org.json.JSONObject().put("text", text).toString()
                onMessagePushListener.onChatMessage(taskID, payload, null)
            }
        } catch (e: Exception) {
            OmniLog.e(tag, "openclaw parse error task=$taskID type=${e.javaClass.simpleName}")
            return
        }
    }

    private fun buildErrorPayload(
        t: Throwable?,
        response: okhttp3.Response?
    ): String {
        return try {
            val message = if (response?.code == 429) {
                "Request rate limited"
            } else {
                "AI transport request failed"
            }
            val exception = t?.javaClass?.simpleName
            val code = response?.code

            org.json.JSONObject().apply {
                put("message", message)
                if (!exception.isNullOrBlank()) put("exception", exception)
                if (code != null) put("statusCode", code)
            }.toString()
        } catch (e: Exception) {
            "AI transport request failed"
        }
    }


    fun finishTask() {
        super.finishTask() {
            isManualCancel = true // 标记为主动取消
            openClawHeartbeatJob?.cancel()
            if (this@ChatTask.provider == "openclaw") {
                openClawFinished = true
                openClawWebSocket?.close(1000, "manual cancel")
            } else if (this@ChatTask::eventSource.isInitialized) {
                eventSource.cancel()
            }
            streamDispatchMutex.withLock {
                onMessagePushListener?.onChatMessageEnd(taskID)
                taskManager.unregisterChatTask(taskID)
            }
        }
        taskScope.cancel()
    }

    fun isOpenClawTask(): Boolean = provider == "openclaw"

    /**
     * Immediately severs an OpenClaw socket before device identity material can be replaced.
     * The reset coordinator removes this task only when this method can verify the local handle is
     * canceled and no later-created socket can be installed.
     */
    fun stopOpenClawSessionForIdentityReset(): Boolean {
        if (!isOpenClawTask()) return true
        val socket = synchronized(openClawLifecycleLock) {
            identityResetStopRequested = true
            isManualCancel = true
            openClawFinished = true
            openClawHeartbeatJob?.cancel()
            openClawHeartbeatJob = null
            openClawWebSocket.also { openClawWebSocket = null }
        }
        val socketCanceled = try {
            socket?.cancel()
            true
        } catch (_: Exception) {
            false
        }
        taskScope.cancel()
        controllerScope.cancel()
        isRunning = false
        cancelScope.launch {
            try {
                onMessagePushListener?.onChatMessageEnd(taskID)
            } catch (_: Exception) {
                // The native authorization state remains disabled even if UI notification fails.
            }
        }
        return socketCanceled && synchronized(openClawLifecycleLock) {
            identityResetStopRequested && openClawWebSocket == null && openClawFinished
        }
    }

    /**
     * 启动 OpenClaw Gateway WebSocket 聊天连接
     *
     * 严格按照 OpenClaw Gateway 协议执行握手流程：
     * 1. 建立 WebSocket 连接
     * 2. 等待 Gateway 发送 connect.challenge 事件
     * 3. 使用设备私钥签名 challenge nonce
     * 4. 发送 connect 请求（含 device identity、scopes、auth）
     * 5. 等待 hello-ok 响应，持久化 deviceToken
     * 6. 发送 chat.send 请求
     * 7. 处理 chat 事件流
     */
    private fun startOpenClawGatewayChat(
        taskID: String,
        content: List<Map<String, Any>>,
        openClawConfig: TaskParams.OpenClawConfig,
        onMessagePushListener: OnMessagePushListener,
    ): WebSocket? {
        if (!OpenClawConfigurationStore.isAuthorized(openClawConfig)) {
            rejectStaleOpenClawTask(taskID, onMessagePushListener)
            return null
        }
        val wsUrl = buildOpenClawGatewayWsUrl(openClawConfig.baseUrl)
        if (wsUrl.isBlank()) {
            launchOrderedStreamDispatch {
                onMessagePushListener.onChatMessage(taskID, "", "error")
                onMessagePushListener.onChatMessageEnd(taskID)
                onTaskStop(TaskFinishType.ERROR, "OpenClaw ws url invalid")
                onTaskDestroy()
            }
            return null
        }
        try {
            ContentEndpointSecurity.requireSafe(
                rawUrl = wsUrl,
                allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            )
        } catch (_: Exception) {
            launchOrderedStreamDispatch {
                onMessagePushListener.onChatMessage(taskID, "", "error")
                onMessagePushListener.onChatMessageEnd(taskID)
                onTaskStop(TaskFinishType.ERROR, "OpenClaw requires secure transport")
                onTaskDestroy()
            }
            return null
        }

        val userMessage = extractLatestUserMessage(content)
        val userAttachments = extractLatestUserAttachments(content)
        if (userMessage.isBlank() && userAttachments.length() == 0) {
            launchOrderedStreamDispatch {
                onMessagePushListener.onChatMessage(taskID, "", "error")
                onMessagePushListener.onChatMessageEnd(taskID)
                onTaskStop(TaskFinishType.ERROR, "OpenClaw message empty")
                onTaskDestroy()
            }
            return null
        }

        val connectId = "connect-$taskID"
        val sendId = "send-$taskID"
        val sessionKey = openClawConfig.sessionKey?.trim().takeIf { !it.isNullOrEmpty() } ?: "main"

        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(wsUrl).build()
        OmniLog.i(tag, "openclaw ws connect requested hasUrl=${wsUrl.isNotBlank()} hasSession=${sessionKey.isNotBlank()}")

        return OpenClawConfigurationStore.withAuthorization(openClawConfig) {
            OkHttpManager.sensitiveContentWebSocket(
                client = client,
                request = request,
                listener = object : WebSocketListener() {
            private var challengeReceived = false
            private var connectRequested = false
            private var handshakeTimeoutJob: Job? = null

            // 不在 onOpen 中直接发送 connect，必须等待 challenge
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!OpenClawConfigurationStore.isAuthorized(openClawConfig)) {
                    webSocket.cancel()
                    rejectStaleOpenClawTask(taskID, onMessagePushListener)
                    return
                }
                OmniLog.i(tag, "openclaw ws opened, waiting for connect.challenge...")
                handshakeTimeoutJob = controllerScope.launch {
                    delay(openClawHandshakeTimeoutMs)
                    if (openClawFinished || isManualCancel || challengeReceived) return@launch
                    streamDispatchMutex.withLock {
                        OmniLog.e(tag, "openclaw handshake timeout task=$taskID")
                        openClawFinished = true
                        onMessagePushListener.onChatMessage(
                            taskID,
                            "OpenClaw handshake timeout: no connect.challenge received",
                            "error"
                        )
                        onMessagePushListener.onChatMessageEnd(taskID)
                        webSocket.close(1000, "handshake timeout")
                        onTaskStop(TaskFinishType.ERROR, "OpenClaw handshake timeout")
                        onTaskDestroy()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                launchOrderedStreamDispatch dispatch@{
                    if (openClawFinished) return@dispatch
                    try {
                        val frame = org.json.JSONObject(text)
                        val type = frame.optString("type")
                        when (type) {
                            // 处理 connect.challenge 事件（Gateway 握手第一步）
                            "event" -> {
                                val event = frame.optString("event")
                                when (event) {
                                    "connect.challenge" -> {
                                        if (!OpenClawConfigurationStore.isAuthorized(openClawConfig)) {
                                            webSocket.cancel()
                                            rejectStaleOpenClawTask(taskID, onMessagePushListener)
                                            return@dispatch
                                        }
                                        challengeReceived = true
                                        handshakeTimeoutJob?.cancel()
                                        if (connectRequested) {
                                            OmniLog.w(tag, "openclaw duplicated connect.challenge ignored")
                                            return@dispatch
                                        }
                                        connectRequested = true
                                        handleConnectChallenge(
                                            webSocket, frame, connectId, openClawConfig,
                                            onMessagePushListener,
                                        )
                                    }
                                    "chat" -> handleChatEvent(
                                        webSocket, frame, taskID, sessionKey,
                                        openClawConfig, onMessagePushListener
                                    )
                                    else -> OmniLog.d(tag, "openclaw ws ignored event=$event")
                                }
                            }
                            "res" -> {
                                val id = frame.optString("id")
                                val ok = frame.optBoolean("ok")
                                if (id == connectId) {
                                        handleConnectResponse(
                                            webSocket, frame, ok, taskID, sendId,
                                            sessionKey, userMessage, userAttachments,
                                            openClawConfig, onMessagePushListener
                                        )
                                    } else if (id == sendId && !ok) {
                                    openClawHeartbeatJob?.cancel()
                                    webSocket.close(1000, "send failed")
                                    OmniLog.e(tag, "openclaw ws send failed task=$taskID")
                                    val errText = "OpenClaw send failed"
                                    onMessagePushListener.onChatMessage(taskID, errText, "error")
                                    onMessagePushListener.onChatMessageEnd(taskID)
                                    onTaskStop(TaskFinishType.ERROR, "OpenClaw send failed")
                                    onTaskDestroy()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        OmniLog.e(tag, "openclaw ws parse error task=$taskID type=${e.javaClass.simpleName}")
                    }
                }
            }


            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                launchOrderedStreamDispatch dispatch@{
                    handshakeTimeoutJob?.cancel()
                    openClawHeartbeatJob?.cancel()
                    if (openClawFinished) return@dispatch
                    logOpenClawBuffers("openclaw task=$taskID (partial)")
                    OmniLog.e(tag, "openclaw ws failure task=$taskID type=${t.javaClass.simpleName}")
                    if (isManualCancel) {
                        onMessagePushListener.onChatMessageEnd(taskID)
                        onTaskStop(TaskFinishType.FINISH, "")
                    } else {
                        onMessagePushListener.onChatMessage(taskID, "OpenClaw transport failed", "error")
                        onMessagePushListener.onChatMessageEnd(taskID)
                        onTaskStop(TaskFinishType.ERROR, "OpenClaw transport failed")
                    }
                    onTaskDestroy()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                launchOrderedStreamDispatch dispatch@{
                    handshakeTimeoutJob?.cancel()
                    openClawHeartbeatJob?.cancel()
                    logOpenClawBuffers("openclaw task=$taskID (closed)")
                    OmniLog.i(tag, "openclaw ws closed task=$taskID code=$code hasReason=${reason.isNotBlank()}")
                    openClawBuffers.remove(taskID)
                    if (!openClawFinished) {
                        openClawFinished = true
                        if (isManualCancel) {
                            onMessagePushListener.onChatMessageEnd(taskID)
                            onTaskStop(TaskFinishType.FINISH, "")
                        } else {
                            val errText = "OpenClaw closed (code=$code)"
                            onMessagePushListener.onChatMessage(taskID, errText, "error")
                            onMessagePushListener.onChatMessageEnd(taskID)
                            onTaskStop(TaskFinishType.ERROR, "OpenClaw closed")
                        }
                        onTaskDestroy()
                        return@dispatch
                    }
                    return@dispatch
                }
            }
                },
                allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            )
        }
    }

    /**
     * 处理 connect.challenge 事件：签名 nonce 并发送 connect 请求
     */
    private fun handleConnectChallenge(
        webSocket: WebSocket,
        frame: org.json.JSONObject,
        connectId: String,
        openClawConfig: TaskParams.OpenClawConfig,
        onMessagePushListener: OnMessagePushListener,
    ) {
        if (!OpenClawConfigurationStore.isAuthorized(openClawConfig)) {
            webSocket.cancel()
            return
        }
        val payload = frame.optJSONObject("payload")
        val nonce = payload?.optString("nonce").orEmpty()
        if (nonce.isBlank()) {
            OmniLog.e(tag, "openclaw challenge nonce is empty, sending connect without device sig")
        }
        val signedAt = System.currentTimeMillis() // 必须毫秒（13位数字）

        // client 信息（必须使用服务端允许的枚举值）
        val clientId = "cli"
        val clientMode = "cli"
        val clientPlatform = "android"
        val clientVersion = "1.0.0"
        val role = "operator"

        // scopes 规范化：trim → 去空 → 去重 → 字典序排序
        val scopesNorm = listOf("operator.read", "operator.write", "operator.admin")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        OmniLog.d(tag, "openclaw signedAt=$signedAt (${signedAt.toString().length} digits)")
        OmniLog.d(tag, "openclaw scopesNorm=${scopesNorm.joinToString(",")}")
        val identity = OpenClawConfigurationStore.withAuthorization(openClawConfig) {
            val deviceId = OpenClawDeviceIdentity.getFingerprint()
            val publicKey = OpenClawDeviceIdentity.getPublicKeyBase64Url()
            val authToken = OpenClawTokenStore.getAuthToken()
            val signature = if (nonce.isNotBlank()) {
                OpenClawDeviceIdentity.signChallenge(
                    nonce = nonce,
                    signedAt = signedAt,
                    deviceId = deviceId,
                    clientId = clientId,
                    clientMode = clientMode,
                    role = role,
                    scopes = scopesNorm,
                    token = authToken,
                    platform = clientPlatform,
                    deviceFamily = "mobile",
                )
            } else ""
            OpenClawHandshakeIdentity(deviceId, publicKey, authToken, signature)
        } ?: run {
            webSocket.cancel()
            return
        }
        val deviceId = identity.deviceId
        val publicKey = identity.publicKey
        val authToken = identity.authToken
        val signature = identity.signature

        OmniLog.i(
            tag,
            "openclaw challenge received hasNonce=${nonce.isNotBlank()} hasDeviceId=${deviceId.isNotBlank()}",
        )

        // 构建 connect 请求参数
        val connectParams = org.json.JSONObject()
        connectParams.put("minProtocol", 3)
        connectParams.put("maxProtocol", 3)

        // client 信息
        val clientInfo = org.json.JSONObject()
        clientInfo.put("id", clientId)
        clientInfo.put("displayName", "openclaw")
        clientInfo.put("version", clientVersion)
        clientInfo.put("platform", clientPlatform)
        clientInfo.put("deviceFamily", "mobile")
        clientInfo.put("mode", clientMode)
        connectParams.put("client", clientInfo)

        // 角色和权限（scopes 使用与签名完全相同的规范化列表）
        connectParams.put("role", role)
        val scopes = org.json.JSONArray()
        scopesNorm.forEach { scopes.put(it) }
        connectParams.put("scopes", scopes)
        connectParams.put("caps", org.json.JSONArray())
        connectParams.put("commands", org.json.JSONArray())
        connectParams.put("permissions", org.json.JSONObject())

        // 认证 token（已在上方获取，用于签名和 connect 请求）
        if (authToken.isNotEmpty()) {
            val auth = org.json.JSONObject()
            auth.put("token", authToken)
            connectParams.put("auth", auth)
        }

        // locale 和 userAgent
        connectParams.put("locale", "zh-CN")
        connectParams.put("userAgent", "omnibot-android/1.0.0")

        // 设备身份（密钥签名）
        val device = org.json.JSONObject()
        device.put("id", deviceId)
        device.put("publicKey", publicKey)
        device.put("signature", signature)
        device.put("signedAt", signedAt)
        device.put("nonce", nonce)
        connectParams.put("device", device)

        // 发送 connect 请求帧
        val connectFrame = org.json.JSONObject()
        connectFrame.put("type", "req")
        connectFrame.put("id", connectId)
        connectFrame.put("method", "connect")
        connectFrame.put("params", connectParams)

        val sent = OpenClawConfigurationStore.withAuthorization(openClawConfig) {
            webSocket.send(connectFrame.toString())
        } ?: false
        if (!sent) {
            webSocket.cancel()
            rejectStaleOpenClawTask(taskID, onMessagePushListener)
            return
        }
        OmniLog.i(tag, "openclaw connect request sent=$sent hasDeviceId=${deviceId.isNotBlank()} hasToken=${authToken.isNotEmpty()}")
    }

    /**
     * 处理 connect 响应（hello-ok 或失败）
     */
    private suspend fun handleConnectResponse(
        webSocket: WebSocket,
        frame: org.json.JSONObject,
        ok: Boolean,
        taskID: String,
        sendId: String,
        sessionKey: String,
        userMessage: String,
        userAttachments: org.json.JSONArray,
        openClawConfig: TaskParams.OpenClawConfig,
        onMessagePushListener: OnMessagePushListener,
    ) {
        if (!OpenClawConfigurationStore.isAuthorized(openClawConfig)) {
            webSocket.cancel()
            rejectStaleOpenClawTask(taskID, onMessagePushListener)
            return
        }
        if (!ok) {
            openClawHeartbeatJob?.cancel()
            webSocket.close(1000, "connect failed")
            OmniLog.e(tag, "openclaw ws connect failed task=$taskID")
            val errText = "OpenClaw connect failed"
            onMessagePushListener.onChatMessage(taskID, errText, "error")
            onMessagePushListener.onChatMessageEnd(taskID)
            onTaskStop(TaskFinishType.ERROR, "OpenClaw connect failed")
            onTaskDestroy()
            return
        }

        // 解析 hello-ok 响应
        val payload = frame.optJSONObject("payload")
        val helloType = payload?.optString("type").orEmpty()
        OmniLog.i(tag, "openclaw connect ok type=$helloType task=$taskID")

        // 持久化 deviceToken（如果 Gateway 颁发了）
        val auth = payload?.optJSONObject("auth")
        val deviceToken = auth?.optString("deviceToken").orEmpty()
        val role = auth?.optString("role")
        val scopesArray = auth?.optJSONArray("scopes")
        val scopesList = if (scopesArray != null) {
            (0 until scopesArray.length()).map { scopesArray.optString(it) }
        } else emptyList()
        val pairingSaved = OpenClawConfigurationStore.withAuthorization(openClawConfig) {
            if (deviceToken.isNotBlank()) {
                OpenClawTokenStore.saveDeviceToken(deviceToken)
                OmniLog.i(tag, "openclaw saved new deviceToken")
            }
            OpenClawTokenStore.saveAuthInfo(role, scopesList)
            true
        } ?: false
        if (!pairingSaved) {
            webSocket.cancel()
            rejectStaleOpenClawTask(taskID, onMessagePushListener)
            return
        }

        // 启动心跳（基于 Gateway 返回的 tickIntervalMs）
        val policy = payload?.optJSONObject("policy")
        val tickIntervalMs = policy?.optLong("tickIntervalMs", 15000L) ?: 15000L
        val safeIntervalMs = tickIntervalMs.coerceAtLeast(openClawMinHeartbeatIntervalMs)
        startHeartbeat(webSocket, safeIntervalMs, openClawConfig)

        // 握手完成，现在发送 chat.send 请求
        val sendParams = org.json.JSONObject()
        sendParams.put("sessionKey", sessionKey)
        sendParams.put("message", userMessage)
        sendParams.put("idempotencyKey", taskID)
        val modelAttachments = filterModelAttachments(userAttachments)
        if (modelAttachments.length() > 0) {
            sendParams.put("attachments", modelAttachments)
        }

        val sendFrame = org.json.JSONObject()
        sendFrame.put("type", "req")
        sendFrame.put("id", sendId)
        sendFrame.put("method", "chat.send")
        sendFrame.put("params", sendParams)

        if (!OpenClawConfigurationStore.isAuthorized(openClawConfig)) {
            webSocket.cancel()
            rejectStaleOpenClawTask(taskID, onMessagePushListener)
            return
        }
        val sent = OpenClawConfigurationStore.withAuthorization(openClawConfig) {
            webSocket.send(sendFrame.toString())
        } ?: false
        if (!sent) {
            webSocket.cancel()
            rejectStaleOpenClawTask(taskID, onMessagePushListener)
            return
        }
        OmniLog.i(tag, "openclaw chat.send request sent=$sent task=$taskID hasSession=${sessionKey.isNotBlank()}")
    }

    /**
     * 处理 chat 事件流
     */
    private suspend fun handleChatEvent(
        webSocket: WebSocket,
        frame: org.json.JSONObject,
        taskID: String,
        sessionKey: String,
        openClawConfig: TaskParams.OpenClawConfig,
        onMessagePushListener: OnMessagePushListener,
    ) {
        val payload = frame.optJSONObject("payload") ?: return
        val runId = payload.optString("runId")
        val payloadSessionKey = payload.optString("sessionKey")
        val isSameRun = runId == taskID
        val isSameSession = payloadSessionKey.isNotBlank() && payloadSessionKey == sessionKey
        if (!isSameRun && !isSameSession) return

        val state = payload.optString("state")
        val messageObj = payload.optJSONObject("message")
        val contentArray = messageObj?.optJSONArray("content")
        val payloadAttachments = extractAttachmentsFromPayload(payload, openClawConfig)
        val contentAttachments = extractAttachmentsFromContentArray(contentArray)
        val attachments = mergeAttachmentArrays(payloadAttachments, contentAttachments)
        val nextText = extractTextFromContentArray(contentArray)
            .ifBlank { messageObj?.optString("text")?.trim().orEmpty() }

        if (!isSameRun) {
            if (state == "final" && attachments.length() > 0 && openClawAttachmentSent.add(runId)) {
                val attachmentPayload = org.json.JSONObject()
                    .put("text", "")
                    .put("attachments", attachments)
                    .toString()
                onMessagePushListener.onChatMessage(taskID, attachmentPayload, "openclaw_attachment")
            }
            return
        }

        if (state == "delta" && nextText.isNotEmpty()) {
            if (!openClawLoggedFirstEvent) {
                openClawLoggedFirstEvent = true
                OmniLog.i(tag, "openclaw ws first delta task=$taskID bytes=${nextText.length}")
            }
            val delta = computeOpenClawDelta(runId, nextText)
            if (delta.isNotEmpty()) {
                val payloadText = org.json.JSONObject().put("text", delta).toString()
                onMessagePushListener.onChatMessage(taskID, payloadText, null)
            }
            return
        }

        if (nextText.isNotEmpty()) {
            if (!openClawLoggedFirstEvent) {
                openClawLoggedFirstEvent = true
                OmniLog.i(tag, "openclaw ws first message task=$taskID bytes=${nextText.length}")
            }
            val delta = computeOpenClawDelta(runId, nextText)
            if (delta.isNotEmpty()) {
                val payloadText = org.json.JSONObject().put("text", delta).toString()
                onMessagePushListener.onChatMessage(taskID, payloadText, null)
            }
        }

        if (state == "final") {
            logResponseBody(
                "openclaw task=$taskID run=$runId",
                openClawBuffers[runId].orEmpty().ifBlank { nextText }
            )
            if (attachments.length() > 0 && openClawAttachmentSent.add(runId)) {
                val attachmentPayload = org.json.JSONObject()
                    .put("text", "")
                    .put("attachments", attachments)
                    .toString()
                onMessagePushListener.onChatMessage(taskID, attachmentPayload, "openclaw_attachment")
            }
            openClawBuffers.remove(runId)
            if (!openClawFinished) {
                openClawFinished = true
                onMessagePushListener.onChatMessageEnd(taskID)
            }
            openClawHeartbeatJob?.cancel()
            webSocket.close(1000, "completed")
            onTaskStop(TaskFinishType.FINISH, "")
            onTaskDestroy()
        } else if (state == "error" || state == "aborted") {
            val errText = "OpenClaw request failed"
            logResponseBody(
                "openclaw task=$taskID run=$runId (partial)",
                openClawBuffers[runId].orEmpty().ifBlank { nextText }
            )
            openClawBuffers.remove(runId)
            if (!openClawFinished) {
                openClawFinished = true
                onMessagePushListener.onChatMessage(taskID, errText, "error")
                onMessagePushListener.onChatMessageEnd(taskID)
            }
            openClawHeartbeatJob?.cancel()
            webSocket.close(1000, "failed")
            onTaskStop(TaskFinishType.ERROR, "OpenClaw error")
            onTaskDestroy()
        }
    }

    /**
     * 启动应用层心跳，按照 Gateway 返回的 tickIntervalMs 发送 tick
     */
    private fun startHeartbeat(
        webSocket: WebSocket,
        intervalMs: Long,
        openClawConfig: TaskParams.OpenClawConfig,
    ) {
        openClawHeartbeatJob?.cancel()
        val effectiveIntervalMs = intervalMs.coerceAtLeast(openClawMinHeartbeatIntervalMs)
        openClawHeartbeatJob = controllerScope.launch {
            while (isActive) {
                delay(effectiveIntervalMs)
                try {
                    if (!OpenClawConfigurationStore.isAuthorized(openClawConfig)) {
                        webSocket.cancel()
                        break
                    }
                    val tickFrame = org.json.JSONObject()
                    tickFrame.put("type", "req")
                    tickFrame.put("id", "tick-${System.currentTimeMillis()}")
                    tickFrame.put("method", "tick")
                    tickFrame.put("params", org.json.JSONObject())
                    val sent = OpenClawConfigurationStore.withAuthorization(openClawConfig) {
                        webSocket.send(tickFrame.toString())
                    } ?: false
                    if (!sent) break
                } catch (e: Exception) {
                    OmniLog.e(
                        tag,
                        "openclaw heartbeat send failed type=${e.javaClass.simpleName}",
                    )
                    break
                }
            }
        }
    }

    private fun buildOpenClawGatewayWsUrl(baseUrl: String): String {
        var url = baseUrl.trim()
        if (url.isEmpty()) return ""
        if (url.endsWith("/v1/chat/completions")) {
            url = url.removeSuffix("/v1/chat/completions")
        }
        if (url.endsWith("/v1")) {
            url = url.removeSuffix("/v1")
        }
        url = url.trimEnd('/')
        return when {
            url.startsWith("ws://") || url.startsWith("wss://") -> url
            url.startsWith("https://") -> "wss://${url.removePrefix("https://")}".trimEnd('/')
            url.startsWith("http://") -> "ws://${url.removePrefix("http://")}".trimEnd('/')
            else -> "ws://$url".trimEnd('/')
        }
    }

    private fun extractLatestUserMessage(content: List<Map<String, Any>>): String {
        for (i in content.size - 1 downTo 0) {
            val message = content[i]
            val role = message["role"] as? String
            if (role?.lowercase() != "user") continue
            val raw = message["content"]
            val text = extractMessageText(raw)
            if (text.isNotBlank()) return text
        }
        val fallback = content.lastOrNull()?.get("content")
        return extractMessageText(fallback)
    }

    private fun extractLatestUserAttachments(content: List<Map<String, Any>>): org.json.JSONArray {
        for (i in content.size - 1 downTo 0) {
            val message = content[i]
            val role = message["role"] as? String
            if (role?.lowercase() != "user") continue
            val attachments = extractOutgoingAttachments(message["content"])
            if (attachments.length() > 0) return attachments
        }
        return org.json.JSONArray()
    }

    private fun extractOutgoingAttachments(raw: Any?): org.json.JSONArray {
        val fromBlocks = org.json.JSONArray()
        val fromPayload = org.json.JSONArray()

        when (raw) {
            is List<*> -> {
                raw.forEachIndexed { index, item ->
                    val block = item as? Map<*, *> ?: return@forEachIndexed
                    val type = block["type"]?.toString()?.trim()?.lowercase().orEmpty()
                    when (type) {
                        "image_url", "image", "input_image" -> {
                            val url = extractImageUrlFromAny(
                                block["image_url"] ?: block["url"] ?: block["imageUrl"]
                            )
                            if (url.isBlank()) return@forEachIndexed
                            val attachment = org.json.JSONObject()
                                .put("type", "image_url")
                                .put("url", url)
                            val fileName = block["fileName"]?.toString().orEmpty()
                            if (fileName.isNotBlank()) {
                                attachment.put("fileName", fileName)
                            }
                            val mimeType = block["mimeType"]?.toString().orEmpty()
                            if (mimeType.isNotBlank()) {
                                attachment.put("mimeType", mimeType)
                            }
                            fromBlocks.put(attachment)
                        }
                        "file", "attachment", "input_file" -> {
                            val attachment = createAttachmentFromMap(block, fallbackIndex = index)
                            if (attachment != null) {
                                fromBlocks.put(attachment)
                            }
                        }
                    }
                }
            }
            is Map<*, *> -> {
                val attachments = raw["attachments"] as? List<*> ?: emptyList<Any?>()
                attachments.forEachIndexed { index, item ->
                    val attachmentMap = item as? Map<*, *> ?: return@forEachIndexed
                    val attachment = createAttachmentFromMap(attachmentMap, fallbackIndex = index)
                    if (attachment != null) {
                        fromPayload.put(attachment)
                    }
                }
            }
        }

        return mergeAttachmentArrays(fromBlocks, fromPayload)
    }

    private fun createAttachmentFromMap(
        source: Map<*, *>,
        fallbackIndex: Int,
    ): org.json.JSONObject? {
        if (!shouldSendAttachmentToModel(source)) return null
        val rawUrl = source["url"]?.toString().orEmpty()
        val rawDataUrl = source["dataUrl"]?.toString().orEmpty()
        val path = source["path"]?.toString().orEmpty()
        val resolvedUrl = when {
            rawDataUrl.isNotBlank() -> rawDataUrl
            rawUrl.isNotBlank() -> rawUrl
            else -> ""
        }

        if (resolvedUrl.isBlank() && path.isBlank()) return null

        val result = org.json.JSONObject()
        val type = source["type"]?.toString()?.trim().orEmpty().ifBlank { "file" }
        result.put("type", type)
        if (resolvedUrl.isNotBlank()) {
            result.put("url", resolvedUrl)
        }
        if (path.isNotBlank()) {
            result.put("path", path)
        }

        val name = source["name"]?.toString().orEmpty()
        val fileName = source["fileName"]?.toString().orEmpty().ifBlank { name }
        if (fileName.isNotBlank()) {
            result.put("fileName", fileName)
        } else {
            result.put("fileName", "attachment_$fallbackIndex")
        }

        val mimeType = source["mimeType"]?.toString().orEmpty()
        if (mimeType.isNotBlank()) {
            result.put("mimeType", mimeType)
        }

        return result
    }

    private fun filterModelAttachments(
        attachments: org.json.JSONArray,
    ): org.json.JSONArray {
        val result = org.json.JSONArray()
        for (i in 0 until attachments.length()) {
            val item = attachments.optJSONObject(i) ?: continue
            if (shouldSendAttachmentToModel(item)) {
                result.put(item)
            }
        }
        return result
    }

    private fun shouldSendAttachmentToModel(source: Map<*, *>): Boolean {
        return when (val raw = source["sendToModel"]) {
            is Boolean -> raw
            is String -> !raw.equals("false", ignoreCase = true)
            else -> true
        }
    }

    private fun shouldSendAttachmentToModel(source: org.json.JSONObject): Boolean {
        if (!source.has("sendToModel")) return true
        return when (val raw = source.opt("sendToModel")) {
            is Boolean -> raw
            is String -> !raw.equals("false", ignoreCase = true)
            else -> true
        }
    }

    private fun extractImageUrlFromAny(raw: Any?): String {
        return when (raw) {
            is String -> raw.trim()
            is Map<*, *> -> raw["url"]?.toString()?.trim().orEmpty()
            else -> ""
        }
    }

    private fun extractMessageText(raw: Any?): String {
        return when (raw) {
            is String -> raw
            is List<*> -> {
                val parts = mutableListOf<String>()
                for (item in raw) {
                    val obj = item as? Map<*, *> ?: continue
                    val type = obj["type"] as? String
                    val text = when (type) {
                        "text", "input_text" -> obj["text"] as? String
                        else -> obj["text"] as? String
                    }
                    if (!text.isNullOrBlank()) parts.add(text)
                }
                parts.joinToString("\n")
            }
            else -> ""
        }
    }

    private fun extractTextFromContentArray(content: org.json.JSONArray?): String {
        if (content == null) return ""
        val parts = mutableListOf<String>()
        for (i in 0 until content.length()) {
            val obj = content.optJSONObject(i) ?: continue
            val type = obj.optString("type")
            val text = when (type) {
                "text", "input_text" -> obj.optString("text")
                else -> obj.optString("text")
            }
            if (text.isNotBlank()) parts.add(text)
        }
        return parts.joinToString("\n")
    }

    private fun extractAttachmentsFromContentArray(content: org.json.JSONArray?): org.json.JSONArray {
        val result = org.json.JSONArray()
        if (content == null) return result
        for (i in 0 until content.length()) {
            val obj = content.optJSONObject(i) ?: continue
            val type = obj.optString("type")
            if (type == "text" || type == "input_text") continue
            val url = obj.optString("url")
            if (url.isBlank()) continue
            val attachment = org.json.JSONObject()
                .put("type", type.ifBlank { "file" })
                .put("mimeType", obj.optString("mimeType"))
                .put("fileName", obj.optString("fileName"))
                .put("url", url)
            result.put(attachment)
        }
        return result
    }

    private fun extractAttachmentsFromPayload(
        payload: org.json.JSONObject,
        openClawConfig: TaskParams.OpenClawConfig?,
    ): org.json.JSONArray {
        val result = org.json.JSONArray()
        val attachments = payload.optJSONArray("attachments") ?: return result
        for (i in 0 until attachments.length()) {
            val obj = attachments.optJSONObject(i) ?: continue
            val rawUrl = obj.optString("url")
            val url = normalizeOpenClawAttachmentUrl(rawUrl, openClawConfig)
            if (url.isBlank()) continue
            val attachment = org.json.JSONObject()
                .put("type", obj.optString("type").ifBlank { "file" })
                .put("mimeType", obj.optString("mimeType"))
                .put("fileName", obj.optString("fileName"))
                .put("url", url)
            val path = obj.optString("path")
            if (path.isNotBlank()) {
                attachment.put("path", path)
            }
            result.put(attachment)
        }
        return result
    }

    private fun mergeAttachmentArrays(
        primary: org.json.JSONArray,
        secondary: org.json.JSONArray,
    ): org.json.JSONArray {
        val result = org.json.JSONArray()
        val seen = mutableSetOf<String>()
        fun addItems(source: org.json.JSONArray) {
            for (i in 0 until source.length()) {
                val obj = source.optJSONObject(i) ?: continue
                val key = buildAttachmentKey(obj, i)
                if (seen.contains(key)) continue
                seen.add(key)
                result.put(obj)
            }
        }
        addItems(primary)
        addItems(secondary)
        return result
    }

    private fun buildAttachmentKey(obj: org.json.JSONObject, index: Int): String {
        val mimeType = obj.optString("mimeType")
        val fileName = obj.optString("fileName")
        val url = obj.optString("url")
        val path = obj.optString("path")
        val digest = when {
            url.isNotBlank() -> url
            path.isNotBlank() -> path
            else -> index.toString()
        }
        return "$mimeType|$fileName|$digest"
    }

    private fun normalizeOpenClawAttachmentUrl(
        url: String?,
        openClawConfig: TaskParams.OpenClawConfig?,
    ): String {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return ""
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("ws://")) return "http://${raw.removePrefix("ws://")}"
        if (raw.startsWith("wss://")) return "https://${raw.removePrefix("wss://")}"
        val baseUrl = openClawConfig?.baseUrl?.trim().orEmpty()
        val httpBase = buildOpenClawHttpBaseUrl(baseUrl)
        if (httpBase.isBlank()) return raw
        return if (raw.startsWith("/")) {
            httpBase + raw
        } else {
            "$httpBase/$raw"
        }
    }

    private fun buildOpenClawHttpBaseUrl(baseUrl: String): String {
        var url = baseUrl.trim()
        if (url.isEmpty()) return ""
        if (url.endsWith("/v1/chat/completions")) {
            url = url.removeSuffix("/v1/chat/completions")
        }
        if (url.endsWith("/v1")) {
            url = url.removeSuffix("/v1")
        }
        url = url.trimEnd('/')
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("ws://") -> "http://${url.removePrefix("ws://")}".trimEnd('/')
            url.startsWith("wss://") -> "https://${url.removePrefix("wss://")}".trimEnd('/')
            else -> "http://$url".trimEnd('/')
        }
    }

    private fun appendOpenClawDelta(runId: String, delta: String) {
        if (delta.isBlank()) return
        val previous = openClawBuffers[runId].orEmpty()
        openClawBuffers[runId] = previous + delta
    }

    private fun computeOpenClawDelta(runId: String, nextText: String): String {
        val previous = openClawBuffers[runId].orEmpty()
        return if (nextText.startsWith(previous)) {
            val delta = nextText.substring(previous.length)
            openClawBuffers[runId] = nextText
            delta
        } else {
            openClawBuffers[runId] = previous + nextText
            nextText
        }
    }

    private fun logOpenClawBuffers(label: String) {
        openClawBuffers.forEach { (runId, content) ->
            if (content.isNotBlank()) {
                logResponseBody("$label run=$runId", content)
            }
        }
    }

    private fun logResponseBody(label: String, body: String?) {
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)?.size ?: 0
        OmniLog.i(tag, "$label responseBodyBytes=$bodyBytes")
    }

    private fun rejectStaleOpenClawTask(
        taskID: String,
        listener: OnMessagePushListener,
    ) {
        if (openClawFinished) return
        openClawFinished = true
        launchOrderedStreamDispatch {
            listener.onChatMessage(taskID, "OpenClaw authorization required", "error")
            listener.onChatMessageEnd(taskID)
            onTaskStop(TaskFinishType.ERROR, "OpenClaw authorization required")
            onTaskDestroy()
            taskManager.unregisterChatTask(taskID)
        }
    }

    private data class OpenClawHandshakeIdentity(
        val deviceId: String,
        val publicKey: String,
        val authToken: String,
        val signature: String,
    )


    override suspend fun emit(value: String) {
        if (value.contains("StreamFinish")) {
            finishTask()
        } else {
            onMessagePushListener?.onChatMessage(taskID, value, null)
        }
    }
}
