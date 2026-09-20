# Physical phone shared-session preflight — 2026-09-13

Request: phone connected; test real-time phone/desktop conversation sync.

Device: vivo V2502A / PD2502, Android 16, USB ADB device available.
Installed app: cn.com.omnimind.bot 0.6.2.3, versionCode 15, debuggable,
lastUpdateTime 2026-09-11 16:48:48. No APK installed during this check.

Actual operations: launched the installed app, opened its chat-mode selector,
confirmed the Remote Codex entry, attempted selection. The existing chat
remained visible; no shared-session success observed. Existing historical
failure messages were not attributed to this test. No user prompt sent,
private history copied, existing credentials replaced, or app data cleared.

Read only the remote bridge preferences (token redacted): remote disabled,
endpoint ws://192.168.3.177:17321/codex, auth token present. Phone TCP connection
to that endpoint timed out after 5 seconds. ADB connectivity is not app-server
connectivity. 4090's reverse relay remains listening on 127.0.0.1:18329.

Repeatable real-device prerequisite test:

```sh
python3 scripts/verify-phone-remote-config.py ADB_SERIAL
```

Result: FAIL prerequisites (remote disabled, configured endpoint unreachable).
This executable checks device configuration and actual TCP reachability only;
it must not be used to claim WebSocket authentication or shared-session success.

Source contract check: RemoteCodexAppServerSession sends ACP protocolVersion 1
initialize/clientCapabilities. The isolated shared backend uses official Codex
app-server, a different protocol. Native desktop still runs a separate stdio
app-server. A URL change alone does not solve either boundary.

Blocked/not executed: phone prompt in a shared native desktop session, both UI
output, reconnect merge without duplicate execution, public website WSS path.
These require the ACP/shared-backend integration and desktop connection first.
End-to-end acceptance remains 待真机验证; this preflight itself used the real phone.
