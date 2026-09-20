# Shared native Codex / new physical phone test (in progress)

Device: OnePlus PJE110, Android 16, ADB serial b49f281b. Original app 0.6.1
(versionCode 11); installed 0.6.2.3 debug (15) with `adb install -r`, preserving
data. No app data cleared. Credentials are not included in this record.

Current diagnostic path: phone localhost:17321 → adb reverse → existing
codex-bridge localhost:17321 → upstream codex-acp 1.11.0 (Apache-2.0) →
transport-only shared-codex-proxy.cjs → shared Codex 0.153.4 localhost:17331.
The latter is loopback-only, has no public tunnel, and is also connected by an
isolated native desktop instance (PID 13749 at startup), using
CODEX_APP_SERVER_WS_URL and --user-data-dir=/tmp/oob-native-shared-desktop-test.
Native desktop TCP connection verified with lsof; rendered UI not verified.
This uses an internal version-specific desktop setting, not a stable public API.

Existing authenticated backend on :17329 and SSH relay on 4090 :18329 remain
separate earlier protocol-test services. USB diagnostics do not establish WAN
or website acceptance.

## Actual failures and changes

1. Phone marker A: initialize timed out. Remote JSON-RPC requests lacked the
   mandatory jsonrpc:"2.0" field. Upstream adapter accepted the equivalent
   standard request. Added field in the existing outbound transport method.
2. Phone markers A2/A3: response appeared in phone UI, but shared backend had
   no corresponding loaded thread. Native logs showed local UUID session
   ownership. Explicit codex-remote was treated as a local owner, and session
   admission omitted the selected agent identity. Fixed the routing predicate
   and propagated the explicit identity through the existing session creation,
   prompt and abandoned-session close path. Retesting is required.
3. During these misrouted local turns the user row disappeared and displayed
   response lost its first character; processing label persisted after send
   became available. This is observed failure, not successful synchronization.

## Repeatable checks

- `scripts/verify-phone-remote-config.py b49f281b`: real device prerequisites
  passed after configuration; not sufficient for session acceptance.
- `scripts/verify-shared-codex-acp.cjs`: upstream initialize and existing shared
  session/load passed (9 history updates) against authenticated :17329. Required
  env: OOB_ACP_BIN, CODEX_PATH (proxy path), OOB_SHARED_CODEX_URL,
  OOB_SHARED_CODEX_TOKEN_FILE, OOB_TEST_SESSION_ID; NODE_PATH points to ws.
- RemoteCodexAppServerSessionTest: 8 passed after JSON-RPC/routing changes.
- `flutter test --no-pub test/services/agent_runtime_service_test.dart`:
  63 passed after explicit session admission identity was added.

Build logs: /tmp/oob-jsonrpc-phone-build.log, /tmp/oob-remote-route-build.log,
/tmp/oob-remote-identity-build.log. Last identity build/reinstallation and final
real-device verification remain in progress; do not infer completion from logs
named here without reading their results.

## Remaining full acceptance

Same native desktop session ID on phone; phone-originated and desktop-originated
messages visible in both actual UIs; live output; disconnect/reconnect history
without duplicate execution; app restart; website WSS over cellular.

Computer-use tool refused access to com.openai.codex for safety reasons. No
alternate UI automation was used to bypass that restriction. A user question
requests the desktop test action/visible result while phone work continues.
Website domain/edge configuration access also remains unavailable.

## Latest physical-device evidence

After an actual local→remote Harness selection on the phone, marker A6 created
remote session `01a09a18-50d6-76f3-9755-d172c3dd5f3c` and real server turn
`01a09a18-5200-7732-86ea-239e3b216acc`. Official thread/read confirms completed
status, full user input, and full response `OOB_NEW_PHONE_SYNC_A6`. Phone screen
showed the user message but no response: NOT a successful UI-sync acceptance.

