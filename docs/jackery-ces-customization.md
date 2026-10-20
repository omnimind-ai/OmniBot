# Jackery Power AI CES 定制版

依据《小万_CES软件定制方案_v14.docx》开发，分支 `shiyang/xiaowan-ces-custom`。Android edition 为 `jackeryCes`，独立包名 `com.jackery.powerai.ces`，Flutter 入口 `lib/main_jackery_ces.dart`。标准版仍使用 `standard` 和原有入口。

软件功能已实现并进行自动检查；**最终品牌图标、CES 最终机型图、实际设备 API/鉴权参数及实机联调尚未具备，不能把本分支视为已经通过附件全部验收的成品**。Word 中的机型图片明确是占位，代码没有把它当作已确认素材，也没有伪造电量或设备状态。

## 逐项落实

| 附件条目 | 本分支行为 | 状态/边界 |
| --- | --- | --- |
| 3.1、3.2 名称/图标/启动页 | 产品名 Jackery Power AI；白底橙色资源覆盖；暖灰白启动背景及 Android 12 启动主题 | 名称/主题完成；`jackery_brand_placeholder.xml` 是明确标注的临时文字标，待替换为厂商品牌确认图标 |
| 3.3 登录 | 跳过启动登录提示、隐藏账户/登录/注册/退出入口；不触发面向观众的云服务初始化和更新提示 | 已实现；工作人员鉴权不被 Reset demo 删除 |
| 3.4 问候/首页 | 英文问候与 search、remember、build、summarize、plan、chat；橙色重点词；设备卡在问候与试用任务之前 | 已实现 |
| 3.4、3.5 工具/模式/宠物 | 隐藏工作区工具岛、环境变量、终端、浏览器、模式选择和宠物；关闭桌面宠物与本机服务 | 已实现；受限路由也会重定向首页 |
| 3.6 输入/模型 | 保留加号、圆形、模型/调节、盾牌、发送；隐藏电脑和 ⌘；底部模型浮层仅显示 Jackery Power AI · On-device | 已实现；未配置设备时显示 AI unavailable，不显示云端备用模型或配置入口 |
| 3.7 侧边栏 | 会话、新建对话及底部唯一 Settings；新建操作橙色；隐藏 Kimi Web、DSH Web 和其它底部入口 | 已实现 |
| 3.8、3.9、3.11 设置 | 只保留顶部 Reset demo 和 Model providers；提供商仅展示工作人员配置的设备模型，观众不可编辑；隐藏所有其它设置及关于页 | 已实现；原生 Provider/提示词修改接口也拒绝观众版变更 |
| 3.10、4.1 全局视觉 | 固定浅色，背景 #F8F7F5、卡片/输入 #FFFFFF、分隔 #E8E5E1、文字 #303030、主色 #FF6727；关闭自定义背景和旧蓝渐变 | 已实现；包含历史、预览、浮层、提示等应用内可达界面；系统文件选择器/键盘由 Android 控制 |
| 4.2 英文化/身份 | 固定 English、直接首页、英文试用任务/错误/空态；现有提示词 owner 固定 Jackery 身份及英文默认回复 | 已实现；底层模型实际遵循系统提示词需在指定模型上验证 |
| 4.3 首次教程 | 不进入 Linux、开发环境、Agent/工具、模型配置教程 | 已实现 |
| 4.4 Reset demo、3.13 清理 | 先官方 ACP 取消并等待结束、阻止新请求/存储写入，再清理会话/消息/轨迹/长短记忆/索引/应用定时任务/应用闹钟/工作区用户文件；恢复模型、英文、浅色、固定对话 | 已实现；保留鉴权、硬件连接、工具安装与正式设备图。第三方系统 Clock 中旧测试闹钟需工作人员单独清理 |
| 4.4 联网搜索 | 固定现有对话生命周期；通过已存在的 ACP MCP 能力，仅暴露允许的搜索工具 | 接入完成；实际搜索 MCP 服务及鉴权需工作人员提供和配置 |
| 6.2 首页设备卡 | 设备图、Battery、Power、Connection、AI model；点击 View device 进入详情；失联显示连接引导 | 已实现接口接入和空态；真实接口及确认素材待提供 |
| 6.2 详情 | 较大设备图、四类状态、Back、临时 slogan | 已实现；最终 3D 效果图待提供 |
| 6.2 对话状态/背景 | 顶部紧凑连接/电量；淡化产品图与 Local AI. Powered anywhere. 在气泡后方；背景无参数、卡片边框、View device 或点击动作 | 已实现；实际产品图待提供 |
| 6.2 未知/异常 | Not connected、Battery unavailable、AI unavailable；字段缺失/非法/过期清空 | 已实现；不使用手机电量或固定演示值 |
| 4.5 展前清理 | Reset demo 操作及工作人员预置连接/授权步骤 | 软件支持；展前操作与硬件验收由工作人员执行 |

## 工作人员配置

模型需提供可调用的 OpenAI-compatible `chat/completions` API，支持流式响应；联网搜索还需该模型支持工具调用。精确的模型 ID、端口、鉴权及状态协议必须以厂商提供的接口为准。当前配置接口不假定具体硬件型号、性能或续航。

将私有配置存为仓库外的 JSON 文件（不要提交真实密钥）：

```json
{
  "modelApiBase": "http://DEVICE_ADDRESS:MODEL_PORT/v1",
  "modelId": "EXACT_DEVICE_MODEL_ID",
  "statusUrl": "http://DEVICE_ADDRESS:STATUS_PORT/status",
  "apiKey": "OPTIONAL_DEVICE_CREDENTIAL",
  "searchMcp": {
    "endpointUrl": "https://SEARCH_SERVER/mcp",
    "transport": "http",
    "bearerToken": "OPTIONAL_SEARCH_CREDENTIAL"
  }
}
```

