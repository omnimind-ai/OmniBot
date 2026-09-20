# OmniInfer 手机本地模型 Skill 验证

需求：只提供按需使用的 Skill，不在普通聊天持续访问；目标模型在 Android 手机运行。

## 模拟设备完整安装验收（进行中）

- 原 `emulator-5580` 只有 2 GB RAM / 6 GB data（2.8 GB 可用），已正常关闭，数据未删除。创建独立 `OobOmniInfer20260917` / `emulator-5582`：ARM64、6 GB RAM、9 GB data。安装后实测 7.7 GB 可用；Ubuntu 引导完成后 6.8 GB 可用。
- 最新小字入口版本 APK 已安装，SHA 和资源见 `fixtures/omniinfer-emulator-20260917/setup.json`。启动阶段使用现有测试环境 Provider，经已有 debug-only 配置入口写入独立测试 Profile，不复制凭据到证据。
- 完整正常引导选择 Ubuntu/Python，系统、Python、pip、uv、Git 安装与校验完成。通过设置 → 模型提供商进入页面，实际看到并点击 `Use local model service`，截图见 `local-service-entry.png`。
- **入口导航和任务启动已在模拟器验证**：点击创建新正式 ACP 对话，实际 user message 为安装请求；`skills_read(omniinfer-local)`、设备/终端/空间检查成功，创建 `session_31547148`，然后在模拟器内执行 `phone.py build`。ACP session `5d505e39-85a0-47e0-9d32-56f709db5641`，turn `cf3f2cac-b504-4842-9410-c2eb33fac957`。脱敏工具摘要见 `entry-agent-progress.json`。
- 最后复核：Python PID 11459、apt PID 11584、dpkg PID 11658 实际存活，正在解包构建依赖。持久终端仍由 App Agent 所有，未因 UI 快照/观察超时重启安装。历史里的 `agent.terminal` 投影显示 interrupted，但其 `isFinal=false`、进程持续输出，不能把该临时投影当作任务已结束。
- **尚未完成**：首次框架构建/安装、模型下载/加载、Provider 接入、正式本地 Agent 简单任务、已安装复用路径。禁止以本节的入口成功替代完整目标。真机回归亦待后续执行。

### 同一安装任务继续核验

构建 PID 11459（proc start ticks 52731）仍存活，依赖安装完成，Android SDK 下载校验完成并在解包；实际目录从 391748 KiB 增至 425744 KiB。没有因为终端 UI interrupted 投影或观察超时重发安装请求。见 `fixtures/omniinfer-emulator-20260917/build-observation.json`。

为覆盖已有安装复用，Skill 新增第 0 节：先一次 probe，服务就绪直接交接；否则核实宿主安装状态，已安装只启动/修复，不把端口不通当未安装。入口提示词同步为这个顺序。新文字和 Skill 尚未更新正在构建的模拟器，避免主 App 更新使 PRoot loader 失效；这一复用路径仍待实际操作验收。Skill validator PASS，12 项脚本回归 PASS，入口导航 Widget 回归 1/1 PASS。

`verify-omniinfer-phone.py` 增加显式 `--allow-emulator`，默认仍拒绝模拟器；报告新增 `emulator-native-inference` / `physical-native-inference` scope。真实模拟器推理尚未执行，不因脚本兼容通过而声明目标完成。

### 原生编译与正式任务验收入口

SDK、NDK、Gradle、OmniInfer 和 llama.cpp 源码已在模拟器内下载/解包完成；原 Python PID 11459 未更换，Gradle daemon PID 12895，Ninja PID 13524。已观察两个 Clang 子进程和逐步增长的 `.ninja_log`，并完成 `libllama-common.so` 链接。**APK 尚未完成，安装与本地 Agent 任务尚未验收。**

新增 `scripts/verify-omniinfer-agent-task.py`，复用既有 canonical turn outcome oracle，并检查同一 turn 的成功执行工具、持久化 ACP 模型/Provider identity、现有 debug query 返回的 loopback Provider 地址、实际 marker 文件精确内容 `338350\n` 和原生宿主进程。已接入 `omniinfer-local-agent.en.json`，在任务完成与 App 重启后各执行一次。脚本语法检查通过；真实设备上使用不存在的 marker 进行负向检查，明确失败 `User admission missing or duplicated`，证据为 `oracle-negative-control.json`。这不是正向任务验收。

安装完成后的 API 检查使用：

```sh
python3 scripts/verify-omniinfer-phone.py emulator-5582 /tmp/omniinfer-emulator-api.json --allow-emulator --restart --tools
```

在正式新对话明确选中本地 Provider 与模型后：

```sh
node scripts/verify-agent-user-journey.mjs emulator-5582 scripts/fixtures/agent-user-journeys/omniinfer-local-agent.en.json /tmp/omniinfer-emulator-agent
```

以上两个正向入口仍待实际执行。API 验证与对话任务验证分别保留，不能以其中一个代替另一个。

## 入口外观反馈修订

用户要求入口更小且文案仅为“使用本地模型服务”。模型提供商页已改为右对齐 12sp 次级色文字按钮，默认高 32dp，移除下载图标、箭头和安装长说明；仍由已有 ACP 新对话触发按需 Skill。原入口回归增加文案、TextButton、高度与无长说明断言，并保留返回后再次打开新对话验证，1/1 PASS。此轮仅完成源码及 Widget 验证，最新外观待真机验证，上一节 APK 构建回执对应修订前版本。

