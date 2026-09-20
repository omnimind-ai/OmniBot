# 对话驱动的长期回归测试索引

## 2026-09-17：手动录制入口与插件详情简化

去掉 OmniFlow “开始使用”卡片，底部入口明确为“手动录制与复用指令”。26 项 Flutter 回归通过；PJE110 真机确认卡片移除、入口跳转、两个标签页录制按钮显示。补充开始/暂停/取消流程未通过，失败保留；不宣称全流程已验收。入口 `scripts/verify-recording-entry.py`；见 [验证记录](recording-entry-20260917.md)。


## 2026-09-17：Function 手动编辑名称与描述

详情增加“编辑”，经统一 ExecutionBackend 复用 save_function，不调用模型。27 项 Flutter 回归通过；PJE110 真机空值、取消不保存、修改后详情/列表同步、保留数据重启、ID/来源/动作/参数不变通过。长期执行入口 `scripts/verify-function-metadata-edit.py --phase edit|restart`；测试输入驱动失败与最终证据均保留。见 [验收记录](function-metadata-edit-20260917.md)。


## 2026-09-17：执行中心四项定向修复

仅处理来源丢失、同 ID 覆盖、自动保存等待模型、取消与截图。PJE110 真机两次录制、重载/重装、真实增强工具成功、GUI 回放截图、动作前/后停止通过。canonical 117 项与 Android 74 项通过，组件 8 通过 / 2 跳过。自动保存约 2.1 秒；完整自然 GUI 收尾耗时补测受共享设备占用影响未完成，不宣称整轮固定耗时。可执行入口 `verify-execution-center-fixes.py`、`verify-execution-center-provenance.py` 及上游 compiler 测试；失败样本与尚未重跑的脚本分支保留。详见 [修复与真机证据](execution-center-four-fixes-20260917.md)。


## 2026-09-17：按需 OmniInfer 本机推理 Skill

按需 Skill，无常驻轮询。PJE110 手机源码构建、宿主安装、16K 本地模型真实 API 回复、流式取消、工具往返、停止/重启复用模型 PASS；12 项脚本回归及 39 项 Provider/插件市场/设置 Flutter 回归通过。新增安装按钮复用正式 ACP 对话，设置去掉执行中心；这两项 UI 变更待真机验证。正式 App 本地模型 Agent 任务、自然语言安装完整链路及离线尚未验收。详见 [验证记录](omniinfer-local-skill-2026-09-17.md)。

模拟设备 emulator-5582 已实际点击入口完成源码构建，系统安装经操作者接管后成功，模型下载及真实 API/工具往返 PASS。正式 App 本地 Agent 文件任务在原生计算阶段长时间未输出，正常取消，未通过验收。发现宿主模板覆盖上游 CPU 多版本优化配置为 OFF；已移除覆盖、增加 APK 优化库检查及完整解包缓存重建回归，当前脚本 **16/16 PASS**，优化版正在模拟设备内重建。后续必须用 `verify-omniinfer-phone.py --allow-emulator --require-optimized-cpu --tools --restart` 核实实际加载库，再运行 `omniinfer-local-agent.en.json` 与正式任务 oracle；优化修复待真机验证。禁止将历史基础 CPU 版本的 API 成功当作优化版或正式 Agent 成功。


## 2026-09-17：执行中心全操作真机扩展审计

用户要求检测是否全部可用，继续 PJE110 真机。新增 `verify-execution-center-controls.py`、`verify-execution-center-arguments.py`、`verify-execution-center-provenance.py`，覆盖真实取消、参数拦截/绑定、回放结果、canonical Function 关联、删除及新录制不得覆盖原指令。关联 bug 已修复并两次真机验证，36 项 Flutter 通过。增强来源丢失、同 ID 覆盖已由真机回归复现为失败；Agent GUI 第二轮真实成功，收尾延迟、停止状态差异与截图缺失仍需处理。不是全部功能稳定验收；完整范围、未运行项及证据见 [扩展审计](execution-center-full-audit-20260917.md)。

## 2026-09-17：Harness 性能与执行正确性

需求：减少逐页模型往返、批量处理与按需读取、保留模型/思考配置、独立读取有限并发；不添加第二个 ACP 生命周期或自研压缩算法。新增执行器回归覆盖并发上限、写入边界、乱序完成按 toolCallId 提交、取消后子任务释放、失败不重试、权限结果后已启动读取的真实提交，以及诊断不含任务正文。旧实现并发测试 `peak=1` 失败已保留。

执行入口：`AgentOrchestratorTest`、`AgentSystemPromptTest`、`python3 -m unittest discover -s scripts -p test_agent_performance_summary.py`；模拟器 UI 入口 `xiaowan-perf-bulk.en.json`、`xiaowan-perf-reads.en.json`，产物用 `assert-harness-performance.py` 对真实文件和哈希独立核验。原生全量 1108 项、并行改动定向 829 项、补 Python 修复后最终定向 835 项通过；GLM-5.1 同批三个真实读取、峰值并发 3 及重启恢复通过，GLM-4.6V 仍选择串行；首轮真实读取误走子 Agent 的失败保留，不以回复“完成”替代路径验收。最终设备结果和耗时见 [性能验收](harness-performance-20260917.md)。新增 `xiaowan-perf-python.en.json` 与 `assert-harness-performance.py --require-python` 覆盖标准库脚本不依赖 ensurepip、产物与重启；工具失败后模型最终误报“没有错误”的失败样本保留。用户指定模拟器，仍 **待真机验证**。

## 2026-09-17：全功能审计，重点 Agent 执行

用户指定模拟器，检查真实操作、错误处理、生命周期和模型误报。详见 [本轮审计](agent-full-audit-20260917.md)。复用既有 UI journey 与 canonical 历史断言，覆盖真实终端子进程取消、流式报错不关连接、半截工具不执行、工具失败/超时、连续五次取消、401/429/503/断流恢复、持久终端会话、技能发现、禁用定时任务创建/重启/更新/删除、命令菜单与权限面板返回。

新增可执行回归：`verify-provider-check-runtime.py`（已安装 Ubuntu 中的自检结果成功→失败覆盖）、`verify-provider-header-ui.mjs`（真实设置编辑、删除、重开和原生实际请求头）、`verify-settings-entry-ui.mjs`（12 个设置页进入/返回，仅入口 smoke）；`xiaowan-live-long-recovery.en.json` 复用原长任务后半段，避免前一阶段失败导致它永远未执行。Flutter 页面回归覆盖删除最后一条请求头，以及已脱敏的保存状态和清除入口。失败及测试前置错误不删除、不冒充通过；真实模型误建目录、恢复后报告没有错误仍未解决。全部外部 Harness、所有页内操作以及物理设备不在已通过范围，仍**待真机验证**。

## 2026-09-16：OmniFlow 包与薄注册层

- 需求：App 不依赖 OmniFlow 内部目录、工具 schema、安装及注册策略；保留录制、注册、执行、重放。
- 新回归：包迁移私有目录后启动与 Transfer ready、动态工具声明/默认可见性、空摘要缓存失效、无 AndroidWorld src 的 open_app、checkpoint 内容身份、标签几何、录制控件层级、截图证据、JSON authoring。执行入口与边界见 [omniflow-package-refactor-20260916.md](omniflow-package-refactor-20260916.md)。
- 原生 1326、Flutter 1292、canonical OmniFlow 276、包 9 项通过。`scripts/test_omniflow_device_lifecycle.py` 在 emulator-5580 最终六例通过：手动录制/语义注册、目标执行/自动注册、模型选 Function 重放、接管/停止/重启、插件启停/更新/卸载重装、非法写入保护。设备回归是实际模型/运行时/Android 操作，不是模拟结果。
- 用户指定模拟器范围，物理设备仍标为**待真机验证**。保留模型误报完成、作者提案被拒绝等此前失败证据；不把清理旧测试 Functions 后的一轮通过称为所有模型稳定可用。普通聊天和 RunLog 页面全部 UI 入口未完整自动化。

## 2026-09-16：README 产品亮点同步

需求：中英文 README 首屏突出 Kimi Code / DeepSeek Harness WebUI、Harness 切换与多智能体并行，并提供使用示例。执行入口：`node --test scripts/readme.test.mjs`。两个文档用例检查首屏关键能力与 Markdown 本地链接；2/2 通过。仅文档变更，无会话历史或生命周期变更；本次未运行功能真机验收，文档检查不代表运行时验收。


## 2026-09-15：共享会话自动重连与被动历史

补充：`scripts/verify-phone-lost-ack.cjs` 在真机丢弃真实 prompt ACK 后断开连接，T 暴露“后端一次、手机问题两份”的故障。复用官方 `clientUserMessageId` / `userMessage.clientId` 修复后，V 真机全程自动恢复、问答各一次、PID不变，PASS。增加相同文本不同身份不合并的 reducer 回归和适配器消息身份回归；388项相关 Flutter、3项适配器测试通过。官网和原生桌面仍是未完成验收项。

详见 [真机记录](shared-session-acceptance-2026-09-15.md)。PJE110 安装 0.6.3/code16 远程候选包：原页面断开 SSH 测试转发、电脑后端完成新轮次、恢复后手机自动补齐且不重启，后端每条问答一次，PASS。入口 `scripts/verify-phone-remote-reconnect.cjs`，必须显式指定测试转发 label、设备和隔离会话。持续被动实时接收及 Bridge 切换系统托管后恢复也 PASS。官网 WSS、蜂窝网络、原生桌面 GUI 和丢失 ACK 的完整验收仍未通过，不代表全部交付。


## 2026-09-14：官网 WSS 前置路由与4090中转

[实际验收记录](website-wss-ingress-2026-09-14.md)。新增 ingress verifier 及5项可执行回归，复现 `/codex` 已通但 `/health` 404 的入口缺口后修复；既有 `/fs/` 路由、鉴权及适配器版本查询一并验证。4090隔离Nginx实际4/8条ACP连接通过；PJE110真机经4090/Nginx发送与被动接收K/L/M通过，后台每轮一次。正式官网未部署，WSS公网TLS和蜂窝网络待真机验证，不能推断用户容量或最大连接数。测试入口 `scripts/test-agent-runtime.sh --bridge DIR` 与 `scripts/verify-bridge-ingress.cjs`。


## 2026-09-14：共享 Codex 的 ACP v2 候选与真机回归

完整产品仍 NOT ACCEPTED。详见 [验收记录](shared-session-acceptance-2026-09-14.md)。

- `ui/test/services/agent_event_reducer_test.dart`：真实 v2 状态、整条消息替换、重复/前缀 chunk、带历史时外部用户消息的最新在前顺序。顺序案例先失败后修复通过。
- `ui/test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart`：被动外部轮次结束，以及迟到旧轮次状态不结束新轮次。
- `app/src/test/java/cn/com/omnimind/bot/agent/runtime/RemoteCodexAppServerSessionTest.kt`：v2 后端身份保留及 v1 兼容边界。
- `scripts/verify-acp-shared-observation.cjs`，环境 `OOB_TEST_ACP_VERSION=2`、`OOB_TEST_SHARED_IDENTITIES=1`：两个真实适配器上的持续观察、问题+回复、真实身份、一次执行、回放、观察端发送后仍能观察。PASS。
- `scripts/verify-shared-external-send.cjs SESSION MARKER` + `scripts/verify-phone-shared-history.cjs SERIAL SESSION MARKER`：PJE110 真机 G/I 自动收到外部问题和回复，未通过重开来刷新。PASS。
- `scripts/verify-shared-interruption.cjs SESSION MARKER BRIDGE_PID`，环境 `OOB_TEST_PHONE_SERIAL=b49f281b`、`OOB_TEST_BRIDGE_PORT=17336`：实际手机发送 H 后生成中强制关闭 App，同一后端轮次继续且只执行一次；重新打开历史通过上述 phone verifier。PASS。
- 自动网络重连订阅尚未实现；原生桌面 GUI、官网 WSS/蜂窝网、丢失提交 ACK 和其他待验项没有通过，不能用本地 USB 反向转发或单元测试替代。


## 2026-09-09：新请求错误详情验收与测试环境恢复

最终：等待共用设备任务正式结束后进入新的 Codex 会话，以本地 GLM-5.1 实际运行 `1788960584626`，14/14 步通过：唯一命令的实际 182 失败及说明持久化、下一轮普通回复、重启复核。错误处理通过不等于沙箱通过；待真机验证。下方为此前环境恢复过程。

新增实际工具说明与退出码一致的 DETAIL journey，原沙箱 EXIT 验收不放宽；31 项 verifier 测试通过。原临时 AVD 数据目录丢失，发送前中止；已新建持久 AVD、安装候选包、确认本地 Provider，并通过 App 启动 Alpine/Codex 安装。新请求尚未验收，待真机验证。见 [记录](codex-live-error-detail-20260909.md)。

## 2026-09-09：ACP 命令失败详情

最终追加：解析收敛至共享工具解析器，旧事件与旧卡片空详情复用；217 项 Flutter、48 项 Node 通过。模拟器两次重启展开既有失败记录的 10 步全部通过；原始退出码显示恢复，无命令重放。最终 APK d1dc4920…，待真机验证；新发命令的实际显示仍待验证。详见下方记录的最终实现章节。

共享投影规范化 `exit_code` / `formatted_output`，已终止且缺少说明的终端工具显示实际退出码，官方状态不变。新增六项，reducer 共 206 项通过；APK 构建并保留数据安装成功，实际卡片和历史恢复待验证、待真机验证。见 [记录](acp-command-exit-detail-20260909.md)。沙箱本身未修复。

## 2026-09-09：Codex 182 最小描述符执行复现

追加实际第五项：直接按路径启动内置 bwrap 仍需经过 sandbox，返回 1 / overflowuid 读取被拒绝。它阻止“绕过描述符加载就算沙箱可用”的错误验收。五项对照结果已保存，产品修复未完成。

`verify-codex-sandbox.py --fd-exec` 在 App UID 上实际比较四种执行路径：普通 shell 与可继承描述符成功，不可继承描述符和 Codex 沙箱均返回 182。已定位 PRoot 加载兼容性，修复尚未完成，不修改权限绕过。见 [证据](codex-terminal-exit-20260909.md)。待真机验证。

## 2026-09-09：生命周期设置收尾

配置等待关闭锁后复查会话关闭、取消等待设置后释放等待且保持原锁所有权：新增 `XiaowanSessionAdmissionTest` 两项，连同 worker、关闭、删除和配置共 30 项实际通过，零跳过。本次仅增加测试，没有产品改动或新设备运行，待真机验证。见 [设置生命周期记录](xiaowan-session-config-admission.md) 及 `artifacts/lifecycle-settings-final-20260909/`。

## 2026-09-09：上下文圈默认零值

UI-CONTEXT-004：暂无用量时画 0 进度空环，不叠加问号；大小输入框保留长按阈值操作，有效用量按原数据更新。复用 `chat_input_area_test.dart`，兼容基线与历史确认卡合计 45 项通过；当前主线新工具链尚未就绪。安装与设备结果见 [验证记录](context-ring-zero-2026-09-09.md)，待真机验证。

## 2026-09-09：历史确认卡误报标识缺失

CODEX-HISTORY-REQUEST-003：历史确认卡展示已有结果，不将 requestId 缺失作为错误提示；失效输入不提示继续回复，缺少身份不能猜测已批准。`agent_request_card_test.dart` 新增三项；隔离合并前基线先失败、修复后 15 项通过。当前主线工具链/依赖阻塞，APK/模拟器重开待验证，待真机验证。详见 [记录](historical-request-notice-2026-09-09.md)。

## 2026-09-08：对话列表打开掉帧

UI-DRAWER-001：300 条合成历史首屏只构建可见范围，末行滚动可达；复用 `home_drawer_test.dart`，修复前 300 行挂载导致断言失败，修复后少于 30 行且滚动可达。20 项抽屉测试、1187 项 Flutter 全量测试及少量历史模拟器打开/重开/重启检查通过。大历史设备帧时间未测，待真机验证。详见 [记录](drawer-lazy-2026-09-08.md)。

## 2026-09-08：40 步统一模拟器测试集

CTX-SUITE-040：原图读取、连续长任务、实际自动摘要和重启恢复。统一入口 `scripts/verify-xiaowan-context-suite.mjs` 顺序复用既有 16 + 17 + 7 步 UI journey；数据生成器与准备、执行、恢复配置命令见 [测试说明](xiaowan-context-40.md)。缺步、失败、错误设备、缺少本次摘要请求或原图校验均不能通过，旧日志不能替代新运行证据。

状态：3 项入口判定测试通过；合成数据重复生成一致、PNG CRC 和解码长度验证通过；离线核对既有 40 步真实报告及 104 条 Provider 记录兼容。既有三组模拟器操作已通过，新增统一入口的完整设备重跑待执行，待真机验证。

## 2026-09-08：六项长上下文反馈复核

以 `context-six-audit-2026-09-08.md` 为本轮证据，纠正此前“最终回复丢失”的未证实判断。
新增可执行回归：`AgentContextOverflowTest`、`AgentConversationContextCompactorTest` 的低阈值/摘要模型容量、不可压缩 schema、超大摘要输入、跨工具统一落盘；`AgentOrchestratorTest` 使用生产压缩器验证真实报错文字、最多一次恢复及已开始输出不重放。
进一步复测发现历史已完成而页面仍旧的独立竞态：`conversation_manager_lifecycle_test.dart` 固定 metadata/history 等待期间当前 runtime 增加消息的场景。加载路径必须在 I/O 后读取当前 owner，不能安装 I/O 前的副本。保留本轮一次失败的 UI 记录，不把持久化成功冒充实时显示通过。
`xiaowan-context-overflow.en.json` 增加 `turn-outcome`，在可见回复、重启恢复、继续任务之间检查唯一用户输入、session/turn 身份及唯一 `end_turn`。
连续复测捕获会话 15 在压缩前恢复工具历史时 OOM：新增 `AgentHistoryToolOutputProjectionTest` 逐页处理 120 个大结果、完整 offload、调用/检查点身份、共享预算、落盘失败回归。`XiaowanAcpConnectionTest` 注入实际 server scope 致命错误，验证既有 connection exit 结束等待。上下文 journey 增加第三长任务和其后普通消息；fixture 用 ADB 读取实际 offload 原文校验，不能跳过内容检查。原始 OOM 和中间失败证据都保留。
`xiaowan-context-summary.en.json` 专门增长同一任务的助手内容，确认实际发出摘要请求。第一次 20 次读取完成但检查点断言失败：原查找在 ACP 异步投影落盘前返回 null，丢弃摘要的持久化机会。增加 Room 完成记录屏障及 `checkpoint awaits committed tool identity instead of dropping a summary during projection lag`，并要求检查点跨重启保持一致；最终状态见本轮报告，不用仅落盘工具结果的测试代替摘要验收。
状态：相关 JVM 测试与模拟器回归通过；原服务商长度单位仍未知，待真机验证。下方旧审计记录保留时间背景，不代表这些风险均已证实。

每次工作对话将用户的实际需求、故障与验收条件加入此索引，并链接既有可执行测试及证据。清单不等于已实现测试；模拟器通过不等于真机验收。原始聊天记录不作为可公开测试数据，样本应脱敏。

## 2026-09-07：小万读取文件与长上下文

更新：用户已授权先用 Kotlin 移植。CTX-001 至 CTX-005 的实现与新增可执行回归见 `context-compaction-fix-2026-09-07.md`；144 项本地测试与连续读取/重启模拟器回归通过，仍待真机验证。下表中“待实现/未修复”为最初审计状态，以该修复报告逐项描述的覆盖与限制为准。CTX-006 配置同步问题不在本次修改范围。

| ID | 用户场景及预期 | 已有入口或记录 | 当前状态 |
| --- | --- | --- | --- |
| FILE-001 | 大图读出后显示，模型收到原图，App 不退出 | `scripts/fixtures/agent-user-journeys/xiaowan-file-read-regression.en.json`；`image-read-crash-2026-09-07.md` | 模拟器验证通过；待真机验证 |
| FILE-002 | 大 HTML/文本分段读取，边界和末页准确，不全量撑爆内存 | 同一 UI journey；`file-read-memory-2026-09-07.md` | 模拟器验证通过；待真机验证 |
| FILE-003 | 工具结果重复字段不反复进入模型请求；重启后原始内容仍可查看 | `XiaowanToolResultPayloadTest`、`AgentEventAdapterTest`；`xiaowan-tool-result-dedup-2026-09-07.md` | 本地与模拟器验证通过；待真机验证 |
| LIFE-001 | 读取后取消，继续发送；重启恢复完成和取消状态；并行会话不串结果 | `xiaowan-emulator-acceptance-2026-09-07.md` 和其 artifacts 中各 journey/result | 模拟器验证含人工补测；不是全自动通过；待真机验证 |
| CTX-001 | 用户报告输入 1,119,534 超过服务上限 1,048,566；首次恢复即超限也应在发送前处理 | `AgentOrchestratorTest.providerPromptLengthRejectionTriggersOneCanonicalPreOutputCompactionRecovery`；`xiaowan-long-context-audit-2026-09-07.md` | JVM 请求拒绝 fixture 配合生产压缩器验证单次恢复；不是原服务商网络复现；原服务长度单位和真机仍待验证 |
| CTX-002 | 同一条用户任务中工具不断累积，能压缩已完成片段，保留任务及调用配对，不重放工具 | 同上；现有 `AgentOrchestratorTest` 尚不覆盖此边界 | 待实现回归用例；未修复 |
| CTX-003 | 服务不返回 usage，或一次/并行工具结果突然增大，仍能在下一请求前维护预算 | 同上 | 待实现回归用例；未修复 |
| CTX-004 | 摘要请求本身不超限；摘要失败、过长或取消，不错误提交检查点、不继续发送已知超限原文 | `AgentConversationContextCompactorTest` 有失败不提交及取消覆盖；其余见审计 | 部分已有测试；新增边界待实现；未修复 |
| CTX-005 | 小万子任务独立维护上下文，不能污染父历史 | 同上；现有子任务测试尚不覆盖压缩 | 待实现回归用例；未修复 |
| CTX-006 | 用户压缩阈值与模型容量区分；切换小容量模型、刷新配置、重启后保持正确 | 同上 | 待实现回归用例与真机复现 |

可执行文件读取 journey 由 `scripts/verify-agent-user-journey.mjs` 运行，测试 Provider 为 `scripts/fixtures/file-read-provider.mjs`，详细样本准备和命令见模拟器报告。它没有模拟 CTX-001 的服务端硬长度上限，不能替代长上下文测试。

本次对话更早的启动延迟、DSH 安装/沙箱/权限和阈值 UI 等反馈也应归档为长期用例；需核对其已有测试入口和实际验收记录，不能从聊天中的“通过”描述推定当前版本已验收。

## 压缩实现约束

下一步先评估可直接复用的成熟压缩组件，核实许可证、上游版本、Android/Kotlin 运行方式、多模型支持、同一任务压缩和持久化恢复契约。将上面的场景作为选型与集成的验收条件。

上游与历史核对结果见 `context-compaction-upstream-decision-2026-09-07.md`：Pi 为首选评估对象，尚未集成。旧版错误识别器的 21 条规则也不能匹配本次错误文字；该文字应作为 CTX-001 的固定回归输入，不能仅用另一种 `context_length_exceeded` 消息代替。

此前审计中的“分批摘要、预算检查、当前任务检查点”等是行为需求，不是授权自行设计另一套算法。没有完成复用评估前，不按该清单从零编写压缩引擎；如无法直接复用，记录具体障碍和方案后再讨论。

## 2026-09-07 上下文数据边界整体验证

配置/usage/模型能力分离，重复压缩、摘要异常结束、数据库检查点与去重分页、部分和空快照不删除历史。执行入口 `scripts/test-context-boundaries.sh`；详细结果及未完成项见 [context-boundaries-2026-09-07.md](context-boundaries-2026-09-07.md)。待真机验证。

- 2026-09-08 后续上下文审计：`context-audit-2026-09-08.md`。新增工具结果文件化后检查点身份、明确清除后迟到快照两项可执行测试，均已运行并失败；尚未修复，待真机验证。


## 2026-09-08 检查点与记忆恢复

上述审计失败是修复前状态。修复及执行证据见 [memory-recovery-2026-09-08.md](memory-recovery-2026-09-08.md)。

| ID | 用户场景与预期 | 可执行入口 | 结果 |
| --- | --- | --- | --- |
| CTX-007 | 文件化结果仍定位同一工具组；展示、回写和重启不丢身份 | AgentConversationHistoryRepositoryTest、AgentConversationHistorySupportTest；xiaowan-checkpoint-restart.en.json | 定向测试及模拟器 11 步通过；待真机验证 |
| CTX-008 | 清除或新摘要后，旧快照和迟到摘要不能覆盖检查点 | ConversationCheckpointTest | SQLite instrumentation 通过；待真机验证 |
| HIST-001 | 长历史只加载有限展示正文，预览回写不能覆盖完整记录 | AgentConversationHistorySupportTest、AgentConversationHistoryRepositoryTest；两组 UI journey | 定向测试及原累积会话模拟器恢复通过；待真机验证 |
| MEM-001 | 长期和每日记忆可写入、检索，重启后仍能读取 | AgentSystemPromptTest、*Memory*Test；xiaowan-memory-restart.en.json | 定向测试及模拟器 7 步通过；真实模型召回质量未验证；待真机验证 |

统一测试入口 scripts/test-context-boundaries.sh；UI 入口 scripts/verify-agent-user-journey.mjs。使用合成独立标记及本机 Provider，证据保留失败与最终通过记录，不复制私人记忆正文。

## 2026-09-08 DSH 沙箱验收

DSH-SANDBOX-001：默认 read-only / workspace-write 应能执行受限命令，不能用普通 shell 或完全访问成功替代。新增可执行入口 scripts/verify-dsh-sandbox.py，直接加载设备已安装官方沙箱实现；两次均实际运行并失败（SANDBOX_UNAVAILABLE）。此入口仅覆盖后端就绪必要条件，完整隔离/UI/重启测试尚未完成。见 [验收报告](dsh-sandbox-acceptance-2026-09-08.md)。待真机验证。

## 2026-09-08 Codex Plan 无输出

CODEX-PLAN-001：声明 Plan 能力后，官方计划快照必须通过 SDK 解码并进入共享展示；计划删除使用相同身份。先复现 MissingFieldException，再通过 AcpSessionUpdateMapperTest 的计划形状、幂等和无关字段回归。真实官方 CLI 的 plan 场景已实现并在两种 Provider 配置运行通过；手机完整 UI/重启验收待执行，待真机验证。见 [报告](codex-plan-output-2026-09-08.md)。

Codex Plan 补验：用户指定模拟器验收，实际 UI 27 步（计划、拒绝实施、重启恢复、Default 回复与再次恢复）通过。新增 CODEX-PLAN-002：已拒绝确认重启后不得出现可操作按钮；入口 codex-plan-resolved-approval.en.json，已运行并失败，未修复。两类结果分开记录，见 Codex Plan 报告。

