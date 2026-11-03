package cn.com.omnimind.baselib.privileged

object PrivilegedActionPolicy {
    val deviceStateTargets: List<String> = listOf("wifi", "bluetooth", "mobile_data", "airplane_mode", "location", "nfc", "battery_saver", "auto_rotate", "auto_brightness", "dark_mode")

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

    const val ACTION_DEVICE_GET_STATE = "device_control.get_state"
    const val ACTION_DEVICE_SET_STATE = "device_control.set_state"
    const val ACTION_DEVICE_GET_DISPLAY = "device_control.get_display"
    const val ACTION_DEVICE_SET_BRIGHTNESS = "device_control.set_brightness"
    const val ACTION_DEVICE_SET_SCREEN_TIMEOUT = "device_control.set_screen_timeout"
    const val ACTION_DEVICE_GET_SOUND = "device_control.get_sound"
    const val ACTION_DEVICE_SET_VOLUME = "device_control.set_volume"
    const val ACTION_DEVICE_SET_DND = "device_control.set_do_not_disturb"

    const val ACTION_SHELL_EXEC = "shell.exec"

    internal const val ACTION_DEVICE_COMMAND = "device_control.command"

    internal const val ACTION_SESSION_START = "shell.session_start"
    internal const val ACTION_SESSION_EXEC = "shell.session_exec"
    internal const val ACTION_SESSION_READ = "shell.session_read"
    internal const val ACTION_SESSION_STOP = "shell.session_stop"

    private val adbVisibleActions = linkedSetOf(
        ACTION_DEVICE_GET_STATE,
        ACTION_DEVICE_SET_STATE,
        ACTION_DEVICE_GET_DISPLAY,
        ACTION_DEVICE_SET_BRIGHTNESS,
        ACTION_DEVICE_SET_SCREEN_TIMEOUT,
        ACTION_DEVICE_GET_SOUND,
        ACTION_DEVICE_SET_VOLUME,
        ACTION_DEVICE_SET_DND,
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
        ACTION_DEVICE_COMMAND,
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
            Regex(
                """(^|[;&|()\s])am\s+force-stop\s+cn\.com\.omnimind\.bot(?:\.[A-Za-z0-9._-]+)?(?=($|[;&|()\s]))""",
                RegexOption.IGNORE_CASE
            ),
            "Force-stopping the host app would disable its accessibility service."
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

    fun visibleAgentActions(backend: PrivilegedBackend): List<String> {
        return when (backend) {
            PrivilegedBackend.ROOT -> rootVisibleActions.toList()
            PrivilegedBackend.ADB,
            PrivilegedBackend.NONE -> adbVisibleActions.toList()
        }
    }

    fun supportedActions(
        backend: PrivilegedBackend,
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
        backend: PrivilegedBackend,
        includeInternal: Boolean = false,
        arguments: Map<String, String> = emptyMap(),
    ): Boolean {
        val normalizedAction = normalizeAction(action)
        if (!supportedActions(backend, includeInternal).contains(normalizedAction)) {
            return false
        }
        if (requiresRoot(normalizedAction, arguments) && backend != PrivilegedBackend.ROOT) {
            return false
        }
        return true
    }

    fun requiresConfirmation(action: String): Boolean {
        return when (normalizeAction(action)) {
            ACTION_DEVICE_SET_STATE,
            ACTION_DEVICE_SET_WIFI_ENABLED,
            ACTION_DEVICE_SET_BRIGHTNESS,
            ACTION_DEVICE_SET_SCREEN_TIMEOUT,
            ACTION_DEVICE_SET_VOLUME,
            ACTION_DEVICE_SET_DND,
            ACTION_PACKAGE_FORCE_STOP,
            ACTION_PACKAGE_GRANT_PERMISSION,
            ACTION_PACKAGE_REVOKE_PERMISSION,
            ACTION_PACKAGE_SET_APPOPS,
            ACTION_SETTINGS_PUT,
            ACTION_DEVICE_SET_MOBILE_DATA_ENABLED,
            ACTION_SHELL_EXEC,
            ACTION_SESSION_START,
            ACTION_SESSION_EXEC -> true
            else -> false
        }
    }

    fun requiresRoot(
        action: String,
        arguments: Map<String, String> = emptyMap(),
    ): Boolean {
        val normalizedAction = normalizeAction(action)
        if (normalizedAction == ACTION_DEVICE_SET_MOBILE_DATA_ENABLED ||
            (normalizedAction == ACTION_DEVICE_SET_STATE && arguments["target"] == "mobile_data")) {
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

    fun isHostPackage(packageName: String?): Boolean {
        val normalized = packageName?.trim().orEmpty()
        return normalized == "cn.com.omnimind.bot" ||
            normalized.startsWith("cn.com.omnimind.bot.")
    }
}