## 当前结果（2026-09-17 17:48）

- **真机安装与 API 推理 PASS**：PJE110 `b49f281b`，宿主 `cn.com.omnimind.localinfer` 0.1.0/code 1。先安装手机编译的 2K 版本，再安装同源 16K 上下文版本，保留宿主模型数据。系统安装成功，不代表自然语言 Agent 安装链路通过。
- 手机实际下载并校验 Qwen3-0.6B-Q8_0，SHA-256 `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`；使用上游 OmniInfer/llama.cpp CPU 原生后端、4 threads、16384 context。没有 GPU/NPU 或对照速度提升结论。
- 16K APK SHA-256 `21be3ee47dbd68bd02b8c6fdbb7a17ca91ab54094f2a3d623d72d1ed3223fda4`。由手机增量构建，桌面只转运安装产物。
- `verify-omniinfer-phone.py --restart --tools` PASS：真实非流式回复、SSE/取消、取消后回复、读取独立测试文件的工具往返、force-stop 后端口关闭、重新启动且不重复下载模型。证据：[16K 真机结果](fixtures/omniinfer-phone-20260917/inference-16k-ready.json)。此前启动后立即探测失败保留于 `inference-16k.json`；上游报告模型就绪后才运行通过，不将失败记录改写为成功。
- 工具调用 arguments 在此上游是 JSON object；App 现有 accumulator 已支持对象和字符串。验收脚本修正为接受这两种真实格式。2K API 回归证据为 `inference-acceptance-v2.json`。
- **正式 App 对话中的本地模型 Agent 任务：未通过验收（尚未完成）**。HTTP 工具往返的 scope 明确为 `http-api-not-app-agent`，不能替代该要求。创建了独立 OmniInfer-Local Provider，但配置/模型选择尚未完成。
- 新增模型提供商页“安装 OmniInfer 本地模型”入口，创建独立本地 Xiaowan ACP 对话并发送按需 Skill 安装请求；系统安装确认仍需用户操作。复用 ConversationThreadTarget/既有 initialMessage owner，没有新增安装 Agent loop、常驻工具或轮询。
- 设置中移除“执行中心”；插件市场原有插件详情入口保留。侧栏“轨迹”指向独立用量统计页，保留。
- Provider/插件市场 37 项 Flutter 回归、设置 2 项回归 PASS，包括安装按钮实际导航、独立 requestKey、返回后再次启动、插件详情导航及设置无执行中心。
- 主 App APK 构建 PASS（2m21s，589 tasks），使用 Android Studio JBR 21 和 `-Pkotlin.incremental=false`；初次增量构建的 androidgui 引用错误未通过改源码处理。产物哈希见 `fixtures/omniinfer-phone-20260917/entry-build.json`。Flutter 静态检查没有错误，仅一项既有 underscore 提示。
- 安装按钮、设置入口变更 **待真机验证**；自然语言从安装到推理的完整 journey、浏览器交接安装路径、正式本地 Agent 对话、断网测试仍未完成。前台再次被另一任务切至通知权限页，已请求协调；未继续使用旧坐标操作。

### 正式对话待执行回归

在空闲真机的新对话选择真实 `OmniInfer-Local` / `Qwen3-0.6B-Q8_0.gguf` 后，执行：

```sh
OOB_ALLOW_PHYSICAL_DEVICE=1 node scripts/verify-agent-user-journey.mjs b49f281b scripts/fixtures/agent-user-journeys/omniinfer-local-agent.en.json /tmp/omniinfer-local-agent
```

该场景要求工具计算 1..100 的平方和、写独立 marker 文件、再读回，并检查正式 ACP 完成及重启后的历史。预期文件内容为 `338350\n`。当前 **未运行**；仅通过回复 marker/历史检查仍不足以证明本地推理，验收还须检查实际 Provider/模型路由、真实工具事件及设备文件内容，不能用 HTTP fixture 或云端模型替代。

以下章节是历史阶段记录，后来的成功不删除早期失败证据。

## 第一阶段：仅 Skill 检查（历史结果）

以下为原生宿主加入前的检查结果。

### 交付与边界

- 内置技能：`app/src/main/assets/builtin_skills/omniinfer-local/`，加入已有 manifest；正文通过现有 skills_read 按需读取，无新增常驻提示词、轮询、Tool 或 Provider。
- 已复制到 PJE110 的 `workspace/.omnibot/skills/omniinfer-local/`，两文件逐字节回读一致。未重装 APK；内置 manifest 将随下次构建打包。本次未验证聊天中的技能发现/选择与执行。
- 上游核对：omnimind-ai/OmniInfer @ `6ad984d414d4b7c27ab5740e63e8ccb1b33e4046`，Apache-2.0，Android AAR/模块部署方式。Skill 不含原生运行时或模型，不能动态安装 AAR。

### 实际执行

