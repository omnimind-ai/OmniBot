# OmniBot 正式版技术隐私与数据安全清单

> 审核快照：2026-08-12
> 状态：正式版上线前工作底稿，存在 P0 阻断项
> 适用范围：OmniBot Android App、`omni-account` 账号服务、官方 AI 网关及相关部署脚本

## 先看结论

当前实现已经具备一些重要基础：账号密码使用 Argon2id 哈希；刷新令牌在服务端只存哈希；Android 账号令牌及多数 Provider/MCP/OpenClaw/Codex 凭据使用 Android Keystore 支持的加密存储；Android 云备份被关闭；云端账号已有 App 内删除和外部网页删除入口。

但**目前不能据此宣称“正式版隐私审核已通过”**。本轮代码已关闭更新统计字段不一致、通用 HTTP 设备指纹 Header、本机 MCP token 弱保护和 OpenClaw 私钥明文四项技术 P0；上线前仍须关闭本文第 11 节其余 P0：决定并处理 Google Play 的 `QUERY_ALL_PACKAGES` 与 `MANAGE_EXTERNAL_STORAGE` 资格、完成真实 Data safety/隐私政策/删除网页配置，并验证删除后备份恢复不会让账号数据重新出现。

本文是**技术数据盘点和上线检查表，不是面向用户的法律隐私政策，也不是法律意见**。公司/运营主体名称、联系邮箱、法定保留期限、数据处理地区、未成年人规则、上游合同角色等，代码无法决定，必须由负责人和法务确认；本文没有代为编造。

本次盘点只查看代码结构与配置模板，**没有读取任何真实 API Key、SMTP 密码、服务器私钥或用户数据**。

## 1. 术语与判断口径

| 标签 | 基础含义 | Google Play Data safety 提醒 |
|---|---|---|
| 【仅本地】 | 数据只在手机或 App 自己的运行环境中处理 | 如果从未离开设备，通常不算 Play 定义的“收集”；一旦作为 AI 上下文、工具结果或网络请求发出，就不再是纯本地 |
| 【收集至官方服务】 | 发往 OmniBot 账号服务、官方网关或官方更新服务 | “收集”是指离开设备，不要求服务端永久保存 |
| 【传给官方上游】 | 官方服务为完成 AI 请求继续传给其配置的模型上游 | 是否在 Play 表单中算“共享”取决于服务提供者/处理者合同角色，不能只靠代码判断 |
| 【传给用户指定第三方】 | 发往用户自己配置的 BYOK、远程 MCP、OpenClaw、Codex Bridge、ACP Agent 或网页 | 仍需在产品内做显著说明；Play 是否适用用户主动发起等例外，应按实际交互与政策人工判断 |
| 【系统/被访问网站】 | Android 系统 Provider、日历提供商或 WebView 中用户访问的网站处理 | App 仍要最小化权限，并说明网站或系统同步可能独立处理数据 |

保护状态说明：

- “Keystore 加密”表示 App 使用 Android Keystore/EncryptedSharedPreferences 一类能力保护本地秘密，不代表接收方服务器也采用相同方式。
- “Android 沙箱”不等于应用层数据库加密。当前 Room、MMKV 和多数工作区文件未发现应用层加密；设备支持并启用系统文件级加密时可获得系统保护。
- “HTTPS”保护传输过程，不代表接收方不保存内容。
- 任何联网接收方都能自然看到请求来源 IP；IP 是否用于日志、风控或地域推断要由真实服务配置确认。

## 2. 数据边界总览

| 边界 | 主要数据 | 谁决定触发 | 当前持久化 |
|---|---|---|---|
| Android 本机 | 对话、消息、Agent 工具记录、工作区文件、记忆、附件、音频、日志、Provider 配置、加密凭据 | 用户使用聊天、Agent、设置或系统分享入口 | Room、MMKV、SharedPreferences、App 私有目录；部分用户文件可在公共存储 |
| 官方账号服务 | 邮箱、密码哈希、验证码哈希、会话哈希、账号状态、AI 模式、额度与用量元数据 | 注册、登录、改密、选择平台/BYOK、平台调用 | 服务端 SQLite 与受限备份；实际磁盘加密状态待确认 |
| 官方 AI 网关 | 用户 JWT、模型/能力、请求内容、附件/音频/图片、计量信息 | 用户选择平台额度并实际调用 | 请求内容会在内存中处理并转给官方上游；未发现业务表主动持久化 prompt/response，生产日志配置仍须复核 |
| 官方模型上游 | 文本、图片、音频、文件派生内容及生成参数 | 平台模式调用 | 当前配置的上游政策、地域、训练/保留选项和合同角色待负责人确认 |
| 用户指定第三方 | BYOK AI、远程 MCP、OpenClaw、Codex Bridge、ACP/本地 Agent 供应商、WebView 网站 | 用户配置、启用并调用 | 由该第三方决定；OmniBot 不能替第三方承诺删除或保留期限 |
| 更新服务/下载源 | 必要的当前应用版本；明确同意后另含随机安装标识，不再发送品牌、型号、OS、SDK 或 Build 指纹 | 首次选择后检查更新；下载更新时访问下载源 | App 本地缓存更新状态与随机 UUID；服务端统计/日志保留待确认 |

## 3. 账号、会话与额度数据

