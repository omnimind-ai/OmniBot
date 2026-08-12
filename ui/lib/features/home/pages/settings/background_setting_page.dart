import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

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
import 'package:ui/services/pet_package_service.dart';
import 'package:ui/services/storage_service.dart';
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

class _PendingBackgroundSave {
  final AppBackgroundConfig snapshot;
  final String? importedPath;

  const _PendingBackgroundSave({
    required this.snapshot,
    required this.importedPath,
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
  const BackgroundSettingPage({super.key});

  @override
  State<BackgroundSettingPage> createState() => _BackgroundSettingPageState();
}

class _BackgroundSettingPageState extends State<BackgroundSettingPage> {
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
  Timer? _petRefreshTimer;
  int _previewProfileToken = 0;
  _PendingBackgroundSave? _pendingAutoSave;
  bool _autoSaveWriterRunning = false;
  bool _isDisposing = false;
  bool _petExpanded = false;
  bool _petBusy = false;
  String _petsDirectoryPath = '';
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
    _savedConfig = AppBackgroundService.current;
    _draftConfig = _savedConfig;
    _draftVisualProfile = AppBackgroundService.currentVisualProfile;
    _remoteUrlController.text = _draftConfig.remoteImageUrl;
    _textColorController.text = _draftConfig.chatTextHexColor;
    _remoteUrlController.addListener(_handleRemoteUrlChanged);
    _textColorController.addListener(_handleTextColorChanged);
    _scheduleDraftVisualProfileRefresh();
    if (_showPetAppearanceSettings) {
      unawaited(_loadPetSettings());
      _petRefreshTimer = Timer.periodic(const Duration(seconds: 4), (_) {
        if (!mounted || !_petExpanded || _petBusy) return;
        unawaited(_loadPetSettings());
      });
    }
  }

  @override
  void dispose() {
    final pendingSnapshot = _normalizedDraft();
    _isDisposing = true;
    final shouldFlushPendingDraft =
        _autoSaveWriterRunning ||
        _pendingAutoSave != null ||
        !_sameConfig(_savedConfig, pendingSnapshot) ||
        _hasUnsavedImportedLocalImage(pendingSnapshot);
    _previewProfileDebounceTimer?.cancel();
    _autoSaveDebounceTimer?.cancel();
    _petRefreshTimer?.cancel();
    if (shouldFlushPendingDraft) {
      _enqueueAutoSave(pendingSnapshot);
    }
    _remoteUrlController
      ..removeListener(_handleRemoteUrlChanged)
      ..dispose();
    _textColorController
      ..removeListener(_handleTextColorChanged)
      ..dispose();
    super.dispose();
  }

  void _handleRemoteUrlChanged() {
    final nextUrl = _remoteUrlController.text.trim();
    if (_draftConfig.sourceType != AppBackgroundSourceType.remote ||
        nextUrl == _draftConfig.remoteImageUrl) {
      return;
    }
    _applyDraftConfig(_draftConfig.copyWith(remoteImageUrl: nextUrl));
  }

  void _handleTextColorChanged() {
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
      final result = await FilePicker.platform.pickFiles(
        type: FileType.image,
        allowMultiple: false,
      );
      if (result == null || result.files.isEmpty) {
        return;
      }
      final path = result.files.single.path;
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

  Future<bool> _isConfigValid(AppBackgroundConfig config) async {
    if (config.sourceType == AppBackgroundSourceType.local &&
        config.localImagePath.trim().isEmpty) {
      return false;
    }
    if (config.sourceType == AppBackgroundSourceType.local &&
        config.localImagePath.trim().isNotEmpty &&
        !await File(config.localImagePath).exists()) {
      return false;
    }
    if (config.sourceType == AppBackgroundSourceType.remote) {
      final uri = Uri.tryParse(config.remoteImageUrl.trim());
      if (uri == null ||
          !(uri.scheme == 'http' || uri.scheme == 'https') ||
          (uri.host.isEmpty)) {
        return false;
      }
    }
    return true;
  }

  void _scheduleAutoSave() {
    _autoSaveDebounceTimer?.cancel();
    final snapshot = _normalizedDraft();
    _autoSaveDebounceTimer = Timer(const Duration(milliseconds: 220), () {
      _enqueueAutoSave(snapshot);
    });
  }

  void _enqueueAutoSave(AppBackgroundConfig snapshot) {
    _pendingAutoSave = _PendingBackgroundSave(
      snapshot: snapshot,
      importedPath: _sessionImportedLocalPath,
    );
    if (_autoSaveWriterRunning) {
      return;
    }
    _autoSaveWriterRunning = true;
    unawaited(_drainAutoSaveQueue());
  }

  Future<void> _drainAutoSaveQueue() async {
    if (mounted && !_isDisposing) {
      setState(() => _saving = true);
    } else {
      _saving = true;
    }

    try {
      while (true) {
        final pending = _pendingAutoSave;
        if (pending == null) {
          break;
        }
        _pendingAutoSave = null;
        await _persistAutoSave(pending);
      }
    } finally {
      _autoSaveWriterRunning = false;
      if (mounted && !_isDisposing) {
        setState(() => _saving = false);
      } else {
        _saving = false;
      }

      // A snapshot may be queued just as the loop finishes. Restart the
      // single writer so that the latest selection is never dropped.
      if (_pendingAutoSave != null && !_autoSaveWriterRunning) {
        _autoSaveWriterRunning = true;
        unawaited(_drainAutoSaveQueue());
      }
    }
  }

  Future<void> _persistAutoSave(_PendingBackgroundSave pending) async {
    final snapshot = pending.snapshot;
    final importedPath = pending.importedPath;
    final isValid = await _isConfigValid(snapshot);
    if (!isValid || _sameConfig(_savedConfig, snapshot)) {
      if (isValid) {
        await _cleanupUnsavedImportedImageIfNeeded(
          importedPath: importedPath,
          snapshot: snapshot,
        );
      }
      return;
    }

    final previousSaved = _savedConfig;
    try {
      await AppBackgroundService.save(snapshot);
      await _cleanupObsoleteLocalImages(
        previousSaved: previousSaved,
        snapshot: snapshot,
        importedPath: importedPath,
      );

      final snapshotIsStillLatest =
          _pendingAutoSave == null && _sameConfig(_draftConfig, snapshot);
      if (mounted && !_isDisposing && snapshotIsStillLatest) {
        setState(() {
          _savedConfig = snapshot;
          _draftConfig = snapshot;
          if (_sessionImportedLocalPath == importedPath) {
            _sessionImportedLocalPath = null;
          }
        });
      } else {
        _savedConfig = snapshot;
        if (snapshotIsStillLatest &&
            _sessionImportedLocalPath == importedPath) {
          _sessionImportedLocalPath = null;
        }
      }
    } catch (error) {
      if (mounted && !_isDisposing && _pendingAutoSave == null) {
        showToast('自动保存失败：$error', type: ToastType.error);
      }
    }
  }

  Future<void> _cleanupObsoleteLocalImages({
    required AppBackgroundConfig previousSaved,
    required AppBackgroundConfig snapshot,
    required String? importedPath,
  }) async {
    final latestPath = _pendingAutoSave?.snapshot.localImagePath;
    final draftPath = _draftConfig.sourceType == AppBackgroundSourceType.local
        ? _draftConfig.localImagePath
        : null;
    if (previousSaved.sourceType == AppBackgroundSourceType.local &&
        previousSaved.localImagePath.isNotEmpty &&
        previousSaved.localImagePath != snapshot.localImagePath &&
        previousSaved.localImagePath != latestPath &&
        previousSaved.localImagePath != draftPath) {
      await AppBackgroundService.deleteManagedLocalImage(
        previousSaved.localImagePath,
      );
    }
    await _cleanupUnsavedImportedImageIfNeeded(
      importedPath: importedPath,
      snapshot: snapshot,
    );
  }

  Future<void> _cleanupUnsavedImportedImageIfNeeded({
    required String? importedPath,
    required AppBackgroundConfig snapshot,
  }) async {
    final latestPath = _pendingAutoSave?.snapshot.localImagePath;
    final draftPath = _draftConfig.sourceType == AppBackgroundSourceType.local
        ? _draftConfig.localImagePath
        : null;
    if (importedPath == null ||
        importedPath == snapshot.localImagePath ||
        importedPath == latestPath ||
        importedPath == draftPath) {
      return;
    }
    await AppBackgroundService.deleteManagedLocalImage(importedPath);
    if (_sessionImportedLocalPath == importedPath) {
      _sessionImportedLocalPath = null;
    }
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
              const ThemeModeSettingCard(),
              const SizedBox(height: 18),
              _buildLanguageSettingCard(),
              const SizedBox(height: 18),
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
              if (_showPetAppearanceSettings) ...[
                const SizedBox(height: 18),
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
              onChanged: (nextMode) {
                ref
                    .read(appLanguageModeProvider.notifier)
                    .setLanguageMode(nextMode);
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
    final nativeState = await OverlayService.getPetOverlayState();
    final nativeSelectedPath = nativeState['selectedPath']?.toString() ?? '';
    final workspaceRoot =
        nativeState['workspaceRootPath']?.toString().trim() ?? '';
    final petsDirectoryPath =
        nativeState['petsDirectoryPath']?.toString().trim().isNotEmpty == true
        ? nativeState['petsDirectoryPath'].toString().trim()
        : workspaceRoot.isEmpty
        ? ''
        : '$workspaceRoot/.omnibot/pets';
    final selectedPath = nativeSelectedPath.isNotEmpty
        ? nativeSelectedPath
        : StorageService.getPetOverlayImagePath();
    final selectedId =
        nativeState['selectedId']?.toString().trim().isNotEmpty == true
        ? nativeState['selectedId'].toString()
        : StorageService.getPetOverlaySelectedId();
    final options = await _scanPetOptions(
      workspaceRoot: workspaceRoot,
      selectedPath: selectedPath,
    );
    if (!mounted) return;
    setState(() {
      _petsDirectoryPath = petsDirectoryPath;
      _selectedPetId = _resolveSelectedPetId(
        selectedId: selectedId,
        selectedPath: selectedPath,
        options: options,
      );
      _petOptions = options;
    });
  }

  Future<List<_OverlayPetOption>> _scanPetOptions({
    required String workspaceRoot,
    required String selectedPath,
  }) async {
    final options = <_OverlayPetOption>[
      const _OverlayPetOption(
        id: 'builtin:xiaowan',
        name: '小万',
        description: '默认的桌面悬浮窗宠物',
        imagePath: '',
        isBuiltin: true,
      ),
    ];
    final root = workspaceRoot.trim();
    if (root.isEmpty) {
      return options;
    }
    final petDirs = [Directory('$root/.omnibot/pets'), Directory('$root/pets')];
    final existingPetDirs = <Directory>[];
    for (final dir in petDirs) {
      if (await dir.exists()) {
        existingPetDirs.add(dir);
      }
    }
    final discovered = <String, File>{};
    for (final petsDir in existingPetDirs) {
      final topLevelCurrent =
          await _firstPetImageFromMetadata(petsDir, root) ??
          await _preferredCurrentPetImageIn(petsDir);
      if (topLevelCurrent != null) {
        _putPreferredDiscoveredPet(discovered, 'current', topLevelCurrent);
      }
      final topLevel = await petsDir.list(followLinks: false).toList();
      for (final entity in topLevel) {
        if (entity is File && _isGeneratedPetPreviewFile(entity.path)) {
          continue;
        }
        if (entity is File && await _isUsablePetImage(entity)) {
          if (_isPreferredPetFileName(entity.path)) {
            continue;
          }
          if (_looksLikePetAtlas(entity.path) ||
              _isAnimationStatePetFile(entity.path)) {
            continue;
          }
          _putPreferredDiscoveredPet(
            discovered,
            _loosePetIdForPath(entity.path, root),
            entity,
          );
        } else if (entity is File &&
            !_isActivePetAlias(entity.path) &&
            !_isAnimationStatePetFile(entity.path) &&
            await _isUsablePetSvg(entity)) {
          final preview = await _materializeSvgPetImage(entity);
          if (preview != null) {
            _putPreferredDiscoveredPet(
              discovered,
              _loosePetIdForPath(entity.path, root),
              preview,
            );
          }
        } else if (entity is Directory) {
          if (_pathBaseName(entity.path).startsWith('.')) {
            continue;
          }
          final preview = await _firstSupportedPetImageIn(entity, root);
          if (preview != null) {
            _putPreferredDiscoveredPet(
              discovered,
              _petDiscoveryKeyForDirectory(entity, root),
              preview,
            );
          }
        }
      }
    }
    if (selectedPath.isNotEmpty) {
      final selectedFile = File(
        _resolveWorkspaceDisplayPath(selectedPath, root),
      );
      if (await _isUsablePetImage(selectedFile)) {
        discovered[_petIdForPath(selectedFile.path, root)] = selectedFile;
      }
    }

    final dedupedDiscovered = <String, File>{};
    for (final file in discovered.values) {
      _putPreferredDiscoveredPet(
        dedupedDiscovered,
        _petDiscoveryKeyForFile(file, root),
        file,
      );
    }
    final customOptions = dedupedDiscovered.values.toList()
      ..sort((left, right) {
        final timeCompare = _petSortTimestamp(
          left,
        ).compareTo(_petSortTimestamp(right));
        if (timeCompare != 0) {
          return timeCompare;
        }
        return _petNameForFile(
          left,
          root,
        ).toLowerCase().compareTo(_petNameForFile(right, root).toLowerCase());
      });
    for (final file in customOptions) {
      options.add(await _customPetOptionForFile(file, root));
    }
    return options;
  }

  void _putPreferredDiscoveredPet(
    Map<String, File> discovered,
    String key,
    File file,
  ) {
    final existing = discovered[key];
    if (existing == null ||
        _petCandidateRank(file) < _petCandidateRank(existing)) {
      discovered[key] = file;
    }
  }

  String _petDiscoveryKeyForDirectory(
    Directory directory,
    String workspaceRoot,
  ) {
    final parentPath = _normalizePath(directory.parent.path);
    final normalizedRoot = _normalizePath(workspaceRoot);
    if (parentPath == _normalizePath('$normalizedRoot/.omnibot/pets') ||
        parentPath == _normalizePath('$normalizedRoot/pets')) {
      final directoryName = _pathBaseName(directory.path).toLowerCase();
      if (directoryName.isNotEmpty) {
        return 'custom:$directoryName';
      }
    }
    return 'custom:${_normalizePath(directory.path).toLowerCase()}';
  }

  String _petDiscoveryKeyForFile(File file, String workspaceRoot) {
    final source = _sourceFileForGeneratedPetPreview(file);
    final normalizedRoot = _normalizePath(workspaceRoot);
    final petRootDirs = {
      _normalizePath('$normalizedRoot/.omnibot/pets'),
      _normalizePath('$normalizedRoot/pets'),
    };
    final parentParentPath = _normalizePath(source.parent.parent.path);
    if (petRootDirs.contains(parentParentPath)) {
      final directoryName = _pathBaseName(source.parent.path).toLowerCase();
      if (directoryName.isNotEmpty) {
        return 'custom:$directoryName';
      }
    }
    return 'custom:${_petIdentityBaseName(source).toLowerCase()}';
  }

  Future<_OverlayPetOption> _customPetOptionForFile(
    File file,
    String workspaceRoot,
  ) async {
    final metadata = await _readPetMetadataFor(file);
    final metadataName =
        metadata['displayName'] ?? metadata['display_name'] ?? metadata['name'];
    final metadataDescription = _petDescriptionFromMetadata(metadata);
    final name = metadataName == null || metadataName.trim().isEmpty
        ? _petNameForFile(file, workspaceRoot)
        : metadataName.trim();
    final description =
        metadataDescription == null || metadataDescription.trim().isEmpty
        ? _petDescriptionForFile(file, workspaceRoot)
        : metadataDescription.trim();
    return _OverlayPetOption(
      id: _petIdForPath(file.path, workspaceRoot),
      name: name,
      description: description,
      imagePath: file.path,
      isBuiltin: false,
      animationLabel:
          _looksLikePetAtlas(_sourceFileForGeneratedPetPreview(file).path)
          ? 'Codex 动态宠物 · 7 类动作'
          : '',
    );
  }

  Future<Map<String, String>> _readPetMetadataFor(File file) async {
    final sourceFile = _sourceFileForGeneratedPetPreview(file);
    final candidates = <File>[
      File('${file.parent.path}${Platform.pathSeparator}pet.json'),
      File(
        '${sourceFile.parent.path}${Platform.pathSeparator}${_baseNameWithoutExtension(sourceFile)}.json',
      ),
    ];
    for (final candidate in candidates) {
      if (!await candidate.exists()) continue;
      try {
        final decoded = jsonDecode(await candidate.readAsString());
        if (decoded is! Map) continue;
        return decoded.map(
          (key, value) => MapEntry(key.toString(), value?.toString() ?? ''),
        );
      } catch (_) {
        return const {};
      }
    }
    return _readPetMarkdownMetadataFor(sourceFile);
  }

  String? _petDescriptionFromMetadata(Map<String, String> metadata) {
    final petType = _firstMetadataValue(metadata, [
      'petType',
      'pet_type',
      'type',
      '宠物类型',
      '类型',
    ]);
    final visualStyle = _firstMetadataValue(metadata, [
      'visualStyle',
      'visual_style',
      'style',
      '视觉风格',
      '风格',
    ]);
    final personality = _firstMetadataValue(metadata, [
      'personality',
      'personalitySetting',
      'personality_setting',
      '性格设定',
      '性格',
    ]);
    final summaryParts = <String>[
      if (petType != null) petType,
      if (visualStyle != null) visualStyle,
      if (personality != null) personality,
    ];
    if (summaryParts.isNotEmpty) {
      return _summarizePetMetadata(
        petType: petType,
        visualStyle: visualStyle,
        personality: personality,
      );
    }
    final description = _firstMetadataValue(metadata, [
      'description',
      'summary',
      '简介',
      '描述',
    ]);
    if (description != null && !_looksLikePathDescription(description)) {
      return _compactPetListDescription(description);
    }
    return null;
  }

  String _summarizePetMetadata({
    required String? petType,
    required String? visualStyle,
    required String? personality,
  }) {
    final parts = <String>[
      if (petType != null) petType,
      if (visualStyle != null)
        _compactPetMetadataPart(
          visualStyle,
          dropContains: const ['适合桌面悬浮', '轮廓清晰', '无背景', '缩小后'],
          maxSegments: 2,
        ),
      if (personality != null)
        _compactPetMetadataPart(personality, maxSegments: 1),
    ].where((part) => part.trim().isNotEmpty).toList();
    if (parts.isEmpty) {
      return '';
    }
    final sentence = parts.join('，');
    return sentence.endsWith('。') ? sentence : '$sentence。';
  }

  String _compactPetMetadataPart(
    String value, {
    List<String> dropContains = const [],
    int maxSegments = 2,
  }) {
    final segments = value
        .split(RegExp(r'[，,；;。.]'))
        .map((segment) => segment.trim())
        .where((segment) {
          if (segment.isEmpty) return false;
          return !dropContains.any(segment.contains);
        })
        .take(maxSegments)
        .toList();
    return segments.join('，');
  }

  String _compactPetListDescription(String value) {
    final sentence = _compactPetMetadataPart(
      value.replaceAll('\n', ' '),
      dropContains: const ['适合桌面悬浮', '轮廓清晰', '无背景', '缩小后'],
      maxSegments: 3,
    );
    final compact = sentence.isEmpty ? value.trim() : sentence;
    final withoutTrailing = compact.replaceFirst(RegExp(r'[，,；;。.\s]+$'), '');
    final limited = withoutTrailing.length <= 28
        ? withoutTrailing
        : withoutTrailing
              .substring(0, 28)
              .replaceFirst(RegExp(r'[，,；;。.\s]+$'), '');
    return limited.endsWith('。') ? limited : '$limited。';
  }

  String? _firstMetadataValue(Map<String, String> metadata, List<String> keys) {
    for (final key in keys) {
      final value = metadata[key]?.trim();
      if (value != null && value.isNotEmpty) {
        return value;
      }
    }
    return null;
  }

  bool _looksLikePathDescription(String value) {
    final trimmed = value.trim();
    return trimmed.startsWith('/workspace/') ||
        trimmed.startsWith(r'C:\') ||
        trimmed.startsWith('custom:') ||
        trimmed.contains('/.omnibot/pets/') ||
        trimmed.contains('/pets/');
  }

  Future<Map<String, String>> _readPetMarkdownMetadataFor(File file) async {
    final baseName = _baseNameWithoutExtension(file);
    final identityBaseName = _petIdentityBaseName(file);
    final candidates = <File>[
      File('${file.parent.path}${Platform.pathSeparator}${baseName}_readme.md'),
      File('${file.parent.path}${Platform.pathSeparator}${baseName}.md'),
      if (identityBaseName != baseName)
        File(
          '${file.parent.path}${Platform.pathSeparator}${identityBaseName}_readme.md',
        ),
      if (identityBaseName != baseName)
        File(
          '${file.parent.path}${Platform.pathSeparator}${identityBaseName}.md',
        ),
      File('${file.parent.path}${Platform.pathSeparator}README.md'),
    ];
    final workspaceRoot = _workspaceRootForPetFile(file);
    if (workspaceRoot != null) {
      candidates.addAll([
        File(
          '$workspaceRoot${Platform.pathSeparator}pets${Platform.pathSeparator}${baseName}_readme.md',
        ),
        File(
          '$workspaceRoot${Platform.pathSeparator}pets${Platform.pathSeparator}${baseName}.md',
        ),
        if (identityBaseName != baseName)
          File(
            '$workspaceRoot${Platform.pathSeparator}pets${Platform.pathSeparator}${identityBaseName}_readme.md',
          ),
        if (identityBaseName != baseName)
          File(
            '$workspaceRoot${Platform.pathSeparator}pets${Platform.pathSeparator}${identityBaseName}.md',
          ),
      ]);
    }
    for (final candidate in candidates) {
      if (!await candidate.exists()) continue;
      try {
        final text = await candidate.readAsString();
        final displayName =
            _firstMarkdownValue(text, ['名称', '名字', 'name']) ??
            _petNameForFile(file, workspaceRoot ?? file.parent.path);
        final description =
            _firstMarkdownValue(text, [
              '简介',
              '描述',
              'description',
              '视觉风格',
              '类型',
            ]) ??
            _petDescriptionForFile(file, workspaceRoot ?? file.parent.path);
        return {'displayName': displayName, 'description': description};
      } catch (_) {
        return const {};
      }
    }
    return const {};
  }

  Future<bool> _hasPetMetadata(Directory directory) {
    return File('${directory.path}${Platform.pathSeparator}pet.json').exists();
  }

  Future<File?> _preferredCurrentPetImageIn(Directory directory) async {
    for (final name in _preferredPetFileNames) {
      final file = File('${directory.path}${Platform.pathSeparator}$name');
      if (await _isUsablePetImage(file)) {
        return file;
      }
      if (await _isUsablePetSvg(file)) {
        final preview = await _materializeSvgPetImage(file);
        if (preview != null) {
          return preview;
        }
      }
    }
    return null;
  }

  Future<File?> _firstSupportedPetImageIn(
    Directory directory,
    String workspaceRoot,
  ) async {
    final metadataImage = await _firstPetImageFromMetadata(
      directory,
      workspaceRoot,
    );
    if (metadataImage != null) {
      return metadataImage;
    }
    final preferred = await _preferredCurrentPetImageIn(directory);
    if (preferred != null) {
      return preferred;
    }
    final hasMetadata = await _hasPetMetadata(directory);
    final images = await directory
        .list(followLinks: false)
        .where((entity) => entity is File)
        .cast<File>()
        .toList();
    final usableImages = <File>[];
    for (final image in images) {
      if (_isGeneratedPetPreviewFile(image.path) ||
          _looksLikePetAtlas(image.path) ||
          _isAnimationStatePetFile(image.path)) {
        continue;
      }
      if (await _isUsablePetImage(image)) {
        usableImages.add(image);
      } else if (await _isUsablePetSvg(image)) {
        final preview = await _materializeSvgPetImage(image);
        usableImages.add(preview ?? image);
      }
    }
    usableImages.sort((left, right) => left.path.compareTo(right.path));
    if (usableImages.isNotEmpty) {
      return usableImages.first;
    }
    if (hasMetadata) {
      final generatedPreviews =
          images
              .where((image) => _isGeneratedPetPreviewFile(image.path))
              .toList()
            ..sort((left, right) => left.path.compareTo(right.path));
      for (final preview in generatedPreviews) {
        if (await _isUsablePetImage(preview)) {
          return preview;
        }
      }
    }
    return hasMetadata ? _firstPetPreviewFromHtml(directory) : null;
  }

  Future<File?> _firstPetImageFromMetadata(
    Directory directory,
    String workspaceRoot,
  ) async {
    final metadataFile = File(
      '${directory.path}${Platform.pathSeparator}pet.json',
    );
    if (!await metadataFile.exists()) {
      return null;
    }
    Map<String, dynamic> metadata;
    try {
      final decoded = jsonDecode(await metadataFile.readAsString());
      if (decoded is! Map) {
        return null;
      }
      metadata = decoded.map((key, value) => MapEntry(key.toString(), value));
    } catch (_) {
      return null;
    }

    final directImageKeys = [
      'imagePath',
      'image_path',
      'previewPath',
      'preview_path',
      'preview',
      'iconPath',
      'icon_path',
    ];
    for (final key in directImageKeys) {
      final file = await _resolveMetadataPetFile(
        metadata[key],
        directory,
        workspaceRoot,
      );
      if (file == null) {
        continue;
      }
      if (await _isUsablePetImage(file)) {
        if (_looksLikePetAtlas(file.path)) {
          final preview = await _materializeAtlasPetImage(file);
          if (preview != null) {
            return preview;
          }
        }
        return file;
      }
      if (await _isUsablePetSvg(file)) {
        final preview = await _materializeSvgPetImage(file);
        if (preview != null) {
          return preview;
        }
        return file;
      }
      final existingPreview = await _existingGeneratedPetPreviewFor(file);
      if (existingPreview != null) {
        return existingPreview;
      }
      if (file.path.toLowerCase().endsWith('.html')) {
        final preview = await _materializeHtmlPetImage(file);
        if (preview != null) {
          return preview;
        }
      }
    }

    final atlasKeys = [
      'spritesheetPath',
      'spritesheet_path',
      'atlasPath',
      'atlas_path',
    ];
    for (final key in atlasKeys) {
      final file = await _resolveMetadataPetFile(
        metadata[key],
        directory,
        workspaceRoot,
      );
      if (file == null) {
        continue;
      }
      if (!await _isUsablePetImage(file)) {
        final existingPreview = await _existingGeneratedPetPreviewFor(file);
        if (existingPreview != null) {
          return existingPreview;
        }
        continue;
      }
      final preview = await _materializeAtlasPetImage(file);
      if (preview != null) {
        return preview;
      }
      final existingPreview = await _existingGeneratedPetPreviewFor(file);
      if (existingPreview != null) {
        return existingPreview;
      }
    }
    return null;
  }

  Future<File?> _existingGeneratedPetPreviewFor(File sourceFile) async {
    final preview = File('${sourceFile.path}$_generatedPetPreviewSuffix');
    if (await _isUsablePetImage(preview)) {
      return preview;
    }
    return null;
  }

  Future<File?> _resolveMetadataPetFile(
    Object? rawValue,
    Directory directory,
    String workspaceRoot,
  ) async {
    if (rawValue == null) {
      return null;
    }
    final value = rawValue.toString().trim();
    if (value.isEmpty ||
        value.startsWith('http://') ||
        value.startsWith('https://') ||
        value.startsWith('data:')) {
      return null;
    }
    final cleanPath = Uri.decodeComponent(
      value.split('#').first.split('?').first,
    );
    if (cleanPath == '/workspace' || cleanPath.startsWith('/workspace/')) {
      return File(_resolveWorkspaceDisplayPath(cleanPath, workspaceRoot));
    }
    final uri = Uri.tryParse(cleanPath);
    if (uri != null && uri.scheme == 'file') {
      return File.fromUri(uri);
    }
    final asFile = File(cleanPath);
    if (asFile.isAbsolute) {
      return asFile;
    }
    return File('${directory.path}${Platform.pathSeparator}$cleanPath');
  }

  Future<File?> _materializeAtlasPetImage(File atlasFile) async {
    final outputFile = File('${atlasFile.path}$_generatedPetPreviewSuffix');
    try {
      final atlasModified = await atlasFile.lastModified();
      final outputExists = await outputFile.exists();
      final outputFresh =
          outputExists &&
          (await outputFile.length()) >= 12 &&
          (await outputFile.lastModified()).millisecondsSinceEpoch >=
              atlasModified.millisecondsSinceEpoch;
      if (outputFresh && await _isUsablePetImage(outputFile)) {
        return outputFile;
      }

      final bytes = await atlasFile.readAsBytes();
      final codec = await ui.instantiateImageCodec(bytes);
      final frame = await codec.getNextFrame();
      final image = frame.image;
      const atlasColumns = 8;
      final atlasRows = switch (image.height) {
        1872 => 9,
        2288 => 11,
        _ => 0,
      };
      if (image.width != 1536 || atlasRows == 0) {
        image.dispose();
        codec.dispose();
        return null;
      }
      final cellWidth = (image.width / atlasColumns).floor();
      final cellHeight = (image.height / atlasRows).floor();
      if (cellWidth != 192 || cellHeight != 208) {
        image.dispose();
        codec.dispose();
        return null;
      }

      const outputSize = 512;
      final recorder = ui.PictureRecorder();
      final canvas = ui.Canvas(recorder);
      final source = ui.Rect.fromLTWH(
        0,
        0,
        cellWidth.toDouble(),
        cellHeight.toDouble(),
      );
      final scale = math.min(outputSize / cellWidth, outputSize / cellHeight);
      final target = ui.Rect.fromLTWH(
        (outputSize - cellWidth * scale) / 2,
        (outputSize - cellHeight * scale) / 2,
        cellWidth * scale,
        cellHeight * scale,
      );
      canvas.drawImageRect(image, source, target, Paint());
      final picture = recorder.endRecording();
      final preview = await picture.toImage(outputSize, outputSize);
      final byteData = await preview.toByteData(format: ui.ImageByteFormat.png);
      image.dispose();
      codec.dispose();
      picture.dispose();
      preview.dispose();
      final previewBytes = byteData?.buffer.asUint8List();
      if (previewBytes == null || previewBytes.isEmpty) {
        return null;
      }
      await outputFile.writeAsBytes(previewBytes, flush: true);
      await outputFile.setLastModified(atlasModified);
      return outputFile;
    } catch (_) {
      return null;
    }
  }

  static const List<String> _preferredPetFileNames = [
    'current.webp',
    'current.png',
    'current.jpg',
    'current.gif',
    'current.svg',
    'pet.webp',
    'pet.png',
    'pet.jpg',
    'pet.gif',
    'pet.svg',
  ];
  static const String _generatedPetPreviewSuffix = '.omnibot-preview.png';

  bool _isPreferredPetFileName(String path) {
    final fileName = File(path).uri.pathSegments.last.toLowerCase();
    return _preferredPetFileNames.contains(fileName);
  }

  bool _isSupportedPetImage(String path) {
    final lower = path.toLowerCase();
    return lower.endsWith('.webp') ||
        lower.endsWith('.png') ||
        lower.endsWith('.jpg') ||
        lower.endsWith('.jpeg') ||
        lower.endsWith('.gif');
  }

  bool _isSupportedPetSvg(String path) {
    return path.toLowerCase().endsWith('.svg');
  }

  bool _isGeneratedPetPreviewFile(String path) {
    final lower = path.toLowerCase();
    return lower.endsWith(_generatedPetPreviewSuffix) ||
        lower.endsWith('.omnibot-preview.svg');
  }

  bool _isActivePetAlias(String path) {
    final name = File(path).uri.pathSegments.last.toLowerCase();
    return name == 'current.svg' ||
        name == 'current.webp' ||
        name == 'current.png' ||
        name == 'current.jpg' ||
        name == 'current.gif';
  }

  bool _isAnimationStatePetFile(String path) {
    final baseName = _baseNameWithoutExtension(File(path)).toLowerCase();
    final stateSuffixes = [
      '_idle',
      '_working',
      '_thinking',
      '_waiting',
      '_done',
      '_sleeping',
      '-idle',
      '-working',
      '-thinking',
      '-waiting',
      '-done',
      '-sleeping',
    ];
    return stateSuffixes.any(baseName.endsWith);
  }

  int _petCandidateRank(File file) {
    final source = _sourceFileForGeneratedPetPreview(file);
    final normalizedPath = _normalizePath(source.path);
    final baseName = _baseNameWithoutExtension(source).toLowerCase();
    if (baseName == 'current' && normalizedPath.contains('/.omnibot/pets/')) {
      return 0;
    }
    if (baseName.endsWith('_full') || baseName.endsWith('-full')) {
      return 1;
    }
    if (baseName == 'current') {
      return 2;
    }
    return 3;
  }

  bool _looksLikePetAtlas(String path) {
    final name = File(path).uri.pathSegments.last.toLowerCase();
    return name == 'spritesheet.webp' ||
        name == 'spritesheet.png' ||
        name == 'atlas.webp' ||
        name == 'atlas.png';
  }

  Future<bool> _isUsablePetImage(File file) async {
    if (!_isSupportedPetImage(file.path)) {
      return false;
    }
    try {
      if (!await file.exists() || await file.length() < 12) {
        return false;
      }
      final bytes = await file.openRead(0, 12).first;
      return _hasSupportedPetImageHeader(file.path, bytes);
    } catch (_) {
      return false;
    }
  }

  Future<bool> _isUsablePetSvg(File file) async {
    if (!_isSupportedPetSvg(file.path)) {
      return false;
    }
    try {
      if (!await file.exists() || await file.length() < 12) {
        return false;
      }
      final head = await file.openRead(0, 512).transform(utf8.decoder).join();
      return head.toLowerCase().contains('<svg');
    } catch (_) {
      return false;
    }
  }

  Future<File?> _materializeSvgPetImage(File svgFile) async {
    final outputFile = File('${svgFile.path}$_generatedPetPreviewSuffix');
    try {
      final svgModified = await svgFile.lastModified();
      final outputExists = await outputFile.exists();
      final existingUsable =
          outputExists && await _isUsablePetImage(outputFile);
      final pictureInfo = await vg.loadPicture(SvgFileLoader(svgFile), null);
      final sourceSize = pictureInfo.size;
      if (sourceSize.width <= 0 || sourceSize.height <= 0) {
        pictureInfo.picture.dispose();
        return existingUsable ? outputFile : null;
      }
      const outputSize = 512;
      final recorder = ui.PictureRecorder();
      final canvas = ui.Canvas(recorder);
      final scale = math.min(
        outputSize / sourceSize.width,
        outputSize / sourceSize.height,
      );
      canvas.translate(
        (outputSize - sourceSize.width * scale) / 2,
        (outputSize - sourceSize.height * scale) / 2,
      );
      canvas.scale(scale);
      canvas.drawPicture(pictureInfo.picture);
      final rasterPicture = recorder.endRecording();
      final image = await rasterPicture.toImage(outputSize, outputSize);
      final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
      pictureInfo.picture.dispose();
      rasterPicture.dispose();
      image.dispose();
      final bytes = byteData?.buffer.asUint8List();
      if (bytes == null || bytes.isEmpty) {
        return existingUsable ? outputFile : null;
      }
      await outputFile.writeAsBytes(bytes, flush: true);
      await outputFile.setLastModified(svgModified);
      return outputFile;
    } catch (_) {
      if (await _isUsablePetImage(outputFile)) {
        return outputFile;
      }
      return null;
    }
  }

  Future<File?> _firstPetPreviewFromHtml(Directory directory) async {
    final preferredNames = ['index.html', 'preview.html'];
    final htmlFiles = <File>[];
    for (final name in preferredNames) {
      final file = File('${directory.path}${Platform.pathSeparator}$name');
      if (await file.exists()) {
        htmlFiles.add(file);
      }
    }
    if (htmlFiles.isEmpty) {
      final allFiles = await directory
          .list(followLinks: false)
          .where((entity) => entity is File)
          .cast<File>()
          .toList();
      htmlFiles.addAll(
        allFiles.where((file) => file.path.toLowerCase().endsWith('.html')),
      );
    }
    for (final htmlFile in htmlFiles) {
      final image = await _materializeHtmlPetImage(htmlFile);
      if (image != null) {
        return image;
      }
    }
    return null;
  }

  Future<File?> _materializeHtmlPetImage(File htmlFile) async {
    try {
      final html = await htmlFile.readAsString();
      final referenced = await _firstReferencedPetImage(htmlFile, html);
      if (referenced != null) {
        return referenced;
      }
      final svgMatch = RegExp(
        r'<svg[\s\S]*?</svg>',
        caseSensitive: false,
      ).firstMatch(html);
      final svgText = svgMatch?.group(0);
      if (svgText == null || svgText.trim().isEmpty) {
        return null;
      }
      final svgFile = File('${htmlFile.path}.omnibot-preview.svg');
      final htmlModified = await htmlFile.lastModified();
      final svgExists = await svgFile.exists();
      final svgFresh =
          svgExists &&
          (await svgFile.lastModified()).millisecondsSinceEpoch >=
              htmlModified.millisecondsSinceEpoch;
      if (!svgFresh || await svgFile.readAsString() != svgText) {
        await svgFile.writeAsString(svgText, flush: true);
        await svgFile.setLastModified(htmlModified);
      }
      return _materializeSvgPetImage(svgFile);
    } catch (_) {
      return null;
    }
  }

  Future<File?> _firstReferencedPetImage(File htmlFile, String html) async {
    final srcMatches = RegExp(
      r'''(?:src|href)=["']([^"']+)["']''',
      caseSensitive: false,
    ).allMatches(html);
    for (final match in srcMatches) {
      final value = match.group(1)?.trim();
      if (value == null ||
          value.isEmpty ||
          value.startsWith('http://') ||
          value.startsWith('https://') ||
          value.startsWith('data:')) {
        continue;
      }
      final relative = Uri.decodeComponent(
        value.split('#').first.split('?').first,
      );
      final file = File(
        '${htmlFile.parent.path}${Platform.pathSeparator}$relative',
      );
      if (await _isUsablePetImage(file)) {
        return file;
      }
      if (await _isUsablePetSvg(file)) {
        final preview = await _materializeSvgPetImage(file);
        if (preview != null) {
          return preview;
        }
      }
    }
    return null;
  }

  bool _hasSupportedPetImageHeader(String path, List<int> bytes) {
    final lower = path.toLowerCase();
    if (lower.endsWith('.png')) {
      return bytes.length >= 8 &&
          bytes[0] == 0x89 &&
          bytes[1] == 0x50 &&
          bytes[2] == 0x4E &&
          bytes[3] == 0x47;
    }
    if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) {
      return bytes.length >= 3 &&
          bytes[0] == 0xFF &&
          bytes[1] == 0xD8 &&
          bytes[2] == 0xFF;
    }
    if (lower.endsWith('.gif')) {
      return bytes.length >= 6 &&
          bytes[0] == 0x47 &&
          bytes[1] == 0x49 &&
          bytes[2] == 0x46;
    }
    if (lower.endsWith('.webp')) {
      return bytes.length >= 12 &&
          bytes[0] == 0x52 &&
          bytes[1] == 0x49 &&
          bytes[2] == 0x46 &&
          bytes[3] == 0x46 &&
          bytes[8] == 0x57 &&
          bytes[9] == 0x45 &&
          bytes[10] == 0x42 &&
          bytes[11] == 0x50;
    }
    return false;
  }

  String _resolveSelectedPetId({
    required String selectedId,
    required String selectedPath,
    required List<_OverlayPetOption> options,
  }) {
    if (selectedPath.isEmpty) return 'builtin:xiaowan';
    final byId = options.any((option) => option.id == selectedId);
    if (byId) return selectedId;
    final normalizedSelected = _normalizePath(selectedPath);
    return options
        .firstWhere(
          (option) =>
              _normalizePath(option.imagePath) == normalizedSelected ||
              _normalizePath(_playbackPathForPetOption(option)) ==
                  normalizedSelected,
          orElse: () => options.first,
        )
        .id;
  }

  String _petIdForPath(String path, String workspaceRoot) {
    final normalized = _normalizePath(path);
    final normalizedRoot = _normalizePath(workspaceRoot);
    if (normalizedRoot.isNotEmpty &&
        (normalized == normalizedRoot ||
            normalized.startsWith('$normalizedRoot/'))) {
      return 'custom:${normalized.substring(normalizedRoot.length).replaceFirst(RegExp(r'^/'), '')}';
    }
    return 'custom:$normalized';
  }

  String _loosePetIdForPath(String path, String workspaceRoot) {
    final source = _sourceFileForGeneratedPetPreview(File(path));
    final baseName = _petIdentityBaseName(source).toLowerCase();
    final parent = _normalizePath(source.parent.path);
    final normalizedRoot = _normalizePath(workspaceRoot);
    final relativeParent =
        normalizedRoot.isNotEmpty && parent.startsWith('$normalizedRoot/')
        ? parent.substring(normalizedRoot.length + 1)
        : parent;
    return 'custom:$relativeParent/$baseName';
  }

  String _petIdentityBaseName(File file) {
    return _baseNameWithoutExtension(_sourceFileForGeneratedPetPreview(file))
        .replaceFirst(RegExp(r'[-_]full$', caseSensitive: false), '')
        .replaceFirst(RegExp(r'[-_]preview$', caseSensitive: false), '')
        .replaceFirst(RegExp(r'[-_]current$', caseSensitive: false), '');
  }

  String _petNameForFile(File file, String workspaceRoot) {
    final displayFile = _sourceFileForGeneratedPetPreview(file);
    final normalizedRoot = _normalizePath(workspaceRoot);
    final topLevelPetDirs = {
      _normalizePath('$workspaceRoot/.omnibot/pets'),
      _normalizePath('$workspaceRoot/pets'),
      normalizedRoot,
    };
    final parentPath = _normalizePath(displayFile.parent.path);
    final parentSegments = displayFile.parent.uri.pathSegments
        .where((segment) => segment.isNotEmpty)
        .toList();
    final parentName =
        topLevelPetDirs.contains(parentPath) || parentSegments.isEmpty
        ? ''
        : parentSegments.last;
    final fileBaseName = _petIdentityBaseName(displayFile);
    final rawName = parentName.isEmpty
        ? (fileBaseName == 'current' ? '自定义宠物' : fileBaseName)
        : parentName;
    return rawName
        .replaceAll(RegExp(r'[-_]+'), ' ')
        .trim()
        .split(' ')
        .where((part) => part.isNotEmpty)
        .map((part) => part[0].toUpperCase() + part.substring(1))
        .join(' ');
  }

  String _petDescriptionForFile(File file, String workspaceRoot) {
    final name = _petNameForFile(file, workspaceRoot);
    return name.isEmpty ? '自定义桌面悬浮窗宠物' : '$name，适合桌面悬浮的自定义电子宠物';
  }

  int _petSortTimestamp(File file) {
    final source = _sourceFileForGeneratedPetPreview(file);
    final candidates = <FileSystemEntity>[
      source,
      file,
      File('${source.parent.path}${Platform.pathSeparator}pet.json'),
      source.parent,
    ];
    var newest = 0;
    for (final candidate in candidates) {
      try {
        if (!candidate.existsSync()) {
          continue;
        }
        final modified = switch (candidate) {
          File() => candidate.lastModifiedSync().millisecondsSinceEpoch,
          Directory() => candidate.statSync().modified.millisecondsSinceEpoch,
          _ => candidate.statSync().modified.millisecondsSinceEpoch,
        };
        if (modified > newest) {
          newest = modified;
        }
      } catch (_) {
        continue;
      }
    }
    return newest;
  }

  String _displayFileNameForPetImage(File file) {
    final name = file.uri.pathSegments.last;
    if (name.toLowerCase().endsWith(_generatedPetPreviewSuffix)) {
      return name.substring(0, name.length - _generatedPetPreviewSuffix.length);
    }
    return name;
  }

  String _displayPathForPetImage(File file) {
    final path = file.path;
    if (path.toLowerCase().endsWith(_generatedPetPreviewSuffix)) {
      return path.substring(0, path.length - _generatedPetPreviewSuffix.length);
    }
    return path;
  }

  File _sourceFileForGeneratedPetPreview(File file) {
    final path = _displayPathForPetImage(file);
    if (path == file.path) {
      return file;
    }
    return File(path);
  }

  String _baseNameWithoutExtension(File file) {
    return _displayFileNameForPetImage(
      file,
    ).replaceAll(RegExp(r'\.[^.]+$'), '').trim();
  }

  String _pathBaseName(String path) {
    final segments = _normalizePath(
      path,
    ).split('/').where((segment) => segment.isNotEmpty).toList();
    return segments.isEmpty ? '' : segments.last;
  }

  String? _workspaceRootForPetFile(File file) {
    final normalized = _normalizePath(file.path);
    for (final marker in ['/.omnibot/pets/', '/pets/']) {
      final markerIndex = normalized.indexOf(marker);
      if (markerIndex > 0) {
        return normalized.substring(0, markerIndex);
      }
    }
    return null;
  }

  String? _firstMarkdownValue(String text, List<String> labels) {
    final lines = const LineSplitter().convert(text);
    for (final rawLine in lines) {
      final line = rawLine
          .replaceFirst(RegExp(r'^\s*[-*]\s*'), '')
          .replaceFirst(RegExp(r'^\s*#+\s*'), '')
          .trim();
      for (final label in labels) {
        final match = RegExp(
          '^${RegExp.escape(label)}\\s*[:：]\\s*(.+)\$',
          caseSensitive: false,
        ).firstMatch(line);
        final value = match?.group(1)?.trim();
        if (value != null && value.isNotEmpty) {
          return value;
        }
      }
    }
    return null;
  }

  String _resolveWorkspaceDisplayPath(String path, String workspaceRoot) {
    final trimmed = path.trim();
    if (trimmed == '/workspace') return workspaceRoot;
    if (trimmed.startsWith('/workspace/')) {
      return '$workspaceRoot/${trimmed.substring('/workspace/'.length)}';
    }
    return trimmed;
  }

  String _normalizePath(String path) {
    return path.trim().replaceAll('\\', '/').replaceAll(RegExp(r'/+$'), '');
  }

  Future<void> _importPetPackage() async {
    if (_petBusy) return;
    setState(() => _petBusy = true);
    try {
      final picked = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['zip'],
        allowMultiple: false,
        withData: true,
      );
      if (picked == null || picked.files.isEmpty) {
        return;
      }
      final selectedFile = picked.files.single;
      final bytes =
          selectedFile.bytes ??
          (selectedFile.path == null
              ? null
              : await File(selectedFile.path!).readAsBytes());
      if (bytes == null || bytes.isEmpty) {
        throw const PetPackageException('无法读取宠物压缩包');
      }
      final installed = await const PetPackageInstaller().installArchiveBytes(
        archiveBytes: bytes,
        petsRootPath: _petsDirectoryPath,
      );
      await _loadPetSettings();
      if (!mounted) return;
      final installedPath = _normalizePath(installed.spritesheetFile.path);
      _OverlayPetOption? installedOption;
      for (final option in _petOptions) {
        if (!option.isBuiltin &&
            _normalizePath(_playbackPathForPetOption(option)) ==
                installedPath) {
          installedOption = option;
          break;
        }
      }
      if (installedOption == null) {
        throw const PetPackageException('宠物已写入，但未能加入外观列表');
      }
      await _applyPetSelection(installedOption);
      if (!mounted) return;
      showToast(context.trLegacy('宠物已导入并选中'), type: ToastType.success);
    } catch (error) {
      if (!mounted) return;
      final message = error is PetPackageException
          ? error.message
          : error.toString();
      showToast(
        '${context.trLegacy('导入宠物失败')}：$message',
        type: ToastType.error,
      );
    } finally {
      if (mounted) setState(() => _petBusy = false);
    }
  }

  Future<void> _refreshPetOptions() async {
    await _loadPetSettings();
    if (!mounted) return;
    final selectedOption = _petOptions.firstWhere(
      (option) => option.id == _selectedPetId,
      orElse: () => _petOptions.first,
    );
    await OverlayService.setPetOverlayImagePath(
      selectedOption.isBuiltin ? '' : _playbackPathForPetOption(selectedOption),
      selectedId: selectedOption.id,
    );
    if (!mounted) return;
    showToast(context.trLegacy('宠物列表已刷新'), type: ToastType.success);
  }

  Future<void> _selectPet(_OverlayPetOption option) async {
    if (_petBusy) return;
    setState(() => _petBusy = true);
    try {
      await _applyPetSelection(option);
    } catch (error) {
      if (!mounted) return;
      showToast(context.trLegacy('选择宠物失败'), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _petBusy = false);
    }
  }

  Future<void> _applyPetSelection(_OverlayPetOption option) async {
    final imagePath = option.isBuiltin ? '' : _playbackPathForPetOption(option);
    await StorageService.setPetOverlaySelectedId(option.id);
    await StorageService.setPetOverlayImagePath(imagePath);
    final synced = await OverlayService.setPetOverlayImagePath(
      imagePath,
      selectedId: option.id,
    );
    if (!synced) {
      throw Exception('native_sync_failed');
    }
    if (!mounted) return;
    setState(() {
      _selectedPetId = option.id;
    });
  }

  String _playbackPathForPetOption(_OverlayPetOption option) {
    if (option.isBuiltin || option.imagePath.isEmpty) {
      return '';
    }
    return _displayPathForPetImage(File(option.imagePath));
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
