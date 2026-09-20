# Shared session acceptance — NOT ACCEPTED

Scope: unchanged Xiaowan UI and native desktop Codex, multi-entry live output,
reconnect history, no duplicate execution, website WSS without phone VPN.
This is the current acceptance decision, not a claim that blocked cases ran.

## Actual results

| Gate | Result | Evidence |
| --- | --- | --- |
| Physical phone sends and receives through official daemon | PASS in preceding run | PJE110 b49f281b, Android16,0.6.2.3/code15; M exact reply, shared-session-new-phone-2026-09-13.md |
| Native desktop API sends to same daemon-owned session | FAIL in this run | fresh Q rejected: active writer; no resend |
| Passive ACP client sees another client's live response | FAIL in this run | verify-acp-shared-observation.cjs: senderReply=true, observerLiveUpdateCount=0 |
| Phone automatically displays external-origin message | FAIL in this run | phone-passive-observation-failed.png still ends at M |
| Reopen same session on physical phone restores external input/reply once | PASS in this run | verify-phone-shared-history.cjs on D0E4148ED12D43E2935D4B34BDB0133C |
| Client disconnect while backend continues | Protocol PASS in preceding run; full two-UI acceptance pending | existing verify-codex-shared-session.cjs; not native UI proof |
| Automatic foreground reconnect and catch-up | NOT ACCEPTED / not demonstrated | manual session reopening above must not substitute |
| Concurrent native/phone admission and acknowledgement-loss deduplication | NOT RUN end-to-end | native admission prerequisite fails; current once-count does not prove lost-ack deduplication |
| Native desktop live pixel rendering | BLOCKED / NOT RUN | Computer Use tool prohibits controlling Codex; task API read is not pixel evidence |
| Website WSS over cellular | BLOCKED / NOT RUN | 4090 config still not writable, sudo unavailable; actual WSS domain/deployment entry requested, pending |

Current phone reverse17321 -> Mac17334 is USB to the daemon-backed Bridge,
not website WSS and not the4090 loopback roundtrip. No APK changes in this run.

## Executable passive observation regression

The script uses real ACP adapter processes and one real configured-model turn.
It does not implement a second application lifecycle or replay a prompt. Its
workspace, session, executable and transport are supplied through environment;
model/provider selection stays with the existing configured agent. Protocol1
is explicitly the current adapter regression, not a claimed v2 implementation.

```sh
# Supply an existing disposable test session and its matching workspace.
export OOB_ACP_BIN=/absolute/path/to/verified-codex-acp
export OOB_TEST_SESSION_ID=<disposable-session-id>
export OOB_TEST_CWD=/absolute/path/to/test-workspace
export CODEX_PATH="$PWD/deploy/codex-relay/shared-codex-proxy.cjs"
export OOB_SHARED_CODEX_SOCKET="$HOME/.codex/app-server-control/app-server-control.sock"
unset OOB_SHARED_CODEX_URL
# NODE_PATH must point to the existing runtime node_modules if ws is not local.
node scripts/verify-acp-shared-observation.cjs
```

It verifies fresh-marker isolation, sender output, passive live output, then
explicit restoration with exactly one completed backend turn and reply. It
exits nonzero if passive live output is missing even when history succeeds.
The first development run compared transport text without trimming outer
whitespace and falsely failed the sender check. The corrected run passed
sender output and still received zero observer updates. Retained final output:
artifacts/shared-codex-2026-09-13/acp-passive-observation-result.json.

## Configuration requirement

Do not encode a machine address, model name or provider-specific routing rule
in the application lifecycle. An agent may discover/configure the endpoint,
query supported models/options through the existing provider/session surfaces,
and verify the selection. Configuration must remain persistent, explicit and
validated; changing model configuration cannot fix missing subscriptions or
multiple backend writers. No model credentials or global provider settings
were changed during acceptance.

The current release does not satisfy the requested shared-session experience.
Persistent observation, canonical external-turn ownership and native backend
attachment remain implementation work. ACP v2 has not been migrated. Missing
native/concurrency/acknowledgement-loss cases remain unimplemented end-to-end
regressions; this report does not substitute for their executable acceptance.


## Bridge QR continuation R — 2026-09-13 22:45 CST

