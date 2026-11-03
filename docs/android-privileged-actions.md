# Android 高权限操作

## 启用与后端

“设置 → 应用权限授权 → 扩展能力 → Root 高级能力”默认关闭。关闭时应用不会为这项能力运行 `su` 或申请 Root。用户主动开启或重试后执行有超时的 UID 校验；只有 `uid=0` 才视为已授权。已启用且此前成功授权的安装在进程恢复时允许校验一次，拒绝或超时后必须手动重试。该授权配置不参与系统云备份或设备迁移。

工具开始前优先选择已启用且授权有效的直接 Root，否则使用已授权的 Shizuku。Root 开关控制直接 Root 后端，不撤销 Shizuku/Sui 的独立授权。确认请求显示本次执行后端、动作和参数；确认后再检查授权是否仍有效。命令开始执行后不切换后端，不自动重放失败的操作。

关闭直接 Root 会阻止新操作、使尚未执行的确认失效，并停止其正在运行的进程和持久 shell。Root 管理器中的应用授权仍由该管理器管理。

## 工具接口

沿用 `android_privileged_action` 和 `android_privileged_session_start/exec/read/stop`。结果的 `backend` 继续表示 `ROOT`、`ADB` 或 `NONE` 权限级别，`provider` 表示 `ROOT`、`SHIZUKU` 或 `NONE` 执行来源。

`android_privileged_action` 使用 `action` 和 `arguments`：

| 动作 | 参数 |
| --- | --- |
| `device_control.get_state` | `target` |
| `device_control.set_state` | `target`、`enabled` |
| `device_control.get_display` / `device_control.get_sound` | 无 |
| `device_control.set_brightness` | `percent`，0–100，对应系统归一化亮度接口 |
| `device_control.set_screen_timeout` | `seconds`，15–1800 |
| `device_control.set_volume` | `stream`、`percent`，0–100 |
| `device_control.set_do_not_disturb` | `mode`：`off` / `priority` / `silent` / `alarms` |
| `settings_control.get` / `settings_control.put` | `namespace`、`key`；写入另需 `value` |
| `shell.exec` | `command`；可选 `timeoutSeconds`、`workingDirectory`、`environment` |

系统开关 `target` 包含 `wifi`、`bluetooth`、`mobile_data`、`airplane_mode`、`location`、`nfc`、`battery_saver`、`auto_rotate`、`auto_brightness`、`dark_mode`。移动数据修改仍要求 Root 权限级别。音量 `stream` 支持 `media`、`alarm`、`ring`、`notification`、`voice_call`、`system`。

保留原有 Wi-Fi/移动数据动作别名以及应用启动/停止、权限授予/撤销、AppOps、设备输入和诊断动作。普通 Android API 能完成的查询和音量操作在应用进程中执行；需要特权的修改使用所选后端中的固定系统命令。没有开放任意 Android API 反射执行入口。

状态修改、raw shell 和持久 shell 命令必须收到 ACP 客户端的真实确认。模型提供的 `confirmed` 不授予任何权限。查询和停止已有 shell 不再次申请确认；原有高风险 shell 命令拒绝规则继续有效。

## 状态与生命周期

结构化系统开关、显示、声音与设置修改结果在 `observation` 中报告 `before`、`after`、`expected`、`commandAccepted`、`verified`、`changed`、`userId` 和 `scope`。系统命令成功返回只表示请求被受理；读回未达到目标时返回 `state_change_unconfirmed`，应先查询状态，不直接重试。异步开关只在有界窗口内轮询状态，不重复发送修改。

`system`、`secure` 设置使用应用所在 Android 用户；`global` 设置报告设备级范围。硬件不存在、命令不受支持、读取权限不足或 ROM 输出无法可靠识别时返回明确失败，不猜测设备状态。亮度要求可读的归一化系统接口；不假定所有 ROM 使用相同的整数亮度范围。

系统命令能力探测只读取 `cmd <service> help` 的命令声明，同时检查 stdout 和 stderr。[AOSP 的默认帮助处理](https://android.googlesource.com/platform/frameworks/libs/modules-utils/+/refs/heads/main/java/com/android/modules/utils/BasicShellCommandHandler.java#304) 打印正常帮助后仍返回 `-1`，进程退出码表现为 `255`；这一约定仅用于只读帮助探测，实际状态修改仍须成功返回并读回验证。授权失败、超时和服务错误保留原始错误；输出被截断且未找到目标声明时报告探测不完整，不据此认定设备不支持。

持久 shell 同时绑定 Conversation 和 ACP Session，并固定使用创建时的执行后端。不同对话、不同 ACP Session 或新授权代次不能接管旧 shell。取消执行会结束相应命令；取消持久 shell 命令会关闭该 shell。官方 ACP session close/delete 的资源清理路径释放对应 shell，工具进度与结果仍由现有 ACP reducer 投影。

直接 Root 子进程通过 UID 握手确认身份。输出在读取过程中限量缓存并持续排空；持久会话保留有截断标记的尾部记录。Root 子进程组由监督进程观察本地租约和宿主进程存活状态，撤销、超时或取消时清理该进程组。

## 验证

定向测试覆盖默认零探测、显式启用、拒绝/超时后的手动重试、迟到授权、取消和输出限量、后端优先级、Conversation/ACP Session 隔离、逐次确认及状态读回。

设备验收需要分别覆盖无 Root、仅 Shizuku、仅直接 Root、两者共存，并验证所用 Android 版本和 ROM 的系统服务命令。自动化 JVM/Flutter 测试不能替代真实 Root 管理器授权、SELinux 和厂商系统行为的验证。

可构建 `:baselib:assembleDebugAndroidTest` 并运行 `cn.com.omnimind.baselib.privileged.AndroidShellCommandCompatibilityTest`，在支持 UiAutomation Rwe 的设备上检查真实系统帮助。该独立测试包默认只读；传入 instrumentation 参数 `omnibot.verifyDarkModeRoundTrip=true` 后，才会通过生产控制器切换一次系统深色模式并在 `finally` 中恢复原状态。原配置为自动或自定义模式时跳过修改，不覆盖其策略。