### 2026-09-09 文件搜索上限与取消传播

- 用户反馈：读取附件后搜索工作区中断，后续任务显示失败。此次只确认并修正搜索遍历上限与取消传播，不把截图中断原因推断为内存溢出。
- 可执行测试：`app/src/test/java/cn/com/omnimind/bot/agent/tool/handlers/FileSearchTraversalTest.kt`，已纳入 `scripts/test-agent-runtime.sh` 的 Android 测试入口。
- 覆盖：达到上限后不枚举下一个文件、非匹配项不消耗上限、中文/空格路径、空结果、最后一个结果产生时取消、读取取消异常传播、致命错误不被当作无匹配、取消后独立操作、40 次重复操作无残留。
- 执行结果：使用本机 Kotlin 编译器编译生产 traversal 与上述 JUnit 测试，Android Studio JBR 运行，6 项通过。该结果不包含完整 Android handler 编译或设备操作验收。
- 尚未完成：成熟搜索程序接入、文件名/正文能力拆分、长行/二进制扫描、大目录模拟器端到端回归；待真机验证。现有未指定 maxResults 的完整结果语义暂未修改。
- 补充执行：`bash scripts/test-agent-runtime.sh --offline --skip-gradle --skip-flutter --skip-webchat`，80 项 Node + 7 项 Python 通过；真实 Provider、Harness、Android/Flutter 和真机验收均未包含在该命令内。

### 2026-09-09 小万命令菜单与构建版本对齐

- 当前 `XiaowanAcpConnection.postInitialize` 只通过官方 AvailableCommandsUpdate 发布 compact。但继续追到前端确认 init 是明确标注的“生成或更新 AGENTS.md”提示词快捷操作，通过普通 prompt 发送，并非原生命令。菜单用例仍应检查 init；曾改为 absent 的误判已撤回。plan 按声明模式显示，review 按声明命令显示。
- emulator-45562 的已安装兼容包（versionName 0.6.2.2 / code 14 / target 35）运行旧 6 步用例通过，显示 init。该包与当前源码不一致，不能用这项成功宣称当前源码验收通过。菜单显示 init 本身并不证明版本差异；该包是兼容包的结论来自其构建基线。旧用例证据保留于本机 /tmp/oob-command-capabilities-20260909，未将私有截图入库。
- 同一菜单用例仍需在当前源码对应 APK 上运行；真机验收未完成。

### 2026-09-09 ACP 取消结束结果在背压下丢失

- 复现：原代码在已取消 worker 内 send CANCELLED 并 runCatching；慢消费者/无缓冲 channel 下只收到 running，send 抛 JobCancellationException，被吞掉。缓冲充足时收到 cancelled，因此具有时序相关性。该协程复现尚不能证明所有用户失败都由此引起。
- 修复：保持 Conversation -> ACP Session -> Turn -> Item 及现有 promptMutex / activePromptJob；工作协程只执行和记录取消，存活的 prompt collector 在 join 后发送原来的官方 PromptResponse(CANCELLED)。没有 NonCancellable 无限发送、私有终态或第二次执行。
- 可执行入口：XiaowanPromptWorkerTest，已加入 scripts/test-agent-runtime.sh。4 项本机 JVM 测试通过，包括 40 轮 x 两种缓冲设置的取消/后续任务、普通完成、工具取消异常、接收端断开。
- 限制：完整 Android 构建、模拟器安装回归与真机验收仍待执行；小万取消当前 turn 不等于暂停并恢复同一个执行栈。
- 回归敏感性核验：将相同测试临时运行于旧 worker 内发送/吞异常逻辑，4 项中 1 项失败（缺少 cancelled）；恢复生产修复后 4 项全通过。临时旧逻辑未写入仓库。
- 后续边界：已新增“执行 worker 尚未启动即停止”的用例。修复前未返回任何结束结果，修复后通过；最终 5 项 JVM 测试通过。
- 真实 Provider 补充：使用本地已有 LLMTHU 配置执行 `node scripts/agent_provider_smoke.mjs`，GLM-5.1 模型目录和真实 completion 通过；这是单次真实连接证据，不覆盖所有请求、拒绝、额度和超时场景，也不替代 App 端到端测试。
- 构建环境补齐：从 Flutter 官方 3.47.2 tag d3b14c876900e553bc736ca19295fc09e3853e8e 安装独立 SDK，已安装 Android platforms;android-37.0 与 build-tools;37.0.0；当前完整构建仍未完成。
- 当前源码 Flutter 验证：Flutter 3.47.2 下运行 agent_event_reducer_test、chat_conversation_runtime_coordinator_test、agent_slash_commands_test，共 296 项通过。未使用旧兼容 SDK 或 APK 替代本次源码验证。
- 补充会话边界：XiaowanPromptWorkerTest 现为 7 项通过，新增跨会话重复停止隔离、执行错误不被改写为取消/成功。当前 Flutter 权限卡片/输入状态/错误格式/终端输出四组测试 21 项通过。

### 2026-09-09 重复停止与恢复的设备回归入口

- `scripts/fixtures/agent-user-journeys/xiaowan-repeat-cancel-recovery.en.json`：43 步，五轮流式输出等待/停止/官方取消终态/下一条成功，随后重启并检查两种终态的历史。执行入口为既有 verify-agent-user-journey.mjs，配套既有 file-read-provider.mjs 的 WAIT/OK 故障分支。
- 服务端不会在 WAIT 中伪造完成；流关闭时释放定时器并记录 completed=false。重复用例保留完整轮次和运行标记，避免旧结果冒充当前请求。
- 已执行：新增真实本地 HTTP fixture 测试 2 项通过；持久化终态 verifier 测试 11 项通过，拒绝缺少官方取消原因、仍 loading、错误被当作取消。
- 43 步设备用例尚未执行，不计为模拟器或真机通过。Android JVM 构建已完成 Gradle 官方 SHA-256 校验后重新启动，仍在进行。
- 取消出口统一：执行器返回 AgentResult.Error(CancellationException) 的兼容路径也交给 prompt collector 收尾，移除了 worker 内直接发送取消结果的第二处代码。完整 Android 构建已进入模块编译阶段，最终结果待确认。

- 最终本轮验证：当前源码完整 Android 73 项测试通过，APK 构建及 emulator-45562 覆盖安装成功；43 步重复停止/继续/重启回归全部通过。Provider 日志确认 10 个唯一请求、无重复发送、5 个取消流关闭。已恢复原有 GLM-5.1 场景绑定并关闭本轮故障服务。证据见 artifacts/lifecycle-cancel-20260909/。该结果仅覆盖本轮取消链路，文件搜索完整改造、其他失败类别的设备回归与真机验收仍未完成。

### 2026-09-09 工具失败恢复与接口错误回归

- 当前 APK 的工具失败 18 步模拟器回归通过：未知工具、坏 JSON 参数、不存在文件、真实 exit 7、真实命令超时，以及重启后完成状态。证据位于 artifacts/tool-failures-20260909/。增强持久化校验，不能用权限错误冒充文件不存在或参数错误；15 项 verifier 单元测试通过，五个设备 turn 重新校验通过。
- 接口错误回归首轮在第 19 步失败：文本选择浮层消费了发送点击，草稿保留；SQLite 查询证实该合成 marker 未被接收，服务端无请求。保留 first-run-failed.json，未计为完整通过。
- 测试驱动改为发送前检测并关闭实际可见的 composer 选择浮层，仍只点击一次 Send，不重放已提交消息。2 项 XML/浮层识别测试通过。仅清除已确认未提交的本轮合成草稿，使用新的运行标记重跑全部接口用例；前端产品代码未因此修改。

- 后续整套接口回归仍在第 19 步失败，保留 second/third/fourth-run-failed.json。发送前布局稳定检查、保留 IME 均未解决，因此此前将问题完全归因于测试驱动的判断不充分。额外 UI 观察中，Android Back 确实会在无 IME 时退出 Activity，驱动已移除无条件 Back，并记录发送前可访问性位置。
- 限流后的合成草稿经额外人工定向 UI 点击后实际入库、完成文件读取并得到 end_turn；没有重新提交已接收消息。这仅证明会话未永久锁死，不能冒充一次点击发送/整套回归通过。该输入区问题继续待定位，证据 manual-rate-recovery.json。
- 离线 Node 83 项、journal verifier 15 项通过；补充 ACP connection/presentation 46 项 Android 测试通过。其他 Harness/真机验收仍未完成。
- 将既有 provider-failures 用例第 22–45 步原样提取单独运行：服务端 503、输出前断流、部分输出后断流，每类错误后执行真实 file_read，再重启验证，共 24 步通过。对应三个错误请求均未重放。证据 remaining-journey.json/remaining-provider-events.json；这不等于原 45 步整套通过。
- 当前实际 APK 的命令菜单 6 步验证通过：显示 init/compact、不显示未支持的 plan/review，清理本轮 `/` 草稿。菜单证据 command-capabilities.json。最后核对恢复原 GLM-5.1 dispatch/compactor 场景绑定，终止本轮测试 Provider；物理设备验收仍待进行。

### 2026-09-09 输入框隐藏选择手柄吞掉发送点击

- 修正此前推断：限流后发送失败的直接原因不是 ACP 取消/重试。独立 7 步用例、发送前截图和全局命中日志证明：相同坐标在成功时命中按钮，失败时命中文本选择手柄 Overlay（RenderExcludeSemantics/RenderAnimatedOpacity）；失败点击未进入按钮或聊天页指针回调。隐藏手柄仍接收触摸，特定草稿长度使它覆盖发送按钮。
- 上游核对：Flutter 3.47.2 `widgets/text_selection.dart` 的 `_SelectionHandleOverlay` 使用 FadeTransition；官方文档明确透明度为零不会禁用命中：https://api.flutter.dev/flutter/widgets/FadeTransition-class.html 。不改本机 SDK，不新增 Agent 生命周期或自动重发。
- 最小应用修复：共享 composer TextField 的 onChanged 通过公开 TextSelectionGestureDetectorBuilderDelegate / EditableTextState.hideToolbar 移除旧选择覆盖层。下一次长按仍恢复正常选择/复制菜单。临时诊断代码已全部移除。
- 可执行回归：`ui/test/features/home/pages/command_overlay/widgets/chat_input_area_test.dart` 新增隐藏手柄测试（旧实现红、修复后绿），包含连续三次输入→发送→再次长按选择/复制菜单；全部输入区 31 项通过，重复用例通过，定向分析无问题。
- 设备入口：`node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/xiaowan-selection-overlay-recovery.en.json /tmp/oob-selection-overlay-run`。原 provider-failures 45 步正在新修复 APK 上运行，尚未计为通过；证据目录 artifacts/selection-overlay-20260909/。待真机验证。
- 最终设备结果：修复 APK（SHA-256 a51940aef8e71f92d04dbf1b13157048c7d8e8d1df2039966e871c44e6d844f6）在 emulator-45562 完整 45 步通过，包括原先连续失败的第 19 步一次发送、6 类接口失败后的真实文件读取及重启历史。服务端验证 6 个失败请求均仅一次、6 个恢复工具结果均有效。证据 after-fix-journey.json/after-fix-provider-events.json；仍待真机验证，未发布。

### 2026-09-09 真实 API 多工具长任务的环境与终态检查

- 首次真实任务未执行任何工具，正式 stopReason=error：旧 ACP 会话仍使用测试服务地址 10.0.2.2:18879。全局 scene binding 恢复不等于覆盖会话内模型选择，保留失败证据 initial-route-failure.json，未重放该轮。
- 模拟器系统时间停在 2023-10-16、auto_time=0，真实服务商模型目录出现 TLS 校验失败。仅校正测试模拟器时间并开启自动校时后，真实服务商目录成功加载 95 个模型；未关闭或降低证书校验。通过 App 模型选择入口选中 GLM-5.1，并再次打开设置验证。
- 加强现有 xiaowan-local-api-long-task 用例：每次完整回复及重启后都要求 canonical end_turn，再检查实际工具结果；真实网络用例运行前要求设备时钟与宿主相差不足五分钟。验证器支持带唯一结束指令的真实多段任务，并保留唯一用户消息、同一会话/轮次、无 loading/error 和正式终态要求；18 项验证器测试通过。
- 16 MiB 合成 HTML 已在测试工作区按哈希校验，后续要求连续 20 页读取。新的真实任务正在执行，尚未计为通过；证据目录 artifacts/real-api-lifecycle-20260909/。待真机验证。

- 实际 GLM-5.1 任务结果：文件操作阶段 11 次真实工具调用通过；16 MiB HTML 连续 20 页的偏移和结果通过，共 21 次工具调用，正式完成和重启恢复均通过。这不证明自动压缩已触发。原整套流程在重启后的登录弹窗处停止，保留 initial-journey.json，不计为整套通过。
- 关闭测试机登录提示后，独立恢复用例实际完成 5 次工具调用：报告读取、不存在文件、exit 7 及后续 exit 0 均通过。但最终回答缺少结束标记，持久化末条文本仅 244 字符，isFinal=false、无 stopReason，界面已显示 Send。完成断言失败，后续重启未执行，不重放已提交任务。原因仍待定位；证据 recovery-journey-failed.json / recovery-observation.json。

### 2026-09-09 MCP 请求超时与用户取消分离

- 发现 Stdio MCP 的 request 使用 withTimeout，自己的 40 秒期限抛出 TimeoutCancellationException，沿工具调用的 CancellationException 分支传播，可被误当作用户取消。红测试复现异常类型错误。
- 在原请求边界使用 kotlinx.coroutines.withTimeoutOrNull，仅本请求到期转换为 java.util.concurrent.TimeoutException；父协程取消和显式 CancellationException 原样传播。不增加 Agent loop、重试或终态。
- 新增 McpRequestTimeoutTest：自身超时后可继续请求、外层超时仍取消、显式取消不转换。与 XiaowanPromptWorkerTest 合计 10 项通过，完整 APK 构建通过。MCP 真实 stdio 超时路径尚未设备验证，待真机验证。
- APK /tmp/OpenOmniBot-0.6.2.2-mcp-timeout-20260909.apk，SHA-256 0b56bf670b9831fd082740af869cccc0ca8bfb5149a91c549aeddd5203850d71；尚未安装，不用构建成功代替设备验收。

- 独立第二轮真实 API 恢复回归 7 步通过（marker OOB_LIVE_RECOVERY_1788896917584）：工具实际失败和恢复、正式完成、重启后完整回答与 end_turn 均通过。日志确认最终模型文本 1056 字符、finish=stop，继而收到官方 PromptResponse(end_turn)。首轮半段回答的原因未确定，不能用重复通过宣布其已修复。证据 repeat-result.json / repeat-completion.log。

- MCP 修复 APK 已覆盖安装至 emulator-45562，启动及 6 步命令菜单回归通过（init/compact 显示，plan/review 不显示）；升级后的真实恢复任务历史 end_turn 校验通过。证据 artifacts/mcp-timeout-20260909/。以上仅是安装/菜单/历史验收，MCP stdio 请求超时的设备验收仍未完成，待真机验证。

### 2026-09-09 ACP 授权详情丢失与确认文案

- LocalAcpRuntime 将传入的 ToolCallUpdate 重建成仅包含标题、in_progress 和选项名称的正文，导致真实 rawInput/content/locations/kind/status 丢失。改为直接使用现有 ACP SDK 0.30.1 serializer，选项仍保留在官方 options 字段，无新协议或前端改造。
- 高权限工具等待同一请求的授权选项时，旧文案却提示另发“确认/取消”。已改为选择本次授权请求的允许/拒绝选项。
- AgentRuntimeProtocolPayloadTest 71 项通过，包含操作详情保留、缺失字段不伪造；现有 Flutter 权限详情展示测试通过。构建中；emulator-45562 未安装 Shizuku，实际高权限授权交互尚未验证，待真机验证。证据 artifacts/permission-details-20260909/。

- 授权详情修复 APK 构建完成并覆盖安装成功，SHA-256 b23c94b9d31b82facc10e2a1dfe9274317967335b620c696109b87ad09dee3ac。实际授权卡片的允许/拒绝/等待中停止仍未设备验收；不能以安装通过替代该验收。

- 已在隔离 emulator-45562 安装并按官方命令启动 Shizuku 13.6.0，实际系统授权弹窗允许 Omnibot 后，App 显示 Granted(root)。此前未安装条件已解除；仍无物理真机。
- 本地真实 GLM API 的授权拒绝用例 5 步通过：请求一次 android_privileged_action(shell.exec, id, confirmed=false)，卡片完整显示命令及新确认提示，实际点击拒绝，模型正常回复并保存 end_turn。额外 journal 断言确认恰好一次工具调用、明确用户拒绝、无重试或替代执行。首次断言因测试误写 shell_exec 失败，依据实际官方 action shell.exec 修正后通过。
- 用例已纳入现有 journey 框架并补充第 6 步工具结果断言；本次运行的是前 5 步，额外断言单独实际执行。允许、等待授权时停止及重复/重启仍待验收。证据 permission-details-20260909/deny-journey.json、deny-card.json、shizuku-setup.json。

- 真实 API 的等待授权时停止→下一轮允许→重启回归 12 步通过。停止轮授权卡为 interrupted，正式 cancelled；允许轮仅一次 id 操作，原始 rawOutput.result 确认 command=id、exitCode=0、stdout 含 uid=0，正式 end_turn；重启后两轮终态仍成立。附加执行结果断言单独通过。证据 stop-allow-journey.json / stop-allow-cards.json。
- 测试入口新增当前轮 permission-pending 校验，防止同名历史卡片提前满足等待条件；取消终态要求不再保留可点击 pending 请求。22 项 verifier 单测通过，当前停止轮追加严格断言通过。设备本轮运行的是原 12 步版本；新增等待条件尚未完整重跑。
- 同时发现允许后的展示 args 被进度字段替换，modelAssistantMessageJson 为空，不能据此断言原始模型参数保存完整；当前实际执行由工具原始结果确认。该历史保真问题继续检查，未宣布已修复。仍待真机验证。

### 2026-09-09 工具进度不得覆盖输入

- 实际允许 id 命令后，journal args 从 action/arguments 变为 backend/command/availableActions。源头为 XiaowanAcpEventBridge.emitToolProgress 将 extras 写入 rawInput。ACP 官方 tool-calls 定义 rawInput 为输入、rawOutput 为输出，稀疏更新应只包含变化字段：https://agentclientprotocol.com/protocol/v1/tool-calls 。
- 修正为 progress extras 使用 rawOutput，进度更新不再填写 rawInput；沿用现有 reducer 对 structuredOutput 的处理，不修改前端业务逻辑。
- 新增 Kotlin 回归在旧实现下失败（42 项中 1 项失败），修复后 42 项全通过。新增 Flutter 测试验证连续三个进度、完成及 JSON 恢复后 args 不变、终端输出更新；已通过。APK 构建并覆盖安装成功，新的严格真实 API/重启回归正在运行，不能提前计为设备通过。证据 artifacts/progress-input-20260909/。
- 另对历史仓库“空字符串旧字段覆盖后来的 canonical 消息”增加复现测试，尚待运行结果；不将其等同于此次参数覆盖的已证实根因。待真机验证。

- Git 当前可见历史定位：进度写 rawInput 出现在 8203f5a42（2026-08-28，统一 ACP runtime 提交）；preserveFullToolPayload 出现在 8c16e38d9（2026-09-08，本地修复快照）。这说明对应代码进入当前路径的提交，不推断更早路径或已发布 APK 的全部行为。
- 仓库空字段复现：AgentConversationHistoryRepositoryTest 7 项中新增用例失败；修正为只有旧非空值才覆盖 incoming，从而保留完整旧消息，同时允许空占位补全。绿测运行中，尚未安装该额外修复。

- 严格真实 API 设备回归在第 12 步失败：本轮等待授权和停止、下一轮当前权限确认、允许点击及正式 end_turn 均通过，args 保留原始 action/arguments/confirmed=false；但 Shizuku 工具实际返回 service_bind_failed（Failed to bind the Shizuku user service），success=false，真实命令成功断言因此失败，未执行后续重启。不将模型正常完成或参数保留冒充工具执行成功，继续排查升级后后端绑定。
- 历史空占位修复 7 项绿测通过，已生成包含两处修复的新 APK（SHA 982d6711b27440203975c28748b5ee0531d5d088497a1ac1a5c952c02809dfe2），尚未安装，避免改动新失败的现场。

### Shizuku 冷启动被提前判定失败（2026-09-09）
- 真实 API 授权后工具失败：服务启动 20:28:13.883，App 收到连接 20:28:18.017，耗时 4134ms；本地只等 3000ms，官方服务端日志允许 30000ms。原始证据 artifacts/shizuku-binding-20260909/before-bind.log。
- 保留现有 ShizukuCapabilityManager 所有权，连接等待对齐上游 30 秒，协程取消仍可立即结束等待，不重放工具操作。
- 执行入口：`:baselib:testDebugUnitTest --tests '*ShizukuServiceBindingTest'`（已加入 scripts/test-agent-runtime.sh）。覆盖 4.2 秒冷启动、取消后连接可用、有界超时及晚到回调。旧 3 秒实现下新增冷启动用例确实失败；绿测及升级后真实 API 流程待运行。全部待真机验证。
- 测试前置修复：AccountApiClientTest 的 StubCall 对齐当前 OkHttp 5.5 的新增元数据接口，执行仍由测试响应替身控制；该类一并回归。
- 绿测 14 项全部通过，APK 覆盖安装成功（SHA 2b11eef4d2ad2e7924f72e860e3f8dd82c74968e409afa34b1d0a734dcf35c3d）。第一轮真实 API 的 16 步全部通过，含当前授权等待停止、下一轮允许真实 id 命令、原始参数保留、正式 end_turn 和重启恢复；冷启动 1597ms，超 3 秒边界由 4.2 秒单测覆盖。
- 第二轮不能计为通过：命令实际成功且只有一次调用，官方 ACP 于 20:49:02.502 返回 end_turn，但历史授权卡缺 streamMeta，助手回复仅保留 64 字符、isFinal=false；回复门禁失败后未重启。这再次复现此前的部分回复/终态丢失症状。保留 repeat-journey-failed.json、repeat-official-completion.log、repeat-history-observation.json；继续追查历史快照保存和事件投影，不能将其误归类为 Shizuku 或模型未完成。所有修复待真机验证。

### 授权卡元数据与历史加载所有权（2026-09-09）
- 针对重复回归 1788900300617：授权卡确认通过 upsertUiCard 只提交卡片字段，旧实现把未提交的 streamMeta 一并抹掉。新增 permission response patch 测试先失败（40 项中 1 项），改为沿现有仓库卡片合并保留缺省元数据后 40 项通过；显式新元数据仍生效。
- 发现并复现另一个入口错误：ChatPage.messages 指向 runtime.messages，ConversationManager.loadConversation 在 onConversationLoaded 之前 clear/addAll，绕过共享协调器。新增延迟 forced-refresh 用例先失败，删除这两行后历史/协调器/保存服务 135 项通过；恢复与分页仍由原入口完成，没有新增 reducer 或生命周期。
- 证据 artifacts/history-owner-20260909/。此时仅源码与可执行回归通过，APK/设备复测尚未完成，最终文本丢失不能提前宣称解决，全部待真机验证。
- 新 APK 构建/覆盖安装成功（SHA edc60c5e04a2c57a6a3909c89e72fb3176f957915c754e5ccd0f0dbcc1010ead）。首次 UI 尚未就绪，发送前空 root 失败已保留；界面就绪后的独立轮次 1788901464879 全部 16 步通过，重启后回复 238 字符、isFinal=true、end_turn，授权卡元数据保留。
- 第二轮 1788901753079 仍未通过：实际 id 成功且单次调用，授权卡元数据正确保留；官方 21:13:12.558 返回 end_turn，但助手落库仅 89 字符、isFinal=false、无 stopReason，未进入重启步骤。说明两处入口修正并未涵盖最终文本丢失全部原因。保留 repeat-history.json 与失败 journey，继续追踪具体写入/投影顺序，不能宣称全生命周期已验收。静态分析有既有 unnecessary_null_comparison 警告（conversation_manager.dart:792）。

### 完成结果与异步保存时间窗口（2026-09-09）
- 诊断版证据：21:23:44.507 运行时为 238 字符、isFinal=true、end_turn；44.530 较早启动的保存仍发送 199 字符非最终快照，44.541 写入数据库；后续 46.139 补全。两轮带诊断日志的真实 API 流程均通过，不能当成旧回退问题消失，也尚未捕获具体失败的后续重装顺序。证据 artifacts/completion-write-20260909/probe-order.log。
- 可执行用例：chat_conversation_runtime_coordinator_test 中 persistence uses the completed projection after awaiting metadata I/O。人为延迟 metadata 写入，期间流式补全并接收正式 PromptResponse，旧实现随后仍发送 partial，断言失败；新实现同一保存队列在 I/O 返回后、runtime/代际一致时读取最新消息，100 项协调器测试通过。没有新增 reducer、状态机或保存队列。
- 临时诊断代码已从源码移除，正在构建/完整回归；修正版设备验证尚未完成，全部待真机验证。
- 仅取快照时机修正的 APK（SHA 971cccdae90133f66959d8c829f729cc21fcf76a0348e4a9ebfd8d3797f3684f）真实 API 第一次仍在最终回复门禁失败，命令成功，第二轮没有启动。该修正不能单独视为问题闭环。
- 补充直接复现：官方 PromptResponse 完成后，replaceConversationSnapshot 对同 ID 的旧部分消息原样替换，导致已完成消息降回未完成。新增 an old history snapshot cannot downgrade an officially completed item 测试先失败；沿同一协调器按已保存 stopReason 保留提交终态，无终态/缺元数据旧副本不能降级，完整终态历史仍可补充 usage。137 项测试通过，正在构建/设备复测；没有改变普通历史排序或增加生命周期推断。

- 最终无诊断日志 APK（SHA 9600433c7e2c42b02db231c53e857a3b044cdaca687fdbeb114140b9542cfa8f）覆盖安装成功；emulator-45562 使用 LLMTHU GLM-5.1 (Debug)，连续两轮 1788903827637 / 1788904098770 各 16 步全通过：当前授权等待停止、新轮授权、真实 id 单次成功、原参数保留、官方完成、重启后取消/完成状态保留。结果归档 terminal-history-first-journey.json 与 terminal-history-repeat-journey.json。137 项本地测试通过；静态检查无 error，但有 7 warning / 1 info，不记为全量 lint 通过。仅证明此回归范围，未覆盖所有长任务和 Harness；无物理设备，待真机验证。

