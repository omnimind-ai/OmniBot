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

if [ "${BACKUP_ENABLED:-0}" != "1" ]; then
  echo "Omnibot backup is disabled."
  exit 0
fi

export TZ="${BACKUP_TIMEZONE:-UTC}"
PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

mkdir -p "$LOG_DIR" "${STATE_DIR:-$TOOL_DIR/state}"
LOG_FILE="$LOG_DIR/backup.log"

log() {
  printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" | tee -a "$LOG_FILE"
}

fail() {
  log "ERROR: $*"
  exit 1
}

if ! command -v restic >/dev/null 2>&1; then
  if command -v apk >/dev/null 2>&1; then
    apk add --no-cache restic >>"$LOG_FILE" 2>&1 || true
  fi
fi

command -v restic >/dev/null 2>&1 || fail "restic is not installed"
[ -d "$BACKUP_SOURCE" ] || fail "BACKUP_SOURCE is not a directory: $BACKUP_SOURCE"
[ -f "$RESTIC_PASSWORD_FILE" ] || fail "RESTIC_PASSWORD_FILE is missing: $RESTIC_PASSWORD_FILE"
[ -f "$EXCLUDES_FILE" ] || fail "EXCLUDES_FILE is missing: $EXCLUDES_FILE"

umask 077
mkdir -p "$(dirname "$LOCK_FILE")"
exec 9>"$LOCK_FILE"
if command -v flock >/dev/null 2>&1; then
  flock -n 9 || fail "another backup is already running"
fi

export RESTIC_REPOSITORY="$BACKUP_REPOSITORY"
export RESTIC_PASSWORD_FILE

is_local_repo=0
case "$BACKUP_REPOSITORY" in
  /*|./*|../*) is_local_repo=1 ;;
esac

if [ "$is_local_repo" -eq 1 ]; then
  mkdir -p "$BACKUP_REPOSITORY"

  source_kb=$(du -sk "$BACKUP_SOURCE" 2>/dev/null | awk '{print $1}')
  available_kb=$(df -Pk "$BACKUP_REPOSITORY" | awk 'NR==2 {print $4}')
  required_kb=$((source_kb * REQUIRE_FREE_PERCENT_OF_SOURCE / 100 + MIN_FREE_KB_AFTER_BACKUP))

  if [ "$available_kb" -lt "$required_kb" ]; then
    fail "not enough free space for local backup repository. available=${available_kb}KB required=${required_kb}KB source=${source_kb}KB"
  fi
fi

set +e
restic snapshots --no-lock >/dev/null 2>&1
repo_status=$?
set -e

if [ "$repo_status" -eq 10 ]; then
  log "Initializing restic repository: $BACKUP_REPOSITORY"
  restic init >>"$LOG_FILE" 2>&1 || fail "restic init failed"
elif [ "$repo_status" -ne 0 ]; then
  fail "cannot access restic repository, restic snapshots exit code $repo_status"
fi

log "Starting backup: source=$BACKUP_SOURCE repo=$BACKUP_REPOSITORY"

set +e
restic backup "$BACKUP_SOURCE" \
  --exclude-file "$EXCLUDES_FILE" \
  --exclude-caches \
  --one-file-system \
  --tag omnibot \
  --tag app-data >>"$LOG_FILE" 2>&1
backup_status=$?
set -e

if [ "$backup_status" -ne 0 ]; then
  fail "restic backup failed with exit code $backup_status. See $LOG_FILE"
fi

if [ "${CHECK_AFTER_BACKUP:-1}" = "1" ]; then
  log "Checking repository"
  if [ -n "${CHECK_READ_DATA_SUBSET:-}" ]; then
    restic check --read-data-subset "$CHECK_READ_DATA_SUBSET" >>"$LOG_FILE" 2>&1 || fail "restic check failed"
  else
    restic check >>"$LOG_FILE" 2>&1 || fail "restic check failed"
  fi
fi

log "Backup completed"

LAST_SUCCESS_FILE="${LAST_SUCCESS_FILE:-${STATE_DIR:-$TOOL_DIR/state}/last-success.env}"
tmp_success="${LAST_SUCCESS_FILE}.$$"
{
  printf 'LAST_SUCCESS_EPOCH=%s\n' "$(date '+%s')"
  printf 'LAST_SUCCESS_LOCAL=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')"
  printf 'LAST_SUCCESS_SOURCE=%s\n' "$BACKUP_SOURCE"
  printf 'LAST_SUCCESS_REPOSITORY=%s\n' "$BACKUP_REPOSITORY"
} > "$tmp_success"
mv -f "$tmp_success" "$LAST_SUCCESS_FILE"
