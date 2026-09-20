# 旧独立宿主：仅用户明确要求旧版或源码构建时使用

# 一句话安装手机本地模型

用户可以说“使用本地模型服务”。本技能按需加载，安装任务结束后不再读取或探测；不增加常驻提示词、定时轮询或新的 Agent loop。

默认从固定 GitHub Release 下载约 19 MB APK，不下载 SDK/NDK，不编译。当前为项目发布的 ARM64 测试宿主（debug 签名），不是上游官方发布的 APK。宿主提供模型加载与上游本机 HTTP API，模型另行下载。安装仍需要 Android 系统确认，不承诺静默安装。源码构建仅作为明确要求时的可选能力。

## 0. 检查并复用已有安装

每次进入先区分服务已就绪、宿主已安装但未启动、宿主未安装，不能把端口不通当作未安装。

- 当前终端能运行 Python 时，先执行一次 `python3 <scriptsDir>/probe.py --port 9099`。真实推理通过后，仍须通过第 3 节“切回主 App 后的后台验收”再交接 Provider，不重新构建、安装或下载模型。
- 若探测未通过，通过现有应用查询能力按精确包名 `cn.com.omnimind.localinfer` 检查安装状态。已安装时，用已有应用启动/GUI 能力打开“本机模型”，点击“启动本机模型”，等待当前加载任务的明确结果，再执行一次 probe。宿主会校验并复用已下载模型。
- 已安装但加载失败时报告宿主的具体错误，修复该错误；不要直接启动一轮重新构建。查询能力不可用或结果不确定也不能报告“未安装”，先通过系统应用界面核实。
- 确认未安装才进入第 1 节默认安装；只有用户明确要求从源码构建时，进入第 1b 节。首次安装完成后再次点击入口应走上述复用路径。

这些检查只发生在本次用户任务中，完成后停止访问，不添加后台轮询。

## 1. 准备预编译安装包（默认）

在现有带 Python 3 和 curl 的终端中执行，不需要切换 Ubuntu 或安装构建工具：

```sh
python3 <scriptsDir>/phone.py prepare
```

下载固定版本 APK，校验 SHA-256 和原生库后生成安装回执；重复准备复用哈希正确的缓存，再进入第 2 节。缺失或校验失败时明确报告安装包问题，不自动转为源码构建。已有宿主不强制更新；签名不兼容时保留安装和模型，不卸载绕过。

## 1b. 从源码构建（仅用户明确要求）

用现有 terminal 工具检查当前发行版。构建脚本支持本机 Ubuntu ARM64；若当前是 Alpine，先通过已有终端设置/GUI 工具切换并初始化 Ubuntu，再继续。不要自行修改 App 私有偏好文件或嵌套启动 PRoot。

先确认 `python3 --version` 为 3.12 或更新版本；若新 Ubuntu 环境尚未安装 Python，用其自身的 `apt-get update` 和 `apt-get install -y python3 ca-certificates` 完成引导，不在 Android 宿主 shell 执行 apt。

在现有 terminal session 中执行 scriptsDir 下的接口：

```sh
python3 <scriptsDir>/phone.py build
```

该接口安装缺失的 JDK 和构建依赖，从固定上游下载源码及 ARM64 工具链并校验 SHA-256，在手机上编译原生 JNI/llama.cpp 和 APK。默认工作目录是 /root/.local/share/omniinfer-phone，下载缓存在 /root/.cache/omniinfer-phone。首次需要数 GB 存储、网络和较长构建时间。不要在电脑执行；失败保留下载与日志，修复具体原因后再执行，不循环重启构建。

保留上游 CPU 多版本构建和运行时硬件选择，不能为缩短构建而设置 `GGML_CPU_ALL_VARIANTS=OFF`。产物检查要求包含基础 ARMv8.0 和支持 FP16/点积的 ARMv8.2 CPU 库；实际使用哪一个仍以设备加载结果为准，不能把“已编译优化库”说成已验证加速幅度。完整校验回执和预期工具文件齐全时可复用解包目录，不要求再次下载压缩包；冷安装预留 5 GiB，完整缓存重建至少预留 1 GiB，空间不足明确失败。

构建期间不要更新或重装承载终端的 OpenOmniBot。若脚本明确报告 PRoot loader 已消失，先确认主 App 更新已经结束，再关闭失效终端、创建新的 Ubuntu terminal session，从校验过的缓存继续同一安装任务；不要在失效会话内循环执行或重新下载全部工具链。

长任务由 terminal_session_* 的现有生命周期管理。只在该会话证明命令仍运行时读取进度，命令完成/失败后停止读取。exitCode=0 且最终 JSON 的 stage=apk_built 才表示构建成功，installed 与 inference_verified 此时仍为 false。

## 2. 安装并启动

