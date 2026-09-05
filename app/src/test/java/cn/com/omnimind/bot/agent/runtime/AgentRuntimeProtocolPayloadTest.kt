package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.mcp.McpServerState
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ProviderModelOption
import com.agentclientprotocol.model.McpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeProtocolPayloadTest {
    private fun officialCatalogAgents(): List<AcpAgentProfile> =
        AcpAgentCatalog.parse(File("src/main/assets/acp/agents.json").readText()).agents

    private fun deepSeekInstallScript(): String =
        File("src/main/assets/acp/install/deepseek-harness.sh").readText()

    @Test
    fun reasoningEffortUsesTheAdvertisedOfficialAcpConfigOption() {
        val configId = resolveAdvertisedReasoningEffortConfigId(
            payload = mapOf(
                "configOptions" to listOf(
                    mapOf(
                        "id" to "thinking_level",
                        "category" to "thought_level",
                        "options" to listOf(
                            mapOf("value" to "low"),
                            mapOf("value" to "high"),
                        ),
                    ),
                ),
            ),
            requestedEffort = "high",
        )

        assertEquals("thinking_level", configId)
        assertNull(
            resolveAdvertisedReasoningEffortConfigId(
                payload = mapOf("configOptions" to emptyList<Any>()),
                requestedEffort = "high",
            ),
        )
    }

    @Test
    fun acpExtensionRequestIsParsedWithoutChangingStandardMessages() {
        val request = parseAcpExtensionLine(
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"_omnibot/presentation\",\"params\":{\"card\":\"usage\"}}"
        )

        assertTrue(request?.isRequest == true)
        assertEquals("_omnibot/presentation", request?.method)
        assertEquals(JsonPrimitive(7), request?.id)
        assertEquals("usage", request?.params?.toString()?.let {
            Json.parseToJsonElement(it).jsonObject["card"]?.toString()?.trim('"')
        })
        assertNull(
            parseAcpExtensionLine(
                "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{}}"
            )
        )
    }

    @Test
    fun acpExtensionNotificationAndArrayParamsAreRetained() {
        val notification = parseAcpExtensionLine(
            "{\"jsonrpc\":\"2.0\",\"method\":\"_omnibot/progress\",\"params\":[1,2]}"
        )

        assertFalse(notification?.isRequest == true)
        assertEquals("_omnibot/progress", notification?.method)
        assertEquals("[1,2]", notification?.params.toString())
    }

    @Test
    fun acpExtensionResponsePreservesJsonRpcIdAndErrorShape() {
        val response = encodeAcpExtensionResponse(
            id = JsonPrimitive("request-1"),
            reply = RawAcpExtensionReply(
                error = Json.parseToJsonElement(
                    "{\"code\":-32000,\"message\":\"declined\"}"
                )
            )
        )

        assertEquals(
            "{\"jsonrpc\":\"2.0\",\"id\":\"request-1\",\"error\":{\"code\":-32000,\"message\":\"declined\"}}",
            response
        )
    }

    @Test
    fun deepSeekHarnessModeCompatibilityFillsOnlyMissingDisplayNames() {
        val raw = """
            {"jsonrpc":"2.0","id":7,"result":{"sessionId":"s-1","modes":{
              "currentModeId":"workspace-write",
              "availableModes":[
                {"id":"workspace-write","description":"Write in workspace"},
                {"id":"read-only","name":"Read only","description":"Read files"}
              ]
            }}}
        """.trimIndent()

        val normalized = normalizeDeepSeekHarnessAcpModeNames(raw, enabled = true)

        assertTrue(normalized.contains("\"id\":\"workspace-write\",\"description\":\"Write in workspace\",\"name\":\"workspace-write\""))
        assertTrue(normalized.contains("\"id\":\"read-only\",\"name\":\"Read only\""))
    }

    @Test
    fun deepSeekHarnessConfigCompatibilityFillsMissingSelectNames() {
        val raw = """
            {"jsonrpc":"2.0","id":8,"result":{"configOptions":[
              {"id":"permission_mode","type":"select","options":[
                {"value":"workspace-write","description":"Write in workspace"},
                {"value":"read-only","label":"Read only"}
              ]}
            ]}}
        """.trimIndent()

        val normalized = normalizeDeepSeekHarnessAcpModeNames(raw, enabled = true)

        assertTrue(normalized.contains("\"value\":\"workspace-write\",\"description\":\"Write in workspace\",\"name\":\"workspace-write\""))
        assertTrue(normalized.contains("\"value\":\"read-only\",\"label\":\"Read only\",\"name\":\"Read only\""))
    }

    @Test
    fun deepSeekHarnessModeCompatibilityIsDisabledForOtherAgents() {
        val raw = "{\"result\":{\"availableModes\":[{\"id\":\"workspace-write\"}]}}"

        assertEquals(raw, normalizeDeepSeekHarnessAcpModeNames(raw, enabled = false))
    }

    @Test
    fun deepSeekHarnessConfigSupportsPartialPermissionUpdates() {
        val current = DeepSeekHarnessConfig(
            baseUrl = "https://provider.example/v1",
            model = "provider-model",
            apiKey = "secret",
            reasoningEffort = "high",
            permissionMode = "workspace-write"
        )

        val updated = deepSeekHarnessConfigFromArgs(
            args = mapOf("permissionMode" to "read-only"),
            current = current,
            sharedProvider = AgentProviderCredentials(current.baseUrl, current.apiKey),
            sharedModel = current.model
        )

        assertEquals(current.baseUrl, updated.baseUrl)
        assertEquals(current.model, updated.model)
        assertEquals(current.apiKey, updated.apiKey)
        assertEquals(current.reasoningEffort, updated.reasoningEffort)
        assertEquals("read-only", updated.permissionMode)
    }

    @Test
    fun adapterDependencyFailureIsOfflineNotMissingEvenWhenStderrMentionsNoSuchFile() {
        val message = """
            Failed to initialize ACP agent DeepSeek Harness:
            Error [ERR_MODULE_NOT_FOUND]: Cannot find package @deepseek-ai/dsh-tools
            proot warning: No such file or directory
        """.trimIndent()

        assertFalse(isMissingAcpAgentFailure(message))
        assertTrue(isAcpInitializeFailure(message))

        val health = managedAgentHealthFromProbe(
            enabled = true,
            launchInstalled = true,
            healthCheckPassed = true,
            previous = AcpAgentHealth(
                status = AcpAgentHealth.STATUS_OFFLINE,
                installed = true,
                error = message,
            ),
        )

        assertEquals(AcpAgentHealth.STATUS_OFFLINE, health.status)
        assertEquals(true, health.installed)
        assertEquals(message, health.error)
    }

    @Test
    fun commandMissingStillUsesMissingStatus() {
        assertTrue(isMissingAcpAgentFailure("ACP launch command not found: dsh-acp-android"))
        assertTrue(isMissingAcpAgentFailure("sh: dsh-acp-android: command not found"))
        assertFalse(isMissingAcpAgentFailure("plugin dependency failed to resolve"))
    }

    @Test
    fun sharedAgentModelUsesTheProviderBoundToTheAgentScene() {
        assertEquals(
            "provider-model",
            resolveSharedAgentModel(
                boundProviderProfileId = "provider-1",
                boundModel = " provider-model "
            )
        )
        assertEquals(
            "provider-model",
            resolveSharedAgentModel(
                boundProviderProfileId = "provider-1",
                boundModel = "provider-model"
            )
        )
        assertNull(
            resolveSharedAgentModel(
                boundProviderProfileId = null,
                boundModel = null
            )
        )
    }

    @Test
    fun officialAcpSessionUpdateUsesCanonicalSessionIdForLocalBinding() {
        val notification = mapOf(
            "method" to "session/update",
            "params" to mapOf(
                "sessionId" to "acp-session-1",
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "content" to mapOf("type" to "text", "text" to "ok"),
                ),
            ),
        )

        assertEquals("acp-session-1", extractThreadId(notification))
    }

    @Test
    fun explicitEventTurnIdWinsOverTheCurrentlyActiveHostTurn() {
        assertEquals(
            "late-old-turn",
            resolveObservedTurnId(
                explicitTurnId = " late-old-turn ",
                activeEventTurnId = null,
                hostActiveTurnId = "new-current-turn",
                disconnectedTurnId = null,
                implicitTurnId = null,
            )
        )
        assertEquals(
            "new-current-turn",
            resolveObservedTurnId(
                explicitTurnId = null,
                activeEventTurnId = null,
                hostActiveTurnId = "new-current-turn",
                disconnectedTurnId = null,
                implicitTurnId = "compat-turn",
            )
        )
        assertEquals(
            "new-current-turn",
            resolveObservedTurnId(
                explicitTurnId = "provider-turn",
                activeEventTurnId = null,
                hostActiveTurnId = "new-current-turn",
                disconnectedTurnId = null,
                implicitTurnId = null,
                preferHostActiveTurn = true,
            )
        )
    }

    @Test
    fun sessionScopedUpdateUsesOnlyItsActivePromptReservationWhenWireTurnIdIsAbsent() {
        assertEquals(
            "reserved-session-turn",
            resolveObservedTurnId(
                explicitTurnId = null,
                activeEventTurnId = null,
                hostActiveTurnId = null,
                disconnectedTurnId = null,
                implicitTurnId = "reserved-session-turn",
            ),
        )
        assertNull(
            resolveObservedTurnId(
                explicitTurnId = null,
                activeEventTurnId = null,
                hostActiveTurnId = null,
                disconnectedTurnId = null,
                implicitTurnId = null,
            ),
        )
    }

    @Test
    fun legacyTaskAndRunIdsAreAcceptedOnlyAsTurnCompatibilityAliases() {
        assertEquals(
            "legacy-task-1",
            extractTurnId(
                mapOf(
                    "method" to "item/agentMessage/delta",
                    "params" to mapOf("taskId" to "legacy-task-1"),
                )
            )
        )
        assertEquals(
            "legacy-run-1",
            extractTurnId(
                mapOf(
                    "method" to "item/agentMessage/delta",
                    "params" to mapOf("run_id" to "legacy-run-1"),
                )
            )
        )
    }

    @Test
    fun acpSessionCompatibilityCanonicalizesOldIdsOnlyAtTheBoundary() {
        val canonical = AcpSessionCompatibility.canonicalize(
            "session/prompt",
            mapOf("threadId" to "old-session", "turnId" to "old-prompt")
        )

        assertEquals("old-session", canonical["sessionId"])
        assertEquals("old-prompt", canonical["promptId"])
        assertEquals("old-session", canonical["threadId"])
        assertEquals("old-prompt", canonical["turnId"])

        val taskAlias = AcpSessionCompatibility.canonicalize(
            "session/prompt",
            mapOf("session_id" to "old-session-2", "task_id" to "old-task-2")
        )
        assertEquals("old-session-2", taskAlias["sessionId"])
        assertEquals("old-task-2", taskAlias["promptId"])

        val canonicalWins = AcpSessionCompatibility.canonicalize(
            "session/cancel",
            mapOf(
                "sessionId" to "new-session",
                "threadId" to "old-session",
                "promptId" to "new-prompt",
                "turnId" to "old-prompt"
            )
        )
        assertEquals("new-session", canonicalWins["sessionId"])
        assertEquals("new-prompt", canonicalWins["promptId"])
    }

    @Test
    fun appSessionListPaginationUsesOpaqueCursorAndStableSnapshot() {
        val sessions = (1..3).map { index ->
            mapOf<String, Any?>("id" to "session-$index")
        }
        val identity: (Map<String, Any?>) -> String = { it["id"].toString() }

        val first = paginateAcpItems(
            sessions,
            limit = 2,
            cursor = null,
            identity = identity,
        )
        assertEquals(listOf("session-1", "session-2"), first.items.map { it["id"] })
        assertFalse(first.nextCursor.isNullOrBlank())
        // The cursor is an opaque protocol value, not a session id or a
        // second application-owned session object.
        assertFalse(first.nextCursor!!.contains("session-3"))

        val second = paginateAcpItems(
            sessions,
            limit = 2,
            cursor = first.nextCursor,
            identity = identity,
        )
        assertEquals(listOf("session-3"), second.items.map { it["id"] })
        assertNull(second.nextCursor)

        try {
            paginateAcpItems(
                sessions + mapOf<String, Any?>("id" to "session-4"),
                limit = 2,
                cursor = first.nextCursor,
                identity = identity,
            )
            assertTrue("A cursor must not paginate a changed snapshot", false)
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("changed"))
        }
    }

    @Test
    fun omittedAppSessionListLimitReturnsEverySessionWithoutAHostCap() {
        val sessions = (1..201).map { index ->
            mapOf<String, Any?>("id" to "session-$index")
        }

        val page = paginateAcpItems(
            items = sessions,
            limit = null,
            cursor = null,
            identity = { it["id"].toString() },
        )

        assertEquals(201, page.items.size)
        assertNull(page.nextCursor)
    }

    @Test
    fun acpSessionCompatibilityAddsLegacyIdsOnlyToResponses() {
        val response = AcpSessionCompatibility.withLegacyIds(
            mapOf("sessionId" to "session-1", "promptId" to "prompt-1")
        )

        assertEquals("session-1", response["threadId"])
        assertEquals("prompt-1", response["turnId"])
    }

    @Test
    fun standardAcpSessionWireParamsStripHostOnlyIdentityFields() {
        val params = standardAcpSessionWireParams(
            method = "session/set_config_option",
            args = mapOf(
                "sessionId" to "session-1",
                "threadId" to "legacy-thread-1",
                "conversationId" to 42L,
                "agentId" to "xiaowan-acp",
                "configId" to "permission_mode",
                "value" to "workspace-write",
                "conversationMode" to "write",
                "_meta" to mapOf("source" to "test"),
            )
        )

        assertEquals(
            mapOf(
                "sessionId" to "session-1",
                "configId" to "permission_mode",
                "value" to "workspace-write",
                "_meta" to mapOf("source" to "test"),
            ),
            params
        )
        assertFalse(params.containsKey("conversationId"))
        assertFalse(params.containsKey("agentId"))
        assertFalse(params.containsKey("threadId"))
    }

    @Test
    fun standardAcpForkWireParamsKeepOnlyOfficialSessionFields() {
        val params = standardAcpSessionWireParams(
            method = "session/fork",
            args = mapOf(
                "sessionId" to "session-1",
                "cwd" to "/workspace",
                "additionalDirectories" to listOf("/workspace/shared"),
                "conversationId" to 42L,
                "agentId" to "codex-acp",
            )
        )

        assertEquals(
            mapOf(
                "sessionId" to "session-1",
                "cwd" to "/workspace",
                "additionalDirectories" to listOf("/workspace/shared"),
            ),
            params
        )
    }

    @Test
    fun permissionRequestUsesOfficialToolCallUpdateShape() {
        val toolCall = standardAcpPermissionToolCallPayload(
            toolCallId = "tool-1",
            title = "Run command",
            optionNames = listOf("Allow once", "Reject"),
        )

        assertEquals("tool-1", toolCall["toolCallId"])
        assertEquals("in_progress", toolCall["status"])
        assertFalse(toolCall.containsKey("detail"))
        assertEquals(
            "Allow once\nReject",
            ((toolCall["content"] as List<*>).single() as Map<*, *>)
                .let { it["content"] as Map<*, *> }["text"]
        )
    }

    @Test
    fun canonicalSessionResponseKeepsSessionIdsWhenLegacyImplementationUsesThreadIds() {
        val response = mapOf<String, Any?>(
            "threadId" to "session-1",
            "turnId" to "prompt-1"
        ).withAcpSessionId()

        assertEquals("session-1", response["sessionId"])
        assertEquals("prompt-1", response["promptId"])
        // Old fields remain response-only compatibility aliases.
        assertEquals("session-1", response["threadId"])
        assertEquals("prompt-1", response["turnId"])
    }

    @Test
    fun remoteThreadListIsProjectedToTheSharedAcpSessionShape() {
        val response = mapOf<String, Any?>(
            "threads" to listOf(
                mapOf(
                    "id" to "thread-1",
                    "threadId" to "thread-1",
                    "title" to "Remote session",
                )
            ),
            "nextCursor" to "cursor-2",
        ).withAcpSessions()

        val sessions = response["sessions"] as List<*>
        val session = sessions.single() as Map<*, *>
        assertEquals("thread-1", session["sessionId"])
        assertEquals("thread-1", session["threadId"])
        assertEquals("Remote session", session["title"])
        assertEquals("cursor-2", response["nextCursor"])
    }

    @Test
    fun remoteSessionFallbackOnlyAcceptsCapabilityErrors() {
        assertTrue(isUnsupportedRemoteAcpMethod(IllegalStateException("Method not found: session/load")))
        assertTrue(isUnsupportedRemoteAcpMethod(IllegalStateException("ACP method is not implemented")))
        assertFalse(isUnsupportedRemoteAcpMethod(IllegalStateException("invalid session id")))
        assertFalse(isUnsupportedRemoteAcpMethod(IllegalStateException("connection refused")))
    }

    @Test
    fun managedAcpCatalogIncludesSupportedAgentsWithoutGemini() {
        assertEquals(
            listOf("小万", "Kimi Code", "Claude Code", "Codex", "OpenCode", "DeepSeek Harness"),
            officialCatalogAgents().map { it.name }
        )
        assertTrue(officialCatalogAgents().all { it.builtIn })
        assertEquals(
            officialCatalogAgents().size,
            officialCatalogAgents().map { it.id }.toSet().size
        )
        val codex = officialCatalogAgents().first {
            it.id == AcpAgentProfileStore.CODEX_AGENT_ID
        }
        assertEquals(
            "codex",
            AcpAgentProfileStore.officialRuntime(codex)?.discoveryCommand
        )
        assertEquals(
            "@openai/codex@latest",
            AcpAgentProfileStore.officialRuntime(codex)?.managedAdapterPackage
        )
        val codexRuntime = requireNotNull(AcpAgentProfileStore.officialRuntime(codex))
        assertTrue(codexRuntime.managedAdapterPackage in codexRuntime.managedAdapterPackages)
        val bridgePackage = codexRuntime.managedAdapterPackages.single {
            it.startsWith("@agentclientprotocol/codex-acp@")
        }
        assertEquals(
            "codex-acp-${bridgePackage.substringAfterLast('@')}",
            codexRuntime.preparationRevision,
        )
        val xiaowan = officialCatalogAgents().first {
            it.id == AcpAgentProfileStore.XIAOWAN_AGENT_ID
        }
        assertEquals("omnibot-xiaowan-acp", xiaowan.command)
        assertEquals(
            "omnibot-xiaowan-acp",
            AcpAgentProfileStore.officialRuntime(xiaowan)?.discoveryCommand
        )
        assertNull(AcpAgentProfileStore.officialRuntime(xiaowan)?.managedAdapterPackage)
        val kimi = officialCatalogAgents().first {
            it.id == AcpAgentProfileStore.KIMI_CODE_AGENT_ID
        }
        assertEquals("kimi", kimi.command)
        assertEquals(listOf("acp"), kimi.arguments)
        val kimiRuntime = AcpAgentProfileStore.officialRuntime(kimi)
        assertEquals("kimi", kimiRuntime?.discoveryCommand)
        assertEquals(KIMI_CODE_NPM_PACKAGE_SPEC, kimiRuntime?.managedAdapterPackage)
        assertEquals("kimi", kimiRuntime?.terminalPackageId)
        assertEquals(
            "kimi-code",
            kimiRuntime?.harnessAdapter?.configAdapterId,
        )
        val deepSeek = officialCatalogAgents().first {
            it.id == AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID
        }
        assertEquals("dsh-acp-android", deepSeek.command)
        assertEquals(listOf("--profile", "acp"), deepSeek.arguments)
        val deepSeekRuntime = AcpAgentProfileStore.officialRuntime(deepSeek)
        assertEquals("dsh", deepSeekRuntime?.discoveryCommand)
        assertTrue(
            deepSeekRuntime?.managedAdapterPackages.orEmpty().contains(
                "@openma/deepseek-harness-acp@latest"
            )
        )
        assertTrue(
            deepSeekRuntime?.managedAdapterPackages.orEmpty().contains(
                "@deepseek-ai/dsh@next"
            )
        )
        assertEquals(2, deepSeekRuntime?.managedAdapterPackages?.size)
        assertTrue(deepSeekRuntime?.managedAdapterPackages.orEmpty().contains("@deepseek-ai/dsh@next"))
        assertTrue(deepSeekRuntime?.requiresNativeBuildTools == true)
        assertTrue(
            deepSeekRuntime?.managedAdapterHealthCommand.orEmpty()
                .contains("command -v dsh")
        )
        assertTrue(
            deepSeekRuntime?.managedAdapterHealthCommand.orEmpty()
                .contains("command -v dsh-acp-android")
        )
        assertTrue(MANAGED_NATIVE_BUILD_PREREQUISITES_COMMAND.contains("omnibot_apk_add 'build-base' 'python3'"))
        assertTrue(MANAGED_NATIVE_BUILD_PREREQUISITES_COMMAND.contains("apk fix --no-cache"))
        assertTrue(MANAGED_NATIVE_BUILD_PREREQUISITES_COMMAND.contains("apk fix --no-cache --upgrade"))
        assertTrue(MANAGED_NATIVE_BUILD_PREREQUISITES_COMMAND.contains("build-essential python3"))
        assertTrue(deepSeekInstallScript().contains("dsh plugin --profile acp add -w"))
        assertTrue(deepSeekInstallScript().contains("profiles/acp/package.json"))
        assertTrue(deepSeekInstallScript().contains("pnpm@11.22.0"))
        assertTrue(deepSeekInstallScript().contains("PNPM_CONFIG_PACKAGE_IMPORT_METHOD=copy"))
        assertTrue(deepSeekInstallScript().contains("PROFILE_LAYOUT_MARKER"))
        assertTrue(
            deepSeekInstallScript().contains(
                "timeout 30 dsh-acp-android --profile acp --dump-config"
            )
        )
        assertTrue(
            deepSeekInstallScript().contains(
                "pnpm config set --location=project packageImportMethod copy"
            )
        )
        assertTrue(
            deepSeekInstallScript().contains("DSH_HOME=\"/root/.dsh/omnibot-acp\"")
        )
    }

    @Test
    fun legacyXiaowanBotProfileIsRecognizedAsAnAlias() {
        assertTrue(
            AcpAgentProfileStore.isLegacyXiaowanAlias(
                AcpAgentProfile(
                    id = "legacy-xiaowan-bot",
                    name = "小万 Bot",
                    command = "legacy-xiaowan"
                )
            )
        )
        assertTrue(
            AcpAgentProfileStore.isLegacyXiaowanAlias(
                AcpAgentProfile(
                    id = "legacy-xiaowan-command",
                    name = "旧入口",
                    command = "omnibot-xiaowan-acp"
                )
            )
        )
        assertFalse(
            AcpAgentProfileStore.isLegacyXiaowanAlias(
                AcpAgentProfile(
                    id = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
                    name = "小万",
                    command = "omnibot-xiaowan-acp",
                    builtIn = true
                )
            )
        )
    }

    @Test
    fun cumulativeAgentSnapshotsBecomeSingleAppendOnlyAcpChunks() {
        assertEquals("你", acpSnapshotDelta("", "你"))
        assertEquals("好", acpSnapshotDelta("你", "你好"))
        assertEquals("吗", acpSnapshotDelta("你好", "你好吗"))
        assertNull(acpSnapshotDelta("你好吗", "你好吗"))
        assertEquals("重新开始", acpSnapshotDelta("旧内容", "重新开始"))
    }

    @Test
    fun deepSeekHarnessConfigRoundTripsAndBuildsLaunchEnvironment() {
        val config = DeepSeekHarnessConfig(
            baseUrl = "https://gateway.example/v1",
            model = "deepseek-custom",
            apiKey = "sk-test",
            reasoningEffort = "high",
            permissionMode = "read-only"
        )

        val restored = parseDeepSeekHarnessConfig(
            buildDeepSeekHarnessConfigJson(config)
        )

        assertEquals(config, restored)
        assertEquals("https://gateway.example/v1", restored.toEnvironment()["DEEPSEEK_BASE_URL"])
        assertEquals("deepseek-custom", restored.toEnvironment()["DSH_MODEL"])
        assertEquals("high", restored.toEnvironment()["DSH_REASONING_EFFORT"])
        assertEquals("high", restored.toEnvironment()["DSH_PI_AI_REASONING_EFFORT"])
        assertEquals(
            "max",
            DeepSeekHarnessConfig(reasoningEffort = "max").toEnvironment()["DSH_REASONING_EFFORT"]
        )
        assertEquals(
            "max",
            DeepSeekHarnessConfig(reasoningEffort = "max").toEnvironment()["DSH_PI_AI_REASONING_EFFORT"]
        )
        assertEquals(
            "enabled",
            DeepSeekHarnessConfig(reasoningEffort = "max").toEnvironment()["DSH_THINKING"]
        )
        assertEquals(
            "disabled",
            DeepSeekHarnessConfig(reasoningEffort = "off").toEnvironment()["DSH_THINKING"]
        )
        assertEquals("read-only", restored.toEnvironment()["DSH_PERMISSION_MODE"])
        assertEquals("/root/.dsh/omnibot-acp-clean", restored.toEnvironment()["DSH_ACP_HOME"])
        assertEquals(
            "/root/.dsh/omnibot-acp-clean/sessions",
            restored.toEnvironment()["DSH_SESSION_ROOT"]
        )
        assertEquals("/root/.dsh/omnibot-acp", restored.toEnvironment()["DSH_HOME"])
        assertEquals("deepseek-official", restored.toEnvironment()["DSH_PROVIDER"])
        assertEquals("1", restored.toEnvironment()["NODE_NO_WARNINGS"])
    }

    @Test
    fun deepSeekHarnessDoesNotInventReasoningDefaults() {
        val environment = DeepSeekHarnessConfig(
            baseUrl = "https://gateway.example/v1",
            model = "deepseek-custom",
            apiKey = "sk-test"
        ).toEnvironment()

        assertNull(environment["DSH_REASONING_EFFORT"])
        assertNull(environment["DSH_PI_AI_REASONING_EFFORT"])
        assertNull(environment["DSH_THINKING"])
    }

    @Test
    fun deepSeekHarnessProviderConfigEditPreservesComposerPermissionDefault() {
        val restored = deepSeekHarnessConfigFromArgs(
            args = mapOf(
                "reasoningEffort" to "high"
            ),
            current = DeepSeekHarnessConfig(permissionMode = "read-only"),
            sharedProvider = AgentProviderCredentials(
                baseUrl = "https://gateway.example/v1",
                apiKey = "sk-updated"
            ),
            sharedModel = "deepseek-custom"
        )

        assertEquals("read-only", restored.permissionMode)
    }

    @Test
    fun sharedAgentProviderSyncsTheActiveDeepSeekHarness() {
        val provider = ModelProviderProfile(
            id = "deepseek-provider",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-shared"
        )
        val credentials = AgentProviderCredentials(provider.baseUrl, provider.apiKey)
        val dsh = syncAgentProviderCredentials(
            DeepSeekHarnessConfig(
                baseUrl = "https://old.example",
                apiKey = "sk-old"
            ),
            credentials,
            sharedModel = "glm-5.1"
        )

        assertEquals(provider.baseUrl, dsh.baseUrl)
        assertEquals(provider.apiKey, dsh.apiKey)
        assertEquals("glm-5.1", dsh.model)
    }

    @Test
    fun officialAgentConfigAdaptersRemapOneProviderWithoutPrivateProtocolFields() {
        val credentials = AgentProviderCredentials(
            baseUrl = "https://gateway.example/v1",
            apiKey = "sk-shared"
        )

        val dsh = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID,
                provider = credentials,
                model = "glm-5.1",
                harnessAdapter = AcpHarnessAdapters.deepSeekHarness,
                deepSeekConfig = DeepSeekHarnessConfig(reasoningEffort = "max")
            )
        )
        assertEquals("glm-5.1", dsh.deepSeekConfig?.model)
        assertEquals("max", dsh.environment["DSH_REASONING_EFFORT"])

        val codex = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.CODEX_AGENT_ID,
                provider = credentials,
                model = "glm-5.1",
                harnessAdapter = AcpHarnessAdapters.codex,
            )
        )
        assertEquals("glm-5.1", codex.codexModel)
        assertEquals(
            "https://gateway.example/v1",
            codex.environment["OPENAI_BASE_URL"]
        )
    }

    @Test
    fun launchConfigWritesAreSelectedByHarnessAdapterNotDshName() {
        val credentials = AgentProviderCredentials(
            baseUrl = "https://gateway.example/v1",
            apiKey = "sk-shared",
        )
        val providerModels = listOf(ProviderModelOption(id = "glm-5.1"))

        val openCodeInput = AgentProviderMappingInput(
            agentId = "opencode-acp",
            provider = credentials,
            model = "glm-5.1",
            harnessAdapter = AcpHarnessAdapters.openCode,
        )
        val openCodeMapping = AgentConfigAdapterRegistry.map(openCodeInput)
        val openCodeWrites = AgentConfigAdapterRegistry.launchConfigWrites(
            input = openCodeInput,
            mapping = openCodeMapping,
            providerModels = providerModels,
            existingConfig = "{\"mcp\":{\"existing\":{}}}",
        )
        assertEquals(1, openCodeWrites.size)
        assertEquals(OPENCODE_CONFIG_PATH, openCodeWrites.single().path)
        assertTrue(openCodeWrites.single().content.contains("\"existing\""))

        val dshWithStandardAdapter = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID,
                provider = credentials,
                model = "glm-5.1",
                harnessAdapter = AcpHarnessAdapters.standard,
            )
        )
        assertNull(dshWithStandardAdapter.deepSeekConfig)
        val dshWrites = AgentConfigAdapterRegistry.launchConfigWrites(
                input = AgentProviderMappingInput(
                    agentId = AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID,
                    provider = credentials,
                    model = "glm-5.1",
                    harnessAdapter = AcpHarnessAdapters.deepSeekHarness,
                ),
                mapping = AgentConfigAdapterRegistry.map(
                    AgentProviderMappingInput(
                        agentId = AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID,
                        provider = credentials,
                        model = "glm-5.1",
                        harnessAdapter = AcpHarnessAdapters.deepSeekHarness,
                    )
                ),
                providerModels = providerModels,
                existingConfig = "",
            )
        assertEquals(1, dshWrites.size)
        assertEquals("/root/.dsh/omnibot-acp/settings.yaml", dshWrites.single().path)
        assertTrue(dshWrites.single().content.contains("id: 'glm-5.1'"))
    }

    @Test
    fun managedAgentInstallationUsesTheExistingOfficialTerminalSetupIds() {
        assertEquals(
            "kimi",
            managedAgentTerminalPackageId(
                officialCatalogAgents().first {
                    it.id == AcpAgentProfileStore.KIMI_CODE_AGENT_ID
                }
            )
        )
        assertEquals(
            "deepseek_harness",
            managedAgentTerminalPackageId(
                officialCatalogAgents().first {
                    it.id == AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID
                }
            )
        )
        assertEquals(
            "codex",
            managedAgentTerminalPackageId(officialCatalogAgents().first {
                it.id == AcpAgentProfileStore.CODEX_AGENT_ID
            })
        )
        assertEquals(
            "claude_code",
            managedAgentTerminalPackageId(officialCatalogAgents().first {
                it.id == "claude-code-acp"
            })
        )
        assertEquals(
            "opencode",
            managedAgentTerminalPackageId(officialCatalogAgents().first {
                it.id == "opencode-acp"
            })
        )
        assertNull(
            managedAgentTerminalPackageId(officialCatalogAgents().first {
                it.id == AcpAgentProfileStore.XIAOWAN_AGENT_ID
            })
        )
    }

    @Test
    fun localAgentMcpUsesTheSelectedHarnessCapability() {
        val state = McpServerState(
            enabled = true,
            running = true,
            host = "192.168.1.8",
            port = 8899,
            token = "secret-token"
        )

        val codexServers = buildLocalAgentAcpMcpServers(
            supportsHttp = true,
            state = state
        )
        val server = codexServers.single() as McpServer.Http
        assertEquals("omnibot", server.name)
        assertEquals("http://127.0.0.1:8899/mcp", server.url)
        assertEquals("Authorization", server.headers.single().name)
        assertEquals("Bearer secret-token", server.headers.single().value)

        assertTrue(
            buildLocalAgentAcpMcpServers(
                supportsHttp = false,
                state = state
            ).isEmpty()
        )
        assertTrue(
            buildLocalAgentAcpMcpServers(
                supportsHttp = false,
                state = state
            ).isEmpty()
        )
    }

    @Test
    fun deepSeekHarnessMcpConnectionUsesOfficialSessionDeclaration() {
        val servers = buildLocalAgentAcpMcpServers(
            supportsHttp = true,
            state = McpServerState(
                enabled = true,
                running = true,
                host = null,
                port = 9001,
                token = "local-secret"
            )
        )
        val server = servers.single() as McpServer.Http
        assertEquals("http://127.0.0.1:9001/mcp", server.url)
        assertEquals("Bearer local-secret", server.headers.single().value)
    }

    @Test
    fun dshFreshSessionOnlyReconnectFallsBackToANewThread() {
        assertTrue(
            isRecoverableAgentThreadError(
                "The selected ACP agent did not advertise session resume or loadSession."
            )
        )
        assertTrue(isRecoverableAgentThreadError("unknown session: old-session"))
        assertTrue(isRecoverableAgentThreadError("session metadata not found"))
        assertTrue(isRecoverableAgentThreadError("session file missing"))
        assertFalse(isRecoverableAgentThreadError("provider authentication failed"))
    }

    @Test
    fun managedNpmPackageSpecsResolveTheirInstalledPackageNames() {
        assertEquals(
            "@openma/deepseek-harness-acp",
            npmPackageName("@openma/deepseek-harness-acp@latest")
        )
        assertEquals("plain-package", npmPackageName("plain-package@1.2.3"))
        assertEquals("@scope/package", npmPackageName("@scope/package"))
    }

    @Test
    fun acpStreamReadFailureIsSuppressedWhileConnectionIsClosing() {
        assertTrue(
            shouldSuppressAcpStreamReadFailure(
                closing = true,
                currentProcess = true,
                processAlive = true
            )
        )
        assertTrue(
            shouldSuppressAcpStreamReadFailure(
                closing = false,
                currentProcess = false,
                processAlive = true
            )
        )
        assertFalse(
            shouldSuppressAcpStreamReadFailure(
                closing = false,
                currentProcess = true,
                processAlive = true
            )
        )
    }

    @Test
    fun officialAcpLaunchDisablesOnlyTheProotLinkCompatibilityMode() {
        val script = File(
            "../ReTerminal/core/main/src/main/assets/init-host.sh"
        ).takeIf { it.exists() } ?: File(
            "ReTerminal/core/main/src/main/assets/init-host.sh"
        )
        val source = script.readText()

        assertTrue(source.contains("OMNIBOT_DISABLE_PROOT_LINK2SYMLINK"))
        assertTrue(source.contains("ARGS=\"\$ARGS --link2symlink\""))
        assertTrue(source.contains("!= \"1\""))
    }

    @Test
    fun officialAcpFilesystemCompatibilityUsesCopyOnlyForDeniedHardLinks() {
        assertTrue(ACP_FILESYSTEM_COMPAT_SCRIPT.contains("promises.link"))
        assertTrue(ACP_FILESYSTEM_COMPAT_SCRIPT.contains("COPYFILE_EXCL"))
        assertTrue(ACP_FILESYSTEM_COMPAT_SCRIPT.contains("EACCES"))
        assertTrue(ACP_FILESYSTEM_COMPAT_SCRIPT.contains("EPERM"))
        assertFalse(ACP_FILESYSTEM_COMPAT_SCRIPT.contains("rename"))
    }


    @Test
    fun sanitizeAgentRuntimeAbsolutePathKeepsLastCleanAbsolutePath() {
        val path = sanitizeAgentRuntimeAbsolutePath(
            """
            init-host: shell warmup
            /workspace
            warning: ignored trailing log
            """.trimIndent()
        )

        assertEquals("/workspace", path)
    }

    @Test
    fun sanitizeAgentRuntimeAbsolutePathRejectsRelativeOutput() {
        assertNull(sanitizeAgentRuntimeAbsolutePath("workspace"))
    }

    @Test
    fun buildAgentTextInputMatchesAppServerTextShape() {
        val input = buildAgentTextInput(" hello ")

        assertEquals(1, input.size)
        assertEquals("text", input[0]["type"])
        assertEquals("hello", input[0]["text"])
        assertTrue(input[0].containsKey("text_elements"))
    }

    @Test
    fun buildAgentTurnInputUsesLocalImageAndWorkspaceFileHint() {
        val input = buildAgentTurnInput(
            text = "Inspect these attachments",
            attachments = listOf(
                mapOf(
                    "name" to "screen.png",
                    "path" to "/android/cache/screen.png",
                    "promptPath" to "/workspace/.omnibot/attachments/screen.png",
                    "mimeType" to "image/png",
                    "isImage" to true
                ),
                mapOf(
                    "name" to "notes.txt",
                    "path" to "/android/cache/notes.txt",
                    "promptPath" to "/workspace/.omnibot/attachments/notes.txt",
                    "mimeType" to "text/plain",
                    "isImage" to false,
                    "sendToModel" to false
                )
            ),
            preferLocalImagePaths = true
        )

        assertEquals("localImage", input[0]["type"])
        assertEquals(
            "/workspace/.omnibot/attachments/screen.png",
            input[0]["path"]
        )
        assertEquals("text", input[1]["type"])
        assertTrue(input[1]["text"].toString().contains("Inspect these attachments"))
        assertTrue(
            input[1]["text"].toString()
                .contains("/workspace/.omnibot/attachments/notes.txt")
        )
    }

    @Test
    fun buildAgentTurnInputUsesInlineImageForRemoteRuntime() {
        val input = buildAgentTurnInput(
            text = "",
            attachments = listOf(
                mapOf(
                    "name" to "screen.png",
                    "dataUrl" to "data:image/png;base64,AA==",
                    "mimeType" to "image/png",
                    "isImage" to true
                )
            ),
            preferLocalImagePaths = false
        )

        assertEquals(1, input.size)
        assertEquals("image", input[0]["type"])
        assertEquals("data:image/png;base64,AA==", input[0]["url"])
    }

    @Test
    fun buildAgentSandboxPolicyUsesAbsoluteWritableRoot() {
        val policy = buildAgentSandboxPolicy("noise\n/workspace")

        assertEquals("workspaceWrite", policy["type"])
        assertEquals(listOf("/workspace"), policy["writableRoots"])
        assertEquals(true, policy["networkAccess"])
        assertEquals(false, policy["excludeTmpdirEnvVar"])
        assertEquals(false, policy["excludeSlashTmp"])
    }

    @Test
    fun resolveAgentSandboxModeUsesCurrentThreadStartEnum() {
        assertEquals(
            "danger-full-access",
            resolveAgentSandboxMode(mapOf("type" to "dangerFullAccess"))
        )
        assertEquals(
            "read-only",
            resolveAgentSandboxMode(mapOf("type" to "readOnly"))
        )
        assertEquals(
            "workspace-write",
            resolveAgentSandboxMode(buildAgentSandboxPolicy("/workspace"))
        )
    }

    @Test
    fun localAcpPermissionBehaviorFollowsComposerPolicy() {
        assertEquals(
            AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT,
            resolveAcpPermissionBehavior(
                mapOf(
                    "approvalPolicy" to "never",
                    "sandboxPolicy" to mapOf("type" to "dangerFullAccess")
                )
            )
        )
        assertEquals(
            AcpPermissionBehavior.ASK_USER,
            resolveAcpPermissionBehavior(
                mapOf(
                    "approvalPolicy" to "on-request",
                    "approvalsReviewer" to "user"
                )
            )
        )
        assertEquals(
            AcpPermissionBehavior.ASK_USER,
            resolveAcpPermissionBehavior(
                mapOf(
                    "approvalPolicy" to "on-request",
                    "approvalsReviewer" to "auto_review"
                )
            )
        )
    }

    @Test
    fun reviewThreadSettingsKeepSelectedFullAccessPolicy() {
        val params = buildAgentThreadSettingsUpdateParams(
            args = mapOf(
                "approvalPolicy" to "never",
                "approvalsReviewer" to "user",
                "sandboxPolicy" to mapOf("type" to "dangerFullAccess"),
                "model" to "gpt-5-codex",
                "effort" to "high"
            ),
            cwd = "/workspace",
            threadId = "thread-1"
        )

        assertEquals("thread-1", params["threadId"])
        assertEquals("/workspace", params["cwd"])
        assertEquals("never", params["approvalPolicy"])
        assertEquals("user", params["approvalsReviewer"])
        assertEquals(
            mapOf("type" to "dangerFullAccess"),
            params["sandboxPolicy"]
        )
        assertEquals("gpt-5-codex", params["model"])
        assertEquals("high", params["effort"])
    }

    @Test
    fun addAgentOptionalRunParamsForwardsModelAndPlanMode() {
        val params = linkedMapOf<String, Any?>("threadId" to "thread-1")

        addAgentOptionalRunParams(
            params,
            mapOf(
                "model" to "gpt-5-codex",
                "effort" to "high",
                "collaborationMode" to "plan",
                "serviceTier" to "auto"
            )
        )

        assertEquals("gpt-5-codex", params["model"])
        assertEquals("high", params["effort"])
        val collaborationMode = params["collaborationMode"] as? Map<*, *>
        val settings = collaborationMode?.get("settings") as? Map<*, *>
        assertEquals("plan", collaborationMode?.get("mode"))
        assertEquals("gpt-5-codex", settings?.get("model"))
        assertEquals("high", settings?.get("reasoning_effort"))
        assertEquals("auto", params["serviceTier"])
    }

    @Test
    fun resolveAgentCollaborationModeFillsStructuredModeSettings() {
        val mode = resolveAgentCollaborationMode(
            mapOf(
                "model" to "gpt-5-codex",
                "collaborationMode" to mapOf(
                    "mode" to "plan",
                    "settings" to mapOf("developer_instructions" to "Use a checklist.")
                )
            )
        )
        val settings = mode?.get("settings") as? Map<*, *>

        assertEquals("plan", mode?.get("mode"))
        assertEquals("gpt-5-codex", settings?.get("model"))
        assertEquals("Use a checklist.", settings?.get("developer_instructions"))
    }

    @Test
    fun resolveAgentCollaborationModeRequiresModel() {
        val params = linkedMapOf<String, Any?>("threadId" to "thread-1")

        addAgentOptionalRunParams(
            params,
            mapOf("collaborationMode" to "plan")
        )

        assertEquals(false, params.containsKey("collaborationMode"))
    }

    @Test
    fun resolveCodexReviewTargetDefaultsToUncommittedChanges() {
        val target = resolveCodexReviewTarget(null)

        assertEquals("uncommittedChanges", target["type"])
    }

    @Test
    fun resolveCodexReviewTargetPreservesExplicitTarget() {
        val target = resolveCodexReviewTarget(
            mapOf(
                "type" to "baseBranch",
                "branch" to "main"
            )
        )

        assertEquals("baseBranch", target["type"])
        assertEquals("main", target["branch"])
    }

    @Test
    fun remoteBridgeConfigRequiresUrlAndCwd() {
        assertTrue(
            CodexRemoteBridgeConfig(
                bridgeUrl = "ws://127.0.0.1:17321/codex",
                cwd = "/Users/ocean/code/project"
            ).isConfigured
        )
        assertEquals(
            false,
            CodexRemoteBridgeConfig(
                bridgeUrl = "ws://127.0.0.1:17321/codex",
                cwd = ""
            ).isConfigured
        )
    }

    @Test
    fun normalizeBridgeUrlsAcceptHostPortAndDefaultPaths() {
        assertEquals(
            "ws://192.168.1.10:17321/codex",
            normalizeCodexBridgeWebSocketUrl("192.168.1.10:17321")
        )
        assertEquals(
            "http://192.168.1.10:17321/health",
            normalizeCodexBridgeHealthUrl("ws://192.168.1.10:17321/codex")
        )
        assertEquals(
            "http://192.168.1.10:17321/fs/list",
            normalizeCodexBridgeFsListUrl("ws://192.168.1.10:17321/codex")
        )
        assertEquals(
            "http://192.168.1.10:17321/fs/upload",
            normalizeCodexBridgeFsUploadUrl("ws://192.168.1.10:17321/codex")
        )
    }

    @Test
    fun defaultThreadSourceKindsUseCurrentCodexAppServerVariants() {
        assertTrue(DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("cli"))
        assertTrue(DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("appServer"))
        assertTrue(DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("subAgentOther"))
        assertEquals(false, DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("interactive"))
        assertEquals(false, DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("background"))
        assertEquals(false, DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("subAgentInteractive"))
    }

    @Test
    fun withLocalIdsInjectsActiveAndActiveTurnIdWhenActive() {
        val response = mapOf<String, Any?>("thread" to mapOf("id" to "thread-1"))

        val enriched = response.withLocalIds(
            threadId = "thread-1",
            conversationId = 42L,
            turnId = "turn-7",
            active = true,
        )

        assertEquals("thread-1", enriched["threadId"])
        assertEquals(42L, enriched["conversationId"])
        assertEquals("turn-7", enriched["turnId"])
        assertEquals("turn-7", enriched["activeTurnId"])
        assertEquals(true, enriched["active"])
    }

    @Test
    fun withLocalIdsSurfacesInactiveWithoutActiveTurnId() {
        val response = mapOf<String, Any?>("thread" to mapOf("id" to "thread-1"))

        val enriched = response.withLocalIds(
            threadId = "thread-1",
            conversationId = 99L,
            turnId = null,
            active = false,
        )

        assertEquals(false, enriched["active"])
        assertNull(enriched["turnId"])
        assertNull(enriched["activeTurnId"])
    }

    @Test
    fun withLocalIdsOmitsActiveFieldsWhenNotProvided() {
        val response = mapOf<String, Any?>("thread" to mapOf("id" to "thread-1"))

        val enriched = response.withLocalIds(
            threadId = "thread-1",
            conversationId = null,
        )

        assertEquals("thread-1", enriched["threadId"])
        assertEquals(false, enriched.containsKey("active"))
        assertEquals(false, enriched.containsKey("activeTurnId"))
        assertEquals(false, enriched.containsKey("turnId"))
    }

    @Test
    fun turnTerminalStatusPrefersTheAcpStopReason() {
        assertEquals(
            "end_turn",
            resolveTurnTerminalStatus("END_TURN", promptResponseReceived = true, cancelled = false, error = null)
        )
        assertEquals(
            "max_tokens",
            resolveTurnTerminalStatus("max_tokens", promptResponseReceived = true, cancelled = false, error = null)
        )
        assertEquals(
            "refusal",
            resolveTurnTerminalStatus("REFUSAL", promptResponseReceived = true, cancelled = false, error = null)
        )
        // A stop reason still wins once the agent has reported one, even if the
        // surrounding coroutine was torn down afterwards.
        assertEquals(
            "end_turn",
            resolveTurnTerminalStatus("end_turn", promptResponseReceived = true, cancelled = true, error = RuntimeException())
        )
    }

    @Test
    fun turnTerminalStatusCoversEveryWayAPromptCanEnd() {
        // Cancelled: a cancelled coroutine usually also surfaces an exception,
        // so cancellation has to outrank failure.
        assertEquals(
            "cancelled",
            resolveTurnTerminalStatus(null, promptResponseReceived = false, cancelled = true, error = RuntimeException("boom"))
        )
        assertEquals(
            "error",
            resolveTurnTerminalStatus(null, promptResponseReceived = false, cancelled = false, error = IllegalStateException())
        )
        // The regression that stranded every codex-acp conversation: a prompt
        // flow that completes without ever emitting a prompt response must
        // still terminate the turn rather than leave it running forever.
        assertEquals(
            "error",
            resolveTurnTerminalStatus(null, promptResponseReceived = false, cancelled = false, error = null)
        )
        assertEquals(
            "error",
            resolveTurnTerminalStatus("   ", promptResponseReceived = false, cancelled = false, error = null)
        )
    }

    @Test
    fun remotePromptUsesOfficialStopReasonForTerminalProjection() {
        assertEquals(
            "cancelled",
            terminalStatusFromAcpParams(mapOf("stopReason" to "cancelled"))
        )
        assertEquals(
            "error",
            terminalStatusFromAcpParams(mapOf("status" to "failed"))
        )
        assertEquals(
            "completed",
            terminalStatusFromAcpParams(mapOf("stopReason" to "end_turn"))
        )
        assertEquals(
            "cancelled",
            terminalStatusFromAcpParams(emptyMap(), fallback = "cancelled")
        )
    }

    @Test
    fun remoteEventUsesTheHostSessionConversationBinding() {
        assertEquals(
            42L,
            resolveAcpEventConversationId(
                remoteEvent = true,
                sessionConversationId = 42L,
                projectedConversationId = null,
            )
        )
    }

    @Test
    fun buildCodexAgentFilesUseAuthJsonAndResponsesProviderConfig() {
        val config = buildCodexConfigToml(
            baseUrl = "https://example.com/v1",
            model = "custom-codex"
        )
        val auth = buildCodexAuthJson("sk-test")

        assertTrue(config.contains("model_provider = \"omnimind\""))
        assertTrue(config.contains("model = \"custom-codex\""))
        assertTrue(config.contains("base_url = \"https://example.com/v1\""))
        assertTrue(config.contains("wire_api = \"responses\""))
        assertTrue(config.contains("requires_openai_auth = true"))
        assertFalse(config.contains("env_key"))
        assertTrue(auth.contains("\"OPENAI_API_KEY\": \"sk-test\""))
    }

    @Test
    fun sharedAgentModelUsesTheBoundProviderRegardlessOfEditingProfile() {
        assertEquals(
            "GLM-5.1",
            resolveSharedAgentModel(
                boundProviderProfileId = "debug-llmthu-glm",
                boundModel = "GLM-5.1"
            )
        )
        assertEquals(
            "glm-5",
            resolveSharedAgentModel(
                boundProviderProfileId = "debug-llmthu-glm",
                boundModel = "glm-5"
            )
        )
        assertEquals(
            "GLM-5.1",
            resolveSharedAgentModel(
                boundProviderProfileId = "debug-llmthu-glm",
                boundModel = "GLM-5.1"
            )
        )
    }

    @Test
    fun acpPermissionModeResolvesHarnessAliases() {
        val advertised = listOf("default", "bypassPermissions", "plan")
        assertEquals(
            "bypassPermissions",
            resolveAcpSessionModeId(advertised, "agent-full-access")
        )
        assertEquals("default", resolveAcpSessionModeId(advertised, "agent"))
        assertEquals("plan", resolveAcpSessionModeId(advertised, "read-only"))
        assertTrue(isAcpFullAccessMode("danger-full-access"))
        assertTrue(isAcpFullAccessMode("bypassPermissions"))
        assertFalse(isAcpFullAccessMode("workspace-write"))
    }

    @Test
    fun acpPermissionDefaultsToUnrestrictedUntilExplicitlyRequested() {
        assertEquals(
            AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT,
            resolveAcpPermissionBehavior(emptyMap())
        )
        assertEquals(
            AcpPermissionBehavior.ASK_USER,
            resolveAcpPermissionBehavior(mapOf("approvalPolicy" to "on-request"))
        )
        assertEquals(
            AcpPermissionBehavior.ASK_USER,
            resolveAcpPermissionBehavior(
                mapOf("sandboxPolicy" to mapOf("type" to "readOnly"))
            )
        )
    }
}
