# 开屏图标动画

`splash-logo.svg` 是可编辑的动画源文件。轮廓来自现有的
`app/src/main/res/drawable-xxxhdpi/splash_logo.png`，保留轮廓和配色，居中放大 20%。
SVG 将原 1152 × 1152 画布四边各内收 96，得到 960 × 960 的 viewBox；
静态开屏的位图画布通过 `splash_logo_static_size` 从 288dp 同比放大至 345.6dp。
动画播放一次，共 900 ms：闭眼、睁眼，配合轻微转动和缩放回弹，最终回到原姿态。

Android 系统开屏使用 AnimatedVectorDrawable，不能直接播放 SVG。修改源文件后运行：

```sh
python3 scripts/generate_splash_animation.py
```

脚本仅使用 Python 标准库，生成浅色、深色两个 animated-vector 和共享的
animator 资源。深色版本映射为应用已有的 `#98AD90` / `#151617` 配色。
导出支持当前图标所需的填充路径、平移分组、旋转/缩放关键帧，以及
`0.4 0 0.2 1` 分段缓动；如需其他 SVG 特性，需要同时扩展导出器。
修改总时长时，也需同步 `values-v31/themes.xml` 和
`values-night-v31/themes.xml` 中的 `windowSplashScreenAnimationDuration`。

动画接入 Android 12+ 的系统开屏；Android 10/11 使用同比放大的静态开屏。
不增加启动延时，系统可以在应用就绪时提前结束开屏。

资源验证（不生成 APK）：

```sh
./gradlew --no-daemon :app:processDevelopStandardDebugResources -Ptarget=lib/main_standard.dart
```
