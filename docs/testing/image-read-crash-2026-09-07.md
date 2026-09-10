# file_read 图片读取内存溢出与闪退验证

## 范围与证据

用户报告来自另一台手机：图片短暂显示后 APP 退出。该手机未连接，尚无其原始图片、机型或 logcat。本次在隔离的 Android 13 ARM64 AVD `emulator-5560` 上复现并修复了一条相符的内存错误链路，不等同于原机验收。

- AVD：OobCleanInstall20260907，2 GB RAM，应用 Java heap growth limit 201326592（192 MiB）。
- 应用：`cn.com.omnimind.bot`，develop standard debug。
- Provider：本机受控 HTTP/SSE fixture，测试配置名 `oob-emulator-regression`；没有调用外部模型或使用真实 API key。
- 小图：1272×2800 JPEG，1154161 bytes；修复前完整工具读取与模型续答成功。
- 大图：3000×2000 随机 RGB PNG，18027082 bytes。
- 大图 SHA-256：`04c39e50a1b369a3580e9b0723f213bc980642599269530a4e48ebdadd1b5956`。

## 已证实的原因

1. 原图片路径先完整读取文件，再生成多份 Base64 字节/字符串；已规范化的数据 URL 也会被重复复制。模型请求依次经过整份 JSON 编码、解析、重编码。
2. 14:01:29，原请求编码在 `HttpAgentLlmClient.streamRoutedTurn` / `JsonToStringWriter.ensureTotalCapacity` 申请 108550104 bytes，触发 OOM，导致 Loopback ACP 中断。
3. 消除该处大块请求缓冲后，14:16:16 进一步复现真正的进程退出：`FATAL EXCEPTION: main`，`StandardMessageCodec.readValueOfType` / `StringFactory.newStringFromUtf8Bytes` 申请 96156472 bytes 失败。图片 Base64 被 ACP rawOutput 带入 Flutter 卡片及嵌套原始结果，再随着历史消息 JSON 回传原生层，造成多份巨大字符串。历史持久化入口为 `ConversationHistoryService._replaceNativeConversationMessages` / `replaceConversationMessages`；主线程在进入业务处理器前的 MethodChannel 解码阶段已经崩溃。
4. 独立错误分支：图片编码失败返回 null 后，`file_read` 原来还会把二进制图片当作文本读取，进一步放大失败。

主线程崩溃堆栈：[reproduced-main-thread-crash.txt](artifacts/image-read-2026-09-07/reproduced-main-thread-crash.txt)。

## 修复边界

- `AgentImageAttachmentSupport`：从文件流直接编码 Base64；已规范化 URL 保持原字符串引用。保留原图字节，不缩图，不新增附件大小配额。
- `AgentWorkspaceAttachmentSupport`：普通文件读取按文件长度分配，避免 ByteArrayOutputStream 扩容再复制。
- `FileToolHandler`：图片读取失败走现有工具错误结果，不回退为文本读取。
- `XiaowanAcpConnection.toolResultAcpPayload`：`file_read` 的本地图片复用已有 ArtifactRef/androidPath，通过现有 `imageUrl` 展示。图片二进制不进入 UI rawOutput；模型工具续答继续使用完整 `imageDataUrl`。没有本地文件的其他工具图片仍保留原行为。
- `HttpAgentLlmClient` / `HttpController`：OpenAI Chat Completions / Responses 规范化复用 JSON tree，最终通过 OkHttp 流式写出；保持 Content-Length，不依赖服务商接受 chunked 上传。现有字符串调用入口及测试注入兼容，不增加重试或生命周期所有者。
- 请求诊断日志保留结构与图片类型，省略内联图片二进制；实际网络请求不变。

本次没有新增 ACP 状态、重放、回调总线或第二套 reducer；没有变更模型配置、沙箱权限或长期记忆 API。

## 验证

