# 0.6.1 失败清单修复与验证

## 范围与基线

针对 PR「fix: 0.6.1 ACP 终态统一、内容完整性与聊天体验修复」列出的失败，实际复现 Flutter 32 项、Android 2 项、Node 1 项。以下分类来自失败输出与当前调用路径核对，不代表已通过基线对比证明所有问题由本 PR 引入。

## 原因与修改

| 范围 | 已确认原因 | 修改与保留的验证 |
| --- | --- | --- |
| Android 终端发行版（2） | `prootDistro=...` 文本只存在于已移除的私有 postToolRule，名称与发行版参数仍由正式工具定义提供 | 改为检查 terminal_execute 的 prootDistro 参数描述、发行版名称与未解析占位符，并断言不恢复 postToolRule |
| Node 启动/刷新（1） | 全文件禁止网络目录方法，误命中用户显式刷新 | 启动方法仍断言只读 Provider 缓存；显式刷新分别断言官方、自定义 Provider 查询及现有 sessionConfig 更新；保留会话配置保存/恢复约束 |
| 聊天列表（8） | 正文由富文本渲染并带行内尾部组件，旧纯 Text 精确匹配找不到内容 | 查询可见 RichText 正文，保留折叠前后可见性、段落顺序、头像和工具卡断言 |
| 场景模型（10） | 设置页移除刷新后普通 Provider 目录没有更新入口；另一个测试仍操作已移除的 GUI 专用开关 | 设置页先显示缓存，再调用既有异步刷新；不改变 ACP 启动路径。GUI 使用共享 scene binding，新增官方转自定义后销毁/重建页面恢复选项的验证 |
| 历史页（1） | 缺少 SharedPreferences、StorageService、ProviderScope 初始化，日期语言依赖宿主环境 | 补齐实际页面依赖并固定测试语言，保留日期分组和归档操作验证 |
| 请求卡（2） | 夹具声明 Claude，断言却要求另一个 Harness ID | 断言响应路由与原请求身份一致，保留 session/conversation 与答案内容验证 |
| 引导页（1） | 场景减少后 take(3)/skip(3) 将记忆嵌入错分到对话页 | 按现有 scene.memory 身份分组，保留用户完整配置引导测试 |
| OmniFlow 执行中心（3） | 文案/参数断言过时；夹具已关联 Function 却尝试再次注册 | 检查真实 run_id 参数；已关联记录打开已有 Function，不再重复注册；单独用未关联夹具验证注册后详情加载失败时的响应恢复 |
| 错误组件（1） | 测试改变全局 ErrorWidget.builder 未在 Flutter 框架检查前恢复 | 在 finally 中恢复，继续验证安全错误组件 |
| 图片预览（4） | pumpAndSettle 不保证真实图像解码或文件 I/O 完成；解码后另发现 contain 放大小图边界 | 测试等待真实解码/分享调用；预览边界使用 scaleDown，与显示尺寸一致。保留大图、自然尺寸、宽图和系统分享断言 |
| 背景预览（2） | 断言使用旧文案且未固定全局语言 | 对齐当前本地化文案，保留颜色、字体大小、独立图层验证 |

## 验证结果（2026-09-06）

- 独立检出修复提交 `0a0218eb1`，排除其他任务未提交修改：Flutter 全量 **1108/1108**；Node 统一入口 **58/58**。
- 共享工作区：Android app JVM **887/887**、126 suites、0 errors/skipped；APK assemble 成功。共享工作区另有其他任务未提交代码，因此这两项不冒充独立提交验收。
- 共享工作区 Flutter 1115 项通过，包含其他任务新增测试；不与独立检出的 1108 项相加。
- 本机 Flutter 3.35.7；独立检出 pub get --offline 按本机 SDK 解析 meta/test_api 版本，未提交锁文件变化。CI 使用 Flutter 3.38.7 与 enforce-lockfile，远端结果需单独查看。
- 定向 Dart analyze 无 error/warning；保留修改范围外的 6 项 info（已有异步 context 提示和 Matrix4 弃用提示），不为清理提示扩大重构。
- 未连接 ADB 设备；未完成最新 APK 安装、真人反馈、真实 Provider 请求或长时间压力验收。测试中的页面重建验证不等同于手机进程重启验证。

## 持久复跑

### 2026-09-06 追加：当前全部修改的本地回归

测试对象为 `64ad2b15c` 加当前未提交的 Provider/Adapter、配置面板及回归测试修改；不是仅测试 HEAD，也不是独立 PR 检出。没有跳过失败用例或修改生产代码以通过本轮测试。

| 检查 | 结果 |
| --- | --- |
| Flutter 全量 `flutter test` | 1120 通过，0 失败 |
| Android app 全量 `:app:testDevelopStandardDebugUnitTest` | 126 suites，889 通过，0 failures/errors/skipped |
| Node 协议、Provider、memory、目录同步测试 | 59 通过 |
| WebChat 测试 | 12 通过；typecheck、build 通过 |
| 全项目 Dart analyze | 0 error、69 warning、277 info；使用 `--no-fatal-warnings`，不能解读成零告警 |
| `git diff --check` | 通过 |

复跑本轮完整范围（Flutter/Dart 需在 PATH，或换成本机 SDK 绝对路径）：

```bash
(cd ui && flutter test)
./gradlew --offline --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest
bash scripts/test-agent-runtime.sh --offline --skip-gradle --skip-flutter
(cd ui && dart analyze --no-fatal-warnings)
git diff --check
```

覆盖现有测试中的模型目录空缓存、刷新成功与失败、参数读写互斥、Provider 映射和用户配置保留、配置持久化、会话切换与迟到响应、聊天历史、工具与审批、终态和流式 Markdown 渲染。配置面板的 12 项测试也已纳入统一入口，包含在 Flutter 全量计数内，不能重复累加。

**验收边界**：以上是本地单元/组件/协议测试，不是每个真实 Harness 的端到端验收。没有调用真实 Provider、运行手机 instrumented tests 或完成手机安装；此前 debug APK 已构建成功，本轮没有修改生产代码或重新构建 APK。外部 Harness 已有会话刷新仍可能返回内存配置；用户“首次刷新直接报错”仍缺实机日志，不能因全量测试通过而关闭这两个问题。全项目静态提示未逐项做历史基线归因，本轮不做无关清理。

### 原始修复集复跑

### 四项模拟器验收进展（2026-09-06，未完成）

