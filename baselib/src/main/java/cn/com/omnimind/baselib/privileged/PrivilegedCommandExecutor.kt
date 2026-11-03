package cn.com.omnimind.baselib.privileged

import cn.com.omnimind.baselib.util.OmniLog
import java.util.concurrent.ConcurrentHashMap

internal class PrivilegedCommandExecutor(
    private val backend: PrivilegedBackend,
    private val provider: PrivilegedProvider,
    private val runner: PrivilegedProcessRunner,
) {
    private val sessions = PrivilegedShellSessionManager(runner)
    private val requests = ConcurrentHashMap<String, Thread>()
    private val requestLock = Any()
    private val cancelled = linkedSetOf<String>()

    private val TAG = "PrivilegedCommandExec"
    private val OUTPUT_LIMIT = 16_000
    private val DEFAULT_TYPED_TIMEOUT_SECONDS = 8L
    private val DEFAULT_SHELL_TIMEOUT_SECONDS = 60L

    private val allowedSettingsNamespaces = setOf("system", "secure", "global")
    private val allowedDumpsysServices = setOf(
        "activity",
        "alarm",
        "battery",
        "connectivity",
        "deviceidle",
        "input",
        "input_method",
        "notification",
        "package",
        "power",
        "usagestats",
        "wifi",
        "window"
    )
    private val allowedLogcatBuffers = setOf("main", "system", "crash", "events", "radio", "kernel")
    private val allowedAppOpsModes = setOf("allow", "ignore", "deny", "default", "foreground")
    private val envKeyPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun execute(request: PrivilegedRequest, admission: () -> Boolean = { true }): PrivilegedResult {
        require(request.userId >= 0) { "Invalid Android user." }
        synchronized(requestLock) {
            if (cancelled.remove(request.requestId)) {
                return failure(request, backend, "cancelled", "The request was cancelled before execution.")
            }
            check(requests.putIfAbsent(request.requestId, Thread.currentThread()) == null) { "request_already_running" }
        }
        return try {
            executeRequest(request, admission).copy(provider = provider)
        } finally {
            synchronized(requestLock) { requests.remove(request.requestId) }
        }
    }

    fun cancel(requestId: String) {
        synchronized(requestLock) {
            val active = requests[requestId]
            if (active != null) active.interrupt() else {
                cancelled += requestId
                while (cancelled.size > 256) cancelled.remove(cancelled.first())
            }
        }
        runner.cancel(requestId)
    }

    private fun executeRequest(request: PrivilegedRequest, admission: () -> Boolean): PrivilegedResult {
        if (!admission()) return failure(request, backend, "privileged_access_changed", "Privileged access changed before execution.")
        if (backend == PrivilegedBackend.NONE) return failure(request, backend, "permission_denied", "No privileged process identity.")
        val action = PrivilegedActionPolicy.normalizeAction(request.action)
        val arguments = request.arguments

        if (!PrivilegedActionPolicy.isSupported(action, backend, includeInternal = true, arguments = arguments)) {
            return failure(
                request = request,
                backend = backend,
                code = "unsupported_action",
                message = "Action is not available for the selected privileged backend."
            ).also { audit(request, it) }
        }

        if (action == PrivilegedActionPolicy.ACTION_SHELL_EXEC ||
            action == PrivilegedActionPolicy.ACTION_SESSION_EXEC
        ) {
            PrivilegedActionPolicy.blockedCommandReason(request.command)?.let { reason ->
                return failure(
                    request = request,
                    backend = backend,
                    code = "blocked_by_policy",
                    message = reason,
                    blockedByPolicy = true
                ).also { audit(request, it) }
            }
        }

        if ((request.requiresConfirmation || PrivilegedActionPolicy.requiresConfirmation(action)) &&
            !isExplicitlyConfirmed(arguments)
        ) {
            return failure(
                request = request,
                backend = backend,
                code = "confirmation_required",
                message = "This privileged action requires explicit confirmation.",
                requiresConfirmation = true
            ).also { audit(request, it) }
        }

        val result = when (action) {
            PrivilegedActionPolicy.ACTION_SHELL_EXEC -> executeRawShell(request, backend, admission)
            PrivilegedActionPolicy.ACTION_SESSION_START ->
                sessions.startSession(request, backend, admission)

            PrivilegedActionPolicy.ACTION_SESSION_EXEC ->
                sessions.execSession(request, backend, admission)

            PrivilegedActionPolicy.ACTION_SESSION_READ ->
                sessions.readSession(request, backend)

            PrivilegedActionPolicy.ACTION_SESSION_STOP ->
                sessions.stopSession(request, backend)

            PrivilegedActionPolicy.ACTION_DEVICE_COMMAND -> executeRawShell(request, backend, admission)
            else -> executeTypedAction(request, backend, action, arguments, admission)
        }
        audit(request, result)
        return result
    }

    fun shutdown() {
        runner.cancelAll()
        sessions.shutdown()
    }

    private fun executeTypedAction(
        request: PrivilegedRequest,
        backend: PrivilegedBackend,
        action: String,
        arguments: Map<String, String>,
        admission: () -> Boolean,
    ): PrivilegedResult {
        val command = runCatching {
            buildCommand(action, arguments, backend, request.userId)
        }.getOrElse { error ->
            return failure(
                request = request,
                backend = backend,
                code = "invalid_arguments",
                message = error.message ?: "Invalid privileged action arguments."
            )
        }

        val result = runner.execute(
            requestId = request.requestId,
            requestAdmission = admission,
            command = command,
            timeoutSeconds = DEFAULT_TYPED_TIMEOUT_SECONDS
        )
        val stdout = trimOutput(result.stdout)
        val stderr = trimOutput(result.stderr)
        val response = if (result.success) {
            val data = when (action) {
                PrivilegedActionPolicy.ACTION_SETTINGS_GET -> mapOf("value" to stdout.trim())
                PrivilegedActionPolicy.ACTION_DIAGNOSTICS_GETPROP -> mapOf("value" to stdout.trim())
                PrivilegedActionPolicy.ACTION_DIAGNOSTICS_LIST_PACKAGES -> {
                    val filter = arguments["filter"]?.trim().orEmpty()
                    val packages = stdout
                        .lineSequence()
                        .map { it.removePrefix("package:").trim() }
                        .filter { it.isNotEmpty() }
                        .filter { filter.isBlank() || it.contains(filter, ignoreCase = true) }
                        .joinToString("\n")
                    mapOf("packages" to packages)
                }
                else -> emptyMap()
            }
            PrivilegedResult(
                requestId = request.requestId,
                action = action,
                success = true,
                code = "ok",
                message = "Privileged action executed successfully.",
                backend = backend,
                output = combineOutput(stdout, stderr),
                stdout = stdout,
                stderr = stderr,
                exitCode = result.exitCode,
                availableActions = PrivilegedActionPolicy.visibleAgentActions(backend),
                data = data,
                command = command.joinToString(" "),
                timeoutSeconds = DEFAULT_TYPED_TIMEOUT_SECONDS.toInt()
            )
        } else {
            failure(
                request = request,
                backend = backend,
                code = result.errorCode ?: if (result.timedOut) "timeout" else "command_failed",
                message = if (result.timedOut) {
                    "Privileged action timed out."
                } else {
                    "Privileged action failed."
                },
                stdout = stdout,
                stderr = stderr,
                exitCode = result.exitCode,
                command = command.joinToString(" "),
                timeoutSeconds = DEFAULT_TYPED_TIMEOUT_SECONDS.toInt()
            )
        }
        return response.withOutputMetadata(result)
    }

    private fun executeRawShell(
        request: PrivilegedRequest,
        backend: PrivilegedBackend,
        admission: () -> Boolean,
    ): PrivilegedResult {
        val command = request.command?.trim().orEmpty()
        if (command.isEmpty()) {
            return failure(
                request = request,
                backend = backend,
                code = "invalid_arguments",
                message = "command is required for shell.exec."
            )
        }
        val timeoutSeconds = request.timeoutSeconds?.coerceIn(5, 600)?.toLong()
            ?: DEFAULT_SHELL_TIMEOUT_SECONDS
        val script = runCatching {
            buildShellScript(
                command = command,
                workingDirectory = request.workingDirectory,
                environment = request.environment
            )
        }.getOrElse { error ->
            return failure(
                request = request,
                backend = backend,
                code = "invalid_arguments",
                message = error.message ?: "Invalid shell.exec arguments."
            )
        }

        val result = runner.execute(
            requestId = request.requestId,
            requestAdmission = admission,
            command = listOf("/system/bin/sh", "-lc", script),
            timeoutSeconds = timeoutSeconds
        )
        val stdout = trimOutput(result.stdout)
        val stderr = trimOutput(result.stderr)
        val response = if (result.success) {
            PrivilegedResult(
                requestId = request.requestId,
                action = request.action,
                success = true,
                code = "ok",
                message = "Privileged shell command executed successfully.",
                backend = backend,
                output = combineOutput(stdout, stderr),
                stdout = stdout,
                stderr = stderr,
                exitCode = result.exitCode,
                availableActions = PrivilegedActionPolicy.visibleAgentActions(backend),
                command = command,
                timeoutSeconds = timeoutSeconds.toInt(),
                workingDirectory = request.workingDirectory,
                environment = request.environment
            )
        } else {
            failure(
                request = request,
                backend = backend,
                code = result.errorCode ?: if (result.timedOut) "timeout" else "command_failed",
                message = if (result.timedOut) {
                    "Privileged shell command timed out."
                } else {
                    "Privileged shell command failed."
                },
                stdout = stdout,
                stderr = stderr,
                exitCode = result.exitCode,
                command = command,
                timeoutSeconds = timeoutSeconds.toInt()
            )
        }
        return response.withOutputMetadata(result)
    }

    private fun PrivilegedResult.withOutputMetadata(result: PrivilegedProcessResult): PrivilegedResult = copy(
        data = data + ("truncated" to
            (result.truncated || result.stdout.length > OUTPUT_LIMIT || result.stderr.length > OUTPUT_LIMIT).toString()),
    )

    private fun buildCommand(
        action: String,
        arguments: Map<String, String>,
        backend: PrivilegedBackend,
        userId: Int,
    ): List<String> {
        return when (action) {
            PrivilegedActionPolicy.ACTION_PACKAGE_LAUNCH -> {
                val packageName = requirePackageName(arguments["packageName"])
                val activityName = arguments["activityName"]?.trim().orEmpty()
                if (activityName.isNotEmpty()) {
                    requireSafeComponent(activityName)
                    listOf("am", "start", "--user", userId.toString(), "-n", "$packageName/$activityName")
                } else {
                    listOf(
                        "am", "start", "--user", userId.toString(),
                        "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LAUNCHER", "-p", packageName
                    )
                }
            }
            PrivilegedActionPolicy.ACTION_PACKAGE_FORCE_STOP -> {
                val packageName = requirePackageName(arguments["packageName"])
                require(!PrivilegedActionPolicy.isHostPackage(packageName)) {
                    "Force-stopping the host app is blocked."
                }
                listOf("am", "force-stop", "--user", userId.toString(), packageName)
            }
            PrivilegedActionPolicy.ACTION_PACKAGE_GRANT_PERMISSION -> {
                listOf(
                    "pm",
                    "grant", "--user", userId.toString(),
                    requirePackageName(arguments["packageName"]),
                    requirePermissionName(arguments["permission"])
                )
            }
            PrivilegedActionPolicy.ACTION_PACKAGE_REVOKE_PERMISSION -> {
                listOf(
                    "pm",
                    "revoke", "--user", userId.toString(),
                    requirePackageName(arguments["packageName"]),
                    requirePermissionName(arguments["permission"])
                )
            }
            PrivilegedActionPolicy.ACTION_PACKAGE_SET_APPOPS -> {
                val mode = arguments["mode"]?.trim()?.lowercase().orEmpty()
                require(allowedAppOpsModes.contains(mode)) { "Unsupported appops mode." }
                listOf(
                    "appops",
                    "set", "--user", userId.toString(),
                    requirePackageName(arguments["packageName"]),
                    requireSimpleToken(arguments["op"], "op"),
                    mode
                )
            }
            PrivilegedActionPolicy.ACTION_SETTINGS_GET -> {
                listOf(
                    "settings", "--user", userId.toString(),
                    "get",
                    requireSettingsNamespace(arguments["namespace"]),
                    requireSimpleToken(arguments["key"], "key")
                )
            }
            PrivilegedActionPolicy.ACTION_SETTINGS_PUT -> {
                listOf(
                    "settings", "--user", userId.toString(),
                    "put",
                    requireSettingsNamespace(arguments["namespace"]),
                    requireSimpleToken(arguments["key"], "key"),
                    requireNotBlank(arguments["value"], "value")
                )
            }
            PrivilegedActionPolicy.ACTION_DEVICE_KEYEVENT -> {
                listOf("input", "keyevent", requireKeyEvent(arguments["key"]))
            }
            PrivilegedActionPolicy.ACTION_DEVICE_EXPAND_NOTIFICATIONS -> {
                listOf("cmd", "statusbar", "expand-notifications")
            }
            PrivilegedActionPolicy.ACTION_DEVICE_EXPAND_QUICK_SETTINGS -> {
                listOf("cmd", "statusbar", "expand-settings")
            }
            PrivilegedActionPolicy.ACTION_DEVICE_SET_WIFI_ENABLED -> {
                listOf("svc", "wifi", if (isEnabled(arguments)) "enable" else "disable")
            }
            PrivilegedActionPolicy.ACTION_DEVICE_SET_MOBILE_DATA_ENABLED -> {
                require(backend == PrivilegedBackend.ROOT) { "Mobile data control requires root backend." }
                listOf("svc", "data", if (isEnabled(arguments)) "enable" else "disable")
            }
            PrivilegedActionPolicy.ACTION_DEVICE_INPUT_TEXT -> {
                listOf("input", "text", encodeInputText(requireNotBlank(arguments["text"], "text")))
            }
            PrivilegedActionPolicy.ACTION_DIAGNOSTICS_GETPROP -> {
                val name = arguments["name"]?.trim()
                    ?.ifEmpty { null }
                    ?: arguments["prop"]?.trim().orEmpty()
                if (name.isBlank()) {
                    listOf("getprop")
                } else {
                    listOf("getprop", requireSimpleToken(name, "name"))
                }
            }
            PrivilegedActionPolicy.ACTION_DIAGNOSTICS_DUMPSYS -> {
                val service = arguments["service"]?.trim()?.lowercase().orEmpty()
                require(allowedDumpsysServices.contains(service)) { "Unsupported dumpsys service." }
                listOf("dumpsys", service)
            }
            PrivilegedActionPolicy.ACTION_DIAGNOSTICS_LIST_PACKAGES -> {
                listOf("pm", "list", "packages", "--user", userId.toString())
            }
            PrivilegedActionPolicy.ACTION_DIAGNOSTICS_LOGCAT_TAIL -> {
                val buffer = arguments["buffer"]?.trim()?.lowercase()?.ifEmpty { "main" } ?: "main"
                require(allowedLogcatBuffers.contains(buffer)) { "Unsupported logcat buffer." }
                if (buffer == "kernel") {
                    require(backend == PrivilegedBackend.ROOT) { "Kernel logcat requires root backend." }
                }
                val lines = arguments["lines"]?.trim()?.toIntOrNull()?.coerceIn(1, 200) ?: 80
                listOf("logcat", "-d", "-b", buffer, "-t", lines.toString())
            }
            else -> error("Unsupported action.")
        }
    }

    private fun buildShellScript(
        command: String,
        workingDirectory: String?,
        environment: Map<String, String>,
    ): String {
        val lines = mutableListOf<String>()
        workingDirectory?.trim()?.takeIf { it.isNotEmpty() }?.let { directory ->
            lines += "cd ${shellQuote(directory)} || exit"
        }
        environment.forEach { (key, value) ->
            require(envKeyPattern.matches(key)) { "Invalid environment variable name: $key" }
            lines += "export $key=${shellQuote(value)}"
        }
        lines += command
        return lines.joinToString(separator = "\n")
    }

    private fun trimOutput(value: String): String {
        val normalized = value
        if (normalized.length <= OUTPUT_LIMIT) {
            return normalized
        }
        return normalized.take(OUTPUT_LIMIT)
    }

    private fun combineOutput(stdout: String, stderr: String): String {
        return when {
            stdout.isNotBlank() && stderr.isNotBlank() -> "$stdout\n[stderr]\n$stderr"
            stdout.isNotBlank() -> stdout
            else -> stderr
        }
    }

    private fun failure(
        request: PrivilegedRequest,
        backend: PrivilegedBackend,
        code: String,
        message: String,
        output: String = "",
        stdout: String = "",
        stderr: String = "",
        transcript: String = "",
        exitCode: Int? = null,
        requiresConfirmation: Boolean = false,
        blockedByPolicy: Boolean = false,
        command: String? = request.command,
        timeoutSeconds: Int? = request.timeoutSeconds,
    ): PrivilegedResult {
        val mergedOutput = if (output.isNotBlank()) output else combineOutput(stdout, stderr)
        return PrivilegedResult(
            requestId = request.requestId,
            action = request.action,
            success = false,
            code = code,
            message = message,
            backend = backend,
            output = mergedOutput,
            stdout = stdout,
            stderr = stderr,
            transcript = transcript,
            exitCode = exitCode,
            requiresConfirmation = requiresConfirmation,
            availableActions = PrivilegedActionPolicy.visibleAgentActions(backend),
            command = command,
            timeoutSeconds = timeoutSeconds,
            workingDirectory = request.workingDirectory,
            environment = request.environment,
            sessionId = request.sessionId,
            blockedByPolicy = blockedByPolicy
        )
    }

    private fun audit(request: PrivilegedRequest, result: PrivilegedResult) {
        val summary = buildString {
            append("action=${request.action}")
            request.sessionId?.takeIf { it.isNotBlank() }?.let { append(", sessionId=$it") }
            append(", success=${result.success}")
            append(", code=${result.code}")
            result.exitCode?.let { append(", exit=$it") }
            if (result.blockedByPolicy) {
                append(", blockedByPolicy=true")
            }
        }
        OmniLog.i(TAG, "Privileged audit: $summary")
    }

    private fun requirePackageName(value: String?): String {
        return requireNotBlank(value, "packageName").also {
            require(Regex("^[A-Za-z0-9._]+$").matches(it)) { "Invalid packageName." }
        }
    }

    private fun requirePermissionName(value: String?): String {
        return requireNotBlank(value, "permission").also {
            require(Regex("^[A-Za-z0-9._]+$").matches(it)) { "Invalid permission." }
        }
    }

    private fun requireSettingsNamespace(value: String?): String {
        val namespace = value?.trim()?.lowercase().orEmpty()
        require(allowedSettingsNamespaces.contains(namespace)) { "Unsupported namespace." }
        return namespace
    }

    private fun requireSimpleToken(value: String?, fieldName: String): String {
        return requireNotBlank(value, fieldName).also {
            require(Regex("^[A-Za-z0-9_./:-]+$").matches(it)) { "Invalid $fieldName." }
        }
    }

    private fun requireKeyEvent(value: String?): String {
        return requireNotBlank(value, "key").also {
            require(Regex("^[A-Za-z0-9_]+$").matches(it)) { "Invalid key event." }
        }
    }

    private fun requireSafeComponent(value: String) {
        require(Regex("^[A-Za-z0-9_.$]+$").matches(value)) { "Invalid activityName." }
    }

    private fun requireNotBlank(value: String?, fieldName: String): String {
        val trimmed = value?.trim().orEmpty()
        require(trimmed.isNotEmpty()) { "$fieldName is required." }
        return trimmed
    }

    private fun isEnabled(arguments: Map<String, String>): Boolean {
        return arguments["enabled"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on", "enable", "enabled")
    }

    private fun isExplicitlyConfirmed(arguments: Map<String, String>): Boolean {
        return arguments["confirmed"]?.trim()?.lowercase() in setOf("1", "true", "yes", "confirm", "confirmed")
    }

    private fun encodeInputText(text: String): String {
        return text
            .replace("%", "%25")
            .replace(" ", "%s")
            .replace("\n", " ")
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }

}