- `uv run --with pyyaml python ~/.codex/skills/.system/skill-creator/scripts/quick_validate.py app/src/main/assets/builtin_skills/omniinfer-local`：PASS。
- `python3 scripts/test-omniinfer-skill.py`：5/5 PASS。使用本机 HTTP fixtures，覆盖模型未加载不发 prompt、空回复失败、重定向不跟随、不自动重试、服务缺失以及连续两次独立调用。不是模型推理或真机验收。
- 真机 PJE110，序列号 `b49f281b`，App `cn.com.omnimind.bot`，0.6.3 / versionCode 16。
- 手机 shell curl 直连 `127.0.0.1:9099/health`：exit 7，connection refused。
- 通过该真机独占 adb forward 运行 probe.py：exit 1，`ok=false, stage=health, error=RemoteDisconnected`；结束后已移除该转发。
- `dumpsys package` 未发现 OmniInfer 服务注册；当前 Gradle/插件目录没有 OmniInfer 接入。

### 当时未通过 / 未运行

本地推理未通过：当前没有可用的手机 OmniInfer 服务。模型下载/导入、加载、聊天、工具调用、取消、会话切换、重启和离线推理均未运行，待原生集成及真机验证。不能把 Skill 文件安装成功或 fixture 测试通过解释成本地模型可用。

后续需先把上游 Android 模块/AAR 编入 APK并提供真实的模型管理入口，再按本 Skill 重跑；不要反复运行探测来代替补齐该依赖。

## 第二阶段：手机源码构建（进行中）

用户补充要求：手机 Agent 一句话完成源码拉取、构建、安装与启动，留下本机模型接口。

- 已加入 `phone.py build/serve` 和薄原生宿主模板；Android 模块和 llama.cpp 均来自固定版本上游源代码。宿主包 `cn.com.omnimind.localinfer`，没有第二套 Agent 生命周期。
- 真机 PJE110 / b49f281b，OpenOmniBot 0.6.3 / 16 的 Ubuntu ARM64 环境；手机自行下载源码、ARM64 SDK/NDK、Gradle，手机 JDK 17 / Clang 21 / aapt2 / CMake 均已实际运行。
- 手动原型完整手机构建 PASS：Gradle 8.9，57 tasks，5m10s，包含原生 JNI 和 llama.cpp。首次 CMake 进程启动失败未稳定复现；独立配置与第二次完整构建成功，未将该错误标记为彻底修复。原型保留在手机 `~/.local/share/omniinfer-phone-prototype-20260917`。
- 正式 Skill 脚本使用全新工作目录重跑中；不能把原型构建结果当正式脚本通过。
- `python3 scripts/test-omniinfer-skill.py`：10/10 PASS，覆盖实际 HTTP fixtures、下载缓存校验、拒绝损坏缓存、路径穿越、重复解包和临时 APK 服务范围。属于脚本回归，不是模型推理。
- 未安装 Shizuku；Skill 增加现有工作区文件预览 → 系统安装器路径，不要求新增常驻 Tool。该 GUI 路径尚待真机执行验证。

### 可执行验收入口

```sh
python3 scripts/test-omniinfer-skill.py
OOB_ALLOW_PHYSICAL_DEVICE=1 node scripts/verify-agent-user-journey.mjs b49f281b scripts/fixtures/agent-user-journeys/omniinfer-phone-install.en.json /tmp/omniinfer-phone-journey
python3 scripts/verify-omniinfer-phone.py b49f281b /tmp/omniinfer-phone-acceptance.json --restart
```

自然语言 journey 必须在空闲真机的新对话执行；助手回复标记不能替代后面的真实 HTTP/模型哈希/流式取消/重启检查。两个真机入口目前未运行。离线测试、App Provider 对话、无副作用工具调用尚未运行。

### 构建中发现的环境边界

- 全新目录的首次脚本运行完成 SDK、NDK、Gradle、两个源码包的校验与解包，在执行 Java 时发生 ENOENT。新终端能正常执行相同 Java。
- 发现共享真机的主 App 于 17:02:27 被其他操作重装，旧 APK 中的 `libproot-loader.so` 路径已删除；旧终端继续引用该路径。此证据支持终端失效诊断，不能据此断言所有原生崩溃都由同一原因造成。
- 第二次脚本构建在链接 libllama.so 时报告 linker segmentation fault；改为 LLD 单线程，正在第三次构建验证，尚未宣称崩溃已修复。
- 根据上游 `nativeLibraryDir` 后端发现接口，宿主明确启用 `jniLibs.useLegacyPackaging=true`；真机验收检查提取后的 CPU 动态库。
- 新增加载器路径失效检查，阻止失效终端再次启动子进程；回归 12/12 PASS。真机使用实际已删除的旧路径验证拒绝，再恢复当前加载器验证 Java 启动 PASS。此检查不等同于重新执行完整的“构建中重装 App”生命周期验收。

## 当前结果（17:27 更新）

