# 小万长上下文 40 步长期回归

测试集 ID：`xiaowan-context-40`。复用 `verify-agent-user-journey.mjs`，按顺序在同一个隔离模拟器、同一条聊天中执行：

| 用例 | 步数 | 关键断言 |
| --- | ---: | --- |
| `xiaowan-file-read-regression.en.json` | 16 | HTML 重复读取、原图 hash、PDF 二进制返回、取消后继续、重启 |
| `xiaowan-context-overflow.en.json` | 17 | 三个各 20 次读取的任务、其后普通消息、唯一完成记录、重启 |
| `xiaowan-context-summary.en.json` | 7 | 实际生成摘要、工具不重复、检查点 hash/cutoff/revision 跨重启一致 |

统一入口会拒绝覆盖旧证据目录；失败即停止，不重发用户消息。三个用例全部通过还不够：服务端日志必须包含本次 runId 的实际摘要请求和原图 hash 校验，旧日志不能充当成功。

## 准备

需要 Node.js、Python 3.9+、ADB、已安装 debug APK 的隔离模拟器及其 `run-as`/SQLite 测试能力。用例页面为英文，小万模式，聊天已打开且空闲。复用旧的压力测试聊天可覆盖持续累积；新聊天的 40 步不等于复现历史上数千条记录的堆压力。

```sh
python3 scripts/fixtures/generate-context-files.py /tmp/oob-context-data
adb -s emulator-5560 shell mkdir -p /data/local/tmp/oob-context-data
adb -s emulator-5560 push /tmp/oob-context-data/large.html /tmp/oob-context-data/notes.txt /tmp/oob-context-data/sample.pdf /tmp/oob-context-data/large.png /data/local/tmp/oob-context-data/
adb -s emulator-5560 shell run-as cn.com.omnimind.bot mkdir -p workspace/oob-file-repro workspace/oob-image-repro
adb -s emulator-5560 shell run-as cn.com.omnimind.bot cp /data/local/tmp/oob-context-data/large.html /data/local/tmp/oob-context-data/notes.txt /data/local/tmp/oob-context-data/sample.pdf workspace/oob-file-repro/
adb -s emulator-5560 shell run-as cn.com.omnimind.bot cp /data/local/tmp/oob-context-data/large.png workspace/oob-image-repro/large.png
```

生成目录必须不存在；`manifest.json` 记录所有输入的长度与 hash。PNG 是确定性的合成噪声图，约 17 MB，不包含私人图片；PDF 是二进制读取样本，不用于证明 PDF 渲染能力。

单独终端启动既有受控 Provider，保留 PID/终端供结束后关闭：

```sh
OOB_FILE_TEST_DIR=/tmp/oob-context-data OOB_FILE_TEST_IMAGE=/tmp/oob-context-data/large.png OOB_FILE_TEST_SERIAL=emulator-5560 node scripts/fixtures/file-read-provider.mjs > /tmp/oob-context-provider.jsonl 2>&1
```

记录模拟器原来的 Provider profileId/modelId，随后在隔离模拟器中绑定测试 Provider：

```sh
adb -s emulator-5560 shell am broadcast -n cn.com.omnimind.bot/.debug.DebugModelProviderConfigReceiver -a cn.com.omnimind.bot.debug.CONFIGURE_MODEL_PROVIDER --es operation configure --es profileId oob-context-regression --es name OOB-Context-Regression --es baseUrl http://10.0.2.2:18769/v1 --es apiKey fixture-not-a-secret --es modelId gpt-4o
```

配置结果保存在 App 的 `files/debug-model-provider-config-result.json`，确认 success 后在小万聊天中选择该测试模型。测试保留聊天数据，不执行卸载或清数据。

## 执行

```sh
node scripts/verify-xiaowan-context-suite.mjs --list
OOB_FILE_TEST_PROVIDER_LOG=/tmp/oob-context-provider.jsonl node scripts/verify-xiaowan-context-suite.mjs emulator-5560 /tmp/oob-context-run-001
```

输出包含各组原始证据、当前运行的 `provider.jsonl` 和总 `result.json`。`--list` 仅检查清单，明确标记 `executed: false`。运行后使用上述 receiver 的 `bind_existing` 操作恢复先前记录的 profileId/modelId，再关闭测试 Provider。

## 验证状态

- 原有三组 40 步在 APK `580f93ba9bdfe4fd2d73f6dd441e18e23b5f029c45941252ffe2485f24973d5e` 上实际通过，见 [完整记录](context-six-audit-2026-09-08.md)；当时分别运行三组。
- 本次 `node --test scripts/verify-xiaowan-context-suite.test.mjs` 的 3 项入口判定测试通过；数据重复生成一致，PNG CRC 和解码长度校验通过；离线读取既有三组报告及 104 条 Provider 记录，通过新入口校验。这不是重新跑过设备操作，统一入口的完整设备重跑待执行。
- 模拟器结果不能替代真机验收，**待真机验证**。不修改 CI 工作流，不把设备不在线跳过为成功。
