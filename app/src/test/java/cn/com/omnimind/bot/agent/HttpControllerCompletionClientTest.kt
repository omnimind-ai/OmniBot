package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpControllerCompletionClientTest {
    @Test
    fun `non streaming model completion permits long generation`() {
        val getter = HttpController::class.java.getDeclaredMethod("getSceneCompletionClient")
        getter.isAccessible = true

        val client = getter.invoke(HttpController) as OkHttpClient

        assertEquals(60_000, client.connectTimeoutMillis)
        assertEquals(0, client.readTimeoutMillis)
        assertEquals(60_000, client.writeTimeoutMillis)
    }
}
