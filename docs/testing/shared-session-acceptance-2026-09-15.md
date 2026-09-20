# Shared Codex acceptance — 2026-09-15

Status: partial physical acceptance; full product NOT ACCEPTED.

## Installed candidate

- OnePlus PJE110, Android 16, USB serial b49f281b.
- developStandardDebug 0.6.3/code16, app data preserved with `adb install -r`.
- Initial candidate APK SHA256 `3472e1e8cc1d896c0ab6d3320bd270ab9b89455cf93b262d4f119c5b819f23a2`.
- Final installed candidate after client-message identity fix: SHA256 `6f440977a8ed2ab689b80212a09bdd14572206e7e9561b9da11f3f9710ecaf60`, same version/code, app data retained.
- This is the remote feature candidate, not the published stable 0.6.3 APK.
- Shared official Codex daemon 0.154.0; isolated workspace `oob-shared-session-probe`.
- Session `01a09ae4-40da-7853-aa19-e1940cd4c4c4`, title `OOB daemon shared M`.
- Phone → ADB reverse → Mac SSH forward → 4090 loopback18336 → reverse SSH → Mac Bridge17336 → same daemon.
- This route does not prove public WSS or cellular connectivity.

## Implemented boundaries

The existing remote transport owns bounded reconnection. Its existing session owner restores official `session/resume` subscriptions with history. Interrupted requests are failed, never automatically resent. Explicit disconnect cancels recovery; stale connection callbacks are ignored.

The shared conversation coordinator binds a viewed session without admitting a fake prompt. Official v2 whole-item history updates use session/turn/item identities in the existing reducer, including thought replacement and clear. History does not manufacture active generation or overwrite another active turn. The v2 page no longer applies a second native Codex snapshot projection after official replay.

## Actual results

| Operation | Result |
| --- | --- |
| Load isolated session and display existing `OOB_NGINX_PHONE_M` question/reply | PASS after explicit session selection |
| Remove only test SSH forward, send `OOB_AUTO_RECONNECT_N` from backend, restore forward | PASS: phone automatically displays question/reply without page reopening; same app PID27119; exactly one backend question/reply |
| External send `OOB_PASSIVE_LIVE_O` while phone remains on page | PASS: phone passively displays question/reply without refresh; exactly once in backend |
| Replace temporary Bridge with launchd-supervised existing Bridge, send `OOB_SUPERVISED_BRIDGE_P` | PASS: phone automatically recovers and displays question/reply |
| Send `OOB_PHONE_SEND_R` using actual phone composer | PASS: same shared backend, phone question/reply exactly once |
| Drop actual `session/prompt` success response then disconnect phone, marker `OOB_LOST_ACK_T` | FAIL: backend executes once but two visible phone user messages; fixed below |
| Repeat actual lost ACK with `OOB_LOST_ACK_V` after fix | PASS: ACK deliberately discarded, phone reconnects without reopening, same app PID1349, backend and visible phone question/reply exactly once |
| Four simultaneous authenticated ACP connections via4090 after ws8.21.3 update | PASS: health, file listing, auth rejection, ACPv2 initialize; median ping62.47ms, max66.14ms; not a capacity guarantee |
| Native Codex desktop GUI both sends and displays this session | NOT VERIFIED |
| Official website WSS and cellular phone | Nginx origin DEPLOYED and authenticated ACP ingress PASS; public TLS/FRP hostname and cellular phone NOT VERIFIED |
| Recovery during active tools/approval, extended outage beyond retry budget | NOT fully physically verified; automatic reconnect currently has a bounded retry budget |
| Atomic history/live cutover during resume | Uses upstream0.154.0 atomic resume response; two deterministic adapter regressions PASS; actual phone outage/recovery `OOB_ATOMIC_RECONNECT_Q` PASS without restart |

The initial history check after reinstall showed the remote greeting because no test session was selected. Explicitly selecting the isolated session passed; reconnect checks above never reopened it. Earlier candidate history binding failure motivated the coordinator regression.

Lost-ACK fixture first checked history too soon, before the accepted user item was readable (zero items). Corrected it to wait for authoritative persistence with a deadline, never resending. The subsequent read-only phone check exposed the actual duplicate bubble (`2 != 1`). Marker U was armed but cancelled before sending while the product fix was prepared. T was never resent.

