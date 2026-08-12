# OmniBot Android 正式发布审计与门禁

更新日期：2026-08-12

## 1. 当前结论

当前代码已经完成大部分账号、平台 AI、凭据保护、隐私最小化、更新完整性和 Android 16KB 构建收口，但**尚不能宣布正式版审核通过或直接上架**。

剩余 P0 主要不是“再改一行代码”，而是必须使用最终 production release AAB/APK、正式签名、真实账号与网关、16KB Android 设备和 Google Play Console 完成验收。任何一项未完成，都不能用 debug 编译或单元测试代替。

严重级别：

- P0：发布阻塞；未通过不得发布或上架。
- P1：应在首个正式版前关闭，或由负责人书面接受风险并安排明确版本。
- P2：质量与维护性改进，不单独阻塞发布。

## 2. 已关闭的代码风险

### 2.1 账号、平台 AI 与零配置模型

- 正式账号与模型网关地址通过 production 构建门禁强制为干净的 HTTPS 基础地址；禁止 user-info、query 和 fragment。
- 平台模式使用用户 JWT 获取安全模型目录，不向 App 暴露内部上游 URL、内部 Token 或模型供应商 Key。
- 只读“OmniBot 官方 AI” Provider、默认模型和场景模型能力已接入；BYOK 仍使用用户本地 Provider/Key。
- 平台目录按服务端明确声明的五类能力处理：文本、视觉、图片生成、TTS、STT；不再仅凭模型名称猜测能力。
- 平台与 BYOK 路由、401 单次刷新、配额错误和安全失败路径已有定向测试。
- 正式额度/计费/TTS 声音错误码已统一为稳定中文提示，不显示服务端私密错误正文。
- 平台聊天与视觉按最终 UTF-8 JSON 执行 15 MiB 发送前硬门禁；多图和长中文上下文超限测试确认不会发请求。
- STT 原始音频限制为 24 MiB，为 multipart 封装保留余量；TTS 每次准备播放时使用 `ensureReadyStatus()` 获取可用目录，不只读取旧缓存。

### 2.2 凭据与秘密

- 账号 Access/Refresh Token、BYOK Key/自定义 Header、Remote MCP Bearer、OpenClaw Token、Remote Codex Token、ACP 环境变量、自定义 TTS curl、本机 MCP Token 和 OpenClaw 私钥均已迁入 Android Keystore 支持的安全存储。
- 旧 MMKV/SharedPreferences 明文或弱密文执行一次性迁移；成功和失败都擦除旧值，安全存储不可用时失败关闭，不回退明文。
- Flutter/状态接口默认只返回 `hasToken`/`hasApiKey` 等状态，不回传持久化秘密原文；编辑空白值保留旧秘密，显式清除才删除。
- Codex auth 运行目录和文件已收紧为仅所属用户可读写；日志和错误路径不得输出原凭据。
- Codex/Claude Code/OpenCode 本地配置已统一为“查看状态、替换、清除”：Codex 只向 Flutter 返回 Base URL、模型和 `hasApiKey`（`apiKey` 固定为空），Claude/OpenCode 只返回固定展示路径、是否存在和受限字节数，原配置正文与 Token 不会进入 Flutter 返回值。替换输入始终为空，空白输入失败且不覆盖；清除需要 App 内两步确认，并明确会退出本地 Agent 会话、不会删除服务端或云端数据。
- 三类内建 Agent 的写入目标由 native 固定 allowlist 决定，调用方不能传路径；正文/新 Token 只经子进程 stdin 传入，不进入 shell argv、命令文本、成功 payload 或原始错误。父目录为 700、文件/唯一临时文件为 600，在同目录完成原子 rename；状态/替换拒绝符号链接、非普通文件和多硬链接，清除只 unlink 固定目录项。失败只返回稳定 `AGENT_CONFIG_*` 错误码。

### 2.3 网络传输

