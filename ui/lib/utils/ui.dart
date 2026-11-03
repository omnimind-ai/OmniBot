import 'package:flutter/material.dart';

import 'dart:async';

import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/omni_glass.dart';

/// 把 #RRGGBB / #AARRGGBB 转换成 [Color]
Color hexToColor(String hex) {
  hex = hex.replaceFirst('#', '');
  if (hex.length == 6) {
    hex = 'FF$hex'; // 默认加上不透明度
  }
  return Color(int.parse(hex, radix: 16));
}

class Loading {
  static OverlayEntry? _overlayEntry;
  static int _activeCount = 0;

  static void show([String? message]) {
    _activeCount++;
    if (_overlayEntry != null) return;

    final navigatorState = GoRouterManager.rootNavigatorKey.currentState;
    if (navigatorState == null) {
      debugPrint(
        'Warning: Navigator state is null, cannot show loading overlay',
      );
      return;
    }

    final context = navigatorState.overlay!.context;
    _overlayEntry = OverlayEntry(
      builder: (_) => Center(
        child: _CustomLoadingWidget(
          message: LegacyTextLocalizer.localize(message ?? '加载中'),
        ),
      ),
    );
    Navigator.of(context).overlay!.insert(_overlayEntry!);
  }

  static void hide() {
    _activeCount--;
    if (_activeCount <= 0) {
      _overlayEntry?.remove();
      _overlayEntry = null;
      _activeCount = 0;
    }
  }

  static Future<T> wrap<T>(Future<T> future, [String? message]) async {
    show(message);
    try {
      final result = await future;
      hide();
      return result;
    } catch (e) {
      hide();
      rethrow;
    }
  }
}

/// 全局 Toast 工具（Overlay 实现）
///
/// 设计还原要点（参考 Figma Toast 组件）：
/// - 背景白色 + 圆角 12
/// - 内边距：L16 R24 T12 B12，左右图标间距 8
/// - 阴影：黑色 15% 不透明度，y 偏移 3，模糊半径 20
/// - 文案字号 16、居中，颜色接近 #1A1C1E
/// - 支持不同类型：info/success/warning/error，对应不同图标与主色
class AppToast {
  static OverlayEntry? _entry;
  static Timer? _timer;
  static int _nextToken = 0;
  static int? _activeToken;

  /// 展示一个 Toast。
  ///
  /// [message] 文案内容
  /// [type] 样式类型（影响图标与主色）
  /// [duration] 展示时长
  /// [position] 位置（顶部/中部/底部）
  static AppToastHandle show(
    String message, {
    ToastType type = ToastType.info,
    Duration duration = const Duration(seconds: 2),
    ToastPosition position = ToastPosition.top,
  }) => _show(message, type: type, duration: duration, position: position);

  static AppToastHandle loading(
    String message, {
    ToastPosition position = ToastPosition.top,
  }) => _show(message, type: ToastType.loading, position: position);

  static AppToastHandle _show(
    String message, {
    required ToastType type,
    Duration? duration,
    required ToastPosition position,
  }) {
    // 若已有展示中的 toast，先移除
    _dismissActive();
    final token = ++_nextToken;

    final nav = GoRouterManager.rootNavigatorKey.currentState;
    if (nav == null || nav.overlay == null) {
      debugPrint('AppToast: navigator is null, skip show');
      return AppToastHandle._(token);
    }

    final alignment = _alignmentFor(position);
    final EdgeInsets margin = _marginFor(position);

    final localizedMessage = LegacyTextLocalizer.localize(message);

    _activeToken = token;
    _entry = OverlayEntry(
      builder: (context) => SafeArea(
        child: IgnorePointer(
          child: _ToastContainer(
            message: localizedMessage,
            type: type,
            alignment: alignment,
            margin: margin,
          ),
        ),
      ),
    );

    nav.overlay!.insert(_entry!);

    if (duration != null) {
      _timer = Timer(duration, () => _dismiss(token));
    }
    return AppToastHandle._(token);
  }

