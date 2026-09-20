# Notification access audit — 2026-09-17

Request: determine whether Xiaowan can read delivery notifications through Android notification access (not GUI automation), and test it.

Physical audit: OnePlus PJE110, b49f281b, Android 16, installed app 0.6.3/code 16. PackageManager query for `android.service.notification.NotificationListenerService` scoped to `cn.com.omnimind.bot` returned `No services found`. POST_NOTIFICATIONS is granted; no notification-listener grant exists for this package. Direct notification access is not implemented/available in this installed package. There is no receiver to test with a synthetic delivery notification; no notification content was collected and no grant was changed.

Source inspection agrees: no NotificationListenerService/BIND_NOTIFICATION_LISTENER_SERVICE registration in the repository app. TaskRuntimeSettings notification entries are the app's own persisted notification IDs. OmniLinkAgentProvider projects remote notification metadata (applicationId, postedAt, hasTitle, hasBody, etc.), not title/body content; this is not direct local delivery-notification reading.

Executable audit:

```sh
python3 scripts/verify-notification-read-capability.test.py
python3 scripts/verify-notification-read-capability.py b49f281b docs/testing/artifacts/notification-access-20260917/physical-audit.json
```

Result: 3 parser/permission distinction regressions passed; physical audit reports `directNotificationAccessReady=false`. This is verified absence, not a successful notification-read test. If implemented later, posting/updating/removing a synthetic notification, authorization/revocation, process restart and actual Agent tool access need additional physical end-to-end acceptance.


## Built-in implementation and acceptance (same day, after the initial audit)

User authorized adding the capability. Added Android NotificationListenerService, system settings entry, default-off per-application allowlist, and `notifications_read` through the existing built-in Agent capability catalog/handler. No additional ACP lifecycle or notification-body database was added. Queries return the current active set for one allowed package, optionally filtered by time and capped at 50. Notification contents are marked untrusted. Returned tool results may enter the existing conversation/model context; the reader itself does not archive bodies. Dismissed notifications and automatic subscriptions are outside this implementation.

Physical device: OnePlus PJE110, serial b49f281b, Android 16. Installed developStandard debug 0.6.3/code 16. Final APK SHA-256: `1addcf82801c1c08b03a0a55a94918c0436b5a33eacfd53c33b53893dae13288`.

Results:
- Android JVM regressions: NotificationAccessTest, 4 passed (catalog definition, authorization/isolation, disconnected/invalid inputs, active-set filtering/order/update/removal).
- Flutter regressions: notification_access_page_test plus settings_page_test, 3 passed. Focused analyzer: no issues.
- Audit parser tests: 4 passed, including short/full component-name normalization.
- Final Android build and physical installation passed.
- Physical `granted.json`: canonical Agent catalog registration, default app denial, shell fixture publication OOB_PREPARING, replacement OOB_DELIVERED, removal without stale content, app revocation all passed.
- Physical `restart.json`: the same checks passed after force-stop and reopening the main app.
- Physical `revoked.json`: actual system UI permission revocation blocks reading; tested on the final APK.
- Physical settings navigation passed. Final notification page title bounds [439,156][641,216], below the status bar; page correctly shows system permission as ungranted after revocation.

Final device state: system notification access disabled, fixture app removed from allowlist, synthetic notifications cleared. Enable Settings → 通知访问, grant system access and select desired applications to use the feature.

