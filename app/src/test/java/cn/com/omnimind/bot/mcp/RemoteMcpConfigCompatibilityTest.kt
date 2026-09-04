package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpConfigCompatibilityTest {

    @Test
    fun `legacy persisted entries receive defaults and remain usable`() {
        val configs = RemoteMcpConfigStore.decodeServersJson(
            """[
              {
                "id":"legacy-mt",
                "name":"MT",
                "endpointUrl":"http://127.0.0.1:8787/mcp",
                "bearerToken":"",
                "enabled":true,
                "lastHealth":"HEALTHY",
                "toolCount":26,
                "lastSyncedAt":1788369271690
              }
            ]""".trimIndent(),
        )

        val config = configs.single()
        assertEquals(RemoteMcpTransport.AUTO, config.transport)
        assertTrue(config.headers.isEmpty())
        assertEquals(RemoteMcpHealth.HEALTHY, config.lastHealth)
        assertEquals(26, config.toolCount)
        assertEquals("auto", config.toMap()["transport"])
    }

    @Test
    fun `mixed legacy and current persisted entries decode independently`() {
        val configs = RemoteMcpConfigStore.decodeServersJson(
            """[
              {
                "id":"legacy",
                "name":"legacy",
                "endpointUrl":"http://127.0.0.1:8787/mcp",
                "enabled":true,
                "lastHealth":"ERROR"
              },
              {
                "id":"current",
                "name":"current",
                "endpointUrl":"https://example.com/sse",
                "headers":{"X-Tenant":"demo"},
                "transport":"SSE",
                "enabled":true,
                "lastHealth":"healthy"
              }
            ]""".trimIndent(),
        )

        assertEquals(listOf("legacy", "current"), configs.map { it.id })
        assertEquals(RemoteMcpHealth.ERROR, configs[0].lastHealth)
        assertEquals(RemoteMcpTransport.SSE, configs[1].transport)
        assertEquals(mapOf("X-Tenant" to "demo"), configs[1].headers)
    }
}
