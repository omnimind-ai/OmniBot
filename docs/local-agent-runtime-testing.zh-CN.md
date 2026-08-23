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
