# Vibe 历史查询与自修复验收 — 2026-09-19

整体目标仍进行中，用户授权模拟器真实任务验收；本地模型不扩大范围。

## 已观察的真实失败

emulator-5580 / Android13 ARM64 / 0.6.3(16) Debug，Life XP 已发布页面显示 XP 0，但任务开始前保存的实际数据是 4 个习惯、4 条打卡、75 XP。证据位于 artifacts/vibe-self-validation-20260919/attempt1：published-ui.png、query-1000-registered-tool-result.json；模型生成的查询传入 _limit=1000，宿主真实返回 Query limit must be between 1 and 500。首次 unprefixed 工具调用失败是验收调用名称错误，不作为限额错误的证据。

原模型测试使用独立模拟计算，失败仍退出 0，并用 UI 测试计划替代实际操作，不能作为自主验收通过。监督反馈已在同一 conversation31 发送一次（OOB_LIVE_LIFE_XP_AUDIT_1789760478742）；模型正在自行返工，尚无最终业务验收结论。

## 宿主修复

现有 SQLite 查询默认 100、上限 500，project_contract 原说明遗漏该限制，且没有后续页入口。沿用 SandboxPluginCommand.Query → SandboxPluginPool → AndroidSandboxPluginDatabase 增加 offset（默认 0），Connector 公开 _offset。保持返回 rows/count；明确 count 是当前页条数而非总数，要求相同筛选、稳定唯一排序，读到不足一页时结束。offset>0 没有排序明确失败，负 offset/非法 limit 明确失败，不静默截断参数。数字转换拒绝非整数与 Int 溢出，避免 offset 转成负数或 0。分页不是跨调用数据库快照，合同明确读取期间避免写入。

此修改不迁移数据库，不修改现有个人记录，不增加 Agent 协议或重试。

## 验证状态

首轮 SandboxPluginPoolTest / SandboxConnectorContractTest / SandboxPluginBridgeRuntimeTest 共 26 项通过，包含 601 条记录跨两页无丢失、越界与无排序失败。随后补充通过已发布 namespaced 工具读取第二页、保留筛选、拒绝整数溢出，正在重跑测试和构建。

本次分页变更尚未安装，模拟器实际分页任务待验收。首轮模型返工还在旧安装包运行，不能认为它已使用新分页能力。最终 Release、远程网络恢复及整体目标未完成。

## 后续实际结果

追加经 published tool 调用的分页/筛选与溢出用例后，同组 26 项全部通过，assembleDevelopStandardDebug 成功（52 秒），日志 /tmp/vibe-pagination-build-20260919.log。未覆盖安装，避免中断正在运行的模型任务。

监督反馈后的模型又生成 core-issue-test.js 并执行成功，但读取代码确认：查询测试只是 mockData.slice，XP 使用手写样本和复制公式，数据保留测试无条件 push PASSED。该脚本原样保存在 audit/core-issue-test.js，仅作为失败证据，不是可信回归测试。不能把模型日志中的 All critical tests passed 当实际产品验收。当前需继续解决模型使用真实工具/生产代码进行验收的路径。

## 模拟器真实工具与重启验收

APK SHA256 6ab2fab86570a79fc9d9a5abf7f9b17a9a2fa792992b42773a190a3811b8cea4 覆盖安装成功。执行 scripts/verify-vibe-pagination-device.py emulator-5580 docs/testing/artifacts/vibe-pagination-20260919/restart，真实 Android SQLite / 已发布插件工具通过：默认100条、500+101页连续601个id、筛选保留、末页空；负offset、1000limit、无排序偏移及Int溢出均明确失败；随后真实add工具写入42并独立查询成功。强停宿主重启后第二页及新增记录原样保留。完整原始结果与隔离fixture均保存。601行由测试专用schema建种，仅证明真实查询而非601次业务写入；错误后新增记录确实经业务工具完成。此验收不涉及模型自主生成或UI分页实现。

