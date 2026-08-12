package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.baselib.account.AccountApiException
import cn.com.omnimind.baselib.account.AccountProtocolException
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeChannelErrorPrivacyTest {
    private val canary =
        "https://private.example.invalid/v1?q=secret C:\\Users\\owner\\private.txt token=canary-token"

    @Test
    fun genericFailureDoesNotExposeExceptionDataInPayloadOrLog() {
        val result = RecordingResult()
        val logs = mutableListOf<String>()

        NativeChannelErrorPrivacy.deliver(
            result = result,
            tag = "TestChannel",
            requestedCode = "NETWORK_ERROR",
            error = IllegalStateException(canary),
            reporter = logs::add,
        )

        assertEquals("NETWORK_ERROR", result.errorCode)
        assertEquals("The network request failed.", result.errorMessage)
        assertNull(result.errorDetails)
        assertRedacted(result.exposedText(logs))
    }

    @Test
    fun callerControlledFailureCodeFailsClosed() {
        val result = RecordingResult()
        val logs = mutableListOf<String>()

        NativeChannelErrorPrivacy.deliver(
            result = result,
            tag = "TestChannel",
            requestedCode = canary,
            error = IllegalStateException(canary),
            reporter = logs::add,
        )

        assertEquals("NATIVE_OPERATION_FAILED", result.errorCode)
        assertEquals("The native operation failed.", result.errorMessage)
        assertNull(result.errorDetails)
        assertRedacted(result.exposedText(logs))
    }

    @Test
    fun accountApiFailureUsesOnlyAllowlistedCodeAndSafeStatus() {
        val knownResult = RecordingResult()
        val knownLogs = mutableListOf<String>()
        NativeChannelErrorPrivacy.deliverAccount(
            result = knownResult,
            tag = "AccountChannel",
            error = AccountApiException(503, "internal_error", canary),
            reporter = knownLogs::add,
        )

        assertEquals("internal_error", knownResult.errorCode)
        assertEquals("The account service is temporarily unavailable.", knownResult.errorMessage)
        assertEquals(mapOf("statusCode" to 503), knownResult.errorDetails)
        assertRedacted(knownResult.exposedText(knownLogs))

        val unknownResult = RecordingResult()
        val unknownLogs = mutableListOf<String>()
        NativeChannelErrorPrivacy.deliverAccount(
            result = unknownResult,
            tag = "AccountChannel",
            error = AccountApiException(418, canary, canary),
            reporter = unknownLogs::add,
        )

        assertEquals("ACCOUNT_HTTP_418", unknownResult.errorCode)
        assertEquals("The account request failed.", unknownResult.errorMessage)
        assertEquals(mapOf("statusCode" to 418), unknownResult.errorDetails)
        assertRedacted(unknownResult.exposedText(unknownLogs))
    }

    @Test
    fun typedAccountProtocolFailureDoesNotExposeItsMessage() {
        val result = RecordingResult()
        val logs = mutableListOf<String>()

        NativeChannelErrorPrivacy.deliverAccount(
            result = result,
            tag = "AccountChannel",
            error = AccountProtocolException(canary),
            reporter = logs::add,
        )

        assertEquals("ACCOUNT_PROTOCOL_ERROR", result.errorCode)
        assertEquals("The account service returned an invalid response.", result.errorMessage)
        assertNull(result.errorDetails)
        assertRedacted(result.exposedText(logs))
    }

    @Test
    fun cancellationIsNeverConvertedIntoAChannelFailure() {
        val result = RecordingResult()

        assertThrows(CancellationException::class.java) {
            NativeChannelErrorPrivacy.deliver(
                result = result,
                tag = "TestChannel",
                requestedCode = "NETWORK_ERROR",
                error = CancellationException(canary),
                reporter = { throw AssertionError("cancellation must not be logged") },
            )
        }

        assertNull(result.errorCode)
    }

    private fun assertRedacted(exposed: String) {
        assertFalse(exposed.contains("private.example.invalid"))
        assertFalse(exposed.contains("private.txt"))
        assertFalse(exposed.contains("canary-token"))
        assertFalse(exposed.contains("C:\\Users\\owner"))
    }

    private class RecordingResult : MethodChannel.Result {
        var errorCode: String? = null
        var errorMessage: String? = null
        var errorDetails: Any? = null

        override fun success(result: Any?) = Unit

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            this.errorCode = errorCode
            this.errorMessage = errorMessage
            this.errorDetails = errorDetails
        }

        override fun notImplemented() = Unit

        fun exposedText(logs: List<String>): String = listOf(
            errorCode,
            errorMessage,
            errorDetails,
            logs.joinToString("\n"),
        ).joinToString("\n")
    }
}
