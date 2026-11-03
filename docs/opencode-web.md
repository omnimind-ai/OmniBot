# OpenCode 官方 Web UI 接入

OpenCode Web 通过现有 `com.omnimind.agent-web` 插件提供，入口位于 Agent 模式的“本地 Web 界面”和侧栏 Web 菜单。与 Kimi 一样，用户可以打开、查看状态和停止对应服务；网页由系统浏览器显示。

## 官方接口选择

[官方 Web 文档](https://opencode.ai/v2/docs/cli/web/)提供共享后台服务和前台 `serve` 两种运行方式。本项目使用前台服务，让已有 ReTerminal Session 负责进程生命周期：

```sh
env -u OPENCODE_PASSWORD -u OPENCODE_SERVER_PASSWORD \
  opencode serve --hostname 127.0.0.1 --port 0
```

启动前检查 CLI 至少为 V2。进程绑定本机随机端口，不启用远程隧道或更改 OpenCode 的共享后台服务设置。继承的服务器密码变量只对该进程清除，由官方服务生成独立密码，并从官方启动输出读取地址和凭据。

`opencode pair` 连接的是共享后台服务。因此宿主使用其对应的[官方 `server.pair` API](https://opencode.ai/v2/docs/api/)为当前前台服务创建登录链接：

1. 原生端向已验证的本机地址发送 `POST /api/pair`，使用该进程的 Basic 凭据。
2. 使用返回的 `code` 构造同源 `/auth/connect/{code}` 链接，并交给系统浏览器。
3. OpenCode 自行消费一次性代码、设置浏览器 cookie，并进入官方 Web UI。

每次打开都获取新代码；状态查询不会创建代码，进程就绪也不以一个可能已过期的登录链接为依据。配对请求禁用代理、重定向和连接失败重试，并设定超时。凭据和登录链接不进入 Flutter、MCP 工具结果或插件操作返回值。配对失败会明确返回错误，保留已运行的服务供用户重试。

## 配置与所有权

- 继续使用 `AgentWebRuntimeManager` 与命名 ReTerminal Session `omnibot-web-opencode`，停止操作只针对这个 Session。
- Provider、默认模型及缓存的模型能力来自现有 Dispatch 配置。通过 `AgentConfigAdapterRegistry` 复用 OpenCode ACP 的配置适配器，不新增配置转换规则。
- Web 配置写入 `~/.config/opencode/omnibot-web.json` 并通过 `OPENCODE_CONFIG` 指定。官方全局配置及 MCP/权限设置仍由 OpenCode 加载；不会覆盖 ACP 的 `opencode.json`。
- 密钥及自定义请求头通过进程环境引用，配置文件使用私有权限。配置指纹变化时，沿用现有 Agent Web 管理器的进程替换行为。
- 宿主只处理 Web 进程和浏览器登录。应用内部仍使用既有 Conversation → ACP Session → Turn → Item 链路；原生端不通过 OpenCode HTTP API 发送对话，也不添加网页消息 reducer。
- Provider 支持范围沿用现有 Agent Web 入口：使用能解析出 API 密钥的共享 Provider。本次未扩展 Web 进程的 ChatGPT OAuth 凭据生命周期。

插件能力：`open_opencode_web`、`get_opencode_web_status`、`stop_opencode_web`。展示位置、名称和状态/停止操作都由插件元数据声明，Flutter 不维护第二份服务目录。

## 验证

```sh
# JDK 21
./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest \
  --tests 'cn.com.omnimind.bot.plugin.official.agentweb.*'

# 上述测试生成实际生产配置及启动命令的无敏感信息测试夹具。
# 在临时目录安装 @opencode/cli 后运行 HTTP 验证，不打开浏览器：
node scripts/verify-opencode-web.mjs \
  /absolute/opencode-cli-directory app/build/reports/agent-web
```

2026-10-21 已对真实 OpenCode 2.0.32 和 2.1.2 验证：前台启动、认证要求、一次性代码消费与重新生成、cookie 访问、官方 HTML/JS 资源，以及通过配置的 Chat Completions、Responses、Anthropic Provider 获得真实运行时回复。模型响应由本地确定性服务提供，不使用真实账户推理。

单元测试覆盖地址校验、配对失败、禁止重定向、密钥不出宿主边界、重复打开复用进程、配置改变后替换进程、停止及 Provider 模型能力映射。Flutter 测试覆盖设置页与侧栏入口。未进行浏览器视觉验收或 Android 实机 Web 操作验收。
