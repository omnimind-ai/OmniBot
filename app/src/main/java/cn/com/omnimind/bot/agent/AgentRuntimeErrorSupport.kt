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

    private const val CERTIFICATE_ERROR_MESSAGE =
        "Provider 的 HTTPS 证书校验失败。请先在系统设置中开启自动日期和时间并确认当前时间正确；" +
            "如果时间正确，请检查 Provider 的证书链。应用不会关闭证书校验。"

    fun userFacingMessage(error: Throwable): String? {
        return when {
            isCertificateValidationFailure(error) -> CERTIFICATE_ERROR_MESSAGE
            isProviderNotBound(error) ->
                "尚未绑定统一 Agent Provider / 模型。请在 Agent 设置中选择 Provider 和模型后重试。"
            isProviderUnavailable(error) ->
                "统一 Agent Provider 不可用或凭据不完整。请检查 Provider 配置后重试。"
            isProviderModelUnavailable(error) ->
                "统一 Agent 模型当前不可用。请刷新 Provider 模型列表并重新选择模型。"
            else -> null
        }
    }

    fun failureKind(error: Throwable): String? {
        return when {
            isCertificateValidationFailure(error) -> PROVIDER_TLS_CERTIFICATE_FAILURE
            isProviderNotBound(error) -> PROVIDER_NOT_BOUND
            isProviderModelUnavailable(error) -> PROVIDER_MODEL_UNAVAILABLE
            isProviderUnavailable(error) -> PROVIDER_UNAVAILABLE
            else -> null
        }
    }

    private fun isProviderNotBound(error: Throwable): Boolean =
        errorMessages(error).any {
            it.contains("not bound to scene.dispatch.model") ||
                it.contains("provider is not bound")
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

    private fun errorMessages(error: Throwable): Sequence<String> =
        generateSequence(error) { it.cause }
            .map { it.message.orEmpty().lowercase() }

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