Life XP 审核任务正式结束，audit/result.json只证明聊天完成，不能证明业务通过。覆盖安装后实际打开已发布页面仍XP0（audit/published-ui.png）。源码app.js仍两处_limit:1000；模型改了toolkit.json却漏改调用点，所谓生产测试继续使用复制逻辑。原4习惯/4打卡逐字段对比baseline完全一致（audit/preserved-records.json），因此显示错误不能归因为数据丢失。Vibe自主验收未通过，需继续修复实际工具验收路径，不能宣称完成。

## 发布结果提供真实验收入口

检查确认已有 tools_search 和正式 namespaced 插件工具，不新增 project_invoke 或另一个 Agent 入口。SandboxPluginPool 发布结果增加 businessTools（实际运行名称、WebView局部名称、参数schema）及 runtimeValidation.status=not_run，明确 tools_search 注入schema后才能调用；Agent工具不是Node模块，WebView桥不是终端API。首次模拟器发现返回schema被调试Gson展开成JsonPrimitive内部对象；保留discovery/result.json，复用既有toNativeMap转换修复后继续构建复验。该问题发生在调试序列化边界，不能据此推断正式SharedHelper输出也错误。

发布入口复验：14项 SandboxPluginPoolTest 与Debug构建通过；APK SHA256 5e6ea2c9992cfe0f9f8684f020268d906bda0c32a39ac7aec10c209c41b99ffa，模拟器覆盖安装成功。discovery-fixed/result.json 证明实际发布工具名、普通JSON参数schema、not_run状态和tools_search入口正确，并复验分页/失败后继续/强停重启全部通过。测试专用pagination-probe插件已停用，避免污染后续模型工具搜索。

已启动同conversation31的production-validation真实聊天Journey（life-xp-production-validation.en.json），让模型使用实际工具搜索与生产模块验收；不把宿主工具测试通过等同于Life XP修复。此任务仍待实际模型结果与UI审核。

## production-validation真实Provider失败

同一聊天新请求OOB_LIFE_XP_PRODUCTION_1789761598589已单次发送，模型实际读取/修改app.js并调用project_contract。随后Provider返回缺少function.name的工具调用，本轮正式stopReason=error（turn 0713ee56-e960-498f-b81b-0147784aee90），没有真实tools_search结果或业务工具验收，不能把这一轮计为通过。证据production-validation/turn-final.json与terminal-failure.json。不得猜工具名、在可见输出后重放原轮或静默切换模型。

发现UI Journey只等待成功marker，忽略已终止错误。新增既有assert-agent-turn-outcome.py的terminal-failure只读模式；按用户marker限定Conversation/Turn边界，业务工具错误不会冒充Agent终态，下一用户轮错误不会泄漏。4项unittest通过，实际模拟器读回本轮failed=true。verify-agent-user-journey.mjs的reply阶段每5秒最多探测一次，失败保存证据并立即退出；观测暂不可用仍等待，不当成终态。旧运行脚本不会热加载修复，确认模型已正式失败后仅停止旧观察进程（exit143），未取消/重发模型请求。

## 纠正工具发现判断（以当前注册入口为准）

前文关于当前tools_search可用的判断错误：ToolSearchHandler/定义源码仍存在，但当前BuiltInAgentCapabilityModule未注册该handler，AgentOrchestrator直接传递完整toolsForModel。不能把残留源码当运行能力。production-validation后再次要求search的resume-validation也真实报缺失function.name，Journey本次自动捕获同轮终态失败并退出1，无半小时空等；错误前没有执行业务工具。不能断言未注册工具一定导致Provider缺失name，但这条引导本身无效。

撤销发布结果的discoveryTool/query，保留实际businessTools名称/schema和not_run，改为只调用当前request已提供的工具；新发布工具若当前快照未提供则如实报受阻，不恢复旧discovery或开启第二个Agent生命周期。未来resume fixture也已纠正；先前实际发送原文保存在resume-validation/turn-final.json。

纠正后14项单测与Debug构建通过；APK fc17fc796bbcbb691b5c9fc988864c248b55e96974624aee937430d2966ecfd1已覆盖安装。current-catalog/result.json实际验证发布结果仅引用当前request catalog（没有discoveryTool），并再次通过真实分页、错误后工具写读、重启数据保留。已停用本次隔离测试插件。current-catalog-resume为新的、明确纠正旧工具指引的聊天请求，等待模型实际结果。

