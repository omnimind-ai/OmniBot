package cn.com.omnimind.baselib.privileged

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class PrivilegedBackend { NONE, ADB, ROOT }

@Serializable
enum class PrivilegedProvider { NONE, SHIZUKU, ROOT }

@Serializable
data class PrivilegedRequest(
    val requestId: String,
    val action: String,
    val arguments: Map<String, String> = emptyMap(),
    val requiresConfirmation: Boolean = false,
    val command: String? = null,
    val timeoutSeconds: Int? = null,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val sessionId: String? = null,
    val userId: Int = 0,
)

@Serializable
data class PrivilegedResult(
    val requestId: String,
    val action: String,
    val success: Boolean,
    val code: String,
    val message: String,
    val backend: PrivilegedBackend = PrivilegedBackend.NONE,
    val output: String = "",
    val stdout: String = "",
    val stderr: String = "",
    val transcript: String = "",
    val exitCode: Int? = null,
    val requiresConfirmation: Boolean = false,
    val availableActions: List<String> = emptyList(),
    val data: Map<String, String> = emptyMap(),
    val command: String? = null,
    val timeoutSeconds: Int? = null,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val sessionId: String? = null,
    val blockedByPolicy: Boolean = false,
    val provider: PrivilegedProvider = PrivilegedProvider.NONE,
    val observation: kotlinx.serialization.json.JsonObject? = null,
) {
    fun toMap(): Map<String, Any?> {
        return linkedMapOf(
            "requestId" to requestId,
            "action" to action,
            "success" to success,
            "code" to code,
            "message" to message,
            "backend" to backend.name,
            "provider" to provider.name,
            "observation" to observation?.toNativeValue(),
            "output" to output,
            "stdout" to stdout,
            "stderr" to stderr,
            "transcript" to transcript,
            "exitCode" to exitCode,
            "requiresConfirmation" to requiresConfirmation,
            "availableActions" to availableActions,
            "data" to data,
            "command" to command,
            "timeoutSeconds" to timeoutSeconds,
            "workingDirectory" to workingDirectory,
            "environment" to environment,
            "sessionId" to sessionId,
            "blockedByPolicy" to blockedByPolicy
        )
    }
}

private fun JsonElement.toNativeValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> mapValues { it.value.toNativeValue() }
    is JsonArray -> map { it.toNativeValue() }
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
}