  static void _dismiss(int token) {
    if (_activeToken != token) return;
    _dismissActive();
  }

  static void _dismissActive() {
    _timer?.cancel();
    _timer = null;
    _entry?.remove();
    _entry = null;
    _activeToken = null;
  }

  /// 便捷方法
  static AppToastHandle success(
    String message, {
    Duration duration = const Duration(seconds: 2),
    ToastPosition position = ToastPosition.top,
  }) => show(
    message,
    type: ToastType.success,
    duration: duration,
    position: position,
  );
  static AppToastHandle error(
    String message, {
    Duration duration = const Duration(seconds: 2),
    ToastPosition position = ToastPosition.top,
  }) => show(
    message,
    type: ToastType.error,
    duration: duration,
    position: position,
  );
  static AppToastHandle warning(
    String message, {
    Duration duration = const Duration(seconds: 2),
    ToastPosition position = ToastPosition.top,
  }) => show(
    message,
    type: ToastType.warning,
    duration: duration,
    position: position,
  );
  static AppToastHandle info(
    String message, {
    Duration duration = const Duration(seconds: 2),
    ToastPosition position = ToastPosition.top,
  }) => show(
    message,
    type: ToastType.info,
    duration: duration,
    position: position,
  );

  static Alignment _alignmentFor(ToastPosition position) {
    switch (position) {
      case ToastPosition.top:
        return Alignment.topCenter;
      case ToastPosition.center:
        return Alignment.center;
      case ToastPosition.bottom:
        return Alignment.bottomCenter;
    }
  }

  static EdgeInsets _marginFor(ToastPosition position) {
    switch (position) {
      case ToastPosition.top:
        return const EdgeInsets.only(top: 40);
      case ToastPosition.center:
        return EdgeInsets.zero;
      case ToastPosition.bottom:
        return const EdgeInsets.only(bottom: 88);
    }
  }
}

class AppToastHandle {
  AppToastHandle._(this._token);

  final int _token;
  bool _dismissed = false;

  void dismiss() {
    if (_dismissed) return;
    _dismissed = true;
    AppToast._dismiss(_token);
  }
}

enum ToastType { info, success, warning, error, loading }

enum ToastPosition { top, center, bottom }

enum DialogType { confirm, alert, input, select, loading }

class _ToastContainer extends StatefulWidget {
  const _ToastContainer({
    required this.message,
    required this.type,
    required this.alignment,
    required this.margin,
  });

  final String message;
  final ToastType type;
  final Alignment alignment;
  final EdgeInsets margin;

  @override
  State<_ToastContainer> createState() => _ToastContainerState();
}