| 数据 | 触发与用途 | 流向/保存位置 | 删除方式 | 加密、哈希或脱敏 | 上线判断 |
|---|---|---|---|---|---|
| 邮箱、规范化邮箱、用户 ID、角色、状态、验证时间 | 注册、登录、找回密码、账号管理 | 【收集至官方服务】`omni-account` 的 `users` 表；邮箱还会交给配置的 SMTP 服务投递验证码 | 账号删除会删除主库用户行；验证码行按邮箱显式删除 | 数据库中为明文业务字段；传输应为 HTTPS；未发现应用层库加密 | 隐私政策须说明账号用途、SMTP 接收方类别、实际存储地区和保留规则 |
| 密码 | 注册、登录、改密、注销前重新验证 | 明文只应在 TLS 请求和校验内存中短暂出现；主库保存 `password_hash` | 改密替换哈希；账号删除删除用户行 | Argon2id 哈希；不存可逆密码 | 保持生产 TLS、禁止请求体日志；定期复核参数强度 |
| 邮箱验证码 | 注册/重置密码 | SMTP 会收到收件邮箱和验证码；账号库保存邮箱、用途、HMAC-SHA256 验证码哈希、过期/使用/失败次数 | 使用/过期后按清理策略；账号删除显式删除该邮箱验证码行 | 主库不存验证码明文；生产不得启用会打印邮箱和验证码的开发发送器 | 必须确认过期验证码清理任务和 SMTP 服务政策 |
| Access JWT | API 鉴权 | 【收集至官方服务】随请求发送；Android 本地保存 | 退出/删号清本地；短期令牌自然过期 | Android `EncryptedSharedPreferences`；服务端通常不落明文 | 日志和错误页不得记录 Authorization |
| Refresh token、会话 ID、设备会话时间 | 刷新、单会话/全会话退出、会话管理 | 本地加密保存；服务端 `sessions` 表只存 SHA-256 refresh token 哈希及时间/撤销信息 | 退出撤销；改密可撤销其他会话；删号通过外键级联删除 | 本地 Keystore 加密；服务端不存 refresh 明文 | 会话列表接口不得返回 token，当前代码按此设计 |
| 平台/BYOK 选择 | 决定请求路由 | 官方账号库只保存模式，不保存 BYOK Key；本地也保存当前模式 | 切换覆盖；删号删除云端偏好；清 App 数据删除本地 | 非秘密配置 | 政策应解释 BYOK 请求不经过官方 AI 网关，但仍可能有账号/更新基础请求 |
| 平台钱包、余额、开关 | 展示与控制平台额度 | 【收集至官方服务】`platform_wallets` | 账号删除级联删除 | 普通数据库字段 | 金额/额度口径和争议处理由产品负责人确认 |
| 用量事件与额度预留 | 防重、扣额、结算与审计 | request ID、user ID、模型、能力、prompt/completion/total tokens、使用/预留额度、实际或超时估算来源、时间 | 账号删除级联删除主库记录；旧备份按备份周期过期 | 未保存 prompt/response 正文；数据库应用层加密未证实 | 明确这是“AI 使用元数据”；确定业务保留期，不能把备份周期冒充法定期限 |
| IP、请求时间、路径、状态 | 网络交付、运维与安全 | 反向代理/系统日志可能保存；进程内限流键会对邮箱/IP 键做 SHA-256 摘要 | 由生产日志轮转与备份策略决定 | 业务 HTTP logger 主要记录方法/路径/耗时；代理访问日志是否脱敏未确认 | P1：确认生产日志字段、访问权限、保留期、删除响应和监控供应商 |

## 4. AI 内容、图片、语音与文件流向

### 4.1 平台额度模式

| 能力 | 实际发送内容与触发条件 | 接收方与保存 | 本地结果/删除 | 保护与未决项 |
|---|---|---|---|---|
| 主文本聊天 | 用户发送时，模型名、system prompt、消息历史、当前文本、Agent 工具定义/结果，以及被纳入上下文的记忆或文件内容 | 【收集至官方服务】官方网关，再【传给官方上游】；账号服务只保存额度/Token 元数据。网关代码路径未发现主动保存正文，但生产日志和上游保留仍待确认 | 消息与 Agent 记录保存在本地 Room；可删对话或清 App 数据 | App 用用户 JWT，不向手机暴露官方上游 Key；官方入口应强制 HTTPS |
| 图片理解 | 用户明确附图，或 Agent 读取图片并把图像/URL/派生结果加入请求时 | 同上；图片内容会离开设备 | 导入/附件副本可能保存在工作区 | 不应描述为“所有相册自动上传”；平台最终 UTF-8 JSON 使用 15 MiB 发送前上限，超限不会联网 |
| 图片生成 | 用户调用图片生成时发送提示词、尺寸/质量等选项 | 【收集至官方服务】官方网关，再【传给官方上游】 | 生成结果下载到本地工作区/附件目录，由存储页或 App 数据清理 | 上游是否保留 prompt/图片、是否用于改进模型必须人工确认 |
| 语音转文字（STT） | 用户录音完成并请求转写，或选择现有音频后，发送音频文件、模型和语言参数；原始音频上限为 24 MiB | 【收集至官方服务】再【传给官方上游】 | App 录音是临时文件，转写后按 `deleteAfter` 删除；用户选中的原文件不删除。录音/选择限制由代码控制 | 麦克风必须即时授权；24 MiB 上限为 multipart 封装留余量；选中现有音频不代表 App 可删除用户原件 |
| 文字转语音（TTS） | 用户播放/自动播放时发送回复文本、声音、风格等 | 【收集至官方服务】再【传给官方上游】 | 生成音频可保存在 `.omnibot/audio`，存储页可清理 | 自动播放也会触发离机发送，设置页和隐私说明应明确 |
| 文件与 Agent 工具 | 只有用户附加、分享导入、Agent 被授权读取或工具执行结果被加入模型上下文时，文件正文/摘要/元数据才发送 | 【收集至官方服务】再【传给官方上游】 | 原文件可能仍在公共存储；App 导入副本在工作区/附件/MCP inbox | 文件路径、日历结果、已安装 App 结果也可能构成敏感上下文；工具调用前告知应与实际范围一致 |

当前产品背景显示官方上游为百炼，但本文不记录内部 URL、令牌或 Key。上游的正式主体、子处理者角色、处理地域、内容保留/训练开关、数据删除接口和合同责任都必须由负责人核对后再写入法律隐私政策。

### 4.2 BYOK 模式

