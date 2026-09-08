package cn.com.omnimind.bot.mcp

import io.modelcontextprotocol.kotlin.sdk.types.LATEST_PROTOCOL_VERSION
import io.modelcontextprotocol.kotlin.sdk.types.SUPPORTED_PROTOCOL_VERSIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpProtocolVersionTest {
    @Test
    fun `remote MCP defaults to the latest stable SDK protocol`() {
        assertEquals("2025-11-25", LATEST_PROTOCOL_VERSION)
        assertEquals("2025-11-25", RemoteMcpClient.DEFAULT_PROTOCOL_VERSION)
        assertEquals(LATEST_PROTOCOL_VERSION, RemoteMcpClient.DEFAULT_PROTOCOL_VERSION)
    }

    @Test
    fun `server SDK keeps the previous acceptance protocol compatible`() {
        assertTrue(SUPPORTED_PROTOCOL_VERSIONS.contains("2025-03-26"))
    }

    @Test
    fun `manual modern boundary uses the current protocol and decodes name headers`() {
        assertEquals("2026-07-28", ModernMcpProtocol.PROTOCOL_VERSION)
        assertEquals("2026-07-28", RemoteMcpClient.MODERN_PROTOCOL_VERSION)
        assertEquals(
            "天气 查询",
            ModernMcpProtocol.decodeMcpHeaderValue(
                RemoteMcpClient.encodeMcpHeaderValue("天气 查询")
            ),
        )
        assertEquals("plain_tool", ModernMcpProtocol.decodeMcpHeaderValue("plain_tool"))
        assertNull(ModernMcpProtocol.decodeMcpHeaderValue("=?base64?/w==?="))
    }
}