地址、密钥均为格式示例，不是可用服务。无鉴权服务可以省略 `apiKey`/`bearerToken`。设备 Provider 密钥进入已有加密 Provider 存储；搜索服务进入已有 Remote MCP 配置 owner；启动导入后 JSON 中的两项凭据会被移除。重置保留相同服务的已存凭据，工作人员更换地址时应重新配置凭据。

搜索工具原始名称默认允许 `web_search`、`web_run`、`search`、`brave_web_search`、`tavily_search`；不同名称通过构建属性 `JACKERY_SEARCH_TOOL_NAMES` 提供逗号分隔的精确名单。工具仍通过现有 ACP `session/update` 与唯一 reducer 处理。搜索尚未配置或调用失败时，助手不得宣称已经搜索。

已安装的 **debug CES** 应用可使用脚本预置（先校验，再操作指定手机）：

```bash
python3 scripts/provision-jackery-ces.py --config /private/staff/connection.json --device-image /private/staff/device.png --dry-run
python3 scripts/provision-jackery-ces.py --serial PHONE_SERIAL --config /private/staff/connection.json --device-image /private/staff/device.png
```

脚本通过 ADB `run-as` 写入本应用私有目录，会先停止 CES 应用，写完由工作人员重新打开。`run-as` 仅支持可调试应用；正式 release 的配置需由受控部署流程预置，不能通过观众界面修改。不要用 `pm clear` 替代 Reset demo，否则会同时清除工作人员鉴权和硬件配置。

正式设备 PNG 的位置为 `files/jackery-ces/device.png`。图标需由品牌方提供可编辑矢量或平台导出资产，替换 `app/src/jackeryCes/res/drawable/jackery_brand_placeholder.xml` 及引用的 launcher/splash 资源；不得把当前文字占位当作已选定品牌图标。

设备遥测字段、时间戳新鲜度和重置细节见 [硬件接口与重置说明](jackery-ces-device-and-reset.md)。当前 `GET statusUrl` 协议是待厂商对接的应用侧契约；若实际协议不同，只调整这个遥测 adapter，继续复用同一个 UI 状态 owner。

## 构建与检查

一个 Gradle 调用只能构建一个 edition，避免共享 Flutter library 把另一版本的界面混入包内。具体任务会自动设置对应 Flutter 入口与 `JACKERY_CES` 编译开关。

```bash
# 按需构建，不是本次自动验收中已产出的安装包
./gradlew assembleDevelopJackeryCesDebug
./gradlew assembleProductionJackeryCesRelease

# Native 专项检查（JDK 21，与嵌入终端已有 class target 一致）
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon :app:testDevelopJackeryCesDebugUnitTest --tests 'cn.com.omnimind.bot.jackery.*' --tests 'cn.com.omnimind.bot.agent.runtime.AcpMaintenanceAdmissionTest' --tests 'cn.com.omnimind.bot.agent.runtime.LocalAcpDemoResetCancellationTest' --tests 'cn.com.omnimind.bot.agent.AgentConversationModePolicyTest' --tests 'cn.com.omnimind.bot.agent.runtime.NaItemProjectionTest'

# 使用 ui/.android/local.properties 指定的 Flutter SDK
cd ui
flutter analyze --no-fatal-warnings --no-fatal-infos
flutter test --dart-define=JACKERY_CES=true test/config test/features/jackery test/services/jackery_device_status_test.dart test/services/jackery_demo_reset_test.dart test/features/home/pages/chat/jackery test/theme/jackery_ces_theme_test.dart test/widgets/jackery_ces_settings_test.dart test/widgets/jackery_ces_surface_test.dart test/widgets/omnibot_error_widget_test.dart
```

本次没有制作/安装 APK，没有浏览器或手机运行验收。自动检查范围包含 CES UI/未知遥测、受限模型/路由、重置等待和失败、历史写入竞态、官方取消不合成终态，以及标准版聊天/输入/侧边栏/Na 的回归。最终实机验收必须使用确认素材及指定设备，检查英文身份回复、联网搜索来源、设备状态变化、失联/重连、后台回前台、正在回复时 Reset demo，以及重启后清理内容不复现。

最终检查结果：

| 检查 | 结果 |
| --- | --- |
| 上述 CES Flutter 专项组合 | 37 项通过 |
| Native 专项（JUnit XML 汇总） | 30 项，0 failure、0 error |
| 标准版聊天/协调器/输入/侧栏/Na 回归 | 169 项通过 |
| 历史/图片/文件预览回归 | 17 项通过 |
| 会话持久化生命周期（含延迟创建/更新的重置保护） | 11 项通过 |
| Office/资源布局和服务，CES 与标准版分别检查 | 各 19 项通过 |
| 标准版主题/共享表面/错误提示 | 10 项通过 |
| 全量 Flutter analyze | exit 0、无 error；保留仓库现有 warning/info，未扩散清理 |
| git diff --check | 通过 |
| 预置脚本 | dry-run 校验通过，损坏 JSON 被拒绝且不输出凭据；未执行手机写入 |

重置的附加失败保护：删除前先验证设备配置；历史创建/更新请求在原生入口同步登记，等待排队写入和 Flutter 存储尾部结束才删除。部分内容已删除后若后续失败，丢弃旧 Flutter projection、防止复活，同时继续报告失败；错误提示不包含私有配置内容。

本分支保留开始时仓库已存在的 Na 开发改动；未把那些改动当作本次新功能重写。
