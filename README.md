<p align="center">
  <picture>
    <img alt="OpenOmniBot" src="docs/pic/OmniBot.png" width="50%">
  </picture>
</p>

<p align="center">
  <a href="README.md"><b>English</b></a> |
  <a href="README.zh-CN.md"><b>简体中文</b></a>
</p>

<h3 align="center">
Multiple AI Agents, Right in Your Pocket
</h3>

<div align="center">
  <img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/omnimind-ai/OmniBot">
  <a href="https://github.com/omnimind-ai/OmniBot/releases/latest"><img alt="GitHub Release" src="https://badgen.net/github/release/omnimind-ai/OmniBot/stable"></a>
  <br>
  <a href="https://trendshift.io/repositories/26966?utm_source=repository-badge&amp;utm_medium=badge&amp;utm_campaign=badge-repository-26966" target="_blank" rel="noopener noreferrer"><img src="https://trendshift.io/api/badge/repositories/26966" alt="omnimind-ai%2FOmniBot | Trendshift" width="250" height="55"/></a>
</div>

<p align="center">
|
<a href="#use-cases"><b>Demo</b></a>
|
<a href="#quick-start"><b>Quick Start</b></a>
|
<a href="https://github.com/omnimind-ai/OpenOmniBot/releases"><b>Release</b></a>
|
<a href="https://github.com/omnimind-ai/OpenOmniBot/issues"><b>Issues</b></a>
|
</p>

