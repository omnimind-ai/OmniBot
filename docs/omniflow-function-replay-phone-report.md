# OmniFlow Function Replay / OmniTransfer 性能与可用性报告

测试日期：2026-09-03（Asia/Shanghai）
设备：OnePlus PJE110，ADB serial `b49f281b`
本次新版复测任务：打开小红书，点击顶部搜索框，输入“Omni”并提交；历史基线使用过“美食”。
任务范围只包含打开、搜索和提交，不包含下单、关注、发消息等不可逆操作。

## 一、问题、判断与结论

本报告按一条完整的论证链组织：先说明 Transfer 面对的真实挑战，再解释为什么几个看似直接的加速办法会失效，然后给出不改变官方语义的优化，最后用 Transfer 内核、手机端 Function Replay、CPU 和内存数据验证效果。这样可以把“算法为什么慢”“为什么不能简单砍矩阵”和“优化是否真的有效”分开回答。

### 可直接用于汇报的完整表述

这次 Transfer 慢，首先不是因为系统盲目构造了一个巨大的方阵，而是因为它要在真实页面之间完成一次受动作条件约束的语义映射：先把当前页面的 UI hierarchy 和截图转换成图特征，再在源页面动作节点与目标页面全部可用节点之间计算关联，经过稀疏图一致性和 refinement 后，才决定当前设备上哪个控件可以执行。真实样本的主要 pair shape 约为 `48×140`，不是 `141×141`；因此真正的挑战是跨页面匹配、动态页面变化和重复输入预处理叠加在一起，而不是单一矩阵的平方规模。

几个直觉上的“提速”办法会失效。把目标节点固定截成 top-k，虽然能缩小计算量，却可能提前裁掉搜索框、提交按钮等合法目标，改变官方 `candidate_policy=all_nodes` 的语义；只记住上一次坐标或 resource-id，则是在绕过 Transfer，页面滚动、重建或分辨率变化后会把旧页面证据误当成当前页面；把整个 forward 或最终坐标缓存下来，也会把旧 observation 带进下一步动作。更严重的是，这些做法会掩盖映射失败，违反长期规则中“映射失败必须报告失败并交给 VLM fallback，不能静默回放源设备坐标”的要求。新增 renew、reopen 或私有 retry 同样不合适，因为那会改变 ACP 对 session、turn、item 的生命周期判断。

所以本次只缓存不改变语义的部分：同一份 hierarchy 的 graph encoding、数值特征和有界关系，以及同一份 hierarchy 加同一张 screenshot 的 visual patches 和 mask；跨页面 forward、关联层、assignment、候选策略和动作落地全部保留官方路径，缓存使用 LRU 16，并将视觉数组设为只读。这样既没有减少候选，也没有缓存旧坐标。数据表明，点击样本的 warm Transfer 从约 `85.4 ms` 降到 `70.1 ms`，改善约 `17.9%`；输入样本从约 `58.7 ms` 降到 `46.1 ms`，改善约 `21.5%`；另一组点击样本从约 `81.8 ms` 降到 `67.8 ms`，改善约 `17.1%`。这些是 Transfer 内核数据，不冒充整条 Replay 的 wall time。

真机结果也支持这个判断：新版组件在 OnePlus PJE110 上用官方 `save_function` 编译并通过 `run_function` 回放，小红书搜索 5/5 完成，平均 `8.798 s`，0 次模型调用、0 次 fallback；针对旧 decoder 错选“展开”控件的问题，淘宝修复后 3/3 完成，平均 `13.301 s`，三轮都将搜索栏排在 rank 1 并进入搜索结果页。端到端剩余耗时主要来自 host action、动作后 observation、输入法和页面加载，而不是可以用删候选节点解决的一个“超大 Matrix”。因此当前最合理的结论是：保留全量候选和官方失败语义，优化输入缓存；把动态页面和系统调度造成的波动单独测量，而不是用降低正确性换取表面上的毫秒数。

先给结论：Transfer 慢的根因不是“需要一个 141×141 的巨大矩阵”，而是 V10 在每一步都要把当前页面编码成图，再对源页面节点和目标页面的全部候选节点进行跨页面关联、稀疏图匹配和两层 refinement。当前真实页面的主要形状是约 `48×140`，而不是 `141×141`；真正占时间的是多次 NumPy 张量计算和页面输入重复预处理。

