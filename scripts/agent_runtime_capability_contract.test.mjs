import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

function sourcePath(relativePath) {
  return path.join(repositoryRoot, relativePath);
}

test("Xiaowan session startup uses the Provider cache and restores session-owned selection", async () => {
  const content = await source("app/src/main/java/cn/com/omnimind/bot/agent/XiaowanAcpConnection.kt");
  const startup = content.slice(
    content.indexOf("private suspend fun loadXiaowanModels()"),
    content.indexOf("private fun hasUsableSharedProviderBinding"),
  );
  assert.match(startup, /ModelProviderConfigStore\.cachedModels/);
  assert.doesNotMatch(startup, /fetchProviderModels|refreshAndGetModels/);
  const refresh = content.slice(content.indexOf("suspend fun refreshModels()"), content.indexOf("override val configOptions"));
  assert.match(refresh, /refreshAndGetModels/);
  assert.match(refresh, /fetchProviderModels/);
  assert.match(refresh, /sessionConfig\.replaceModels/);
  assert.match(content, /profileStore\.sessionConfiguration\(sessionId\.value\)/);
  assert.match(content, /saveSessionConfiguration\(sessionId\.value/);
});

test("request forwarding and prompt completion do not invent variants or tool outcomes", async () => {
  const llm = await source("app/src/main/java/cn/com/omnimind/bot/agent/llm/AgentLlmClient.kt");
  const reducer = await source("ui/lib/services/agent_event_reducer.dart");
  assert.doesNotMatch(llm, /modelCandidates|StreamRequestVariant|buildRequestVariants/);
  assert.doesNotMatch(reducer, /_markUnfinishedToolCardsInterruptedForTask|_markToolCardsCompleteForTask/);
});

test("file skill and terminal tools never apply application character truncation", async () => {
  for (const file of ["FileToolHandler", "SkillsToolHandler", "TerminalToolHandler", "PrivilegedToolHandler", "SharedHelper"]) {
    const content = await source(`app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/${file}.kt`);
    assert.doesNotMatch(content, /maxChars|truncateText|truncateTerminalTail/, file);
  }
});

test("tool envelopes and user configuration have no host size policy", async () => {
  const adapter = await source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentEventAdapter.kt");
  const manager = await source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeManager.kt");
  assert.doesNotMatch(adapter, /compactToolResultContent|outputTruncated|headTail/);
  assert.doesNotMatch(manager, /requireAgentConfigSize|MAX_AGENT_CONFIG_FILE_CHARS/);
});

test("Office previews do not discard document content", async () => {
  const content = await source("ui/lib/services/office_preview_service.dart");
  assert.doesNotMatch(content, /_maxDoc|_maxWorkbook|_maxCell|_maxSlide|_truncateText/);
});

test("orchestrator does not wrap transport failure in a second terminal path", async () => {
  const content = await source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt");
  assert.doesNotMatch(content, /TerminalTurnRequestFailure|var terminalError|streamTurnWithTransportPolicy/);
});

async function source(relativePath) {
  return readFile(sourcePath(relativePath), "utf8");
}

async function dartSources(relativeDirectory) {
  const paths = await readdir(sourcePath(relativeDirectory), { recursive: true });
  return Promise.all(
    paths
      .filter((relativePath) => relativePath.endsWith(".dart"))
      .map(async (relativePath) => ({
        relativePath,
        content: await readFile(sourcePath(path.join(relativeDirectory, relativePath)), "utf8"),
      })),
  );
}

test("the active harness, not a conversation-mode policy, supplies Agent tools", async () => {
  const [modePolicy, dispatcher, contracts, registry, orchestrator] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/AgentConversationModePolicy.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/SubagentDispatcher.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeContracts.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolRegistry.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt"),
  ]);

  assert.match(modePolicy, /if \(isChatOnlyMode\(conversationMode\)\) \{\s*return emptyList\(\)\s*\}/);
  assert.match(modePolicy, /return definitions/);
  assert.doesNotMatch(modePolicy, /allowedTools|progressive|toolSearch/i);
  assert.match(dispatcher, /val harnessCatalog = inheritedSubagentCatalog\(parentCatalogProvider\(\)\)/);
  assert.match(dispatcher, /toolRegistry = harnessCatalog/);
  assert.doesNotMatch(contracts, /usesProgressiveDiscovery|exposeToolNames/);
  assert.match(registry, /get\(\) = allToolsByName\.values\.toList\(\)/);
  assert.doesNotMatch(orchestrator, /usesProgressiveDiscovery|activateDiscoveredTools/);
});

