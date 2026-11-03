import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/omni_glass.dart';

const int _kDefaultContextTokenThreshold = 128000;

class ContextThresholdSheet extends StatefulWidget {
  const ContextThresholdSheet({
    super.key,
    required this.initialThreshold,
    required this.currentUsageTokens,
    required this.onThresholdSaved,
  });

  final int initialThreshold;
  final int? currentUsageTokens;
  final Future<bool> Function(int threshold) onThresholdSaved;

  @override
  State<ContextThresholdSheet> createState() => _ContextThresholdSheetState();
}

class _ContextThresholdSheetState extends State<ContextThresholdSheet> {
  late final TextEditingController _controller;
  final FocusNode _focusNode = FocusNode();
  Timer? _autoSaveTimer;
  String? _errorText;
  String? _saveErrorText;
  late double _draftThreshold;
  late int _lastSavedThreshold;
  bool _isSaving = false;
  int? _queuedThreshold;

  static const List<int> _presets = <int>[
    32000,
    64000,
    128000,
    256000,
    512000,
    1000000,
  ];

  int get _sliderMaximum {
    return <int>[
      ..._presets,
      widget.initialThreshold,
      _draftThreshold.round(),
    ].reduce(
      (current, candidate) => current >= candidate ? current : candidate,
    );
  }

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(
      text: widget.initialThreshold.toString(),
    );
    _draftThreshold = widget.initialThreshold.toDouble();
    _lastSavedThreshold = widget.initialThreshold;
    _focusNode.addListener(_handleFocusChange);
  }

  @override
  void dispose() {
    _autoSaveTimer?.cancel();
    _focusNode.removeListener(_handleFocusChange);
    _focusNode.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _handleFocusChange() {
    if (_focusNode.hasFocus) {
      return;
    }
    final parsed = _parseInput(showEmptyError: false);
    if (parsed != null) {
      unawaited(_commitThreshold(parsed));
    }
  }

  Future<bool> _handleWillPop() async {
    await _flushPendingAutoSave();
    return true;
  }

  void _updateDraftThreshold(
    double value, {
    bool updateText = true,
    bool clearError = true,
  }) {
    final normalized = value.round();
    if (normalized <= 0) {
      return;
    }
    setState(() {
      _draftThreshold = normalized.toDouble();
      if (updateText) {
        final text = normalized.toString();
        _controller.value = TextEditingValue(
          text: text,
          selection: TextSelection.collapsed(offset: text.length),
        );
      }
      if (clearError) {
        _errorText = null;
      }
      _saveErrorText = null;
    });
  }

  int? _parseInput({bool showEmptyError = true}) {
    final raw = _controller.text.trim();
    if (raw.isEmpty) {
      if (showEmptyError) {
        setState(() {
          _errorText = LegacyTextLocalizer.isEnglish
              ? 'Please enter a threshold'
              : '请输入阈值';
        });
      }
      return null;
    }
    final parsed = int.tryParse(raw);
    if (parsed == null) {
      setState(() {
        _errorText = LegacyTextLocalizer.isEnglish
            ? 'Threshold must be an integer'
            : '阈值必须是整数';
      });
      return null;
    }
    if (parsed <= 0) {
      setState(() {
        _errorText = LegacyTextLocalizer.isEnglish
            ? 'Threshold must be positive'
            : '阈值必须为正整数';
      });
      return null;
    }
    setState(() {
      _errorText = null;
      _draftThreshold = parsed.toDouble();
    });
    return parsed;
  }

  void _scheduleAutoSave() {
    _autoSaveTimer?.cancel();
    final parsed = _parseInput(showEmptyError: false);
    if (parsed == null || parsed == _lastSavedThreshold) {
      return;
    }
    _autoSaveTimer = Timer(const Duration(milliseconds: 320), () {
      unawaited(_commitThreshold(parsed));
    });
  }

  Future<void> _commitThreshold([int? value]) async {
    final parsed = value ?? _parseInput();
    if (parsed == null || parsed == _lastSavedThreshold) {
      return;
    }
    _autoSaveTimer?.cancel();
    _queuedThreshold = parsed;
    if (_isSaving) {
      return;
    }

    while (_queuedThreshold != null) {
      final target = _queuedThreshold!;
      _queuedThreshold = null;
      if (!mounted) {
        return;
      }
      setState(() {
        _isSaving = true;
        _saveErrorText = null;
      });
      final success = await widget.onThresholdSaved(target);
      if (!mounted) {
        return;
      }
      if (success) {
        setState(() {
          _lastSavedThreshold = target;
          _isSaving = false;
          _saveErrorText = null;
        });
        continue;
      }
      setState(() {
        _isSaving = false;
        _saveErrorText = LegacyTextLocalizer.isEnglish
            ? 'Auto-save failed, please try again later'
            : '自动保存失败，请稍后重试';
      });
      break;
    }
  }

  Future<void> _flushPendingAutoSave() async {
    _autoSaveTimer?.cancel();
    final parsed = _parseInput(showEmptyError: false);
    if (parsed != null && parsed != _lastSavedThreshold) {
      await _commitThreshold(parsed);
    }
  }

  String _formatThresholdLabel(int threshold) {
    if (threshold >= 1000000) {
      final millions = threshold / 1000000;
      return millions % 1 == 0
          ? '${millions.toStringAsFixed(0)}M'
          : '${millions.toStringAsFixed(1)}M';
    }
    if (threshold >= 1000) {
      final kilo = threshold / 1000;
      return kilo % 1 == 0
          ? '${kilo.toStringAsFixed(0)}k'
          : '${kilo.toStringAsFixed(1)}k';
    }
    return threshold.toString();
  }

  String _formatTokenCount(int value) {
    return value.toString().replaceAllMapped(
      RegExp(r'\B(?=(\d{3})+(?!\d))'),
      (_) => ',',
    );
  }

  String _formatUsagePercent(double ratio) {
    if (!ratio.isFinite) {
      return '0%';
    }
    final percent = ratio * 100;
    final rounded = percent >= 100 || percent % 1 == 0
        ? percent.toStringAsFixed(0)
        : percent.toStringAsFixed(1);
    return '$rounded%';
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;
    final draftThreshold = _draftThreshold.round();
    final sliderMaximum = _sliderMaximum;
    final currentUsageTokens = widget.currentUsageTokens;
    final usageRatio = currentUsageTokens == null
        ? null
        : currentUsageTokens / draftThreshold;
    final noUsageData = LegacyTextLocalizer.isEnglish ? 'No data yet' : '暂无数据';
    final dividerColor = isDark
        ? palette.borderSubtle
        : palette.borderSubtle.withValues(alpha: 0.9);
    final accentColor = palette.accentPrimary;
    final warningColor = isDark
        ? const Color(0xFFE0A06A)
        : const Color(0xFFD65A3A);
    final successColor = isDark
        ? const Color(0xFF8DBB95)
        : const Color(0xFF2F8F6B);
    final pendingAutoSave = _autoSaveTimer?.isActive ?? false;
    final statusText = switch ((_saveErrorText, _isSaving, pendingAutoSave)) {
      (final String message?, _, _) => message,
      (_, true, _) => LegacyTextLocalizer.isEnglish ? 'Saving…' : '正在自动保存…',
      (_, false, true) =>
        LegacyTextLocalizer.isEnglish ? 'Pending auto-save' : '即将自动保存',
      _ =>
        draftThreshold == _lastSavedThreshold
            ? (LegacyTextLocalizer.isEnglish ? 'Auto-saved' : '已自动保存')
            : (LegacyTextLocalizer.isEnglish
                  ? 'Auto-save on change'
                  : '修改后自动保存'),
    };
    final statusColor = _saveErrorText != null
        ? warningColor
        : _isSaving || pendingAutoSave
        ? accentColor
        : palette.textSecondary;
    final navigator = Navigator.of(context);

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        unawaited(
          _handleWillPop().then((shouldPop) {
            if (shouldPop && mounted) {
              navigator.pop();
            }
          }),
        );
      },
      child: SafeArea(
        top: false,
        bottom: false,
        child: Padding(
          padding: EdgeInsets.only(top: 12, bottom: bottomInset),
          child: MediaQuery.removeViewInsets(
            context: context,
            removeBottom: true,
            child: OmniGlassSheet(
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(28),
              ),
              // Paint the gesture area with the same pane while keeping the
              // form above system controls. The outer padding owns the IME.
              child: SafeArea(
                top: false,
                left: false,
                right: false,
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Center(
                        child: Container(
                          width: 38,
                          height: 4,
                          decoration: BoxDecoration(
                            color: palette.borderStrong,
                            borderRadius: BorderRadius.circular(999),
                          ),
                        ),
                      ),
                      const SizedBox(height: 18),
                      Text(
                        LegacyTextLocalizer.isEnglish
                            ? 'Adjust Context Threshold'
                            : '调整上下文阈值',
                        style: TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.w700,
                          color: palette.textPrimary,
                        ),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        LegacyTextLocalizer.isEnglish
                            ? 'Changes are auto-saved. The new threshold takes effect immediately.'
                            : '修改后自动保存，新的阈值会立刻用于当前对话。',
                        style: TextStyle(
                          fontSize: 13,
                          height: 1.4,
                          color: palette.textSecondary,
                        ),
                      ),
                      const SizedBox(height: 18),
                      Container(
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        decoration: BoxDecoration(
                          border: Border(
                            top: BorderSide(color: dividerColor),
                            bottom: BorderSide(color: dividerColor),
                          ),
                        ),
                        child: Row(
                          children: [
                            Expanded(
                              child: _ThresholdMetric(
                                label: LegacyTextLocalizer.isEnglish
                                    ? 'Current context'
                                    : '当前上下文',
                                value: currentUsageTokens == null
                                    ? noUsageData
                                    : _formatTokenCount(currentUsageTokens),
                                accent: palette.textPrimary,
                              ),
                            ),
                            Container(
                              width: 1,
                              height: 38,
                              color: dividerColor,
                            ),
                            Expanded(
                              child: _ThresholdMetric(
                                label: LegacyTextLocalizer.isEnglish
                                    ? 'Target threshold'
                                    : '目标阈值',
                                value: _formatTokenCount(draftThreshold),
                                accent: accentColor,
                              ),
                            ),
                            Container(
                              width: 1,
                              height: 38,
                              color: dividerColor,
                            ),
                            Expanded(
                              child: _ThresholdMetric(
                                label: LegacyTextLocalizer.isEnglish
                                    ? 'Usage'
                                    : '占用比例',
                                value: usageRatio == null
                                    ? noUsageData
                                    : _formatUsagePercent(usageRatio),
                                accent: usageRatio == null
                                    ? palette.textSecondary
                                    : usageRatio >= 1
                                    ? warningColor
                                    : successColor,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 18),
                      SliderTheme(
                        data: SliderTheme.of(context).copyWith(
                          activeTrackColor: accentColor,
                          inactiveTrackColor: palette.segmentTrack,
                          thumbColor: palette.surfacePrimary,
                          overlayColor: accentColor.withValues(alpha: 0.12),
                          thumbShape: const RoundSliderThumbShape(
                            enabledThumbRadius: 10,
                          ),
                          trackHeight: 4,
                        ),
                        child: Slider(
                          min: 1,
                          max: sliderMaximum.toDouble(),
                          divisions: sliderMaximum > 1 ? 1000 : null,
                          value: _draftThreshold.clamp(
                            1,
                            sliderMaximum.toDouble(),
                          ),
                          onChanged: (value) => _updateDraftThreshold(value),
                          onChangeEnd: (value) {
                            _updateDraftThreshold(value);
                            unawaited(_commitThreshold(value.round()));
                          },
                        ),
                      ),
                      const SizedBox(height: 4),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: _presets.map((preset) {
                          final selected = draftThreshold == preset;
                          final chipBackground = selected
                              ? accentColor
                              : palette.surfaceSecondary;
                          final chipBorder = selected
                              ? accentColor
                              : dividerColor;
                          final chipTextColor = selected
                              ? (isDark
                                    ? Theme.of(context).colorScheme.onPrimary
                                    : Colors.white)
                              : palette.textSecondary;
                          return InkWell(
                            borderRadius: BorderRadius.circular(999),
                            onTap: () {
                              _updateDraftThreshold(preset.toDouble());
                              unawaited(_commitThreshold(preset));
                            },
                            child: AnimatedContainer(
                              duration: const Duration(milliseconds: 180),
                              padding: const EdgeInsets.symmetric(
                                horizontal: 12,
                                vertical: 8,
                              ),
                              decoration: BoxDecoration(
                                color: chipBackground,
                                borderRadius: BorderRadius.circular(999),
                                border: Border.all(color: chipBorder),
                              ),
                              child: Text(
                                _formatThresholdLabel(preset),
                                style: TextStyle(
                                  fontSize: 12,
                                  fontWeight: FontWeight.w600,
                                  color: chipTextColor,
                                ),
                              ),
                            ),
                          );
                        }).toList(),
                      ),
                      const SizedBox(height: 16),
                      TextField(
                        controller: _controller,
                        focusNode: _focusNode,
                        keyboardType: TextInputType.number,
                        inputFormatters: [
                          FilteringTextInputFormatter.digitsOnly,
                        ],
                        decoration: InputDecoration(
                          labelText: '精确阈值',
                          hintText: _kDefaultContextTokenThreshold.toString(),
                          helperText:
                              '默认 $_kDefaultContextTokenThreshold，可输入任意正整数',
                          errorText: _errorText,
                          filled: true,
                          fillColor: palette.surfaceSecondary,
                          labelStyle: TextStyle(color: palette.textSecondary),
                          hintStyle: TextStyle(color: palette.textTertiary),
                          helperStyle: TextStyle(
                            color: palette.textTertiary,
                            fontSize: 12,
                          ),
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(16),
                            borderSide: BorderSide(color: dividerColor),
                          ),
                          enabledBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(16),
                            borderSide: BorderSide(color: dividerColor),
                          ),
                          focusedBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(16),
                            borderSide: BorderSide(
                              color: accentColor,
                              width: 1.4,
                            ),
                          ),
                        ),
                        style: TextStyle(color: palette.textPrimary),
                        onChanged: (value) {
                          if (value.trim().isEmpty) {
                            _autoSaveTimer?.cancel();
                            setState(() {
                              _errorText = null;
                              _saveErrorText = null;
                            });
                            return;
                          }
                          final parsed = int.tryParse(value.trim());
                          if (parsed == null) {
                            return;
                          }
                          if (parsed <= 0) {
                            _autoSaveTimer?.cancel();
                            setState(() {
                              _errorText = '阈值必须为正整数';
                            });
                            return;
                          }
                          _updateDraftThreshold(
                            parsed.toDouble(),
                            updateText: false,
                          );
                          _scheduleAutoSave();
                        },
                        onSubmitted: (_) {
                          final parsed = _parseInput();
                          if (parsed != null) {
                            unawaited(_commitThreshold(parsed));
                          }
                        },
                      ),
                      const SizedBox(height: 14),
                      Row(
                        children: [
                          Icon(
                            _saveErrorText != null
                                ? Icons.error_outline_rounded
                                : _isSaving || pendingAutoSave
                                ? Icons.sync_rounded
                                : Icons.check_circle_outline_rounded,
                            size: 16,
                            color: statusColor,
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              statusText,
                              style: TextStyle(
                                fontSize: 12,
                                color: statusColor,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ThresholdMetric extends StatelessWidget {
  const _ThresholdMetric({
    required this.label,
    required this.value,
    required this.accent,
  });

  final String label;
  final String value;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: palette.textSecondary,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            value,
            style: TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w700,
              color: accent,
            ),
          ),
        ],
      ),
    );
  }
}
