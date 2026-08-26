package com.rk.terminal.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rk.libcommons.TerminalCommand
import com.rk.resources.drawables
import com.rk.resources.strings
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.screens.settings.Settings
import com.rk.terminal.ui.screens.terminal.HeadlessTerminalSessionClient
import com.rk.terminal.ui.screens.terminal.MkSession
import com.termux.terminal.TerminalSession

class SessionService : Service() {
    companion object {
        private const val TAG = "SessionService"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_COLUMNS = 120
        private const val DEFAULT_ROWS = 40
        private const val DEFAULT_CELL_WIDTH = 10
        private const val DEFAULT_CELL_HEIGHT = 20
        private const val AGENT_SESSION_ID_PREFIX = "session_"

        /**
         * 无头（Agent）会话基础环境：OMNIBOT_HEADLESS=1 让 root 段走 setsid + 写 pid 文件分支，
         * OMNIBOT_EXECUTOR_KEY=sessionId 使 pid 文件按会话隔离——停止会话时 killpg 据此定位
         * 进程组，不再共享 chroot-default.pid（开发者审查 P1#2）。
         */
        @VisibleForTesting
        internal fun headlessBaseEnv(sessionId: String): LinkedHashMap<String, String> = linkedMapOf(
            "OMNIBOT_HEADLESS" to "1",
            "OMNIBOT_EXECUTOR_KEY" to sessionId,
            "HOME" to "/root",
            "PAGER" to "cat",
            "GIT_PAGER" to "cat"
        )

        /**
         * 会话后端是否已与当前 Agent 后端不一致：切换后端后旧会话必须终止重建，
         * 否则 Agent 会复用切换前创建的 proot 会话（真机复现）；无记录按不一致处理。
         * public：app 模块的 ReTerminalSessionBridge 也要用。
         */
        fun backendChanged(existing: Int?, current: Int): Boolean = existing != current
    }

    data class HeadlessSessionAccess(
        val sessionId: String,
        val session: TerminalSession,
        val created: Boolean
    )

    private val sessions = hashMapOf<String, TerminalSession>()
    // 会话创建时的容器后端：Agent 切换后端后据此终止旧会话重建（见 backendChanged）
    private val sessionBackends = hashMapOf<String, Int>()
    val sessionList = mutableStateMapOf<String,Int>()
    var currentSession = mutableStateOf(Pair("main",com.rk.settings.Settings.working_Mode))

