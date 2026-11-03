# #566 空闲后创建会话失败：定位与修复

2026-11-03。修复提交 `8a94906e009d90e306392e0389231ef25ab6a6c7`，原提交 `0a6269f539a86fceee08e0a0cfd633f59fbd32f0`。

## 结论

同版本 App、OkHttp 5.5.0 与原 Na 短空闲超时配置已复现 `session/new` 连接失败。服务器在空闲约 6 秒后关闭 HTTP/1 连接；网络库在空闲不足 10 秒时跳过深入健康检查。这个时间窗口内，创建会话 POST 可能复用失效连接并在读取响应头时失败。写请求禁用失败重试，因此错误直接返回界面。

电脑直连原代理和绕过代理直连实际 Na 服务均在 6.5 秒时失败，12 秒时重新连接成功，排除 USB 和录制代理作为必要触发条件。原代理 0、3、5.5 秒也成功。源码阈值与这些边界实验一致。

旧两次日志只保留了 `session/new` / `Http1ExchangeCodec.readResponseHeaders` 的异常摘要，没有当时的 TCP 轨迹；此次用相同版本和配置重现、记录关闭时序并验证修复，不能伪称补回了历史抓包。

## 修复

复用现有 `naHttpResponse` 传输入口：GET/HEAD 仍使用原客户端；写请求从空连接池发起，并在完成后关闭连接，避免取得此前已关闭的空闲连接。复用现有 dispatcher、TLS 配置及取消机制；写请求仍不因 I/O 失败自动重发，不新增会话、重试状态机或用户请求重放。

代价是写请求需要新建连接，HTTPS 下可能增加握手成本。读取状态与 SSE 接收保留原连接复用策略。生产 HTTPS 延迟尚未量化。

## 验证

| 检查 | 修复前 | 修复后 |
| --- | --- | --- |
| 原生回归：服务器关闭已归池连接再创建会话 | SocketException，3 项中 1 项失败 | 通过 |
| 全部 Na 原生测试 | 本轮新增用例复现失败 | 46/46，0 错误、0 跳过 |
| 手机正常 App，空闲 7.001 秒后发送 | 执行失败，服务未收到创建请求 | 完整回复 |
| 手机正常 App，空闲 9.001 秒后发送 | — | 完整回复 |
| 手机正常 App，空闲 12.001 秒后发送 | — | 完整回复 |

三轮修复后真机检查均为：创建会话 1 次、提交消息 1 次、执行 1 次。状态查询连接和写请求连接 ID 不同；修复包日志中 ACP 请求失败、Flutter 异常与崩溃均为 0。电脑新连接方案在 6.5、8、9.5、12 秒四个点均成功。

单元测试还覆盖 POST/PUT/DELETE 已收到请求但响应丢失时不重发，以及 GET 保持活连接复用；原有取消、附件、流式与会话归属测试全部通过。服务仍使用原来的 keep-alive 设置，没有使用上轮临时 `Connection: close` 服务端处理来掩盖问题。

## 手机实际操作画面

全部从 `lib/main_standard.dart` 启动，真实首页新建 Na 对话、输入、等待、发送、接收输出。仅样例服务输出可控；没有使用测试结果入口。录像未经剪辑、无音轨，已全量解码并抽帧检查。

- 修复前 7 秒：[截图](before-idle-7s.png) · [录像，16.2 秒](before-idle-7s.mp4)
- 修复后 7 秒：[截图](after-idle-7s.png) · [录像，16.8 秒](after-idle-7s.mp4)
- 修复后 9 秒：[截图](after-idle-9s.png) · [录像，24.5 秒](after-idle-9s.mp4)
- 修复后 12 秒：[截图](after-idle-12s.png) · [录像，21.0 秒](after-idle-12s.mp4)

时长为 MP4 媒体时长；空闲间隔以服务请求时间戳和手机发送动作记录计算。APK、截图、录像哈希与逐轮计数见 [manifest.json](manifest.json)。

## 范围

独立 debug 包，仅临时调整包名和应用名，正式版未覆盖。与此前一致，诊断构建排除了独立 Pi 资源校验任务 `verifyPiAcpBundle`；本次不能证明完整正式版构建、生产鉴权、真实模型全链路或 16.7 毫秒帧耗时达标。

参考：[Node HTTP 空闲超时](https://nodejs.org/api/http.html#serverkeepalivetimeout)、[实际使用的 OkHttp 5.5.0 官方源码包](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp-jvm/5.5.0/okhttp-jvm-5.5.0-sources.jar)；本次核对 `RealConnection.kt` 的 10 秒阈值与 `RealRoutePlanner.kt` 的非 GET 检查入口。
