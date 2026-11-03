package cn.com.omnimind.baselib.privileged

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

internal class DeviceActionException(
    val code: String,
    message: String,
    val diagnostic: PrivilegedResult? = null,
) : Exception(message)

/** 将命令受理与设备状态验证分开，未知状态不能投影为成功。 */
internal class DeviceActionExecution(
    val request: PrivilegedRequest,
    val userId: Int,
    private val command: suspend (String) -> PrivilegedResult,
    private val stillAllowed: () -> Boolean,
    private val verificationPolls: Int = 12,
    private val pollMillis: Long = 250,
) {
    suspend fun run(vararg arguments: String): PrivilegedResult {
        coroutineContext.ensureActive()
        checkAccess()
        return command(arguments.joinToString(" ", transform = PrivilegedProcessRunner::shellQuote))
    }

    fun checkAccess() {
        if (!stillAllowed()) throw DeviceActionException("privileged_access_changed", "Privileged access changed before execution.")
    }

    suspend fun supports(service: String, operation: String): Boolean {
        val result = run("cmd", service, "help")
        if (result.code !in setOf("ok", "command_failed")) {
            throw DeviceActionException(result.code, result.message, result)
        }
        val lines = (result.stdout + "\n" + result.stderr).lineSequence()
            .map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        // AOSP 的默认 help 处理在打印帮助后返回 -1（进程退出码 255）。
        // 这个约定只用于固定的只读帮助探测，不改变实际操作的成功判定。
        if (result.exitCode !in setOf(0, -1, 255) || lines.any { HELP_ERROR.containsMatchIn(it) }) {
            throw DeviceActionException("capability_probe_failed", "Could not inspect $service/$operation support.", result)
        }
        val declaration = Regex("^" + Regex.escape(operation) + "(?=[ \\t\\[<]|$)")
        val commandIndent = lines.filter { it.first().isWhitespace() && !HELP_HEADING.matches(it.trim()) }
            .minOfOrNull { it.takeWhile { character -> character == ' ' || character == '\t' }.length }
        val declared = lines.any { line ->
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length
            (indent == 0 || indent == commandIndent) && declaration.containsMatchIn(line.trimStart())
        }
        if (declared) return true
        if (result.data["truncated"] == "true") {
            throw DeviceActionException("capability_probe_incomplete", "The $service help output is incomplete; support for $operation is unknown.", result)
        }
        if (lines.none { HELP_HEADING.matches(it.trim()) || HELP_DECLARATION.matches(it.trim()) }) {
            throw DeviceActionException("capability_probe_failed", "The $service service did not return recognizable command help.", result)
        }
        return false
    }

    suspend fun requireCommand(service: String, operation: String) {
        if (!supports(service, operation)) throw DeviceActionException("unsupported_action", "This system does not provide $service/$operation.")
    }

    fun query(value: JsonElement?, scope: String = "device"): PrivilegedResult = if (value == null || value == JsonNull) {
        failure("state_unavailable", "The current device state could not be read.")
    } else result(true, "ok", "Device state read successfully.", buildJsonObject {
        put("userId", userId); put("scope", scope); put("value", value)
    })

    suspend fun mutate(
        expected: JsonElement,
        scope: String = "device",
        read: suspend () -> JsonElement?,
        matches: (JsonElement?) -> Boolean = { it == expected },
        write: suspend () -> PrivilegedResult,
    ): PrivilegedResult {
        val before = read()
        val execution = if (matches(before)) null else {
            coroutineContext.ensureActive()
            checkAccess()
            write()
        }
        val accepted = execution?.success ?: true
        suspend fun observe(): JsonElement? = try { read() } catch (_: DeviceActionException) { null }
        var after = if (execution == null) before else observe()
        // 系统开关通常异步生效；这里只查询，不重复发送修改命令。
        var attempts = 0
        val deadline = System.nanoTime() + verificationPolls * pollMillis * 1_000_000
        while (accepted && !matches(after) && stillAllowed() && attempts++ < verificationPolls &&
            (pollMillis == 0L || System.nanoTime() < deadline)) {
            delay(pollMillis)
            after = observe()
        }
        val verified = accepted && after != null && matches(after)
        return result(
            verified,
            if (verified) "ok" else if (accepted) "state_change_unconfirmed" else execution!!.code,
            if (verified) "Device state verified." else if (accepted) "The request was submitted, but the target state is unconfirmed. Check the state before retrying."
            else "The system did not complete the operation.",
            observation(before, after, expected, accepted, verified, userId, scope),
        ).copy(exitCode = execution?.exitCode)
    }

    fun failure(code: String, message: String) = result(false, code, message, null)

    fun failure(error: DeviceActionException): PrivilegedResult = error.diagnostic?.copy(
        requestId = request.requestId,
        action = request.action,
        success = false,
        code = error.code,
        message = error.message.orEmpty(),
    ) ?: failure(error.code, error.message.orEmpty())

    private fun result(success: Boolean, code: String, message: String, observation: JsonObject?) = PrivilegedResult(
        request.requestId, request.action, success, code, message, observation = observation,
    )

    companion object {
        private val HELP_HEADING = Regex("(?i)(?:usage:.*|.*\\bcommands:.*|subcmds?:.*)")
        private val HELP_DECLARATION = Regex("help(?:[ \\t]+.*)?")
        private val HELP_ERROR = Regex(
            "(?i)^\\s*(?:Exception occurred while executing\\b|(?:java\\.[\\w.]+\\.)?(?:SecurityException|RemoteException)\\b|(?:cmd:\\s*)?Permission (?:denied|denial)\\b)"
        )

        fun observation(before: JsonElement?, after: JsonElement?, expected: JsonElement, accepted: Boolean,
            verified: Boolean, userId: Int, scope: String): JsonObject = buildJsonObject {
            put("userId", userId); put("scope", scope)
            put("before", before ?: JsonNull); put("after", after ?: JsonNull); put("expected", expected)
            put("commandAccepted", accepted); put("verified", verified)
            put("changed", if (before == null || after == null) JsonNull else JsonPrimitive(before != after))
        }

        fun boolean(arguments: Map<String, String>, name: String): Boolean = when (arguments[name]?.lowercase()) {
            "true", "1", "yes", "on", "enable", "enabled" -> true
            "false", "0", "no", "off", "disable", "disabled" -> false
            else -> throw IllegalArgumentException("$name must be a boolean.")
        }

        fun integer(arguments: Map<String, String>, name: String, range: IntRange): Int =
            requireNotNull(arguments[name]?.toIntOrNull()?.takeIf { it in range }) { "$name must be in ${range.first}..${range.last}." }
    }
}