- 时间：当前工作区保留同一已接纳 prompt 的开始时间，避免页面派发快照清空；`page dispatch snapshot preserves prompt timing through history commit` 覆盖接纳、页面快照、ACP 输出、完成与历史写入。相关 coordinator、footer、模型面板合计 107 项通过，APK 构建成功。
- Web 与输入适配：`AgentWebRuntimeTest` 21 项、`AgentWebPluginTest` 2 项、`AcpPromptInputCompatibilityAdapterTest` 4 项通过，并加入 `scripts/test-agent-runtime.sh`。这些是配置／进程管理模拟测试，不是实际 Kimi／DeepSeek 网页对话通过。
- `OmniFlowTargetSmall` 已启动，`emulator-5554` 覆盖安装成功，Launcher 启动返回 `Status: ok`。停在首次使用引导，尚未配置 Provider 或发送验证对话。
- 模拟器 host GPU 截图全黑但可访问性树更新，设备 Awake 且无窗口 SECURE 标志；模拟器输出包含 `glUniformMatrix3fv error 0x502`。已正常关闭该模拟器，改为软件 GPU 冷启动，不清除用户数据。复跑命令：`emulator -avd OmniFlowTargetSmall -no-snapshot -no-audio -gpu swiftshader_indirect`。
- 后续必验且不能省略：新回复时间实际可见并重开保留；各 Harness 真正输入／输出；Kimi Web 与 DeepSeek WebUI 实际启动和交互；切换模型后请求实际生效及返回会话不串配置。当前不具备将四项目标标为完成的证据。
- 软件 GPU 冷启动后截图恢复，已通过新增只读设备门禁 `ADB=/path/to/adb node scripts/verify-agent-device.mjs emulator-5554`（验证在线、启动完成、版本、进程与前台；不代表对话通过）。
- Ubuntu 硬链接修复进展：最小复现为应用 UID 在自己的缓存目录创建普通硬链接也返回权限错误；内置 PRoot 帮助明确提供 `--link2symlink` 对应此场景。现在只对 rootfs 解压调用内置 PRoot，提前安装原有 PRoot 文件和库，不改变 ACP 最终启动参数。`scripts/verify-rootfs-hardlinks.mjs SERIAL plain` 稳定失败，默认 production 模式读取实际 `init-host.sh` 解压命令并验证官方归档的 gunzip/uncompress 内容一致，模拟器通过。脚本仅生成独立缓存夹具，不读取凭据；夹具保留在 app cache 的 `oob-rootfs-hardlinks.*`。
- 重新构建及覆盖安装成功。首次失败的模拟器 rootfs 已移至 `local/ubuntu-failed-hardlinks-20260906` 保留可恢复副本，配置、history、workspace 未清除；通过应用 Start setup 重新安装，已观察到新 uncompress 链接正常生成，tar 进程仍在工作，尚不提前声称完整环境安装通过。
- 随后该安装尝试已终止，页面在 82% 报 `Command timed out after 30000ms`；tar 进程退出且没有 ready 标记。确认来源是 `EmbeddedTerminalSetupManager.getPackageInventory()` 的 30 秒隐藏命令，它包含首次 rootfs 解压时间。硬链接错误已越过，但环境仍未安装完成；后续需要沿现有安装与终端 owner 处理准备阶段预算，不得把此次尝试记为通过或仅重试冒充修复。
- 后续修复：库存探测和包安装在 `EmbeddedTerminalSetupManager` 共用原有 15 分钟安装预算，其他终端／ACP 超时未改。契约回归 44 项通过，环境安装定向 JVM 测试通过，新 APK 构建及覆盖安装成功。第二个中断测试 rootfs 备份在 `local/ubuntu-timeout-20260906`，无删除；通过 `scripts/start-agent-device-setup.mjs SERIAL` 重走保存的引导选择。模拟器实际观察到 tar 超过 30 秒仍正常执行，随后正式 `.omnibot-rootfs-ready` 标记生成，进入 apt-get 包安装阶段。此时证明 rootfs 安装成功，不代表工具安装、真实模型对话或四项目标全部完成。
- 测试 Provider：`node scripts/agent_provider_smoke.mjs` 使用既有环境凭据，GLM-5.1 的目录和 API 冒烟请求通过（该脚本允许 reasoning-only 短响应，因此不等同于可见文本验收）。新增 `scripts/configure-agent-test-provider.mjs emulator-5554`，复用现有 debug receiver，以 stdin 传凭据而非本机参数或脚本常量；已确认独立 `oob-emulator-regression` Profile 与 GLM-5.1 绑定成功。只更新测试 Profile，不清除其他 Profile，后续必须从 UI 验证切换及实际请求。
- 安装等待证据：同一次 apt-get/dpkg 进程仍在工作；从 ca-certificates 的证书更新推进到 `121 added` 和其他软件包配置。没有因界面长停 98% 就重启安装；这仍不是安装完成证据。
- 该次基础工具安装随后已结束，页面报环境配置失败但只展示长输出，未明确展示终止原因。`scripts/verify-agent-tools.mjs emulator-5554` 通过应用已安装 init-host 实际探测，确认 node/npm/python3/pip3 存在，uv 与外部 Harness 缺失；脚本因缺项返回非零，不伪报整体就绪。确认安装进程终止后，通过原有 UI 返回 Start setup，只补库存探测判定缺失的基础项，不重建 rootfs。四项对话验收仍未通过。
- 补装基础项后，应用页面实际显示 100% / `Your development setup is ready`，完成引导进入聊天。ACP 配置页实际显示 GLM-5.1。发送 `Reply with exactly OOB_TIMING_OK`，实际收到 `OOB_TIMING_OK`；结束后的可访问性树仍只有 token 数字，无时间，**时间需求仍失败**，此前 107 项测试不能关闭此问题。正在使用 `[DEBUG-oob-timing]` 临时诊断定位真实快照／完成路径，诊断只含布尔状态与计数，完成定位后必须移除。
- 当前状态更正：重新检查发现工作区的保留计时修改与 `page dispatch snapshot preserves prompt timing through history commit` 测试已消失，HEAD 仍为 `64ad2b15c`；来源未确认，不能覆盖不明来源的改动。引用已移除变量的临时诊断导致一次构建失败，已完整撤除本轮 `[DEBUG-oob-timing]` 诊断，相关生产文件无本轮残留 diff。必须先确认并重新落实时间修复，再重新构建和实测；旧测试通过记录不代表当前树通过。已请求确认是否有并行任务修改这两处。
- 首次安装选择 Ubuntu、Chat Agent Assistant，以及 Codex/Claude/OpenCode，点击 Start setup 后在 54% 失败：`tar: can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl': Permission denied`，`usr/bin/uncompress -> usr/bin/gunzip` 同样失败，最后 `Failed to extract ubuntu rootfs.`。当前入口为 `ReTerminal/core/main/src/main/assets/init-host.sh` 的 `tar -xf`，尚未归因或修改。应在该解压边界构建硬链接失败的持久化回归，不应跳过错误、写 ready 标记或关闭安全校验来伪造安装成功。

