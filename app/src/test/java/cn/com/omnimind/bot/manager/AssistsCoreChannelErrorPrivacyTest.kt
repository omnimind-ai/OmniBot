package cn.com.omnimind.bot.manager

import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AssistsCoreChannelErrorPrivacyTest {
    private val canary =
        "https://private.example.invalid/v1 C:\\Users\\owner\\private.txt " +
            "token=canary-token body={\"secret\":\"canary-body\"}"

    @Test
    fun genericFailureExcludesExceptionDataFromChannelPayloadAndLog() {
        val result = RecordingResult()
        val logs = mutableListOf<String>()

        AssistsCoreChannelErrorPrivacy.deliver(
            result = result,
            tag = "AssistsCoreManager",
            requestedCode = "GET_CONVERSATIONS_ERROR",
            error = IllegalStateException(canary),
            reporter = logs::add,
        )

        assertEquals("GET_CONVERSATIONS_ERROR", result.errorCode)
        assertEquals("The requested operation failed.", result.errorMessage)
        assertNull(result.errorDetails)
        assertRedacted(result.exposedText(logs))
    }

    @Test
    fun agentStartupFailureUsesStableUserFacingMessage() {
        val result = RecordingResult()
        val logs = mutableListOf<String>()

        AssistsCoreChannelErrorPrivacy.deliver(
            result = result,
            tag = "AssistsCoreManager",
            requestedCode = "CREATE_AGENT_TASK_ERROR",
            error = IllegalArgumentException(canary),
            reporter = logs::add,
        )

        assertEquals("CREATE_AGENT_TASK_ERROR", result.errorCode)
        assertEquals("Agent execution failed.", result.errorMessage)
        assertNull(result.errorDetails)
        assertRedacted(result.exposedText(logs))
    }

    @Test
    fun callerControlledErrorCodeFailsClosed() {
        val result = RecordingResult()
        val logs = mutableListOf<String>()

        AssistsCoreChannelErrorPrivacy.deliver(
            result = result,
            tag = "AssistsCoreManager",
            requestedCode = canary,
            error = IllegalStateException(canary),
            reporter = logs::add,
        )

        assertEquals("ASSISTS_OPERATION_FAILED", result.errorCode)
        assertEquals("The requested operation failed.", result.errorMessage)
        assertNull(result.errorDetails)
        assertRedacted(result.exposedText(logs))
    }

    @Test
    fun cancellationIsPropagatedWithoutPayloadOrLog() {
        val result = RecordingResult()

        assertThrows(CancellationException::class.java) {
            AssistsCoreChannelErrorPrivacy.deliver(
                result = result,
                tag = "AssistsCoreManager",
                requestedCode = "GET_CONVERSATIONS_ERROR",
                error = CancellationException(canary),
                reporter = { throw AssertionError("cancellation must not be logged") },
            )
        }

        assertNull(result.errorCode)
    }

    private fun assertRedacted(exposed: String) {
        assertFalse(exposed.contains("private.example.invalid"))
        assertFalse(exposed.contains("C:\\Users\\owner"))
        assertFalse(exposed.contains("canary-token"))
        assertFalse(exposed.contains("canary-body"))
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
