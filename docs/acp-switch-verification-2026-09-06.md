# ACP 切换与发送验证 — 2026-09-06

## 边界与修复

沿用 `Conversation -> ACP Session -> Turn -> Item`，不新增协议、Agent
生命周期、重试或持久化消息队列。UI 切换等待仍由现有
`HarnessSwitchSendBarrier` 所有；实际请求仍进入原有发送入口。

1. **失败回滚身份混用（真机日志证实）**：16:08:52 小万切 Claude 因
   Provider 协议不兼容失败。native 已恢复小万，但 Flutter 把预显示的
   Claude ID 用于原 conversation 5 的 `session/load`，随后被正确拒绝。
   将预显示标签和已提交会话身份分开，回滚仅使用后者。
   未发现该时间点的新进程崩溃记录；不能把此问题直接定性为进程闪退。
2. **切换期间发送丢草稿（模拟器证实）**：初版回滚修复包上，16:17:30
   Kimi 切 DSH，初始化约 43 秒。期间单次点击发送，切换结束后草稿消失，
   没有新会话或 ACP prompt。原因是发送等待完成才读输入，而新会话重置
   已清空文字和附件。
3. **最终发送修复**：点击发送时捕获切换期间的文字和附件；切换目的地
   应用时，跨过既有会话重置保留输入；成功后放行原发送入口。切换失败
   不把等待的发送交给旧 Agent，保留草稿供用户处理。既有每目标提交锁
   继续阻止重复点击同时提交。不增加自动重放。

## 本地检查

- 回滚初版：111 项 Flutter 测试通过，APK 构建成功；数据保留安装到
  emulator-5556。SHA-256：
  `48efd8e6fffc384c7fd63b2708fd2b4ed2301d6ce142fb8d04104e25c50e8d14`。
- 发送修复：117 项 Flutter 测试通过；调整草稿恢复到 reset 之后，随后
  23 项切换等待与架构测试通过。包括失败不放行、过期完成不能放行新切换、
  原生切换串行及跳过过期选择。架构测试为源码契约检查，不是 widget 验收。
- Dart 定向分析无 error；7 条 warning、11 条 info。Flutter analyze 包装
  命令的分析服务启动异常，使用同一 SDK 的 dart analyze 检查。

## 设备验收

- 初版包：模拟器小万恢复 conversation 5；小万 -> Kimi 初始化成功。
  Kimi -> DSH 初始化成功，但切换期间发送失败（如上），不记为发送通过。
- 最终发送修复包构建成功（5m20s），SHA-256
  `6871c189aada246683c14618cffd8e8179bdaa96ab1770ee7aa70ef9ccffdbcd`，
  install-r 到模拟器。16:27:52 开始小万 -> DSH，初始化未结束时单次点击
  Send；16:29:24 初始化完成，16:29:26 官方 prompt 发出，16:29:41
  `end_turn`，UI 回复 SWITCH_OK。conversation 7 数据库只有 1 条
  user_message 与 1 条 assistant_message 含此标记。应用 PID10246 未退出。
- 返回原 conversation 5 恢复成功。再切 DSH 时已有进程被复用，第二条
  SWITCH_FAIL_KEEP 进入独立会话并正常完成；这没有注入初始化失败，
  **不能记为失败回滚验收**。
- 随后覆盖安装另一维护任务串行构建的整合包，SHA-256
  `557ad099fdc5d9590dc01b82719a6dcaaca522632f2c068f0a0934c1f87fb023`，
  包含相同 Flutter 修复及其 Provider 适配改动。模拟器重启恢复
  conversation 5、原摘要与历史。真机安装由另一任务负责；本任务未操作
  真机 UI，不以安装作为真机交互验收。

## 用户要求的长任务验收：流程通过，沙箱兼容性仍有问题

简单回复仅证明请求路由，不能证明真实工具执行。用户明确要求长任务后，
停止准备简单失败探针，只删除本任务尚未发送的 `FAIL_DRAFT` 草稿。
改为在小万切 DSH 的初始化期间提交下面的独立文件开发任务：

