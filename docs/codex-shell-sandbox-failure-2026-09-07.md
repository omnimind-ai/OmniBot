# Codex 命令执行失败：Android/proot 沙箱兼容性

## 当前真实执行

手机 b49f281b，Codex CLI 0.153.4，ACP 会话尾号 d5a17ed9。
2026-09-07 09:43–09:44 的用户回合中，模型成功调用 exec_command，
但 ls、pwd 和 find 三次均立即以 182 退出，无 stdout。
该回合的实际策略为 on-request + workspace-write，网络受限。
最终 ACP end_turn 只表示模型结束了回合，不代表命令执行成功。

## 同机独立复现

使用应用已安装的 Alpine/init-host 引导，同一个 app UID，在 /workspace 执行
无网络、无模型请求的固定打印命令。没有修改用户的权限或 Codex 配置：

| 路径 | 结果 |
| --- | --- |
| 普通 /bin/sh 打印 OOB_SHELL_OK | 成功 |
| codex sandbox -- /bin/sh -lc 'printf OOB_SANDBOX_OK' | 退出 182，无输出 |
| codex --enable use_legacy_landlock sandbox -- /bin/sh -lc 'printf OOB_SANDBOX_OK' | 退出 101，Sandbox(LandlockRestrict) |

因此，这个样本的失败在 Codex 的本地命令沙箱启动边界，和先前官方 Provider
401 是不同故障。182 本身不是普通文件权限错误：PRoot 官方 loader 源码
使用它报告致命加载失败。尚未定位默认沙箱内部具体哪一个系统调用触发失败；
不能仅凭退出码宣称已经修好 PRoot。

此前 Codex 正文投影的真实验收覆盖文字和连续对话，没有覆盖命令执行。
不得用这些成功样本宣称完整 Android 执行适配已通过。本项仍未修复；
不能把关闭沙箱后的成功等同于原受限执行策略已兼容。

参考：
- https://github.com/termux/proot/blob/master/src/loader/loader.c
- https://github.com/openai/codex/issues/30153 （相关 PRoot 沙箱失败报告，错误字符串不同，并非本机根因的替代证据）

本机临时诊断：/tmp/oob-codex-exec-probe.py；实时日志：
/tmp/oob-codex-permission-current.log。报告不包含凭证或私有对话正文。
