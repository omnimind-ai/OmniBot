package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionUsage
import cn.com.omnimind.baselib.llm.contentText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class AgentOrchestratorTest {
    @Test
    fun htmlToolCardIsVisibleBeforeArgumentsFinishStreaming() = assertHtmlToolStream("success")

    @Test
    fun failedHtmlInputDoesNotExecuteOrReplayTheTool() = assertHtmlToolStream("error")

    @Test
    fun cancelledHtmlInputDoesNotExecuteOrReplayTheTool() = assertHtmlToolStream("cancelled")

    private fun assertHtmlToolStream(outcome: String) = runBlocking {
        val updates = mutableListOf<com.agentclientprotocol.model.SessionUpdate>()
        val visible = kotlinx.coroutines.CompletableDeferred<Unit>()
        val bridge = cn.com.omnimind.bot.agent.runtime.XiaowanAcpEventBridge {
            updates += it
            if (it is com.agentclientprotocol.model.SessionUpdate.ToolCall) visible.complete(Unit)
        }
        val executor = FakeToolExecutor(
            results = mapOf("file_write" to listOf(successfulContextResult("file_write")))
        )
        var requests = 0
        var visibleBeforeEnd = false
        val client = HttpAgentLlmClient(
            scope = this,
            modelOverride = AgentModelOverride(
                providerProfileId = "test-provider",
                apiBase = "https://example.invalid/v1", apiKey = "test-key", modelId = "test-model"
            ),
            streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                val source = object : okhttp3.sse.EventSource {
                    override fun request() = okhttp3.Request.Builder().url("https://example.invalid/v1").build()
                    override fun cancel() = Unit
                }
                requests++
                if (requests == 1) {
                    listener.onEvent(source, null, "message",
                        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"html-1","function":{"name":"file_write","arguments":"{\"path\":\"/workspace/index.html\",\"content\":\"<html>"}}]}}]}""")
                    visibleBeforeEnd = kotlinx.coroutines.withTimeoutOrNull(1000) { visible.await(); true } ?: false
                    assertTrue("The HTML tool card must exist while input is still streaming", visibleBeforeEnd)
                    assertTrue("Partial JSON must never execute", executor.executeCalls.isEmpty())
                    val card = updates.filterIsInstance<com.agentclientprotocol.model.SessionUpdate.ToolCall>().single()
                    assertEquals(com.agentclientprotocol.model.ToolCallStatus.PENDING, card.status)
                    if (outcome == "cancelled") throw CancellationException("user cancelled")
                    if (outcome == "error") {
                        listener.onFailure(source, java.io.IOException("connection reset"), null)
                        return@HttpAgentLlmClient source
                    }
                    listener.onEvent(source, null, "message",
                        """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"hello</html>\"}"}}]},"finish_reason":"tool_calls"}]}""")
                } else {
                    listener.onEvent(source, null, "message",
                        """{"choices":[{"delta":{"content":"HTML 已创建"},"finish_reason":"stop"}]}""")
                }
                listener.onEvent(source, null, "message", "[DONE]")
                source
            },
            maxTransientStreamRetries = 2,
            transientStreamRetryDelayMs = 0,
        )
        val result = createOrchestrator(client, executor).run(
            AgentOrchestrator.Input(
                callback = bridge,
                initialMessages = initialMessages("制作一个 HTML 页面"),
                executionEnv = FakeExecutionEnvironment("制作一个 HTML 页面")
            )
        )
        assertTrue("No work card appeared before the provider finished the input", visibleBeforeEnd)
        if (outcome != "success") {
            assertTrue(result is AgentResult.Error)
            assertEquals(1, requests)
            assertTrue(executor.executeCalls.isEmpty())
            assertTrue(updates.filterIsInstance<com.agentclientprotocol.model.SessionUpdate.ToolCallUpdate>().none {
                it.status == com.agentclientprotocol.model.ToolCallStatus.IN_PROGRESS ||
                    it.status == com.agentclientprotocol.model.ToolCallStatus.COMPLETED
            })
            return@runBlocking
        }
        assertTrue(result is AgentResult.Success)
        assertEquals(listOf("file_write"), executor.executeCalls)
        assertEquals("<html>hello</html>", executor.executeArguments.single()["content"]?.jsonPrimitive?.content)
        assertEquals(1, updates.filterIsInstance<com.agentclientprotocol.model.SessionUpdate.ToolCall>().size)
        val changes = updates.filterIsInstance<com.agentclientprotocol.model.SessionUpdate.ToolCallUpdate>()
        assertTrue(changes.all { it.toolCallId.value == "html-1" })
        assertTrue(changes.any { it.status == com.agentclientprotocol.model.ToolCallStatus.IN_PROGRESS })
        assertEquals(com.agentclientprotocol.model.ToolCallStatus.COMPLETED, changes.last().status)
    }

    private lateinit var originalLocale: Locale
    private val eventJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Before
    fun setUpLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    }

    @After
    fun tearDownLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun failedToolResultFeedsNextRoundWithoutSyntheticPrompt() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("file_read"))),
                assistantTurn(toolCalls = listOf(toolCall("file_search"))),
                assistantTurn(content = "已根据失败结果改用搜索工具继续处理。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "file_read" to listOf(
                    ToolExecutionResult.Error("file_read", "读取失败")
                ),
                "file_search" to listOf(
                    ToolExecutionResult.ContextResult(
                        toolName = "file_search",
                        summaryText = "已找到匹配文件",
                        previewJson = "{}",
                        rawResultJson = "{}",
                        success = true
                    )
                )
            )
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("继续处理 README"),
                executionEnv = FakeExecutionEnvironment("继续处理 README")
            )
        )

        assertEquals(listOf("file_read", "file_search"), toolExecutor.executeCalls)
        assertEquals(3, llmClient.requests.size)
        assertEquals("tool", llmClient.requests[1].messages.last().role)
        assertEquals(
            1,
            llmClient.requests[1].messages.count { it.role == "user" }
        )
        assertTrue(callback.finalChatMessages().last().contains("继续处理"))
        assertTrue(result is AgentResult.Success)
    }

    @Test
    fun slowToolInvocationStaysInTheSameLogicalRun() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("file_read", id = "call-slow-read"))),
                assistantTurn(content = "慢工具完成后继续处理。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf("file_read" to listOf(successfulContextResult("file_read"))),
            delaysMs = mapOf("file_read" to 150L)
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("执行一个耗时读取"),
                executionEnv = FakeExecutionEnvironment("执行一个耗时读取")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertEquals(listOf("file_read"), toolExecutor.executeCalls)
        assertEquals(2, llmClient.requests.size)
        assertEquals("tool", llmClient.requests[1].messages.last().role)
    }

    @Test
    fun spacedImageGenerationChainDoesNotRequireManualContinue() = runBlocking {
        val stepCount = 6
        val llmClient = FakeLlmClient(
            turns = List(stepCount) { index ->
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "image_generation",
                            id = "call-image-$index"
                        )
                    )
                )
            } + assistantTurn(content = "绘图链已完成。")
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "image_generation" to List(stepCount) {
                    ToolExecutionResult.ContextResult(
                        toolName = "image_generation",
                        summaryText = "第 $it 张图已生成",
                        previewJson = "{}",
                        rawResultJson = "{}",
                        imageDataUrl = "data:image/png;base64,AAA"
                    )
                }
            ),
            delaysMs = mapOf("image_generation" to 35L)
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("连续生成多张图"),
                executionEnv = FakeExecutionEnvironment("连续生成多张图")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertEquals(stepCount, toolExecutor.executeCalls.size)
        assertEquals(stepCount + 1, llmClient.requests.size)
    }

    @Test
    fun defaultAgentTurnDoesNotStopAtSixteenModelRounds() = runBlocking {
        val modelRoundCount = 17
        val llmClient = FakeLlmClient(
            turns = List(modelRoundCount) { index ->
                if (index == modelRoundCount - 1) {
                    assistantTurn(content = "完成")
                } else {
                    assistantTurn(
                        toolCalls = listOf(
                            toolCall(
                                name = "file_read",
                                arguments = "{\"path\":\"/workspace/missing.txt\"}",
                                id = "call-file-read-$index"
                            )
                        )
                    )
                }
            }
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "file_read" to List(modelRoundCount - 1) {
                    ToolExecutionResult.Error("file_read", "文件不存在")
                }
            )
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("读取文件"),
                executionEnv = FakeExecutionEnvironment("读取文件")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertEquals(modelRoundCount - 1, toolExecutor.executeCalls.size)
        assertEquals(modelRoundCount, llmClient.requests.size)
    }

    @Test
    fun multiRoundAgentPromptLeavesOptionalToolSchedulingToTheConfiguredProvider() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("file_read"))),
                assistantTurn(content = "工具结果已用于完成当前回答。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf("file_read" to listOf(successfulContextResult("file_read")))
        )

        val result = createOrchestrator(
            llmClient = llmClient,
            toolExecutor = toolExecutor,
            availableToolNames = setOf("file_read")
        ).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("读取文件后继续回答"),
                executionEnv = FakeExecutionEnvironment("读取文件后继续回答")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertEquals(2, llmClient.requests.size)
        llmClient.requests.forEach { request ->
            assertNull(request.toolChoice)
            assertNull(request.parallelToolCalls)
        }
    }

    @Test
    fun memorySearchResultBeyondLegacyTwentyItemCapFeedsTheSamePrompt() = runBlocking {
        val allHits = (1..64).joinToString(separator = ",") { index ->
            "{\"id\":\"memory-$index\",\"text\":\"fact-$index\"}"
        }
        val rawMemoryResult = "{\"count\":64,\"hits\":[$allHits]}"
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "memory_search",
                            arguments = "{\"query\":\"project decisions\",\"limit\":64}"
                        )
                    )
                ),
                assistantTurn(content = "我已结合全部 64 条历史事实继续回答。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "memory_search" to listOf(
                    ToolExecutionResult.ContextResult(
                        toolName = "memory_search",
                        summaryText = "命中 64 条记忆",
                        previewJson = rawMemoryResult,
                        rawResultJson = rawMemoryResult,
                        success = true
                    )
                )
            )
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("基于所有项目决策继续"),
                executionEnv = FakeExecutionEnvironment("基于所有项目决策继续")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertEquals(2, llmClient.requests.size)
        val replayedToolResult = llmClient.requests[1].messages.last()
        assertEquals("tool", replayedToolResult.role)
        assertTrue(replayedToolResult.contentText().contains("memory-1"))
        assertTrue(replayedToolResult.contentText().contains("memory-64"))
    }

    @Test
    fun explicitOversizedLongTermMemoryWriteReachesTheToolUnchangedInTheSamePrompt() = runBlocking {
        // This is the user path, rather than a storage-unit shortcut: the model
        // asks to persist a large user-provided fact, the tool receives it, and
        // the result returns to the same logical prompt for the final answer.
        val memoryText = "user-requested-memory:" + "preserve-this-verbatim|".repeat(12_000) + "final-fact"
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "memory_upsert_longterm",
                            arguments = "{\"text\":\"$memoryText\"}",
                            id = "call-store-memory"
                        )
                    )
                ),
                assistantTurn(content = "已按原文保存，并继续完成当前回答。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "memory_upsert_longterm" to listOf(
                    ToolExecutionResult.ContextResult(
                        toolName = "memory_upsert_longterm",
                        summaryText = "已写入长期记忆",
                        previewJson = "{\"inserted\":true}",
                        rawResultJson = "{\"inserted\":true}"
                    )
                )
            )
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("请把下面内容原样记入长期记忆：$memoryText"),
                executionEnv = FakeExecutionEnvironment("请原样保存这条长期记忆")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertEquals(2, llmClient.requests.size)
        assertEquals(listOf("memory_upsert_longterm"), toolExecutor.executeCalls)
        assertEquals(
            memoryText,
            toolExecutor.executeArguments.single().getValue("text").jsonPrimitive.content
        )
        val continuationToolMessage = llmClient.requests[1].messages.last()
        assertEquals("tool", continuationToolMessage.role)
        assertEquals(
            1,
            llmClient.requests[1].messages.count { it.role == "user" }
        )
    }

    @Test
    fun multipleToolCallsExecuteInModelOrderWithoutToolNameSchedulingRules() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall("terminal_execute", id = "call-terminal"),
                        toolCall("file_read", id = "call-read")
                    )
                ),
                assistantTurn(content = "两个结果都已纳入回答。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "terminal_execute" to listOf(successfulContextResult("terminal_execute")),
                "file_read" to listOf(successfulContextResult("file_read"))
            )
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("先运行命令，再读取结果文件"),
                executionEnv = FakeExecutionEnvironment("先运行命令，再读取结果文件")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertEquals(listOf("terminal_execute", "file_read"), toolExecutor.executeCalls)
        assertEquals(2, llmClient.requests.size)
        assertEquals(2, llmClient.requests[1].messages.count { it.role == "tool" })
    }

    @Test
    fun manyModelSelectedToolCallsStayInOnePromptAndRetainEveryResult() = runBlocking {
        val toolCallCount = 64
        val calls = (1..toolCallCount).map { index ->
            toolCall(
                name = "file_read",
                arguments = "{\"path\":\"/workspace/file-$index.txt\"}",
                id = "call-file-$index"
            )
        }
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = calls),
                assistantTurn(content = "已完成全部 64 个文件的读取。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "file_read" to List(toolCallCount) {
                    successfulContextResult("file_read")
                }
            )
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("读取工作区中的全部 64 个文件"),
                executionEnv = FakeExecutionEnvironment("读取工作区中的全部 64 个文件")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertEquals(2, llmClient.requests.size)
        assertEquals(List(toolCallCount) { "file_read" }, toolExecutor.executeCalls)
        val replayedResults = llmClient.requests[1].messages.filter { it.role == "tool" }
        assertEquals(toolCallCount, replayedResults.size)
        assertEquals(calls.map { it.id }, replayedResults.map { it.toolCallId })
        assertEquals(
            1,
            llmClient.requests[1].messages.count { it.role == "user" }
        )
    }

    @Test
    fun permissionRequestEndsOnlyTheCurrentPromptWithoutAnAutomaticFollowUp() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "context_apps_query",
                            arguments = "{\"query\":\"maps\"}",
                            id = "call-installed-apps"
                        )
                    )
                ),
                assistantTurn(content = "this must not be requested before the user decides")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "context_apps_query" to listOf(
                    ToolExecutionResult.PermissionRequired(listOf("installed_apps"))
                )
            )
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("帮我查已安装的地图应用"),
                executionEnv = FakeExecutionEnvironment("帮我查已安装的地图应用")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertEquals("permission_required", (result as AgentResult.Success).outputKind)
        assertEquals(listOf("context_apps_query"), toolExecutor.executeCalls)
        assertEquals(1, llmClient.requests.size)
        assertEquals(1, llmClient.requests.single().messages.count { it.role == "user" })
    }

    @Test
    fun cancellationAfterToolExecutionDoesNotReplayThePromptOrTool() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(assistantTurn(toolCalls = listOf(toolCall("file_read")))),
            failuresByRequest = mapOf(2 to CancellationException("user cancelled prompt"))
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf("file_read" to listOf(successfulContextResult("file_read")))
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("读取后总结"),
                executionEnv = FakeExecutionEnvironment("读取后总结")
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals("Agent execution cancelled", (result as AgentResult.Error).message)
        assertEquals(listOf("file_read"), toolExecutor.executeCalls)
        assertEquals(2, llmClient.requests.size)
    }

    @Test
    fun cancellationAfterAToolLeavesTheNextUserPromptFreeToStartWithoutReplayingIt() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("file_read"))),
                assistantTurn(content = "新的问题已经处理完成。")
            ),
            failuresByRequest = mapOf(2 to CancellationException("user cancelled prompt"))
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf("file_read" to listOf(successfulContextResult("file_read")))
        )
        val orchestrator = createOrchestrator(llmClient, toolExecutor)

        val cancelled = orchestrator.run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("读取报告后总结"),
                executionEnv = FakeExecutionEnvironment("读取报告后总结")
            )
        )
        val nextCallback = RecordingCallback()
        val next = orchestrator.run(
            AgentOrchestrator.Input(
                callback = nextCallback,
                initialMessages = initialMessages("换个问题：现在报告在哪？"),
                executionEnv = FakeExecutionEnvironment("换个问题：现在报告在哪？")
            )
        )

        assertTrue(cancelled is AgentResult.Error)
        assertEquals("Agent execution cancelled", (cancelled as AgentResult.Error).message)
        assertTrue(next is AgentResult.Success)
        assertEquals(listOf("file_read"), toolExecutor.executeCalls)
        assertEquals(3, llmClient.requests.size)
        assertEquals(
            "换个问题：现在报告在哪？",
            llmClient.requests.last().messages.last().contentText()
        )
        assertEquals(listOf("新的问题已经处理完成。"), nextCallback.finalChatMessages())
    }

    @Test
    fun promptCacheKeyIsStableAcrossAgentModelRounds() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("file_read"))),
                assistantTurn(content = "完成")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "file_read" to listOf(
                    ToolExecutionResult.ContextResult(
                        toolName = "file_read",
                        summaryText = "read",
                        previewJson = "{}",
                        rawResultJson = "{}",
                        success = true
                    )
                )
            )
        )
        val cacheKey = "omnibot:v1:0123456789abcdef0123:conversation:42"

        val callback = RecordingCallback()
        createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("读取文件"),
                executionEnv = FakeExecutionEnvironment("读取文件"),
                promptCacheKey = cacheKey
            )
        )

        assertEquals(2, llmClient.requests.size)
        assertTrue(llmClient.requests.all { it.promptCacheKey == cacheKey })
    }

    @Test
    fun failedToolResultCanNaturallyBecomeTextReply() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("file_read"))),
                assistantTurn(content = "读取失败，我先直接告诉你当前限制。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "file_read" to listOf(
                    ToolExecutionResult.Error("file_read", "文件不存在")
                )
            )
        )
        val callback = RecordingCallback()

        createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("看看配置文件"),
                executionEnv = FakeExecutionEnvironment("看看配置文件")
            )
        )

        assertEquals(2, llmClient.requests.size)
        assertEquals("tool", llmClient.requests[1].messages.last().role)
        assertEquals(
            1,
            llmClient.requests[1].messages.count { it.role == "user" }
        )
        assertTrue(callback.finalChatMessages().last().contains("读取失败"))
    }

    @Test
    fun executionLikeRequestWithoutToolCallsReturnsPlainAssistantText() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(content = "我不能直接代你打开设置，但可以告诉你下一步。")
            )
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("帮我打开系统设置"),
                executionEnv = FakeExecutionEnvironment("帮我打开系统设置")
            )
        )

        assertEquals(1, llmClient.requests.size)
        assertTrue(callback.errors.isEmpty())
        assertTrue(callback.finalChatMessages().last().contains("打开设置"))
        assertTrue(result is AgentResult.Success)
    }

    @Test
    fun followUpPromptUsesPersistedConversationHistoryWithoutAddingAnotherUserTurn() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(assistantTurn(content = "明天也适合继续在北京安排。"))
        )
        val callback = RecordingCallback()
        val history = listOf(
            ChatCompletionMessage("user", JsonPrimitive("我周末在北京")),
            ChatCompletionMessage("assistant", JsonPrimitive("可以安排城市内活动。")),
            ChatCompletionMessage("user", JsonPrimitive("那明天呢？"))
        )

        val result = createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = history,
                executionEnv = FakeExecutionEnvironment("那明天呢？")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertTrue(callback.completedResult is AgentResult.Success)
        val requestMessages = llmClient.requests.single().messages
        assertEquals(history.map { it.role }, requestMessages.map { it.role })
        assertEquals(history.map { it.contentText() }, requestMessages.map { it.contentText() })
        assertEquals(2, requestMessages.count { it.role == "user" })
        assertEquals("那明天呢？", requestMessages.last().contentText())
    }

    @Test
    fun longPersistedConversationRemainsWholeThroughAFollowUpToolRound() = runBlocking {
        val priorTurns = (1..24).flatMap { turn ->
            listOf(
                ChatCompletionMessage("user", JsonPrimitive("第 $turn 轮用户事实：fact-$turn")),
                ChatCompletionMessage("assistant", JsonPrimitive("第 $turn 轮已确认"))
            )
        }
        val history = priorTurns + ChatCompletionMessage(
            "user",
            JsonPrimitive("请依据第一轮和第二十四轮事实继续处理")
        )
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("memory_search", id = "call-history"))),
                assistantTurn(content = "已结合完整对话历史继续处理。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf("memory_search" to listOf(successfulContextResult("memory_search")))
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = history,
                executionEnv = FakeExecutionEnvironment("请依据第一轮和第二十四轮事实继续处理")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertEquals(2, llmClient.requests.size)
        val initialRequest = llmClient.requests.first().messages
        assertEquals(history.map { it.role }, initialRequest.map { it.role })
        assertTrue(initialRequest.any { it.contentText().contains("fact-1") })
        assertTrue(initialRequest.any { it.contentText().contains("fact-24") })

        val afterTool = llmClient.requests[1].messages
        assertEquals("tool", afterTool.last().role)
        assertEquals(25, afterTool.count { it.role == "user" })
        assertTrue(afterTool.any { it.contentText().contains("fact-1") })
        assertTrue(afterTool.any { it.contentText().contains("fact-24") })
    }

    @Test
    fun cancellationIsTheTerminalOutcomeOfTheCurrentPrompt() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = emptyList(),
            failures = listOf(CancellationException("user cancelled prompt"))
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("继续刚才的任务"),
                executionEnv = FakeExecutionEnvironment("继续刚才的任务")
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals("Agent execution cancelled", (result as AgentResult.Error).message)
        assertEquals(1, llmClient.requests.size)
        assertTrue(callback.finalChatMessages().isEmpty())
    }

    @Test
    fun pseudoToolMarkupIsHandledAsPlainAssistantText() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    content = "<tool_call><function=name>terminal_execute</function></tool_call>"
                )
            )
        )
        val callback = RecordingCallback()

        createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("执行命令"),
                executionEnv = FakeExecutionEnvironment("执行命令")
            )
        )

        assertEquals(1, llmClient.requests.size)
        assertTrue(callback.errors.isEmpty())
        assertTrue(callback.chatMessages.any { it.first.contains("<tool_call>") })
    }

    @Test
    fun ordinary_assistant_text_ends_the_turn_without_recovery() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    content = "让我先查找 AJ1 页面上的产品列表，寻找浅蓝色和灰色的男款 AJ1。",
                    finishReason = "stop"
                ),
                assistantTurn(toolCalls = listOf(toolCall("browser_use"))),
                assistantTurn(
                    content = "我已经定位到 AJ1 列表页，接下来可以继续筛选。",
                    finishReason = "stop"
                ),
                assistantTurn(
                    content = "已按你的条件定位到 AJ1 列表页，建议继续筛选浅蓝色和灰色男款。",
                    finishReason = "stop"
                )
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "browser_use" to listOf(
                    ToolExecutionResult.ContextResult(
                        toolName = "browser_use",
                        summaryText = "已定位到 AJ1 列表页",
                        previewJson = "{}",
                        rawResultJson = "{}",
                        success = true
                    )
                )
            )
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("继续在 AJ1 页面筛选"),
                executionEnv = FakeExecutionEnvironment("继续在 AJ1 页面筛选")
            )
        )

        assertTrue(toolExecutor.executeCalls.isEmpty())
        assertEquals(1, llmClient.requests.size)
        assertEquals(
            "让我先查找 AJ1 页面上的产品列表，寻找浅蓝色和灰色的男款 AJ1。",
            callback.finalChatMessages().last(),
        )
        assertTrue(result is AgentResult.Success)
    }

    @Test
    fun action_intent_text_does_not_trigger_a_hidden_recovery_round() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    content = "我先搜索一下合适的结果。",
                    finishReason = "stop"
                ),
                assistantTurn(
                    content = "让我再检查一下更多信息。",
                    finishReason = "stop"
                )
            )
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("继续执行"),
                executionEnv = FakeExecutionEnvironment("继续执行")
            )
        )

        assertEquals(1, llmClient.requests.size)
        assertEquals("我先搜索一下合适的结果。", callback.finalChatMessages().last())
        assertTrue(result is AgentResult.Success)
    }

    @Test
    fun skillCompletionMetadataDoesNotOverrideProviderStop() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("file_write"))),
                assistantTurn(content = "页面已经创建完成。", finishReason = "stop")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "file_write" to listOf(successfulContextResult("file_write"))
            )
        )
        val callback = RecordingCallback()
        val completionSkill = ResolvedSkillContext(
            skillId = "vibe-project-builder",
            frontmatter = mapOf(
                "completion-start-tools" to "file_write, file_edit, terminal_execute",
                "completion-tools" to "project_check, project_publish"
            ),
            bodyMarkdown = "Build and publish the project.",
            triggerReason = "test"
        )

        val result = createOrchestrator(
            llmClient = llmClient,
            toolExecutor = toolExecutor,
            availableToolNames = setOf("file_write")
        ).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("创建一个 NBA HTML 应用"),
                executionEnv = FakeExecutionEnvironment(
                    userMessage = "创建一个 NBA HTML 应用",
                    resolvedSkills = listOf(completionSkill)
                )
            )
        )

        assertEquals(listOf("file_write"), toolExecutor.executeCalls)
        assertEquals(2, llmClient.requests.size)
        assertEquals("页面已经创建完成。", callback.finalChatMessages().single())
        assertTrue(result is AgentResult.Success)
    }

    @Test
    fun trace_style_retry_text_does_not_trigger_a_hidden_recovery_round() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    content = "让我再尝试一次返回首页。",
                    finishReason = "stop"
                ),
                assistantTurn(
                    content = "让我最后一次尝试返回首页。",
                    finishReason = "stop"
                )
            )
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("继续尝试返回首页"),
                executionEnv = FakeExecutionEnvironment("继续尝试返回首页")
            )
        )

        assertEquals(1, llmClient.requests.size)
        assertEquals("让我再尝试一次返回首页。", callback.finalChatMessages().last())
        assertTrue(result is AgentResult.Success)
    }

    @Test
    fun lengthFinishReasonEndsCurrentPromptWithoutSyntheticUserMessageByDefault() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    content = "第一段还没说完",
                    finishReason = "length"
                ),
                assistantTurn(
                    content = "，后续完成。",
                    finishReason = "stop"
                )
            )
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("写一个长回复"),
                executionEnv = FakeExecutionEnvironment("写一个长回复")
            )
        )

        assertEquals(1, llmClient.requests.size)
        assertEquals("第一段还没说完", callback.finalChatMessages().last())
        assertTrue(callback.chatMessages.any { it.first == "第一段还没说完" && !it.second })
        assertTrue(result is AgentResult.Success)
        assertEquals("length", (result as AgentResult.Success).response.finishReason)
    }

    @Test
    fun malformedToolCallIsRejectedByTheJsonParserAndCompleteReissueExecutes() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "file_read",
                            arguments = """{"path":"/workspace/part",
                            """.trimIndent(),
                            id = "call-truncated"
                        )
                    ),
                    finishReason = "length"
                ),
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "file_read",
                            arguments = """{"path":"/workspace/complete.txt"}""",
                            id = "call-complete"
                        )
                    ),
                    finishReason = "tool_calls"
                ),
                assistantTurn(content = "已使用完整参数读取文件。", finishReason = "stop")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "file_read" to listOf(
                    ToolExecutionResult.ContextResult(
                        toolName = "file_read",
                        summaryText = "读取完成",
                        previewJson = "{}",
                        rawResultJson = "{}",
                        success = true
                    )
                )
            )
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("读取文件"),
                executionEnv = FakeExecutionEnvironment("读取文件")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertEquals(listOf("file_read"), toolExecutor.executeCalls)
        assertEquals(3, llmClient.requests.size)
        val rejectedResult = llmClient.requests[1].messages.last()
        assertEquals("tool", rejectedResult.role)
        assertEquals("call-truncated", rejectedResult.toolCallId)
        assertTrue(rejectedResult.contentText().isNotBlank())
        assertFalse(rejectedResult.contentText().contains("参数可能被截断"))
    }

    @Test
    fun validToolCallExecutesEvenWhenProviderReportsLength() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "file_read",
                            arguments = "{\"path\":\"/workspace/complete.txt\"}",
                            id = "call-valid-length"
                        )
                    ),
                    finishReason = "length"
                ),
                assistantTurn(content = "文件已读取。", finishReason = "stop")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "file_read" to listOf(
                    ToolExecutionResult.ContextResult(
                        toolName = "file_read",
                        summaryText = "读取完成",
                        previewJson = "{\"path\":\"/workspace/complete.txt\"}",
                        rawResultJson = "{\"path\":\"/workspace/complete.txt\"}",
                        success = true
                    )
                )
            )
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("读取文件"),
                executionEnv = FakeExecutionEnvironment("读取文件")
            )
        )

        assertTrue(result is AgentResult.Success)
        assertEquals(listOf("file_read"), toolExecutor.executeCalls)
        assertEquals(2, llmClient.requests.size)
        val executedResult = llmClient.requests[1].messages.last()
        assertEquals("tool", executedResult.role)
        assertEquals("call-valid-length", executedResult.toolCallId)
        assertTrue(executedResult.contentText().contains("complete.txt"))
    }

    @Test
    fun reasoningEffortIsForwardedIntoModelRequests() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(content = "已按低思考强度返回。")
            )
        )

        createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("简单回答"),
                executionEnv = FakeExecutionEnvironment(
                    "简单回答",
                    reasoningEffort = "low"
                )
            )
        )

        assertEquals(1, llmClient.requests.size)
        assertEquals("low", llmClient.requests.first().reasoningEffort)
    }

    @Test
    fun noneReasoningEffortDisablesThinkingOnTheWire() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(assistantTurn(content = "你好"))
        )

        createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("hello"),
                executionEnv = FakeExecutionEnvironment(
                    "hello",
                    reasoningEffort = "none"
                )
            )
        )

        val request = llmClient.requests.single()
        assertEquals(false, request.enableThinking)
        assertEquals(null, request.reasoningEffort)
        assertEquals("disabled", request.thinking?.type)
    }

    @Test
    fun longReasoningUpdatesAreNotTruncated() = runBlocking {
        val longReasoning = buildString {
            repeat(900) { index ->
                append("第${index}段思考内容，用于验证长文本流式更新不会被截断。")
            }
        }
        val callback = ThinkingCaptureCallback()

        createOrchestrator(
            FakeLlmClient(
                turns = listOf(assistantTurn(content = "已完成。")),
                reasoningUpdates = listOf(listOf(longReasoning))
            ),
            FakeToolExecutor()
        ).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("测试长思考"),
                executionEnv = FakeExecutionEnvironment("测试长思考")
            )
        )

        assertEquals(longReasoning, callback.thinkingUpdates.last())
        assertTrue(callback.thinkingUpdates.last().length > 3000)
    }

    @Test
    fun terminalExecuteRunsOnlyOncePerExplicitToolCall() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "terminal_execute",
                            arguments = """{"command":"echo hi"}"""
                        )
                    )
                ),
                assistantTurn(content = "终端命令失败，我先根据结果回复你。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "terminal_execute" to listOf(
                    ToolExecutionResult.TerminalResult(
                        toolName = "terminal_execute",
                        summaryText = "命令执行失败",
                        previewJson = "{}",
                        rawResultJson = "{}",
                        success = false
                    )
                )
            )
        )

        createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("执行 echo hi"),
                executionEnv = FakeExecutionEnvironment("执行 echo hi")
            )
        )

        assertEquals(listOf("terminal_execute"), toolExecutor.executeCalls)
        assertEquals(2, llmClient.requests.size)
    }

    @Test
    fun interruptedToolResultFeedsNextRoundAndKeepsAgentAlive() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "terminal_execute",
                            arguments = """{"command":"sleep 30"}"""
                        )
                    )
                ),
                assistantTurn(content = "工具已被用户手动停止，我改为直接说明当前状态。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "terminal_execute" to listOf(
                    ToolExecutionResult.Interrupted(
                        toolName = "terminal_execute",
                        summaryText = "工具调用已被用户手动停止",
                        previewJson = """{"status":"interrupted"}""",
                        rawResultJson = """{"status":"interrupted","interruptedBy":"user"}""",
                    )
                )
            )
        )
        val callback = RecordingCallback()

        createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("执行 sleep 30"),
                executionEnv = FakeExecutionEnvironment("执行 sleep 30")
            )
        )

        assertEquals(2, llmClient.requests.size)
        assertEquals("tool", llmClient.requests[1].messages.last().role)
        assertTrue(callback.finalChatMessages().last().contains("用户手动停止"))
    }

    @Test
    fun interruptedVlmTaskStopsWithoutStartingAnotherModelRound() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "vlm_task",
                            arguments = """{"goal":"打开蓝牙"}"""
                        )
                    )
                ),
                assistantTurn(content = "不应该在用户停止 GUI 任务后继续执行。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "vlm_task" to listOf(
                    ToolExecutionResult.Interrupted(
                        toolName = "vlm_task",
                        summaryText = "视觉任务已停止",
                        previewJson =
                            """{"run_id":"gui-run-stopped","status":"interrupted"}""",
                        rawResultJson =
                            """{"run_id":"gui-run-stopped","status":"interrupted"}""",
                        interruptedBy = "user",
                        interruptionReason = "manual_stop",
                    )
                )
            )
        )

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("打开蓝牙"),
                executionEnv = FakeExecutionEnvironment("打开蓝牙")
            )
        )

        assertEquals(1, llmClient.requests.size)
        assertEquals(listOf("vlm_task"), toolExecutor.executeCalls)
        assertTrue(result is AgentResult.Success)
        assertTrue((result as AgentResult.Success).hasUserVisibleOutput)
    }

    @Test
    fun toolHandleIsCreatedBeforeToolStartCallbackBindsCardId() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "browser_use",
                            arguments = """{"action":"navigate","url":"https://example.com"}"""
                        )
                    )
                ),
                assistantTurn(content = "已收到浏览器工具结果。")
            )
        )
        val runControl = TrackingRunControl()
        val callback = CardBindingCallback(runControl, "task-tool-1")

        createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("打开页面"),
                executionEnv = FakeExecutionEnvironment(
                    "打开页面",
                    runControl = runControl
                )
            )
        )

        assertEquals("task-tool-1", runControl.lastHandle?.currentCardId())
    }

    @Test
    fun invalidToolArgumentsAreFedBackAsToolResultInsteadOfStopping() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(toolCall(name = "file_read", arguments = "["))
                ),
                assistantTurn(content = "参数不合法，我改成直接说明原因。")
            )
        )
        val callback = RecordingCallback()

        createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("读取文件"),
                executionEnv = FakeExecutionEnvironment("读取文件")
            )
        )

        assertEquals(2, llmClient.requests.size)
        assertEquals("tool", llmClient.requests[1].messages.last().role)
        assertTrue(callback.finalChatMessages().last().contains("参数不合法"))
    }

    @Test
    fun invalidToolArgumentsBackfillRemainingToolCallIds() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "file_read",
                            arguments = "[",
                            id = "call-read"
                        ),
                        toolCall(
                            name = "file_search",
                            arguments = """{"query":"README"}""",
                            id = "call-search"
                        )
                    )
                ),
                assistantTurn(content = "参数不合法，我改成直接说明原因。")
            )
        )
        val toolExecutor = FakeToolExecutor()
        val callback = RecordingCallback()

        createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("读取文件"),
                executionEnv = FakeExecutionEnvironment("读取文件")
            )
        )

        val toolMessages = llmClient.requests[1].messages.filter { it.role == "tool" }
        assertEquals(2, llmClient.requests.size)
        assertTrue(toolExecutor.executeCalls.isEmpty())
        assertEquals(listOf("call-read", "call-search"), toolMessages.map { it.toolCallId })
        assertTrue(toolMessages.last().content.toString().contains("本轮未执行该工具"))
        assertEquals(listOf("file_read"), callback.toolCallStarts)
        assertEquals(listOf("file_read"), callback.toolCallCompletions)
    }

    @Test
    fun validationFailureIsFedBackAsToolResultInsteadOfStopping() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "file_read",
                            arguments = """{"path":"README.md"}"""
                        )
                    )
                ),
                assistantTurn(content = "校验失败后，我改成文本解释。")
            )
        )
        val toolCatalog = FakeToolCatalog(
            validationErrors = mapOf("file_read" to "缺少必填字段")
        )

        val callback = RecordingCallback()
        AgentOrchestrator(
            llmClient = llmClient,
            toolRegistry = toolCatalog,
            toolRouter = FakeToolExecutor(),
            eventAdapter = AgentEventAdapter(eventJson),
            model = "test-model"
        ).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("读取文件"),
                executionEnv = FakeExecutionEnvironment("读取文件")
            )
        )

        assertEquals(2, llmClient.requests.size)
        assertEquals("tool", llmClient.requests[1].messages.last().role)
        assertTrue(callback.finalChatMessages().last().contains("校验失败"))
    }

    @Test
    fun validationFailureBackfillsRemainingToolCallIds() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "file_read",
                            arguments = """{"path":"README.md"}""",
                            id = "call-read"
                        ),
                        toolCall(
                            name = "file_search",
                            arguments = """{"query":"README"}""",
                            id = "call-search"
                        )
                    )
                ),
                assistantTurn(content = "校验失败后，我改成文本解释。")
            )
        )
        val toolExecutor = FakeToolExecutor()
        val toolCatalog = FakeToolCatalog(
            validationErrors = mapOf("file_read" to "缺少必填字段")
        )
        val callback = RecordingCallback()

        AgentOrchestrator(
            llmClient = llmClient,
            toolRegistry = toolCatalog,
            toolRouter = toolExecutor,
            eventAdapter = AgentEventAdapter(eventJson),
            model = "test-model"
        ).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("读取文件"),
                executionEnv = FakeExecutionEnvironment("读取文件")
            )
        )

        val toolMessages = llmClient.requests[1].messages.filter { it.role == "tool" }
        assertEquals(2, llmClient.requests.size)
        assertTrue(toolExecutor.executeCalls.isEmpty())
        assertEquals(listOf("call-read", "call-search"), toolMessages.map { it.toolCallId })
        assertTrue(toolMessages.last().content.toString().contains("本轮未执行该工具"))
        assertEquals(listOf("file_read"), callback.toolCallStarts)
        assertEquals(listOf("file_read"), callback.toolCallCompletions)
    }

    @Test
    fun borrowedToolExecutorIsNotDisposedByChildOrchestrator() = runBlocking {
        val toolExecutor = FakeToolExecutor()
        val orchestrator = AgentOrchestrator(
            llmClient = FakeLlmClient(listOf(assistantTurn(content = "子任务完成"))),
            toolRegistry = FakeToolCatalog(),
            toolRouter = toolExecutor,
            eventAdapter = AgentEventAdapter(eventJson),
            model = "test-model",
            ownsToolRouter = false
        )

        orchestrator.run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("执行子任务"),
                executionEnv = FakeExecutionEnvironment("执行子任务")
            )
        )

        assertEquals(0, toolExecutor.disposeCalls)
    }

    @Test
    fun promptTokenUsageIsReportedAfterEveryModelTurn() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(toolCall("file_search")),
                    promptTokens = 321
                ),
                assistantTurn(
                    content = "已根据工具结果完成回复。",
                    promptTokens = 654
                )
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "file_search" to listOf(
                    ToolExecutionResult.ContextResult(
                        toolName = "file_search",
                        summaryText = "已找到结果",
                        previewJson = "{}",
                        rawResultJson = "{}",
                        success = true
                    )
                )
            )
        )
        val callback = RecordingCallback()

        createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("搜索配置"),
                executionEnv = FakeExecutionEnvironment("搜索配置")
            )
        )

        assertEquals(listOf(321, 654), callback.promptTokenUpdates)
    }

    @Test
    fun usageSpeedMetricsAreReportedInFinalChatMessage() = runBlocking {
        val callback = RecordingCallback()

        createOrchestrator(
            llmClient = FakeLlmClient(
                turns = listOf(
                    assistantTurn(
                        content = "已完成。",
                        prefillTokensPerSecond = 123.4,
                        decodeTokensPerSecond = 56.7
                    )
                )
            ),
            toolExecutor = FakeToolExecutor()
        ).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("继续"),
                executionEnv = FakeExecutionEnvironment("继续")
            )
        )

        assertNotNull(callback.lastPrefillTokensPerSecond)
        assertNotNull(callback.lastDecodeTokensPerSecond)
        assertEquals(123.4, callback.lastPrefillTokensPerSecond!!, 0.0)
        assertEquals(56.7, callback.lastDecodeTokensPerSecond!!, 0.0)
    }

    @Test
    fun toolResultImageContinuationIsIncludedByDefault() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("browser_use"))),
                assistantTurn(content = "已读取截图结果。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "browser_use" to listOf(
                    ToolExecutionResult.ContextResult(
                        toolName = "browser_use",
                        summaryText = "截图已生成",
                        previewJson = "{}",
                        rawResultJson = "{}",
                        success = true,
                        imageDataUrl = "data:image/jpeg;base64,AAA"
                    )
                )
            )
        )

        createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("看一下页面"),
                executionEnv = FakeExecutionEnvironment("看一下页面")
            )
        )

        val toolMessage = llmClient.requests[1].messages.last()
        assertEquals("tool", toolMessage.role)
        assertTrue(toolMessage.content.toString().contains("\"image_url\""))
    }

    @Test
    fun toolResultImageContinuationIsOmittedWhenPolicyDisablesIt() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("browser_use"))),
                assistantTurn(content = "已按文字摘要继续。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "browser_use" to listOf(
                    ToolExecutionResult.ContextResult(
                        toolName = "browser_use",
                        summaryText = "截图已生成",
                        previewJson = "{}",
                        rawResultJson = "{}",
                        success = true,
                        imageDataUrl = "data:image/jpeg;base64,AAA"
                    )
                )
            )
        )

        createOrchestrator(
            llmClient = llmClient,
            toolExecutor = toolExecutor,
            toolImageContinuationPolicy = AgentToolImageContinuationPolicy(
                supportsToolImageContinuation = false,
                routeLabel = "model=mimo-v2.5"
            )
        ).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("看一下页面"),
                executionEnv = FakeExecutionEnvironment("看一下页面")
            )
        )

        val toolMessage = llmClient.requests[1].messages.last()
        assertEquals("tool", toolMessage.role)
        assertTrue(toolMessage.content is JsonPrimitive)
        assertFalse(toolMessage.content.toString().contains("\"image_url\""))
    }

    @Test
    fun `surfaces transient stream failure without replaying the logical turn`() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(assistantTurn(content = "已在重连后成功完成。")),
            failures = listOf(
                AgentStreamRequestException(
                    statusCode = 503,
                    reason = "upstream temporarily unavailable",
                    responseBody = null
                )
            )
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(
            llmClient = llmClient,
            toolExecutor = FakeToolExecutor()
        ).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("继续执行网页查询"),
                executionEnv = FakeExecutionEnvironment("继续执行网页查询")
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals(1, llmClient.requests.size)
        assertEquals("HTTP 503: upstream temporarily unavailable", callback.errors.single())
        assertTrue(callback.lastErrorRetryable)
        assertTrue(callback.finalChatMessages().isEmpty())
    }

    @Test
    fun `does not replay a provider turn in the orchestrator after client retry policy`() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(assistantTurn(content = "不应被第二次调用")),
            failures = listOf(
                AgentStreamRequestException(
                    statusCode = 503,
                    reason = "upstream temporarily unavailable",
                    responseBody = null
                )
            )
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(
            llmClient = llmClient,
            toolExecutor = FakeToolExecutor()
        ).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("不要重复执行"),
                executionEnv = FakeExecutionEnvironment("不要重复执行")
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals(1, llmClient.requests.size)
        assertTrue(callback.finalChatMessages().isEmpty())
    }

    @Test
    fun `surfaces transient http 500 without replaying the logical turn`() = runBlocking {
        val originalFailure = AgentStreamRequestException(500, "internal server error", null)
        val llmClient = FakeLlmClient(
            turns = listOf(assistantTurn(content = "服务恢复后已完成。")),
            failures = listOf(originalFailure)
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("继续"),
                executionEnv = FakeExecutionEnvironment("继续")
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals(1, llmClient.requests.size)
        assertEquals("HTTP 500: internal server error", callback.errors.single())
        org.junit.Assert.assertSame(originalFailure, (result as AgentResult.Error).exception)
        assertTrue(callback.lastErrorRetryable)
    }

    @Test
    fun `quota failure ends the prompt without replay and leaves explicit retry available`() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = emptyList(),
            failures = listOf(
                AgentStreamRequestException(
                    statusCode = 429,
                    reason = "request rejected",
                    responseBody = """{"error":{"code":"insufficient_quota"}}"""
                )
            )
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("继续"),
                executionEnv = FakeExecutionEnvironment("继续")
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals(1, llmClient.requests.size)
        assertEquals("HTTP 429: request rejected", callback.errors.single())
        assertTrue(callback.lastErrorRetryable)
    }

    @Test
    fun `surfaces retryable transient error without orchestrator retries`() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = emptyList(),
            failures = List(4) {
                AgentStreamRequestException(
                    statusCode = 503,
                    reason = "upstream temporarily unavailable",
                    responseBody = null
                )
            }
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(
            llmClient = llmClient,
            toolExecutor = FakeToolExecutor()
        ).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("继续执行网页查询"),
                executionEnv = FakeExecutionEnvironment("继续执行网页查询")
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals(1, llmClient.requests.size)
        assertEquals(
            "HTTP 503: upstream temporarily unavailable",
            callback.errors.single()
        )
        assertTrue(callback.lastErrorRetryable)
        assertTrue(callback.finalChatMessages().isEmpty())
    }

    @Test
    fun `surfaces non transient api error as manually resumable terminal error`() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = emptyList(),
            failures = listOf(
                AgentStreamRequestException(
                    statusCode = 400,
                    reason = "invalid request payload",
                    responseBody = null
                )
            )
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(
            llmClient = llmClient,
            toolExecutor = FakeToolExecutor()
        ).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("缁х画鎵ц缃戦〉鏌ヨ"),
                executionEnv = FakeExecutionEnvironment("缁х画鎵ц缃戦〉鏌ヨ")
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals("HTTP 400: invalid request payload", callback.errors.single())
        assertTrue(callback.lastErrorRetryable)
        assertTrue(callback.finalChatMessages().isEmpty())
    }

    @Test
    fun `surfaces a provider stream failure without retrying`() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = emptyList(),
            failures = listOf(
                IllegalStateException(
                    "provider stream failed"
                )
            )
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("hello"),
                executionEnv = FakeExecutionEnvironment("hello")
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals(1, llmClient.requests.size)
        assertEquals("provider stream failed", callback.errors.single())
    }

    @Test
    fun `does not expose an incomplete tool call parser error to the user`() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = emptyList(),
            failures = listOf(AgentIncompleteToolCallException(toolCallIndex = 1))
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("hello"),
                executionEnv = FakeExecutionEnvironment("hello")
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals(1, llmClient.requests.size)
        assertTrue(callback.errors.single().contains("Provider"))
        assertFalse(callback.errors.single().contains("missing function.name"))
    }

    @Test
    fun `context overflow ends the current prompt without replacing history or replaying`() = runBlocking {
        val overflow = AgentStreamRequestException(
            statusCode = 400,
            reason = "invalid_request_error",
            responseBody = "Your input exceeds the context window of this model"
        )
        val llmClient = FakeLlmClient(turns = emptyList(), failures = listOf(overflow))
        val callback = RecordingCallback()

        val result = createOrchestrator(
            llmClient = llmClient,
            toolExecutor = FakeToolExecutor()
        ).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("继续长任务"),
                executionEnv = FakeExecutionEnvironment(
                    userMessage = "继续长任务",
                ),
                conversationId = 42L
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals(1, llmClient.requests.size)
        assertEquals("继续长任务", llmClient.requests.single().messages.last().content.toString().trim('"'))
        assertEquals(1, callback.errors.size)
    }

    private fun createOrchestrator(
        llmClient: AgentLlmClient,
        toolExecutor: FakeToolExecutor,
        availableToolNames: Set<String> = emptySet(),
        toolImageContinuationPolicy: AgentToolImageContinuationPolicy =
            AgentToolImageContinuationPolicy.DEFAULT
    ): AgentOrchestrator {
        return AgentOrchestrator(
            llmClient = llmClient,
            toolRegistry = FakeToolCatalog(availableToolNames = availableToolNames),
            toolRouter = toolExecutor,
            eventAdapter = AgentEventAdapter(eventJson),
            model = "test-model",
            toolImageContinuationPolicy = toolImageContinuationPolicy
        )
    }

    private fun initialMessages(userMessage: String): List<ChatCompletionMessage> {
        return listOf(
            ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive(userMessage)
            )
        )
    }

    private fun assistantTurn(
        content: String = "",
        toolCalls: List<AssistantToolCall> = emptyList(),
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        totalTokens: Int? = null,
        prefillTokensPerSecond: Double? = null,
        decodeTokensPerSecond: Double? = null,
        finishReason: String? = null
    ): ChatCompletionTurn {
        return ChatCompletionTurn(
            message = ChatCompletionMessage(
                role = "assistant",
                content = if (content.isBlank()) null else JsonPrimitive(content),
                toolCalls = toolCalls.ifEmpty { null }
            ),
            finishReason = finishReason,
            usage =
                if (
                    promptTokens == null &&
                    completionTokens == null &&
                    totalTokens == null &&
                    prefillTokensPerSecond == null &&
                    decodeTokensPerSecond == null
                ) {
                    null
                } else {
                    ChatCompletionUsage(
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        totalTokens = totalTokens,
                        prefillTokensPerSecond = prefillTokensPerSecond,
                        decodeTokensPerSecond = decodeTokensPerSecond
                    )
                }
        )
    }

    private fun toolCall(
        name: String,
        arguments: String = "{}",
        id: String = "call-$name"
    ): AssistantToolCall {
        return AssistantToolCall(
            id = id,
            function = AssistantToolCallFunction(
                name = name,
                arguments = arguments
            )
        )
    }

    private fun successfulContextResult(toolName: String): ToolExecutionResult.ContextResult {
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = "$toolName succeeded",
            previewJson = "{}",
            rawResultJson = "{}"
        )
    }

    private class FakeLlmClient(
        turns: List<ChatCompletionTurn>,
        reasoningUpdates: List<List<String>> = emptyList(),
        failures: List<Throwable> = emptyList(),
        private val failuresByRequest: Map<Int, Throwable> = emptyMap(),
    ) : AgentLlmClient {
        private val queuedTurns = ArrayDeque(turns)
        private val queuedReasoningUpdates = ArrayDeque(
            reasoningUpdates.map { updates -> ArrayDeque(updates) }
        )
        private val queuedFailures = ArrayDeque(failures)
        val requests = mutableListOf<ChatCompletionRequest>()

        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
            onContentUpdate: (suspend (String) -> Unit)?,
            onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
        ): ChatCompletionTurn {
            requests += request
            failuresByRequest[requests.size]?.let { throw it }
            if (queuedFailures.isNotEmpty()) {
                throw queuedFailures.removeFirst()
            }
            val reasoningQueue = if (queuedReasoningUpdates.isEmpty()) {
                null
            } else {
                queuedReasoningUpdates.removeFirst()
            }
            while (reasoningQueue != null && reasoningQueue.isNotEmpty()) {
                onReasoningUpdate?.invoke(reasoningQueue.removeFirst())
            }
            val turn = queuedTurns.removeFirst()
            val content = turn.message.contentText()
            if (content.isNotBlank()) {
                onContentUpdate?.invoke(content)
            }
            return turn
        }
    }

    private class FakeToolCatalog(
        private val validationErrors: Map<String, String> = emptyMap(),
        availableToolNames: Set<String> = emptySet()
    ) : AgentToolCatalog {
        override val toolsForModel: List<ChatCompletionTool> = availableToolNames.map { toolName ->
            ChatCompletionTool(function = ChatCompletionFunction(name = toolName))
        }

        override fun runtimeDescriptor(toolName: String): AgentToolRegistry.RuntimeToolDescriptor {
            return AgentToolRegistry.RuntimeToolDescriptor(
                name = toolName,
                displayName = toolName,
                toolType = if (toolName.startsWith("terminal")) "terminal" else "builtin"
            )
        }

        override fun validateArguments(toolName: String, arguments: JsonObject) {
            val message = validationErrors[toolName] ?: return
            throw IllegalArgumentException(message)
        }
    }

    private class FakeToolExecutor(
        results: Map<String, List<ToolExecutionResult>> = emptyMap(),
        private val delaysMs: Map<String, Long> = emptyMap()
    ) : AgentToolExecutor {
        private val queuedResults = results.mapValues { (_, value) -> ArrayDeque(value) }
        val executeCalls = mutableListOf<String>()
        val executeArguments = mutableListOf<JsonObject>()
        var disposeCalls: Int = 0

        override suspend fun execute(
            toolCall: AssistantToolCall,
            args: JsonObject,
            runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
            env: AgentExecutionEnvironment,
            callback: AgentCallback,
            toolHandle: AgentToolExecutionHandle
        ): ToolExecutionResult {
            executeCalls += toolCall.function.name
            executeArguments += args
            delaysMs[toolCall.function.name]
                ?.takeIf { it > 0L }
                ?.let { delay(it) }
            val queue = queuedResults[toolCall.function.name]
            return if (queue != null && queue.isNotEmpty()) {
                queue.removeFirst()
            } else {
                ToolExecutionResult.Error(toolCall.function.name, "missing fake result")
            }
        }

        override suspend fun dispose() {
            disposeCalls += 1
        }
    }

    private open class RecordingCallback : AgentCallback {
        val chatMessages = mutableListOf<Pair<String, Boolean>>()
        val promptTokenUpdates = mutableListOf<Int>()
        val toolCallStarts = mutableListOf<String>()
        val toolCallCompletions = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var completedResult: AgentResult? = null
        var lastPrefillTokensPerSecond: Double? = null
        var lastDecodeTokensPerSecond: Double? = null
        var lastErrorRetryable: Boolean = false

        override suspend fun onThinkingStart() = Unit

        override suspend fun onThinkingUpdate(thinking: String) = Unit

        open override suspend fun onToolCallStart(toolName: String, arguments: JsonObject) {
            toolCallStarts += toolName
        }

        override suspend fun onToolCallStart(
            toolCallId: String,
            toolName: String,
            arguments: JsonObject,
            toolType: String?,
        ) {
            onToolCallStart(toolName, arguments)
        }

        override suspend fun onToolCallProgress(
            toolName: String,
            progress: String,
            extras: Map<String, Any?>
        ) = Unit

        override suspend fun onToolCallComplete(
            toolName: String,
            result: ToolExecutionResult
        ) {
            toolCallCompletions += toolName
        }

        override suspend fun onToolCallComplete(
            toolCallId: String,
            toolName: String,
            result: ToolExecutionResult,
        ) {
            onToolCallComplete(toolName, result)
        }

        override suspend fun onChatMessage(message: String) {
            chatMessages += message to true
        }

        override suspend fun onChatMessage(message: String, isFinal: Boolean) {
            chatMessages += message to isFinal
        }

        override suspend fun onChatMessage(
            message: String,
            isFinal: Boolean,
            prefillTokensPerSecond: Double?,
            decodeTokensPerSecond: Double?
        ) {
            chatMessages += message to isFinal
            lastPrefillTokensPerSecond = prefillTokensPerSecond
            lastDecodeTokensPerSecond = decodeTokensPerSecond
        }

        override suspend fun onPromptTokenUsageChanged(
            latestPromptTokens: Int,
            promptTokenThreshold: Int?
        ) {
            promptTokenUpdates += latestPromptTokens
        }

        override suspend fun onClarifyRequired(
            question: String,
            missingFields: List<String>?
        ) = Unit

        override suspend fun onComplete(result: AgentResult) {
            completedResult = result
        }

        override suspend fun onError(error: String) {
            errors += error
        }

        override suspend fun onError(error: String, retryable: Boolean) {
            lastErrorRetryable = retryable
            onError(error)
        }

        override suspend fun onPermissionRequired(missing: List<String>) = Unit

        fun finalChatMessages(): List<String> {
            return chatMessages.filter { it.second }.map { it.first }
        }
    }

    private class ThinkingCaptureCallback : RecordingCallback() {
        val thinkingUpdates = mutableListOf<String>()

        override suspend fun onThinkingUpdate(thinking: String) {
            thinkingUpdates += thinking
        }
    }

    private class CardBindingCallback(
        private val runControl: TrackingRunControl,
        private val cardId: String
    ) : RecordingCallback() {
        override suspend fun onToolCallStart(toolName: String, arguments: JsonObject) {
            runControl.bindCurrentCardId(cardId)
        }
    }

    private class FakeExecutionEnvironment(
        override val userMessage: String,
        override val conversationMode: String = "normal",
        override val reasoningEffort: String? = null,
        override val runControl: AgentRunControl = NoOpAgentRunControl,
        override val resolvedSkills: List<ResolvedSkillContext> = emptyList(),
    ) : AgentExecutionEnvironment {
        override val agentRunId: String = "test-run"
        override val runtimeContextRepository: AgentRuntimeContextRepository
            get() = throw UnsupportedOperationException("unused in test")
        override val workspaceDescriptor: AgentWorkspaceDescriptor
            get() = throw UnsupportedOperationException("unused in test")
        override val workspaceManager: AgentWorkspaceManager
            get() = throw UnsupportedOperationException("unused in test")
        override val workspaceMemoryService: WorkspaceMemoryService
            get() = throw UnsupportedOperationException("unused in test")
        override val terminalEnvironment: Map<String, String> = emptyMap()
    }

    private class TrackingRunControl : AgentRunControl {
        var lastHandle: TrackingHandle? = null

        override fun beginToolExecution(
            toolName: String,
            toolCallId: String
        ): AgentToolExecutionHandle {
            return TrackingHandle(
                toolName = toolName,
                toolCallId = toolCallId
            ).also { handle ->
                lastHandle = handle
            }
        }

        fun bindCurrentCardId(cardId: String) {
            lastHandle?.bindCardId(cardId)
        }
    }

    private class TrackingHandle(
        override val toolName: String,
        override val toolCallId: String
    ) : AgentToolExecutionHandle {
        override val generation: Long = 1L
        private var cardId: String? = null

        override fun bindCardId(cardId: String) {
            this.cardId = cardId
        }

        override fun currentCardId(): String? = cardId

        override fun bindExecutionJob(job: Job) = Unit

        override fun bindStopAction(action: (suspend () -> Unit)?) = Unit

        override fun recordProgress(summary: String, extras: Map<String, Any?>) = Unit

        override fun latestProgressSnapshot(): AgentToolProgressSnapshot =
            AgentToolProgressSnapshot()

        override fun isManualStopRequested(): Boolean = false

        override fun throwIfStopRequested() = Unit

        override fun complete() = Unit
    }
}