### MCP 静默进程关闭顺序（2026-09-09）
- 发现 stdio close 在 destroy 子进程前调用 BufferedReader.close；readLine 正等待 stdout 时，两者竞争同一锁，关闭可能一直等待。真实 `sh -c exec sleep 30` 子进程回归先复现 2 秒关闭门禁超时，终止进程提前后通过；同一回归还检查重复 close。
- 现有 XiaowanStdioMcpConnection.close 调用经过测试的清理函数；清理在 IO 上执行，进入资源释放后不被调用者取消打断，不增加业务生命周期。
- MCP 清理、请求超时分类、Prompt worker 取消共 11 项测试通过，入口已加入 scripts/test-agent-runtime.sh。证据 artifacts/mcp-cleanup-20260909/。尚未验证 Android App 的真实 stdio 服务，待模拟器及真机验证；不能将主机 JVM 子进程测试算作设备验收。

### HTTP/SSE MCP 停止与权限拒绝（2026-09-09）
- HTTP 阻塞 execute/正文读取没有连接协程取消；真实 TCP MockWebServer 延迟响应头的旧实现超过 1.5 秒取消门禁。修正同一请求范围中取消 OkHttp Call，保持正文和 SSE 消费期间的取消绑定。
- 连续测试又暴露 tools/call 把 401/403 当成会话失效并重新发现/调用；现在只允许已有 session ID 的 404 走既有恢复，其他拒绝直接报告。依据 MCP 2025-11-25 transports session-management；没有更改鉴权配置或新增重试层。
- 22 项通过：HTTP 响应头/正文卡住取消后继续、SSE endpoint 等待取消重复两次、401/403/429 后新请求成功且无隐藏重放，以及既有协商/404 恢复/取消回归。长期入口 scripts/test-agent-runtime.sh 已包含 RemoteMcpClientInteropTest。证据 artifacts/http-mcp-cancel-20260909/。
- 这里是主机 TCP 集成测试，非 Android App 验收；待模拟器及真机验证。本地请求停止不等于远端工具已停止，协议取消通知与真实服务行为仍待核验。

### 按协议版本发送取消（2026-09-09）
- MCP 2025-11-25 使用 notifications/cancelled；2026-07-28 HTTP 以关闭请求流作为取消信号，不能盲目给新版发送旧通知。参考官方 cancellation 规范及 TypeScript SDK 2026-07-28 migration。
- 修正旧版 HTTP：在已执行的请求因调用者取消退出时，沿原 endpoint/session 发送一次不带 id 的通知，引用原 requestId，不重新初始化；最多等待 1 秒。initialize 不发取消通知。另修正现有 notifications/initialized 错带 id，新断言先复现失败。
- 24 项通过，含两次取消/继续、取消通知端点不响应、初始化取消边界及既有 HTTP/SSE/权限拒绝回归。执行入口沿用 scripts/test-agent-runtime.sh，证据 artifacts/legacy-mcp-cancel-20260909/。
- 本次只补齐旧版 HTTP；旧版 SSE 的远端取消通知仍待实现，不能称所有远端任务已可停止。主机 TCP 测试仅证明通知到达，不替代实际远端工具终止、模拟器和真机验收；待真机验证。

### 旧版 SSE 原请求取消与连续操作（2026-09-09）
- 新 TCP SSE fixture 完成 endpoint/initialize/tools 握手后，旧实现本地取消结束但远端未收到通知，red.xml 保存失败。使用项目已有 MockWebServer 依赖；最初 JDK HttpServer 测试编译失败不计为 bug 复现。
- 沿现有连接记录原消息端点和工具 requestId；POST 与 SSE 等待由外层统一发送一次取消通知，不重新 GET/初始化。第一次修正重复回归仍漏第二次取消，随后将网络取消捕获扩大到 coroutineScope 退出边界，覆盖响应消费结束到清理完成之间的取消。
- 25 项测试与 APK 构建通过。SSE fixture 连续覆盖读取等待、POST 响应延迟和接收后立即取消，每次随后调用成功，6 次调用对应 6 条连接，无取消重连。scripts/test-agent-runtime.sh 新增 RemoteMcpSseCancellationTest；证据 artifacts/sse-mcp-cancel-20260909/。
- APK 已留存，尚未安装本次版本或进行 App MCP 界面验收；主机 TCP 测试不代表真实生产服务一定终止工作。待真机验证。

### MCP App 配置、实际取消、恢复和重启验收（2026-09-09）
- 在 emulator-45562 覆盖安装 SHA a585a11d89edb3e3fb5b4841687fe57469e7e96aa9029c9980372b45ec693ff4，保持既有本地模型 API。通过 App 设置 → MCP Tools 添加隔离测试服务，实际刷新 Connected / Tools 3。
- scripts/fixtures/mcp-lifecycle-server.mjs 提供无文件/命令权限的 legacy HTTP fixture；操作入口及清理见同目录 mcp-lifecycle-server.md。设备入口：scripts/verify-agent-user-journey.mjs + xiaowan-mcp-stop-recovery.en.json。新增只读 fixture 观察断言，不绕过 ACP 或修改历史。5 项测试辅助器/时钟前置回归通过。
- 两轮 1788907215245、1788907375120，共 26 步全通过：真实模型调用等待工具，点击 Stop 后 canonical cancelled，服务收到同 requestId 唯一取消且解除等待，下一次真实 echo 成功并正式完成，两类历史重启后均保留。证据 artifacts/mcp-device-20260909/。
- 完成后通过 UI 只删除测试服务，验证服务列表恢复原先空状态，移除测试 reverse，服务进程正常退出。未清除用户数据。当前只验收模拟器 legacy HTTP；SSE/权限拒绝的 App 实际流程与物理设备仍未覆盖，待真机验证。

### SSE 与 401 拒绝的 App 连续验收（2026-09-09）
- 保留既有本地模型 API，UI 添加 /sse 测试端点后 Connected / Tools 3。初轮 1788907965688 停止/真实远端取消/回显/重启均通过，但第 15 步输入前 UiTestAutomationBridge 返回空 root，整轮保持失败，未发送该消息。
- sender 的既有就绪循环现在只在动作前容许空观察并等待，仍保护已有草稿、绝不重发。新增 Node 测试旧代码先失败、修正后通过；7 项观察/就绪/时钟测试、23 项 Python 历史断言通过。
- 独立拒绝恢复 1788908221211 的 12 步通过；完整复测 1788908409224 的 25 步通过。两次 SSE 工具取消都匹配原请求和原 stream endpoint；两次 401 都仅实际调用一次，失败工具与 HTTP 401 历史保留，助手正常收尾，后续 echo 及重启恢复正确。证据 artifacts/sse-device-20260909/，新入口 xiaowan-mcp-stop-denial-recovery.en.json。
- 测试服务仅本机回环；结束后仅删除测试配置、移除 reverse 并终止服务，原空服务列表恢复。未修改模型凭据或清除对话。物理设备仍缺失，待真机验证。
- 另观察到导航进入 Local Agent Sessions 时显示 runtime unavailable；源码显示列表查询全局 Agent 状态，聊天可按对话绑定运行，暂作排查线索，尚未确认根因/修复，不计入本项验收。

### 2026-09-09：冷启动会话列表误报运行时不可用

- 复现：小万历史聊天重启后，点击顶部已选中的 Agent 分段；列表显示 `Agent runtime is unavailable`，未尝试连接。
- 原因：`AgentRuntimeManager.status()` 的可用性探测得到 `ready=true`，但合并 `LocalAcpRuntime.statusPayload()` 时被 `ready=isConnected=false` 覆盖；分发环境的 `connected` 判定也可能被覆盖。保留 host 对这两个字段的所有权，其他 ACP 元数据正常合并。未新增生命周期或重试。
- 可执行回归：`AgentRuntimeProtocolPayloadTest.disconnectedTransportDoesNotHideAnAvailableAgent`、`transportCannotOverrideHostDistributionOrAvailabilityChecks`；旧逻辑 2/2 失败，修正后该测试类 73/73 通过。执行 `./gradlew :app:testDevelopStandardDebugUnitTest --tests '*AgentRuntimeProtocolPayloadTest'`；Flutter 会话列表测试 2/2 通过。
- 模拟器操作：保留数据重装、冷进入列表、打开历史、重新进入、强停重启后再进入，列表均恢复。设备/版本/原始 XML/红绿日志见 `artifacts/session-readiness-20260909/verification.json`。无物理设备，**待真机验证**。
- 此结果只验收“可用性被未连接状态覆盖”。列表的更新时间和 Loaded 统计尚需另行核查，不据此宣称所有会话管理操作通过。

### 2026-09-09：会话列表读取意外刷新所有会话时间

- 实际故障：不发送消息，仅重新进入 Local Agent Sessions，4 条历史会话 `updatedAt` 全部变化。读取经 `listThreads -> ensureBinding -> buildUpdatedConversation` 无条件写入当前时间并触发列表通知。
- 修正：已有绑定的元数据同步无变化时保持 Conversation 原值；cwd 无变化时不写 binding；实际标题/归档/模式变化仍更新时间和发布通知。没有改变会话身份或 ACP 生命周期。
- 可执行单元回归 `AgentSessionBindingMetadataTest` 覆盖重复同步、真实变化后重复同步、缺省元数据与历史保留；旧代码 3 项失败，修正后与协议测试共 76 项通过。入口：`./gradlew :app:testDevelopStandardDebugUnitTest --tests '*AgentSessionBindingMetadataTest' --tests '*AgentRuntimeProtocolPayloadTest'`。
- 可执行设备断言：`python3 scripts/assert-agent-session-metadata.py SERIAL CHECKPOINT.json --capture`，通过 UI 进入列表/返回/重复进入/强停重启，再运行同命令去掉 `--capture`。要求已有绑定历史、全程不发送消息或编辑元数据。只读 SQLite backup，对比 Conversation 与 Binding 时间；物理设备需 `OOB_ALLOW_PHYSICAL_DEVICE=1`。
- Android 13 ARM64 模拟器 emulator-45562 已保留数据重装，以上 4 阶段实际操作后全部断言通过。证据 `artifacts/session-metadata-20260909/verification.json`；**待真机验证**。此验证不等同于已解决列表 Loaded/Running 统计缺失。

### 2026-09-09：本地会话列表遗漏活动/载入状态与标准 sessionId

- `session/list` 的本地投影此前没有 loaded/active，页面默认全部为 false。现在直接读取现有 `sessions` 与 `AcpTurnOwnershipRegistry`，无第二套状态机；列表解析优先接受标准 sessionId，兼容已有 threadId。
- 可执行测试：`LocalAcpSessionListStateTest` 经真实 runtime.handleMethod(session/list) 验证载入、任务保留、取消后仍载入；旧实现失败。Flutter `agent_sessions_page_test.dart` 新增纯 sessionId 三种状态测试，旧解析失败。修正后相关 Kotlin 77 项、Flutter 3 项通过，构建成功。
- 实际本地模型操作：发送 `xiaowan-live-permission.json`，权限等待 -> 顶部区域上划切回模式入口 -> 会话列表 1 Running -> 返回原对话 Stop -> 再进入 0 Running/1 Loaded -> 强停重启，日志确认 session/load 恢复原会话，仍为 0 Running/1 Loaded；取消历史断言通过。
- 设备可执行断言：`python3 scripts/assert-agent-session-list.py SERIAL --running 1 --loaded 0`；停止后 `--running 0 --loaded 1`；配合 `assert-agent-turn-outcome.py SERIAL MARKER permission-pending/cancelled`。该页面的 Loaded 统计排除 Running，避免重复计数。重启后若聊天自动 load，不能假定 Loaded 必须为 0。
- 证据：`artifacts/session-list-state-20260909/verification.json`。立即停止后的第一次持久化观察尚未收敛，后续观察通过；未重发任务。**待真机验证**。本次只验证进入页面时的快照；页面停留期间的自动刷新、关闭会话失败路径仍需继续检查。

### 2026-09-09：关闭/归档失败后提前丢失会话引用

- 对照官方 Kotlin SDK 0.30.1 `ClientSessionImpl.close`：await SessionClose 响应后才 removeSessionHolder。本地 close/archive 原先先 sessions.remove 再 close，失败即丢失仍存在的会话。
- 修正现有运行时关闭顺序：成功后 compare-and-remove 同一个 ClientSession，再移除相关会话元数据；失败透传，保留引用。无自动重试或新生命周期。
- `LocalAcpSessionCloseTest` 两项覆盖关闭/归档被拒绝、引用保留、下一次显式成功关闭后移除；旧代码两项断言失败，修正后相关 Kotlin 共 79 项通过。前两次测试准备阶段出现 JUnit 非 void 签名错误，不计为 bug 的红测；有效红测日志为 `oob-session-close-red3.log`。
- 模拟器通过 UI Archive 验证：已载入变为 0，Conversation 4 的 37 条历史全部保留。但 DB isArchived=1 时列表仍显示 Archived 0，不能从该页取消归档，**整条流程未通过**。待补齐本地归档元数据投影后通过 UI 恢复测试会话。
- 关闭拒绝分支尚未做设备故障注入；**待真机验证**。证据 `artifacts/session-close-20260909/verification.json`。删除请求之后又 close，以及活动任务关闭失败时的终止归属另行核查，不据本次测试宣称通过。
- 上游来源：https://repo.maven.apache.org/maven2/com/agentclientprotocol/acp-jvm/0.30.1/acp-jvm-0.30.1-sources.jar ，SHA-256 `9a24ce36f8a0d2f4a42642e09471d747c2ee60dbed361e69e5f2a26da04acb92`。

### 2026-09-09：归档状态来自 Conversation，修复列表与取消归档入口

- 真实复现：Conversation 4 isArchived=1，但 session/list 未投影该字段，页面显示 Archived 0 并只提供 Archive。只读 `AgentSessionBindingRepository.getConversationByThreadId` 从已有绑定定位 Conversation，列表投影其 isArchived，不写库、不推断协议生命周期。
- `LocalAcpSessionListStateTest` 增加持久化归档 true/false 翻转与 list 不调用 setArchived 的断言；旧代码失败，修正后相关 79 项 Kotlin 测试通过并完成构建。
- 模拟器实际操作：重装显示 Archived 1 -> Unarchive -> Archive -> Unarchive -> 强停重启恢复。每一步运行 `assert-agent-session-list.py` 对照 loaded/running/archived；最终 DB 归档=false、37 条历史保留，原取消结果经 `assert-agent-turn-outcome.py` 验证。上一轮归档的测试会话已通过 UI 恢复，未用数据库写入修正状态。
- 入口：`./gradlew :app:testDevelopStandardDebugUnitTest --tests '*LocalAcpSessionListStateTest'`；设备 `python3 scripts/assert-agent-session-list.py SERIAL --running 0 --loaded 0 --archived 1`，取消归档后 archived=0，loaded 按是否实际加载检查。证据 `artifacts/session-archive-state-20260909/verification.json`。
- **待真机验证**。本次覆盖归档/取消归档 UI 正常路径与持久化；不代替关闭失败设备注入、删除协议顺序、活动会话关闭失败或页面停留自动刷新验收。

### 2026-09-09：session/delete 使用正式 SDK，避免删除成功后再次 close

- 本地依赖 `com.agentclientprotocol:acp-jvm:0.30.1` 已提供 `Client.deleteSession`。旧 host 跳过小万协议请求，外部 Agent 则 raw delete 后再 close，可能将已成功删除误报为会话不存在。改为统一正式 SDK 调用、声明能力检查、确认成功后清理 host 绑定；拒绝/传输失败保留原状态，不添加重试。
- 官方语义与关闭不同：delete 主要从 session/list 移除，存储及 load 行为由 Agent 定义；参考 https://agentclientprotocol.com/rfds/session-delete 。小万已有的删除回调保留 Conversation 历史；host 提前保存绑定的 conversationId，以兼容回调先解除绑定。
- 可执行 `LocalAcpSessionDeleteTest`：旧逻辑断言失败；覆盖活动任务拒绝、Agent 拒绝后引用保留、成功只 delete 不 close、能力未声明不发请求，以及回调解除绑定后的 conversationId。执行 `./gradlew :app:testDevelopStandardDebugUnitTest --tests '*LocalAcpSessionDeleteTest'`。
- 当前 Flutter 只有 deleteSession 服务定义、没有页面调用；不把普通删除聊天当成该 ACP 操作验收。本轮仅保留数据重装并验证原 37 条历史和取消结果，**删除协议设备测试未运行，待真机验证**。没有删除任何已有设备会话。
- 证据目录 `artifacts/session-delete-20260909/`，测试最终结果以其中 XML/verification.json 为准。

### 2026-09-09：取消请求失败不能伪装成功或释放原任务

- `interruptTurn` 原先吞掉 ClientSession.cancel 的传输异常和 2 秒期限，仍返回 ok；活动会话 close 又把前置取消失败当作本地 cancelled。改为保留既有期限、正常传播请求异常；close 不合成终态。原 prompt/所有权继续存在，直到正式响应或其自身错误结束。
- `LocalAcpCancellationFailureTest` 经真实 runtime.handleMethod 验证 cancel/close 两条入口：IOException 传输失败可见、原会话/turnId/执行 Job 保留；再次显式 cancel 可发出，但通知成功也不等同 prompt 完成。有效旧逻辑红测 2 项断言失败，修正后相关 79 项测试通过。
- 测试准备曾遇 Mockito checked-exception 配置限制、协程堆栈恢复复制异常对象；修正为 thenAnswer 抛错、核对异常类型/消息。前两次测试失败不计为产品回归证据。有效日志见 `artifacts/cancel-request-failure-20260909/`。
- 模拟器重复验收入口：`node scripts/verify-agent-user-journey.mjs SERIAL scripts/fixtures/agent-user-journeys/xiaowan-live-permission-stop-allow.en.json OUTPUT`。最终状态以证据目录 verification.json 为准；关闭/取消发送失败的设备注入尚未完成，**待真机验证**。
- 后续：前端取消入口仅 debugPrint 请求失败，仍需补充面向用户的提示；不能让提示修改任务终态。页面停留自动刷新也尚未验收。

- 本轮最终结果补充：16 步真实模型权限等待/停止/批准命令/重启回归全部通过，runId `1788911973583`，见上述证据目录 `device/`；取消传输失败设备注入仍未运行，不能扩大为该失败场景已验收。

### 2026-09-09：ACP 停止请求不能提前中断卡片，失败需提示

- 源码确认 `_onCancelTask`、`_cancelDispatchTask` 和按 taskId 停止路径在发送取消请求前先 interruptActiveToolCard，导致请求失败时 UI 仍显示中断。ACP 分支移除提前投影；非 ACP 分支维持原逻辑。两处普通聊天/Agent 取消 catch 使用既有错误格式化与 toast 提示，不合成任何终态或重试。
- `agent_runtime_service_test.dart` 新增失败到达调用方、无自动重试、再次显式取消保持原身份的 MethodChannel 回归。与 `chat_conversation_runtime_coordinator_test.dart` 共 161 项通过；构建通过。
- 本轮模拟器采用既有 16 步 `xiaowan-live-permission-stop-allow.en.json`，最终运行结果见 `artifacts/stop-ui-20260909/verification.json`。正常停止流程不能证明取消发送失败提示的设备行为；该故障注入未完成，**待真机验证**。
- 仍需检查 command_overlay/chat_bot_sheet.dart 的关闭失败标志及再次操作；没有将本次提示改动扩大为浮层流程已验收。

### 2026-09-09：正式 PromptResponse 必须关闭未回答请求

- 模拟器运行 `1788912529987` 在第 5 步实际失败：正式 `cancelled` 已持久化，但同一轮批准卡片仍是 `pending`，再次读取仍失败。证据保留在 `artifacts/stop-ui-20260909/failed-device-before-terminal-fix/`，不是用延长等待掩盖失败。
- 在既有 `AgentEventReducer._completeTurn` 中处理该轮未回答的 `agent_request`；已提交、批准、拒绝等结果保持原样，其他轮次不变。取消、失败、正常结束均不允许残留可交互请求。已取消/中断/失败的请求状态不会被重放覆盖为 pending。未改工具完成语义、未添加生命周期。
- 三项终态回归先红后绿；加入取消/中断/失败请求重放回归，相关 reducer、service、coordinator 共 361 项通过。构建成功，设备流程结果见同目录 verification.json。
- 旧版本已保存的异常历史是否需要规范化，以及取消传输失败 toast 的设备故障注入，尚未验收；**待真机验证**。

### 2026-09-09：快捷浮层区分停止任务与关闭会话

- `ChatBotSheet` 停止仅调用既有 `session/cancel`，不提前中断工具卡片，也不顺带 `session/close`。失败解除本次取消标记、提示错误；原 prompt 继续拥有终态；再次显式停止可重试，成功应答后的重复停止不重复发送。过期卡片在设置取消标记前验证任务身份。
- 关闭浮层直接交给原生 `session/close` 所有者处理取消和关闭，移除前端重复取消。关闭失败解除关闭标记，使下一次显式关闭可发出；不合成任务完成。
- 旧实现反跑新增断言失败。两个 Flutter 测试文件共 6 项通过，覆盖取消失败、显式重试、重复停止、结果到达、后续任务、启动各阶段停止，以及关闭失败。新增关闭测试独立文件以隔离静态 EventChannel 测试监听生命周期；该隔离不是产品修改。
- 构建和 emulator-45562 覆盖安装成功。实际入口检查显示宠物点击只是挥手，不打开旧浮层；源码剩余直接调用为授权恢复场景。**未完成浮层真实用户流程验收，待真机验证**。证据 `artifacts/overlay-stop-20260909/verification.json`。未把主聊天页通过等同为浮层通过。

### 2026-09-09：活动会话内的旧历史请求不可重新交互

- 现有历史恢复在 `isAiResponding` 或 `preserveLiveStreamingState` 为真时直接跳过所有请求规范化，导致已保存正式 stopReason 的旧 pending 请求也被保留。空闲恢复已有过期处理，不能将它误报为完全缺少历史防护。
- 在既有快照规范化中优先读取每条消息自己的 PromptResponse stopReason，关闭该旧请求；当前未结束请求和已批准结果不变。保留实时消息的合并路径同时覆盖已缓存的旧请求，保留其他 content 字段。未修改数据库或伪造 ACP 响应。
- 两项新增回归先失败后通过；协调器与 reducer 共 303 项通过，覆盖新恢复与保留实时缓存分支。设备上旧失败标记 `1788912529987` 在安装本轮修复前已通过 cancelled 历史断言，说明此前空闲恢复已处理该样本，不能充作本轮活动恢复问题的设备复现。
- 本轮构建/设备结果见 `artifacts/history-request-20260909/verification.json`。**待真机验证**。

### 2026-09-09：递归列目录不能让根目录消耗 limit

- 引入定位：`051eb5442`（2026-09-02）将原 `.drop(1).take(limit)` 改为先 take 后 drop，改动用于可选限额/深度；提交差异保存在同目录 `introduction.patch`。这是操作顺序回归，不能归因于模型找不到文件。
- 原 `file_list` 递归分支先 take(limit) 再 drop(1)，实际 limit=1 返回 0 项，limit=2 返回 1 项。抽取原逻辑后，限额与取消回归两项先失败。修复先去掉遍历根节点，再复用既有可取消遍历；不改变省略 limit/maxDepth 的完整结果契约。
- `FileListTraversalTest` 覆盖限额、根节点排除、深度、非递归、40 次重复和取消后新操作；与搜索遍历、无限额契约共 16 项通过，APK 构建成功。
- 新增真实模型用例 `scripts/fixtures/agent-user-journeys/xiaowan-file-list-limits.en.json`，两个独立文件，三次不同 limit，按 canonical tool journal 核对实际 count/items，并重启复查。
- 旧包模拟器运行 `1788914584465` 实际返回 0/1/2；持久化工具断言复现失败。该轮在输出部分最终回复后超过 180 秒仍未完成，因此主用例在回复等待处失败；随后正常点击 Stop，官方 cancelled 断言通过。未将未完成回复误算为成功，也未重装打断活动任务。模型流等待原因尚未定位，保持为后续调查项。
- 修复包运行 `1788915006738`：7 步通过，三次实际工具结果 1/2/2，正常结束并重启复查通过。设备结果见 `artifacts/file-list-limits-20260909/verification.json`。**待真机验证**。

### 2026-09-09：流内 Provider 错误必须终止部分回复

- `AgentLlmStreamAccumulator` 原逻辑只在正文和工具调用都为空时抛出已收到的 Provider 错误；已有部分输出时可能成功返回，错误后保持连接则继续等待。新增两个回归在旧实现失败。现将明确 error 对象作为本次流的终止条件，buildTurn 优先保留错误，不执行失败回复中的工具输入；沿用既有 client completion 和 ACP 生命周期。
- 累积器、HTTP client、Responses/Anthropic 回归共 88 项通过；本机故障发生器 3 项、终态验证器 25 项通过，构建通过。
- 模拟器受控故障接口：partial text/error/no EOF、partial file_write/error/no EOF、下一次正常请求、重启恢复。首次断言把 pending 输入展示记录误当执行结果，保留失败证据；核对记录及目标文件不存在后，修正验证器允许 pending/false 输入但拒绝执行结果，增加实际文件不存在的设备检查。最终 `1788915874025` 17 步通过，部分正文保留、文件未创建、每轮仅一次 HTTP 请求，错误连接由客户端释放。
- 已恢复 GLM-5.1 可见选择；Provider 安全元数据及 scene bindings 与执行前完全一致；测试服务 18879 已停止。未改动真实密钥。
- 这不能证明前次真实模型中途停顿也是该原因。流内错误的用户提示分类仍偏通用，详细错误保留，待后续检查。证据 `artifacts/inband-provider-error-20260909/verification.json`，**待真机验证**。


### 2026-09-09：Provider 错误分类必须跨过正式 ACP 错误边界

- 小万流内 error 原先只抛普通异常，不能可靠区分额度不足、请求限流和原因不明的 429。保留 Provider status/code，复用现有错误格式化；未知 429 不再默认称为频率限制。异常仍是原请求的失败，不增加重试或第二套生命周期。
- 设备运行 `1788916583700` 复现“任务已失败但用户提示仍通用”。接收端分类尝试 `1788917086654` 仍失败：官方 SDK 已将异常序列化成 JsonRpcException，原类型不在接收端。撤回无效接收端改动，在 XiaowanAcpConnection 发送正式错误前保留 executor 已生成的用户提示，原异常作为 cause；取消分支仍优先处理。
- 可执行入口：`node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/xiaowan-provider-limit-categories.en.json OUT`。三类注入错误分别验证唯一终态及精确提示，随后发送正常请求，并重启复查四轮历史。使用本地故障服务，不能冒充真实 Provider 额度事故。
- 分类/取消 worker 单元测试 23 项、Flutter 错误格式化 61 项、故障服务 3 项通过；APK 构建通过。设备最终结果见 `artifacts/provider-limit-categories-20260909/verification.json`；两次失败证据均保留。**待真机验证**。