| 能力 | 流向 | 官方是否取得正文 | 凭据与删除 | 必须告知用户 |
|---|---|---|---|---|
| 文本/视觉/图片生成/STT/TTS | 【传给用户指定第三方】App 直接请求用户配置的 Provider | 正常 BYOK AI 请求不经过官方网关；账号服务仍可能接收登录/模式，更新服务仍可能接收更新参数 | API Key、兼容自定义 Header 使用 Keystore 支持的加密存储；删 Provider/清配置应同时删秘密；清 App 数据/卸载删除私有存储 | 第三方会收到内容、IP、模型参数和其 Key；第三方保留、训练、地区、计费由用户与第三方关系决定 |
| 自定义 TTS curl | 【传给用户指定第三方】命令中的目标地址收到文本及用户自定义字段 | 否 | 当前命令整体迁移到 `AppSecretStore`，普通 MMKV 只保留不含命令的配置；不安全凭据端点会失败关闭 | 命令可能内嵌 Header/Token，不能显示到日志或错误上报；只允许 HTTPS，调试回环例外需清楚限制 |
| 任意兼容 HTTP 请求 | 目标由用户配置 | 视目标而定 | 通用客户端已移除设备/Build Header，并在发送前清理旧版 `App-Device-*` / `APP-Other-Info` | 第三方仍会收到用户主动发送的内容、鉴权和网络 IP；不得把此次 Header 修复解释为第三方不处理数据 |

### 4.3 远程工具与网页

- 远程 MCP：用户配置、启用并调用后，工具名、参数、文件/上下文和工具结果会发往【用户指定第三方】；返回值又可能进入 AI 上下文。
- OpenClaw：连接用户指定网关时会发送设备身份公钥/指纹、签名认证信息、消息和工具数据；网关令牌是秘密。
- 远程 Codex Bridge：Bridge URL、工作目录、令牌以及会话/文件操作会发往用户指定 Bridge；Bridge 后面的模型供应商可能继续处理内容。
- 本地 ACP/Codex/Claude Code/OpenCode：App 可把环境变量/配置传给本地子进程；子进程再按自己的配置连接第三方。不能因为进程“本地启动”就把后续网络处理声明为仅本地。
- WebView：普通网页看到访问 IP、Cookie/站点存储和用户在网页内提交的数据。网页只能从合法 HTTPS origin 请求摄像头、麦克风或位置；每个站点的每次请求都先显示中英文接收方与用途，再请求 Android 运行时权限，不保存“始终允许”。摄像头、麦克风或位置数据由该网站处理，不是 OmniBot AI/STT；空资源、未知资源、DRM、异常 origin、请求被替换或页面导航后的旧批准都会失败关闭。

## 5. 本地对话、文件与日志

| 数据 | 保存位置与保护 | 何时可能离机 | 用户删除方式 | 缺口 |
|---|---|---|---|---|
| 对话、标题、摘要、上下文摘要、消息、模型/Token 统计 | Room 数据库；Android 沙箱，未发现应用层数据库加密 | 发起后续模型请求、Agent 总结/记忆或远程工具时，所选历史可能成为上下文 | 删除单个对话；存储页“对话历史”；清 App 数据/卸载 | “对话历史”清理已覆盖消息、会话、Agent 记录、Codex 绑定与本地 Token 用量；仍须在最终包验证附件引用和缓存的实际删除结果 |
| AgentConversationEntry 工具事件 | Room `payloadJson`、状态和摘要 | 工具循环、恢复会话或远程 Agent 时 | 随对话历史清理；清 App 数据 | 工具参数/结果可能含文件、日历、App 清单等敏感内容 |
| 工作区、记忆、offload、附件、分享草稿、浏览器下载/截图、终端数据 | App 私有目录或用户选择的公共存储；大多未应用层加密 | 被用户附加、Agent 读取、MCP/远程 Agent 使用时 | 存储页按类别清理；记忆/用户文件可能需对应功能逐项删；清 App 数据只保证私有目录 | “删除全部本机私人数据”入口尚未实现，等待用户对破坏性范围明确授权；实现前必须明确公共存储文件是否保留 |
| 录音临时文件与 TTS 音频 | `cacheDir/speech_input`、工作区 `.omnibot/audio` | STT/TTS 请求时 | 临时录音成功/失败/取消路径清理；语音音频类别可清；清 App 数据 | 测试进程被杀/崩溃后的陈旧文件清理；用户选中原音频不删除 |
| 已安装 App 名称、包名、图标 | 查询后缓存于内存；图标/路径可进入本地 Room | Agent 调用 App 上下文工具后，匹配结果可能进入官方/BYOK AI 上下文 | 清 App 数据；需提供缓存刷新/删除策略 | Play 将安装清单视为个人和敏感信息；详见第 8 节 |
| Runtime 错误/崩溃日志 | MMKV，`SensitiveDataSanitizer` 对 Bearer/JWT/API Key/token/password/cookie/邮箱等做脱敏；保留最近约 200 条 | 当前未发现自动上传 | 日志 UI 清理或清 App 数据 | Android Logcat 仍可能被调试/系统工具读取；脱敏规则需要测试覆盖 |
| AI 请求诊断日志 | 本地，默认不保存 request/response 正文，仅 URL、模型、方法、状态、大小等；约最近 10 条 | 当前未发现自动上传 | 日志页提供二次确认清理；升级启动同步清除旧版正文，失败则删除整项；清 App 数据也可删 | 已有可见清理入口；继续回归 URL 查询参数和错误文本脱敏 |
| Flutter 调试事件输出 | 生产源码不再直接调用 `print`/`debugPrint`；`SafeLog` 只在 debug 构建接收固定事件枚举及布尔值、计数、字节数，不能接收任意字符串或对象 | 当前未发现自动上传；release 构建的 `SafeLog` 不输出 | `SafeLog` 自身不持久化；debug Logcat 由系统/调试工具管理 | 静态门禁禁止业务源码直接日志和 `dart:developer`；最终 release 仍须复核第三方依赖与原生日志 |
| MCP inbox 文件 | App 私有 `filesDir/mcp_inbox`，有数量/大小和约 2 小时记录 TTL | 当前本机 MCP `file_transfer` 只返回 inbox 元数据，不返回文件正文、下载 URL、token 或 Header；文件内容只有在其他经用户触发的读取/模型路径中才可能离机 | 单项/全部/存储页清理；进程重启时立即删除旧进程遗留；清 App 数据 | 外部客户端短期下载 Header 协议尚未实现，等待用户明确授权；启用前仍须解决凭据不持久化、模型不可见和 LAN HTTP 窃听风险 |

