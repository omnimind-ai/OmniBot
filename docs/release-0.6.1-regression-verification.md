# 0.6.1 失败清单修复与验证

## 范围与基线

针对 PR「fix: 0.6.1 ACP 终态统一、内容完整性与聊天体验修复」列出的失败，实际复现 Flutter 32 项、Android 2 项、Node 1 项。以下分类来自失败输出与当前调用路径核对，不代表已通过基线对比证明所有问题由本 PR 引入。

## 原因与修改

| 范围 | 已确认原因 | 修改与保留的验证 |
| --- | --- | --- |
| Android 终端发行版（2） | `prootDistro=...` 文本只存在于已移除的私有 postToolRule，名称与发行版参数仍由正式工具定义提供 | 改为检查 terminal_execute 的 prootDistro 参数描述、发行版名称与未解析占位符，并断言不恢复 postToolRule |
| Node 启动/刷新（1） | 全文件禁止网络目录方法，误命中用户显式刷新 | 启动方法仍断言只读 Provider 缓存；显式刷新分别断言官方、自定义 Provider 查询及现有 sessionConfig 更新；保留会话配置保存/恢复约束 |
| 聊天列表（8） | 正文由富文本渲染并带行内尾部组件，旧纯 Text 精确匹配找不到内容 | 查询可见 RichText 正文，保留折叠前后可见性、段落顺序、头像和工具卡断言 |
| 场景模型（10） | 设置页移除刷新后普通 Provider 目录没有更新入口；另一个测试仍操作已移除的 GUI 专用开关 | 设置页先显示缓存，再调用既有异步刷新；不改变 ACP 启动路径。GUI 使用共享 scene binding，新增官方转自定义后销毁/重建页面恢复选项的验证 |
| 历史页（1） | 缺少 SharedPreferences、StorageService、ProviderScope 初始化，日期语言依赖宿主环境 | 补齐实际页面依赖并固定测试语言，保留日期分组和归档操作验证 |
| 请求卡（2） | 夹具声明 Claude，断言却要求另一个 Harness ID | 断言响应路由与原请求身份一致，保留 session/conversation 与答案内容验证 |
| 引导页（1） | 场景减少后 take(3)/skip(3) 将记忆嵌入错分到对话页 | 按现有 scene.memory 身份分组，保留用户完整配置引导测试 |
| OmniFlow 执行中心（3） | 文案/参数断言过时；夹具已关联 Function 却尝试再次注册 | 检查真实 run_id 参数；已关联记录打开已有 Function，不再重复注册；单独用未关联夹具验证注册后详情加载失败时的响应恢复 |
| 错误组件（1） | 测试改变全局 ErrorWidget.builder 未在 Flutter 框架检查前恢复 | 在 finally 中恢复，继续验证安全错误组件 |
| 图片预览（4） | pumpAndSettle 不保证真实图像解码或文件 I/O 完成；解码后另发现 contain 放大小图边界 | 测试等待真实解码/分享调用；预览边界使用 scaleDown，与显示尺寸一致。保留大图、自然尺寸、宽图和系统分享断言 |
| 背景预览（2） | 断言使用旧文案且未固定全局语言 | 对齐当前本地化文案，保留颜色、字体大小、独立图层验证 |

## 验证结果（2026-09-06）

- 独立检出修复提交 `0a0218eb1`，排除其他任务未提交修改：Flutter 全量 **1108/1108**；Node 统一入口 **58/58**。
- 共享工作区：Android app JVM **887/887**、126 suites、0 errors/skipped；APK assemble 成功。共享工作区另有其他任务未提交代码，因此这两项不冒充独立提交验收。
- 共享工作区 Flutter 1115 项通过，包含其他任务新增测试；不与独立检出的 1108 项相加。
- 本机 Flutter 3.35.7；独立检出 pub get --offline 按本机 SDK 解析 meta/test_api 版本，未提交锁文件变化。CI 使用 Flutter 3.38.7 与 enforce-lockfile，远端结果需单独查看。
- 定向 Dart analyze 无 error/warning；保留修改范围外的 6 项 info（已有异步 context 提示和 Matrix4 弃用提示），不为清理提示扩大重构。
- 未连接 ADB 设备；未完成最新 APK 安装、真人反馈、真实 Provider 请求或长时间压力验收。测试中的页面重建验证不等同于手机进程重启验证。

## 持久复跑

```bash
(cd ui && flutter test --reporter expanded)
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest
bash scripts/test-agent-runtime.sh --offline --skip-gradle --skip-flutter --skip-webchat
./gradlew --no-daemon --no-parallel :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
git diff --check
```

所有原失败场景均保留或替换为当前公开接口的行为验证，没有通过跳过测试、放宽生命周期语义或恢复私有协议使测试通过。
