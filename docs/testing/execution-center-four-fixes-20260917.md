# 执行中心四项问题修复与真机验证（2026-09-17）

范围仅为本轮用户点名的问题：增强来源丢失、新录制覆盖旧指令、自动保存拖慢收尾、停止状态与截图。前序失败见 [完整审计](execution-center-full-audit-20260917.md)。不代表整个 App 的所有功能验收通过。

## 根因与修改

| 问题 | 根因 | 修改位置与行为 |
| --- | --- | --- |
| 重载后无法增强 | Function v2 可执行协议没有保存管理层来源；重载只返回可执行字段 | canonical OmniFlow FunctionStore 在同一 Store 文件中持久化 `function_sources`，list/get 返回 `source_run_id`；增强入口剥离管理字段后仍按原始 RunLog 编译，不从最近回放猜来源 |
| 不同录制互相覆盖 | 自动编译产生同一个语义 ID，Store 按 ID 更新 | 自动创建遇到不同来源时用源 RunLog 的稳定摘要区分 ID；同源重复保存保持幂等；显式增强仍更新原 Function |
| 收尾慢 | 自动注册默认调用语义增强模型，持有 Bridge 等待模型输出 | 自动保存只冻结真实证据、编译和登记；模型增强改为显式离线操作。没有新增后台 Agent、重试队列或另一套生命周期 |
| 停止显示失败、截图缺失 | `function_stopped` 与 `cancelled` 映射不同；公开 RunLog 把所有非成功写成 failed；XML-only 观察不抓图 | 统一原有取消状态并扩展 canonical RunLog enum；执行期间观察保存截图，是否向模型发送图片仍由原请求决定 |

真机增强进一步发现两个真实错误并修复：512 token 预算被推理耗尽，未输出 JSON；同结构页面再次截图导致冻结来源与临时截图冲突。增强使用现有 8192 authoring 预算，并复用 Store 已冻结的 transfer states；仍拒绝真实来源冲突，不放宽校验，不替代 OmniTransfer。

主要代码位于 `~/Projects/Omni/OmniFlow-exp/omniflow/bridge.py`、`omniflow/functions/store.py`、`schemas/oob/omniflow_run_log.v1.json`。Android 只改观察截图、取消投影与错误透传。默认构建的 `artifacts/omniflow-gui-runtime-2.2.0.zip` 和 `plugins/catalog.v1.json` 已同步；不是仅在设备注入临时 Python 补丁。

## 真机结果

设备 PJE110（ADB b49f281b），Android 16 / ColorOS，0.6.3 / code 16 / developStandardDebug。保留数据安装，增强前经历进程重启及重新安装；未清除用户数据。最终 APK SHA256：`0cf87eb87085bfab3072bc9bd484598c5c75d16f9fede159e85bdfcf951a1257`。组件 SHA256：`83106bd4f80c1e703ecf70f51343317f9d4f8447bed6ae189853280e7fb83d43`。

| 实际操作 | 结果 |
| --- | --- |
| 从执行中心手动录制两次打开系统设置搜索，逐次完成保存 | 通过；保存约 2.11 秒、2.15 秒；生成不同 ID，保存前已有 Function 全部保持原值 |
| 重启后读取第一条录制，第二条录制完成后再读取 | 通过；原步骤、原始 source_run_id 保留 |
| 重载第二条指令，从 UI 点增强，经真实 Agent 调用 save_function | 通过；原生工具卡为成功，结果 registered=true，实际 Store 来源与步骤保持一致。模型此次没有改名/描述，这是合法结果 |
| 从执行中心执行第二条指令 | 通过；实际进入设置搜索页，RunLog succeeded；9.72 秒含验收查询开销，不能当纯 GUI 时间 |
| 检查执行观察的两份截图，并点第一步“动作截图” | 通过；两份文件非空，第一步图片在 App 内实际显示，没有“状态截图不可用” |
| GUI 开始前停止；真实执行一条动作后停止 | 两次通过；原生按钮停止后，list/get/UI/延迟读取都为 cancelled，未回跳 succeeded |

