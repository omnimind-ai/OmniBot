#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
TOOL_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
CONFIG_FILE="${BACKUP_CONFIG:-$TOOL_DIR/config.env}"

if [ -f "$CONFIG_FILE" ]; then
  # shellcheck disable=SC1090
  . "$CONFIG_FILE"
fi

PID_FILE="${WATCHDOG_PID_FILE:-$TOOL_DIR/watchdog.pid}"

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

stop_pid() {
  pid="$1"
  is_watchdog_pid "$pid" || return 0
  pgid=$(ps -o pgid= -p "$pid" 2>/dev/null | awk '{print $1; exit}')
  current_pgid=$(ps -o pgid= -p "$$" 2>/dev/null | awk '{print $1; exit}')
  if [ -n "$pgid" ] && [ "$pgid" != "$current_pgid" ]; then
    kill -TERM "-$pgid" 2>/dev/null || true
  else
    kill "$pid" 2>/dev/null || true
  fi
  i=0
  while is_watchdog_pid "$pid" && [ "$i" -lt 10 ]; do
    sleep 1
    i=$((i + 1))
  done
  if is_watchdog_pid "$pid"; then
    if [ -n "$pgid" ] && [ "$pgid" != "$current_pgid" ]; then
      kill -KILL "-$pgid" 2>/dev/null || true
    else
      kill -KILL "$pid" 2>/dev/null || true
    fi
  fi
  echo "Stopped backup watchdog: pid=$pid"
}

if [ -f "$PID_FILE" ]; then
  stop_pid "$(cat "$PID_FILE" 2>/dev/null || true)"
  rm -f "$PID_FILE"
fi

for pid in $(find_watchdog_pids); do
  stop_pid "$pid"
done
