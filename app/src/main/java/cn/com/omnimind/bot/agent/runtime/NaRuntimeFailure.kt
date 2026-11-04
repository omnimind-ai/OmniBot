package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.account.AccountApiException
import cn.com.omnimind.baselib.account.AccountNotAuthenticatedException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException

/** Safe diagnostic vocabulary at the existing Na request boundary. */
internal interface NaRuntimeFailure {
    val failureKind: String
    val statusCode: Int?
    val phase: String?
}

internal val naRuntimeFailureKinds = setOf(
    "na_connection_failed", "na_request_timeout", "na_tls_certificate_failure",
    "na_authentication_required", "na_permission_denied", "na_connection_changed",
    "na_owner_conflict", "na_service_unavailable", "na_rate_limited",
    "na_request_rejected", "na_response_invalid", "na_admission_unconfirmed",
)

internal val naRuntimeFailurePhases = setOf(
    "connection", "session_create", "session_load", "message_admission", "run_read",
    "attachment_upload", "request", "response", "status",
)

internal class NaRuntimeException(
    override val failureKind: String,
    override val phase: String? = null,
    override val statusCode: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException(naRuntimeFailureMessage(failureKind), cause), NaRuntimeFailure {
    init {
        require(failureKind in naRuntimeFailureKinds) { "Unknown Na failure kind" }
        require(phase == null || phase in naRuntimeFailurePhases) { "Unknown Na request phase" }
        require(statusCode == null || statusCode in 100..599) { "Invalid HTTP status" }
    }
}

internal fun naHttpFailure(
    statusCode: Int,
    phase: String,
    cause: Throwable? = null,
): NaRuntimeException = NaRuntimeException(
    failureKind = when (statusCode) {
        401 -> "na_authentication_required"
        403 -> "na_permission_denied"
        408 -> "na_request_timeout"
        429 -> "na_rate_limited"
        in 500..599 -> "na_service_unavailable"
        else -> "na_request_rejected"
    },
    phase = phase,
    statusCode = statusCode,
    cause = cause,
)

/** Classification does not retry a request or change its logical turn. */
internal fun naConnectionFailure(error: Throwable, phase: String): Throwable {
    if (error is CancellationException || error is NaRuntimeFailure) return error
    val causes = generateSequence(error) { it.cause }.take(16).toList()
    if (causes.any { it is CancellationException || it is NaRuntimeFailure }) return error
    causes.filterIsInstance<AccountApiException>().firstOrNull()?.let {
        return naHttpFailure(it.statusCode, phase, cause = error)
    }
    val kind = when {
        causes.any { it is AccountNotAuthenticatedException } -> "na_authentication_required"
        causes.any { it is SSLHandshakeException || it is SSLPeerUnverifiedException ||
            it is CertificateException || it is CertPathValidatorException } -> "na_tls_certificate_failure"
        causes.any { it is SocketTimeoutException ||
            (it is InterruptedIOException && it.message?.equals("timeout", ignoreCase = true) == true) } -> "na_request_timeout"
        causes.any { it is IOException } -> "na_connection_failed"
        else -> return error
    }
    return NaRuntimeException(kind, phase, cause = error)
}

internal fun naRuntimeFailureMessage(kind: String): String = when (kind) {
    "na_connection_failed" -> "无法连接 Na 服务，请检查网络后重试。"
    "na_request_timeout" -> "连接 Na 服务超时，请稍后重试。"
    "na_tls_certificate_failure" -> "Na 安全连接验证失败，请检查网络和设备时间。"
    "na_authentication_required" -> "Na 连接验证已失效，请重新登录或检查连接设置。"
    "na_permission_denied" -> "当前账号没有此 Na 操作的权限，请检查账号授权。"
    "na_connection_changed" -> "Na 连接已变化，请重新打开此对话。"
    "na_owner_conflict" -> "此 Na 对话属于其他账号或连接，请切回原账号或新建对话。"
    "na_service_unavailable" -> "Na 服务暂时不可用，请稍后重试。"
    "na_rate_limited" -> "Na 请求过于频繁，请稍后重试。"
    "na_request_rejected" -> "Na 未接受此请求，请重新打开对话后重试。"
    "na_response_invalid" -> "Na 返回了无法识别的响应，请稍后重试。"
    "na_admission_unconfirmed" -> "尚未确认 Na 是否已受理，请重新打开对话查看状态。"
    else -> "Na 操作未完成，请重试。"
}
