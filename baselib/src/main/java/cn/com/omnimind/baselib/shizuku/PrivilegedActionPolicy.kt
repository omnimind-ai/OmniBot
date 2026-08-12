package cn.com.omnimind.baselib.shizuku

object PrivilegedActionPolicy {
    private data class CommandBlockRule(
        val regex: Regex,
        val reason: String,
    )

    const val ACTION_PACKAGE_LAUNCH = "package_control.launch_activity"
    const val ACTION_PACKAGE_FORCE_STOP = "package_control.force_stop"
    const val ACTION_PACKAGE_GRANT_PERMISSION = "package_control.grant_permission"
    const val ACTION_PACKAGE_REVOKE_PERMISSION = "package_control.revoke_permission"
    const val ACTION_PACKAGE_SET_APPOPS = "package_control.set_appops"

    const val ACTION_SETTINGS_GET = "settings_control.get"
    const val ACTION_SETTINGS_PUT = "settings_control.put"

    const val ACTION_DEVICE_KEYEVENT = "device_control.keyevent"
    const val ACTION_DEVICE_EXPAND_NOTIFICATIONS = "device_control.expand_notifications"
    const val ACTION_DEVICE_EXPAND_QUICK_SETTINGS = "device_control.expand_quick_settings"
    const val ACTION_DEVICE_SET_WIFI_ENABLED = "device_control.set_wifi_enabled"
    const val ACTION_DEVICE_SET_MOBILE_DATA_ENABLED = "device_control.set_mobile_data_enabled"
    const val ACTION_DEVICE_INPUT_TEXT = "device_control.input_text"

    const val ACTION_DIAGNOSTICS_GETPROP = "diagnostics.getprop"
    const val ACTION_DIAGNOSTICS_DUMPSYS = "diagnostics.dumpsys"
    const val ACTION_DIAGNOSTICS_LIST_PACKAGES = "diagnostics.list_packages"
    const val ACTION_DIAGNOSTICS_LOGCAT_TAIL = "diagnostics.logcat_tail"

    const val ACTION_SHELL_EXEC = "shell.exec"

    internal const val ACTION_SESSION_START = "shell.session_start"
    internal const val ACTION_SESSION_EXEC = "shell.session_exec"
    internal const val ACTION_SESSION_READ = "shell.session_read"
    internal const val ACTION_SESSION_STOP = "shell.session_stop"

    private val adbVisibleActions = linkedSetOf(
        ACTION_PACKAGE_LAUNCH,
        ACTION_PACKAGE_FORCE_STOP,
        ACTION_PACKAGE_GRANT_PERMISSION,
        ACTION_PACKAGE_REVOKE_PERMISSION,
        ACTION_PACKAGE_SET_APPOPS,
        ACTION_SETTINGS_GET,
        ACTION_SETTINGS_PUT,
        ACTION_DEVICE_KEYEVENT,
        ACTION_DEVICE_EXPAND_NOTIFICATIONS,
        ACTION_DEVICE_EXPAND_QUICK_SETTINGS,
        ACTION_DEVICE_SET_WIFI_ENABLED,
        ACTION_DIAGNOSTICS_GETPROP,
        ACTION_DIAGNOSTICS_DUMPSYS,
        ACTION_DIAGNOSTICS_LIST_PACKAGES,
        ACTION_DIAGNOSTICS_LOGCAT_TAIL,
        ACTION_SHELL_EXEC
    )

    private val rootVisibleActions = linkedSetOf(
        *adbVisibleActions.toTypedArray(),
        ACTION_DEVICE_SET_MOBILE_DATA_ENABLED
    )

    private val internalOnlyActions = linkedSetOf(
        ACTION_DEVICE_INPUT_TEXT,
        ACTION_SESSION_START,
        ACTION_SESSION_EXEC,
        ACTION_SESSION_READ,
        ACTION_SESSION_STOP
    )

