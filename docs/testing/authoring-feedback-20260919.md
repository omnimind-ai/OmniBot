# Authoring 拒绝反馈与持久化 — 2026-09-19

本轮目标来自“把这些错误都补齐”的整体任务，本轮只处理一个已复现缺口，不代表整体完成。

## 修改

- canonical OmniFlow-exp/omniflow/bridge.py 原样透出 compiler 的 rejected_error；保留 authoring_workflow 结构，不替模型补 binding，也不新增重试/Planner。
- 拒绝报告原子保存到既有 Store 相邻 authoring_reports，文件名为 run/request 身份哈希，避免来源 ID 变为路径；返回 diagnostic_path/diagnostic_persisted。写盘失败仍返回真实编译错误与结构化诊断。
- Android OmniFlowManagementToolHandler 失败也投影为 success=false 的 ContextResult，保留完整 rawResultJson；避免把诊断路径/详情丢掉。仍由同一工具历史 owner 持久化。
- 打包 OmniFlow 2.2.2；相对 2.2.1 仅 bridge.py、组件/host 元数据与来源记录改变。保留原发布包 canonical OmniTransfer checkpoint 字节；没有更新 Transfer 代码或权重。

## 已执行

- canonical `.venv/bin/python -m pytest tests/test_device_runlog_compiler.py -q`：18 通过。新增拒绝原因、临时目录删除后报告仍可读取、Store 重开无伪注册、诊断写盘失败不吞原错误。首次测试缺 Path 导入失败，已修正重跑。
- `:app:testDevelopStandardDebugUnitTest --tests '*OmniFlowManagementResultTest'`：通过；错误详情保留且不标成功。
- 组件打包/最终 APK 的结果以下文与日志记录为准。
- 无 ADB 真机连接，本轮未安装。**待真机验证**：从执行中心触发被拒绝 authoring，查看工具失败详情，重启后读取报告与失败历史，再成功执行既有 Function。不能声称 authoring 稳定性已解决。

## 整体仍未完成

1. 文本/图片实际上传真机全链路；PDF/DOCX/XLSX 当前仍只有解析提示，没有完整正文解析接入。
2. Codex 固定加密公网入口、配对/恢复、切网/休眠/重启，原生桌面协同验收。
3. authoring 明确参数意图、生成稳定性，以及不同参数的真实回放验收。
4. Vibe 生成模型自己执行测试/返工的完整真机闭环，跨日与历史边界。
5. 聊天与工具取消/失败/长任务等既有模拟器回归升级到同一最终 Release 真机验收。
6. 最终版本号与 Release 汇总验收；本地模型按用户要求停止扩展。

没有把通知历史/自动订阅等此前明确未实现的扩展当成本轮已修复功能，也没有把当前通知活跃集合读取误称完整历史。

## 最终本地构建结果

- Debug APK 构建成功；SHA256 `e299afb6eeaee0c77262047d927ba61678568a9685c60b4e1d0f587ff1c17acc`。
- `OMNIFLOW_APK_TEST_PATH=app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk python3 -m unittest discover -s plugins/omni-vlm-lite/tests -p test_runtime_bundle.py`：9 项通过，0 跳过，实际 APK 内组件与清单一致。
- Android management 结果测试 2 项通过。两个仓库 scoped/diff whitespace 检查通过。
- adb devices 与 mdns 无设备，未安装、未声明真机验收完成。

## 后续：带回上一份方案（2.2.4）

实际模型在2.2.3拒绝后仍无法修复 search_term 参数绑定。检查发现既有三次编译尝试仅返回错误文本，下一次无状态请求没有上一份方案。现由同一 compiler 的 harness_feedback 返回 previous_response，拒绝 trace 保存 raw_response；bridge 将完整 authoring_attempts 纳入既有原子诊断持久化。不新增重试 owner，不替模型填写 binding。

- canonical tests/test_device_runlog_compiler.py：18通过，覆盖上一份方案反馈和三次拒绝报告保留。
- 2.2.4 相比2.2.3仅 compiler.py、bridge.py和组件元数据变更，Transfer源码/权重未改变。ZIP SHA256 bdd5b0e10f198ac7d1539ce69daee434fb03af2e95fa51020f4b0a40effed85a。
- Debug构建成功，最终APK内组件9项检查通过。APK SHA256 bffab4b8e4583c3e2f81574634ef879e370590bdd9d4eba84ad84f2f4f089047。
- 已覆盖安装 emulator-5580 Android13 ARM64，正在进行真实录制/增强；尚不能宣称模型绑定已修复。

### 2.2.4 真实模型结果：拒绝