- 统一凭据端点规则拒绝嵌入式 user-info、fragment 和敏感 query 参数。
- 携带 API Key、Bearer、自定义鉴权 Header 或会话秘密的请求只允许 HTTPS/WSS；仅 debug 构建的严格字面回环地址允许 HTTP/WS。
- Remote MCP SSE 返回 endpoint 必须与初始 endpoint 同源，防止 Bearer 随跨域 endpoint 泄漏。
- 账号、官方模型、更新下载、Remote MCP、Remote Codex、OpenClaw、文本/视觉/图片/TTS/STT 的鉴权路径已有代码层安全检查。
- 浏览器和无凭据本地功能仍需 HTTP，因此 `network_security_config` 保留全局 cleartext；凭据安全依赖上述统一校验和回归测试。

### 2.4 隐私与日志

- 首次启动提供明确的同意/拒绝入口，并持久化 `PENDING/GRANTED/DECLINED`；`PENDING` 前不生成也不发送可选安装标识。
- 更新检查只发送必要版本参数；只有明确同意后才附带随机 `installId`，不再发送品牌、型号、OS、SDK 或 Build fingerprint。
- 通用 HTTP 客户端不再自动附加设备型号、fingerprint、host、user 等高敏 Header，并在发送前清除旧版设备 Header。
- release 运行日志默认关闭正文或只记录脱敏元数据；AI 请求诊断不默认保存 request/response 正文，升级后启动会同步清除旧版本遗留正文，清除无法验证时删除整项并保持采集关闭。
- Flutter 生产源码已清除直接 `print`/`debugPrint` 调用；唯一的 `debugPrint` 位于 `SafeLog` 内部，只在 debug 构建接收固定事件枚举及布尔值、计数、字节数，接口不接受用户文本、标识符、路径、URL、异常或请求/响应正文。静态源码门禁禁止业务代码绕过 `SafeLog`。
- 隐私数据流细节继续维护在 `docs/production-privacy-data-inventory.zh-CN.md`；本文件只记录发布门禁。

### 2.5 Android 安全基础

- `compileSdk` 与 `targetSdk` 已升级到 36。
- release 开启 R8 混淆和资源收缩。
- `allowBackup=false`，旧/新备份规则均为拒绝列表，不允许 App 私有凭据和数据库进入 Android Auto Backup/设备迁移。
- APK 更新链校验 HTTPS、最大大小、SHA-256、包名、版本单调递增和签名连续性；重定向到 HTTP 会失败。
- 外部导入使用 `content://`，限制单文件、总大小和数量；MCP inbox 有大小、数量、TTL 和启动清理。
- 本机 MCP `file_transfer` 当前只向客户端返回 inbox 的文件 ID、名称、MIME、大小和接收时间，不返回文件正文、下载 URL、短期 token 或鉴权 Header。外部客户端短期下载 Header 协议尚未实现，等待用户对这一数据出口明确授权。
- WebView 桥限制可信来源和 data URL 大小；网页敏感权限只接受合法 HTTPS origin，摄像头/麦克风仅映射到 Android `CAMERA`/`RECORD_AUDIO`，空资源、未知资源、DRM 或组合中的任一非白名单资源都会立即拒绝。网页定位已声明粗略/精确位置权限并启用，但每个站点的每次请求都必须先显示中英文接收方、用途以及“数据由网站处理，不是 OmniBot AI”，再请求 Android 运行时权限；不保存“始终允许”。权限响应绑定 requestId、标签页和导航代次，取消、替换、切换标签或导航后的旧批准不能生效。`productionStandardRelease` 与 `productionPlayRelease` 的精确权限基线均已同步四项 Web 运行时权限。
- 所有日历读取/写入都必须经过本机原生逐次确认，AI 传入字段不能代替用户点击。
- 当前所有 Shizuku/高权限动作均因缺少可验证的本机一次性确认而失败关闭；模型传入 `confirmed`、`confirmationToken` 或类似字段不能形成授权。生物识别/锁屏认证桥尚未实现，等待用户明确授权。
- CAMERA、RECORD_AUDIO、日历、通知等普通危险权限按使用场景即时请求并给出用途说明；未使用的多项权限已删除。
- Android lint 已恢复 `abortOnError=true`；最终候选包仍须实际跑完 lint，不能只依赖配置值。

