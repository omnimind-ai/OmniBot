# 录制控制条与执行中心回放 — 2026-09-19

目标仍是整体修复，按用户授权在模拟器进行真实任务验收。emulator-5580 / Android13 ARM64 / 0.6.3(16) Debug；APK SHA256 f4055ed42732b294d287ab2f86c29215ee64c03896c9c6070b2b32c5146b4142。

## 已复现与修改

上一轮真实Transfer失败source节点为Pause manual recording，目标页面是Execution Center。ManualRecordingControlOverlay现在在手动输入/按键/等待与触控手势的既有录制动作期间临时隐藏控制条，finally以NonCancellable恢复仍有效的原View；不替换录制动作或源坐标。

OmniFlowToolChannel沿用interactive工具beforeOperation，在第一次操作前通过已有TaskRuntimeSettings活动引用移到后台，等待真实onActivityPaused；管理工具不执行此hook。失败正常返回，不增加Agent loop或重放。

## 验收

- 既有OmniFlowToolChannelManualRecordingTest 5项通过（源码合同检查，补充证据）；Debug构建成功并覆盖安装。
- 真正从零录制两步设置搜索battery，通过。原始canonical-source-run.json与clean-source-evidence.json确认四个前后观察均无暂停录制节点，屏幕记录1080x2400。驱动最初错误地读取xml而非forest，现同时支持两种正式观察表示并强制非空；对已保存实际观察重新执行断言通过，未改原始证据。
- 执行中心真实点击原始Function，设置首页→搜索→battery完整两步回放通过。离开后第二次重跑同一路径通过。分别见attempt1/replay-verified.json、repeat/replay-verified.json和两份真实replay-run.json；未绕过OmniTransfer。
- 真实增强仍被拒绝：完整Function的parameter缺少bindings，三次均function_author_parameter_invalid。证据attempt1/author-terminal.json。已补canonical字段缺失反馈及回归，尚未打入此安装包。

范围：这证明原始两步回放和重复操作；参数化完整回放、此次录制的新包重启后回放、取消期间恢复控制条仍需后续设备验收。不能据此宣称整体完成。输入法切换的窗口树仍出现超屏边界，当前实际Transfer回放成功；是否影响其他场景待核验。

## 后续增强2.2.9

已构建、APK组件9项通过并覆盖安装，复用新录制human_1789758379450_64f45642。模型将两个顺序动作错分成两次occurrence，search_term只绑定第二次，三次均parameter_occurrence_incomplete。authoring-bindings/author-terminal.json保存实际模型结果；无新增Function。

2.2.10补充每个occurrence是完整调用、完整函数只有occurrence_index=0的合同，缺失绑定反馈参数名/缺失调用索引/实际span，越界索引反馈合法范围。不自动改步骤或补绑定。新增回归覆盖两类错误，设备编译和render binding共62项通过。

## 后续增强2.2.10

APK构建、组件9项检查和安装通过。真实模型修正了occurrence划分，但complete_function.parameters仍只有name/description，三次字段缺失反馈后也未补上。局部Function拥有正确绑定，完整Function缺失绑定；没有注册，证据authoring-occurrences/author-terminal.json。

2.2.11为既有参数校验补location路径：局部functions[i].parameters[j]、完整complete_function.parameters[j]。明确完整/局部参数同一schema，空参数应为[]；不填补或删除模型方案。host回归核对完整字段路径，62项通过。

## 2.2.11：参数化两步流程首次通过

APK SHA256 a364c434806880330694b00c8a2cd974d70026f7d6a5b426ce03ca4045040772，最终APK组件9项通过并安装。authoring-path：真实增强生成两个2步可执行Function，参数search_term绑定第二步text；从设置首页实际输入wifi，完整回放成功，真实UI和RunLog一致。restart阶段强停重启后Function原样保留。

重复回放在恢复模拟器既有无障碍绑定后，首次实际输入为ifi，RunLog参数和最终页面均ifi：测试驱动在焦点/输入法过渡时过早注入，属于驱动失败，保留parameter-replay-restart。脚本现等待输入框焦点、可见输入法，并核对输入框实际等于wifi才提交，不重发或补写业务结果。parameter-replay-focus重跑完整回放通过。不能声称无障碍服务强停后无须恢复。

当前继续用最终同一安装包从零重新录制并增强，以核验不依赖之前方案。整体Vibe/remote/最终Release仍未完成。

## 最终包从零重测的失败与后续

2.2.11 fresh-final重新录制通过，但真实模型错误认为task_parameter要求planner_handoff，functions为空，被既有safe_local校验拒绝。前一次成功不能证明稳定生成，已保留fresh-final/author-terminal.json。

2.2.12为既有Stage2合同补对称校验：模型自报所有步骤stable/task_parameter时，完整Function要求direct_replay；online_observation仍要求planner_handoff。只校验自报分类与模式一致性，不改变模型分类/绑定/动作。host回归覆盖模式错误→精确反馈→模型修复，63项通过。

## 2.2.12 最终本轮验收

APK SHA256 6ba399ed7960bd23f98e23ca3dda818d0e49c28c0dc4286902545793d342dee6。最终APK组件9项检查通过，覆盖安装成功。authoring-mode使用fresh-final实际新录制，真实模型生成参数化完整流程，实际UI提交wifi后两步回放成功；authoring-mode/replay-verified.json及replay-run.json确认参数、动作和页面一致。该组restart.json确认新Function强停重启后原样保留。

本轮累计两份独立实际录制通过增强/完整改参回放（第一份2.2.11，第二份最终2.2.12），两组均验证Function重启保留；第一份另通过重复回放，首次输入法焦点驱动失败保留。没有将多次调试结果写成一次成功；没有替模型写Function或binding。

仍未覆盖：最终同一Release包、更多不同任务/模型、录制取消期间浮层恢复，强停后无障碍系统绑定的用户恢复路径。整体Vibe自主测试返工、remote网络恢复等继续进行，不能据此标整体完成。
