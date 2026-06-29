# Omnibot Automatic Backup

Omnibot includes an opt-in local backup feature for the app private data directory. It uses `restic` inside the embedded Alpine environment and stores encrypted snapshots in public storage by default.

## Defaults

- Backup source: the Android app data directory, for example `/data/user/0/cn.com.omnimind.bot`.
- Backup tool path: `/workspace/.omnibot/backup`.
- Restic repository: `/sdcard/Backups/omnibot-restic-repo`.
- Restic password file: `/sdcard/Backups/omnibot-restic-password`.
- Schedule: daily at 03:17 Beijing time.

The password file content is never copied into the app UI, logs, or repository. Keep a separate copy of the password file. Without it, restic snapshots cannot be restored.

## Enabling

Open **Settings > Alpine Environment > Automatic backup** and enable the switch. Public storage access is required for the default `/sdcard` repository.

When enabled, Omnibot installs or updates:

- the backup scripts in `/workspace/.omnibot/backup`;
- a daily cron entry;
- a catch-up cron entry that checks for a missed daily backup every 30 minutes;
- a local watchdog that keeps `crond` running and triggers catch-up checks.

The feature is disabled by default. Installing the app or opening the settings page does not start backups until the user enables it.

## Recovery Behavior

On app open, Omnibot checks the backup config. If backup is enabled, it refreshes the bundled scripts, ensures the scheduler is installed, starts the watchdog, and runs a catch-up check.

The embedded Alpine init script also runs the same scheduler check when Alpine starts. This covers cases where the app process was killed and later reopened.

This is a user-space scheduler. If the phone reboots, Android kills the Alpine process, cron, and watchdog. Backups resume only after Omnibot or the embedded Alpine environment starts again.

## Manual Backup

The settings page includes **Back up now**. It runs the same restic backup script immediately and then refreshes the status.

## Restore Basics

Restore requires the repository and the password file. From Alpine:

```sh
export RESTIC_REPOSITORY=/sdcard/Backups/omnibot-restic-repo
export RESTIC_PASSWORD_FILE=/sdcard/Backups/omnibot-restic-password
restic snapshots
restic restore latest --target /tmp/omnibot-restore
```

Review restored files before copying them over a live app data directory.