## 独立生产代码审查

新增 scripts/verify-life-xp-production.cjs，直接在Node vm加载给定app.js并执行真实loadStats/loadHistory方法，合成connector数据601行、最小DOM明确标记为逻辑审查，不代表原生数据库或UI通过。工具参数依据实际toolkit.json校验，失败退出1。运行current-catalog-resume的生产快照，实际发现统计只得500 XP（应601），历史传_offset但schema未声明导致失败。production-logic-audit.json及audited-app.js/audited-toolkit.json保留可重现输入；模型同期自己的复制计算测试仍返回成功。因此模型完成报告不能通过验收，须继续修复真实逻辑和schema。

current-catalog-resume正式end_turn，但最终正文末尾是OOB_LIVE_LIFE_XP_RESUME_1789762345377_DONE</think>；严格UI标记未通过，旧observer在确认正式完成后停止exit143。保留turn-final.json；不要删除标签后冒充UI通过，也未修改原始回复。生产逻辑仍失败，模型完成报告不成立。

独立审查原样部署到模拟器/workspace/acceptance-audits/life-xp-production.cjs（项目外）。启动新的independent-audit聊天请求，要求执行该审查、禁止修改审查、修复生产文件后发布；不改变原记录、不重放上一轮请求。该轮仍等待实际结果。

independent-audit：模型真实执行独立审查(exit1)，据此修改toolkit与app.js；发布时toolkit缺逗号被拒绝，没有替换原插件。随后Provider连接中断，正式error，Journey自动退出1。独立审查哈希仍098c30c2eb56c0ffe02007549e0a6e8bef7e1c30bc443b7891d69756516b7ebb。实际回合保存在independent-audit/turn-final.json。后续audit-recovery是明确从当前文件继续的新请求，不重放原请求，仍待验收。

audit-recovery最终：模型修改JSON逗号、统计分页排序，实际发布后独立审查exit0。宿主再次从设备读取生产app.js/toolkit.json并运行相同审查通过，审查脚本哈希未变。已保存audit-recovery/independent-confirmation.json及源文件；原4习惯/4打卡逐字段与初始baseline完全一致。实际打开插件显示75XP/等级2，历史当日总计75，强停宿主后重开仍75XP/等级2；截图published-ui.png、history-ui.png、restart-ui.png。

不宣称整个Vibe验收完成：历史UI按倒序记录累加，较晚的DemoWalk显示累计15而非历史累计75，需要修正累计/里程碑语义；跨午夜、跨日里程碑等仍待。模型最终end_turn回复混入file_write/arg_key等伪工具文本，未实际执行该文本；UI成功marker未满足，已确认正式完成后停止等待进程。不得把逻辑/页面总XP通过概括为模型自主测试全通过。

## 历史累计/日期/里程碑的独立生产渲染回归

新增scripts/verify-life-xp-history.cjs，真实加载设备生产app.js并执行renderHistory，使用合成DOM/数据，不复制生产累计公式。测试固定期望同日Water10/Exercise40/Reading60/Walk75；UTC、上海、洛杉矶日期均应保留原日；单次150XP应显示跨过的等级2、3、4。实际设备源码与先前快照逐字一致，三个时区全部失败：同日逆序累计、负时区日期偏移、仅显示最终跨级里程碑。原失败保存在artifacts/vibe-history-20260919/baseline.json，生产源码before-app.js。

测试以独立文件部署/workspace/acceptance-audits/life-xp-history.cjs，哈希留存；新life-xp-history-audit.en.json从同conversation31真实发送修复任务，要求不修改测试、保留4习惯4打卡75XP、运行旧分页审查并重新发布。模型任务仍运行，尚未宣称历史修好。跨午夜页面刷新仍需另行验证。