### 2.6 16KB loader32 代码收口

- 正式构建链不再提取旧 deb 内 4KB 对齐的 `loader32`。
- `termux/proot v5.1.107.77` 所需 ARM32 loader 源码和 GPL-2.0 许可证已本地固定；构建不从公网下载或执行源码。
- 固定上游提交、官方源码归档 SHA-256、每个 vendored 文件 SHA-256、NDK `28.2.13676358`、编译/链接参数和最终产物 SHA-256。
- 构建门禁检查 `_start` 位于 `0x20000000`、无未定义符号、ELF32/ARM/ET_EXEC、ARM EABI、无 `PT_DYNAMIC/PT_INTERP`、固定三个 `PT_LOAD`，且每个段均至少 16KB 对齐并满足 offset/address 同余。
- 已验证连续构建产物一致，loader32 三个 `PT_LOAD` 的 `p_align` 均为 `0x4000`；App develop-standard Kotlin 编译在此前阶段曾通过，但最终工作树受 `StorageUsageChannel` 本机数据删除收口的编译问题阻塞，必须在取得相应破坏性功能授权并正确处理后重新运行，不能沿用此前结果作为最终证据。
- OmniBot 正式根工程只包含 `ReTerminal/core/main`，正式 App 只从该模块生成的单一 loader32 路径打包；旧 `ReTerminal/app` standalone 模块不在 OmniBot 根 `settings.gradle.kts` 中，不能作为 OmniBot 发布入口。

这只关闭了 loader32 的代码 P0。最终 release AAB/APK 的全部 ELF 和真实 16KB 设备仍须按第 4 节验收。

### 2.7 Flutter 工作区与本地回归

- 工作区文件浏览器不再只依赖字符串前缀判断路径：普通工作区和用户明确授权的挂载目录分别建立规范化边界，并在访问前解析符号链接/目录联接；解析失败、越界或从授权挂载再次跳出边界时均失败关闭。
- 异步文本读取绑定启动时的文件路径和读取代次；用户从文件 A 快速切换到文件 B 后，A 的迟到结果不能覆盖 B，也不能在保存时写入 B。保存前还会重新确认当前文件与编辑文件一致。
- 可编辑文本执行 5 MiB 硬上限：读取前检查文件大小，流式读取过程中再次累计实际字节数，防止大小变化或注入读取器绕过限制；超限文件只显示安全的不可编辑状态。
- 最终一次 Flutter 全量测试为 `664/664` 通过；最终一次静态分析为 `0 error / 0 warning / 143 info`。这些是本地代码回归证据，不等于 production 候选包、真实设备或商店审核已经通过。

### 2.8 发布变体、版本号与更新链

- 已建立 `standard`（官网直装）与 `play` 两个正式发行版本：`productionStandardRelease` 保留经完整性校验的应用内 APK 更新；`productionPlayRelease` 在合并 Manifest 中移除 `REQUEST_INSTALL_PACKAGES`，并从原生调度、Flutter 服务和 UI 入口共同关闭应用内 APK 自更新。
- production 构建必须显式提供 `OMNI_RELEASE_VERSION_CODE`，缺失、为零或无效时失败；debug 的开发回退值不能进入正式包。标签、CI、手动发布和本地发布脚本使用同一套可审计公式，并验证新值大于既有正式标签对应的值。
- 正式标签还必须与 Android 唯一的 `versionName` 完全一致；例如源码仍为 `0.5.6.16` 时，`v0.5.7` 会在构建前失败。标签必须已存在并指向当前 `HEAD`，工作树的 staged、unstaged、untracked 任一变化都会失败；Flutter/Gradle 构建完成后和真正发布前会再次核对，避免构建步骤改变源码后仍把产物归因给旧标签。
- `--skip-build` 不再从全局输出目录猜测并重标旧 APK：必须显式给出单个 `--reuse-artifact`，并核对最终 Manifest 的包名、versionName、versionCode、发行 edition、完整权限集合、非 debug/testOnly 状态以及唯一批准证书。即使全部通过，复用产物仍无法证明代码来自本次标签，因此只允许离线复核/拷贝，禁止进入 GitHub 或 Worker 发布通道。
- Play 使用独立的 signed `productionPlayRelease` AAB 入口。AAB 通过 JAR 签名和批准证书核对，并用固定 `bundletool-all-1.18.1.jar`（SHA-256 `675786493983787ffa11550bdb7c0715679a44e1643f3ff980a529e9c822595c`）解码最终 base Manifest；权限必须与受审基线完全一致且不得出现 `REQUEST_INSTALL_PACKAGES`。Play AAB 只能交给 Play 发布流程，不能上传到 GitHub Release/Worker 自更新通道。
- 更新检查已增加 single-flight 协调和代次校验：并发请求共享结果，强制检查可使旧的静默检查失效，迟到响应不能覆盖新缓存或新界面状态。
- 更新资产选择对所有候选入口应用发行版本过滤，包括响应顶层的兼容资产字段；`play` 版本不会因旧格式响应重新获得直装 APK，`standard` 版本也不会选中其他发行版本的资产。HTTPS、SHA-256、包名、版本和签名连续性校验仍保持强制。

