# Release 裁剪兼容性审计（2026-10-29）

对上一轮 **36.728 MiB** 的压缩 Release 做成品审计后，发现了实际 Gson 反射契约回归：部分反序列化目标类及泛型信息被裁剪，另有 Kotlin 默认构造器被移除。首页能启动、插件能注册和字段名仍存在，都不足以排除这些问题。

修复前 APK 的 SHA-256 为 `e48f8130e054cae47a1f4317c55f73d3bdceb59d34520d9c4a31592e1adf21fe`，实际大小 38,512,421 B（36.728 MiB）。已在这个已安装的 Release 上实际执行 25 项独立 instrumentation fixture，结果为 **19 项通过、6 项失败**，六组反射问题全部复现。修复后 Release 已构建，为 38,522,525 B（36.738 MiB），仅增加 **10,104 B / 9.867 KiB / 0.0262%**。修正版已覆盖安装，使用完全相同的独立测试 APK，**25 项全部通过**；测试工具已移除。两版各 5 次进程冷启动，中位数为 **440 → 500 ms**，本轮观察到增加 60 ms，未把启动速度写成完全不变。

## 已定位的反射问题

前三项已在实际 Release DEX 中核对到错误控制流，后三项已核对源代码与成品构造器契约。六组问题均由修复前真机 fixture 进一步确认：它们直接使用已安装目标 APK 的类和 Gson，但只解析合成记录，不调用真实业务 store。

| 范围 | 实际成品证据 | 功能影响 |
|---|---|---|
| 精确闹钟记录 | `ExactAlarmRecordRaw` 类不存在；原 `TypeToken` 泛型变为 `List<Object>`；生产读取循环遇到非空元素时进入 `ClassCastException` 路径 | 非空闹钟记录无法正常恢复 |
| 内置技能清单 | `BuiltinSkillManifest` / `BuiltinSkillAsset` 类身份消失；生产代码把非空解析结果转入异常分支并捕获；APK 清单实际含 5 个技能 | 清单被视为空，内置技能不能正常初始化 |
| 官方 embedding 响应 | `EmbeddingResponse` / `EmbeddingItem` 类身份消失；生产 `embed` 对非空解析结果进入 `ClassCastException` 路径 | 即使网络返回成功，也无法取得向量 |
| ACP Agent 健康记录 | `AcpAgentHealth` 无参构造器被移除 | Gson 使用不执行构造器的分配路径时，缺少的 `capabilities` 变成 `null`，而非默认空映射 |
| 技能注册记录 | `SkillRegistryEntry` 无参构造器被移除 | 缺字段记录的 `enabled`、`source`、`installState` 失去 `true`、`user`、`installed` 默认值 |
| 官方 VLM 配置 | `OfficialVlmOperationConfig` 无参构造器被移除 | 缺少的 `apiBase`、`model`、`wireApi` 变成 `null`，后续规范化逻辑不能按非空字符串处理 |

生产读取边界分别位于 `AgentAlarmToolService`、`AgentSkillRuntime`、`PlatformEmbeddingGateway` 及对应配置 owner。正常类合并/内联本身不是缺陷；这里的判据是反射仍依赖目标类身份、泛型参数或构造器默认行为，而最终 DEX 已不能满足该契约。

## 修复范围

`app/proguard-rules.pro` 对 **20 个精确 DTO 类型**保留类身份、构造器与字段：加强 17 个已有活跃模型规则，并新增 3 个目标类型的保护。只保留字段名的旧规则无法阻止目标类被合并/裁剪或无参构造器被删除。

未列出的普通方法仍可裁剪和优化；没有全包关闭 R8，也没有恢复无生产调用者的旧模型。Release AOT、资源收缩与 DEX 压缩继续保留。无调用者的 `ModelSceneConfigCache.CacheData/Metadata`、旧终端反射入口等不因本次审计重新引入。

已构建的修复后 APK 与旧 Release 比较，未新增或删除 ZIP entries。除两份 DEX、对应 dexopt 文件和版本控制元数据外，assets、Android resources、原生库的解压后字节全部相同。这说明修复没有通过恢复大批资源来换取兼容性；下面的真机成品 fixture 进一步验证了对应运行时行为。

## 插件、JNI、资源与组件的成品核对

这部分比较原 Profile 与上述修复前 Release，结论仅覆盖表中边界；它与已经发现的 Gson 问题并不冲突。脱敏摘要见[成品核对 JSON](performance/release-shrinking-artifact-audit-2026-10-29.json)。

