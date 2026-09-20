# Remote Codex 排队请求与断线 — 2026-09-19

在既有 RemoteCodexAppServerSession 发现两个边界：超时仅覆盖响应等待，未覆盖 writeMutex 排队；排队期间断线后写入前不再检查连接身份。

新增两项确定性协程用例在旧实现失败（14项中2失败）：排队 prompt 在 disconnect 后仍调用旧连接 writeLine；20ms请求期限在写锁等待期间不起作用，外部500ms观察超时。

修复：请求期限覆盖写锁等待/发送/响应，发送前在同一锁内核对原连接身份与运行状态。尚未开始写入的请求超时不发 cancel_request。已经尝试发送的请求取消仍用官方通知，固定原连接，并限时1秒尽力发送，避免取消流程再次被写锁卡住。不重放 prompt，不新增会话或重试 owner。

修复后 RemoteCodexAppServerSessionTest 14项通过，Debug APK构建成功。保留既有 v2 订阅恢复、不重放丢失确认的 prompt、迟到事件隔离和主动断开停止恢复用例。

本轮用户更新验收要求：在模拟器上执行真实任务；后续按该指示执行，不用未连接真机阻止模拟器验收。当前启动 OobUbuntuFresh20260916 / emulator-5580（Android13 ARM64），保留数据覆盖安装成功。真实网络中断与恢复用户流程尚未运行，不能把单测当设备验收。

```
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests '*RemoteCodexAppServerSessionTest' :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
```

## 2026-09-19 当前服务预检

launchd 的 cn.omnimind.codex-shared-bridge-lan 进程实际运行。使用仓库外既有私有配置与 token 文件，通过 scripts/verify-bridge-ingress.cjs 实际验证 loopback 路由：health、文件列表、有效鉴权与拒绝无效鉴权、ACP v2 initialize 全通过，initialize 939.35ms，ping中位66.02ms。没有发送模型任务。此为本机入口预检，不是公网、手机或断线恢复验收。

verify-remote-chat-device.py 现允许显式 emulator-N 目标；物理设备仍需 OOB_ALLOW_PHYSICAL_DEVICE=1。复用同一真实输入/回复断言，不把模拟器标成真机。Python编译检查通过；远程UI任务需等待当前Life XP Journey释放模拟器。

## 预检归属纠正与模拟器连接恢复

后续检查发现127.0.0.1:17337是既有SSH转发，不能用该预检证明launchd的LAN监听正常。电脑已换网，原192.168.3.177绑定失效；实际LAN HTTP探测超时。toybox nc空输入退出0也不足以证明可达，旧TCP结论不作验收证据。

为测试启动独立127.0.0.1:17339 Bridge，复用原共享Codex daemon、现有私有token文件与demo工作目录，不修改旧服务。通过真实模拟器设置界面保存ws://10.0.2.2:17339/codex，会话列表实际显示Ready/connected。认证HTTP文件入口、拒绝无效凭据、ACP v2 initialize预检通过；不是公网验收。

发现后台启动日志输出完整token和含token配对二维码。server.mjs默认对非交互/重定向输出隐藏配对凭据；保留交互终端显示及显式--show-pairing。connection-url.test.mjs启动实际子进程，用合成token验证默认日志不含凭据，以及显式配对仍包含正确URL/token/cwd；npm test共5项通过。实际测试Bridge重启输出确认凭据隐藏。私有凭据不写入测试仓库。模拟器真实模型文件任务已从新建会话发送一次，等待结果。

第一条实际任务通过：模拟器新建远程聊天，真实输入并发送创建文件请求；电脑demo目录recovery-20260919.txt内容独立核对正确，手机唯一可见回复通过。证据artifacts/remote-recovery-20260919/real-task.json、real-file.json、real-task.png。

停止仅属于本次测试的17339 Bridge并重新启动，保留同一手机进程/页面。第二条任务准备时发现长文本ADB一次输入被截断，发送前精确断言拦截，没有发送；清理草稿后的第一次尝试也因残余草稿被拦截，未发送。修复驱动逐字符输入后继续，失败证据保留after-restart-task.json及after-restart-task-retry.json。恢复任务结果尚待，不把未发送归为模型失败。

verify-phone-remote-config.py不再用空输入nc退出码证明可达；ws要求设备实际收到HTTP状态行，当前模拟器路由通过；wss明确返回未验证，不能用裸TCP验证TLS。此预检仍不证明鉴权/ACP/恢复。

