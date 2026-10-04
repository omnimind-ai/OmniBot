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
 * Port of `ui/test/services/agent_event_reducer_test.dart`, lines 1-1450.
 * Same inputs, same assertions, same test names as the Dart acceptance tests.
 */
class AgentEventReducerTestA {
    private lateinit var reducer: AgentEventReducer
    private lateinit var runtime: ChatConversationRuntimeState
    private val extraRuntimes = ArrayList<ChatConversationRuntimeState>()

    @Before
    fun setUp() {
        reducer = AgentEventReducer()
        runtime = ChatConversationRuntimeState(conversationId = 42, mode = CHAT_RUNTIME_MODE_AGENT)
    }

    @After
    fun tearDown() {
        runtime.dispose()
        extraRuntimes.forEach { it.dispose() }
    }

    // --- for (final reason in ['cancelled', 'error', 'end_turn']) ---------

    private fun officialSettlesOnlyUnresolvedRequests(reason: String) {
        runtime.currentDispatchTurnId = "turn-1"
        runtime.activeAcpTurnId = "turn-1"
        runtime.isAiResponding = true
        for ((key, value) in linkedMapOf(
            "waiting" to "pending",
            "answered" to "accepted",
            "other-turn" to "pending",
        )) {
            runtime.messages.add(
                ChatMessage(
                    id = key,
                    type = 2,
                    user = 3,
                    content = jsonMapOf(
                        "cardData" to jsonMapOf(
                            "type" to "agent_request",
                            "requestId" to key,
                            "status" to value,
                        ),
                    ),
                    streamMeta = jsonMapOf(
                        "parentTaskId" to if (key == "other-turn") "turn-2" else "turn-1",
                    ),
                ),
            )
        }
        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = reason,
            error = if (reason == "error") "Transport failed" else null,
        )
        val byId = runtime.messages.associateBy { it.id }
        assertEquals(reason, "cancelled", byId["waiting"]!!.cardData!!["status"])
        assertEquals(reason, "accepted", byId["answered"]!!.cardData!!["status"])
        assertEquals(reason, "pending", byId["other-turn"]!!.cardData!!["status"])
    }

    @Test
    fun `official cancelled settles only unresolved requests owned by that turn`() =
        officialSettlesOnlyUnresolvedRequests("cancelled")

    @Test
    fun `official error settles only unresolved requests owned by that turn`() =
        officialSettlesOnlyUnresolvedRequests("error")

    @Test
    fun `official end_turn settles only unresolved requests owned by that turn`() =
        officialSettlesOnlyUnresolvedRequests("end_turn")

    @Test
    fun `compact Xiaowan result keeps file content through replay and history`() {
        val body = "正文😀".repeat(16000)
        val event = jsonMapOf(
            "method" to "session/update",
            "turnId" to "compact-turn",
            "params" to jsonMapOf(
                "sessionId" to "compact-session",
                "update" to jsonMapOf(
                    "sessionUpdate" to "tool_call_update",
                    "toolCallId" to "compact-read",
                    "kind" to "read",
                    "title" to "Read document",
                    "status" to "completed",
                    "rawInput" to jsonMapOf("path" to "/workspace/data.json"),
                    "rawOutput" to jsonMapOf(
                        "toolName" to "custom_document_tool",
                        "toolType" to "context",
                        "summary" to "Read document",
                        "success" to true,
                        "result" to jsonMapOf("content" to body, "hasMore" to true, "nextOffset" to 64000),
                    ),
                ),
            ),
        )
        reducer.reduce(runtime, event)
        reducer.reduce(runtime, event)
        val card = runtime.messages.single().cardData!!
        assertEquals("compact-read", card["toolCallId"])
        assertEquals("workspace", card["toolType"])
        assertEquals("success", card["status"])
        val preview = DartJson.decode(card["resultPreviewJson"] as String) as Map<*, *>
        assertEquals(body, preview["content"])
        assertEquals(64000, preview["nextOffset"])
        @Suppress("UNCHECKED_CAST")
        val restored = ChatMessage.fromJson(
            DartJson.decode(DartJson.encode(runtime.messages.single().toJson())) as Map<String, Any?>,
        )
        assertEquals(card["resultPreviewJson"], restored.cardData!!["resultPreviewJson"])
    }

    @Test
    fun `partial HTML input stays current on the same card and survives serialization`() {
        val partials = listOf("{\"content\":\"<html>", "{\"content\":\"<html>正在生成正文")
        for (index in partials.indices) {
            val update = jsonMapOf(
                "sessionUpdate" to if (index == 0) "tool_call" else "tool_call_update",
                "toolCallId" to "html-input",
            )
            if (index == 0) {
                update["title"] = "写入文件"
                update["kind"] = "edit"
                update["status"] = "pending"
            }
            update["rawInput"] = partials[index]
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "html-turn",
                    "params" to jsonMapOf(
                        "sessionId" to "html-session",
                        "update" to update,
                    ),
                ),
            )
            val card = runtime.messages.single().cardData!!
            assertEquals("pending", card["status"])
            assertEquals(partials[index], card["argsJson"])
            assertEquals("html-input", card["toolCallId"])
            @Suppress("UNCHECKED_CAST")
            val restored = ChatMessage.fromJson(
                DartJson.decode(DartJson.encode(runtime.messages.single().toJson())) as Map<String, Any?>,
            )
            assertEquals(partials[index], restored.cardData!!["argsJson"])
        }
    }

    // --- for (final stopReason in ['cancelled', 'error', 'end_turn']) -----

    private fun officialNeverInventsSuccessForUnfinishedHtmlTools(stopReason: String) {
        for ((key, value) in linkedMapOf(
            "input" to "pending",
            "writing" to "in_progress",
            "saved" to "completed",
            "denied" to "failed",
        )) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "html-turn",
                    "params" to jsonMapOf(
                        "sessionId" to "html-session",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call",
                            "toolCallId" to key,
                            "title" to "写入文件",
                            "kind" to "edit",
                            "status" to value,
                            "rawInput" to if (key == "input") {
                                "{\"content\":\"<html>"
                            } else {
                                jsonMapOf("path" to "/workspace/$key.html")
                            },
                        ),
                    ),
                ),
            )
        }
        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = "html-session",
            turnId = "html-turn",
            stopReason = stopReason,
            error = if (stopReason == "error") "connection lost" else null,
        )
        val cards = runtime.messages
            .filter { it.cardData?.get("toolType") == "file" }
            .map { it.cardData!! }
        assertEquals(stopReason, 4, cards.size)
        assertEquals(
            stopReason,
            mapOf(
                "input" to "pending",
                "writing" to "running",
                "saved" to "success",
                "denied" to "error",
            ),
            cards.associate { it["toolCallId"] to it["status"] },
        )
        assertFalse(stopReason, runtime.isAiResponding)
        assertTrue(
            stopReason,
            cards.any { (it["argsJson"] ?: "").toString().contains("<html>") },
        )
    }

    @Test
    fun `official cancelled never invents success for unfinished HTML tools`() =
        officialNeverInventsSuccessForUnfinishedHtmlTools("cancelled")

    @Test
    fun `official error never invents success for unfinished HTML tools`() =
        officialNeverInventsSuccessForUnfinishedHtmlTools("error")

    @Test
    fun `official end_turn never invents success for unfinished HTML tools`() =
        officialNeverInventsSuccessForUnfinishedHtmlTools("end_turn")

    @Test
    fun `reads ACP identity through the bridge event envelope`() {
        val event = jsonMapOf(
            "message" to jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-nested",
                    "turnId" to "turn-nested",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_thought_chunk",
                        "content" to jsonMapOf("text" to "nested reasoning"),
                    ),
                ),
            ),
        )

        assertEquals("session-nested", acpEventSessionId(event))
        assertEquals("turn-nested", acpEventTurnId(event))
    }

    @Test
    fun `ignores a duplicated host ACP notification by explicit event id`() {
        val event = jsonMapOf(
            "eventId" to "session-dedupe:1",
            "method" to "session/update",
            "turnId" to "turn-dedupe",
            "params" to jsonMapOf(
                "sessionId" to "session-dedupe",
                "update" to jsonMapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "message-dedupe",
                    "content" to jsonMapOf("type" to "text", "text" to "只显示一次"),
                ),
            ),
        )

        reducer.reduce(runtime, event)
        reducer.reduce(runtime, LinkedHashMap(event))

        assertEquals(1, runtime.messages.size)
        assertEquals("只显示一次", runtime.messages.single().text)
    }

    @Test
    fun `imports the removed AgentStreamEvent shape through ACP identity`() {
        val first = reducer.reduce(
            runtime,
            jsonMapOf(
                "kind" to "text_snapshot",
                "taskId" to "legacy-task-1",
                "entryId" to "legacy-message-1",
                "seq" to 1,
                "text" to "旧 Harness 的回答",
            ),
        )

        assertTrue(first.handled)
        assertEquals("旧 Harness 的回答", runtime.messages.single().text)
        assertEquals("legacy-task-1", runtime.messages.single().turnId)

        reducer.reduce(
            runtime,
            jsonMapOf("kind" to "completed", "taskId" to "legacy-task-1", "seq" to 2),
        )

        assertTrue(runtime.isAiResponding)
        assertEquals("旧 Harness 的回答", runtime.messages.single().text)
        assertTrue(runtime.acpCompatibilityDiagnostics.isEmpty())
    }

    @Test
    fun `imports legacy tool lifecycle into the shared ACP tool card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "kind" to "tool_started",
                "taskId" to "legacy-tool-turn",
                "entryId" to "legacy-tool-1",
                "seq" to 1,
                "toolName" to "terminal_execute",
                "toolType" to "terminal",
                "status" to "running",
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "kind" to "tool_completed",
                "taskId" to "legacy-tool-turn",
                "entryId" to "legacy-tool-1",
                "seq" to 2,
                "toolName" to "terminal_execute",
                "toolType" to "terminal",
                "status" to "success",
                "summary" to "命令完成",
            ),
        )

        val cards = runtime.messages.filter { it.cardData?.get("type") == "agent_tool_summary" }
        assertEquals(1, cards.size)
        assertEquals("success", cards.single().cardData?.get("status"))
        assertEquals("legacy-tool-turn", cards.single().cardData?.get("taskId"))
    }

    @Test
    fun `quarantines a turn event without identity instead of merging it`() {
        runtime.isAiResponding = true
        runtime.currentDispatchTurnId = "xiaowan-turn-1"
        runtime.activeAcpTurnId = "xiaowan-turn-1"

        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "item/agentMessage/delta",
                "params" to jsonMapOf("delta" to "late output without turn id"),
            ),
        )

        assertTrue(result.handled)
        assertNotNull(result.compatibilityWarning)
        assertTrue(result.compatibilityWarning!!.contains("缺少 turnId"))
        assertTrue(runtime.messages.isEmpty())
        assertEquals(1, runtime.acpCompatibilityDiagnostics.size)
        assertEquals("turn_id_missing", runtime.acpCompatibilityDiagnostics.single()["reason"])
        assertEquals("xiaowan-turn-1", runtime.currentDispatchTurnId)

        val repeated = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "item/agentMessage/delta",
                "params" to jsonMapOf("delta" to "another late output without turn id"),
            ),
        )
        assertNull(repeated.compatibilityWarning)
        assertEquals(2, runtime.acpCompatibilityDiagnostics.size)
    }

    @Test
    fun `missing ACP messageId still stays scoped to its official turn`() {
        for (text in listOf("第一段", "第二段")) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "method" to "session/update",
                    "params" to jsonMapOf(
                        "sessionId" to "session-no-message-id",
                        "turnId" to "turn-no-message-id",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "agent_message_chunk",
                            "content" to jsonMapOf("type" to "text", "text" to text),
                        ),
                    ),
                ),
            )
        }

        assertEquals(1, runtime.messages.size)
        assertEquals("第一段第二段", runtime.messages.single().text)
        assertTrue(runtime.acpCompatibilityDiagnostics.isEmpty())
    }

    @Test
    fun `missing messageId uses item identity to keep same-turn messages separate`() {
        for (itemId in listOf("item-a", "item-b")) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "method" to "session/update",
                    "params" to jsonMapOf(
                        "sessionId" to "session-same-turn",
                        "turnId" to "turn-same-turn",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "agent_message_chunk",
                            "itemId" to itemId,
                            "content" to jsonMapOf("type" to "text", "text" to itemId),
                        ),
                    ),
                ),
            )
        }

        assertEquals(2, runtime.messages.size)
        assertTrue(runtime.messages.map { it.text }.containsAll(listOf("item-a", "item-b")))
    }

    @Test
    fun `retains scalar and list ACP unknown updates without a turn id`() {
        for (rawUpdate in listOf<Any?>("provider-progress", mutableListOf<Any?>("phase-1", 0.5))) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "method" to "session/update",
                    "params" to jsonMapOf(
                        "sessionId" to "session-extension-shape",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "vendor_progress",
                            "rawUpdate" to rawUpdate,
                        ),
                    ),
                ),
            )
        }

        assertEquals(2, runtime.acpExtensionUpdates.size)
        assertTrue(
            runtime.acpExtensionUpdates.map { it["rawUpdate"] }
                .containsAll(listOf<Any?>("provider-progress", listOf<Any?>("phase-1", 0.5))),
        )
    }

    @Test
    fun `retains all ACP extension updates for a long-lived conversation`() {
        for (index in 0 until 700) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "method" to "session/update",
                    "params" to jsonMapOf(
                        "sessionId" to "session-long-extension-history",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "vendor_progress",
                            "rawUpdate" to jsonMapOf("sequence" to index),
                        ),
                    ),
                ),
            )
        }

        assertEquals(700, runtime.acpExtensionUpdates.size)
        assertEquals(0, (runtime.acpExtensionUpdates.first()["rawUpdate"] as Map<*, *>)["sequence"])
        assertEquals(699, (runtime.acpExtensionUpdates.last()["rawUpdate"] as Map<*, *>)["sequence"])
    }

    @Test
    fun `maps agent message deltas into assistant text`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/agentMessage/delta",
                    "params" to jsonMapOf("turnId" to "turn-1", "delta" to "hello"),
                ),
            ),
        )

        assertTrue(result.handled)
        assertEquals("hello", runtime.messages.single().text)
        assertEquals(2, runtime.messages.single().user)
    }

    @Test
    fun `ACP assistant chunks preserve Markdown whitespace byte for byte`() {
        val chunks = listOf(
            "程序运行成功了！",
            "\n\n",
            "---\n\n## 完成情况",
            "\n\n### 程序效果\n\n",
            "```text\n第一行\n第二行\n```",
            "\n\n后续 **加粗** 和 [链接](https://example.com)",
        )

        for (chunk in chunks) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "method" to "session/update",
                    "params" to jsonMapOf(
                        "sessionId" to "session-markdown",
                        "turnId" to "turn-markdown",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "agent_message_chunk",
                            "messageId" to "message-markdown",
                            "content" to jsonMapOf("type" to "text", "text" to chunk),
                        ),
                    ),
                ),
            )
        }

        assertEquals(chunks.joinToString(""), runtime.messages.single().text)
    }

    @Test
    fun `ACP assistant chunks preserve spaces at token boundaries`() {
        for (chunk in listOf("POSIX", " Shell", " and", " Markdown")) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "method" to "session/update",
                    "params" to jsonMapOf(
                        "sessionId" to "session-spaces",
                        "turnId" to "turn-spaces",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "agent_message_chunk",
                            "messageId" to "message-spaces",
                            "content" to jsonMapOf("text" to chunk),
                        ),
                    ),
                ),
            )
        }

        assertEquals("POSIX Shell and Markdown", runtime.messages.single().text)
    }

    @Test
    fun `projects ACP assistant image content into the shared image card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-assistant-image",
                    "turnId" to "turn-assistant-image",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "assistant-image",
                        "content" to jsonMapOf(
                            "type" to "image",
                            "data" to "AAAA",
                            "mimeType" to "image/png",
                        ),
                    ),
                ),
            ),
        )

        val imageCard = runtime.messages.single { it.cardData?.get("toolType") == "image" }
        assertEquals("agent_tool_summary", imageCard.cardData?.get("type"))
        assertEquals("data:image/png;base64,AAAA", imageCard.cardData?.get("imageDataUrl"))
        assertEquals("assistant_media", imageCard.cardData?.get("toolName"))
    }

    @Test
    fun `projects ACP assistant image resources into the shared image card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-assistant-resource",
                    "turnId" to "turn-assistant-resource",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "assistant-resource",
                        "content" to jsonMapOf(
                            "type" to "resource",
                            "resource" to jsonMapOf(
                                "uri" to "workspace://result.png",
                                "mimeType" to "image/jpeg",
                                "blob" to "BBBB",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val imageCard = runtime.messages.single { it.cardData?.get("toolType") == "image" }
        assertEquals("data:image/jpeg;base64,BBBB", imageCard.cardData?.get("imageDataUrl"))
    }

    @Test
    fun `projects ACP assistant audio content into the shared audio card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-assistant-audio",
                    "turnId" to "turn-assistant-audio",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "assistant-audio",
                        "content" to jsonMapOf(
                            "type" to "audio",
                            "data" to "AAAA",
                            "mimeType" to "audio/mpeg",
                        ),
                    ),
                ),
            ),
        )

        val audioCard = runtime.messages.single { it.cardData?.get("toolType") == "audio" }
        assertEquals("audio", audioCard.cardData?.get("toolType"))
        assertEquals("data:audio/mpeg;base64,AAAA", audioCard.cardData?.get("audioDataUrl"))
    }

    @Test
    fun `keeps ACP advertised commands in shared runtime state`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-commands",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "available_commands_update",
                        "availableCommands" to mutableListOf(
                            jsonMapOf("name" to "review", "description" to "Review the workspace"),
                            jsonMapOf("name" to "/review", "description" to "duplicate"),
                            jsonMapOf("name" to "ship", "description" to "Ship the change"),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        assertEquals(2, runtime.availableAcpCommands.size)
        assertEquals("ship", runtime.availableAcpCommands.last()["name"])
    }

    @Test
    fun `keeps dynamic ACP config options in shared runtime state`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-config",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "config_option_update",
                        "configOptions" to mutableListOf(
                            jsonMapOf(
                                "id" to "model",
                                "name" to "Model",
                                "type" to "select",
                                "currentValue" to "model-a",
                                "options" to mutableListOf(
                                    jsonMapOf("value" to "model-a", "name" to "Model A"),
                                ),
                            ),
                            jsonMapOf("name" to "invalid-without-id"),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        assertEquals(1, runtime.acpConfigOptions.size)
        assertEquals("model", runtime.acpConfigOptions.single()["id"])
    }

    @Test
    fun `projects ACP user history only when explicitly replaying`() {
        val live = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-history",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "user_message_chunk",
                        "messageId" to "user-live",
                        "content" to jsonMapOf("type" to "text", "text" to "must not duplicate"),
                    ),
                ),
            ),
        )
        assertTrue(live.handled)
        assertTrue(runtime.messages.isEmpty())

        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-history",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "user_message_chunk",
                        "messageId" to "user-replay",
                        "replay" to true,
                        "content" to jsonMapOf("type" to "text", "text" to "replayed"),
                    ),
                ),
            ),
        )
        assertEquals(1, runtime.messages.size)
        assertEquals(1, runtime.messages.single().user)
        assertEquals("replayed", runtime.messages.single().text)
    }

    @Test
    fun `projects a live ACP user chunk only when the host query is absent`() {
        val liveEvent = jsonMapOf(
            "method" to "session/update",
            "turnId" to "turn-live-user",
            "params" to jsonMapOf(
                "sessionId" to "session-live-user",
                "turnId" to "turn-live-user",
                "update" to jsonMapOf(
                    "sessionUpdate" to "user_message_chunk",
                    "messageId" to "dsh-user-message",
                    "content" to jsonMapOf("type" to "text", "text" to "DSH 实时用户问题"),
                ),
            ),
        )

        reducer.reduce(runtime, liveEvent)

        assertEquals(1, runtime.messages.size)
        assertEquals(1, runtime.messages.single().user)
        assertEquals("DSH 实时用户问题", runtime.messages.single().text)

        val hostRuntime = ChatConversationRuntimeState(conversationId = 43, mode = CHAT_RUNTIME_MODE_AGENT)
        extraRuntimes.add(hostRuntime)
        hostRuntime.currentDispatchTurnId = "turn-live-user-ai"
        hostRuntime.messages.add(ChatMessage.userMessage("DSH 实时用户问题", id = "turn-live-user-user"))
        reducer.reduce(hostRuntime, liveEvent)

        assertEquals(1, hostRuntime.messages.size)
        assertEquals("turn-live-user-user", hostRuntime.messages.single().id)
    }

    @Test
    fun `maps ACP elicitation requests into the shared request card`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "jsonrpc" to "2.0",
                    "id" to "elicitation-1",
                    "method" to "elicitation/create",
                    "params" to jsonMapOf(
                        "sessionId" to "session-elicitation",
                        "title" to "需要确认",
                        "description" to "请提供项目名称",
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        assertEquals(1, runtime.messages.size)
        assertEquals("agent_request", runtime.messages.single().cardData?.get("type"))
        assertEquals("user_input", runtime.messages.single().cardData?.get("requestKind"))
        assertEquals("elicitation-1", runtime.messages.single().cardData?.get("requestId"))
    }

    @Test
    fun `preserves request ownership when a later ACP update omits it`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "agentId" to "xiaowan-acp",
                "agentName" to "小万",
                "message" to jsonMapOf(
                    "id" to "elicitation-owner-1",
                    "method" to "elicitation/create",
                    "params" to jsonMapOf("sessionId" to "session-owner-1", "title" to "需要确认"),
                ),
            ),
        )

        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "id" to "elicitation-owner-1",
                    "method" to "elicitation/create",
                    "params" to jsonMapOf("title" to "需要确认（更新）"),
                ),
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("xiaowan-acp", card["agentId"])
        assertEquals("小万", card["agentName"])
        assertEquals("session-owner-1", card["sessionId"])
    }

    @Test
    fun `uses the ACP elicitation schema instead of a generic input title`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "id" to "elicitation-schema-1",
                    "method" to "elicitation/create",
                    "params" to jsonMapOf(
                        "message" to "The agent needs your input.",
                        "requestedSchema" to jsonMapOf(
                            "type" to "object",
                            "properties" to jsonMapOf(
                                "question_0" to jsonMapOf(
                                    "type" to "string",
                                    "title" to "插件名称",
                                    "description" to "请输入要安装的插件",
                                    "oneOf" to mutableListOf(
                                        jsonMapOf("const" to "android", "title" to "Android 插件"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("插件名称", card["title"])
        val detail = card["detail"] as String
        assertTrue(detail.contains("请输入要安装的插件"))
        assertTrue(detail.contains("Android 插件"))
        assertFalse(detail.contains("requestedSchema"))
    }

    @Test
    fun `uses the schema question for legacy ACP user-input envelopes`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "id" to "user-input-schema-1",
                    "method" to "item/tool/requestUserInput",
                    "params" to jsonMapOf(
                        "questions" to mutableListOf(
                            jsonMapOf(
                                "id" to "details",
                                "label" to "The agent needs your input.",
                                "description" to "The agent needs your input.",
                            ),
                        ),
                        "requested_schema" to DartJson.encode(
                            jsonMapOf(
                                "type" to "object",
                                "properties" to jsonMapOf(
                                    "details" to jsonMapOf(
                                        "type" to "string",
                                        "title" to "插件详情",
                                        "description" to "请提供插件名称和用途",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("插件详情", card["title"])
        assertEquals("请提供插件名称和用途", card["detail"])
    }

    @Test
    fun `preserves unknown ACP extensions at the shared runtime boundary`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-extension",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "provider_progress",
                        "rawUpdate" to jsonMapOf("text" to "still working", "progress" to 0.5),
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        assertEquals("provider_progress", runtime.acpExtensionUpdates.single()["sessionUpdate"])
        assertEquals(0.5, (runtime.acpExtensionUpdates.single()["rawUpdate"] as Map<*, *>)["progress"])
    }

    @Test
    fun `retains ACP extension requests and their response ids`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "_omnibot/presentation",
                "id" to "extension-1",
                "acpExtensionRequest" to true,
                "message" to jsonMapOf(
                    "jsonrpc" to "2.0",
                    "id" to "extension-1",
                    "method" to "_omnibot/presentation",
                    "params" to jsonMapOf("card" to "usage"),
                ),
            ),
        )

        assertTrue(result.handled)
        assertEquals("extension-1", result.requestId)
        assertEquals("_omnibot/presentation", runtime.acpExtensionUpdates.single()["method"])
        assertEquals(true, runtime.acpExtensionUpdates.single()["request"])
        assertEquals("usage", (runtime.acpExtensionUpdates.single()["params"] as Map<*, *>)["card"])
    }

    @Test
    fun `projects session-scoped ACP titles without a turn id`() {
        runtime.conversation = ConversationPayload.create(
            id = 42,
            mode = ConversationModes.AGENT,
            title = "旧标题",
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
                    "sessionId" to "session-title",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "session_info_update",
                        "title" to "ACP 新标题",
                        "updatedAt" to 1234,
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        assertEquals("thread/name/updated", result.method)
        assertEquals("ACP 新标题", runtime.conversation?.let { ConversationPayload(it).title })
    }

    @Test
    fun `ACP reasoning chunks preserve Markdown whitespace`() {
        val chunks = listOf("分析步骤", "\n\n", "- 第一项", "\n- 第二项")

        for (chunk in chunks) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "method" to "session/update",
                    "params" to jsonMapOf(
                        "sessionId" to "session-reasoning",
                        "turnId" to "turn-reasoning",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "agent_thought_chunk",
                            "messageId" to "reasoning-message",
                            "content" to jsonMapOf("type" to "text", "text" to chunk),
                        ),
                    ),
                ),
            )
        }

        val thinking = runtime.messages.single { it.cardData?.get("type") == "deep_thinking" }
        assertEquals(chunks.joinToString(""), thinking.cardData?.get("thinkingContent"))
    }

    @Test
    fun `one turn completes reasoning and assistant text together`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-1",
                    "turnId" to "turn-1",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_thought_chunk",
                        "messageId" to "reason-1",
                        "content" to jsonMapOf("text" to "先思考"),
                    ),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-1",
                    "turnId" to "turn-1",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-1",
                        "content" to jsonMapOf("text" to "最终答案"),
                    ),
                ),
            ),
        )

        // Captured instance, as in Dart (`thinking` is read again below).
        val thinking = runtime.messages.first { it.cardData?.get("type") == "deep_thinking" }
        // Assistant text marks the thinking phase complete; the turn remains
        // active until the ACP terminal notification arrives.
        assertEquals(false, thinking.cardData?.get("isLoading"))
        assertTrue(runtime.isAiResponding)

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "end_turn",
        )

        assertEquals(false, thinking.cardData?.get("isLoading"))
        assertFalse(runtime.isAiResponding)
        assertTrue(runtime.activeAgentTurnIds.isEmpty())
    }

    @Test
    fun `projects native ACP session updates when turn id is on the envelope`() {
        // AgentRuntimeManager keeps session/update protocol params untouched and
        // attaches the host-owned turn id to the outer event envelope.
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "native-turn-1",
                "threadId" to "native-session-1",
                "params" to jsonMapOf(
                    "sessionId" to "native-session-1",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_thought_chunk",
                        "messageId" to "thought-1",
                        "content" to jsonMapOf("type" to "text", "text" to "来自 DSH 的思考"),
                    ),
                ),
            ),
        )

        val thinking = runtime.messages.first { it.cardData?.get("type") == "deep_thinking" }
        assertEquals("来自 DSH 的思考", thinking.cardData?.get("thinkingContent"))
        assertEquals("native-turn-1", thinking.cardData?.get("taskID"))
        assertTrue(runtime.isAiResponding)
    }

    @Test
    fun `a text-only turn completes without creating a thinking card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-1",
                    "turnId" to "turn-1",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-1",
                        "content" to jsonMapOf("text" to "直接回答"),
                    ),
                ),
            ),
        )
        assertTrue(runtime.messages.none { it.cardData?.get("type") == "deep_thinking" })

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "end_turn",
        )

        assertFalse(runtime.isAiResponding)
        assertEquals("直接回答", runtime.messages.single().text)
    }

    @Test
    fun `official turn completion clears a local dispatch placeholder`() {
        runtime.isAiResponding = true
        runtime.currentDispatchTurnId = "local-request"
        runtime.lastAgentTurnId = "local-request"
        runtime.activeAcpTurnId = "turn-1"

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = "session-1",
            turnId = "turn-1",
            stopReason = "end_turn",
        )

        assertFalse(runtime.isAiResponding)
        assertNull(runtime.currentDispatchTurnId)
        assertNull(runtime.activeAcpTurnId)
    }

    @Test
    fun `prompt response closes its primed request without an admitted ACP id`() {
        runtime.isAiResponding = true
        runtime.currentDispatchTurnId = "local-request"
        runtime.lastAgentTurnId = "local-request"
        runtime.isDeepThinking = true
        runtime.activeThinkingCardId = "local-request-thinking"
        runtime.messages.add(
            ChatMessage(
                id = "local-request-thinking",
                type = 2,
                user = 3,
                content = jsonMapOf(
                    "cardData" to jsonMapOf(
                        "type" to "deep_thinking",
                        "taskID" to "local-request",
                        "cardId" to "local-request-thinking",
                        "isLoading" to true,
                        "stage" to ThinkingStage.THINKING,
                        "thinkingContent" to "",
                    ),
                    "id" to "local-request-thinking",
                ),
            ),
        )

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "official-turn-1",
            stopReason = "end_turn",
        )

        val card = runtime.messages.single().cardData!!
        assertFalse(runtime.isAiResponding)
        assertFalse(runtime.isDeepThinking)
        assertNull(runtime.currentDispatchTurnId)
        assertEquals(false, card["isLoading"])
        assertEquals(ThinkingStage.COMPLETE, card["stage"])
    }

    @Test
    fun `owning prompt response without a wire id closes its local request`() {
        runtime.isAiResponding = true
        runtime.currentDispatchTurnId = "local-request"
        runtime.lastAgentTurnId = "local-request"

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = null,
            stopReason = "end_turn",
        )

        assertFalse(runtime.isAiResponding)
        assertNull(runtime.currentDispatchTurnId)
        assertTrue(runtime.acpCompatibilityDiagnostics.isEmpty())
    }

    @Test
    fun `id-less terminal never closes an already admitted official turn`() {
        runtime.isAiResponding = true
        runtime.currentDispatchTurnId = "local-request"
        runtime.lastAgentTurnId = "local-request"
        runtime.activeAcpTurnId = "official-turn-1"

        reducer.reduce(runtime, jsonMapOf("method" to "turn/completed", "params" to jsonMapOf()))

        assertTrue(runtime.isAiResponding)
        assertEquals("official-turn-1", runtime.activeAcpTurnId)
        assertTrue(runtime.acpCompatibilityDiagnostics.isEmpty())
    }

    @Test
    fun `id-less turn plan updates are quarantined instead of using the active run`() {
        runtime.isAiResponding = true
        runtime.currentDispatchTurnId = "new-turn"
        runtime.activeAcpTurnId = "new-turn"

        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "turn/plan/updated",
                "params" to jsonMapOf("plan" to "- [pending] stale plan"),
            ),
        )

        assertTrue(runtime.messages.isEmpty())
        assertEquals(1, runtime.acpCompatibilityDiagnostics.size)
        assertEquals("turn/plan/updated", runtime.acpCompatibilityDiagnostics.single()["method"])
    }

    @Test
    fun `id-less raw response completion is quarantined`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "rawResponseItem/completed",
                "params" to jsonMapOf(
                    "item" to jsonMapOf("type" to "function_call", "name" to "read_file"),
                ),
            ),
        )

        assertTrue(result.handled)
        assertTrue(runtime.messages.isEmpty())
        assertEquals("turn_id_missing", runtime.acpCompatibilityDiagnostics.single()["reason"])
    }

    @Test
    fun `many message ids in one turn stay a single active turn`() {
        // ACP mints a new messageId per assistant message inside a turn; message
        // identity is not turn identity.
        for (messageId in listOf("msg-a", "msg-b", "msg-c", "msg-d")) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "turnId" to "turn-1",
                    "message" to jsonMapOf(
                        "method" to "item/agentMessage/delta",
                        "params" to jsonMapOf(
                            "turnId" to "turn-1",
                            "itemId" to messageId,
                            "delta" to "chunk for $messageId",
                        ),
                    ),
                ),
            )
        }

        assertEquals(4, runtime.messages.size)
        assertEquals(4, runtime.currentAiMessages.size)
        assertEquals(setOf("turn-1"), runtime.activeAgentTurnIds)
    }

    @Test
    fun `turn completion clears the active turn and its text cache`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "turnId" to "turn-1",
                "message" to jsonMapOf(
                    "method" to "item/agentMessage/delta",
                    "params" to jsonMapOf("turnId" to "turn-1", "itemId" to "msg-a", "delta" to "答案"),
                ),
            ),
        )
        assertEquals(setOf("turn-1"), runtime.activeAgentTurnIds)

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "end_turn",
        )

        assertFalse(runtime.isAiResponding)
        assertTrue(runtime.activeAgentTurnIds.isEmpty())
        assertTrue(runtime.currentAiMessages.isEmpty())
    }

    @Test
    fun `turn completion clears the official turn owner`() {
        runtime.currentDispatchTurnId = "turn-1"
        runtime.activeAcpTurnId = "turn-1"
        runtime.lastAgentTurnId = "turn-1"
        runtime.isAiResponding = true

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "end_turn",
        )

        assertTrue(runtime.activeAgentTurnIds.isEmpty())
        assertNull(runtime.lastAgentTurnId)
    }

    @Test
    fun `ACP admits a turn after the local request placeholder`() {
        runtime.currentDispatchTurnId = "request-1-ai"
        runtime.lastAgentTurnId = "request-1-ai"
        runtime.isAiResponding = true

        val started = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "turn/started",
                "params" to jsonMapOf("turnId" to "acp-turn-1"),
            ),
        )

        assertTrue(started.handled)
        assertEquals("acp-turn-1", runtime.activeAcpTurnId)
        assertEquals("request-1-ai", runtime.currentDispatchTurnId)

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "acp-turn-1",
            stopReason = "end_turn",
        )

        assertNull(runtime.activeAcpTurnId)
        assertNull(runtime.currentDispatchTurnId)
        assertFalse(runtime.isAiResponding)
    }

    @Test
    fun `keeps local run identity stable after ACP turn admission`() {
        runtime.currentDispatchTurnId = "local-run-1"
        runtime.activeRunId = "local-run-1"
        runtime.isAiResponding = true

        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "turn/started",
                "params" to jsonMapOf("sessionId" to "session-1", "turnId" to "official-turn-1"),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "item/agentMessage/delta",
                "params" to jsonMapOf(
                    "sessionId" to "session-1",
                    "turnId" to "official-turn-1",
                    "itemId" to "item-1",
                    "delta" to "hello",
                ),
            ),
        )

        val message = runtime.messages.single()
        assertEquals("local-run-1", runtime.activeRunId)
        assertEquals("official-turn-1", runtime.activeAcpTurnId)
        assertEquals("local-run-1", message.runId)
        assertEquals("official-turn-1", message.turnId)
        assertEquals("local-run-1", message.streamMeta?.get("runId"))
    }

    @Test
    fun `ACP admits a turn from the first session update when no turn started exists`() {
        runtime.currentDispatchTurnId = "request-1-ai"
        runtime.lastAgentTurnId = "request-1-ai"
        runtime.isAiResponding = true

        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "allowImplicitTurnAdmission" to true,
                "params" to jsonMapOf(
                    "sessionId" to "session-1",
                    "turnId" to "acp-turn-1",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-1",
                        "content" to jsonMapOf("text" to "OpenCode response"),
                    ),
                ),
            ),
        )

        assertEquals("acp-turn-1", runtime.activeAcpTurnId)
        assertEquals("OpenCode response", runtime.messages.single().text)

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = "session-1",
            turnId = "acp-turn-1",
            stopReason = "end_turn",
        )

        assertFalse(runtime.isAiResponding)
        assertNull(runtime.currentDispatchTurnId)
        assertNull(runtime.activeAcpTurnId)
    }

    @Test
    fun `does not admit an untrusted first event as the active turn`() {
        runtime.currentDispatchTurnId = "request-1-ai"
        runtime.lastAgentTurnId = "request-1-ai"
        runtime.isAiResponding = true

        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-1",
                    "turnId" to "late-old-turn",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-old",
                        "content" to jsonMapOf("text" to "迟到的旧输出"),
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        assertNull(runtime.activeAcpTurnId)
        assertTrue(runtime.messages.isEmpty())
        assertEquals("request-1-ai", runtime.currentDispatchTurnId)
    }
}
