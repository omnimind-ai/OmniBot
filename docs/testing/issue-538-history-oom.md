# Issue #538：历史工具记录读取导致 Java 堆耗尽

调查日期：2026-09-23。对应 [issue #538](https://github.com/omnimind-ai/OmniBot/issues/538)。

## 发布包证据

分析 GitHub Release 的 `OpenOmniBot-v0.6.3.1-standard.apk`，未用其他构建的 mapping 猜测类名。

- APK SHA-256：`d263a932aacaa59bc3958ccf6ec58287b3944acf66448d5b8218d6efef79bfb3`，与 release asset digest 一致。
- DEX `.source`：`r8-map-id-bf610230afafc604565ed1463a3ad4eaee330e4783645e07c432c95a1064140c`，与报告一致。
- 使用 `apkanalyzer dex code --class zn <apk>`（以及 `ny`、`op`、`xo`）检查实际字节码、SQL、字符串常量及调用关系。

| 报告中的帧 | 发布包字节码与源码对应 |
| --- | --- |
| `zn.X` | `AgentConversationEntryDao.hydrate` 内的 `fullText`：读取 32 KB 的数据库 BLOB 分片，再 `ByteArrayOutputStream.write(chunk)` |
| `zn.Y` | `hydrate`：恢复 summary 和 payloadJson |
| `zn.T` | `getLogicalThreadPage`：逐项 hydrate，但将完整字符串累积为一页 List |
| `op.d` | `AgentConversationHistoryRepository.buildToolCardMessage`：解析原文、构造预览、原文转 UTF-8 计算 SHA-256、写文件并生成 artifact |
| `ny.b / ny.r / ny.i` | `AgentWorkspaceManager.buildArtifactForFile / shellPathForAndroid / isWithin` 路径转换及检查 |
| `xo.t` | 历史消息列表加载协程，调用 `op.d` 构造工具卡片 |

两次崩溃均属于历史展示读取链。第二次是生成完整记录附件路径时的小分配失败，并非证据表明有目录递归扫描。崩溃栈也不足以证明常驻内存泄漏或特定 ROM 问题。

## 原因与修复

数据库分片仅绕开了 CursorWindow 的单行大小限制。原实现随后把全部分片拼回连续 byte array，并将同页所有原文保留在 List 中。工具卡片显示摘要前，又完整解析 JSON、复制输出字符串和编码原文计算摘要。因此已有的 UI 截断和文件 offload 发生得太晚。

修复保留原有历史所有权、分页 SQL 的模式去重、ACP 身份及完整数据库原文：

1. 展示读取先获取 slice，逐条处理。大型工具记录按 32 KB 分片直接写临时文件，同时计算 SHA-256，成功后才发布内容寻址文件。
2. 从文件提取有界展示字段。先限制单个 JSON 值的大小，再交给 Gson；仅换成 JsonReader 仍会在 `nextString()` 时创建巨大字符串。超大字段提供完整记录附件入口。
3. 分页 lookahead 只读取 slice，不恢复不可见记录。工具摘要也只读取有限展示文本。工具记录的展示投影不回写数据库；既有历史中断归一化只条件更新对应 header，不覆盖工具原文、原始摘要或模型回放数据。
4. 仍需完整字符串的其他 DAO 调用使用分段 Buffer，消除 ByteArrayOutputStream 的连续扩容副本。这些完整读取本身不宣称具备恒定内存；本次重点消除已定位的展示链问题。

## 回归验证

最终结果：57 项 JVM 测试（测试堆上限 128 MiB）及 1 项 adb/Room 实机测试全部通过，`git diff --check` 通过。16 × 12 MiB 的展示投影压力用例通过；最终联合 Gradle 验证为 `BUILD SUCCESSFUL`。

- 原分配方式的独立 JVM 复现：16 条、每条 12 MiB，`-Xmx128m`，第 9 条出现 `OutOfMemoryError`。它复现的是发布包 `fullText + page.map(hydrate)` 的分配方式，不等同于复现用户整段操作。
- `ConversationEntryTextTest`：UTF-8 分片边界、512 MiB 增量写入、缺失/越界分片、inline 字节长度及协程取消。
- `AgentHistoryDisplayProjectionTest`：转义字符和 Unicode、嵌套值及过深结构、大字段之后的 ACP 身份、预览预算隔离、完整文件字节及 SHA-256、一致内容复用、写入失败清理、16 × 12 MiB 历史展示压力测试。
- `ConversationEntryStreamingTest`：在独立 Android Room 数据库里读取超过 4 MiB 的工具记录，验证实际分片流的 SHA-256、header 状态更新后的原文不变和过期更新拒绝。测试只使用临时数据库，并在结束时删除。

测试设备为 PJD110 / Android API 36，报告设备为 iQOO 9 Pro / API 35；未声称完成该机型的长时间随机崩溃复现。未替换手机上现有 OmniBot 应用。

限制测试 JVM 堆的临时 Gradle init script（不是 Gradle daemon 的堆参数）：

```groovy
allprojects {
    tasks.withType(Test).configureEach {
        maxHeapSize = '128m'
        maxParallelForks = 1
    }
}
```

```sh
./gradlew --no-daemon -I /tmp/omnibot-issue-538/limited-heap.gradle \
  :baselib:testDebugUnitTest --tests '*ConversationEntryTextTest' \
  :app:testDevelopStandardDebugUnitTest \
  --tests '*AgentHistoryDisplayProjectionTest' \
  --tests '*AgentConversationHistorySupportTest' \
  --tests '*AgentHistoryToolOutputProjectionTest' \
  -Ptarget=lib/main_standard.dart

./gradlew --no-daemon :baselib:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=cn.com.omnimind.baselib.database.ConversationEntryStreamingTest
```
