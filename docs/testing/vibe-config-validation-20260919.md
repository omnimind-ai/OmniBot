# SQLite 配置不再静默忽略 — 2026-09-19

## 复现和修复边界

真实从零生成的 Life XP 把 check_in_date、_limit、_order_by 写进 executor.config。现有 SQLite 执行器只使用 config.table，业务值、筛选及分页来自 args。因此配置虽然存在却未执行，project_check 仍报告通过。

在既有 SandboxPluginPool.inspectProject 的源码检查/发布路径调用 SandboxProjectToolPolicy.validateSourceConfig。解析现有 connector 与 executor 合并后的配置，对 SQLite 拒绝 table 之外的字段，并指出工具名、无效字段及正确 tool arguments 入口。没有新增执行层、重试或推测 SQL；未改变已安装插件加载行为。

## 验证

- SandboxPluginPoolTest 新回归在旧实现失败：无效 _limit 被放行，非法重新发布未拒绝；原始 before.xml。
- 修复后 Pool、ConnectorContract、BridgeRuntime 三组定向测试执行成功，after.log。
- 补充 connector 继承无效字段/合法 table 接受测试，ConnectorContract 10 项通过，独立 XML 与 contract.log。
- 验证非法重新发布不破坏原已存记录；以前发布的配置仍可加载。

证据：artifacts/vibe-config-validation-20260919/。

ProductionStandardRelease 测试签名候选构建成功（4m26s），apksigner 校验通过。原模型回合结束后保留数据安装 emulator-5580，设备 base.apk 哈希一致。候选 app/build/outputs/release-candidate-20260919-vibe-config/OmniBot-0.6.3-release-test-signed.apk，SHA256 `46a10f277a37fe45848336dab19f837f74a181819f6c85f63ff05f421e2db86e`。新发布检查尚待模拟器真实模型调用验证，不宣称已完成；物理真机待验证。
