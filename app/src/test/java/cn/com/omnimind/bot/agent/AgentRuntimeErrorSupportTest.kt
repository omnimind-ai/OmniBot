package cn.com.omnimind.bot.agent

import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeErrorSupportTest {
    @org.junit.Test
    fun `confirmed context rejection survives ACP without blaming credentials or generic configuration`() {
        val error = AgentStreamRequestException(400,
            "OpenAIException - Prompt exceeds max length Error happened to model=GLM-4.6V", null)
        assertEquals("provider_context_exceeded", AgentRuntimeErrorSupport.failureKind(error))
        val message = AgentRuntimeErrorSupport.userFacingMessage(error)!!
        assertTrue(message.contains("上下文") && message.contains("历史记录已保留"))
        val wire = AgentRuntimeErrorSupport.acpPromptFailure(message, error)
        assertEquals("provider_context_exceeded", AgentRuntimeErrorSupport.failureKind(wire))
        assertEquals("provider_request_rejected", AgentRuntimeErrorSupport.failureKind(
            AgentStreamRequestException(400, "unsupported parameter", null)))
        assertEquals("provider_service_unavailable", AgentRuntimeErrorSupport.failureKind(
            AgentStreamRequestException(503, "Prompt exceeds max length", null)))
    }

    @Test fun `image rejection keeps capability guidance across ACP and preserves auth classification`() {
        val failure = AgentStreamRequestException(400, "gateway fallback timeout", null, imageInput = true)
        assertEquals(AgentRuntimeErrorSupport.PROVIDER_IMAGE_REQUEST_REJECTED,
            AgentRuntimeErrorSupport.failureKind(failure))
        val wire = AgentRuntimeErrorSupport.acpPromptFailure("image rejected", failure)
        assertEquals(AgentRuntimeErrorSupport.PROVIDER_IMAGE_REQUEST_REJECTED,
            AgentRuntimeErrorSupport.failureKind(wire))
        assertTrue(AgentRuntimeErrorSupport.userFacingMessage(wire)!!.contains("支持图片"))
        assertEquals(AgentRuntimeErrorSupport.PROVIDER_AUTHENTICATION_FAILED,
            AgentRuntimeErrorSupport.failureKind(AgentStreamRequestException(401, "bad key", null, imageInput = true)))
        assertEquals(AgentRuntimeErrorSupport.PROVIDER_REQUEST_REJECTED,
            AgentRuntimeErrorSupport.failureKind(AgentStreamRequestException(400, "image in error text", null)))
    }

    @Test fun `structured provider failures do not depend on message language`() {
        for ((status, code, kind) in listOf(
            Triple(401, "unknown", "provider_authentication_failed"),
            Triple(403, "permission_denied", "provider_request_rejected"),
            Triple(503, "server_error", "provider_service_unavailable"),
            Triple(404, "model_not_found", "provider_model_unavailable"),
        )) {
            for (failure in listOf(
                AgentProviderStreamException(status, code, "测试失败"),
                AgentStreamRequestException(status, "测试失败", """{"error":{"code":"$code"}}"""),
            )) {
                assertEquals(kind, AgentRuntimeErrorSupport.failureKind(failure))
                val outgoing = AgentRuntimeErrorSupport.acpPromptFailure("测试失败", failure)
                val incoming = com.agentclientprotocol.protocol.JsonRpcException(outgoing.code, outgoing.message,
                    kotlinx.serialization.json.Json.parseToJsonElement(outgoing.data.toString()))
                assertEquals(kind, AgentRuntimeErrorSupport.failureKind(incoming))
                assertEquals("测试失败", incoming.message)
                assertEquals(setOf("failureKind"), (incoming.data as kotlinx.serialization.json.JsonObject).keys)
            }
        }
    }

    @Test
    fun `provider quota rate and unspecified request limits stay distinct`() {
        for ((code, expected) in listOf(
            "insufficient_quota" to "provider_quota_exceeded",
            "rate_limit_exceeded" to "provider_rate_limited",
            "unknown" to "provider_request_limited",
        )) {
            val http = AgentStreamRequestException(429, "limited", """{"error":{"code":"$code","message":"limited"}}""")
            assertEquals(expected, AgentRuntimeErrorSupport.failureKind(http))
            val accumulator = AgentLlmStreamAccumulator(json = kotlinx.serialization.json.Json)
            accumulator.consume("""{"error":{"code":"$code","message":"limited"},"status_code":429}""")
            val failure = runCatching { accumulator.buildTurn() }.exceptionOrNull()!!
            assertEquals(expected, AgentRuntimeErrorSupport.failureKind(IllegalStateException("outer", failure)))
            assertTrue(AgentRuntimeErrorSupport.userFacingMessage(failure) != null)
        }
        assertNull(AgentRuntimeErrorSupport.failureKind(IllegalStateException("A file mentions insufficient_quota")))
    }

    @Test
    fun requestTimeoutDoesNotClaimMissingCredentialsOrEmptyModels() {
        val error = IllegalStateException("model fetch failed", java.net.SocketTimeoutException("timeout"))
        assertEquals("provider_request_timeout", AgentRuntimeErrorSupport.failureKind(error))
        assertTrue(AgentRuntimeErrorSupport.userFacingMessage(error)!!.contains("服务商请求超时"))
    }
    @Test
    fun authenticationFailureIsNotReportedAsMissingModel() {
        for (message in listOf("获取模型列表失败 (401)：Invalid API Key",
            "AuthenticationError: OpenAIException 身份验证失败。Error happened to model=GLM-4.5-Air")) {
            val error = IllegalStateException(message)
            assertEquals("provider_authentication_failed", AgentRuntimeErrorSupport.failureKind(error))
            assertTrue(AgentRuntimeErrorSupport.userFacingMessage(error)!!.contains("身份验证失败"))
        }
    }
    @Test
    fun `incomplete call diagnostic never invents a retry`() {
        val message = AgentRuntimeErrorSupport.userFacingMessage(AgentIncompleteToolCallException(0)).orEmpty()
        assertTrue(message.contains("不完整"))
        assertTrue(!message.contains("已自动重试"))
    }

    @Test
    fun `upstream invalid model failure explains model selection without a cache refresh`() {
        val error = IllegalStateException("chat completion stream request failed(404): NotFoundError: OpenAIException - Invalid model.Error happened to model=GLM-5")
        assertEquals("provider_model_unavailable", AgentRuntimeErrorSupport.failureKind(error))
        assertTrue(AgentRuntimeErrorSupport.userFacingMessage(error).orEmpty().contains("重新选择"))
        assertTrue(!AgentRuntimeErrorSupport.userFacingMessage(error).orEmpty().contains("刷新"))
    }

    @Test
    fun `provider connection abort identifies an interrupted response`() {
        val error = AgentStreamRequestException(200, "Software caused connection abort", null)
        assertEquals("provider_stream_interrupted", AgentRuntimeErrorSupport.failureKind(error))
        assertTrue(AgentRuntimeErrorSupport.userFacingMessage(error).orEmpty().contains("连接中断"))
    }

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
    fun `diagnostic messages redact credentials without truncating the provider detail`() {
        val error = IllegalStateException(
            "request failed Bearer abc.def token=secret-value " + "x".repeat(500)
        )

        val diagnostic = AgentRuntimeErrorSupport.safeDiagnosticMessage(error)

        assertTrue(diagnostic.length > 300)
        assertTrue(!diagnostic.contains("abc.def"))
        assertTrue(!diagnostic.contains("secret-value"))
    }
}