Phone Sessions screen lists “OOB new phone sync A6” with matching ID suffix.
Opening it initially failed ACP schema validation because session/load omitted
cwd and mcpServers. Added both required fields, built and installed successfully.
The actual subsequent load reaches the adapter and returns updates, but native
AgentRuntimeManager logs “Dropping turn-scoped event without a turn id:
method=session/update ...”. Official ACP load history updates have no wire turnId.
This remaining history-projection issue is verified, not fixed. Do not invent
new turn IDs or bypass the one reducer to hide it.

The shared Bridge real-model script also completed all checks (auth, new,
prompt, list, reconnect/load, continued context), log
/tmp/oob-shared-bridge-real.log. Again this is protocol evidence only.

A4 also triggered an actual ForegroundServiceDidNotStartInTimeException for
TaskRuntimeService on Android 16 after remote request failure. Service lifecycle
failure remains unresolved, requires repeatable real-device regression.

Current temporary instrumented Bridge PID 15140; native test backend PID 13748;
native isolated desktop PID 13749. Do not assume PIDs remain valid without
checking. The test bridge logs methods/session IDs/errors only to
/tmp/oob-shared-wire-errors.log (0600). No prompt payload logging added to product.

## 2026-09-13 follow-up: actual phone history recovery now passes

Added a small opt-in `_meta` extension to a pinned COPY of codex-acp 1.11.0.
`deploy/codex-relay/prepare-shared-acp.py` verifies the original distribution
SHA-256 and preserves the Apache-2.0 license. It returns the already-read official
Codex thread snapshot from session/load only when requested. No new Agent loop,
history source, reducer or invented turn IDs. Native normalizes this into the
existing remote snapshot boundary, only when its thread ID matches the request.

`verify-shared-codex-acp.cjs` passed both metadata identity checks and identical
repeated loads. New phone build installed; UI actually displayed full A6 user
message and full reply. After another actual phone send (B), backend completed
turn `01a09a23-a37a-7631-8318-c6476b67bf96`; live display did NOT show its reply.
Reopening the existing remote session did restore both messages correctly.

Executable physical checks:
- `python3 scripts/open-shared-test-on-pje110.py b49f281b`: PASS real session-list
  navigation and A6 history restoration (coordinates guarded to this model).
- With NODE_PATH=/tmp/oob-phone-bridge-runtime/node_modules and
  OOB_SHARED_CODEX_URL=ws://127.0.0.1:17331:
  `node scripts/verify-phone-shared-history.cjs b49f281b 01a09a18-50d6-76f3-9755-d172c3dd5f3c OOB_NEW_PHONE_SYNC_A6 OOB_NEW_PHONE_SYNC_B`
  PASS: actual visible phone rows and backend each contain one user/reply pair.
- The same visible-history check FAILED before reopening: missing B reply.
  This failure remains the live-update acceptance blocker.

Bridge currently uses /tmp/oob-phone-bridge-runtime/codex-acp-shared.mjs, PID
16219 at startup. Temporary coordinator identity-only diagnostics added to
locate live projection mismatch; remove diagnostics once resolved.
Native UI confirmation question updated to the existing test session title;
no answer yet. Public WSS and real network recovery remain unverified.

### Correction: live UI renders, accessibility-only assertion was insufficient

Actual screenshot of the PJE110 after sending D, without reloading the session,
shows full OOB_NEW_PHONE_SYNC_D and a completed indicator. Artifact:
artifacts/shared-codex-2026-09-13/phone-live-D.png. Coordinator identity diagnostics
confirm streaming deltas entered the correct existing runtime, reserved host
turn and assistant message. No projection logic was changed for this probe;
identity-only diagnostics were removed from source afterward.

Android uiautomator exposes the live user row but omits the live assistant text,
whereas snapshot-loaded assistant text is exposed. Thus the XML-only verifier
failure did not establish missing visible output. Live D display is PASS by
agent-operated physical screenshot inspection, not by XML automation. XML-only
script remains suitable for restored history; do not label its live-output
assertion a complete renderer test. Earlier B/C 'missing' observations are
inconclusive about rendering rather than a proven live-stream failure.

### Interruption acceptance: FAILED (real PJE110)