全量候选不能直接裁成前 N 个节点：V10 的官方配置是 `candidate_policy=all_nodes`，每个启用的目标节点都可能是合法动作目标。硬截断会改变匹配语义，可能把正确控件裁掉，也会违反“映射失败后交给 VLM，而不是静默使用源设备坐标”的长期规则。因此本次优化选择缓存“不会改变结果的中间输入”，而不是减少候选集合：

1. 对同一份 UI hierarchy 缓存 `encode_graph` 的 token、数值特征和有界关系。
2. 对同一份 hierarchy 加同一份 screenshot 缓存 visual patches 和 mask。
3. 缓存使用 LRU 上限 16，并将视觉数组设为只读，避免跨 replay 共享可变数据。
4. 跨页面 forward、关联层、assignment 和候选策略保持原样；缓存失效仍然回到官方计算路径。

在相同页面样本的本地 NumPy 基准中，warm replay 的 Transfer 计算从约 85.4 ms 降至约 70.1 ms（约 17.9%），输入动作从约 58.7 ms 降至约 46.1 ms（约 21.5%），点击动作的另一组 `48×140` 样本从约 81.8 ms 降至约 67.8 ms（约 17.1%）。这是 Transfer 内核的基准，不是整条 Function wall time。新版 APK 已在真实手机上由官方 `save_function` 编译录制 RunLog，并完成淘宝 3 轮完整 Replay；小红书的 5 轮结果作为历史对照保留。早先的系统启动确认、无障碍未就绪和源状态不一致属于前置失败样本，不是 Transfer 矩阵本身失败。

## 版本与一致性

| 项目 | 实际值 |
| --- | --- |
| App | `cn.com.omnimind.bot` `versionCode=10` |
| OmniFlow runtime component | `2.1.8` |
| OmniTransfer | point-conditioned sparse graph V10，checkpoint `d1700845f599b9854b29a435166dfb18ce6a141fb4ab76bce7687c88188637a4` |
| OOB canonical action schema | `eb552c08e89123f42667c2c3296db9b3094d74715dfdb4d0cb7df50aeec62333` |
| 组件包 SHA-256 | `644ad6289383246762e137cbc08c8a22ab51f397663abe9c040b9338b2ae4a79` |
| 组件包大小 | 11,143,337 bytes |
| 最新 APK SHA-256 | `ec5e31ae943f5f160aa9ed23cab08062653b8ef73e9ce4510a81f25dc1d9fe81` |

手机端 marker 已与本次组件包 SHA 一致；runtime 中包含 Python 官方执行层的 timing 字段，未使用 Kotlin 侧重复转换或坐标回放。APK 安装后的首次 OmniFlow 调用会按 catalog 校验并替换本地组件，已在本次手机测试中确认替换成功。

## 二、Transfer 面临的挑战

Transfer 同时承担了三个不同性质的工作，不能只用“矩阵大小”解释全部耗时：

| 阶段 | 实际工作 | 是否能安全缓存 |
| --- | --- | --- |
| 页面输入准备 | XML/UI graph 编码、token、数值特征、关系特征、截图 patch | 可以；输入证据不变时结果不变 |
| 跨页面匹配 | `source_nodes × target_nodes` 的 affinity、soft correspondence、稀疏图一致性、两层 refinement | 当前不能整体缓存；目标页面或动作条件变化会改变结果 |
| 动作落地 | 根据映射结果选择目标节点，再由 OOB 执行 click/input | 不能缓存执行结果；必须使用本次页面的映射 |

V10 当前配置是：`source_context_nodes=48`、`target_context_nodes=64`、`hidden_dim=128`、`association_layers=2`、`candidate_policy=all_nodes`。由于真实目标页面启用节点约 140 个，目标上下文会扩展到全部允许节点，因此一次匹配的主要 pair shape 是约 `48×140`。以 512 维 float32 pair feature 估算，单个 pair feature 张量约为 13.1 MiB；这不是 141×141 的平方矩阵，也不是所有中间张量的总内存。两层 refinement、relation/context 临时数组和 Python/NumPy 分配会进一步造成峰值内存和 CPU 波动。

