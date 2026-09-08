# PR #522：0.6.1 ACP、内容完整性与聊天体验修复

## 当前交付状态

本次按整个分支相对 main 的共同祖先重新统计，不只统计最近一次 Markdown 修复。PR 保持草稿：当前存在测试失败，不宣称已可合并或发布。停止新增功能和进一步重构，本次收尾仅整理、验证、提交现有实现。

版本为 0.6.1（versionCode 11），从 0.6.0.3（10）升级。此前版本曾覆盖安装成功，但不能代表本次最新提交已安装或完成真机验收。

## 累计改动范围

统计基线：`cc5b976578fcefe8d2168c01c846e080bb1e8fc1`（本次 fetch 后 main 与分支的共同祖先）。

| 分类 | 文件数 | 新增行 | 删除行 |
| --- | ---: | ---: | ---: |
| 生产实现／配置 | 138 | 4244 | 8650 |
| 测试／脚本 | 89 | 7329 | 2446 |
| 文档 | 5 | 随交付记录更新 | — |
| 合计 | 232 | 以 GitHub 最终 diff 为准 | 以 GitHub 最终 diff 为准 |

生产实现／配置净减少 4406 行；测试增加不等于新增业务框架。分类按路径统计，属于审阅辅助，不是代码质量评分。本次提交包含之前尚未提交的配置、工作区及渲染修复；不提交 Flutter 崩溃日志、APK、密钥或设备数据。

## 功能变化

### 1. 内容与历史更完整

- 工具结果、终端、文件、浏览器观察、附件、技能、memory 检索与历史恢复移除一批应用默认字符数、数量、深度和截断策略。
- 历史 DAO 不再只投影截断后的内容；保留推理与工具结果，减少“执行过但恢复后事实丢失”。
- Word／Excel／PowerPoint 预览不再按固定段落、工作表、行列、幻灯片或单元格字符数裁剪。
- WebChat 文件读取、终端输出、MCP 结果等入口同步减少默认裁剪。摘要卡可以短，但不能因此丢掉原始详情。
- memory 写入保持显式，定时整理为可选；移除隐藏失败学习写入及检索／注入裁剪，不迁移或删除已有 memory 数据。

“不设应用默认上限”不代表设备资源无限。系统权限、协议校验、取消、调用者明确指定的范围及实际环境限制仍存在。权限与安全保护不能因为是自定义实现就一概删除；大文件内存、磁盘增长和资源耗尽仍需真机测试。

### 2. 执行结果只由现有 ACP 请求所有者裁决

- 保持 Conversation → ACP Session → 请求 → Item 的既有归属，完成、取消、失败收敛到所属 prompt response／真实异常及现有显式取消入口。
- 删除旧 state/thread/turn 通知、快照及页面 catch 对同一请求的重复结束判断；不再额外插入第二份错误消息。
- session/cancel 应答不冒充 prompt 已完成；迟到结果不能结束下一次请求或写入另一个会话。
- 取消保留已输出文本和已执行工具事实，不重放已执行操作。准备阶段停止后处理迟到创建的会话。
- 历史与远端快照负责合并，不凭快照推断当前请求终态、不擦除已提交的用户消息。
- 删除固定模型轮数、自动摘要重试、自动长度续传和修复工具调用用的伪用户提示；真实传输重试仍由既有传输层负责。
- 删除静态工具分类／可见性和子 Agent 能力裁剪，能力来源收敛到当前 Harness／工具环境。

此次统一的是请求终态决定权，不代表所有旧内容字段、历史兼容或第三方桥接均已删除。

### 3. 工具工作卡与真实执行一致

- 流式工具调用拥有真实 id 和名称后即可展示当前参数，未完整 JSON 也可以保真显示；展示不等于执行。
- pending 显示为准备中，不再推断为正在执行或等待用户审批；真实权限请求沿既有权限卡处理。
- 请求结束不再批量伪造工具 success／interrupted；保留最近真实状态。
- 网络中断、模型被拒绝和工具参数不完整显示更准确的原因，不再无依据声称“已自动重试一次”；未完整调用不拿来执行。

### 4. Provider、Adapter 与会话配置

- 模型缓存跟随保存后的 Provider identity／revision；合法空目录可以替换旧缓存，临时凭据查询不污染保存配置，迟到响应不覆盖新配置。
- 自定义 Adapter 保留用户命令、参数、环境和 API 配置，不因名称含小万而误删；保存配置不主动打断正在运行的进程，下次进程启动使用新配置。
- 模型选择按 session 保存并核对 Provider 归属。聊天已有输入区接入模型与参数弹层，读取 ACP configOptions 并沿 setSessionConfigOption 写入；外部选项不在 Flutter 另造厂商生命周期。
- 当前内置小万实际仍声明 default/none/low/medium/high/max 推理档位，default 不发送 override，切换模型重置为 default。这是本地声明，并非已验证所有 Provider 都支持全部档位；此处以当前代码为准，修正较早“完全不声明档位”的阶段记录。
- 普通会话启动读取缓存；用户显式刷新配置可刷新模型目录。当前 Node 源码契约与该显式刷新路径冲突，见失败清单。
- 弹层避让键盘；工作区目录主刷新改为异步 IO 并忽略过期目录结果，避免页面刷新／滑动期间同步扫描，保留加载与错误状态。并非所有工作区同步 IO 已清除。

