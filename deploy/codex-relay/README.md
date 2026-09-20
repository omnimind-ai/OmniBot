# Codex reverse SSH test relay

**Current status, 2026-09-15:** the native desktop SSH host is now connected.
Sending through its supported task API reached the same daemon as the Bridge,
and the reply appeared on the untouched physical phone. A real phone send also
completed on that host. Native GUI text rendering and access to the original
already-running local desktop task remain unverified; this is not a completed
consumer release. See the SSH-host evidence in
[the regression record](../../docs/testing/conversation-regression-index.md).
The deployment sections below include historical test routes; they are not a
single current production installation recipe.

## Experimental ACP v2 shared-session candidate (2026-09-14)

This opt-in entry point uses the official SDK `experimental/v2` boundary and
the pinned upstream Codex adapter's execution/session machinery. It does not
make upstream codex-acp 1.11.0 a native v2 implementation. Do not replace the
default production adapter until the remaining acceptance gates pass.

```sh
cd deploy/codex-relay
npm ci --ignore-scripts
npm run prepare:adapter
export OOB_CODEX_ACP_MODULE="$PWD/codex-acp-v2-module.mjs"
export OOB_SHARED_CODEX_SOCKET="$HOME/.codex/app-server-control/app-server-control.sock"
export CODEX_PATH="$PWD/shared-codex-proxy.cjs"
unset OOB_SHARED_CODEX_URL
# Pass this absolute executable to codex-bridge's --acp-bin option:
realpath shared-acp-v2.mjs
```

The generated upstream module retains its adjacent Apache-2.0 LICENSE. The
preparer checks upstream version and source hash before applying its narrow
integration seams. The original dependency is unchanged. Exact dependencies
are in package-lock.json; model/provider resolution remains upstream-owned.

The Android candidate negotiates v2 and reuses its existing Conversation,
session/turn ownership and Flutter reducer. `session/prompt` acknowledges
admission; backend-derived `state_update` completes the turn. Shared observation
survives a prompt completing. Official backend user/item identities are retained.

See [current acceptance](../../docs/testing/shared-session-acceptance-2026-09-15.md)
for actual-phone evidence and remaining gaps. Phone text synchronization on the
local test route and automatic reconnection have passed on an actual phone;
native desktop GUI and public website/cellular acceptance remain unverified.

The v2 adapter uses the official Codex0.154.0 atomic `thread/resume` snapshot
and subscription boundary, retaining subsequent notifications until the upstream
session state/history projection is ready. It does not take a second history
snapshot. `npm test` checks that boundary and preserves post-snapshot ordering.

For a supervised shared Bridge, install dependencies with `npm ci --ignore-scripts`
in both this directory and `tools/codex-bridge`, then prepare the adapter above.
Run `install-shared-bridge.py --socket ABS_SOCKET --token-file PRIVATE_TOKEN_FILE
--cwd WORKSPACE --port PORT` for a dry run, and add `--apply` to install the
macOS login launch agent. This reuses the existing daemon. Combine it with
`install-macos.py --tunnel-only` for the reverse SSH service. Token contents are
read from a private file at startup and are not placed in launchd arguments.

## Official daemon transport probe

The daemon Unix control socket speaks WebSocket, not JSONL. The official
`app-server proxy` forwards raw bytes; piping JSONL into it is not an ACP
adapter transport. The existing `shared-codex-proxy.cjs` now explicitly accepts
either `OOB_SHARED_CODEX_URL` (with token file) or `OOB_SHARED_CODEX_SOCKET`.
Socket mode requires an absolute, private socket owned by the current user.
WebSocket compression negotiation is disabled for compatibility with the
official daemon. No daemon is started, replaced or restarted by this proxy.

