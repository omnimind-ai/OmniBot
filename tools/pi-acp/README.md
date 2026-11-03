# Pi ACP for OmniBot

This adapter exposes Pi's official **1.4.0 SDK** through ACP v1 over stdio. It
runs in the existing Android terminal environment (Node **22.19+**, bash and
ripgrep). The ACP TypeScript SDK is pinned to **2.0.0**, using its v1 entrypoint;
the package version does not select ACP v2.

The adapter follows the repository's segmented dual license, included in
`LICENSE`. Its official Pi and ACP dependencies retain their own licenses.

## Development and packaging

```sh
cd tools/pi-acp
npm ci --ignore-scripts
npm run typecheck
npm test
npm run package:android
npm run package:check
```

`package:android` embeds compiled adapter JavaScript, package metadata and the
complete lockfile in `app/src/main/assets/acp/install/pi.sh`, and updates the
catalog preparation revision. Android builds check that the asset matches the
source. No adapter source is downloaded at install time.

The bundle check compares the decompressed file bytes, installer commands and
preparation revision. Gzip platform headers and compression differences do not
invalidate an otherwise identical artifact.

The installer uses
`npm ci --omit=dev --ignore-scripts` for pinned official dependencies, installs
each revision in its own directory, checks it before switching the launcher,
and preserves previous installations and session data.

The app's **Agent → Pi → Prepare** action and terminal environment's **Pi**
package both use that catalog installer. Normal Agent switching does not
install packages. `omnibot-pi-acp --health` terminates after checking the SDK
import, version and Node runtime; it does not require model credentials.
`omnibot-pi-acp --version` reads only the installed Pi SDK package metadata and
prints its version, without loading the SDK or requiring Provider configuration.
The adapter's own version remains available in ACP `initialize` and `--health`.
Existing installations receive this change through Pi's explicit Prepare/reinstall
action after updating the app; checking for updates does not replace the launcher.

## Ownership

- OmniBot owns Conversation history, logical turn identity, Provider selection
  and the shared ACP reducer. This package does not create another host event
  protocol or retry a prompt.
- One ACP session owns one Pi `AgentSession`, model registry snapshot, and MCP extension. Pi owns the
  model loop, built-in tools, skills, compaction and persisted context. ACP
  session IDs are Pi session IDs. Exact-ID load never resumes the latest file.
- ACP message IDs are recorded through Pi custom entries before output starts.
  The following Pi message entry uses that ID during replay; native Pi entries
  without a marker use their persisted entry ID. Text or timestamps are never
  identity keys. Tool IDs come from Pi's tool calls.
- `session/prompt` waits for Pi prompt completion and idle settlement. A
  low-level `agent_end` is not ACP completion. Errors belong to the request;
  cancellation calls Pi `abort()`, unblocks pending permissions and settles the
  same request. Agent-level and provider-level transport retries are disabled.
- Every tool passes through ACP `session/request_permission`. OmniBot's existing
  selected permission policy decides whether to prompt or allow automatically.
- Closing aborts active work, emits Pi's `session_shutdown` extension hook,
  closes MCP connections and disposes the session. It retains the session file.

## Configuration

The Kotlin `PiConfigAdapter` writes the official `models.json` format under
`/root/.pi/omnibot-acp`, with host identity and model defaults in
`_meta.omnibot`. Each ACP session registers its own `omnibot` Provider through
the official `ModelRuntime` API. Credentials and route identity are fixed at
process launch; mutable model capabilities are scoped to each session.

| Environment value | Purpose |
| --- | --- |
| `PI_CODING_AGENT_DIR` | Managed Pi state and session directory |
| `OMNIBOT_PI_MODELS_PATH` | Host-generated `models.json` |
| `OMNIBOT_PI_MODEL` | Explicit initial model from the shared Provider |
| `OMNIBOT_PI_API_KEY` | Process credential or host-managed Responses relay grant |
| `OMNIBOT_PI_HEADERS` | JSON object of resolved Provider headers |
| `OMNIBOT_PI_PROVIDER_ID` | Expected Provider profile ID, checked against the file before startup |
| `OMNIBOT_PI_PROVIDER_REVISION` | Expected Provider configuration revision |
| `OMNIBOT_PI_PROVIDER_API` | Expected Pi API, such as `openai-completions` |
| `OMNIBOT_PI_PROVIDER_BASE_URL` | Expected normalized Provider endpoint |
| `OMNIBOT_PI_SKILL_PATHS` | Additional skill directories, separated by `:` |

