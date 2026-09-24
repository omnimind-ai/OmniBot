import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:flutter_svg/flutter_svg.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/app_language_mode.dart';
import 'package:ui/l10n/app_locale_controller.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/services/overlay_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/app_background_widgets.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';
import 'package:ui/widgets/settings_section_title.dart';
import 'package:ui/widgets/theme_mode_setting_card.dart';

const bool _showPetAppearanceSettings = true;

class _AppearanceTextColorPreset {
  final String label;
  final String hex;
  final Color color;

  const _AppearanceTextColorPreset({
    required this.label,
    required this.hex,
    required this.color,
  });
}

class _OverlayPetOption {
  final String id;
  final String name;
  final String description;
  final String imagePath;
  final bool isBuiltin;
  final String animationLabel;

  const _OverlayPetOption({
    required this.id,
    required this.name,
    required this.description,
    required this.imagePath,
    required this.isBuiltin,
    this.animationLabel = '',
  });
}

const List<_AppearanceTextColorPreset> _kAppearanceTextColorPresets =
    <_AppearanceTextColorPreset>[
      _AppearanceTextColorPreset(
        label: '白',
        hex: '#FFFFFF',
        color: Color(0xFFFFFFFF),
      ),
      _AppearanceTextColorPreset(
        label: '深灰',
        hex: '#353E53',
        color: Color(0xFF353E53),
      ),
      _AppearanceTextColorPreset(
        label: '浅蓝',
        hex: '#DCEBFF',
        color: Color(0xFFDCEBFF),
      ),
      _AppearanceTextColorPreset(
        label: '藏蓝',
        hex: '#1D3E7B',
        color: Color(0xFF1D3E7B),
      ),
      _AppearanceTextColorPreset(
        label: '青绿',
        hex: '#2F7A4A',
        color: Color(0xFF2F7A4A),
      ),
      _AppearanceTextColorPreset(
        label: '暖黄',
        hex: '#F59E0B',
        color: Color(0xFFF59E0B),
      ),
    ];

class BackgroundSettingPage extends StatefulWidget {
  const BackgroundSettingPage({
    super.key,
    this.showBasicPreferences = true,
    this.petOnly = false,
  });

  final bool showBasicPreferences;
  final bool petOnly;

  @override
  State<BackgroundSettingPage> createState() => _BackgroundSettingPageState();
}