使用手机系统安装器，不要求 Shizuku，也不新增常驻 Tool。在另一个现有 terminal session 启动临时 APK 交接接口：

```sh
omniinfer_artifact_log=$(mktemp /tmp/omniinfer-artifact.XXXXXX)
python3 <scriptsDir>/phone.py serve > "$omniinfer_artifact_log" 2>&1 &
omniinfer_artifact_pid=$!
printf 'artifact_pid=%s artifact_log=%s\n' "$omniinfer_artifact_pid" "$omniinfer_artifact_log"
```

`terminal_session_exec` 等待命令结束，因此服务必须在这个持久会话的后台运行，并将输出重定向到普通日志文件。下一条会话命令用 `cat "$omniinfer_artifact_log"` 读取实际就绪 JSON；若仍为空，核实该 PID 存活后再有限等待启动结果。不要以前台运行 serve，不要读取 `/proc/<pid>/fd/1` 或对日志执行 `tail -f`，这些操作会阻止 Agent 继续安装。保存该会话、PID 和日志路径；下载完成或安装任务取消时，在同一会话结束该 PID、删除本次日志，再停止会话，不使用 nohup 脱离现有会话管理。

它只在 127.0.0.1 临时提供 /artifact.apk，输出本次产物的 url、sha256、package，使用 Android APK MIME 和附件文件名。通过现有 GUI 工具在手机浏览器的新标签页打开这个实际 URL，下载 `OmniInfer-local.apk`，从本次下载记录打开并交给系统安装器。不要打开旧下载或猜测 URL。

APK 下载不是普通网页加载。若浏览器导航超时，先检查本次下载记录的完成状态和产物哈希；不能只凭导航超时宣称下载失败、重复下载或改走已知 MIME 不正确的 workspace 预览。确认完成后从浏览器下载记录打开 APK，让其保留下载响应中的 APK MIME。

若出现“允许来自此来源的应用”，通过系统界面完成授权后返回安装。核实系统显示安装完成，打开“本机模型”，点击“启动本机模型”。安装器取消、失败或等待确认时不能报告安装完成；不使用终端 root 假象绕过 Android 权限。下载完成后停止 artifact terminal session，它不负责模型推理，也不需要常驻。

GUI 工具的 success 或自然语言“安装成功”不是独立安装证据。在向用户宣布成功前，必须通过应用查询或系统应用信息页确认精确包名 `cn.com.omnimind.localinfer` 已安装；查不到或屏幕仍停在文件列表时，标注安装未验证，继续处理实际安装步骤，不先宣布成功再核实。最终还须打开宿主并运行第 3 节真实推理验证。

清理必须调用该会话的 `terminal_session_stop`，不能只 `kill $!` 后输出“已停止”：在手机终端的 Python 包装器下，后台 PID 可能是包装进程，真正的服务子进程仍然存活。以会话停止结果和本次端口不再提供 APK 为清理证据。

当前部分 App 版本会把 workspace APK 识别成 `application/octet-stream`，系统“打开文件”列表因此没有软件包安装器。不要把这一入口当成已验证的安装路径，也不要选择无关 App；使用上面的 APK MIME 下载入口。浏览器安装路径仍须在当前设备实际验证，不能用 HTTP fixtures 代替。

如果设备已经具备可用的 Shizuku 高级动作权限，也可以通过 tools_search 找到真实的 android_privileged_action，使用其 shell.exec：

1. 确认 Android curl 可用，用 `--noproxy 127.0.0.1` 和有限超时将该 loopback URL 下载到 `/data/local/tmp/omniinfer-phone-<本次唯一标记>.apk`。
2. 核对文件 SHA-256 与构建接口一致，再执行 `pm install -r` 该 APK，检查实际 Success。
3. 启动 `am start -n cn.com.omnimind.localinfer/.MainActivity --es operation start`。
4. 删除本次 /data/local/tmp APK，停止临时 artifact terminal session。

URL、SHA-256、包名必须来自本次准备或构建输出；命令参数正确引用，不使用私有目录绕过或假设 shell 能读取主 App 数据。遵循高级动作既有授权结果；没有 Shizuku/安装权限时明确等待该权限或系统安装确认，不把下载成功当安装成功。

首次启动的宿主从 Qwen 官方固定版本下载 Qwen3 0.6B Q8_0（约 640 MB）、校验哈希、通过上游 OmniInfer 加载 CPU 后端。失败显示在宿主页面；不回退云端。模型文件保存在宿主私有目录，后续启动复用。状态显示“本机模型已就绪”后再验证，并完成下面的后台运行设置。

## 2b. 允许宿主在后台提供服务

本地 HTTP 服务由上游 OmniInferService 的前台服务承载，但部分厂商仍会冻结它。尤其 OPPO/一加的系统后台管理会在切回主 App 后冻结宿主，导致 /health 和 /v1/models 超时；不能误判为协议不兼容、模型不存在或需要重装。

