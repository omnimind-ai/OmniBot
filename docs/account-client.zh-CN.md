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

平台模式还会访问品牌模型网关的 `GET /v1/models`。该请求复用账号 Access JWT；若返回 401，会刷新登录会话并且只重试一次。客户端只读取模型 ID、所属方和支持的接口类型，不接收或保存 New API 内网地址、网关 Token、百炼 Key。

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
- 平台模式自动显示只读的“OmniBot 官方 AI”提供商
- 自动同步官方文本模型，并把已验证的 `Qwen3.5-Plus` 设为首次使用的主文本模型

主聊天文本请求已经读取这个模式：平台模式携带账号 Access Token 访问品牌网关；BYOK 模式继续使用设备上的模型提供商配置。切换到平台模式时，本地 BYOK Provider、Key 和主文本选择会被保留；切回 BYOK 或退出账号时会恢复原来的本地选择。图片生成和语音仍沿用原有配置；通用 BYOK 模型列表也没有改线，只有只读官方 Provider 的文本模型列表来自品牌网关。
