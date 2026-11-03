package cn.com.omnimind.baselib.privileged

import android.content.Context
import android.os.Process
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.UUID
import cn.com.omnimind.baselib.privileged.PrivilegedSessionRegistry.Binding as SessionBinding

data class PrivilegedStatus(
    val provider: PrivilegedProvider = PrivilegedProvider.NONE,
    val backend: PrivilegedBackend = PrivilegedBackend.NONE,
    val generation: Long = 0,
    val availableActions: List<String> = emptyList(),
) {
    fun isGranted(): Boolean = provider != PrivilegedProvider.NONE && backend != PrivilegedBackend.NONE
    val label: String get() = when (provider) {
        PrivilegedProvider.ROOT -> "Root"
        PrivilegedProvider.SHIZUKU -> "Shizuku (${backend.name})"
        PrivilegedProvider.NONE -> "Unavailable"
    }
    companion object {
        fun selectBackend(root: RootAccessSnapshot, shizuku: PrivilegedStatus): PrivilegedStatus = if (root.granted) {
            PrivilegedStatus(PrivilegedProvider.ROOT, PrivilegedBackend.ROOT, root.generation,
                PrivilegedActionPolicy.visibleAgentActions(PrivilegedBackend.ROOT))
        } else shizuku
    }

}

/** 只选择执行后端，Agent 的会话、确认和工具生命周期仍由 ACP 持有。 */
class PrivilegedCapabilityManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shizuku = ShizukuCapabilityManager.get(appContext)
    private val sessions = PrivilegedSessionRegistry()
    private val userId = Process.myUid() / 100000
    private lateinit var root: RootAccessController
    private val runner = PrivilegedProcessRunner(
        root = true,
        leaseDirectory = File(appContext.cacheDir, "privileged-processes"),
        ownerPid = Process.myPid(),
        admission = { root.snapshot.enabled },
        accessDenied = { root.markDenied() },
    )
    private val rootExecutor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT, runner)
    private val device = PrivilegedDeviceController(appContext, userId)

    init {
        // 进程恢复时撤销上次进程遗留的租约，不启动 su，也不保留跨进程 shell。
        File(appContext.cacheDir, "privileged-processes").listFiles()
            ?.filter { it.name.startsWith("process-") && it.name.endsWith(".lease") }
            ?.forEach { it.delete() }
        val prefs = appContext.getSharedPreferences("omni_root_access", Context.MODE_PRIVATE)
        root = RootAccessController(
            preferences = object : RootAccessPreferences {
                override var enabled: Boolean
                    get() = prefs.getBoolean("enabled", false)
                    set(value) { prefs.edit().putBoolean("enabled", value).apply() }
                override var previouslyGranted: Boolean
                    get() = prefs.getBoolean("previously_granted", false)
                    set(value) { prefs.edit().putBoolean("previously_granted", value).apply() }
            },
            scope = scope,
            probe = {
                val result = runInterruptible {
                    runner.execute("root-probe", listOf("/system/bin/id", "-u"), 30)
                }
                when {
                    result.success && result.stdout.trim() == "0" -> RootAccessStatus.GRANTED
                    result.timedOut -> RootAccessStatus.TIMED_OUT
                    result.errorCode == "root_denied" -> RootAccessStatus.DENIED
                    else -> RootAccessStatus.UNAVAILABLE
                }
            },
            revoke = {
                rootExecutor.shutdown()
                sessions.removeProvider(PrivilegedProvider.ROOT)
            },
        )
    }

    fun initialize() { root.initialize() }
    fun rootStatus(): RootAccessSnapshot = root.snapshot
    suspend fun setRootEnabled(enabled: Boolean): RootAccessSnapshot = root.setEnabled(enabled)
    suspend fun requestRootAccess(): RootAccessSnapshot = root.requestAccess()

    fun getStatus(): PrivilegedStatus {
        val other = shizuku.getStatus()
        val shizukuStatus = if (other.isGranted()) status(PrivilegedProvider.SHIZUKU, other.backend, shizuku.generation) else PrivilegedStatus()
        return PrivilegedStatus.selectBackend(root.snapshot, shizukuStatus)
    }

    fun reserve(ownerId: String, sessionId: String? = null, acpSessionId: String? = null): Access {
        val selected = if (sessionId == null) getStatus() else sessions.selection(sessionId, ownerId, acpSessionId) ?: PrivilegedStatus()
        return Access(ownerId, acpSessionId, selected)
    }

    suspend fun closeAcpSession(acpSessionId: String) {
        sessions.forSession(acpSessionId).forEach { (sessionId, binding) ->
            val result = Access(binding.ownerId, acpSessionId, binding.status).stopPrivilegedSession(sessionId)
            check(result.success || result.code == "session_not_found") { "Could not close privileged shell: ${result.code}" }
        }
    }

    private fun status(provider: PrivilegedProvider, backend: PrivilegedBackend, generation: Long) = PrivilegedStatus(
        provider, backend, generation, PrivilegedActionPolicy.visibleAgentActions(backend)
    )

    private fun valid(status: PrivilegedStatus): Boolean = when (status.provider) {
        PrivilegedProvider.ROOT -> root.snapshot.let { it.granted && it.generation == status.generation }
        PrivilegedProvider.SHIZUKU -> shizuku.getStatus().let {
            it.isGranted() && it.backend == status.backend && shizuku.generation == status.generation
        }
        PrivilegedProvider.NONE -> false
    }

    inner class Access internal constructor(private val ownerId: String, private val acpSessionId: String?, val status: PrivilegedStatus) {
        fun isValid(): Boolean = valid(status)

        suspend fun executeAgentAction(action: String, arguments: Map<String, String>, requiresConfirmation: Boolean): PrivilegedResult =
            execute(PrivilegedRequest(UUID.randomUUID().toString(), action, arguments, requiresConfirmation, userId = userId))

        suspend fun executeRawShell(
            command: String, timeoutSeconds: Int? = null, workingDirectory: String? = null,
            environment: Map<String, String> = emptyMap(), confirmed: Boolean = false,
        ): PrivilegedResult = execute(PrivilegedRequest(
            UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_SHELL_EXEC,
            confirmation(confirmed), command = command, timeoutSeconds = timeoutSeconds,
            workingDirectory = workingDirectory, environment = environment, userId = userId,
        ))

        suspend fun startPrivilegedSession(
            sessionName: String? = null, workingDirectory: String? = null,
            environment: Map<String, String> = emptyMap(), confirmed: Boolean = false,
        ): PrivilegedResult {
            require(!acpSessionId.isNullOrBlank()) { "An ACP session is required for a persistent privileged shell." }
            val sessionId = UUID.randomUUID().toString()
            val result = execute(PrivilegedRequest(
                UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_SESSION_START,
                confirmation(confirmed) + mapOf("sessionName" to sessionName.orEmpty()),
                workingDirectory = workingDirectory, environment = environment, sessionId = sessionId, userId = userId,
            ), checkSession = false)
            if (result.success) {
                sessions.register(sessionId, SessionBinding(ownerId, acpSessionId, status))
                if (!isValid()) {
                    stopPrivilegedSession(sessionId)
                    return failure(PrivilegedRequest(result.requestId, result.action), "privileged_access_changed")
                }
            }
            return result
        }

        suspend fun execPrivilegedSession(sessionId: String, command: String, timeoutSeconds: Int?, confirmed: Boolean): PrivilegedResult =
            execute(PrivilegedRequest(
                UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_SESSION_EXEC,
                confirmation(confirmed), command = command, timeoutSeconds = timeoutSeconds, sessionId = sessionId, userId = userId,
            ))

        suspend fun readPrivilegedSession(sessionId: String): PrivilegedResult = execute(PrivilegedRequest(
            UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_SESSION_READ, sessionId = sessionId, userId = userId,
        ))

        suspend fun stopPrivilegedSession(sessionId: String): PrivilegedResult {
            val request = PrivilegedRequest(
                UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_SESSION_STOP, sessionId = sessionId, userId = userId,
            )
            val binding = SessionBinding(ownerId, acpSessionId, status)
            if (!sessions.owns(sessionId, binding)) return failure(request, "session_not_found")
            // 关闭只释放已存在的进程，撤销权限之后仍然必须能够清理。
            val result = when (status.provider) {
                PrivilegedProvider.ROOT -> runInterruptible(Dispatchers.IO) { rootExecutor.execute(request) }
                PrivilegedProvider.SHIZUKU -> shizuku.stopExistingSession(request, status.generation)
                PrivilegedProvider.NONE -> failure(request, "session_not_found")
            }
            if (result.success || result.code == "session_not_found") sessions.remove(sessionId, binding)
            return result.copy(provider = status.provider, backend = status.backend)
        }

        private suspend fun execute(request: PrivilegedRequest, checkSession: Boolean = true): PrivilegedResult {
            if (checkSession && request.sessionId != null && !sessions.owns(request.sessionId, SessionBinding(ownerId, acpSessionId, status))) {
                return failure(request, "session_not_found")
            }
            if (!isValid()) return failure(request, "privileged_access_changed")
            if (PrivilegedActionPolicy.requiresConfirmation(request.action) && request.arguments["confirmed"] != "true") {
                return failure(request, "confirmation_required").copy(requiresConfirmation = true)
            }
            val result = if (device.handles(request.action)) {
                device.execute(request, status.backend, ::isValid) { command ->
                    dispatch(request.copy(action = PrivilegedActionPolicy.ACTION_DEVICE_COMMAND, command = command, requiresConfirmation = false, timeoutSeconds = 8))
                }
            } else dispatch(request)
            if (result.code == "root_denied") root.markDenied()
            if (result.code == "root_unavailable") root.markUnavailable()
            if (result.code == "session_not_found" && request.sessionId != null) sessions.remove(request.sessionId)
            return result.copy(provider = status.provider, backend = status.backend)
        }

        private suspend fun dispatch(request: PrivilegedRequest): PrivilegedResult {
            if (!isValid()) return failure(request, "privileged_access_changed")
            return when (status.provider) {
                PrivilegedProvider.ROOT -> runInterruptible(Dispatchers.IO) { rootExecutor.execute(request, ::isValid) }
                PrivilegedProvider.SHIZUKU -> shizuku.execute(request, status.generation, status.backend)
                PrivilegedProvider.NONE -> failure(request, "permission_denied")
            }
        }

        private fun failure(request: PrivilegedRequest, code: String) = PrivilegedResult(
            request.requestId, request.action, false, code, when (code) {
                "session_not_found" -> "The privileged session is unavailable or belongs to another conversation."
                "confirmation_required" -> "This action requires ACP client permission."
                else -> "Privileged access changed or is unavailable; no action was executed."
            }, backend = status.backend, provider = status.provider,
        )
    }

    companion object {
        @Volatile private var instance: PrivilegedCapabilityManager? = null
        suspend fun closeExistingAcpSession(acpSessionId: String) {
            instance?.closeAcpSession(acpSessionId)
        }
        fun get(context: Context): PrivilegedCapabilityManager = instance ?: synchronized(this) {
            instance ?: PrivilegedCapabilityManager(context).also { instance = it }
        }
        private fun confirmation(confirmed: Boolean) = if (confirmed) mapOf("confirmed" to "true") else emptyMap()
    }
}
