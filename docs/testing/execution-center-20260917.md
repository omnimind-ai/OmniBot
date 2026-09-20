# 执行中心解耦与真机验收 — 2026-09-17

## 本轮结论

PJE110（`b49f281b`，Android 16 / ColorOS）上实际完成：设置入口 → 执行中心 → 手动录制 → 保存复用指令 → 详情内执行 → 系统设置搜索页面 → 查看运行记录。旧指令也通过列表的执行按钮运行成功。覆盖安装 0.6.3 developStandard Debug，未清除用户数据。

强制停止后的无权限阶段**失败**：ColorOS 将小万从 `enabled_accessibility_services` 移除，原生观察返回 `android_gui_accessibility_not_ready`。通过系统设置实际重新授权后，指令保持不变，再次从页面执行成功。此结果不代表“强停后无需重新授权”。失败日志与恢复结果均保留。

## 根因与修改边界

原页面把保存的 Function ID（例如 `open_target_page`）直接用作工具名。包运行时只声明统一入口 `run_function`，因此在工具发现阶段就抛出 `runtime_tool_not_declared`，尚未开始回放。修复为 `run_function({function_id, arguments, goal})`，没有注册每一个业务函数为硬编码工具。

- `ExecutionCenterPage` 和 RunLog 详情页依赖 `ExecutionBackend`；路由注入 `OmniFlowExecutionBackend`。
- OmniFlow 工具名、调用信封、插件准备、注册响应和增强请求归适配器；列表、分页、去重、销毁后的迟到响应归一个页面控制器。
- 显示层继续使用共享 Function/RunLog artifact schema，并非与数据格式完全无关。没有增加 Agent loop、ACP reducer 或第二套执行生命周期。
- 新增固定底部“手动录制”，复用原录制流程；设置里新增执行中心入口。列表改为分离的标题、描述、步骤信息和执行按钮。
- 参数表单验证必填、数字/整数及 JSON 类型，保留布尔 false/default；完整 schema 校验仍由运行时负责。执行期间禁止重复点击，错误不再直接展示 PlatformException 包装。
- 减少了页面重复的列表和协议处理，但抽取接口、控制器后总代码量不等同于减少。未改 OmniTransfer 实现。

## 真机证据

APK SHA-256：`7c663e8a9a9ee7e4497ecbbadbcf8d022efad472cf38bf0c3fed40832b88c77b`。

| 操作 | 结果 |
| --- | --- |
| 设置 → 执行中心 | 真实页面可进入，固定手动录制按钮可见 |
| 手动录制：待机 → 开始 → 暂停 → 继续 → 点击设置搜索框 → 完成 | RunLog `human_1789629806389_e98977c9` 成功，1 个动作，前后状态证据齐全；控件点击未混入 |
| 自动生成保存 | `complete_task` 已写入 Function Store，并刷新显示 |
| 原有 Function 列表执行 | `tool-4b496462-0596-4b43-8223-17389eec74fb` 成功，`tool_name=run_function` |
| 新 Function 回放 | `tool-b1f0f813-f013-4832-b3f9-6e3a4d47a94c` 成功；执行前断言未在搜索页，执行后实际看到“搜索历史” |
| 强停后首次继续 | 失败：系统收回无障碍授权，保留 failure.json |
| 重新授权后持久化与回放 | Function 内容不变；`tool-b899fca2-0f08-465b-90de-7b044361a778` 成功，实际搜索页再次可见 |
| 运行记录 → 详情 | 展示本次成功、2 个执行步骤（打开设置 + 映射点击），耗时 3.04 秒 |

两次脚本回放的点击均有真实 OmniTransfer `omnitransfer_point_conditioned_sparse_graph_v10` 映射证据。没有用测试侧坐标替代运行时迁移；测试侧点击只用于操作真实 App 控件。

自动生成的标题本次为泛化的 `Complete requested task`，命名质量仍待改善；不能把可执行等同于模型生成的名称与语义描述已经完善。本轮只验收上述明确路径，不代表所有应用、所有参数类型和所有设备均已真机覆盖。

## 可执行回归

```sh
cd ui
flutter test test/features/task/run_log \
  test/features/home/pages/command_overlay/services/manual_recording_flow_controller_test.dart
flutter test test/features/home/pages/settings/settings_page_test.dart
flutter analyze lib/features/task/execution lib/features/task/pages/execution_history \
  lib/features/task/router_config.dart lib/features/home/pages/settings/settings_page.dart
```

32 个执行/详情/录制/参数/控制器测试、2 个设置测试通过。包括与 OmniFlow 无关的内存 backend、加载去重、分页偏移、失败保留、迟到响应、重复执行阻止、录制完成刷新。调用协议测试先复现红测，修复后通过。构建通过。执行中心范围分析零问题；加入设置页的最终分析有 1 条原有 `unnecessary_underscores` info（settings_page.dart:250），无错误。

真机重复执行（需同一测试录制仍在，手机解锁、已授权，并停留执行中心或上次回放的设置搜索页）：

```sh
OOB_GUI_SERIAL=b49f281b OOB_ALLOW_PHYSICAL_DEVICE=1 \
OOB_GUI_OUTPUT=/tmp/oob-execution-physical \
python3 scripts/verify-execution-center-device.py \
  --function-id complete_task --recorded-run-id human_1789629806389_e98977c9
```

脚本通过真实 UI 发起执行，仅通过调试工具读取运行记录；会强停 App 验证持久化。如系统撤销权限，脚本明确失败。在系统 UI 重新授权后，用相同参数追加 `--resume-after-authorization` 继续；保留原 failure.json，不把首次失败改写成成功。录制创建过程本轮以实际按钮逐步操作完成，脚本不伪造源录制，也不自动生成该 fixture。

证据目录：[artifacts/execution-center-20260917](artifacts/execution-center-20260917)。