Sent fresh E once from the actual phone. The verifier observed backend turn
`01a09a32-8e94-7701-b436-8db19894dcee` inProgress, then stopped only the isolated
Bridge. Backend stayed running, but the same turn became interrupted with one
user item and no assistant reply. Phone displayed 本轮执行失败. Thus backend
process survival does not establish generation survival. No E resend occurred.
The continuation/no-loss criterion FAILED; duplicate execution was not observed.
Bridge restarted on the same port. The executable regression now fails promptly
on an interrupted/failed backend turn instead of waiting for its deadline.
This is Bridge-process interruption, not a verified cellular network outage.

Acceptance flow (all required for full success):
1. Connect phone over authenticated public WSS with Wi-Fi off and USB detached.
2. Open the exact same session ID in phone and native desktop Codex.
3. Send marker A from phone: native desktop and phone each show one input/reply.
4. Send marker B from native desktop: both show one input/reply without reopening.
5. Disconnect phone during a longer turn; desktop completes that same turn.
6. Reconnect phone without resend: missing items restored, same IDs, no duplicates.
7. Restart phone app and desktop client; same session/history remains available.
Record device/version, session/turn IDs, endpoint and visible results per step.
Current evidence covers phone sends/live display and explicit history loading;
native desktop display/send, public WSS, and complete reconnect remain unaccepted.

### Phone process exit: PASS after Bridge lifetime fix

The shared protocol verifier previously only searched the disconnected-turn
marker in serialized history, which also matched the user input. Replaced this
with explicit completed status and exactly one matching assistant reply. The
strengthened real-backend test passed (session
`01a09a34-f0d4-7c10-8f2d-f59bdfb0cdc4`). This remains protocol-only evidence.

A subscribed protocol observer did not prevent the real phone F turn from being
interrupted when its Bridge process was stopped. F turn
`01a09a35-80cd-7011-8cde-5b352e74359c` failed continuation.

The Bridge previously killed the per-connection ACP process when a socket closed.
It now drains admitted session/prompt requests until official responses arrive
before tearing down a disconnected connection's process. It neither replays a
prompt nor synthesizes completion. Client requests requiring input/approval get
a JSON-RPC error when the client is absent; disconnected clients never authorize
action. Bridge process failure remains distinct and is not fixed by this change.

Physical PJE110 b49f281b, installed 0.6.2.3/code15 debug, USB reverse17321:
- Sent G once via actual phone UI.
- Verifier observed inProgress and force-stopped only the phone app.
- Backend turn `01a09a37-3f7e-7e93-b621-368ac9a054cc` completed with exactly one
  user/reply pair; backend gained exactly one turn. PASS.
- Reopened the phone app and selected the same remote session from its list.
  `open-shared-test-on-pje110.py b49f281b OOB_NEW_PHONE_SYNC_G`: PASS.
- This verifies explicit recovery after app termination, not automatic reconnect
  or cellular/WSS access, and does not verify native desktop display.

Reproduce interruption with a fresh marker entered in the actual phone, then:
```sh
OOB_TEST_PHONE_SERIAL=b49f281b \
OOB_SHARED_CODEX_URL=ws://127.0.0.1:17331 \
NODE_PATH=/tmp/oob-phone-bridge-runtime/node_modules \
node scripts/verify-shared-interruption.cjs SESSION_ID FRESH_MARKER BRIDGE_PID
```
Verifier checks that PID belongs to the isolated test Bridge. It sends no prompt.
Use the phone send button after ARMED. Without OOB_TEST_PHONE_SERIAL it tests
Bridge process termination, a separately failing fault mode.

### External-client send: live display FAILED, explicit history recovery PASS

`verify-shared-external-send.cjs` sends once to the already selected isolated
session from an independent official app-server protocol client. H completed
as turn `01a09a39-5d58-7610-a230-db2eb7833e08`, with exactly one full assistant
reply. Physical phone screenshot immediately after completion still ends at G:
artifacts/shared-codex-2026-09-13/phone-external-H-before-reload.png. Unlike prior
XML-only observations this is direct visual inspection of the phone viewport.