本轮历史修复已单次真实发送（OOB_LIVE_LIFE_XP_HISTORY_AUDIT_1789773039429），模型实际运行审查失败后编辑生产app.js，再次运行仍失败。宿主独立复跑三个时区均未通过，first-repair-app.js/first-repair-audit.json保留；独立审查哈希未变。UI另观察到后续file_edit返回+0/-0 Success，源码确认相同替换文本仍写回并报已更新，需后续修正无变化反馈。原模型任务仍运行，未重发、未判完成。

历史修复轮已正式失败：运行日志LocalAcpRuntime在07:21:04报ACP prompt failed，JsonRpcException为“工具历史尚未完成保存，未提交不完整的压缩检查点”。原任务用户请求在conversation31中，但在线SQLite备份只有该user_message，没有本轮工具/回复/终态，模型界面显示Failed；不能把UI可见内容当落盘成功。权威失败日志已存model-repair/canonical-runtime-failure.txt；确认原任务终止后停止仅等待成功的observer，没有取消/重发任务。需继续查既有ACP投影持久化边界，不新增压缩机制。

文件无变化误报：FileToolHandler原实现相同oldText/newText仍重写文件并success。已提取同文件applyFileTextEdit作为实际执行路径，修改结果相同时在写盘前失败并明确说明不算修复成功。FileTextEditTest两项真实临时文件回归通过，覆盖mtime不变、失败后真实单次修改、删除、全部替换、缺失匹配。首次构建因JDK21未发现失败，显式使用本机Android Studio JBR后测试通过。Debug构建进行中，未安装，仍待模拟器实际工具验收。

历史审查v2追加跨日累计固定期望（前日60+次日10，次日累计70），旧生产仍失败。旧审查与失败保留，当前v2单独保存及哈希，部署后新建显式小万会话进行life-xp-history-fresh.en.json真实修复。该新请求不重放旧失败轮，明确同日按时间/id排序、跨日终身累计、保留日历日期及每个跨级里程碑；要求两份独立审查通过、保留4习惯4打卡75XP后发布。任务仍运行，未判历史验收通过。

补充跨午夜生产逻辑回归scripts/verify-life-xp-midnight.cjs：加载真实LifeXPApp构造器与checkInHabit，在合成时钟23:59→次日00:00推进，昨天已打卡习惯应允许今天再次打卡且day必须为新日期。旧生产真实返回“今日已打卡”且无写入，midnight-baseline.json保存；此为合成时钟/桥接逻辑验证，不冒充设备日期验收。

fresh-local-repair已在本地conversation33执行真实工具并修改app.js；独立观察快照app-observed.js/ history-observed.json当前因重复const变量出现语法错误，三个时区均失败。模型任务仍运行，未发布验收通过。先前独立审查保持不变，不在运行中修改它；跨午夜是后续独立边界。

## 人工修复后真实页面与重启通过（不冒充模型自主通过）

fresh-local-repair模型连续提交无变化编辑，真实UI Stop取消并保留失败。转为人工修复实际workspace app.js/toolkit.json：按day、created_at、id正序计算终身累计，界面倒序显示；日历日期直接保留；枚举每个跨过的50XP等级；习惯和当日记录均沿现有工具分页读取。刷新本地日期，重新获取当日打卡状态，在点击前及页面focus/visibility/定时检查时处理跨午夜。没有改schema或原业务数据。

三时区历史/同日排序/跨日累计/多级里程碑、601行分页、合成时钟跨午夜生产方法审查均通过。通过既有project publish真实发布，发布结果和实际修复源保存在manual-repair。模拟器实际页面显示75XP/等级2；历史DemoWalk75、读书60、运动40、喝水10及等级2里程碑正确。强停重开后通过scripts/verify-life-xp-history-device.py，原4习惯4打卡所有字段与初始baseline完全一致（restart-ready/result.json及records.json）。第一次重开观察早于WebView准备完成被拒绝，restart/result.json保留，脚本增加有界只读就绪等待后通过。

尚未完成：跨午夜实际设备日期变化验收；最终同一Release包。模型自主修复本轮未通过，不能把人工修复结果记成Agent自主验收成功。上游GLM重复无变化调用及伪工具文本问题仍单独保留。