## 3. 仍需代码或工程收口

### 当前 P0 编译阻塞

- 当前最终工作树的 Android App Kotlin/单元测试编译并非全绿：`StorageUsageChannel` 中用于收口本机对话/历史数据清理的引用存在编译阻塞。该路径属于“删除全部本机私人数据”的广泛、永久删除能力，尚未取得用户对破坏性范围的明确授权，因此不得通过删掉校验、伪造空实现或其他绕行方式使构建表面通过。
- 在用户明确授权、删除范围得到实现并完成对应编译与回归前，不能构建或签字认可正式 signed release AAB/APK，也不能把 Flutter 全绿或此前的 Android 阶段性编译结果当作替代证据。

### 已关闭的原工程 P0

- 发布流水线的递增 `versionCode`、production 显式值门禁以及标签/CI/本地脚本一致性已经实现；最终上传前仍须用真实发布标签和候选包核对实际 Manifest 中的值。
- Play/Direct 分发已落实为 `play`/`standard` 正式变体，并在 Manifest、原生调度、Flutter 服务和 UI 中体现。最终合并 Manifest 与 Play Console 权限扫描仍属于人工发布门禁。

本节原有的两个工程 P0 已关闭，但第 4、5 节的签名候选包、真实设备、真实网关和 Play 合规 P0 尚未关闭，因此不能据此批准正式发布。

### P1

