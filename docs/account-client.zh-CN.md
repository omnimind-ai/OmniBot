# OmniBot 账号客户端

这一层负责连接 OmniBot 与 `omni-account`，目前位于 `baselib/account`。

## 当前流程

```text
“我的 → 账号与 AI 服务”界面
          │
          ▼
AccountRepository
  ├─ 注册、登录、退出、找回/修改密码、删除账号
  ├─ 查看/撤销登录设备、查看平台用量
  ├─ Access Token 失效后自动刷新
  ├─ 读取或修改 platform / byok 模式
  └─ 用用户 JWT 获取平台可用的文本模型
          │
          ├─ AccountApiClient ──HTTPS──> omni-account
          └─ PlatformModelApiClient ──HTTPS──> 品牌模型网关 /v1/models

账号 Access/Refresh Token ──> Android Keystore 加密存储
用户自己的模型 API Key   ──> 原有设备本地模型配置
```

账号服务器只接收 `platform` 或 `byok` 这个选择，不接收用户自己的模型 API Key。

## 服务地址

`develop` 调试版本和 `production` 正式版本均默认使用账号品牌域名：

```properties
OMNIBOT_BASE_URL=https://account.omnimind.com.cn
```

打包时仍可通过同名构建属性覆盖该默认值，以便切换部署环境。反向代理负责把 `/v1/auth/*` 和 `/v1/me/*` 转发到内部账号服务。不要在客户端配置 New API 的真实内部地址。

## Na 云端空间

托管 Na 的手机本地历史也按账号和 Cloud 入口隔离。列表、分页、全文读取与导出
均检查现有 Conversation/ACP session 的账号前缀，退出或换号后清掉对应显示缓存，
不会删除历史。重新登录原账号可恢复；已验证 userId 与登录凭证一起加密保存，
因此已有登录在进程重启后可离线查看自己的缓存。普通本地和 BYOK 对话保持原行为。
升级前不能证明归属的 Na 记录保留在本机，但不会自动分配给下一位登录用户。

`OMNIBOT_CLOUD_URL` 指定 Cloud 的 HTTPS 入口，默认值为
`https://cloud.omnimind.com.cn`。正式模式使用当前账号登录凭证，用户无需填写
容器地址或访问令牌。初次使用通过 `GET /v1/cloud/workspace` 检查空间，必要时
以空请求体调用 `POST /v1/cloud/workspace/claim`；授权、名额和执行就绪状态
均由服务器决定，只有 `executionAvailable=true` 才访问 `/v1/cloud/na/api/*`。

Na 复用 `AccountRepository` 的加密 Token 存储和刷新锁。接近过期时先刷新，
读取返回 401 最多刷新重试一次，发送任务和文件写入不自动重放。连接上下文
固定账号和登录代次，退出或换账号后拒绝旧请求结果；会话绑定同时区分服务
入口和账号，防止把旧会话发送到新账号。本轮隔离范围为 Na 会话历史；普通
本地 Agent、BYOK 配置和其他设备资料仍采用原有的设备级行为。

调试包可显式切换独立测试服务；该模式的实例令牌只保存在进程内，正式包
不显示该入口。调试构建的官方服务仅额外允许 `127.0.0.1` 或 `::1` 的 HTTP 回环地址。
公网域名、证书、转发与服务端执行开关仍需单独部署，客户端接入不自动开通
生产账号，也不会把磁盘或内存配额转换成模型额度。

## 已接入的接口

- `POST /v1/auth/email-codes`
- `POST /v1/auth/register`
- `POST /v1/auth/login`
- `POST /v1/auth/refresh`
- `POST /v1/auth/logout`
- `POST /v1/auth/password-reset`
- `GET /v1/me`
- `DELETE /v1/me`
- `PUT /v1/me/password`
- `GET /v1/me/sessions`
- `DELETE /v1/me/sessions/{session_id}`
- `DELETE /v1/me/sessions`
- `GET /v1/me/platform-usage?limit=1..100`
- `GET /v1/me/ai-settings`
- `PUT /v1/me/ai-settings`

找回密码使用 `reset_password` 专用验证码用途，不能复用注册验证码。所有需要登录的账号接口在 Access Token 返回 401 时只刷新并重试一次；新 Token 仍返回 401 时会清除已失效的本地会话，避免反复续期。单个账号响应最多读取 1 MiB，超出时按协议错误终止。

删除账号只有在服务端明确返回成功后才清除本地 Token 和 AI 模式；密码错误、网络错误或其他失败都会保留当前登录，方便用户修正后重试。服务端删除成功后的本地模型绑定清理属于收尾动作，即使收尾异常也不会把已完成的删除误报成失败。

登录后，官方渠道会访问品牌模型网关的 `GET /v1/models`。该请求复用账号 Access JWT；若返回 401，会刷新登录会话并且只重试一次。客户端只读取模型 ID、所属方和支持的接口类型，不接收或保存 New API 内网地址、网关 Token、百炼 Key。

Flutter 账号中心已经支持：

- 请求注册验证码、注册并自动登录
- 请求找回密码验证码并重置密码
- 邮箱密码登录与退出当前设备
- 查看登录邮箱和平台余额
- 查看平台用量明细
- 修改密码、查看和撤销其他登录设备
- 二次确认后删除账号
- 切换平台额度 / 用户自备 API Key
- BYOK 模式跳转到原有模型提供商配置页
- 登录后自动追加只读的“OmniBot 官方 AI”渠道；模型提供商设置页仍只管理本机 BYOK Provider
- 自动同步官方文本模型，并把已验证的 `Qwen3.5-Plus` 设为首次使用的主文本模型

主聊天文本、图片生成和语音播放按所选 Provider 或场景绑定路由：选择官方渠道时携带账号 Access Token 访问品牌网关，并只使用网关模型目录声明的能力；选择 BYOK 时继续使用设备上的模型提供商、图片生成与语音配置。登录和官方目录同步不会改写本地 BYOK Provider、Key 或场景选择。若 BYOK 图片 Provider 没有 Key，而构建时配置了 `OMNIBOT_IMAGE_API_KEY`，图片工具会继续使用小万内置图片服务作为回退。