Reopened the SAME session through its existing phone list:
`open-shared-test-on-pje110.py b49f281b OOB_NEW_PHONE_SYNC_H` PASS.
`verify-phone-shared-history.cjs b49f281b 01a09a18-50d6-76f3-9755-d172c3dd5f3c OOB_NEW_PHONE_SYNC_H`
PASS: exactly one visible user/reply pair matching backend. This does not pass
real-time remote-origin display, and the sender was a protocol client, not the
native desktop UI. No H replay was performed.

Source evidence for next work: pinned codex-acp loadSession streams history;
its subscribeToSessionEvents call is installed in prompt handling. Phone only
loaded this connection before H. Native also requires a host prompt reservation
for turn-less ACP session/update attribution, and current phone snapshot sync
is intentionally a one-time hydration. Thus end-to-end remote-origin live
observation and reconnect hydration are missing capabilities, not just tunnel
configuration. Any integration must reuse the same ACP session and reducer,
retain authoritative identities and avoid adding snapshot polling/replay.

### Official compatibility check (2026-09-13)

Official v2 announcement remains Draft and explicitly recommends negotiated,
feature-flagged testing rather than production default. It separates prompt
acceptance from state-update completion. v2 session/resume supports replayFrom
and required message IDs for idempotent replay. These match needed primitives,
but do not establish working adapter or native-client integration.

Sources checked:
- https://agentclientprotocol.com/announcements/acp-v2-draft
- https://agentclientprotocol.com/protocol/v2/session-setup
- https://agentclientprotocol.com/protocol/v2/migration

Installed codex-acp is 1.11.0 with its existing v1 lifecycle. Note that v1 does
not categorically prohibit out-of-turn session updates; the currently installed
adapter and app's host reservation ownership do not implement remote-origin
observation. Do not claim v2 is the only possible solution. It is an official
path requiring an explicit lifecycle migration, not a small configuration fix.
A scope question about experimental v2 has been sent to the user and remains
unanswered. No v2 product migration has been applied.

### Final diagnostic-free APK installed and checked

Gradle assembleDevelopStandardDebug passed (20s). Installed with adb install -r
on PJE110 b49f281b; data retained, 0.6.2.3/code15. APK SHA-256:
`e6cf4ada1313b1644568fcfd96748639eb8a7e3cfe7c024b61d7bbc12f71fbc1`.
Temporary coordinator diagnostics are absent in this source/build. Physical
remote session-list navigation and H history restoration passed again after
installation. Full synchronization acceptance remains incomplete as above.

### Physical phone through 4090 relay: history PASS

Revalidated SSH zewen access; nginx config target root:root0644, not writable,
noninteractive sudo unavailable. Prepared website-acp-location.conf, not applied.
Opened reverse SSH4090:18321 -> Mac17321 ACP Bridge and local forward
Mac17332 -> 4090:18321. Existing real ACP Bridge regression passed authentication,
initialize,new,prompt,list,new-connection load and retained context through this
route (/tmp/oob-acp-4090-probe.log).

Force-stopped idle phone app and replaced USB reverse with17321 -> Mac17332.
PJE110 re-opened same A6 session through its UI and H history matched backend
exactly once. This is a real phone traversing the4090 SSH relay, but its initial
network hop is still USB. Public WSS and cellular remain unverified. Test tunnels
are temporary processes, not production supervision.

### Resumed first-stage native desktop investigation

Current device PJE110 remains connected. Old isolated native desktop PID13749
was gone; its logs show native thread/resume failed with invalid transport in
mcp_servers.codex_app. A TCP connection had not established native session use.

Installed official standalone Codex0.153.4 using inspected installer from
https://chatgpt.com/codex/install.sh, pinned to the installed bundled version.
Executable links are in /tmp/oob-managed-codex-bin; original brew command and
desktop application not replaced. Installer-added temporary PATH block was
removed from ~/.zprofile. Official daemon bootstrap succeeded; remote control
is disabled, backend=pid, daemon reports0.153.4 and auto-update enabled.

