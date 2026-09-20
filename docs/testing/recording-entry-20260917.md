# 手动录制入口与插件详情简化

用户反馈：手动录制入口未显示，OmniFlow 插件详情“开始使用”太复杂，要求去掉。

修改前真机执行中心底部已有“手动录制”，未复现按钮丢失。此次按插件入口不明确处理：删除 OmniFlow catalog 的 ready 说明卡片，并使通用详情页只在存在 ready 配置时显示“开始使用”标题。其他插件的 ready 能力保留。将原来的“已保存操作与执行记录”按钮改为“手动录制与复用指令”，继续导航同一执行中心，未添加录制实现或新的生命周期。

验证：插件市场与执行中心 Flutter 回归 26 项通过，包含录制按钮在两个标签页可命中、一次启动及完成后恢复；Dart analyze 无错误，只有 plugin_detail_page.dart 原有第 140 行 braces 提示。默认 APK 构建、保留数据安装成功。

真机 PJE110 / b49f281b，Android 16，0.6.3/code16 developStandardDebug。已实际打开 OmniFlow 插件详情、滚动到末尾确认“开始使用”消失、点击新入口进入执行中心，并查看两个标签页底部按钮。APK SHA256：`c421bf723d547f49662b6d020c847ad8d4ab9c1a485f1e9cd2be407009a48f55`。

新增 `scripts/verify-recording-entry.py`，复用实际 UI Journey 与 controls audit；从插件详情开始验证整页没有旧卡片、入口跳转、两个标签页显示按钮，再启动/暂停/取消录制。首次补充流程因运行记录标题也叫“手动录制”，旧 driver 无法唯一选择而超时；后续页面离开测试入口，重试前置条件失败。脚本已改为回到复用指令页再调用已有录制审计，但该补充分支未重跑完成，**本轮不将开始/暂停/取消算作通过**。界面显示和跳转的真机观察已经完成，不能把它们扩张成录制全流程验收。

执行环境：`OOB_GUI_SERIAL=b49f281b OOB_ALLOW_PHYSICAL_DEVICE=1 OOB_GUI_OUTPUT=/tmp/oob-recording-entry python3 scripts/verify-recording-entry.py`。必须从已打开的 OmniFlow 插件详情且设备空闲时开始。失败与脱敏结果保存在 [artifacts/recording-entry-20260917](artifacts/recording-entry-20260917/)。未发布 release。