真机尝试记录（2026-09-06）：用户要求真机运行后，`adb devices -l` 曾发现 `b49f281b`（PJE110，状态 device）。随后读取已安装包版本时即返回 `adb: device 'b49f281b' not found`；无线 mDNS 无设备。未执行安装、启动或模型交互，不能记为真机通过。恢复连接后先重新确认设备及 APK，再执行 `adb -s b49f281b install -r app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk`，核验成功后继续模型刷新／选择／会话切换验收；不卸载、不清除数据。

```bash
(cd ui && flutter test --reporter expanded)
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest
bash scripts/test-agent-runtime.sh --offline --skip-gradle --skip-flutter --skip-webchat
./gradlew --no-daemon --no-parallel :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
git diff --check
```

### 时间缺失的当前修复（2026-09-06，用户再次报告后）

- 在当前树新增 `page snapshot preserves admitted prompt timing through history`：调用真实 coordinator 的请求入口、页面快照、ACP 输出、完成与历史提交，不注入开始时间。修复前 `endedAt` 实际为 null，测试失败。
- 原因：`replaceConversationSnapshot` 无条件清空 `agentEntryStartTimes`，删除了请求入口记录的计时。现在只保留绑定到同一会话、同一活动请求的 prompt 开始时间；没有添加计时器、协议或完成事件，也不补造旧历史时间。
- coordinator 与 footer 95 项测试通过；定向 Dart 分析无错误（9 项既有警告）。APK 构建成功，`emulator-5554` 覆盖安装成功；手机不在线，不能据此声称手机验收完成。
- 新增只读设备断言：`ADB=/path/to/adb node scripts/verify-reply-timing.mjs SERIAL REPLY_MARKER`，要求实际可见的唯一回复同时包含结束时间和耗时；UI dump 失败直接失败，不读取旧快照冒充新结果。
- 当前模拟器实际对话通过：从输入框发送 `Reply TIME_OK`，小万／GLM-5.1 返回 `TIME_OK`，截图与可访问性树均显示 `12.7s`、`02:25:37`。完成后停止并重开应用，再次运行设备断言通过，两个值保持不变。启动命令曾报告等待超时，但后续新 UI dump 确认应用恢复并显示持久化回复；不能把该命令自身记为启动成功。此结果仅覆盖当前模拟器的小万真实对话，不代表其他 Harness 或未连接手机通过。

### 外部 Harness 实机流程继续（2026-09-06）

