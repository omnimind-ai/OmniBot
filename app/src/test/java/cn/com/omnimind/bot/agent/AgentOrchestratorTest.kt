package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionUsage
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class AgentOrchestratorTest {
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
    fun inputLimitsModelRoundsAndCompletionTokens() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("file_read")))
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
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("读取文件后继续"),
                executionEnv = FakeExecutionEnvironment("读取文件后继续"),
                maxModelRounds = 1,
                maxCompletionTokens = 4096
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals(1, llmClient.requests.size)
        assertEquals(4096, llmClient.requests.single().maxCompletionTokens)
        assertTrue(callback.errors.single().contains("1 轮模型调用上限"))
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

        createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
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
    fun intermediateTextAfterToolChainAlsoTriggersRecoveryRound() = runBlocking {
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

        assertEquals(listOf("browser_use"), toolExecutor.executeCalls)
        assertEquals(4, llmClient.requests.size)
        assertEquals("user", llmClient.requests[3].messages.last().role)
        assertTrue(
            llmClient.requests[3].messages.last().contentText().contains("完整最终答案")
        )
        assertTrue(callback.finalChatMessages().last().contains("建议继续筛选"))
        assertTrue(result is AgentResult.Success)
    }

    @Test
    fun actionIntentRecoveryStopsAfterSingleGuardRound() = runBlocking {
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

        assertEquals(2, llmClient.requests.size)
        assertEquals("让我再检查一下更多信息。", callback.finalChatMessages().last())
        assertTrue(result is AgentResult.Success)
    }

    @Test
    fun loadedSkillCompletionToolsPreventEarlyStopBeforeProjectPublish() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("file_write"))),
                assistantTurn(content = "页面已经创建完成。", finishReason = "stop"),
                assistantTurn(toolCalls = listOf(toolCall("project_check"))),
                assistantTurn(content = "项目检查完成。", finishReason = "stop"),
                assistantTurn(toolCalls = listOf(toolCall("project_publish"))),
                assistantTurn(content = "插件已发布，可以直接打开。", finishReason = "stop")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "file_write" to listOf(successfulContextResult("file_write")),
                "project_check" to listOf(successfulContextResult("project_check")),
                "project_publish" to listOf(successfulContextResult("project_publish"))
            )
        )
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
            availableToolNames = setOf("file_write", "project_check", "project_publish")
        ).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("创建一个 NBA HTML 应用"),
                executionEnv = FakeExecutionEnvironment(
                    userMessage = "创建一个 NBA HTML 应用",
                    resolvedSkills = listOf(completionSkill)
                )
            )
        )

        assertEquals(
            listOf("file_write", "project_check", "project_publish"),
            toolExecutor.executeCalls
        )
        assertEquals(6, llmClient.requests.size)
        assertTrue(result is AgentResult.Success)
    }

    @Test
    fun projectCheckBeforeFileWriteDoesNotSatisfySkillCompletionContract() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(toolCalls = listOf(toolCall("project_check"))),
                assistantTurn(toolCalls = listOf(toolCall("file_write"))),
                assistantTurn(content = "文件已经更新完成。", finishReason = "stop"),
                assistantTurn(toolCalls = listOf(toolCall("project_check"))),
                assistantTurn(toolCalls = listOf(toolCall("project_publish"))),
                assistantTurn(content = "插件已重新检查并发布。", finishReason = "stop")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "project_check" to listOf(
                    successfulContextResult("project_check"),
                    successfulContextResult("project_check")
                ),
                "file_write" to listOf(successfulContextResult("file_write")),
                "project_publish" to listOf(successfulContextResult("project_publish"))
            )
        )
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
            availableToolNames = setOf("file_write", "project_check", "project_publish")
        ).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("更新现有 HTML 应用"),
                executionEnv = FakeExecutionEnvironment(
                    userMessage = "更新现有 HTML 应用",
                    resolvedSkills = listOf(completionSkill)
                )
            )
        )

        assertEquals(
            listOf("project_check", "file_write", "project_check", "project_publish"),
            toolExecutor.executeCalls
        )
        assertEquals(6, llmClient.requests.size)
        assertTrue(result is AgentResult.Success)
    }

    @Test
    fun traceStyleRetryIntentStillStopsAfterSingleGuardRound() = runBlocking {
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

        assertEquals(2, llmClient.requests.size)
        assertEquals("让我最后一次尝试返回首页。", callback.finalChatMessages().last())
        assertTrue(result is AgentResult.Success)
    }

    @Test
    fun lengthFinishReasonContinuesAndPublishesCombinedFinalText() = runBlocking {
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

        assertEquals(2, llmClient.requests.size)
        assertEquals("user", llmClient.requests[1].messages.last().role)
        assertTrue(
            llmClient.requests[1].messages.last().contentText().contains("输出长度上限")
        )
        assertEquals("第一段还没说完，后续完成。", callback.finalChatMessages().last())
        assertTrue(callback.chatMessages.any { it.first == "第一段还没说完" && !it.second })
        assertTrue(callback.chatMessages.any { it.first == "第一段还没说完，后续完成。" && !it.second })
        assertTrue(result is AgentResult.Success)
        assertEquals("stop", (result as AgentResult.Success).response.finishReason)
    }

    @Test
    fun lengthContinuationStopsAfterGuardLimit() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(content = "A", finishReason = "length"),
                assistantTurn(content = "B", finishReason = "length"),
                assistantTurn(content = "C", finishReason = "length"),
                assistantTurn(content = "D", finishReason = "length")
            )
        )
        val callback = RecordingCallback()

        val result = createOrchestrator(llmClient, FakeToolExecutor()).run(
            AgentOrchestrator.Input(
                callback = callback,
                initialMessages = initialMessages("持续输出"),
                executionEnv = FakeExecutionEnvironment("持续输出")
            )
        )

        assertEquals(4, llmClient.requests.size)
        assertEquals("ABCD", callback.finalChatMessages().last())
        assertTrue(result is AgentResult.Success)
        assertEquals("length", (result as AgentResult.Success).response.finishReason)
    }

    @Test
    fun lengthTruncatedToolCallIsRejectedAndOnlyCompleteReissueExecutes() = runBlocking {
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
        assertTrue(rejectedResult.contentText().contains("参数可能被截断"))
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

        createOrchestrator(llmClient, toolExecutor).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("读取文件"),
                executionEnv = FakeExecutionEnvironment("读取文件")
            )
        )

        val toolMessages = llmClient.requests[1].messages.filter { it.role == "tool" }
        assertEquals(2, llmClient.requests.size)
        assertTrue(toolExecutor.executeCalls.isEmpty())
        assertEquals(listOf("call-read", "call-search"), toolMessages.map { it.toolCallId })
        assertTrue(toolMessages.last().content.toString().contains("本轮未执行该工具"))
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
        val callback = RecordingCallback()
        val toolCatalog = FakeToolCatalog(
            validationErrors = mapOf("file_read" to "缺少必填字段")
        )

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

        AgentOrchestrator(
            llmClient = llmClient,
            toolRegistry = toolCatalog,
            toolRouter = toolExecutor,
            eventAdapter = AgentEventAdapter(eventJson),
            model = "test-model"
        ).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("读取文件"),
                executionEnv = FakeExecutionEnvironment("读取文件")
            )
        )

        val toolMessages = llmClient.requests[1].messages.filter { it.role == "tool" }
        assertEquals(2, llmClient.requests.size)
        assertTrue(toolExecutor.executeCalls.isEmpty())
        assertEquals(listOf("call-read", "call-search"), toolMessages.map { it.toolCallId })
        assertTrue(toolMessages.last().content.toString().contains("本轮未执行该工具"))
    }

    @Test
    fun exclusiveToolBackfillsRemainingToolCallIds() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    toolCalls = listOf(
                        toolCall(
                            name = "terminal_execute",
                            arguments = """{"command":"echo hi"}""",
                            id = "call-terminal"
                        ),
                        toolCall(
                            name = "file_search",
                            arguments = """{"query":"README"}""",
                            id = "call-search"
                        )
                    )
                ),
                assistantTurn(content = "终端命令执行后，我改成直接说明状态。")
            )
        )
        val toolExecutor = FakeToolExecutor(
            results = mapOf(
                "terminal_execute" to listOf(
                    ToolExecutionResult.TerminalResult(
                        toolName = "terminal_execute",
                        summaryText = "命令执行完成",
                        previewJson = "{}",
                        rawResultJson = "{}",
                        success = true
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

        val toolMessages = llmClient.requests[1].messages.filter { it.role == "tool" }
        assertEquals(2, llmClient.requests.size)
        assertEquals(listOf("terminal_execute"), toolExecutor.executeCalls)
        assertEquals(listOf("call-terminal", "call-search"), toolMessages.map { it.toolCallId })
        assertTrue(toolMessages.last().content.toString().contains("本轮未执行该工具"))
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
    fun `retries transient stream failures before succeeding`() = runBlocking {
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

        assertTrue(result is AgentResult.Success)
        assertEquals(1, callback.retryingEvents.size)
        assertEquals("已在重连后成功完成。", callback.finalChatMessages().last())
        assertTrue(callback.errors.isEmpty())
        assertFalse(callback.lastErrorRetryable)
    }

    @Test
    fun `retries transient http 500 before succeeding`() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(assistantTurn(content = "服务恢复后已完成。")),
            failures = listOf(
                AgentStreamRequestException(
                    statusCode = 500,
                    reason = "internal server error",
                    responseBody = null
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

        assertTrue(result is AgentResult.Success)
        assertEquals(2, llmClient.requests.size)
        assertEquals(1, callback.retryingEvents.size)
    }

    @Test
    fun `does not retry quota exhausted 429`() = runBlocking {
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
        assertTrue(callback.retryingEvents.isEmpty())
        assertEquals("HTTP 429: request rejected", callback.errors.single())
    }

    @Test
    fun `surfaces retryable terminal error after exhausting transient retries`() = runBlocking {
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
        assertEquals(3, callback.retryingEvents.size)
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
        assertTrue(callback.retryingEvents.isEmpty())
        assertEquals("invalid request payload", callback.errors.single())
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
        assertTrue(callback.retryingEvents.isEmpty())
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
        assertTrue(callback.retryingEvents.isEmpty())
        assertTrue(callback.errors.single().contains("Provider"))
        assertFalse(callback.errors.single().contains("missing function.name"))
    }

    @Test
    fun `detects provider context overflow without confusing throttling`() {
        assertTrue(
            isContextOverflowTurnFailure(
                AgentStreamRequestException(
                    statusCode = 400,
                    reason = "invalid_request_error",
                    responseBody = "Your input exceeds the context window of this model"
                )
            )
        )
        assertTrue(
            isContextOverflowTurnFailure(
                AgentStreamRequestException(
                    statusCode = 400,
                    reason = "invalid_parameter_error",
                    responseBody = "Range of input length should be [1, 131072]"
                )
            )
        )
        assertFalse(
            isContextOverflowTurnFailure(
                AgentStreamRequestException(
                    statusCode = 429,
                    reason = "rate limit",
                    responseBody = "Too many tokens were submitted this minute"
                )
            )
        )
    }

    @Test
    fun `detects zero output length stop only when prompt fills context`() {
        assertTrue(
            isLengthStopAtContextCapacity(
                finishReason = "length",
                promptTokens = 127_000,
                completionTokens = 0,
                contextCapacityTokens = 128_000
            )
        )
        assertFalse(
            isLengthStopAtContextCapacity(
                finishReason = "length",
                promptTokens = 100_000,
                completionTokens = 0,
                contextCapacityTokens = 128_000
            )
        )
        assertFalse(
            isLengthStopAtContextCapacity(
                finishReason = "length",
                promptTokens = 127_000,
                completionTokens = 1,
                contextCapacityTokens = 128_000
            )
        )
    }

    @Test
    fun `context overflow compacts and retries once without consuming round budget`() = runBlocking {
        val overflow = AgentStreamRequestException(
            statusCode = 400,
            reason = "invalid_request_error",
            responseBody = "Your input exceeds the context window of this model"
        )
        val llmClient = FakeLlmClient(
            turns = listOf(assistantTurn(content = "压缩后完成。")),
            failures = listOf(overflow)
        )
        val compactor = FakeContextCompactor()

        val result = createOrchestrator(
            llmClient = llmClient,
            toolExecutor = FakeToolExecutor()
        ).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("继续长任务"),
                executionEnv = FakeExecutionEnvironment("继续长任务"),
                conversationId = 42L,
                contextCompactor = compactor,
                maxModelRounds = 1
            )
        )

        assertFalse(result is AgentResult.Error)
        assertEquals(2, llmClient.requests.size)
        assertEquals(1, compactor.overflowCompactionCalls)
        assertEquals("[compacted]", llmClient.requests.last().messages.first().content.toString().trim('"'))
    }

    @Test
    fun `second context overflow stops instead of looping compaction`() = runBlocking {
        val overflow = AgentStreamRequestException(
            statusCode = 400,
            reason = "context_length_exceeded",
            responseBody = null
        )
        val llmClient = FakeLlmClient(
            turns = emptyList(),
            failures = listOf(overflow, overflow)
        )
        val compactor = FakeContextCompactor()

        val result = createOrchestrator(
            llmClient = llmClient,
            toolExecutor = FakeToolExecutor()
        ).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("继续长任务"),
                executionEnv = FakeExecutionEnvironment("继续长任务"),
                conversationId = 42L,
                contextCompactor = compactor,
                maxModelRounds = 1
            )
        )

        assertTrue(result is AgentResult.Error)
        assertEquals(2, llmClient.requests.size)
        assertEquals(1, compactor.overflowCompactionCalls)
    }

    @Test
    fun `zero output length stop at context capacity compacts and retries once`() = runBlocking {
        val llmClient = FakeLlmClient(
            turns = listOf(
                assistantTurn(
                    promptTokens = 127_000,
                    completionTokens = 0,
                    finishReason = "length"
                ),
                assistantTurn(content = "压缩后恢复输出。")
            )
        )
        val compactor = FakeContextCompactor()

        val result = createOrchestrator(
            llmClient = llmClient,
            toolExecutor = FakeToolExecutor()
        ).run(
            AgentOrchestrator.Input(
                callback = RecordingCallback(),
                initialMessages = initialMessages("继续长任务"),
                executionEnv = FakeExecutionEnvironment("继续长任务"),
                conversationId = 42L,
                contextCompactor = compactor,
                maxModelRounds = 1
            )
        )

        assertFalse(result is AgentResult.Error)
        assertEquals(2, llmClient.requests.size)
        assertEquals(1, compactor.overflowCompactionCalls)
    }

    private fun createOrchestrator(
        llmClient: FakeLlmClient,
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
        failures: List<Throwable> = emptyList()
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
            onContentUpdate: (suspend (String) -> Unit)?
        ): ChatCompletionTurn {
            requests += request
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
        results: Map<String, List<ToolExecutionResult>> = emptyMap()
    ) : AgentToolExecutor {
        private val queuedResults = results.mapValues { (_, value) -> ArrayDeque(value) }
        val executeCalls = mutableListOf<String>()

        override suspend fun execute(
            toolCall: AssistantToolCall,
            args: JsonObject,
            runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
            env: AgentExecutionEnvironment,
            callback: AgentCallback,
            toolHandle: AgentToolExecutionHandle
        ): ToolExecutionResult {
            executeCalls += toolCall.function.name
            val queue = queuedResults[toolCall.function.name]
            return if (queue != null && queue.isNotEmpty()) {
                queue.removeFirst()
            } else {
                ToolExecutionResult.Error(toolCall.function.name, "missing fake result")
            }
        }
    }

    private class FakeContextCompactor : AgentContextCompactionController {
        var overflowCompactionCalls = 0

        override suspend fun resolvePromptTokenThreshold(conversationId: Long?): Int = 128_000

        override suspend fun compactIfNeeded(
            conversationId: Long?,
            conversationMode: String,
            promptTokens: Int?,
            messages: List<ChatCompletionMessage>,
            contextTokens: Int?,
            promptTokenThresholdOverride: Int?,
            callback: AgentCallback?
        ): List<ChatCompletionMessage> = messages

        override suspend fun compactForOverflow(
            conversationId: Long?,
            conversationMode: String,
            latestPromptTokens: Int?,
            messages: List<ChatCompletionMessage>,
            promptTokenThresholdOverride: Int?,
            callback: AgentCallback?
        ): List<ChatCompletionMessage> {
            overflowCompactionCalls += 1
            return listOf(
                ChatCompletionMessage(
                    role = "user",
                    content = JsonPrimitive("[compacted]")
                )
            )
        }
    }

    private open class RecordingCallback : AgentCallback {
        val chatMessages = mutableListOf<Pair<String, Boolean>>()
        val promptTokenUpdates = mutableListOf<Int>()
        val errors = mutableListOf<String>()
        val retryingEvents = mutableListOf<Triple<Int, Int, Long>>()
        var completedResult: AgentResult? = null
        var lastPrefillTokensPerSecond: Double? = null
        var lastDecodeTokensPerSecond: Double? = null
        var lastErrorRetryable: Boolean = false

        override suspend fun onThinkingStart() = Unit

        override suspend fun onThinkingUpdate(thinking: String) = Unit

        open override suspend fun onToolCallStart(toolName: String, arguments: JsonObject) = Unit

        override suspend fun onToolCallProgress(
            toolName: String,
            progress: String,
            extras: Map<String, Any?>
        ) = Unit

        override suspend fun onToolCallComplete(
            toolName: String,
            result: ToolExecutionResult
        ) = Unit

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

        override suspend fun onRetrying(
            retryCount: Int,
            maxRetries: Int,
            retryDelayMs: Long,
            message: String,
            retryReason: String?
        ) {
            retryingEvents += Triple(retryCount, maxRetries, retryDelayMs)
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
        override val resolvedSkills: List<ResolvedSkillContext> = emptyList()
    ) : AgentExecutionEnvironment {
        override val agentRunId: String = "test-run"
        override val runtimeContextRepository: AgentRuntimeContextRepository
            get() = throw UnsupportedOperationException("unused in test")
        override val workspaceDescriptor: AgentWorkspaceDescriptor
            get() = throw UnsupportedOperationException("unused in test")
        override val failureLearningSkill: ResolvedSkillContext?
            get() = null
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
