package cn.com.omnimind.bot.agent.projection

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Port of ui/test/services/agent_event_reducer_test.dart, lines 1451-2905.
 */
class AgentEventReducerTestB {
    private lateinit var reducer: AgentEventReducer
    private lateinit var runtime: ChatConversationRuntimeState

    @Before
    fun setUp() {
        reducer = AgentEventReducer()
        runtime = ChatConversationRuntimeState(conversationId = 42, mode = CHAT_RUNTIME_MODE_AGENT)
    }

    @After
    fun tearDown() {
        runtime.dispose()
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): JsonMap = this as JsonMap

    /** Dart `{...base, 'params': {...base.params, 'update': {...base.params.update, ...overrides}}}`. */
    private fun withUpdate(base: JsonMap, vararg overrides: Pair<String, Any?>): JsonMap {
        val copy = deepCopyMap(base)
        val params = copy["params"].asMap()
        val update = params["update"].asMap()
        for ((k, v) in overrides) update[k] = v
        return copy
    }

    /** Dart `{...base, 'params': {...base.params, 'update': replacement}}`. */
    private fun replaceUpdate(base: JsonMap, replacement: JsonMap): JsonMap {
        val copy = deepCopyMap(base)
        copy["params"].asMap()["update"] = replacement
        return copy
    }

    /** Dart `{...base, 'params': {...base.params, ...overrides}}`. */
    private fun withParams(base: JsonMap, vararg overrides: Pair<String, Any?>): JsonMap {
        val copy = deepCopyMap(base)
        val params = copy["params"].asMap()
        for ((k, v) in overrides) params[k] = v
        return copy
    }

    private fun turnStarted(turnId: String) = jsonMapOf(
        "message" to jsonMapOf(
            "method" to "turn/started",
            "params" to jsonMapOf("turnId" to turnId),
        ),
    )

    private fun isDartInt(value: Any?): Boolean = value is Int || value is Long