- 当前模拟器 `verify-agent-tools.mjs` 实际确认 node/npm/python3/pip3/uv 已安装；kimi/dsh/codex-acp/claude-agent-acp/opencode 均未安装，因此外部输入输出与两种 Web UI 尚未通过。
- 从 Settings → Agent Mode 的 Kimi Code 行执行现有 Install 动作。可重复命令：`ADB=/path/to/adb node scripts/start-harness-device-install.mjs emulator-5554 'Kimi Code'`。要求目标行已经可见，按行名称及当前可访问性 bounds 定位安装按钮；不绕过应用安装 owner，不自动重试。
- 页面显示 Installing in background；设备进程树确认 PRoot 13497 → sh 13521 → `kimi-code@latest` 13531 正在执行官方 `@moonshot-ai/kimi-code@latest` 安装。此记录仅为进行中证据；后续先检查同一进程是否结束及应用结果，不能重复启动安装或把它记为输入输出通过。
- 后续同一次安装已结束：npm 日志确认 `@moonshot-ai/kimi-code` 0.41.0 下载、node-pty 安装脚本执行，最终 exit 0 / info ok；设备库存 `kimi:present`，其他四种外部 Harness 仍缺失。未在安装进行中重启或重复安装；ACP 初始化及 Kimi Web 实测仍待完成。Agent 管理页 5 项既有测试通过（含后台安装不阻塞其他设置、插件 Web 动作入口）。
- 模型切换初步实测：Provider 页显示 95 个模型且 95/95 在聊天列表；聊天模型面板首次展开经历 Loading models 后返回实际模型列表。选择 `DeepSeek-V3-250324` 后面板显示对应名称，从真实输入框发送 `Reply MODEL_OK`，返回 `MODEL_OK`，`verify-reply-timing.mjs emulator-5554 MODEL_OK` 通过。此证据证明选择后仍能完成真实对话，但尚缺请求层模型 ID 核验、切回原模型及跨会话隔离，不将整项模型切换标为通过。
- Kimi Web 已通过应用入口启动现有后台服务，并打开系统 Chrome；Chrome 首次引导选择不登录账号。真实页面白屏：Chrome 109.0.5414.123 的 `Array.prototype.toSorted` 为 undefined，官方 Kimi 前端抛 `TypeError: e.toSorted is not a function`，body 字符数及交互控件数均为 0。
- 持久化白屏门禁：`adb -s SERIAL forward tcp:0 localabstract:chrome_devtools_remote` 返回端口后，执行 `node scripts/verify-agent-web-page.mjs PORT 'Kimi Code Web' [--reload]`。仅诊断当前匹配标签页；输出能力、控件数和已隐藏 URL 的异常，不记录凭据或网页内容。`--reload` 仅用于无未保存输入的测试页。此门禁已稳定捕获白屏，不能作为对话验证替代品。
- 正在按 Chromium 官方下载说明准备 Android_Arm64 快照 1692964（官方 bucket generation 1788631464561751），用于独立测试浏览器验证，不覆盖原 Chrome、不修改 Kimi 前端。尚未安装／验证新版浏览器，不提前将白屏记为修复。
- 对照结果：官方 zip 的 MD5/base64 与存储元数据一致（`9QCM4t16MQm1GNJKRj2xZA==`）；提取 ChromePublic.apk，确认包名 `org.chromium.chrome`、版本 155.0.8044.0 后安装成功，未覆盖 `com.android.chrome`。新浏览器不登录账号、不启用通知。相同 Kimi 服务无需重启，页面加载后标题为 `Kimi Code`；同一检测器返回 `arrayToSorted:function`、bodyCharacters 310、controls 16、exceptions []，白屏对照通过。该版本为测试快照，不作为生产浏览器版本建议；实际对话仍待验收。
- Kimi Web 真实对话进展：将应用在旧 Chrome 中打开的原始本地鉴权链接仅在内存中转交新版 Chromium，未打印或保存 token；刷新后通过官方鉴权。完成官方欢迎页，跳过另行登录 Kimi 账号，工作台已显示共享 Provider 模型 `GLM-5.1 · thinking`。发送 `Reply KIMI_WEB_OK` 时官方页面要求先选工作区，使用其目录选择器选择 `/workspace`；确认选择后官方页面自动继续原发送，没有再次提交。实际回复 `KIMI_WEB_OK`，请求完成。
- 新增 `verify-kimi-web-reply.mjs SERIAL MARKER`：要求 Kimi 会话页面可见、独立回复 TextView 恰好等于测试 marker（不是用户输入 `Reply MARKER`）、没有仍启用的 Interrupt。此脚本已在模拟器上通过。随后执行 `verify-agent-web-page.mjs 53356 'workspace | Kimi Code' --reload`，官方页面恢复，无脚本异常；再次运行回复断言确认恢复结果。此验收覆盖一条小型真实对话及刷新恢复，不代表 ACP、多工具交互、DeepSeek Web 或全部模型切换通过。
- Kimi ACP 实测：在应用右上角模式选择菜单选择 Kimi Code，完成官方 ACP 初始化后进入独立新会话。输入框发送 `Reply KIMI_ACP_OK`，先显示 Processing，随后真实回复 `KIMI_ACP_OK`，显示 `15.0s / 03:04:34`。`verify-reply-timing.mjs emulator-5554 KIMI_ACP_OK` 通过。Home 离开再返回应用后同一断言再次通过（这是前后台恢复，不是杀进程恢复）。原 Kimi Web 进程未重启，ACP 使用独立进程；此结果覆盖基础文本路径，工具调用、取消及其余 Harness 尚未完成设备验收。
- DeepSeek 准备进展：确认无 DeepSeek/npm 安装进程后，在 Agent Mode 可见行执行 `start-harness-device-install.mjs emulator-5554 'DeepSeek Harness'`。页面显示 Installing in background，现有安装 owner 启动 PRoot 18025，apt-get 18259 执行 `install -y --no-install-recommends build-essential python3`，其子 dpkg 18315 正在配置 dpkg。Kimi Web/ACP 独立进程继续保留。安装尚未结束，不能启动第二次安装或声称 DeepSeek ACP/Web 可用；下一步应继续检查同一进程及应用安装结果。
- 请求模型的后续核验设施：`agent-provider-observer.mjs` 为测试专用透明 HTTP 转发器，只向明确指定的上游 origin 转发，保留请求与 SSE，不重试、不改写模型响应；日志只包含请求序号、端点、模型 ID、状态码，不含凭据和对话内容。2 项转发／脱敏测试通过并接入 `test-agent-runtime.sh`。真实 Provider 冒烟也经该转发器通过（GLM-5.1，目录 200、完成请求 200），这仍不是设备模型切换的证据。
- 当前测试观察器在本机 loopback 55684 运行，上游为原 llmapi.paratera.com；模拟器专用 `oob-emulator-regression` Profile 已临时配置到 `http://10.0.2.2:55684`。保留运行进程，不强制更新已有 Kimi 会话。后续核验请求层模型 ID；验证完成必须用不覆盖 `OMNIBOT_TEST_BASE_URL` 的原环境运行 `configure-agent-test-provider.mjs emulator-5554`，恢复原测试 Provider 地址后再关闭观察器。手机和其他 Profile 未改。
- 请求层设备证据：从 Kimi 切回独立小万会话，面板显示 GLM-5.1；发送 `Reply MODEL_A1`，观察器请求 id=3 确认 `/v1/chat/completions` 的 `model=GLM-5.1`，响应 200，实际回复 `MODEL_A1`，时间断言通过。随后展开模型选择列表出现真实 `session/load: timeout`，仅保留 GLM-5.1；对应观察器 id=4 `/v1/models` 为 transport_error。未自动重试或把第二次成功冒充首次成功。
- 失败后独立诊断：直连 Provider 与经观察器的现有 Provider smoke 均通过；观察器 id=5 models 200、id=6 completion 200。目前不足以把该次超时归因于应用或上游。测试观察器源码补充时间戳、耗时、客户端断开标志和异常类型（不打印异常正文）；当前仍运行的旧观察器尚未重启加载此诊断改动。下一步需核对超时边界并继续 A→B→A 的请求层验证；该项尚未通过。
- 已确认并修复一项会触发正常慢响应失败的应用限制：`HttpController.fetchProviderModels` 的 connect/read/call timeout 均被单独限制为 4 秒，旧注释用于启动探测，而现有启动已读取缓存。新增真实本地 HTTP 服务延迟 5 秒返回模型目录的回归；修复前 SocketTimeoutException，修复后使用与其他 Provider 检查一致的 OkHttp 默认超时通过，没有增加重试或无限等待。CustomHeaders 3 项、Anthropic 9 项通过，新回归加入统一测试脚本。APK 构建成功，设备重新验收尚待完成；不据此声称所有上游超时已消失。
- DeepSeek 安装最终结束：镜像尝试 npm 日志 19_16_17 与官方源尝试 19_22_21 均 exit 1，PRoot 18025／npm 19582 已不存在。日志末尾未给出具体原因，不应直接归因于磁盘或网络；尚无 DeepSeek 可用证据。确认安装终止后才开始覆盖安装模型刷新修复 APK，未打断活跃安装。
- 覆盖安装实际失败：`INSTALL_FAILED_INSUFFICIENT_STORAGE`。模拟器仍为旧 APK，不能将新模型刷新修复记为设备已生效；当前需检查本轮创建的失败 rootfs 副本／测试缓存，保留用户配置与历史后腾出安装空间。这能证明 APK 安装缺空间，但不能反推前面 npm 失败一定是同一原因。

所有原失败场景均保留或替换为当前公开接口的行为验证，没有通过跳过测试、放宽生命周期语义或恢复私有协议使测试通过。

### 模拟器安装空间恢复（2026-09-06）

