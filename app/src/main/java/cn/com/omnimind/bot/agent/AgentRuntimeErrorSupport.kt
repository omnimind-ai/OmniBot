package cn.com.omnimind.bot.agent

import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts known provider and transport failures into user-facing messages
 * at the existing ACP boundary without changing retry or task ownership.
 */
internal object AgentRuntimeErrorSupport {
    const val PROVIDER_TLS_CERTIFICATE_FAILURE = "provider_tls_certificate_failure"
    const val PROVIDER_NOT_BOUND = "provider_not_bound"
    const val PROVIDER_AUTHENTICATION_FAILED = "provider_authentication_failed"
    const val PROVIDER_QUOTA_EXCEEDED = "provider_quota_exceeded"
    const val PROVIDER_RATE_LIMITED = "provider_rate_limited"
    const val PROVIDER_REQUEST_LIMITED = "provider_request_limited"
    const val PROVIDER_SERVICE_UNAVAILABLE = "provider_service_unavailable"
    const val PROVIDER_REQUEST_REJECTED = "provider_request_rejected"
    const val PROVIDER_UNAVAILABLE = "provider_unavailable"
    const val PROVIDER_MODEL_UNAVAILABLE = "provider_model_unavailable"
    const val PROVIDER_STREAM_INTERRUPTED = "provider_stream_interrupted"
    const val PROVIDER_STREAM_IDLE_TIMEOUT = "provider_stream_idle_timeout"
    const val PROVIDER_REQUEST_TIMEOUT = "provider_request_timeout"
    const val PROVIDER_TOOL_CALL_INCOMPLETE = "provider_tool_call_incomplete"
    const val HARNESS_PREPARATION_IN_PROGRESS = "harness_preparation_in_progress"
    const val HARNESS_PROFILE_MISSING = "harness_profile_missing"

    private const val CERTIFICATE_ERROR_MESSAGE =
        "Provider 的 HTTPS 证书校验失败。请先在系统设置中开启自动日期和时间并确认当前时间正确；" +
            "如果时间正确，请检查 Provider 的证书链。应用不会关闭证书校验。"

    fun userFacingMessage(error: Throwable): String? {
        return when (failureKind(error)) {
            PROVIDER_QUOTA_EXCEEDED ->
                "模型服务商额度不足，请检查账户余额或配额后再试。"
            PROVIDER_RATE_LIMITED ->
                "模型服务商限制了请求频率，请稍后再试。"
            PROVIDER_REQUEST_LIMITED ->
                "模型服务商限制了本次请求，请检查额度和请求频率后再试。"
            PROVIDER_SERVICE_UNAVAILABLE -> "模型服务商暂时不可用，请稍后再试或更换模型连接。"
            PROVIDER_REQUEST_REJECTED -> "模型服务商拒绝了本次请求，请检查模型及请求配置。"
            PROVIDER_TLS_CERTIFICATE_FAILURE -> CERTIFICATE_ERROR_MESSAGE
            PROVIDER_AUTHENTICATION_FAILED ->
                "服务商身份验证失败，请检查所选 Provider 的密钥和认证请求头。"
            PROVIDER_NOT_BOUND ->
                "Agent Provider / 模型还没有对齐到 Dispatch Model（scene.dispatch.model）。" +
                    "Harness 安装不依赖这个绑定；请检查默认 Provider 和模型后重试。"
            PROVIDER_UNAVAILABLE ->
                "统一 Agent Provider 不可用或凭据不完整。请检查 Provider 配置后重试。"
            PROVIDER_MODEL_UNAVAILABLE ->
                "服务商拒绝了当前模型。本轮已停止，请重新选择可用模型后重试。"
            PROVIDER_STREAM_IDLE_TIMEOUT ->
                "Provider 连续一段时间没有返回新的流式更新。请检查接口地址、模型和网络后重试。"
            PROVIDER_REQUEST_TIMEOUT ->
                "服务商请求超时，未能及时收到响应。请稍后重试；这不代表密钥错误或模型列表为空。"
            PROVIDER_STREAM_INTERRUPTED ->
                "模型响应期间连接中断。本轮已停止，请检查网络后重试；未完成的工具调用不会执行。"
            PROVIDER_TOOL_CALL_INCOMPLETE ->
                "Provider 返回了不完整的工具调用，响应缺少工具名称；" +
                    "请重试本轮。若持续出现，请检查 Provider 是否完整转发 tool_calls/function.name。"
            HARNESS_PREPARATION_IN_PROGRESS ->
                "另一个 Harness 正在安装或准备中。当前切换不会等待它；请稍后重试，" +
                    "或者先切换到已经安装完成的 Harness。"
            HARNESS_PROFILE_MISSING ->
                "当前 Harness 的官方 ACP profile 尚未安装。请在 Agent 设置中点击“安装/准备 Harness”，" +
                    "完成后再重试；应用不会用私有脚本替代 Harness 自己的插件工作流。"
            else -> null
        }
    }

