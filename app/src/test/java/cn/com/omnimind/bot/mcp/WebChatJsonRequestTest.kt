package cn.com.omnimind.bot.mcp

import com.google.gson.JsonSyntaxException
import com.google.gson.JsonParser
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.gson.gson
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.ktor.server.plugins.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebChatJsonRequestTest {
    @Test
    fun sessionBootstrapRouteReturnsValidJsonWithProductionConverterOrder() = testApplication {
        application {
            install(ContentNegotiation) {
                json(McpJson)
                gson()
            }
            routing {
                post("/webchat/api/session/bootstrap") {
                    call.respondWebChatJson(
                        mapOf(
                            "success" to true,
                            "server" to mapOf("running" to true, "port" to 8899),
                        )
                    )
                }
            }
        }

        val response = client.request("/webchat/api/session/bootstrap") {
            method = HttpMethod.Post
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.headers[HttpHeaders.ContentType]
                .orEmpty()
                .startsWith(ContentType.Application.Json.toString())
        )
        val payload = JsonParser.parseString(response.bodyAsText()).asJsonObject
        assertTrue(payload.get("success").asBoolean)
        assertEquals(8899, payload.getAsJsonObject("server").get("port").asInt)
    }

    @Test
    fun sessionBootstrapRouteRejectsInvalidJsonBody() = testApplication {
        application {
            routing {
                post("/webchat/api/session/bootstrap") {
                    try {
                        call.receiveOptionalWebChatJsonObject()
                        call.respondWebChatJson(mapOf("success" to true))
                    } catch (_: BadRequestException) {
                        call.respondWebChatJson(
                            mapOf("error" to "Invalid JSON request body"),
                            HttpStatusCode.BadRequest
                        )
                    }
                }
            }
        }

        val response = client.request("/webchat/api/session/bootstrap") {
            method = HttpMethod.Post
            setBody("[not-an-object]")
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            "Invalid JSON request body",
            JsonParser.parseString(response.bodyAsText()).asJsonObject.get("error").asString
        )
    }

    @Test
    fun serializesHeterogeneousSessionBootstrapPayloadAsValidJson() {
        val json = serializeWebChatJson(
            mapOf(
                "success" to true,
                "server" to mapOf("running" to true, "port" to 8899),
            )
        )
        val payload = JsonParser.parseString(json).asJsonObject

        assertTrue(payload.get("success").asBoolean)
        assertTrue(payload.getAsJsonObject("server").get("running").asBoolean)
        assertEquals(8899, payload.getAsJsonObject("server").get("port").asInt)
    }

    @Test
    fun parsesHeterogeneousJsonObject() {
        val payload = parseWebChatJsonObject(
            """
            {
              "title": "Telegram",
              "enabled": true,
              "count": 3,
              "attachments": [{"name": "photo.jpg", "size": 42}],
              "metadata": {"source": "telegram"}
            }
            """.trimIndent()
        )

        assertEquals("Telegram", payload["title"])
        assertEquals(true, payload["enabled"])
        assertEquals(3.0, payload["count"])
        assertTrue(payload["attachments"] is List<*>)
        assertTrue(payload["metadata"] is Map<*, *>)
    }

    @Test
    fun rejectsBlankOrNonObjectBodies() {
        assertThrows(JsonSyntaxException::class.java) { parseWebChatJsonObject("") }
        assertThrows(JsonSyntaxException::class.java) { parseWebChatJsonObject("[]") }
        assertThrows(JsonSyntaxException::class.java) { parseWebChatJsonObject("null") }
    }
}
