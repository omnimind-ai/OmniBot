# 跨设备 Codex 会话管理验证 — 2026-09-12

用户目标：保持小万 UI，通过跨网连接管理电脑 Codex 会话。本轮先验证现有 Bridge 与真实上游的协议闭环，没有宣称接管官方桌面 App 的活跃任务。

## 实际执行

- 安装 cloudflared 2026.9.1。Bridge 使用仓库 0.1.5 server.mjs 的临时副本，@agentclientprotocol/codex-acp 1.11.0，回环监听、随机 Token，凭据不入库。
- 新增可执行 scripts/verify-remote-bridge-session.cjs，依赖现有 Bridge 的 ws 包。测试目标和凭据文件由环境传入。
- 本机真实协议测试 7 项通过：错误 Token 拒绝、ACP initialize、新建、真实模型 end_turn、列举接口、新连接加载同一已持久化 Session、继续请求返回上一轮标记。日志 /tmp/oob-local-session-test-final.log。
- 初次仅创建空会话就断开，load 返回 no rollout found。改为执行真实首轮后验证持久化。另一次测试要求完整标记位于单个流 chunk，断言失败；已改为拼接 agent_message_chunk 后检查，重跑通过。不能把此测试错误视作产品 bug。

## 入口

```sh
NODE_PATH=/path/to/bridge/node_modules OOB_BRIDGE_URL=ws://127.0.0.1:17321/codex OOB_BRIDGE_TOKEN_FILE=/private/token-file OOB_BRIDGE_CWD=/path/to/disposable/workspace node scripts/verify-remote-bridge-session.cjs
```

此入口创建测试会话并调用两轮真实模型，可能计费，不删除用户会话。它验证列举接口返回结构，不断言所有桌面会话可见。不是 App UI 或 Android 验收。

## 跨网结果与限制

Cloudflare Quick Tunnel 域名分配不等于建立通道。普通 DNS 将 edge 解析到本机代理的 198.18 地址；使用官方 DoH 查询的真实 edge 地址后，HTTP2 仍 TLS EOF，QUIC 超时。未关闭 TLS 校验或修改系统 DNS。所有失败的临时隧道已停止；没有可交付的公网连接地址。不是直连打洞成功，也不是中继成功。

adb devices 无设备。手机扫码、会话列表/加载/继续、重启及中途断网均未验收，待真机验证。之前修复的 App APK 本轮未安装。官方桌面活动会话共享、断线期间任务持续执行不在本轮通过范围。