## 三、为什么几个直觉方案会失效

### 1. 直接把候选节点截断到固定数量

这会让 `all_nodes` 变成隐含的 top-k 策略。节点排序不等于动作相关性排序，搜索框、提交按钮等节点也可能在不同页面出现在不同位置。截断可以让矩阵变小，但会以漏召回和错误点击换速度；对于 Function Replay，这种错误比多几十毫秒更严重，因此没有采用。

### 2. 只缓存上一次坐标或 resource-id

这不是 Transfer 加速，而是绕过 Transfer。页面发生滚动、布局变化、分辨率变化或控件重建时，旧坐标/旧 id 可能已经不代表当前页面。长期规则明确要求映射失败必须报告失败并走 VLM fallback，不能静默回放源设备坐标。

### 3. 把整个 forward 结果缓存起来

forward 的结果依赖当前 source/target graph、截图证据和候选集合。直接缓存最终坐标会把旧页面状态当成当前页面状态，尤其会破坏 replay 连续动作之间的页面切换。因此只缓存纯输入预处理，不缓存跨页面匹配结果和动作结果。

### 4. 为了“安全”增加 renew/reopen/retry 生命周期

这会改变 ACP 对 session、turn、item 的判断，造成表面上更稳、实际生命周期不一致。Transfer 的性能问题应在官方 Python runtime 的计算边界解决，不应新增 Kotlin/Flutter 私有生命周期或第二条重试路径。

### 5. 结论、实现和证据的对应关系

| 已确认的问题 | 不能采用的做法 | 本次采用的做法 | 对应证据 |
| --- | --- | --- | --- |
| 真实主要 pair shape 约为 `48×140`，不是 `141×141` | 固定 top-k 截断候选节点 | 保留 `all_nodes`，只缓存不改变语义的输入 | V10 配置与本地矩阵形状见 4.1 |
| 每次 replay 会重复做 graph/visual 预处理 | 缓存最终坐标或整个 forward 结果 | LRU 缓存 graph encoding、visual patches 和 mask | warm Transfer 约提升 17.1%–21.5%，相关测试 `8 passed` |
| 页面切换时 package/activity 可能先于 XML 到达 | 在没有 hierarchy 时立即匹配，或自行重开 session | 在官方 Python observation 边界等待可用 hierarchy | OmniFlow readiness 回归测试 `10 passed`；手机已完成新组件替换 |
| Function wall time 不等于 Transfer kernel time | 用单个毫秒数代表整条 Replay | 分开报告 Transfer、动作、观察、入口和模型调用 | 历史手机 run 的逐步耗时和在线 VLM 分解见第六、七节 |
| 手机通过 USB 时无法得到可信电流 | 用电量百分比或 `current=0` 推算功耗 | 明确标记功耗不可用，不填伪造数据 | `dumpsys battery` 显示 USB powered |

## 四、数据支撑：Transfer 与整条 Replay 的关系

### 4.1 Transfer 内核基准

以下是同一台开发机、同一 V10 checkpoint、同类真实手机 XML/screenshot 输入的本地 NumPy warm benchmark。首轮包含冷的 Python/NumPy 和文件输入成本，所以单独列出；后四轮用于观察缓存后的稳定成本。

| 样本 | 矩阵形状 | 首轮 | warm 1 | warm 2 | warm 3 | warm 4 | warm 均值 | warm 改善 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| click | `48×142` | 168.3 ms | 70.0 | 72.0 | 69.1 | 69.5 | 70.1 ms | 约 17.9% |
| input_text | `48×86` | 56.6 ms | 46.0 | 46.0 | 45.9 | 46.5 | 46.1 ms | 约 21.5% |
| click | `48×140` | 83.5 ms | 67.0 | 69.6 | 67.4 | 67.3 | 67.8 ms | 约 17.1% |

优化前对应基线约为 `85.4 ms`、`58.7 ms` 和 `81.8 ms`。缓存没有改变输出校验，也没有改变候选策略；新增回归测试确认同一 matcher 对同一 graph 的两次 forward 只执行一次 graph 编码和一次视觉预处理，相关 OmniTransfer 测试为 `8 passed`。

