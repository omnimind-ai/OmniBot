# 应用图标与开屏动画

在「设置 → 外观设置 → 应用图标」中可以选择以下六款图标。灵光星芒是首次安装和旧版本升级后的默认款。

| 图标 | 资源标识 | 设计方案 |
| --- | --- | --- |
| 灵光星芒 | `sparkle` | C · 默认 |
| 在线圆点 | `dot` | A |
| 终端光标 | `cursor` | B |
| 倾听声波 | `wave` | D |
| 纯净断口 | `open` | E |
| 经典侧耳 | `classic` | 原版 |

图标会同步到 Android 桌面入口、最近任务、开屏和 Flutter 应用内标识。Android 12 及以上使用系统开屏动画，Android 10/11 使用同款静态图标。两套开屏资源均有浅色和深色版本。

`app-icons/` 保存六款 SVG 设计稿。`splash-logo.svg` 是默认款的开屏源文件，其他款直接使用各自的 SVG。修改后运行以下命令重新生成浅色和深色的静态图、动画及开屏背景。

```sh
python3 scripts/generate_splash_animation.py
```

动画共 900 ms，六款共用转动、缩放回弹和眨眼关键帧。生成脚本支持填充路径、描边、平移分组和 `0.4 0 0.2 1` 分段缓动。调整时长时，同步更新 SVG 和 `values-v31/themes.xml` 中的 `windowSplashScreenAnimationDuration`。

桌面自适应图标前景位于 `app/src/main/res/drawable-nodpi/launcher_logo*.png`，Flutter 预览和应用内图标位于 `ui/assets/common/app_icon_*.png`。更新图形时同步导出这两处的对应 PNG。

```sh
./gradlew --no-daemon :app:processDevelopStandardDebugResources -Ptarget=lib/main_standard.dart
```
