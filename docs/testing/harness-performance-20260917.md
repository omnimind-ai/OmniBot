> 2026-09-18 update: user requested removing the fixed four-call cap. The runtime now starts the entire contiguous parallelSafe group. Historical capped results below remain unchanged; see tool-parallel-no-fixed-cap-20260918.md for current validation.

# Harness 性能优化与验收（2026-09-17）

用户要求：落实减少模型往返、按需读取、模型/思考配置以及独立工具有限并发；保持前端与单一 ACP 生命周期，避免硬编码任务规则。用户指定使用模拟器。**物理设备待真机验证**。

## 实现

- `AgentSystemPrompt` 与终端参数说明：确定性批量计算、产物创建和核验可在现有终端/插件一次完成；单项文件操作仍使用现有文件能力。普通批量 I/O 不需要启动子 Agent。模型自己选择执行方法，未新增按任务关键词分流的规则。
- `file_read` 引导先检索、再读取必要范围；保留已有分页默认值、完整原文件和上下文外存/压缩语义。未另造压缩算法或变更用户历史。
- 当工具目录声明可并行读取时，使用标准 `parallel_tool_calls=true` 请求模型一次生成多个工具调用；不强制 tool_choice，兼容性仍由现有 Provider adapter 处理。这与运行时是否并发写入是两件事，写入继续串行。
- 工具 owner 可显式声明 `parallelSafe`，插件和 capability 注册层均支持，默认 false。仅内置 file_read/list/search/stat 默认开启。当前执行器中相邻的可并行调用最多 4 个，写入、GUI、终端和未声明工具保持顺序边界。此字段不进入模型 API 的 function schema。
- 并行工作仍归当前 ACP prompt coroutine 管理；结果按模型声明顺序经过现有 callback/历史 owner 提交。取消会等待子任务释放，已经开始的只读调用不会被错误记录成“未执行”。没有第二个 scheduler、reducer 或 retry owner。
- 文件 I/O 放到 IO dispatcher，避免阻塞 prompt 所在协程的线程。
- 同一执行器增加无正文诊断：context/model/tool/projection/turn 耗时，以现有 agentRunId、conversationId、toolCallId 关联。`summarize-agent-performance.py` 使用时间区间并集统计并行耗时，避免把并发工具时间相加后误判。
- 保留当前 Provider、模型以及官方 ACP reasoning 配置；未强制换模型或降低用户选定思考强度。已有 off/low 等配置的请求语义继续由现有测试覆盖。

## 可复现测试

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest
node --test scripts/agent_runtime_capability_contract.test.mjs scripts/agent-ui-xml.test.mjs
python3 -m unittest discover -s scripts -p test_agent_performance_summary.py
python3 scripts/prepare-content-search-fixture.py emulator-5580
node scripts/verify-agent-user-journey.mjs emulator-5580 \
  scripts/fixtures/agent-user-journeys/xiaowan-perf-reads.en.json OUTPUT_DIRECTORY
node scripts/verify-agent-user-journey.mjs emulator-5580 \
  scripts/fixtures/agent-user-journeys/xiaowan-perf-bulk.en.json OUTPUT_DIRECTORY
