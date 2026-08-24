package cn.com.omnimind.bot.agent.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeProtocolPayloadTest {
    @Test
    fun managedAcpCatalogIncludesSupportedAgentsWithoutGemini() {
        assertEquals(
            listOf("Codex", "Claude Code", "OpenCode", "DeepSeek Harness"),
            AcpAgentProfileStore.OFFICIAL_AGENTS.map { it.name }
        )
        assertTrue(AcpAgentProfileStore.OFFICIAL_AGENTS.all { it.builtIn })
        val codex = AcpAgentProfileStore.OFFICIAL_AGENTS.first()
        assertEquals(
            "codex",
            AcpAgentProfileStore.officialRuntime(codex)?.discoveryCommand
        )
        assertEquals(
            "@agentclientprotocol/codex-acp@1.1.7",
            AcpAgentProfileStore.officialRuntime(codex)?.managedAdapterPackage
        )
        val deepSeek = AcpAgentProfileStore.OFFICIAL_AGENTS.first {
            it.id == AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID
        }
        assertEquals("dsh-acp-demo", deepSeek.command)
        assertEquals(
            listOf("--config", "/root/.dsh/omnibot-acp/cordis.yml"),
            deepSeek.arguments
        )
        val deepSeekRuntime = AcpAgentProfileStore.officialRuntime(deepSeek)
        assertEquals("node", deepSeekRuntime?.discoveryCommand)
        assertTrue(
            deepSeekRuntime?.managedAdapterPackages.orEmpty().contains(
                "@deepseek-ai/dsh-acp-demo@next"
            )
        )
        assertTrue(
            deepSeekRuntime?.managedAdapterPackages.orEmpty().contains(
                "@deepseek-ai/dsh-llm-deepseek@next"
            )
        )
        assertTrue(
            deepSeekRuntime?.managedAdapterPackages.orEmpty()
                .all { it.endsWith("@next") }
        )
        assertTrue(deepSeekRuntime?.requiresNativeBuildTools == true)
        assertTrue(
            deepSeekRuntime?.managedAdapterHealthCommand.orEmpty().contains("node-pty")
        )
        assertTrue(MANAGED_NATIVE_BUILD_PREREQUISITES_COMMAND.contains("omnibot_apk_add 'build-base' 'python3'"))
        assertTrue(MANAGED_NATIVE_BUILD_PREREQUISITES_COMMAND.contains("apk fix --no-cache"))
        assertTrue(MANAGED_NATIVE_BUILD_PREREQUISITES_COMMAND.contains("apk fix --no-cache --upgrade"))
        assertTrue(MANAGED_NATIVE_BUILD_PREREQUISITES_COMMAND.contains("build-essential python3"))
        assertTrue(DEEPSEEK_HARNESS_NPM_INSTALL_COMMAND.contains("repair_deepseek_harness_node_pty"))
        assertTrue(DEEPSEEK_HARNESS_NPM_INSTALL_COMMAND.contains("node-gyp configure"))
        assertTrue(DEEPSEEK_HARNESS_NPM_INSTALL_COMMAND.contains("cmd_copy = rm -rf"))
        assertTrue(DEEPSEEK_HARNESS_NPM_INSTALL_COMMAND.contains("omnibot-node-gyp-copy"))
        assertTrue(DEEPSEEK_HARNESS_NPM_INSTALL_COMMAND.contains("exec /bin/ln"))
        val installScriptLines = DEEPSEEK_HARNESS_NPM_INSTALL_COMMAND
            .lineSequence()
            .map(String::trim)
            .toList()
        val packageInstallIndex = installScriptLines.indexOf(
            "install_deepseek_harness_packages"
        )
        val nativeRepairIndex = installScriptLines.indexOf(
            "repair_deepseek_harness_node_pty"
        )
        assertTrue(packageInstallIndex >= 0)
        assertTrue(nativeRepairIndex > packageInstallIndex)
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
        assertEquals("read-only", restored.toEnvironment()["DSH_PERMISSION_MODE"])
        assertEquals("1", restored.toEnvironment()["NODE_NO_WARNINGS"])
    }

    @Test
    fun deepSeekHarnessProviderConfigEditPreservesComposerPermissionDefault() {
        val restored = deepSeekHarnessConfigFromArgs(
            args = mapOf(
                "baseUrl" to "https://gateway.example/v1",
                "model" to "deepseek-custom",
                "apiKey" to "sk-updated",
                "reasoningEffort" to "high"
            ),
            current = DeepSeekHarnessConfig(permissionMode = "read-only")
        )

        assertEquals("read-only", restored.permissionMode)
    }

    @Test
    fun deepSeekHarnessCordisCompositionOwnsTheMissingAcpCapabilities() {
        val config = buildDeepSeekHarnessCordisConfig()

        assertTrue(config.contains("name: '@deepseek-ai/dsh-llm-deepseek'"))
        assertTrue(config.contains("name: '$DEEPSEEK_HARNESS_OMNIBOT_ACP_PLUGIN_PATH'"))
        assertFalse(config.contains("name: '@deepseek-ai/dsh-acp-demo'"))
        assertTrue(config.contains("workspaceRoot: /workspace"))
        assertTrue(config.contains("persistenceCompression: none"))
        assertTrue(config.contains("process.env.DSH_MODEL"))
        assertTrue(config.contains("process.env.DSH_REASONING_EFFORT"))
        assertTrue(config.contains("process.env.DSH_PERMISSION_MODE"))
        assertTrue(config.contains("policy: ask"))
    }

    @Test
    fun deepSeekHarnessInteractiveBridgePublishesUiAndConfigEvents() {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val source = listOf(
            workingDirectory.resolve(
                "app/src/main/assets/deepseek_harness/omnibot-acp-demo.mjs"
            ),
            workingDirectory.resolve(
                "src/main/assets/deepseek_harness/omnibot-acp-demo.mjs"
            )
        ).firstOrNull(File::isFile)?.readText()
            ?: error("DeepSeek Harness interactive ACP asset is missing.")

        assertTrue(source.contains("sessionUpdate: 'agent_thought_chunk'"))
        assertTrue(source.contains("messageId: thoughtMessageId("))
        assertTrue(source.contains("sessionUpdate: 'tool_call'"))
        assertTrue(source.contains("sessionUpdate: 'tool_call_update'"))
        assertTrue(source.contains("messageId: event.data.message.id"))
        assertTrue(source.contains("category: 'model'"))
        assertTrue(source.contains("category: 'thought_level'"))
        assertTrue(source.contains("category: 'mode'"))
        assertTrue(source.contains("installModelSelection(agentCtx, selection)"))
        assertTrue(source.contains("setSandboxMode(record.agent.session, mode)"))
    }

    @Test
    fun dshFreshSessionOnlyReconnectFallsBackToANewThread() {
        assertTrue(
            isRecoverableAgentThreadError(
                "The selected ACP agent did not advertise session resume or loadSession."
            )
        )
        assertTrue(isRecoverableAgentThreadError("unknown session: old-session"))
        assertFalse(isRecoverableAgentThreadError("provider authentication failed"))
    }

    @Test
    fun managedNpmPackageSpecsResolveTheirInstalledPackageNames() {
        assertEquals(
            "@deepseek-ai/dsh-acp-demo",
            npmPackageName("@deepseek-ai/dsh-acp-demo@next")
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
            resolveTurnTerminalStatus("END_TURN", cancelled = false, error = null)
        )
        assertEquals(
            "max_tokens",
            resolveTurnTerminalStatus("max_tokens", cancelled = false, error = null)
        )
        assertEquals(
            "refusal",
            resolveTurnTerminalStatus("REFUSAL", cancelled = false, error = null)
        )
        // A stop reason still wins once the agent has reported one, even if the
        // surrounding coroutine was torn down afterwards.
        assertEquals(
            "end_turn",
            resolveTurnTerminalStatus("end_turn", cancelled = true, error = RuntimeException())
        )
    }

    @Test
    fun turnTerminalStatusCoversEveryWayAPromptCanEnd() {
        // Cancelled: a cancelled coroutine usually also surfaces an exception,
        // so cancellation has to outrank failure.
        assertEquals(
            "cancelled",
            resolveTurnTerminalStatus(null, cancelled = true, error = RuntimeException("boom"))
        )
        assertEquals(
            "error",
            resolveTurnTerminalStatus(null, cancelled = false, error = IllegalStateException())
        )
        // The regression that stranded every codex-acp conversation: a prompt
        // flow that completes without ever emitting a prompt response must
        // still terminate the turn rather than leave it running forever.
        assertEquals(
            "end_turn",
            resolveTurnTerminalStatus(null, cancelled = false, error = null)
        )
        assertEquals(
            "end_turn",
            resolveTurnTerminalStatus("   ", cancelled = false, error = null)
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
}
