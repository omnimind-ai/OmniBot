package cn.com.omnimind.bot.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.rk.terminal.runtime.RootProbe
import com.rk.terminal.service.SessionService
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.rk.settings.Settings
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ReTerminalSessionBridge {
    data class SessionAccessResult(
        val sessionId: String,
        val session: TerminalSession,
        val created: Boolean
    )

    private const val BIND_TIMEOUT_MS = 15_000L
    private const val SESSION_STARTUP_GRACE_MS = 2_000L
    private const val SESSION_STARTUP_POLL_MS = 50L

    private val bindMutex = Mutex()
    @Volatile
    private var binder: SessionService.SessionBinder? = null
    @Volatile
    private var bindDeferred: CompletableDeferred<SessionService.SessionBinder>? = null
    @Volatile
    private var serviceConnection: ServiceConnection? = null

    suspend fun ensureHeadlessSession(
        context: Context,
        sessionId: String? = null,
        sessionTitle: String? = null,
        extraEnv: Map<String, String> = emptyMap(),
        workingMode: Int = Settings.terminal_distribution
    ): SessionAccessResult {
        // Agent 无头会话用独立开关 + 每次启动经 RootProbe 重检实际 uid/能力，
        // 授权撤销则回退 proot 并改写持久化设置（开发者审查 P1#1 / P2#6）
        val agentBackend = RootProbe.resolveAgentBackend()
        return withContext(Dispatchers.Main.immediate) {
            val sessionBinder = awaitBinder(context)
            val requestedSessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() }
            requestedSessionId?.let { existingId ->
                sessionBinder.getSession(existingId)?.let { existing ->
                    val existingWorkingMode = sessionBinder.getService().sessionList[existingId]
                    if (existingWorkingMode != workingMode) {
                        sessionBinder.terminateSession(existingId)
                        return@let
                    }
                    // 后端切换后旧会话必须重建：否则 Agent 复用切换前的 proot 会话（真机复现）
                    if (SessionService.backendChanged(
                            sessionBinder.backendOfSession(existingId),
                            agentBackend
                        )
                    ) {
                        sessionBinder.terminateSession(existingId)
                        return@let
                    }
                    if (existing.isRunning || awaitSessionRunning(existing)) {
                        return@withContext SessionAccessResult(
                            sessionId = requestedSessionId,
                            session = existing,
                            created = false
                        )
                    }
                    sessionBinder.terminateSession(existingId)
                }
            }
            val access = sessionBinder.createHeadlessSession(
                requestedId = requestedSessionId,
                context = context.applicationContext,
                workingMode = workingMode,
                sessionTitle = sessionTitle,
                extraEnv = extraEnv,
                agentBackend = agentBackend
            )
            awaitSessionRunning(access.session)
            SessionAccessResult(
                sessionId = access.sessionId,
                session = access.session,
                created = access.created
            )
        }
    }

    suspend fun getSession(
        context: Context,
        sessionId: String
    ): TerminalSession? = withContext(Dispatchers.Main.immediate) {
        awaitBinder(context).getSession(sessionId)
    }

    suspend fun sendCommand(
        context: Context,
        sessionId: String,
        command: String
    ) = withContext(Dispatchers.Main.immediate) {
        val session = awaitBinder(context).getSession(sessionId)
            ?: error("ReTerminal session not found: $sessionId")
        val payload = if (command.endsWith("\n")) command else "$command\n"
        val bytes = payload.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    suspend fun stopSession(
        context: Context,
        sessionId: String
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        val sessionBinder = awaitBinder(context)
        val service = sessionBinder.getService()
        val existed = sessionBinder.getSession(sessionId) != null
        if (!existed) {
            return@withContext false
        }
        sessionBinder.terminateSession(sessionId)
        if (service.sessionList.isEmpty()) {
            clearCachedBinding(context.applicationContext)
        }
        true
    }

    private suspend fun awaitBinder(context: Context): SessionService.SessionBinder {
        binder?.let { return it }
        val deferred = bindMutex.withLock {
            binder?.let { return it }
            bindDeferred?.let { return@withLock it }

            val nextDeferred = CompletableDeferred<SessionService.SessionBinder>()
            val appContext = context.applicationContext
            val intent = Intent(appContext, SessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val sessionBinder = service as? SessionService.SessionBinder
                    if (sessionBinder == null) {
                        nextDeferred.completeExceptionally(
                            IllegalStateException("SessionService binder unavailable.")
                        )
                        return
                    }
                    binder = sessionBinder
                    nextDeferred.complete(sessionBinder)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    binder = null
                    bindDeferred = null
                    serviceConnection = null
                }

                override fun onBindingDied(name: ComponentName?) {
                    binder = null
                    bindDeferred = null
                    serviceConnection = null
                    if (!nextDeferred.isCompleted) {
                        nextDeferred.completeExceptionally(
                            IllegalStateException("SessionService binding died.")
                        )
                    }
                }

                override fun onNullBinding(name: ComponentName?) {
                    binder = null
                    bindDeferred = null
                    serviceConnection = null
                    if (!nextDeferred.isCompleted) {
                        nextDeferred.completeExceptionally(
                            IllegalStateException("SessionService returned null binding.")
                        )
                    }
                }
            }

            serviceConnection = connection
            bindDeferred = nextDeferred
            if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                bindDeferred = null
                serviceConnection = null
                nextDeferred.completeExceptionally(
                    IllegalStateException("Failed to bind SessionService.")
                )
            }
            nextDeferred
        }
        return try {
            withTimeout(BIND_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (error: TimeoutCancellationException) {
            bindMutex.withLock {
                if (bindDeferred === deferred) {
                    bindDeferred = null
                }
            }
            clearCachedBinding(context.applicationContext)
            throw IllegalStateException(
                "SessionService bind timed out after ${BIND_TIMEOUT_MS}ms.",
                error
            )
        } catch (error: Throwable) {
            bindMutex.withLock {
                if (bindDeferred === deferred) {
                    bindDeferred = null
                }
            }
            throw error
        }
    }

    private suspend fun awaitSessionRunning(session: TerminalSession): Boolean {
        if (session.isRunning) {
            return true
        }
        return withTimeoutOrNull(SESSION_STARTUP_GRACE_MS) {
            while (!session.isRunning) {
                delay(SESSION_STARTUP_POLL_MS)
            }
            true
        } == true
    }

    private fun clearCachedBinding(appContext: Context) {
        binder = null
        bindDeferred = null
        serviceConnection?.let { connection ->
            runCatching {
                appContext.unbindService(connection)
            }
        }
        serviceConnection = null
    }
}