# 每次只运行一个 UI journey；下一个开始前等待上一个完成。
adb -s emulator-5580 logcat -d -v threadtime '[Omni]AgentPerformance:I' '*:S' > performance.log
python3 scripts/assert-harness-performance.py emulator-5580 ACTUAL_UNIQUE_MARKER performance.log
python3 scripts/assert-harness-read-batch.py emulator-5580 ACTUAL_READ_MARKER performance.log
```

真实任务脚本继续复用 UI 输入和 canonical journal 断言，不注入回复、不绕开执行器。哈希/字符数产物由宿主独立读取原文件重新计算，不能用模型声称“完成”代替。

## 已完成的检查

- 新并发回归在旧实现失败：`Independent reads were serialized: peak=1`，见 `artifacts/harness-performance-20260917/parallel-red.log`。
- 原生全量 **1108 项通过，0 失败、0 跳过**。覆盖并发上限、写入边界、乱序完成、取消与释放、错误不重复执行、权限结果后的已启动读取提交、诊断不泄露正文，以及既有流式/上下文/思考配置回归。最终标准批量选项改动后，定向 829 项通过，0 失败、0 跳过，APK 构建成功。
- 12 个各模拟等待 50ms 的读取，在同一真实执行器测试里：串行 **626ms**，并行 **157ms**；断言并发上限及所有调用身份/顺序一致。这是受控工具等待测试，**不是实际模型四倍加速的证据**。
- 本地协议/脚本回归通过。未重跑所有外部 Harness，未改 Flutter 产品 UI。
- 首个真实模型批量任务：处理 1,900,026 字符文件，3 次工具调用，4 次模型调用，总执行 **50,482ms**。模型 **48,175ms**、工具 **2,147ms**、上下文 **35ms**、投影 **7ms**。产物、历史及重启通过。
- 收紧指引后的第二次真实任务：同一文件和产物要求，结果正确，但仍拆成两次命令及一次写文件，4 次模型调用，总执行 **45,474ms**。这说明提示词不能保证所有模型都选择最少轮次；不把两次自然波动称为严格 A/B 性能收益。

## 保留的失败和测试边界

- 首次真实“单批三次文件查询”模型误用 `subagent_dispatch`，启动 3 个子 Agent。虽然返回了正确计数，**调用路径验收失败**。证据保留在 `live-reads/` 和 `initial-live-performance.log`；随后在通用指引中区分批量工具调用和任务分派。
- 第二个 bulk 的界面观察最初把完成标记限定为独立一行。Markdown 将它合并到上一句，且语义节点后面附带 token/时间信息，导致误判。已修复测试匹配器、补拒绝用户输入/草稿与部分匹配的回归。独立产物/历史验证始终保留，随后 UI 及重启观察在 `bulk-observation-final/` 通过。没有重发该用户任务。
- 一次过早启动的 UI journey 与未结束的观察竞争 UIAutomator，设备命令退出 137，未发送新消息。失败保留在 `final-reads/`，属于测试操作错误，不算 App 通过或 App 故障；后续严格串行执行 UI journeys。
- 补充指引后的读取内容、历史和重启通过，但模型仍逐次发起三个查询。新增 `assert-harness-read-batch.py` 要求同一模型轮次、三个真实工具 ID 与重叠执行，旧请求再次红灯。不能把 `verified-reads/` 的内容通过冒充并行通过；失败见 `model-batch-red.log`。随后补标准批量调用选项，进行下一次复测。
- 原来的“20 页约 9 分钟”是强制逐页读取测试，和批量统计/哈希的任务要求不同，不能拿来宣称端到端提速倍数。
- 真实模型决策仍有不确定性，提示词不能杜绝误报完成；应持续运行带实际产物/调用身份断言的回归。

补充环境记录：同一模拟器还被另一项附件验收使用，出现 UIAutomationService 重复注册（测试进程退出 137），且一次已核对文本的遗留草稿携带了另一测试留下的失效附件，使本轮在模型调用前失败。该失败没有重复执行工具；随后切换到独立新会话。UI journey 增加只读进程占用检查，已有失败不删除。

最终安装版本与哈希见 `artifacts/harness-performance-20260917/installed-candidate.json`。最终模拟器复测结果追加于下方。

## 最终模拟器复测

- 安装 0.6.3/code16 候选包后，GLM-4.6V 即使收到标准 `parallel_tool_calls=true` 仍选择三个模型轮次分别查询；内容正确，并行决策未通过。失败见 `standard-option-batch-red.log`，保留模型能力差异。
- 通过原有模型菜单临时选同一 Provider 已配置的 GLM-5.1，对同一真实文件查询任务：**同一轮 3 次 file_search，实际并发峰值 3**，正确计数 1/1/0。整个任务 2 次模型调用，耗时 16,508ms；工具等待区间并集 1,974ms，工具各自耗时之和 3,698ms。见 `glm51-batch-verification.json` 和 `glm51-performance.json`。这证明运行时端到端并发可用，不表示所有模型自动采用该路径，亦不是跨模型速度的严格对照。
- 这次原始 journey 在调用验收脚本时出现假失败：日志通过 stdin 输入，但脚本先启动 ADB 子进程，stdin 被子进程读取。改为先读入日志，再调用 ADB；使用同一任务重新观察，未重发。`glm51-observation/` 的结果、真实重叠、重启及历史 7 步全部通过。原失败 `glm51-batch/` 保留。
- 连续日志用 `OOB_AGENT_PERFORMANCE_LOG=/tmp/oob-perf-device.log` 传入 journey，避免 Android logcat 环形缓冲区覆盖早期工具事件。该变量仅供验收，不改变 App 路由或生命周期。

### 实际执行新发现：Python 被不必要的 venv 初始化阻断

关闭思考、完成任务、重启及恢复默认配置的 UI 流程 16 步通过（`glm51-no-thinking/`），但严格工具验收失败：第一次 Python 命令被启动包装器要求先 `python3 -m venv`，因 Ubuntu 缺少 ensurepip 返回 1。模型恢复为 shell + file_write 后产物正确，但最终错误声称 `No errors encountered`。该轮不能标为无错误通过；见 `python-venv-red.log` 和 `python-venv-failure.json`。

修正现有 `EmbeddedTerminalRuntime.buildPythonEnvironmentPrelude`：普通 python/python3 只激活现有项目环境，不创建环境；`python -m pip`、pip 等包工具继续创建隔离环境。不安装额外包、不新增 Agent 规则。原生可执行 shell 回归使用缺少 venv 支持的解释器替身，验证普通脚本成功、包安装明确失败、已有环境仍激活。新增 `xiaowan-perf-python.en.json` 实际 Agent 回归，必须由一次 Python 工具完成真实产物，使用 `assert-harness-performance.py ... --require-python` 验证。

模型最终总结与真实错误记录矛盾是仍存在的模型决策问题；本轮没有用文本规则篡改其回复，失败样本保留。界面“已完成”及最终完成标记本身不构成执行正确性的证明。

最终 Python 修复候选包原生 **835 项通过，0 失败、0 跳过**，含可执行 shell 回归；离线检查通过。之前 shell 回归在 macOS `/var` 与 `/private/var` 临时目录别名下未命中项目路径，修正测试路径为 canonicalFile 后通过（不是为放宽断言改动产品）。标准批量选项引入后，旧源码断言“必须始终为 null”也被改为要求显式工具 opt-in；真实 Kotlin 行为测试仍同时验证 opt-in 与无 opt-in 两条路径。初次失败日志均保留。

**最终真实 Python 任务通过**：恢复原有 GLM-4.6V，使用关闭思考配置，要求一次标准库脚本，实际只有 1 次 terminal_execute / Python 调用、2 次模型调用；字符数 1,900,026 和 SHA-256 由宿主重新计算一致。总执行 28,090ms，模型 25,602ms，工具 2,422ms。见 `python-final-verification.json`。这是对指定批处理路径的实际验证，提示词和上下文不同，不将相对之前 50 秒的变化称为严格 A/B 加速比例。

最终含 Python 修复的安装版本和 APK 哈希见 `python-installed-candidate.json`（0.6.3/code16，`0db89a0e82dc8c6e7b40d59b70b3068c8406c409ca0ec3da8c514049d78a95b6`），取代较早 `installed-candidate.json` 所指的并行修复阶段候选包。本轮未发布 release。

最终 `python-final/` 的实际发送、完成、重启、历史与配置恢复 **16 步全部通过**。结束时确认模型 GLM-4.6V、reasoning_effort=default 已恢复，恢复小万无障碍服务。所有设备结果均为用户指定模拟器；**待真机验证**。
