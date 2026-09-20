# 人生经验值：从零创建与录屏验收

2026-09-17，emulator-5580，OpenOmniBot 0.6.3 (16)，GLM-4.6V。
安装 APK SHA256 见 artifacts/life-xp-demo-20260917/device.json。真机未操作，待真机验证。

## 真实结果

这是实际聊天、实际模型生成源文件和发布、实际 Android WebView 点击与 SQLite 读取，未手写替换模型生成的业务代码，未伪造工具返回或写库模拟打卡。

**一条指令一次成功：未通过。** 初始指令因 Provider 返回缺失工具名失败；发送一次继续指令后生成并发布，再经过两轮基于实际验收错误的修正。四次请求各自只有一次 admission，正式终态见 turns.json，均保留在录像中。

- 创建前 /workspace/life-xp-demo 和 local.project.life-xp-demo 均不存在。
- 第一版点击喝水：NOT NULL check_ins.day，0 XP，失败。
- 修正后实际三次打卡：10 → 40 → 60 XP，达到 2 级；同日重复点击没有增加记录或 XP。
- 历史初次验收失败：XP 为 0，缺少升级记录。模型修正后显示喝水 +10、运动 +30、读书 +20，日合计 60，累计 10/40/60，等级提升到 2。
- 新增 DemoWalk，15 XP，实际保存；进程 force-stop 后重新打开，60 XP/2 级、完成状态、三条历史和新增习惯均保留。
- 自动化还遇到一次 UIAutomator 空根节点和新增弹窗可见性失败；失败结果未覆盖，后续补录并完成保存与重启验证。

证据目录：artifacts/life-xp-demo-20260917/。acceptance-second/result.json 记录打卡通过及历史失败；final-acceptance 和 final-verified 保留历史通过但弹窗操作未完成；persistence-verified/result.json 记录新增与重启通过。history-final.png 为重启后数据加载完成画面。generated-first、generated-final 为模型实际生成文件。

## 视频

- life-xp-full.mp4：原速录制片段按时间顺序拼接，保留输入超时、首次失败、修正与验收。Android 录屏时长限制导致自动分段，段间有短暂切换；后续排查与补录之间也有间隔，非帧连续单文件。
- life-xp-preview.mp4：创建和修正阶段 16 倍速，最后验收片段原速。没有移除已录下的失败片段，无配音。

## 可执行入口

- 需求 fixture：scripts/fixtures/user-scenarios/life-xp-build.json。
- 聊天入口：node scripts/verify-agent-user-journey.mjs emulator-5580 scripts/fixtures/agent-user-journeys/life-xp-build.en.json OUTPUT。
- 录屏：python3 scripts/record-agent-demo.py SERIAL OUTPUT；创建 OUTPUT/stop-recording 文件结束。
- 全流程页面验收：python3 scripts/verify-life-xp-demo.py SERIAL OUTPUT（要求初始 XP 为零，不会清空现有数据）。
- 在已经存在的三条打卡上继续历史/新增/重启：追加 --resume；已打开新增弹窗时仅补验新增/重启：追加 --finish。

## 尚未解决与验证范围

本次只覆盖单日 Demo 流程，不应宣称产品级全部验收通过。源代码仍以 UTC toISOString 取日，跨时区/午夜行为未正确实现；多日升级里程碑归属逻辑需修正；查询默认 100 条，长期累计可能截断。未测试跨日、超过 100 条、快速重复新增、真机性能。模型生成质量与单指令成功率仍不达标。
