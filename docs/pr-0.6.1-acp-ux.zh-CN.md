# 0.6.1：ACP 对话与自定义 Adapter 体验修复

## 交付结论

本 PR 汇总 `codex/release-0.6.1` 的累计修改，以减少应用自设策略、内容丢失、取消与配置故障为主；不是前端改版，也不继续建设新的 Agent 框架。版本从 `0.6.0.3`（10）升至 `0.6.1`（11），已按用户要求覆盖安装到手机。先以草稿提交审阅，升级后的数据恢复与外部 Harness 对话仍待验收，不据离线测试或安装成功宣称可以直接发布。

## 功能变化

### 1. 减少应用造成的内容丢失和能力限制

- 工具结果、终端输出、文件、浏览器观察、附件、技能、memory 和历史恢复移除一批应用默认字符数、数量、深度和截断策略；工具执行、历史保存与界面详情尽量使用同一份完整事实。
- 移除历史 DAO 的截断投影；保留完整推理与工具结果，减少恢复后只剩摘要、工具已执行但上下文丢失的问题。工具卡片的视觉预览仍可简短，详情不因此截掉原文。
- 本地 Agent 不再注入固定模型轮数、completion token 上限、上下文溢出后的自动摘要重试、长度续传和缺失工具修复用的伪用户消息。显式 `/compact` 和用户主动重试不等于自动后台补轮。
- 删除静态工具可见性、并发分类及子 Agent 能力裁剪策略；子任务继承当前执行环境的能力，不再要求固定双任务或固定子 Agent 工具白名单。
- memory 写入保持显式，定时整理为可选；移除隐藏失败学习写入和本地检索／注入裁剪。没有迁移或删除用户已有 memory 数据。
- 终端启动与输出、MCP 结果、WebChat 文件读取和历史导航同步调整，不再由这些入口重新施加同类默认截断。

这里的“不设应用默认上限”不是承诺无限设备资源。调用者明确请求的单次范围、协议校验、系统权限、进程取消和实际执行环境限制仍有各自语义；不能将其概括为“所有安全校验已删除”。

### 2. 对话、取消和恢复沿同一 ACP 生命周期

- 保持 `Conversation -> ACP Session -> Turn -> Item`；旧请求／事件输入在兼容边界归一，不增加私有终态、第二个 reducer、页面重试循环或 Agent 生命周期。
- canonical prompt 由原始 `PromptResponse` 或所属请求的真实错误结束；`session/cancel` 的应答不再冒充 prompt 已完成。
- 停止后保留剩余文字和已执行工具结果；界面在所属 prompt 结束后恢复输入。取消、自然完成和传输失败竞态不重复执行操作。
- 迟到的上一轮结果按原始请求归属处理，不能结束下一轮或误写另一对话；断开后迟到挂接的 prompt Job 不再被执行。
- 发送后立即停止，覆盖状态检查、连接、创建 session 等阶段，清理实际迟到的会话，不制造启动失败气泡；手动再次发送仍是一次正常用户请求。
- 历史／远程快照负责合并已有事实，不能凭快照或文本猜生命周期，不能擦除已提交的用户消息。桥接入站事件保持到达顺序。

### 3. 自定义 Adapter 更可用，配置不再误覆盖

- 自定义 Agent 使用自身命令、参数、环境变量和 Provider 配置；用户填写的 API key／模型环境变量不再被共享 Provider 的清理逻辑删除。预置 Agent 仍可沿现有统一 Provider 映射。
- 保存自定义启动配置不再主动断开正在运行的进程或取消当前对话；现有进程保留启动快照，**下次启动 Agent 进程**时读取已保存配置，不承诺下一条消息立即应用。
- 名称含“小万”／`xiaowan`、命令复用内置入口的用户配置不再被当作旧内置 Agent 删除或去重；只迁移确定的历史 ID，保留选择与 conversation/session 绑定。
- Harness 目录、包版本与安装资源集中到 `app/src/main/assets/acp/`；配置格式转换器从公共契约文件拆出，通用自定义 ACP 入口不要求新增厂商生命周期分支。
- 安装仅由明确的准备／安装操作触发，不因聊天连接失败偷偷安装、切路或重放用户任务。目录和状态来自原生实际数据，不在 Flutter 再造预置兜底列表。
- 累计变更还包括已有配置读写边界上的 revision、审计、加密快照与回滚接口；这不是 ACP 官方方法或 Agent 生命周期，Keystore 与真机回滚仍需单独验收。