- Gradle wrapper 8.13 已被 Flutter 提示即将不再支持，应在独立变更中升级到至少 8.14，并重跑全量构建。
- 旧 `ReTerminal/app` standalone 工程仍保留另一套历史预编译 PRoot loader 和联网下载任务。它不进入 OmniBot 根工程，但应单独退役或改为复用 `core/main` 固定构建，避免维护者误从遗留入口产出 4KB/多来源二进制。
- `AgentRuntimeManager` 的 Codex/Claude 托管 ACP 适配器随 App 交付专用 `package.json`、lockfile v3 和完整安装树 Merkle 清单：两个适配器、全部 transitive、Codex Linux ARM64 runtime、Claude Linux ARM64 glibc/musl runtime 都固定版本；133 个包共 6,067 个普通文件的内容、模式和数量按包绑定，`.bin` 只允许清单中的精确相对目标，未知包、未知文件、硬链接、逃逸 symlink、包内深层 `node_modules` 或当前平台 runtime 缺失都会失败。安装资产经有界 stdin 送入新进程，并以硬编码 SHA-512、`O_EXCL|O_NOFOLLOW` 写入全新随机 staging，既不暴露到 argv，也不会超过系统参数长度；随后只运行固定 registry 的 `npm ci --ignore-scripts`，明确要求 Node >=22、`engine-strict`、`env -i`、专用 HOME/cache/空 npmrc，并在安装前后拒绝 `.npmrc`、`npm-shrinkwrap.json` 和未知顶层控制文件。验证成功后才原子切换版本目录。marker 不是信任根，每次启动仍会用 `O_NOFOLLOW` 重验锁和清单、重算完整树，并在 `exec` 前再次验证官方适配器的绝对路径，绝不回退 `/root/.npm-global`。官方 profile 的 command/args 不可覆盖，旧覆盖会迁回官方定义；npm 原始输出被丢弃，用户只收到稳定 `AGENT_RUNTIME_ADAPTER_*` 错误码。离线审计同时挂入本地发布入口和所有 Gradle `preProduction*ReleaseBuild` 任务。
- 内嵌终端启动不再自动执行浮动的 `pip --upgrade`、PyPI `uv` 或全局 `pnpm` 安装，也不会通过 PNPM/Corepack 版本探测隐式下载包管理器；基础包安装失败只返回 `AGENT_RUNTIME_BASE_PACKAGE_INSTALL_FAILED`，不拼接仓库、路径或命令原文。独立 Codex/Claude/OpenCode CLI、uv、codex-pets 以及在线 Skills CLI 搜索仍缺各自完整的跨平台依赖锁，因此当前正式版分别返回 `AGENT_RUNTIME_MANAGED_CLI_LOCK_REQUIRED`、`AGENT_RUNTIME_UV_LOCK_REQUIRED`、`AGENT_RUNTIME_CODEX_PETS_LOCK_REQUIRED`、`AGENT_RUNTIME_SKILLS_CLI_LOCK_REQUIRED`，不会用浮动版本冒充可用。它们是明确保留的正式功能缺口，完成独立 lock/integrity 审查前不得标记为已交付。
- 已关闭：Codex/Claude Code/OpenCode 本地运行配置的“查看状态、替换、清除”生命周期已实现；原配置/Token 不进入 Flutter 状态、命令行、日志或诊断，定向 Flutter 测试覆盖旧秘密不回显、空替换不覆盖、显式替换和两步清除。Android 定向单元测试已新增 allowlist、路径注入、symlink/hard-link、权限和稳定错误 fixture，但最终执行仍被本节所述 `StorageUsageChannel` 无关编译阻塞挡住，不能记作 Android 测试通过。
- “删除全部本机私人数据”入口当前未实现，等待用户对破坏性范围明确授权；相关 `StorageUsageChannel` 收口引用目前也使最终 Android App Kotlin/单元测试编译阻塞。实现时必须逐项说明公共存储、工作区、下载和生成媒体是否保留，卸载不能被描述为会自动删除公共文件；授权前不得为了产出 signed release 而绕过该阻塞。
- 本机 MCP 外部客户端短期下载令牌/Header 协议当前未实现，等待用户对文件数据出口明确授权；授权前继续只返回 inbox 元数据，不得通过替代 URL、日志或调试接口绕过。
- 若恢复 Shizuku/高权限能力，必须先取得用户对一次性确认桥的明确授权；每个动作以及任意 shell/高权限会话命令都应要求系统生物识别或锁屏认证，不提供“始终允许”，认证失败即拒绝。
- OpenClaw 设备身份需要显式重置入口和后果说明。
- 远程 Provider、MCP、OpenClaw、Codex 等向用户指定第三方发送数据前，仍应逐功能补齐目的地和数据类型提示；WebView 摄像头、麦克风和定位的逐站点、逐请求披露已完成代码收口，仍需最终候选包真实网站/真实设备验收。

### P2

- 清理 Gradle 9 不兼容的 deprecated API 和 SDK XML 工具版本不一致警告。
- 将 Manifest 中乱码注释、遗留命名和重复安全规则整理为可维护的中文/英文注释；不影响运行时但会增加审核误判。
- Flutter 最终一次本地静态分析记录为 `0 error / 0 warning / 143 info`，全量测试为 `664/664` 通过；这些 info 主要是废弃 API、样式和维护性提示，可按 P2 继续收口。该记录不等于 Android production 候选包、签名、真实设备或真实服务验收已经通过。

## 4. 需真实环境人工验证的 P0

### 4.1 最终 production release 与签名升级