The fix passes the existing phone message ID through ACP `_meta["dev.omnimind/clientMessageId"]` into official Codex `turn/start.clientUserMessageId`. Codex persists it as `userMessage.clientId`; the adapter retains it for live updates and history. The single reducer merges by that supplied identity even after the local dispatch reservation is released. No text matching, copied sessions or another message store is introduced. This is reconciliation, not a guarantee that arbitrary explicit duplicate prompt submissions are deduplicated by Codex.

## Executable regression entry points

```sh
# Private endpoint/token paths are supplied locally, never committed.
NODE_PATH=tools/codex-bridge/node_modules \
OOB_ALLOW_PHYSICAL_DEVICE=1 \
OOB_RECONNECT_FORWARD_LABEL=cn.omnimind.codex-acp-v2-relay-test.forward \
OOB_SHARED_CODEX_URL="$OOB_TEST_DAEMON_URL" \
node scripts/verify-phone-remote-reconnect.cjs SERIAL SESSION FRESH_OOB_MARKER

node scripts/verify-shared-external-send.cjs SESSION FRESH_OOB_MARKER
node scripts/verify-phone-shared-history.cjs SERIAL SESSION FRESH_OOB_MARKER

OOB_ALLOW_PHYSICAL_DEVICE=1 OOB_TEST_FORWARD_PORT=17337 \
OOB_TEST_FAULT_PORT=17339 OOB_TEST_PHONE_PORT=17321 \
node scripts/verify-phone-lost-ack.cjs SERIAL SESSION FRESH_OOB_MARKER
# Reconnect the explicitly isolated test forward so the actual phone enters
# the fixture, then send the printed input from its composer. The fixture
# drops only the first successful prompt response and restores ADB routing.
```

Use a fresh synthetic marker for each generation. The phone verifier checks authoritative backend identity/exact counts and actual UI accessibility text; it does not send, reopen or refresh. The fault verifier restores only the explicitly named test forwarding launch agent in `finally`.

- Flutter reducer/coordinator: 324 tests passed before candidate build/install.
- Final reducer/coordinator/runtime-service group: 388 tests PASS after lost-ACK identity repair; final APK build and actual install PASS.
- Focused RemoteCodexAppServerSession JVM tests passed; APK build passed.
- Bridge fixture: five ingress cases and four detached prompt cases passed with ws8.21.3; npm audit reports zero known vulnerabilities.
- Initial broad regression invocation failed environment setup (JDK21 not selected); rerun with Android Studio JBR21 and durable Flutter3.47.2 exited0. Selected Node/JVM/Flutter and WebChat typecheck/build checks passed; live provider and full harness suites were not selected.
- `cd deploy/codex-relay && npm test`: client identity forwarding, atomic resume snapshot and queued update ordering; three tests PASS. Official source: `openai/codex`, tag `rust-v0.154.0`, `codex-rs/app-server/src/thread_state.rs`, `ThreadListenerCommand::SendThreadResumeResponse` (atomic history/subscription comment), and protocol `TurnStartParams.ts` / `ThreadItem.ts`; tag object `36eab01061df3cde5f95ec20a526777b430091ba`.

## Service deployment

`deploy/codex-relay/install-shared-bridge.py` installs a standard login launch agent, using explicit socket, workspace, private token-file and port configuration. `run-shared-bridge.py` reads the token at startup and execs the existing Bridge; it adds no agent loop or session lifecycle. Stdout goes to `/dev/null` to avoid QR/token logging. Config and stderr are private. The existing daemon is never restarted.

Installed label: `cn.omnimind.codex-shared-bridge-test`; SSH forward/reverse agents retain their existing labels. This requires a logged-in, awake Mac. Website routing remains a separate deployment gate.

Public-user acceptance continuation: user requires a general-user flow, not a personal4090 deployment. Existing QR parsing imports Bridge URL/token/cwd; it does not establish a managed public route or exchange an expiring pairing code for per-device credentials. General-user registration, scoped pairing/revocation, cross-account isolation and reconnect across endpoint changes are NOT implemented or verified by the ingress test. They must gain executable integration coverage when implemented; this note is not a substitute for those tests. Repeated the existing public ingress verifier with four ACPv2 connections: PASS, max initialize5352.64ms, median ping576.64ms, max1619.57ms. Phone b49f281b disappeared from `adb devices`; mDNS exposed a different device, which was not used. Requested reconnect/unlock of the intended PJE110. No physical or native desktop GUI acceptance was claimed.

