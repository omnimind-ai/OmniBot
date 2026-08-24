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
你的端侧 AI 助手
</h3>

<div align="center">
  <img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/omnimind-ai/OmniBot">
  <a href="https://github.com/omnimind-ai/OpenOmniBot/releases/latest"><img alt="GitHub Release" src="https://badgen.net/github/release/omnimind-ai/OpenOmniBot/stable"></a>
  <br>
  <a href="https://trendshift.io/repositories/26966" target="_blank"><img src="https://trendshift.io/api/badge/repositories/26966" alt="omnimind-ai%2FOpenOmniBot | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>
  <br>
  <a href="https://omnimind.com.cn"><img src="https://img.shields.io/badge/About_us-万象智维-purple.svg?color=%234b0c77" alt="万象智维"></a>
  <a href="https://linux.do"><img src="https://img.shields.io/badge/Linux_Do-社区-yellow.svg?color=%23ac3712" alt="LinuxDo社区"></a>
  <a href="#community">
    <img src="https://img.shields.io/badge/WeChat-微信群-lightgreen" alt="微信群"/>
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
</p>

> OpenOmniBot 直接运行在 Android 设备上，将聊天、Agent 工具、本地工作区与系统级集成整合在一个应用中。

OpenOmniBot 是一个基于 Android 原生 Kotlin 与 Flutter 构建的端侧 AI Agent。与传统 AI Chat 不同，它关注的是 **从理解 -> 决策 -> 执行 -> 反馈的完整闭环**。

<h2 id="core-capabilities">核心能力</h2>

- **工具生态扩展**：Skills、Alpine 环境、浏览器、MCP、安卓系统级工具等。
- **系统级能力**：支持定时任务、闹钟提醒、日历创建/查询/修改、音频播放控制。
- **记忆系统**：支持短期与长期记忆嵌入。
- **生产力工具**：支持读写文件、浏览工作区、调用浏览器、调用终端。

<p align="center">
  <img src="docs/tutorial/example.png" alt="示例" />
</p>


<details>
<summary id="quick-start"><strong>快速开始</strong></summary>

### 配置应用

从左侧栏打开设置页面：

<p align="center">
  <img src="docs/tutorial/two.png" alt="配置 AI 能力" width="260" />
  <img src="docs/tutorial/three.png" alt="配置 AI 提供商" width="420" />
</p>

然后前往场景模型配置：

<p align="center">
  <img src="docs/tutorial/four.png" alt="配置 AI 模型" width="260" />
</p>

说明：除了 `Memory embedding` 强制需要嵌入模型之外，其他场景为了获得更好的体验，建议优先使用多模态或视觉模型。

<p align="center">
  <img src="docs/tutorial/five.png" alt="Alpine 环境" width="260" />
</p>

一般情况下，应用启动时会自动初始化 Alpine 环境，你也可以在这里自行管理环境。

<h2 id="use-cases">使用场景</h2>

### Skills

你可以直接把 skills 仓库链接发给小万，让她帮你安装。推荐仓库：https://github.com/OpenMinis/MinisSkills

在技能仓库中可以选择开启或关闭某项技能：

<p align="center">
  <img src="docs/tutorial/six.png" alt="技能仓库" width="260" />
  <img src="docs/tutorial/seven.png" alt="技能示例" width="260" />
</p>

### 定时任务

<p align="center">
  <img src="docs/tutorial/ten.png" alt="定时任务" width="260" />
  <img src="docs/tutorial/eleven.png" alt="时间设置" width="260" />
</p>

定时任务用于执行 subagent 流程；闹钟仅用于提醒。你也可以把一个完整任务交给 subagent，它会像完整 agent 一样执行。

### 浏览器

<p align="center">
  <img src="docs/tutorial/twelve.png" alt="浏览器" width="260" />
</p>

### Workspace

<p align="center">
  <img src="docs/tutorial/workspace.jpg" alt="Workspace" width="260" />
</p>

### Remote Codex bridge

如果希望在手机端使用运行在电脑上的 Codex 模式，请在已安装并登录 Codex CLI 的电脑上启动 `codex-bridge`：

```bash
npx @thuocean/codex-bridge
```

在终端交互中选择监听的局域网地址和 token 模式，然后在 OpenOmniBot 的 Codex 设置中扫码连接。更多选项和排障说明见 [codex-bridge README](tools/codex-bridge/README.md)。

</details>

<h2 id="development-guide">开发指南</h2>

### 环境要求

- Flutter SDK `3.9.2+`
- JDK `11+`
- Node.js `20.19+` 或 `22.12+`、pnpm `10.28.0`（用于 WebUI 开发）