- **正式 Skill 手机构建 PASS**：第三次构建 11m1s，57 tasks；CMake 重新编译全部 327 个原生目标，单线程链接成功，APK 打包成功。两条并行编译进程已实测，CPU 库启用解包式打包。
- APK SHA-256：`3ee65fbda15c3b1fcb643762721d5b5ffe733c676bbd06c4afaffdabe25d08f6`。源码、SDK、NDK 和 Gradle 均由手机获取并校验；电脑没有编译该 APK。
- 手机文件：`/workspace/OmniInfer-local.apk`；构建接口回执与三次日志保存在 [fixtures/omniinfer-phone-20260917](fixtures/omniinfer-phone-20260917)。
- 新版 Skill 复制到手机后，保留其他注册项，仅将该 Skill 的 workspace registry source 设为 `user`，遵循既有用户技能格式，防止当前旧 APK 每次扫描覆盖新版文件。此为文件安装，不是聊天中的自然语言任务验收。
- **安装未通过**：ADB shell 从手机的临时 loopback artifact 服务取 APK，SHA 校验一致。ColorOS 显示“本机模型 0.1.0 / 16.3 MB / 来自电脑端未知来源”，其后返回 `OPLUS_ADB_INSTALL_CANCEL`，安装会话 abandoned。曾尝试点击页面上的“继续安装”，但不能据此认定安装成功或判断取消者。共享前台存在其他操作切换，确切取消原因未确定。未绕过系统安装限制。
- 临时 artifact 服务已停止；没有后台定时探测。APK 副本留在 workspace 和本次 `/data/local/tmp/omniinfer-phone-20260917.apk`，后者供安装恢复使用。
- **实际验收 FAIL**：`verify-omniinfer-phone.py b49f281b .../acceptance.json --restart --tools` 在安装前置条件明确失败：`Native inference host is not installed`。没有模拟推理结果。模型下载、加载、回复、工具往返、停止/重开及离线均未执行。
- 自然语言 phone-Agent journey 尚未运行，不能宣称“一句话安装并跑通”已完成；需前台空闲后继续实际安装和端到端验收。

## 后续复核：系统安装入口

- 上一轮属于实质进展：完成手机源码构建、脚本修订与构建证据归档，安装仍未通过。本轮再次确认 `pm path cn.com.omnimind.localinfer` 为空。
- 经已有 GUI 从工作区打开 `OmniInfer-local.apk`，文件预览显示 `application/octet-stream` 和“系统打开”。下一次准备点击时页面已被切回 workspace，按当前元素匹配的自动化拒绝点击，没有使用旧坐标强行继续。前台协调阻碍仍存在。
- 真机 `cmd package query-activities` 证明：相同 content URI 使用 octet-stream 时没有系统安装器，使用 `application/vnd.android.package-archive` 时包含 `com.android.packageinstaller/.InstallStart`。保留 `apk-mime-resolution.json`。
- 因此移除 Skill 中“workspace 系统打开必然可安装”的假设，改用临时 loopback APK 下载入口，明确 MIME 与附件文件名；没有修改主 App 文件打开逻辑。服务器响应增加 Content-Disposition，现有可执行 HTTP 回归覆盖 MIME 和附件名，12/12 PASS。
- 新版 Skill 已复制到手机，未开启常驻服务。浏览器下载 → 系统安装器路径尚未实际执行，不能称为安装问题已验收。自然语言 Agent journey、模型推理和全部后续功能仍待前台空闲后验证。

## 第三轮阻塞复核

上一轮为实质进展：发现并记录真机 APK MIME 解析差异，修订临时 APK 交接入口并通过回归。本轮只读复核仍显示宿主未安装；前台停在主 App 工作区，未获得此前请求的前台协调回复。静止快照不能证明其他操作者已释放设备，此前两轮曾发生安装取消和预览页面被切走。

同一前台协调/系统安装阻碍已连续三轮存在；没有正在等待的构建或安装进程。目标标记为 blocked，等待手机前台可独占测试后恢复。已完成源码构建不代表完整目标完成；恢复顺序仍为手机 Agent 自然语言入口、APK 下载/系统安装、真实模型加载/API、停止/重启和所需接入验收。

## 模拟设备安装验收续接（2026-09-17 19:11）

- 当前使用 `emulator-5582`（ARM64 API 33），主 App 0.6.3/code 16。从 Provider 页的 `Use local model service` 小文字入口发起真实 Agent 安装任务。入口截图、设备配置与构建回执在 `fixtures/omniinfer-emulator-20260917/`。
- 手机环境中的源码构建已实际完成，APK SHA-256 `6d4bea468f06c4b47dd69623b6e94fe4e4617f8ae9a6de06ecdb1430c0eef47b`。该回执仍明确 `installed=false`、`inference_verified=false`，不能当完整安装验收。
- 发现实际阻塞命令：Agent 已后台启动 `phone.py serve`，但随后 `cat /proc/$!/fd/1` 持续读取终端而无法结束。修订 Skill：服务输出重定向普通日志；后续有限读取该文件；下载后结束指定 PID 与会话，不读进程 stdout fd，不用 `tail -f`。
- 正常点击 Stop 取消原 ACP turn 后，系统仍可见旧 serve 与 cat 子进程。续接任务明确要求停止旧 artifact session，再复用已构建 APK，未重装主 App、未重新构建、未伪造安装成功。
- 可执行复现输入：`scripts/fixtures/user-scenarios/omniinfer-install-continue.json`。新增回归执行 Skill 中真实 shell 段，验证命令返回、有效服务回执与清理后端口关闭。`python3 scripts/test-omniinfer-skill.py` **13/13 PASS**，仅脚本级验证；该流程修复仍待真机验证。
- 当前完整目标仍未完成：系统安装、模拟设备模型加载、本机 API、正式 App 对话使用该 Provider 执行任务均待后续证据。现有真机 API 回执不能替代正式 App Agent 对话验收。

