#!/bin/sh
# Experimental isolated desktop chat probe only. Desktop startup -c MCP/tool overrides are
# not shared backend configuration; those desktop-only tools are not accepted here.
# Session settings still travel in official app-server RPCs.
set -eu
codex_bin="$HOME/.codex/packages/standalone/current/codex"
for arg in "$@"; do
  if [ "$arg" = app-server ]; then
    printf '%s\n' 'OmniBot isolated shared-backend probe: desktop startup tool overrides are not forwarded.' >&2
    exec node "$(dirname "$0")/shared-codex-proxy.cjs" app-server
  fi
done
exec "$codex_bin" "$@"
