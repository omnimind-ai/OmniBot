#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
MARK_BEGIN="# BEGIN omnibot-app-daily-restic-backup"
MARK_END="# END omnibot-app-daily-restic-backup"
CATCH_UP_MARK_BEGIN="# BEGIN omnibot-app-backup-catch-up"
CATCH_UP_MARK_END="# END omnibot-app-backup-catch-up"
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

crontab "$TMP_CRON"
rm -f "$TMP_CRON"

/bin/sh "$SCRIPT_DIR/bin/stop-watchdog.sh" 2>/dev/null || true

echo "Removed omnibot-app-daily-restic-backup cron block."
echo "Removed omnibot-app-backup-catch-up cron block."
echo "Backups, password file, and logs were left in place."