### 续接中的实际下载结果

旧会话通过 `terminal_session_stop` 结束，新会话 `session_355cafc2` 的后台服务启动命令已返回成功。导航到实际 APK URL 的 `browser_use` 报 30 秒加载超时，但浏览器下载文件 `files/browser_downloads/conversation_1/OmniInfer-local.apk` 实际已完整落盘，设备侧 SHA-256 与构建回执一致。Agent 随后通过终端又下载到公共 Download 并验证哈希，已停止临时服务、准备系统安装。Skill 补充“导航超时先检查下载记录”，避免将下载响应当普通网页失败。以上仍不代表安装或模型推理已通过。

### 系统权限与临时服务清理边界

Agent 的首次 GUI 安装调用因权限不足返回前置要求。测试操作者通过 Android 系统设置开启 Overlay 与 Accessibility，然后点击 Continue task。只读确认 `SYSTEM_ALERT_WINDOW: allow`，无障碍服务为 `cn.com.omnimind.bot/cn.com.omnimind.accessibility.service.AssistsService`。没有执行 ADB install 或修改权限数据库。

该权限要求已结束原 Agent turn，需显式发新用户续接输入，保留为 `omniinfer-install-after-permissions.json`。另外真实进程证据表明 `kill 20009; echo "serve stopped"` 只终止 Python 包装器，PID 20012 的服务仍存活；Skill 明确要求 `terminal_session_stop` 及端口关闭证据，续接任务也要求清理这个会话。不能把 shell echo 当停止成功。

续接后真实 `terminal_session_stop(session_355cafc2)` 返回成功；ADB 进程列表已无 `phone.py serve`。这验证了通过现有会话生命周期清理子进程的模拟设备路径，无需新增后台管理机制。

### GUI 引导模型前置条件修正

实际安装 GUI run `gui-a70dc581-1d29-4db6-9195-7bb88c9c35f3` 与 `gui-a27fb1d1-95d8-4302-b102-c9bbaa69d81d` 在执行任何 UI action 前失败（step_count=0）。错误明确为 GLM-5.1 仅接受 text，截图输入被 HTTP 400 拒绝；不能仅解释为网络超时。正常 Stop 结束重复尝试。

同一测试 Provider 的模型列表包含 GLM-4.6V；对 64×64 纯红 PNG 的真实图片请求返回 HTTP 200 / `red`。随后通过既有 debug receiver 的 `bind_existing` 仅修改 `scene.vlm.operation.primary` 为 GLM-4.6V，dispatch 与 compactor 仍 GLM-5.1。该云端引导配置仅用于安装工具操作，不能计作最终本地 Agent 验收。后续任务输入保存在 `omniinfer-install-vision-ready.json`。

复现时须为 GUI 场景绑定实际支持图像的模型，而不是把纯文本 bootstrap 模型绑定到所有场景：

```sh
adb -s emulator-5582 shell am broadcast \
  -n cn.com.omnimind.bot/.debug.DebugModelProviderConfigReceiver \
  -a cn.com.omnimind.bot.debug.CONFIGURE_MODEL_PROVIDER \
  --es operation bind_existing --es profileId oob-emulator-regression \
  --es modelId GLM-4.6V --es sceneIds scene.vlm.operation.primary
```

该指令使用设备上既有 Provider，不传递或输出凭据。尚待系统安装成功与实际本地推理证据。

视觉模型续接输入 helper 报“Send tapped but composer did not clear”，但数据库核实该 marker 用户消息恰好入库一次，且 Agent 已开始执行；未重发。当前 GUI 能力已实际打开系统 Downloads，先前快照中的权限对话框不能当作权限再次失效；系统 accessibility 的 enabled/bound 均正常、crashed 为空。

### GUI 完成误报（未通过安装验收）

后续 GUI run 返回 success=true，文本声称系统已安装，主 Agent 随即提前说“安装成功”。但同一模拟设备 `pm path cn.com.omnimind.localinfer` 仍为空，context_apps_query 也查不到，屏幕仍是 Downloads。已保留 `gui-install-false-success.json`。因此该阶段判定未通过，不能依据 VLM 自述或 tool success 验收。必须继续以精确包名存在、原生宿主运行、真实模型 API 和正式 Agent 任务为最终证据。

## 系统安装实际完成（19:30）

正常 Stop 取消误报中的 Agent turn 后，测试操作者接管模拟设备 UI：打开系统 Downloads → 点击 OmniInfer-local.apk → Package installer → Just once → Continue → Install。安装器实际显示 App installed，`pm path cn.com.omnimind.localinfer` 返回 base.apk；APK 哈希仍匹配手机源码构建回执。没有使用 adb install。

证据为 `system-installed.png` 和 `system-install-result.json`。明确包含人工界面接管，不能宣称全自动 Agent 安装通过。随后从安装器 Open 打开宿主、点击“启动本机模型”；宿主已开始实际下载 Qwen3 0.6B Q8_0，私有目录 `.part` 已增长。模型加载、API 与正式 App 本地 Agent 任务仍待验证。

## 模拟设备本机 API 通过（19:32）