Cloudflare test continuation (2026-09-15 06:05 UTC): local macOS cloudflared2026.9.1 quick tunnels failed to register with both QUIC (timeout) and HTTP/2 (TLS EOF); edge DNS resolved to the local proxy's198.18.* fake IPs. Those two local test processes were stopped without changing phone configuration. Linux cloudflared2026.9.1 was downloaded from the official release, verified against release asset SHA256 `03f1f25d1cc93b9ad6c60569d44060bc4f17ed97075760ed8cfca4b12dcd68cc`, and copied to4090 at `~/.local/share/omnibot/cloudflare-test/cloudflared`. A temporary HTTP/2 tunnel to4090's existing `http://127.0.0.1:18101` registered at lax01. Public host `indicating-readers-insurance-peripherals.trycloudflare.com` returned401 without credentials. Running the existing `scripts/verify-bridge-ingress.cjs` against its `/codex` WSS endpoint with the private token file and isolated test cwd passed two authenticated ACPv2 connections, health/files and unauthorized rejection. Max initialize2880.15ms, median ping914.78ms, max1487.82ms. This is public TLS/WSS ingress verification, not cellular or native GUI/session recovery acceptance. The tunnel is a foreground temporary test service, not a fixed-domain deployment. PJE110 b49f281b is connected but locked; user unlock/cellular request is pending. No prompt was sent and no phone route was changed in this test.

DNS continuation: after the user configured `bridge.omnimind.com.cn`, Google DNS-over-HTTPS returned A `121.89.86.47`, TTL600, status0. HTTPS `/health` and `/codex` both failed TLS handshake with `TLSV1_ALERT_INTERNAL_ERROR`; no certificate bypass or authenticated public probe was attempted. DNS is verified, public WSS is not. SSH4090 reports hostname4090, Caddy inactive, Nginx active, and the FRP configuration remains permission-denied. The public Caddy server SSH entry is still needed to inspect certificate issuance and route the new hostname to the existing origin. Phone b49f281b remains connected; cellular and native desktop GUI acceptance remain pending.

The candidate Nginx site is staged at `4090:~/omnibot-bridge-deploy/website.candidate.conf`; it preserves the existing website and adds authenticated `/health`, `/fs/`, `/codex` proxy routes. Expected original config SHA256: `5618b6a331a884a42b59ff283db85ca37494567a607b4ddc09f46e86cc575e2f`. It has NOT been installed or reloaded. `sudo -n -l` still reports interactive authentication required; the public FRP/TLS hostname is not yet verified.

Continuation after user consent: phone b49f281b remains available; SSH reverse listener18336 and authenticated Bridge ingress pass again (ACPv2, health/files/auth rejection). The native SSH entry still resolves to the isolated loopback service and reports Codex0.154.0. This is transport preflight, not native GUI acceptance. Added/staged `apply-website-route.sh`: verifies original config hash, backs up, installs the reviewed candidate, validates Nginx and reloads, restoring/reloading original on failure. Shell syntax checked; actual privileged execution remains pending server authentication. The exact command and public website URL question were sent to the user.

After the user reported deployment complete, independently verified the installed site SHA256 `67f7cccbfd577bac9861ee750a35cd12a414c3d8bcf2c18b157530aaf3f2a2c2` matches the candidate; Nginx is active and unauthenticated origin `/health` returns401. Ran `verify-bridge-ingress.cjs` through a temporary SSH forward to the actual website origin18101: four authenticated ACPv2 connections PASS, health/files PASS, unauthorized access rejected; max initialize592.67ms, median ping61.96ms, max138.67ms. The probe made no model requests or file writes. Public WSS/cellular remains pending the exact public hostname; the root-only FRP config cannot be inspected with the current SSH account. No additional sudo authorization is needed to repeat already-authorized deployment work, but interactive server authentication is still required if further privileged changes become necessary.


## Remaining native integration gate

After three consecutive goal continuations addressing the native integration gate, the current active native task still fails the read-only shared-backend ownership preflight. The requested native UI marker `OOB_NATIVE_DESKTOP_AB` is absent (zero queries/replies); the public reconnect marker remains exactly one query/reply. Physical camera scan feedback is also outstanding. Public transport, phone bidirectional protocol traffic and Wi-Fi recovery do not prove native desktop/current-task sharing. Current code and installed configuration remain a development candidate, not a completed ToC release. No private IPC, lock deletion, copied histories or second backend migration was used to bypass this gate. Further native acceptance requires the requested native SSH-host UI operation or a supported attachment surface for the app-owned backend; the active task cannot be transparently moved by the available interface.