class _ToastContainerState extends State<_ToastContainer>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  late final Animation<double> _opacity;
  late final Animation<Offset> _offset;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 180),
    );
    _opacity = CurvedAnimation(parent: _controller, curve: Curves.easeOut);
    // 由轻微位移过渡到 0，实现“浮现”效果
    final begin = widget.alignment == Alignment.bottomCenter
        ? const Offset(0, 0.06)
        : const Offset(0, -0.06);
    _offset = Tween<Offset>(
      begin: begin,
      end: Offset.zero,
    ).animate(CurvedAnimation(parent: _controller, curve: Curves.easeOut));

    // 延迟一帧启动动画，避免 Overlay 初始闪烁
    WidgetsBinding.instance.addPostFrameCallback((_) => _controller.forward());
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final colors = _ToastColors.of(widget.type, palette);
    final surfaceColors = _ToastSurfaceColors.resolve(
      palette: palette,
      isDark: isDark,
    );
    final message = widget.message.trim();
    final isMultiLine = message.contains('\n') || message.length > 26;
    final maxWidth = (MediaQuery.sizeOf(context).width - 32)
        .clamp(220.0, 360.0)
        .toDouble();
    final fontSize = _fontSizeFor(message);
    final lineHeight = isMultiLine ? 1.3 : 1.0;

    return Align(
      alignment: widget.alignment,
      child: Container(
        margin: widget.margin,
        child: SlideTransition(
          position: _offset,
          child: FadeTransition(
            opacity: _opacity,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                ConstrainedBox(
                  constraints: BoxConstraints(
                    maxWidth: maxWidth,
                    minHeight: 36,
                  ),
                  child: Container(
                    padding: EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: isMultiLine ? 10 : 8,
                    ),
                    decoration: ShapeDecoration(
                      color: surfaceColors.background,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(
                          isMultiLine ? 18 : 50,
                        ),
                        side: BorderSide(color: surfaceColors.borderColor),
                      ),
                      shadows: surfaceColors.shadows,
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: isMultiLine
                          ? CrossAxisAlignment.start
                          : CrossAxisAlignment.center,
                      children: [
                        Padding(
                          padding: EdgeInsets.only(top: isMultiLine ? 1 : 0),
                          child: widget.type == ToastType.loading
                              ? SizedBox(
                                  width: 14,
                                  height: 14,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                    valueColor: AlwaysStoppedAnimation<Color>(
                                      colors.color,
                                    ),
                                  ),
                                )
                              : Icon(
                                  colors.icon,
                                  color: colors.color,
                                  size: 14,
                                ),
                        ),
                        const SizedBox(width: 10),
                        Flexible(
                          child: Text(
                            message,
                            maxLines: 4,
                            overflow: TextOverflow.ellipsis,
                            softWrap: true,
                            textAlign: TextAlign.left,
                            style: TextStyle(
                              color: surfaceColors.textColor,
                              fontSize: fontSize,
                              fontFamily: 'PingFang SC',
                              fontWeight: FontWeight.w400,
                              height: lineHeight,
                              letterSpacing: fontSize <= 12 ? 0.2 : 0.4,
                              decoration: TextDecoration.none,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  double _fontSizeFor(String message) {
    if (message.length > 120) {
      return 12;
    }
    if (message.length > 60) {
      return 13;
    }
    return 14;
  }
}

class _ToastSurfaceColors {
  final Color background;
  final Color borderColor;
  final Color textColor;
  final List<BoxShadow> shadows;

  const _ToastSurfaceColors({
    required this.background,
    required this.borderColor,
    required this.textColor,
    required this.shadows,
  });

  static _ToastSurfaceColors resolve({
    required OmniThemePalette palette,
    required bool isDark,
  }) {
    return _ToastSurfaceColors(
      background: palette.surfacePrimary,
      borderColor: palette.borderSubtle,
      textColor: palette.textPrimary,
      shadows: [
        BoxShadow(
          color: palette.shadowColor.withValues(alpha: isDark ? 0.22 : 0.08),
          blurRadius: 20,
          offset: const Offset(0, 6),
        ),
      ],
    );
  }
}

class _ToastColors {
  final Color color;
  final IconData icon;
  const _ToastColors(this.color, this.icon);

  static _ToastColors of(ToastType type, OmniThemePalette palette) {
    switch (type) {
      case ToastType.success:
        return _ToastColors(
          const Color(0xFF22C55E),
          Icons.check_circle_rounded,
        );
      case ToastType.warning:
        return _ToastColors(const Color(0xFFFFAA2C), Icons.warning_rounded);
      case ToastType.error:
        // 参考设计中的警示红
        return _ToastColors(const Color(0xFFF64C30), Icons.error_rounded);
      case ToastType.info:
        return _ToastColors(palette.textSecondary, Icons.info_rounded);
      case ToastType.loading:
        return _ToastColors(palette.textSecondary, Icons.hourglass_top_rounded);
    }
  }
}

/// 顶层便捷函数：全局弹出 Toast
void showToast(
  String message, {
  ToastType type = ToastType.info,
  Duration duration = const Duration(seconds: 2),
  ToastPosition position = ToastPosition.top,
}) {
  AppToast.show(message, type: type, duration: duration, position: position);
}

/// Dialog组件工具类
class AppDialog {
  /// 确认对话框 - 返回bool表示用户选择
  static Future<bool?> confirm(
    BuildContext context, {
    required String title,
    dynamic content,
    String cancelText = '取消',
    String confirmText = '确认',
    bool barrierDismissible = true,
    bool fitsContent = false,
    double? buttonTextSize,
    Color? confirmButtonColor,
  }) {
    return showOmniGlassCardSheet<bool>(
      context: context,
      barrierDismissible: barrierDismissible,
      fitsContent: fitsContent,
      builder: (context) => _AppDialogWidget(
        title: title,
        content: content,
        type: DialogType.confirm,
        cancelText: LegacyTextLocalizer.localize(cancelText),
        confirmText: LegacyTextLocalizer.localize(confirmText),
        confirmButtonColor: confirmButtonColor,
        buttonTextSize: buttonTextSize,
      ),
    );
  }

  /// 警告对话框 - 只有确认按钮
  static Future<void> alert(
    BuildContext context, {
    required String title,
    dynamic content,
    String confirmText = '确定',
    bool barrierDismissible = true,
  }) {
    return showOmniGlassCardSheet<void>(
      context: context,
      barrierDismissible: barrierDismissible,
      builder: (context) => _AppDialogWidget(
        title: title,
        content: content,
        type: DialogType.alert,
        confirmText: confirmText,
      ),
    );
  }

  /// 输入对话框 - 返回用户输入的文本
  static Future<String?> input(
    BuildContext context, {
    required String title,
    dynamic content,
    String? hintText,
    String? initialValue,
    String cancelText = '取消',
    String confirmText = '确认',
    bool barrierDismissible = true,
    int maxLines = 1,
    TextInputType keyboardType = TextInputType.text,
  }) {
    return showOmniGlassCardSheet<String>(
      context: context,
      barrierDismissible: barrierDismissible,
      builder: (context) => _AppDialogWidget(
        title: title,
        content: content,
        type: DialogType.input,
        cancelText: LegacyTextLocalizer.localize(cancelText),
        confirmText: LegacyTextLocalizer.localize(confirmText),
        hintText: hintText,
        initialValue: initialValue,
        maxLines: maxLines,
        keyboardType: keyboardType,
      ),
    );
  }

  /// 选择对话框 - 返回选中的选项索引
  static Future<int?> select(
    BuildContext context, {
    required String title,
    dynamic content,
    required List<String> options,
    int? selectedIndex,
    bool barrierDismissible = true,
  }) {
    return showOmniGlassCardSheet<int>(
      context: context,
      barrierDismissible: barrierDismissible,
      builder: (context) => _AppDialogWidget(
        title: title,
        content: content,
        type: DialogType.select,
        options: options,
        selectedIndex: selectedIndex,
      ),
    );
  }

  /// 加载对话框 - 显示loading状态
  static void loading(
    BuildContext context, {
    String title =
        '加载中', // Will be localized via LegacyTextLocalizer.localize in widget
    dynamic content,
    bool barrierDismissible = false,
  }) {
    showOmniGlassCardSheet<void>(
      context: context,
      barrierDismissible: barrierDismissible,
      builder: (context) => _AppDialogWidget(
        title: title,
        content: content,
        type: DialogType.loading,
      ),
    );
  }

  /// 关闭loading对话框
  static void dismissLoading(BuildContext context) {
    Navigator.of(context).pop();
  }
}

/// 便捷函数保持向后兼容
Future<bool?> showCustomConfirmDialog(
  BuildContext context, {
  required String title,
  required dynamic content,
  String cancelText = '取消',
  String confirmText = '确认',
}) {
  return AppDialog.confirm(
    context,
    title: title,
    content: content,
    cancelText: cancelText,
    confirmText: confirmText,
  );
}

class _AppDialogWidget extends StatefulWidget {
  final String title;
  final dynamic content;
  final DialogType type;
  final String? cancelText;
  final String? confirmText;
  final Color? confirmButtonColor;
  final String? hintText;
  final String? initialValue;
  final int maxLines;
  final TextInputType keyboardType;
  final List<String>? options;
  final int? selectedIndex;
  final double? buttonTextSize;

  const _AppDialogWidget({
    required this.title,
    this.content,
    required this.type,
    this.cancelText,
    this.confirmText,
    this.confirmButtonColor,
    this.hintText,
    this.initialValue,
    this.maxLines = 1,
    this.keyboardType = TextInputType.text,
    this.options,
    this.selectedIndex,
    this.buttonTextSize,
  });

  @override
  State<_AppDialogWidget> createState() => _AppDialogWidgetState();
}

class _AppDialogWidgetState extends State<_AppDialogWidget> {
  late TextEditingController _textController;
  int? _selectedOption;

  @override
  void initState() {
    super.initState();
    _textController = TextEditingController(text: widget.initialValue ?? '');
    _selectedOption = widget.selectedIndex;
  }

  @override
  void dispose() {
    _textController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return OmniGlassCardBody(
      padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _buildTitle(),
                  if (widget.content != null) ...[
                    const SizedBox(height: 8),
                    _buildContent(),
                  ],
                  if (widget.type == DialogType.input ||
                      widget.type == DialogType.select ||
                      widget.type == DialogType.loading) ...[
                    const SizedBox(height: 16),
                    _buildBody(),
                  ],
                ],
              ),
            ),
          ),
          if (widget.type != DialogType.loading) ...[
            const SizedBox(height: 16),
            _buildButtons(),
          ],
        ],
      ),
    );
  }

  Widget _buildTitle() {
    final palette = context.omniPalette;
    return Text(
      widget.title,
      textAlign: TextAlign.center,
      style: TextStyle(
        color: palette.textPrimary,
        fontSize: 16,
        fontFamily: 'PingFang SC',
        fontWeight: FontWeight.w500,
        height: 1.56,
        letterSpacing: 0.39,
      ),
    );
  }

  Widget _buildContent() {
    final palette = context.omniPalette;
    if (widget.content is Widget) {
      return widget.content as Widget;
    } else if (widget.content is String) {
      return Text(
        widget.content as String,
        textAlign: TextAlign.center,
        style: TextStyle(
          color: palette.textSecondary,
          fontSize: 14,
          fontFamily: 'PingFang SC',
          fontWeight: FontWeight.w400,
          height: 1.50,
          letterSpacing: 0.39,
        ),
      );
    } else {
      return SizedBox.shrink();
    }
  }

  Widget _buildBody() {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    switch (widget.type) {
      case DialogType.input:
        return TextField(
          controller: _textController,
          maxLines: widget.maxLines,
          keyboardType: widget.keyboardType,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 14,
            fontFamily: 'PingFang SC',
          ),
          decoration: InputDecoration(
            hintText: widget.hintText,
            hintStyle: TextStyle(
              color: palette.textTertiary,
              fontSize: 14,
              fontFamily: 'PingFang SC',
            ),
            filled: true,
            fillColor: palette.surfaceSecondary,
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide(color: palette.borderSubtle),
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide(color: palette.borderSubtle),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide(color: palette.accentPrimary),
            ),
            contentPadding: const EdgeInsets.symmetric(
              horizontal: 14,
              vertical: 12,
            ),
          ),
        );
      case DialogType.select:
        return Container(
          constraints: BoxConstraints(maxHeight: 300),
          child: SingleChildScrollView(
            child: Column(
              children: widget.options!.asMap().entries.map((entry) {
                int index = entry.key;
                String option = entry.value;
                bool isSelected = _selectedOption == index;
                return GestureDetector(
                  onTap: () => setState(() => _selectedOption = index),
                  child: Container(
                    width: double.infinity,
                    padding: EdgeInsets.symmetric(vertical: 12, horizontal: 16),
                    margin: EdgeInsets.only(bottom: 8),
                    decoration: BoxDecoration(
                      color: isSelected
                          ? palette.accentPrimary.withValues(
                              alpha: isDark ? 0.18 : 0.1,
                            )
                          : (isDark
                                ? palette.surfaceSecondary
                                : Colors.transparent),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(
                        color: isSelected
                            ? palette.accentPrimary
                            : palette.borderSubtle,
                      ),
                    ),
                    child: Text(
                      option,
                      style: TextStyle(
                        color: isSelected
                            ? palette.accentPrimary
                            : palette.textPrimary,
                        fontSize: 14,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
        );
      case DialogType.loading:
        return Padding(
          padding: EdgeInsets.symmetric(vertical: 20),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  valueColor: AlwaysStoppedAnimation<Color>(
                    palette.accentPrimary,
                  ),
                ),
              ),
              SizedBox(width: 12),
              Text(
                LegacyTextLocalizer.localize('请稍候...'),
                style: TextStyle(
                  color: palette.textSecondary,
                  fontSize: 14,
                  fontFamily: 'PingFang SC',
                ),
              ),
            ],
          ),
        );
      default:
        return SizedBox.shrink();
    }
  }

  Widget _buildButtons() {
    final palette = context.omniPalette;
    final confirmColor = widget.confirmButtonColor ?? palette.accentPrimary;
    final onConfirm =
        ThemeData.estimateBrightnessForColor(confirmColor) == Brightness.light
        ? Colors.black
        : Colors.white;
    final shape = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(14),
    );
    final confirm = FilledButton(
      style: FilledButton.styleFrom(
        backgroundColor: confirmColor,
        foregroundColor: onConfirm,
        minimumSize: const Size(0, 44),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        textStyle: TextStyle(
          fontSize: widget.buttonTextSize ?? 15,
          fontWeight: FontWeight.w500,
        ),
        shape: shape,
      ),
      onPressed: () {
        switch (widget.type) {
          case DialogType.confirm:
            Navigator.of(context).pop(true);
          case DialogType.input:
            Navigator.of(context).pop(_textController.text);
          case DialogType.select:
            Navigator.of(context).pop(_selectedOption);
          default:
            Navigator.of(context).pop();
        }
      },
      child: Text(LegacyTextLocalizer.localize(widget.confirmText ?? '确认')),
    );
    if (widget.type == DialogType.alert) {
      return Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(minWidth: 166),
          child: confirm,
        ),
      );
    }
    return Row(
      children: [
        Expanded(
          child: TextButton(
            style: TextButton.styleFrom(
              backgroundColor: palette.surfaceSecondary,
              foregroundColor: palette.textPrimary,
              minimumSize: const Size(0, 44),
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              textStyle: TextStyle(fontSize: widget.buttonTextSize ?? 15),
              shape: shape,
            ),
            onPressed: () => Navigator.of(context).pop(null),
            child: Text(
              LegacyTextLocalizer.localize(widget.cancelText ?? '取消'),
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(child: confirm),
      ],
    );
  }
}

class _CustomLoadingWidget extends StatefulWidget {
  final String message;

  const _CustomLoadingWidget({required this.message});

  @override
  State<_CustomLoadingWidget> createState() => _CustomLoadingWidgetState();
}

class _CustomLoadingWidgetState extends State<_CustomLoadingWidget> {
  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return OmniGlassPanel(
      width: 165,
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            width: 28,
            height: 28,
            child: CircularProgressIndicator(
              strokeWidth: 2,
              color: palette.accentPrimary,
            ),
          ),
          const SizedBox(height: 16),
          Text(
            widget.message,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 14,
              height: 1.5,
              decoration: TextDecoration.none,
            ),
          ),
        ],
      ),
    );
  }
}

/// Adds the physical bottom system inset to a scroll view's content padding.
///
/// Full-screen page scrollables should use this together with
/// `SafeArea(bottom: false)`: the viewport can then extend behind the gesture
/// navigation handle, while the final item can still scroll to a safe resting
/// position above it.
EdgeInsets edgeToEdgeScrollPadding(BuildContext context, EdgeInsets padding) {
  return padding.copyWith(
    bottom: padding.bottom + MediaQuery.viewPaddingOf(context).bottom,
  );
}