test("installed Skills are not hidden by an OmniBot platform keyword policy", async () => {
  const [handler, skillRuntime, failureHook, systemPrompt] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/SkillsToolHandler.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/skill/AgentSkillRuntime.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/skill/SelfImprovingSkillFailureHook.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentSystemPrompt.kt"),
  ]);

  assert.doesNotMatch(handler, /SkillCompatibilityChecker|homekit|healthkit|apple-|ios/i);
  assert.doesNotMatch(skillRuntime, /object SkillCompatibilityChecker|homekit|healthkit|apple-/i);
  assert.doesNotMatch(failureHook, /SkillCompatibilityChecker/);
  assert.doesNotMatch(systemPrompt, /SkillCompatibilityChecker/);
});

test("image generation leaves prompt and image capacity to the configured provider", async () => {
  const handler = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/ImageGenerationToolHandler.kt",
  );

  assert.doesNotMatch(handler, /MAX_IMAGE_(?:PROMPT|JSON|BYTES)/);
  assert.doesNotMatch(handler, /image prompt exceeds|generated image exceeds/);
  assert.doesNotMatch(handler, /readBodyLimited\(.*MAX_IMAGE/);
  assert.match(handler, /requireImageGenerationPrompt/);
  assert.match(handler, /it\.body\?\.bytes\(\) \?: ByteArray\(0\)/);
});

test("platform routes do not add a second request-size policy before the provider", async () => {
  const [mediaProtocol, llmClient, embeddingGateway] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/media/PlatformMediaGateway.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/llm/AgentLlmClient.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/workspace/memory/PlatformEmbeddingGateway.kt"),
  ]);

  assert.doesNotMatch(mediaProtocol, /MAX_PLATFORM_JSON_UTF8_BYTES|request_too_large|发送上限 15/);
  assert.doesNotMatch(llmClient, /requirePlatformJsonRequestWithinLimit/);
  assert.doesNotMatch(embeddingGateway, /requirePlatformJsonRequestWithinLimit/);
});

test("provider diagnostics preserve the complete response after credential redaction", async () => {
  const [llmClient, errorSupport] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/llm/AgentLlmClient.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/AgentRuntimeErrorSupport.kt"),
  ]);

  assert.doesNotMatch(llmClient, /responseBody\s*=\s*responseBody\?\.take\(4000\)/);
  assert.doesNotMatch(errorSupport, /maxLength:\s*Int\s*=\s*300|redacted\.take\(maxLength\)/);
  assert.match(errorSupport, /Bearer \*\*\*/);
  assert.match(errorSupport, /api\[_-\]\?key\|token\|authorization/);
});

test("automatic compaction cannot create a private ACP lifecycle update", async () => {
  const [connection, reducer] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/XiaowanAcpConnection.kt"),
    source("ui/lib/services/agent_event_reducer.dart"),
  ]);

  const compactionCallback = connection.slice(
    connection.indexOf("override suspend fun onContextCompactionStateChanged"),
    connection.indexOf("override suspend fun onClarifyRequired"),
  );
  assert.doesNotMatch(compactionCallback, /emitUpdate|acpPresentationMeta/);
  assert.doesNotMatch(reducer, /_upsertAcpContextCompactionCard|_acpContextCompactionLabel/);
});

test("Flutter has no legacy capped history builder or synthetic user summary path", async () => {
  const dispatchSupport = await source(
    "ui/lib/features/home/pages/chat/mixins/chat_dispatch_support.dart",
  );

  assert.doesNotMatch(dispatchSupport, /buildConversationHistory|ChatService\.getRecentMessages/);
  assert.doesNotMatch(dispatchSupport, /kCompactedContextSummaryPrefix|_kMaxInlineImageBytes/);
  assert.doesNotMatch(dispatchSupport, /role['"]\s*:\s*['"]user['"].*context-summary/s);
});