    fun failureKind(error: Throwable): String? {
        providerResponseFailureKind(error)?.let { return it }
        return when {
            isCertificateValidationFailure(error) -> PROVIDER_TLS_CERTIFICATE_FAILURE
            isAuthenticationFailure(error) -> PROVIDER_AUTHENTICATION_FAILED
            isProviderNotBound(error) -> PROVIDER_NOT_BOUND
            isProviderModelUnavailable(error) -> PROVIDER_MODEL_UNAVAILABLE
            isStreamIdleTimeout(error) -> PROVIDER_STREAM_IDLE_TIMEOUT
            isRequestTimeout(error) -> PROVIDER_REQUEST_TIMEOUT
            isProviderUnavailable(error) -> PROVIDER_UNAVAILABLE
            isStreamInterrupted(error) -> PROVIDER_STREAM_INTERRUPTED
            isIncompleteToolCall(error) -> PROVIDER_TOOL_CALL_INCOMPLETE
            isHarnessPreparationInProgress(error) -> HARNESS_PREPARATION_IN_PROGRESS
            isHarnessProfileMissing(error) -> HARNESS_PROFILE_MISSING
            else -> null
        }
    }

    // Preserve classification in the official JSON-RPC error, before the SDK
    // serializes away Kotlin exception types. This is diagnostic data, not a turn state.
    fun acpPromptFailure(message: String, cause: Throwable?): JsonRpcException {
        val kind = cause?.let(::failureKind)
        return JsonRpcException(-32603, message,
            kind?.let { JsonObject(mapOf("failureKind" to JsonPrimitive(it))) })
    }

    private val knownFailureKinds = setOf(
        PROVIDER_QUOTA_EXCEEDED, PROVIDER_RATE_LIMITED, PROVIDER_REQUEST_LIMITED,
        PROVIDER_AUTHENTICATION_FAILED, PROVIDER_SERVICE_UNAVAILABLE, PROVIDER_REQUEST_REJECTED,
        PROVIDER_MODEL_UNAVAILABLE, PROVIDER_TLS_CERTIFICATE_FAILURE, PROVIDER_NOT_BOUND,
        PROVIDER_UNAVAILABLE, PROVIDER_STREAM_INTERRUPTED, PROVIDER_STREAM_IDLE_TIMEOUT,
        PROVIDER_REQUEST_TIMEOUT, PROVIDER_TOOL_CALL_INCOMPLETE,
        HARNESS_PREPARATION_IN_PROGRESS, HARNESS_PROFILE_MISSING,
    )

    private fun providerResponseFailureKind(error: Throwable): String? {
        for (cause in generateSequence(error) { it.cause }) {
            val status: Int?
            val code: String?
            when (cause) {
                is JsonRpcException -> {
                    val kind = ((cause.data as? JsonObject)?.get("failureKind") as? JsonPrimitive)?.content
                    if (kind in knownFailureKinds) return kind
                    continue
                }
                is AgentProviderStreamException -> {
                    status = cause.statusCode
                    code = cause.providerCode
                }
                is AgentStreamRequestException -> {
                    status = cause.statusCode
                    code = runCatching {
                        val root = Json.parseToJsonElement(cause.responseBody.orEmpty()) as? JsonObject
                        val detail = root?.get("error") as? JsonObject
                        (detail?.get("code") as? JsonPrimitive)?.content
                    }.getOrNull()
                }
                else -> continue
            }
            when (code?.lowercase()) {
                "insufficient_quota", "quota_exceeded", "quota_exhausted" -> return PROVIDER_QUOTA_EXCEEDED
                "rate_limit_exceeded", "rate_limit_error" -> return PROVIDER_RATE_LIMITED
                "authentication_error", "invalid_api_key" -> return PROVIDER_AUTHENTICATION_FAILED
                "model_not_found" -> return PROVIDER_MODEL_UNAVAILABLE
            }
            when (status) {
                401 -> return PROVIDER_AUTHENTICATION_FAILED
                429 -> return PROVIDER_REQUEST_LIMITED
                400, 403, 422 -> return PROVIDER_REQUEST_REJECTED
                in 500..599 -> return PROVIDER_SERVICE_UNAVAILABLE
            }
        }
        return null
    }

