#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
TOOL_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
CONFIG_FILE="${BACKUP_CONFIG:-$TOOL_DIR/config.env}"

if [ ! -f "$CONFIG_FILE" ]; then
  echo "Missing config file: $CONFIG_FILE" >&2
  exit 1
fi

# shellcheck disable=SC1090
. "$CONFIG_FILE"

[ "${BACKUP_ENABLED:-0}" = "1" ] || exit 0

export TZ="${BACKUP_TIMEZONE:-UTC}"
PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

mkdir -p "$LOG_DIR" "${STATE_DIR:-$TOOL_DIR/state}"
LOG_FILE="${SCHEDULER_LOG_FILE:-$LOG_DIR/scheduler.log}"
LOCK_FILE="${WATCHDOG_LOCK_FILE:-$TOOL_DIR/watchdog.lock}"
PID_FILE="${WATCHDOG_PID_FILE:-$TOOL_DIR/watchdog.pid}"
INTERVAL="${WATCHDOG_INTERVAL_SECONDS:-600}"

log() {
  printf '%s watchdog: %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >> "$LOG_FILE"
}

case "$INTERVAL" in
  ''|*[!0-9]*) INTERVAL=600 ;;
esac

exec 8>"$LOCK_FILE"
if command -v flock >/dev/null 2>&1; then
  flock -n 8 || {
    log "already running"
    exit 0
  }
fi

printf '%s\n' "$$" > "$PID_FILE"
trap 'rm -f "$PID_FILE"; exit 0' INT TERM EXIT

log "started; interval=${INTERVAL}s"

crond_running() {
  for proc in /proc/[0-9]*; do
    pid=${proc##*/}
    case "$pid" in *[!0-9]*) continue ;; esac
    [ -r "$proc/cmdline" ] || continue
    cmdline=$(cat "$proc/cmdline" 2>/dev/null | tr '\000' ' ' || true)
    case "$cmdline" in
      crond|crond\ *|*/crond|*/crond\ *) return 0 ;;
    esac
  done
  return 1
}

while :; do
  if [ -f "$CONFIG_FILE" ]; then
    # shellcheck disable=SC1090
    . "$CONFIG_FILE"
    [ "${BACKUP_ENABLED:-0}" = "1" ] || exit 0
  fi

  if command -v crond >/dev/null 2>&1; then
    if ! crond_running; then
      log "crond is not running; starting it"
      TZ="${BACKUP_TIMEZONE:-UTC}" crond -b -L "$LOG_DIR/crond.log" 8>&- || log "failed to start crond"
    fi
  else
    log "crond command is unavailable"
  fi

  /bin/sh "$SCRIPT_DIR/catch-up.sh" 8>&- || log "catch-up check failed"
  sleep "$INTERVAL" 8>&-
done