Reproduction (grant/revoke using Android Settings UI before the corresponding phase):

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests '*NotificationAccessTest' :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
cd ui
flutter test test/features/home/pages/settings/notification_access_page_test.dart test/features/home/pages/settings/settings_page_test.dart
cd ..
python3 scripts/verify-notification-read-capability.test.py
python3 scripts/verify-notification-read-device.py b49f281b granted docs/testing/artifacts/notification-access-20260917/granted.json
# Force-stop/reopen the main app before repeating as restart.json.
python3 scripts/verify-notification-read-device.py b49f281b revoked docs/testing/artifacts/notification-access-20260917/revoked.json
```

Limits: physical tests use synthetic notifications posted by Android shell and the production reader plus real Agent catalog. No private notification body was exported. A live model choosing/invoking the tool and an actual food-delivery order have not been exercised; those end-to-end scenarios remain unverified. The debug fixture receiver is protected by android.permission.DUMP and is absent from release variants.


## Real Agent demo and bulk selection (follow-up, 2026-09-17)

User requested “全部打开”, actual message reading, real-time listening, and a complete Agent → memory demo without mocked data. Added “全部开启 / 全部关闭” using one atomic persisted allowlist update for currently installed packages (including system SMS apps, excluding Xiaowan itself). Newly installed apps remain off. Existing individual switches still apply. Added MessagingStyle message extraction and the cancellable `notifications_wait` capability, which awaits the real NotificationListenerService posted/removed callback and returns the current snapshot. It runs inside the existing ACP tool call, waits at most 120 seconds per call, and does not create a background Agent session or permanent subscription. Memory writes continue to use the existing Agent memory tools.

Final tested APK: 0.6.3/code 16, SHA-256 `f2088ff6069a27a329e0575875da0760a83dfebdc6a4e832cf5225961d1572b2`, installed on PJE110 b49f281b Android 16. Focused Android regressions: 6 passed, including bulk system-app selection and wait event/cancellation behavior. Flutter consent/bulk toggle regression passed; focused analyzer clean. Build passed.

Physical UI: tapped “全部开启”; 453 current packages became allowed, including `com.android.mms` and `com.coloros.alarmclock`. After force-stop/relaunch the same 453 selections persisted. System notification access remains enabled for the user-requested demo.

Real Agent runs used the saved configured model through the regular Xiaowan chat UI; no provider/model mock, tool-result injection, direct memory write, or shell notification fixture was used:

1. Conversation 86, marker `OOB_LIVE_NOTIFICATION_MEMORY_1789639639077`: `notifications_read(com.android.mms)` returned zero current notifications. `notifications_wait(...,120)` honestly timed out with `changed=false`. Agent wrote a daily observation and searched memory; canonical completion was `end_turn`. **No SMS body was available, so actual incoming SMS/MessagingStyle body acceptance remains unverified.** Evidence: `artifacts/notification-access-20260917/sms-live-timeout.json`. UI send/reply passed. The generic journey turn assertion was emulator-only; it was replaced for these physical demos with the notification-specific canonical history assertion.
2. Conversation 87, marker `OOB_LIVE_NOTIFICATION_CLOCK_1789639924460`: Agent called `notifications_read`, then `notifications_wait`. While waiting, the actual ColorOS Clock app was initialized and granted notification permission, and a real 30-second timer was launched via Android's standard SET_TIMER intent. The app posted title “计时” and body “00:00:30”. `notifications_wait` returned one notification with `changed=true`, `timedOut=false`. Agent then called `memory_write_daily` with the observed clock content and `memory_search`, which returned the saved tag. Official completion and UI final reply both passed. The timer finished; Clock returned to its idle “开始” screen. Evidence: `artifacts/notification-access-20260917/clock-agent-live.json`.

Executable regression entry points:

```sh
OOB_ALLOW_PHYSICAL_DEVICE=1 node scripts/verify-agent-user-journey.mjs b49f281b scripts/fixtures/agent-user-journeys/notification-clock-memory.json /tmp/notification-clock-demo
# Once Agent is waiting, trigger the real Clock source (complete first-use UI if needed):
adb -s b49f281b shell am start -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH 30 --es android.intent.extra.alarm.MESSAGE OOB_Notification_Demo --ez android.intent.extra.alarm.SKIP_UI true
# Substitute the run-specific marker printed by the sender:
OOB_ANDROID_SQLITE=cache/oob-sqlite-wrapper python3 scripts/assert-notification-agent-demo.py b49f281b OOB_LIVE_NOTIFICATION_CLOCK_1789639924460 /tmp/notification-clock-verified.json
```

Physical device has no system sqlite3. Read-only history verification used the emulator's SQLite executable plus its matching libsqlite in app cache, through `cache/oob-sqlite-wrapper`; `agent_test_database.py` now accepts optional `OOB_ANDROID_SQLITE` and still uses SQLite online backup, never separately copying live database/WAL. No app credentials or unrelated message bodies were exported. The assertion rejects shell fixture sources, requires an actual changed event with a body, tagged memory write and successful recall, and official Agent completion.

Scope: notification access reads system notifications, not the SMS inbox or arbitrary in-app advertisement overlays. A message must produce a visible/readable system notification. This demo proves real notification event → real Agent tool → daily memory → retrieval. Always-on listening after the turn/app exits is not implemented.

Restart acceptance: after force-stop/relaunch, a new conversation 88 (`OOB_LIVE_NOTIFICATION_RECALL_1789640156914`) called only `memory_search` and retrieved the saved Clock observation. No new notification read or memory write was needed. UI journey and canonical `end_turn` passed. Evidence: `artifacts/notification-access-20260917/restart-agent-memory.json`; verify with the same `assert-notification-agent-demo.py` using the recall marker.

## Natural “recent messages” request, real Agent (2026-09-17 follow-up)

Physical conversation 89, marker `OOB_LIVE_NOTIFICATION_RECENT_1789646444461`. Input: “What recent messages do I have? Please check and summarize in Chinese.” No tool names, package names or notification bodies were provided in the input. Agent used installed-app discovery and `notifications_read` for 12 packages. Successful reads returned Weibo 6, Xiaohongshu 3, Taobao 1 and Kuaishou 4 active notification records, with actual body text. SMS/email and the other queried packages returned zero. Counts include group summaries, and some retained notifications are older than today. UI send/reply and final canonical completion passed. Bodies were not copied into repository evidence.

Executable journey: `scripts/fixtures/agent-user-journeys/notification-recent-messages.json`; assert with `scripts/assert-notification-agent-demo.py b49f281b OOB_LIVE_NOTIFICATION_RECENT_1789646444461 OUTPUT` (same SQLite override as above). Evidence: `artifacts/notification-access-20260917/recent-messages-agent.json`.

Interpretation limit: the Agent's answer overstated coverage (“all possible sources”) and equated no notifications with no personal messages. The verified conclusion is narrower: it read current notifications for the listed 12 apps, not historical inboxes or every installed app. The user-facing response should preserve this distinction.