新源 human_1789756679279_04b7d11c 两步录制通过，实际增强调用三次模型后返回 function_author_semantic_step_invalid；未注册新Function。正式 end_turn，未重放请求。设备持久报告三次 raw_response 与工具返回逐项一致，证据在 artifacts/authoring-feedback-20260919/attempt1/。模型输出了示例中的八步、重复操作和不存在的确认步骤，实际源只有两步。

后续2.2.5修改：用精确字段合同替换长八步示例，保留四阶段 authoring 语义；未知/重复source_step_index的编译错误给出具体索引及允许集合。原有host-model回归扩展为未知索引、重复索引、空响应三种真实拒绝反馈，20项通过。构建与实际模型验收进行中，不能把模拟host修复测试当真实模型成功。

### 2.2.5 真实模型结果：拒绝

APK SHA256 1117bfa1ee712f6d47586786a2377b9c8b0dacf3de2de4531d1fb1e61048ed5f，实际APK组件9项通过并覆盖安装。复用前一轮真实录制，不注入新Function。模型正确生成两步和 action_arg text绑定，但三次都把方案包在 Root 字段里，旧dispatcher返回误导性的legacy plan contract错误。证据在 attempt2/tool-result.json。首次verify过早查询尚未开始注册的Store，保留失败；驱动现等待同一真实聊天的工具结果和正式终态再查询。正式终态后的verify仍失败，无新Function。

后续2.2.6：顶层给出无具体步骤的JSON骨架，说明不能加wrapper；dispatcher只把真正的reason/plan交给legacy转换，其余返回准确expected/received字段。回归增加实际Root误包装→host反馈修正案例，21项通过。

### 2.2.6 真实模型结果：拒绝；反馈返工首次明确生效

APK SHA256 484421e3d0abfa85aa2b59adf72451dba9b0cf3d7284e37445706bde3cc0f067，实际APK组件9项通过并覆盖安装。attempt3/author-terminal.json保留完整同会话结果。第一次含多余value字段；第二次模型根据反馈正确删除，但局部函数和complete_function都叫search_in_settings，触发笼统ID错误；第三次未理解重复原因。未注册新Function，未进行回放冒充通过。

2.2.7补充明确跨局部/完整流程唯一ID合同，校验返回重复ID与唯一范围；不自动改名、不自动补binding。canonical设备编译+render binding两组58项通过。render prompt测试原本把JSON空格当合同，改为解析真实顶层骨架和字段集合后通过。最终模拟器复验仍进行中。

### 2.2.7 真实模型结果：拒绝

APK SHA256 694204894e48e381a620e97cc5fb102c379e112bbf9f48ffe00484f668b657cc。模型不再出现伪造八步、Root包装或重复ID，但把业务参数名search_term误作源动作参数名，三次返回function_author_plan_parameter_target_invalid。完整证据 attempt4/author-terminal.json，仍未生成Function。

2.2.8：既有默认值校验现在反馈source_step_index、错误arg_name、源动作实际args键；prompt明确业务参数名与arg_name不同。新增真实input_text动作的host反馈回归，确认最终绑定到$.steps[0].action.args.text。测试初版缺失输入定位点，补充源点后又发现实际args包括text/x/y，修正断言后两组59项通过；没有改动动作投影或补binding。模拟器待复验。

### 2.2.8 最终本轮设备结果

APK SHA256 fc1651d8e5ba3eed3e6c15eb36203909c0e567d961d4613987917344c9d5ba17；最终APK组件9项通过，覆盖安装emulator-5580 / Android13 ARM64 / 0.6.3(16) Debug。实际模型通过增强，生成2步隐藏完整证据navigate_and_search_settings和1步可执行search_settings（search_term绑定输入text）。attempt5/author-terminal.json、authored.json为实际产物，restart.json证明强停重启后两份Function原样保留。不是从零一次成功或完整流程通过。

从设置首页直接回放失败；随后单独从搜索页入口测试局部输入，也失败。首次重启后无障碍未就绪，记录local-replay失败；重新设置既有无障碍后执行local-replay-restored。其failed-replay-run.json明确error=omnitransfer_target_page_identity_mismatch：source节点为Pause manual recording，source display1080x2601；target为Execution Center的Refresh等节点，display1080x2400。参数已实际替换为wifi，但真实Transfer拒绝，未绕过或重放源坐标。

下一步需要修复录制取景包含控制浮层及执行中心启动回放仍留在本App的问题。当前只有增强注册和重启持久化通过，完整改参回放、从零重复稳定性、Vibe/remote/最终Release仍未完成。驱动已保留失败RunLog，后续复验沿用现有入口，不更改成功标准。
