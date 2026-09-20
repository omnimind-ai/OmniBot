# OmniFlow 完整 authoring 接入验收（2026-09-18）

用户要求：复用 OmniFlow-exp 的完整 authoring，跑通实际录制、注册、参数绑定和执行。

## 修改

`buildFunctionEnhancementPrompt` 仅转发 `save_function({run_id, enhance:true})`，不再提供 function/functions/arguments，避免进入已有 Function 的 metadata enhancement 分支。官方 compiler 的 semantic_analysis / Function 提炼 / binding / 注册流程保持原样。允许一次返回多个 Function，保留原始录制产物。

执行中心等待新 Agent 会话返回后通过现有 library owner 刷新 Function 列表，不添加 Agent 生命周期或旁路写入。

## 可执行回归

- `cd ui && flutter test test/features/task/run_log/omniflow_execution_center_page_test.dart`：入口 JSON、禁止传既定 Function、保留多个结果、返回后重新读取列表。
- `../OmniFlow-exp/.venv/bin/python -m pytest ../OmniFlow-exp/tests/test_device_runlog_compiler.py -k authoring -q`：现有官方完整 authoring 与拒绝结果处理。
- 真机驱动复用既有 Journey/device 工具，只在真实 UI 发起录制、增强和执行；debug 接口读取轨迹和 Store，不注入 Function、不替换 OmniTransfer。

```sh
OOB_GUI_SERIAL=b49f281b OOB_ALLOW_PHYSICAL_DEVICE=1 \
 OOB_GUI_OUTPUT=docs/testing/artifacts/omniflow-authoring-20260918 \
 python3 scripts/verify-omniflow-authoring-device.py --phase record
# 依次运行 author、模型真实完成后的 verify、replay、restart。
```

录制使用录制浮窗的“动作 → 输入文字”而非绕过录制器直接向目标输入框注入按键。UI 观察使用项目已有 observe 接口；录制时不要运行 uiautomator dump，避免其 UI Automation 会话干扰无障碍服务和浮窗。设备需保持无障碍、浮窗权限有效。

## 已完成的本地验证

页面测试15项通过，官方 authoring focused tests 2项通过；修改文件 Dart analyze 无问题。Debug、minified Release 构建成功。

真机状态以相邻 artifacts/omniflow-authoring-20260918 中的阶段产物为准。录制失败尝试保留为失败，不算参数化验收。源码和构建通过不代替最终真机结果。

## 真机发现的上游问题

第一次完整 authoring 返回 `function_source_state_conflict:state_d6f03fd9586c3598d79d`。已有 Function 的轻量增强复用 Store 冻结截图，但不提供 Function 的完整 authoring 重读 Android 当前同结构状态，导致证据冲突。

在 **OmniFlow-exp/omniflow/bridge.py** 的同一 owner 扩展冻结证据选择：已有 `function_sources` 记录对应 run_id 时，完整 authoring 同样使用现存 transfer-state catalog。未取消冲突校验，未覆盖源截图，未改动 action mapping。原测试增加 full_authoring=True/False 两种入口，修改前新分支失败，修改后整个 test_device_runlog_compiler.py 的17项通过。

打包运行时升为2.2.1；与2.2.0 ZIP逐文件比较，只改变 bridge.py、来源信息、host.json 与组件版本，OmniTransfer/checkpoint 不变。10项运行包测试（包含实际 APK 内嵌归档校验）通过。

## 最终真机结果：通过

PJE110 / b49f281b / Android 16，App 0.6.3 (16)，OmniFlow runtime 2.2.1。

- 真 UI 录制 `human_1789700328296_2ad5d047`，两步：点击设置搜索、输入 battery；RunLog succeeded。
- 点击增强，Agent 实际调用 save_function，仅 run_id/enhance；修复后生成两个 Function：`search_settings`、`complete_task__e4acd0993b9c0232`。原录制 Function 保留。
- 两个产物均包含 required string `search_term`，真实绑定 `$.arguments.search_term -> $.steps[1].action.args.text`。
- 从实际参数页传入 wifi，官方 run_function 执行到系统设置搜索框 wifi；RunLog `tool-e8b485b0-0792-4977-9137-2bb3fa6f01da` succeeded / function_completed，实际 input_text 参数为 wifi。
- 强停并重启 App 后，通过 canonical list_functions 读取，两份 Function 的完整定义与重启前一致。
- 覆盖安装最终 minified Release 后，参数化详情仍在；实际参数页再次输入 wifi 并执行，截图确认系统设置搜索 wifi。Release APK 内嵌归档10项校验通过。
- OEM 强停会关闭小万无障碍服务；最终已通过系统设置恢复。录制时不使用会干扰服务的 uiautomator dump。
- 此为产品 Function authoring/参数执行/持久化验收，不等同于跨设备或自主召回 benchmark。

最终 APK：`artifacts/OpenOmniBot-authoring-2.2.1-release.apk`（Android debug 证书签名的 Release 测试包）；SHA-256 与详细结果见 `artifacts/omniflow-authoring-20260918/verification.json`。

## 后续录屏复测限制

随后两份新录制未通过参数化验收：一份 authoring 被编译器拒绝，另一份产出无 bindings 的 Function。上述成功只代表先前单次运行，不代表 authoring 已稳定。[完整实测与视频](artifacts/omniflow-authoring-demo-20260918/README.md)。已有参数化 Function 的独立执行仍通过。