- 前置条件是第 3 节的 Android 编译阻塞在明确授权后正确关闭，并重新取得最终工作树 Kotlin/单元测试全绿证据；在此之前不得开始或批准正式签名候选包。
- 分别使用正式 release keystore 或 Play App Signing 构建 production-standard 与 production-play 候选 AAB/APK；不得使用 debug 签名，也不得用一个变体的验收结果替代另一个变体。
- 核对 standard 合并 Manifest 保留 `REQUEST_INSTALL_PACKAGES` 且自更新入口可用；核对 play 合并 Manifest 移除该权限，后台更新任务已取消，应用内更新 UI 不可达。
- 验证包名、versionCode/versionName、V2/V3 签名、证书连续性、升级安装和回滚拒绝。
- 从当前已发布版本升级，确认账号、加密存储和旧凭据迁移正常；再做一次全新安装。
- 不在命令、日志、截图或文档中记录 keystore 密码、Key Alias 密码或任何服务密钥。

### 4.2 清数据首装与账号主链

在专用测试账号和最终 release 候选包上执行：

1. 清除 App 数据或全新安装。
2. 首次隐私选择分别覆盖同意和拒绝。
3. 邮箱验证码注册/登录。
4. 选择平台额度。
5. 自动获得只读官方 Provider 与服务端模型目录。
6. 无本地 Provider/Key 的情况下直接聊天。
7. 验证 401 只刷新一次、退出/撤销后失败关闭。
8. 验证配额确实扣减、余额不足 UX、重复请求/取消请求不会异常多扣。
9. 切换 BYOK，确认本地 Provider/Key 不被平台模式覆盖，且不会发往官方网关。

### 4.3 真实平台五类模型

必须用最终 production 包和真实网关逐类验证，不允许只依赖 mock/unit test：

- 文本：普通聊天、流式输出、取消、长上下文、配额扣减。
- 视觉：选取真实图片、大小/格式错误、模型目录能力匹配、配额扣减。
- 图片生成：生成、下载、超时/失败、结果文件清理、配额扣减。
- TTS：真实音频返回、格式检测、播放/停止、自动播放隐私提示、缓存清理、配额扣减。
- STT：麦克风即时授权、录音取消、文件选择、超限/不支持格式、临时文件清理、配额扣减。

同时抓包确认只发往预期 HTTPS 目的地，不出现内部上游 Key、通用设备 Header 或敏感 URL query。

服务端发布前还必须运行 `omni-platform-deploy/scripts/linux/official-catalog-gate.sh`，并在负责人明确同意真实计费后运行 `official-capabilities-live-gate.sh`。二者只从权限严格的文件读取测试用户 JWT，不得把 JWT 放入命令行值、日志或文档。

### 4.4 16KB page size 与 32-bit guest

- 对最终 APK 执行 `zipalign -c -P 16 -v 4`。
- 解包最终 APK/AAB 生成的目标 APK，逐个检查所有 `.so` 的每个 `PT_LOAD`；不能只检查 loader32。
- 在 `adb shell getconf PAGE_SIZE` 返回 `16384` 的 Android 15/16 设备或模拟器上冷启动、登录、聊天、图片、STT/TTS、MCP 和终端。
- loader32 还需在支持 AArch32 compat 的 ARM64 环境运行 ARMHF/ARMv7 guest，至少验证 `/bin/true`、shell、fork/exec、signal、bind mount、文件读写。
- 在普通 4KB ARM64 设备回归 aarch64 Alpine/Ubuntu 与 32-bit guest，防止 16KB 链接修改破坏旧设备。

当前连接手机是 4KB、arm64-only，不能替代上述两类测试。

### 4.5 Android 15/16 行为

