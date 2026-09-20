# 原生 Codex 与手机共享会话：可行性验证

需求：保留原生电脑界面；手机发送出现在电脑原会话；两端输出一致；生成中断线执行继续，重连补历史且不重发；通过小万官网认证中继跨网。

## 本轮实际运行

本机 CLI 与桌面内置 CLI 均 0.153.4。现有桌面 app-server 为独立 stdio 进程，未开放默认 app-server-control.sock。现有 OmniBot Bridge 每 WebSocket 启动 codex-acp，不满足共享桌面后端要求。

已运行官方 `codex app-server --listen ws://127.0.0.1:17329`，仅本机回环。两个真实 WebSocket 协议客户端连接同一个 app-server；创建隔离测试会话（cwd=/tmp/oob-shared-session-probe），调用真实模型，不授权工具执行。

可执行回归 `scripts/verify-codex-shared-session.cjs`：桌面侧种子消息生成持久历史 → 第二客户端 resume 同 id → 手机侧发送 → 双端收到 delta/完成 → 手机重连读回唯一 turn → 再次发送后立即断开手机 → 桌面收到完成 → 两端重新连接历史 turn id 一致。全部通过；结果见 `artifacts/shared-codex-2026-09-13/protocol-result.txt`。

```sh
codex app-server --listen ws://127.0.0.1:17329
NODE_PATH=/tmp/oob-phone-bridge-runtime/node_modules OOB_SHARED_CODEX_URL=ws://127.0.0.1:17329 node scripts/verify-codex-shared-session.cjs
```

测试在服务确认 turn/start 后断开；**不证明发送成功但确认包丢失时重复提交具备服务器幂等性**。恢复只读/resume，不重发 prompt。第一次空线程 resume 报 no rollout，已保留该发现并改为先有真实种子轮次。本轮临时回环服务已停止，测试线程保留。

## 原生桌面候选接入点

只读检查本机安装包 /Applications/ChatGPT.app/Contents/Resources/app.asar，发现 CODEX_APP_SERVER_WS_URL 能选择 WebSocket 后端；另有 CODEX_APP_SERVER_USE_LOCAL_DAEMON=1 的本地 daemon 路径。它们为本机版本内部开关，不承诺稳定公开接口。本轮未改变启动配置、未重启用户桌面，**未验证原生桌面接入或已有活动会话迁移**。

官方 daemon start 实际失败：缺少 ~/.codex/packages/standalone/current/codex 管理安装。没有伪造管理目录，也没有重启桌面中的当前工作。独立回环服务用于协议验证，不能代替原生桌面验收。

## 尚未完成

- 现有 ACP Bridge 到共享 app-server 的适配尚未实现；本次脚本直接使用官方 app-server 协议，不是新增产品私有生命周期。
- 官网域名与可部署后端/服务器未提供，已询问用户。没有部署中继，没有完成公网打洞或转发；官网中继是双端主动连接的跨网方案，不等同已验证 P2P NAT 打洞。
- adb devices -l 无设备：手机安装、界面、重连均未测，待真机验证。
- 原生桌面与手机双端 live UI、原会话的请求所有权及审批处理、重启后恢复均未验收。

本轮结论：共享 app-server 的双客户端真实模型路径可行，完整产品链路尚未完成。