test("private retry presentation cannot create or revive an ACP turn", async () => {
  const reducer = await source("ui/lib/services/agent_event_reducer.dart");
  const retryBlock = reducer.slice(
    reducer.indexOf("final retry = _asStringMap(presentation['retry']);"),
    reducer.indexOf("final recovery = _asStringMap(presentation['recovery']);"),
  );

  assert.doesNotMatch(retryBlock, /_touchActiveTurn\(/);
  assert.match(retryBlock, /_upsertAcpRetryPresentation/);
});

test("a manual retry keeps history and uses the ordinary explicit-send path", async () => {
  const actions = await source(
    "ui/lib/features/home/pages/chat/chat_page_user_message_actions.dart",
  );
  const retryStart = actions.indexOf("Future<void> _retryFailedAgentTurn(");
  const retryEnd = actions.indexOf("List<Map<String, dynamic>> _extractRetryAttachments", retryStart);
  const retryBody = actions.slice(retryStart, retryEnd);

  assert.ok(retryStart >= 0);
  assert.ok(retryEnd > retryStart);
  assert.match(retryBody, /await _retryUserMessageText\(/);
  assert.match(retryBody, /retainedUserMessageId: userMessage\.id/);
  assert.doesNotMatch(retryBody, /_tryAgentFlow\(/);
  assert.doesNotMatch(actions, /_buildPendingManualRetryMessage/);
  assert.doesNotMatch(actions, /agentMaxRetries'\]\s*=\s*.*\?\?\s*3/);
});

test("protocol-only skipped tool results do not create fake user-visible tool activity", async () => {
  const orchestrator = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt",
  );
  const syntheticResultBlock = orchestrator.slice(
    orchestrator.indexOf("private suspend fun appendSyntheticToolResultMessages"),
    orchestrator.indexOf("private fun buildSyntheticToolSkipMessage"),
  );

  assert.match(syntheticResultBlock, /appendToolResultMessage\(/);
  assert.doesNotMatch(syntheticResultBlock, /callback\.onToolCall(?:Start|Complete)\(/);
});

test("the WebChat browser bridge does not impose traversal or collection quotas", async () => {
  const bridge = await source(
    "app/src/main/java/cn/com/omnimind/bot/webchat/BrowserMirrorService.kt",
  );

  assert.match(bridge, /amount = arguments\.readInt\("amount"\)/);
  assert.match(bridge, /maxDepth = arguments\.readInt\("max_depth"\) \?: arguments\.readInt\("maxDepth"\)/);
  assert.match(bridge, /scrollCount = arguments\.readInt\("scroll_count"\) \?: arguments\.readInt\("scrollCount"\)/);
  assert.doesNotMatch(bridge, /coerceIn\(1, (?:20_000|8|20)\)/);
  assert.doesNotMatch(bridge, /\?: (?:500|5|10)/);
});

test("WebChat realtime updates are ordered without a dropping host buffer", async () => {
  const hub = await source(
    "app/src/main/java/cn/com/omnimind/bot/webchat/RealtimeHub.kt",
  );

  assert.match(hub, /Channel<RealtimeEvent>\(Channel\.UNLIMITED\)/);
  assert.match(hub, /for \(event in pendingEvents\) \{\s*events\.emit\(event\)/);
  assert.doesNotMatch(hub, /extraBufferCapacity\s*=\s*256|BufferOverflow\.DROP_OLDEST|events\.tryEmit/);
});

test("subagent dispatch accepts one requested task and has no static concurrency ceiling", async () => {
  const [definition, handler, dispatcher] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolDefinitions.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/SubagentToolHandler.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/SubagentDispatcher.kt"),
  ]);

  const subagentDefinition = definition.slice(definition.indexOf("val subagentDispatchTool"));
  assert.match(subagentDefinition, /putJsonArray\("required"\) \{\s*add\("tasks"\)\s*\}/);
  assert.doesNotMatch(subagentDefinition.slice(0, subagentDefinition.indexOf("private val builtinToolDefinitions")), /minItems|maxItems/);
  assert.match(handler, /return taskCount/);
  assert.match(handler, /return requested/);
  assert.doesNotMatch(handler, /coerceAtMost|MAX_.*CONCURRENCY|DEFAULT_.*CONCURRENCY/);
  assert.match(dispatcher, /val limit = concurrency/);
  assert.doesNotMatch(dispatcher, /coerceAtMost|MAX_.*CONCURRENCY|DEFAULT_.*CONCURRENCY/);
});

test("context compaction is an explicit slash command, not an automatic synthetic user turn", async () => {
  const openclawPage = await source("ui/lib/features/home/pages/chat/chat_page_openclaw.dart");

  assert.match(openclawPage, /trimmed == '\/compact' \|\| trimmed\.startsWith\('\/compact '\)/);
  assert.match(openclawPage, /_runtimeCoordinator\.beginContextCompaction\([\s\S]*?trigger: 'manual'/);
  assert.match(openclawPage, /AssistsMessageService\.compactConversationContext\(/);
});

test("manual context thresholds remain user-configurable instead of capped by chat UI", async () => {
  const [chatPageUi, overlays] = await Promise.all([
    source("ui/lib/features/home/pages/chat/chat_page_ui.dart"),
    source("ui/lib/features/home/pages/chat/widgets/chat_page_overlays.dart"),
  ]);

  assert.doesNotMatch(chatPageUi, /_k(?:Min|Max)ContextTokenThreshold/);
  assert.doesNotMatch(overlays, /_k(?:Min|Max)ContextTokenThreshold/);
  assert.match(overlays, /if \(parsed <= 0\)/);
  assert.match(overlays, /widget\.initialThreshold,\s*_draftThreshold\.round\(\)/);
});

test("no chat path can reintroduce automatic context compaction or a second turn loop", async () => {
  const [chatSources, orchestrator] = await Promise.all([
    dartSources("ui/lib/features/home/pages/chat"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt"),
  ]);
  const compactionCallers = chatSources
    .filter(({ content }) => content.includes("beginContextCompaction("))
    .map(({ relativePath }) => relativePath)
    .sort();

  assert.deepEqual(compactionCallers, [
    "chat_page_openclaw.dart",
    "services/chat_conversation_runtime_coordinator.dart",
  ]);
  for (const { content } of chatSources) {
    assert.doesNotMatch(content, /trigger\s*[:=]\s*['\"]auto['\"]/);
    assert.doesNotMatch(content, /automatic(?:ally)?\s+compact/i);
  }
  assert.doesNotMatch(orchestrator, /(?:MAX_|DEFAULT_)(?:TURN|DEBATE|ROUND)/);
  assert.doesNotMatch(orchestrator, /(?:repeat|take)\(\s*16\s*\)/);
  assert.doesNotMatch(orchestrator, /toolChoiceForRound|JsonPrimitive\("auto"\)/);
  assert.match(orchestrator, /toolChoice\s*=\s*null/);
  assert.doesNotMatch(orchestrator, /parallelToolCalls\s*=\s*true/);
  assert.match(orchestrator, /parallelToolCalls\s*=\s*null/);
});

test("terminal Agent failures do not use a host retryability guess", async () => {
  const orchestrator = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt",
  );

  assert.match(orchestrator, /callback\.onError\(message, retryable = true\)/);
  assert.match(orchestrator, /private fun terminalFailureMessage\(error: Throwable\)/);
  assert.doesNotMatch(orchestrator, /RetryDecision|classifyRetryableTurnFailure/);
  assert.doesNotMatch(orchestrator, /retryableStatus|looksLikeTransientTransportFailure/);
  assert.doesNotMatch(orchestrator, /insufficient_quota|monthly usage limit|available balance/);
});

test("the local ACP bridge has no private automatic-retry callback or presentation", async () => {
  const [callback, connection] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentModels.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/XiaowanAcpConnection.kt"),
  ]);

  assert.doesNotMatch(callback, /suspend fun onRetrying\(/);
  assert.doesNotMatch(connection, /override suspend fun onRetrying\(/);
  assert.doesNotMatch(connection, /"retry" to mapOf\(/);
});

test("the local Agent loop does not add an unreachable tool batch scheduler", async () => {
  const orchestrator = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt",
  );

  assert.match(orchestrator, /for \(call in validatedCalls\) \{/);
  assert.match(orchestrator, /round=\$round model_tool_calls=\$\{validatedCalls\.size}/);
  assert.doesNotMatch(orchestrator, /ToolBatch|batch\.parallel|awaitAll\(\)/);
});

test("native task persistence has no private reasoning-size ceiling", async () => {
  const assistsCoreManager = await source(
    "app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt",
  );

  assert.doesNotMatch(
    assistsCoreManager,
    /MAX_PERSISTED_THINKING_CHARS|THINKING_TRUNCATION_NOTICE/,
  );
});

test("every persisted history page restores terminal tool and thinking presentation", async () => {
  const repository = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/conversation/AgentConversationHistoryRepository.kt",
  );
  const pagedHistory = repository.slice(
    repository.indexOf("suspend fun listConversationMessagesPaged("),
    repository.indexOf("suspend fun clearConversationMessages("),
  );

  assert.match(pagedHistory, /val normalized = normalizeEntriesForDisplay\(entries\)/);
  assert.doesNotMatch(pagedHistory, /if \(offset == 0\)/);
});

test("remote session hydration cannot infer or terminate the ACP lifecycle", async () => {
  const remoteCodex = await source(
    "ui/lib/features/home/pages/chat/chat_page_remote_codex.dart",
  );

  assert.match(remoteCodex, /A session snapshot is for initial history hydration only\./);
  assert.match(remoteCodex, /final previousActive = runtime\?\.isAiResponding \?\? false;/);
  assert.match(remoteCodex, /final isAiResponding = previousActive;/);
  assert.match(remoteCodex, /final activeTaskId = runtimeTaskId;/);
  assert.doesNotMatch(remoteCodex, /Timer\.periodic|assumeActive|gracePeriod|_inferRemoteCodexSnapshotActive|remoteCodexLatestTurnLooksExternallyActive/);
  assert.doesNotMatch(remoteCodex, /remoteCodexTaskId|remote_task_id/);
});

test("managed Harness installation is explicit and never runs from chat launch", async () => {
  const [runtimeManager, chatPage] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeManager.kt"),
    source("ui/lib/features/home/pages/chat/chat_page_agent.dart"),
  ]);
  const launchEnvironment = runtimeManager.slice(
    runtimeManager.indexOf("private suspend fun prepareLocalAcpLaunch"),
    runtimeManager.indexOf("private suspend fun prepareSharedProviderBinding"),
  );
  const agentDispatch = runtimeManager.slice(
    runtimeManager.indexOf('if (method.startsWith("agent/"))'),
    runtimeManager.indexOf('if (\n            method == "model/list"'),
  );
  const chatEntry = chatPage.slice(
    chatPage.indexOf("Future<void> _handleAgentTap()"),
    chatPage.indexOf("Future<void> _leaveAgentMode()"),
  );

  assert.doesNotMatch(launchEnvironment, /ensureManagedAcpAdapter\(/);
  assert.match(agentDispatch, /if \(method == "agent\/prepare"\) \{\s*ensureManagedAcpAdapter\(targetProfile\)/);
  assert.doesNotMatch(chatEntry, /prepareAgent\(/);
});

test("official ACP bridge upgrades remain declarative and require an explicit preparation pass", async () => {
  const catalog = JSON.parse(await source("app/src/main/assets/acp/agents.json"));
  const bridges = catalog.agents.flatMap(({ runtime }) =>
    (runtime?.managedAdapterPackages ?? [])
      .filter((spec) => spec.startsWith("@agentclientprotocol/"))
      .map((spec) => ({ runtime, spec })),
  );
  assert.ok(bridges.length > 0);
  for (const { runtime, spec } of bridges) {
    const match = spec.match(/^@agentclientprotocol\/([^@]+)@(\d+\.\d+\.\d+(?:-[\w.-]+)?)$/);
    assert.ok(match, `Bridge package must declare its installed version: ${spec}`);
    const [, name, version] = match;
    assert.equal(runtime.preparationRevision, `${name}-${version}`);
    assert.equal(runtime.managedInstallCommand, undefined);
  }
});

test("runtime resolves Harness capability from the official profile, not a private agent-id switch", async () => {
  const [adapters, configAdapters] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpHarnessAdapters.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentConfigAdapters.kt"),
  ]);

  assert.match(adapters, /fun forProfile\(profile: AcpAgentProfile\)/);
  assert.doesNotMatch(adapters, /fun forAgentId\(/);
  assert.doesNotMatch(configAdapters, /buildSharedAgentProviderEnvironment\(/);
});

test("local MCP is declared only through the official ACP session surface", async () => {
  const [adapters, runtimeMcp, localRuntime] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpHarnessAdapters.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeMcp.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/LocalAcpRuntime.kt"),
  ]);

  assert.doesNotMatch(adapters, /AcpHarnessMcpTransport|mcpTransport|mcpEnvironment/);
  assert.match(runtimeMcp, /internal fun buildLocalAgentAcpMcpServers\(\s*supportsHttp: Boolean,/);
  assert.match(runtimeMcp, /if \(!supportsHttp\) return emptyList\(\)/);
  assert.match(localRuntime, /buildLocalAgentAcpMcpServers\(\s*supportsHttp = shouldDeclareLocalServer,/);
});

test("every local Harness attributes a session-scoped update through its active prompt reservation", async () => {
  const [runtimeManager, adapters] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeManager.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpHarnessAdapters.kt"),
  ]);
  const eventProjection = runtimeManager.slice(
    runtimeManager.indexOf("// ACP session/update is session-scoped on the wire."),
    runtimeManager.indexOf("// Standard ACP session/update notifications are session-scoped"),
  );

  assert.match(eventProjection, /val implicitTurnId = if \(sourceAgentId != null\) \{\s*localEventRuntime\.activeTurnIdForSession\(threadId\)/);
  assert.doesNotMatch(eventProjection, /supportsImplicitTurnAttribution|activeAgentId\(\)/);
  assert.doesNotMatch(adapters, /supportsImplicitTurnAttribution/);
});

test("model discovery leaves catalog size to the active harness", async () => {
  const [runtimeService, runtimeManager] = await Promise.all([
    source("ui/lib/services/agent_runtime_service.dart"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeManager.kt"),
  ]);

  assert.match(runtimeService, /static Future<Map<String, dynamic>> listModels\(\) \{\s*return _invokeMap\('model\/list'\);\s*\}/);
  assert.doesNotMatch(runtimeService, /_invokeMap\('model\/list', \{'limit': \d+\}\)/);
  assert.match(runtimeManager, /"model\/list" -> requestWrappedList\(\s*"model\/list",\s*canonicalArgs,\s*"models"/);
  assert.doesNotMatch(runtimeManager, /args\.ifEmpty \{ mapOf\("limit" to \d+\) \}/);
});

test("session discovery follows official cursors without a client-side page ceiling", async () => {
  const [runtimeService, sessionsPage] = await Promise.all([
    source("ui/lib/services/agent_runtime_service.dart"),
    source("ui/lib/features/home/pages/agent/agent_sessions_page.dart"),
  ]);

  assert.match(runtimeService, /static Future<Map<String, dynamic>> listSessions\(\{\s*int\? limit,/);
  assert.match(runtimeService, /if \(limit != null\) 'limit': limit,/);
  assert.match(sessionsPage, /final seenCursors = <String>\{\};\s*while \(true\)/);
  assert.match(sessionsPage, /nextCursor == null \|\| !seenCursors\.add\(nextCursor\)/);
  assert.doesNotMatch(sessionsPage, /for \(var page = 0; page < \d+; page\+\+\)/);
  assert.doesNotMatch(sessionsPage, /listSessions\(\s*limit:/);
});

test("MCP context and schedule lists do not impose a second result ceiling", async () => {
  const mcpServer = await source(
    "app/src/main/java/cn/com/omnimind/bot/mcp/AndroidDeviceMcpServer.kt",
  );

  const contextQuery = mcpServer.slice(mcpServer.indexOf('"context_apps_query"'));
  const scheduleList = mcpServer.slice(mcpServer.indexOf('"schedule_task_list"'));
  assert.match(contextQuery, /requestedLimit \?: Int\.MAX_VALUE/);
  assert.match(scheduleList, /requestedLimit\?\.let\(tasks::take\) \?: tasks/);
  assert.doesNotMatch(contextQuery.slice(0, contextQuery.indexOf('"file_transfer"')), /coerceIn\(1, 100\)|\?: 20/);
  assert.doesNotMatch(scheduleList.slice(0, scheduleList.indexOf('"schedule_task_update"')), /coerceIn\(1, 100\)|\?: 100/);
});

test("persisted conversation history never replaces a large item with a truncated projection", async () => {
  const [entryDao, databaseHelper, historyRepository, historySupport] = await Promise.all([
    source("baselib/src/main/java/cn/com/omnimind/baselib/database/AgentConversationEntryDao.kt"),
    source("baselib/src/main/java/cn/com/omnimind/baselib/database/DatabaseHelper.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/conversation/AgentConversationHistoryRepository.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/conversation/AgentConversationHistorySupport.kt"),
  ]);

  assert.doesNotMatch(entryDao, /SAFE_ENTRY_PROJECTION|getThreadEntries(?:Asc|Desc|DescPaged)Safe|getByThreadAndEntryIdSafe|payloadLimit|summaryLimit/);
  assert.doesNotMatch(databaseHelper, /getAgentConversationEntries(?:Asc|Desc|DescPaged)Safe|getAgentConversationEntryByThreadAndIdSafe/);
  assert.match(historyRepository, /DatabaseHelper\.getAgentConversationEntriesDescPaged\(/);
  assert.match(historySupport, /fun prepareEntryForStorage\(entry: AgentConversationEntry\): AgentConversationEntry \{[\s\S]*?return entry/);
  assert.doesNotMatch(historySupport, /fun materializeRecord\(/);
});

test("stdio MCP results preserve one complete projection for the tool loop and history", async () => {
  const stdioMcp = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/runtime/XiaowanMcpSession.kt",
  );
  const resultMapper = stdioMcp.slice(
    stdioMcp.indexOf("internal fun remoteMcpCallResult"),
    stdioMcp.indexOf("private fun sanitizeMcpToolSegment"),
  );

  assert.match(resultMapper, /\?: raw/);
  assert.match(resultMapper, /previewJson = raw,/);
  assert.doesNotMatch(resultMapper, /raw\.take\(600\)|raw\.take\(1200\)|raw\.length <= 1200/);
});

test("remote MCP results preserve one complete projection for the tool loop and history", async () => {
  const remoteMcp = await source(
    "app/src/main/java/cn/com/omnimind/bot/mcp/RemoteMcpClient.kt",
  );
  const summaryMapper = remoteMcp.slice(
    remoteMcp.indexOf("private fun buildSummaryText"),
    remoteMcp.indexOf("private fun deepStringMap"),
  );

  assert.match(summaryMapper, /return textBlocks\.joinToString\("\\n"\)/);
  assert.match(summaryMapper, /return gson\.toJson\(result\)/);
  assert.match(summaryMapper, /private fun buildPreviewJson\(result: Any\?\): String \{\s*return gson\.toJson\(result\)\s*\}/);
  assert.doesNotMatch(summaryMapper, /\.take\(|max(?:Chars|Length)|truncate/i);
});

test("memory search has no OmniBot default result ceiling", async () => {
  const memoryTool = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/MemoryToolHandler.kt",
  );
  const searchHandler = memoryTool.slice(
    memoryTool.indexOf('"memory_search" ->'),
    memoryTool.indexOf('"memory_write_daily" ->'),
  );

  assert.match(searchHandler, /val limit = requestedLimit \?: Int\.MAX_VALUE/);
  assert.doesNotMatch(searchHandler, /coerce(?:AtMost|In)|MAX_(?!VALUE)|DEFAULT_|\.take\(/);
});

test("tool detail keeps the complete persisted result while its compact preview stays visual-only", async () => {
  const transcript = await source(
    "ui/lib/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart",
  );
  const resultRenderer = transcript.slice(
    transcript.indexOf("String _buildStructuredOutputText"),
    transcript.indexOf("String _buildPreviewText"),
  );
  const detailSheet = transcript.slice(
    transcript.indexOf("class _AgentToolDetailContent"),
    transcript.indexOf("String _agentToolCopyText"),
  );

  assert.match(transcript, /int\? maxOutputLines,/);
  assert.match(resultRenderer, /maxOutputLines == null && rawMap\.isNotEmpty/);
  assert.match(resultRenderer, /final detailMap = [\s\S]*?rawMap[\s\S]*?: previewMap;/);
  assert.match(resultRenderer, /_formatCompleteResultPayload\(detailResult\)/);
  assert.match(transcript, /JsonEncoder\.withIndent\('  '\)\.convert\(jsonDecode\(normalized\)\)/);
  assert.doesNotMatch(transcript, /int maxChars = 6000|maxOutputLines: 80/);
  assert.doesNotMatch(detailSheet, /maxOutputLines:/);
});

test("the local ACP connection terminates prompts with PromptResponse, not private turn events", async () => {
  const connection = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/XiaowanAcpConnection.kt",
  );

  assert.match(connection, /Event\.SessionUpdateEvent\(update\)/);
  assert.match(connection, /Event\.PromptResponseEvent\(/);
  assert.match(connection, /PromptResponse\(stopReason = StopReason\.CANCELLED\)/);
  assert.doesNotMatch(connection, /AgentStreamEvent|acp\/presentation|codex\/event/);
  assert.doesNotMatch(connection, /turn\/(?:started|completed|failed|cancelled)/);
});