Android Manifest 已设置 `allowBackup=false`，并在旧/新备份规则中排除 root、file、database、sharedpref、external 和 device-protected 各域。因此 App 私有数据不应参加 Android Auto Backup 或设备迁移；发布包仍要用实际 AAB/APK 验证 merged manifest。

## 6. 更新检查、安装标识与设备信息

| 场景 | 发送内容 | 同意门 | 保存/删除 | 当前结论 |
|---|---|---|---|---|
| 基础更新检查 | `currentVersion`、beta 轨道选择、edition、下载源；网络接收方可见 IP | 用户作出“同意/拒绝”决定后都会发送，用于核心更新检查 | 更新状态缓存位于 SharedPreferences；清 App 数据/卸载删除本地缓存；服务端日志保留待确认 | 必须与“可选匿名统计”分开说明，不能让用户误以为拒绝后完全不联网 |
| 可选匿名更新统计 | 随机 UUID `installId`；不含品牌、型号、Android 版本、SDK 或 Build 指纹 | 只有 `GRANTED` 时才惰性生成/读取并附加；`PENDING`/`DECLINED` 不生成、不读取、不发送 | UUID 在 SharedPreferences；清 App 数据/卸载会重置。未发现单独“重置安装标识”按钮 | **已修复：首次同意与关于页文案和实际最小字段一致；仍须用最终 release 包抓包复核** |
| APK 下载 | 下载源收到 IP、请求头及下载信息 | 用户选择下载/安装 | APK 与更新缓存由更新流程清理 | 下载源清单、校验/签名、日志政策需在正式说明中确认 |

随机 `installId` 不是 Android ID，也不是硬件序列号；但它仍是可用于关联多次更新检查的“设备或其他标识符”候选项。是否称为“匿名”需要谨慎，因为与 IP、型号、时间组合后不一定不可关联。

## 7. 凭据与密钥库存

| 凭据/密钥 | 当前保存与传输 | 删除方式 | 审核结论 |
|---|---|---|---|
| 账号 Access/Refresh token | `EncryptedSharedPreferences` + Android Keystore；只发官方账号/网关 | 退出、删号成功后、本地账户清理 | 基础合格；继续保持日志脱敏和失败关闭 |
| BYOK API Key、自定义 Header | `ModelProviderSecretStore` 加密保存；普通 MMKV 只留 Provider 元数据；旧明文迁移后清除 | 删除 Provider、清配置、清 App 数据 | 基础合格；不得回显完整 Key |
| 远程 MCP Bearer token | `RemoteMcpCredentialStore` 加密保存，迁移失败关闭；凭据远端要求 HTTPS（安全调试回环例外） | 设置页显式清除、删除远程 MCP 配置或清 App 数据 | UI/Flutter 通道只接收 `hasBearerToken` 状态，不回传原 token；空白编辑保留旧值，只有显式清除才删除 |
| OpenClaw 网关/设备 token | `AppSecretStore` 加密保存，旧明文迁移失败关闭 | 解绑/清 token/清 App 数据 | 网关 token 基础合格 |
| Codex Remote Bridge token | `AppSecretStore` 加密保存；传给用户配置 Bridge，要求安全传输 | 删除/禁用远程配置、清 App 数据 | 本地保存基础合格；状态接口不应返回原 token，设置页用“写入新值/已配置”模式更安全 |
| ACP Agent 环境变量 | `AppSecretStore` 保存真实值，普通配置留空/元数据；启动时注入子进程 | 删除 Agent Profile/清 App 数据 | 本地保存基础合格；第三方进程的网络和日志行为要单独说明 |
| 自定义 TTS curl | 整条命令可能含秘密，当前已转 `AppSecretStore`，普通 MMKV 持久化时清空命令字段 | 重置语音配置/清 App 数据 | 基础合格；严格禁止写入诊断日志 |
| 本机 LAN MCP Server token | `AppSecretStore`/Android Keystore 支持的安全存储；旧签名派生密文仅作一次性只读迁移，不再接受明文降级 | 刷新 token；清 App 数据 | **已修复：旧明文/弱密文无论迁移成功失败都擦除；安全存储不可用时停服并清除 enabled** |
| OpenClaw Ed25519 设备私钥 | 原始 32 字节私钥进入 `AppSecretStore`；MMKV 只留公开 fingerprint；fingerprint 从安全私钥对应公钥重算 | 清 App 数据；产品内独立“重置设备身份”入口仍待决定 | **私钥明文 P0 已修复：旧 raw key 始终擦除，迁移失败不生成明文回退；身份重置生命周期列 P1** |
| 本地 Codex API Key | 写入本地 Agent 运行环境的 `/root/.codex/auth.json`，目录 700、文件 600；读取状态时只返回 `hasApiKey`，`apiKey` 固定为空 | 配置页输入新值替换；两步确认后同时清除 `config.toml`/`auth.json`；清 App 数据 | 文件权限降低同机读取风险但不是加密；UI 已明确其可能为明文。写入经 stdin、同目录 0600 唯一临时文件和原子 rename，正文不进入命令/日志/返回 payload |
| Claude Code/OpenCode 原始配置 | 固定写入本地运行环境 allowlist 文件；目录 700、文件 600；配置正文可能由用户自行放入 token | 配置页只允许“替换整份文件”；两步确认后清除对应固定文件；清 App 数据 | UI 明确文件可能含明文秘密；状态查询只使用文件元数据并返回 `hasConfig`/`byteCount`/展示路径，不读取或回传正文。Flutter 再做返回字段白名单，诊断和原始错误不附正文 |

### 已修复：通用 HTTP 客户端的隐式设备信息外发

`OkHttpManager` 的通用客户端现在只保留 App 版本、平台和是否 Debug 等低敏元数据；`HeaderInterceptor` 会在发送前移除旧版 `App-Device-*` / `APP-Other-Info`，不再向官方、BYOK 或用户自定义地址附加 manufacturer/brand/product/device/model、fingerprint、host、user 等设备信息。

定向测试已覆盖旧设备头清理与 BYOK Authorization/自定义 Provider Header 保留。最终 release 仍须抓包覆盖平台、BYOK、TTS、下载和 MCP，确认拒绝统计时没有替代通道发送设备信息。