### 2026-09-09：单行大文件搜索的内存与取消边界

- `file_search` 原先 `bufferedReader().useLines` 先分配整行，再检查取消；匹配靠前时仍读取整行。提取原逻辑后，受控 Reader 分别证明“已有足够摘要仍继续读”和“取消后继续读”，新增回归旧逻辑 2 项失败。
- 仅在原 FileToolHandler 搜索路径改为分块扫描，保留跨块匹配所需尾部和前 40/后 120 字符摘要。每块读取前后检查同一个 coroutine context；匹配足够即返回。不限制文件大小，不增加工具、生命周期或隐藏结果上限。caseSensitive 使用标准字符串大小写匹配。
- `FileContentSearchTest` 覆盖超长无换行、取消后后续操作、40 次跨块/大小写/Unicode 查询、CR/LF/CRLF、空文件、跨行不匹配、超过块长的 query 和短读取；与目录遍历测试共 13 项通过，APK 构建通过。
- 可执行设备入口：先 `python3 scripts/prepare-content-search-fixture.py emulator-45562`，再 `node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/xiaowan-content-search.en.json OUT`。准备仅写独立合成 HTML，不修改对话；重复准备校验哈希且不覆盖不同内容。实际工具结果断言要求头部、尾部、无匹配计数 1/1/0 和精确摘要，随后重启复查。
- 测试数据初次 stdin/tee 传输缓慢，终止该测试进程并移除其未完成合成文件，改用 adb push + run-as cp；这是测试准备修正，不计产品故障。设备最终重复运行结果见 `artifacts/content-search-20260909/verification.json`。
- **待真机验证**。确定性的读取期间取消覆盖来自单元测试；模拟器验证真实模型搜索与重启，不冒充在设备上精确命中了块间取消时刻。未测量发布版内存峰值。


### 2026-09-09：正式 ACP 错误数据与失败前更新交付

- 进一步发现之前只保留原生中文错误提示仍会丢失语义：Flutter 再次分类时无法还原身份验证/模型错误。原生分类与共享服务新增回归各自先失败。复用安装的 ACP Kotlin SDK 0.30.1 JsonRpcException.data 携带现有 failureKind，LocalAcpRuntime 在原请求结果中保留该诊断字段，AgentRuntimeService 统一格式化；无新增页面状态、重试或终态事件。Provider 401、403、503、model_not_found 同时支持 HTTP 与流内错误。
- 设备首轮 `1788918678490` 分类正确，但严格断言发现失败前 partial 丢失；未放宽断言。根因：XiaowanPromptWorker 子协程抛错取消 channelFlow 生产者，缓冲的 session/update 被丢弃。新增 40 次慢消费者回归在旧逻辑失败；worker 将异常交回生产者，现有 channelFlow 使用 close(error) 先交付已有元素再报错，取消仍通过原官方 PromptResponse(CANCELLED)。
- 上游依据及失败证据见 `artifacts/provider-error-data-20260909/`。failureKind 是应用诊断元数据，不宣称为官方 ACP 状态。JSON-RPC error.data 与 Kotlin Channel.close(cause) 语义均已核对；未引入新 SDK 或第二条流。
- 可执行设备入口 `scripts/fixtures/agent-user-journeys/xiaowan-provider-error-data.en.json`；同时复跑既有 provider-limit-categories 用例，断言精确分类、partial 保留、唯一请求终态、下一请求完成与重启恢复。最终结果见同目录 verification.json。
- **待真机验证**。故障服务为受控本地注入，不能代表真实服务商发生鉴权或额度事故；先前真实模型中途停流仍未证明与此同因。


### 2026-09-09：持久终端进程退出必须结束命令等待

- 模拟器旧包运行 `1788920146314`：真实模型创建终端并执行 `exit 7`，终端已显示进程退出码 7，但小万仍等待工具完成；60 秒回复观察超时后，用户 Stop 路径正式取消该轮。原因是原 `sendSessionCommandAndAwait` 只等 sourced shell wrapper 的完成标记，进程退出、会话消失或替换均没有结束条件。
- 在原终端轮询内检查同一个 TerminalSession 的存在性与运行状态；正式命令完成标记优先，否则已退出进程返回工具失败，由现有 Agent loop 决定后续操作。没有新增 ACP 状态、重试或进程自动重开路径。
- `PersistentSessionCommandTest` 旧轮询先红后绿，覆盖丢失/退出会话、部分输出、完成标记优先、40 次重复及取消后下一次独立命令；相关原生测试共 16 项通过，APK 构建及覆盖安装成功。
- 可执行入口：`node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/xiaowan-session-exit.en.json OUT`。要求真实工具 journal 证明两次创建、第一次 exec 以 exit=7 失败、第二次不同 sessionId 的 exec 实际 stdout 正确、成功停止新终端，最后正式结束并重启复查。结果见 `artifacts/terminal-exit-20260909/verification.json`。
- 取消验证器旧断言错误要求一定有 assistant_message；纯工具轮已有正式 cancelled 元数据却被误判。修正为同一轮任意记录的正式 stopReason，并保留唯一终态和无待回答请求校验；文本“cancelled”不能代替元数据。新增回归先失败后通过，验证器 27 项通过，旧包失败轮实际取消断言通过。
- 独立未解决：权限回归先成功 Stop，随后真实模型返回空正文且无 tool_calls，正式报错；原因尚未定位，未计为全流程通过。另一次权限用例因模型设置弹层未关闭而未发送，不算产品生命周期复现。失败证据均保留。
- 小万 Stop 是取消当前轮，session/resume 恢复持久上下文，不提供命令执行栈暂停/原地续跑。本次修复不增加“暂停”能力。**待真机验证**。

- 本轮设备结果：`1788920467528` 全部 7 步通过；重复运行 `1788920830576` 在 60 秒回复观察期限失败，保留原失败结果，随后同一轮正式完成。另行核对实际工具结果及重启后的终态、工具记录均通过；没有重发请求，也不将迟到完成改记为完整 7 步通过。


### 2026-09-09：空模型响应与取消后下一请求的复查

- 复跑此前失败的真实模型权限流程：等待权限、Stop、正式 cancelled、下一请求、实际批准执行 id、正式完成、重启复查。`1788921133610` 全部 16 步通过；此前 `1788919769944` 的空正文/无 tool_calls 没有再次出现，不能据此宣称间歇故障已修复，也不能归因于终端退出修复。
- `HttpAgentLlmClientTest` 新增同一客户端连续 20 组“finish_reason=stop + 空正文 + DONE → 正常响应”，每个逻辑调用只发一次请求，空响应必须报错，下一调用实际返回 recovered。该回归验证现有实现，没有为得到绿灯增加重试或改变 ACP 语义；相关测试共 32 项通过。它不是旧故障的根因复现。
- 可执行入口：`./gradlew :app:testDevelopStandardDebugUnitTest --tests '*HttpAgentLlmClientTest'`；设备入口沿用 `scripts/fixtures/agent-user-journeys/xiaowan-live-permission-stop-allow.en.json`。证据 `artifacts/empty-response-recovery-20260909/verification.json`。
- 本轮仅新增测试、证据，没有产品代码改动。真实接口空响应根因仍待原始响应或稳定复现证据；未加入无依据的兜底重试。**待真机验证**。


### 2026-09-09：未提供暂停与执行栈恢复时的真实命令入口

- 小万现有 AvailableCommandsUpdate 发布 compact；init 是 UI 明示的生成/更新 AGENTS.md 提示词快捷入口，进入普通 prompt。session/resume 恢复持久会话上下文，不等于用户输入 /resume 后恢复某条已取消命令的执行栈。
- 新增 `scripts/fixtures/agent-user-journeys/xiaowan-unsupported-lifecycle.en.json`：检查 init/compact 显示，plan/review/pause/resume 不可点击；实际输入 /pause、/resume，断言没有活动 Stop 控件且数据库 user_message 数量不增加；重启重复整套操作。复用原 UI 输入与数据库只读快照，不直接调用产品接口。
- 解析回归覆盖 20 轮 /pause、/resume、大小写与带参数输入；相关 Flutter 5 项通过。当前实现即通过，未增加产品生命周期、重试或前端状态。
- 首次设备用例重启前均通过，重启后 UIAutomator 返回 null root，未执行点击；失败记录保留。设备上随后新快照确认界面已恢复，为用例补上命令按钮就绪观察，再执行原流程。最终证据见 `artifacts/unsupported-lifecycle-20260909/verification.json`。
- 此用例验证未支持命令不会误启动任务，不覆盖 init 生成文件内容，也不代表所有 Harness 的能力已验收。**待真机验证**。


### 2026-09-09：停留在会话列表时更新本地任务状态

- 页面原定时刷新只接受 remote，并监听已过时的 thread/turn/item 事件；本地小万被排除，正式任务结束后列表可能保留 Running。页面级回归实际复现：服务的 session/list 已返回完成状态，页面仍找不到 Finished now。
- 统一使用既有每 3 秒 session/list 查询，不从文本、工具输出或旧事件名推断状态；本地与远端同一路径，移除旧事件订阅。增加正在刷新保护，避免慢查询重叠；列表被其他页面覆盖时停止轮询，返回后恢复，dispose 后停止。
- 另移除远端“查询 loaded 再查询所有 session”的重复请求，因为现有 listLoadedSessions 已映射到同一 session/list；标准快照已有 loaded/active 信息，不需要另一套 loaded 查询。
- 可执行页面测试 `agent_sessions_refresh_test.dart` 覆盖 20 次运行/完成切换、页面覆盖/返回/销毁、慢查询；与原列表解析测试共 5 项通过。定向分析与最终 APK 构建通过。
- 设备入口 `scripts/fixtures/agent-user-journeys/xiaowan-session-list-refresh.en.json`：真实模型执行 sleep 60，进入列表后不点击刷新、不离开页面，观察 Running 1/Loaded 0 变为 Running 0/Loaded 1；核对唯一工具执行、正式完成并重启复查。前两次因测试未处理工具层及切层期间两个辅助功能容器而在进入列表前失败，原请求继续运行并正式完成；证据保留，不计完整验收通过。最终结果见 `artifacts/session-refresh-20260909/verification.json`。
- **待真机验证**。3 秒是既有列表观察频率，不改变 ACP 活动状态、完成时刻或 Agent loop。


### 2026-09-09：整合后的 Agent 回归入口

- `scripts/test-agent-runtime.sh` 补入本次已有的目录遍历、取消/关闭/删除失败、会话元数据与列表状态、列表刷新、命令入口和浮层关闭测试；核对最终 JUnit XML 中实际存在对应类，不能仅从 filter 参数宣称覆盖。
- 整合首轮 Orchestrator 5 项失败：旧断言要求直接显示 HTTP 原文，与已验证的分类提示冲突。更新精确提示断言，同时增加原始异常 HTTP 状态与 message 保留断言；不降低唯一请求、不重放、无虚假成功等要求。中间一次把旧界面简写误当异常 message 的测试错误也保留，最终按异常类原格式修正。产品代码未改。
- 最终分两组执行同一入口：`--offline --skip-flutter --skip-webchat` 与 `--offline --skip-gradle`。Node 86、终态验证器 27、Flutter 703、WebChat 12 项通过；WebChat typecheck/build 通过。原生精确计数及类清单见 `artifacts/integrated-regression-20260909/native-results.json`，总结果 `verification.json`。
- 这是离线整合检查；未运行 live Provider、实际 Harness CLI 和独立上下文压缩专项，不能视作所有 Agent/所有操作验收通过。保留此前本地 API/模拟器证据的独立适用范围。**待真机验证**。


### 2026-09-09：整合后的上下文专项与超限识别入口

- 运行独立 `scripts/test-context-boundaries.sh`；发现 AgentContextOverflowTest 未被专项或 Agent 总入口选中，现补入两个入口。该测试保留用户原始 `Prompt exceeds max length` 与数字 Input length 超限样本，并拒绝限流、服务错误、输出参数和文件名长度等误判。
- 最终专项 Kotlin 182、Flutter 349 项通过，JUnit 明确包含两项 overflow 测试。结果及实际类清单见 `artifacts/context-integrated-20260909/`。本轮没有修改压缩算法、检查点语义或产品代码。
- 当前为 Pi 与 Gemini 的 Kotlin 源码移植，具体固定版本及 MIT/Apache-2.0 声明见 `docs/third-party/compaction.md`；不宣称为直接 TypeScript SDK 集成。原上游 TypeScript 测试未在本轮执行，本地回归也不能证明所有服务商硬长度单位完全一致。
- 本轮没有运行设备长任务、SQLite instrumentation 或真实 Provider 超限；保留既有设备证据的独立范围，**待真机验证**。


### 2026-09-09：实际官方 Harness CLI 的重复协议验证

- 使用既有独立 npm 测试目录 `/tmp/oob-all-harness-acceptance` 和当前 Kotlin AgentAdapterCatalogTest 生成的配置，启动真实 Codex、Claude、DSH、Kimi、OpenCode CLI，连接本机合成响应服务；不读取真实 API 密钥、不修改用户 CLI 配置。
- `verify-installed-harness-adapters.mjs CLI_DIR app/build/reports/harness-adapters` 完整 14 组合连续两轮通过；Claude conversation 三组合核对两轮输出、续聊历史与会话归属；官方 tools.js 转换成功/失败工具结果两项通过。
- `verify-codex-completed-messages.mjs` 将原安装补丁应用两次，验证幂等；完整文本、部分文本、正常回复、失败和 Plan 五类×两配置，共 10 项通过。已把 Plan 纳入该长期入口；原 `test-agent-runtime.sh --harnesses DIR` 调用该入口时将一并覆盖。
- Codex 的失败通过协商的 Air sessionFailure 元数据报告；现有 AcpHarnessAdapters.codex.promptFailure 与 LocalAcpRuntime 在同一 PromptResponse 处理它，不能只读 end_turn。这里没有新增 Agent 生命周期或补丁。
- 版本与脱敏结果见 `artifacts/harness-cli-20260909/verification.json`。这是实际 CLI 加受控 HTTP，不是真实模型推理或 Android 沙箱验收；记录的 reasoning 字段不等于所有服务商都已实际执行相同推理设置。**待真机验证**。


### 2026-09-09：当前 APK 的 Android DSH 安装与沙箱复核

- 在 emulator-45562 的当前 0.6.2.2 debug 中通过 Agent mode → DeepSeek Harness 实际安装，界面显示 Assistant installed。安装前 Node 缺失，不能计作沙箱测试；安装后 DSH 0.1.2-rc.1、bash 正常，重启保留。
- 执行 `python3 scripts/verify-dsh-sandbox.py emulator-45562 OUTPUT.json` 并在重启后重复：官方 read-only/workspace-write 均为 SANDBOX_UNAVAILABLE。Landlock 官方静态启动器在 proot 内外均返回 125，报告内核不支持或未启用。
- 继续在测试 Alpine 以 App UID 安装 bubblewrap 0.12.0，重复官方启动参数，实际返回 `/proc/sys/kernel/overflowuid: Permission denied`；不是仅凭未安装推断不可用。测试入口增加该启动探测的 stderr，保留原官方 provider 判定。重启重复仍失败。
- 证据见 `artifacts/dsh-current-20260909/verification.json` 及四份探测结果。没有修改产品生命周期、系统权限或启用无沙箱回退；测试依赖保留。当前环境沙箱未通过，不宣称 DSH 已完成验收。没有执行 DSH 模型任务或隔离性验收，**待真机验证**。


### 2026-09-09：init 入口复用发送准入与附件引用

- 源码发现 init 卡片直接调用任务创建，绕过 `_sendMessage` 的 bootstrap、Harness 切换等待及发送锁；手动发送 /init 则没有将已经提取的附件传入任务。卡片现复用 `_sendMessage(text: '/init')`，手动命令把附件继续传入原 `_startAgentTurnCommand`，不增加第二套生命周期。
- 既有 chat_architecture_test 增加入口/附件传递约束，与 HarnessSwitchSendBarrier、slash parser 共 30 项通过；已在原 Agent 总测试入口内。新增约束属于源码检查，不能证明设备行为。证据 `artifacts/init-admission-20260909/`。
- 本轮尚未构建重装或运行 init 真实生成文件、重复点击、附件历史和重启恢复；不宣称该问题完成验收。**待真机验证**。


### 2026-09-09：init 实际创建、更新与重启重复

- 构建并覆盖安装当前 APK，安装与本地产物 SHA-256 均为 `da1be92cd71df42a6e15fc6a6d6705daf13a25b048fb1bf73c50fde609515ed8`，保留 App 数据。使用原本地 GLM 配置从 UI 点击 init，三次请求各自正式完成，工具调用分别 14/21/17 次；同一 Conversation/Session、三个独立 turn，无重复用户提交。首次创建 4,854 字节 AGENTS.md，后续实际读取和更新已有文件。
- 新增 `assert-agent-init.py`，按操作前 entry ID 校验唯一 init 提交、归属、正式成功与工具投影唯一性；6 项可执行验证器测试拒绝旧成功、重复提交、错误/取消/未完成、混合身份、重复工具记录，加入 Agent 总测试入口。长期设备入口 `xiaowan-init.en.json` 复用 UI journey，校验非空文件及哈希。
- 首次完整 journey 在重启后的菜单展开未就绪时失败，没有第三次用户提交；保存失败。为入口增加只读可点击等待，再从已确认未提交的步骤继续，第三次通过。第二/三次 init 步骤耗时约 298/391 秒，是完整工作区分析，不是消息启动计时。最后再重启，最新正式完成记录和文件哈希不变。证据位于 `artifacts/init-admission-20260909/`，不归档生成文档正文及私人记忆。
- 这是分段完成的实际重复序列，不宣称修正后的完整 journey 已一次跑绿。附件实际引用、快速重复点击、切换 Harness 时的准入及物理手机仍待验收。**待真机验证**。


### 2026-09-09：未提交消息的命令保留草稿附件

- 原 `_sendMessage` 在命令路由前清空附件；实际模拟器旧 APK 选入 33 字节测试文件后发送 /pause，确认没有新增 user_message，但附件消失。失败断言有连续可用快照，不是观察中断。
- 附件清理移动到普通消息及 Agent 快捷任务各自既有 addUserMessage 之后；现有 ChatPageModeState 按附件 ID 消费已放入消息的引用，保留等待期间新选附件、同路径但不同 ID 的替换和其他模式草稿。没有新增 ACP 生命周期。相关状态/架构/切换/命令测试 35 项通过，状态测试覆盖 20 轮及重复消费；加入 Agent 总测试入口。
- 准备：将 `scripts/fixtures/attachments/oob-attachment-admission.txt` push 到设备 `/sdcard/Download/`，通过 Add attachment 打开系统文件选择器，必要时使用 `scripts/tap-test-document-control.py SERIAL 'Show roots'`、`Downloads`，最后选择 `oob-attachment-admission.txt`。该 helper 仅允许这三个测试标签。
- 执行 `node scripts/verify-agent-user-journey.mjs SERIAL scripts/fixtures/agent-user-journeys/xiaowan-command-attachment-retention.en.json OUTPUT_DIR`。覆盖 /pause、/resume、/pause 连续三次无新增用户提交、附件仍可见；重启后重新选入同一文件，重复同一入口。修复版两组均完整 7 步通过，构建与安装哈希一致。证据见 `artifacts/attachment-admission-20260909/verification.json`，保留旧版失败。
- 这里不测试附件草稿跨进程持久化，也不代表 init 实际模型附件引用或快速重复点击已经验收。**待真机验证**。


### 2026-09-09：init 附件实际读取、取消与重启

- 当前 cbbd80f APK 中从系统选择器选择既有 33 字节测试附件，点击 init；历史校验唯一附件及 session/turn 输出后点击 Stop，再以正式 cancelled 验证，随后重启。第一组完整 3 步通过；模型实际调用 file_read 读取 /workspace/.omnibot/attachments 下副本，工具结果包含测试内容。
- 新增长期入口 `xiaowan-init-attachment-cancel.en.json`；扩展 init 验证器明确区分 active/end_turn/cancelled，附件缺失/重复时失败。验证器 8 项通过，相关 Kotlin 11、Flutter 2、journey harness 2 项通过。本轮未改产品代码。
- 重启后重新选入附件再执行，第二组在已启动后、点击 Stop 前遇到 UIAutomator 无法取得 idle state。保留失败；未重发 init，重新读取当前界面后仅点击 Stop，正式 cancelled，重启后复核。第二组按分段验收，不计完整 journey 跑绿。
- 可重复检查读取往返：`python3 scripts/assert-agent-attachment-roundtrip.py SERIAL USER_ENTRY_ID`，限定 init 的单条历史边界，核对唯一成功工具读取、返回哨兵内容及原缓存引用/工作区副本字节。当前实跑 USER_ENTRY_ID=782；第二次取消恢复验证基线 787。证据 `artifacts/init-attachment-20260909/`。
- 仍待快速重复点击、切换时准入及物理设备；这是文本附件实际路径，不宣称全部文件格式或第二次模型读取均覆盖。**待真机验证**。


### 2026-09-09：init 快速双击、取消与重启重复

- 复用维护的 init 验证器，新增 `xiaowan-init-double-tap.en.json`。Android 输入工具在刚观察到的 init 控件位置执行一次双击手势，保存两个点击时间；不在第一次关闭菜单后寻找别的控件。间隔实际为 98/78 毫秒。
- 模拟器完整 5 步一次通过：双击启动→唯一用户消息与同一 turn 输出→Stop→正式取消→重启→重复。两组同一 session、两个独立 turn，最后重启后取消记录仍一致。相关验证器 8、journey harness 2 项通过，本轮无产品变更。证据见 `artifacts/init-double-tap-20260909/verification.json`。
- 这是实际触摸的防重复提交结果，不证明两次触摸都进入 Flutter 回调，也不覆盖 Harness 切换等待期间排队发送。**待真机验证**。


### 2026-09-09：切换完成与发送恢复之间的新切换

- 确定性异步测试复现：先完成已有 Harness 切换，紧接着开始下一次切换，再让等待发送的 continuation 恢复。旧等待器的两个 waiter 都返回 true，虽然新的切换仍未完成。保留实际失败日志。
- 在现有 HarnessSwitchSendBarrier.waitUntilIdle 内，每次成功等待后重新检查当前 pending；若原等待失败，仍返回 false，不因后来切换成功而自动重发。没有新增业务生命周期或传输重试。
- 20 轮每轮两个 waiter 的切换衔接及失败不复活测试，与架构/附件状态共 32 项通过；该测试文件原先未进 Agent 总入口，现已补入。构建、空闲检查后覆盖安装及 APK 哈希核对通过。证据 `artifacts/switch-resumption-20260909/verification.json`。
- 当前是确定性单元时序证据，尚未在模拟器操作中验收本次并发切换；安装成功不替代验收。**待真机验证**。


### 2026-09-09：Harness 切换草稿与实际发送归属

- 新增长期 `xiaowan-harness-switch-draft.en.json`：prepare-draft 仅通过 UI 输入脱敏草稿，不发送；选择 DSH 后检查完整 EditText 文本及原生 selected_profile_id，切回小万再次检查，再点击一次 Send，验证本地 GLM 实际回复与正式完成，重启复核。输入工具按字符键入的约 51/56 秒不是 App 消息启动耗时。
- 首次把回复整行断言误用于多行草稿，测试失败；截图及原生选中项确认已切 DSH、草稿仍在。保留失败，修正为输入框完整文本检查，从原未发送草稿继续完成。之后完整 11 步重新运行一次通过，journey harness 2 项通过。本轮未改产品代码。
- `assert-agent-prompt-owner.py SERIAL OOB_LIVE_SWITCH_DRAFT_RUNID xiaowan-acp` 读取唯一用户提交、Conversation→Session 绑定、ProfileStore 的 Session→Agent 映射以及输出 turn，实际验证两轮属于小万且各自独立 session。证据 `artifacts/harness-switch-draft-20260909/verification.json`。
- 这证明顺序切换、草稿保留及实际发送归属；没有刻意触发微任务间隙的新切换，不替代上一轮竞态的设备验收。未发送 DSH 模型任务、不涉及沙箱通过。**待真机验证**。


### 2026-09-09：近期 init、附件和切换修复后的离线总回归

- 运行更新后的 test-agent-runtime.sh --offline。Node 86、历史验证器 27、init 验证器 8、App JVM 658、baselib JVM 3 项通过；原生报告无失败、错误或跳过，并核对新增超限、目录/搜索和 session 生命周期类。测试阶段短暂无输出时检查同一个 JVM 线程，实际在运行安装包全部组合的 shell 语法检查，随后正常完成，没有重启测试。
- 首次 Flutter 阶段因调用者用了另一脚本的 OOB_FLUTTER_BIN 变量名，落到系统 Dart 3.9.2，依赖解析失败。保留失败；改用本入口要求的 FLUTTER_BIN=/tmp/oob-flutter-3.47.2/bin/flutter，以 --offline --skip-gradle 继续余下阶段。Flutter 716、WebChat 12 项以及 typecheck/build 通过，不重复计算再次运行的 Node/Python 数量。本轮没有产品修改。
- 证据 `artifacts/integrated-latest-20260909/verification.json` 与实际原生类清单、两阶段日志。Flutter 并行人类可读日志会复用当前测试标题，不能仅凭某个名称未出现断言未执行；保留选定文件、最终数量及此前定向回归的独立证据。
- 本次明确跳过真实 Provider/Harness CLI，不含设备旅程；此前实际本地 API、模拟器结果维持各自适用范围。并发切换设备验收和 DSH 沙箱仍未解决全部验收条件。**待真机验证**。


### 2026-09-09：五种官方 Harness 的真实 API 生命周期对比

- 使用既有 verify-live-harness-conversations.mjs，临时 CLI HOME、透明观察代理和实际服务商 API，GLM-5.1/GLM-5.2 均先经真实模型目录确认。未使用合成回复，未修改用户 CLI 配置。新增逐案例脱敏进度输出。
- 五种 Harness 的可见回复、同 session 续聊、真实文件工具输出、实际模型切换、取消后下一轮均通过。Codex、Claude Code、Kimi Code、OpenCode 的进程重启 session/load 通过；DSH 未声明 loadSession，记录不支持，不计恢复通过。
- Kimi 的思考设置实际请求变化通过；Claude/OpenCode/Codex 本次 session 未声明该选项，不能计思考能力通过。DSH 该断言失败，尚不能确定是 Harness 参数映射还是观察字段不全；待进一步定位。Codex 模型目录检查失败：实际出现 gpt-5.6-luna 请求，不能宣称所有请求都遵守配置。总结果失败，保留原始脱敏 JSON，不降级断言。
- 执行入口：OMNIBOT_TEST_MODEL=GLM-5.1 OMNIBOT_TEST_SECOND_MODEL=GLM-5.2 node scripts/verify-live-harness-conversations.mjs CLI_DIRECTORY app/build/reports/harness-adapters；凭据仅从环境读取。证据 artifacts/harness-real-api-20260909/。
- 本次在 Mac 运行官方 CLI，不代表 Android DSH 沙箱通过，也不替代 App 点击流程与物理设备验收。**待真机验证**。


