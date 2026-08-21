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
                .contains("选择 Provider 和模型")
        )
    }
}
