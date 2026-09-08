# 打开对话列表掉帧：按需构建修复

用户反馈：打开对话列表掉帧。本轮定位的是首页抽屉列表，不把结论扩大到所有页面或所有掉帧来源。

复现：既有 `HomeDrawer` widget 测试注入 300 条同一天的合成会话。修复前首屏实际挂载 300 个 `ConversationSlidable`，`lessThan(30)` 断言失败。外层 ListView 的子项是整组 Column，组内屏幕外行全部构建和布局；折叠高度动画也保留整个子树。

修复沿现有 HomeDrawer 分组和导航 owner：统一 CustomScrollView，分组使用 SliverMainAxisGroup，普通、置顶、定时子会话通过 SliverList.builder 按可见范围构建；搜索同样使用 builder。折叠时移除该分组的正文 sliver，保留标题、计数、缩进及展开状态持久化。原整组正文高度/透明度动画被移除，避免为折叠动画布局全部历史。会话行增加 threadKey 对应 Widget key。未改查询协议、Agent 生命周期或添加缓存。

## 可执行回归

```sh
cd ui
flutter test --no-pub test/features/home/widgets/home_drawer_test.dart test/features/home/widgets/home_drawer_plugin_market_test.dart
flutter test --no-pub
```

新增 `large conversation list builds only viewport rows`：300 条首屏挂载少于 30 行、末行未提前挂载；滚动到第 300 条后可见且挂载仍少于 30 行。既有定时分组滚动测试改为操作 CustomScrollView，保留末行可达断言。20 项定向测试通过，全量 1187 项通过。静态分析无错误，保留修复前已存在的 `_conversationRelativeTimeLabel` 未使用警告。

## 设备证据与边界

0.6.2.2（14）debug APK 构建、`adb install -r` 通过，未清数据。模拟器 emulator-45562：打开抽屉、关闭重开、force-stop/重新启动后打开，均通过 UIAutomator 确认 Archive/New conversation 入口存在。该设备只含少量历史，未测量大历史帧时间；不能将 debug 包功能检查当作帧率改善证据。结构化结果及 APK hash 见 [verification.json](artifacts/drawer-lazy-20260908/verification.json)。

**待真机验证**：用户设备同等历史量下反复打开、滑动、折叠、重启的 profile/release 帧时间尚未测量。图片预加载、运行状态通知和数据库列表加载可能是其他性能来源，本次没有将其认定为根因，也未盲目一起修改。