    private val commandBlockRules = listOf(
        CommandBlockRule(
            Regex("""(^|[;&|()\s])(reboot|shutdown)(?=($|[;&|()\s]))""", RegexOption.IGNORE_CASE),
            "Power-off and reboot commands are blocked."
        ),
        CommandBlockRule(
            Regex("""(^|[;&|()\s])(svc|cmd)\s+power(?=($|[;&|()\s]))""", RegexOption.IGNORE_CASE),
            "Direct power-management commands are blocked."
        ),
        CommandBlockRule(
            Regex("""(^|[;&|()\s])recovery\s+--wipe_[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE),
            "Recovery wipe commands are blocked."
        ),
        CommandBlockRule(
            Regex("""(^|[;&|()\s])sm\s+partition(?=($|[;&|()\s]))""", RegexOption.IGNORE_CASE),
            "Storage repartition commands are blocked."
        ),
        CommandBlockRule(
            Regex("""(^|[;&|()\s])fastboot(?=($|[;&|()\s]))""", RegexOption.IGNORE_CASE),
            "Fastboot commands are blocked."
        ),
        CommandBlockRule(
            Regex("""/dev/block/""", RegexOption.IGNORE_CASE),
            "Direct block-device writes are blocked."
        ),
        CommandBlockRule(
            Regex("""(^|[;&|()\s])(mkfs\S*|newfs\S*)(?=($|[;&|()\s]))""", RegexOption.IGNORE_CASE),
            "Filesystem formatting commands are blocked."
        ),
        CommandBlockRule(
            Regex(
                """(^|[;&|()\s])(mount|toybox\s+mount|busybox\s+mount)\b[^\n]*\b(remount|rw)\b[^\n]*\s/(system|vendor|product|system_ext)(/|\b)""",
                RegexOption.IGNORE_CASE
            ),
            "Remounting protected system partitions is blocked."
        ),
        CommandBlockRule(
            Regex("""(?:>|>>)\s*/(system|vendor|product|system_ext)(/|\b)""", RegexOption.IGNORE_CASE),
            "Direct writes to protected system partitions are blocked."
        ),
        CommandBlockRule(
            Regex(
                """\b(cp|mv|rm|touch|install|dd|tee)\b[^\n]*\s/(system|vendor|product|system_ext)(/|\b)""",
                RegexOption.IGNORE_CASE
            ),
            "Direct writes to protected system partitions are blocked."
        )
    )

    fun normalizeAction(raw: String): String = raw.trim().lowercase()

    fun visibleAgentActions(backend: ShizukuBackend): List<String> {
        return when (backend) {
            ShizukuBackend.ROOT -> rootVisibleActions.toList()
            ShizukuBackend.ADB,
            ShizukuBackend.NONE -> adbVisibleActions.toList()
        }
    }

    fun supportedActions(
        backend: ShizukuBackend,
        includeInternal: Boolean = false,
    ): Set<String> {
        val actions = linkedSetOf<String>()
        actions += visibleAgentActions(backend)
        if (includeInternal) {
            actions += internalOnlyActions
        }
        return actions
    }

    fun isSupported(
        action: String,
        backend: ShizukuBackend,
        includeInternal: Boolean = false,
        arguments: Map<String, String> = emptyMap(),
    ): Boolean {
        val normalizedAction = normalizeAction(action)
        if (!supportedActions(backend, includeInternal).contains(normalizedAction)) {
            return false
        }
        if (requiresRoot(normalizedAction, arguments) && backend != ShizukuBackend.ROOT) {
            return false
        }
        return true
    }

    fun requiresConfirmation(action: String): Boolean {
        val normalizedAction = normalizeAction(action)
        // The privileged service cannot currently verify an app-issued, one-time local approval.
        // Keep every Shizuku action fail-closed, including diagnostic reads: settings, logcat,
        // dumpsys, properties and installed-package data can all expose sensitive information.
        // Unknown actions are also confirmation-requiring so a newly added action cannot become
        // model-executable merely because this policy was not updated at the same time.
        return normalizedAction.isNotEmpty()
    }

    fun requiresRoot(
        action: String,
        arguments: Map<String, String> = emptyMap(),
    ): Boolean {
        val normalizedAction = normalizeAction(action)
        if (normalizedAction == ACTION_DEVICE_SET_MOBILE_DATA_ENABLED) {
            return true
        }
        if (normalizedAction == ACTION_DIAGNOSTICS_LOGCAT_TAIL) {
            val buffer = arguments["buffer"]?.trim()?.lowercase().orEmpty()
            if (buffer == "kernel") {
                return true
            }
        }
        return false
    }

    fun blockedCommandReason(command: String?): String? {
        val normalized = command?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        return commandBlockRules.firstOrNull { rule ->
            rule.regex.containsMatchIn(normalized)
        }?.reason
    }
}
