# GLM 故障自检查、有限恢复与学习持久化

2026-09-16。用户需求：GLM 出错后不能卡死，需要自检查、自修复与持久化经验。

实际接口证据：现有环境的 GLM-5.1 模型列表、文本和文本＋原生工具均通过。有效 32×32 白色 PNG 的图片请求，以及图片＋工具请求，均 HTTP 400，错误含 gateway fallback timeout。GLM-4.6V 由同一 Provider 目录发现，图片＋report_color 原生工具返回通过。该证据定位到此 Provider 的图片请求路径，不证明所有 GLM-5.1 服务均不支持图片。初次图片探针的 PNG CRC 不正确，已弃用该样本并使用校验生成的有效 PNG 重做上述两类请求。

可行配置方案：保留聊天模型；通过现有 Provider/场景配置将 `scene.vlm.operation.primary` 设为验证过的 GLM-4.6V，再验收原 GUI 操作。本轮未修改用户模型绑定，未证明真机恢复。

实现：
- 复用原 HTTP transport owner，默认仅在无输出的临时错误后重试一次；显式上限限制为两次。400 不因错误文本出现 timeout 而重放，已输出或出现工具意图时仍终止原请求。重试日志不再输出服务商原始错误正文。
- ACP prompt 错误和 OmniFlow 模型调用在既有边界写入 `self-improving-agent/data/provider-diagnostic.json`。保存受控错误类别和建议，不存 prompt、响应正文、密钥或端点；取消不记录。诊断不改变 turn 状态，写盘异常不阻断终结。
- 修复 builtin 刷新/重装删除整个技能目录的问题，保留 `data/`，更新代码并删除过期非数据文件。
- 扩展现有 self-improving-agent；`scripts/check-provider.mjs` 从 stdin 接收配置，20 秒、单请求、禁止重定向、正常 TLS；text 与 vision_tool 两类探针独立验证，安全结果保存到 `data/provider-check.json`。探针成功不自动宣称永久修复。修复配置仍由原 Provider owner 持久化。

可执行回归：
```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest --tests '*BuiltinSkillAssetsTest' --tests '*ProviderFailureJournalTest' --tests '*HttpAgentLlmClientTest' --tests '*AgentRuntimeErrorSupportTest'
node --test scripts/provider-recovery.test.mjs
# 安装候选 APK 后运行；会重启指定设备上的 App，仅写入并清除独立测试标记。
python3 scripts/verify-provider-learning-persistence.py emulator-5580
```

53 项 JVM、4 项 Node 测试通过，skill 校验、APK 构建通过。JVM 包含原 GUI 失败脱敏 fixture 的 400 不重放、重试耗尽后下一请求成功、已有输出不重放、数据多次刷新保留和凭据不落盘。Node 包含 stdin CLI、跨进程持久化、401/400/429/503 分类、无正文泄露、有效 PNG 和原生工具验证。测试已接入 `scripts/test-agent-runtime.sh`。

设备：emulator-5580、0.6.3 / code 16，覆盖安装成功。删除单个打包脚本模拟缺失后，实际启动恢复该脚本；两次启动均保留独立 data 测试标记。测试结束删除自身标记，保留用户数据与 Provider 配置。APK SHA 与汇总见 [verification.json](artifacts/provider-recovery-20260916/verification.json)。首次 Gradle 使用 JDK 17 被工具链要求阻塞，改用本机 JDK 21 后通过。

**待真机验证**：目前没有物理设备连接。此模拟器检查只证明学习文件跨启动保留；原 GUI 操作、错误后的 UI 可继续发送、实际场景配置修复及重启恢复，均未作本轮真机验收。上游图片路径 400 仍存在；没有把纯文本或候选模型探针成功冒充原问题已彻底修复。
