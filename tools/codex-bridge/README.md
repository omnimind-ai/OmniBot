# Omnibot Codex Bridge

Self-host this small bridge on a Windows, macOS, or Linux PC that has the official `codex-acp` adapter available. Omnibot connects to the bridge over WebSocket; the bridge forwards ACP JSON-RPC over stdio without exposing harness-specific methods.

The bridge also exposes authenticated HTTP helpers used by Omnibot:

- `GET /health`: check bridge and Codex CLI availability.
- `GET /fs/list?path=/abs/path`: list remote directories for the in-app cwd picker.
- `GET /fs/read?path=/abs/path`: read a remote file for preview/editing.
- `POST /fs/write`: write UTF-8 text back to a remote file.
- `POST /fs/upload`: upload a base64-encoded attachment into `<cwd>/.omnibot/attachments`.
- `POST /fs/delete`: delete a remote file or directory.
- `POST /fs/move`: rename or move a remote file or directory.

The remote Agent speaks ACP directly: `initialize`, `session/new`, `session/prompt`, `session/update`, and `session/cancel`.

## Run

### Agent tool: connect a running session (source checkout, acceptance pending)

The optional `session-connect-mcp.mjs` uses the official MCP SDK. It registers
`connect_session_to_xiaowan(sessionId)`, checks the configured Codex backend's
official `thread/loaded/list`, and reads only matching session metadata. It
creates a private PNG QR carrying the existing Bridge connection plus the
selected session ID. The updated Android scanner opens that session through
the existing chat route without listing all sessions or sending a prompt.

Create a mode0600 JSON configuration outside the repository:

```json
{
  "publicUrl": "wss://bridge.example.com/codex",
  "backendUrl": "ws+unix:///absolute/path/to/app-server-control.sock:/",
  "tokenFile": "/absolute/path/to/bridge-token",
  "outputDir": "/absolute/path/to/private-pairing-output"
}
```

`backendUrl` must identify the same running backend used by the Bridge adapter.
For an authenticated WebSocket backend, optionally provide `backendTokenFile`.
The token in `tokenFile` is the Bridge credential, not the backend credential.
No credentials are returned as text by the tool; the resulting QR PNG itself
contains the credential and must remain private. This is an existing-credential
connection invitation, not an expiring or session-scoped authorization grant.

Register using the absolute paths for your installation:

```bash
codex mcp add xiaowan-session-connect \
  --env OMNIBOT_CONNECT_CONFIG=/absolute/path/to/private-config.json \
  -- node /absolute/path/to/tools/codex-bridge/session-connect-mcp.mjs
```

Ask the agent to connect the current session; it must supply the actual session
ID from host context. If the session is not loaded on the configured backend,
the tool returns `SESSION_NOT_LOADED_ON_BACKEND`. It never resumes it to test
ownership, starts another Codex, or copies its history. Registering the tool
does not make an unrelated native desktop app-server accessible. Current native
desktop sharing and physical QR-to-selected-session acceptance remain pending.

### Bring your own public tunnel (source checkout)

Cloudflare Tunnel, FRP or an existing reverse proxy can forward to the same
Bridge. Configure the tunnel to reach `http://127.0.0.1:17321`, including
`/codex` WebSocket upgrades, `/health` and `/fs/`. Then advertise its public
address instead of the local listen address:

```bash
node tools/codex-bridge/server.mjs --host 127.0.0.1 --cwd /path/to/project \
  --public-url wss://bridge.example.com/codex
```

Select a remembered token or create one in the existing setup. Scan the printed
QR in Xiaowan's Remote PC Bridge settings: the public URL, token and working
directory are imported together. `OMNIBOT_BRIDGE_PUBLIC_URL` is equivalent to
`--public-url`. This option is currently in this source checkout; do not assume
the published npm version includes it. It does not create a tunnel, change DNS,
or attach a separately started native Codex app to this backend.

Use a fixed tunnel for a saved connection. Cloudflare Quick Tunnel URLs and
`--token auto` credentials change across launches and require re-pairing. QR
codes contain the Bridge access credential: only share them with the intended
phone. A shared public hostname is not multi-user isolation; each deployment
must route to the correct user's authenticated Bridge.

Recommended startup:

```bash
npx @thuocean/codex-bridge
```

The bridge opens a terminal setup flow where you can use Up/Down and Enter to choose the LAN address to listen on and either auto-generate a token, enter a token manually, or disable token auth. Custom values are typed in place on the selected row. Press Esc on later steps to go back and reselect the previous setup item.