    private fun isRequestTimeout(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is java.net.SocketTimeoutException }

    /** Preserve the complete provider diagnostic while removing credentials. */
    fun safeDiagnosticMessage(error: Throwable): String {
        val raw = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString("; ")
            .ifBlank { error.javaClass.simpleName }
        val redacted = raw
            .replace(
                Regex("Bearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE),
                "Bearer ***"
            )
            .replace(
                Regex(
                    "(api[_-]?key|token|authorization)\\s*[:=]\\s*[^,;\\s]+",
                    RegexOption.IGNORE_CASE
                ),
                "\\$1=***"
            )
        return redacted
    }

    private fun isAuthenticationFailure(error: Throwable): Boolean =
        errorMessages(error).any {
            it.contains("authenticationerror") || it.contains("authentication_error") ||
                it.contains("invalid api key") || it.contains("incorrect api key") ||
                it.contains("身份验证失败") ||
                Regex("(?:failed|失败)\\s*\\(401\\)").containsMatchIn(it)
        }

    private fun isProviderNotBound(error: Throwable): Boolean =
        errorMessages(error).any {
            it.contains("not bound to scene.dispatch.model") ||
                it.contains("provider is not bound") ||
                it.contains("scene.dispatch.model") &&
                it.contains("no verified provider/model binding")
        }

    private fun isProviderModelUnavailable(error: Throwable): Boolean =
        errorMessages(error).any {
            (it.contains("bound agent model") &&
                (it.contains("not available") || it.contains("no model"))) ||
                (it.contains("chat completion stream request failed(404)") &&
                    (it.contains("invalid model") || it.contains("model_not_found")))
        }

    private fun isProviderUnavailable(error: Throwable): Boolean =
        errorMessages(error).any {
            it.contains("provider") &&
                (it.contains("unavailable") ||
                    it.contains("no usable credentials") ||
                    it.contains("not configured"))
        }

    private fun isStreamInterrupted(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { candidate ->
            candidate is AgentStreamRequestException &&
                (candidate.statusCode == null || candidate.statusCode in 200..299) &&
                listOf("software caused connection abort", "connection reset", "stream was reset", "unexpected end of stream", "broken pipe")
                    .any { candidate.reason.contains(it, ignoreCase = true) }
        }

    private fun isStreamIdleTimeout(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it.message.orEmpty().contains(
                "chat completion stream idle timeout",
                ignoreCase = true,
            )
        }

    private fun errorMessages(error: Throwable): Sequence<String> =
        generateSequence(error) { it.cause }
            .map { it.message.orEmpty().lowercase() }

    private fun isIncompleteToolCall(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { candidate ->
            candidate is AgentIncompleteToolCallException ||
                Regex("tool_call\\[\\d+] missing function\\.name", RegexOption.IGNORE_CASE)
                    .containsMatchIn(candidate.message.orEmpty())
        }

    private fun isHarnessPreparationInProgress(error: Throwable): Boolean =
        errorMessages(error).any {
            it.contains("harness preparation is already running") ||
                it.contains("harness preparation in progress")
        }

    private fun isHarnessProfileMissing(error: Throwable): Boolean =
        errorMessages(error).any {
            it.contains("profile \"acp\" does not exist") ||
                it.contains("profile 'acp' does not exist") ||
                it.contains("create it with 'dsh plugin --profile acp add")
        }

    private fun isCertificateValidationFailure(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { candidate ->
            val className = candidate.javaClass.name.lowercase()
            val message = candidate.message.orEmpty().lowercase()
            className.contains("sslhandshakeexception") ||
                className.contains("certpathvalidatorexception") ||
                message.contains("trust anchor") ||
                message.contains("unable to find valid certification path") ||
                message.contains("certpath") ||
                message.contains("certificate chain") ||
                message.contains("chain validation failed") ||
                message.contains("pkix")
        }
    }
}
