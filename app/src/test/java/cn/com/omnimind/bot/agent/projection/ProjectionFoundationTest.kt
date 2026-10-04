package cn.com.omnimind.bot.agent.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports `acp_extension_registry_test.dart` and covers the shared helpers. */
class ProjectionFoundationTest {
    @Test
    fun `keeps Xiaowan shared presentation metadata compatible`() {
        val projection = AcpExtensionRegistry.shared.project(
            jsonMapOf(
                "_meta" to jsonMapOf(
                    "cn.com.omnimind.agent" to jsonMapOf(
                        "usage" to jsonMapOf("turnUsage" to jsonMapOf("in" to 10, "out" to 4)),
                        "reasoning" to jsonMapOf("taskTitle" to "inspect"),
                    ),
                ),
            ),
        )
        assertTrue(projection.presentation["usage"] is Map<*, *>)
        assertTrue(projection.presentation["reasoning"] is Map<*, *>)
        val extension = projection.extensions["cn.com.omnimind.agent"] as Map<*, *>
        assertTrue(extension["reasoning"] is Map<*, *>)
    }

    @Test
    fun `projects common metadata aliases from another ACP namespace`() {
        val projection = AcpExtensionRegistry.shared.project(
            jsonMapOf(
                "_meta" to jsonMapOf(
                    "com.example.agent" to jsonMapOf(
                        "thinking" to jsonMapOf("taskTitle" to "plan"),
                        "context_compaction" to jsonMapOf("status" to "started"),
                        "artifacts" to listOf(jsonMapOf("uri" to "file:///tmp/result.txt")),
                    ),
                ),
            ),
        )
        assertTrue(projection.presentation["reasoning"] is Map<*, *>)
        assertTrue(projection.presentation["compaction"] is Map<*, *>)
        assertTrue(projection.presentation["artifacts"] is List<*>)
    }

    @Test
    fun `allows a Harness adapter to register a typed namespace projector`() {
        val registry = AcpExtensionRegistry(
            mapOf("com.example.typed" to { payload -> mapOf("usage" to payload["tokens"]) }),
        )
        val projection = registry.project(
            jsonMapOf("_meta" to jsonMapOf("com.example.typed" to jsonMapOf("tokens" to 42))),
        )
        assertEquals(42, projection.presentation["usage"])
        assertEquals(42, (projection.extensions["com.example.typed"] as Map<*, *>)["tokens"])
    }

    @Test
    fun `retains non-object extension payloads for forward compatibility`() {
        val projection = AcpExtensionRegistry().project(
            jsonMapOf(
                "_meta" to jsonMapOf(
                    "com.example.scalar" to "provider-progress",
                    "com.example.list" to listOf(1, 2, 3),
                ),
            ),
        )
        assertEquals("provider-progress", projection.extensions["com.example.scalar"])
        assertEquals(listOf(1, 2, 3), projection.extensions["com.example.list"])
    }

    @Test
    fun `maps legacy reasoning fields into the shared reasoning projection`() {
        val projection = AcpExtensionRegistry().project(
            jsonMapOf(
                "_meta" to jsonMapOf(
                    "com.example.agent" to jsonMapOf(
                        "deep_thinking" to "thinking",
                        "task_title" to "Inspect the repository",
                        "sub_tasks" to listOf("read", "test"),
                        "clarify" to jsonMapOf("question" to "Which file? "),
                    ),
                ),
            ),
        )
        assertEquals(
            mapOf(
                "text" to "thinking",
                "taskTitle" to "Inspect the repository",
                "subTasks" to listOf("read", "test"),
            ),
            projection.presentation["reasoning"],
        )
        assertEquals(mapOf("question" to "Which file? "), projection.presentation["clarification"])
    }