### 4.2 真实手机端到端基准

真实手机上的端到端数据必须区分“前置失败”和“正式 Replay”。前置失败用于定位系统授权或录制状态问题，正式 Replay 才用于评价最新版。新版 APK 已包含缓存后的 `2.1.8` runtime、`run_function` 官方协议路径和 hierarchy readiness 检查；官方 `save_function` 已将重新录制的 RunLog 编译为带 `input_text` 绑定的 Function，随后淘宝 3 轮均实际进入 V10 Transfer 并完成全部动作，详见 4.5。

### 4.3 本次新版部署的实测失败样本

为了区分“包没有更新”“运行时没有启动”“Transfer 计算失败”和“业务页面前置条件不满足”，本次新版 APK 在同一台旧手机上保留了失败日志，而没有把失败重试成一个模糊的成功数字：

| run | 结果 | 已完成阶段 | 关键耗时/证据 | 归因 |
| --- | --- | --- | --- | --- |
| `tool-f913523b-31f8-4732-a7a0-551123e2484a` | failed | `open_app` 未通过 | 总计约 21.23 s；观察到 `com.oplus.securitypermission`，未进入 Transfer | ColorOS 首次启动确认层遮挡目标 App；运行时按契约终止 |
| `tool-a269c665-c2fa-495e-af22-fae4583fa7f4` | failed | `open_app` + 第一个 click 成功，第二个 input 被拒绝 | `open_app` 步骤约 13.81 s；首个 click 的 `transfer_ms=3,949.425 ms`；错误为 `omnitransfer_target_candidate_not_executable` | Function 源状态是搜索结果页，打开 App 后实际状态是首页/推荐页；V10 没有可执行的可靠候选 |
| `human_1788384466306_2fba5b2d` | recording succeeded | 3 个动作均真实执行并记录 | 点击搜索、输入 `Omni`、点击提交；动作数为 3；RunLog 含 3 组 `before/after_state_id` 和对应状态文件 | 官方 `save_function` 编译成功，生成 `complete_source_workflow`；不是 Kotlin 侧转换 |

这三条记录说明：第一条不是 Transfer 慢，第二条不是“矩阵太大”，第三条也不是“手动录制入口消失”。要得到有效的 Function Replay，录制必须从能被 `open_app` 恢复的应用初始页开始，并且 RunLog 必须保留官方 observation 状态证据；缺少这两个条件时，正确行为是失败或要求重新录制，而不是加入坐标兜底。重新录制满足条件后，官方编译和回放已经成功。

前置失败样本结束后的设备快照为：OmniBot PSS `558,603 KB`、RSS `676,728 KB`；小红书 PSS `467,950 KB`、RSS `702,528 KB`。该次 CPU 采样器因 Android/本机 `awk` 兼容性错误产生了不可用输出，因此不把它计入 CPU 平均或峰值；正式 Replay 的有效 CPU/RSS 采样见第八节。

### 4.4 最新 APK 正式 Function Replay

使用上述官方编译出的 `complete_source_workflow`，参数为 `input_text=Omni`，在同一台 OnePlus PJE110 上从小红书冷启动首页开始连续执行 5 轮。每轮均通过 `run_function`，每一步均使用 `omnitransfer_point_conditioned_sparse_graph_v10`，没有模型调用、fallback 或源设备坐标直通。

| 指标 | 结果 |
| --- | ---: |
| 有效轮数 | 5 |
| 完成率 | 5/5（100%） |
| 每轮动作 | 3/3 |
| 模型调用 | 0 |
| fallback | 0 |
| 总时长范围 | 8.312–9.122 s |
| 平均总时长 | 8.798 s |

5 轮的 `duration_ms` 为 `8606、9071、9122、8312、8878`。逐步平均耗时为：点击搜索框 `3.124 s`，输入文字 `2.451 s`，提交搜索 `2.157 s`。最终包名均为 `com.xingin.xhs`，完成原因均为 `function_completed`。

### 4.5 新版 OmniTransfer decoder 的真实淘宝复测

