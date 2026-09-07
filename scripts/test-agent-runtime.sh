#!/usr/bin/env bash
# Run the maintained local Agent/ACP regression set.
#
# By default this is safe for offline development: it runs local JVM, Flutter,
# and Node tests. Pass --live (or set OMNIBOT_LIVE_PROVIDER_TEST=1) to add one
# short real Provider model-list + chat-completion request. The token is read
# only from the environment and is never printed.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

RUN_GRADLE=1
RUN_FLUTTER=1
RUN_WEBCHAT=1
RUN_LIVE="${OMNIBOT_LIVE_PROVIDER_TEST:-0}"
HARNESS_CLI_DIR=""
RUN_LIVE_HARNESSES=0

usage() {
  cat <<'EOF'
Usage: scripts/test-agent-runtime.sh [options]

Options:
  --live          Run the real Provider smoke test using an environment token.
  --offline       Do not call any network Provider, even if a token is set.
  --skip-gradle   Skip Android/JVM tests.
  --skip-flutter  Skip Flutter tests.
  --skip-webchat  Skip WebChat conversation reconciliation tests.
  --harnesses DIR Run required Codex/Claude Code output regressions with a
                  disposable installed CLI directory (no real Provider calls).
  --live-harnesses DIR  Also run all five official Harnesses against the real
                       configured API, including tools, switching and restart.
  --help          Show this help.

Live Provider environment:
  OMNIBOT_TEST_API_KEY   Preferred token; fallback: LLMTHU_API_KEY or OPENAI_API_KEY.
  OMNIBOT_TEST_BASE_URL  Preferred base URL; fallback: LLMTHU_API_BASE or LLMTHU_API_BASE_URL.
  OMNIBOT_TEST_MODEL     Preferred model; fallback: LLMTHU_MODEL or GLM-5.1.
  OMNIBOT_TEST_SECOND_MODEL Required distinct model for --live-harnesses.
  OMNIBOT_TEST_TIMEOUT_MS Request timeout; default: 120000.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --live) RUN_LIVE=1 ;;
    --offline) RUN_LIVE=0; RUN_LIVE_HARNESSES=0 ;;
    --skip-gradle) RUN_GRADLE=0 ;;
    --skip-flutter) RUN_FLUTTER=0 ;;
    --skip-webchat) RUN_WEBCHAT=0 ;;
    --harnesses) [[ $# -ge 2 && -d "$2/node_modules" ]] || { echo '--harnesses requires an installed CLI directory' >&2; exit 2; }; HARNESS_CLI_DIR="$2"; shift ;;
    --live-harnesses) [[ $# -ge 2 && -d "$2/node_modules" ]] || { echo '--live-harnesses requires an installed CLI directory' >&2; exit 2; }; HARNESS_CLI_DIR="$2"; RUN_LIVE=1; RUN_LIVE_HARNESSES=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ "$RUN_LIVE_HARNESSES" == "1" && -z "${OMNIBOT_TEST_SECOND_MODEL:-}" ]]; then
  echo '--live-harnesses requires OMNIBOT_TEST_SECOND_MODEL; missing cases cannot count as passed.' >&2
  exit 2
fi

run_step() {
  local label="$1"
  shift
  printf '\n== %s ==\n' "$label"
  "$@"
}

run_step "Node protocol/provider tests" \
  node --test \
    scripts/agent-provider-observer.test.mjs \
    scripts/install-dev-shell.test.mjs \
    scripts/skill-install-shell.test.mjs \
    scripts/agent_provider_smoke.test.mjs \
    scripts/agent_memory_unbounded.test.mjs \
    scripts/agent_runtime_capability_contract.test.mjs \
    scripts/sync_models_dev_catalog.test.mjs

if [[ "$RUN_GRADLE" == "1" ]]; then
  run_step "Android Agent/ACP JVM tests" \
    ./gradlew --no-daemon --no-parallel \
      -Dkotlin.incremental=false \
      -Dkotlin.compiler.execution.strategy=in-process \
      :app:testDevelopStandardDebugUnitTest \
      --tests 'cn.com.omnimind.bot.agent.AgentOrchestratorTest' \
      --tests 'cn.com.omnimind.bot.agent.AgentEventAdapterTest' \
      --tests 'cn.com.omnimind.bot.agent.AgentConversationModePolicyTest' \
      --tests 'cn.com.omnimind.bot.agent.AgentSystemPromptTest' \
      --tests 'cn.com.omnimind.bot.agent.AgentLlmStreamAccumulatorTest' \
      --tests 'cn.com.omnimind.bot.agent.HttpAgentLlmClientTest' \
      --tests 'cn.com.omnimind.bot.agent.HttpControllerCustomHeadersTest' \
      --tests 'cn.com.omnimind.bot.agent.AgentRuntimeErrorSupportTest' \
      --tests 'cn.com.omnimind.bot.agent.AgentConversationHistorySupportTest' \
      --tests 'cn.com.omnimind.bot.agent.AgentConversationHistoryRepositoryTest' \
      --tests 'cn.com.omnimind.bot.agent.AgentToolDefinitionsVlmTest' \
      --tests 'cn.com.omnimind.bot.agent.BrowserHostStoreTest' \
      --tests 'cn.com.omnimind.bot.agent.BrowserObservationScriptsTest' \
      --tests 'cn.com.omnimind.bot.agent.BrowserToolResultPayloadsTest' \
      --tests 'cn.com.omnimind.bot.agent.LongTermMemoryIndexTest' \
      --tests 'cn.com.omnimind.bot.agent.WorkspaceMemoryEmbeddingIndexTest' \
      --tests 'cn.com.omnimind.bot.agent.WorkspaceMemoryShortHistoryIndexTest' \
      --tests 'cn.com.omnimind.bot.webchat.WorkspaceFileServiceTest' \
      --tests 'cn.com.omnimind.bot.webchat.BrowserMirrorServiceTest' \
      --tests 'cn.com.omnimind.bot.webchat.RealtimeHubTest' \
    --tests 'cn.com.omnimind.bot.agent.SubagentProgressTextTest' \
    --tests 'cn.com.omnimind.bot.agent.SubagentConcurrencyTest' \
    --tests 'cn.com.omnimind.bot.agent.SubagentProfileRegistryTest' \
    --tests 'cn.com.omnimind.bot.agent.SubagentCapabilityInheritanceTest' \
    --tests 'cn.com.omnimind.bot.agent.AgentToolDefinitionsSubagentTest' \
      --tests 'cn.com.omnimind.bot.agent.AgentToolDefinitionsUnboundedParametersTest' \
      --tests 'cn.com.omnimind.bot.agent.SkillRuntimeBehaviorTest' \
      --tests 'cn.com.omnimind.bot.agent.CalendarListLimitTest' \
    --tests 'cn.com.omnimind.bot.agent.BrowserUseRequestTest' \
      --tests 'cn.com.omnimind.bot.agent.AgentRuntimeContextQueryTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.LocalAcpRuntimeTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.LocalAcpRuntimeConfigTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.LocalAcpRuntimeInitializationTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStoreTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.XiaowanSessionConfigTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.XiaowanAcpConnectionTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.RemoteCodexBridgeConnectionTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.AgentRuntimeProtocolPayloadTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.XiaowanAcpPresentationBridgeTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.AgentRuntimeManagerConfigTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.AgentConfigAdaptersTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.AgentAdapterCatalogTest' \
      --tests 'cn.com.omnimind.bot.agent.ManagedAcpAdapterPreparationTest' \
      --tests 'cn.com.omnimind.bot.agent.AcpPromptInputCompatibilityAdapterTest' \
      --tests 'cn.com.omnimind.bot.plugin.official.agentweb.AgentWebRuntimeTest' \
      --tests 'cn.com.omnimind.bot.plugin.official.agentweb.AgentWebPluginTest' \
      --tests 'cn.com.omnimind.bot.plugin.sandbox.XiaowanChatCompletionRequestFactoryTest' \
      --tests 'cn.com.omnimind.bot.agent.PlatformEmbeddingGatewayTest' \
      --tests 'cn.com.omnimind.bot.agent.AgentImageAttachmentSupportTest' \
      --tests 'cn.com.omnimind.bot.agent.AgentWorkspaceAttachmentSupportTest' \
      --tests 'cn.com.omnimind.bot.mcp.RemoteMcpClientInteropTest' \
      --tests 'cn.com.omnimind.bot.mcp.McpFileInboxTest' \
      --tests 'com.ai.assistance.operit.terminal.setup.EnvironmentInstallExecutionTest' \
      --tests 'com.ai.assistance.operit.terminal.setup.EnvironmentSetupLogicTest'
fi

if [[ "$RUN_FLUTTER" == "1" ]]; then
  FLUTTER_BIN="${FLUTTER_BIN:-}"
  if [[ -z "$FLUTTER_BIN" ]]; then
    FLUTTER_BIN="$(command -v flutter || true)"
  fi
  if [[ -z "$FLUTTER_BIN" && -x /Users/wuzewen/flutter/bin/flutter ]]; then
    FLUTTER_BIN=/Users/wuzewen/flutter/bin/flutter
  fi
  if [[ -z "$FLUTTER_BIN" ]]; then
    echo "Flutter was not found. Set FLUTTER_BIN or use --skip-flutter." >&2
    exit 1
  fi
  run_step "Flutter Agent UI/service tests" bash -c \
    "cd '$ROOT_DIR/ui' && '$FLUTTER_BIN' test \\
      test/agent_tool_summary_card_test.dart \\
      test/office_preview_service_test.dart \\
      test/widgets/streaming_text_test.dart \\
      test/widgets/message_bubble_timing_test.dart \\
      test/widgets/omnibot_markdown_body_math_test.dart \\
      test/agent_tool_transcript_test.dart \\
      test/features/home/pages/chat/chat_architecture_test.dart \\
      test/features/home/pages/chat/acp_config_button_test.dart \\
      test/features/home/pages/chat/widgets/chat_message_list_test.dart \\
      test/features/home/pages/chat/composer_keyboard_metrics_tracker_test.dart \\
      test/features/home/pages/chat/composer_lift_chain_test.dart \\
      test/features/home/pages/chat/composer_lift_intent_tracker_test.dart \\
      test/services/model_provider_config_service_test.dart \\
      test/services/model_provider_live_discovery_test.dart \\
      test/features/home/pages/model_provider_setting/model_provider_setting_page_test.dart \\
      test/features/home/pages/agent/agent_mode_setting_page_test.dart \\
      test/features/home/pages/agent/agent_config_page_test.dart \\
      test/features/home/pages/command_overlay/chat_bot_sheet_acp_test.dart \\
      test/features/home/pages/command_overlay/widgets/chat_input_area_test.dart \\
      test/services/agent_runtime_service_test.dart \\
      test/services/workspace_memory_service_test.dart \\
      test/features/memory/services/mem0_memory_service_test.dart \\
      test/services/agent_event_reducer_test.dart \\
      test/services/conversation_service_test.dart \\
      test/services/conversation_history_service_test.dart \\
      test/features/home/pages/settings/experience_misc_setting_page_test.dart \\
      test/features/home/pages/command_overlay/widgets/cards/terminal_output_utils_test.dart \\
      test/features/home/pages/chat/utils/deep_thinking_persistence_test.dart \\
      test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart \\
      test/features/home/pages/chat/conversation_manager_lifecycle_test.dart"
fi

if [[ "$RUN_WEBCHAT" == "1" ]]; then
  run_step "WebChat conversation tests" bash -c \
    "cd '$ROOT_DIR/webchat' && pnpm test && pnpm typecheck && pnpm build"
fi

if [[ "$RUN_LIVE" == "1" ]]; then
  if [[ -z "${OMNIBOT_TEST_API_KEY:-${LLMTHU_API_KEY:-${OPENAI_API_KEY:-}}}" ]]; then
    echo "--live requires OMNIBOT_TEST_API_KEY, LLMTHU_API_KEY, or OPENAI_API_KEY." >&2
    exit 1
  fi
  run_step "Live Provider smoke" node scripts/agent_provider_smoke.mjs
else
  printf '\n== Live Provider smoke ==\nSKIPPED (use --live with a test-token environment variable)\n'
fi

if [[ -n "$HARNESS_CLI_DIR" ]]; then
  run_step "Codex actual ACP answer, partial stream, failure" \
    node scripts/verify-codex-completed-messages.mjs "$HARNESS_CLI_DIR" app/build/reports/harness-adapters
  run_step "Claude Code actual ACP conversation and continuation" \
    node scripts/verify-installed-harness-adapters.mjs "$HARNESS_CLI_DIR" app/build/reports/harness-adapters claude-code conversation
  run_step "Claude Code official tool stdout and failure output" \
    node scripts/verify-claude-terminal-output.mjs "$HARNESS_CLI_DIR/node_modules/@agentclientprotocol/claude-agent-acp/dist/tools.js" \
      app/build/reports/harness-adapters/claude-code-anthropic-chat_completions.json
else
  printf '\nHarness output acceptance INCOMPLETE: rerun with --harnesses DIR.\n'
fi
if [[ "$RUN_LIVE_HARNESSES" == "1" ]]; then
  run_step "Real API: official Harness conversation acceptance" \
    node scripts/verify-live-harness-conversations.mjs "$HARNESS_CLI_DIR" app/build/reports/harness-adapters
fi
printf '\nSelected local checks passed. Real-device release acceptance must be recorded separately.\n'
