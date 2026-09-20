# Vibe 从零自主生成验收 — 2026-09-19

设备 emulator-5580 / Android 13，ProductionStandardRelease 0.6.3(16)，Android Debug 测试签名。
安装 APK SHA256 `8a6ead35ba73ca8985f7f2b8b64e5c3ce17c26d5b5a6283fefed790a4ae1b8fc`，与设备安装文件一致。

## 当前结论：未通过

模型从空项目创建 `life-xp-autonomous-1789787755680`，真实发布并可从插件详情 Open App 打开。原会话 42，任务 marker `OOB_LIVE_LIFE_XP_AUTONOMOUS_1789787755680` 正式结束。`continued-observation/result.json` 只证明回合完成，不是业务验收。

独立实际点击 water 的完成按钮：页面显示 `NOT NULL constraint failed: check_ins.check_in_date`，只读一致 SQLite 快照显示 check_ins 仍为零行。应用显示错误而未崩溃，但核心打卡不可用。原始页面、截图、数据库结果与代码快照在 `artifacts/vibe-autonomous-20260919/checkin-baseline/`。

核对当前生产文件，app.js SHA256 `102f5cbff735147c4f09431c514cd83959b7ad88de95bbcc36c07bb54b35f092`。已确认：

- check_in_habit 未声明/传入 schema 要求的非空日期；实际 UI 失败如上。
- 今日查询把 2026-09-19 写在 executor.config 中，但宿主实际只读取 config.table，该日期筛选被忽略；生产代码也采用 UTC 日期。
- 今日 XP 使用 check_ins 中不存在的 xp_reward 字段。
- 倒序历史累计错误：此前 40 + 今日 30 的最新累计显示 30，而不是 70。
- 模型测试复制计算公式，没有执行实际生产方法，最终回复仍声称通过。

独立生产逻辑入口：

```sh
TZ=Asia/Shanghai node scripts/verify-generated-life-xp-logic.cjs docs/testing/artifacts/vibe-autonomous-20260919/checkin-baseline/app.js
```

本次实际运行 2 项失败，exit 1，原始结果 `checkin-baseline/production-logic.log`。它是生产逻辑测试，不代替设备 UI 或原生持久化验收。

新增可重复真实 UI 打卡入口（先打开指定已发布应用，所选习惯当日尚未完成）：

```sh
OOB_TEST_RELEASE_READ=1 python3 scripts/verify-life-xp-checkin-device.py emulator-5580 OUTPUT life-xp-autonomous-1789787755680 water
```

此入口校验一次真实点击、唯一新增记录、本地日期、原记录不变、页面 XP 和重复按钮保护。仅语法检查通过，尚未完整执行；先前实际失败由现场相同操作复现，不冒充新增脚本已运行。

修复任务已在原会话 42 真实发送，marker `OOB_LIVE_LIFE_XP_REPAIR_1789789516907`，唯一 user entry 1751，admission.json 核验无重复。复用现有 Journey，不人工修改生成 App。输入 `scripts/fixtures/user-scenarios/life-xp-autonomous-repair.json`；入口 `scripts/fixtures/agent-user-journeys/life-xp-autonomous-repair.en.json`。要求生成模型执行实际生产测试、业务工具和可用 UI 验证，保留失败。修复及重新验收待结果。

本项是用户授权的模拟器真实模型/实际 UI 验收；物理真机待验证。尚不覆盖最终全功能同包验收。

## 原会话自主返工结果

任务 `OOB_LIVE_LIFE_XP_REPAIR_1789789516907` 正式结束，原始工具/回复在 repair/final-task-evidence.json。真实 terminal_execute 返回 exit 1，模型仍报告全部修复；不能计自主验收通过。

独立复核新 app.js `88e1ebc90f43146d6fe0fca789c671c0bef951b903ddda55d5af7a32c0ae7ab5`：本地日期构造通过，历史倒序累计仍失败。测试夹具补齐真实 habits 奖励来源后，旧代码仍失败，新代码仍为30而不是70；没有放宽断言。补充负时区日期显示和页面跨午夜刷新用例，当前仍失败，详见 repair/production-snapshot。

真实重新发布页 water 打卡通过：verify-life-xp-checkin-device.py 实际执行，当前日期2026-09-19保存一行、总XP10、重复按钮不再新增，repair/checkin/result.json。

紧接着同页 exercise 打卡前置检查失败：未完成的运动按钮被禁用，无法继续操作，repair/checkin-exercise/before.xml 和 result.json。生产代码在 isSubmitting=true 时 renderHabits，然后 finally 只复位标志未重绘，导致所有按钮保留禁用状态。这是新的实际连续操作故障，不通过重开页面掩盖。

宿主发布检查另已补齐不支持的 SQLite config 拒绝，见 vibe-config-validation-20260919.md。仍需模型修复业务/测试、实际连续打卡与重启验收。


## 独立测试返工与后续模型选择

OOB_LIVE_LIFE_XP_AUDIT_REPAIR_1789790603141 在会话42唯一入库1795。真实terminal_execute分别执行两个时区的独立测试，失败；真实project_check已拒绝无效SQLite config `_order_by`，模型据错误修改toolkit。之后项目slug漏一位数字，反复读取不存在路径。独立工具ID确认不是UI重复展示。监督通过实际Stop按钮取消，canonical取消断言通过，原任务result保持失败，代码和日志未清除。

停止后的 app.js `2f5d1e658d229064aa058aa3747841dc185ace34efbb89feb523857b868a7f7e` 两个时区仍各3失败，保存在independent-repair/stopped-source。不能计模型自主修复通过。

