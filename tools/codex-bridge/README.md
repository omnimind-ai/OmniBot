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
