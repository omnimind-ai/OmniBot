# 官方 OmniInfer 按需组件：模拟器验收（2026-09-18）

需求：第三种部署方式——主 App 只保留小入口和加载器，按需下载基于官方 AAR
打包的引擎，在主 App 进程内运行，模型独立下载。正式对话仍使用既有
Provider → Conversation → ACP Session → Turn → Item；不引入新 Agent loop。

本轮仅操作模拟器 `emulator-5582`（AVD `OobOmniInfer20260917`，Android 13 / API 33，
ARM64，6 GB RAM）。物理设备 `b49f281b` 未安装本轮实验包，**待真机验证**。

## 实现与来源

- 官方 Maven：`io.github.omnimind-ai:omniinfer:0.2.4`。
- 组件是独立 Android packaging project 的 DEX/资源/native 库容器；不作为另一个 App 安装。
- 主 APK 不包含 OmniInfer 类、原生库、组件 APK 或模型。真实 DEX class definitions
  和 ZIP native libraries 检查通过，见 `fixtures/omniinfer-module-emulator-20260918/artifacts.json`。
- 官方 AAR 中 22 个 native libraries 与组件内文件逐字节相同，未修改推理实现。
- API 30+ `DexClassLoader`、`ResourcesLoader`、`AppComponentFactory`；SDK 的 Service
  由 Android 创建与管理。下载固定 URL 与 SHA-256，私有目录，DEX 只读后加载。
- 引擎组件 28,734,817 bytes，SHA-256
  `b317a12bda248f9197024fd90d7067e8d6038394c7a6fde81dee2a17a02f9006`。
- 远程预发布：<https://github.com/omnimind-ai/OmniBot/releases/tag/omniinfer-runtime-v0.2.4-test.1>。
  它是项目封装的实验组件，不是上游官方 APK；没有设为主 App 最新稳定版本。
- 小入口：Settings → Model Providers → Use local model service。主进程本地服务仍是
  `http://127.0.0.1:9099/v1`，Chat Completions、空 Key；模型 ID 由实际服务发现。
- 按需 skill 同步更新；旧独立宿主/源码构建移到引用文档，仅明确请求旧路径时使用。

## 已执行的结果

1. 独立组件构建成功，用时 1m31s；不在模拟器下载 SDK/NDK 或编译 native library。
2. 主 App 构建、安装、设置小入口打开原生页面通过。第一次安装因剩余空间不足失败；
   只删除本轮复制的模型副本后安装成功，旧宿主模型、原有对话和其他数据保留。
3. 初次加载先使用开发时复制的组件/模型；**该次不算远程下载验收**。
4. 之后停止服务，移除本轮组件副本，通过原生页面从实际 Release 下载组件。
   再停止服务，移除主 App 中的模型副本，通过页面从 ModelScope 下载模型。
   两次下载完成且 SHA-256 正确；记录 `remote-download.json`。不是只做主机端 curl。
5. 官方 SDK 在主 App PID 4607 中加载 `libggml-cpu-android_armv8.2_2.so`。
   非流式非空回答约 0.259s；流式、取消、取消后再回答通过；API read-only 工具往返通过。
   这些是 **API 级测试，不能替代正式 App Agent**，见 `inference.json`。
6. 切回 MainActivity 120 秒后，相同 PID 的健康检查、模型发现、非空回答、流式取消恢复通过。
   见 `main-foreground-120s.json` 和关联 inference 记录。它不证明 OPPO 真机后台策略已解决。
7. Flutter 小入口与 Provider 页面 26 项测试通过；旧技能下载/校验/清理 HTTP fixture 18 项通过；
   skill frontmatter 校验通过。

## 正式 Agent 与长输入调查

新建对话，明确选择本机 Provider 的 Qwen3 0.6B、Reasoning Off。通过真实聊天输入：
用 Python 计算 1..100 平方和，写文件并读回，期望 `338350\n`，最终标记 DONE。
本轮标记 `OOB_LIVE_OMNIINFER_AGENT_1789703055711`；不重放历史失败任务。