### 2026-09-09：Codex 标题模型覆盖与 DSH 思考设置误判

- 官方 codex-acp 1.10.0 的 TitleGenerator.generateAndPersist 在临时 thread 上覆盖 model 为 gpt-5.6-luna。此前真实目录外请求均为该模型并收到 403。沿用版本及源码形状校验的安装补丁，去掉标题专用 model 覆盖，继承 thread 已配置的模型；不新增标题 Agent 或主机重试。旧完成消息补丁标记不再提前退出，支持已打旧补丁的安装升级，两个补丁分别幂等。
- 真实 GLM-5.1/5.2 对比重跑，Codex 目录外请求归零，续聊、实际文件工具、模型切换、进程重启恢复、取消后下一轮通过。完成文本/部分文本/正常/失败/Plan 共 5 场景 × 2 wire fixture 通过，重复安装后源文件完全一致。
- DSH 的默认与 off 都可能省略参数；原测试把不同选项等同于不同 wire 是误判。改测明确 high 再 off，真实请求从无参数→reasoning_effort=high→无参数，API 两次均成功，未改 DSH 产品代码。其 session/load 不支持仍单独记录，不能计恢复通过。
- 新 APK 构建成功、尚未安装。本模拟器未安装 Codex ACP，后续需验证安装器及实际 App 操作；现有 Codex 用户仅升级 APK 不代表已重新应用安装补丁，需覆盖安装/更新路径验收。证据 artifacts/codex-title-reasoning-20260909/。本次 Mac CLI 通过不证明 Android DSH 沙箱可用，**待真机验证**。


### 2026-09-09：Codex 安装补丁更新后的准备版本失效

- 发现标题补丁变化未更新 preparationRevision，旧设备的 online/installed 状态可能继续复用旧准备记录。在真实 catalog 读取的测试中先复现旧 revision 错误复用，再将现有准备标记更新为 codex-acp-1.10.0-message-completion-1-title-model-1。未新增安装生命周期或每条消息安装逻辑。
- 覆盖旧完成消息补丁版本不能复用、命令及包健康仍要求准备、新版本准备后可复用。Native 83、Node catalog 46 项通过；构建及 emulator-45562 保留数据覆盖安装、启动成功，hash 见 artifacts/codex-preparation-revision-20260909/verification.json。
- 用户新增自动压缩检查优先级，尚未点击手机内 Codex 安装，当前没有后台安装任务；实际新装/更新与重启验收仍待进行。**待真机验证**。


### 2026-09-09：本地真实 API 连续自动摘要与重启恢复

- emulator-45562 最新安装 APK，小万既有本地 GLM-5.1 配置；在测试会话 6 通过 UI 将阈值从 128k 调为 32k，开始前无摘要。一次用户发送要求连续读取 20 页，不调用手动 compact。正式 end_turn，观察到同任务三次不同摘要 revision/cutoff 推进；重启前后最终摘要 hash/cutoff/revision 完全一致。结束后通过 UI 恢复 128k，数据库确认成功。
- 实际 3 步任务 + 4 步续接验证均通过；不是重新跑完合并入口。新 xiaowan-live-auto-compact.en.json 合并上述流程及严格分页断言，共 8 步。verify_live_checkpoint 仅接受本次用户之后、下一用户之前的成功工具检查点，排除旧/后续任务摘要冒充成功；5 项断言测试、2 项 journey 测试通过，纳入 Agent 总入口。
- 严格分页断言实际失败：除覆盖全部 20 页，还存在初始页重复、读取 offload 文件绕路以及一次非法 offset 后恢复。原结果含 nextOffset，但 boundToolOutputs 把整个工具文本替换成引用，当前分页控制信息不再直接可见；后续需要修复这一上下文投影边界，不能因最终完成就计为全通过。证据 artifacts/auto-compaction-real-api-20260909/；无凭据或完整摘要内容入库。
- 这次证明真实 API 的自动摘要持续运行且检查点跨 App 重启保持，并未证明无绕路或所有长任务都可靠。**待真机验证**。


### 2026-09-09：外存结果保留当前分页元数据

- 实测首次 20 页任务用了 43 次工具调用，模型绕路读取外存结果。新红测试经导入修正后明确失败 Paging cursor lost for file_read。沿现有 boundToolOutputs 投影，在完整结果落盘后，为最新结果保留有上限的精确小字段，包括 nextOffset、hasMore、任意 cursor；没有工具名分支，正文不截成摘要，不引入 Agent loop。小字段仍受共享工具预算约束，旧结果不追加新预览。宿主适配边界及限制记录在 third-party/compaction.md。
- 独立 92 项测试通过，包含 60 轮新结构化结果预算回归、不同工具名、完整外存正文、异常输入、深度和长度限制。最新 APK 已构建覆盖安装，hash 与实际 API 所用 APK 分别记录在 artifacts/tool-output-metadata-20260909/verification.json。真实 API 后仅补了外层 JSON 两字符预算计数及边界测试，未为该微小变更重复跑 API。
- 本地 API 同一 32k 阈值实测恰好 20 次 file_read，0..1245184 以 65536 连续推进，无重复/工具错误；任务阶段 195176ms，前轮 555354ms，单次时长对比不宣称通用加速比例。正式完成及重启后的完成记录、已有摘要 checkpoint 均保持。阈值通过 UI 恢复 128k 并核对落库。
- 合并 8 步入口仍失败：本轮压缩后的 cutoff 在当前用户消息之前，不能满足“本任务内部工具组被摘要”的严格条件。没有降低断言或标成全绿；分页严格断言单独实跑通过，重启 3 步独立通过。更长同任务压缩、正文理解质量与真机仍待验证；**待真机验证**。


### 2026-09-09：最新修复后 60 页同任务摘要及重启续读

- 新增独立 xiaowan-live-auto-compact-60.en.json，沿用 32k 阈值与既有本地 GLM-5.1，在最新 APK 00d1cb6e095a9743d5ffe8e47e8201ce87db8240e7971dfd64c1ea9bde264ad9、emulator-45562 完整 8/8 通过：恰好 60 次 file_read，0..3866624 按 65536 连续推进，无重复或工具失败，同一规范 session/turn。约第 28 页检查点进入本任务工具组，之后继续推进；正式完成及重启前后 checkpoint 一致。
- 另一个独立 4/4 续读旅程通过：新消息要求继续此前任务，不提供文件路径或 offset；实际只调用一次原文件 file_read，offset=3932160，正式完成。该消息提及 60 页，因此不声称无任何提示的记忆测试或全文理解验收。两组实际报告分别保留，不拼成单次 12 步报告。
- 旧 20 页严格摘要失败保留；新增长压力用例满足原先缺少的同任务摘要条件，没有降低其断言。验证器保留旧 20 页模式，新增严格 60 页与单次续读模式；旧 20 页实际分页验证再次通过。具体准备/执行见 xiaowan-real-api-compaction.md。
- 本轮未修改产品代码。测试工具输入约 186/196 秒为按字符输入耗时；数据快照也有明显耗时，不用它们推断 App 首 token 延迟。阈值已通过 UI 恢复 128k，落库核对成功。恢复时一次控件未匹配未点击，重新取得页面后完成，没有重发模型任务。证据 artifacts/auto-compaction-60-20260909/；**待真机验证**。


### 2026-09-09：小块正文读取及压缩后可读性

- `file_read.maxChars` 贯通既有读取器，范围 2–65536、默认不变；不新增 Agent loop 或压缩算法。6 项文件读取测试验证单行 HTML、Unicode、原文件完整性和中英文 schema。
- 模拟器现有本地 API 先完整 4/4，再重启重复 5/5：两次 2048 字符读取及第二页正文校验值、正式结束均通过。证据见 `artifacts/small-file-pages-20260909/` 与 `artifacts/small-body-compaction-20260909/`。
- 新增 32K/10K 工具预算下大结果外存后小块正文保留测试；压缩器/Orchestrator/预算/超限恢复共 91 项通过。保留两次测试前提不充分的失败记录，不将其计为产品 bug。
- UI 自动化 focus 后空节点只等待观察恢复，不重放输入框点击；2 项脚本回归通过。首次重启用例因该观察问题失败，原记录保留；新执行完整通过。
- 专项说明 `xiaowan-small-file-pages.md`；本轮没有证明所有摘要语义或所有文件格式正确，**待真机验证**。


### 2026-09-09：正文分页修复后的整合回归

- 总入口补齐压缩器、预算、文件读取和发送观察脚本测试。Node 88、日志验证器 27、init 8、checkpoint 5 通过。
- App 本轮实际运行 693 项，1 条旧 schema 断言误将可续读 maxChars 当作丢弃原文的截断；保留失败并修正为仅 file_read 可声明可选续读页大小，其他工具继续禁止。定向复测 schema 7 + 文件读取 6，全 13 通过。baselib 3 项是 Gradle UP-TO-DATE 复用旧通过报告，不计本轮重新执行。75 个指定类的报告全部匹配，没有缺失。
- 同类 Node 源码断言也已修正并通过。没有以重命名参数规避断言，没有修改产品逻辑。原文完整性和分页连续性仍由实际文件读取测试验证。
- Flutter 716、WebChat 12、typecheck/build 通过；配置的本地 GLM-5.1 目录与真实 completion 通过。证据 artifacts/integrated-compaction-20260909/verification.json。结果来自多个明确记录的阶段，不是一轮连续全绿。本次未重跑官方 Harness CLI，不替代模拟器用户旅程或物理设备验收，**待真机验证**。


### 2026-09-09：小万关闭调用被取消后 MCP 清理遗漏

- 实际私有 Session 方法的时序测试复现：close 置 closed=true 后等待 prompt 停止，调用者取消导致 MCP close 及 onClosed 未执行；再次关闭直接返回。
- 原 close 方法用 NonCancellable 完成已有资源清理，closeMutex 让重复关闭等待同次清理结束，不新增生命周期或普通 Stop 重试。第一次修复中的协程 cancel 名称解析问题被测试拦截，明确限定为 Session.cancel 后通过；失败版未安装。
- JVM 关闭/删除/连接 10 项、ACP 源码契约 46 项通过；已接入总入口。构建、保留数据安装成功，APK SHA256 7702d3535eead7f26999857da5cba2af2ee5d9a96db1a1f450b93e4f0312c9a4。
- 新增模拟器本地 API 用户旅程完整 11/11：启动后 Stop、正式取消、同会话下一轮正常完成、重启后两个终态保留。该旅程不等于精确 close 取消竞态的设备验收；会话列表没有独立 close/delete 按钮。详见 xiaowan-session-close-cleanup.md 和 artifacts/session-close-cleanup-20260909/。
- **精确关闭竞态待设备验证，待真机验证**。不得将普通停止通过扩大为所有关闭路径已验收。


### 2026-09-09：MCP 关闭失败不得伪装成功或阻断显式再次清理

- 两条真实旧实现失败：MCP 关闭吞异常；Session 在清理失败后再次 close 未重新清理（应两次、实际一次）。初版异常身份断言调整为对外错误消息和调用次数后，保留行为红测。
- 原 MCP owner 尝试关闭其余连接后报告普通异常；原 Session owner 在 closeMutex 内单独记录 cleanupComplete，禁止新 prompt 的 closed 标志不再等同于清理成功。失败保留登记，下次显式 close 可继续；没有自动重试、重开会话或新 ACP 状态。
- 28 项 JVM、46 项契约通过。APK b06078c33e18e9cbc627643243743c6d4a28ca568cacb4f48b30d40e13042f39 构建和保留数据安装成功；emulator-45562 使用现有本地 API 的 Stop→下一轮→重启历史 11/11 通过。
- 证据 artifacts/session-close-failure-20260909/，用例继续在 XiaowanSessionCloseCleanupTest 及既有用户旅程入口。普通 Stop 不证明 MCP 关闭异常在设备上已验收，**待设备故障注入、待真机验证**。

### 2026-09-09：终端停止必须清理实际子进程，并保留 ACP 标准输入

- 用户要求：完成生命周期问题，停止后仍能继续任务；不能只看 UI 已取消或模型文字。
- 范围：沿用 Conversation → ACP Session → Turn → Item；终端启动脚本把 host TERM 交给 PRoot 的 QUIT 清理，不新增 reducer、状态机或业务重试。工具停止复用已有进程清理。会话 close 的失败/取消清理见独立 close 回归。
- 可执行入口与脱敏样本：[xiaowan-terminal-stop-lifecycle.md](xiaowan-terminal-stop-lifecycle.md)，`scripts/test-terminal-host-stop.py`、`scripts/test-terminal-child-state.py`、`scripts/verify-terminal-stdio.py` 与 `xiaowan-terminal-child-stop.en.json`。
- 边界：真实子进程身份/年龄、防止自然到期和 App 重启伪通过、正式 cancelled、下一轮终端真实 stdout 与 end_turn、无助手文字的合法结束、重启历史、Android mksh 后台 stdin、非零退出码。
- 结果及失败证据：`artifacts/terminal-stop-lifecycle-20260909/`。保留观察器失败、中间版本 stdin 丢失、设备时间预检失败以及过度要求助手回复导致的断言失败；没有把失败整轮改写为通过。**待真机验证**。

- 最终 APK `5a0e6e644340c9070fd0a9835e6bab595e7fd3d94d0cd26835117cd1464f1961`：真实 API 界面回归 12/12 通过，已安装运行时的 stdin/exit=7 探针通过；尚待物理手机验收。


### 2026-09-09：设置准入、关闭后排队请求与参数弹层返回

- 对应用户目标：运行中/取消后的配置生命周期，以及参数操作后不能继续任务的问题。
- Kotlin 在已有 promptMutex/closeMutex 上保护设置入口；新增 4 个可复现的失败用例，覆盖 worker 尚未启动、取消清理未结束、会话关闭和排队后关闭。
- 参数弹层通过 Flutter PopEntry 声明根页面可处理返回，并保留既有顶层弹层返回顺序。覆盖系统返回、直接 Navigator 返回、主动关闭、多个弹层及焦点保留。
- 入口：[xiaowan-session-config-admission.md](xiaowan-session-config-admission.md)，`XiaowanSessionAdmissionTest`、`glass_popup_back_test.dart`、`xiaowan-session-config-cancel.en.json`。已接入既有测试脚本。
- 最终 APK `d20b5d3f3984717f0645dac8275982651c0df1469baae2e6e5da2505a8fc08ef`：原生 18 项、Flutter 21 项、Node 48 项通过；emulator-45562 / 配置的真实 GLM-5.1 API 连续 27/27 步通过，包含恢复默认设置和重启。证据在 `artifacts/session-config-admission-20260909/`，中间真实失败未删除。精确并发空档目前仅 JVM 覆盖，**待真机验证**。

### 2026-09-09：ACP 删除失败保留清理所有权

- 对应生命周期问题：删除先移除 Session 登记，关闭资源失败后无法再次清理。
- 入口：[xiaowan-session-delete-cleanup.md](xiaowan-session-delete-cleanup.md)、`XiaowanSessionDeleteCleanupTest`，已加入 `scripts/test-agent-runtime.sh`。
- 旧实现两项中一项真实失败；修复后相关原生测试 13 项通过，APK 构建成功。覆盖关闭失败后显式重试、绑定解除失败后不重复释放资源。
- 失败分支目前由 JVM 测试覆盖，UI 归档不能替代 ACP 删除验收。**待设备故障注入与真机验证**。
- 最新包 `6bf2ee96974596b68922f4b144aada216674467eb9676c87b0f52a2ffc5142d3` 已保留数据安装；运行 `1788950333752` 使用真实 GLM-5.1 API 完整设置/停止/下一轮/重启回归 **27/27 步通过**，最终恢复默认设置。

### 2026-09-09：执行前登记与关闭互斥

- 入口：[xiaowan-worker-registration.md](xiaowan-worker-registration.md)、既有 `XiaowanPromptWorkerTest` / `XiaowanSessionAdmissionTest`。
- 新增三条旧实现真实失败：登记前执行、拒绝登记后执行、关闭时进入附件准备。使用既有 worker 的 lazy start 和 Session closeMutex 修复，无新协议或重试路径。
- 相关原生 21 项通过，APK 构建及保留数据安装成功。精确交错为 JVM 证据，**待真机验证**。
- APK `8a3e5e93fe4501245b676a13b728542080f5901a4a9e5cb0bb473181c84bca3b` 在 emulator-45562 使用真实 GLM-5.1 API 连续 27/27 步通过（`1788950971603`），包含设置、真实子进程停止、下一轮完成和重启恢复。证据 `artifacts/worker-registration-20260909/`。

### 2026-09-09：最新安装版本环境与命令能力复验

- [当前版本报告](current-runtime-capabilities-20260909.md)：同一 `8a3e5e93…` APK，App UID 下标准输入与退出码探针通过；DSH 官方 read-only/workspace-write 仍失败，Landlock 内核未启用、bubblewrap 的 /proc 访问被拒绝。没有修改权限或启用无隔离回退。
- 小万菜单及手动 /pause、/resume 回归 **23/23 步通过**，重启前后均没有误提交用户消息或启动任务。复用已有可执行测试，不发模型请求。
- 结果在 `artifacts/dsh-sandbox-20260909-current/`。DSH 隔离和 UI 错误展示尚未完成验收，**待真机验证**。

### 2026-09-09：登记等待中的断开与停止

- `XiaowanPromptWorkerTest` 新增 collector 断开和 worker 停止两种挂起交错，各重复 20 次，验证子任务释放、零工具执行、终态发送边界以及断开后的独立请求。
- 与 SessionAdmission 合计 17 项通过；本次仅扩展测试，没有产品改动。详情 [登记生命周期回归](xiaowan-worker-registration.md)，证据 `artifacts/worker-registration-wait-20260909/`。
- 精确调度仍是 JVM 验证，不能替代真机验收。**待真机验证**。

### 2026-09-09：生命周期修改后的原生整组验证

- 执行 `scripts/test-agent-runtime.sh --skip-flutter --skip-webchat --live`，连续退出 0。App 708 项实际运行通过；Node 88、Python 53 项通过；GLM-5.1 模型列表及对话请求通过。
- baselib 3 项是 UP-TO-DATE 的既有结果，本轮未重跑。Flutter、WebChat 和其他 Harness 未在本轮执行。
- [完整范围与证据说明](integrated-native-current-20260909.md)，XML 结果及原始哈希清单在 `artifacts/integrated-native-current-20260909/`。**待真机验证**，不代表全部目标完成。

### 2026-09-09：Flutter 与 WebChat 整组回归

- 维护入口 `scripts/test-agent-runtime.sh --skip-gradle --offline` 连续退出 0：Flutter 720 项、WebChat 12 项及 typecheck/build 全部通过。
- Node 88、Python 53 是该入口重复执行的既有用例，不累计为新增覆盖；本轮未运行 Gradle、真实 API 或其他 Harness。
- [执行范围与证据](integrated-ui-current-20260909.md)，结果 `artifacts/integrated-ui-current-20260909/`。没有浏览器验收或产品改动，**待真机验证**。

### 2026-09-09：Codex Android 安装与真实对话

- App 管理页实际安装 Codex CLI 0.153.4 / Codex ACP 1.10.0，观察安装中、安装成功、Available。未清数据或修改权限。
- 新增可执行 `codex-real-api-basic.en.json`，现有本地 Provider 下两轮及重启 **11/11 步通过**。补充查询证实同一 Conversation/Session、不同 turnId。
- [完整范围](codex-android-real-20260909.md)，证据 `artifacts/codex-android-real-20260909/`。工具、权限与 Plan 的 Android 验收仍待进行，**待真机验证**。

### 2026-09-09：权限弹层返回与 Codex 终端实测

- [权限菜单返回修复](permission-popup-back-20260909.md)：真实打开权限菜单按系统返回退出 App。复用通用弹层返回处理修复，Flutter 38 项与 Node 48 项通过。
- [Codex 终端失败](codex-terminal-exit-20260909.md)：workspace-write 下预期输出标记并 exit 7，实际一次 commandExecution 返回 182、输出为空，**未修复**。
- 原 13 步仅证明对话恢复等流程通过；已增加真实工具退出码/输出断言，并对同一任务只读重验得到失败。观察器 30 项通过，未重发请求或提升权限。**待真机验证**。
- 权限返回新包另有完整 **12/12 步**模拟器回归通过，覆盖重复与重启；权限仍为 workspace-write。重启时 null root 的观察失败保留，最终用已有 expect 就绪等待恢复。

### 2026-09-09：Codex 沙箱直接对照

- 新增实际运行的 `scripts/verify-codex-sandbox.py`：同一 App UID/PRoot/命令，普通 bash 返回 7 并输出标记，官方 `codex sandbox -P :workspace` 返回 182 且无命令输出。探针如实返回失败。
- 无模型请求或 ACP，未修改权限或持久化配置，排查范围缩小到 Codex 沙箱执行边界。具体内部原因仍未查明，**未修复、待真机验证**。
- 证据 `artifacts/codex-terminal-exit-20260909/maintained-sandbox-probe.json`；完整说明见 [Codex 终端验收](codex-terminal-exit-20260909.md)。
- 同一入口新增 `--native` 诊断并实际运行：直接原生二进制仍 status=182、signal=null，普通 bash 对照通过；记录 `maintained-native-probe.json`。排除当前 npm 包装层的信号转换，内部原因仍未确定。没有修改产品配置或权限。

## 2026-09-11：远程 Codex Harness

- 最终需求：前端不变，仅在现有 Harness 选择器选择“远程 Codex”，使用远端设备执行。
- 先前独立“电脑 Codex”入口和连接页改版已撤回。历史记录见
  [连接简化方案](computer-codex-connection-2026-09-11.md)，不能当作当前功能。
- 可执行入口：`ui/test/features/home/pages/agent/remote_codex_harness_selection_test.dart`，
  已加入 `scripts/test-agent-runtime.sh`；复用原聊天菜单、runtime service 和切换屏障测试。
- 覆盖远程启用/连接顺序、重复选择不重写配置、失败恢复、不把本地连接当成远程、
  发送等待切换、失败拒绝排队发送、保存配置恢复，以及无需本地安装的 Harness 入口。
- 结果与边界见 [远程 Harness 选择](remote-codex-harness-selection-2026-09-11.md)。
  **待真机验证**，不代表已接管官方桌面活动线程。

## 2026-09-11 远程断线重连

用户要求先修复断网重连，再测跨网。RemoteCodexAppServerSessionTest 新增初始化就绪、启动失败释放、断线请求终止/重连不重放及迟到事件隔离，已接入 scripts/test-agent-runtime.sh；聚焦 7 项通过、APK 构建成功，未连接手机，待真机验证。详情见 [重连验证记录](remote-reconnect-2026-09-11.md)。

## 2026-09-12 跨设备会话管理与公网通道

新增 scripts/verify-remote-bridge-session.cjs：真实 ACP 会话新建、首轮、断开后加载与继续，7 项本机检查通过；公网隧道连接失败，手机未连接，待真机验证。见 [验证记录](remote-session-wan-2026-09-12.md)。

## 2026-09-13：小万输出暂时回退后恢复

旧页面快照覆盖同 id ACP 消息已在可执行测试中复现并修正；新增三项覆盖通知瞬间正文、思考、预览元数据、官方完成后的迟到保存与显式删除。312 项通过，debug 构建成功；无真机连接，待真机验证。见 [验证记录](xiaowan-stream-rollback-2026-09-13.md)。

## 2026-09-13：生命周期快照后续审计

新增两项确定性失败回归：官方完成后旧历史遗漏回复、旧运行标志重新激活任务。相关全集 321 通过 / 2 失败；两项未修复，待真机验证。普通页面路径已有部分防护，不能据此断言真机必现。见 [审计记录](lifecycle-snapshot-audit-2026-09-13.md)。

## 2026-09-13：快照生命周期边界修复

用户同意后在既有 coordinator 修复历史遗漏新消息及旧运行标志重建任务，两项原失败已通过；新增重复部分历史/无绑定恢复/显式清空验证。相关 324 项通过，debug 构建成功。无真机连接，待真机验证。详见 [最终修复记录](lifecycle-snapshot-audit-2026-09-13.md)。

## 2026-09-13：显式删除与迟到快照

两个新增可执行回归失败：显式删除后旧历史加载/页面保存重新加入删除项。接口级复现，普通页面已有部分保护，真实并发可达性待验证。未修复、待真机验证；见 [记录](deletion-snapshot-audit-2026-09-13.md)。

## 2026-09-13：明确删除与历史修改版本修复

明确消息 id 删除经数据库事务提交后再更新 UI；旧读取/保存/预览回调失效，局部更新不携带整页。357 项 Flutter 通过，应用与数据库 instrumentation APK 编译成功；数据库重开用例未运行，无真机连接，待真机验证。实际下一轮模型上下文仍须真机验收。见 [最终记录](deletion-snapshot-audit-2026-09-13.md)。

## 2026-09-13：原生 Codex 与手机共享会话

双客户端连接同一官方 app-server 的真实模型测试通过，含双端输出、生成中断线与双端历史恢复，不重发 prompt。原生桌面和手机界面尚未接通；官网部署信息缺失，跨网未测，待真机验证。详见 [记录](native-codex-shared-session-2026-09-13.md)。

## 2026-09-13：4090 反向 SSH 中继

经真实 4090 SSH 链路的认证、双客户端输出和断线历史恢复通过；隧道进程退出后自动恢复。新增 `scripts/verify-codex-relay.cjs` 可重复验证认证与延迟。官网 WSS、原生桌面接入和手机真机尚未验收，见 [部署测试记录](codex-relay-4090-2026-09-13.md)。

## 2026-09-13：手机已连接后的共享会话真机预检

vivo V2502A / Android 16 / 小万 0.6.2.3：ADB 已连，远程配置关闭且旧地址 TCP 超时。`python3 scripts/verify-phone-remote-config.py ADB_SERIAL` 实机执行失败。双端同步、重连历史和不重复执行未验收，见 [真机记录](phone-shared-session-preflight-2026-09-13.md)。

## 2026-09-13：新手机 PJE110 共享会话实测

已安装 0.6.2.3，修复 JSON-RPC 版本字段、远程身份路由/传递和 load 必填字段。真实手机 A6 在电脑完成并有准确会话/turn ID，但手机历史更新仍被丢弃，双端显示未通过；另记录 Android 16 前台服务崩溃。见 [持续验收记录](shared-session-new-phone-2026-09-13.md)。

### PJE110 后续验收更新