淘宝复测专门覆盖了之前暴露问题的页面：源状态和目标状态都同时存在“搜索栏”和“展开”控件。旧 decoder 在离线重放中曾把 `com.taobao.taobao:id/iv_more_tab` 排在搜索栏前面，导致真实 Replay 第一动作错误；新版没有删除候选，也没有使用旧坐标，而是在官方 decoder 的既有 pair score 上增加语义精确项。离线同权重对比为：旧配置 rank1 是“展开,按钮”（0.6165），新配置 rank1 是“搜索栏”（0.6893）。

| 真实 run | 结果 | 总时长 | Transfer（click / input） | V10 结果 | 最终页面 |
| --- | --- | ---: | ---: | --- | --- |
| `tool-feb739a9-2994-444b-a366-9f1b59c41bca` | 3/3 | 15.149 s | 2.199 / 1.200 s | 搜索栏 rank1 | `com.taobao.search.sf.MainSearchResultActivity` |
| `tool-1d732757-3d20-4671-a2e9-36ebe1d47e6b` | 3/3 | 13.604 s | 3.779 / 1.281 s | 搜索栏 rank1 | `com.taobao.search.sf.MainSearchResultActivity` |
| `tool-18f44a49-17b4-4d10-92e6-d23e7e929607` | 3/3 | 11.150 s | 4.540 / 1.246 s | 搜索栏 rank1 | `com.taobao.search.sf.MainSearchResultActivity` |

三轮均为 `model_calls=0`、`fallback_steps=0`、`function_completed`，并使用同一个 checkpoint `d1700845f...88637a4`。逐步平均为：点击搜索栏 `5.328 s`（其中 Transfer `3.506 s`），输入文字 `2.858 s`（Transfer `1.242 s`），提交 `2.551 s`；端到端平均 `13.301 s`。点击 Transfer 的波动来自淘宝首页网络内容和页面层级变化，不是候选裁剪；三次目标候选都正确且可执行。

同一轮之前还有两个明确的非业务失败：无障碍服务未绑定时返回 `android_gui_accessibility_not_ready`；错误地用 `goAsync()` 持有长时间 Broadcast 时，ColorOS 在约 10 秒后报告 `Broadcast ANR` 并杀掉 `cn.com.omnimind.bot`。后者是测试入口生命周期问题，已恢复为原有异步调试入口，没有进入生产 OmniFlow 生命周期，也没有作为 Transfer 成功率计入。

## 五、历史真实手机结果

| 路径 | 次数 | 成功 | 步骤 | 模型调用 | fallback | 用时 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 在线 VLM 对照 | 1 | 1 | 3/3 | 2 | 0 | 28.912 s |
| 历史 Function Replay | 3 | 3 | 3/3 | 0 | 0 | 8.752–14.066 s |
| 历史最终 APK Replay | 2 | 2 | 3/3 | 0 | 0 | 6.704–14.862 s |
| 最新 APK 小红书对照 Replay | 5 | 5 | 3/3 | 0 | 0 | 8.312–9.122 s，平均 8.798 s |
| 最新 APK 淘宝 decoder 修复后 Replay | 3 | 3 | 3/3 | 0 | 0 | 11.150–15.149 s，平均 13.301 s |

Replay 的直接效果是稳定完成 3 个动作：进入搜索、输入关键词、提交查询。小红书对照最终进入 `com.xingin.xhs` 搜索结果页；淘宝修复后最终进入 `com.taobao.search.sf.MainSearchResultActivity`。三步均使用 OmniTransfer V10 映射；没有 VLM fallback，也没有 source-device 坐标 passthrough。

## 六、最新版与历史版本的逐步耗时

以下是最新版 5 轮正式 Replay 的逐步平均值；每一步都来自 Python 官方 timing 字段，不包含物理设备观测时间：

| step | 动作 | Transfer | host.act | 动作后观察 | action dispatch | step 总耗时 |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 0 | 点击顶部搜索框 | 1,342.6 ms | 1,557.6 ms | 181.5 ms | 1,739.1 ms | 3,124.1 ms |
| 1 | 输入“Omni” | 1,162.3 ms | 1,085.1 ms | 177.2 ms | 1,262.3 ms | 2,450.7 ms |
| 2 | 点击搜索提交 | 1,041.1 ms | 855.7 ms | 232.1 ms | 1,087.8 ms | 2,156.8 ms |
| 合计 |  | 3,546.0 ms | 3,498.4 ms | 590.8 ms | 4,089.2 ms | 7,731.6 ms |