增强证据为工具调用 `call_8w57cawyu2d9f58j2o5gwhgv` 的原生详情及随后 Store 回读，不是模型最终回答自述。测试曾错误地要求“增强必须改名”，因此产生一个验收假阴性；保留失败文件并改为核对真实工具状态、registered、Function ID、来源与动作。后续脚本复核时另一项真机测试打开了安装器，阻断了页面前置条件，未把这种测试干扰算成产品成功或产品失败。

本次证明自动保存不再等待模型，不能承诺整轮固定 2 秒。显式增强这一轮聊天显示约 1 分 19 秒，工具阶段约 1 分 5 秒，仍受模型速度影响。补充的“自然完成 GUI 到最终回复”整轮耗时复测因共享真机被其他测试占用而未完成；不能把不同操作的 2.1 秒保存与此前 2 分 34 秒整轮直接相减。

## 自动化与执行入口

- canonical OmniFlow 定向及相关回归 **117 项通过**（含 16 个 device RunLog compiler 测试）；覆盖来源重载、碰撞、同源幂等、自动注册禁止模型调用、取消不能注册、增强复用冻结截图。
- Android OmniFlow 模块 **64 项通过**，App OmniFlow **10 项通过**；最终 Gradle 定向测试成功。
- 组件打包测试 **8 项通过、2 项跳过**；默认 APK 构建成功并安装真机。
- 本轮没有修改 Flutter 页面，不将旧 Flutter 测试数计入新验收。

```sh
# 仅在真机空闲、解锁并授权无障碍时操作；测试仅导航系统设置，不修改设置。
export OOB_GUI_SERIAL=b49f281b
export OOB_ALLOW_PHYSICAL_DEVICE=1
export OOB_GUI_OUTPUT=/tmp/oob-four-fix
# 从小万空闲主页开始，先确认选中本地“小万” Harness。
python3 scripts/verify-execution-center-fixes.py --phase record --fresh-home
python3 scripts/verify-execution-center-fixes.py --phase replay
# 保留数据重启/重装，回到主页后执行。
python3 scripts/verify-execution-center-fixes.py --phase enhance --fresh-home
# 在已经打开的 save_function 原生结果页可独立重新核验：
python3 scripts/verify-execution-center-fixes.py --phase verify-enhancement
# 从空闲本地测试聊天启动实际 GUI 并在真实动作后停止。
python3 scripts/verify-execution-center-fixes.py --phase stop
```

`verify-execution-center-provenance.py` 的只读来源/旧步骤回归在最终设备已通过。`verify-execution-center-arguments.py` 补了每个 before_state 的持久截图断言；这个新增分支尚未单独重跑，同等截图路径已由 replay 阶段真机覆盖。脚本增强结果验证经过实际原生结果观察和 Store 回读；最后补充的自动展开工具卡导航分支尚未完整重跑，应保留该覆盖限制。

```sh
cd ~/Projects/Omni/OmniFlow-exp
.venv/bin/python -m pytest tests/test_device_runlog_compiler.py tests/test_invocation_protocol.py tests/test_checker_lifecycle.py tests/test_completion_verification.py tests/test_kernel_configuration.py tests/test_function_service_adapters.py -q

cd ~/Projects/Omni/OpenOmniBot
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon --no-parallel :omniflow-android:testDebugUnitTest :app:testDevelopStandardDebugUnitTest --tests 'cn.com.omnimind.bot.omniflow.*'
```

脱敏证据见 [artifacts/execution-center-four-fixes-20260917](artifacts/execution-center-four-fixes-20260917/)。只保留本轮合成设置导航记录与断言，不复制手机通知、其他用户会话或凭据。真实 budget 截断与 source conflict 的失败样本也保留。

## 历史边界

已经被旧版本覆盖的指令、已经丢失的原始来源、从未抓取的旧截图，不能凭空恢复；有原始 RunLog 时可重新登记。修复保证新录制与后续重载/增强路径。未进行删除历史或清空数据来制造“全绿”，未发布新的 release，也未提交或推送代码。