SDK 仍为 ACP Kotlin 0.30.1；Claude ACP bridge 固定 0.74.0、Codex ACP bridge 固定 1.10.0，其他目录项仍可能为 latest/next。不宣称“全部换成官方新内核”或“任意 Harness 已验证”。已有配置审计、加密快照与回滚属于宿主配置能力，不是官方 ACP 生命周期；平台安全实现仍待真机验收。

### 5. 实时 Markdown 渲染

- 删除输出结束才显示真实表格的纯文本预览、按旧字符偏移拆分前后段、隐藏未完整表格文本的分支。
- 当前全文交给已有 Markdown 包装组件，表格语法完整即可显示，后续行继续更新，未完整内容保留原文。
- 此项生产文件净减少 281 行，没有新增解析器、依赖或 ACP 生命周期。
- 保留公式、资源链接、选择复制与现有自定义 Markdown 扩展；并非全量替换成原生解析器。潜在自动格式修复风险另行评估，本次不继续重构。

## 本次重新验证（不累计重复运行次数）

| 验证层 | 本次结果 | 说明 |
| --- | --- | --- |
| Flutter 全 test 目录 | 1076 通过，32 失败 | 1108 项；替代此前定向测试数字，不宣称全部通过 |
| Android app JVM 全量 | 885 通过，2 失败 | 887 项，126 suites，0 errors／skipped |
| Node 统一入口 | 57 通过，1 失败 | 58 项；失败后统一脚本提前退出，其他层单独运行 |
| WebChat | 12/12 通过 | typecheck 与 build 通过；未做浏览器验收 |
| APK 构建 | 通过（14 秒） | 测试与构建联合命令先在测试阶段失败；独立 assemble 成功，不等于测试通过 |
| diff 格式 | git diff --cached --check 通过 | 不等于完整代码或安全审计 |

此前渲染 37 项和相关会话／工具 313 项定向通过，已经包含在全量执行中，不再次相加。未执行真实 Provider smoke、最新 APK 手机安装、长时间大输出压力、历史／memory 真机恢复。

### 当前失败清单

- Android：AgentTerminalToolDefinitionTest 的 Ubuntu、Alpine 两项名称／发行版定义断言失败，与现有远端 CI 两项失败一致。
- Node：Xiaowan session startup uses the Provider cache and restores session-owned selection。全文件禁止 refreshAndGetModels 的源码断言命中显式刷新方法；需核对并按实际启动／刷新语义修正测试或实现，不能直接忽略。
- Flutter：chat_message_list_test 8 项（终态展示、折叠与头像）；scene_model_setting_page_test 10 项（目录加载、刷新、选择）；chat_history_page_test 1 项；agent_request_card_test 2 项；onboarding_choice_page_test 1 项；omniflow_execution_center_page_test 3 项；omnibot_error_widget_test 1 项；image_preview_overlay_test 4 项；app_background_widgets_test 2 项。

这些是失败分布，不是根因结论。尚未逐项区分产品回归、测试夹具过时和本机环境问题，也未证明它们全部来自本 PR。不得以“都是旧测试”作为合并依据。

### 持久化复跑入口

```bash
# 既有定向入口；当前会在 Node 失败后退出
bash scripts/test-agent-runtime.sh --offline

# 本次全量命令，各层独立运行以取得完整结果
(cd ui && flutter test --reporter expanded)
./gradlew --offline --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest
./gradlew --offline --no-daemon --no-parallel :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
bash scripts/test-agent-runtime.sh --offline --skip-gradle --skip-flutter --skip-webchat
(cd webchat && pnpm test && pnpm run typecheck && pnpm run build)
git diff --cached --check
```

## 合并前条件

- 分类处理上列测试失败，并在最新提交重跑 CI；不以扩大重构、伪造状态或放宽断言达成绿灯。
- 最新 APK 覆盖安装后验证连续对话、工作卡、流式表格、取消／会话切换与模型配置。
- 验证历史、memory、配置恢复和大输出实际资源表现。
- 保留必要安全校验，优先减少重复实现与错误推断，不把删除所有保护当作目标。

## 记录与边界

[当前实现与逐次修复记录](acp-runtime-custom-logic-inventory.zh-CN_副本.md) 保留历史过程；本页是最新累计交付状态，优先于旧阶段“全绿／已安装”陈述。[修改前审计](acp-runtime-custom-logic-inventory.zh-CN.md)与[阶段策略审计](acp-runtime-policy-audit.zh-CN.md)仅作归档。docs/harness-engineering.html 是静态说明，不是应用新页面。

此前手机覆盖安装成功属于旧构建证据，本次不据此勾选最新版本验收。PR 不包含密钥、签名配置、APK、构建产物或本地崩溃日志。
