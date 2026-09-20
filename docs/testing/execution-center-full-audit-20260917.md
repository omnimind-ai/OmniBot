# 执行中心与 Agent GUI 真机扩展审计 — 2026-09-17

结论：核心录制、参数回放和 Agent GUI 可以执行，但不能称为全部功能稳定可用。已实际发现来源丢失、同 ID 自动覆盖及收尾延迟。没有把模型说“完成”当作验收依据。

设备 PJE110 / b49f281b，Android 16 / ColorOS。0.6.3（16）developStandard Debug，覆盖安装保留数据。最终 APK SHA-256：`04590827c3d6d4eec47176cff90e984c696b3b98b94c16cd3919b149e8f31407`。同日早一轮重启测试见 [原记录](execution-center-20260917.md)，它不是本轮重新执行的结果。

## 实际操作结果

| 用户操作 | 本轮真机结果 |
| --- | --- |
| 执行中心入口、刷新、两个标签切换 | 通过 |
| 手动录制待机取消 | 通过；RunLog cancelled，没有增加 Function |
| 开始录制、暂停、取消 | 通过；RunLog cancelled，没有增加 Function |
| 手动录制点击搜索框、动作菜单输入 battery、完成 | 通过；真实两步 RunLog，前后证据完整 |
| 自动生成并持久化 Function | 能生成，但覆盖了既有 `complete_task`，不通过身份保持验收 |
| 空必填参数、取消执行弹窗 | 通过；显示“请填写此参数”，没有新增运行 |
| 绑定 query 参数并执行 | 两次真实回放通过；系统设置输入框实际为 battery，canonical RunLog succeeded |
| 运行列表、详情、查看对应指令 | 修复后通过；由 diagnostics.function_id 关联正确 Function |
| 步骤详情、动作截图入口 | 能打开；该回放状态显示“状态截图不可用”，XML 可读。截图展示未通过，不等同于证据完全缺失 |
| 删除弹窗取消 | 通过；保留原 Function |
| 删除确认 | 通过；只删除本轮专用 fixture，目录复核已消失 |
| 增强 | 失败；重载后缺 source_run_id，未进入 Agent 聊天 |
| Agent GUI：接管、继续、停止 | 控件可操作，聊天显示已取消；底层首轮 RunLog 为 failed/OmniFlow execution stopped，终态一致性仍需检查 |
| Agent GUI：再次打开设置搜索页面并核验 | 通过；真实 open_app、click、press_key，最终聊天收到 OOB_EXEC_RECHECK_DONE，未输入、未修改设置 |
| 强停后的持久化/重放 | 本轮未重跑；同日上一轮需系统重新授权才能继续，不是无条件通过 |
| 数字/布尔/JSON 参数、快速连点、插件禁用/启用、手动“注册为复用指令”按钮、全部其他 App | 本轮没有逐项真机验收；部分只有现有单元测试，不计入真机通过 |

## 已修复并安装验证

canonical 回放 RunLog 的 Function ID 位于 `diagnostics.function_id`。页面原来只读顶层字段，导致执行成功后仍显示“注册为复用指令”。现在列表和详情复用 `linkedExecutionFunction`，优先取 canonical 身份；去掉把回放 run_id 冒充原始 source_run_id 的补写。

先保留失败的 widget 测试，再修复；相关 Flutter 最终 **36 项通过**，限定范围 analyze 无问题，APK 构建成功。真机 `tool-afad2070-dbf4-4f6c-a73f-42ac7e29ca13` 及再次脚本回放 `tool-49da7964-dc15-401f-b0e4-8e341129c573` 都能打开对应指令。没有新增 Agent 生命周期、协议或迁移替代实现。

## 尚未解决的发现

1. **来源没有持久化到 Function 读取结果。** 注册响应可临时带来源，但重载 Function 后没有 `source_run_id`，增强适配器因此报错。应该在 canonical 包的产物/存储元数据里保留来源，并由统一接口读回；不能拿最近一次回放补成来源。
2. **新录制静默覆盖既有 ID。** 前一轮 `complete_task` 是一个点击步骤，source state 为 `state_38f1fc5195bc1b3881c1`；本轮新录制自动生成相同 ID，读取变成两步。需要 canonical 注册层区分新建与明确更新，冲突不得静默覆盖。不是仅把标题改漂亮就能解决。
3. **执行后处理偏慢。** 第二次 Agent GUI RunLog `gui-11962dfa-422a-439f-a1b2-11ff97edbeaa` 的 GUI 阶段约 33.5 秒，整轮聊天显示 2 分 34 秒。中途 GUI 已结束，但仍观察到推理控制条。源码中 `bridge._complete_run` 在 host finish_run 后同步执行 save_function 自动增强，Python client 的同一调用锁还会占用管理请求。整轮差值也包含 Agent 读证据/回复，不能全部算作注册耗时。本次最终自然完成，没有手动伪造完成状态。
4. **停止终态及截图需要补查。** 首轮用户停止在聊天为 cancelled、RunLog 为 failed；回放步骤截图入口提示不可用。保留实际结果，尚未确定分别属于哪些层的契约/采集策略问题。

## 可执行回归入口

保留手机解锁、无障碍授权，进入执行中心。脚本使用现有 native 观察接口，不用 UIAutomator 干扰正在运行的无障碍服务。目录中的脱敏证据只保留本次合成任务，不复制其他聊天、系统 XML 或通知。

```sh
export OOB_GUI_SERIAL=b49f281b
export OOB_ALLOW_PHYSICAL_DEVICE=1
export OOB_GUI_OUTPUT=/tmp/oob-full-audit
python3 scripts/verify-execution-center-controls.py
python3 scripts/verify-execution-center-arguments.py \
  --recorded-run-id human_1789631007025_dd7fa33e
python3 scripts/verify-execution-center-provenance.py \
  --baseline-function docs/testing/artifacts/execution-center-20260917/function.json \
  --source-run-id human_1789629806389_e98977c9
```

`controls` 前三项通过、增强失败；`arguments` 四项通过，实际绑定执行、关联、删除均由 UI 完成；`provenance` 两项实际失败并返回非零。参数用例显式调用公共 save_function 从真实录制制作 fixture，不代表模型自动参数化通过，也不绕过 OmniTransfer。历史 RunLog/状态被清理后须重新真实录制，不能伪造成功 fixture。Agent 对话本轮通过真实输入框发送，未新增完整 Agent GUI 自动化脚本；仍不能称为全部功能全自动覆盖。

证据：[artifacts/execution-center-full-audit-20260917](artifacts/execution-center-full-audit-20260917)。失败记录保留，没有将未执行项目改成通过。
