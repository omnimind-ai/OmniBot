# Release RunLog 序列化 — 2026-09-19

最终候选 bd53088f…ac04f0a，emulator-5580 Android13，实际设置搜索 Function 改参回放。执行前应用强停后无障碍关闭，Run 弹明确引导，未开始流程；通过系统设置 Omnibot→Use Omnibot→Allow 恢复。原参数等待脚本误用 Debug XML 的 visible-to-user/IME节点，标准 UIAutomator 不提供，失败保留；改为真实 EditText 焦点及系统 input_method 的 mInputShown 后，实际回放创建新日志。

真实 Release 新日志 tool-538b5648-3e40-4498-9db9-7b85264ba00b 的快照字段是 schema_version/run_id/c/d/e/started_at_ms/finished_at_ms/i/final_state_id/k。R8 mapping 明确 goal→c、status→d、success→e、error→f、steps→i、diagnostics→k。读取端 parseRunSnapshot 要求原始协议键，因此快照不兼容。事件 NDJSON 使用 Map 固定键，现有读取逻辑可从事件恢复；本次不新增迁移或猜测混淆字段。

修复：CanonicalRunLogRecord 的上述6字段添加 @SerializedName 固定协议名，不关闭R8、不添加整包keep。新增 CanonicalRunLogSerializationTest，使用 hostile FieldNamingStrategy 验证全部协议键与运行/成功/失败/取消状态。旧实现实际1失败，修改后1通过。该测试不是R8设备替代品。

证据：artifacts/release-final-20260919/runlog-serialization/before.json、before.events.ndjson、before-test.xml、after-test.xml。真实运行脚本 verify-omniflow-release-replay.py 已加入固定协议键检查，缺失立即失败，不能把等待超时当模型失败；正常回放和可选 --recover-accessibility 均只通过真实UI执行，Root只读取产物。

修复后ProductionStandardRelease测试签名构建进行中（/tmp/oob-release-runlog-fixed-20260919.log）。尚未安装新包验收，不能宣称Release问题完成。旧候选的其他通过记录仍有效于旧哈希，不能自动转移给新包。后续需实际回放、新日志键、旧事件恢复、权限恢复路径以及新候选主要流程回归。

修复后测试签名Release构建成功3m48s，SHA256 6e2025353218ac08846c9d619afde15a4c6e6b57074235b25a1cc87ca135e1fa，已安装且设备哈希一致。模拟器强停后无障碍提示→系统设置重新启用→真实改参wifi回放通过，accessibility-replay-verified/result.json。新日志正确保存所有协议键，主流程索引0、1各执行一次，附加hide_keyboard checker单独记账。首次断言错误地要求总步骤2，失败保留；修正为完整主流程顺序而不排除合法checker。

旧混淆快照 tool-538b… 及新快照 tool-2c7c… 都已强停重开真实 Run Log 页，通过现有事件恢复/快照读取显示完成状态和3步，原文件哈希不变。入口verify-run-log-reopen.py；legacy-reopen-verified与new-reopen结果通过。首轮驱动未处理Flutter合并语义节点的换行，失败保留。

页面实际检查额外发现官方action_type扁平字段未被UI读取，三个步骤均显示Action。补充公共格式widget回归旧实现实际失败，展示层改读action_type及扁平参数，保留tool/args旧格式和原始JSON。2项widget测试通过，分析检查结果见public-action-ui。尚未把这项UI修复安装验证，当前6e2025包不含此后续UI修复。

UI修复候选构建成功1m20s，已校验签名并安装，SHA256 953fd15cb394562612c0ffb8a0cc2aa8520cb60438bc7c88dafa33975aa05563，设备base.apk一致。verify-run-log-reopen.py新增实际动作名称和运行开始时间断言：旧混淆快照和新规范快照的强停重开均通过，显示Tap/Press key/Enter text wifi，原文件哈希不变；证据release-runlog-ui-fixed-20260919/legacy-actions、new-actions。同包完整回放继续验收，其他大功能同包回归尚未全部完成。

最终UI修复候选953fd15c…aa05563再次真实无障碍恢复和完整wifi回放通过，run tool-e6586656-6f3b-42d2-ad01-4171e263a198，见release-runlog-ui-fixed-20260919/replay/result.json。同包模型图片上传回归进行中；整体发布验收未完成。