最新版 5 轮平均完整 wall time 为 `8,797.8 ms`，比逐步 timing 合计多约 `1,066.2 ms`，主要属于 Function admission、初始 observation 和 bridge 调度；它不属于某个具体物理点击，因此没有强行摊到动作上。

历史最终 APK 的单轮明细（run `tool-55b1fd25-806e-4148-829a-a01c2ed9f590`）如下，保留用于版本对照：

| step | 动作 | Transfer | host.act | 动作后观察 | action dispatch | step 总耗时 |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 0 | 点击顶部搜索框 | 1,101.478 ms | 1,728.178 ms | 230.182 ms | 1,958.422 ms | 3,183.213 ms |
| 1 | 输入“美食” | 840.352 ms | 1,074.487 ms | 178.361 ms | 1,252.897 ms | 2,151.389 ms |
| 2 | 点击搜索提交 | 814.528 ms | 1,438.137 ms | 258.851 ms | 1,697.040 ms | 2,563.412 ms |
| 合计 |  | 2,756.358 ms | 4,240.802 ms | 667.394 ms | 4,908.359 ms | 7,898.014 ms |

该历史 run 的完整 wall time 比 step 合计多约 `6.964 s`，属于 Function 入口、初始 host observation、Python bridge 启动/调度等步骤，不能摊到某个物理点击。

## 七、在线 VLM 的组件时间

在线 run `debug-vlm-28247a3f-afee-48a6-9b71-c530e24444d5` 的日志给出：

- 第一次模型请求从 dispatch 到流结束约 2.068 秒；其中首事件约 1.960 秒。
- 第二次模型请求从 dispatch 到流结束约 3.230 秒；其中首事件约 2.324 秒。
- 两次云端流式响应合计约 5.298 秒，另有请求 dispatch 开销约 0.167 秒。
- 在线总 wall time 为 28.912 秒，且最终同样完成 3/3；其余时间主要是初始 runtime/界面准备、Function 复用执行以及最终完成判定。

这说明在当前设备和网络条件下，Replay 去掉了在线模型调用，速度主要受端侧 Transfer 和 OOB 动作/观察影响。

## 八、CPU 与内存

CPU 使用 `/proc/<pid>/stat` 的累计 user+system ticks 采样，百分比按单核归一；这是端侧 CPU 活跃度代理，不是 Android 完整 CPU 消耗统计。超过 100% 表示进程同时使用了多个核。RSS 使用 `/proc/<pid>/status` 的 `VmRSS` 采样，是进程常驻内存峰值代理，不等同于 PSS。

| 路径/进程 | CPU 平均 | CPU 峰值 | RSS 峰值代理 |
| --- | ---: | ---: | ---: |
| 最终 Replay / `cn.com.omnimind.bot` | 55.86% | 110.74% | 848.28 MB |
| 最终 Replay / `com.xingin.xhs` | 27.16% | 74.96% | 660.16 MB |
| 在线 VLM / `cn.com.omnimind.bot` | 42.09% | 104.38% | 727.54 MB |
| 在线 VLM / `com.xingin.xhs` | 32.69% | 158.15% | 664.22 MB |

最新版正式 Replay 的两轮有效端侧采样（`/proc/<pid>/stat`，单核归一）为：

| run | 结果 | CPU 平均 | CPU 峰值 | OmniBot RSS 峰值 |
| --- | --- | ---: | ---: | ---: |
| `tool-8a782ca6-2325-44d1-9775-fea86c099de5` | 3/3 成功 | 39.39% | 75.00% | 未采集 |
| `tool-1d0e9463-5416-42df-a138-631600a77a09` | 3/3 成功 | 45.21% | 134.37% | 816,528 KB（约 797.4 MiB） |