To connect the existing ACP adapter to a running daemon, use:
```sh
unset OOB_SHARED_CODEX_URL
export OOB_SHARED_CODEX_SOCKET="$HOME/.codex/app-server-control/app-server-control.sock"
export CODEX_PATH="$PWD/deploy/codex-relay/shared-codex-proxy.cjs"
# NODE_PATH must resolve the bridge runtime's installed ws dependency.
```

Repeat existing integration regressions against that explicit endpoint:
```sh
OOB_SHARED_CODEX_URL="ws+unix://$OOB_SHARED_CODEX_SOCKET:/" \
  node scripts/verify-codex-shared-session.cjs
# Take the isolated threadId printed above; use it with the existing patched
# pinned adapter path in OOB_ACP_BIN (see prepare-shared-acp.py).
OOB_TEST_SESSION_ID=<printed-test-thread-id> OOB_EXPECT_THREAD_SNAPSHOT=1 \
  node scripts/verify-shared-codex-acp.cjs
```

Verified with the running official daemon0.154.0 and codex-acp1.11.0; the bundled
desktop CLI is0.153.4. Physical phone send/receive via this transport passed.
The desktop app's localhost task API still uses a separate app-server and
sending through that API into the daemon-owned test thread fails with `active writer`.
This is not evidence that the separately configured SSH entry has the same
failure; its native GUI send/display has not been verified.
These commands do not switch an already-running desktop backend. The
experimental native-daemon-proxy.sh is not a validated production launcher
and omits desktop startup tool overrides. Full native integration is pending.

This deployment reuses OpenSSH and macOS launchd. It does not implement NAT
hole punching or replace the application's ACP lifecycle.

Current route: local test client → SSH local forward → 4090 loopback:18329 →
SSH reverse forward → Mac loopback:17329 → isolated Codex app-server.

## Install on the Mac

Requires an existing verified SSH alias with noninteractive key authentication,
and the installed Codex executable. Review the dry run before applying:

```sh
python3 deploy/codex-relay/install-macos.py --ssh-host 4090
python3 deploy/codex-relay/install-macos.py --ssh-host 4090 --apply
```

Two login launch agents supervise the backend and tunnel. SSH keepalive detects
an unresponsive connection (15 seconds × 3 failures); launchd restarts failed
processes with a 10-second throttle. This does not keep a sleeping Mac online,
run before login, or guarantee resumption of generation after backend failure.
The app-server uses a generated capability token stored with mode 0600 under
`~/Library/Application Support/OmniBot/codex-relay-test/`. Never commit the token.
Only loopback listeners are opened. The original desktop process is unchanged.

## Repeat actual network tests

Install the `ws` Node dependency outside the repository or use the existing
tool runtime via NODE_PATH. In a separate terminal open the test forward:

```sh
ssh -NT -o BatchMode=yes -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o ControlMaster=no -o ControlPath=none \
  -L 127.0.0.1:17330:127.0.0.1:18329 4090
```

```sh
export OOB_SHARED_CODEX_TOKEN_FILE="$HOME/Library/Application Support/OmniBot/codex-relay-test/capability-token"
node scripts/verify-codex-relay.cjs ws://127.0.0.1:17329 ws://127.0.0.1:17330
OOB_SHARED_CODEX_URL=ws://127.0.0.1:17330 node scripts/verify-codex-shared-session.cjs
```

The second test sends three small real model prompts to an isolated thread;
it tests two protocol clients and never resends a prompt after reconnect.
It does not verify native desktop UI or physical phone integration, or
deduplication when the initial send acknowledgement is lost.

## Public website deployment: not yet installed

4090's existing website listens on loopback behind FRP. Its existing Nginx/FRP
services belong to other service accounts, and this SSH account cannot sudo.
Public SSH reachability alone does not supply a public HTTPS endpoint.

Use a dedicated website subdomain and the existing public edge: TLS/WSS at
the edge → authenticated routing → 4090 loopback relay. Keep this test's raw
Codex capability endpoint private. A product gateway must enforce user/device
pairing and authorization, and the existing ACP adapter must remain the app's
business boundary. Do not embed one global backend token in website code.