Native CODEX_APP_SERVER_USE_LOCAL_DAEMON is gated by startup overrides; test
desktop continued spawning a standalone stdio backend. Official app-server
proxy returned no initialize response in a5-second open-stdin probe. Neither
path counts as successful native session sharing.

Experimental native-daemon-proxy.sh (historical test filename) now preserves
the desktop stdio transport and reuses shared-codex-proxy.cjs to reach17331.
Native initialization succeeded, but native recovery also exposed the MCP
configuration error. This wrapper deliberately does not forward desktop
startup tool configuration and is not production-ready. Original live task
was not restarted. Failed isolated windows were closed; current isolated
window PID23776 remains for user inspection (/tmp/oob-native-stdio-shared-test).
Its current process uses a /tmp diagnostic copy of the proxy logging config
field names only; subsequent launches use the non-diagnostic repository proxy.

User was asked to open the exact A6 test task and confirm H. Computer Use
explicitly prohibits controlling Codex UI, so a native GUI pass cannot be
asserted from backend RPC probes. That native acceptance is pending.


### Agent-operated native entry and tunnel recovery test (19:20–19:27)

User explicitly authorized self-operated tests. Used purpose-built Codex app
navigate/send/read tools; did not bypass the Computer Use restriction.
Native navigate to A6 returned success. Native send of
`Reply only OOB_NATIVE_PHONE_SYNC_I. Do not use tools.` returned an error:
`thread 01a09a18-50d6-76f3-9755-d172c3dd5f3c already has an active writer`.
Native read still reported notLoaded and completed H. Native navigation is not
proof of rendered UI or shared execution. No resend of I occurred. This is a
real native-entry failure, not a successful two-protocol-client test.

PJE110 b49f281b remains on diagnostic-free 0.6.2.3/code15. Agent opened the
app and sent fresh J once through its actual input/send controls. It displayed
execution failure; neither temporary SSH tunnel remained listening, while the
ACP Bridge was still running. Screenshot: phone-tunnel-down-J.png. Native read
still ended at H. J was not replayed.

Extended existing install-macos.py with --tunnel-only and optional
--test-forward-port. Installed separate ACP reverse/forward launchd jobs,
keeping backend ownership unchanged. Full real Bridge regression through4090
passed authentication, initialize/new, model output, list, reload and retained
context. The immediate probe before launchd startup returned ECONNREFUSED;
subsequent service status and the full probe succeeded.

New executable `python3 scripts/verify-acp-relay-restart.py` terminated only
these isolated SSH jobs while idle. PASS: reverse25382->25664 and
forward25385->25676. This establishes launchd restart, not model continuation
through a fault or stable public WSS. Bridge/backend remain test processes.

After restart, agent navigated the real phone list back to A6 (not another
newer test task), checked H history and sent fresh K once. The physical screen
showed exact assistant `OOB_PHONE_NATIVE_SYNC_K` (phone-relay-recovered-K.png).
Native read_thread independently returned the same user/reply in completed
turn `01a09a84-743e-7733-9f66-a57a2042227d` (7.838s). PASS: phone send/receive
through4090 after tunnel restart and native API history visibility. Desktop
live rendering and native send remain unpassed. The phone required UI
navigation; automatic foreground catch-up was not demonstrated.

Screenshot after history reload shows an earlier failure block without its J
query in that viewport; preservation of failed local queries across remote
history hydration needs a dedicated reproduction (not yet implemented or
verified). Do not label history preservation fully accepted.

Remaining: single execution backend usable by native desktop, external-origin
live observation through canonical ACP/runtime, automatic reconnect catch-up,
acknowledgement-loss/concurrent admission, and website WSS over cellular.


### Official daemon transport corrected (21:07–21:14)