`verify-omniinfer-phone.py emulator-5582 .../inference.json --allow-emulator --tools` **PASS**：固定模型完整下载并验证 SHA-256、真实非流式回复、流式取消、取消后再次回复、真实工具请求/结果回传均通过。证据 scope 为 `emulator-native-inference`，工具回合明确 `http-api-not-app-agent`。不是最终正式 App Agent 验收；此轮未执行 restart 参数。

随后通过 App 正常 UI 新建 `OmniInfer-Local` Provider，填写 `http://127.0.0.1:9099/v1`，使用 Chat Completions、不填 API Key；点击获取模型，实际返回 `Qwen3-0.6B-Q8_0.gguf`。Scene Model Config 将 Agent 绑定到该 Provider/模型，新建对话再次显式选择本地模型并设 Reasoning effort=Off，发起 `omniinfer-local-agent.json` 文件任务；尚待正式 turn 结果与独立 oracle。

正式文件任务已唯一提交到 conversationId=2；ACP session `995b2ac6-f07b-4921-a84a-654bc21c7a97` 的持久配置为 model=`Qwen3-0.6B-Q8_0.gguf`、providerProfileId=`profile-9`、reasoning_effort=`none`。原生 JNI 日志确认开始采样，宿主 PID 27605 正在高 CPU 运行（约 267%），暂未返回工具结果。仅作为真实请求正在运行的证据，不标记任务成功、不重发同一逻辑 turn。

### 正式本地 Agent 请求性能定位（同一请求，未重发）

上一轮为实质进展：系统 UI 安装、模型下载、本机 API 验收及本地 Provider 配置均已落实。本轮确认 PID 27605 持续高 CPU，任务文件仍不存在。通过 Android 自带 simpleperf 对当前 debuggable 宿主做 5 秒真实采样，约 81.48% 的子调用耗时归于 `ggml_compute_forward_flash_attn_ext`，另约 13.15% 为矩阵乘法；见 `formal-agent-cpu-profile.txt`。说明当前执行原生计算，不能据等待超时擅自重启或重发。采样所需临时 perf_harden 设置已在报告导出后恢复为 1，原始采样文件已删除，仅保留符号耗时报告。debuggerd 的无 root 栈读取未获支持，没有使用 root 提权安装或更改模型。

## 恢复上游 CPU 优化构建（进行中）

进一步核对实际 CMakeCache：`GGML_NATIVE=OFF`、`GGML_CPU_ALL_VARIANTS=OFF`、无显式 ARM_ARCH。上游 pinned `android/omniinfer-server/build.gradle.kts` 原本启用 CPU_ALL_VARIANTS=ON，宿主模板却追加 OFF 覆盖它。模拟设备 CPU feature 包含 fp16/dotprod。这个构建偏差已证实；它对完整 Agent 首 token 延迟的改善幅度仍需重建后实测，不能提前宣称性能问题已修复。

- 移除模板中的 OFF，复用上游 Android 多版本 CPU 后端及自动选择，不修改 JNI 推理循环。
- APK 验证新增基础 `android_armv8.0_1` 与 FP16/dotprod `android_armv8.2_2` 库检查；旧单基础库 APK 会被拒绝。
- 重建复用有固定哈希解包回执及预期文件的工具链/源码；5 GiB 冷启动门槛仅用于不完整缓存，完整缓存重建最低 1 GiB。脚本回归 **16/16 PASS**，覆盖缺失工具、错误回执、压缩缓存缺失时复用，以及旧 APK 被拒绝。
- 正式本地 turn 正常点击 Stop 后取消，任务文件尚未生成。因准备更新宿主、且上游 prefill 循环不检查取消标志，停止独立 localinfer 进程释放 CPU；没有重发原 turn，也没有停止或重装主 App。
- 当前 9 GiB 测试分区空间不足，清理了本次 Ubuntu apt 下载缓存与旧的可再生 native build intermediates；保留已下载模型、源码、工具链、旧 APK 与证据。重建前实际可用约 1.7 GiB。
- 修订后的脚本/模板部署到 `/workspace/omniinfer-acceleration-rebuild`，通过现有 Ubuntu PRoot 在模拟设备内重新编译。日志 `/workspace/omniinfer-acceleration-rebuild.log`，当前主机 exec session `97617`。未在电脑编译 APK。
- `verify-omniinfer-phone.py` 改为读取真实宿主进程 maps、记录实际加载 CPU 库及哈希；新增 `--require-optimized-cpu`，用于重建后拒绝仅加载 ARMv8.0 基础库。此新正向检查尚未运行。

完整目标仍未完成：优化版构建、系统更新安装、优化库实际加载、本机 API 重测、正式本地 Agent 工具任务与恢复验证均待证据；真机修复验收仍待执行。

### 主 App 产物与优化构建进展

主 App 最新源码 `assembleDevelopStandardDebug` 已成功（43s，589 tasks）。逐项比较 APK zip 内的 SKILL.md、phone.py 和宿主 Gradle 模板与工作树完全一致，SHA-256 为 `0ba8f16de6276aa9c2930a2fdd0f8273afe4625fb2b0fdb9fb3c9245a9d8fce1`，见 `updated-main-build.json`。尚未安装，以免删除正在运行的 PRoot loader。

