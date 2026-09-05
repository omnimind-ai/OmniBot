package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.account.AiAccessMode
import cn.com.omnimind.baselib.account.AiRequestAccess
import cn.com.omnimind.bot.media.PlatformMediaGatewayExecutor
import com.google.gson.JsonParser
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformEmbeddingGatewayTest {
    @Test
    fun `embedding dimensions and response size belong to the provider`() = runBlocking {
        val dimensions = 8_193
        val body = """{"padding":"${"x".repeat(2 * 1024 * 1024)}","data":[{"embedding":[${List(dimensions) { "0.1" }.joinToString(",")}]}]}"""
        val executor = PlatformMediaGatewayExecutor(
            executeRequest = { request -> response(request, 200, body) },
            accessProvider = {
                AiRequestAccess(
                    mode = AiAccessMode.PLATFORM,
                    platformGatewayUrl = "https://model.example.com",
                    bearerToken = "jwt",
                )
            },
        )
        assertEquals(List(dimensions) { 0.1 }, PlatformEmbeddingGateway(executor).embed("custom", "memory"))
    }

    @Test
    fun `official embedding uses JWT endpoint and refreshes once on 401`() = runBlocking {
        val codes = ArrayDeque(listOf(401, 200))
        var token = "jwt-one"
        val requests = mutableListOf<Request>()
        val executor = PlatformMediaGatewayExecutor(
            executeRequest = { request ->
                requests += request
                val code = codes.removeFirst()
                response(
                    request,
                    code,
                    if (code == 200) {
                        """{"data":[{"embedding":[0.1,0.2,0.3]}]}"""
                    } else {
                        "{}"
                    },
                )
            },
            accessProvider = {
                AiRequestAccess(
                    mode = AiAccessMode.PLATFORM,
                    platformGatewayUrl = "https://model.example.com",
                    bearerToken = token,
                )
            },
            refreshSession = { token = "jwt-two" },
        )

        val vector = PlatformEmbeddingGateway(executor).embed("text-embedding-v4", "hello")

        assertEquals(listOf(0.1, 0.2, 0.3), vector)
        assertEquals(2, requests.size)
        assertEquals("https://model.example.com/v1/embeddings", requests[0].url.toString())
        assertEquals("Bearer jwt-one", requests[0].header("Authorization"))
        assertEquals("Bearer jwt-two", requests[1].header("Authorization"))
    }

    @Test
    fun `official embedding forwards a long memory without host truncation`() = runBlocking {
        val requests = mutableListOf<Request>()
        val executor = PlatformMediaGatewayExecutor(
            executeRequest = { request ->
                requests += request
                response(request, 200, """{"data":[{"embedding":[0.1]}]}""")
            },
            accessProvider = {
                AiRequestAccess(
                    mode = AiAccessMode.PLATFORM,
                    platformGatewayUrl = "https://model.example.com",
                    bearerToken = "jwt",
                )
            },
        )
        val memory = "memory-" + "x".repeat(16 * 1024)

        PlatformEmbeddingGateway(executor).embed("text-embedding-v4", memory)

        val body = Buffer().use { buffer ->
            requireNotNull(requests.single().body).writeTo(buffer)
            buffer.readUtf8()
        }
        val forwarded = JsonParser.parseString(body)
            .asJsonObject
            .getAsJsonArray("input")[0]
            .asString
        assertEquals(memory, forwarded)
    }

    private fun response(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody())
            .build()
}
