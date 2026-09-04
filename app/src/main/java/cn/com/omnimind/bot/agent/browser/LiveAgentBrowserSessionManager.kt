package cn.com.omnimind.bot.agent

import android.content.Context
import android.view.ViewGroup

interface AgentBrowserLiveSessionHandle {
    val workspaceId: String

    fun closeSession()
}

class LiveAgentBrowserSessionStore<T : AgentBrowserLiveSessionHandle> {
    private val sessions = linkedMapOf<String, T>()
    @Volatile
    private var currentWorkspaceId: String? = null

    @Synchronized
    fun acquire(workspaceId: String, create: () -> T): T {
        val existing = sessions[workspaceId]
        currentWorkspaceId = workspaceId
        return existing ?: create().also { sessions[workspaceId] = it }
    }

    fun current(): T? = currentWorkspaceId?.let(sessions::get)

    @Synchronized
    fun clear() {
        sessions.values.toList().forEach { it.closeSession() }
        sessions.clear()
        currentWorkspaceId = null
    }
}

object LiveAgentBrowserSessionManager {
    private val store = LiveAgentBrowserSessionStore<BrowserUseEngine>()

    fun acquireEngine(
        context: Context,
        workspaceManager: AgentWorkspaceManager,
        agentRunId: String,
        workspace: AgentWorkspaceDescriptor
    ): BrowserUseEngine {
        val engine = store.acquire(workspace.id) {
            BrowserUseEngine(
                context = context,
                workspaceManager = workspaceManager,
                agentRunId = agentRunId,
                workspace = workspace
            )
        }
        return engine
    }

    fun currentSnapshot(): Map<String, Any?> {
        return store.current()?.liveSessionSnapshot() ?: BrowserUseEngine.unavailableSnapshot()
    }

    suspend fun handleCurrentCall(
        method: String,
        arguments: Map<String, Any?> = emptyMap()
    ): Any? {
        val engine = store.current()
        return if (engine == null) {
            if (
                method == "getSnapshot" ||
                method == "getLiveBrowserSessionSnapshot" ||
                method == "capturePreviewFrame"
            ) {
                BrowserUseEngine.unavailableSnapshot()
            } else {
                throw IllegalStateException("当前没有可用的浏览器会话")
            }
        } else {
            engine.handleHostCall(method = method, arguments = arguments)
        }
    }

    suspend fun executeCurrent(request: BrowserUseRequest): BrowserUseOutcome? {
        return store.current()?.execute(request)
    }

    suspend fun captureCurrentFramePng(): ByteArray? {
        return store.current()?.captureActiveFramePng()
    }

    fun attachActiveTabTo(
        container: ViewGroup,
        hostContext: Context,
        workspaceId: String
    ): Boolean {
        val engine = store.current()
        if (engine == null || engine.workspaceId != workspaceId) {
            return false
        }
        return engine.attachActiveTabTo(container = container, hostContext = hostContext)
    }

    fun detachActiveTabFrom(container: ViewGroup? = null) {
        store.current()?.detachActiveTabFrom(container)
    }

    fun releaseRunOwnership() {
        // 浏览器会话改为全局保活；run 结束时不销毁会话。
    }
}
