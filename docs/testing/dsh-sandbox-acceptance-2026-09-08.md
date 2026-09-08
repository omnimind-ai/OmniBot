# DSH 默认沙箱验收：不通过

2026-09-08，emulator-5560 / OobCleanInstall20260907（Alpine，Android 13 ARM64）；App 0.6.1 (11)，官方 DSH 0.1.2-rc.1。安装 APK SHA-256 为 22ac0030d9e308be998cad4496c2b6f2f55b54b99e12a19b4f7f3b0d334df4c1，与此前记忆验证包一致。未更改生产配置、权限或安装依赖。

## 执行与结果

可重复入口：

```sh
python3 scripts/verify-dsh-sandbox.py emulator-5560 docs/testing/artifacts/dsh-sandbox-2026-09-08/probe.json
```

同一应用 UID，经已安装 init-host / Alpine，加载官方 Cordis Context 与 LocalSandboxProvider，调用实际 confine 和后端探测，没有替换内部探测函数。连续两次执行均退出 1（验收失败），详见 probe.json 与 probe-repeat.json。

- 普通 bash 固定打印成功，退出 0。
- 官方 read-only 和 workspace-write 均抛出 SANDBOX_UNAVAILABLE，命令未执行。
- 官方 Landlock probe 为 unusable，原始启动器退出 125：landlock is not enforced by this kernel (ABI unsupported or disabled)。绕过 proot，在同一应用 UID 下直接执行原静态启动器，也返回同一错误。
- bwrap 未安装（ENOENT）。只读内核配置：CONFIG_USER_NS 与 CONFIG_SECURITY_LANDLOCK 均未启用；内核 5.15.119-android13-8-00034-gd34029c8258b-ab10871489。

本次证明当前环境仍没有官方默认可用后端。Landlock 失败不以 proot 为必要条件；不能归因于 ACP 生命周期或 bash 缺失。没有安装 bwrap 实测其启动，不能把缺失文件直接写成其启动测试失败。

## 验收边界及后续

本次为实际安装包环境的后端必要条件验收；它已失败，未继续模型/UI 回合、工作区内写入与工作区外拒绝、重启恢复的完整验收。脚本即使将来返回 0，也只表示后端启动就绪，不能替代完整隔离性测试。

保持原权限，没有单次提权，也没有切换 danger-full-access。完全访问可绕过工作区隔离，但不构成沙箱修复。若需要维持官方受限模式，需要提供支持其后端的内核运行环境；应用层改名、重试或伪造 probe 成功无法补齐内核能力。

当前 adb 与无线发现均无实体手机。历史手机探测不是本轮验收，待真机验证。此报告不宣称沙箱已修复。
