# OmniFlow 包重构与自动化验收（2026-09-16）

## 实现与交付范围

用户要求：一个可插拔 OmniFlow 包加薄注册层，保留手动录制、注册、GUI 执行与重放，沿用现有界面。本轮用户明确选择模拟器验收，不把模拟器结果写成真机通过。

- 包公开 host.json：接口版本、prepare/start 入口、源码根和工具声明。schema 从 canonical Bridge 导出，App 不再维护重复的管理工具 schema 和交互分类表。
- 包负责 Python/NumPy/Pillow 准备、PYTHONPATH、Transfer checkpoint、Function 数据路径、RunLog 编译和完成后注册。删除 App 内闲置的第二个 Function 编译器。
- App 保留 observe/act、权限、录制、暂停/停止/接管、模型访问、历史提交与原 UI。沿用插件安装/更新/启停和 ACP owner，没有新增 Agent loop、reducer 或业务状态机。
- RuntimeProvider 只检查公开入口；源码编辑相对于 sourceRoot。真实 OmniTransfer 来自 ~/Projects/Omni/OmniTransfer，映射失败交回正常 VLM，未增加 node-id lookup 或源坐标兜底。
- Provider 88 行、工具声明投影 15 行、RuntimeProvider 75 行，共 178 行。这不包括 Android 能力、通信与控制生命周期，不能称 App 总共只剩 178 行。八个主要边界文件相对工作树基线净减少约 596 行。

## 验收中发现并修改的问题