> Use Kimi Code / DeepSeek Harness WebUI on Android, switch between Agent Harnesses, and run multiple agents in parallel.
> iOS & macOS: [ViaVera](https://github.com/omnimind-ai/ViaVera)

OpenOmniBot brings AI chat, agent runtimes, local workspaces, and Android system tools into one app. Choose an agent, delegate tasks, use tools, and collect results from your phone.

<h2 id="core-capabilities">Core Capabilities</h2>

- **Kimi / DeepSeek WebUI integration**: Use Kimi Code Web and DeepSeek Harness Web on your phone. MCP/plugin tools launch the local services, check their status, and stop them.
- **Switch between Harnesses**: Choose Xiaowan, Codex, Claude Code, OpenCode, Kimi Code, or DeepSeek Harness from a shared chat interface to suit the task.
- **Multiple agents in parallel**: Explicitly request delegation or parallel work to run independent subtasks in subagents with isolated contexts, then combine their results.
- **Tools and skills**: Extend agents with Skills, MCP, browser access, a terminal, local workspaces, and Android system tools.
- **System integration and memory**: Scheduled tasks, alarms, calendar management, audio playback control, and short-term and long-term memory.
- **Connect to Codex on your computer**: Access your computer's Codex from your phone through [codex-bridge](tools/codex-bridge/README.md).

### Kimi / DeepSeek WebUI: an agent workspace on your phone

Install the corresponding runtime components and configure a compatible Provider and model, then ask the assistant to “Open Kimi Code WebUI” or “Open DeepSeek Harness WebUI.” The built-in plugin starts the local Web service, opens its interface, and provides status and stop tools. Model connections use the Provider configured in the app.

### Switch Harnesses to suit the task

A Harness is the runtime in which an agent executes tasks. Use the Agent selector in chat to choose Xiaowan, Codex, Claude Code, OpenCode, Kimi Code, or DeepSeek Harness. Install the relevant components and configure a model first; available models and tools depend on the selected Harness and Provider.

### Run agents in parallel, then bring the results together

For example, ask an agent that supports subtask delegation:

> Delegate three tasks in parallel: analyze the project structure, review test coverage, and identify documentation gaps. Combine the findings and recommend next steps.

Subtasks run independently with isolated contexts, optional roles and concurrency settings, and aggregated results. This suits work that can be split into independent parts, such as code exploration, research organization, and option comparisons. Scheduling capabilities depend on the selected Harness.

<p align="center">
  <img src="docs/tutorial/example.png" alt="Example" />
</p>


<details>
<summary id="quick-start"><strong>Quick Start</strong></summary>

### Configure the app

Open the settings page from the left sidebar:

<p align="center">
  <img src="docs/tutorial/two.png" alt="Configure AI capabilities" width="260" />
  <img src="docs/tutorial/three.png" alt="Configure AI providers" width="420" />
</p>

Then open the scenario model settings:

<p align="center">
  <img src="docs/tutorial/four.png" alt="Configure AI models" width="260" />
</p>

Note: `Memory embedding` requires an embedding model. For the best overall experience, the other scenarios should use multimodal or vision-capable models whenever possible.

<p align="center">
  <img src="docs/tutorial/five.png" alt="Alpine environment" width="260" />
</p>

The app usually initializes the Alpine environment automatically on startup, and you can also manage that environment from the same settings area.

<h2 id="use-cases">Use Cases</h2>

### Skills

You can ask OmniBot to install a skill by simply sending it the repository link. Recommended collection: https://github.com/OpenMinis/MinisSkills

Enable or disable skills from the skill repository:

<p align="center">
  <img src="docs/tutorial/six.png" alt="Skill store" width="260" />
  <img src="docs/tutorial/seven.png" alt="Skill example" width="260" />
</p>

### Scheduled tasks

<p align="center">
  <img src="docs/tutorial/ten.png" alt="Scheduled task" width="260" />
  <img src="docs/tutorial/eleven.png" alt="Timing" width="260" />
</p>

Scheduled tasks execute subagent flows. Alarms are reminder-only. A subagent can be assigned a complete task and behaves like a full agent.

### Browser

<p align="center">
  <img src="docs/tutorial/twelve.png" alt="Browser" width="260" />
</p>

### Workspace

<p align="center">
  <img src="docs/tutorial/workspace.jpg" alt="Workspace" width="260" />
</p>

### Remote Codex bridge

To use Codex mode with Codex running on a PC or Mac, start `codex-bridge` on the computer where the Codex CLI is installed and logged in:

```bash
npx @thuocean/codex-bridge
```

Choose the LAN address and token mode in the terminal setup UI, then scan the printed QR code from OpenOmniBot's Codex settings. For advanced options and troubleshooting, see the [codex-bridge README](tools/codex-bridge/README.md).

</details>

<h2 id="development-guide">Development Guide</h2>

### Requirements

- Flutter SDK `3.47.2+`
- JDK `17+`
- Node.js `20.19+` or `22.12+` and pnpm `10.28.0` (for WebUI development)

### Get the code

```bash
git clone https://github.com/omnimind-ai/OpenOmniBot.git
cd OpenOmniBot

cd ui
flutter pub get
```

If Flutter reports `Could not read script '.../ui/.android/include_flutter.groovy'`, run:

```bash
flutter clean
flutter pub get
```

### Develop the WebUI locally

The WebUI in `webchat/` is a standalone React + TypeScript + Vite project. During local development, Vite serves the frontend with hot reload and proxies `/webchat/api` requests to the Android app's local service.

1. Install and start OpenOmniBot on an Android device. Keep the computer and device on the same trusted LAN.
2. In the app, open **Settings > Local Service**, enable the service, and copy its address and Token. The default port is `8899`, but always use the address shown by the app.
3. Start the WebUI development server from the repository root, replacing the sample address with the Android local-service address (do not append `/webchat`):

```bash
cd webchat
pnpm install --frozen-lockfile

VITE_WEBCHAT_PROXY_TARGET=http://192.168.1.20:8899 pnpm dev
```

On PowerShell, set the proxy target first:

```powershell
$env:VITE_WEBCHAT_PROXY_TARGET = "http://192.168.1.20:8899"
pnpm dev
```

Open the URL printed by Vite (normally `http://localhost:5173`) and enter the Token copied from the app. Use `pnpm dev` for end-to-end API/SSE testing; the proxy keeps session cookies, realtime events, workspace access, and browser mirroring on the same local origin.

Before submitting WebUI changes, run:

```bash
cd webchat
pnpm run typecheck
pnpm run build
```

The production files are generated in `webchat/dist/`. Both `dist/` and `node_modules/` are local outputs and must not be committed.

Android builds handle the WebUI automatically: Gradle runs the locked pnpm install, executes the Vite production build, clears stale WebChat assets, and copies only `dist/` into the APK. To verify this step without building the full app, run:

```bash
./gradlew :app:syncWebChatBundle -Ptarget=lib/main_standard.dart
```

Flutter Web is not part of this workflow.

### Build and install

Release APK builds use `OMNIBOT_UPDATE_WORKER_URL` as the default GUI VLM proxy
and receive the Gelab route from the update Worker. The upstream Gelab key stays
in the Worker. Debug APK builds use the OpenAI-compatible LLM API configured by
`LLMTHU_API_BASE`, `LLMTHU_API_KEY`, and `LLMTHU_MODEL` for normal LLM requests,
context compaction, and `scene.vlm.operation.primary`.
For local acceptance testing, Release builds can explicitly enable
`-POOB_BUNDLE_LLMTHU_PROVIDER=1` to bundle the same `LLMTHU_*` configuration
for use out of the box. Regular Release and CI builds do not bundle this
configuration by default.

```bash
cd ..

./gradlew :app:installDevelopStandardDebug -Ptarget=lib/main_standard.dart
```

For acceptance testing on a physical device, use the repository's LLMTHU
installation script. It enables the LLMTHU provider by default and reads the
API key from `LLMTHU_API_KEY` in the current shell. If the key is missing, the
script fails immediately instead of producing an unusable test APK. Regular
Release and CI builds still do not automatically bundle the key.

```bash
export LLMTHU_API_KEY='your-api-key'
./scripts/install_release_llmthu_device.sh
```

<h2 id="architecture">Architecture Overview</h2>

```text
OpenOmniBot/
├── app/                        # Android host app: entry point, agent orchestration, system abilities, MCP, services
├── ui/                         # Flutter Android UI: chat, settings, tasks, and memory
├── webchat/                    # React + TypeScript WebUI; Vite builds the static bundle packaged by Android
├── baselib/                    # Shared core libraries: database, storage, networking, model config, permissions
├── assists/                    # Shared task lifecycle and chat/model coordination
├── uikit/                      # Native overlay UI: floating ball, overlay panels, half-screen surfaces
└── ReTerminal/core/            # Embedded terminal experience modules
```

<h2 id="community">More</h2>

Thanks to developers from [LINUX.DO](https://linux.do) and other communities for supporting OpenOmniBot.

Special thanks to these open-source projects:

- https://github.com/RohitKushvaha01/ReTerminal
- https://github.com/OpenMinis

<table align="center">
  <tr>
    <td align="center">
      <img src="https://omni.1775885.xyz/community/wechat-qr" alt="WeChat Group" width="220"/><br/>
      <b>WeChat Group</b><br/>
      <a href="https://discord.gg/WnBvBXgykD">Join the Discord community</a>
    </td>
  </tr>
</table>