Fresh inspection found the managed daemon currently running0.154.0, while
bundled desktop CLI is0.153.4. Do not assume the earlier installation version
remained pinned. No daemon restart/update was performed during this test.
Official versioned source app-server-transport/src/transport/unix_socket.rs
shows WebSocket upgrade on the Unix control socket. JSONL into official raw
app-server proxy returned no initialize response. The initial Node WebSocket
probe failed; daemon log specifically reported unsupported
sec-websocket-extensions. Setting perMessageDeflate:false made initialize pass.

shared-codex-proxy.cjs now accepts exactly one explicit URL or private owned
Unix socket. It converts existing adapter JSONL to official WebSocket, disables
compression negotiation, retains network token requirements and adds no
session lifecycle, replay or backend spawning. Tested proxy initialize and
clean stdin close against the real daemon.

Existing executable verify-codex-shared-session.cjs now disables compression
negotiation and PASSed on the daemon Unix endpoint: two clients share a thread,
output/completion fanout, reconnect history, and completion after client
connection loss. This remains protocol-only. Isolated thread:
01a09ae4-40da-7853-aa19-e1940cd4c4c4, renamed OOB daemon shared M.
Existing verify-shared-codex-acp.cjs PASSed against the same daemon via the
modified proxy and pinned adapter1.11.0: initialize,9 history updates, identity
snapshot and repeated load unchanged. ACP protocol remains v1.

Started separate loopback ACP Bridge17334 PID29192 using the existing bridge
runtime and modified transport. Phone USB reverse17321 temporarily points to
Mac17334 (NOT the4090 route17332). Force-stopped idle phone, reopened its list,
selected exact daemon test thread, guarded prior test history, and sent fresh
M exactly once with real input/send controls. PJE110 b49f281b, Android16,
0.6.2.3/code15: exact OOB_DAEMON_PHONE_M visibly rendered, screenshot
phone-official-daemon-M.png. Native read API independently returned completed
turn01a09ae6-a6eb-7b81-b6b6-f46c808d498f, duration6.643s, same input/reply.
No APK rebuild was needed because only host transport changed.

Native send API of fresh OOB_DAEMON_NATIVE_N to that same thread FAILED with
active writer. N was not replayed. Native desktop process has not switched to
the daemon; native API history read is not live rendering. No raw-history edits,
lock deletion, or global desktop restart were used to force a pass. Native
backend attachment remains unresolved; v2 migration has not started. Current
phone test connection is direct USB to the daemon Bridge, not public WSS or4090.


### Mimi-style loopback SSH connection workflow

System SSH127.0.0.1:22 refused connections; sudo-n unavailable. Used stock
OpenSSH10.2p1 in an isolated unprivileged loopback-only instance with private
generated host/client keys. Authentication succeeded without changing system
Remote Login or authorized_keys. Noninteractive shell initially lacked codex;
configured that sshd's PATH to verified CLI0.154.0. Shared socket already existed;
no second Codex backend was started.

Extended verify-codex-shared-session.cjs with optional OOB_TEST_SSH_TARGET and
OOB_TEST_SSH_CONFIG. Its first client now can use the stock SSH raw proxy with
normal WebSocket framing; other clients connect directly to the existing daemon.
Initial temporary-config real regression PASS: shared thread
01a09b1c-4ab1-7641-9d7e-1eff1cadd094, real model output to both clients,
reconnect restoration and completion after client disconnect. This is protocol
acceptance of the native SSH connection flow, not execution by native Desktop.

Persisted isolated sshd and key/config paths under
~/.local/share/omnibot/shared-ssh-test, supervised by launchd. Added dedicated
SSH alias omnibot-shared-local-test via a scoped Include, preserving and backing
up original config bytes. Persistent-alias SSH version/socket preflight PASS.
No model/provider changes and no GPL implementation code copied.

Native settings still require adding/selecting that SSH host and opening the
test workspace through it. User action requested because Codex GUI control is
explicitly prohibited by the available Computer Use tool; native task APIs
provide no SSH registration capability. Native same-session write/live UI are
still pending. Public website WSS, persistent ACP observation and v2 migration
are not passed by this SSH setup.