    @Test
    fun `late output from an older turn cannot reclaim the active turn`() {
        reducer.reduce(runtime, turnStarted("turn-old"))
        // Model the valid post-terminal hand-off without allowing a second
        // turn/started event to overwrite an active turn.
        runtime.currentDispatchTurnId = "turn-new"
        runtime.lastAgentTurnId = "turn-new"
        runtime.activeAcpTurnId = "turn-new"
        runtime.isAiResponding = true

        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/agentMessage/delta",
                    "params" to jsonMapOf(
                        "turnId" to "turn-old",
                        "itemId" to "old-message",
                        "delta" to "迟到的旧输出",
                    ),
                ),
            ),
        )

        assertEquals("turn-new", runtime.currentDispatchTurnId)
        assertTrue(runtime.messages.isEmpty())
    }

    @Test
    fun `turn started without an id does not invent an ACP turn identity`() {
        runtime.activeRunId = "local-run-without-wire-id"
        runtime.currentDispatchTurnId = "local-run-without-wire-id"
        runtime.isAiResponding = true

        val result = reducer.reduce(
            runtime,
            jsonMapOf("method" to "turn/started", "params" to jsonMapOf()),
        )

        assertTrue(result.handled)
        assertNull(runtime.activeAcpTurnId)
        assertEquals("local-run-without-wire-id", runtime.activeRunId)
        assertEquals("local-run-without-wire-id", runtime.currentDispatchTurnId)
        assertTrue(runtime.isAiResponding)
    }

    @Test
    fun `turn-scoped ACP update without a turn id is ignored`() {
        runtime.currentDispatchTurnId = "turn-active"
        runtime.lastAgentTurnId = "turn-active"
        runtime.isAiResponding = true
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-1",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-1",
                        "content" to jsonMapOf("type" to "text", "text" to "无法归属"),
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        assertTrue(runtime.messages.isEmpty())
        assertEquals("turn-active", runtime.currentDispatchTurnId)
    }

    @Test
    fun `uses the host prompt reservation for an ACP update without turn id`() {
        runtime.currentDispatchTurnId = "local-reserved-turn"
        runtime.lastAgentTurnId = "local-reserved-turn"
        runtime.isAiResponding = true
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "allowImplicitTurnAdmission" to true,
                "params" to jsonMapOf(
                    "sessionId" to "session-1",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-1",
                        "content" to jsonMapOf("type" to "text", "text" to "通过宿主 reservation 归属"),
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        assertEquals("通过宿主 reservation 归属", runtime.messages.single().text)
        assertEquals("local-reserved-turn", runtime.currentDispatchTurnId)
        assertNull(runtime.activeAcpTurnId)
    }

    @Test
    fun `late completion from an older turn does not clear the newer turn`() {
        reducer.reduce(runtime, turnStarted("turn-old"))
        runtime.currentDispatchTurnId = "turn-new"
        runtime.lastAgentTurnId = "turn-new"
        runtime.activeAcpTurnId = "turn-new"
        runtime.isAiResponding = true
        assertEquals("turn-new", runtime.currentDispatchTurnId)
        assertTrue(runtime.isAiResponding)

        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "turn/completed",
                    "params" to jsonMapOf("turnId" to "turn-old"),
                ),
            ),
        )

        assertEquals("turn-new", runtime.currentDispatchTurnId)
        assertEquals("turn-new", runtime.lastAgentTurnId)
        assertTrue(runtime.isAiResponding)
    }

    private fun chunkEnvelope(text: String) = jsonMapOf(
        "message" to jsonMapOf(
            "method" to "session/update",
            "turnId" to "turn-1",
            "params" to jsonMapOf(
                "sessionId" to "session-1",
                "turnId" to "turn-1",
                "update" to jsonMapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "message-1",
                    "content" to jsonMapOf("type" to "text", "text" to text),
                ),
            ),
        ),
    )

    @Test
    fun `ignores ACP updates that arrive after their turn completed`() {
        reducer.reduce(runtime, chunkEnvelope("首段"))
        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "end_turn",
        )

        reducer.reduce(runtime, chunkEnvelope("迟到尾帧"))

        assertFalse(runtime.isAiResponding)
        assertEquals("首段", runtime.messages.single().text)
    }

    @Test
    fun `maps reasoning deltas into deep thinking card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/reasoning/textDelta",
                    "params" to jsonMapOf("turnId" to "turn-1", "delta" to "thinking"),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("deep_thinking", cardData["type"])
        assertEquals("thinking", cardData["thinkingContent"])
        assertEquals(true, cardData["isLoading"])
    }

    @Test
    fun `maps ACP agent thought chunks into deep thinking card`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "turnId" to "turn-claude",
                "message" to jsonMapOf(
                    "method" to "item/reasoning/delta",
                    "params" to jsonMapOf(
                        "turnId" to "turn-claude",
                        "itemId" to "claude-message-1",
                        "delta" to "先确认用户消息与当前轮次",
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        val message = runtime.messages.single()
        assertEquals("claude-message-1-agent-thinking", message.id)
        assertEquals("deep_thinking", message.cardData?.get("type"))
        assertEquals("turn-claude", message.cardData?.get("taskID"))
        assertEquals("先确认用户消息与当前轮次", message.cardData?.get("thinkingContent"))
    }

    @Test
    fun `projects structured ACP reasoning into the shared thinking card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-structured-thinking",
                "params" to jsonMapOf(
                    "sessionId" to "session-structured-thinking",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_thought_chunk",
                        "messageId" to "thought-structured",
                        "content" to jsonMapOf("type" to "text", "text" to ""),
                        "_meta" to jsonMapOf(
                            "cn.com.omnimind.agent" to jsonMapOf(
                                "reasoning" to jsonMapOf(
                                    "taskDescription" to "检查 ACP 投影是否完整",
                                    "subTasks" to listOf("保留工具结果", "渲染统一卡片"),
                                    "preparation" to "先确认会话归属",
                                    "taskTitle" to "统一展示层检查",
                                    "memoryActions" to listOf("保留旧卡片字段"),
                                    "stage" to "planning",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("deep_thinking", cardData["type"])
        val thinking = cardData["thinkingContent"] as String
        assertTrue(thinking.contains("检查 ACP 投影是否完整"))
        assertTrue(thinking.contains("统一展示层检查"))
        assertTrue(thinking.contains("保留工具结果"))
        assertTrue(thinking.contains("先确认会话归属"))
        assertTrue(thinking.contains("保留旧卡片字段"))
        assertEquals("统一展示层检查", cardData["taskTitle"])
        assertEquals(listOf("保留工具结果", "渲染统一卡片"), cardData["subTasks"])
        assertEquals(listOf("保留旧卡片字段"), cardData["memoryActions"])
    }

    @Test
    fun `projects plain ACP reasoning metadata from another namespace`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-generic-reasoning",
                "params" to jsonMapOf(
                    "sessionId" to "session-generic-reasoning",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_thought_chunk",
                        "messageId" to "thought-generic",
                        "content" to jsonMapOf("type" to "text", "text" to ""),
                        "_meta" to jsonMapOf(
                            "com.example.acp" to jsonMapOf(
                                "thinking" to jsonMapOf(
                                    "text" to "来自 ACP Harness 的推理摘要",
                                    "stage" to "planning",
                                    "summary" to "已完成计划整理",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("来自 ACP Harness 的推理摘要", cardData["thinkingContent"])
        assertEquals(1, cardData["stage"])
        assertEquals("已完成计划整理", cardData["reasoningSummary"])
    }

    @Test
    fun `routes ACP metadata media and artifacts through shared cards`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-presentation-resources",
                "params" to jsonMapOf(
                    "sessionId" to "session-presentation-resources",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-presentation-resources",
                        "content" to jsonMapOf("type" to "text", "text" to ""),
                        "_meta" to jsonMapOf(
                            "com.example.acp" to jsonMapOf(
                                "media" to listOf(
                                    jsonMapOf(
                                        "imageDataUrl" to "data:image/png;base64,ACP",
                                        "title" to "ACP image",
                                    ),
                                ),
                                "artifacts" to listOf(
                                    jsonMapOf(
                                        "uri" to "workspace://acp-result.md",
                                        "title" to "ACP result",
                                        "mimeType" to "text/markdown",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(
            runtime.messages.any { message ->
                message.cardData?.get("toolType") == "image" &&
                    message.cardData?.get("imageDataUrl") == "data:image/png;base64,ACP"
            },
        )
        assertTrue(
            runtime.messages.any { message ->
                message.cardData?.get("type") == "artifact_card" &&
                    (message.cardData?.get("artifact") as? Map<*, *>)?.get("uri") ==
                    "workspace://acp-result.md"
            },
        )
    }

    @Test
    fun `does not render an empty ACP thought start as a blank card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-thinking-start",
                "params" to jsonMapOf(
                    "sessionId" to "session-thinking-start",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_thought_chunk",
                        "messageId" to "thought-start",
                        "content" to jsonMapOf("type" to "text", "text" to ""),
                        "_meta" to jsonMapOf(
                            "cn.com.omnimind.agent" to jsonMapOf(
                                "reasoning" to jsonMapOf("stage" to "thinking"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(runtime.messages.isEmpty())
    }

    @Test
    fun `retains reasoning metadata from an empty ACP start`() {
        val base = jsonMapOf(
            "method" to "session/update",
            "turnId" to "turn-reasoning-metadata",
            "params" to jsonMapOf(
                "sessionId" to "session-reasoning-metadata",
                "update" to jsonMapOf(
                    "sessionUpdate" to "agent_thought_chunk",
                    "messageId" to "thought-metadata",
                    "content" to jsonMapOf("type" to "text", "text" to ""),
                    "_meta" to jsonMapOf(
                        "cn.com.omnimind.agent" to jsonMapOf(
                            "reasoning" to jsonMapOf(
                                "taskTitle" to "检查工作区",
                                "subTasks" to listOf("读取状态"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        // Dart `base` is a const map; hand the reducer a copy so it stays pristine.
        reducer.reduce(runtime, deepCopyMap(base))
        assertEquals("检查工作区", runtime.messages.single().cardData?.get("taskTitle"))
        assertEquals(listOf("读取状态"), runtime.messages.single().cardData?.get("subTasks"))

        reducer.reduce(
            runtime,
            withUpdate(
                base,
                "content" to jsonMapOf("type" to "text", "text" to "开始检查"),
                "_meta" to null,
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertTrue((card["thinkingContent"] as String).contains("开始检查"))
        assertEquals("检查工作区", card["taskTitle"])
        assertEquals(listOf("读取状态"), card["subTasks"])
    }

    @Test
    fun `keeps ACP turn usage that arrives before the assistant text`() {
        val base = jsonMapOf(
            "method" to "session/update",
            "turnId" to "turn-usage-before-text",
            "params" to jsonMapOf(
                "sessionId" to "session-usage-before-text",
                "update" to jsonMapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "message-usage-before-text",
                    "content" to jsonMapOf("type" to "text", "text" to ""),
                    "_meta" to jsonMapOf(
                        "cn.com.omnimind.agent" to jsonMapOf(
                            "usage" to jsonMapOf(
                                "turnUsage" to jsonMapOf("ctx" to 128, "in" to 100, "out" to 28, "cache" to 12),
                            ),
                        ),
                    ),
                ),
            ),
        )

        reducer.reduce(runtime, deepCopyMap(base))
        assertTrue(runtime.messages.isEmpty())

        reducer.reduce(
            runtime,
            withUpdate(
                base,
                "content" to jsonMapOf("type" to "text", "text" to "正文"),
                "_meta" to null,
            ),
        )

        assertEquals("正文", runtime.messages.single().text)
        assertEquals(
            mapOf("ctx" to 128, "in" to 100, "out" to 28, "cache" to 12),
            runtime.messages.single().turnUsage,
        )
    }

    @Test
    fun `projects standard ACP usage updates into conversation context usage`() {
        runtime.conversation = ConversationPayload.create(
            id = 42,
            mode = ConversationModes.AGENT,
            title = "ACP usage",
            status = 0,
            messageCount = 0,
            createdAt = 1,
            updatedAt = 1,
        )

        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-usage",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "usage_update",
                        "used" to 12345,
                        "size" to 128000,
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        val conversation = ConversationPayload(runtime.conversation!!)
        assertEquals(12345, conversation.latestPromptTokens)
        assertEquals(128000, conversation.promptTokenThreshold)
        assertTrue(conversation.latestPromptTokensUpdatedAt > 0)
    }

    @Test
    fun `projects ACP turn usage into the shared assistant footer`() {
        val base = jsonMapOf(
            "method" to "session/update",
            "turnId" to "turn-usage-footer",
            "params" to jsonMapOf(
                "sessionId" to "session-usage-footer",
                "update" to jsonMapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "message-usage-footer",
                ),
            ),
        )

        reducer.reduce(
            runtime,
            withUpdate(base, "content" to jsonMapOf("type" to "text", "text" to "带用量的统一正文")),
        )
        reducer.reduce(
            runtime,
            withUpdate(
                base,
                "content" to jsonMapOf("type" to "text", "text" to ""),
                "_meta" to jsonMapOf(
                    "cn.com.omnimind.agent" to jsonMapOf(
                        "usage" to jsonMapOf(
                            "latestPromptTokens" to 100,
                            "promptTokenThreshold" to 128000,
                            "turnUsage" to jsonMapOf("ctx" to 100, "in" to 100, "out" to 20, "cache" to 10),
                        ),
                    ),
                ),
            ),
        )

        val message = runtime.messages.single()
        assertEquals("带用量的统一正文", message.text)
        assertEquals(mapOf("ctx" to 100, "in" to 100, "out" to 20, "cache" to 10), message.turnUsage)
    }

    private fun planEvent(update: JsonMap) = jsonMapOf(
        "method" to "session/update",
        "turnId" to "turn-plan-v2",
        "params" to jsonMapOf("sessionId" to "session-plan-v2", "update" to update),
    )

    @Test
    fun `ACP v2 plan update and removal share the plan card route`() {
        reducer.reduce(
            runtime,
            planEvent(
                jsonMapOf(
                    "sessionUpdate" to "plan_update",
                    "plan" to jsonMapOf("type" to "markdown", "id" to "plan-1", "content" to "# Plan\n\n1. inspect"),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        assertEquals("plan", runtime.messages.single().cardData?.get("toolType"))
        assertEquals("plan-1", runtime.messages.single().cardData?.get("planId"))
        assertTrue((runtime.messages.single().cardData?.get("summary") as String).contains("inspect"))
        val planCardId = runtime.messages.single().id

        reducer.reduce(
            runtime,
            planEvent(
                jsonMapOf(
                    "sessionUpdate" to "plan_update",
                    "plan" to jsonMapOf(
                        "type" to "markdown",
                        "id" to "plan-1",
                        "content" to "# Plan\n\n1. inspect\n2. implement",
                    ),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        assertEquals(planCardId, runtime.messages.single().id)
        assertTrue((runtime.messages.single().cardData?.get("summary") as String).contains("implement"))

        reducer.reduce(runtime, planEvent(jsonMapOf("sessionUpdate" to "plan_removed", "id" to "plan-1")))

        assertTrue(runtime.messages.isEmpty())
    }

    private fun toolEvent(turnId: String, sessionId: String, update: JsonMap) = jsonMapOf(
        "method" to "session/update",
        "turnId" to turnId,
        "params" to jsonMapOf("sessionId" to sessionId, "update" to update),
    )

    @Test
    fun `standard ACP image tool content reaches the shared image card`() {
        reducer.reduce(
            runtime,
            toolEvent(
                "turn-image-content",
                "session-image-content",
                jsonMapOf(
                    "sessionUpdate" to "tool_call_update",
                    "toolCallId" to "image-1",
                    "kind" to "other",
                    "title" to "Generated image",
                    "status" to "completed",
                    "content" to listOf(
                        jsonMapOf(
                            "type" to "content",
                            "content" to jsonMapOf("type" to "image", "data" to "AAAA", "mimeType" to "image/png"),
                        ),
                    ),
                    "rawOutput" to jsonMapOf("toolType" to "context"),
                ),
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("image", card["toolType"])
        assertEquals("data:image/png;base64,AAAA", card["imageDataUrl"])
        assertTrue((card["resultPreviewJson"] as String).contains("image/png"))
    }

    @Test
    fun `official ACP read kind uses the shared workspace tool route`() {
        reducer.reduce(
            runtime,
            toolEvent(
                "turn-read-kind",
                "session-read-kind",
                jsonMapOf(
                    "sessionUpdate" to "tool_call",
                    "toolCallId" to "read-1",
                    "kind" to "read",
                    "title" to "读取文件",
                    "status" to "in_progress",
                    "rawInput" to jsonMapOf("path" to "/workspace/README.md"),
                ),
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("workspace", card["toolType"])
        assertEquals("read-1", card["toolCallId"])
    }

    @Test
    fun `official ACP ToolKind values share the same card route mapping`() {
        val cases = listOf(
            "read" to "workspace",
            "edit" to "file",
            "delete" to "file",
            "move" to "file",
            "search" to "search",
            "execute" to "terminal",
            "fetch" to "browser",
            "think" to "plan",
        )

        for ((kind, _) in cases) {
            val callId = "kind-$kind"
            reducer.reduce(
                runtime,
                toolEvent(
                    "turn-official-kinds",
                    "session-official-kinds",
                    jsonMapOf(
                        "sessionUpdate" to "tool_call",
                        "toolCallId" to callId,
                        "kind" to kind,
                        "title" to "ACP $kind",
                        "status" to "in_progress",
                    ),
                ),
            )
        }

        for ((kind, expectedToolType) in cases) {
            val card = runtime.messages.single { message ->
                message.cardData?.get("toolCallId") == "kind-$kind"
            }
            assertEquals("kind=$kind", expectedToolType, card.cardData?.get("toolType"))
        }
    }

    @Test
    fun `sparse ACP tool updates keep the initial official kind and title`() {
        val base = toolEvent(
            "turn-sparse-tool-update",
            "session-sparse-tool-update",
            jsonMapOf(
                "sessionUpdate" to "tool_call",
                "toolCallId" to "sparse-read",
                "kind" to "read",
                "title" to "读取 README",
                "status" to "in_progress",
                "rawInput" to jsonMapOf("path" to "/workspace/README.md"),
            ),
        )
        reducer.reduce(runtime, base)
        reducer.reduce(
            runtime,
            toolEvent(
                "turn-sparse-tool-update",
                "session-sparse-tool-update",
                jsonMapOf(
                    "sessionUpdate" to "tool_call_update",
                    "toolCallId" to "sparse-read",
                    "status" to "completed",
                ),
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("workspace", card["toolType"])
        assertEquals("读取 README", card["toolTitle"])
        assertEquals("success", card["status"])
    }

    @Test
    fun `official ACP tool media content uses the shared media card route`() {
        reducer.reduce(
            runtime,
            toolEvent(
                "turn-tool-media-content",
                "session-tool-media-content",
                jsonMapOf(
                    "sessionUpdate" to "tool_call",
                    "toolCallId" to "media-1",
                    "kind" to "other",
                    "title" to "生成预览",
                    "status" to "completed",
                    "content" to listOf(
                        jsonMapOf(
                            "type" to "content",
                            "content" to jsonMapOf(
                                "type" to "resource",
                                "resource" to jsonMapOf(
                                    "uri" to "workspace://preview.png",
                                    "blob" to "CCCC",
                                    "mimeType" to "image/png",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("image", card["toolType"])
        assertEquals("data:image/png;base64,CCCC", card["imageDataUrl"])
    }

    @Test
    fun `projects ACP tool resource links into the shared artifact card`() {
        reducer.reduce(
            runtime,
            toolEvent(
                "turn-tool-resource",
                "session-tool-resource",
                jsonMapOf(
                    "sessionUpdate" to "tool_call_update",
                    "toolCallId" to "tool-resource-1",
                    "kind" to "other",
                    "title" to "生成文件",
                    "status" to "completed",
                    "content" to listOf(
                        jsonMapOf(
                            "type" to "content",
                            "content" to jsonMapOf(
                                "type" to "resource_link",
                                "name" to "result.md",
                                "uri" to "workspace://result.md",
                                "mimeType" to "text/markdown",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val artifactCard = runtime.messages.single { message ->
            message.cardData?.get("type") == "artifact_card"
        }
        val artifact = artifactCard.cardData?.get("artifact") as Map<*, *>
        assertEquals("workspace://result.md", artifact["uri"])
        assertEquals("result.md", artifact["fileName"])
        assertEquals("text/markdown", artifact["mimeType"])
    }

    @Test
    fun `standard ACP terminal content keeps the shared terminal session`() {
        reducer.reduce(
            runtime,
            toolEvent(
                "turn-terminal-content",
                "session-terminal-content",
                jsonMapOf(
                    "sessionUpdate" to "tool_call_update",
                    "toolCallId" to "terminal-content-1",
                    "kind" to "execute",
                    "title" to "Run tests",
                    "status" to "completed",
                    "content" to listOf(jsonMapOf("type" to "terminal", "terminalId" to "shell-1")),
                ),
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("terminal", card["toolType"])
        assertEquals("shell-1", card["terminalSessionId"])
        assertTrue((card["resultPreviewJson"] as String).contains("terminalId"))
    }

    @Test
    fun `projects ACP retry state into the next shared assistant message`() {
        val base = jsonMapOf(
            "method" to "session/update",
            "turnId" to "turn-retry",
            "params" to jsonMapOf(
                "sessionId" to "session-retry",
                "update" to jsonMapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "message-retry",
                    "content" to jsonMapOf("type" to "text", "text" to ""),
                    "_meta" to jsonMapOf(
                        "cn.com.omnimind.agent" to jsonMapOf(
                            "retry" to jsonMapOf(
                                "count" to 1,
                                "maxRetries" to 3,
                                "delayMs" to 1000,
                                "message" to "请求失败，正在重试",
                                "reason" to "timeout",
                            ),
                        ),
                    ),
                ),
            ),
        )
        reducer.reduce(runtime, deepCopyMap(base))

        assertTrue(runtime.messages.isEmpty())
        assertFalse(runtime.isAiResponding)
        assertNull(runtime.currentDispatchTurnId)

        reducer.reduce(
            runtime,
            replaceUpdate(
                base,
                jsonMapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "message-retry",
                    "content" to jsonMapOf("type" to "text", "text" to "恢复后的统一正文"),
                ),
            ),
        )

        val message = runtime.messages.single()
        assertEquals("恢复后的统一正文", message.text)
        assertEquals(true, message.content?.get("agentRetrying"))
        assertEquals(1, message.content?.get("agentRetryCount"))
        assertEquals(3, message.content?.get("agentMaxRetries"))

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-retry",
            stopReason = "end_turn",
        )
        assertNull(runtime.messages.single().content?.get("agentRetrying"))
    }

    @Test
    fun `projects ACP recovery state into the existing error presentation`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-recovery",
                "params" to jsonMapOf(
                    "sessionId" to "session-recovery",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-recovery",
                        "content" to jsonMapOf("type" to "text", "text" to "网络连接中断"),
                        "_meta" to jsonMapOf(
                            "cn.com.omnimind.agent" to jsonMapOf(
                                "recovery" to jsonMapOf(
                                    "error" to "网络连接中断",
                                    "retryable" to true,
                                    "continueable" to false,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val message = runtime.messages.single()
        assertTrue(message.isError)
        assertEquals("网络连接中断", message.content?.get("agentErrorText"))
        assertEquals(true, message.content?.get("agentRetryable"))
        assertNull(message.content?.get("agentContinueable"))
    }

    @Test
    fun `does not project partial ACP recovery as a continuation action`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-partial-recovery",
                "params" to jsonMapOf(
                    "sessionId" to "session-partial-recovery",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-partial-recovery",
                        "content" to jsonMapOf("type" to "text", "text" to "半截答案"),
                        "_meta" to jsonMapOf(
                            "cn.com.omnimind.agent" to jsonMapOf(
                                "recovery" to jsonMapOf(
                                    "error" to "连接中断",
                                    "retryable" to true,
                                    "continueable" to true,
                                    "continueResumeMode" to "approximate",
                                    "persistAsError" to false,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val message = runtime.messages.single()
        assertEquals("半截答案", message.text)
        assertFalse(message.isError)
        assertNull(message.content?.get("agentContinueable"))
        assertNull(message.content?.get("agentContinueResumeMode"))
    }

    @Test
    fun `buffers ACP recovery and clarification until assistant text exists`() {
        val metadataOnly = jsonMapOf(
            "method" to "item/agentMessage/delta",
            "params" to jsonMapOf(
                "turnId" to "turn-pending-presentation",
                "entryId" to "message-pending-presentation",
                "delta" to "",
                "acpPresentation" to jsonMapOf(
                    "recovery" to jsonMapOf(
                        "error" to "需要重新连接",
                        "retryable" to true,
                        "continueable" to false,
                    ),
                    "clarification" to jsonMapOf(
                        "question" to "是否继续？",
                        "missingFields" to listOf("arguments.confirmed"),
                    ),
                ),
            ),
        )

        reducer.reduce(runtime, deepCopyMap(metadataOnly))
        assertTrue(runtime.messages.isEmpty())

        reducer.reduce(
            runtime,
            withParams(metadataOnly, "delta" to "恢复后的正文", "acpPresentation" to null),
        )

        val message = runtime.messages.single()
        assertEquals("恢复后的正文", message.text)
        assertTrue(message.isError)
        assertEquals("需要重新连接", message.content?.get("agentErrorText"))
        assertEquals(true, message.content?.get("agentClarificationRequired"))
        assertEquals("是否继续？", message.content?.get("agentClarificationQuestion"))
        assertEquals(listOf("arguments.confirmed"), message.content?.get("agentClarificationMissingFields"))
    }

    @Test
    fun `preserves ACP clarification fields on the existing assistant reply`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-clarification",
                "params" to jsonMapOf(
                    "sessionId" to "session-clarification",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-clarification",
                        "content" to jsonMapOf("type" to "text", "text" to "是否继续执行？"),
                        "_meta" to jsonMapOf(
                            "cn.com.omnimind.agent" to jsonMapOf(
                                "clarification" to jsonMapOf(
                                    "question" to "是否继续执行？",
                                    "missingFields" to listOf("arguments.confirmed"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val message = runtime.messages.single()
        assertEquals("是否继续执行？", message.text)
        assertEquals(true, message.content?.get("agentClarificationRequired"))
        assertEquals("是否继续执行？", message.content?.get("agentClarificationQuestion"))
        assertEquals(listOf("arguments.confirmed"), message.content?.get("agentClarificationMissingFields"))
    }

    @Test
    fun `ignores private automatic compaction presentation metadata`() {
        val base = jsonMapOf(
            "method" to "session/update",
            "turnId" to "turn-compaction",
            "params" to jsonMapOf(
                "sessionId" to "session-compaction",
                "update" to jsonMapOf(
                    "sessionUpdate" to "agent_thought_chunk",
                    "messageId" to "thought-compaction",
                    "content" to jsonMapOf("type" to "text", "text" to ""),
                    "_meta" to jsonMapOf(
                        "cn.com.omnimind.agent" to jsonMapOf(
                            "compaction" to jsonMapOf(
                                "status" to "compressing",
                                "trigger" to "auto",
                                "latestPromptTokens" to 126000,
                                "promptTokenThreshold" to 128000,
                            ),
                        ),
                    ),
                ),
            ),
        )
        reducer.reduce(runtime, deepCopyMap(base))

        assertTrue(runtime.messages.isEmpty())
        assertFalse(runtime.isContextCompressing)

        reducer.reduce(
            runtime,
            replaceUpdate(
                base,
                jsonMapOf(
                    "sessionUpdate" to "agent_thought_chunk",
                    "messageId" to "thought-compaction",
                    "content" to jsonMapOf("type" to "text", "text" to ""),
                    "_meta" to jsonMapOf(
                        "cn.com.omnimind.agent" to jsonMapOf(
                            "compaction" to jsonMapOf("status" to "completed"),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(runtime.messages.isEmpty())
        assertFalse(runtime.isContextCompressing)
    }

    @Test
    fun `appends multiple ACP reasoning rounds to one thinking card`() {
        val event = jsonMapOf(
            "method" to "item/reasoning/delta",
            "params" to jsonMapOf(
                "turnId" to "turn-multi-round",
                "itemId" to "thought-for-one-prompt",
            ),
        )

        reducer.reduce(runtime, withParams(event, "delta" to "第一轮思考"))
        reducer.reduce(runtime, withParams(event, "delta" to "第二轮思考"))

        val thinkingMessages = runtime.messages.filter { message ->
            message.cardData?.get("type") == "deep_thinking"
        }
        assertEquals(1, thinkingMessages.size)
        assertEquals("第一轮思考第二轮思考", thinkingMessages.single().cardData?.get("thinkingContent"))
    }

    @Test
    fun `starts a new ACP reasoning card when an explicit retry segment arrives`() {
        fun event(itemId: String, text: String, segmentIndex: Int): JsonMap = jsonMapOf(
            "method" to "item/reasoning/delta",
            "params" to jsonMapOf(
                "turnId" to "turn-retry-segment",
                "itemId" to itemId,
                "delta" to text,
                "acpPresentation" to jsonMapOf(
                    "reasoning" to jsonMapOf("segmentIndex" to segmentIndex),
                ),
            ),
        )

        reducer.reduce(runtime, event(itemId = "reason-failed", text = "失败请求的思考", segmentIndex = 0))
        reducer.reduce(runtime, event(itemId = "reason-retry", text = "成功重试的思考", segmentIndex = 1))

        val thinkingCards = runtime.messages.filter { message ->
            message.cardData?.get("type") == "deep_thinking"
        }
        assertEquals(2, thinkingCards.size)
        assertTrue(
            thinkingCards.map { it.cardData?.get("thinkingContent") }
                .containsAll(listOf("失败请求的思考", "成功重试的思考")),
        )
        assertEquals(
            false,
            thinkingCards.first { it.cardData?.get("thinkingContent") == "失败请求的思考" }
                .cardData?.get("isLoading"),
        )
    }

    private fun reasoningDelta(turnId: String, itemId: String?, delta: String) = jsonMapOf(
        "method" to "item/reasoning/textDelta",
        "params" to jsonMapOf("turnId" to turnId, "delta" to delta).apply {
            if (itemId != null) put("itemId", itemId)
        },
    )

    private fun commandStarted(turnId: String, id: String, command: String) = jsonMapOf(
        "method" to "item/started",
        "params" to jsonMapOf(
            "turnId" to turnId,
            "item" to jsonMapOf(
                "id" to id,
                "type" to "commandExecution",
                "command" to command,
                "status" to "in_progress",
            ),
        ),
    )

    @Test
    fun `keeps reasoning segments interleaved around ACP tool calls`() {
        reducer.reduce(runtime, reasoningDelta("turn-interleaved", "reason-before-tool", "先检查工作区"))
        reducer.reduce(runtime, commandStarted("turn-interleaved", "tool-1", "pwd"))
        reducer.reduce(runtime, reasoningDelta("turn-interleaved", "reason-after-tool", "根据工具结果继续判断"))
        reducer.reduce(runtime, commandStarted("turn-interleaved", "tool-2", "git status"))
        reducer.reduce(runtime, reasoningDelta("turn-interleaved", "reason-after-second-tool", "确认第二个工具结果"))
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "item/agentMessage/delta",
                "params" to jsonMapOf(
                    "turnId" to "turn-interleaved",
                    "itemId" to "answer-1",
                    "delta" to "最终答案",
                ),
            ),
        )

        // Dart: `buildAgentRunTimelineEntries(runtime.messages).single.group!`
        // then `group.allMessagesOldestFirst`. That Flutter helper is not part
        // of the projection port; its single-group precondition is that every
        // message belongs to one run, and its oldest-first order for a
        // reducer-owned run is the newest-first runtime list reversed.
        val runIds = runtime.messages.map { it.runId }.toSet()
        assertEquals("expected exactly one run group, got $runIds", 1, runIds.size)
        val allMessagesOldestFirst = runtime.messages.toList().asReversed()
        assertEquals(
            listOf(
                "deep_thinking",
                "agent_tool_summary",
                "deep_thinking",
                "agent_tool_summary",
                "deep_thinking",
                "assistant_text",
            ),
            allMessagesOldestFirst.map { message -> message.cardData?.get("type") ?: "assistant_text" },
        )
        val thinkingCards = allMessagesOldestFirst.filter { message ->
            message.cardData?.get("type") == "deep_thinking"
        }
        assertEquals(
            listOf("先检查工作区", "根据工具结果继续判断", "确认第二个工具结果"),
            thinkingCards.map { it.cardData?.get("thinkingContent") },
        )
        assertEquals(
            listOf(
                "reason-before-tool-agent-thinking",
                "reason-after-tool-agent-thinking",
                "reason-after-second-tool-agent-thinking",
            ),
            thinkingCards.map { it.id },
        )
        for (card in thinkingCards) {
            assertEquals(false, card.cardData?.get("isLoading"))
            // Dart `isA<int>()`: a Kotlin Int or Long both represent Dart int.
            assertTrue("startTime=${card.cardData?.get("startTime")}", isDartInt(card.cardData?.get("startTime")))
            assertTrue("endTime=${card.cardData?.get("endTime")}", isDartInt(card.cardData?.get("endTime")))
        }
    }

    @Test
    fun `segments ACP reasoning without message ids across tool calls`() {
        reducer.reduce(runtime, reasoningDelta("turn-without-message-id", null, "工具前"))
        reducer.reduce(runtime, commandStarted("turn-without-message-id", "tool-without-message-id", "pwd"))
        reducer.reduce(runtime, reasoningDelta("turn-without-message-id", null, "工具后"))

        val thinkingCards = runtime.messages.toList().asReversed().filter { message ->
            message.cardData?.get("type") == "deep_thinking"
        }
        assertEquals(listOf("工具前", "工具后"), thinkingCards.map { it.cardData?.get("thinkingContent") })
        assertEquals(
            listOf(
                "turn-without-message-id-agent-thinking",
                "turn-without-message-id-agent-thinking-segment-2",
            ),
            thinkingCards.map { it.id },
        )
    }

    @Test
    fun `renders the official ACP session-update envelope`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-1",
                    "params" to jsonMapOf(
                        "sessionId" to "session-1",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "agent_message_chunk",
                            "messageId" to "message-1",
                            "content" to jsonMapOf("type" to "text", "text" to "来自标准 ACP"),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        assertEquals("来自标准 ACP", runtime.messages.single().text)
        assertEquals(2, runtime.messages.single().user)
    }

    @Test
    fun `legacy state_change cannot start a prompt without a request`() {
        val running = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-lifecycle",
                    "update" to jsonMapOf("sessionUpdate" to "state_change", "state" to "running"),
                ),
            ),
        )

        assertTrue(running.handled)
        assertNull(running.compatibilityWarning)
        assertFalse(runtime.isAiResponding)

        val idle = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-lifecycle",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "state_change",
                        "state" to "idle",
                        "stop_reason" to "end_turn",
                    ),
                ),
            ),
        )

        assertTrue(idle.handled)
        assertFalse(runtime.isAiResponding)
    }

    @Test
    fun `ignores the pre-release ACP state_update spelling`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-lifecycle-draft",
                    "update" to jsonMapOf("sessionUpdate" to "state_update", "state" to "running"),
                ),
            ),
        )

        assertTrue(result.handled)
        assertNull(result.compatibilityWarning)
        assertFalse(runtime.isAiResponding)
    }
}
