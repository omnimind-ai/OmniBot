# 本地 Agent/ACP 测试集

统一入口是：

```bash
scripts/test-agent-runtime.sh
```

它会自动运行：

- Node 协议与 Provider 请求构造测试；
- Android/JVM 的 ACP 状态、Provider fallback、Harness 准备测试；
- Flutter Agent 页面与运行时服务测试。

如果需要真实联网调用 Provider，只在当前 shell 注入测试 Token，然后追加 `--live`：

```bash
export OMNIBOT_TEST_API_KEY='测试 token'
export OMNIBOT_TEST_BASE_URL='https://your-provider.example/v1'
export OMNIBOT_TEST_MODEL='glm-5.1'
scripts/test-agent-runtime.sh --live
```

Token 不会写入仓库、不会打印，也不会传给 Gradle 或 Flutter 测试。真实 smoke 只执行一次 `/models` 和一次短的非流式 `/chat/completions`，最大输出 8 tokens。

没有 Token 时，默认只运行本地测试；显式使用 `--offline` 可以强制跳过真实 Provider 请求：

```bash
scripts/test-agent-runtime.sh --offline
```

当前测试集覆盖的关键行为是：检测已安装 Harness 不会触发 npm/node-gyp 下载；检测会把可运行 Harness 标记为 `online`；Provider `/models` 暂时不可用时仍保留已绑定模型；Agent 页面检测和显式 Harness 初始化不会混用。

不要把 Token 写入 `.env`、脚本或提交记录。建议通过 shell profile、密码管理器或 CI secret 注入 `OMNIBOT_TEST_API_KEY`。

## 长期验证规则

后续修复必须先证明不变量，再修改实现；单个现象不得触发全局重构。验证按
“Provider/协议 -> Harness 适配器 -> ACP runtime -> UI/模拟器 -> 真实在线端点”
分层进行，每一层都要记录通过、失败、未覆盖和失败归属。

### 不可更改的不变量

- 一个用户发送对应一个逻辑 ACP `turnId`；网络重试不能创建第二个用户可见回合。
- `conversationId`、`sessionId`、`turnId`、`messageId`、`toolCallId` 是归属键；旧事件不得借用当前回合。
- Conversation history 是用户可见事实源；ACP echo、replay、reconnect 只能幂等合并。
- Agent 生命周期只走官方 ACP session/turn/update/cancel/close 语义；不得恢复私有 stream 协议、第二 reducer 或第二状态机。
- Provider 能力必须由配置/目录或官方协议证明；适配器不得猜测、静默换路或吞掉终态错误。

### 已与官方对齐的部分

- ACP 的 `session/prompt -> session/update -> PromptResponse` 生命周期及 `session/cancel`。
- Codex 的 Responses 配置、`env_http_headers` 和独立认证文件入口。
- Claude Code 的 Anthropic Messages endpoint；DeepSeek 官方 Anthropic 路径只在对应 Provider endpoint 上使用。
- OpenCode 的官方 provider 配置结构和环境变量引用。
- Provider 自定义 Header 由共享 Provider 编辑并映射到各官方 Harness surface，不复制成第二事实源。

### 可以继续调整的部分

- Provider 的在线超时预算、重试次数和测试用例规模，但必须保持“输出后不重放、不换路”。
- Harness 配置生成器的字段映射，只能根据对应版本的官方配置契约和真实失败修改。
- 测试脚本的在线覆盖范围；在线测试不得成为默认 CI 的不稳定依赖，也不得输出凭证。
- UI 的状态呈现与日志细节，只能投影已被 runtime 证明的状态。

每次提交的测试报告必须单独标记：已验证不变量、尚未验证的不变量、属于上游环境
还是本地代码、可修改项、不可修改项，以及哪些行为已经对齐官方且以后不得重新引入旁路。
