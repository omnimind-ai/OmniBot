# Chat Completions 可选缓存字段兼容修复

用户截图：`chat completion stream request failed(400): 未知请求字段: prompt_cache_key`。

原因：普通 Chat Completions 出口透传可选 OpenAI 字段，只有 DeepSeek 专用出口过滤，未知兼容服务商可能严格拒绝该字段。

修复沿用 ProviderRequestCapabilities 和 HttpController 的既有请求出口：新增默认 false 的 supportsChatPromptCacheKey；仅精确匹配官方 OpenAI HTTPS root/v1 地址时保留该字段。其他 Chat Completions 连接在发送前移除顶层 prompt_cache_key。保留原始请求中的本地身份用于用量归属，不修改消息、工具参数、密钥或 ACP 生命周期，不添加失败重试。本轮不改变 Responses 的既有行为，不增加配置页面。

验证：HttpControllerResponsesTest 24 项、HttpControllerDeepSeekTest 7 项、HttpAgentLlmClientTest 30 项，共 61 项通过。新测试覆盖流式/非流式、第三方/本地/缺失地址、伪装 OpenAI 域名、嵌套工具参数同名字段不被删除以及官方 OpenAI 保留字段。编译缓存冲突后编译器回退完成，Gradle 最终 BUILD SUCCESSFUL。

尚未安装此修复包或在截图对应服务商上复测。手机有活跃任务，不中断安装；测试通过不等于原会话真机验收。