通过系统应用详情打开精确包名 `cn.com.omnimind.localinfer` 的耗电管理。在 OPPO/一加选择“完全允许后台行为”，并确认系统的“允许”弹窗；其他厂商通过其对应的后台运行/电池设置允许该宿主工作。只调整本机模型宿主，不关闭全局省电、不更改其他应用，不使用 root、隐藏 shell 白名单或常驻保活循环。该设置允许服务在使用本地模型时继续运行，可能增加耗电，停止模型服务仍使用宿主的“停止服务”。

如果设置已经允许，不重复操作；如需要用户亲自确认而现有 GUI 无法完成，明确报告等待这一步，不能宣称已可用于主 App 对话。

## 3. 验证与 API 交接

```sh
python3 <scriptsDir>/probe.py --port 9099
```

probe 顺序验证 health、模型列表和真实非流式生成；只连手机 loopback，不使用代理，不自动重试。非零退出就是未通过。确认实际安装包版本与原生服务进程后，再把 Provider 字段交给现有配置入口：

- Base URL：http://127.0.0.1:9099/v1
- 协议：OpenAI-compatible / Chat Completions
- 模型 ID：GET /v1/models 返回的真实 ID
- 认证：该上游 loopback 服务不要求云端 API Key；不要填写用户云端密钥。

### 切回主 App 后的后台验收（必须）

前台 probe 成功不能作为安装完成。启动宿主并确认模型就绪后，通过现有 GUI 切回 OpenOmniBot 模型提供商页，至少等待 120 秒，且期间不要打开宿主（可由现有一次终端命令等待，不添加轮询），再执行同一个 probe，并点击页面“拉取/重试”。须同时看到真实 API 模型 ID 和列表中的模型。宿主进程仍在但接口超时时，先检查 2b 的厂商后台限制；即使设置显示允许，也需核查实际接口，短时成功不能证明已解决；不修改已正确的 /v1 地址或换协议，不清空模型缓存伪装修复。重新打开宿主前台恢复、回主 App 又超时，应明确报告后台运行未通过。

对已有安装重复此流程，并在宿主停止/重开后再次切回主 App 验证，确认系统设置和模型文件均被复用。完成用户任务后停止探测；不让 Agent 永久访问本地模型服务。

不添加“让本地模型回答”的普通工具，不把 Android API 当成 Responses 或 Anthropic 协议。没有 Provider 写入工具时，报告可用地址与字段，明确尚未自动配置。

在支持的 Provider/Harness 上验证实际对话与流式停止后继续，再验证一个无副作用工具调用及结果回传。记录设备、宿主/OmniInfer 版本、模型、后端、操作与结果。重复启动不重新下载；停止后端口不可用；重开后模型可再次加载。离线性必须实际断网测试，不能仅根据 loopback 地址推断。Android 模块一次加载一个模型，并行会话的排队/取消归属未经验证前不得承诺并行推理。

## 远程安装包

- 固定版本：https://github.com/omnimind-ai/OmniBot/releases/tag/omniinfer-local-v0.1.0-test.1
- APK：https://github.com/omnimind-ai/OmniBot/releases/download/omniinfer-local-v0.1.0-test.1/OmniInfer-local-arm64-v0.1.0-test.1.apk
- 脚本使用同一资产的 GitHub API 下载入口（Accept: application/octet-stream），避免 github.com 直链连接失败；无需登录或密钥。
- SHA-256：`c8152e7094b36de5b6ad32765c97b4e408c27a45a34917163593db571a93fcb2`

不解析 latest，不下载主 App APK，不将下载失败自动转成源码构建。下载使用有限重试、断点续传和校验后原子替换；校验失败保留文件并明确报错。主 App 不携带宿主 APK。

## 来源与范围

OmniInfer @ 6ad984d414d4b7c27ab5740e63e8ccb1b33e4046；llama.cpp @ 30b6a755e29692e8bc8e072885325716a2fee70f。源码使用上游实现，仅宿主构建配置适配 ARM64 工具链。OmniInfer 和默认 Qwen 模型为 Apache-2.0，llama.cpp 为 MIT；SDK/NDK 各自许可证随下载包保留。

- Android 集成：https://github.com/omnimind-ai/OmniInfer/blob/6ad984d414d4b7c27ab5740e63e8ccb1b33e4046/docs/android/integration.md
- API：https://github.com/omnimind-ai/OmniInfer/blob/6ad984d414d4b7c27ab5740e63e8ccb1b33e4046/docs/android/api-examples.md
- ARM64 工具链：https://github.com/lzhiyong/termux-ndk

阶段必须分别报告：手机构建、APK 安装、模型加载、API 回复、App 对话、停止/重开、离线测试。未运行和失败明确标注，不能把脚本测试或电脑结果当真机验收。