- 再次运行 `verify-reply-timing.mjs emulator-5554 MODEL_A1`，实际回复的耗时和完成时间均可见；当前 coordinator 的 `page snapshot preserves admitted prompt timing through history` 定向回归通过。手机不在线，不能推广为手机验收。
- 本轮生成的两个失败 rootfs 副本已归档至 `/tmp/oob-failed-rootfs-backup.Z2FL5m/failed-rootfs.tar`。设备端 `tar -cf - -C local ubuntu-failed-hardlinks-20260906 ubuntu-timeout-20260906 | sha256sum` 与电脑 `shasum -a 256` 均为 `428f7c32dec71341cd44544b7e5e3fdd5693bb33e2724090b74beba6a5589bf2`，`tar -tf` 成功，分别包含 3412 和 3418 个归档条目。先前同目录 `.tar.gz` 校验失败，不可用于恢复。
- 仅在校验通过后删除设备上的这两个确切失败副本；当前 `local/ubuntu/.omnibot-rootfs-ready` 仍存在，未删除配置、历史或 workspace。可用空间从约 428 MB 增至 842 MB，开始重新覆盖安装已构建的模型刷新修复 APK，安装结果待下一条证据确认。
- 测试 Provider 观察器 2 项回归再次通过；实际 A→B→A 及其余 Harness 验收仍未完成，不用这些局部检查代替总验收。
- 随后 `adb -s emulator-5554 install -r .../app-develop-standard-debug.apk` 返回 `Success`。MainActivity 恢复后重新取得成功的 UI dump，原 `MODEL_A1` 回复及 `15.7s / 03:17:20` 均保留；首次启动期间一次 dump 为 null root，已丢弃并重新采集，没有拿旧 XML 充当验收。当前模型刷新修复已安装，真实刷新结果仍待验证。
- 新 APK 首次展开 Model：观察器 id=7 的 `/v1/models` 返回 200，页面直接显示实际目录，无 `session/load: timeout`。选择 `DeepSeek-V3-250324` 后面板显示对应值，输入 `Reply MODEL_B1`，关闭键盘后确认输入完整再发送；观察器 id=8 确认真实请求 `model=DeepSeek-V3-250324`，返回 200，`verify-reply-timing.mjs emulator-5554 MODEL_B1` 通过。这是 A→B 请求级验证，尚待切回 A 与跨会话隔离。
- 输入过程发现一个待复核现象：键盘展开时截图中 composer 不可见，关闭键盘后输入 `Reply MODEL_B1` 完整保留且可发送；未重发或清空输入。应在下一轮验证键盘遮挡／布局路径，当前证据不能解释成输入丢失，也不能忽略为已通过。
- 切回验证完成：再次展开目录的观察器 id=9 返回 200，通过面板搜索并选择 `GLM-5.1`，确认面板值后发送完整输入 `Reply MODEL_A2`；观察器 id=10 的实际请求为 `model=GLM-5.1`，响应 200，`verify-reply-timing.mjs emulator-5554 MODEL_A2` 通过。结合 id=3 的 A、id=8 的 B、id=10 的 A，当前小万同一会话 A→B→A 已得到请求与可见回复双重证据；外部 Harness 和跨会话隔离不能据此关闭。

### 键盘输入路径的新增回归（2026-09-06）