    inner class SessionBinder : Binder() {
        fun getService():SessionService{
            return this@SessionService
        }
        fun terminateAllSessions(){
            sessions.values.forEach{
                it.finishIfRunning()
            }
            sessions.clear()
            sessionBackends.clear()
            sessionList.clear()
            currentSession.value = Pair("main", com.rk.settings.Settings.working_Mode)
            updateNotification()
        }

        fun createSession(
            id: String,
            context: android.content.Context,
            workingMode:Int,
            launchCommand: TerminalCommand? = null
        ): TerminalSession {
            val existing = sessions[id]
            if (existing != null) {
                sessionList[id] = workingMode
                currentSession.value = Pair(id, workingMode)
                updateNotification()
                return existing
            }
            return MkSession.createSession(
                context,
                HeadlessTerminalSessionClient,
                id,
                workingMode = workingMode,
                launchCommand = launchCommand
            ).also {
                sessions[id] = it
                sessionBackends[id] = com.rk.settings.Settings.container_backend
                sessionList[id] = workingMode
                currentSession.value = Pair(id, workingMode)
                updateNotification()
            }
        }

        fun createHeadlessSession(
            requestedId: String?,
            context: android.content.Context,
            workingMode: Int,
            sessionTitle: String? = null,
            extraEnv: Map<String, String> = emptyMap(),
            agentBackend: Int? = null
        ): HeadlessSessionAccess {
            val sessionId = resolveSessionId(requestedId)
            val existing = sessions[sessionId]
            if (existing != null) {
                existing.mSessionName = sessionTitle?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: existing.mSessionName
                sessionList[sessionId] = workingMode
                currentSession.value = Pair(sessionId, workingMode)
                updateNotification()
                return HeadlessSessionAccess(
                    sessionId = sessionId,
                    session = existing,
                    created = false
                )
            }

            val mergedEnv = headlessBaseEnv(sessionId).apply {
                putAll(extraEnv)
            }

            val session = MkSession.createSession(
                context = context,
                sessionClient = HeadlessTerminalSessionClient,
                session_id = sessionId,
                workingMode = workingMode,
                extraEnv = mergedEnv,
                launchCommand = null,
                // Agent 无头会话只认 agent 开关（默认读独立设置；桥接层会传入经 RootProbe
                // 重检后的值），不认终端 UI 性能开关（开发者审查 P1#1）
                backendOverride = agentBackend ?: com.rk.settings.Settings.agent_container_backend
            ).also { created ->
                created.mSessionName = sessionTitle?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: "Agent Session"
                created.updateTerminalSessionClient(HeadlessTerminalSessionClient)
                if (created.emulator == null) {
                    created.updateSize(
                        DEFAULT_COLUMNS,
                        DEFAULT_ROWS,
                        DEFAULT_CELL_WIDTH,
                        DEFAULT_CELL_HEIGHT
                    )
                }
                sessions[sessionId] = created
                sessionBackends[sessionId] = agentBackend ?: com.rk.settings.Settings.agent_container_backend
                sessionList[sessionId] = workingMode
                currentSession.value = Pair(sessionId, workingMode)
                updateNotification()
            }

            return HeadlessSessionAccess(
                sessionId = sessionId,
                session = session,
                created = true
            )
        }

        fun getSession(id: String): TerminalSession? {
            return sessions[id]
        }

        /** 会话创建时的容器后端；无记录返回 null（历史会话） */
        fun backendOfSession(id: String): Int? {
            return sessionBackends[id]
        }
        fun terminateSession(id: String) {
            runCatching {
                //crash is here
                sessions[id]?.apply {
                    if (emulator != null){
                        sessions[id]?.finishIfRunning()
                    }
                }

                sessions.remove(id)
                sessionBackends.remove(id)
                sessionList.remove(id)
                if (sessions.isEmpty()) {
                    currentSession.value = Pair("main", com.rk.settings.Settings.working_Mode)
                    stopSelf()
                } else {
                    if (currentSession.value.first == id) {
                        sessionList.entries.firstOrNull()?.let { next ->
                            currentSession.value = Pair(next.key, next.value)
                        }
                    }
                    updateNotification()
                }
            }.onFailure { it.printStackTrace() }

        }
    }

    private val binder = SessionBinder()
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        sessions.forEach { s -> s.value.finishIfRunning() }
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForegroundForAndroid14(notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startForegroundForAndroid14(notification: Notification) {
        val preferredType = resolveForegroundServiceType()
        if (tryStartForeground(notification, preferredType)) {
            return
        }

        if (preferredType != ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC &&
            hasPermission(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC) &&
            tryStartForeground(notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        ) {
            return
        }

        Log.e(TAG, "Unable to start SessionService as a foreground service; stopping.")
        stopSelf()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveForegroundServiceType(): Int {
        return if (hasPermission(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun tryStartForeground(notification: Notification, foregroundServiceType: Int): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
            true
        } catch (error: RuntimeException) {
            Log.e(
                TAG,
                "Failed to start foreground service with type=$foregroundServiceType",
                error
            )
            false
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_EXIT" -> {
                sessions.forEach { s -> s.value.finishIfRunning() }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exitIntent = Intent(this, SessionService::class.java).apply {
            action = "ACTION_EXIT"
        }
        val exitPendingIntent = PendingIntent.getService(
            this, 1, exitIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ReTerminal")
            .setContentText(getNotificationContentText())
            .setSmallIcon(drawables.terminal)
            .setContentIntent(pendingIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    null,
                    "EXIT",
                    exitPendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private val CHANNEL_ID = "session_service_channel"

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Session Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification for Terminal Service"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val notification = createNotification()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun getNotificationContentText(): String {
        val count = sessions.size
        if (count == 1){
            return "1 session running"
        }
        return "$count sessions running"
    }

    private fun resolveSessionId(requestedId: String?): String {
        val sanitizedRequestedId = sanitizeSessionId(requestedId)
        if (!sanitizedRequestedId.isNullOrEmpty()) {
            return sanitizedRequestedId
        }
        var candidate: String
        do {
            candidate = AGENT_SESSION_ID_PREFIX + java.util.UUID.randomUUID().toString().take(8)
        } while (sessions.containsKey(candidate))
        return candidate
    }

    private fun sanitizeSessionId(raw: String?): String? {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        return normalized
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .take(48)
            .takeIf { it.isNotEmpty() }
    }
}