恢复后的第二条真实任务通过：原聊天页面未刷新/重开，手机进程ID保持不变；模型读取第一条文件并创建第二条指定文件，独立磁盘核对正确，手机可见唯一B回复。见after-restart-task-character-input.json、after-restart-file.json及after-restart-ui.png。范围仅空闲期间Bridge进程中断后下一用户请求成功；仍未覆盖活跃prompt中断、后端turn身份/重复项精确核对、公网或电脑休眠。

## 执行中断线：同一任务继续和手机自动恢复通过

脚本verify-shared-interruption.cjs扩展支持私有用户所属Unix socket及明确本仓库127.0.0.1:17339测试Bridge；保留端口/进程/工作目录限制。先只读确认fresh marker，再从手机原聊天实际发送C任务。后端状态inProgress时停止测试Bridge，观察相同turnId 01a0b6c5-b099-7192-9c02-82db6790e6d9最终completed；未发送任何替代prompt。

恢复测试Bridge后，原手机页面自动显示C结果，进程未变，电脑active-20260919.txt内容正确。读取权威thread确认同一session共3个completed turn，各1个userMessage和1个对应最终marker，没有重放；backend-three-turns.json、active-outage-identity.json保存证据。UI原观察进程一次uiautomator退出137失败，原因未定，失败保留active-outage-ui.json；后续仅被动读取通过active-outage-passive-ui.json，未重发。观察脚本增加有界只读重试并删除旧XML避免误用快照。

这证明模拟器本机链路在Bridge进程中断期间保留实际任务并恢复显示；不证明公网TLS、NAT打洞、电脑休眠或最终Release包。当前仍为0.6.3(16) Debug。

## Remote开启后本地会话不落盘的事件归属问题

实际顺序：Remote三轮验收→同进程打开本地Life XP conversation31→真实本地工具执行。用户消息已保存，后续工具/回复未保存；最终压缩等待工具历史超时，canonical ACP错误已保留。源码确认_handleAgentRuntimeEvent仅依据全局remoteEnabled把事件走远程分支，_ensureRemoteCodexRuntimeForThread又复用已存在的本地Conversation id并标成ephemeral，造成后续持久化跳过。

修复在既有事件入口按explicitConversationId或已绑定session/turn查询到的Conversation身份判定：正id为持久本地会话，Remote配置开启不能把它转为远程临时投影；负id/尚未绑定远程事件保持原分支。不增加新生命周期/重试/压缩算法。remote_event_ownership_test与既有coordinator共117项通过。正在构建，待Remote开启→本地真实工具→失败恢复→重启历史验收。

事件归属修复Debug构建成功48秒并覆盖安装；当前APK信息保存file-edit-recovery-20260919/installed.json。Remote配置核对保持enabled=true。首次新建聊天仍采用Remote Harness，模型明确没有本地file_*工具；reply标记出现但正式工具/历史断言拒绝，未计通过。证据journey/result.json。改为显式已有conversation31/xiaowan-acp本地会话发起新的验收任务，待结果。

已有小万conversation31任务实际开始后，在线SQLite备份确认本轮assistant_message、tool_event、ui_card在运行期间持续写入（id1093起，local-journey/live-journal.json）。此前同场景只保存user_message，此项说明事件归属修复在实际设备恢复了运行中落盘。

不能计文件编辑业务验收通过：虽然新请求要求file_edit测试，模型实际继续旧Life XP修复，尚未执行要求的两次编辑。检查XiaowanAcpConnection与OmniAgentExecutor确认当前文本作为userMessage传入、continueMode默认false且当前用户消息最后附加；暂未发现代码丢弃最新文本的依据，不能把模型偏离任务直接归因传输。原任务仍运行，不重发。

## 本地工具失败恢复与重启：最终实测通过

旧conversation31模型持续偏离最新file_edit验收，按真实UI Stop取消，权威cancelled已落盘（local-journey/turn-cancelled.json）。没有将该轮算通过。新建显式xiaowan-acp会话32，真实界面发送一次，执行file_write alpha→file_edit alpha/alpha明确失败→file_edit alpha/beta成功→file_read beta，正式end_turn；强化assert-agent-turn-outcome.py核对同轮两个file_edit的真实参数和状态、读取正文。重启后回复仍显示，独立读取Android文件仍beta，Remote配置enabled=true。fresh-local/result.json五步全部通过，persisted-turn.json与independent-file.json留证。

此项同时验收文件无变化不得假报成功、工具失败后继续、Remote开启下本地事件持久化、重启保留。长上下文自动压缩成功仍需独立验收，不能仅凭这条短任务证明。