- edge-to-edge：所有页面、键盘、弹窗、权限页、相册/文件选择和横竖屏视觉检查。
- 前台服务：终端 `specialUse`、媒体播放、dataSync 回退、通知权限拒绝、系统超时和后台启动限制。
- 精确闹钟、开机恢复、时区变化、电池优化、悬浮窗、后台任务和通知点击。
- CAMERA、RECORD_AUDIO、ACCESS_COARSE_LOCATION、ACCESS_FINE_LOCATION、日历、文件和通知权限的首次拒绝、永久拒绝、设置页恢复和撤销后行为。
- 用三个受控 HTTPS 测试页分别验证网页摄像头、麦克风、组合资源和定位：每次都显示正确接收方/用途/第三方处理披露；拒绝未知/DRM/空资源；拒绝 HTTP、无 host、userinfo 与异常 origin；替换请求、切换标签和导航后旧批准不能生效；重新访问必须再次确认且不存在“始终允许”。
- 低内存、进程被杀、网络切换、离线、超时、重复点击和旋转恢复。

### 4.6 数据删除、备份与恢复

- 验证删除账号后服务端 Token 立即失效，平台请求失败关闭，本地账号/官方 Provider 状态清理。
- 验证删除对话会同时删除关联 tool event、token usage、Codex binding、附件引用和缓存。
- 验证清本地数据、卸载、重新安装后 Keystore 密文不会产生不可恢复循环或明文降级。
- 验证 Android Auto Backup/设备迁移不包含 App 私有文件、数据库、SharedPreferences、外部应用目录或 device-protected 数据。
- 服务端必须完成加密备份、恢复演练、RPO/RTO、删除 tombstone 传播和“恢复旧备份后已删除账号不会复活”演练；这是运维 P0，不在 Android 编译范围内。

## 5. Google Play 专属门禁

### P0 权限与政策声明

- `QUERY_ALL_PACKAGES`：已安装 App 清单属于个人和敏感数据。必须证明广泛可见性是核心功能并提交 Play 声明，或改为 `<queries>`/Intent 定向可见性。
- `MANAGE_EXTERNAL_STORAGE`：必须符合文件管理/文档管理等允许用途并提交声明；否则改用 Storage Access Framework/MediaStore。
- `REQUEST_INSTALL_PACKAGES`：代码已通过 `play`/`standard` 变体拆分；Play 版移除该权限并关闭应用内 APK 自更新，Direct 版保留。提交前仍须核对最终合并 Manifest、AAB 权限扫描和 Play Console 结果。
- `SCHEDULE_EXACT_ALARM`：确认闹钟/提醒是面向用户的核心功能，完善即时说明和 Play 声明。
- `SYSTEM_ALERT_WINDOW`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：必须由用户主动触发并准确说明用途，避免首启强制索取。
- `FOREGROUND_SERVICE_SPECIAL_USE` / `DATA_SYNC` / `MEDIA_PLAYBACK`：Play Console 声明、Manifest subtype 与实际功能必须一致；终端长期运行不能伪装成 dataSync。

### P0 平台要求