同一会话手机发送、完整历史恢复、手机进程退出后电脑继续完成且不重复：真机通过。
外部协议客户端发送 H：手机实时显示失败，重新加载后历史通过。原生桌面 UI 和公网仍未验收。
Bridge 离线期间的完成、失败、已有权限请求、新权限请求共四个模拟 ACP 传输回归通过；
执行 `NODE_PATH=依赖目录/node_modules node scripts/verify-bridge-detached-prompt.cjs`，
或在现有 `scripts/test-agent-runtime.sh` 中追加 `--bridge 依赖目录`。
此模拟测试补充此前真机结果，不代替权限交互的真机验收。


- 2026-09-13 agent-operated cross-device tests: SSH child termination/recovery
  regression `python3 scripts/verify-acp-relay-restart.py` PASS; phone PJE110
  0.6.2.3/code15 fresh K through4090 after restart PASS, native API reads same
  completed turn. Full native send FAILED (active writer); automatic live
  native/phone sync and public WSS remain unpassed. See
  `shared-session-new-phone-2026-09-13.md`, 19:20–19:27 entry. Failed local J
  query preservation across remote hydration: dedicated regression NOT YET
  IMPLEMENTED; screenshot evidence only, no acceptance claim.


- 2026-09-13 official daemon Unix WebSocket: existing
  verify-codex-shared-session.cjs and verify-shared-codex-acp.cjs PASS with
  compression negotiation disabled. PJE1100.6.2.3/code15 true send/reply M PASS
  through modified transport. Native send N FAILED active writer; native
  backend switch, live synchronization and ACP v2 remain unimplemented.
  See shared-session-new-phone-2026-09-13.md 21:07–21:14 entry and the daemon
  regression commands in deploy/codex-relay/README.md.


- 2026-09-13 full shared-session acceptance request, parameterized configuration:
  added executable verify-acp-shared-observation.cjs against actual configured
  agents. FAIL: sender receives, passive observer gets0 updates; backend runs
  once and physical-phone explicit reload PASS. Native send Q FAIL active writer.
  Website and native pixel gates BLOCKED; concurrency/lost-ack end-to-end cases
  NOT RUN. Acceptance decision and exact evidence in
  shared-session-acceptance-2026-09-13.md. No full acceptance claimed.


- Mimi-style native SSH entry, 2026-09-13: verify-codex-shared-session.cjs now
  accepts OOB_TEST_SSH_TARGET and optional OOB_TEST_SSH_CONFIG, reusing stock
  SSH app-server proxy. Both temporary and installed-alias real-model runs
  PASS; persistent-alias thread01a09b1e-2360-7841-bf1d-d3774a710e90.
  Native desktop host registration/UI verification remains BLOCKED pending
  user settings action; protocol success is not native acceptance. Deployment
  and cleanup documented in deploy/codex-relay/README.md.

- 2026-09-13 Bridge QR continuation R: actual PJE110 0.6.2.3/code15 send in existing session PASS; backend exact once PASS; phone first reply character missing FAIL. Reuses verify-phone-shared-history.cjs with OOB_QR_CONTINUE_R; executed and failed visible reply assertion. Screenshot and command in shared-session-acceptance-2026-09-13.md. Root cause and recovery verification pending.

- 2026-09-13 full acceptance, reject custom complex rules: repeated-leading
  token regression in agent_event_reducer_test.dart reproduced exact R failure
  before removing live text equality/prefix deduplication. 320 focused tests
  PASS, candidate built/installed PJE110 0.6.2.3/code15. Candidate physical live
  verification PENDING UNLOCK. Native send active-writer FAIL; passive ACP
  observer zero updates FAIL; website permissions BLOCKED. See full rerun in
  shared-session-acceptance-2026-09-13.md; full acceptance NOT PASSED.

## 0.6.3 DSH thinking toggle and idle configuration restart

See [DSH reasoning acceptance](dsh-reasoning-0.6.3.md). Executable coverage: `AgentWebRuntimeTest`, `LocalAcpRuntimeConfigTest`, `scripts/verify-dsh-reasoning-wire.mjs`, and `scripts/verify-dsh-phone-reasoning.py`. Wire on/off, physical generation, idle-edit restart, and post-restart Off/re-enabled High generation passed on PJE110 with final 0.6.3/code 16. A separately observed offline turn failed without automatic replay.

## Public Bridge pairing and selective session browsing (2026-09-15)

Public WSS live acceptance on installed PJE110 candidate `4d85943b...`: kept the existing session page open and sent `OOB_PUBLIC_LIVE_X` and `OOB_PUBLIC_LIVE_Y` from the same backend protocol. Both appeared on the actual phone exactly once; `verify-phone-shared-history.cjs` passed each. X exposed a verifier bug: an immediate `thread/read` snapshot reported interrupted, while the same turn later completed once. Fixed `verify-shared-external-send.cjs` to wait for official `turn/completed` before verifying persistence; `verify-shared-external-send.test.cjs` reproduces the premature persisted-state interpretation and is included in `test-agent-runtime.sh`. The corrected verifier passed Y against the live backend. Actual phone composer/send for `OOB_PHONE_PUBLIC_SEND_Z` passed backend/phone exact-one-query/reply checks.

`scripts/verify-phone-public-reconnect.cjs` passed with `OOB_PUBLIC_RECONNECT_AA`: disabled real phone Wi-Fi, confirmed no default network, generated one backend turn, restored Wi-Fi and observed automatic visible catch-up without page reopening. App PID23480 unchanged; phone Wi-Fi state restored to1; no USB17321 network forward. This is public WSS over the existing Cloudflare/4090 route, not native desktop GUI acceptance. Requested native SSH-host send `OOB_NATIVE_DESKTOP_AB`; PENDING user action. Actual camera QR scan also remains PENDING. All these results are isolated-session evidence, not current-native-task migration.

MCP connection entry continuation: `tools/codex-bridge/session-connect-mcp.mjs` uses official `@modelcontextprotocol/sdk`1.30.0 and `qrcode`1.5.4 (exact pins). `session-connect.test.mjs` drives an actual MCP stdio client/server against a synthetic backend and verifies tool discovery, ownership rejection, metadata-only reads, private PNG output and absence of prompts/resumes. Combined Bridge tests: four PASS. Existing QR parser suite plus selected-session case: four PASS. Android build/install passed on PJE110, 0.6.3/code16, APK SHA256 `4d85943b8a3010923fc244c57db1c6a5605f8964d9aa00ac8d3889cc44f26da5`; no data cleared. Real MCP invocation rejected the active native task with `SESSION_NOT_LOADED_ON_BACKEND`, then produced a private PNG for the already-loaded isolated test session. Registered `xiaowan-session-connect` via `codex mcp add`; configuration and QR credential artifacts are private and outside the repository. Physical camera scan -> selected-session routing is PENDING user scan; native current-task sharing is still NOT implemented. npm pack dry-run includes all required connection modules; nothing published. The invitation reuses the Bridge credential, not a new session-scoped or expiring authorization token.

User requires bring-your-own Cloudflare/tunnel support and selecting a session without downloading all history. `tools/codex-bridge/connection-url.test.mjs` tests external WSS address validation and the running Bridge's real QR payload (three PASS); the npm dry-run package includes the endpoint module. `ui/test/features/home/pages/agent/agent_sessions_refresh_test.dart` tests a single remote page, no periodic remote scan, explicit pagination and identity deduplication, alongside existing local refresh lifecycle tests. The two session-page suites passed six tests. `scripts/verify-phone-session-pagination.py` passed on PJE110 b49f281b, 0.6.3/code16, APK SHA256 `83580c7c4485f2a850090c839c9f883b4083cb7f738b8ffc935190cd772acd5a`: first page25, explicit load-more50; actual pull-to-refresh restored25. Installed with data preserved.

`scripts/verify-shared-backend-owner.mjs` checks official `thread/loaded/list` without resuming, sending or reading history. The current native task is absent from the configured daemon; exit2 is the expected mismatch result, not successful current-task sharing. MCP registration/current-native-task connection and session-specific sharing remain incomplete. Cloudflare public-phone marker `OOB_CLOUDFLARE_PASSIVE_W` passed the existing physical-history verifier before the new APK install; this proves displayed message agreement over the configured public route, not native desktop GUI operation. Cellular-specific acceptance was explicitly removed by the user.

## 2026-09-15 — Sidebar device sessions and compact layout

- User reports: remote sessions are hard to find; device switching is slow and the initial layout is too heavy. Earlier `1`/`2` first-send failures remain unresolved and are not covered by this UI delivery.
- Reuse `AgentSessionsPage` inside `HomeDrawer`; phone/computer text tabs retain the mounted remote page and its pagination state. No new session/reducer/history protocol. One configured remote computer is supported.
- Regression: sidebar remote navigation previously supplied `conversationId=new`, overriding the selected ACP session target. Remote targets now use their existing route payload without this conflicting query.
- Executable checks: `cd ui && flutter test test/features/home/pages/agent/agent_sessions_refresh_test.dart test/features/home/widgets/home_drawer_test.dart`; 24 tests passed, including the retention test asserting one connect/list across repeated tab switches and preserving session identity. Targeted Dart analysis passed.
- Physical entry: `OOB_ALLOW_PHYSICAL_DEVICE=1 python3 scripts/verify-phone-device-sidebar.py b49f281b 'OOB daemon shared M' '我在。需要我做什么'` (start with the drawer open).
- Physical result: PASS on PJE110 / Android 16 / 0.6.3 code 16 debug, APK SHA256 `549fa933c9cba3059aa6a8574e649ebb550945d262298857ac69d11401253fac`. Repeated phone/computer switching and opening the selected remote history passed after installation/relaunch. No observed layout overflow. This does not validate native desktop shared-backend sending or first-send recovery. Cold-load latency is still network-dependent; no quantitative latency improvement claim.

### Follow-up — same sidebar rows and click feedback

- User reports all clicks fail and requests identical UI. Before the change, PJE110 device tabs and the isolated `OOB daemon shared M` row did respond; history appeared after loading. The exact user-reported nonresponsive target was not identified, so this is not evidence that all click failures are fixed.
- Local and remote rows now share `DrawerConversationRow` and `DrawerConversationTitle` (existing 13px typography, 4/9/2/9 padding, Material/InkWell full-row action). Remote browsing reuses `HomeDrawerSearchField`; lifecycle and ACP routing are unchanged.
- 24 focused Flutter tests and targeted Dart analysis passed. The physical regression now taps near the right edge of the selected remote row to exercise whitespace hit testing, not only its title.

### 2026-09-15 — Slow click followed by generic assistant failure

- Actual phone logs showed `session/load` rejection on native desktop sessions, followed by unsupported `config/read` and `model/list` requests. Read-only backend ownership inspection confirmed the selected active native session was not loaded on the configured Bridge backend. Listing history is not proof of shared-backend admission. The precise server rejection payload was not captured; do not attribute this instance to a specific lock error.
- `_prepareRemoteCodexSessionTarget` now returns admission success/failure. The existing conversation-target owner stops subsequent configuration, staged input and initial-message processing on failure. Unknown load errors receive a contextual connection/session-access message. No replay, second lifecycle or backend migration is introduced.
- Installed candidate: PJE110 `b49f281b`, Android 16, develop debug 0.6.3/code 16, APK SHA256 `477eec070dfb174f1edfaa3696e85aecaefd5113ab4a369246318ee8db57feb6`. Incremental install preserved app data. Build passed; 46 focused Flutter tests passed. Targeted Dart analysis reported no errors, with 5 warnings and 13 informational findings; it was not a clean analysis pass.
- Executable physical failure regression: open the computer sidebar with an unavailable session visible, then run `ADB=/path/to/adb OOB_ALLOW_PHYSICAL_DEVICE=1 python3 scripts/verify-phone-remote-load-failure.py SERIAL TITLE`. It taps the actual row and requires the real load rejection; checks that no configuration/model requests follow during the subsequent 5 seconds and that the process remains alive. It does not replace successful shared-session acceptance or prove absence of all network traffic.
- Physical results: PASS for the failure regression; PASS for `verify-phone-device-sidebar.py` both after install/relaunch and after the failed admission. Each sidebar run switched phone/computer twice, tapped the row's right edge, and verified the isolated session's existing history. No prompts were sent by these scripts. The initial failure-script attempt stopped before tapping because of Android shell date quoting; corrected execution passed.
- Still unresolved: original native desktop session access and two-way native GUI acceptance. No claim that native sessions are now openable, that all latency is fixed, or that the feature is production-ready. Toast wording was not separately captured on device.

#### Follow-up: exact backend rejection confirmed

A direct authenticated ACP v2 `session/resume` against the running Bridge reproduced the same selected native-session failure without sending a prompt. The response was JSON-RPC `-32603`, `Internal error`, with `data.details` stating `thread <redacted-session-id> already has an active writer`. This resolves the earlier uncertainty about the underlying rejection. The installed desktop project inventory still contained no shared SSH project. Stock SSH access and `codex app-server proxy --help` succeeded, but those checks do not prove the desktop UI is attached to that daemon. The next required acceptance step remains enabling the prepared SSH host in the native desktop and selecting the isolated project from that host; repeated resume attempts, lock deletion or copied history are not fixes.

#### SSH host enabled: actual native integration progress (2026-09-15)

- User screenshot shows `omnibot-shared-local-test` enabled and connected. The app's `list_threads` now returns `remote-ssh-discovered:omnibot-shared-local-test`; `list_projects` still returns local projects only. Therefore an empty remote-project list alone is not a valid host-connection failure check.
- Used the desktop's supported `send_message_to_thread` with that explicit host and the existing isolated `OOB daemon shared M` session. Marker `OOB_NATIVE_SSH_CONNECTED_AC` completed in turn `01a0a42f-c1ad-7ba2-a6b3-8d96e69d2ca3`; its exact reply appeared on the untouched physical phone. Direct daemon `thread/read` confirmed the same turn and reply. No writer conflict occurred.
- The native tool's input is stored as `functionCallOutput` named `send_message_to_thread` in namespace `codex_app`, containing a delegation envelope, not a `userMessage`. The unchanged `verify-phone-shared-history.cjs` correctly failed its user-input assertion for this marker. Do not loosen normal user-message invariants or claim this is a manual desktop-composer test.
- Sent `Reply only OOB_PHONE_NATIVE_SSH_AD. Do not use tools.` through the real phone composer/send control. Native `wait_threads` on the SSH host reported completed turn `01a0a430-f894-77d2-8ba0-c8a4efdb451d`; the same backend contained exactly one user input and one reply. Physical verifier passed after returning the app to foreground. A Back key intended to dismiss the keyboard had instead backgrounded the app, so the first visible-history assertion failed on the Android launcher. This run proves foreground restoration, not uninterrupted foreground rendering for AD.
- The native `read_thread` tool returned the completed turn with empty items for both fresh turns, while raw daemon history contained the actual items. Native GUI text rendering remains UNVERIFIED. The supported navigation tool opened the isolated task for user inspection; GUI automation remains prohibited. The original calling task has not been migrated or validated for phone access.
- Physical device remains PJE110 / Android16 / debug0.6.3 code16, candidate SHA256 `477eec070dfb174f1edfaa3696e85aecaefd5113ab4a369246318ee8db57feb6`. No new APK was built for this connection test, no existing prompts were replayed, and no locks were removed.

#### Desktop open still reports another application owns the session

User screenshot after supported desktop navigation showed the active-writer banner. The screenshot does not identify its session or host, so the exact UI route remains unproven. Official deep-link reference (`https://learn.chatgpt.com/docs/reference/commands#tasks`) documents `codex://threads/<id>` as local-only; the available navigation tool has no host parameter. Do not use either as proof of SSH GUI access.

The existing connection helper now returns configured `desktopSshHost`, owner cwd and session ID together, with an explicit instruction to enter through that SSH connection. It does not invent a host-qualified deep link or claim that configured host identity verifies GUI routing. With no desktop host configured it reports that missing setup. Extended MCP integration test passed; the live helper returned the configured SSH alias and isolated session identity against the real daemon. This is a connection-guidance correction, not a fix to Codex's native router. No phone runtime code changed. Native GUI acceptance and original local-task migration remain pending.

Native diagnostic logs now resolve the screenshot ambiguity: at `2026-09-15T08:36:00.802Z`, `maybe_resume_started` for the isolated shared test session explicitly recorded `hostId=local`; the visible owner route was `/local/<session-id>`. At `08:36:01.256Z`, that same session's `thread/resume` failed with `-32600` and `already has an active writer`. This proves the incorrect local admission path for the test session, rather than an inference from the generic banner.

Executable routing gate: `node scripts/verify-native-shared-route.mjs LOG SESSION_ID EXPECTED_HOST`. Ran on the live native log with the isolated session and `remote-ssh-discovered:omnibot-shared-local-test`: **FAIL**, observed host `local`. It reads diagnostic logs only, selects the latest admission, and does not interact with the prohibited native UI. A future PASS will prove the logged route only; rendered messages still require separate acceptance. The remaining manual step is opening the isolated workspace from the SSH connection's folder control, rather than using the hostless task-navigation tool.

### 2026-09-16 — Ubuntu bootstrap reports PRoot execve(tar)

- User evidence: screenshot of Agent response to installing Node.js; response quotes `proot error: execve("/system/bin/tar")` and `Failed to extract ubuntu rootfs.`. Full errno, failing device model and installed version were not provided. This is not sufficient to establish the device-specific root cause.
- Existing owner: shared `ReTerminal/core/main/src/main/assets/init-host.sh`, used by terminal UI and Agent terminal tools. No ACP lifecycle changes.
- Corrected bootstrap weaknesses: resolve loader from current APK nativeLibraryDir after caller overrides; explicitly reject missing/unreadable/non-executable loaders; propagate runtime installation failures; probe PRoot/tar before resetting incomplete rootfs, retain original stderr and execution exit code. No automatic command replay or data clear.
- Executable regression: `python3 scripts/test-rootfs-bootstrap.py` (integrated in `scripts/test-agent-runtime.sh`). Process fixture covers stale loader override, missing loader, exec failure preserving partial files and errno, extraction failure without ready marker, subsequent recovery, and restart preserving user files without extracting again.
- Local result: 4 bootstrap tests PASS; `python3 scripts/test-terminal-host-stop.py` 2 tests PASS; shell syntax check PASS. These are host/process-fixture checks, not Android execution tests.
- Real-device entry: `ADB=/path/to/adb node scripts/verify-rootfs-hardlinks.mjs SERIAL full` for isolated real archive/guest execution, plus actual first setup → install Node.js → node --version → close/reopen → repeat in the App. Verify failure/retry on isolated fixture; do not clear existing user rootfs.
- Device status: `adb devices -l` and `adb mdns services` returned no devices. Device/installed version/actual setup outcome unavailable. **待真机验证**; screenshot incident is not claimed fixed or accepted.
- Build result: `:app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart` PASS (15s). Candidate APK `app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk`, SHA256 `18818a8023207352ebf696270e0c857f2e1e31918b968dd0d2c60e93ab38f101`. ZIP inspection confirms updated `assets/init-host.sh` and `lib/arm64-v8a/libproot-loader.so` are packaged. Not installed; no device available.

### 2026-09-16 — Alpine/Ubuntu installation boundary audit

- User asks whether Alpine avoids the Ubuntu startup failure. Both use the same PRoot loader and host extraction script. Alpine rootfs is bundled; Ubuntu uses the verified download path. Offline Alpine packaging does not eliminate host execution failures.
- Expanded `scripts/test-rootfs-bootstrap.py` to execute the production shell wrapper for both distributions (16 cases total): first boot/stale loader, restart, legacy install migration, missing loader, exec failure, corrupt archive/retry, incomplete extraction, missing archive, and invalid ready marker boundaries.
- Reproduced before fix: missing archive cleaned partial files before reporting failure; invalid ready marker with missing layout bypassed execution preflight. Both failed for Alpine and Ubuntu (4 failures). Fixed shared extraction preconditions before any cleanup. After fix: 16/16 PASS; host signal/stdin tests 2/2 PASS.
- Inspected packaged Alpine archive `assets/embedded-terminal-runtime/alpine.tar`: shell, busybox, OS metadata, env, apk and APK database/release entries present; zero tar hardlink members. This reduces exposure to the Ubuntu archive's hardlink-specific failure; it does not prove executable compatibility on Android.
- No ADB device available. First setup, package installation/network interruption, upgrades, and App restart on real Alpine/Ubuntu installations remain **待真机验证**. No claim of complete installation acceptance.

### 2026-09-16 — Fresh Android emulator Ubuntu setup (executed)

- Dedicated new AVD: `OobUbuntuFresh20260916`, serial `emulator-5580`; created with Android 13 Google APIs ARM64 image, Pixel 6 profile, no snapshots. No existing App/rootfs data were reused or cleared from other devices.
- APK: developStandard/debug 0.6.3, SHA256 `2c874d406f1338224420e026e16b9683cc8950966189877accae18fa93b26ad6`.
- Agent-operated App UI: first onboarding page → Ubuntu → Node.js / Web → no extra harnesses → Start setup. Observed actual rootfs download (29,870,567 bytes), PRoot/system tar extraction, apt/dpkg installation, final `100%` / `Your development setup is ready`. Setup started approximately 13:57 and completed 14:02 CST (~5.5 minutes). No execve(tar) error reproduced.
- Runtime verification entry (emulator only, restarts App): `ADB=/path/to/adb python3 scripts/verify-ubuntu-startup.py emulator-5580 /tmp/ubuntu-result.json`. Executes installed production init-host as App UID, validates ready marker, Ubuntu ID, apt, Node >=22, npm, real JS execution; writes a fixture file, force-stops/relaunches App, verifies persistence and Node in a new process, removes fixture.
- Executed result: PASS. Ubuntu 24.04.4 LTS; apt 2.8.3 (arm64); Node v22.23.2; npm 10.9.8; `NODE_EXEC_OK` and `RESTART_NODE_OK`.
- Raw current-run evidence: `/tmp/oob-ubuntu-20260916/setup-success.xml` and `/tmp/oob-ubuntu-20260916/runtime-result.json` (temporary local artifacts).
- Non-blocking run-as warnings: sdcard permission and unavailable /proc/self/fd binds; Node and persisted file checks still passed. UI remained at 98%/Verifying while package installation was still running; this is a separate progress-display finding, not fixed in this test turn.
- Scope: actual Android emulator UI and installed-runtime acceptance, not physical-device acceptance. Original screenshot device failure was not reproduced; its exact root cause and real-device fix remain **待真机验证**. Script covers post-setup runtime/restart; initial onboarding UI steps above were agent-operated, not automated by that script.

### 2026-09-16 — Actual recording, registration and Agent GUI acceptance

- Same fresh Android 13 ARM64 emulator `emulator-5580`, developStandard debug 0.6.3, APK SHA256 `2c874d406f1338224420e026e16b9683cc8950966189877accae18fa93b26ad6`. No physical device connected: **待真机验证**.
- Actual UI: send `/record`, grant overlay/accessibility, open Settings home, Start recording, tap Battery, Finish. Run `human_1789540216954_b6ff1a9a` succeeded with one received/committed action, before/after state IDs, zero pending/failed/incomplete actions. Battery page visually observed. Only recording/persistence passed; no semantic Function compilation or replay claim.
- Automatic registration and explicit `Register Function` both FAILED: `FUNCTIONS_REQUIRED: functions are required unless enhance=true`. `OmniFlowFunctionRegistration.saveRunLog` currently supplies only `run_id` and `run_log`; the installed runtime requires Function drafts or model enhancement. GUI replay BLOCKED by registration; not executed. Runtime additionally reported OmniTransfer backend unavailable, so mapping/replay acceptance remains unproven.
- Sent normal Agent goal: `Open Android Settings and show Battery. Do not change any settings.` The Agent invoked GUI execution, but RunLog `gui-63a89521-9540-4a6d-8e6f-a22165016a23` FAILED with zero actions: VLM planner HTTP 400 from GLM-5.1, upstream fallback connection timeout. The error does not prove a transient outage; request/model compatibility still needs diagnosis.
- Executable evidence gate: `python3 scripts/verify-gui-lifecycle-evidence.py docs/testing/fixtures/gui-lifecycle-20260916`. Actual result **1 PASS, 2 FAIL**, exit 1. Checked-in small real-run samples omit request/fallback identifiers. This gate checks exported runtime evidence; it does not automate the above UI operations, prove replay or replace live retesting. Full source screenshots/state bundles are not included in these small fixtures.
- Test-tool interference: the first recording attempt `human_1789539895905_b5df9828` was invalidated by `uiautomator dump`, which attaches UiAutomation with flags=0 and suppresses accessibility services. Excluded that attempt. Subsequent active GUI checks used only ADB screenshots/input; do not use UIAutomator while recording/replaying unless accessibility suppression is explicitly disabled. Restart/resume, repeated replay and cross-device mapping remain NOT TESTED.

#### Registration contract correction, still not accepted end to end

- Updated the existing `OmniFlowFunctionRegistration.saveRunLog` request to pass `enhance=true` with the immutable RunLog. The installed Python bridge explicitly requires this when no authored Function drafts are supplied. No local action-to-Function compiler or coordinate replay was added.
- Extended the existing `OmniFlowToolChannelManualRecordingTest`; focused Gradle test result 5/5 PASS. APK build PASS, installed successfully with `adb install -r` on emulator-5580. SHA256 `0ed51c2b0cdd5b0ff94f8858e382b239ebeb58ddebd76092b401a4a4f0f08094`.
- Reopened the persisted source RunLog and pressed Register Function through the real UI. The request reached GLM-5.1; model stream failed after 23.5 seconds (HTTP response status 200 does not establish stream success). A second explicit attempt also failed after 27 seconds. The spinner cleared and the registration button returned; no registered Function was verified. Exact stream error still needs capture. Do not mark registration/replay PASS from the parameter fix.
- Execution control review reached the existing `OmniFlow.execute` coroutine, `ExecutionControls`, and `ExecutionOverlay` pause/stop gates. This is source inspection only; actual takeover/resume/stop, no-late-action, and subsequent-run acceptance are still pending.

#### Model transport diagnosis and error visibility