Before calling this a stable public service, provision edge configuration
access, certificate renewal, service boot supervision, log rotation, connection
limits and monitoring. Verify WebSocket Upgrade and idle timeouts through every
proxy hop. Then test physical-phone cellular access, native desktop same-session
display, both-side reconnect, acknowledgement-loss behavior and host restart.
Backend process restart and network reconnect have different execution semantics.

## Stop installed test services

```sh
launchctl bootout "gui/$(id -u)/cn.omnimind.codex-relay-test"
launchctl bootout "gui/$(id -u)/cn.omnimind.codex-shared-test"
```

To prevent loading at the next login, remove only the two matching plist files
from `~/Library/LaunchAgents/`. Logs are under
`~/Library/Logs/OmniBot/codex-relay-test/`; token/state are retained separately.

## ACP website route prepared (2026-09-13)

The phone-compatible authenticated ACP Bridge is on Mac loopback17321. An
additional TEST reverse SSH listener now forwards 4090 loopback18321 to it.
This is distinct from the raw app-server listener18329 described above.
The original fragment exposed only `/codex`; the current
`website-acp-location.conf` supersedes it with authenticated `/health` and `/fs/`
as well, and targets the v2 relay described below. The public edge must forward
WebSocket Upgrade, and TLS must terminate at the website edge. This snippet
contains no token and has NOT been installed or syntax-tested by server nginx.
The current SSH account cannot edit root-owned nginx configuration or sudo.
An administrator must review/include it, run `nginx -t`, and reload nginx.

The reverse forward used for this test is:
```sh
ssh -NT -o BatchMode=yes -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o ControlMaster=no -o ControlPath=none \
  -R 127.0.0.1:18321:127.0.0.1:17321 4090
```
The first temporary SSH processes exited before the later phone test. The ACP
tunnels are now supervised separately, without spawning another Codex backend:
```sh
python3 deploy/codex-relay/install-macos.py --ssh-host 4090 --tunnel-only \
  --local-port 17321 --remote-port 18321 --test-forward-port 17332 --apply
python3 scripts/verify-acp-relay-restart.py
```
The restart regression terminates only the two named test SSH jobs while model
prompts are idle. Follow it with the real Bridge regression and a physical-phone
send; process replacement alone does not prove end-to-end connectivity.
A local test forward17332 -> 4090:18321 is used solely for verification. Stop
these services with `launchctl bootout gui/$(id -u)/cn.omnimind.codex-acp-relay-test`
and `launchctl bootout gui/$(id -u)/cn.omnimind.codex-acp-forward-test`; remove
their matching plists to prevent loading at login. The Bridge/backend are still
test processes; this does not establish a supervised production service.
The phone's current USB reverse maps17321 -> Mac17332, forcing test traffic
through4090. Restore direct USB testing with:
```sh
adb -s b49f281b reverse tcp:17321 tcp:17321
```
Neither this USB route nor successful protocol tests establish public cellular
access. Final phone endpoint must be the authorized website `wss://…/codex`.


## Native desktop through loopback SSH (Mimi-style workflow)

The tested connection flow uses stock OpenSSH and Codex app-server proxy.
It does not copy Mimi implementation code or patch the native desktop bundle.
Reference inspected: gaixianggeng/mimi-remote commit
75c2cb8914a784833e4fba8b16019dd506d049b8, docs/shared-ssh-app-server.md.

On this test Mac, system SSH22 was disabled and noninteractive sudo was absent.
An isolated user-owned sshd now listens ONLY on127.0.0.1:17335. It accepts a
separate generated test key, disables password/interactive authentication,
forwarding and root login, and is supervised by the login launch agent
cn.omnimind.codex-loopback-ssh-test. Config/key files are private under
~/.local/share/omnibot/shared-ssh-test/. Neither system SSH configuration nor
global authorized_keys was changed. ~/.ssh/config has one additional Include
for the dedicated alias; its original bytes are backed up in the private state
directory. Noninteractive PATH is configured ONLY for this isolated sshd to
resolve the installed verified official CLI; it must be revalidated on upgrade.

