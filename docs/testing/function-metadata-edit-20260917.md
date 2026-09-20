# 手动编辑 Function 名称与描述

需求：仅增加详情页“编辑”入口，不调用模型、不改变 ID、来源、步骤或参数。

实现：页面经 ExecutionBackend.updateFunctionMetadata 调用现有 save_function；保存前读取最新 artifact，修改两项语义字段，移除管理层 source_run_id 后走已有更新路径。来源由同一 Store 保留。保存成功回读并更新详情和列表；失败保留输入；空值拦截，保存期间禁止重复提交。

自动化：function_metadata_edit_test.dart、execution_center_controller_test.dart、omniflow_execution_center_page_test.dart、omniflow_tool_client_test.dart 共 27 项通过；定向 Dart analyze 无问题。真机验证通过，结果见下方。

可执行真机回归：scripts/verify-function-metadata-edit.py，复用既有 Journey。参数 --function-id 和 --source-run-id 必须指向测试自建的录制；不修改用户其他指令。--phase edit 通过真实页面验证空名称、取消不写入、编辑保存、详情/列表同步及全部非语义字段保持原值；保留数据重启后 --phase restart 验证持久化。使用 OOB_GUI_SERIAL、OOB_ALLOW_PHYSICAL_DEVICE=1、OOB_GUI_OUTPUT 环境变量。


真机：PJE110 / b49f281b，Android 16，0.6.3/code16 developStandardDebug；APK SHA256 `67875bc31df603e6ee02eefa30ba393624f6dcf860d9ce38ce7bb9fdebade388`。真实 UI 修改名称与描述、空名称校验、取消不保存、详情/列表更新通过；随后保留数据重新安装以重启进程，重新进入执行中心回读通过。全部非语义字段与原始录制来源保持一致。未调用模型、未执行 Function。默认 APK 构建与安装成功，未发布新 release。

真机脚本开始时遇到 ColorOS 不响应注入 Ctrl+A、MOVE_END 仅移动到当前行末、对话框动画导致输入节点尚未就绪；已修正测试驱动的真实键盘清空与等待方式，保留失败日志，未将失败当通过。修正后 edit/restart 两阶段均实际通过。脱敏证据在 [artifacts/function-metadata-edit-20260917](artifacts/function-metadata-edit-20260917/)。

```sh
OOB_GUI_SERIAL=b49f281b OOB_ALLOW_PHYSICAL_DEVICE=1 OOB_GUI_OUTPUT=/tmp/oob-function-edit python3 scripts/verify-function-metadata-edit.py --phase edit --function-id complete_source_workflow__1d20e9d1cdb67c8c --source-run-id human_1789632950805_ef882039
# 保留数据重启/重新安装后，在新启动的主页执行相同命令，将 phase 改为 restart。
```