输入提交后官方 native 引擎收到请求，但首条输出长时间等待。5 秒 `simpleperf --app`
采样 42,079 samples，约 51.18% 在 `ggml_compute_forward_flash_attn_ext`，6.54% 在
Q8 matrix multiply；支持长输入计算瓶颈，不能把 CPU 采样说成任务完成。
见 `qwen3-long-prompt-profile.txt`。未改动全局性能权限，`security.perf_harden` 仍为 1。

后续结论以实际 journey/oracle 文件为准；未通过正式 Agent 前不得宣布需求全部验收。

## 可复现执行入口

以下命令要求先通过 App 的原生入口加载对应模型；物理设备测试不得用模拟器结果代替。

```sh
python3 scripts/verify-omniinfer-payload.py MAIN.apk RUNTIME.apk OFFICIAL.aar OUTPUT.json
python3 scripts/verify-omniinfer-phone.py emulator-5582 OUTPUT.json --allow-emulator --in-app --require-optimized-cpu --tools
python3 scripts/verify-omniinfer-in-app-background.py emulator-5582 OUTPUT.json
node scripts/verify-agent-user-journey.mjs emulator-5582 scripts/fixtures/agent-user-journeys/omniinfer-in-app-agent.en.json OUTPUT_DIR
python3 scripts/test-omniinfer-skill.py
cd ui
flutter test test/features/home/pages/model_provider_setting/model_provider_setting_page_test.dart
```

Formal journey 检查实际 Provider、唯一用户 turn、单一 session、成功工具、文件内容、
ACP 完成及重启后历史/产物。重启后的 oracle 只验证持久化证据，不伪称模型自动重载。

## 补充：Qwen3.5 与 system 消息兼容

官方 AAR 自带 catalog 的 Qwen3.5 0.8B Q4_0：507,154,688 bytes，SHA-256
`444406ddd926550c724ec18d5120a9d40ded44908a063b0e66e9a7e5464c652c`。
已通过原生入口实际下载、官方 CPU 加载、非空回答、流式取消/恢复和 API 工具往返。
当前原生页可选择 Qwen3 或 Qwen3.5，组件始终为同一份官方 AAR 打包产物。

Qwen3 正式任务 `1789703055711` 超过 10 分钟未出现首条输出，journey 失败；
通过正式聊天的 Stop 取消并验证 canonical cancelled，升级后取消历史仍在。
请求形状为两条开头 system、一条 user，60 个工具定义约 40,246 JSON 字符，
`enable_thinking=false`。没有把等待解释为隐藏思考。

Qwen3.5 首轮正式任务 `1789704131206` 触发官方 C++ 的 SIGABRT：
`Unable to generate parser ... System message must be at the beginning`。
这与 Qwen3 长输入慢是不同问题。原始 crash 的必要片段已保留；没有重新发送该 turn。

修复在现有 `HttpAgentLlmClient` 的请求适配处：仅 loopback:9099 的 GGUF Chat
Completions 请求合并连续开头的纯文本 system 消息，按原顺序以空行分隔；
原 ChatCompletionRequest、用户历史、消息/工具身份不变。中途 system 不重排，
在进入 native 前明确报错。Responses 与其他 Provider 路由不变。
实际 HTTP 客户端回归先以 expected 2 / actual 3 失败，修复后 HTTP 客户端 35 项、
适配器边界 3 项全部通过。仍须以修复后的正式 Agent journey 判断端到端验收。

模拟器每次更新约 243 MB 的主 APK 需要临时安装空间。后续更新时清除了本轮可重建的
模型副本，并从主机端已校验的同一模型恢复；这是测试环境准备，不作为另一次远程下载。
文件复用/冷启动测试的 mtime 基线取自恢复后的最终安装状态。

最终候选主 App SHA-256 `ba6606d07452bd091f86a07b43a9ee57d31335c8e404b63027ec95c1f5d349ea`，
版本仍为工作区 0.6.3（用 APK 哈希区分实验构建），引擎不内置检查再次通过。
`scripts/verify-omniinfer-component-lifecycle.py emulator-5582 OUTPUT.json` 已执行：
重复启动、停止/启动、真正冷启动后的同模型推理都通过，组件与模型大小/mtime 完全不变。
见 `component-lifecycle.json` 及三个关联真实 inference 回执。