同一手机构建 PID 32464 / start ticks 621372 保持运行；CMakeCache 已确认 CPU_ALL_VARIANTS=ON。native log 从 37 增至 156 行，已实际生成 `libggml-cpu-android_armv8.2_2.so` 并继续编译 llama 公共运行库。分区剩余约 1.6 GiB。此为构建进展，不是 APK/推理/正式 Agent 验收通过。


## 默认预编译安装（用户确认后的实现）

默认入口现要求使用随 App 附带的预编译 APK，已安装先复用；源码构建仅用户明确要求时执行。没有发布或假设上游 APK URL。当前随附产物是本次手机源码构建的 debug 宿主，约 19 MB，版本 0.1.0 (1)，正式发布签名/分发仍待完成。准备失败不自动回退长时间构建，不卸载已有宿主来绕过签名错误。

执行入口：`python3 <scriptsDir>/phone.py prepare` → 已有短期 APK server → 系统安装器 → 模型加载 → 既有 Provider。没有新 Agent loop、常驻访问或本地回答 Tool。回归：`python3 scripts/test-omniinfer-skill.py` 18/18；Provider 设置页 26/26；Skill validator PASS。新用例实际准备随附 APK，验证重复准备、哈希失败拒绝、不得启动构建或工具链下载，以及入口提示使用预编译包。

主 App 构建成功，模拟器和 PJE110 b49f281b 均 `install -r` 成功；打包内 Skill、脚本、APK 与源文件逐字节一致，见 `fixtures/omniinfer-emulator-20260917/prebuilt-main-build.json`。清理模拟器本次生成的 `.cxx` 和模块 build 中间文件释放约 849 MB，保留模型、源码、工具链、最终 APK。

模拟器通过 Downloads → Package installer → Update → App installed → Open 完成预编译宿主更新。启动后原模型复用。首次检查误在模型加载完成前运行，明确失败记录为 `inference-before-model-ready.json`；等宿主显示就绪后重新执行验收，`inference-optimized.json` PASS：实际加载 `libggml-cpu-android_armv8.2_2.so`；真实非流式回复 0.25 s，取消、取消后回复、只读工具往返、重启复用均通过。此结果不是正式 App Agent 任务通过，也不是加速倍数对照实验。

真机已实际进入模型提供商页，点击「使用本地模型服务」后在正式新对话中读取更新 Skill，并明确识别默认不构建流程。完整复用任务结果及正式本地 Agent 任务仍在验收，不能据此宣称全部完成。

真机验收边界补充：确认真机安装 APK SHA 与本次主包一致。但真机既有 omniinfer-local 注册为 `source=user`，按照现有 Skill 所有权规则保留了旧自定义脚本，执行 prepare 明确返回不支持该动作，未启动构建。不能据此声称真机完成新版 Skill 验收；需通过既有 Skill 安装入口有意切换到新版内置 Skill，不能后台覆盖用户 Skill。期间真机被另一测试切换并发送其他任务，停止操作真机避免互相干扰。模拟器内置 Skill prepare 实际成功，输出 apk_prepared 且固定 APK 哈希一致。

正式模拟器 Agent 任务 `OOB_LIVE_OMNIINFER_AGENT_1789648164035` 已发出一次，仍等待首个输出；API 验收不替代该任务。没有重发、回退云端或模拟工具结果。


## 远程分发（替代内置 19 MB APK）

用户确认后发布独立测试 Release：https://github.com/omnimind-ai/OmniBot/releases/tag/omniinfer-local-v0.1.0-test.1 。非 latest、prerelease，确认主 App latest 仍为 v0.6.3。上传 APK、SHA256SUMS、宿主构建源码压缩包和上游许可证；不上传签名私钥。APK SHA 与已验证手机构建产物一致。主 App assets 中删除 APK；源码构建仍为明确请求时的选项。

`phone.py prepare` 复用已有 fetch：下载固定资产、有限重试和续传、SHA-256 校验成功后原子替换、正确缓存复用。新增/更新回归通过实际本地 HTTP 下载 fixture 验证安装回执、缓存不联网、损坏缓存拒绝、下载失败不构建或生成成功回执。脚本 18/18 PASS；Provider 页 26/26 PASS。

设备远程验证：模拟器 github.com 直链下载约 21 秒并生成正确 apk_prepared；PJE110 直链连接超时，因此切换同一资产的固定 GitHub API 地址（asset 570236640，octet-stream，无凭据），真机已开始实际下载。完整结果另附。该验证操作仅在隔离的 `/workspace/omniinfer-remote-acceptance` 中运行脚本，不覆盖真机 source=user 的旧 Skill，不把脚本准备当系统安装验收。

此前正式 App 本地模型任务在规定等待时间内没有完成，journey 明确失败；没有重发或云端回退。远程分发改动不能视为修复该推理延迟。

远程验收最终结果：PJE110 b49f281b 使用 API 固定资产地址实际下载 19,457,802 字节，约 328 秒，SHA-256 一致，生成 apk_prepared；再次运行 cached=true，无新下载。模拟器下载及再次缓存复用同样 PASS。记录 `fixtures/omniinfer-emulator-20260917/remote-distribution.json`。真机当前网络下载慢，不承诺固定安装耗时。

