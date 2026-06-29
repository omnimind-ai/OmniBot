#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
TOOL_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
CONFIG_FILE="${BACKUP_CONFIG:-$TOOL_DIR/config.env}"

if [ ! -f "$CONFIG_FILE" ]; then
  printf 'configured: false\n'
  exit 0
fi

# shellcheck disable=SC1090
. "$CONFIG_FILE"

export TZ="${BACKUP_TIMEZONE:-UTC}"
export RESTIC_REPOSITORY="$BACKUP_REPOSITORY"
export RESTIC_PASSWORD_FILE
PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

list_crond_processes() {
  for proc in /proc/[0-9]*; do
    pid=${proc##*/}
    case "$pid" in *[!0-9]*) continue ;; esac
    [ -r "$proc/cmdline" ] || continue
    cmdline=$(cat "$proc/cmdline" 2>/dev/null | tr '\000' ' ' || true)
    case "$cmdline" in
      crond|crond\ *|*/crond|*/crond\ *) printf '%s %s\n' "$pid" "$cmdline" ;;
    esac
  done
  return 0
}

printf 'configured: true\n'
printf 'enabled: %s\n' "${BACKUP_ENABLED:-0}"
printf 'time: %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')"
printf 'source: %s\n' "$BACKUP_SOURCE"
printf 'repository: %s\n' "$BACKUP_REPOSITORY"
printf 'password_file: %s\n' "$RESTIC_PASSWORD_FILE"
printf 'restic: '
if command -v restic >/dev/null 2>&1; then
  restic version | head -n 1
else
  printf 'not installed\n'
fi

printf 'crond: '
crond_processes=$(list_crond_processes)
if [ -n "$crond_processes" ]; then
  printf '%s\n' "$crond_processes"
else
  printf 'not running\n'
fi

printf 'watchdog: '
watchdog_pids=""
for proc in /proc/[0-9]*; do
  pid=${proc##*/}
  case "$pid" in *[!0-9]*) continue ;; esac
  [ -r "$proc/cmdline" ] || continue
  cmdline=$(cat "$proc/cmdline" 2>/dev/null | tr '\000' ' ' || true)
  case " $cmdline " in
    *" $SCRIPT_DIR/watchdog.sh "*) watchdog_pids="$watchdog_pids $pid" ;;
  esac
done
if [ -n "$watchdog_pids" ]; then
  printf 'running pid(s):%s\n' "$watchdog_pids"
else
  printf 'not running\n'
fi

LAST_SUCCESS_FILE="${LAST_SUCCESS_FILE:-${STATE_DIR:-$TOOL_DIR/state}/last-success.env}"
if [ -f "$LAST_SUCCESS_FILE" ]; then
  printf '\nlast success marker:\n'
  sed -n '1,20p' "$LAST_SUCCESS_FILE"
fi

if command -v restic >/dev/null 2>&1 && [ -f "$RESTIC_PASSWORD_FILE" ]; then
  printf '\nlatest snapshots:\n'
  restic snapshots --latest 5 2>/dev/null || true
fi