1. 空远程摘要错误命中旧包缓存：仅以非空内容摘要判断匹配。
2. Transfer 用 checkpoint 文件名推断身份：改为真实内容 SHA-256。
3. 通用 open_app 引用 AndroidWorld 实验 src：canonical 包移除该依赖，保留真实宿主包名。
4. Gson 未采用 Kotlin 默认值，省略 agentVisible 会隐藏工具：解析边界补齐契约默认值。
5. Planner 丢掉不可点击文字节点的 bounds：保留实际观察到的标签位置，不修改 clickability、不猜目标。
6. 录制控件初次 addView 尚未附着，触摸层盖住 Pause：附着后由原 owner 调整层级；回归要求 Pause/Resume 不进入任务轨迹。
7. JSON 作者请求强制虚拟 submit_json 工具，在当前 GLM 路由返回空结果：改为标准 JSON response_format，canonical compiler 继续验证内容，兼容旧 submit_json 返回。[Z.AI JSON 模式](https://docs.z.ai/guides/capabilities/struct-output)
8. 手动录制把截图当成可选 debug 输出：现在采集前后截图，缺证据明确标记 incomplete。平台仅针对截图间隔错误延迟后补采一次，不重复动作、不复用旧图；截图读取后采集 XML，避免保留等待前的树。[Android 截图错误契约](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)

## 可执行回归

### 模拟器集成测试

入口：scripts/test_omniflow_device_lifecycle.py。使用既有 debug 入口进入实际 owner，通过实时 XML 定位真实 UI 控件；模型调用、GUI dispatch、Store、RunLog、canonical Transfer 均为真实实现。驱动点击只用于测试设置与录制/停止控制，不向 Transfer 提供目标。

~~~sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb" \
OOB_GUI_SERIAL=emulator-5580 \
OOB_GUI_OUTPUT=docs/testing/artifacts/gui-plugin-package-20260916/automated \
python3 -m unittest discover -s scripts -p test_omniflow_device_lifecycle.py -v
~~~

六个用例：

- 非法写入不改变 Store。
- 真实录制 Pause/Resume/Finish、前后图像证据与语义注册。
- 目标执行与自动注册。
- 接管/继续/停止，重启后不重跑且历史不变。
- 插件启停/更新/卸载重装保留 Functions。
- 模型自行选择 Function 并重放，必须 recall_hit=true 且实际出现 Battery usage；普通 GUI fallback 成功不能冒充重放。

设备：emulator-5580，Android 13 ARM64、1080×2400、Ubuntu 24.04，App 0.6.3 developStandardDebug。install -r 保留数据；插件操作走现有 OmniPluginHost。脚本只接受隔离模拟器；未选择设备时的 SKIP 不计为通过。作者调用测试上限为 600 秒，超时会失败并通过实际停止控件释放任务，不给产品增加重试或超时规则。

**最终模拟器轮次：6/6 通过，198.540 秒。** 普通目标执行实际点击 1 次，auto_registered=true；重放由模型选择已保存的 Function，recall_hit=true，并核对实际 Battery usage 页面。录制前后截图均存在且 evidence_complete=true。旧失败证据保留在各 automated-before-* 目录，不覆盖成成功。此结果是本次测试场景的通过，不是任意模型/Function 组合的稳定性保证。

### 其余回归

| 范围 | 已运行结果 |
|---|---|
| Flutter 全套 | 1292 通过 |
| App JVM 全套最终重跑 | 1099 通过 |
| omniflow-android | 64 通过 |
| androidgui | 11 通过 |
| assists | 截图证据修改后 34 通过 |
| baselib / uikit | 116 / 2 通过 |
| canonical OmniFlow tests/ | 276 通过 |
| canonical Transfer 身份及人工对齐 | 3 通过 |
| 实际 ZIP / APK 包契约 | 9 通过 |
| offline Agent 协议 | Node 93、Python 31+8+5+9+2+16、WebChat 12 通过；typecheck/build 通过 |

~~~sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest :omniflow-android:testDebugUnitTest :androidgui:testDebugUnitTest :assists:testDebugUnitTest :baselib:testDebugUnitTest :uikit:testDebugUnitTest
cd ui && flutter test
# 回到仓库根目录
bash scripts/test-agent-runtime.sh --offline --skip-gradle --skip-flutter
OMNIFLOW_APK_TEST_PATH=app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk python3 plugins/omni-vlm-lite/tests/test_runtime_bundle.py
# canonical OmniFlow-exp 中
./.venv/bin/pytest -q tests
~~~

包测试覆盖实际 ZIP 解压、私有目录迁移后公开入口启动 MCP、真实 Transfer ready、工具声明、无实验 src 的 open_app、APK 内容摘要。apt/apk 分支使用命令替身，不代表 Alpine 实机安装通过。Ubuntu prepare 已在模拟器实际安装 Pillow，后续幂等启动。

完整日志、RunLog、注册结果与插件状态见 artifacts/gui-plugin-package-20260916/。修正两处过时测试夹具：Flutter 流式状态使用真实任务 reservation；远程历史契约验证按需加载而非全量循环。生产行为没有为测试恢复旧方案。

原生六模块最终完整重跑共 1326 项，无失败/错误（native-counts.json、native-final.txt）。截图修复在模拟器触发过真实 errorCode=3，随后补采成功，前后图片证据和实际注册通过。测试驱动也修正了 UI 等待：异步附着后才定位控件，最多点击一次，不重试未知结果的动作。

### 保留的失败与限制

automated-before-fixture-cleanup 中，模型在打开 Battery 后又调用旧测试生成的 complete_task，进入 Battery Saver 后误报完成。独立 UI 验收判定失败，没有按模型 success 字段记为通过。两条早期测试生成的能力 start_recording / complete_task 已先完整导出到 fixture-cleanup，再通过公开 delete_function 删除；新录制生成的 open_battery_page 保留。此清理是测试环境准备，**不是模型误判已修复的证据**。旧失败样本与运行入口保留，不能把清理后通过等同于任意 Function 组合均可靠。

另外有一次自动作者提案被 canonical compiler 拒绝，正确返回 FUNCTION_AUTHORING_REJECTED 而没有保存伪造能力；先前同一流程也曾成功。拒绝样本见 automated-before-rate-fix/goal-result.json。语义生成仍受模型输出质量影响，本轮未绕过校验或添加无限重试。

## 验收边界与构建

- 当前交付是本地 debug APK 和 artifacts/omniflow-gui-runtime-2.2.0.zip，未发布公共 Release。安装包与源码摘要记录于证据目录 build.json。
- 模拟器集成覆盖真实后端和控制 UI；尚不等于每个普通聊天入口/RunLog 页面按钮都完成 UI 自动化。接管后人为换页面的恢复也不在六例内，不记为通过。
- 没有重跑所有远程 Harness 的线上验收；offline 通过不代表所有提供商兼容。JSON authoring 在当前模型上可能耗时数分钟，不能保证低延迟。
- 按用户选择完成模拟器范围；物理手机仍为“待真机验证”，本轮不要求连接手机。
- Transfer 工作树包含共享修改，正式发布前还须固定提交并重新验包；dirty 构建不是已固定上游的正式发行。

~~~sh
python3 scripts/build-omniflow-component.py --omniflow-working-tree --omnitransfer-working-tree --omnitransfer-checkpoint ../OmniTransfer/output/runtime-v10-numpy.npz --output /tmp/omniflow-development.zip
~~~

OmniFlow 工作分支已推送至 ef6be7d2；标签几何修复为 8f0005f2。中间思考参数尝试已撤销，没有按模型名特判或第二套作者协议。
