# 手机模型目录超时修复

## 证据

用户反馈手机模型提供商页远端列表刷新失败。2026-09-07 09:09:01、09:09:14 的日志均为 SocketTimeoutException，位置是 Http2Stream.takeHeaders / HttpController.fetchProviderModels。当前编辑 Provider 是已有凭据的 LLMTHU，baseUrl 为 https://llmapi.paratera.com；没有修改密钥或绑定。

代码为每次目录请求创建 OkHttpClient，读取超时隐式为 10 秒。电脑无鉴权同端点诊断分别约 0.56 秒和 10.44 秒收到 401；这只说明响应等待可能超过 10 秒，不证明手机凭据有误，也不足以断言 HTTP/2 必然不兼容。

## 修改

- 目录请求复用现有 Provider 连接池，并使用 60 秒有限读取等待；不添加模型数据缓存、自动重试或自动换地址。
- Response 使用 use 关闭。
- SocketTimeoutException 映射为 provider_request_timeout，页面显示等待超时，而不是笼统的配置失败。
- 慢目录响应回归从 5 秒提高到 12 秒，覆盖旧隐式读取超时。

## 验证

- HttpControllerCustomHeadersTest 3 项、AgentRuntimeErrorSupportTest 15 项通过，慢响应测试实际等待 12 秒成功。
- Flutter agent_runtime_service_test 58 项通过。
- APK 构建成功，SHA256 fae862cafa621e83c0236dec316b07253bb07b1f9150d4f2ca46335c5f0f3d98，09:15:47 install -r 安装到 b49f281b，保留数据。
- 手机打开模型选择触发拉取后，09:17:14 被另一轮 PACKAGE UPDATED 安装中断（系统 exit-info 确认），并非已证明的应用崩溃；正在协调设备占用。该次刷新没有验收结论。

### 真机最终验证

- 协调其他任务停止真机操作后，在 09:24 左右进入 LLMTHU 模型提供商页，远端加载完成显示「共 95 个模型」。
- 随后点击该页远端拉取按钮，列表经历加载过程后再次显示「共 95 个模型」，未显示刷新失败。完成首次进入和手动重复拉取两次实际网络验收；没有修改密钥、地址或聊天模型绑定，没有发送聊天消息。
- 最终验收使用 09:17 另一任务从共享工作树构建并覆盖安装的包；该任务确认保留本次目录超时修改。它不是上文 09:15 APK 的同一哈希，不能混作同一构建证据。
- 手机同时出现的 CC 403「当前登录凭证不能访问该模型接口」已同步给负责该问题的任务，与此次模型目录请求超时分开处理；目录拉取成功不代表 CC 官方 Provider 权限问题已修复。
