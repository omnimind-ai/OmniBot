# RunLog 截图坐标单位 — 2026-09-19

在原参数化 Settings wifi 回放日志 tool-e6586656-6f3b-42d2-ad01-4171e263a198 上核对了原始 action 和 state。tool/args 为 x=348.148148、y=274.583333，display=1080×2400，因此实际点为(376,659)。原始日志、state及截图保留于 artifacts/runlog-coordinate-units-20260919/。

必须区分两个格式：

- 内部 canonical tool/args 坐标为0..1000，参考 baselib/.../ActionCoordinateCodec.kt。
- InternalRunLogStore.externalActionPayload 对官方 action_type 格式转换为屏幕像素。get_run_log_state 返回 State.asHostMap 的 display，不能对官方格式再次按1000解码。

原页面把两种坐标都当截图原始像素，且旧测试给两种格式相同的(600,1200)，没有验证单位差异。首先纠正旧格式的中心(500,500)复现失败；初次复现测试曾误认为官方格式也是相对坐标，后经真实接口链核对纠正，不把这个错误断言当官方格式缺陷证据。

当前显示层只在现有 StateSheet 内按格式解码为屏幕比例，基于实际显示图片尺寸定位：官方格式依赖有效 display，旧格式按1000；无效/越界位置不画。图像和位置提供正常无障碍描述，以便真实设备独立核对可见边界。没有改动作执行、传输或日志文件。

8项widget检查通过：两种格式、中心和真实非中心点、越界隐藏、截图分辨率与屏幕分辨率不同，以及既有详情展示。定向分析无error/warning，测试括号info已修正。新Release正在构建；尚未实际设备验证截图坐标，不宣称验收完成。


## 候选和实际模拟器结果

ProductionStandardRelease 测试签名构建2m4s，apksigner通过；APK `app/build/outputs/release-candidate-20260919-runlog-units/OmniBot-0.6.3-release-test-signed.apk`，SHA256 `1742ecd745e0d3b3ec73639ccc37d2c0fa6a9d3a80a36b0d81331ebc28092b1c`。保留数据安装 emulator-5580，设备base.apk哈希一致。

执行入口：

```sh
python3 scripts/verify-run-log-reopen.py emulator-5580 tool-e6586656-6f3b-42d2-ad01-4171e263a198 3 OUTPUT --expect-action-labels --expect-action-point
```

首次设备断言因Flutter将Icon无障碍边界扩大到整个定位层而失败（device/result.json保留）。人工查看实际截图已看到正确标记；不能用扩大后的语义矩形判定图标中心。改为读取真实截图中绘制的redAccent像素：原始Settings截图无该颜色，检查独立目标色区域大小并取得实际中心；并没有编辑或生成验收截图。

再次完整执行强停→重开原日志→展开动作→打开截图，device-pixels/result.json通过：期望(388.756,1094.744)，真实绘制中心(388.5,1094.5)，误差均小于1像素；3步及动作标题保留，原日志SHA256 `951140d4fa8ccdc3ed5304be5044bbc6fb526f19b9440210d702cf8dd77e5e00`未变。页面走实际官方像素格式；内部旧格式的直接前端输入仅widget覆盖，不冒充额外设备验收。物理真机待验证。

最终定向 dart analyze：No issues found，原始analyze.log保存。