Physical device b49f281b / OnePlus PJE110, installed 0.6.2.3 code 15.
User opened Bridge connection; USB reverse tcp:17321 -> tcp:17334.
In the existing phone conversation, entered and tapped Send exactly once:
`Reply only OOB_QR_CONTINUE_R. Do not use tools.`
Same backend session `01a09ae4-40da-7853-aa19-e1940cd4c4c4`, completed turn
`01a09b3a-7d50-70b3-a0ba-26537cc41294`, duration 10166 ms.
Backend and native read_thread API both report exact `OOB_QR_CONTINUE_R`.
Actual phone screenshot and accessibility text show `OB_QR_CONTINUE_R`:
first O is missing. Transport continuation PASS; exact phone display FAIL.
The existing executable regression passed backend exactly-once assertions,
then failed visible phone reply assertion (0 !== 1). No resend performed.
Native GUI live display, reconnect/reload of this turn, and WAN NOT RUN.
Evidence: artifacts/shared-codex-2026-09-13/phone-qr-continue-R-missing-prefix.png.
Reproduce the read-only verification while the R reply is visible:

```sh
NODE_PATH=/tmp/oob-phone-bridge-runtime/node_modules \
OOB_SHARED_CODEX_URL="ws+unix://$HOME/.codex/app-server-control/app-server-control.sock:/" \
node scripts/verify-phone-shared-history.cjs b49f281b \
  01a09ae4-40da-7853-aa19-e1940cd4c4c4 OOB_QR_CONTINUE_R
```

Cause of missing prefix not yet established. No fix or full acceptance claimed.


## Full acceptance rerun and simpler chunk handling — 2026-09-13 22:46 onward

User requires full acceptance and explicitly rejects complex custom rules.
Decision remains NOT ACCEPTED; no custom synchronization, queue or retry
mechanism was added.

- Native app send via send_message_to_thread to the existing isolated M task,
  marker OOB_NATIVE_ACCEPT_S: rejected `already has an active writer`. Do not
  treat this as a native SSH UI test; the purpose-built tool reports local host.
- Real ACP passive observation rerun: marker
  OOB_OBSERVER_4CDE586A380F4E308BE38456ECB68815, observer updates = 0, FAIL.
  Backend executed once and replied with an extra trailing period. Thus the
  strict sender/history marker assertions also fail; that formatting mismatch
  is not evidence of transport loss. Native read_thread confirms the period.
- Original physical missing-prefix R isolated in the shared reducer: chunks
  `O`, `O`, `B_QR_CONTINUE_R` produce `OB_QR_CONTINUE_R` before the change.
  Removed live text equality/prefix guessing from _deduplicateReplayDelta;
  ACP chunks append. No new state, message identity, protocol or retry path.
  Existing history replay path retained. Two tests that assumed chunks were
  cumulative snapshots now assert append semantics, including explicit message
  identity. Added Bridge continuation repeated-leading-token regression.
- Focused reducer + coordinator suite: 320 PASS. Execution:
  `cd ui && /tmp/oob-flutter-3.47.2/bin/flutter test --no-pub test/services/agent_event_reducer_test.dart test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart`.
- Build PASS with Android Studio JBR 21 (first attempt used an environment
  without a discoverable Java 21 toolchain and failed). Actual PJE110 b49f281b
  install -r PASS, retained data, version 0.6.2.3/code15.
  Candidate SHA256 b61e77fdb3826916f8a7384ed9e2c5ccb6650400aac2464989f3b6267ad2ac9e.
- Before installation, force-stop + reopen M restored the R reply on the
  physical phone. After installation, local UI navigation also located R,
  but a subsequent exact backend/UI check encountered the phone lock screen
  and failed visible-user assertion. New candidate live-send/restart/repeat
  acceptance is PENDING PHONE UNLOCK, not passed. User was asked to unlock.
- Website configuration rechecked on 4090: WEBSITE_NOT_WRITABLE and
  SUDO_UNAVAILABLE. Public website WSS deployment and physical cellular
  acceptance remain BLOCKED. USB reverse is not a WAN acceptance substitute.
- Concurrent native/phone sends and lost-admission-ack recovery remain NOT RUN
  end-to-end: native shared send is not available through the tested entry.


Official shared backend rerun via installed SSH alias: PASS two clients bind
same thread, both receive output/completion, reconnect restores history without
resend, generation survives client disconnect and both recover same turn IDs.
Thread 01a09b43-d8cf-7203-ab85-c28fa304cf1d; marker OOB_SHARED_1789311308450.
This is protocol-only, not native UI or physical phone verification.
Command: NODE_PATH=/tmp/oob-phone-bridge-runtime/node_modules
OOB_SHARED_CODEX_URL=ws+unix://<private-daemon-socket>:/
OOB_TEST_SSH_TARGET=omnibot-shared-local-test
node scripts/verify-codex-shared-session.cjs.
Focused Dart analysis: no errors, 3 warnings and 61 informational findings
in existing sections; exit 2. No broad cleanup included.
