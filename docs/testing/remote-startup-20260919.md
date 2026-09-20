# Remote 普通启动恢复原会话 — 2026-09-19

旧Release实际任务A成功后强停启动无法自动恢复，手动侧栏重开才能恢复；原始失败保存在artifacts/release-final-20260919/remote/restart-history.json。

根因：chat_page_lifecycle.dart 的 _persistVisibleThreadTargetIfNeeded 在remote分支提前return，未保存last-visible target。现在先保存既有导航目标，再保留remote跳过本地current-conversation/history写入的分支。不新增ACP生命周期、重试或消息重放；启动仍遵循用户resumeLast/newConversation设置和现有session/load。

验证：Flutter ConversationHistory/Storage/ChatArchitecture共54项通过，包含新增remote导航身份持久化、清除一次性route/requestKey且不覆盖本地历史选择的案例。定向analyze无error/warning，5条info。该服务测试不替代实际启动路径验收。

可执行设备回归：在真实Remote任务完成、回复可见且空闲后，运行 `python3 scripts/verify-remote-chat-device.py emulator-5580 --expect OOB_MARKER --restart --output OUTPUT.json`。脚本只强停与普通Launcher启动，不打开侧栏、不给route、不重发任务；要求自动恢复原回复且UI无运行按钮，保存前后截图。还需独立后端身份/唯一任务核对及继续任务。

新ProductionStandardRelease测试签名候选SHA256：3d6723f50ed0249a02f04b846a4fe33f6d260bbf281bd5492ee56d74b4940ec3。构建成功2m3s，apksigner验证通过。记录见artifacts/remote-startup-20260919。尚未安装，当前模拟器由60页真实任务占用，不中断它。**待模拟器实际启动验收，待真机验证**。公网/TLS/NAT/休眠不在本次已验证范围。

后续候选加入运行日志官方动作坐标及截图/标记共同缩放，APK位于app/build/outputs/release-candidate-20260919-remote-startup-scaled/OmniBot-0.6.3-release-test-signed.apk，SHA256 d989f74a9b9c96ffaa8eccbead90483f69e581384fd0f33cbdb59b8d6378d343。构建2m成功、测试签名验证通过；包内Vibe0.2.2的5个文件与当前源文件逐字节一致。截图官方/旧格式和缩放位置widget检查4项通过。尚未安装，不能将包内容核对称作模型自主验收通过。

已保留数据安装d989f74a候选，设备base.apk哈希一致。首次准备错误使用不支持的agentSessionId查询参数，停留在本地聊天，记录为before-restart.json失败且未发送任务；改走实际侧栏Computer→Create and read release file，旧回复B可见。执行verify-remote-chat-device.py --restart后，强停→普通Launcher启动无需手动选会话自动恢复B，通过，证据automatic-restart.json和前后截图。正在从恢复页面实际发送独立任务C以核对继续执行；尚未计C通过。

恢复页面真实发送任务C通过：读取原B、创建release-startup-20260919-c.txt并读回，手机最终回复可见。独立共享后端只读验证同一sessionId=01a0b708-221e-77d1-84d0-68dccbfd4428，唯一新user turn且官方completed，包含真实commandExecution；宿主新文件SHA256 f72062b7997f5984437c7b693746fa0b3677ebe4f78c272d0e2e5a0ca883559a，旧B仍原样。证据continued-real-task.json与continued-backend-files.json。第二次普通启动恢复最新C正在检查；本次是模拟器真实任务，公网/TLS/NAT/休眠及物理设备仍未覆盖。

- d989f74a测试签名Release / emulator-5580：Remote原B强停普通启动自动恢复通过；恢复后手机真实发送C、同一后端会话唯一执行、宿主文件正确且旧B保留通过；第二次强停普通启动自动恢复最新C也通过。证据artifacts/remote-startup-20260919/{automatic-restart,continued-real-task,continued-backend-files,second-automatic-restart}.json。自动恢复问题在本模拟器实际场景验收通过，待真机验证；不代表公网/TLS/NAT/休眠通过。