- Found that the shared registration result parser discarded canonical nested `error.message`/`error.code`, replacing actionable failures with a generic registration failure. Fixed that boundary and added executable regression coverage; focused Flutter tool-client suite 5/5 PASS.
- Real App registration capture `/tmp/oob-register-capture2/28.png` proves a separate failure: HTTP 200 returned an ordinary `chat.completion` JSON containing generated Function content, while the caller expected SSE. The Python `model_turn` payload omits `stream`; Kotlin `ChatCompletionRequest` defaults it to false. Corrected `OmniFlowModelHost` to set `stream=true` at its existing streaming-client boundary, without changing model selection or action coordinates.
- `OmniFlowModelHostTest` now exercises absent/false/true stream inputs against the actual host and asserts the outgoing streaming request. Suite 9/9 PASS; APK build PASS. This explains the registration HTTP-200 failure, not the earlier GUI planner HTTP 400. Actual registration after this second fix remains pending.
- Added `ExecutionRegistryTest` lifecycle coverage for repeated stop (one cancellation), stale cleanup after a new run begins, rejecting the old run's stop, and stopping the next run. Focused suite PASS. These unit checks do not establish real takeover/resume or no-late-action acceptance.
- Installed stream-fix APK on emulator-5580 (SHA256 `fd6cda1136458cac1dee46e97f7ca76daaed22eb51bdb590c1f7170f56336991`) and repeated actual Register Function. At 14:58:01 the model stream completed normally after 51.4s, then canonical registration returned `RUN_LOG_COMPILE_FAILED: function_enhancement_split_arguments_incomplete`. This proves the SSE mismatch is removed for this operation, but registration remains FAIL. Installed `functions/assets.py` validates that the `arguments` map keys exactly equal generated Function IDs; no missing keys have been fabricated or validation bypassed. The installed runtime's authoring contract must be reconciled with the current canonical compiler before claiming the full pipeline works.
- Packaging audit: the authoritative dependency used by `scripts/build-omniflow-component.py` is adjacent **OmniFlow-exp**, pinned in `runtime.properties` to `d453a47e55b6a2c30c99b54b56bbea9c4578de44` (runtime `2026.08.21.catalog.dc201798.d453a47e`). The separate OmniFlow checkout is not this App's package source. Current OmniFlow-exp uses `functions/compiler.py`; its bridge requires an existing Function when `enhance=true`, whereas the installed pin requires enhancement for RunLog-only input. A blind source copy/upgrade is therefore invalid; migration must wire model authoring through the canonical compiler and cover host contract compatibility.
- Repeated ordinary Agent goal after the stream fix at 15:01. Run `gui-62d9067e-5c0c-4867-bf47-87ef8f6a695d` failed with zero actions and the same GLM-5.1 HTTP 400/upstream fallback timeout. Raw test RunLog: `/tmp/oob-gui-20260916/agent-run-after-stream-fix.json`. Controls appeared then disappeared on failure. The failure happened before a stop click was performed, so this attempt is **not** pause/stop acceptance. No inferred success or model-switch workaround was used.

#### Actual planning-stage Stop and restart, 2026-09-16 15:06–15:14 CST

- Device: emulator-5580, Android 13 ARM64; installed 0.6.3 debug APK SHA256 `fd6cda1136458cac1dee46e97f7ca76daaed22eb51bdb590c1f7170f56336991`. Physical device absent: **待真机验证**.
- Sent `Use the GUI tool to open Android Settings. Do not use terminal commands.` Waited for a fresh GUI overlay, then clicked its actual Stop button during initial planning. Run `gui-c9f099e9-5901-4851-ac7a-8c5458269e6a` finished with `function_stopped`, zero dispatched steps. ACP returned `stopReason=cancelled`; chat showed `任务已取消`.
- Observed the same stored RunLog/events again around 15:11, then force-stopped and relaunched App. Cancelled user/assistant history remained visible. Export after restart matched the pre-restart RunLog; one terminal event, no later events or committed actions. This only proves this planning-stage case over the observed interval, not cancellation of an in-flight physical gesture.
- Evidence: `docs/testing/artifacts/gui-stop-2026-09-16/` contains actual before/after RunLogs, events and screenshots. Executable check: `python3 scripts/verify-gui-lifecycle-evidence.py docs/testing/artifacts/gui-stop-2026-09-16 GuiStopEvidence` — 1/1 PASS. It checks captured evidence, does not automate UI or replace a fresh live run.
- Next GUI prompt after force-stop was gated by missing accessibility permission. System Settings showed Omnibot Off. Restored On through actual Settings UI. No new GUI overlay appeared before permission recovery, so takeover/resume was NOT tested. No automatic resume claimed; the permission card remained in chat.
- Registration still FAIL (canonical split arguments validation), ordinary GUI execution still FAIL (model HTTP 400), replay BLOCKED. Full pipeline is not accepted.

## 2026-09-16 GLM 自检查、有限恢复与学习持久化

- 请求：模型错误后可诊断、有限恢复、修复经验跨重启保留。
- 53 项 JVM / 4 项 Node 通过，入口及覆盖见 [provider-recovery-20260916.md](provider-recovery-20260916.md)。原 GLM GUI 400 fixture 已用于可执行的不重放测试；技能刷新删除学习数据的缺陷已修改。
- 真实接口：GLM-5.1 文字/文字工具通过，有效图片请求 400＋gateway fallback timeout；GLM-4.6V 图片工具探针通过。未修改用户模型配置。
- emulator-5580 / 0.6.3 两次实际启动保留学习数据、恢复打包脚本。仅为持久化验证；原 GUI 操作与失败后可继续发送的本轮端到端验收仍未完成，**待真机验证**。

#### GUI physical-dispatch pause/cancellation boundary, 2026-09-16

- Source trace found suspended work between the existing pause gate and physical dispatch: overlay avoidance/progress callbacks, accessibility readiness, and pre-action observation. Added `beforeDispatch` to the existing `AndroidGuiEnvironment.act` boundary and wired it to the same OmniFlow `beforeOperation` owner. It runs after readiness/observation and checks coroutine cancellation immediately before platform dispatch. The existing owner also rechecks overlay pause after its hook returns. No second lifecycle, retry loop or coordinate fallback was added.
- Extended `AndroidGuiEnvironmentTest` with deterministic suspension tests: no physical dispatch while the gate waits; exactly one dispatch after release; cancellation while paused produces zero dispatches even when the gate is later released. Entire suite: 11 tests, 0 failures/errors. ExecutionRegistry/ExecutionFeedback focused suites and the APK build passed. These are JVM tests with a fake Android platform; they do not prove actual touch interruption or stale-screen recovery after manual takeover.
- Built and installed developStandard debug APK SHA256 `4cf142251a9e08610cc3b063986af61466de9125adc82369a74acda956bb585f` on emulator-5580 using `install -r` (Success). Launch returned to accessibility permission flow; this new APK has not completed live takeover/resume acceptance. **待真机验证**. Registration, replay and model HTTP 400 blockers remain open.
- Regression command: `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon --no-parallel :androidgui:testDebugUnitTest --tests '*AndroidGuiEnvironmentTest'`.

#### Revalidated configured GUI execution, 2026-09-16 15:21 CST

- A concurrent user task, “修复GLM模型错误与自恢复”, persisted a dedicated GUI scene binding to GLM-4.6V through the existing debug configuration receiver and executed the normal GUI runtime through DebugVlmTaskReceiver. This task did not change that binding or claim authorship of the run. Avoid concurrent UI control while that task verifies chat attachments.
- Independently read actual emulator RunLog `debug-vlm-450ce60b-7df5-4542-9a66-431c8865efa3`: success=true, status=succeeded, four actions, five successful model calls, resolved model GLM-4.6V. Actual screenshot showed Android Battery page. Source goal is read-only opening Settings/Battery. Two initial actions navigated the existing permission UI, followed by open_app Settings and click Battery. This is a debug-entry runtime test, not a fresh normal chat-entry acceptance.
- Preserved exports at `docs/testing/artifacts/gui-configured-model-2026-09-16/`. Command `python3 scripts/verify-gui-lifecycle-evidence.py docs/testing/artifacts/gui-configured-model-2026-09-16 GuiLifecycleEvidence.test_goal_driven_gui_run_completes` PASS. The previous GLM-5.1 HTTP 400 samples remain valid failed runs; the new success proves this configured visual route, not a generic HTTP 400 repair or all model compatibility.
- Repeated canonical registration of original manual recording using DebugOmniFlowToolReceiver, run_id `human_1789540216954_b6ff1a9a`, enhance=true and a read-only Battery-navigation instruction. This path resolves the RunLog through the host rather than exercising the UI snapshot wrapper. Started 15:22:29 CST; result pending at this checkpoint. Do not infer registration or replay success from GUI execution.

- Registration terminal at 15:23:46 CST: success=false, RUN_LOG_COMPILE_FAILED / function_step_contract_invalid. Saved actual response as `registration-result.json` in the above artifact directory. The unchanged canonical parser rejects invalid Function step structure; registration and replay remain unaccepted.

#### Canonical semantic-authoring transport integration (not deployed), 2026-09-16

- Updated canonical adjacent OmniFlow-exp on branch `codex/oob-authoring-host-callback`, commit `40fb6fd6`, pushed to origin (main unchanged). Compiler accepts a host `complete_json` callback and retains its existing three-attempt semantic validation/materialization owner. Device Bridge accepts RunLog-only enhance=true and uses that callback; existing-Function enhancement remains unchanged. Rejected proposals return FUNCTION_AUTHORING_REJECTED and do not register the hidden evidence fallback as a successful Function.
- Focused upstream validation: `.venv/bin/python -m pytest -q tests/test_device_runlog_compiler.py tests/test_function_render_binding.py` — 43 passed. New tests exercise invalid proposal feedback followed by compiler-owned action generation, and three invalid proposals with no Function published. These are Python tests with a fake host model, not real registration acceptance.
- App runtime remains pinned to the older bundled commit; these changes are NOT yet packaged or installed. Migration inspection also found the current Bridge discards the temporary compiler transfer-state catalog while saving Function definitions. Source-state persistence must be fixed and tested before package migration/replay. No source-coordinate fallback is permitted. Existing on-device registration failure remains unresolved until a new package is actually validated; **待真机验证**.

#### Registered source evidence persistence (upstream, not deployed), 2026-09-16

- Canonical OmniFlow-exp commit `dccda921` on `codex/oob-authoring-host-callback` persists the compiler source catalog and copies referenced screenshots to content-addressed files before publishing Function definitions. Bridge source lookup first reads this Store-owned evidence, retaining the existing host path for older non-catalog sources. Conflicting content under the same state identity fails rather than overwriting source evidence. The aggregate catalog run_id identifies its Store; it does not claim to represent a single trajectory.
- Python tests cover original screenshot deletion plus Bridge re-instantiation, preserving earlier states when registering another run, idempotent re-import, and rejection of conflicting state content without altering the existing catalog. Compiler/render-binding suite: 45 passed; invocation/single-tool/device-compiler suite: 25 passed. These use synthetic fixtures and a fake host, not actual device replay; **待真机验证**.
- Not yet packaged in OOB. Current packaged properties still pin August OmniFlow/OmniTransfer and the old association checkpoint, whereas current OmniFlow requires canonical V10. Packaging must upgrade the compatible source/checkpoint/runtime dependency set together; copying just the new compiler into the old package would not be a valid acceptance build.

## 2026-09-16 聊天上传图片：实际视觉模型、错误提示与重启

- 用户明确是聊天上传图片。通过聊天模型选择器将当前模拟器 Provider 默认模型持久设为 GLM-4.6V，真实上传 PNG 得到正确颜色，未用 GUI 配置替代聊天配置。
- 原图片 HTTP 400/422 增加受控 failureKind，经原 ACP/runtime 投影到可操作提示；不删除图片、不隐式切换 Provider、不重放已开始的 turn。
- 60 JVM / 64 Flutter 通过；最终新 APK、emulator-5580 / 0.6.3，第二张不同颜色图片、唯一回复、正式完成、重启历史与模型选择共 8/8 步通过。首轮自动化失败与准备失败明确保留。
- 可执行入口、原始证据和限制见 [chat-image-20260916.md](chat-image-20260916.md)。**待真机验证**；未覆盖全部图片格式、缓存清理及外部 Harness。

## 2026-09-16 Provider API Key 输入期间保存竞态

- 反馈：模型列表 401，服务端未收到 Authorization；是否填写时丢失。
- 可执行用例：`ui/test/features/home/pages/model_provider_setting/model_provider_setting_page_test.dart` → `editing API key during pending save preserves the newer key`。
- 旧代码复现失败；修复后 Provider 页面、配置和发现相关 41 项通过。
- 证据与边界：`docs/testing/provider-key-save-20260916.md`。截图具体触发原因未确定；待真机验证。

## 2026-09-16 模型列表请求固定 Provider 身份

- 反馈：排查密钥填在 A、请求却使用 B，以及保存修复后的其他问题。
- 可执行用例：`ui/test/services/model_provider_config_service_test.dart` → `implicit discovery stays on resolved provider after editor switches`。
- 旧代码漏传解析后的 profileId；新代码固定快照身份。42 项 Provider 回归通过。
- 证据：`docs/testing/provider-identity-20260916.md`。待真机验证。

## 2026-09-16 Provider 设置轻量校验与保存等待

- 要求：减少耦合，缺少凭据明确提示、空鉴权头拒绝、保存成功后刷新。
- 测试：`model_provider_setting_page_test.dart` 的 empty credentials / empty Authorization / refresh waits for in-flight credential save 场景。
- 47 项 Provider 测试通过；真机未连接，待真机验证。
- 记录：`docs/testing/provider-settings-guards-20260916.md`。

## 2026-09-16 自检鉴权一致性与持久化故障历史

- 要求：增强既有 skill，失败可继续，修复经验可持久保存，避免额外耦合。
- 可执行：`scripts/provider-recovery.test.mjs`、`ProviderFailureJournalTest`、`BuiltinSkillAssetsTest`、`HttpAgentLlmClientTest`。
- 范围/结果：`docs/testing/self-recovery-history-20260916.md`；待真机验证。

## 2026-09-17 聊天图片上传检查

- 图片编码、工作区读取、选图及附件透传现有测试通过。
- 新发现待验证边界：历史快照保留缓存路径后的清理/重发；失效工作区图片降为 ResourceLink。
- 新边界端到端测试未实现、未运行，未宣称修复；待真机验证。
- 记录：`docs/testing/chat-image-audit-20260917.md`。

## 2026-09-17 文件传输持久化修复

- 要求：修复聊天附件缓存失效、历史重发、失效本地文件的处理。
- 新用例：`ConversationAttachmentPersistenceTest`，与图片/工作区附件测试一起运行。
- 修复与证据边界：`docs/testing/file-transfer-20260917.md`。
- 端到端 picker/Room/重发/模型收取与真机验收尚未完成；目标保持进行中。

### 文件传输 Android/Room 集成补充

- 入口：`python3 scripts/verify-attachment-persistence.py --serial DEVICE --output result.json`（已安装当前 Debug 包）。
- 模拟器真实存储验证：PNG/文本、删除缓存、旧快照、重复 upsert、prompt 路径恢复、重启、清理均通过。
- 新补修：重复原生 upsert 复用既有持久附件。实际选图到模型收取和真机验收仍未完成。

### 2026-09-17 — Unsupported document feedback and persistent Agent hints

See [document-parser-feedback-20260917.md](document-parser-feedback-20260917.md).
Executable: AgentFileReadSupportTest and the existing PDF branch of
file-read-provider.mjs / xiaowan-file-read-regression.en.json. Requires unsuccessful
content-read status, document_parser_required, retained original artifact, and a
bilingual parser table in the loaded file_read definition. Device journey for this
change not run; 待真机验证.

### 2026-09-17 — Restore Vibe Builder and generated App access

See [vibe-app-restoration-20260917.md](vibe-app-restoration-20260917.md).
Executable coverage: sandbox JVM suite, plugin_market_page_test.dart (open/pin,
identity and disabled controls), scripts/verify-vibe-package.py (main APK assets).
Device generation/open/shortcut/restart/update/data retention acceptance pending;
待真机验证. Restore existing plugin capabilities without a second Agent lifecycle.

### 2026-09-17 — Real Agent-created App acceptance

[vibe-app-live-20260917.md](vibe-app-live-20260917.md): real chat/model build,
failed Android SVG writes, model-generated SQLite UI mismatch, corrective publish,
actual Save click, SQLite single-write assertion, republish data retention and
force-stop/reopen. Entry points: vibe-build-notes.en.json, vibe-notes-repair.en.json,
scripts/verify-vibe-notes-ui.py. Emulator final pass after repairs; 待真机验证.

### 2026-09-17 — 执行中心统一接口、手动录制与 Function 回放

- 反馈：`runtime_tool_not_declared:open_target_page`，缺手动录制按钮，页面耦合 OmniFlow，用户要求真机操作。
- 入口：`ui/test/features/task/run_log/`、`manual_recording_flow_controller_test.dart`、`settings_page_test.dart`；34 项通过。
- 真机脚本：`scripts/verify-execution-center-device.py`，使用真实已录制 Settings 搜索流程；UI 点击执行、读取 canonical RunLog、目标页面断言、强停后持久化。
- PJE110 实际录制/保存/回放/运行详情通过；强停收回无障碍导致首次恢复失败，系统 UI 重新授权后再次回放通过。保留失败，不宣称免授权恢复。
- 报告与已运行证据：[execution-center-20260917.md](execution-center-20260917.md)。自动生成标题泛化仍待改善；其他应用与所有参数类型未逐一真机验收。

### 2026-09-17 个人记录 Demo 录屏
- 请求：录制实际操作直到打开生成的 App 页面。
- 执行：`python3 scripts/record-vibe-notes-demo.py emulator-5580 docs/testing/artifacts/vibe-demo-20260917`
- 复用 `verify-vibe-notes-ui.py`：插件详情打开、真实输入和保存、SQLite 恰好一条、进程重启后再次打开并显示同一记录。
- 结果：模拟器 0.6.3 (16) 通过，34 秒录屏；`artifacts/vibe-demo-20260917/result.json` 与 `personal-notes-demo.mp4`。本次录制已有模型生成 App 的使用流程，不包含生成过程。不涉及新增产品代码；相关修复仍待真机验证。

### 2026-09-17 — DSH 流式大段空白反馈与模拟回放

- 回归入口：`ui/test/features/home/pages/chat/widgets/dsh_streaming_layout_test.dart`；真机回放：`ui/test/device/dsh_streaming_layout_live.dart`。
- 已复现并修正：完成后正文仍未完全显示；停顿后下一批逐字输出延迟增长。覆盖长思考、多段 Markdown、官方完成和历史重新加载，83 项相关测试通过。
- PJE110 / Android 16 独立测试宿主：3 项模拟 DSH 事件回放连续两次通过（含 Dart VM 重启）；不等同于真实模型请求验收。
- 不将这两个显示问题等同于用户所有“大段空白”场景；原应用完整链路待真机验证。详情：[dsh-streaming-layout-20260917.md](dsh-streaming-layout-20260917.md)。

### 2026-09-17 人生经验值从零创建及全程录屏
- 需求：真实发送创建请求，录制直到发布打开，并验收打卡、升级、经验历史、自定义习惯、重启保留。
- 可执行输入：`scripts/fixtures/user-scenarios/life-xp-build.json`；入口 `scripts/verify-agent-user-journey.mjs`、`scripts/record-agent-demo.py`、`scripts/verify-life-xp-demo.py`。
- 结果：一次生成失败；继续创建及两轮模型修正后，单日核心流程在模拟器实际通过。跨日/长期数据问题仍存在，待真机验证。详见 `life-xp-demo-20260917.md`，原速与加速视频、各次失败/通过结果均保留。

### 2026-09-17 Builder owns tests and repairs; supervisor audits evidence
- Persisted in Vibe Builder 0.2.2 Skill and workflow reference; covers actual Life XP failures, executable tests, real business/UI readback, explicit blocked tests and no fabricated completion.
- Execution: `:app:syncPluginAssets`; `python3 scripts/verify-vibe-package.py app/build/generated/plugin_assets/main`. Compare all critical runtime files byte-for-byte and reject stale APKs.
- Prior `life-xp-build` scenarios and `verify-life-xp-demo.py` remain behavioral regression; 0.2.2 live model behavior not yet run, no updated APK installed, 待真机验证. See `vibe-self-validation-20260917.md`.

### 2026-09-17 — 小万读取外卖通知能力核验

- 需求：通过通知访问权限读取其他 App 通知，不使用手机操控。
- 真机 PJE110 / Android 16 / 0.6.3 (16)：发送通知权限已授权，但无通知监听服务及对应授权；该直接读取能力未实现，不能计为读取成功。
- 执行入口：`scripts/verify-notification-read-capability.py`、`scripts/verify-notification-read-capability.test.py`（3 项通过）。记录：[notification-access-20260917.md](notification-access-20260917.md)。


### Built-in notification reading — 2026-09-17
- Request: read selected apps' delivery notifications directly through Android notification access.
- Executable regressions: `NotificationAccessTest`, `ui/test/features/home/pages/settings/notification_access_page_test.dart`, `scripts/verify-notification-read-capability.test.py`, `scripts/verify-notification-read-device.py`.
- Boundaries: default denial, app isolation/revocation, system revocation, notification replacement/removal, process restart, existing Agent catalog registration, settings resume.
- Results: JVM 4, Flutter 3, audit parser 4 passed; physical synthetic lifecycle/restart/revocation passed on PJE110 b49f281b Android 16, app 0.6.3/code 16. Live model invocation and real food-delivery order not tested.
- Details/artifacts: [notification access](notification-access-20260917.md), `artifacts/notification-access-20260917/{granted,restart,revoked}.json`.


### Bulk notification consent and real Agent memory demo — 2026-09-17
- Requests: 全部开启; real message source; live event listening; Agent writes and recalls memory without mocked provider/tool results.
- Executable UI journeys: `notification-message-memory.json`, `notification-clock-memory.json`, `notification-memory-recall.json` under `scripts/fixtures/agent-user-journeys/`; canonical history assertion `scripts/assert-notification-agent-demo.py`.
- Results: real Clock notification reached `notifications_wait`, then Agent daily memory write and recall passed on physical PJE110 b49f281b, conversation 87. SMS returned empty and timed out; SMS body is not verified. Bulk consent persisted across app restart. Native tests 6 passed, Flutter bulk test passed, focused analyze clean.
- Boundaries: current installed apps only, system SMS included, future apps off; per-turn wait max 120 seconds, cancellation removes collector; no permanent background Agent subscription.
- Evidence: [notification access report](notification-access-20260917.md).

### Natural recent-message discovery — 2026-09-17
- Input asks about recent messages without naming tools or apps.
- Executable: `notification-recent-messages.json` UI journey plus `assert-notification-agent-demo.py` RECENT branch.
- Physical conversation 89 passed: automatic app discovery and 12 actual notification reads, four sources with bodies. Evidence `notification-access-20260917/recent-messages-agent.json`.
- Retained notifications only; group summaries count as records; zero notifications does not prove no unread/history messages. Agent wording overstated all-app coverage, so that semantic claim is not accepted.

### Recent screen recording invisible to Agent — 2026-09-17
- User report: just-created screen recording cannot be found.
- Physical reproduction: actual MP4 exists, but app UID stat returns Permission denied; Agent terminal saw an empty Screenshots directory and incorrectly inferred absence.
- Executable: `scripts/verify-recording-file-access.py SERIAL RECORDING OUTPUT_JSON`. Current test is failing as expected; permissions/product behavior not changed.
- Details: [recording file access](recording-file-access-20260917.md).

### Action ends after one planning response — 2026-09-17
- Physical conversation89 user41104: plan text, zero tools, end_turn; prior continuation user41097 does execute a tool.
- Executable `scripts/verify-agent-action-turn.py SERIAL USER_ENTRY_ID OUTPUT`; latest action fails, previous action passes.
- Diagnosis status: runtime normal completion with no executed tool confirmed; model omission versus provider/parser loss not established, no fix claimed.
- [Evidence and boundary](agent-plan-only-20260917.md).

#### Plan-only action completion mitigation — subsequently reverted at user request
- Raw provider logs confirmed two stop responses with zero tool calls, not parser loss.
- Historical mitigation added a bilingual current-turn execution contract; user subsequently requested rollback. Those added blocks are now removed, lifecycle unchanged; earlier passes do not certify the reverted build.
- Executable real-model journey `agent-action-recording-continuation.json`, checked by `assert-agent-action-recording.py`.
- PJE110 b49f281b, original conversation89: four consecutive action/correction turns (including restart) passed with actual tools; copied recording hash matches source. Prompt tests7 and build passed.
- Evidence: `artifacts/agent-plan-only-20260917/fixed-consecutive-turns.json` and `provider-fixed-contract.json`.

- OmniInfer 默认预编译安装：`scripts/test-omniinfer-skill.py`（18 PASS）、Provider 设置页（26 PASS）；随附测试 APK 的准备/重复使用/校验失败拒绝/不触发源码构建。模拟器系统更新和优化 CPU API 验证 PASS，正式本地 Agent 与真机完整安装复用待结果。详见 `omniinfer-local-skill-2026-09-17.md`。

- OmniInfer 远程 APK：固定 Release 资产、SHA-256、下载后原子发布、缓存复用、失败不回退构建；执行 `python3 scripts/test-omniinfer-skill.py`（18 PASS）。模拟器实际远程下载通过，真机下载与完整系统安装结果见 OmniInfer 当日报告。

- User-requested prompt rollback / 0.6.3 (16) Release: added execution-contract blocks removed; 7 existing prompt tests pass with rollback guards; productionStandardRelease test-signed APK installed on PJE110 b49f281b, normal UI response and restart retention passed. Original plan-only defect is not certified fixed after rollback. See `agent-plan-only-20260917.md` and `rollback-release-*.json` evidence.

- 本地 Provider 无法拉取模型：`scripts/verify-omniinfer-model-list-lifecycle.py SERIAL OUTPUT`；真实真机先宿主前台查 models，再切主 App 等 30 秒查 models。PJE110 前台 PASS、后台 timeout FAIL；2 秒即时切换通过。API 路径正确，后台可用性待修复。

- PJE110 模型不显示根因确认为 OplusHans 厂商后台冻结。系统允许宿主后台行为后，30 秒后台 models 回归 PASS，真实 Provider UI 已显示 Qwen 模型；见 models-background-fix.json。安装 Skill 已加入后台权限与跨 App 验收；服务重启边界待当前对话结束。

- 更正本地模型后台验收：30秒 PASS 不代表持久修复。默认120秒、核对前台App的受控真机回归 FAIL；正式对话仍无输出，旧CPU库采样显示注意力和点积计算。之前“已恢复”仅短时，不代表修复完成。

- 0.6.3 Release physical Computer Sessions failure: reproducible DNS failure for expired/unavailable temporary Bridge tunnel; server cloudflared absent, normal-domain DNS works. `verify-session-list-visible.py` red on Computer, no error on Local. Remote recovery pending endpoint restoration/re-pairing. See `remote-session-endpoint-20260917.md`.

- 2026-09-18 去掉固定4工具并发上限：连续parallelSafe组全部启动，顺序边界与取消机制保留。旧实现peak4红灯，新实现71项执行器测试通过、Release构建安装通过；真机实际8工具并发待验证，模型/可观测性未通过。详见 tool-parallel-no-fixed-cap-20260918.md。

## 2026-09-18：OmniFlow Release 无法启用