| 检查范围 | 结果 |
|---|---|
| Flutter 注册入口 | `GeneratedPluginRegistrant.registerWith` 保留；14 个插件类和无参构造全部存在，仍是独立的 FlutterPlugin 实现 |
| 关键 assets | 原 Profile 的 351 个 assets 在 Release 中逐字节相同；包括扫码模型、终端 rootfs/proot、OmniFlow ZIP、ACP 脚本与内置技能。只新增两个 dexopt 文件 |
| Manifest 组件 | 两侧 50 个组件的名称集合相同；49 个本地组件类存在。唯一不在 DEX 的 Google `ModuleDependencies` 两侧均为 `enabled=false` 元数据声明 |
| JNI 方法 | Termux 4/4、FlutterJNI 42/42、MMKV 82/82、Barhopper 8/8 方法签名保持一致；dartjni 的 6 个 Java JNI 导出及对应方法保留 |
| 原生库 | 只移除 Profile 专属 `libvmservice_snapshot.so`；`libapp`、`libflutter`、`libdartjni` 随 Release 模式变化，其余 9 个 SO 字节完全相同 |
| 动态资源 | 实际 DEX 中核对 12 处 `getIdentifier` 调用；应用与终端圆角查询指向系统 `android` 资源。其余为通用媒体、图片、Google 服务和 Flutter 本地化入口，未定位到具体缺失的应用资源目标 |

没有将以下正常优化误报为缺陷：Flutter 本地化回调合并到另一类，mapping 与实际方法仍可对应；未使用的 Camera SurfaceUtil、DataStore 多进程计数器及部分 Jansi native API 被裁剪；旧 xededitor 终端反射目标在原 APK 就不存在且调用者未被使用。SO 中保留未用 JNI 导出，不意味着对应 Java 类必须一直保留。

这些检查证明相应注册入口和资源仍在，不等于已经完成实际扫码、音视频播放、相册选择或终端运行时功能验收。

## 修复后实际 DEX 复核

独立读取修正版 APK 后，**20/20 模型、143/143 实例字段、11/11 必要无参构造器**全部通过。提醒记录的 `List<? extends ExactAlarmRecordRaw>`、技能清单的 `List<BuiltinSkillAsset>`、向量响应的 `List<EmbeddingItem>` / `List<Double>` 泛型均已恢复。三个生产解析方法均恢复具体类型读取与成功返回路径，不再含此前的必然 `ClassCastException` 指令。

构建、逐模型核对、哈希、体积与设备执行结果见[验证记录 JSON](performance/release-shrinking-validation-2026-10-29.json)。Release 构建成功（2 分 12 秒），ZIP 完整性和已有 Debug 证书签名校验通过。该段是成品静态复核；设备执行结果另列于下文。

## 直接针对已安装 Release 的验证方法

新增的[独立验证工具](../scripts/verification/release_artifact/README.md)通过目标 ClassLoader 执行 fixture，使用的是已安装并经过 R8 的目标 DEX。验证 APK 不重新打包目标类、Gson、Room 或 Flutter，不依赖重新编译 `:app` 来替代成品。

隔离措施包括独立 `:release_audit` 进程、普通 `android.app.Application`、不启动目标 providers，并在 fixture 前校验进程、Application 身份和目标非 Debug 标记，并记录待核对的 APK 哈希。JSON 输入均为合成记录；不读取真实历史或凭据，不调用生产 store getter，不写入原有配置。

两版实际执行覆盖反射模型/缺字段默认值、APK 内置技能清单、14 个插件构造、原生 PTY 中仅执行 `exit 7` 的子进程，以及 Room 生成 schema 和内存数据库 SQL。该范围不启动 Dart、不连接 Flutter engine、不激活相机、不运行 Agent，也不安装终端发行版。

早期一次尝试返回“找不到 instrumentation 信息”，未进入任何业务 fixture；该结果既不是测试通过，也不是模型回归的运行时复现。必须读取有效结果中的 `targetApkSha256`、每个 case 和失败数，不能以 shell 命令是否结束代替验收。

测试工具获准安装后，修复前有效运行返回的 `targetApkSha256` 与上述旧 Release 完全一致，进程为 `cn.com.omnimind.bot:release_audit`，隔离守卫通过。六个实际失败如下：