### 获取代码

```bash
git clone https://github.com/omnimind-ai/OpenOmniBot.git
cd OpenOmniBot

cd ui
flutter pub get
```

如果 Flutter 提示 `Could not read script '.../ui/.android/include_flutter.groovy'`，可以执行：

```bash
flutter clean
flutter pub get
```

### 本地开发 WebUI

`webchat/` 是独立的 React + TypeScript + Vite 项目。本地开发时由 Vite 提供热更新，并将 `/webchat/api` 请求代理到 Android 应用提供的本地服务。

1. 在 Android 设备上安装并启动 OpenOmniBot，确保电脑和设备位于同一个可信局域网。
2. 在应用中打开 **设置 > 本地服务**，启用服务并复制地址和 Token。默认端口是 `8899`，但应以应用实际显示的地址为准。
3. 从仓库根目录启动 WebUI 开发服务器。将下面的示例地址替换为 Android 本地服务地址，地址末尾不要添加 `/webchat`：

```bash
cd webchat
pnpm install --frozen-lockfile

VITE_WEBCHAT_PROXY_TARGET=http://192.168.1.20:8899 pnpm dev
```

PowerShell 使用以下方式设置代理地址：

```powershell
$env:VITE_WEBCHAT_PROXY_TARGET = "http://192.168.1.20:8899"
pnpm dev
```

打开 Vite 输出的地址（通常为 `http://localhost:5173`），输入从应用复制的 Token。端到端调试 API、SSE 实时事件、工作区和浏览器镜像时应使用 `pnpm dev`，开发代理会让这些请求和会话 Cookie 保持在同一本地域名下。

提交 WebUI 修改前执行：

```bash
cd webchat
pnpm run typecheck
pnpm run build
```

生产静态文件会生成到 `webchat/dist/`。`dist/` 和 `node_modules/` 都是本地产物，不应提交到仓库。

Android 构建会自动处理 WebUI：Gradle 使用锁文件安装依赖、执行 Vite 生产构建、清除旧 WebChat 资源，并且只将 `dist/` 复制进 APK。如需跳过完整 APK 构建、单独验证该流程，可以执行：

```bash
./gradlew :app:syncWebChatBundle -Ptarget=lib/main_standard.dart
```

此流程不再使用 Flutter Web。

### 构建并安装

Release APK 默认使用 `OMNIBOT_UPDATE_WORKER_URL` 作为 GUI VLM 代理地址，
并从更新 Worker 获取 Gelab 线路；Gelab 的上游 Key 只保存在 Worker。
Debug APK 统一使用 `LLMTHU_API_BASE`、`LLMTHU_API_KEY` 和
`LLMTHU_MODEL` 配置的 OpenAI-compatible LLM API，覆盖普通 LLM、上下文压缩
和 `scene.vlm.operation.primary`。
本地验收 Release 可显式传入 `-POOB_BUNDLE_LLMTHU_PROVIDER=1`，使用同一组
`LLMTHU_*` 配置完成开箱即用验收；普通 Release 和 CI 默认不内置该配置。

```bash
cd ..

./gradlew :app:installDevelopStandardDebug -Ptarget=lib/main_standard.dart
```

<h2 id="architecture">架构概览</h2>
```text
OpenOmniBot/
├── app/                        # Android 主宿主模块：入口、Agent 编排、系统能力、MCP、前台服务
├── ui/                         # Flutter Android UI 模块：聊天、设置、任务和记忆
├── webchat/                    # React + TypeScript WebUI；由 Vite 构建并通过 Android 打包静态产物
├── baselib/                    # 基础核心库：数据库、存储、网络、模型配置、权限等
├── assists/                    # 公共任务生命周期与聊天/模型协调
├── uikit/                      # 原生浮层 UI：悬浮球、覆盖层面板、半屏界面
└── ReTerminal/core/            # 内嵌终端体验相关模块
```

<h2 id="community">其他</h2>

感谢 [LINUX.DO](linux.do) 等社区开发者对 OpenOmniBot 的支持。

特别感谢这些优秀的开源项目：

- https://github.com/RohitKushvaha01/ReTerminal
- https://github.com/OpenMinis

<table align="center">
  <tr>
    <td align="center">
      <img src="https://omni.1775885.xyz/community/wechat-qr" alt="WeChat Group" width="220"/><br/>
      <b>WeChat Group</b><br/>
      <a href="https://discord.gg/WnBvBXgykD">加入 Discord 社区</a>
    </td>
  </tr>
</table>