> In /workspace/acp_long_20260906 build a Python stdlib CSV report CLI with
> grouping and numeric totals. Add 12 tests covering empty input, Unicode,
> quoted commas and invalid numbers. Run tests, fix failures, verify the CLI
> with sample files and write REPORT.md with actual results. Work only in
> this new directory. Do not use memory tools.

验收须包含真实工具调用、文件与测试输出、官方完成、持久化及重启后的
记录。不把“任务已发出”或“模型自述测试成功”当成验收通过。

### 实际结果（16:36–16:47，emulator-5556）

- 使用整合包 `557ad099...`，在小万原 conversation 5 中输入任务，选择
  DSH 后于初始化结束前单次点击 Send。16:36:02 开始切换，16:36:46
  初始化完成，16:36:48 正式发送；没有二次点击或重发。
- 新 conversation **9**，session
  `0f2d9b10-7446-4b97-a04c-e6aec318f0f8`，唯一逻辑 turn
  `ec4126bf-a9fa-4fa6-a54a-74d0dfc710f9`。
- 16:39:18 收到首个工具事件，距 prompt 约 **150 秒**。最终
  16:46:53.800 官方 `PromptResponse stopReason=end_turn`；耗时
  **605616 ms（10 分 6 秒）**。期间应用 PID12040 保持存活。
- **22 个独立工具事件：20 success、2 error**，超过 16 次仍正常执行。
  两次错误是 DSH 的 `workspace-write` 沙箱在 Android/PRoot 上没有可用
  后端；分别发生在创建目录和运行 unittest。Harness 随后以自身工具的
  `sandbox_permissions: danger-full-access` 再调用后成功。该行为是本次
  已观察的官方 Harness 行为，**不是本任务关闭沙箱或新增重试**。
  不能据此宣称默认沙箱已经适配；应单独检查权限/能力配置，不能静默放宽。
- 真实 bash 返回 `Ran 12 tests in 4.114s / OK`。已读取主程序与测试源码，
  并核对实际工具输出：9 个 CLI 场景包括表格/JSON、自动数值列、Unicode、
  引号逗号、非法数值、空文件、只有表头、缺列及缺文件；样例销售总额
  3288.04、数量47，非法数据样例总额23.75、数量11；错误场景退出1。
  测试本身首轮全通过，没有伪造“修复测试失败”的步骤。
- 最终真实产物：主程序、测试文件及包标记、4个CSV样例、REPORT.md；
  报告内容与上述工具输出一致。产物位于设备
  `/data/user/0/cn.com.omnimind.bot/workspace/acp_long_20260906`。
- 执行中 Home 到后台再返回；随后切到旧 conversation 5，再回到9。
  原请求继续、原session保留，新增工具事件归属9而非5，没有重放用户输入。
- 官方完成后 force-stop/relaunch，文件SHA-256与数据库分组计数逐项
  **完全不变**。会话仍为1条用户消息、5条助手消息、22条工具事件、10条
  UI card；页面仍显示完成与10m6s，Send可用。旧conversation5压缩
  检查点仍是 `5|128000|2230|432|1788681218627`。

关键产物SHA-256：

- csv_report.py：`6e455b41e4e471af400e6fd94bdb64fa66545885116adb439864b3d1b770320f`
- tests/test_csv_report.py：`46244d29eca340ee8c9ea5a427f6fbf578cbc2a6f0c1e0fd4ae08a375218414d`
- REPORT.md：`ee4c14f83ae2e76d716451aac0e91a9428581bc02a7933f00c1137c7ce5f1580`

### 验收边界

这证明本次修复下，切换期间提交的真实长任务可完成连续工具执行、
跨会话显示与持久化；不代表所有 Harness、GUI 操作、长任务取消、
进程中断后继续执行均已验证。真机 Claude 初始化失败回滚仍缺少更新包
上的现场复验。DSH首次输出等待与默认沙箱兼容错误保留为明确待办。

验收路径：有草稿切换、初始化期间点击发送、同一 prompt 只出现一次、
真实响应完成、切回旧会话历史仍在、初始化失败保留草稿且不误发旧 Agent。