| Fixture | 修复前实际结果 |
|---|---|
| `alarm_record_generic_roundtrip` | 断言失败：记录被解码成 `com.google.gson.internal.LinkedTreeMap`，没有得到要求的 Raw 类型 |
| `bundled_skill_manifest` | `ClassNotFoundException` |
| `memory_embedding_response` | `ClassNotFoundException` |
| `agent_health_missing_field_defaults` | 断言失败：缺少的 `capabilities` 默认值变成 `null` |
| `official_vlm_missing_field_defaults` | 断言失败：缺少的 `apiBase` 默认值变成 `null` |
| `skill_registry_missing_field_defaults` | 断言失败：`enabled` 默认值丢失 |

19 个通过项包括隔离守卫、QuickLog 默认值、Provider 新旧字段兼容、14 个插件构造、返回码为 7 的原生 PTY 子进程和 Room 内存数据库 schema/SQL 检查。插件构造通过只证明这一构造边界，不能替代插件连接引擎后或完整功能的交互验收。旧版的 `INSTRUMENTATION_CODE: 0` 是 `RESULT_CANCELED`，不能当作整套通过。修复版返回 `RESULT_OK`（`-1`），25 项均通过且没有 `runnerError`，目标 APK SHA 与修正版完全一致。两项旧版 `ClassNotFoundException` 另有实际生产字节码必然抛错的证据，不是仅凭原类名不存在判定误裁剪。

## 修复前后结果与当前验收状态

| 项目 | 修复前 | 修复后 |
|---|---|---|
| 已构建 APK SHA-256 | `e48f8130e054cae47a1f4317c55f73d3bdceb59d34520d9c4a31592e1adf21fe` | `7f1c2ad5c213ca90e45774c809365f746557c2f7da0cb3cbf6ef0a7f6f3fd4a4` |
| APK 实际大小 | 38,512,421 B / 36.728 MiB | 38,522,525 B / 36.738 MiB；增加 9.867 KiB |
| 有效 Release instrumentation | **25 项：19 通过、6 失败；目标 APK 哈希匹配** | **25 项全部通过；同一测试 APK；目标哈希匹配** |
| 本轮进程冷启动（各 5 次） | `[441, 440, 422, 422, 444]`；中位数 **440 ms** | `[509, 483, 500, 473, 502]`；中位数 **500 ms** |
| 最终设备产物、签名与完整性 | 作为本轮基线归档 | 已覆盖安装且设备 SHA 一致；签名与旧包一致，ZIP 完整性通过；测试工具已移除 |

上一轮该基线 APK 的 10 次 `TotalTime` 中位数为 474 ms，详见[瘦身报告](apk-size-performance-2026-10-29.md)；它是历史参考，不自动充当本轮修复前后比较。本轮重新采集两版各 5 次样本，首开预热分别为 491 ms、499 ms，未计入中位数。每次 force-stop 后等待 1 秒，使用 `am start -W TotalTime`，启动 3 秒后确认主进程存活；未清空应用数据和系统页缓存。前后电池温度分别为 27.5→27.6 °C、27.8→27.9 °C，Thermal Status 均为 0。两组顺序执行，未随机交错，不能把全部 60 ms 差异严格归因于单一改动；本轮结果表明修正版约 0.5 秒显示 Activity，并非网络/模型全部就绪时间。

最终设备 APK 哈希核对一致，主进程存活；最后一次启动的致命异常、插件缺失、类/方法加载失败和插件注册失败白名单计数均为 0。只读截图确认正常首页及已有历史条目可见。仅卸载独立测试包 `cn.com.omnimind.releaseaudit`，保留修正版主应用；未卸载或清空主应用数据。

## 证据边界

- 旧报告中 37 项兼容性单测在受支持的 Debug 变体运行，不能证明 R8 成品的反射契约。本次独立 instrumentation 用于补足这一缺口。
- 原 Profile/Release 字段名相同，只能证明字段存在；不能证明反射目标类、泛型 Signature、无参构造器和默认值完整。这里已根据实际反例收紧验证标准。
- 成品检查未读取用户凭据。fixture 的独立进程和内存数据隔离须在运行时证明，不能因测试代码看似只读而省略守卫。
- 修复前真机 fixture 已复现 6 项问题，修复后 25 项全部通过。这个结论覆盖本报告列出的成品测试，不等于全部功能验收。相机、网络模型、真实音视频、已有闹钟触发、后台任务和完整 UI 交互等，仍需要各自的端到端证据。
