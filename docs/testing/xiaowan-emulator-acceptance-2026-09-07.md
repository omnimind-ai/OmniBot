# 小万文件读取模拟器验收

用户本轮明确要求在模拟器测试。本报告只代表模拟器验证，不代表实体手机验收，也不修改“所有 bug 需真机测试”的长期默认规则。

## 环境与方式

- Android 13 ARM64，隔离 AVD `emulator-5560`，2 GB RAM，应用 Java heap growth limit 192 MiB。
- 已安装 APK SHA-256 与本轮构建文件一致：`8698d4fbd47fb80a69f90ededc529d62484ac27a9a64180a85dd7fb063338833`。
- 小万模式，测试 Provider `oob-emulator-regression`，本机受控 HTTP/SSE 接口；没有调用外部真实模型。
- 通过实际 UI 新建对话、发送消息、点击停止、切换对话和重启。文件读取、模型请求、ACP 状态更新和历史存储均由 App 自身执行。
- 使用每轮唯一标记，防止旧成功消息满足新测试；测试后端验证文件内容、图像哈希及重复结果。保存截图与步骤报告。

## 可重复运行

使用前两份文件/图片报告中的隔离模拟器准备方式，将 `/tmp/oob-file-repro` 的样本和 `/tmp/oob-large.png` 放到 App 测试工作区。不要向主力手机写入测试 Provider 配置。

```bash
OOB_FILE_TEST_DIR=/tmp/oob-file-repro OOB_FILE_TEST_IMAGE=/tmp/oob-large.png \
  node scripts/fixtures/file-read-provider.mjs

# 在小万中新建空白对话后执行；脚本自己添加唯一 runId。
node scripts/verify-agent-user-journey.mjs emulator-5560 \
  scripts/fixtures/agent-user-journeys/xiaowan-file-read-regression.en.json \
  docs/testing/artifacts/xiaowan-emulator-acceptance-2026-09-07
```

`CANCEL` 场景先实际读取 HTML 首页，再让模型续答保持等待，通过 UI 停止。它测试的是文件读取后的续答取消，不是慢磁盘 I/O 中途取消。

`HOLD` 场景同样在读取后等待，用于切换会话。测试后端可通过仅监听本机的 `POST /fixture/release?marker=...` 返回该等待请求的正常完成响应；没有绕过 ACP 直接向 UI 注入结果。

## 初次测试脚本问题

首轮两个 HTML 场景通过，大图续答时测试后端错误地将图文混合 content 数组当字符串 JSON 解码，返回 HTTP 500；App 显示本轮失败，进程未退出。此项属于测试夹具错误，不能算作 App 图片回归。已修正为从图文数组提取 text envelope，并保留图片 SHA-256 校验。

首轮截图与请求日志保留在 `artifacts/xiaowan-emulator-acceptance-2026-09-07-fixture-failure/`，随后以新的 runId、新对话重跑完整流程，没有删除原失败对话。

## 实际结果

本轮场景通过了模拟器验证，包含自动步骤和明确记录的人工补测；不是一份从头到尾全绿的自动化报告。没有为通过测试新增生产适配器或修改外部 Harness loop。

| 场景 | 检查及结果 | 证据 |
| --- | --- | --- |
| 大 HTML 及重复读取 | 16,777,301 字节样本；两轮分别核对首页 65,536 字符、下一页 65,536 字符、末尾 128 字符，内容与 offset/nextOffset/hasMore 均正确 | 主目录 `result.json` 步骤 1–4；Provider 请求 1–8 |
| 大图与重复读取 | 18,027,082 字节 PNG；初次及重启、切换会话后再次读取，模型收到的原图 SHA-256 均为 `04c39e50a1b369a3580e9b0723f213bc980642599269530a4e48ebdadd1b5956` | 主报告步骤 5–6；`image-preview-complete/`；请求 9–10、21–22 |
| 图片显示 | 展开工具结果可见缩略图，点击后进入全屏图片，返回后界面仍可操作、进程 PID 保持 20218 | `image-preview-complete/expanded.png`、`fullscreen.png` |
| 二进制文件识别 | PDF 类型样本返回 binary 元数据、不作为文本解码；不代表 PDF 解析或阅读器验收 | 主报告步骤 7–8；请求 11–12 |
| 取消及继续使用 | HTML 首页读取后续答等待，点击 Stop；HTTP 流断开，官方 PromptResponse 为 cancelled，界面保留部分输出并恢复 Send；随后读取指定文本行成功 | 主报告步骤 9–14；`12.png`；请求 13–16；`lifecycle.log` |
| 重启恢复 | 两次实际进程重启后，完成结果和取消状态均恢复；没有重新调用模型 | `restart/result.json`、截图；PID 15306 → 19989 → 20218；恢复记录 |
| 会话并行及归属 | A 读 HTML 后等待；新 B 读取指定文本行并完成，模型请求不包含 A 的标记；在 B 页面让 A 正常结束，B 没有出现 A 的内容，返回 A 可见其完成结果 | `switch-a/`、`switch-b-complete/`、`switch-return/`；请求 17–20 |

全部受控请求共 22 次，文件结果断言均通过，没有重复工具结果或 Provider 校验错误。检查本轮对应 App PID、时间范围内的日志，未发现 `OutOfMemoryError`、`FATAL EXCEPTION` 或 `ANR in`；见 `log-review.json`。这不是对任意文件大小、全部格式、其他设备或真实模型服务的保证。

## 自动化中断及补测

- 主流程步骤 15 在 ADB 调用处报错。旧报告只记录调用点，不能反推出确切的 ADB 错误原因。实际进程已从 15306 变成 19989，界面恢复了原有历史。保留该失败报告，并独立再次执行重启、完成消息和取消状态断言，三步全通过。已补充 runner 的 code/status/signal 记录，避免下次丢失进程错误信息。
- 切换到 B 后，输入脚本记录了点击发送，但截图及新的 UI 快照证明草稿仍未发送，Provider 也没有 B 请求。停止等待脚本，使用新的语义 Send 控件发送保留的草稿一次，随后完成 B 的结果与会话隔离检查。
- 已给输入脚本增加“点击后输入框必须清空”的观察断言，不自动重发。补测图片时该断言再次发现未发送草稿，正确返回失败；检查确认没有该消息请求后，人工点击 Send，原图校验和图片 UI 检查通过。没有将未发送的步骤记为成功。
- 两次未发送现象的具体 UI 原因未定位；当时键盘与文字选择浮层可见。此项作为输入自动化限制保留，不归因于文件读取，也不据此修改 App 生命周期。

证据目录：`docs/testing/artifacts/xiaowan-emulator-acceptance-2026-09-07/`。本轮修改仅涉及测试夹具、测试脚本和报告；安装包为本报告记录哈希的既有修复构建。