- **target API 门槛**：自 2026-08-31 起，提交到 Google Play 的手机新应用和应用更新必须 target Android 16（API 36）或更高；这决定版本能否提交，不能替代 16KB 兼容验证。代码当前已为 36，最终 AAB 和 Play Console 仍需确认。[Google Play 官方 target API 时间表](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-GB_ALL)
- **16KB page size 门槛**：Google Play 要求 target Android 15（API 35）及更高版本的应用在 64 位设备上支持 16KB memory page size；自 2027-02-01 起，不支持 16KB 的应用更新将无法发布。这与 2026-08-31 生效的 target API 36 提交门槛是两项独立要求。本项目最终 AAB、由 AAB 生成的目标 APK、全部 ELF 检查与真实 16KB 设备测试缺一不可。[Android Developers 官方 16KB 兼容要求](https://developer.android.com/guide/practices/page-sizes)
- 只发布 arm64-v8a 时满足 64 位要求，但要确认设备覆盖范围、32-bit guest 产品说明和 Play device catalog。
- 每次发布递增 versionCode，并在 internal testing 轨道完成安装、升级、崩溃/ANR 和预发布报告。

### P0 法律与商店资料

- 可公开访问的隐私政策、用户协议、账号删除 URL、运营主体、联系邮箱和未成年人政策。
- Play Data safety 必须与代码真实数据流一致：账号、安装标识、对话/文件/音频/图片、已安装 App、日历、诊断、第三方 Provider、官方上游和删除周期。
- 明确官方模型上游/子处理者的主体、处理地区、内容保留、训练开关、删除接口和合同责任；在负责人确认前不得猜测写入政策。
- 完成 GPL/第三方开源许可证、NOTICE、源码提供义务和依赖清单审查；vendored PRoot 源码与 GPL-2.0 必须随发布合规处理。
- 准备内容分级、应用访问测试账号、广告声明、出口/加密声明、截图与商店文案，并与实际权限和功能一致。

## 6. 可复现的本地命令

以下命令不含密钥；签名属性和服务密钥应只由安全 CI/本机受保护配置提供。

```powershell
# production 官方端点门禁
.\gradlew.bat --no-daemon --max-workers=1 :app:verifyProductionEndpoints

# 该负例应失败，证明 HTTP 不能进入 production
.\gradlew.bat --no-daemon --max-workers=1 :app:verifyProductionEndpoints -POMNIBOT_BASE_URL=http://127.0.0.1:8080

# 固定源码构建 loader32，并准备嵌入运行时
.\gradlew.bat --no-daemon --max-workers=1 :core:main:buildProotLoader32For16Kb :core:main:prepareEmbeddedTerminalRuntime

# Android 定向编译
.\gradlew.bat --no-daemon --max-workers=1 :app:compileDevelopStandardDebugKotlin

# 最终统一阶段才运行 production release；需要安全环境提供正式签名配置。
# 先提交全部改动并建立与 versionName 相同的标签，再从该标签对应的 clean HEAD 运行。
# 直接调用 Gradle 不是正式发布入口，因为它不会执行标签、权限和证书全套门禁。
$env:OMNI_RELEASE_CERT_SHA256 = '<公开的批准发布证书SHA256>'
bash scripts/build-local-release.sh --edition standard --tag v<与versionName完全相同> --version-code <按标签公式得到的整数> --out-dir <standard输出目录> --non-interactive

# Play 候选包使用独立 signed AAB 入口。BUNDLETOOL_JAR 必须是文档固定版本/hash的官方文件。
$env:BUNDLETOOL_JAR = '<bundletool-all-1.18.1.jar绝对路径>'
bash scripts/build-local-release.sh --edition play --bundle --tag v<与versionName完全相同> --version-code <同一次发布对应的整数> --out-dir <play输出目录> --non-interactive

# APK 16KB ZIP 对齐
& "$env:ANDROID_HOME\build-tools\36.0.0\zipalign.exe" -c -P 16 -v 4 <final-release.apk>

# 签名、包名和证书链
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs <final-release.apk>

# ELF 头与 LOAD 段；对最终包中的每一个 .so 重复
& "$env:ANDROID_HOME\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe" -h -lW <library.so>

# 真实设备 page size 和 ABI
adb shell getconf PAGE_SIZE
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.product.cpu.abilist32
```

清数据命令会删除测试设备上的 App 本地数据，只能在已确认的测试包和测试账号上执行：

```powershell
adb shell pm clear cn.com.omnimind.bot.debug
```

## 7. 发布签字条件

只有同时满足以下条件，才可把状态改为“正式版通过”：

- 最终 Android 工作树的 Kotlin/单元测试编译通过；`StorageUsageChannel` 删除能力必须先取得明确授权并按确定范围实现，不得以绕过或空实现换取绿灯。
- 所有 P0 代码项关闭，P1 有负责人和明确版本。
- production release AAB/APK 构建、R8、签名、Manifest、权限和升级路径通过。
- 清数据首装与平台五类模型真实端到端通过，并有配额/抓包证据。
- 最终包全部 ELF 16KB 对齐，16KB Android 设备运行通过，ARM32 guest 有兼容证据。
- Play 权限声明、Data safety、隐私政策、账号删除和开源许可证审核通过。
- 服务端安全、备份恢复、删除传播、监控告警和应急回滚演练完成。

在这些证据齐全前，正确结论应是“Flutter 候选已通过本地回归，但最终 Android 工作树仍有编译阻塞，正式发布继续受 P0 门禁阻塞”，而不是“软件已完全按正式版审核通过”。
