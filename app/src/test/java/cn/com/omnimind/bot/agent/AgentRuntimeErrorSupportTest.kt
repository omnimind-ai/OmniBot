package cn.com.omnimind.bot.agent

import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeErrorSupportTest {
    @Test
    fun `certificate chain failures explain the device clock and preserve tls`() {
        val handshake = SSLHandshakeException("handshake failed").apply {
            initCause(
                CertPathValidatorException(
                    "Trust anchor for certification path not found"
                )
            )
        }
        val error = IllegalStateException("Chain validation failed", handshake)

        val message = AgentRuntimeErrorSupport.userFacingMessage(error)

        assertTrue(message.orEmpty().contains("自动日期和时间"))
        assertTrue(message.orEmpty().contains("不会关闭证书校验"))
        assertEquals(
            AgentRuntimeErrorSupport.PROVIDER_TLS_CERTIFICATE_FAILURE,
            AgentRuntimeErrorSupport.failureKind(error)
        )
    }

    @Test
    fun `ordinary acp failures are not relabeled as certificate failures`() {
        val error = IllegalStateException("ACP session is already active")

        assertNull(AgentRuntimeErrorSupport.userFacingMessage(error))
        assertNull(AgentRuntimeErrorSupport.failureKind(error))
    }

    @Test
    fun `provider stream idle timeout maps to a recoverable provider message`() {
        val error = AgentStreamIdleTimeoutException(90_000L)

        assertEquals(
            AgentRuntimeErrorSupport.PROVIDER_STREAM_IDLE_TIMEOUT,
            AgentRuntimeErrorSupport.failureKind(error)
        )
        assertTrue(
            AgentRuntimeErrorSupport.userFacingMessage(error)
                .orEmpty()
                .contains("没有返回新的流式更新")
        )
    }

    @Test
    fun `provider readiness failures get actionable boundary kinds`() {
        val error = IllegalStateException(
            "Agent Provider is not bound to scene.dispatch.model."
        )

        assertEquals(
            AgentRuntimeErrorSupport.PROVIDER_NOT_BOUND,
            AgentRuntimeErrorSupport.failureKind(error)
        )
        assertTrue(
            AgentRuntimeErrorSupport.userFacingMessage(error)
                .orEmpty()
                .contains("Dispatch Model")
        )
    }

    @Test
    fun `provider binding error names the Agent scene and explains the boundary`() {
        val message = AgentRuntimeErrorSupport.userFacingMessage(
            IllegalStateException(
                "Agent Provider is not bound to scene.dispatch.model."
            )
        ).orEmpty()

        assertTrue(message.contains("scene.dispatch.model"))
        assertTrue(message.contains("Harness 安装不依赖这个绑定"))
    }

    @Test
    fun `xiaowan missing verified binding maps to provider not bound`() {
        val error = IllegalStateException(
            "No verified Provider/model binding for Xiaowan ACP. " +
                "Select a model in scene.dispatch.model and retry."
        )

        assertEquals(
            AgentRuntimeErrorSupport.PROVIDER_NOT_BOUND,
            AgentRuntimeErrorSupport.failureKind(error)
        )
    }

    @Test
    fun `incomplete provider tool calls get an actionable boundary error`() {
        val error = IllegalStateException(
            "stream parsing failed",
            AgentIncompleteToolCallException(toolCallIndex = 1)
        )

        val message = AgentRuntimeErrorSupport.userFacingMessage(error).orEmpty()

        assertEquals(
            AgentRuntimeErrorSupport.PROVIDER_TOOL_CALL_INCOMPLETE,
            AgentRuntimeErrorSupport.failureKind(error)
        )
        assertTrue(message.contains("工具调用"))
        assertTrue(message.contains("Provider"))
        assertTrue(!message.contains("missing function.name"))
    }

    @Test
    fun `harness preparation does not turn another switch into a wait`() {
        val error = IllegalStateException(
            "Harness preparation is already running for deepseek-harness-acp. " +
                "Wait for that installation to finish before starting another unprepared Harness."
        )

        assertEquals(
            AgentRuntimeErrorSupport.HARNESS_PREPARATION_IN_PROGRESS,
            AgentRuntimeErrorSupport.failureKind(error)
        )
        assertTrue(
            AgentRuntimeErrorSupport.userFacingMessage(error)
                .orEmpty()
                .contains("不会等待")
        )
    }

    @Test
    fun `missing official harness profile points to preparation instead of raw stderr`() {
        val error = IllegalStateException(
            "dsh: profile \"acp\" does not exist; create it with 'dsh plugin --profile acp add <package>'"
        )

        assertEquals(
            AgentRuntimeErrorSupport.HARNESS_PROFILE_MISSING,
            AgentRuntimeErrorSupport.failureKind(error)
        )
        assertTrue(
            AgentRuntimeErrorSupport.userFacingMessage(error)
                .orEmpty()
                .contains("官方 ACP profile")
        )
    }

    @Test
    fun `diagnostic messages redact credentials and stay bounded`() {
        val error = IllegalStateException(
            "request failed Bearer abc.def token=secret-value " + "x".repeat(500)
        )

        val diagnostic = AgentRuntimeErrorSupport.safeDiagnosticMessage(error)

        assertTrue(diagnostic.length <= 300)
        assertTrue(!diagnostic.contains("abc.def"))
        assertTrue(!diagnostic.contains("secret-value"))
    }
}
