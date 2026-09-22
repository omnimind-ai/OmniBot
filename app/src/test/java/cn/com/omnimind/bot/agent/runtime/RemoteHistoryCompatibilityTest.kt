package cn.com.omnimind.bot.agent.runtime

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.ToNumberPolicy
import org.junit.Assert.*
import org.junit.Test

class RemoteHistoryCompatibilityTest {
    private val gson = GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()

    @Test fun importedItemsMatchPreMigrationDartFixtures() {
        val raw = javaClass.getResourceAsStream("/chat/remote-history-compatibility.json")!!.bufferedReader().use { it.readText() }
        val fixtures = JsonParser.parseString(raw).asJsonArray
        assertTrue(fixtures.size() >= 9)
        fixtures.forEachIndexed { index, fixture ->
            val turn = gson.fromJson(fixture.asJsonObject["turn"], Map::class.java).entries.associate { it.key.toString() to it.value }
            val actual = RemoteHistoryCompatibility.items(turn)
            assertEquals("baseline turn $index", fixture.asJsonObject["items"], gson.toJsonTree(actual))
        }
    }

    @Test fun normalizationIsIdempotentAndDoesNotInferTurnActivity() {
        val response = mapOf("sessionId" to "s", "configOptions" to listOf("keep"), "thread" to mapOf(
            "id" to "s", "status" to "busy", "turns" to listOf(mapOf("id" to "t", "status" to "failed",
                "events" to listOf(mapOf("method" to "item/commandExecution/outputDelta", "params" to mapOf("itemId" to "tool", "delta" to "output"))),
                "worklog" to mapOf("messages" to emptyList<Any?>())))))
        val normalized = RemoteHistoryCompatibility.normalizeResponse(response)
        assertEquals(normalized, RemoteHistoryCompatibility.normalizeResponse(normalized))
        assertEquals(listOf("keep"), normalized["configOptions"])
        val thread = normalized["thread"] as Map<*, *>
        assertEquals("busy", thread["status"])
        val turn = (thread["turns"] as List<*>).single() as Map<*, *>
        assertEquals("failed", turn["status"])
        assertFalse(turn.containsKey("events"))
        assertFalse(turn.containsKey("worklog"))
    }

    @Test fun duplicateSnapshotsMergeWithoutErasingContentOrSessionIdentity() {
        val turn = mapOf("items" to listOf(mapOf("id" to "tool", "type" to "commandExecution", "command" to "pwd")),
            "events" to listOf(mapOf("threadId" to "s", "turnId" to "t", "item" to mapOf(
                "id" to "tool", "type" to "command_execution", "command" to "", "status" to "completed", "output" to "/workspace"))))
        val item = RemoteHistoryCompatibility.items(turn).single()
        assertEquals("pwd", item["command"])
        assertEquals("s", item["threadId"])
        assertEquals("t", item["turnId"])
        assertEquals("completed", item["status"])
    }

    @Test fun contentImportHandlesDataUrlsBase64AndLocalImagesWithoutLosingText() {
        val content = RemoteHistoryUserContent.extract(listOf(
            mapOf("type" to "input_text", "text" to "Look\n\n"),
            mapOf("type" to "image", "base64" to "AAAA", "mimeType" to "jpeg"),
            mapOf("type" to "image", "source" to mapOf("path" to "/tmp/photo.webp")),
        ))
        assertEquals("Look\n\n", content.text)
        assertEquals("data:image/jpeg;base64,AAAA", content.attachments[0]["dataUrl"])
        assertEquals("image.jpg", content.attachments[0]["name"])
        assertEquals("/tmp/photo.webp", content.attachments[1]["path"])
        assertEquals("photo.webp", content.attachments[1]["name"])
    }

    @Test fun normalAcpLoadResponsesAreUntouched() {
        val response = mapOf("sessionId" to "s", "configOptions" to listOf("option"))
        assertSame(response, RemoteHistoryCompatibility.normalizeResponse(response))
    }
}
