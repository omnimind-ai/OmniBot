# DSH 首次插件发现

用户反馈：全新启动 DSH 不知道自己的插件机制，将 DSH 插件解释为 OmniFlow 函数。
安装包版本 0.1.2-rc.1，自带 dsh-tool-cordis 与 dsh-cordis-host-runner，但官方 ACP/base profile 未挂载这两个组件。官方工具包 README 明确说明没有 shipped bundle 挂载该工具集，且动态定义是进程内临时对象，不在重启后保留。

本次修改：安装时从实际安装包的 README.md 和 dsh-tool-cordis/README.md 生成版本匹配的参考技能，启动器通过官方 DSH_BUNDLED_SKILL_DIR 接入现有 skill-filesystem。使用专用托管目录，不改用户 Cordis patch/skills。准备版本提升 v4，使存量安装重新准备。没有新增 Agent loop、插件运行时、WebView 或权限降级。

验证：
- node --test scripts/deepseek-shell-install.test.mjs：5 通过（全新生成、重复生成、上游文档缺失失败、用户 profile 保留）。
- node --test scripts/deepseek-browser-navigation.test.mjs：2 通过。
- python3 scripts/verify-dsh-plugin-reference.py emulator-5554 OUTPUT：真实模拟器 App UID / Alpine / 官方 FileSystemSkillProvider；临时空 DSH_HOME，生成后发现并读取 dsh-plugins，重新构造 provider 再次通过；未调用模型、未更改用户权限。产物 artifacts/dsh-plugin-reference-0623/result.json。

未完成：整个发行版从空磁盘安装、模型实际选择技能、官方动态插件 define/run/stop 与持久插件安装/启停流程、真机验收。不能将参考发现通过写成完整插件能力验收。当前 Android 内核沙箱探测失败，Alpine/Ubuntu PRoot 不提供独立内核；完全访问尚未获用户明确同意，保持原权限。