When you enter a token manually, the bridge remembers it in `~/.omnibot/codex-bridge.json` and reuses it on later launches. If the setup UI opens, the remembered token appears as the default token choice so you can reuse, replace, or forget it without adding flags. Run with `--interactive` to force this setup UI, or `--forget-token` to clear the remembered token before setup.

Scripted startup is still supported:

```bash
npx @thuocean/codex-bridge --cwd "/Users/you/code/project" --token auto --no-interactive
```

Windows PowerShell example:

```powershell
npx @thuocean/codex-bridge --cwd "C:\Users\you\code\project" --token auto --no-interactive
```

Or install it globally:

```bash
npm install -g @thuocean/codex-bridge
codex-bridge
```

When the bridge starts, it prints a terminal QR code. In Omnibot, tap Settings -> 服务与环境 -> Codex -> 扫码连接 to fill the remote Bridge URL, cwd, and token automatically.

For local development from this repository:

```bash
cd tools/codex-bridge
npm install
npm start -- --cwd "/Users/you/code/project" --token auto --no-interactive
```

If you do not use the QR code, set these values in Omnibot under Settings -> 服务与环境 -> Codex:

- Bridge URL: the printed `Quick connect bridge URL`, usually `ws://<pc-lan-ip>:17321/codex`
- Remote cwd: the project path passed with `--cwd`
- Bridge Token: the printed `Bridge token`; with `--token auto` it is generated for this run

For WAN access, put this behind Tailscale, WireGuard, a trusted reverse proxy with TLS, or another private network path. Do not expose the bridge directly to the public internet.

If the printed IP is not reachable from your phone, override the advertised address:

```bash
npx @thuocean/codex-bridge --cwd "/Users/you/code/project" --token auto --public-host 192.168.1.20
```

For unattended scripts or service managers, pass `--no-interactive` or set `OMNIBOT_BRIDGE_INTERACTIVE=0`.

## CLI Options

- `--cwd <path>` or positional `project-dir`: Codex working directory, default current directory
- `--token <value|auto>`: bearer token; `auto` generates a random token for this run
- `--no-token`: disable token auth for trusted private networks
- `--host <host>`: listen host, default `0.0.0.0`
- `--port <port>`: listen port, default `17321`
- `--public-host <host>`: advertised host/IP used in the QR code
- `--acp-bin <path>`: ACP agent executable, default `codex-acp`
- `--codex-home <path>`: optional `CODEX_HOME` override
- `--config <path>`: bridge config path for the remembered manual token
- `--forget-token`: clear the remembered manual token before setup
- `--interactive`: force terminal setup prompts
- `--no-interactive`: start immediately without terminal prompts

## Environment

- `OMNIBOT_BRIDGE_HOST`: listen host, default `0.0.0.0`
- `OMNIBOT_BRIDGE_PUBLIC_HOST`: optional advertised host/IP used in the QR code
- `OMNIBOT_BRIDGE_PORT`: listen port, default `17321`
- `OMNIBOT_BRIDGE_TOKEN`: optional bearer token; set to `auto` to generate one
- `OMNIBOT_BRIDGE_CWD`: default project directory
- `CODEX_ACP_BIN`: ACP agent executable override
- `OMNIBOT_BRIDGE_INTERACTIVE`: set to `0`/`false` to disable prompts, or `1`/`true` to force prompts
- `OMNIBOT_BRIDGE_CONFIG`: bridge config path, default `~/.omnibot/codex-bridge.json`
- `OMNIBOT_BRIDGE_MAX_READ_BYTES`: max file preview payload, default 12 MiB
- `OMNIBOT_BRIDGE_MAX_UPLOAD_BYTES`: max decoded attachment upload size, default 24 MiB
- `CODEX_HOME`: optional Codex config directory override

## Troubleshooting

If Omnibot can reach the bridge but reports that remote Codex is unavailable, open the printed health check URL:

```bash
curl -H "Authorization: Bearer <token>" http://<pc-lan-ip>:17321/health
```

`ready: false` usually means the PC cannot run `codex-acp --version`. Install the official adapter and make sure `codex-acp` is on `PATH`, or start the bridge with an explicit executable:

```bash
npx @thuocean/codex-bridge --cwd "/Users/you/code/project" --token auto --acp-bin /absolute/path/to/codex-acp
```

On Windows, npm usually installs command shims as `.cmd` files. If the health check still says `ready: false`, run this in PowerShell:

```powershell
where.exe codex
codex --version
```

Then pass the `.cmd` path printed by `where.exe`:

```powershell
npx @thuocean/codex-bridge --cwd "C:\Users\you\code\project" --token auto --acp-bin "C:\Users\you\AppData\Roaming\npm\codex-acp.cmd"
```
