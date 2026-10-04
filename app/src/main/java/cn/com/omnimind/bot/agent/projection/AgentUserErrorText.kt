package cn.com.omnimind.bot.agent.projection

private val agentUserErrorsZh: Map<String, String> = linkedMapOf(
    "modelRequired" to "请先在模型设置中选择一个可用模型，再启动助手。",
    "credentials" to "模型连接验证失败，请在模型设置中检查接口地址和密钥。",
    "modelUnavailable" to "当前模型不可用，请在模型设置中重新选择。",
    "certificate" to "连接验证失败，请检查网络是否需要登录，并确认设备时间正确。",
    "interrupted" to "回复连接已中断，请检查网络后重试。未完成的工具调用不会执行。",
    "timeout" to "等待回复超时，请检查网络后重试；若仍失败，请更换模型。",
    "incompleteTool" to "模型返回了不完整的工具调用，请重试；若仍失败，请更换模型。",
    "installBusy" to "已有助手正在安装，请等待完成后再试。",
    "incompatible" to "当前模型连接不适用于这个助手，请更换模型连接或助手。",
    "unsupportedTools" to "当前模型连接不支持这个助手的工具，请更换模型连接或助手。",
    "notInstalled" to "助手尚未安装完成，请在助手设置中重新安装。",
    "quota" to "模型服务商额度不足，请检查账户余额或配额后再试。",
    "requestLimited" to "模型服务商限制了本次请求，请检查额度和请求频率后再试。",
    "rateLimit" to "模型服务商限制了请求频率，请稍后再试。",
    "serviceUnavailable" to "模型服务商暂时不可用，请稍后再试或更换模型连接。",
    "requestRejected" to "模型服务商拒绝了本次请求，请检查模型及请求配置。",
    "unknown" to "助手暂时无法完成操作，请重试。",
)

private val agentUserErrorsEn: List<String> = listOf(
    "Choose an available model in model settings, then start the assistant.",
    "Could not verify the model connection. Check its address and key in model settings.",
    "This model is unavailable. Choose another in model settings.",
    "Could not verify the secure connection. Check whether your network requires sign-in and whether the device time is correct.",
    "The reply was interrupted. Check your connection and try again. Incomplete tool calls will not run.",
    "The reply timed out. Check your connection and try again, or choose another model.",
    "The model returned an incomplete tool call. Try again, or choose another model.",
    "Another assistant is being installed. Try again when it finishes.",
    "This model connection is incompatible with the assistant. Choose another connection or assistant.",
    "This model connection does not support the assistant’s tools. Choose another connection or assistant.",
    "The assistant is not fully installed. Reinstall it in assistant settings.",
    "The model provider quota is exhausted. Check your balance or quota before retrying.",
    "The model provider limited this request. Check your quota and request rate before retrying.",
    "The model provider is rate limiting requests. Try again later.",
    "The model provider is temporarily unavailable. Try later or choose another connection.",
    "The model provider rejected this request. Check the model and request configuration.",
    "The assistant could not complete this action. Please try again.",
)

private val agentHttpFailureRegex =
    Regex("(?:chat completion stream|responses|anthropic) request failed\\((\\d{3})\\)")

/**
 * Port of Dart `formatAgentRuntimeErrorForUser`: short, actionable UI text
 * for an ACP boundary error. [failureKind] is the stable native
 * classification (`AgentRuntimeErrorSupport.failureKind`); unknown payloads
 * are never shown raw.
 */
internal object AgentUserErrorText {
    fun format(rawMessage: String?, failureKind: String? = null, english: Boolean = false): String =
        formatAgentRuntimeErrorText(rawMessage ?: "", failureKind?.trim(), english)
}

