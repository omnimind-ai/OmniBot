# ACP 命令失败详情丢失

## 后续实际历史验收（最终实现）

安装后重开原有失败记录，先后发现旧事件及直接保存的卡片绕过实时投影。两次实际界面观察均未显示退出码，不能用前述 206 项通过替代设备结论。

最终将提取退出码和 formatted_output 收敛到既有 `normalizeAgentToolCall`：同时读取实时 rawOutput 与保存的 rawResultJson，仅用于详情，不改变当前状态或身份。撤回上轮 reducer 专属字段映射，避免两套解析。旧卡片详情缺少输出与说明时也复用该解析器，不迁移或重写数据库。

新增历史事件 4 项及旧卡片 transcript 1 项，历史与 transcript 均保留修复前实际失败日志。连同实时 reducer 共 **217 项通过**；既有 Node 合约及 journey **48 项通过**。新 APK `d1dc4920e1bb93a9dad8b4daa88d0cd5854fd12dc764c7d834bf398f56eb80a7` 构建并保留数据安装成功。

使用既有 runner 运行 `scripts/fixtures/agent-user-journeys/codex-existing-exit-detail.en.json`，两次重启后依次展开同一条既有合成记录并打开详情，**10/10 步实际通过**，都读到 `Command exited with code 182`。fixture 明确要求选择含合成运行 1788953461021 的 conversation 7；不是跨任意历史的通用用例，也没有重发该命令。该 runner 只新增透传既有 tap helper 的 parentLabel，沿用新鲜坐标和唯一控件断言。

最终结果及两次详情截图归档于同目录，已关闭详情返回对话。**待真机验证**；本次没有重新发送命令，新增消息的实际设备显示仍待验证。沙箱执行故障仍未解决。

以下保留上轮实时修复与中间包的检查记录，不代表最终实现或最终设备结果。

Codex 实际失败结果包含 `rawOutput: {formatted_output: "", exit_code: 182}`，工具官方状态为 failed，但共享卡片 summary 为空。共享 ACP 投影只接受 camelCase 退出码及 terminalOutput，漏掉这两个结果字段。

修复在现有 `_acpStructuredToolOutput` 规范化字段名称，原始 rawOutput 保留；现有工具解析器仅在终端工具已成功/失败且未提供 summary/message/description 时显示实际退出码。不合成 ACP 完成事件、不按退出码覆盖官方运行状态，不指定任何 sandbox 错误原因。没有布局变化。

六项新增 reducer 回归覆盖空输出、有输出、运行中带退出码、完成且零退出码、优先保留具体错误说明、缺退出码不猜测。前两项在修复前实际失败；修复后 `flutter test test/services/agent_event_reducer_test.dart` 共 **206 项通过**。一次中间命令误用了不存在的 parser 测试文件，已改为实际存在的 reducer 测试入口，未把中间命令算作通过。

`dart analyze` 三个相关文件退出 2：无 error，3 warning、61 info，详见归档日志；不能称静态检查全绿。APK 构建成功，SHA-256 `4e9b797680059fb48019b6f77fc38f76715e9af85e20834cc8368c6124413de5`，v0.6.2.2 develop debug，保留数据安装到 emulator-45562 成功。

证据在 `artifacts/acp-command-exit-detail-20260909/`。**本次尚未完成安装后的实际失败卡片操作及历史恢复验收，待模拟器操作验证、待真机验证。** 此修复只恢复错误事实的显示，Codex/DSH sandbox 执行故障仍未解决。