- 新增只读 `verify-chat-keyboard.mjs emulator-N`，确认 IME 已显示、应用聚焦输入框及 composer 模型按钮实际可见；当前旧 APK 的一次复查通过，说明此前遮挡不是每次发生，不能声称已在设备稳定复现。
- 现有 `ComposerKeyboardMetricsTracker` 的确定性失败：平台高度从 0 直接变成 300（没有中间动画帧）后，计算器只给出距底部 40，完整键盘被防抖吞掉；相同后续采样又直接返回缓存。新增 `a keyboard opening without intermediate frames cannot hide composer` 修复前失败（Expected >=300, Actual 40），修复后通过。
- 修改现有防抖判断，仅导航栏静止 inset 内的残余信号可暂缓；完整键盘高度立即交给原布局公式，不添加计时器或 Agent 生命周期。键盘计算器、输入意图与页面链路共 22 项通过，加入统一 `test-agent-runtime.sh`；定向 Dart 分析无问题。
- `/tmp/oob-keyboard-build.log` 构建成功（18 秒），已发起覆盖安装，设备验收待确认。该回归证明一个可导致遮挡的布局缺陷，不足以证明此前设备现象的每次成因。
- 随后覆盖安装 `Success`。启动尚未稳定时，时间断言因 marker 不可见失败、键盘断言因 IME 未打开失败，未跳过断言；等待页面真实恢复后重新采集成功 UI dump，键盘打开时输入框位于 `[81,477][643,573]`、模型按钮位于 `[391,585][447,641]`，`verify-chat-keyboard.mjs` 通过。原 `MODEL_A2` 的 `11.7s / 03:45:21` 仍存在，时间断言再次通过。此次设备通过与单元测试的确定性红绿分开记录。
- DeepSeek 后续尝试：在 Agent mode 滚动至完整可见行后，`start-harness-device-install.mjs emulator-5554 'DeepSeek Harness'` 成功派发原有 Install 动作。第一次脚本因行底部 Install 按钮未进入可见范围而失败，未点击其他按钮；滚动后才重新执行。当前进程树为 app 20951 → PRoot 21608 → sh 21632/21643 → npm cache clean 21675，页面显示 Installing in background。磁盘已恢复可用空间后发起这次正常安装，尚不声称前次 npm 失败是磁盘原因；后续必须跟踪同一安装，不重复启动或提前记为 DeepSeek Web/ACP 通过。
- 同一次安装推进：缓存清理日志 `19_53_42` exit 0，随后 PRoot 21608 下的 `dsh@next` 21720 下载依赖；`19_55_23` 日志最终记录 exit 0 / info ok，后面仍有 generic complete-log 提示，因此只记为 npm 阶段退出成功。父安装 shell 21643 继续执行后续 Node 检查，不能将整项 DeepSeek 安装或 ACP/Web 标为完成。
- 跨会话模型隔离：新建独立小万会话、选择 `DeepSeek-V3-250324`，发送完整 `Reply ISOLATION_B`；观察器 id=12 确认实际模型 B、200，回复与时间断言通过。返回含 MODEL_A1/B1/A2 的旧会话，面板仍为 GLM-5.1；发送 `Reply BACK_A`，观察器 id=13 确认 GLM-5.1、200，时间断言通过。旧 MODEL_A2 初次检查因处于屏幕下方而不可见，后续通过当前可见的旧消息和面板确认选中了原会话，不将滚动可见性失败报告成历史丢失。此证据覆盖“新会话 B → 旧会话 A”请求隔离，不代表所有外部 Harness 的配置隔离。
- DeepSeek 安装本次最终退出，21608/21643 不再存在。库存脚本确认 dsh 命令存在，但新增可选用法 `verify-agent-tools.mjs emulator-5554 deepseek-harness-acp` 执行仓库 catalog 原有 `managedAdapterHealthCommand` 返回 failed，不能算安装完整。设备上 dsh/dsh-acp-android/pnpm 均存在，ACP profile package.json 与 pnpm-workspace.yaml 已创建；`node_modules/@openma/deepseek-harness-acp/package.json` 缺失。该 profile 的 dependencies 为空、bundles 为 `@deepseek-ai/dsh-base` 和 `@deepseek-ai/dsh-acp-app`。下一步应检查实际 DSH 插件安装命令与当前官方 ACP 入口，不能重复全量 npm 安装掩盖阶段差异。
- 官方入口对照通过：安装的 `@deepseek-ai/dsh` 为 0.1.2-rc.1，官方 `dsh-acp-app` bundle 自带 ACP stdio 配置。新增 `verify-deepseek-acp-initialize.mjs emulator-5554`，通过设备原 init-host 直接启动官方 `node --expose-internals .../dsh/lib/bin.js --profile acp`，不加载应用的 headless patch、不安装第三方 ACP 插件、不发送模型请求。实际 initialize 返回 protocolVersion 1，stdin EOF 后进程正常退出，脚本 exit 0。90 秒是测试观察窗口，不修改应用或官方会话超时。
- 因而后续可以从现有 catalog/安装 owner 移除过时的第三方插件依赖与 Web 屏蔽层。但必须连同共享 Provider 映射一起对齐：现有 ACP 配置仍输出 DSH_MODEL/DSH_PROVIDER，官方 ACP bundle 默认 provider=deepseek-official、model=deepseek-v4-flash；Web 已有 llm-pi-ai/agent-default-model 官方 patch。此次只证明原生 ACP initialize，不证明使用共享模型的实际对话，更不证明 Web 已通过。
- 官方 ACP 接入已落到代码：catalog 固定到已验证的 `@deepseek-ai/dsh@0.1.2-rc.1`，启动参数使用 `--profile acp --patch .../omnibot-dispatch.patch.yml`。安装脚本删除第三方 ACP 插件、pnpm profile 安装、headless 插件屏蔽、profile reset 与全局 npm cache 清理，保留原生 PTY 检查／必要修复及仅传 Node flag 的薄启动脚本；现有 profile 和用户插件不删除。
- ACP 共享 Provider 配置复用原有 Web Cordis Provider 构造器，只将模型选项写到官方 `acp` 配置项，Web 仍写 `agent-default-model`。凭据通过 `OMNIBOT_DSH_API_KEY` 传递，patch 只引用变量名；此键加入现有共享 Provider 环境归属集合。新测试 `deepSeekAcpUsesOfficialProviderPatchWithoutPersistingCredentials` 在旧实现下 AssertionError，修复后通过，同时验证模型、路由 Header 与不落盘 API key。
- 组合回归：AgentConfigAdaptersTest 38、ManagedAcpAdapterPreparationTest 10、EnvironmentSetupLogicTest 17、AgentWebRuntimeTest 21 全通过；协议契约 44 项通过。安装脚本 `sh -n`、`git diff --check` 通过。`/tmp/oob-dsh-official-build.log` 构建成功（15 秒），已发起覆盖安装；尚未完成应用内官方 ACP 的真实模型对话，不能据此关闭 DeepSeek ACP/Web 验收。
- 首次覆盖安装再次返回 `INSTALL_FAILED_INSUFFICIENT_STORAGE`，当时约 751 MB 可用；未安装成功。确认无 npm/DSH 安装进程后，仅清理本轮下载的 `/data/user/0/cn.com.omnimind.bot/local/ubuntu/root/.npm/_cacache`（约 235 MB，可重新下载），保留 `_logs`、已安装包、配置、profile、history，再发起 `install -r`。这是本次测试空间整理，不把清缓存逻辑重新加入生产安装脚本。
- 清理后约 968 MB 可用，第二次覆盖安装返回 `Success`。官方 DSH 接入 APK 现已装入模拟器；应用内准备／选择、真实共享 Provider 请求及 Web 验收仍待完成，不用此前独立 initialize 成功替代这些后续路径。
- 应用内准备实测：`start-harness-device-install.mjs` 派发后，官方脚本完成；设备 `dsh-acp-android` 不再包含 headless patch，catalog 健康检查脚本通过。随后应用创建 PRoot 23687 → Node 23711，参数确为官方 ACP profile 与 dispatch patch；设备 patch 的 ACP provider/model 为 `omnibot-dispatch / GLM-5.1`，引用环境凭据。
- 但应用内 initialize 仍报 `Timed out waiting for 90000 ms`，界面显示“等待 Dispatch Model 配置”，不能把该文案当作根因。`LocalAcpRuntime.initializeAgent` 是该 90 秒边界；异常处理先抛 CancellationException，超时后 23711 仍存活，清理路径需继续核对。没有增加重试或延长生产超时。
- 独立对照：`verify-deepseek-acp-initialize.mjs emulator-5554 --managed-patch` 使用同一已生成 patch、cwd=/workspace，21898 ms 返回官方 initialize 成功，EOF 正常退出。它没有继承应用的凭据或全部环境，因此不能直接证明应用 transport 有错；差异包括应用注入的禁用 link2symlink、Node 文件系统兼容 preload 与旧 DSH 环境变量。新增 `--disable-link2symlink` 单变量对照正在执行，需等待当前进程结果。
- 新增 `tap-agent-device-control.mjs emulator-N LABEL` 按新 UI dump 中唯一可点击控件的首行名称定位，已用于 Settings → Agent Mode；不会读取旧 dump 或在控件不唯一时随意点击。
- 禁用 link2symlink 单变量对照已通过：`--managed-patch --disable-link2symlink` 在 22942 ms 返回 initialize 成功并正常退出，不能将应用超时归因于该开关。应用旧的 23711 进程此时仍存在，下一步比较 Node preload／其余环境及 SDK 接收链路；未盲目撤除现有文件系统兼容措施。
- Node preload 对照通过：再加 `--filesystem-compat`，21661 ms initialize 成功；再加应用现有 `_meta` 的 `--app-meta`，22996 ms initialize 成功。均正常 EOF 退出，不应撤除已通过对照的文件系统适配或仅凭猜测删能力声明。此对照仍不包含应用全部环境或 JVM SDK。
- 为定位应用收发边界，在 `LocalAcpRuntime` 临时加入 `[DEBUG-oob-init]`：只记录 initialize 已 flush 的请求 ID、收到的响应 ID／响应布尔值，不记录 payload 内容、凭据或模型响应。诊断 APK `/tmp/oob-dsh-stdio-diagnostic-build.log` 构建成功（23 秒），已发起覆盖安装；定位后必须移除这两处诊断，不得作为最终生产日志遗留。
- 后续当前模拟器确认诊断 APK 已安装（lastUpdateTime 2026-09-06 04:40:07）。Check again 弹窗显示 Agent check succeeded，但这只报告能力，不代表 ACP prompt 已通过。实际选择 DeepSeek Harness 并发送一次 `Reply DSH_ACP_ONE` 后，24137 的 initialize 在 04:48:14.097 flush，04:49:44.048 才记录同 ID=1 的响应，04:49:44.077 报 90000 ms 超时。响应读取与截止时间仅差约 30 ms；不能据此断言 SDK 丢响应，需区分 Harness 延迟与 host 读取／调度延迟。未重发该消息。
- 本轮再次运行 coordinator 和 message footer 回归，95 项通过；在实际模拟器旧会话运行 `verify-reply-timing.mjs emulator-5554 BACK_A` 通过，仍显示 19.0s / 04:01:49。没有手机连接，此证据不代表手机版本。
- 新增 `send-agent-test-message.mjs`，限定模拟器和测试 marker，只填空草稿、核对完整输入、不自动重试。实际输入阶段通过，但发送按钮没有语义名称，脚本在点击前断言失败并保留草稿；随后仅按本次新 UI dump 的发送按钮位置人工式点击一次。该脚本当前不能声称无人值守发送通过，需要补齐控件可访问性或明确的验证定位方式，不能偷偷增加坐标兜底。
- 进一步独立对照增加 `--app-capabilities`（文件读写、terminal、plan、elicitation），与已有四个参数组合运行在 58275 ms 返回 protocolVersion 1 并正常退出。没有证据表明这些能力必然导致死锁，不撤除能力。脚本增加只输出 Agent 方法名／请求布尔值的诊断，供后续检查初始化期间的反向请求，不记录参数。
- 大输入区发送按钮补标准本地化 tooltip：Send/发送，活动请求时 Stop/停止；只改善原 IconButton 可访问性，不改变动作或 Agent 生命周期。现有实际输入测试补 tooltip 断言，旧实现为 null 时红，修复后输入区 26 项测试全绿，定向 Dart analyze 无问题。
- `[DEBUG-oob-init]` 新增 stdout read 边界，用于与现有 collect 日志比较调度延迟，仍为临时诊断待删除。`/tmp/oob-dsh-read-boundary-build.log` 构建成功（27 秒），但覆盖安装返回 `INSTALL_FAILED_INSUFFICIENT_STORAGE`，/data 剩余约 560 MB。因此当前模拟器仍是上一诊断 APK，新增 tooltip 和 read 日志尚无设备证据。只读空间检查确认 app cache 128 KB、code_cache 48 KB、tmp 7.4 MB、root .cache 97 MB，npm _cacache 已不存在；本轮未删除任何数据，不能靠再次清同一缓存解决。
- 存储后续：确认无 apt/dpkg 进程，将约 248 MB 的 `local/ubuntu/var/cache/apt` 归档到 `/tmp/oob-apt-cache-backup.se604A/apt-cache.tar`，传输进程正常退出后，完整 tar 检查通过，设备／电脑 SHA-256 均为 `123cf386ee02eb1c057829aeebc384b3d4a25dd8a9fb4199353c41eb6e1411a6`。随后仅移除该处 archives 和两个生成的 pkgcache.bin/srcpkgcache.bin，重建空 archives/partial；已安装包和用户数据不变。807 MB 可用时覆盖安装仍失败，不能声称安装成功。
- 继续备份约 196 MB 的 APT lists 缓存：`apt-lists.tar.gz` 虽命令 exit 0，但 gzip/tar 完整性失败、解压哈希不匹配，**不可用于恢复，也未据此删除设备原目录**。改用未压缩 `apt-lists.tar`，传输尚在进行。设备端原 tar SHA-256 为 `277fcd7298e6e91bb889e5f2f40a4372f91bb88799e183deb5f24fc366a4d105`；后续必须等待同一进程结束并比对后才清理。
- 未压缩 lists 传输最终 exit 0，完整 tar 列表通过，电脑 SHA-256 与上述设备值一致。随后仅删除已备份的 `local/ubuntu/var/lib/apt/lists` 缓存并重建空 partial/auxfiles（以后 apt update 可重建，亦可从 `/tmp/oob-apt-cache-backup.se604A/apt-lists.tar` 恢复）。可用空间随后约 1.3 GB；最新 read-boundary 诊断 APK 覆盖安装返回 **Success**，已发起启动。此次才完成新增 tooltip/stdout read 诊断的安装，实际发送及时间差验收仍待运行。
- MainActivity 启动命令成功。`send-agent-test-message.mjs emulator-5554 DSH_ACP_TWO` 的单次 ADB 整串输入实际变成 `eply DSH_ACP_TW`，精确草稿断言失败且未发送；不能记为 ACP 请求失败。脚本改为先核对聚焦，再逐字符分开发送输入命令，保留最后精确比对，不增加重试；此改进尚待设备验证，当前测试草稿仍保留，下一次运行不可忽略非空草稿保护。
- 后续只清除该已核对的未发送测试草稿。逐字符版本脚本对 DSH_ACP_TWO 完整核对并语义点击 Send 成功，无坐标兜底。但重启恢复的是小万会话，25169 日志证明实际连接 xiaowan-acp，UI 显示 Failed，时间断言失败；此消息不能记为 DeepSeek 验收。Provider 观察器无新增结果输出，失败原因仍需核对。
- 明确重新选择 DeepSeek Harness 后，用同一脚本发送独立 DSH_ACP_THREE，精确输入／语义发送成功。应用 PID 25169 日志确认 deepseek-harness-acp；initialize flush 为 05:05:39.444，05:07:10.350 超时，stdout read 为 05:07:17.817，collect/inbound 为 05:07:17.825。即约 98.4 秒才读到响应，read→collect 约 8 ms；不能说 SDK 在收到响应后拖延 90 秒，重点转到 Harness 启动／读取之前的耗时。没有重放该消息。
- 探针新增 `--shared-test-key`：只从 LLMTHU_API_KEY 环境取测试凭据，通过 stdin 第一行读入子进程环境，不写文件／参数／输出。与 managed-patch、disable-link2symlink、filesystem-compat、app-meta、app-capabilities 组合运行，61221 ms initialize 成功并正常 EOF 退出；此前不带凭据为 58275 ms，单次结果不足以归因于凭据。安装包内官方 PiAiAdapter.resolveModel 的代码仅从本地模型 snapshot 构造信息，不是 discoverModels 网络请求。应用已超时的 Node 25900 仍存在；待收敛初始化错误路径中的临时连接清理，不能增加重试来掩盖。
- 初始化取消清理修复：新增 LocalAcpRuntimeInitializationTest，构造替换仅发生在测试的 Android connection 边界，真实 Protocol/Client.initialize 发出请求后取消。旧实现明确失败 `Unadopted connection leaked expected 1 but was 0`。生产修改仅在原 connect catch 中以 NonCancellable 完成 nextProtocol/nextConnection 清理，再传播原取消或初始化错误；没有新增 lifecycle、重试或终止事件。
- 补充调用方 deadline 回归：初版清理后把外层 TimeoutCancellationException 包装成初始化失败，测试明确失败；现通过当前协程 isActive 区分外层已取消与当前 owner 的初始化超时，保留调用方取消语义。最终初始化 2、配置 3、运行时 30，共 35 项通过，测试已加入 test-agent-runtime.sh。
- 三处 `[DEBUG-oob-init]` 全部撤除。`/tmp/oob-init-cleanup-build.log` 测试与 APK 构建成功（27 秒），已发起覆盖安装；尚未通过设备上的进程退出断言，不能仅凭模拟测试宣称 DeepSeek 对话或清理全部验收通过。
- 该清理修复 APK 随后覆盖安装返回 Success，MainActivity 启动命令成功。当前模拟器已是无临时 init 诊断日志的清理修复版本；后续需通过既有生产日志／进程身份验证初始化失败后的资源释放。
- 当前 APK 新一次 DeepSeek 选择：26278 启动 PRoot 26595 → Node 26620，05:19:17.114 launch，05:20:44.588 initialize 成功，约 87.5 秒，未走超时清理。切换尚未完成时尝试语义 Stop 未找到控件，未点击其他位置；旧页面仍显示 DSH_ACP_TWO 的失败记录，不能把 DSH_CLEANUP_ONE 的发送派发当作已在新会话完成输入。新增 `verify-agent-process-exit.mjs emulator-N PID...` 只断言事先捕获的 PID 退出，无 kill/retry；因本次成功保持连接，未运行“应退出”断言冒充清理通过。
- 等待真实切换完成后，新页面显示 DeepSeek Harness 欢迎语，Model & settings 为 GLM-5.1。随后用持久化脚本发送 DSH_READY_ONE，观察器 id=14 确认实际 `/v1/chat/completions` model=GLM-5.1、响应 200，回复时间断言通过；Home 离开再返回后，同一时间断言再次通过。这是当前 DeepSeek 官方 ACP + 共享 Provider 的基础文本／时间／前后台恢复通过，不含工具、进程重启、模型切换或失败清理。
- 已从 Agent Mode 的 DeepSeek Harness Web 原入口发起 Web 启动；新增进程为 PRoot 27351 → Node 27378，与 ACP 26595/26620 独立。正在等待现有启动的结果，尚未确认浏览器页面或 Web 对话，未重复启动。
- DeepSeek Web 打开本地端口 45417，默认 Chrome 的渲染 gate 实际失败（bodyCharacters=67，controls=0）。当前服务正文为认证需要的 HTTP 401，不可记为渲染通过。新增 `open-agent-web-test-browser.mjs` 从已有本地 CDP 页内存转交链接到测试 Chromium，不记录 URL；但本次转交时页面已跳到无 token 根地址，目标 Chromium 没有原浏览器 Cookie，所以其 401 不能用于判定浏览器兼容性。
- 安装包内官方 dsh-client-connection 源码确认：root query token 交换 Cookie 后 303 跳到 `/`，Cookie 与 Host authority 绑定。原 Chrome 当前有一个 dsh-auth Cookie，目标 Chromium 没有；验证脚本已增加断言，禁止将已完成交换的无 token 地址当作新的登录链接。下一步需重新使用该运行进程官方打印的完整登录地址，并分别检查 Cookie 交换和最终交互。只读 HTTP 探针跟随跳转但不保存 Cookie 同样会 401，不能用它证明 token 错误；没有修改官方认证或关闭安全校验。
- 使用同一存活 Web 进程最初的完整登录链接重新进入 Chromium，链接只在内存使用，不输出。新增 `verify-deepseek-web-auth.mjs emulator-N SNAPSHOT FORWARD_PORT SERVICE_PORT`：原 token 交换得到 303/Cookie，携带 Cookie 得到 200 HTML。使用 Node http.request 明确保留浏览器 Host，避免 ADB 转发端口改变 Cookie authority；之前 fetch 跟随跳转不带 Cookie／转发 Host 差异不作为 token 失败证据。
- 新版 Chromium 的 Cookie 原样经相同 Host 回放也返回 200；正常同源 Page.navigate 后页面加载通过。持久化 `verify-agent-web-navigation.mjs 52986 45417` 记录首页、静态资源、模型目录／会话接口均 200，Cookie 阻止原因列表为空，页面可交互。此结果不是首次外部打开通过；最初 401 与后续同源导航差异仍待诊断，未关闭认证、未加自动刷新或登录补丁。
- `prepare-deepseek-web-workspace.mjs 52986` 从官方 Choose workspace / Edit path / Open 控件选择 `/workspace` 通过，不直接改服务状态。页面显示 GLM-5.1。`send-deepseek-web-test-message.mjs 52986 DSH_WEB_ONE` 精确检查空草稿、写入完整文本、语义点击 Send message，通过且没有重复发送。
- Web 实际请求：观察器 id=15、16 均为 GLM-5.1 并返回 200；Web 会话标题自动变为 `Reply DSH_WEB_ONE — DeepSeek Harness`。不将两个模型请求直接等同于重复用户发送。新增 `verify-deepseek-web-reply.mjs` 区分真实 userRow 与助手 Markdown 段落，得到 userCount=1、assistantCount=1、sendRestored=true；`--reload` 后同样通过。渲染 gate 在新标题下通过（24 控件、无捕获异常）。当前 DeepSeek Web 的已认证基础文本与刷新恢复通过，首次打开认证体验仍不能关闭。
