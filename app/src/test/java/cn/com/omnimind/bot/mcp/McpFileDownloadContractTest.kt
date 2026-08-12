package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFileDownloadContractTest {
    @Test
    fun `download token is carried by a header and never by the url`() {
        val token = "temporary-secret"
        val url = McpFileDownloadContract.buildUrl("192.168.1.8", 8088, "file-id")
        val headers = McpFileDownloadContract.buildHeaders(token)

        assertEquals("http://192.168.1.8:8088/mcp/file/file-id", url)
        assertFalse(url.contains(token))
        assertFalse(url.contains("?"))
        assertEquals(token, headers[McpFileDownloadContract.TOKEN_HEADER])
    }

    @Test
    fun `ipv6 hosts are bracketed in the download url`() {
        val url = McpFileDownloadContract.buildUrl("fd00::1", 8088, "file-id")
        assertTrue(url.startsWith("http://[fd00::1]:8088/"))
    }
}
