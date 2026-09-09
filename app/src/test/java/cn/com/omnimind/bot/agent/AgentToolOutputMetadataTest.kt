package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AgentToolOutputMetadataTest {
    @Test fun cursorValuesRemainExactAndBodiesAreNotExcerpted() {
        val payload = JsonObject(mapOf("content" to JsonPrimitive("private-body".repeat(10000)),
            "nextOffset" to JsonPrimitive(65536), "nextCursor" to JsonPrimitive("opaque+/=cursor"),
            "hasMore" to JsonPrimitive(true), "path" to JsonPrimitive("/workspace/doc.html")))
        val wire = JsonObject(mapOf("rawResultJson" to JsonPrimitive(payload.toString())))
        val projected = Json.parseToJsonElement(requireNotNull(toolOutputMetadata(wire.toString())))
            .jsonObject["rawResultJson"]!!.jsonObject
        assertEquals(payload["nextOffset"],projected["nextOffset"])
        assertEquals(payload["nextCursor"],projected["nextCursor"])
        assertEquals(payload["hasMore"],projected["hasMore"])
        assertFalse(projected.containsKey("content"))
    }
    @Test fun oversizedCursorIsOmittedRatherThanAltered() {
        val wire = JsonObject(mapOf("nextCursor" to JsonPrimitive("z".repeat(513)), "hasMore" to JsonPrimitive(true)))
        val projected=Json.parseToJsonElement(requireNotNull(toolOutputMetadata(wire.toString()))).jsonObject
        assertFalse(projected.containsKey("nextCursor"))
        assertEquals(wire["hasMore"],projected["hasMore"])
    }
    @Test fun malformedPlainAndVeryLargeOutputsKeepReferenceOnly() {
        for (text in listOf("plain output", "{bad", "x".repeat(512*1024+1))) assertNull(toolOutputMetadata(text))
    }
    @Test fun metadataSizeAndDepthAreBounded() {
        val fields=(0..1000).associate { "key$it" to JsonPrimitive("value".repeat(100)) }
        val projected=requireNotNull(toolOutputMetadata(JsonObject(fields).toString()))
        assertTrue(projected.length <= 4096)
        val exactEdge=(0..6).associate { "a$it" to JsonPrimitive("x".repeat(512)) } +
            ("a7" to JsonPrimitive("x".repeat(448)))
        assertTrue(requireNotNull(toolOutputMetadata(JsonObject(exactEdge).toString())).length <= 4096)
        val deep="{\"nested\":".repeat(1000)+"0"+"}".repeat(1000)
        assertNull(toolOutputMetadata(deep))
    }
}
