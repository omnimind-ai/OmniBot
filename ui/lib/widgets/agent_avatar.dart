import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:ui/services/agent_avatar_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';

Future<AgentAvatarState?> showAgentAvatarPicker(BuildContext context) {
  return showDialog<AgentAvatarState>(
    context: context,
    barrierColor: Colors.black.withValues(alpha: 0.28),
    builder: (context) => const _AgentAvatarPickerDialog(),
  );
}

class AgentAvatarButton extends StatefulWidget {
  const AgentAvatarButton({
    super.key,
    this.size = 28,
    this.tooltip = '修改 Agent 头像',
    this.showEditBadge = false,
    this.showCompletedBadge = false,
    this.showBorder = true,
    this.onChanged,
  });

  final double size;
  final String tooltip;
  final bool showEditBadge;
  final bool showCompletedBadge;
  final bool showBorder;
  final ValueChanged<AgentAvatarState>? onChanged;

  @override
  State<AgentAvatarButton> createState() => _AgentAvatarButtonState();
}

class _AgentAvatarButtonState extends State<AgentAvatarButton> {
  bool _isPressed = false;
  bool _isOpeningPicker = false;

  @override
  void initState() {
    super.initState();
    AgentAvatarService.ensureLoaded();
  }

  Future<void> _openPicker() async {
    if (_isOpeningPicker) {
      return;
    }
    setState(() {
      _isOpeningPicker = true;
    });
    try {
      final selectedState = await showAgentAvatarPicker(context);
      if (selectedState == null || !mounted) {
        return;
      }
      widget.onChanged?.call(selectedState);
    } finally {
      if (mounted) {
        setState(() {
          _isOpeningPicker = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: widget.tooltip,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: _openPicker,
        onTapDown: (_) => setState(() => _isPressed = true),
        onTapCancel: () => setState(() => _isPressed = false),
        onTapUp: (_) => setState(() => _isPressed = false),
        child: AnimatedScale(
          duration: const Duration(milliseconds: 140),
          curve: Curves.easeOutCubic,
          scale: _isPressed ? 0.94 : 1,
          child: ValueListenableBuilder<AgentAvatarState>(
            valueListenable: AgentAvatarService.avatarStateNotifier,
            builder: (context, state, _) {
              return AgentAvatarCircle(
                state: state,
                size: widget.size,
                showEditBadge: widget.showEditBadge,
                showCompletedBadge: widget.showCompletedBadge,
                showBorder: widget.showBorder,
              );
            },
          ),
        ),
      ),
    );
  }
}

class AgentAvatarCircle extends StatelessWidget {
  const AgentAvatarCircle({
    super.key,
    this.state,
    this.avatarIndex,
    this.customImagePath,
    this.size = 28,
    this.showEditBadge = false,
    this.showCompletedBadge = false,
    this.showBorder = true,
  });

  final AgentAvatarState? state;
  final int? avatarIndex;
  final String? customImagePath;
  final double size;
  final bool showEditBadge;
  final bool showCompletedBadge;

  /// 头像外圈那条 1.5px 描边。聊天页里 agent 头像不需要这圈白框
  /// (`showBorder: false`);头像选择弹窗/设置页仍保留描边做视觉分隔。
  /// 边线是 `foregroundDecoration`,盖在已铺满 `size` 的图片外沿上,
  /// 去掉后图片自然补满这 1.5px,整体 `size×size` 尺寸不变。
  final bool showBorder;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final badgeSize = (size * 0.38).clamp(10.0, 16.0).toDouble();
    final badgeIconSize = (badgeSize * 0.64).clamp(7.0, 11.0).toDouble();
    final resolvedPresetIndex = avatarIndex ?? state?.presetIndex ?? 0;
    final resolvedCustomImagePath =
        customImagePath ?? state?.customImagePath ?? '';
    final badgeIcon = showCompletedBadge
        ? Icons.check_rounded
        : showEditBadge
        ? Icons.edit_rounded
        : null;
    final badgeColor = showCompletedBadge
        ? const Color(0xFF23B26D)
        : palette.accentPrimary;

    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Container(
            foregroundDecoration: showBorder
                ? BoxDecoration(
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: context.isDarkTheme
                          ? palette.borderStrong
                          : const Color(0xFFFFFFFF),
                      width: 1.5,
                    ),
                  )
                : null,
            child: ClipOval(
              child: _AvatarImage(
                presetIndex: resolvedPresetIndex,
                customImagePath: resolvedCustomImagePath,
                size: size,
              ),
            ),
          ),
          if (badgeIcon != null)
            Positioned(
              right: -1,
              bottom: -1,
              child: Container(
                width: badgeSize,
                height: badgeSize,
                decoration: BoxDecoration(
                  color: badgeColor,
                  shape: BoxShape.circle,
                  border: Border.all(color: palette.surfacePrimary, width: 1),
                ),
                child: Icon(
                  badgeIcon,
                  size: badgeIconSize,
                  color: Colors.white,
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _AvatarImage extends StatelessWidget {
  const _AvatarImage({
    required this.presetIndex,
    required this.customImagePath,
    required this.size,
  });

  final int presetIndex;
  final String customImagePath;
  final double size;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final presetAsset = AgentAvatarService.assetForIndex(presetIndex);

    Widget fallback() {
      return Image.asset(
        presetAsset,
        width: size,
        height: size,
        fit: BoxFit.cover,
        errorBuilder: (_, _, _) {
          return ColoredBox(
            color: palette.surfaceElevated,
            child: Icon(
              Icons.smart_toy_outlined,
              size: size * 0.54,
              color: palette.textSecondary,
            ),
          );
        },
      );
    }

    if (customImagePath.trim().isEmpty) {
      return fallback();
    }
    return Image.file(
      File(customImagePath),
      width: size,
      height: size,
      fit: BoxFit.cover,
      errorBuilder: (_, _, _) => fallback(),
    );
  }
}

class _AgentAvatarPickerDialog extends StatefulWidget {
  const _AgentAvatarPickerDialog();

  @override
  State<_AgentAvatarPickerDialog> createState() =>
      _AgentAvatarPickerDialogState();
}

class _AgentAvatarPickerDialogState extends State<_AgentAvatarPickerDialog> {
  static bool _isImagePickerActive = false;
  bool _isPickingLocalImage = false;

  Future<void> _selectPresetAvatar(
    BuildContext context,
    int avatarIndex,
  ) async {
    final selectedState = await AgentAvatarService.setPresetAvatarIndex(
      avatarIndex,
    );
    if (!context.mounted) {
      return;
    }
    Navigator.of(context).pop(selectedState);
  }

  Future<void> _pickLocalImage(BuildContext context) async {
    if (_isPickingLocalImage || _isImagePickerActive) {
      return;
    }
    _isImagePickerActive = true;
    if (mounted) {
      setState(() {
        _isPickingLocalImage = true;
      });
    }
    try {
      final pickedFile = await ImagePicker().pickImage(
        source: ImageSource.gallery,
        imageQuality: 96,
      );
      if (pickedFile == null) {
        return;
      }
      if (!context.mounted) {
        return;
      }
      final croppedBytes = await showDialog<Uint8List>(
        context: context,
        barrierColor: Colors.black.withValues(alpha: 0.36),
        builder: (context) =>
            _AgentAvatarCropDialog(imagePath: pickedFile.path),
      );
      if (croppedBytes == null) {
        return;
      }
      final selectedState = await AgentAvatarService.setCustomAvatarBytes(
        croppedBytes,
      );
      if (!context.mounted) {
        return;
      }
      Navigator.of(context).pop(selectedState);
    } catch (error) {
      showToast('选择头像失败：$error', type: ToastType.error);
    } finally {
      _isImagePickerActive = false;
      if (mounted) {
        setState(() {
          _isPickingLocalImage = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final surfaceColor = isDark ? palette.surfacePrimary : Colors.white;

    return Dialog(
      insetPadding: const EdgeInsets.symmetric(horizontal: 36),
      backgroundColor: Colors.transparent,
      elevation: 0,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 326),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: surfaceColor,
            borderRadius: BorderRadius.circular(24),
            border: Border.all(
              color: isDark ? palette.borderSubtle : const Color(0xFFE2EAF4),
            ),
            boxShadow: [
              BoxShadow(
                color: palette.shadowColor.withValues(
                  alpha: isDark ? 0.36 : 0.16,
                ),
                blurRadius: 28,
                offset: const Offset(0, 16),
              ),
            ],
          ),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(18, 18, 18, 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    ValueListenableBuilder<AgentAvatarState>(
                      valueListenable: AgentAvatarService.avatarStateNotifier,
                      builder: (context, state, _) {
                        return AgentAvatarCircle(state: state, size: 42);
                      },
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        'Agent 头像',
                        style: TextStyle(
                          color: palette.textPrimary,
                          fontSize: 17,
                          fontWeight: FontWeight.w700,
                          fontFamily: 'PingFang SC',
                        ),
                      ),
                    ),
                    IconButton(
                      tooltip: '关闭',
                      visualDensity: VisualDensity.compact,
                      onPressed: () => Navigator.of(context).pop(),
                      icon: Icon(
                        Icons.close_rounded,
                        size: 20,
                        color: palette.textTertiary,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                _LocalAvatarPickerButton(
                  isBusy: _isPickingLocalImage,
                  onTap: () => _pickLocalImage(context),
                ),
                const SizedBox(height: 14),
                ValueListenableBuilder<AgentAvatarState>(
                  valueListenable: AgentAvatarService.avatarStateNotifier,
                  builder: (context, selectedState, _) {
                    return GridView.builder(
                      shrinkWrap: true,
                      physics: const NeverScrollableScrollPhysics(),
                      itemCount: AgentAvatarService.presetAvatars.length,
                      gridDelegate:
                          const SliverGridDelegateWithFixedCrossAxisCount(
                            crossAxisCount: 3,
                            mainAxisSpacing: 12,
                            crossAxisSpacing: 12,
                          ),
                      itemBuilder: (context, index) {
                        final selected =
                            !selectedState.hasCustomImage &&
                            selectedState.presetIndex == index;
                        return _AgentAvatarPickerItem(
                          avatarIndex: index,
                          selected: selected,
                          onTap: () => _selectPresetAvatar(context, index),
                        );
                      },
                    );
                  },
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _LocalAvatarPickerButton extends StatelessWidget {
  const _LocalAvatarPickerButton({required this.isBusy, required this.onTap});

  final bool isBusy;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final accentColor = context.isDarkTheme
        ? palette.accentPrimary
        : const Color(0xFF2C7FEB);
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        onTap: isBusy ? null : onTap,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 12),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: accentColor.withValues(alpha: 0.24)),
          ),
          child: Row(
            children: [
              Container(
                width: 32,
                height: 32,
                decoration: BoxDecoration(
                  color: accentColor,
                  shape: BoxShape.circle,
                ),
                child: isBusy
                    ? const Padding(
                        padding: EdgeInsets.all(8),
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Icon(
                        Icons.image_outlined,
                        color: Colors.white,
                        size: 18,
                      ),
              ),
              const SizedBox(width: 11),
              Expanded(
                child: Text(
                  '从相册选择并裁剪',
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              Icon(
                Icons.arrow_forward_ios_rounded,
                size: 14,
                color: palette.textTertiary,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AgentAvatarCropDialog extends StatefulWidget {
  const _AgentAvatarCropDialog({required this.imagePath});

  final String imagePath;

  @override
  State<_AgentAvatarCropDialog> createState() => _AgentAvatarCropDialogState();
}

class _AgentAvatarCropDialogState extends State<_AgentAvatarCropDialog> {
  static const double _maxUserScale = 4;
  static const int _outputSize = 512;

  ui.Image? _image;
  Object? _loadError;
  bool _isSaving = false;
  double _scale = 1;
  double _startScale = 1;
  Offset _offset = Offset.zero;
  Offset _startOffset = Offset.zero;
  Offset _startFocalPoint = Offset.zero;

  @override
  void initState() {
    super.initState();
    _loadImage();
  }

  @override
  void dispose() {
    _image?.dispose();
    super.dispose();
  }

  Future<void> _loadImage() async {
    try {
      final bytes = await File(widget.imagePath).readAsBytes();
      final codec = await ui.instantiateImageCodec(bytes);
      final frame = await codec.getNextFrame();
      if (!mounted) {
        frame.image.dispose();
        return;
      }
      setState(() {
        _image = frame.image;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = error;
      });
    }
  }

  double _baseScale(double cropSize) {
    final image = _image;
    if (image == null) {
      return 1;
    }
    return math.max(cropSize / image.width, cropSize / image.height);
  }

  Offset _clampOffset(Offset proposed, double scale, double cropSize) {
    final image = _image;
    if (image == null) {
      return Offset.zero;
    }
    final baseScale = _baseScale(cropSize);
    final displayedWidth = image.width * baseScale * scale;
    final displayedHeight = image.height * baseScale * scale;
    final maxDx = math.max(0, (displayedWidth - cropSize) / 2);
    final maxDy = math.max(0, (displayedHeight - cropSize) / 2);
    return Offset(
      proposed.dx.clamp(-maxDx, maxDx).toDouble(),
      proposed.dy.clamp(-maxDy, maxDy).toDouble(),
    );
  }

  Future<Uint8List> _renderCroppedAvatar(double cropSize) async {
    final image = _image;
    if (image == null) {
      throw StateError('图片还没有加载完成');
    }

    final effectiveScale = _baseScale(cropSize) * _scale;
    final displayedWidth = image.width * effectiveScale;
    final displayedHeight = image.height * effectiveScale;
    final imageLeft = cropSize / 2 + _offset.dx - displayedWidth / 2;
    final imageTop = cropSize / 2 + _offset.dy - displayedHeight / 2;
    final sourceSize = cropSize / effectiveScale;
    final maxSourceLeft = math.max(0, image.width - sourceSize);
    final maxSourceTop = math.max(0, image.height - sourceSize);
    final sourceLeft = (-imageLeft / effectiveScale)
        .clamp(0, maxSourceLeft)
        .toDouble();
    final sourceTop = (-imageTop / effectiveScale)
        .clamp(0, maxSourceTop)
        .toDouble();
    final sourceRect = Rect.fromLTWH(
      sourceLeft,
      sourceTop,
      math.min(sourceSize, image.width.toDouble()),
      math.min(sourceSize, image.height.toDouble()),
    );
    final destinationRect = Rect.fromLTWH(
      0,
      0,
      _outputSize.toDouble(),
      _outputSize.toDouble(),
    );

    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    final paint = Paint()
      ..isAntiAlias = true
      ..filterQuality = FilterQuality.high;
    canvas.drawImageRect(image, sourceRect, destinationRect, paint);
    final picture = recorder.endRecording();
    final croppedImage = await picture.toImage(_outputSize, _outputSize);
    final byteData = await croppedImage.toByteData(
      format: ui.ImageByteFormat.png,
    );
    croppedImage.dispose();
    picture.dispose();
    if (byteData == null) {
      throw StateError('头像裁剪失败');
    }
    return byteData.buffer.asUint8List();
  }

  Future<void> _confirmCrop(double cropSize) async {
    if (_isSaving) {
      return;
    }
    setState(() => _isSaving = true);
    try {
      final bytes = await _renderCroppedAvatar(cropSize);
      if (!mounted) {
        return;
      }
      Navigator.of(context).pop(bytes);
    } catch (error) {
      showToast('裁剪头像失败：$error', type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _isSaving = false);
      }
    }
  }

  Widget _buildCropViewport(double cropSize) {
    final image = _image;
    if (image == null) {
      return SizedBox(
        width: cropSize,
        height: cropSize,
        child: const Center(child: CircularProgressIndicator(strokeWidth: 2)),
      );
    }
    final baseScale = _baseScale(cropSize);
    final imageWidth = image.width * baseScale;
    final imageHeight = image.height * baseScale;
    return GestureDetector(
      onScaleStart: (details) {
        _startScale = _scale;
        _startOffset = _offset;
        _startFocalPoint = details.focalPoint;
      },
      onScaleUpdate: (details) {
        final nextScale = (_startScale * details.scale)
            .clamp(1.0, _maxUserScale)
            .toDouble();
        final nextOffset =
            _startOffset + (details.focalPoint - _startFocalPoint);
        setState(() {
          _scale = nextScale;
          _offset = _clampOffset(nextOffset, nextScale, cropSize);
        });
      },
      child: SizedBox(
        width: cropSize,
        height: cropSize,
        child: Stack(
          alignment: Alignment.center,
          clipBehavior: Clip.none,
          children: [
            ClipOval(
              child: ColoredBox(
                color: context.omniPalette.surfaceElevated,
                child: SizedBox(
                  width: cropSize,
                  height: cropSize,
                  child: Center(
                    child: Transform.translate(
                      offset: _offset,
                      child: Transform.scale(
                        scale: _scale,
                        child: OverflowBox(
                          minWidth: 0,
                          minHeight: 0,
                          maxWidth: double.infinity,
                          maxHeight: double.infinity,
                          child: _DecodedCropImage(
                            image: image,
                            width: imageWidth,
                            height: imageHeight,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
            IgnorePointer(
              child: Container(
                width: cropSize,
                height: cropSize,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: Colors.white.withValues(alpha: 0.92),
                    width: 2,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final screenWidth = MediaQuery.of(context).size.width;
    final cropSize = math.min(260.0, screenWidth - 96).clamp(190.0, 260.0);

    return Dialog(
      insetPadding: const EdgeInsets.symmetric(horizontal: 28),
      backgroundColor: Colors.transparent,
      elevation: 0,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 356),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: isDark ? palette.surfacePrimary : Colors.white,
            borderRadius: BorderRadius.circular(24),
            border: Border.all(
              color: isDark ? palette.borderSubtle : const Color(0xFFE2EAF4),
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(18, 18, 18, 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        '裁剪头像',
                        style: TextStyle(
                          color: palette.textPrimary,
                          fontSize: 17,
                          fontWeight: FontWeight.w700,
                          fontFamily: 'PingFang SC',
                        ),
                      ),
                    ),
                    IconButton(
                      tooltip: '关闭',
                      visualDensity: VisualDensity.compact,
                      onPressed: _isSaving
                          ? null
                          : () => Navigator.of(context).pop(),
                      icon: Icon(
                        Icons.close_rounded,
                        size: 20,
                        color: palette.textTertiary,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                if (_loadError != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 40),
                    child: Text(
                      '图片加载失败，请重新选择',
                      style: TextStyle(
                        color: palette.textSecondary,
                        fontSize: 13,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                  )
                else
                  _buildCropViewport(cropSize.toDouble()),
                const SizedBox(height: 12),
                Text(
                  '拖动或双指缩放，圆框内即头像',
                  style: TextStyle(
                    color: palette.textTertiary,
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
                const SizedBox(height: 18),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        onPressed: _isSaving
                            ? null
                            : () => Navigator.of(context).pop(),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: palette.textSecondary,
                          side: BorderSide(color: palette.borderSubtle),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(14),
                          ),
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                        child: const Text('取消'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: FilledButton(
                        onPressed: _image == null || _loadError != null
                            ? null
                            : () => _confirmCrop(cropSize.toDouble()),
                        style: FilledButton.styleFrom(
                          backgroundColor: isDark
                              ? palette.accentPrimary
                              : const Color(0xFF2C7FEB),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(14),
                          ),
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                        child: _isSaving
                            ? const SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  valueColor: AlwaysStoppedAnimation<Color>(
                                    Colors.white,
                                  ),
                                ),
                              )
                            : const Text('设为头像'),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _DecodedCropImage extends StatelessWidget {
  const _DecodedCropImage({
    required this.image,
    required this.width,
    required this.height,
  });

  final ui.Image image;
  final double width;
  final double height;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: width,
      height: height,
      child: CustomPaint(
        size: Size(width, height),
        painter: _DecodedCropImagePainter(image),
      ),
    );
  }
}

class _DecodedCropImagePainter extends CustomPainter {
  const _DecodedCropImagePainter(this.image);

  final ui.Image image;

  @override
  void paint(Canvas canvas, Size size) {
    final sourceRect = Rect.fromLTWH(
      0,
      0,
      image.width.toDouble(),
      image.height.toDouble(),
    );
    final destinationRect = Offset.zero & size;
    final paint = Paint()
      ..isAntiAlias = true
      ..filterQuality = FilterQuality.high;
    canvas.drawImageRect(image, sourceRect, destinationRect, paint);
  }

  @override
  bool shouldRepaint(_DecodedCropImagePainter oldDelegate) {
    return oldDelegate.image != image;
  }
}

class _AgentAvatarPickerItem extends StatelessWidget {
  const _AgentAvatarPickerItem({
    required this.avatarIndex,
    required this.selected,
    required this.onTap,
  });

  final int avatarIndex;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final selectedColor = context.isDarkTheme
        ? palette.accentPrimary
        : const Color(0xFF2C7FEB);

    return Material(
      color: selected
          ? selectedColor.withValues(alpha: context.isDarkTheme ? 0.14 : 0.08)
          : Colors.transparent,
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(18),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOutCubic,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(18),
            border: Border.all(
              color: selected ? selectedColor : palette.borderSubtle,
              width: selected ? 1.6 : 1,
            ),
          ),
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              AgentAvatarCircle(avatarIndex: avatarIndex, size: 56),
              if (selected)
                Positioned(
                  right: -3,
                  bottom: -3,
                  child: Container(
                    width: 18,
                    height: 18,
                    decoration: BoxDecoration(
                      color: selectedColor,
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: palette.surfacePrimary,
                        width: 1.2,
                      ),
                    ),
                    child: const Icon(
                      Icons.check_rounded,
                      size: 12,
                      color: Colors.white,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