## 8. Android 权限与敏感设备数据

| 权限/能力 | 访问的数据与触发 | 离机条件 | 本地保存/删除 | 正式版要求 |
|---|---|---|---|---|
| `QUERY_ALL_PACKAGES` | App/Agent 查询已安装或可启动应用的标签、包名、图标；Android 没有普通运行时授权弹窗 | 只有 App 上下文工具结果进入官方/BYOK 模型时离机；不应自动上传完整清单 | 图标/包信息可缓存 Room；清 App 数据 | **P0 Play 门槛**：证明“广泛 App 可见性”是核心功能并提交声明，或改用 `<queries>`/Intent 定向可见性。必须做显著披露，不能把“去设置授权”写成不存在的运行时权限 |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Agent 日历工具在调用时读取日历账户、事件 ID/标题/时间/地点/时区/全天状态，或创建/修改/删除事件、描述和提醒 | 工具参数/结果进入 AI 循环时发到平台/BYOK；Android 日历提供商还可能自行云同步 | 工具记录在本地对话；事件由系统日历保存并由系统/日历 App 删除 | 即时权限说明；读取和写入均由本机原生弹窗逐次披露目的地并确认，AI 参数不能代替用户点击 |
| `MANAGE_EXTERNAL_STORAGE`、旧版 `READ_EXTERNAL_STORAGE`、`READ_MEDIA_AUDIO` | Agent 文件工具可广泛读取/管理公共存储；音频选择读取用户指定文件 | 用户选择/Agent 调用后，文件内容或摘要进入 AI、MCP、远程 Agent 时离机 | 公共文件可能在卸载后仍保留；App 副本按工作区清理 | **P0 Play 门槛**：若不符合文件管理/文档管理等核心用途，移除 all-files，改用 Storage Access Framework/MediaStore；若符合则显著披露并提交权限声明 |
| `CAMERA` | 当前明确用途包括扫描 Codex Bridge QR；WebView 网站也可单独请求摄像头 | QR 中 Bridge URL/token/cwd 进入 App 配置；网页获准后由该网站处理画面 | 未发现 QR 帧主动持久化；Bridge token 加密保存；网页授权不持久化 | 即时授权；把“扫描配对码”和“网页摄像头”分别说明；网页仅限合法 HTTPS origin，按站点、按请求确认 |
| `RECORD_AUDIO` | 用户录音转写；WebView 网站可单独请求麦克风 | STT 时发平台/官方上游或 BYOK；网页获准后发给站点 | App 临时录音转写后删除；TTS 输出另存；网页授权不持久化 | 即时授权、录音状态可见；自动/后台录音必须禁止；网页仅限合法 HTTPS origin，按站点、按请求确认 |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | 只有 WebView 中当前网站明确请求定位且用户在 App 披露页和 Android 系统权限页依次同意时访问 | 获准后位置信息由当前网站处理，不发给 OmniBot AI；网站自身的后续传输/保存由其政策决定 | OmniBot 不保存网站定位授权，也不提供“始终允许”；Android 系统权限可在系统设置撤销 | 仅接受合法 HTTPS origin；每站点、每请求显示中英文接收方和用途；拒绝、替换、取消或导航后旧授权失效 |
| Shizuku/高权限动作 | 理论上可读取诊断、设置、包信息或执行设备/shell/会话动作；当前所有动作因缺少可信本机一次性确认而失败关闭 | 当前不会因 Agent/模型请求执行高权限动作；模型传入 `confirmed`、`confirmationToken` 等字段会被忽略/剔除 | 当前不产生高权限执行结果；既有本地会话/日志仍按对应存储类别清理 | 生物识别/锁屏一次性确认桥等待用户明确授权；若实现，每个动作和任意 shell/高权限会话命令都须系统认证，不提供“始终允许”，认证失败即拒绝 |
| 设备/Build 信息读取 | 品牌、型号、OS、SDK、Build 指纹等大多无需危险权限即可读取 | 本轮已从更新统计和通用 HTTP Header 删除；正常网络接收方仍可见 IP | 更新 UUID/状态在 SharedPreferences | 定向源码/测试已证明旧字段删除；最终 release 仍须抓包证明没有第三方 SDK 或替代通道外发 |
| 分享/打开文件入口 | 导出的 `McpFileReceiverActivity` 接收 SEND/SEND_MULTIPLE/content VIEW | 仅当用户从系统分享/打开并继续使用，副本或文本才可能进入 AI/MCP | 草稿、工作区或 MCP inbox；按对应类别删除 | 校验 URI、类型、数量/大小；不把“收到分享 Intent”等同于用户已经同意上传 |

Manifest 还声明通知、悬浮窗、电池优化豁免、前台服务、安装 APK、精确闹钟、开机启动等权限/特殊访问。它们未必直接产生本文要求的数据类型，但正式上架仍应遵循“实际功能需要才声明”、按需提示和 Play 专项政策；未使用权限应从 release manifest 移除。

## 9. 备份、删除与外部删除网页

### 9.1 本地删除

- 退出账号：清除本地账号 token/会话状态，不等于删除云端账号，也不等于删除本地对话和文件。
- 删除云端账号：App 在服务器删除成功后清本地账号 token/模式，但当前设计明确保留本地对话、工作区、Provider 配置等。
- 删除对话/存储页清理：只覆盖选定类别，不等于完整擦除；当前“对话历史”会在同一事务删除消息、会话、Agent 历史、Codex 线程绑定与本地 Token 用量。
- 完整本地清除：Android“清除存储”或卸载可删除 App 私有数据；用户主动写入公共存储的文件可能仍保留。
- “删除云端账号后，同时删除此设备上的全部 OmniBot 私有数据”入口当前未实现，等待用户对破坏性范围明确授权；如获授权，执行前必须逐项列出私有目录与公共文件的删除/保留范围，并提供不可逆操作确认。

### 9.2 云端账号删除