ACP Kotlin SDK 本次仍为 **0.30.1**，不能描述为“已换成全新官方内核”。资产目录固定 Claude ACP bridge **0.74.0**、Codex ACP bridge **1.10.0**；其他目录项仍有 `latest`／`next`。必要的 Android/PRoot 启动兼容、Provider 格式转换和第三方 Harness bridge 仍存在。标准 ACP 接入与 MCP/plugin 能力扩展是方向，不代表任意 Harness 均已实测，也不是新增了一套用户动态插件市场。

### 4. 前端保持原有使用方式

不新增页面体系、导航设计或视觉改版。前端变化限于现有聊天状态／输入恢复、历史与工具详情完整性、自定义配置保留和说明，以及删除已失效的 Runtime Settings JSON 编辑器、自动补轮与重复目录逻辑。上下文设置保留用户输入的阈值，不额外强制应用自定最大值。`docs/harness-engineering.html` 是静态说明文档，不是接入应用的新页面或插件 App surface。

### 5. 安装反馈：模型提供商缓存修复

- 成功刷新以新远端目录替换旧缓存，包括合法空列表；不再重新混入服务端已移除的模型，手动添加项保持独立。
- 临时 API Key／headers 查询不污染已保存配置的模型缓存。
- 设置页点击刷新先保存有效草稿，再按保存后的 Provider revision 获取模型；离开并重开页面不再因后续自动保存丢掉刚获取的模型列表。
- 保存失败不继续拉模型；刷新返回时已切换 profile、修改 revision 或草稿，则不把迟到结果显示在新配置下。
- 删除远端模型传递现有 profile 的 base URL 和 revision，修复“页面删除成功，重开又出现”。

仅调整现有 service 与页面事件处理，无布局变化、新缓存框架或 Agent 生命周期。新增 9 项真实 service／组件路径回归，连同原有 Provider 测试共 46 项通过并加入统一运行器；原生存储和网络使用测试替身。

## 持久化验证

统一入口：

```bash
scripts/test-agent-runtime.sh --offline
```

2026-09-05 最终完整运行成功：

| 验证层 | 结果 | 证据边界 |
| --- | --- | --- |
| Android/JVM 定向回归 | 484 项，44 suites，零失败／错误／跳过 | 实际产品方法、协议处理与执行资源；Android/进程外部依赖含替身 |
| Flutter 页面／服务回归 | 455 项通过 | 真实组件交互、协调器、reducer；原生进程与数据库通道含替身 |
| Node 契约／Provider 脚本测试 | 52 项通过 | 包含源码契约与模拟 HTTP，不是 52 次真实模型对话 |
| WebChat | 12 项通过，typecheck/build 通过 | 消息合并、导航、文件请求；未做浏览器视觉验收 |

对话覆盖包括：正常双轮发送、取消后剩余工具结果、取消与成功／失败竞态、准备期间立即停止、同 session／换 session 的迟到结果、无流式输出结束、后台对话与历史合并。配置覆盖包括保存／重新打开、清空参数环境、保存失败不打断当前执行、自定义名称与命令身份、明确旧 ID 迁移。

`AcpProfileStoreTestContext` 使用真实 catalog、profile store 和临时磁盘 JSON，重新构造 Context/store 验证保存结果；只替换 Android IO 接口。不能以此冒充 Android SharedPreferences 崩溃恢复、Room、Keystore 或真实 Harness 冷启动。