Credentials and header values are kept in memory, outside model/session
configuration files. OpenAI Chat Completions, Responses and Anthropic Messages
use Pi's corresponding official API implementations. Missing or unavailable
models fail explicitly. Model and thinking selectors use ACP config options.

An idle `session/load` or `session/resume` can refresh that session's catalog
from the file when Provider ID, revision, API and endpoint still match its
launch identity. A different Provider's file is ignored. Invalid matching
catalogs, including removal of the selected model, return an ACP error while
leaving the previous snapshot usable. Active prompts reject loading; refreshing
another session cannot alter their model or tool continuation. Updates use
Pi's `registerProvider`/`setModel` and ACP `config_option_update`, retaining the
session and history. Legacy files without identity remain fixed snapshots.

Provider thinking defaults initialize new sessions. Existing official
`thinking_level_change` entries take precedence, and Pi clamps selections to
the declared capabilities. A refreshed catalog never replaces process
credentials or headers.

For an explicitly declared switch-only Chat Completions route, ACP presents
`off`/`on` instead of SDK levels that all send the same enabled switch. This
requires `supportsReasoningEffort: false`, a known SDK switch format and no
token budget or level-dependent request settings. Mandatory thinking offers
only `on`. The value is projected from Pi's official `thinkingLevel`; changing
it still calls `setThinkingLevel` and persists only Pi's normal session entry.
Unknown capabilities and routes with real effort/budget controls retain the
SDK's advertised levels.

ACP `mcpServers` is passed to `createMcpExtension({ loadConfig })` for that
session, with direct tool exposure. HTTP headers and stdio environment values
remain literal values, not Pi configuration interpolation or shell commands.
Server names are normalized deterministically to Pi's namespace requirements.
This does not write global `mcp.json`. The adapter advertises Streamable HTTP,
not legacy SSE. OmniBot's MCP server retains ownership of phone permissions and
plugin/tool capabilities.

## Supported surface and verification

Implemented: initialize, session new/load/resume/list/prompt/update/cancel/close,
mode and config options, text/thought/tool streaming, images, text resources,
resource links and token usage. `session/delete`, forks, steering/follow-up,
interactive TUI extension dialogs and third-party executable extension loading
are not advertised. PC Bridge integration is outside this first release.

Tests run the real Pi SDK against local deterministic model and MCP servers.
They cover protocol mappings, credentials/headers, history identity after
restart, concurrent sessions, tool execution/denial, cancellation and failures
before/after output, catalog refresh isolation and thinking defaults. These fixtures do not establish compatibility with a live
model service or Android PRoot. Device acceptance must additionally exercise
installation, SDK imports, bash/file tools, the phone MCP server and cleanup on
each supported terminal distribution.

The native `PiConfigAdapterTest` also exports its generated model documents to
`app/build/reports/pi-model-capabilities/`. Verify those exact Kotlin outputs
through the ACP adapter and Pi's real request builders with:

```sh
node --import tsx scripts/verify-model-capabilities.ts
```

Each fixture provides `name`, `document`, `modelId`, `expectedLevels`,
`expectedDefault`, and `expectedWire` keyed by Pi thinking level. Wire
expectations are recursive request-body subsets; optional `expectedAbsent`
lists omitted fields as dotted paths. The script verifies the initial default
request and every selectable level. It retains the original endpoint for SDK
format detection but intercepts every fetch and supplies deterministic SSE;
it sends no model request over the network.

After installing the debug APK and preparing Pi in a disposable emulator, run
the same fixture-driven checks inside its PRoot:

```sh
node --import tsx scripts/verify-device.mjs emulator-5554 alpine
node --import tsx scripts/verify-device.mjs emulator-5554 ubuntu
```

The device script uses temporary session/workspace files, adb reverse for its
local Provider/MCP fixtures, and the app's existing `init-host` launcher. It
checks actual on-device file/bash execution, MCP results, process restart,
message identities, cancellation (including a running bash child) and clean shutdown. It does not exercise a
live model service or grant phone-operation permissions. It removes its test
files and port mappings when finished.

References: [Pi SDK](https://github.com/earendil-works/pi/blob/v1.4.0/packages/coding-agent/docs/sdk.md),
[MCP](https://github.com/earendil-works/pi/blob/v1.4.0/packages/coding-agent/docs/mcp.md),
[ACP](https://agentclientprotocol.com/).