在同一已配置服务商的实际模型菜单选择GLM-5.1。保留上述GLM-4.6V失败，后续是不同模型的新监督修复任务，不把它当原turn重试。完整验收条件作为输入文件oob-life-xp-repair-acceptance.md传入测试工作区，独立audit文件不允许模型修改。

首次新会话准备没有显式agentId，继承默认Remote，发送器焦点校验失败（glm51-repair/result.json），没有执行发送。修正为受支持路由agentId=xiaowan-acp，并在Journey发送前检查小万欢迎页、实际GLM-5.1选择和关闭设置。glm51-local-repair运行中，未计通过。

GLM-5.1 新任务实际发送完成：marker OOB_LIVE_LIFE_XP_GLM51_1789792313492，会话43、唯一user entry1849；发送前小万和GLM-5.1检查均通过。模型实际读取验收合同、file_list与独立audit、Builder Skill、项目8个核心文件。admission.json及原始Journey保留于glm51-local-repair；仍在执行，不计业务通过。先前Remote准备marker1789792163004本地入库0，发送器在输入前焦点校验拒绝，失败保留。


## GLM-5.1 实际返工与独立新边界

原 GLM-5.1 回合最终发生 canonical agent.status/error：回复连接已中断；没有 project_check/project_publish，结果保留失败，terminal-evidence.json 保存完整合成任务证据。模型已实际读取已发布业务工具、修改 app.js/toolkit，原独立四项生产方法测试在两个时区通过。其 supplementary tests 最后实际7通过1失败，命令尾部 echo 掩盖shell exit（输出仍明确COVERAGE_EXIT=1），不计通过。

独立从当前实际app.js快照2934a7aa06eb83f2493f8ca8fb4e4d85e9101de2438e6730c9e415fa34f4d81e确认新故障：同页跨午夜直接checkInHabit会先以旧currentDate写入，然后loadData才刷新日期。新增可执行回归保留真实生产write/load方法、仅隔离时钟/DOM/工具输入，实际失败为2026-09-19而非2026-09-20，见glm51-local-repair/in-progress-source/independent-expanded.log。这是生产逻辑复现，不冒充设备跨午夜验收。

后续新任务fixture life-xp-midnight-followup.en.json要求从保存项目继续修复及发布。首次发送在键盘输入阶段ADB超时，唯一草稿“Continue the existing Life X”，只读查库marker1789803996514入库0。保留failed result，使用已有prepare-only精确前缀恢复机制，不重发已入库请求。发送完成及后续验收待记录。

后续输入恢复：字符级ADB再次超时，完整原要求保存在工作区oob-midnight-followup.md，短指令保留原草稿前缀。整段输入实际丢字被全文校验拒绝，未发送；之后每12字符输入并实际读取UI逐段校验，252字符完整匹配后仅点击一次Send。只读DB确认原会话43唯一user entry1959、marker OOB_LIVE_LIFE_XP_MIDNIGHT_FIX_1789803996514、完整prompt逐字相等。admission.json、complete-draft.xml、send-tap.json保留；continued-observation仅观察同一回合，无重发。模型任务与真实UI验收进行中。

新回合真实工具已复现新增跨午夜失败，并由模型file_edit修复checkInHabit写入前更新日期。监督独立拉取实际app.js SHA25681d018f97839997a16939735c6527495b6d88ddca53958d61dbb77c95f631ea1，两个时区各5项生产方法回归通过，原始stdout/result位于glm51-midnight-followup/production-audit。仅读代码并隔离时钟/DOM/工具输入，不代表实际UI、发布或重启通过；保存的原业务数据仍3习惯/1水打卡。当前发布和独立页面验收仍待。

## GLM-5.1 发布后实际 UI 与重启

新回合 project_check/project_publish 实际成功。真实业务调用有效添加冥想25、拒绝非正XP、拒绝重复水打卡、有效运动打卡、独立读回通过。最后准备UI操作时，permission_section明确缺少无障碍权限；官方end_turn，没有DONE标记，Journey失败保留。不能说模型自主UI验收通过，也不是没有尝试UI。terminal-evidence.json记录原始permission卡。

外部监督从插件Open App进入。首次UI树未就绪，脚本在定位按钮前失败，没有点击或改数据；ui-reading保留失败。待实际页面树就绪后，ui-reading-ready真实reading打卡通过：60XP、等级2、原记录不变、重复按钮保护。随后仅在同一页面滚动，ui-meditation真实冥想打卡通过：85XP，未通过重开规避连续禁用。

扩展既有verify-life-xp-history-device.py的可选project-slug，兼容原life-xp-demo。新模式按只读SQLite原始记录独立计算每日/累计/等级，逐项核对实际可见历史单元格，同时核对原记录逐字段不变。ui-history通过，2026/09/19四项、每日与累计85、等级2。

强停PID29455后启动PID17120，首次被历史无障碍授权弹窗阻挡（ui-history-restart失败保留），不是数据丢失。通过实际系统设置Use Omnibot→Allow恢复无障碍，再从原插件入口打开，ui-history-restart-ready全部通过：4习惯/4打卡/1升级记录逐字段相同，页面仍85XP/等级2，restart.json记录真实进程变化。当前包仍1742ecd7测试签名Release/emulator-5580。仅模拟器真实任务，待物理真机；未完成模型自主UI、同页自然跨午夜等全部边界。

新增实际业务工具不一致：发布后entry2005 get_user_progress仍返回total_xp=0，当时前面的真实get_history/list_habits已证明水10。独立可执行入口 `node scripts/verify-life-xp-progress-tool.cjs .../terminal-evidence.json`实际失败0!=10，见progress-tool-mismatch.log。页面后来85并不修复这个旧进度工具。保留待修复，不能宣称整个生成App已完成验收。
