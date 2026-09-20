# Authoring 坐标证据修复 — 2026-09-19

整体目标进行中；本轮修复了事实表达，但实际 authoring 仍失败，不能宣布稳定性问题解决。

## 修复

在 canonical OmniFlow-exp/omniflow/functions/compiler.py 的既有 _source_ui_projection 中增加显示尺寸（复用 observation_display）和单位说明：动作点为0–1000归一化坐标，节点 bounds 为像素。官方 authoring prompt 说明按宽高换算后比较，原始动作/节点不修改、不选目标、不推断或补写 binding。

扩展既有 test_runlog_authoring_uses_host_model_and_compiler_feedback，用1080×2400而非方形1000测试输入；验证模型实际收到单位/尺寸、原始节点边界和正确归一化动作，同时保持已有编译反馈/注册检查。首次样本前后屏幕尺寸不一致触发 display_conflict，修正样本后18项通过；用进程内旧 nodes-only 投影重跑回归，明确在缺失 display 处失败。未改设备数据模拟成功。

## 打包与安装

- 组件2.2.3，ZIP SHA256 `e2da696223326034d06b38fe62ea157de5539ef9a317f21f6dc2b5a42abda333`。
- 相比2.2.2仅 compiler.py、component.json、host.json、runtime.properties 不同。OmniTransfer源码/权重保持原字节。
- Debug APK SHA256 `4a5662242c8460bf20e2bd82a65f917b3b9d5f700b321890a036c18a8d3b1680`。实际APK内组件9项测试通过；覆盖安装成功。
- emulator-5580 / Android13 ARM64 / 0.6.3(16)。设备实际加载 host version `2.2.3-35d83b33c341`；compiler.py SHA256 `156f55a9ed8f0d59652e668fe59a57d82975f194702f8ae103bc5e26f1eeab1f` 与 canonical 文件一致。

## 实际验收：失败

- 修正后的英文驱动从零录制两步设置搜索，通过，源 run_id `human_1789756103904_5a81da9b`。
- 实际增强按钮启动真实模型，84.7秒后正式 end_turn。save_function 返回 registered=false / FUNCTION_AUTHORING_REJECTED。
- 具体原因：`function_author_task_parameter_binding_incomplete:occurrence_index=0:source_step_index=1:missing_agent_declared_parameters=search_term`。未生成新Function，不做参数回放来冒充本次成功。
- 错误、diagnostic_path 和 diagnostic_persisted=true 已保留在工具历史；见 `artifacts/authoring-coordinate-20260919/attempt1/tool-result.json`。源轨迹、读取到的Function列表和失败断言同目录保留。

下一步：当前持久诊断只保留 rejected_error，临时目录中的 authoring_failure.json（含模型最后一份 raw_response）仍会被清理。应沿用现有诊断持久化边界保留失败 proposal 和尝试信息，以核对模型遗漏绑定的具体格式；不能猜测后自动补 binding 或绕过编译校验。随后继续真实模型/新录制/改参数回放与重启验收。