private fun formatAgentRuntimeErrorText(rawMessage: String, failureKind: String?, english: Boolean): String {
    val raw = rawMessage.lowercase()
    fun text(key: String): String {
        val zh = agentUserErrorsZh.getValue(key)
        return if (english) agentUserErrorsEn[agentUserErrorsZh.keys.indexOf(key)] else zh
    }

    // Errors may already have been formatted before durable projection.
    val zhValues = agentUserErrorsZh.values.toList()
    for ((index, zh) in zhValues.withIndex()) {
        if (rawMessage == zh || rawMessage == agentUserErrorsEn[index]) {
            return if (english) agentUserErrorsEn[index] else zh
        }
    }
    val httpStatus = agentHttpFailureRegex.find(raw)?.groupValues?.get(1)?.toIntOrNull()
    if (failureKind == "provider_service_unavailable") return text("serviceUnavailable")
    if (failureKind == "provider_request_rejected") return text("requestRejected")
    if (failureKind == "provider_quota_exceeded") return text("quota")
    if (failureKind == "provider_rate_limited") return text("rateLimit")
    if (failureKind == "provider_request_limited") return text("requestLimited")
    if (httpStatus == 401) return text("credentials")
    if (httpStatus == 429) {
        if (raw.contains("insufficient_quota") || raw.contains("quota_exceeded") ||
            raw.contains("quota exhausted") || raw.contains("quota_exhausted") ||
            raw.contains("额度不足") || raw.contains("余额不足")
        ) {
            return text("quota")
        }
        if (raw.contains("rate_limit_exceeded") || raw.contains("rate_limit_error")) {
            return text("rateLimit")
        }
        return text("requestLimited")
    }
    if (httpStatus != null && httpStatus >= 500) return text("serviceUnavailable")
    if (httpStatus == 400 || httpStatus == 403 || httpStatus == 422) return text("requestRejected")
    if (failureKind == "provider_not_bound" || raw.contains("dispatch model") &&
        (raw.contains("not configured") || (raw.contains("no usable") && !raw.contains("credentials")))
    ) {
        return text("modelRequired")
    }
    if (failureKind == "provider_unavailable" ||
        failureKind == "provider_authentication_failed" ||
        raw.contains("no usable credentials") ||
        raw.contains("authentication") ||
        raw.contains("unauthorized") ||
        raw.contains("invalid api key")
    ) {
        return text("credentials")
    }
    if (failureKind == "provider_model_unavailable") return text("modelUnavailable")
    if (failureKind == "provider_tls_certificate_failure" ||
        raw.contains("self_signed_cert") ||
        raw.contains("certificate_verify_failed") ||
        raw.contains("certificate verify failed")
    ) {
        return text("certificate")
    }
    if (failureKind == "provider_stream_interrupted" ||
        raw.contains("stream disconnected") ||
        raw.contains("connection reset") ||
        raw.contains("error sending request for url")
    ) {
        return text("interrupted")
    }
    if (failureKind == "provider_stream_idle_timeout" ||
        failureKind == "provider_request_timeout" ||
        raw.contains("timed out") || raw.contains("timeout")
    ) {
        return text("timeout")
    }
    if (failureKind == "provider_tool_call_incomplete" ||
        raw.contains("missing function.name") || raw.contains("missing function name")
    ) {
        return text("incompleteTool")
    }
    if (failureKind == "harness_preparation_in_progress" ||
        raw.contains("harness preparation is already running") ||
        raw.contains("harness preparation in progress")
    ) {
        return text("installBusy")
    }
    if (raw.contains("requires an anthropic-compatible") ||
        raw.contains("requires an openai responses-compatible") ||
        raw.contains("does not support the openai responses")
    ) {
        return text("incompatible")
    }
    if (raw.contains("unknown variant namespace") && raw.contains("tools")) {
        return text("unsupportedTools")
    }
    if (raw.contains("not installed") ||
        raw.contains("executable not found") ||
        raw.contains("command not found")
    ) {
        return text("notInstalled")
    }
    // Unknown server errors are untrusted; never render the raw payload.
    return text("unknown")
}