收尾补测曾发现工具卡片两项旧样式断言失败：主分支组件已使用圆形 24dp 图标槽／16dp 图标和 12.5sp 标题，而测试仍要求旧的 SizedBox／20dp／18dp／12sp。经对比 `origin/main` 确认该生产组件本 PR 没有差异，只更新测试以锁定现有样式，不更改界面。工具卡片、完整 transcript 和聊天架构测试一并加入统一运行器，避免只运行主对话测试漏掉它们。

补充编译命令：

```bash
./gradlew --no-daemon --no-parallel :app:assembleDevelopStandardDebug \
  -Ptarget=lib/main_standard.dart --console=plain --quiet
git diff --check
```

APK 构建与 `git diff --check` 均通过。`--live` 真实 Provider smoke 本次没有运行。

Provider 修复的 5 个 Dart 文件另行定向分析：本机 `flutter analyze` 因旧 Flutter 入口查找不存在的 `analysis_server.dart.snapshot` 而退出，未作为通过项。用同一 SDK 的官方 `dart analyze --no-fatal-warnings <file>` 完成替代检查，无错误／警告，页面有一条括号风格 info；没有修改 SDK，也不提交工具崩溃日志。

### 手机覆盖安装

2026-09-05 在连接的 vivo V2502A 上执行 `adb install -r`，返回 `Success`；包管理器确认 `cn.com.omnimind.bot` 从 `versionName=0.6.0.3 / versionCode=10` 更新为 `0.6.1 / 11`。未卸载、未清空数据，未据安装成功宣称历史数据完整性或真实对话已验收。

可重复的官方工具路径（先通过上述构建和测试，再选定设备）：

```bash
adb devices -l
adb -s <serial> install -r app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk
adb -s <serial> shell dumpsys package cn.com.omnimind.bot
```

首次成功安装 APK 的 SHA-256：`d6b8ed8c924fd3b1a3707cc2e25491208e2ab601f449b982c4d4da5b6c8b9f6a`。随后加入 Provider 缓存修复的 APK 重新构建并再次覆盖安装成功，SHA-256 为 `23225a2c717d3369c07a4981f60161e8f7530e8b9e130354af4dca94f79d6fdf`。第二次安装曾等待手机系统确认，随后同一次 `adb install -r` 返回 `Success`；不是以首次安装结果冒充修复版安装证据。APK 本身不提交 Git。

## 合并前仍需验证

- [x] 真实手机覆盖安装并核对版本号，无卸载或清空数据。
- [ ] 验证升级后原有对话／memory 恢复、完整大输出读写的 Android 内存与数据库表现。
- [ ] 使用真实 Provider 和目标 Harness 验证连续对话、工具结果、取消／切换会话、配置保存后显式重启；确认外部包与 Android/PRoot 的兼容性。
- [ ] 验证没有默认裁剪后的长时间会话、磁盘增长和实际资源耗尽行为；物理容量问题仍可能导致真实失败。
- [ ] 真机覆盖配置审计／加密快照／回滚及失败路径；本次磁盘替身不覆盖该平台能力。

以上是验收清单，不要求为通过测试添加私有重试、自动安装、隐式摘要或第二套生命周期。

## 提交与文档

保留分支原有两个 ACP 兼容边界提交；累计实现与相互依赖测试合并为一个功能提交，文档归档与交付记录单独提交，随后用户报告的 Provider 缓存修复单独提交，避免机械按前后端拆出不可编译的中间版本。没有修改 `.github/`、`AGENTS.md`、签名配置或提交本地密钥、构建产物、设备日志。

- [当前实现与逐次修复记录](acp-runtime-custom-logic-inventory.zh-CN_副本.md)：含问题来源、先红后绿证据、验证边界及长期产品要求。
- [修改前审计快照](acp-runtime-custom-logic-inventory.zh-CN.md)、[阶段性策略审计](acp-runtime-policy-audit.zh-CN.md)：明确标为历史归档，不再作为恢复旧策略的依据。
