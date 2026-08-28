package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.bot.agent.workspace.memory.LongTermMemoryIndex
import cn.com.omnimind.bot.agent.workspace.memory.TurnMemoryLoadTracker
import cn.com.omnimind.bot.agent.tool.AgentToolHandlerModule
import cn.com.omnimind.bot.plugin.OmniPluginHost
import cn.com.omnimind.bot.plugin.OmniPluginSession
import com.rk.terminal.runtime.TerminalDistribution
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class OmniAgentExecutor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scheduleToolBridge: AgentScheduleToolBridge
) {
    internal data class TimeContextSnapshot(
        val locale: cn.com.omnimind.baselib.i18n.PromptLocale,
        val zoneId: String,
        val generatedAt: ZonedDateTime,
        val content: String
    )

    companion object {
        /**
         * Keep a clean native-tool baseline while MCP/plugin discovery is
         * being measured. The capability implementations remain installed;
         * this switch only prevents them from entering a normal Agent turn.
         */
        private const val EPHEMERAL_CACHE_TYPE = "ephemeral"
        internal const val TIME_CONTEXT_MIN_REFRESH_MILLIS = 60 * 60 * 1000L
        private val timeContextCacheLock = Any()
        @Volatile
        private var timeContextSnapshot: TimeContextSnapshot? = null

        internal fun buildCachedSystemPromptContent(prompt: String): JsonElement {
            return buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", prompt)
                        put("cache_control", buildJsonObject {
                            put("type", EPHEMERAL_CACHE_TYPE)
                        })
                    }
                )
            }
        }

        internal fun resolveTimeContextSnapshot(
            cached: TimeContextSnapshot?,
            now: ZonedDateTime,
            locale: cn.com.omnimind.baselib.i18n.PromptLocale
        ): TimeContextSnapshot {
            if (
                cached != null &&
                cached.locale == locale &&
                cached.zoneId == now.zone.id &&
                cached.generatedAt.toLocalDate() == now.toLocalDate()
            ) {
                val elapsedMillis = Duration.between(cached.generatedAt, now).toMillis()
                if (elapsedMillis in 0 until TIME_CONTEXT_MIN_REFRESH_MILLIS) {
                    return cached
                }
            }
            return TimeContextSnapshot(
                locale = locale,
                zoneId = now.zone.id,
                generatedAt = now,
                content = buildTimeContextContent(now, locale)
            )
        }

        internal fun buildTimeContextContent(
            now: ZonedDateTime,
            locale: cn.com.omnimind.baselib.i18n.PromptLocale
        ): String {
            val zoneId = now.zone
            return when (locale) {
                cn.com.omnimind.baselib.i18n.PromptLocale.ZH_CN -> """
                    [time_context]
                    本地日期: ${now.toLocalDate()}
                    时区: ${zoneId.id}
                    星期: ${now.dayOfWeek.name}
                    这是最多复用 1 小时的粗粒度日期上下文，只用于解释“今天”“明天”等相对日期。需要精确当前时间时必须调用 `context_time_now`；不要把本上下文当作用户原文或长期记忆。
                """.trimIndent()

                cn.com.omnimind.baselib.i18n.PromptLocale.EN_US -> """
                    [time_context]
                    Local date: ${now.toLocalDate()}
                    Timezone: ${zoneId.id}
                    Day of week: ${now.dayOfWeek.name}
                    This coarse date context is reused for up to one hour and only interprets relative dates such as "today" and "tomorrow". You must call `context_time_now` when the exact current time is needed. Do not treat this context as user-authored text or long-term memory.
                """.trimIndent()
            }
        }

        internal fun mergeInitialPromptMessages(
            leadingMessages: List<ChatCompletionMessage>,
            historyMessages: List<ChatCompletionMessage>,
            currentUserMessage: ChatCompletionMessage,
            continueMode: Boolean
        ): List<ChatCompletionMessage> {
            val replayHistory = historyMessages.toMutableList()
            val latestHistoryUser = replayHistory.lastOrNull()
                ?.takeIf { message ->
                    message.role == "user" &&
                        !AgentConversationHistorySupport.isContextSummaryMessage(message)
                }
                ?.also { replayHistory.removeAt(replayHistory.lastIndex) }
            val messages = mutableListOf<ChatCompletionMessage>()
            messages.addAll(leadingMessages)
            messages.addAll(replayHistory)
            if (continueMode) {
                when {
                    latestHistoryUser != null -> messages.add(latestHistoryUser)
                    replayHistory.none(::isUserTurnMessage) -> messages.add(currentUserMessage)
                }
            } else {
                messages.add(currentUserMessage)
            }
            return messages
        }

        /**
         * Pure chat shares the ACP transport, but it is not an Agent replay.
         * Never carry tool calls/results (or an assistant placeholder that only
         * represented a tool turn) into a no-tools request. Apart from making
         * the provider interpret old execution state as current context, that
         * can make the UI restore tool/thinking cards for a pure-chat turn.
         */
        internal fun filterChatOnlyHistoryMessages(
            historyMessages: List<ChatCompletionMessage>
        ): List<ChatCompletionMessage> {
            return historyMessages.mapNotNull { message ->
                when (message.role.trim().lowercase()) {
                    "user" -> message.takeIf { it.content != null }
                    "assistant" -> {
                        message.takeIf { it.content != null }?.copy(
                            toolCalls = null,
                            toolCallId = null,
                            name = null,
                            reasoningContent = null
                        )
                    }
                    else -> null
                }
            }
        }

        private fun isUserTurnMessage(message: ChatCompletionMessage): Boolean {
            return message.role == "user" &&
                !AgentConversationHistorySupport.isContextSummaryMessage(message)
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }
    private val agentModelScene = "scene.dispatch.model"

    suspend fun processUserMessage(
        userMessage: String,
        conversationHistory: List<Map<String, Any?>>,
        runtimeContextRepository: AgentRuntimeContextRepository,
        attachments: List<Map<String, Any?>>,
        conversationId: Long?,
        conversationMode: String,
        modelOverride: AgentModelOverride?,
        reasoningEffort: String?,
        terminalEnvironment: Map<String, String>,
        callback: AgentCallback,
        runControl: AgentRunControl = NoOpAgentRunControl,
        permissionRequester: AgentPermissionRequester? = null,
        continueMode: Boolean = false,
        historyMessagesOverride: List<ChatCompletionMessage>? = null
    ): AgentResult {
        var toolRouter: AgentToolRouter? = null
        var pluginSession: OmniPluginSession? = null
        return try {
            val agentRunId = UUID.randomUUID().toString()
            val promptCacheKey = cn.com.omnimind.baselib.llm.PromptCacheKeyStore.forConversation(
                context,
                conversationId
            )
            val terminalDistribution = TerminalDistribution.selected()
            val workspaceManager = AgentWorkspaceManager(context)
            val memoryService = WorkspaceMemoryService(context, workspaceManager)
            val workspaceDescriptor = workspaceManager.buildWorkspaceDescriptor(
                conversationId = conversationId,
                agentRunId = agentRunId
            )
            val historyRepository = AgentConversationHistoryRepository(context)
            val promptIdentityContext = runCatching {
                WorkspaceMemoryPromptContext(
                    soul = memoryService.readSoul().trim(),
                    longTermMemory = "",
                    todayShortMemory = "",
                    longTermIndexSummary = ""
                )
            }.getOrNull()
            val ltmIndex = runCatching {
                LongTermMemoryIndex(workspaceManager)
            }.getOrNull()
            val memoryLoadTracker = TurnMemoryLoadTracker()
            val skillIndexService = SkillIndexService(context, workspaceManager)
            val skillLoader = SkillLoader(workspaceManager)
            val installedSkills = skillIndexService.listInstalledSkills()
                .map { skill ->
                    skill.copy(
                        description = AgentTerminalDistributionText.resolve(
                            skill.description,
                            terminalDistribution
                        )
                    )
                }
                .sortedBy { it.id.lowercase() }
            val failureLearningSkill = SelfImprovingSkillFailureHook.resolveInstalledSkill(
                installedSkills = installedSkills,
                skillLoader = skillLoader
            )
            // Pi-style progressive disclosure: skill bodies are loaded through
            // skills_read and become replayable tool results instead of a volatile
            // leading message that invalidates the full conversation prefix.
            val resolvedSkills = emptyList<ResolvedSkillContext>()
            // chat_only is still an ACP turn, but it has no tool capability.
            // Do not initialize the plugin/MCP session only to discard every
            // definition a few lines later; this keeps pure chat independent
            // from plugin startup while preserving the normal Agent catalog.
            val activePluginSession = if (
                AgentRuntimeFeatureFlags.ENABLE_PLUGIN_RUNTIME &&
                !AgentConversationModePolicy.isChatOnlyMode(conversationMode)
            ) {
                OmniPluginHost.get(context).openSession()
            } else {
                null
            }
            pluginSession = activePluginSession
            val toolRegistry = AgentToolRegistry(
                context = context,
                conversationMode = conversationMode,
                terminalDistribution = terminalDistribution,
                pluginToolDefinitions = activePluginSession?.toolDefinitions.orEmpty(),
                userMessage = userMessage,
                toolRoutingMode = AgentToolRoutingMode.fromSkillFrontmatter(
                    resolvedSkills.map(ResolvedSkillContext::frontmatter),
                ),
            )
            val initialMessages = buildInitialMessages(
                promptSeed = historyRepository.buildPromptSeed(
                    conversationId = conversationId,
                    conversationMode = conversationMode
                ),
                userMessage = userMessage,
                attachments = attachments,
                continueMode = continueMode,
                workspaceDescriptor = workspaceDescriptor,
                installedSkills = installedSkills,
                skillsRootShellPath = workspaceManager.shellPathForAndroid(workspaceManager.skillsRoot())
                    ?: workspaceManager.skillsRoot().absolutePath,
                skillsRootAndroidPath = workspaceManager.skillsRoot().absolutePath,
                resolvedSkills = resolvedSkills,
                memoryContext = promptIdentityContext,
                terminalDistribution = terminalDistribution,
                conversationMode = conversationMode,
                historyMessagesOverride = historyMessagesOverride
            )

            val llmClient = HttpAgentLlmClient(
                scope = scope,
                json = json,
                modelOverride = modelOverride
            )
            val toolImageContinuationPolicy = runCatching {
                AgentToolImageContinuationPolicyResolver.resolve(
                    HttpController.resolveChatCompletionRouteInfo(
                        modelOrScene = agentModelScene,
                        explicitApiBase = modelOverride?.apiBase,
                        explicitApiKey = modelOverride?.apiKey,
                        explicitCustomHeaders = modelOverride?.customHeaders,
                        explicitModel = modelOverride?.modelId,
                        explicitProtocolType = modelOverride?.protocolType,
                        explicitWireApi = modelOverride?.wireApi
                    )
                )
            }.getOrDefault(AgentToolImageContinuationPolicy.DEFAULT)
            val contextCompactor = AgentConversationContextCompactor(
                historyRepository = historyRepository,
                modelScene = agentModelScene,
                modelOverride = modelOverride,
                reasoningEffort = reasoningEffort,
                promptCacheKey = promptCacheKey,
                json = json
            )
            val eventAdapter = AgentEventAdapter(json)
            // Break the SubagentDispatcher ↔ AgentToolRouter cycle: hand the
            // dispatcher a lazy reference to the router that we'll populate
            // immediately after the router is constructed.
            val routerRef = AtomicReference<AgentToolExecutor?>()
            val catalogRef = AtomicReference<AgentToolCatalog?>(toolRegistry)
            val subagentDispatcher = SubagentDispatcher(
                llmClient = llmClient,
                toolExecutorProvider = {
                    routerRef.get() ?: error("subagent dispatcher invoked before router was bound")
                },
                parentCatalogProvider = {
                    catalogRef.get() ?: error("subagent dispatcher missing parent catalog")
                },
                eventAdapter = eventAdapter,
                model = agentModelScene,
                toolImageContinuationPolicy = toolImageContinuationPolicy
            )
            toolRouter = AgentToolRouter(
                context = context,
                scope = scope,
                scheduleToolBridge = scheduleToolBridge,
                workspaceManager = workspaceManager,
                subagentDispatcher = subagentDispatcher,
                toolCatalog = toolRegistry,
                terminalDistribution = terminalDistribution,
                capabilityModules = if (activePluginSession != null) {
                    listOf(AgentToolHandlerModule(activePluginSession.toolHandlers))
                } else {
                    emptyList()
                }
            )
            pluginSession = null
            routerRef.set(toolRouter)
            val orchestrator = AgentOrchestrator(
                llmClient = llmClient,
                toolRegistry = toolRegistry,
                toolRouter = toolRouter,
                eventAdapter = eventAdapter,
                model = agentModelScene,
                toolImageContinuationPolicy = toolImageContinuationPolicy
            )

            orchestrator.run(
                AgentOrchestrator.Input(
                    callback = callback,
                    initialMessages = initialMessages,
                    conversationId = conversationId,
                    promptCacheKey = promptCacheKey,
                    contextCompactor = contextCompactor,
                    executionEnv = DefaultAgentExecutionEnvironment(
                        agentRunId = agentRunId,
                        userMessage = userMessage,
                        runtimeContextRepository = runtimeContextRepository,
                        workspaceDescriptor = workspaceDescriptor,
                        resolvedSkills = resolvedSkills,
                        failureLearningSkill = failureLearningSkill,
                        workspaceManager = workspaceManager,
                        workspaceMemoryService = memoryService,
                        conversationMode = conversationMode,
                        reasoningEffort = reasoningEffort,
                        modelProviderProfileId = modelOverride?.providerProfileId,
                        terminalEnvironment = terminalEnvironment,
                        runControl = runControl,
                        permissionRequester = permissionRequester,
                        longTermMemoryIndex = ltmIndex,
                        turnMemoryLoadTracker = memoryLoadTracker
                    )
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            callback.onError("Agent execution failed: ${e.message}")
            AgentResult.Error("Agent execution failed", e)
        } finally {
            runCatching { toolRouter?.dispose() }
            runCatching { pluginSession?.closeSuspending() }
        }
    }

    private fun buildInitialMessages(
        promptSeed: AgentConversationHistoryRepository.PromptSeed,
        userMessage: String,
        attachments: List<Map<String, Any?>>,
        continueMode: Boolean,
        workspaceDescriptor: AgentWorkspaceDescriptor,
        installedSkills: List<SkillIndexEntry>,
        skillsRootShellPath: String,
        skillsRootAndroidPath: String,
        resolvedSkills: List<ResolvedSkillContext>,
        memoryContext: WorkspaceMemoryPromptContext?,
        terminalDistribution: TerminalDistribution.Spec = TerminalDistribution.alpine,
        conversationMode: String = AgentConversationModePolicy.AGENT_MODE,
        historyMessagesOverride: List<ChatCompletionMessage>? = null
    ): List<ChatCompletionMessage> {
        val locale = AppLocaleManager.resolvePromptLocale(context)
        val chatOnly = AgentConversationModePolicy.isChatOnlyMode(conversationMode)
        val leadingMessages = if (chatOnly) {
            val chatPrompt = AgentPromptSettingsStore.readChatPrompt(context).trim()
            buildList {
                if (chatPrompt.isNotEmpty()) {
                    add(
                        ChatCompletionMessage(
                            role = "system",
                            content = JsonPrimitive(chatPrompt)
                        )
                    )
                }
            }
        } else {
            val systemPrompt = AgentSystemPrompt.build(
                workspace = workspaceDescriptor,
                installedSkills = installedSkills,
                skillsRootShellPath = skillsRootShellPath,
                skillsRootAndroidPath = skillsRootAndroidPath,
                resolvedSkills = resolvedSkills,
                memoryContext = memoryContext,
                locale = locale,
                terminalDistribution = terminalDistribution
            )
            buildList {
                add(ChatCompletionMessage(
                    role = "system",
                    content = buildCachedSystemPromptContent(systemPrompt)
                ))
                add(buildCachedTimeContextMessage(locale))
            }
        }
        val historyMessages = historyMessagesOverride ?: promptSeed.historyMessages
        return mergeInitialPromptMessages(
            leadingMessages = leadingMessages,
            historyMessages = if (chatOnly) {
                filterChatOnlyHistoryMessages(historyMessages)
            } else {
                historyMessages
            },
            currentUserMessage = buildCurrentUserMessage(userMessage, attachments),
            continueMode = continueMode
        )
    }

    private fun buildCachedTimeContextMessage(
        locale: cn.com.omnimind.baselib.i18n.PromptLocale
    ): cn.com.omnimind.baselib.llm.ChatCompletionMessage {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val content = synchronized(timeContextCacheLock) {
            val snapshot = resolveTimeContextSnapshot(
                cached = timeContextSnapshot,
                now = now,
                locale = locale
            )
            timeContextSnapshot = snapshot
            snapshot.content
        }
        return cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "system",
            content = JsonPrimitive(content)
        )
    }

    private fun buildCurrentUserMessage(
        userMessage: String,
        attachments: List<Map<String, Any?>>
    ): cn.com.omnimind.baselib.llm.ChatCompletionMessage {
        val rawText = AgentAttachmentPromptSupport.buildUserMessageText(
            text = userMessage,
            attachments = attachments
        )
        val normalizedAttachments = normalizeAttachments(
            attachments.filter(AgentAttachmentPromptSupport::shouldSendAttachmentToModel)
        )
        val imageParts = normalizedAttachments
            .filter { it.isImage }
            .mapNotNull { attachment ->
                val imageUrl = resolveImageAttachmentUrl(attachment)
                if (imageUrl.isBlank()) {
                    null
                } else {
                    buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", imageUrl)
                        })
                    }
                }
            }
        val content = if (imageParts.isEmpty()) {
            JsonPrimitive(rawText)
        } else {
            buildJsonArray {
                if (rawText.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", rawText)
                        }
                    )
                }
                imageParts.forEach { add(it) }
            }
        }
        return cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "user",
            content = content
        )
    }

    private data class PromptAttachment(
        val isImage: Boolean,
        val url: String?,
        val dataUrl: String?,
        val path: String?,
        val mimeType: String?
    )

    private fun normalizeAttachments(attachments: List<Map<String, Any?>>): List<PromptAttachment> {
        return attachments.map { item ->
            val mimeType = item["mimeType"]?.toString()?.trim()
            val explicitImage = item["isImage"]?.toString()?.toBooleanStrictOrNull()
            val isImage = explicitImage ?: mimeType.orEmpty().lowercase().startsWith("image/")
            PromptAttachment(
                isImage = isImage,
                url = item["url"]?.toString(),
                dataUrl = item["dataUrl"]?.toString(),
                path = item["path"]?.toString(),
                mimeType = mimeType
            )
        }
    }

    private fun resolveImageAttachmentUrl(attachment: PromptAttachment): String {
        val dataUrl = attachment.dataUrl.orEmpty().trim()
        if (dataUrl.startsWith("data:")) return dataUrl

        val remoteUrl = attachment.url.orEmpty().trim()
        if (remoteUrl.startsWith("https://") || remoteUrl.startsWith("http://") || remoteUrl.startsWith("data:")) {
            return remoteUrl
        }
        val path = attachment.path.orEmpty().trim()
        if (path.isNotEmpty()) {
            val resolved = AgentImageAttachmentSupport.resolveImageAttachmentUrl(
                mapOf(
                    "path" to path,
                    "mimeType" to attachment.mimeType,
                    "isImage" to attachment.isImage
                )
            )
            if (resolved.isNotBlank()) {
                return resolved
            }
        }
        return ""
    }
}
