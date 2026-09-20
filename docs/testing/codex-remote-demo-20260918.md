# Codex Remote 真机修复与录屏（2026-09-18）

## 实测结论

PJE110 / b49f281b / Android 16；App 0.6.3 (16) developStandardDebug。最终 APK SHA256 `67c5c6e206838c20a49d1c0262b599f29e69c7da22060af975a28aa343372d55`。覆盖安装保留数据。

手机通过同一 Wi-Fi 的 `ws://192.168.3.177:17337/codex` 连接带鉴权的 Bridge，复用既有 Codex daemon；adb reverse 为空，USB 只用于操作、取证和录屏。新 launchd 服务 `cn.omnimind.codex-shared-bridge-lan` 使用持久工作目录 `/Users/wuzewen/Projects/Omni/demos/codex-remote`，凭据仍在仓库外私有文件。原有服务未停止。

## 根因及修复

1. 远程开关关闭，旧 Cloudflare Quick Tunnel 域名在手机报 DNS 解析失败。恢复开关，改为当前局域网地址。公网/蜂窝网络不属于本次通过范围。
2. 原 Bridge 默认 `/private/tmp/oob-shared-session-probe` 目录消失，health 报适配器 spawn ENOENT；重新创建目录后 health 立即恢复，证明不是适配器文件缺失。实际 demo 改用持久目录。部署工具增加显式 --host，默认仍为 loopback。
3. ACP v2 `session/set_config_option` 必须声明 `type: id`（布尔值为 boolean），手机发送 v1 格式导致 -32602。修复既有 RemoteAcpProtocol 边界，保留 v1 和显式类型，不新建生命周期。
4. 新建会话先绑定了临时 Conversation，页面随后基于 sessionId hash 新建另一份 runtime；native 正确拒绝跨 Conversation 重绑，但回复落在原 runtime，页面看不到。页面现在优先复用共享 coordinator 的 session ownership，未知 session 事件不抢占当前页面；已订阅的会话激活不再次 load/replay。

## 真机验收

会话 `01a0b306-b4fa-71d1-8f9b-fbecd0126718`：

- 手机真实输入并发送创建 linked-demo.md 的请求；电脑实际创建文件，手机显示 `OOB_REMOTE_LINKED_READY`。同一 backend 记录一次 completed turn。
- 从电脑协议客户端向同一会话发送 `OOB_REMOTE_COMPUTER_SYNC`；电脑端断言一次 completed turn，手机未刷新即显示问题和回复，真机脚本验收通过。
- 两个 turn 的真实记录保存在 accepted-backend.json，手机画面见 result.png。
- 观察到用户开始在手机输入新内容，保留草稿，未强停或重启。因此重启恢复 **待真机验证**。
- 原生 Codex 桌面 GUI 显示/交互 **未验证**，本次电脑端使用真实 daemon 协议客户端。不能把这段视频称为原生桌面 GUI 双端验收。

## 可执行回归

- `:app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.agent.runtime.RemoteCodexAppServerSessionTest`：12项通过。
- Flutter `chat_architecture_test.dart`、`chat_conversation_runtime_coordinator_test.dart`：137项通过。
- Dart analyze：无 error，6项既有 warning、10项 info；未顺带清理无关代码。
- `scripts/verify-remote-config-v2.py`：配置 OOB_ACP_BIN、OOB_TEST_CWD 和既有共享适配器环境；真实 initialize/new、无类型配置拒绝、有类型配置通过、close；不发 prompt。
- `scripts/verify-bridge-ingress.cjs`：LAN 鉴权、health/files、ACP v2 initialize、无效凭据拒绝通过。
- `scripts/verify-remote-chat-device.py SERIAL --send '...OOB_MARKER...' --expect OOB_MARKER --output PATH`：使用空 demo 输入框，一次实际发送，等待可见回复，不重发。设置 `OOB_ALLOW_PHYSICAL_DEVICE=1`；不用 --send 时检查被动回复/历史。不要在无障碍录制期间使用该 UIAutomator 驱动。
- `scripts/verify-shared-external-send.cjs SESSION OOB_MARKER`：配置 OOB_SHARED_CODEX_URL、OOB_TEST_CWD 和 NODE_PATH；强校验所选 session 工作目录，单次发送并核对真实 completed turn。原有测试通过。

首轮 UI 驱动过于严格，要求整段无障碍 label 等于 marker，未考虑回复附带耗时/时间。改为首行精确匹配并排除输入框，修正后被动接收测试通过；没有为了匹配失败重发手机请求。首次手机成功同时由实际录像、截图、文件和 backend 记录证实。

## 视频与产物

[86秒演示](artifacts/codex-remote-demo-20260918/demo.mp4)：最终通过版本从新会话输入到双向回复的原速录像，截取原录像前86秒，结束于用户后续个人操作之前。没有加速、重排或拼入先前失败轮次。

原始失败录屏、协议修复后但身份修复前的录屏，以及最终完整录屏分别为 raw.mp4、fixed-demo-raw.mp4、accepted-demo-raw.mp4。细节见 [verification.json](artifacts/codex-remote-demo-20260918/verification.json)。

局域网地址随电脑 IP 变化需要更新；电脑休眠/离线不可连接。固定公网域名与公网验收尚未完成。