class _BackgroundSettingPageState extends State<BackgroundSettingPage>
    with WidgetsBindingObserver {
  final TextEditingController _remoteUrlController = TextEditingController();
  final TextEditingController _textColorController = TextEditingController();

  late AppBackgroundConfig _savedConfig;
  late AppBackgroundConfig _draftConfig;
  AppBackgroundVisualProfile _draftVisualProfile =
      AppBackgroundVisualProfile.defaultProfile;
  BackgroundPreviewKind _previewKind = BackgroundPreviewKind.chat;
  bool _saving = false;
  String? _sessionImportedLocalPath;
  Timer? _previewProfileDebounceTimer;
  Timer? _autoSaveDebounceTimer;
  int _previewProfileToken = 0;
  int _autoSaveRequestId = 0;
  bool _appIsActive = true;
  bool _heldDraftOnPause = false;
  bool _syncingControllers = false;
  final List<AppBackgroundConfig> _serviceSaveTargets = [];
  bool _petExpanded = false;
  bool _petBusy = false;
  String _selectedPetId = 'builtin:xiaowan';
  List<_OverlayPetOption> _petOptions = const [
    _OverlayPetOption(
      id: 'builtin:xiaowan',
      name: '小万',
      description: '默认的桌面悬浮窗宠物',
      imagePath: '',
      isBuiltin: true,
    ),
  ];

  AppBackgroundConfig get _previewConfig {
    return _draftConfig;
  }

  bool get _showPetSection =>
      _showPetAppearanceSettings &&
      (widget.petOnly || widget.showBasicPreferences);

  bool _sameConfig(AppBackgroundConfig left, AppBackgroundConfig right) {
    return left.toJson().toString() == right.toJson().toString();
  }

  bool _hasUnsavedImportedLocalImage(AppBackgroundConfig snapshot) {
    final importedPath = _sessionImportedLocalPath;
    return importedPath != null && importedPath != snapshot.localImagePath;
  }

  String get _autoSaveHint => _saving
      ? context.l10n.appearanceAutoSaving
      : context.l10n.appearanceAutosaveHint;

  String? get _remoteUrlErrorText {
    if (_draftConfig.sourceType != AppBackgroundSourceType.remote) {
      return null;
    }
    final raw = _remoteUrlController.text.trim();
    if (raw.isEmpty) {
      return _draftConfig.enabled
          ? context.l10n.appearanceInvalidHttpUrl
          : null;
    }
    final uri = Uri.tryParse(raw);
    if (uri == null ||
        !(uri.scheme == 'http' || uri.scheme == 'https') ||
        uri.host.isEmpty) {
      return context.l10n.appearanceInvalidHttpUrl;
    }
    return null;
  }

  String? get _textColorErrorText {
    final raw = _textColorController.text.trim();
    if (_draftConfig.chatTextColorMode != AppBackgroundTextColorMode.custom &&
        raw.isEmpty) {
      return null;
    }
    if (raw.isEmpty) {
      return context.l10n.appearanceInvalidHexColor;
    }
    return normalizeAppBackgroundHexColor(raw) == null
        ? context.l10n.appearanceInvalidHexColorFormat
        : null;
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    AppBackgroundService.notifier.addListener(_handleCommittedConfigChanged);
    _savedConfig = AppBackgroundService.current;
    _draftConfig = _savedConfig;
    _draftVisualProfile = AppBackgroundService.currentVisualProfile;
    _remoteUrlController.text = _draftConfig.remoteImageUrl;
    _textColorController.text = _draftConfig.chatTextHexColor;
    _remoteUrlController.addListener(_handleRemoteUrlChanged);
    _textColorController.addListener(_handleTextColorChanged);
    _scheduleDraftVisualProfileRefresh();
    if (_showPetSection) {
      unawaited(_loadPetSettings());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    AppBackgroundService.notifier.removeListener(_handleCommittedConfigChanged);
    final pendingSnapshot = _normalizedDraft();
    final shouldFlushPendingDraft =
        !_sameConfig(_savedConfig, pendingSnapshot) ||
        _hasUnsavedImportedLocalImage(pendingSnapshot);
    _previewProfileDebounceTimer?.cancel();
    _autoSaveDebounceTimer?.cancel();
    if (shouldFlushPendingDraft && _appIsActive) {
      unawaited(_flushPendingDraftOnDispose(pendingSnapshot));
    } else if (!_appIsActive) {
      final importedPath = _sessionImportedLocalPath;
      if (importedPath != null &&
          importedPath != _savedConfig.localImagePath &&
          !_serviceSaveTargets.any(
            (target) => target.localImagePath == importedPath,
          )) {
        unawaited(AppBackgroundService.deleteManagedLocalImage(importedPath));
      }
    }
    _remoteUrlController
      ..removeListener(_handleRemoteUrlChanged)
      ..dispose();
    _textColorController
      ..removeListener(_handleTextColorChanged)
      ..dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _appIsActive = true;
      if (_showPetSection) unawaited(_loadPetSettings());
      return;
    }
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused) {
      _appIsActive = false;
      _heldDraftOnPause = !_sameConfig(_savedConfig, _normalizedDraft());
      _autoSaveDebounceTimer?.cancel();
      ++_autoSaveRequestId;
    }
  }

  void _handleCommittedConfigChanged() {
    final committed = AppBackgroundService.current;
    if (_serviceSaveTargets.any((target) => _sameConfig(target, committed))) {
      return;
    }
    if (_sameConfig(_savedConfig, committed)) {
      // Resume may find the same stored value. Keep the local draft and save it
      // only after the shared settings refresh has confirmed no native edit.
      if (_heldDraftOnPause && _appIsActive) _scheduleAutoSave();
      _heldDraftOnPause = false;
      return;
    }

    // Native settings changed while this Flutter route stayed mounted. Drop
    // its older draft so the pending debounce/dispose path cannot overwrite it.
    _autoSaveDebounceTimer?.cancel();
    ++_autoSaveRequestId;
    final importedPath = _sessionImportedLocalPath;
    _sessionImportedLocalPath = null;
    _heldDraftOnPause = false;
    setState(() {
      _savedConfig = committed;
      _draftConfig = committed;
      _draftVisualProfile = AppBackgroundService.currentVisualProfile;
      _syncingControllers = true;
      try {
        _remoteUrlController.text = committed.remoteImageUrl;
        _textColorController.text = committed.chatTextHexColor;
      } finally {
        _syncingControllers = false;
      }
      _saving = false;
    });
    _scheduleDraftVisualProfileRefresh();
    if (importedPath != null && importedPath != committed.localImagePath) {
      unawaited(AppBackgroundService.deleteManagedLocalImage(importedPath));
    }
  }

  void _handleRemoteUrlChanged() {
    if (_syncingControllers) return;
    final nextUrl = _remoteUrlController.text.trim();
    if (_draftConfig.sourceType != AppBackgroundSourceType.remote ||
        nextUrl == _draftConfig.remoteImageUrl) {
      return;
    }
    _applyDraftConfig(_draftConfig.copyWith(remoteImageUrl: nextUrl));
  }

  void _handleTextColorChanged() {
    if (_syncingControllers) return;
    final normalized = normalizeAppBackgroundHexColor(
      _textColorController.text.trim(),
    );
    if (normalized == null ||
        (_draftConfig.chatTextColorMode == AppBackgroundTextColorMode.custom &&
            normalized == _draftConfig.chatTextHexColor)) {
      if (mounted) {
        setState(() {});
      }
      return;
    }
    _applyDraftConfig(
      _draftConfig.copyWith(
        chatTextColorMode: AppBackgroundTextColorMode.custom,
        chatTextHexColor: normalized,
      ),
    );
  }

  void _applyDraftConfig(AppBackgroundConfig nextConfig) {
    if (_sameConfig(_draftConfig, nextConfig)) {
      return;
    }
    setState(() {
      _draftConfig = nextConfig;
    });
    _scheduleDraftVisualProfileRefresh();
    _scheduleAutoSave();
  }

  Future<void> _pickLocalImage() async {
    try {
      final file = await FilePicker.pickFile(type: FileType.image);
      if (file == null) {
        return;
      }
      final path = file.path;
      if (path == null || path.isEmpty) {
        showToast('选择图片失败：无法读取图片路径', type: ToastType.error);
        return;
      }
      final importedPath = await AppBackgroundService.importLocalImage(path);
      final previousImported = _sessionImportedLocalPath;
      if (previousImported != null &&
          previousImported != _savedConfig.localImagePath &&
          previousImported != importedPath) {
        await AppBackgroundService.deleteManagedLocalImage(previousImported);
      }
      if (!mounted) {
        if (importedPath != _savedConfig.localImagePath) {
          await AppBackgroundService.deleteManagedLocalImage(importedPath);
        }
        return;
      }
      setState(() {
        _sessionImportedLocalPath = importedPath == _savedConfig.localImagePath
            ? null
            : importedPath;
        _draftConfig = _draftConfig.copyWith(
          enabled: true,
          sourceType: AppBackgroundSourceType.local,
          localImagePath: importedPath,
          remoteImageUrl: '',
        );
        _remoteUrlController.text = '';
      });
      _scheduleDraftVisualProfileRefresh();
      _scheduleAutoSave();
    } catch (error) {
      showToast('选择图片失败：$error', type: ToastType.error);
    }
  }

  void _setSourceType(AppBackgroundSourceType sourceType) {
    _applyDraftConfig(
      _draftConfig.copyWith(
        enabled: true,
        sourceType: sourceType,
        localImagePath: sourceType == AppBackgroundSourceType.local
            ? _draftConfig.localImagePath
            : '',
        remoteImageUrl: sourceType == AppBackgroundSourceType.remote
            ? _remoteUrlController.text.trim()
            : '',
      ),
    );
  }

  AppBackgroundConfig _normalizedDraft() {
    final remoteUrl = _remoteUrlController.text.trim();
    final sourceType = _draftConfig.sourceType;
    return _draftConfig.copyWith(
      remoteImageUrl: sourceType == AppBackgroundSourceType.remote
          ? remoteUrl
          : '',
      localImagePath: sourceType == AppBackgroundSourceType.local
          ? _draftConfig.localImagePath.trim()
          : '',
      enabled:
          _draftConfig.enabled &&
          (sourceType == AppBackgroundSourceType.local
              ? _draftConfig.localImagePath.trim().isNotEmpty
              : sourceType == AppBackgroundSourceType.remote
              ? remoteUrl.isNotEmpty
              : false),
    );
  }

  Future<String?> _validateConfig(AppBackgroundConfig config) async {
    final l10n = context.l10n;
    if (config.sourceType == AppBackgroundSourceType.local &&
        config.localImagePath.trim().isEmpty) {
      return l10n.appearancePickLocalImageFirst;
    }
    if (config.sourceType == AppBackgroundSourceType.local &&
        config.localImagePath.trim().isNotEmpty &&
        !await File(config.localImagePath).exists()) {
      return l10n.appearanceLocalImageMissing;
    }
    if (config.sourceType == AppBackgroundSourceType.remote) {
      final uri = Uri.tryParse(config.remoteImageUrl.trim());
      if (uri == null ||
          !(uri.scheme == 'http' || uri.scheme == 'https') ||
          (uri.host.isEmpty)) {
        return l10n.appearanceInvalidHttpUrl;
      }
    }
    return null;
  }

  void _scheduleAutoSave() {
    _autoSaveDebounceTimer?.cancel();
    final snapshot = _normalizedDraft();
    final requestId = ++_autoSaveRequestId;
    _autoSaveDebounceTimer = Timer(const Duration(milliseconds: 220), () {
      unawaited(_persistAutoSave(requestId, snapshot));
    });
  }

  Future<void> _persistAutoSave(
    int requestId,
    AppBackgroundConfig snapshot,
  ) async {
    final importedPath = _sessionImportedLocalPath;
    final validationError = await _validateConfig(snapshot);
    if (requestId != _autoSaveRequestId || !_appIsActive) return;
    if (validationError != null || _sameConfig(_savedConfig, snapshot)) {
      if (validationError == null) {
        await _cleanupUnsavedImportedImageIfNeeded(
          importedPath: importedPath,
          snapshot: snapshot,
        );
      }
      if (requestId == _autoSaveRequestId) {
        if (mounted) {
          setState(() => _saving = false);
        } else {
          _saving = false;
        }
      }
      return;
    }

    if (mounted && requestId == _autoSaveRequestId) {
      setState(() => _saving = true);
    } else if (!mounted) {
      _saving = true;
    }

    final previousSaved = _savedConfig;
    try {
      _serviceSaveTargets.add(snapshot);
      await AppBackgroundService.save(snapshot);
      if (requestId != _autoSaveRequestId) {
        return;
      }

      await _cleanupObsoleteLocalImages(
        previousSaved: previousSaved,
        snapshot: snapshot,
        importedPath: importedPath,
      );

      if (!mounted) {
        _savedConfig = snapshot;
        _draftConfig = snapshot;
        _sessionImportedLocalPath = null;
        return;
      }
      setState(() {
        _savedConfig = snapshot;
        _draftConfig = snapshot;
        _sessionImportedLocalPath = null;
      });
    } catch (error) {
      if (mounted && requestId == _autoSaveRequestId) {
        showToast('自动保存失败：$error', type: ToastType.error);
      }
    } finally {
      _serviceSaveTargets.remove(snapshot);
      if (mounted && requestId == _autoSaveRequestId) {
        setState(() => _saving = false);
      } else if (!mounted) {
        _saving = false;
      }
    }
  }

  Future<void> _flushPendingDraftOnDispose(AppBackgroundConfig snapshot) async {
    final importedPath = _sessionImportedLocalPath;
    final validationError = await _validateConfig(snapshot);
    if (validationError != null) {
      return;
    }
    if (_sameConfig(_savedConfig, snapshot)) {
      await _cleanupUnsavedImportedImageIfNeeded(
        importedPath: importedPath,
        snapshot: snapshot,
      );
      return;
    }

    final previousSaved = _savedConfig;
    try {
      _serviceSaveTargets.add(snapshot);
      await AppBackgroundService.save(snapshot);
      await _cleanupObsoleteLocalImages(
        previousSaved: previousSaved,
        snapshot: snapshot,
        importedPath: importedPath,
      );
      _savedConfig = snapshot;
      _draftConfig = snapshot;
      _sessionImportedLocalPath = null;
    } catch (_) {
      // Silently skip persistence failures while the page is disposing.
    } finally {
      _serviceSaveTargets.remove(snapshot);
    }
  }

  Future<void> _cleanupObsoleteLocalImages({
    required AppBackgroundConfig previousSaved,
    required AppBackgroundConfig snapshot,
    required String? importedPath,
  }) async {
    if (previousSaved.sourceType == AppBackgroundSourceType.local &&
        previousSaved.localImagePath.isNotEmpty &&
        previousSaved.localImagePath != snapshot.localImagePath) {
      await AppBackgroundService.deleteManagedLocalImage(
        previousSaved.localImagePath,
      );
    }
    await _cleanupUnsavedImportedImageIfNeeded(
      importedPath: importedPath,
      snapshot: snapshot,
    );
    _sessionImportedLocalPath = null;
  }

  Future<void> _cleanupUnsavedImportedImageIfNeeded({
    required String? importedPath,
    required AppBackgroundConfig snapshot,
  }) async {
    if (importedPath == null || importedPath == snapshot.localImagePath) {
      return;
    }
    await AppBackgroundService.deleteManagedLocalImage(importedPath);
    _sessionImportedLocalPath = null;
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(title: context.l10n.appearanceTitle, primary: true),
      body: SafeArea(
        top: false,
        bottom: false,
        child: SingleChildScrollView(
          padding: edgeToEdgeScrollPadding(
            context,
            const EdgeInsets.fromLTRB(16, 12, 16, 24),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (!widget.petOnly) ...[
                Padding(
                  padding: const EdgeInsets.only(left: 4, bottom: 12),
                  child: Text(
                    context.trLegacy(_autoSaveHint),
                    style: TextStyle(
                      fontSize: 12,
                      color: palette.textSecondary,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
                if (widget.showBasicPreferences) ...[
                  const ThemeModeSettingCard(),
                  const SizedBox(height: 18),
                  _buildLanguageSettingCard(),
                  const SizedBox(height: 18),
                ],
                SettingsSectionTitle(
                  label: context.l10n.appearanceBackgroundSource,
                ),
                _buildSourceCard(),
                const SizedBox(height: 18),
                SettingsSectionTitle(label: context.l10n.appearancePreview),
                _buildPreviewCard(),
                const SizedBox(height: 18),
                SettingsSectionTitle(label: context.l10n.appearanceAdjustments),
                _buildAdjustCard(),
              ],
              if (_showPetSection) ...[
                if (!widget.petOnly) const SizedBox(height: 18),
                SettingsSectionTitle(label: '宠物'),
                _buildPetCard(),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPreviewCard() {
    return _buildCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 8,
            children: BackgroundPreviewKind.values.map((kind) {
              final selected = _previewKind == kind;
              final label = kind == BackgroundPreviewKind.chat
                  ? context.l10n.appearancePreviewChat
                  : context.l10n.appearancePreviewWorkspace;
              return ChoiceChip(
                key: ValueKey('background-preview-kind-${kind.name}'),
                label: Text(label),
                selected: selected,
                onSelected: (_) {
                  setState(() => _previewKind = kind);
                },
              );
            }).toList(),
          ),
          const SizedBox(height: 12),
          AppBackgroundPreview(
            config: _previewConfig,
            kind: _previewKind,
            visualProfile: _draftVisualProfile,
            showDragHint: true,
            onViewportChanged: (offset, imageScale) {
              _applyDraftConfig(
                _draftConfig.copyWith(
                  focalX: offset.dx,
                  focalY: offset.dy,
                  imageScale: imageScale,
                ),
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _buildLanguageSettingCard() {
    return Consumer(
      builder: (context, ref, child) {
        final mode = ref.watch(appLanguageModeProvider);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SettingsSectionTitle(
              label: context.l10n.languageTitle,
              subtitle: context.l10n.languageSubtitle,
              bottomPadding: 10,
            ),
            OmniSegmentedSlider<AppLanguageMode>(
              key: const ValueKey('language-mode-slider'),
              value: mode,
              keyPrefix: 'language-mode-option',
              options: [
                OmniSegmentedOption<AppLanguageMode>(
                  value: AppLanguageMode.system,
                  label: context.l10n.languageFollowSystem,
                  icon: LucideIcons.smartphone,
                  id: 'system',
                ),
                OmniSegmentedOption<AppLanguageMode>(
                  value: AppLanguageMode.zhHans,
                  label: context.l10n.languageZhHans,
                  icon: LucideIcons.languages,
                  id: 'zhHans',
                ),
                OmniSegmentedOption<AppLanguageMode>(
                  value: AppLanguageMode.en,
                  label: context.l10n.languageEnglish,
                  icon: LucideIcons.languages,
                  id: 'en',
                ),
              ],
              onChanged: (nextMode) async {
                try {
                  await ref
                      .read(appLanguageModeProvider.notifier)
                      .setLanguageMode(nextMode);
                } catch (_) {
                  if (context.mounted)
                    showToast(context.trLegacy('设置失败'), type: ToastType.error);
                }
              },
            ),
          ],
        );
      },
    );
  }

  Widget _buildSourceCard() {
    final palette = context.omniPalette;
    final localPath = _draftConfig.localImagePath.trim();
    final sourceType = _draftConfig.sourceType;
    return _buildCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SwitchListTile.adaptive(
            key: const ValueKey('appearance-background-enable-switch'),
            contentPadding: EdgeInsets.zero,
            secondary: _buildGlobalSettingLeadingIcon(icon: LucideIcons.image),
            title: Text(
              context.l10n.appearanceEnableBackground,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: palette.textPrimary,
              ),
            ),
            subtitle: Text(
              context.l10n.appearanceEnableBackgroundSubtitle,
              style: TextStyle(fontSize: 12, color: palette.textSecondary),
            ),
            value: _draftConfig.enabled,
            onChanged: (value) {
              _applyDraftConfig(_draftConfig.copyWith(enabled: value));
            },
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              ChoiceChip(
                key: const ValueKey('background-source-local'),
                label: Text(context.l10n.appearanceSourceLocal),
                selected: sourceType == AppBackgroundSourceType.local,
                onSelected: (_) =>
                    _setSourceType(AppBackgroundSourceType.local),
              ),
              ChoiceChip(
                key: const ValueKey('background-source-remote'),
                label: Text(context.l10n.appearanceSourceRemote),
                selected: sourceType == AppBackgroundSourceType.remote,
                onSelected: (_) =>
                    _setSourceType(AppBackgroundSourceType.remote),
              ),
            ],
          ),
          if (sourceType == AppBackgroundSourceType.local) ...[
            const SizedBox(height: 12),
            Text(
              localPath.isEmpty
                  ? context.l10n.appearanceNoLocalImage
                  : localPath,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 12, color: palette.textSecondary),
            ),
            const SizedBox(height: 10),
            OutlinedButton.icon(
              key: const ValueKey('background-pick-local-image'),
              onPressed: _pickLocalImage,
              icon: const Icon(LucideIcons.images),
              label: Text(
                localPath.isEmpty
                    ? context.l10n.appearancePickImage
                    : context.l10n.appearanceRepickImage,
              ),
            ),
          ],
          if (sourceType == AppBackgroundSourceType.remote) ...[
            const SizedBox(height: 12),
            TextField(
              key: const ValueKey('background-remote-url-field'),
              controller: _remoteUrlController,
              decoration: InputDecoration(
                labelText: context.l10n.appearanceRemoteImageUrl,
                hintText: context.l10n.appearanceRemoteImageUrlHint,
                border: OutlineInputBorder(),
                errorText: _remoteUrlErrorText,
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildAdjustCard() {
    final palette = context.omniPalette;
    return _buildCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildSliderRow(
            label: context.l10n.appearanceBackgroundBlur,
            subtitle: context.l10n.appearanceBackgroundBlurSubtitle,
            value: _draftConfig.blurSigma,
            min: 0,
            max: 24,
            onChanged: (value) {
              _applyDraftConfig(_draftConfig.copyWith(blurSigma: value));
            },
          ),
          _buildSliderRow(
            label: context.l10n.appearanceOverlayIntensity,
            subtitle: context.l10n.appearanceOverlayIntensitySubtitle,
            value: _draftConfig.frostOpacity,
            min: 0,
            max: 0.55,
            onChanged: (value) {
              _applyDraftConfig(_draftConfig.copyWith(frostOpacity: value));
            },
          ),
          _buildSliderRow(
            label: context.l10n.appearanceOverlayBrightness,
            subtitle: context.l10n.appearanceOverlayBrightnessSubtitle,
            value: _draftConfig.brightness,
            min: 0.5,
            max: 1.5,
            onChanged: (value) {
              _applyDraftConfig(_draftConfig.copyWith(brightness: value));
            },
          ),
          _buildSliderRow(
            label: context.l10n.appearanceChatTextSize,
            subtitle: context.l10n.appearanceChatTextSizeSubtitle,
            value: _draftConfig.chatTextSize,
            min: 12,
            max: 22,
            valueFormatter: (value) => '${value.toStringAsFixed(1)}sp',
            onChanged: (value) {
              _applyDraftConfig(_draftConfig.copyWith(chatTextSize: value));
            },
          ),
          const SizedBox(height: 8),
          _buildTextColorSection(),
          const SizedBox(height: 6),
          Text(
            context.l10n.appearancePreviewTip,
            style: TextStyle(fontSize: 12, color: palette.textSecondary),
          ),
        ],
      ),
    );
  }

  Widget _buildGlobalSettingLeadingIcon({IconData? icon, Widget? child}) {
    final palette = context.omniPalette;
    return SizedBox(
      width: 18,
      height: 18,
      child:
          child ??
          (icon != null
              ? Icon(icon, size: 18, color: palette.textPrimary)
              : const SizedBox.shrink()),
    );
  }

  Future<void> _loadPetSettings() async {
    try {
      final snapshot = await OverlayService.getPetAppearanceState();
      if (mounted) _applyPetSnapshot(snapshot);
    } catch (error) {
      if (mounted) showToast('读取桌宠列表失败：$error', type: ToastType.error);
    }
  }

  void _applyPetSnapshot(Map<String, dynamic> snapshot) {
    final entries = (snapshot['options'] as List?) ?? const [];
    final options = entries
        .whereType<Map>()
        .map((raw) {
          final item = raw.map((key, value) => MapEntry(key.toString(), value));
          return _OverlayPetOption(
            id: item['id']?.toString() ?? '',
            name: item['name']?.toString() ?? '',
            description: item['description']?.toString() ?? '',
            imagePath: item['imagePath']?.toString() ?? '',
            isBuiltin: item['isBuiltin'] == true,
            animationLabel: item['animationLabel']?.toString() ?? '',
          );
        })
        .where((option) => option.id.isNotEmpty)
        .toList(growable: false);
    setState(() {
      _petOptions = options.isEmpty
          ? const [
              _OverlayPetOption(
                id: 'builtin:xiaowan',
                name: '小万',
                description: '默认的桌面悬浮窗宠物',
                imagePath: '',
                isBuiltin: true,
              ),
            ]
          : options;
      _selectedPetId = snapshot['selectedId']?.toString() ?? 'builtin:xiaowan';
    });
  }

  Future<void> _importPetPackage() async {
    if (_petBusy) return;
    setState(() => _petBusy = true);
    try {
      final selectedFile = await FilePicker.pickFile(
        type: FileType.custom,
        allowedExtensions: const ['zip'],
      );
      if (selectedFile == null) return;
      final path = selectedFile.path;
      if (path == null || path.isEmpty) throw StateError('无法读取宠物压缩包');
      final snapshot = await OverlayService.importPetPackage(path);
      if (!mounted) return;
      _applyPetSnapshot(snapshot);
      showToast(context.trLegacy('宠物已导入并选中'), type: ToastType.success);
    } catch (error) {
      if (mounted)
        showToast(
          '${context.trLegacy('导入宠物失败')}：$error',
          type: ToastType.error,
        );
    } finally {
      if (mounted) setState(() => _petBusy = false);
    }
  }

  Future<void> _refreshPetOptions() async {
    try {
      final snapshot = await OverlayService.getPetAppearanceState();
      if (!mounted) return;
      final selectedId =
          snapshot['selectedId']?.toString() ?? 'builtin:xiaowan';
      _applyPetSnapshot(await OverlayService.selectPetAppearance(selectedId));
      if (mounted)
        showToast(context.trLegacy('宠物列表已刷新'), type: ToastType.success);
    } catch (error) {
      if (mounted) showToast('刷新桌宠列表失败：$error', type: ToastType.error);
    }
  }

  Future<void> _selectPet(_OverlayPetOption option) async {
    if (_petBusy) return;
    setState(() => _petBusy = true);
    try {
      final snapshot = await OverlayService.selectPetAppearance(option.id);
      if (mounted) _applyPetSnapshot(snapshot);
    } catch (error) {
      if (mounted) showToast(context.trLegacy('选择宠物失败'), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _petBusy = false);
    }
  }

  Widget _buildPetCard() {
    final palette = context.omniPalette;
    final selectedOption = _petOptions.firstWhere(
      (option) => option.id == _selectedPetId,
      orElse: () => _petOptions.first,
    );
    return DecoratedBox(
      key: const ValueKey('appearance-pet-card'),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        children: [
          InkWell(
            borderRadius: const BorderRadius.vertical(top: Radius.circular(8)),
            onTap: () {
              final willExpand = !_petExpanded;
              setState(() => _petExpanded = willExpand);
              if (willExpand) {
                unawaited(_loadPetSettings());
              }
            },
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 14, 16),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          context.trLegacy('宠物'),
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                            color: palette.textPrimary,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          '${context.trLegacy('已选')}：${selectedOption.name}',
                          style: TextStyle(
                            fontSize: 13,
                            color: palette.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  Tooltip(
                    message: context.trLegacy('刷新'),
                    child: IconButton(
                      onPressed: _petBusy ? null : _refreshPetOptions,
                      icon: const Icon(LucideIcons.refreshCw),
                      color: palette.textSecondary,
                    ),
                  ),
                  const SizedBox(width: 4),
                  Icon(
                    _petExpanded
                        ? LucideIcons.chevronUp
                        : LucideIcons.chevronDown,
                    color: palette.textSecondary,
                  ),
                ],
              ),
            ),
          ),
          AnimatedCrossFade(
            firstChild: const SizedBox.shrink(),
            secondChild: Column(
              children: [
                Divider(height: 1, color: palette.borderSubtle),
                _buildPetImportTile(),
                ..._petOptions.map(_buildPetOptionTile),
              ],
            ),
            crossFadeState: _petExpanded
                ? CrossFadeState.showSecond
                : CrossFadeState.showFirst,
            duration: const Duration(milliseconds: 180),
          ),
        ],
      ),
    );
  }

  Widget _buildPetImportTile() {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
      child: Row(
        children: [
          Icon(LucideIcons.fileArchive, size: 22, color: palette.textSecondary),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  context.trLegacy('导入 Codex 宠物包'),
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  context.trLegacy(
                    '支持 .codex-pet.zip（pet.json + spritesheet.webp）',
                  ),
                  style: TextStyle(fontSize: 12, color: palette.textSecondary),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          OutlinedButton.icon(
            key: const ValueKey('pet-package-import-button'),
            onPressed: _petBusy ? null : _importPetPackage,
            icon: const Icon(LucideIcons.fileUp, size: 18),
            label: Text(context.trLegacy('上传压缩包')),
          ),
        ],
      ),
    );
  }

  Widget _buildPetOptionTile(_OverlayPetOption option) {
    final palette = context.omniPalette;
    final selected = option.id == _selectedPetId;
    return DecoratedBox(
      decoration: BoxDecoration(
        border: Border(top: BorderSide(color: palette.borderSubtle)),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
        child: Row(
          children: [
            _buildPetPreview(option),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    option.name,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                      color: palette.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    context.trLegacy(option.description),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      color: palette.textSecondary,
                    ),
                  ),
                  if (option.animationLabel.isNotEmpty) ...[
                    const SizedBox(height: 5),
                    Text(
                      context.trLegacy(option.animationLabel),
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                        color: palette.accentPrimary,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: 12),
            FilledButton.tonal(
              onPressed: selected || _petBusy ? null : () => _selectPet(option),
              child: Text(context.trLegacy(selected ? '已选' : '选择')),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPetPreview(_OverlayPetOption option) {
    final palette = context.omniPalette;
    final imageFile = option.isBuiltin ? null : File(option.imagePath);
    final imageStamp = imageFile != null && imageFile.existsSync()
        ? imageFile.lastModifiedSync().millisecondsSinceEpoch
        : 0;
    final isSvg = option.imagePath.toLowerCase().endsWith('.svg');
    return Container(
      width: 58,
      height: 58,
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      clipBehavior: Clip.antiAlias,
      child: Padding(
        padding: const EdgeInsets.all(6),
        child: option.isBuiltin
            ? Image.asset(
                'assets/avatar/default_avatar1.png',
                bundle: rootBundle,
                fit: BoxFit.contain,
                errorBuilder: (_, __, ___) =>
                    Icon(LucideIcons.pawPrint, color: palette.textSecondary),
              )
            : isSvg
            ? SvgPicture.file(
                imageFile!,
                key: ValueKey('${option.imagePath}:$imageStamp'),
                fit: BoxFit.contain,
                placeholderBuilder: (_) =>
                    Icon(LucideIcons.pawPrint, color: palette.textSecondary),
              )
            : Image.file(
                imageFile!,
                key: ValueKey('${option.imagePath}:$imageStamp'),
                fit: BoxFit.contain,
                errorBuilder: (_, __, ___) =>
                    Icon(LucideIcons.imageOff, color: palette.textSecondary),
              ),
      ),
    );
  }

  Widget _buildSliderRow({
    required String label,
    required String subtitle,
    required double value,
    required double min,
    required double max,
    required ValueChanged<double> onChanged,
    String Function(double value)? valueFormatter,
  }) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
                context.trLegacy(label),
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: palette.textPrimary,
                ),
              ),
              const Spacer(),
              Text(
                valueFormatter?.call(value) ?? value.toStringAsFixed(2),
                style: TextStyle(fontSize: 12, color: palette.textSecondary),
              ),
            ],
          ),
          const SizedBox(height: 2),
          Text(
            context.trLegacy(subtitle),
            style: TextStyle(fontSize: 12, color: palette.textSecondary),
          ),
          Slider(value: value, min: min, max: max, onChanged: onChanged),
        ],
      ),
    );
  }

  Widget _buildTextColorSection() {
    final palette = context.omniPalette;
    final selectedHex = normalizeAppBackgroundHexColor(
      _draftConfig.chatTextHexColor,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          context.l10n.appearanceTextColorTitle,
          style: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            color: palette.textPrimary,
          ),
        ),
        const SizedBox(height: 2),
        Text(
          context.l10n.appearanceTextColorSubtitle,
          style: TextStyle(fontSize: 12, color: palette.textSecondary),
        ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 10,
          runSpacing: 10,
          children: [
            ChoiceChip(
              key: const ValueKey('appearance-text-color-auto'),
              label: Text(context.l10n.appearanceTextColorAuto),
              selected:
                  _draftConfig.chatTextColorMode ==
                  AppBackgroundTextColorMode.auto,
              onSelected: (_) {
                _textColorController.text = '';
                _applyDraftConfig(
                  _draftConfig.copyWith(
                    chatTextColorMode: AppBackgroundTextColorMode.auto,
                    chatTextHexColor: '',
                  ),
                );
              },
            ),
            ..._kAppearanceTextColorPresets.map((preset) {
              final selected =
                  _draftConfig.chatTextColorMode ==
                      AppBackgroundTextColorMode.custom &&
                  selectedHex == preset.hex;
              return InkWell(
                key: ValueKey('appearance-text-color-${preset.hex}'),
                onTap: () {
                  _textColorController.text = preset.hex;
                  _applyDraftConfig(
                    _draftConfig.copyWith(
                      chatTextColorMode: AppBackgroundTextColorMode.custom,
                      chatTextHexColor: preset.hex,
                    ),
                  );
                },
                borderRadius: BorderRadius.circular(999),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 160),
                  width: 34,
                  height: 34,
                  decoration: BoxDecoration(
                    color: preset.color,
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: selected
                          ? (context.isDarkTheme
                                ? palette.accentPrimary
                                : AppColors.primaryBlue)
                          : palette.borderStrong,
                      width: selected ? 3 : 1.5,
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: 0.06),
                        blurRadius: 8,
                        offset: const Offset(0, 2),
                      ),
                    ],
                  ),
                ),
              );
            }),
          ],
        ),
        const SizedBox(height: 12),
        TextField(
          key: const ValueKey('appearance-text-color-field'),
          controller: _textColorController,
          decoration: InputDecoration(
            labelText: context.l10n.appearanceCustomColorLabel,
            hintText: context.l10n.appearanceCustomColorHint,
            border: const OutlineInputBorder(),
            errorText: _textColorErrorText,
          ),
        ),
      ],
    );
  }

  Widget _buildCard({required Widget child}) {
    return SizedBox(width: double.infinity, child: child);
  }

  void _scheduleDraftVisualProfileRefresh() {
    final previewConfig = _previewConfig;
    _previewProfileDebounceTimer?.cancel();
    final fallbackProfile = AppBackgroundVisualProfile.derive(
      config: previewConfig,
    );
    if (mounted) {
      setState(() {
        _draftVisualProfile = fallbackProfile;
      });
    } else {
      _draftVisualProfile = fallbackProfile;
    }
    final token = ++_previewProfileToken;
    _previewProfileDebounceTimer = Timer(const Duration(milliseconds: 140), () {
      unawaited(_refreshDraftVisualProfile(token, previewConfig));
    });
  }

  Future<void> _refreshDraftVisualProfile(
    int token,
    AppBackgroundConfig previewConfig,
  ) async {
    final analyzed = await AppBackgroundService.analyzeVisualProfile(
      previewConfig,
    );
    if (!mounted || token != _previewProfileToken) {
      return;
    }
    setState(() {
      _draftVisualProfile = analyzed;
    });
  }
}
