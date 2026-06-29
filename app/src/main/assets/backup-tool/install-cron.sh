#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
CONFIG_FILE="${BACKUP_CONFIG:-$SCRIPT_DIR/config.env}"

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

mkdir -p "$LOG_DIR" "${STATE_DIR:-$SCRIPT_DIR/state}"
mkdir -p "$(dirname "$RESTIC_PASSWORD_FILE")"
umask 077

if [ ! -f "$RESTIC_PASSWORD_FILE" ]; then
  dd if=/dev/urandom bs=32 count=1 2>/dev/null | base64 > "$RESTIC_PASSWORD_FILE"
  chmod 600 "$RESTIC_PASSWORD_FILE" 2>/dev/null || true
  echo "Generated restic password file: $RESTIC_PASSWORD_FILE"
fi

if ! command -v restic >/dev/null 2>&1; then
  if command -v apk >/dev/null 2>&1; then
    apk add --no-cache restic
  fi
fi

command -v restic >/dev/null 2>&1 || {
  echo "restic is not installed. Install it first: apk add --no-cache restic" >&2
  exit 1
}

command -v crontab >/dev/null 2>&1 || {
  echo "crontab is not available on this system" >&2
  exit 1
}

MARK_BEGIN="# BEGIN omnibot-app-daily-restic-backup"
MARK_END="# END omnibot-app-daily-restic-backup"
CATCH_UP_MARK_BEGIN="# BEGIN omnibot-app-backup-catch-up"
CATCH_UP_MARK_END="# END omnibot-app-backup-catch-up"
CRON_CMD="TZ=${BACKUP_TIMEZONE:-UTC} /bin/sh $SCRIPT_DIR/bin/backup.sh >> $LOG_DIR/cron.log 2>&1"
CRON_LINE="$CRON_SCHEDULE $CRON_CMD"
CATCH_UP_CMD="TZ=${BACKUP_TIMEZONE:-UTC} /bin/sh $SCRIPT_DIR/bin/catch-up.sh >> $LOG_DIR/scheduler.log 2>&1"
CATCH_UP_LINE="${CRON_CATCH_UP_SCHEDULE:-*/30 * * * *} $CATCH_UP_CMD"
TMP_CRON=$(mktemp)

(crontab -l 2>/dev/null || true) | awk \
  -v begin="$MARK_BEGIN" -v end="$MARK_END" \
  -v catch_begin="$CATCH_UP_MARK_BEGIN" -v catch_end="$CATCH_UP_MARK_END" '
  $0 == begin {skip=1; next}
  $0 == end {skip=0; next}
  $0 == catch_begin {skip=1; next}
  $0 == catch_end {skip=0; next}
  skip != 1 {print}
' > "$TMP_CRON"

{
  printf '%s\n' "$MARK_BEGIN"
  printf '%s\n' "$CRON_LINE"
  printf '%s\n' "$MARK_END"
  printf '%s\n' "$CATCH_UP_MARK_BEGIN"
  printf '%s\n' "$CATCH_UP_LINE"
  printf '%s\n' "$CATCH_UP_MARK_END"
} >> "$TMP_CRON"

crontab "$TMP_CRON"
rm -f "$TMP_CRON"

chmod +x "$SCRIPT_DIR"/bin/*.sh "$SCRIPT_DIR/install-cron.sh" "$SCRIPT_DIR/uninstall-cron.sh" 2>/dev/null || true

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

if command -v crond >/dev/null 2>&1 && ! crond_running; then
  TZ="${BACKUP_TIMEZONE:-UTC}" crond -b -L "$LOG_DIR/crond.log"
  echo "Started crond in the background; log: $LOG_DIR/crond.log"
fi

if [ "${START_BACKUP_WATCHDOG:-1}" = "1" ]; then
  /bin/sh "$SCRIPT_DIR/bin/start-watchdog.sh"
fi

echo "Installed daily cron backup:"
echo "$CRON_LINE"
echo "Installed catch-up cron:"
echo "$CATCH_UP_LINE"
echo "Display time: ${CRON_DISPLAY_TIME:-$CRON_SCHEDULE}"
