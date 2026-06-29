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
LOCK_FILE="${SCHEDULER_LOCK_FILE:-$TOOL_DIR/scheduler.lock}"

log() {
  printf '%s catch-up: %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >> "$LOG_FILE"
}

backup_time="${BACKUP_DAILY_TIME:-03:17}"
case "$backup_time" in
  [0-2][0-9]:[0-5][0-9]) ;;
  *) log "invalid BACKUP_DAILY_TIME: $backup_time"; exit 1 ;;
esac

today=$(date '+%Y-%m-%d')
schedule_epoch=$(date -d "$today $backup_time:00" '+%s')
now_epoch=$(date '+%s')
grace_seconds=$(( ${CATCH_UP_GRACE_MINUTES:-10} * 60 ))
due_epoch=$(( schedule_epoch + grace_seconds ))

if [ "$now_epoch" -lt "$due_epoch" ]; then
  exit 0
fi

exec 8>"$LOCK_FILE"
if command -v flock >/dev/null 2>&1; then
  flock -n 8 || {
    log "another catch-up check is already running"
    exit 0
  }
fi

if [ ! -f "$RESTIC_PASSWORD_FILE" ]; then
  log "password file missing: $RESTIC_PASSWORD_FILE"
  exit 1
fi

latest_epoch=$(
  RESTIC_REPOSITORY="$BACKUP_REPOSITORY" \
  RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
  BACKUP_SOURCE="$BACKUP_SOURCE" \
  python3 - <<'PY' 2>/dev/null || true
import datetime
import json
import os
import subprocess
import sys

env = os.environ.copy()
source = env.get("BACKUP_SOURCE")
try:
    proc = subprocess.run(
        ["restic", "snapshots", "--json"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env,
        check=False,
    )
except Exception:
    sys.exit(2)

if proc.returncode != 0:
    sys.exit(proc.returncode)

try:
    snapshots = json.loads(proc.stdout or "[]")
except Exception:
    sys.exit(3)

latest = 0
for snap in snapshots:
    paths = snap.get("paths") or []
    if source and source not in paths:
        continue
    ts = snap.get("time")
    if not ts:
        continue
    ts = ts.replace("Z", "+00:00")
    try:
        dt = datetime.datetime.fromisoformat(ts)
    except ValueError:
        continue
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=datetime.timezone.utc)
    latest = max(latest, int(dt.timestamp()))

print(latest)
PY
)

latest_epoch="${latest_epoch:-0}"
case "$latest_epoch" in
  ''|*[!0-9]*) latest_epoch=0 ;;
esac

if [ "$latest_epoch" -ge "$schedule_epoch" ]; then
  exit 0
fi

LAST_SUCCESS_FILE="${LAST_SUCCESS_FILE:-${STATE_DIR:-$TOOL_DIR/state}/last-success.env}"
stamp_epoch=0
if [ -f "$LAST_SUCCESS_FILE" ]; then
  stamp_epoch=$(awk -F= '$1 == "LAST_SUCCESS_EPOCH" {print $2; exit}' "$LAST_SUCCESS_FILE" 2>/dev/null || printf '0')
fi
case "$stamp_epoch" in
  ''|*[!0-9]*) stamp_epoch=0 ;;
esac

if [ "$stamp_epoch" -ge "$schedule_epoch" ]; then
  exit 0
fi

log "daily backup missing after $backup_time; running catch-up backup"
/bin/sh "$SCRIPT_DIR/backup.sh"
log "catch-up backup finished"
