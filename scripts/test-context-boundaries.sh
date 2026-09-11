#!/usr/bin/env bash
# Run from any directory. Device UI journeys require an isolated, configured AVD.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest \
  --tests 'cn.com.omnimind.bot.agent.AgentSystemPromptTest' \
  --tests '*Memory*Test' \
  --tests 'cn.com.omnimind.bot.agent.AgentContextBudgetTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentContextOverflowTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentConversationContextCompactorTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentToolOutputMetadataTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentOrchestratorTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentConversationHistory*Test' \
  --tests 'cn.com.omnimind.bot.agent.AgentFileReadSupportTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentImageAttachmentSupportTest' \
  --tests 'cn.com.omnimind.bot.agent.ToolImageAcpPayloadTest' \
  --tests 'cn.com.omnimind.bot.agent.XiaowanToolResultPayloadTest' \
  --tests 'cn.com.omnimind.bot.agent.JsonRequestEncodingTest'
flutter_bin="${OOB_FLUTTER_BIN:-$(sed -n 's/^flutter.sdk=//p' local.properties)/bin/flutter}"
cd ui
"$flutter_bin" test \
  test/features/home/pages/chat/chat_architecture_test.dart \
  test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart \
  test/services/agent_event_reducer_test.dart \
  test/services/conversation_history_service_test.dart
