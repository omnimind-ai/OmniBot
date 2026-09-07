# 缓存命中调查记录（已撤回的试验）

> 当前状态：下文是历史试验记录，不代表最终实现。后续无键请求也命中缓存，约 99% 命中且关闭推理仍出现约 16 秒等待，不能认定缺少缓存键是整体延迟的根因。已删除本次新增的 VerifiedPromptCacheSupport 注册表、配置失效钩子及 DebugProviderLatencyProbe，保留原有请求兼容性过滤。下文所述调试操作和能力存储测试不在最终普通构建中。最终修复范围和延迟证据见 [启动延迟调查](chat-start-latency-2026-09-07.md)。

## 实测证据

手机 b49f281b 当前配置的 OpenAI compatible Provider（GLM-5.1）最近两次真实请求间隔约 35 秒，系统消息、工具定义与已有消息前缀保持一致，但发送体没有 `prompt_cache_key`，服务端返回 `cached_tokens=0`。更早一次请求命中 8192，不能将这三次跨越 44 分钟的请求当成连续缓存实验。

同一手机、同一接口、同一模型与固定无隐私的约 13k token 前缀，顺序执行无键、有键、无键、有键；有键的两次使用同一个随机实验键。四次均 HTTP 200：

|请求|发送完成 ms|响应头 ms|prompt tokens|cached tokens|
|---|---:|---:|---:|---:|
|无键 1|201|8419|12959|0|
|有键 1|51|1864|12959|12928|
|无键 2|49|3357|12952|0|
|有键 2|50|10943|12959|12928|

有键两次命中约 99.8%，无键两次均未命中。当前接口接受并有效使用该字段。高命中仍出现 10.9 秒等待，说明修复缓存回归不能消除上游全部首包延迟。

最终安装包第二轮四次均成功：无键 0/12952（11.37 秒），有键 12928/12959（3.31 秒），无键 12928/12959（6.14 秒），有键 12928/12952（4.99 秒）。无键也可命中，服务端存在自动缓存，不能把所有未命中或启动延迟都归因于缺少缓存键。该轮返回 `cacheKeySupportVerified=true`，当前手机已保存能力记录。

## 缺陷与修复范围

9 月 6 日为解决另一接口拒绝未知字段的 HTTP 400，兼容性策略将所有第三方 Chat Completions 的顶层 `prompt_cache_key` 都删除了。这个默认保护有必要，但缺少已验证支持接口的能力覆盖，误伤了当前服务商。

沿用 `ProviderRequestCapabilities` 与原有请求构造入口：官方 OpenAI 保持支持；第三方只有对应 endpoint + model 的显式成功验证记录才保留字段。未经验证接口仍过滤字段，不增加请求失败后重发。验证元数据持久化，不在每次发送前探测网络；修改路由、协议、认证或删除配置时清理该 endpoint 的旧验证记录。

`DebugProviderLatencyProbe` 的显式 `cache_compare` 使用合成前缀验证。只有两次有键请求均成功且返回实际命中、期间 Provider 配置未改变，才记录能力。普通请求沿用 `PromptCacheKeyStore` 的安装范围 + conversationId 稳定键，不使用实验随机键，不改变 ACP session/turn/item 生命周期与历史。

## 验证

新增能力存储测试覆盖默认拒绝、持久化重读、模型与 endpoint 隔离、配置失效；请求构造与 ACP/SSE 生命周期回归测试和 debug APK 构建结果另见本次验证日志。

`/tmp/oob-cache-fix-validation.log`：137 项测试通过，构建成功。补齐导入/替换失效后，`/tmp/oob-cache-fix-final.log`：能力存储 3、缓存键 2、Provider 配置 10 项通过，最终 APK 构建成功并安装到 b49f281b。随后用户报告每条消息仍慢，生命周期排查继续；缓存修复不是整体延迟问题已解决的证据。

参考：[ACP prompt turn](https://agentclientprotocol.com/protocol/v1/prompt-turn)、[GLM context caching](https://docs.z.ai/guides/capabilities/cache)。上表为实际配置网关的测量，不能将 GLM 原厂缓存说明替代网关能力实测。
