# OmniInfer HTP 真机重试（2026-09-18）

设备：PJE110 / b49f281b，Android 16，SM8550（骁龙 8 Gen 2）。测试由 Agent 操作真机完成。

APK：`/tmp/OmniBot-omniinfer-htp-debug.apk`，0.6.3，SHA256 `8c91e12435fa36d74ab8e260e7c1ee537d367555d405083c5efbd3240efcc4ee`。覆盖安装成功，未清除数据。此测试版本显式选择 `llama.cpp/htp`，不适用于此前 CPU 模拟器测试。官方 AAR 0.2.4、下载组件和 Qwen3.5 0.8B Q4_0 模型保持原版本，4 个 CPU 工作线程、16384 上下文；未更改 Provider 或 ACP 生命周期。

## 实测

- 官方 HTP0 会话建立，Hexagon v73；native 配置为 `backend_type=npu accelerator=htp device=HTP0`、`n_gpu_layers=99`。
- HTTP 短回复、流式输出、取消恢复、真实只读工具往返通过。首次短回复 0.528 秒；此前 CPU 同用例 0.576 秒。单次短请求不足以得出可靠加速比例。
- 正式 App 对话首次实际 terminal_execute 工具调用成功，但模型只打印了部分平方数，没有完成求和、写文件和读回，预期文件不存在。模型最终只回复路径，遗漏验收标记。任务未通过。
- 官方最终 end_turn 已持久化，记录整轮 85.217 秒、prefill 345.4 token/s、decode 18.8 token/s。CPU 之前约 231 秒仍无工具输出并人工取消，故不能计算完整任务加速比。
- CPU 采样包含 DSP queue / FastRPC 相关调用，也包含 CPU 算子；这是混合执行证据，不是逐算子的 NPU 时间统计。不能宣称全部计算卸载 NPU。
- 冷启动后保留了同一条正式对话结果；再次从原入口加载模型并运行 API 回归，结果见 cold-restart-inference.json。

## 长期回归入口

```sh
# 从 native 入口加载 HTP 前开始采集（避免 logcat 环形缓冲区覆盖初始化记录）。
adb -s b49f281b logcat -v brief GGML:I OmniInferJni:I '*:S' > /tmp/htp-backend.log
# 加载后执行，不与正式 Agent 推理并发。
python3 scripts/verify-omniinfer-phone.py b49f281b /tmp/htp-api.json --in-app --model Qwen3.5-0.8B-Q4_0.gguf --htp-log /tmp/htp-backend.log --tools
# 新建空 Agent 对话，选中 OmniInfer-Local / Qwen3.5 并关闭思考，再执行一次：
OOB_ALLOW_PHYSICAL_DEVICE=1 OOB_ANDROID_SQLITE=cache/oob-sqlite-wrapper node scripts/verify-agent-user-journey.mjs b49f281b scripts/fixtures/agent-user-journeys/omniinfer-in-app-qwen35-agent.en.json /tmp/htp-agent
```

本次 journey 的发送成功；模型已经结束但遗漏预期标记，因此人工结束 UI 等待，没有重发或伪造成功。task-oracle.json 为失败；formal-observed.json 保留该合成测试的真实工具输入输出、终态和速度。所有证据位于 `docs/testing/fixtures/omniinfer-htp-phone-20260918/`。HTP 验证只证明官方 RPC 会话及卸载配置与实际推理同时成立，尚无逐算子 NPU profile。
