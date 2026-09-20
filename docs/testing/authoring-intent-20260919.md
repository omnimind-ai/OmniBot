# Authoring 复用意图与真实复测 — 2026-09-19

整体目标未完成，authoring 稳定性未验收通过。

## 本轮修改

- 增强入口继续调用 canonical save_function(run_id, enhance=true)，增加已有 instruction 参数：用户可在执行前指定的业务输入由模型显式分类为 task_parameter 并声明绑定；固定控件仍 stable，实时观察仍 online_observation。不传预编写 Function，不推断/补齐绑定，不修改 OmniTransfer。
- 15 项执行中心页面测试通过；首次测试缺 dart:convert 导入，修复后重跑通过。
- Debug 构建、覆盖安装通过：SHA256 `e78e8ebd80d53ad13b7416ba36153a867dab1d3ec5bbe1a3937c4257f8024d43`，0.6.3 (16)，emulator-5580 / Android 13 ARM64。

## 实际操作与失败保留

- 复用 verify-omniflow-authoring-device.py，新增 OOB_GUI_LANGUAGE=en，英文标签来自真实页面；允许设置搜索由 com.google.android.settings.intelligence 承载。
- attempt1：输入弹窗出现 IME 后驱动点击旧位置，弹窗消失，输入未录制；已取消该录制，失败不计通过。
- attempt2：改为等待弹窗输入框已经聚焦，避免旧位置点击。之后发现系统键盘与 App 弹窗均有 Enter，驱动因匹配不唯一停止。保留失败，限定 App 的 Button 后继续原录制，未重复动作。
- 最终真实录制 `human_1789755402339_0186a986`：点击设置搜索、通过录制器输入 battery，两步 succeeded。原始动作和完整 canonical RunLog 位于 artifacts/authoring-intent-20260919/attempt2/。
- 实际增强按钮触发真实模型，95 秒后正式 end_turn；得到 search_settings(search_term)，绑定 $.arguments.search_term -> $.steps[0].action.args.text。原录制保留。
- 但完整两步 complete_manual_recording 被标记 agent_visible=false，仅单步输入片段可执行。从设置首页经参数页输入 wifi 回放，正式结果 failed/function_yielded，未盲目执行原坐标。**回放验收失败**，不能把有参数当作全流程通过。

## 后续调查的具体证据

canonical compiler 的 _source_ui_projection 只传 nodes，未传显示尺寸/坐标单位；project_run_log_step_actions 的动作坐标则是0–1000归一化。

本轮真实首步 canonical action 为像素 (376,659)，display 1080×2400；编译动作变为 (348.148,274.583)，但 source_ui 搜索框 bounds 仍为 [200,624][553,695]。模型无法从输入明确对应两个坐标系。这是证据表达缺口，可能影响第一步 online_observation 分类；尚未取得模型完整分类理由，不宣称它是唯一原因。

下一步在 canonical OmniFlow compiler 的事实投影补齐单位/显示尺寸，保持原动作和节点边界，既不选择节点也不推断绑定。必须遵守该仓库 AGENTS.md 的文档读取要求后修改，增加 focused tests、重新打包组件，并重新实际录制/生成/改参回放及重启验收。当前尚未修改 upstream compiler，未更新运行组件版本。
