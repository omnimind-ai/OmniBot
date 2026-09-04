package cn.com.omnimind.bot.agent

/**
 * Converts transport failures into a useful message at the ACP boundary.
 * Provider traffic must continue to use the platform trust store; this
 * helper only makes certificate failures diagnosable to the user.
 */
internal object AgentRuntimeErrorSupport {
    const val PROVIDER_TLS_CERTIFICATE_FAILURE = "provider_tls_certificate_failure"
    const val PROVIDER_NOT_BOUND = "provider_not_bound"
    const val PROVIDER_UNAVAILABLE = "provider_unavailable"
    const val PROVIDER_MODEL_UNAVAILABLE = "provider_model_unavailable"
    const val PROVIDER_STREAM_IDLE_TIMEOUT = "provider_stream_idle_timeout"
    const val PROVIDER_TOOL_CALL_INCOMPLETE = "provider_tool_call_incomplete"
    const val HARNESS_PREPARATION_IN_PROGRESS = "harness_preparation_in_progress"
    const val HARNESS_PROFILE_MISSING = "harness_profile_missing"

    private const val CERTIFICATE_ERROR_MESSAGE =
        "Provider 的 HTTPS 证书校验失败。请先在系统设置中开启自动日期和时间并确认当前时间正确；" +
            "如果时间正确，请检查 Provider 的证书链。应用不会关闭证书校验。"

    fun userFacingMessage(error: Throwable): String? {
        return when {
            isCertificateValidationFailure(error) -> CERTIFICATE_ERROR_MESSAGE
            isProviderNotBound(error) ->
                "Agent Provider / 模型还没有对齐到 Dispatch Model（scene.dispatch.model）。" +
                    "Harness 安装不依赖这个绑定；请检查默认 Provider 和模型后重试。"
            isProviderUnavailable(error) ->
                "统一 Agent Provider 不可用或凭据不完整。请检查 Provider 配置后重试。"
            isProviderModelUnavailable(error) ->
                "统一 Agent 模型当前不可用。请刷新 Provider 模型列表并重新选择模型。"
            isStreamIdleTimeout(error) ->
                "Provider 连续一段时间没有返回新的流式更新。请检查接口地址、模型和网络后重试。"
            isIncompleteToolCall(error) ->
                "Provider 返回了不完整的工具调用。应用已自动重试一次，但响应仍缺少工具名称；" +
                    "请重试本轮。若持续出现，请检查 Provider 是否完整转发 tool_calls/function.name。"
            isHarnessPreparationInProgress(error) ->
                "另一个 Harness 正在安装或准备中。当前切换不会等待它；请稍后重试，" +
                    "或者先切换到已经安装完成的 Harness。"
            isHarnessProfileMissing(error) ->
                "当前 Harness 的官方 ACP profile 尚未安装。请在 Agent 设置中点击“安装/准备 Harness”，" +
                    "完成后再重试；应用不会用私有脚本替代 Harness 自己的插件工作流。"
            else -> null
        }
    }

    fun failureKind(error: Throwable): String? {
        return when {
            isCertificateValidationFailure(error) -> PROVIDER_TLS_CERTIFICATE_FAILURE
            isProviderNotBound(error) -> PROVIDER_NOT_BOUND
            isProviderModelUnavailable(error) -> PROVIDER_MODEL_UNAVAILABLE
            isStreamIdleTimeout(error) -> PROVIDER_STREAM_IDLE_TIMEOUT
            isProviderUnavailable(error) -> PROVIDER_UNAVAILABLE
            isIncompleteToolCall(error) -> PROVIDER_TOOL_CALL_INCOMPLETE
            isHarnessPreparationInProgress(error) -> HARNESS_PREPARATION_IN_PROGRESS
            isHarnessProfileMissing(error) -> HARNESS_PROFILE_MISSING
            else -> null
        }
    }

    /** Keep native diagnostics useful without returning credentials to Dart or logs. */
    fun safeDiagnosticMessage(error: Throwable, maxLength: Int = 300): String {
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
        return redacted.take(maxLength)
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
            it.contains("bound agent model") &&
                (it.contains("not available") || it.contains("no model"))
        }

    private fun isProviderUnavailable(error: Throwable): Boolean =
        errorMessages(error).any {
            it.contains("provider") &&
                (it.contains("unavailable") ||
                    it.contains("no usable credentials") ||
                    it.contains("not configured"))
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
