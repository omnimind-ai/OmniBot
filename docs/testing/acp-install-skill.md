# ACP 安装技能验证（2026-09-10）

需求：由小万读取技能，在当前设备安装 ACP 助手，保留用户可编辑命令并根据真实错误排错。

通过现有 builtin_skills manifest 注册 install-acp-agent，沿用 skills_list / skills_read 和 terminal_execute。技能未新增自动注册接口；现有工具不能注册时明确通过 Agent mode 的自定义入口保存。技能不会把网络阻塞标为已解决。

执行 `node --test scripts/acp-install-skill.test.mjs`：1 项通过。实际运行诊断脚本，验证缺失 Node/npm、包管理器存在但不得调用安装、重复执行成功。

debug APK 构建成功，emulator-5554（Android 13 arm64）覆盖安装 Success，0.6.2.3，lastUpdateTime 2026-09-10 17:10:06。启动 MainActivity 成功，现有技能加载流程自动生成 workspace/.omnibot/skills/install-acp-agent/SKILL.md 及 scripts。

未执行：小万真实模型主动选择技能、完成 ACP 安装和对话、目标助手重启验证。没有连接物理设备，待真机验证。技能落盘不是完整安装能力验收。