- App 内已有经过当前密码确认的删除流程。
- 外部网页代码入口为 `/account-deletion`；正式 Play Console 可配置为 `https://account.omnimind.com.cn/account-deletion`，但提交前必须人工验证公网可访问、HTTPS、页面中应用名称与商店名称一致、无需重新安装 App、可完成身份确认和删除。
- 网页实现将邮箱/密码/JWT 只保留在页面 JavaScript 内存，不写 Cookie、localStorage、URL，且未加载第三方脚本；应继续保持 `no-store` 和严格 CSP。
- 主库删除会移除用户，并通过外键/显式逻辑删除会话、偏好、钱包、用量/预留和验证码记录。
- 网页当前写“受限灾备中旧副本最长 30 天自动删除”；部署模板也把 `BACKUP_RETENTION_DAYS` 示例设为 30 且校验上限。**这只是当前产品/部署承诺，不是本文认定的法定期限**。上线前要核对生产实际值、定时任务、失败告警和历史备份，不得只看模板。

### 9.3 服务端备份风险

- 备份脚本会归档账号与网关 SQLite、做校验并限制目录权限；代码层未证明备份归档本身加密，需确认主机磁盘/对象存储加密、密钥管理、最小权限和恢复审计。
- 账号从主库删除后，旧滚动备份只能随周期过期；如果在周期内恢复旧备份，可能让已删除账号重新出现。当前未发现删除 tombstone/恢复后重删清单。
- **P0：在正式承诺账号删除前，建立并演练“恢复后删除对账”机制**，或采用不可逆删除 tombstone/等效方案，记录恢复演练证据。

## 10. Google Play Data safety 与政策映射

Google 明确要求开发者对 App 及其 SDK 的离机数据处理负责；“仅本地访问”通常不算收集，但本 App 的文件、日历、App 清单和对话可能在 AI/工具路径中离机，所以不能笼统填写“未收集数据”。最终答案必须在 Play Console 由负责人按真实 release 包、真实上游合同和真实服务配置提交。

### 10.1 建议逐项评估的 Play 数据类型

| Play 候选类型 | 本项目对应数据 | 必填判断提示 |
|---|---|---|
| 个人信息 / Email address、User IDs | 注册邮箱、账号 ID | 账号功能必需；说明是否可选、用途、删除 |
| 消息 / Other in-app messages | 用户 prompt、回复、对话/Agent 上下文 | 平台 AI 会离机；BYOK/远程工具也会离机 |
| 照片和视频 | 用户附加图片、视觉输入、生成图片 | 仅在用户选择/调用时，但属于可离机内容 |
| 音频文件 / Voice or sound recordings | STT 录音/选中音频、TTS 输入文本与输出音频 | 区分上传的录音与仅本地生成结果 |
| 文件和文档 | 用户附加/分享/Agent 读取的文件 | 只有被选择/读取并进入请求时收集；all-files 权限本身仍需显著披露 |
| 日历 | 日历账户、事件及写入内容 | 工具调用会让结果进入 AI；不要只按“系统权限”漏报 |
| App activity / Installed apps | 已安装 App 标签、包名、匹配结果 | Play 明确把 App inventory 视为个人和敏感信息 |
| App interactions / Other user-generated content | AI 使用、工具操作、工作区内容 | 根据 Play 当期分类和实际目的选择 |
| Device or other IDs | 随机安装 UUID；可能还包括 Bridge/OpenClaw 设备指纹 | UUID 虽非硬件 ID，仍可跨请求关联；按最终真实用途、接收方和保留期填写 |
| App info and performance / Diagnostics | 版本、edition、更新参数、本地诊断日志（若将来上传） | 当前未发现自动上传本地日志；基础更新请求仍离机 |
| Approximate location | IP 可能被接收方用于粗略位置 | 是否按 Play 规则申报要依据服务端实际用途，不可凭代码臆测 |

每一种最终声明都要人工回答：是否收集、是否共享、是否必需/可选、用途、是否临时处理、传输是否加密、是否可请求删除。对“官方上游”和“用户指定第三方”是否属于 Play 的 sharing 例外，必须结合显著告知、用户动作及合同角色确认，本文不代替该法律/政策判断。

### 10.2 官方链接

