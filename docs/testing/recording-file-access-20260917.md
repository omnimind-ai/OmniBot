# Recent recording unavailable to Agent — 2026-09-17

User reported that Agent could not find a just-created screen recording. Physical device PJE110 b49f281b, installed 0.6.3/code16, Android16. The actual recording exists in `/sdcard/Pictures/Screenshots/`, recorded 20:02:17, modified 20:02:23, size 4,802,335 bytes. Only metadata was inspected; video was not copied or opened.

Observed Agent conversation89 user entry40951 requested organizing the recent recording. `terminal_execute` listed the public storage directories but saw Screenshots as `total 0`, then repeatedly searched and incorrectly concluded the file did not exist. Several commands hid errors via `2>/dev/null` and pipelines completed with exit0, so successful command completion was not proof of successful file access.

Physical evidence: shell stat succeeds; the same stat under `run-as cn.com.omnimind.bot` returns Permission denied. AppOps MANAGE_EXTERNAL_STORAGE is default (recent rejected access); READ_MEDIA_VIDEO is ignore. The app manifest declares all-files access but does not declare READ_MEDIA_VIDEO. Existing UI offers 所有文件访问权限 via the public-storage settings handler.

Executable red regression (already run, exit1):

```sh
python3 scripts/verify-recording-file-access.py b49f281b /sdcard/Pictures/Screenshots/Record_2026-09-17-20-02-17_5b17c6e510bce824f9d850dff297e4be.mp4 docs/testing/artifacts/recording-file-access-20260917/permission-denied.json
```

Expected once authorized: appCanStat=true and same byte size. Current result: systemCanStat=true, appCanStat=false, Permission denied. This reproduces the actual file-access failure, rather than treating an empty search as evidence of absence.

Diagnosis only: no permission was changed, no user media modified, no product fix claimed. Granting all-files access through the existing settings entry or explicitly attaching the selected recording can provide access. After a permission change, rerun the regression and a real Agent search before declaring the workflow fixed. Agent handling should distinguish inaccessible storage from no matching files; that behavior has not been fixed in this diagnosis.

Follow-up correction: after the user enabled all-files access, AppOps UID mode was allow and the actual Agent terminal successfully found and copied the recording, while adb run-as still returned Permission denied. Run-as has a different external-storage view and cannot be treated as authoritative app-runtime acceptance. The earlier lack of permission was supported by both AppOps and actual Agent output; future acceptance must use real Agent file operations. The consecutive-turn recovery journey and SHA-256 verification now prove actual access. See `agent-plan-only-20260917.md`.
