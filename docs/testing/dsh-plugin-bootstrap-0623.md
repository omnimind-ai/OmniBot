# DSH 首次插件发现

用户反馈：全新启动 DSH 不知道自己的插件机制，将 DSH 插件解释为 OmniFlow 函数。
安装包版本 0.1.2-rc.1，自带 dsh-tool-cordis 与 dsh-cordis-host-runner，但官方 ACP/base profile 未挂载这两个组件。官方工具包 README 明确说明没有 shipped bundle 挂载该工具集，且动态定义是进程内临时对象，不在重启后保留。

本次修改：安装时从实际安装包的 README.md 和 dsh-tool-cordis/README.md 生成版本匹配的参考技能，启动器通过官方 DSH_BUNDLED_SKILL_DIR 接入现有 skill-filesystem。使用专用托管目录，不改用户 Cordis patch/skills。准备版本提升 v4，使存量安装重新准备。没有新增 Agent loop、插件运行时、WebView 或权限降级。

验证：
- node --test scripts/deepseek-shell-install.test.mjs：5 通过（全新生成、重复生成、上游文档缺失失败、用户 profile 保留）。
- node --test scripts/deepseek-browser-navigation.test.mjs：2 通过。
- python3 scripts/verify-dsh-plugin-reference.py emulator-5554 OUTPUT：真实模拟器 App UID / Alpine / 官方 FileSystemSkillProvider；临时空 DSH_HOME，生成后发现并读取 dsh-plugins，重新构造 provider 再次通过；未调用模型、未更改用户权限。产物 artifacts/dsh-plugin-reference-0623/result.json。

未完成：整个发行版从空磁盘安装、模型实际选择技能、官方动态插件 define/run/stop 与持久插件安装/启停流程、真机验收。不能将参考发现通过写成完整插件能力验收。当前 Android 内核沙箱探测失败，Alpine/Ubuntu PRoot 不提供独立内核；该项是当时的权限状态；后续用户已明确同意本设备 DSH 完全访问，其他 Agent 权限未改。

APK 构建成功（1m57s），emulator-5554 覆盖安装 Success 并打开；首次 App 内触发运行准备、模型读取技能仍待验收。


## 2026-09-10 发布依赖升级

固定官方 `@deepseek-ai/dsh@0.1.5-rc.1`，同步安装器、注册表与 preparationRevision。
不新增四个 preset 的私有切换接口：该版官方 ACP 仍未暴露这些选项。
发布构建采用主线已有的 JDK 21 修正（来源 5ef08dc11），未纳入主线新返回动画。

模拟器 emulator-5554，Android 13 / arm64 / Alpine，App 0.6.2.3 / 15：
- 运行实际安装脚本升级成功，保留已有 profile；npm 安装更新 498 包。
- 下载使用临时本机官方 npm 转发（主机 HTTPS、npm integrity 校验），未持久化该地址；设备直连失败，不能将此结果作为所有网络下安装成功的证明。
- 官方 ACP initialize 成功，protocolVersion=1；独立空 DSH_HOME 与重复官方技能发现通过，上游版本确认 0.1.5-rc.1。
- 聚焦 Node 安装/登录测试 7 项通过；EnvironmentSetupLogicTest、ManagedAcpAdapterPreparationTest 和 debug APK 构建通过。新版覆盖安装 Success。
- 旧沙箱探针引用的 Landlock 包在新版更名，已支持官方 node-addon-system/landlock-run，同时兼容旧包；实际 Android 内核仍不能提供 workspace-write/read-only 隔离，此问题未宣称修复。

执行入口：
```sh
node --test scripts/deepseek-shell-install.test.mjs scripts/deepseek-browser-navigation.test.mjs
node scripts/verify-deepseek-acp-initialize.mjs emulator-5554 --alpine --managed-patch --disable-link2symlink --filesystem-compat
python3 scripts/verify-dsh-plugin-reference.py emulator-5554 OUTPUT.json
python3 scripts/verify-dsh-sandbox.py emulator-5554 OUTPUT.json
```
最后一项在本设备预期报告不可用并退出 1，不属于通过的隔离验收。
证据见 artifacts/dsh-015-0623。仅模拟器连接，**待真机验证**。

新版 App 内真实模型回合已尝试，官方 ACP 返回 `Internal error: turn failed: Connection error.`；没有执行到本轮工具验收。因此新版完整模型/工具链验收未通过，不能用 initialize 成功替代。