- [Google Play：Data safety 表单说明](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Google Play：App 账号删除要求](https://support.google.com/googleplay/android-developer/answer/13327111)
- [Google Play：`QUERY_ALL_PACKAGES` 广泛 App 可见性政策](https://support.google.com/googleplay/android-developer/answer/10158779)
- [Google Play：`MANAGE_EXTERNAL_STORAGE` All files access 政策](https://support.google.com/googleplay/android-developer/answer/10467955)
- [Android Developers：声明包可见性需求](https://developer.android.com/training/package-visibility/declaring)
- [Android Developers：All files access 技术说明](https://developer.android.com/training/data-storage/manage-all-files)

### 10.3 Google Play 与直接 APK 分发的差异

| 发布渠道 | 该渠道特有的上线门槛 | 两个渠道都不能绕过的事项 |
|---|---|---|
| Google Play | 准确提交 Data safety；配置可用的外部删号网页；对 `QUERY_ALL_PACKAGES`、`MANAGE_EXTERNAL_STORAGE` 等受限权限满足资格、显著披露并提交声明，否则移除权限 | 真实隐私政策、更新统计同意、最小化设备 Header、凭据安全、AI/第三方流向说明、账号与本地删除、备份恢复对账 |
| 官网/企业渠道/其他直接 APK 分发 | Play 表单与 Play 权限批准本身不适用，但要验证下载页身份、HTTPS、APK 签名与升级兼容、完整性校验、更新源、`REQUEST_INSTALL_PACKAGES` 的必要性和清晰安装提示；目标地区或渠道另有规则时由负责人确认 | 同左。直接分发**不会**让广泛 App 清单、全盘文件访问、相机、麦克风、日历或隐式设备信息外发变得不敏感，也不能用“非 Play”替代显著告知和用户控制 |

因此可以把 `QUERY_ALL_PACKAGES`/`MANAGE_EXTERNAL_STORAGE` 的“Play 批准或移除”视为 Play 专属 P0；但它们的最小化、显著披露、运行时控制、AI 外发范围和安全测试仍是直接分发 P0/P1。直接包发布还应把签名证书托管、密钥轮换、旧版本升级、更新回滚和下载源可用性纳入独立发布审核，本文不记录任何实际签名密钥。

## 11. 上线阻断项与改进优先级

### P0：未关闭前不应按正式版发布或提交“已完全合规”声明

- [x] **减少更新统计实际字段并对齐同意文案**：只保留 `currentVersion` 与明确同意后的随机安装 ID；`PENDING`/`DECLINED` 不生成、读取或发送 ID。最终 release 抓包仍是发布门禁。
- [x] **移除通用 HTTP 设备指纹 Header**：已删除 Build fingerprint/host/user/board/型号等并清理旧 Header；最终 release 仍须抓包覆盖平台、BYOK、TTS、下载、MCP。
- [x] **迁移本机 MCP token**：已迁到 Keystore/AppSecretStore，成功/失败都擦除旧明文/弱密文，失败关闭并停服。
- [x] **保护 OpenClaw Ed25519 私钥**：已迁到 Keystore/AppSecretStore，擦除 MMKV 原始私钥且不再明文降级；产品内身份重置入口转列 P1。
- [ ] **决定 `QUERY_ALL_PACKAGES`**：满足 Play 核心用途并提交真实权限声明/显著披露，或从 release manifest 移除并改为窄化查询。
- [ ] **决定 `MANAGE_EXTERNAL_STORAGE`**：满足受限用途并提交声明，或改用 SAF/MediaStore；必须用最终商店定位证明其确为核心功能。
- [ ] **完成正式法律文本与 Play 配置**：由负责人填写真实运营主体、联系方式、处理地区、各类数据用途/保留、未成年人规则、第三方类别；发布可访问隐私政策；提交准确 Data safety；配置并实测外部删除 URL。
- [ ] **确认官方 AI 上游治理**：处理者/第三方角色、内容留存/训练、地域、子处理者、删除和安全条款必须有负责人证据，不能只写“百炼”即视为完成。
- [ ] **验证账号删除全链路**：主库、网关副本/日志、SMTP 相关记录、备份过期、恢复后重删均有测试和负责人签字；核对网页“最长 30 天”与生产实际一致。
- [ ] **确认生产静态/备份加密与权限**：数据库和备份归档的实际磁盘/对象存储加密、最小权限、审计、密钥轮换和恢复演练有证据。

### P1：建议首个正式版本前完成，至少要形成有负责人和日期的接受记录

- [ ] 在取得用户对破坏性范围的明确授权后，提供统一的“删除全部本机私人数据”能力；明确云端删号不自动删除本地数据以及公共文件可能保留。
- [x] 补齐“清除对话历史”的关联表/本地 Token 用量/Agent 会话与索引清理，避免残留和孤儿记录。
- [x] 为 AI 请求诊断日志增加可见的二次确认清理入口；正文默认不落盘并持续测试 Authorization、Cookie、邮箱、URL 查询参数脱敏。
- [ ] 确认 Nginx/CDN/更新 Worker/监控/SMTP 的 IP、UA、错误日志字段和实际保留期；记录供应商访问权限。
- [x] MCP inbox 在进程重启时立即清理旧文件；当前 `file_transfer` 仅返回文件 ID、名称、MIME、大小和接收时间，不返回正文、下载 URL、token 或 Header。
- [ ] 外部客户端短期下载 Header 协议尚未实现，等待用户明确授权；若获授权，必须使用一次性短期凭据、不进入 URL/模型正文/持久化状态，并继续剥离远程 MCP `_meta`。
- [ ] 明确本机 LAN MCP 使用 HTTP 时的局域网窃听风险，并决定是否提供 TLS/仅回环模式。
- [x] 日历读取、创建、修改、删除均由本机原生弹窗逐次确认，提示请求/结果可能进入当前 AI Provider 上下文；模型传入 `confirmed` 不构成授权。
- [ ] Shizuku/高权限能力当前全部失败关闭；生物识别/锁屏一次性确认桥等待用户明确授权。若获授权，每个动作以及任意 shell/高权限会话命令都须系统认证，不提供“始终允许”，认证失败即拒绝。
- [ ] 在 Android 16 使用 `RESTRICT_LOCAL_NETWORK` 兼容开关回归本机/LAN MCP 的拒绝、授权与恢复；不要在 production 提前申请 Android 17 的 `ACCESS_LOCAL_NETWORK`。
- [x] 为本地 Codex、Claude Code、OpenCode 提供状态-only 查看、replace-only 输入和两步清除；明确明文文件风险，固定 700/600 权限和路径 allowlist，正文/Token 不进入 Flutter 返回、shell argv、日志或诊断。App `allowBackup=false`；最终候选包仍须在真实设备复核清除、会话断开和备份恢复。
- [ ] 为 OpenClaw 提供明确的“重置设备身份”入口，并说明重置后可能需要重新配对。
- [ ] 对 Room、MMKV、工作区文件做威胁建模；高敏场景决定是否增加应用层加密、锁屏绑定或导出前确认。
- [ ] 每个 Provider、远程 MCP/OpenClaw/Codex/WebView 权限页显示“数据发给谁、哪些内容、如何停止和删除”。
- [ ] 用最终签名 release AAB 做 merged manifest、权限、备份、Network Security Config、导出组件和第三方 SDK 数据流复核。

## 12. 上线前人工决策记录模板

以下项目不能由代码作者替法务/负责人作答。每项至少记录“结论、负责人、证据链接、确认日期、下次复核日期”。

| 决策 | 待确认问题 | 当前证据状态 |
|---|---|---|
| 运营与法律主体 | 正式主体名称、公开联系渠道、适用地区、未成年人和投诉路径是什么？ | 未在本文填写 |
| 官方 AI 上游 | 谁是处理者/独立第三方？处理地域、保留/训练、删除、子处理者与安全承诺是什么？ | 代码只证明技术流向，不证明合同 |
| SMTP/CDN/更新/监控 | 接收哪些数据、用途、日志、地域、保留和删除能力是什么？ | 仅识别技术接口，生产供应商和政策待确认 |
| 数据保留 | 账号、用量、日志、备份分别保留多久，依据是什么？ | 只看到备份模板上限与网页承诺，不能推导其他期限 |
| Play restricted permissions | App 的核心定位能否满足两项受限权限？替代方案为何不足？ | 待产品与 Play 负责人决定 |
| Data safety sharing | 官方上游与用户指定第三方分别如何在 Play 当期规则下申报？ | 待合同/交互/政策联合判断 |
| 删除与恢复 | 如何保证恢复备份后不会复活已删除账号？哪些第三方要另行删除？ | 当前缺恢复后对账证据 |
| 静态加密 | 生产磁盘、备份、对象存储、密钥轮换与访问审计是否满足要求？ | App 代码不能证明服务器基础设施状态 |

## 13. 代码证据索引

本节用于复核事实，不表示每个文件都已经通过安全审核。

| 事实 | 主要代码/配置证据 |
|---|---|
| Manifest 权限、备份与导出组件 | `app/src/main/AndroidManifest.xml`；`app/src/main/res/xml/backup_rules.xml`；`app/src/main/res/xml/data_extraction_rules.xml` |
| 网络明文基础策略 | `app/src/main/res/xml/network_security_config.xml` |
| 更新同意、字段与安装 UUID | `app/src/main/java/cn/com/omnimind/bot/update/PrivacyConsentStore.kt`；`AppUpdateManager.kt`；`ui/lib/l10n/app_zh.arb` |
| 通用 HTTP 设备 Header | `baselib/src/main/java/cn/com/omnimind/baselib/http/OkHttpManager.kt`；`http/interceptor/HeaderInterceptor.kt` |
| Flutter 生产日志门禁 | `ui/lib/core/safe_log.dart`；`ui/test/core/safe_log_test.dart` |
| 账号 token 与 Provider 秘密 | `baselib/src/main/java/cn/com/omnimind/baselib/account/AccountTokenStore.kt`；`llm/ModelProviderSecretStore.kt`；`llm/ModelProviderConfigStore.kt` |
| 通用安全秘密存储 | `baselib/src/main/java/cn/com/omnimind/baselib/util/AppSecretStore.kt` |
| 远程 MCP 凭据 | `app/src/main/java/cn/com/omnimind/bot/mcp/RemoteMcpCredentialStore.kt`；相关 Remote MCP 配置 Store/Channel |
| 本机 MCP token、文件 inbox 与元数据边界 | `app/src/main/java/cn/com/omnimind/bot/mcp/McpServerManager.kt`；`McpFileInbox.kt`；`McpToolExecutors.kt`；`RemoteMcpClient.kt` |
| Shizuku/高权限失败关闭 | `baselib/src/main/java/cn/com/omnimind/baselib/shizuku/PrivilegedActionPolicy.kt`；`PrivilegedCommandExecutor.kt`；`app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/PrivilegedToolHandler.kt` |
| OpenClaw 令牌与设备私钥 | `assists/src/main/java/cn/com/omnimind/assists/openclaw/OpenClawTokenStore.kt`；`OpenClawDeviceIdentity.kt` |
| Codex/ACP 凭据与运行配置 | `app/src/main/java/cn/com/omnimind/bot/agent/runtime/CodexRemoteBridgeConfigStore.kt`；`AcpAgentProfileStore.kt`；`AgentRuntimeManager.kt` |
| 自定义 TTS 秘密与音频 | `baselib/src/main/java/cn/com/omnimind/baselib/llm/SceneVoiceConfigStore.kt`；`app/src/main/java/cn/com/omnimind/bot/voice/SpeechRecorder.kt`；`app/src/main/java/cn/com/omnimind/bot/ui/channel/SpeechTranscriptionChannel.kt`；`app/src/main/java/cn/com/omnimind/bot/voice/SceneVoicePlaybackManager.kt` |
| 本地对话与用量 | `baselib/src/main/java/cn/com/omnimind/baselib/database/Conversation.kt`；`Message.kt`；`AgentConversationEntry.kt`；`TokenUsageRecord.kt` |
| 本地日志与清理 | `baselib/src/main/java/cn/com/omnimind/baselib/util/OmniLog.kt`；`RuntimeLogStore.kt`；`AiRequestLogStore.kt`；`app/src/main/java/cn/com/omnimind/bot/ui/channel/StorageUsageChannel.kt` |
| 已安装 App 清单 | `app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt`；Agent runtime context/app query 工具；`baselib/.../database/AppIcons.kt` |
| 日历 | `app/src/main/java/cn/com/omnimind/bot/agent/tool/calendar/AgentCalendarToolService.kt` |
| 账号表与哈希 | `omni-account/internal/database/migrations/001_initial.sql` 至 `007_platform_usage_settlement_source.sql`；`internal/password/password.go`；`internal/token/token.go`；`internal/verification/verification.go` |
| 账号删除 | `omni-account/internal/database/account_lifecycle.go`；`internal/auth/auth.go`；`internal/httpapi/account_deletion.go`；`account_deletion.html` |
| 备份周期与脚本 | `omni-platform-deploy/env/production.env.example`；`scripts/linux/backup-systemd-sqlite.sh`；生产配置校验脚本 |

## 14. 每次正式发布的最小复核

- [ ] 从干净设备首次启动，分别选择同意/拒绝统计，抓包对比全部请求字段。
- [ ] 平台文本、图片、STT、TTS、文件、日历和 App 工具各做一次，记录实际接收域名与 payload 类别，不保存真实用户内容。
- [ ] BYOK、远程 MCP、OpenClaw、Codex Bridge 各做一次，确认只发用户看到并同意的内容，且秘密不出现在日志/UI 状态。
- [ ] 删除单对话、清各存储类别、退出、删号、清 App 数据、卸载分别验证“删了什么、没删什么”。
- [ ] 执行一次账号删除后的备份恢复演练，证明已删除账号不会恢复可用。
- [ ] 用 release 包扫描危险/特殊权限、导出组件、备份规则、HTTP 明文策略、SDK 清单和硬编码端点。
- [ ] 将实测结果与隐私政策、App 内显著披露、Play Data safety、权限声明和外部删除网页逐字对照；任何一处行为变化都重新提交/更新声明。