- 113 项 Kotlin 相关测试通过：图片编码、附件读取、24 MB 内联图 JSON/HTTP 分段写出及重发、UTF-8 Content-Length、ACP 图片结果、ACP presentation、LLM SSE/路由及 OpenAI Responses/headers。
- 192 项 Flutter `agent_event_reducer_test.dart` 测试通过。
- 增加大图传输的分段写入断言，完整 HTTP body 不进入一个大缓冲；验证原图字节与 padding、已规范化 URL 的引用复用，以及 UI 结果大小与模型图像内容互不影响。
- 首次修复后大图读取：模型续答请求 24086400 bytes，fixture 校验原图 SHA-256 一致，UI 显示 `OOB_IMAGE_LARGE_DONE`、读取文件 Success。
- 展开工具记录可见图片缩略图；点击后全屏预览正常。
- 强制结束 APP 后重启：历史完成回复和图片记录保留；展开后图片从本地文件显示。重启本身没有触发模型请求或工具重放。
- 重启后再次读取同一张图：续答请求 24095699 bytes，SHA-256 仍一致，第二次显示 `OOB_IMAGE_LARGE_DONE`。两个成功进程 PID 10790、11345 均未出现 OOM/FATAL。
- 已安装模拟器，不代表已安装用户反馈问题的另一台手机。
- 截图中的较早 Interrupted 记录来自保留的失败复现，未删除历史以掩盖问题。

截图：[完成与缩略图](artifacts/image-read-2026-09-07/large-image-completed.png)、[全屏预览](artifacts/image-read-2026-09-07/large-image-preview.png)、[重启后记录](artifacts/image-read-2026-09-07/large-image-after-restart.png)。

## 重复运行

使用隔离模拟器，先构建/安装 debug APK 并完成工作区初始化。不要把 fixture 配置写到用户主力手机。

生成压力测试原图（需要 Pillow）：

```bash
python3 - <<'PY'
import random
from PIL import Image
Image.frombytes('RGB', (3000, 2000), random.Random(47).randbytes(18000000)).save('/tmp/oob-large.png')
PY
adb -s emulator-5560 push /tmp/oob-large.png /data/local/tmp/oob-large.png
adb -s emulator-5560 shell run-as cn.com.omnimind.bot mkdir -p workspace/oob-image-repro
adb -s emulator-5560 shell run-as cn.com.omnimind.bot cp /data/local/tmp/oob-large.png workspace/oob-image-repro/large.png
OOB_IMAGE_TEST_FILE=/tmp/oob-large.png node scripts/fixtures/image-read-provider.mjs
```

另一个终端配置隔离 Provider，选择 OmniAi / 小万模式并从空草稿发送：

```bash
OMNIBOT_TEST_API_KEY=fixture-only OMNIBOT_TEST_BASE_URL=http://10.0.2.2:18769/v1 OMNIBOT_TEST_MODEL=gpt-4o node scripts/configure-agent-test-provider.mjs emulator-5560
node scripts/send-agent-test-message.mjs emulator-5560 OOB_IMAGE_LARGE
```

fixture 必须收到一个 `file_read` 的图像工具结果、原图哈希一致并返回 `_DONE`。同时记录 crash buffer、检查图片预览，重启后再读取一次。小图测试可把 JPEG 放到同目录 `small.jpg` 后发送 `OOB_IMAGE_SMALL`。

## 最终安装包

- 构建成功：`/tmp/oob-image-final-build.log`（113 项原生测试 + APK）。
- 已安装并用仓库中的 fixture 再次通过 18 MB 原图测试：续答请求 24104998 bytes，原图 SHA-256 一致，PID 12182；界面显示第三次 `OOB_IMAGE_LARGE_DONE`（8.2s），无 OOM/FATAL。
- APK：`app/build/outputs/image-read-fix-20260907/OpenOmniBot-image-read-fix-debug.apk`。
- APK SHA-256：`d8a92ffdee76cef9e75c9433fbcdbeecc327a24d33a854213d7126db8803900e`。
- 最终版本保留 HTTP Content-Length，并通过 Unicode/原图分段写入测试。

最终界面：[final-apk-completed.png](artifacts/image-read-2026-09-07/final-apk-completed.png)。测试结束后停止本机 fixture 服务；模拟器保留测试历史和隔离测试配置，重新发送前需按上面的命令启动 fixture。
