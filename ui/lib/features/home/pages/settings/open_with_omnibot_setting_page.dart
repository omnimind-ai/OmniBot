import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/app_state_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_ui.dart';

class OpenWithOmnibotSettingPage extends StatefulWidget {
  const OpenWithOmnibotSettingPage({super.key});

  @override
  State<OpenWithOmnibotSettingPage> createState() =>
      _OpenWithOmnibotSettingPageState();
}

class _OpenWithOmnibotSettingPageState
    extends State<OpenWithOmnibotSettingPage> {
  static const String _sharedOpenModeDefault = 'default';
  static const String _sharedOpenModeWorkspace = 'workspace';
  static const String _targetImage = 'image';
  static const String _targetFile = 'file';

  String _sharedOpenImageMode = _sharedOpenModeDefault;
  String _sharedOpenFileMode = _sharedOpenModeDefault;
  bool _sharedOpenModesLoaded = false;

  @override
  void initState() {
    super.initState();
    _loadSharedOpenMode();
  }

  Future<void> _loadSharedOpenMode() async {
    final modes = await AppStateService.getSharedOpenModes();
    if (!mounted) return;
    setState(() {
      _sharedOpenImageMode = _normalizeSharedOpenMode(modes['imageMode']);
      _sharedOpenFileMode = _normalizeSharedOpenMode(modes['fileMode']);
      _sharedOpenModesLoaded = true;
    });
  }

  Future<void> _onSharedOpenModeChanged({
    required String target,
    required String? value,
  }) async {
    final nextMode = _normalizeSharedOpenMode(value);
    final isImageTarget = target == _targetImage;
    final currentMode = isImageTarget
        ? _sharedOpenImageMode
        : _sharedOpenFileMode;
    if (nextMode == currentMode) return;
    final previousMode = currentMode;
    setState(() {
      if (isImageTarget) {
        _sharedOpenImageMode = nextMode;
      } else {
        _sharedOpenFileMode = nextMode;
      }
    });
    final saved = await AppStateService.setSharedOpenMode(
      nextMode,
      target: target,
    );
    if (!mounted) return;
    final normalizedSaved = _normalizeSharedOpenMode(saved);
    setState(() {
      if (isImageTarget) {
        _sharedOpenImageMode = normalizedSaved;
      } else {
        _sharedOpenFileMode = normalizedSaved;
      }
    });
    if (normalizedSaved != nextMode) {
      setState(() {
        if (isImageTarget) {
          _sharedOpenImageMode = previousMode;
        } else {
          _sharedOpenFileMode = previousMode;
        }
      });
      showToast(context.l10n.settingsSaveFailed, type: ToastType.error);
    }
  }

  String _normalizeSharedOpenMode(String? value) {
    return switch (value?.trim()) {
      _sharedOpenModeWorkspace => _sharedOpenModeWorkspace,
      _ => _sharedOpenModeDefault,
    };
  }

  String _defaultModeLabel(String target) {
    return target == _targetImage
        ? context.trLegacy('附加到对话输入框')
        : context.trLegacy('生成局域网文件链接');
  }

  String _modeSubtitle({required String target, required String mode}) {
    if (mode == _sharedOpenModeWorkspace) {
      return context.trLegacy('添加到 workspace，附加到对话，并在提示词中发送文件路径');
    }
    return target == _targetImage
        ? context.trLegacy('照片等文件会直接附加到对话输入框')
        : context.trLegacy('非照片文件会启动文件服务器并生成局域网链接');
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final sections = [
      _SettingSection(
        label: context.trLegacy('使用小万打开'),
        items: [
          _SettingItem(
            icon: LucideIcons.image,
            title: context.trLegacy('图片'),
            subtitle: _modeSubtitle(
              target: _targetImage,
              mode: _sharedOpenImageMode,
            ),
            trailing: _buildSharedOpenModeDropdown(
              target: _targetImage,
              value: _sharedOpenImageMode,
              onChanged: (value) =>
                  _onSharedOpenModeChanged(target: _targetImage, value: value),
            ),
          ),
          _SettingItem(
            icon: LucideIcons.file,
            title: context.trLegacy('文件'),
            subtitle: _modeSubtitle(
              target: _targetFile,
              mode: _sharedOpenFileMode,
            ),
            trailing: _buildSharedOpenModeDropdown(
              target: _targetFile,
              value: _sharedOpenFileMode,
              onChanged: (value) =>
                  _onSharedOpenModeChanged(target: _targetFile, value: value),
            ),
          ),
        ],
      ),
    ];

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(title: context.trLegacy('使用小万打开'), primary: true),
      body: OmniContentWidth(
        child: SafeArea(
          top: false,
          bottom: false,
          child: ListView.separated(
            padding: edgeToEdgeScrollPadding(
              context,
              const EdgeInsets.fromLTRB(22, 12, 22, 32),
            ),
            itemCount: sections.length,
            separatorBuilder: (_, __) => const SizedBox(height: 22),
            itemBuilder: (context, index) {
              return _buildSettingsSection(sections[index]);
            },
          ),
        ),
      ),
    );
  }

  Widget _buildSharedOpenModeDropdown({
    required String target,
    required String value,
    required ValueChanged<String?> onChanged,
  }) {
    final palette = context.omniPalette;
    if (!_sharedOpenModesLoaded) {
      return SizedBox(
        width: 18,
        height: 18,
        child: CircularProgressIndicator(
          strokeWidth: 2,
          color: palette.accentPrimary,
        ),
      );
    }
    return OmniSettingsDropdown<String>(
      value: value,
      maxWidth: 168,
      menuMinWidth: 204,
      options: [
        OmniSettingsOption(
          value: _sharedOpenModeDefault,
          label: _defaultModeLabel(target),
        ),
        OmniSettingsOption(
          value: _sharedOpenModeWorkspace,
          label: context.trLegacy('存入 workspace 并发送路径'),
        ),
      ],
      onChanged: onChanged,
    );
  }

  Widget _buildSettingsSection(_SettingSection section) {
    return OmniSettingsGroup(
      title: section.label,
      children: [
        for (final item in section.items)
          OmniSettingsTile(
            icon: item.icon,
            title: item.title,
            subtitle: item.subtitle,
            trailing: item.trailing,
          ),
      ],
    );
  }
}

class _SettingSection {
  final String label;
  final List<_SettingItem> items;

  const _SettingSection({required this.label, required this.items});
}

class _SettingItem {
  final IconData? icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;

  const _SettingItem({
    this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
  });
}
