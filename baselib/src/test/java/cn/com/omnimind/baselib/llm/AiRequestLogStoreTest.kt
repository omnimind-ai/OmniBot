package cn.com.omnimind.baselib.llm

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRequestLogStoreTest {
    private val listType = object : TypeToken<List<AiRequestLogEntry>>() {}.type

    @Test
    fun `legacy request and response bodies are replaced by metadata`() {
        val raw =
            """
                [{
                  "a":"legacy-1",
                  "b":123,
                  "c":"Chat",
                  "d":"test-model",
                  "f":"https://example.invalid/v1/chat?api_key=fake-value",
                  "k":"{\"private\":\"request-body\"}",
                  "l":"{\"private\":\"response-body\"}",
                  "n":0,
                  "o":0
                }]
            """.trimIndent()

        val scrubbed = AiRequestLogStore.scrubLegacyContentJson(raw)
        val entries: List<AiRequestLogEntry> = Gson().fromJson(requireNotNull(scrubbed), listType)
        val entry = entries.single()

        assertEquals("legacy-1", entry.id)
        assertEquals("Chat", entry.label)
        assertEquals("test-model", entry.model)
        assertEquals("", entry.requestJson)
        assertEquals("", entry.responseJson)
        assertTrue(entry.requestSizeBytes > 0)
        assertTrue(entry.responseSizeBytes > 0)
    }

    @Test
    fun `malformed legacy log fails closed instead of echoing input`() {
        assertNull(AiRequestLogStore.scrubLegacyContentJson("not valid json"))
    }
}
