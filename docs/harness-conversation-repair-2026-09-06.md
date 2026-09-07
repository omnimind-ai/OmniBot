# Harness 对话修复与验收（进行中）

## 已确认的故障与修改

- CC 的本地协议标签检查误拦多协议网关。已移除此判断；沿用原配置适配器，不搜索地址、不切换 Provider，不将 OpenAI 接口伪装为 Anthropic。
- 同 Provider 换模型时 Flutter 断开进程，原生保存场景绑定又再次使运行时失效。协同任务已改为既有 session/config 接口，同 Provider 仅换模型不失效；显式 Provider 切换仍走原失效边界。
- OpenCode 和 DSH 的官方选项值与原始模型 ID 不同。现有 Harness 适配器增加选项匹配，OpenCode 只匹配受管 omnibot 前缀，DSH 只接受唯一匹配的二元组合值；保留模型自身命名空间。应用于会话初始配置和显式模型选择，不新增生命周期。
- 模型列表 401 被展示为 0/无模型。共享错误处理增加鉴权失败类别，页面保留错误而不同时显示空列表。
- 预置服务商只有默认地址、没有凭据也会被自动探测。按用户要求仅在选择列表隐藏未配凭据的预置项，不删除配置或添加密钥；已有凭据、自定义匿名连接不受此规则影响。

## 真实手机诊断

设备 b49f281b / PJE110。只读 debug query 显示当前绑定 debug-llmthu-glm / GLM-5.1，有已存密钥；预置 DeepSeek、Mimo、Kimi、MiniMax、百炼无密钥。没有导出或替换凭据。

debug-only `verify_bound_provider` 在手机进程内使用现有绑定，通过现有 HttpController 检查：

| 请求协议 | 结果 |
| --- | --- |
| Chat Completions | HTTP 200 / OK |
| Responses | HTTP 200 / OK |
| Anthropic | Software caused connection abort，无 HTTP 状态 |

这是实际服务商请求，不是完整 Harness 验收。此前 Codex GLM-4.5-Air 的上游鉴权错误不能推断当前 GLM-5.1 密钥失效。

## 自动化验证

- 错误处理 14 项、配置适配器 52 项通过，包含跨 Harness 模型选项测试。
- Flutter 服务/选择器/运行时 65 项通过；后续隐藏预置项版本的选择器 8 项通过；包含聊天架构的组合 26 项通过。
- 真实本地官方 CLI 对已有配置夹具的 12 个组合完成 initialize、session/new、配置、prompt、官方结束响应；使用本地确定性响应，非真实账号推理、非 Android 工具执行。
- CLI 证据目录：`/var/folders/c_/3hg5mxq55td06p3tvmnrb5l00000gn/T/oob-acp-matrix-KEFnfy`。

## 尚未完成

最新 Debug APK 已构建并通过 `install -r` 安装到 b49f281b，保留用户数据。SHA256：`659a15766c0884a260364331c71168a7d7074d52960da68c212cdd56c460f2a7`。手机 UI 已交接协同任务验收，避免并发操作。

同包已安装到 emulator-5556。通过真实 UI 打开 Model & settings → Model，列表隐藏五个未配置认证的预置 Provider，仍显示两个已有配置 Provider。只读配置查询确认五个预置配置仍在，没有删除。当前 Provider 实际拉取返回 95 个模型（这只证明目录加载，不代表每个模型均可推理）。加载完成后当前卡片展开，第二个 Provider 会移到屏幕下方；收起当前卡片后确认第二项仍显示。

最终包的逐 Harness 真机模型切换、连续对话、终端长任务与重启验收；CC 的实际 Anthropic 请求中断仍需真实 Harness 证据。不得将上述局部通过写成全部完成。
