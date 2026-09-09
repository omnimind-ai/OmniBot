# 生命周期修复后的原生整组回归

2026-09-09，在 integration 工作树执行既有入口：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' PATH='/tmp/oob-flutter-3.47.2/bin:'"$PATH" bash scripts/test-agent-runtime.sh --skip-flutter --skip-webchat --live
```

真实 API 凭据由既有环境提供，不写入测试记录。该完整脚本本次连续完成，退出码 0。

| 检查 | 结果 |
| --- | --- |
| Node 协议、Provider、测试脚本契约 | 88 项通过 |
| Python 终态观察器 | 29 项通过 |
| Python init 断言、上下文检查点 | 8 + 5 项通过 |
| Python 子进程身份观察器、host 停止 | 9 + 2 项通过 |
| App Agent/ACP 原生测试 | 77 个测试类，708 项通过，0 失败/错误/跳过；本次实际运行 |
| baselib Shizuku 绑定 | 3 项已有通过结果；本轮 Gradle 为 UP-TO-DATE，未重跑 |
| 配置的真实 GLM-5.1 API | 模型列表与对话 completion 通过 |

原生范围由 `scripts/test-agent-runtime.sh` 维护，包含上下文预算/压缩、文件与图片、工具参数、终端、模型拒绝/流式失败、MCP、Session 配置/关闭/删除/取消、历史及绑定。它不是整个仓库所有测试，也不能证明所有可触发 UI 操作已经验收。

证据：`artifacts/integrated-native-current-20260909/` 中保留每个原生测试用例结果；复制时移除 system-out/system-err，manifest 保存原 XML SHA-256 和测试计数。没有把先前的失败改写成成功。

本轮未运行 Flutter、WebChat、其他 Harness CLI，也没有重新构建或安装 APK；这些必须引用各自独立结果。真实 API 冒烟不等于完整模型故障场景，已有用户流程证据也不能替代物理设备验收。DSH 沙箱仍失败。**待真机验证，整体目标未完成。**
