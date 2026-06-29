#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
TOOL_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
CONFIG_FILE="${BACKUP_CONFIG:-$TOOL_DIR/config.env}"

[ -f "$CONFIG_FILE" ] || exit 0

# shellcheck disable=SC1090
. "$CONFIG_FILE"

[ "${BACKUP_ENABLED:-0}" = "1" ] || exit 0

mkdir -p "$LOG_DIR" "${STATE_DIR:-$TOOL_DIR/state}"
PID_FILE="${WATCHDOG_PID_FILE:-$TOOL_DIR/watchdog.pid}"
LOG_FILE="${SCHEDULER_LOG_FILE:-$LOG_DIR/scheduler.log}"

is_watchdog_pid() {
  pid="$1"
  [ -n "$pid" ] || return 1
  [ -d "/proc/$pid" ] || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  cat "/proc/$pid/cmdline" 2>/dev/null | tr '\000' ' ' | grep -F "$SCRIPT_DIR/watchdog.sh" >/dev/null 2>&1
}

find_watchdog_pids() {
  for proc in /proc/[0-9]*; do
    pid=${proc##*/}
    case "$pid" in *[!0-9]*) continue ;; esac
    [ "$pid" = "$$" ] && continue
    [ -r "$proc/cmdline" ] || continue
    cmdline=$(cat "$proc/cmdline" 2>/dev/null | tr '\000' ' ' || true)
    case " $cmdline " in
      *" $SCRIPT_DIR/watchdog.sh "*) printf '%s\n' "$pid" ;;
    esac
  done
}

if [ -f "$PID_FILE" ]; then
  old_pid=$(cat "$PID_FILE" 2>/dev/null || true)
  if is_watchdog_pid "$old_pid"; then
    echo "Backup watchdog already running: pid=$old_pid"
    exit 0
  fi
fi

existing_pid=$(find_watchdog_pids | head -n 1 || true)
if [ -n "$existing_pid" ]; then
  printf '%s\n' "$existing_pid" > "$PID_FILE"
  echo "Backup watchdog already running: pid=$existing_pid"
  exit 0
fi

if command -v setsid >/dev/null 2>&1; then
  setsid /bin/sh "$SCRIPT_DIR/watchdog.sh" >> "$LOG_FILE" 2>&1 &
else
  nohup /bin/sh "$SCRIPT_DIR/watchdog.sh" >> "$LOG_FILE" 2>&1 &
fi

echo "Started backup watchdog; log: $LOG_FILE"
