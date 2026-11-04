package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.account.AccountApiException
import cn.com.omnimind.baselib.account.AccountNotAuthenticatedException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class NaRuntimeFailureTest {
    @Test fun httpFailuresCarryStatusAndPhaseWithoutResponsePayloads() {
        for ((status, kind) in mapOf(
            401 to "na_authentication_required", 403 to "na_permission_denied",
            408 to "na_request_timeout", 429 to "na_rate_limited",
            503 to "na_service_unavailable", 400 to "na_request_rejected",
        )) {
            val failure = naHttpFailure(status, "message_admission")
            assertEquals(kind, failure.failureKind)
            assertEquals(status, failure.statusCode)
            assertEquals("message_admission", failure.phase)
            assertFalse(failure.message.orEmpty().contains("模型"))
        }
    }

    @Test fun networkTimeoutAndTlsFailuresRemainDistinctFromModelFailures() {
        for ((cause, kind) in listOf(
            IOException("https://secret.invalid/?token=secret") to "na_connection_failed",
            SocketTimeoutException("token=secret timeout") to "na_request_timeout",
            SSLHandshakeException("token=secret certificate") to "na_tls_certificate_failure",
        )) {
            val failure = naConnectionFailure(cause, "session_create") as NaRuntimeException
            assertEquals(kind, failure.failureKind)
            assertEquals("session_create", failure.phase)
            assertSame(cause, failure.cause)
            assertFalse(failure.message.orEmpty().contains("secret"))
        }
    }

    @Test fun accountHttpFailuresKeepStructuredStatusWithoutAccountPayloads() {
        for ((status, kind) in mapOf(
            401 to "na_authentication_required", 403 to "na_permission_denied",
            429 to "na_rate_limited", 503 to "na_service_unavailable",
        )) {
            val accountError = AccountApiException(status, "opaque-account-code", "token=secret-payload")
            val error = IllegalStateException("account operation failed", accountError)
            val failure = naConnectionFailure(error, "message_admission") as NaRuntimeException
            assertEquals(kind, failure.failureKind)
            assertEquals(status, failure.statusCode)
            assertEquals("message_admission", failure.phase)
            assertSame(error, failure.cause)
            assertFalse(failure.message.orEmpty().contains("secret-payload"))
            assertFalse(failure.message.orEmpty().contains("模型"))
        }
    }

    @Test fun accountSessionGuardsRequireNaAuthenticationWithoutProviderGuidance() {
        val accountError = AccountNotAuthenticatedException()
        for (error in listOf(accountError, IllegalStateException("session guard", accountError))) {
            val failure = naConnectionFailure(error, "connection") as NaRuntimeException
            assertEquals("na_authentication_required", failure.failureKind)
            assertEquals("connection", failure.phase)
            assertNull(failure.statusCode)
            assertSame(error, failure.cause)
            assertTrue(failure.message.orEmpty().contains("Na"))
            assertFalse(failure.message.orEmpty().contains("模型"))
        }
    }

    @Test fun cancellationAndExistingFailuresAreNeverReclassified() {
        val cancelled = CancellationException("cancelled")
        val wrapped = IllegalStateException("outer", cancelled)
        val typed = naHttpFailure(503, "run_read")
        val ordinary = IllegalArgumentException("invalid input")
        for (failure in listOf(cancelled, wrapped, typed, IllegalStateException("outer", typed), ordinary)) {
            assertSame(failure, naConnectionFailure(failure, "request"))
        }
    }
}
