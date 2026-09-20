# 官网 Bridge 入口与中转验收 — 2026-09-14

状态：隔离 Nginx + 4090 中转已验证；正式官网 WSS/蜂窝网络待部署、待真机验证。不是多租户正式发布，也不是最大容量结论。

## 实际链路

Mac Bridge17336 → 受 launchd 监督的反向 SSH → 4090 loopback18336。

协议探针通过 Mac17338 → SSH → 4090 隔离 Nginx18136 → relay18336 → Bridge17336 → 已运行的同一个 Codex daemon。手机通过 USB reverse17321 → Mac17338 走同一路径。没有启动第二个 Codex 后端。

旧 v1 中转未被替换。新增 launchd：`cn.omnimind.codex-acp-v2-relay-test` 和 `.forward`，复用安装器的 tunnel-only 模式。稳定性范围是现有 SSH keepalive/launchd 监督；本轮未测试电脑睡眠或主机重启。

## 发现并修复的实际入口缺口

1. 原配置只有 `/codex`。协议连接成功，但 Android 还使用 `/health`；真实 Nginx 返回404，入口 verifier 失败，手机会话入口未成功恢复。补齐 `/health` 及既有 `/fs/` 路由，不修改手机 URL/交互逻辑。
2. v2 适配器缺少 Bridge 所调用的 `--version` CLI 入口。新增从自身 package.json 读取名称/版本的快速路径，版本查询不启动 Codex 后端。实际 `/health` 现在返回 ready。
3. 文件接口继续由现有 Bridge 鉴权。Nginx 36MiB请求体上限为默认24MiB文件的 base64/JSON 提供空间；本轮只验证目录读取及拒绝未认证访问，没有验证最大文件上传。

配置：`deploy/codex-relay/website-acp-location.conf`。已通过4090实际 nginx1.28.3的 `nginx -t`，并加载进隔离的非特权 Nginx 实例。生产网站配置没有修改。

## 可执行验证

`scripts/verify-bridge-ingress.cjs` 检查手机使用的健康检查、目录读取、未认证请求拒绝、认证后 WebSocket hello、每条连接的真实 ACP initialize，以及 ping/pong。不会创建会话、发送模型请求或写文件；仅列出明确配置的隔离工作目录。

`scripts/verify-bridge-ingress.test.cjs` 的5个本地真实 WebSocket/HTTP fixture测试通过：正常并发、错误鉴权、错误ACP版本、缺失健康路由、缺失文件路由。它们测试 verifier 能否发现故障，不替代实际服务或手机验收。已接入既有 `scripts/test-agent-runtime.sh --bridge DIR`。

实际 Nginx 全入口小规模测试：

| 认证 ACP 连接数 | 最大初始化耗时 | Ping 中位 RTT | 最大 Ping RTT | 结果 |
|---|---:|---:|---:|---|
| 4 | 477.94ms | 60.49ms | 66.36ms | PASS |
| 8 | 778.66ms | 74.78ms | 208.95ms | PASS |

这些是短时间样本，不是并发生成测试、持久在线测试、用户容量或服务器上限。所有计数连接均执行了真实ACP初始化；不是未认证的空WebSocket数量。探针关闭后检查 Bridge，只保留手机的一个适配器子进程，未见该次探针连接残留。

证据：`artifacts/bridge-ingress-2026-09-14/nginx-full-ingress-{4,8}.json`。其他 connections-* 文件是补齐 HTTP 检查之前的阶段性样本。

## 真机

设备：OnePlus PJE110，b49f281b，Android16；既有候选APK0.6.2.3/code15，SHA256 b3c3572ffc8355f68fba60a01b2ca2d9ccc44b3f5ca3b3422e139ddaa7e19ac7。本轮无需重建APK。

会话：01a09ae4-40da-7853-aa19-e1940cd4c4c4，隔离目录 /tmp/oob-shared-session-probe。

- K：手机经4090中转发送，实际显示问题及完整回复，后台只执行一次。PASS，phone-relay-K.json。
- L：补齐 Nginx 路由后重开手机会话，电脑协议入口发送；手机经 Nginx 被动显示问题及完整回复，无再次重开刷新。PASS，phone-nginx-L.json。
- M：手机经 Nginx 发送，实际显示问题和完整回复、后台只执行一次。PASS，phone-nginx-M.json。

## 正式官网的部署阻塞与操作

重新检查：SSH4090的zewen账号没有Nginx配置写权限，也没有可用sudo。已向用户请求可用部署SSH入口/部署方式，尚未收到。不要把此阻塞解释为ACP或模型能力限制。

有权限的部署者需把配置安装为root拥有的 `/etc/nginx/snippets/omnibot-acp.conf`，在现有 `/etc/nginx/sites-enabled/omnibot-website` 的 server 块中 include，运行 nginx -t 成功后 reload。公网 TLS/FRP 边缘也必须保持 WebSocket Upgrade，再用真实官网 wss:// URL 运行 verifier，并用手机蜂窝网络做完整交互验收。尚未测到的边缘配置不能算通过。

本轮没有实现用户/设备注册与多租户路由，没有修复 App 自动网络重连，也没有验证原生 Codex GUI。它们仍是整体功能交付的独立缺口。

测试结束时，隔离Nginx18136及临时SSH本地转发17338已停止；配置文件保留在4090 `/tmp/oob-wss-nginx-probe/nginx.conf` 供复查。手机USB reverse恢复到受监督的17337中转入口，未留下依赖临时Nginx的手机配置。正式官网配置仍未修改。