因此最新版这两轮的 CPU 平均约 `42.3%`，观测峰值 `134.4%`；峰值超过 100% 表示进程在短时间内使用了多个核，不表示电量百分比。由于 RSS 是进程级峰值代理，不能把它等同于整个系统内存或 PSS 峰值。

淘宝修复后第三轮使用 Android `top` 在运行期间采样，得到的端侧观测如下。`top` 的 CPU 是系统进程采样值，Python bridge 与 OmniBot 是两个不同进程，不能相加成单一“Transfer CPU”。

| 进程 | 观测 CPU | 观测 RES/RSS |
| --- | ---: | ---: |
| `cn.com.omnimind.bot` | 16.6%–72.4% | 710–855 MB |
| `python3 -m omniflow.bridge` | 21.4%–496% | 67–141 MB |

该轮结束后的 `dumpsys meminfo` 快照为 OmniBot PSS `541,354 KB`、RSS `748,276 KB`；Python bridge PSS `69,156 KB`、RSS `69,160 KB`。Python 的峰值超过 100% 是多核并行使用的结果，不能解释为电量比例。这个采样说明手机端真正的峰值不只来自 `48×140` pair score，还包括 Flutter/Android 宿主、NumPy bridge 和页面观察分配。

历史最终 APK 测试结束后的 `dumpsys meminfo` 快照为：

- OmniBot：PSS 519,859 KB，RSS 725,184 KB。
- 小红书：PSS 454,050 KB，RSS 687,784 KB。

最新版正式 Replay（run `tool-1d0e9463-5416-42df-a138-631600a77a09`）结束后的快照为：OmniBot PSS `569,545 KB`、RSS `688,136 KB`；小红书 PSS/RSS 未在同一时刻作为正式采样记录，因此不与上面的历史快照混算。

这些数值是本机当前系统状态下的诊断数据，不应当被解释为所有设备上的固定上限。CPU/RSS 采样会受到小红书后台线程、系统调度和网络加载影响；要做发布级功耗对比，需要固定亮度、温度、网络和前后台进程，并重复多轮。

## 九、功耗限制

本轮手机通过 USB 连接 ADB，`dumpsys battery` 显示 `USB powered: true`，且系统报告 `Battery current: 0`。因此本轮不能从 charge-counter 推导可信的能耗或平均功率；报告不填充伪造的 mWh/W 数据，也不使用电量百分比。需要权威功耗结果时，应在断开充电并保持电池状态稳定的条件下重复，或使用外部硬件功率分析仪。

## 十、稳定性判断与下一步

历史小红书 Function 在当前 OnePlus 真实设备上已达到实机可用状态；本次淘宝 Function 在 decoder 修复后 3 轮均 3/3 成功，0 次模型调用，0 次 fallback。小红书对照 5 轮范围为 `8.312–9.122 s`；淘宝修复后 3 轮范围为 `11.150–15.149 s`。这说明算法正确性已从之前的“错误候选 rank1”修复，但淘宝端到端时长仍受首页动态内容、输入法和页面网络加载影响，不能与小红书时长直接横比。

当前可以把最新版标记为“在已验证的搜索场景端到端可用”：组件替换、官方 `save_function` 编译、`run_function` 执行、V10 Transfer、动作落地、动作后 observation、历史 RunLog 提交均已闭环。淘宝的 decoder 修复已在 3 轮真机 Replay 中复现；此前的系统启动确认、无障碍未就绪和录制源状态不一致仍作为失败防线保留，不能通过 renew、隐式重试或源坐标兜底绕过。

本次补充实测进一步确认了两个发布前置条件：ColorOS 的“允许小万打开小红书”需要先由用户确认一次；Function 的第一步必须对应 `open_app` 后可复现的源页面。当前手机上的 OmniLink 对端 `V2502A` 仍显示离线，后台日志为 `omnilink_provider_route_unavailable`；这只阻断依赖该远端 host 的 Online/Bridge 路径，不应在本地 OmniFlow 生命周期中新增 renew 或隐式重试来掩盖它。

本结论覆盖已实际执行并闭环验证的“小红书搜索 Omni”和历史“小红书搜索美食”Function；不能外推到未在手机上录制、编译并验证的下单、关注、联系人写入等任务。
