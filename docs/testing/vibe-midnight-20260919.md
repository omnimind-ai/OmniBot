# Life XP 实际跨午夜验收 — 2026-09-19

模拟器emulator-5580，0.6.3(16) Debug。复用当前已发布生产app.js/toolkit/schema等创建隔离插件副本，原项目不写入。通过真实界面喝水打卡，禁止直接写数据库或注入模型回复。

执行入口：`python3 scripts/verify-life-xp-midnight-device.py emulator-5580 OUTPUT`。脚本保存自动时间/时区和原时钟，将模拟器设置23:59:15，第一天UI打卡后核对SQLite日期及按钮禁用；保持原WebView和进程打开，自然跨午夜后按钮恢复，第二天UI打卡，再核对两天记录与累计20XP。finally恢复按真实耗时校正的原时钟、时区和自动设置，停用隔离插件。

attempt1是隔离fixture技能名称不匹配被发布校验拒绝。attempt2页面已打开，但标题同名节点不唯一导致准备断言失败，未改时钟；改为唯一习惯名称做就绪观察。两个失败证据保留，不作为业务失败。

attempt3全部通过：同一App PID28925，2026-09-19和2026-09-20各1次喝水，累计20XP/此前10XP显示正确。result.json与before-midnight.png、after-midnight.png保存原始结果；自动时间1、自动时区1、Asia/Shanghai已独立核对恢复。测试插件停用。没有改写原4习惯4打卡75XP。

范围：生产代码真实WebView日期转换、按钮状态、真实写入与历史累计；不是最终Release包验收，也不能冒充模型自主修复成功。
