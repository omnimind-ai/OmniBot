# Agent 版本检测与 OpenCode V2

Agent 模式页提供“检查更新”、单个“升级”和“全部升级”。当前版本来自选定终端环境中 CLI 的 `--version`，最新版本来自 npm 的轻量 `dist-tags` 接口。检查结果、检查时间与失败原因按 Ubuntu / Alpine 分别缓存；打开页面只读缓存，不自动下载或安装。终端尚未准备时，不为版本检测安装 rootfs。

## 版本与安装的所有者

- `acp/agents.json` 继续声明安装包、固定版本、准备修订和安装脚本。
- `ManagedAgentUpdates` 只负责包版本比较和查询；`AcpAgentProfileStore` 保存版本信息，ACP health 更新不会覆盖它。
- `AgentRuntimeManager` 的宿主管理接口 `agent/checkUpdates` 返回带 `packageVersion` 的现有目录。此方法不发送给 ACP Agent。
- 单个与批量升级都复用 `agent/prepare(force=true)`。Flutter 服务串行排队，离开设置页后继续执行，一个失败不会阻止其他已排队项目。
- 安装绑定发起操作时的终端环境；排队期间切换环境后，旧队列不会安装到新的环境中。
- 完成安装后重新运行 CLI 版本检测，不把查询到的最新版本直接当作已安装版本。已启动的 ACP 进程继续使用原来的会话，新程序在下次启动时生效。

这些属于包管理，不增加 ACP 会话、Turn、终态事件或重试路径。Conversation → ACP Session → Turn → Item 的所有者保持不变。

`@latest` CLI 查询发布通道；已有固定版本的 ACP 适配器继续保持固定。固定版本项目展示“版本由应用管理”和上游版本，不绕过目录声明升级到未适配版本。不降级比目标更新的本地版本，未安装项目不纳入“全部升级”。

## OpenCode V2 迁移

安装包改为 `@opencode/cli@latest`，启动命令仍为官方 `opencode acp`。

共享安装脚本在临时 npm prefix 中准备 V2，按 rootfs 选择 `@opencode/cli-linux-arm64` 或 `@opencode/cli-linux-arm64-musl`。验证实际二进制版本后才移除旧 `opencode-ai` 并发布新安装，避免共用 `opencode` 命令导致 npm `EEXIST`。下载、加载器或版本校验失败时不替换旧命令。

首次安装在 `~/.config/opencode/` 中保留 `opencode.json.pre-v2.bak`、`opencode.jsonc.pre-v2.bak`；后续升级不会覆盖已有备份。

配置适配器只迁移受管 `omnibot` Provider：

- 使用 V2 `providers`、`package`、`settings`、`headers`、`body`、`capabilities` 和 variants 数组。
- Chat Completions、Responses、Anthropic 分别选择 OpenCode 原生 compatible Provider。
- 保留用户的其他 Provider、MCP、权限、策略和 Agent 配置，由 OpenCode 官方兼容层读取仍使用 V1 格式的部分。
- 已有 V2 Provider 优先于同名旧配置；迁移幂等。无法解析的配置会报错，不会当成空配置覆盖。
- 写入 V2 配置之前校验运行时确实已迁移，避免旧 V1 可执行文件读取 V2 配置。

官方 V1 插件实现需要按 OpenCode 的插件迁移指南单独适配；包安装器不会改写用户插件源码。

## 验证

```sh
# Kotlin / catalog / configuration tests: use JDK 21 because ReTerminal targets Java 21.
./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest

cd ui
flutter test test/services/agent_runtime_service_test.dart \
  test/features/home/pages/agent/agent_mode_setting_page_test.dart \
  test/features/home/pages/agent/agent_updates_test.dart
cd ..
node --test scripts/opencode-install-shell.test.mjs

# Install @opencode/cli into a disposable directory. Generate production
# config fixtures with AgentAdapterCatalogTest before running the real CLI.
node scripts/verify-installed-harness-adapters.mjs \
  /absolute/disposable-cli-directory app/build/reports/harness-adapters open-code
```

2026-10-20 使用真实 OpenCode 2.0.32 验证了三种 Provider 协议的 ACP initialize、session/new、配置选项、session/prompt、文本更新和官方 PromptResponse；生成配置通过官方 V2 JSON Schema。另以 macOS 原生包替换平台包名，运行完整 npm V1 1.18.29 → V2 2.0.32 迁移及 V2 重装，确认 npm 目录登记和原配置备份。Ubuntu / Alpine ARM64 的平台选择与失败保留由 shell 回归覆盖；这不等同于 Android 实机安装验收。

参考：[CC Switch](https://github.com/farion1231/cc-switch)、[OpenCode V2 安装](https://opencode.ai/v2/docs/)、[V1 迁移](https://opencode.ai/v2/docs/migrate-v1/)、[V2 Provider](https://opencode.ai/v2/docs/providers/)、[V2 ACP](https://opencode.ai/v2/docs/cli/acp/)。