    @Test
    fun `dart json encodes like dart convert`() {
        assertEquals(
            """{"a":"x/y","b":[1,2.5,1.0,true,null],"c":"\"\\\n\t\u0001é😀"}""",
            DartJson.encode(
                linkedMapOf(
                    "a" to "x/y",
                    "b" to listOf(1, 2.5, 1.0, true, null),
                    "c" to "\"\\\n\t\u0001é😀",
                ),
            ),
        )
        val decoded = DartJson.decode("""{"n":3,"big":12345678901,"d":1.50,"e":1e2,"s":"é\/"}""") as Map<*, *>
        assertEquals(3, decoded["n"])
        assertEquals(12345678901L, decoded["big"])
        assertEquals(1.5, decoded["d"])
        assertEquals(100.0, decoded["e"])
        assertEquals("é/", decoded["s"])
        assertEquals("1e+21", dartDoubleToString(1e21))
        assertEquals("100.0", dartDoubleToString(100.0))
        assertEquals("0.1", dartDoubleToString(0.1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dart json rejects malformed input`() {
        DartJson.decode("{\"a\":")
    }

    @Test
    fun `chat message round trips and sanitizes transport frames`() {
        val message = ChatMessage(
            id = "m1",
            type = 2,
            user = 3,
            content = jsonMapOf("cardData" to jsonMapOf("type" to "deep_thinking", "taskID" to "t1"), "id" to "m1"),
            streamMeta = jsonMapOf("turnId" to "turn-1", "toolCallId" to " "),
            createAtMillis = 1_700_000_000_123L,
        )
        val restored = ChatMessage.fromJson(DartJson.decode(DartJson.encode(message.toJson())) as Map<String, Any?>)
        assertEquals(message, restored)
        assertEquals("t1", restored.runId)
        assertEquals("turn-1", restored.turnId)
        assertNull(restored.toolCallId)
        assertEquals("m1", restored.cardId)

        val sanitized = ChatMessage.fromJson(
            jsonMapOf(
                "id" to "a",
                "type" to 1,
                "user" to 2,
                "content" to jsonMapOf(
                    "text" to """{"choices":[{"delta":{"content":"你好"}}]}{"choices":[{"delta":{"content":"!"}}]} tail""",
                ),
                "createAt" to 5,
            ),
        )
        // Dart moves the cursor to the next non-whitespace before the tail.
        assertEquals("你好!tail", sanitized.text)
        assertEquals(5L, sanitized.createAtMillis)
        assertEquals(2, ChatMessage.fromJson(jsonMapOf("id" to "x", "type" to 2.0)).type)
    }

    @Test
    fun `reads ACP identities through bridge envelopes`() {
        val event = jsonMapOf(
            "payload" to jsonMapOf(
                "message" to jsonMapOf(
                    "params" to jsonMapOf("sessionId" to " s1 ", "turnId" to "t1", "allowImplicitTurnAdmission" to true),
                ),
            ),
        )
        assertEquals("s1", acpEventSessionId(event))
        assertEquals("t1", acpEventTurnId(event))
        assertTrue(acpEventAllowsImplicitTurnAdmission(event))
        assertFalse(acpEventIsLegacyCompatibilityShape(event))
        assertTrue(acpEventIsLegacyCompatibilityShape(jsonMapOf("taskId" to "x")))
        assertEquals("s:t", acpTurnKey(sessionId = "s", turnId = "t"))
        assertEquals("t", acpTurnKey(turnId = " t "))
        assertEquals("", acpTurnKey(sessionId = "s"))
        val identity = AgentToolIdentity.fromMaps(raw = jsonMapOf("call_id" to "c/1"), sessionId = "s 1")
        assertEquals("c/1", identity.toolCallId)
        assertEquals("tool:s_1:c_1:command", identity.cardId(suffix = "command", fallback = "f"))
    }

    @Test
    fun `stream meta normalizes only provided values`() {
        assertNull(ensureAgentStreamMessageMeta(null))
        assertEquals(
            mapOf("seq" to 2, "parentTaskId" to "task", "isFinal" to false),
            ensureAgentStreamMessageMeta(null, seq = 2, parentTaskId = " task ", runId = " "),
        )
        assertEquals(
            true,
            ensureAgentStreamMessageMeta(mapOf("isFinal" to true), kind = "text")!!["isFinal"],
        )
    }
}