- 需求与证据：[Release 启用回归](omniflow-release-enable-20260918.md)。
- 执行：`python3 scripts/verify-omniflow-release-enable.py --serial SERIAL --out OUTPUT`；实际 minified Release 真机启用、列表调用、强停恢复。
- UI 失败反馈/重试：`ui/test/features/home/pages/plugin_market/plugin_market_page_test.dart` 中 `enable failure retains native reason and permits retry`。
- PJE110 真机通过；64 项组件单测与12项页面测试通过。模型任务与跨设备迁移不在本次验收范围。

## 2026-09-18：增强必须走 OmniFlow 完整 authoring

- [完整 authoring 真机验收](omniflow-authoring-20260918.md)。入口只传 run_id/enhance，支持多个 Function，返回列表刷新。
- 执行：`scripts/verify-omniflow-authoring-device.py --phase record|author|verify|replay|restart`（逐阶段执行，环境参数见文档）。复用现有 Journey，参数与绑定必须来自真实模型，不允许注入产物。
- 上游回归：OmniFlow-exp `tests/test_device_runlog_compiler.py::test_enhancement_uses_frozen_screenshot_after_android_recaptures_state`，同时覆盖轻量增强和完整 authoring，拒绝源证据被重采样替换。
- 真机完整链路通过，Release 换参数执行再次通过；15项页面测试、17项上游编译/bridge测试、10项运行包测试通过。

## 2026-09-18 OmniFlow authoring 录屏复测

- 入口：`scripts/verify-omniflow-authoring-device.py`（record / author / verify / replay）。
- 实际真机新录制两次：第一次编译拒绝；第二次生成两份无参数 Function。参数化验收均失败，不能标记全链路通过。verify 在断言前持久化真实产物。
- 之前已生成的参数化 Function 独立执行 wifi 通过；与本次新产物严格区分。
- 录屏、原始输入、失败断言及结果：[复测证据](artifacts/omniflow-authoring-demo-20260918/README.md)。待修复：authoring 稳定性和 rejected_error 透出。

- 2026-09-18：官方 OmniInfer AAR 按需下载、主 App 内加载、远程模型、同进程后台 120 秒、正式 Agent 任务与重启历史：见 [验收记录](omniinfer-downloadable-runtime-2026-09-18.md)。可执行入口 `scripts/verify-omniinfer-payload.py`、`scripts/verify-omniinfer-phone.py --in-app`、`scripts/verify-omniinfer-in-app-background.py`、`omniinfer-in-app-agent.en.json`。API/下载模拟器通过；正式 Agent 与真机状态以记录为准，不能用 API 成功替代。

- 2026-09-18：OmniInfer HTP 真机重试；API 推理通过、完整 Agent 文件任务失败，见 [记录](omniinfer-htp-phone-2026-09-18.md)。

## 2026-09-18 Codex Remote 连接与协同 demo

[真实手机验收](codex-remote-demo-20260918.md)：复现远程关闭、临时域名失效、cwd 缺失、ACP v2 配置缺 type、临时 Conversation 被 hash runtime 替换。修复后 PJE110 真 UI 发送创建电脑文件并显示回复通过；电脑协议客户端同 session 发消息，手机被动同步通过。12项 Kotlin、137项 Flutter、真实 v2 配置回归及 LAN ingress 通过。长期执行入口 verify-remote-config-v2.py、verify-remote-chat-device.py、verify-shared-external-send.cjs。重启、原生桌面 GUI、公网/蜂窝待验，不宣称完整覆盖。

## 2026-09-18 — 本地模型可选下载与自动 CPU / HTP

- 需求：不使用本地模型不下载引擎/模型；启用后自动适配硬件，无需用户选择。
- 可执行：`LocalInferenceBackendTest`（5 项通过），`scripts/verify-omniinfer-payload.py`（本次 APK 检查通过，新增模型/组件不得内置断言）。
- 边界：失败回退只在 loadModel 启动阶段，成功选择按系统/组件/模型身份保存，不重放 Agent 请求。
- 记录：[omniinfer-auto-backend-20260918.md](omniinfer-auto-backend-20260918.md)。无 ADB/无线真机，安装与启动/重启/失败恢复 **待真机验证**。

## 2026-09-19 — authoring 编译拒绝原因不能丢失

- 复现：完整 authoring 拒绝仅有通用错误，临时编译报告被删除，Android Error 投影再丢失详情。
- 可执行回归：canonical `tests/test_device_runlog_compiler.py` 18 项通过；Android `OmniFlowManagementResultTest` 验证失败状态与原始诊断保留。
- 持久化、写盘失败、无伪 Function 注册覆盖；不改变 Transfer、绑定或重试 owner。
- [修复与剩余整体范围](authoring-feedback-20260919.md)。无设备，**待真机验证**。

## 2026-09-19 — PDF 正文不能只读元信息

- `AgentPdfReadSupportTest` 使用真实 PDFBox 与真实 PDF 测试文字分页、源文件保留、损坏/空白/密码、临时文件清理；3 项通过。
- `AgentFileReadSupportTest` 同轮通过；旧故意损坏 PDF 的 Provider fixture 改为明确 parse_failed，不能误报正文读取。
- [实现与边界](pdf-body-read-20260919.md)。Office 正文、OCR 未完成；上传/重启/失败后继续 **待真机验证**。

PDF 本轮最终补充：新增真实上传 PDF 样本与重启回读 Journey；解析器回归增至 4 项，原文本 8 项通过，最终 APK 构建成功。设备 Journey 未运行；见上述记录。

## 2026-09-19 — DOCX / XLSX 读取正文而非元信息

- 沿用 file_read 接入本地 OOXML 文字提取，原附件保留，公式/缓存/原始值明确区分。
- `AgentOfficeReadSupportTest` 覆盖真实Office样本、分页、工作表/坐标、共享字符串、错误索引、空文档、损坏和外部引用。
- 实际上传与重启回读 Journey 已添加，未执行；**待真机验证**。见 [实现与执行入口](office-body-read-20260919.md)。

## 2026-09-19 — Remote Codex 排队超时和断线后禁止旧请求写入

- 新用例在旧实现实际2失败；修复后 RemoteCodexAppServerSessionTest 14项通过。
- 原连接身份核验、排队纳入请求期限、未发送请求不发取消、取消通知限时；不重放Agent turn。
- [复现和修复](remote-write-recovery-20260919.md)。用户现指定模拟器真实任务验收；设备网络恢复用例尚未完成。

## 2026-09-19 — 实际附件上传：快速失败崩溃与 ACP 工作区路径

- 模拟器通过系统选择器上传 PDF 实际触发 foreground 启动超时崩溃；TaskRuntime 同一服务握手后 reconciliation 修复。2 项 JVM 回归通过，覆盖安装后同类 46ms 失败不再退出 App。
- 随后真实 ACP 任务暴露 `/workspace/...` 被当 Android 路径，历史持久文件存在仍误报丢失。沿用工作区映射修复，新增长期引用回归。
- 完整正文/重启回读 Journey 仍在验证，不把失败轮次算通过。[证据与执行入口](attachment-runtime-20260919.md)。

后续设备结果：同一 Debug APK，PDF 正文和正式完成通过，原首行格式断言失败保留；继续原会话的重启及真实回读 6 步通过。DOCX/XLSX 各 8 步真实模型 Journey 全通过，包含系统选取、解析正文、正确回答、重启和再次工具读取。文档断言新增同轮 parser/contentAvailable/content 校验，拒绝只有元信息的成功。详见上述报告；不代表最终 Release 或其余大功能已验收。

## 2026-09-19 — 最新图片与损坏文件后的继续操作

- 当前包实际 PNG 上传识图、正式完成、重启历史与模型选择保留：8/8 通过。Recent 列表未显示旧日期测试图的准备失败保留，不冒充模型失败。
- 新增实际损坏 PDF 上传、单次 file_read 失败且没有正文、明确反馈、同会话下一任务成功且不重放工具、重启历史：8/8 通过。
- 复用现有 Journey 和同轮状态断言；[执行入口、设备与证据](image-document-recovery-20260919.md)。

## 2026-09-19 — Authoring 复用意图与完整回放复测

- 增强入口使用已有 instruction 传递业务输入复用意图，15 项页面测试和 Debug 构建通过。
- 英文模拟器真实录制两步、真实生成 search_term 绑定通过；完整 Function 被模型隐藏，单步片段从首页回放 yielded，整体验收失败。
- 保留两次驱动准备失败及后续真实产物；确认 compiler 事实投影缺少动作归一化坐标与节点像素坐标的单位/尺寸说明，待进一步修复验证。[详情](authoring-intent-20260919.md)。

## 2026-09-19 — 官方 authoring 坐标单位和显示尺寸

- canonical compiler 补齐 source_ui 的显示尺寸/单位，保留原动作和节点；18项官方测试、9项实际APK组件测试通过，组件2.2.3已安装，设备文件哈希一致。
- 重新真实录制通过；真实模型 authoring 返回缺失 search_term binding，注册失败，未宣称全流程成功。[验收与下一缺口](authoring-coordinate-20260919.md)。

- 2026-09-19 OmniFlow authoring失败返工：canonical `tests/test_device_runlog_compiler.py` 覆盖反馈原始方案、未知/重复源索引、三次失败报告持久化；`scripts/verify-omniflow-authoring-device.py` record/author/verify/replay/restart复用真实UI链路。2.2.4模拟器真实增强仍失败，完整方案已保存；2.2.5后续结果见 `authoring-feedback-20260919.md`，未宣称全流程通过。

- 2026-09-19 录制浮层污染/执行中心未离开：复用verify-omniflow-authoring-device.py的record与replay-source，非空canonical XML检查和真实两步回放两次通过；失败也保存replay-run。记录recording-page-20260919.md。参数化全流程仍未通过。

- 2026-09-19 authoring参数化完整流程：recording-page-20260919的authoring-path与authoring-mode分别保留两份独立源的真实增强、battery→wifi两步回放、Function强停重启保存。最终2.2.12/Debug；历史拒绝和焦点驱动失败未删除。canonical device_runlog_compiler + function_render_binding共63项通过；脚本等待正式终态/输入焦点和实际字段值后提交。详细边界见recording-page-20260919.md。

- 2026-09-19 Vibe 长历史：SandboxPluginPoolTest 增加 601 行跨页、已发布工具读取第二页并保留筛选、非法边界和整数溢出回归；与合同/Bridge 共 26 项通过，Debug 构建通过。模拟器安装与实际分页尚待执行。模型自修复仍出现复制计算和无条件成功的伪验收，保留失败证据，见 vibe-pagination-20260919.md；整体目标未完成。
- Vibe分页后续设备验收：scripts/verify-vibe-pagination-device.py 真实发布隔离fixture，读取601行、非法查询后业务写入/独立读回、强停重启后读回通过；原始证据 artifacts/vibe-pagination-20260919/restart/result.json。Life XP模型自修复仍失败：已发布UI XP0、调用点仍1000，数据逐字段未丢；保持未通过状态。
- Vibe发布验收入口：实际发布返回namespaced名称、局部名称和参数schema，明确runtimeValidation=not_run以及现有tools_search路径。14项单测通过；模拟器discovery-fixed再次覆盖真实工具、分页、重启通过。模型Life XP自主验收仍进行中。
- 2026-09-19 真实GLM缺失function.name导致正式失败，Vibe production-validation未通过；保留turn-final与terminal-failure。Journey增加同轮终态失败及时退出，python3 -m unittest discover -s scripts -p test_agent_terminal_failure.py 4项通过，真实模拟器错误识别通过；不猜测工具、不重放任务。
- 更正上条Vibe发布提示：当前运行时没有注册tools_search；旧判断只依据残留源码错误。已撤销该入口提示，使用current_request_catalog；14项单测与模拟器current-catalog真实发布/分页/恢复/重启通过。新模型验收current-catalog-resume仍待结果，先前两轮Provider错误不能计通过。
- Life XP独立生产逻辑回归：node scripts/verify-life-xp-production.cjs APP_JS TOOLKIT_JSON，直接执行生产方法，601行统计/历史断言；当前实际失败500!=601及未声明_offset。快照与失败见vibe-self-validation-20260919/current-catalog-resume。合成桥接仅作逻辑测试，不是UI/持久化验收。
- audit-recovery：生产601行统计/历史审查通过（脚本未变），模拟器实际发布页75XP/等级2及强停重开保持，4习惯4打卡逐字段未变。历史倒序累计/跨日里程碑仍待修复；最终模型伪工具文本仍为未解决反馈。
- Release候选构建复现并修复PDFBox可选JP2Decoder的R8缺失类错误，ProductionStandardRelease测试签名构建及签名校验通过。候选尚未设备回归，不计最终Release验收；见release-candidate-20260919.md。

- Bridge后台日志凭据回归：tools/codex-bridge下npm test，实际子进程合成token验证默认隐藏和显式配对兼容，共5项通过。模拟器通过真实设置更换测试端点并加载会话列表；模型任务/断线恢复尚待，见remote-write-recovery-20260919.md。

- Remote执行中断线真实模拟器回归通过：verify-shared-interruption.cjs在原turn inProgress时停止专用Bridge，权威同turn完成一次；恢复Bridge后手机原页自动显示，实际文件正确。精确session/turn证据与UI观察失败保留于remote-recovery-20260919；公网/Release仍未验收。

- Life XP实际renderHistory回归新增verify-life-xp-history.cjs：同日累计、跨时区日期、一次跨多个等级；三个时区真实旧生产代码均失败，baseline保存于vibe-history-20260919。已发起同聊天模型修复，未验收通过。

- file_edit相同替换不再假报已更新：FileTextEditTest两项通过，验证磁盘内容/mtime和下一次真实修改。设备验收待执行。长Life XP会话触发压缩检查点失败且本轮工具历史缺失，权威日志保存，仍待修复。

- file-edit-recovery.en.json模拟器fresh-local五步通过：真实无变化错误→正确修改→正文读回→正式完成→重启回复，额外独立磁盘beta/Remote开启核验通过。原Remote误选及长旧会话偏离后取消记录保留，不混作成功。

- Life XP跨午夜checkInHabit生产逻辑回归：verify-life-xp-midnight.cjs，合成时钟推进后昨日打卡错误阻止次日打卡，旧实现失败；原始结果midnight-baseline.json。待修复及实际模拟器日期边界验收。

- Life XP人工修复后的实际发布页/重启验收通过：verify-life-xp-history-device.py emulator-5580 OUTPUT baseline-records.json；四条累计75/60/40/10、等级2里程碑及原数据逐字段不变。三时区/601行/合成跨午夜审查通过，实际跨午夜与Release仍待；不计模型自主修复通过。

- Life XP实际跨午夜通过：verify-life-xp-midnight-device.py，隔离副本真实UI第一天打卡→同页自然跨午夜→按钮恢复→第二天打卡→历史20XP；时钟/时区恢复、测试插件停用，attempt3证据见vibe-midnight-20260919.md。

- 最终测试签名 Release（SHA256 bd53088f…ac04f0a）模拟器实际文件编辑恢复五步通过：明确无变化失败→正确编辑→正文回读→官方完成→重启保留。入口 file-edit-recovery.en.json，显式 OOB_TEST_RELEASE_READ=1 只读观察；首次路由转义准备错误保留。见 release-candidate-20260919.md。其他 Release 链路仍待。

- 同一最终 Release 实际 PDF 上传/正文/重启/回读 8 步通过。入口 chat-upload-pdf.en.json，结果 release-final-20260919/pdf；文本层解析已验证，OCR 等不在本次范围。

- 同一最终 Release Life XP 真实页面/历史累计/等级里程碑及原4习惯4打卡字段不变通过，release-final-20260919/vibe-history。verify-life-xp-history-device.py 复用只读一致快照，显式支持已root模拟器Release观察，路径受限；已移出本地忽略影响并列入可审查变更。模型自主返工仍未通过。

- 最终Release Remote真实写文件→应用重启→侧栏重开原会话→继续写文件通过，宿主文件独立核对。自动回到原会话观察失败保留，不能计通过；执行入口verify-remote-chat-device.py，release-final-20260919/remote证据。

- 最终Release实际OmniFlow回放发现CanonicalRunLogRecord缺少SerializedName，R8把status/success/steps等6个协议字段改名。新增序列化回归旧实现失败、修复后通过；实际新包验收待执行，见release-runlog-serialization-20260919.md。原无障碍引导和脚本IME观察失败均保留，不当作模型失败。

- RunLog序列化修复实际Release 6e202535…135e1fa验证通过：强停后真实系统无障碍恢复、主流程两步改参wifi回放、新字段持久化、旧混淆快照从原事件恢复及新快照重启读取。证据release-runlog-fixed-20260919/accessibility-replay-verified、legacy-reopen-verified、new-reopen；单包其他大功能回归仍需完成。
- 同次实际页面发现官方RunLog扁平action_type未被UI显示。新增旧实现失败的widget用例，修复后公共格式/旧格式2项通过、定向analyze无问题；新Release构建进行中，UI修复设备验收待执行。

- 官方action_type展示修复已安装Release 953fd15c…aa05563，模拟器新旧日志强停重开均显示具体Tap/Press key/Enter text wifi，选定运行时间与原快照哈希核对通过。verify-run-log-reopen.py --expect-action-labels，证据release-runlog-ui-fixed-20260919；整体同包验收未完成。

- 最新Release953fd15c图片实际红/蓝→绿/黄两轮各8步通过，含重启和GLM-4.6V保留。verify-chat-image-persistence.py独立验证两张已保存附件字节/会话/promptPath，2/2通过。prepare-chat-image.py增加实际Images分类选择，精确文件名仍为点击前提。
- 自动压缩60页前置样本审计发现旧fixture不足；中止未发送驱动且确认无用户入库。prepare-auto-context-fixture.py复用原生成器创建独立16MiB输入，新Release journey执行中，不计通过。


## 2026-09-19：工具流参数归属与长任务失败后恢复

原工具流实现把后到名称的index=1参数拼进index=0，结束流时还会再次猜测归属。已删除两处跨index猜测，按服务商明确编号累计；最终缺名称仍明确失败。解析器25项、HTTP客户端35项、错误分类18项通过。新ProductionStandardRelease测试签名候选2aaade38…e0990a已安装emulator-5580（Android13），设备APK哈希一致。

可执行复现：启动 `node scripts/fixtures/provider-failure-server.mjs`，在模拟器通过设置页配置独立ToolIndexAudit（adb reverse tcp:18879 tcp:18879，http://127.0.0.1:18879/v1，无真实凭据），新本地Agent会话选择其gpt-4o。运行 `OOB_TEST_RELEASE_READ=1 node scripts/verify-agent-user-journey.mjs emulator-5580 scripts/fixtures/agent-user-journeys/tool-index.en.json OUTPUT`，再运行 `python3 scripts/verify-tool-index-files.py emulator-5580 OUTPUT FILE_RESULT_JSON`。实际6步通过，两个真实文件内容、唯一调用、正式终态及重启保留通过。服务商为受控协议注入，不能冒充真实模型生成；证据 `artifacts/tool-index-20260919/device` 和 `persisted-files.json`。结束后聊天已切回真实GLM-4.6V。物理设备待验证。

另：此前953fd15c候选真实60页任务在8次读取后HTTP400，未通过自动压缩检查点验收；原会话随后独立17+25任务、无旧工具重放和重启回复保留5步通过。`chat-context-failure-recovery.en.json`及 `artifacts/release-runlog-ui-fixed-20260919/context-failure-recovery` 为长期入口/结果，长任务400原因仍待定位。

- 同一2aaade38候选真实GLM-4.6V的XLSX系统选择器上传、表格正文值核对、正式完成、重启保留及再次读取原附件8/8通过，证据 `artifacts/tool-index-20260919/xlsx/result.json`。本轮受控工具流6步、真实文件编辑5步、DOCX8步、XLSX8步共27步通过，范围见该目录summary.json；不是整包全部功能通过。

- 真实GLM长任务HTTP400已捕获明确原因Prompt exceeds max length。现已修复成功响应后的恢复标记重置、强制摘要被offload提前返回和被替代消息大小误导的摘要边界，并增加明确上下文错误反馈。115项Kotlin、66项Flutter、3项观察工具回归通过；修复后Release模拟器验收仍待执行。原GLM地址已恢复并强停重开确认。详见[长任务恢复记录](context-recovery-20260919.md)，不把本次6页后文本伪工具调用的失败计为60页通过。

- Remote自动启动恢复：保存导航目标的提前返回已修复，54项Flutter检查通过；verify-remote-chat-device.py新增--restart严格禁止手动重开/重发替代自动恢复。新3d6723f5测试签名Release已构建未安装；设备验收待当前60页任务结束。详见remote-startup-20260919.md。

- RunLog动作截图：补齐官方flat action_type坐标读取，旧tool/args继续兼容，非法非有限坐标不渲染。run_log_detail_page_test.dart新增官方/旧格式实际点开截图并检查定位标记的widget案例，4项通过。新代码待打包和模拟器截图验收，不据widget测试宣称设备已修复；截图缩放坐标精度仍需检查。

- RunLog截图缩放补充：截图与原始像素坐标现在同置FittedBox内缩放，标记按22px图标中心定位。1200×2400合成PNG的widget测试核对缩放后标记中心与图像中心相等，官方/旧格式均通过，合计4项。原夹具因异步图片加载未结束而停止，仅测试进程受影响；改用持久合成图并预加载后通过，未触碰设备任务。最新代码仍待模拟器实际截图验证。

- Vibe从零自主验收新增独立输入life-xp-autonomous.json与journey life-xp-autonomous.en.json。以每次marker数字后缀创建新slug/目录，禁止覆盖现有Life XP，要求生产计算代码测试、实际业务工具与可用UI验收、自主修复及原始证据。输入2780字符，通过既有发送器3000字符边界；JSON/引用检查通过，但尚未实际发送。journey完成仅证明Agent回合结束，不能替代独立业务、数据和UI验收。

- 长任务重启后续读：新增xiaowan-release-auto-resume.en.json复用既有不含路径/offset答案的真实用户输入，由断言独立核对Release新样本路径和60×65536偏移。保留旧样本入口；新入口待原60页及重启检查通过后在同一会话执行，当前尚未运行。

- d989f74a测试签名Release / emulator-5580：Remote原B强停普通启动自动恢复通过；恢复后手机真实发送C、同一后端会话唯一执行、宿主文件正确且旧B保留通过；第二次强停普通启动自动恢复最新C也通过。证据artifacts/remote-startup-20260919/{automatic-restart,continued-real-task,continued-backend-files,second-automatic-restart}.json。自动恢复问题在本模拟器实际场景验收通过，待真机验证；不代表公网/TLS/NAT/休眠通过。

- 摘要前缀游标丢失：forcedSummaryRetainsCompletedPrefixCursorBeforeTailOffloading旧实现红、新实现绿，相关99项通过，证据summary-prefix-20260919；源码修复未进行真实60页复测。Vibe自主新任务首次仅输入准备超时，marker1789786991049用户入库0；保留部分合成草稿和失败结果，不记为模型失败。后续输入等待上限改600秒，业务验收标准未变。

- Vibe输入准备恢复曾因Android End仅到行尾导致文字插错位置，全文精确校验正确拒绝发送；原任务仍0次入库。驱动恢复仅允许prepare-only且精确前缀匹配，禁止修改外来草稿；修正为Ctrl+End（实际设备恢复路径尚未复测），4项驱动检查通过。新8a6ead35候选已安装，独立第二次准备使用1633字符同等业务/验收要求，修正动态slug和固定Skill名矛盾，600秒输入上限；设备模型任务尚未计通过。

- Vibe独立第二次准备已在8a6ead35候选通过真实输入全文校验并提交，marker OOB_LIVE_LIFE_XP_AUTONOMOUS_1789787755680，输入阶段234877ms。首次未提交输入失败未算模型失败；第二次真实模型结果仍在等待，不能以发送通过替代自主业务验收。证据vibe-autonomous-20260919/second-attempt。

- 真任务观察器：reply等待阶段先只读canonical journal，正式completed后才抓实际UI，避免UIAutomator压制Agent做UI验收所需的无障碍服务。继续观察支持明确marker，不重发prompt；终态oracle33项通过，Node语法检查通过。Vibe会话42任务1611只更换观察进程，未取消模型；continued-observation继续原任务，业务验收仍未通过。

- Vibe 从零任务正式结束但业务未通过：实际 water 打卡因 check_in_date 非空约束失败且零新增记录，当前生产方法日期/历史累计两项独立测试仍失败。保存真实 UI、SQLite 和生产代码哈希；新增可重复打卡入口（仅语法检查，完整执行待模型修复）。原会话模型返工准备中。详见 vibe-autonomous-20260919.md。

- 60页真实压缩复测观察上限从20分钟改为60分钟，依据此前约48分钟实际运行；严格60次顺序读取、正式终态、持久化检查点和重启断言未削弱。当前最新候选尚待复测，延长观察不是通过证据，也不改变模型任务生命周期。

- Vibe SQLite ignored config：真实生成项目暴露 config 中筛选/分页未执行却通过检查。新增发布源检查只允许 table，保留已安装插件加载；旧实现回归失败，修复后三组单测通过，继承 config 补充回归10项通过。候选正在构建，实际模拟器验收待执行。见 vibe-config-validation-20260919.md。

- Vibe自主返工后真实 water 打卡/本地日期/10XP/重复保护通过；同页下一项 exercise 仍被禁用，新增连续操作实际失败证据。独立历史累计、负时区日期显示、页面跨午夜刷新失败；模型终端测试exit1却声称完成，记录不计通过。新增独立测试输入到手机工作区，下一轮原会话返工不改生产数据。见 vibe-autonomous-20260919.md。

- RunLog坐标单位回归：官方action_type是display像素，旧tool/args是0..1000，按各自单位投影到显示截图；8项widget通过。1742ecd7候选模拟器强停重开实际原日志/动作截图通过，原记录不变，实际红色图标中心与原点投影误差<1px。首次语义边界误判保留，后用真实绘制像素核验。见runlog-coordinate-units-20260919.md。

- Vibe GLM-5.1独立新回归：`TZ=Asia/Shanghai node scripts/verify-generated-life-xp-logic.cjs SNAPSHOT/app.js`增加同页跨午夜直接打卡日期（保留真实write与reload，隔离时钟/DOM）。当前实际模型代码2934a7aa：原四项通过、新项失败，写入昨天；证据vibe-autonomous-20260919/glm51-local-repair/in-progress-source/independent-expanded.log。模型回合canonical网络error、未发布，补充测试7通过1失败，不计整体通过。后续可执行journey life-xp-midnight-followup.en.json；待模型修复及设备操作验收。

- 最新1742ecd7 Release中，模型发布的Life XP实际连续reading→冥想、重复保护、历史85XP/等级2及强停重开数据不变通过；复用checkin-device与扩展history-device的optional slug入口，证据glm51-midnight-followup。模型UI步骤被无障碍缺失挡住，外部系统UI恢复后验证；不算全自主验收。新增verify-life-xp-progress-tool.cjs基于该真实回合业务工具输出，发现旧进度0与实际水10不一致，实际失败待修复。
