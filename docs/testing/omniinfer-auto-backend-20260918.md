# 本地推理按需组件与自动后端 — 2026-09-18

需求：本地模型可选，推理组件与模型均按需下载，用户无需选择 CPU / HTP。

现有 LocalModelActivity 按钮仍是 prepare/start 的唯一产品调用入口；浏览页面、普通聊天不会下载或启动引擎。沿用官方 OmniInfer 0.2.4，下载组件 SHA 固定；官方 sources.jar 的 loadModel 返回 true 前检查服务健康，false 提供 getLastError。未增加 Agent 生命周期或推理重试。

DownloadableInferenceRuntime 仅在启动时根据 SOC_MANUFACTURER/HARDWARE 判断 HTP 候选资格，真实 loadModel 成功才保存选择。HTP 返回 false 后 stop 清理再试 CPU，最多两次加载；非高通或之前已验证 CPU 的配置直接 CPU。偏好由引擎 SHA、模型 SHA、系统 fingerprint 隔离。引擎或系统变化重新选择。未知 SDK 异常直接报错，不盲目重试；原生进程崩溃不能由该回退机制恢复。日志记录选择后端，普通页面不要求选择硬件。

## 已执行

- LocalInferenceBackendTest：5 项通过，覆盖不支持 HTP、HTP 失败清理/CPU 成功与下次复用、HTP 成功、双失败不持久化、SDK 异常不重放。
- assembleDevelopStandardDebug：成功。APK SHA256 `30047f7c259de1ebdc15ac4595c80f09c5a52ab05ef98ceb8ee4a3839a2aee1b`。
- verify-omniinfer-payload.py：实际 APK 无引擎 DEX/native、无 GGUF/模型和内嵌 OmniInfer APK；组件 22 个 native 库与官方 AAR 一致。证据 fixtures/omniinfer-auto-backend-20260918/payload.json。
- adb devices -l 与 adb mdns services 均无可用设备。**待真机验证**：页面进入不下载、显式下载/启动、HTP 正常启动、不支持设备 CPU 路径、HTP 加载失败回退、双失败页面恢复、重启后偏好/组件复用。单测不等于这些真机操作通过。

## 执行入口

```sh
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests 'cn.com.omnimind.bot.localmodel.LocalInferenceBackendTest' :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
python3 scripts/verify-omniinfer-payload.py MAIN.apk RUNTIME.apk OFFICIAL.aar OUTPUT.json
# 真机连接、安装新包，通过现有原生页面启动后，沿用真实 API 验证：
python3 scripts/verify-omniinfer-phone.py SERIAL OUTPUT.json --in-app --model Qwen3.5-0.8B-Q4_0.gguf --tools
```
