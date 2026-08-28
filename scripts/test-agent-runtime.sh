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
RUN_LIVE="${OMNIBOT_LIVE_PROVIDER_TEST:-0}"

usage() {
  cat <<'EOF'
Usage: scripts/test-agent-runtime.sh [options]

Options:
  --live          Run the real Provider smoke test using an environment token.
  --offline       Do not call any network Provider, even if a token is set.
  --skip-gradle   Skip Android/JVM tests.
  --skip-flutter  Skip Flutter tests.
  --help          Show this help.

Live Provider environment:
  OMNIBOT_TEST_API_KEY   Preferred token; fallback: LLMTHU_API_KEY or OPENAI_API_KEY.
  OMNIBOT_TEST_BASE_URL  Preferred base URL; fallback: LLMTHU_API_BASE_URL.
  OMNIBOT_TEST_MODEL     Preferred model; fallback: LLMTHU_MODEL or GLM-5.1.
  OMNIBOT_TEST_TIMEOUT_MS Request timeout; default: 30000.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --live) RUN_LIVE=1 ;;
    --offline) RUN_LIVE=0 ;;
    --skip-gradle) RUN_GRADLE=0 ;;
    --skip-flutter) RUN_FLUTTER=0 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

run_step() {
  local label="$1"
  shift
  printf '\n== %s ==\n' "$label"
  "$@"
}

run_step "Node protocol/provider tests" \
  node --test \
    scripts/agent_provider_smoke.test.mjs \
    scripts/sync_models_dev_catalog.test.mjs

if [[ "$RUN_GRADLE" == "1" ]]; then
  run_step "Android Agent/ACP JVM tests" \
    ./gradlew --no-daemon --no-parallel \
      -Dkotlin.incremental=false \
      -Dkotlin.compiler.execution.strategy=in-process \
      :app:testDevelopStandardDebugUnitTest \
      --tests 'cn.com.omnimind.bot.agent.runtime.LocalAcpRuntimeTest' \
      --tests 'cn.com.omnimind.bot.agent.runtime.AgentRuntimeManagerConfigTest' \
      --tests 'cn.com.omnimind.bot.agent.ManagedAcpAdapterPreparationTest' \
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
      test/features/home/pages/agent/agent_mode_setting_page_test.dart \\
      test/services/agent_runtime_service_test.dart"
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

printf '\nAgent runtime test set passed.\n'