```sh
ssh omnibot-shared-local-test 'codex --version'
OOB_TEST_SSH_TARGET=omnibot-shared-local-test   OOB_SHARED_CODEX_URL="ws+unix://$HOME/.codex/app-server-control/app-server-control.sock:/"   node scripts/verify-codex-shared-session.cjs
```

NODE_PATH must resolve the existing ws dependency. An alternative SSH config
can be supplied with OOB_TEST_SSH_CONFIG. The regression's first protocol
client uses SSH -> stock raw app-server proxy -> WebSocket; the other clients
use the supplied endpoint. Model/provider remain configured by Codex. The
script does not start another backend, select a fixed model, or replay prompts.
These results do not substitute for native Desktop UI acceptance.

Next native UI step: add/select SSH host omnibot-shared-local-test in native
Codex, and open /tmp/oob-shared-session-probe FROM THAT HOST. Do not select
ordinary This Mac for these daemon-owned test threads. Current tools cannot
perform that settings operation: Computer Use prohibits Codex, and native task
APIs expose no SSH host registration method. This step has been requested from
the user; native completion is pending.

To stop only this test SSH service:
```sh
launchctl bootout "gui/$(id -u)/cn.omnimind.codex-loopback-ssh-test"
```
Remove its matching LaunchAgents plist to prevent login startup, and remove
only the added Include line from ~/.ssh/config. Do not restore the whole backup
if subsequent SSH configuration changes have been made. Do not stop the shared
Codex daemon as part of SSH cleanup; other clients may still use it.

The phone's current test route remains USB to its existing daemon Bridge17334.
This loopback SSH service supplies native-desktop attachment, not public WSS.

## Current v2 relay / website ingress probe (2026-09-14)

A separate pair of launchd agents supervises Mac17336 -> 4090:18336 and the
local test forward17337 -> 4090:18336. They do not replace the older v1 relay:

```sh
python3 deploy/codex-relay/install-macos.py --ssh-host 4090 --tunnel-only \
  --local-port 17336 --remote-port 18336 --test-forward-port 17337 \
  --tunnel-label cn.omnimind.codex-acp-v2-relay-test --apply
```

`website-acp-location.conf` now targets this v2 upstream18336. Its configuration
and actual Upgrade/ACP forwarding were validated using a separate unprivileged
Nginx instance on 4090 loopback18136. The production website has NOT loaded it.
An administrator must include it inside the existing website server block,
run `nginx -t`, reload, and check that the public TLS/FRP edge forwards Upgrade.
This is single-Bridge access, not multi-user device routing.

Ingress regression and bounded measurements (no model prompts):

```sh
NODE_PATH=deploy/codex-relay/node_modules \
  node --test scripts/verify-bridge-ingress.test.cjs
export OOB_BRIDGE_URL=ws://127.0.0.1:17337/codex
export OOB_BRIDGE_TOKEN_FILE="$HOME/Library/Application Support/OmniBot/codex-relay-test/capability-token"
export OOB_BRIDGE_CWD=/tmp/oob-shared-session-probe
OOB_TEST_CONNECTIONS=4 NODE_PATH=deploy/codex-relay/node_modules \
  node scripts/verify-bridge-ingress.cjs
```

For a deployed website set `OOB_BRIDGE_URL` to its credential-free `wss://.../codex`
URL. TLS certificate verification remains enabled. Never pass a token in the URL
or commit it. The script requires successful authenticated ACP initialization
for every counted connection, checks invalid credentials, and measures ping RTT.
It also checks the Android client's HTTP health and authenticated directory
listing routes. It reports neither a maximum capacity nor model-generation
throughput.
