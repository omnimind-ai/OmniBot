# 最新图片与文档失败后继续 — 2026-09-19

用户要求按模拟器真实任务验收。emulator-5580，Android 13 ARM64，0.6.3 (16) developStandardDebug，APK SHA256 `137b633b5599cb34ddf7722753141a71003e7d1c997a390172bf90cd36f35e2f`。沿用设备已有真实 GLM-4.6V Provider；未注入回复、未写入设备数据库。

## 图片：8/8 通过

- 使用系统选择器上传 `chat-upload-colors-second.png`。模型实际回答 green, yellow；唯一 marker 对应单个用户请求和正式 end_turn。
- 重启后正确回复仍在，模型设置仍显示 GLM-4.6V。
- 第一次准备脚本没有在 Recent 首屏找到旧日期图片，超时退出，未发送。随后通过当前界面的 Images 分类选择确切文件名，真实任务才开始；没有猜坐标或更换图片。
- 证据：`artifacts/chat-image-20260919/current/result.json` 及逐步截图/正式状态断言。
- 可执行入口：`prepare-chat-image.py SERIAL chat-upload-colors-second.png`，随后 `verify-agent-user-journey.mjs SERIAL scripts/fixtures/agent-user-journeys/chat-upload-colors-second.en.json OUTPUT`。Recent 未显示旧文件时，需要通过实际分类选择，不得把准备失败视为识图失败。

## 损坏 PDF → 明确反馈 → 同会话下一条任务：8/8 通过

- 新增合成截断样本 `scripts/fixtures/chat-upload-broken.pdf`，经系统选择器实际上传。
- 真模型调用一次 file_read，工具 success=false、contentAvailable=false、errorCode=document_parse_failed，无正文；模型向用户说明无法读取，并正常结束当前回合。
- 同一聊天发送独立算术任务 17+25，回答42、正式完成；这一轮没有任何工具事件，没有偷偷重试上一个附件任务。
- 重启后成功回复仍在。过程中没有新增 App 崩溃。
- 证据：`artifacts/document-live-20260919/failure-recovery/result.json`。
- 长期执行入口：`prepare-chat-image.py SERIAL chat-upload-broken.pdf`，随后 `verify-agent-user-journey.mjs SERIAL scripts/fixtures/agent-user-journeys/chat-document-failure-recovery.en.json OUTPUT`。断言扩展既有 `assert-agent-turn-outcome.py`，按同轮身份检查实际失败与后续成功，不引入新测试框架。

边界：这证明一次工具解析失败不会卡死当前聊天，不等于所有网络、Provider、进程错误均已验证。尚未覆盖全部图片格式与扫描 PDF OCR。OmniFlow、Vibe、远程连接和最终 Release 的整体目标仍进行中。