最终主 APK 构建成功，逐字节确认 Skill 与脚本一致、ZIP 无内嵌 APK；已在 emulator-5582 install -r 成功，证据 remote-main-build.json。真机未覆盖用户自定义 Skill/未再次更新主 App；新版入口完整安装路径仍待真机验收。正式 Agent 用例等待超时后尝试正常 UI Stop 两次均因 uiautomator 无响应失败，结束专用推理宿主以释放测试资源，不重发该 turn；此任务记录为失败，不视作验收通过。


## 模型列表拉取失败：真机前后台边界

PJE110 / b49f281b：Provider 页面实际配置 `http://127.0.0.1:9099/v1`、Chat Completions；界面显示等待回复超时。直连 /health 与 /v1/models 都超时。打开已有宿主 MainActivity 后 /health 200，/v1/models 200 且返回 Qwen3-0.6B-Q8_0.gguf（约 0.05 秒）。返回主 App 点击重试，随后再次超时。未更改 Provider 地址、密钥或模型 ID。

新增可执行回归 `python3 scripts/verify-omniinfer-model-list-lifecycle.py b49f281b docs/testing/fixtures/omniinfer-phone-20260917/models-lifecycle.json`。立即切回主 App 的 2 秒测试通过（models-lifecycle-immediate.json）；默认等 30 秒的背景阶段明确超时，前台阶段始终 200（models-lifecycle.json）。确认有前后台可用性问题，API 路径与响应格式正确。dumpsys 显示上游 OmniInferService 已是 foreground service，isFrozen=false，不能简单断言缺少前台服务或已被 Android cached freezer 冻结。具体后台网络/系统限制原因待进一步定位，尚未修复。


## 模型不显示：厂商后台冻结已定位并解除

根因证据：PJE110 Android 16 日志中 OplusHansManager 在宿主退后台约 6 秒后记录 `freeze uid: 10395 cn.com.omnimind.localinfer pids: [19894] scene: LcdOn`，OAppNetControlService 随后记录该 UID 被列入限制。此前 ActivityManager 的 isFrozen=false 不能排除厂商冻结。

通过系统应用详情→耗电管理→完全允许后台行为。系统确认弹窗已展示，下一次自动点击“允许”找不到按钮（期间界面由外部操作离开），因此不冒称该确认由自动化执行；之后 deviceidle 白名单出现宿主，真实后台回归由 FAIL 变为 PASS。`models-lifecycle-background-allowed.json`：前台与切回主 App 等 30 秒均返回 200 和真实模型。回到模型提供商页，实际显示“已获取 1 个模型”、共 1 个模型和 Qwen3-0.6B-Q8_0.gguf，截图 models-background-fixed.png，版本和哈希见 models-background-fix.json。没有改协议、端口或提供虚假模型列表。

持久修复安装流程：Skill 添加仅针对宿主的系统后台设置说明、厂商冻结诊断、必须切回主 App 至少 30 秒后再次 probe 并验证模型列表，保留停止服务及任务结束停止探测。不加保活轮询、新协议或 Agent loop。18 个脚本回归和 Skill validator 通过。当前有用户对话执行，未重启宿主打断它；重启边界验收待该任务结束。


## 用户反馈：本地对话无输入输出（修正此前短时恢复结论）

当前真机 UI 对话使用 Qwen3-0.6B-Q8_0.gguf、思考默认，显示处理中且无输出。未重发或重启该对话。主 App 已由外部更新成非 debuggable 0.6.3（20:54:02），不能使用 debug DB backup；没有绕过权限读取数据库。

系统后续日志显示 21:08:11 和 21:10:02 OplusHans 再次冻结 UID10395，尽管耗电管理 RadioButton 实际显示“完全允许后台行为”已选中。此前仅 30 秒成功不足以证明持久修复，撤回完全恢复的结论。通知管理显示开关关闭且不可操作；宿主 manifest 未声明 POST_NOTIFICATIONS，这只是待验证线索，不能声称加通知权限已证明能修复厂商冻结。

前台采样实际推理进程 CPU ~414%；simpleperf 5 秒采得 80,141 样本，61.70% ggml_compute_forward_flash_attn_ext，23.37% ggml_vec_dot_q8_0_q8_0。后台冻结时相同采样为 0，CPU 0。maps 仍是旧基础 libggml-cpu.so，非最新发布的 armv8.2 变体。证据 no-output-active-cpu-profile.txt。采样证明本地引擎在计算，不能单凭它确认当前输入 token 数或特定 ACP turn 归属；尚无首个可见输出和正式任务完成证据。security.perf_harden 已恢复1，原始采样临时文件已删除。

回归默认后台等待改为120秒，验证请求前必须仍处于预期前台应用。第一次120秒观察被诊断操作切换到系统设置/宿主干扰，已将报告标为 invalid、passed=false，不采纳其成功结果。后续受控测试单独记录。Skill 同步加强后台验收时长及“设置显示允许不等于实际可用”的要求。

受控120秒验收最终 FAIL：正确前台身份校验通过，宿主前台 models 200，切主 App 等120秒后 models timeout。见 models-lifecycle-background-120s-controlled.json。等待中一度观测推理CPU382%，说明后台限制与实际计算耗时都需继续诊断，不能把当前失败全部归因于协议或完全没有执行。
