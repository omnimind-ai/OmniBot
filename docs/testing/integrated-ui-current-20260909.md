# 生命周期修改后的 Flutter / WebChat 回归

2026-09-09，在 integration 工作树复用维护中的执行入口：

```sh
FLUTTER_BIN=/tmp/oob-flutter-3.47.2/bin/flutter bash scripts/test-agent-runtime.sh --skip-gradle --offline
```

整次脚本退出码 0：

- Flutter：**720 项通过**，覆盖维护名单中的 Agent reducer、会话协调与切换、取消/关闭、配置、弹层返回、历史展示及工具卡片等。
- WebChat：**12 项通过**，随后 typecheck 和 production build 通过。
- 该入口重复执行 Node 88 项、Python 53 项，均通过；这些是上一轮已有用例的重跑，不应相加声称新增不同测试。

本轮未执行 Gradle、真实 Provider 或其他 Harness CLI；未使用浏览器验收。原生 708 项和真实 GLM-5.1 结果属于 [前一轮独立执行](integrated-native-current-20260909.md)，不能拼接成一次全量运行。

证据 `artifacts/integrated-ui-current-20260909/` 保留汇总日志、命令、原日志哈希和范围说明。精确执行名单由 `scripts/test-agent-runtime.sh` 维护。没有新增框架或产品修改。

自动化 UI 测试与 WebChat 构建不代表真机操作验收；其他 Harness 的手机操作、DSH 隔离及精确并发时序仍有未完成项。**待真机验证，整体目标未完成。**
