#!/bin/sh
set -eu

stable_fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)" || \
  stable_fail 'SKILL_INSTALL_RUNTIME_UNAVAILABLE'

SYSTEM_PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
NODE_BIN="$(PATH="$SYSTEM_PATH" command -v node 2>/dev/null || true)"
[ -n "$NODE_BIN" ] && [ -x "$NODE_BIN" ] || \
  stable_fail 'SKILL_INSTALL_RUNTIME_UNAVAILABLE'

exec env -i HOME=/nonexistent PATH="$SYSTEM_PATH" \
  "$NODE_BIN" "$SCRIPT_DIR/install_exact_skill.cjs" "$@"
