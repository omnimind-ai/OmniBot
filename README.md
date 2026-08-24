<p align="center">
  <picture>
    <img alt="OpenOmniBot" src="docs/pic/OmniBot.png" width="50%">
  </picture>
</p>

<h3 align="center">
Your On-Device AI Assistant
</h3>

<div align="center">
  <img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/omnimind-ai/OmniBot">
  <a href="https://github.com/omnimind-ai/OpenOmniBot/releases/latest"><img alt="GitHub Release" src="https://badgen.net/github/release/omnimind-ai/OpenOmniBot/stable"></a>
  <br>
  <a href="https://trendshift.io/repositories/26966" target="_blank"><img src="https://trendshift.io/api/badge/repositories/26966" alt="omnimind-ai%2FOpenOmniBot | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>
  <br>
  <a href="https://omnimind.com.cn"><img src="https://img.shields.io/badge/About_us-万象智维-purple.svg?color=%234b0c77" alt="OmniMind"></a>
  <a href="https://linux.do"><img src="https://img.shields.io/badge/Linux_Do-Community-yellow.svg?color=%23ac3712" alt="Linux Do Community"></a>
  <a href="#community">
    <img src="https://img.shields.io/badge/WeChat-Group-lightgreen" alt="WeChat Group"/>
  </a>
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
<a href="README.md"><b>English</b></a> 
|
<a href="README.zh-CN.md"><b>简体中文</b></a>
|
</p>

> OpenOmniBot runs directly on your Android device and combines chat, agent tools, local workspaces, and system integrations in one app.

OpenOmniBot is an on-device AI agent built with native Android Kotlin and Flutter. Instead of stopping at chat, it focuses on the full loop of **understand -> decide -> execute -> reflect**.

<h2 id="core-capabilities">Core Capabilities</h2>

- **Extensible tool ecosystem**: Skills, Alpine environment, browser access, MCP, and Android system-level tools.
- **System-level actions**: Supports scheduled tasks, alarms, calendar creation/query/update, and audio playback control.
- **Memory system**: Short-term and long-term memory with embedding support.
- **Productivity tools**: Read and write files, browse the workspace, use the browser, and access the terminal.

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

- Flutter SDK `3.9.2+`
- JDK `11+`
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

```bash
cd ..

./gradlew :app:installDevelopStandardDebug -Ptarget=lib/main_standard.dart
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

<h2 id="community">Community</h2>

Thanks to the community （ including linux.do ）developers supporting OpenOmniBot.

Special thanks to these open-source projects:

- https://github.com/RohitKushvaha01/ReTerminal
- https://github.com/OpenMinis

<table align="center">
  <tr>
    <td align="center">
      <img src="https://omni.1775885.xyz/community/wechat-qr" alt="WeChat Group" width="220"/><br/>
    </td>
  </tr>
</table>
Join Discord: https://discord.gg/WnBvBXgykD
