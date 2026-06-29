#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
TOOL_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
CONFIG_FILE="${BACKUP_CONFIG:-$TOOL_DIR/config.env}"

[ -f "$CONFIG_FILE" ] || exit 0

# shellcheck disable=SC1090
. "$CONFIG_FILE"

[ "${BACKUP_ENABLED:-0}" = "1" ] || exit 0

export TZ="${BACKUP_TIMEZONE:-UTC}"
PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

mkdir -p "$LOG_DIR" "${STATE_DIR:-$TOOL_DIR/state}"
LOG_FILE="${STARTUP_LOG_FILE:-$LOG_DIR/startup.log}"
LOCK_FILE="${STARTUP_LOCK_FILE:-$TOOL_DIR/startup.lock}"

log() {
  printf '%s startup: %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >> "$LOG_FILE"
}

exec 9>"$LOCK_FILE"
if command -v flock >/dev/null 2>&1; then
  flock -n 9 || {
    log "another startup ensure is already running"
    exit 0
  }
fi

if [ ! -d "$BACKUP_SOURCE" ]; then
  log "source missing; skip scheduler ensure: $BACKUP_SOURCE"
  exit 0
fi

log "ensuring backup scheduler"
if /bin/sh "$TOOL_DIR/install-cron.sh" >> "$LOG_FILE" 2>&1; then
  log "scheduler install/check completed"
else
  log "scheduler install/check failed"
  exit 1
fi

if /bin/sh "$SCRIPT_DIR/catch-up.sh" >> "$LOG_FILE" 2>&1; then
  log "catch-up check completed"
else
  log "catch-up check failed"
  exit 1
fi

log "startup ensure finished"
