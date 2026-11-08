import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/builtin_official_provider_catalog.dart';
import 'package:ui/services/chatgpt_oauth_service.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/model_vendor_catalog.dart';
import 'package:ui/services/scene_model_config_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';

import 'provider_connection_tab.dart';
import 'provider_models_tab.dart';
import 'provider_setting_models.dart';

const double _kProviderPopupMaxHeight = 340;
const double _kProviderPopupMinWidth = 200;
const double _kProviderPopupHorizontalMargin = 16;

class ModelProviderDetailPage extends StatefulWidget {
  const ModelProviderDetailPage({
    super.key,
    this.initialProfile,
    this.isNew = false,
    this.reusePlaceholderProfileId,
    this.initialPresetValue,
    this.initialTab,
    this.initialRemoteModels = const <ProviderModelOption>[],
    this.existingProfileNames = const <String>[],
    this.onProfileChanged,
    this.onModelsChanged,
    this.onProfileDeleted,
  });

  final ModelProviderProfileSummary? initialProfile;
  final bool isNew;

  /// When adding the first provider on a clean install where native storage has
  /// an untouched placeholder (`profile-1`), reuse its ID so we don't leave an
  /// orphan empty profile behind.
  final String? reusePlaceholderProfileId;
  final String? initialPresetValue;
  final ProviderDetailTab? initialTab;
  final List<ProviderModelOption> initialRemoteModels;
  final List<String> existingProfileNames;
  final ValueChanged<ModelProviderProfileSummary>? onProfileChanged;
  final void Function(
    String profileId,
    List<ProviderModelOption> remoteModels,
    List<String> manualModelIds,
    Set<String> hiddenChatModelIds,
  )?
  onModelsChanged;
  final ValueChanged<String>? onProfileDeleted;

  @override
  State<ModelProviderDetailPage> createState() =>
      _ModelProviderDetailPageState();
}

class _ModelProviderDetailPageState extends State<ModelProviderDetailPage>
    with FlClashPageLoadMixin {
  static const Duration _autoSaveDebounce = Duration(milliseconds: 600);

  final TextEditingController _nameController = TextEditingController();
  final TextEditingController _baseUrlController = TextEditingController();
  final TextEditingController _apiKeyController = TextEditingController();
  final TextEditingController _modelSearchController = TextEditingController();

  final FocusNode _nameFocusNode = FocusNode();
  final FocusNode _baseUrlFocusNode = FocusNode();
  final FocusNode _apiKeyFocusNode = FocusNode();

  final ScrollController _connectionScrollController = ScrollController();
  final ScrollController _modelsScrollController = ScrollController();

  Timer? _autoSaveTimer;

  ModelProviderProfileSummary? _profile;
  late bool _isNew = widget.isNew;
  late ProviderDetailTab _currentTab;

  bool _isLoading = false;
  bool _isFetchingModels = false;
  bool _obscureApiKey = true;
  bool _isSyncingControllers = false;
  bool _isSavingProfile = false;
  bool _saveQueued = false;
  bool _isSelectingPreset = false;
  bool _apiKeyDirty = false;
  bool _customHeadersDirty = false;
  bool _advancedExpanded = false;
  bool _isDisposing = false;
  bool _cachedIsEnglish = false;
  int _draftMutationRevision = 0;

  String _selectedPresetValue = 'openai_compatible';
  String _selectedSourceType = BuiltinOfficialProviderCatalog.customKey;
  String _selectedProtocolType = 'openai_compatible';
  String _selectedWireApi = 'chat_completions';

  ProviderSaveState _saveState = ProviderSaveState.idle;
  ProviderErrorDiagnosis? _saveErrorDiagnosis;
  ProviderErrorDiagnosis? _modelFetchErrorDiagnosis;

  List<ProviderModelOption> _manualModels = const [];
  List<ProviderModelOption> _remoteModels = const [];
  List<String> _manualModelIds = const [];
  Set<String> _hiddenChatModelIds = <String>{};
  final Map<String, bool> _expandedModelGroups = <String, bool>{};
  final Map<String, ModelTestState> _modelTestStates =
      <String, ModelTestState>{};

  final List<EditableHeaderEntry> _customHeaderEntries =
      <EditableHeaderEntry>[];
  int _nextCustomHeaderEntryId = 0;
  String? _customHeadersErrorText;

  bool get _canUpdateUi => mounted && !_isDisposing;
  bool get _isEnglish =>
      _canUpdateUi
          ? Localizations.localeOf(context).languageCode == 'en'
          : _cachedIsEnglish;
  String _text(String zh, String en) => _isEnglish ? en : zh;

  bool get _isChatGptProvider =>
      _selectedSourceType == ChatGptOAuthService.sourceType;

  bool get _readOnly => _profile?.readOnly ?? false;

  bool get _hasAnyProfileFieldFocus =>
      _nameFocusNode.hasFocus ||
      _baseUrlFocusNode.hasFocus ||
      _apiKeyFocusNode.hasFocus ||
      _customHeaderEntries.any(
        (entry) =>
            entry.nameFocusNode.hasFocus || entry.valueFocusNode.hasFocus,
      );

  ProviderPresetOption get _selectedPreset => resolvePresetForValues(
    sourceType: _selectedSourceType,
    protocolType: _selectedProtocolType,
    wireApi: _selectedWireApi,
    baseUrl: _baseUrlController.text,
    explicitPresetValue: _selectedPresetValue,
  );

  ProviderWireProtocol get _selectedWireProtocol =>
      ProviderWireProtocol.fromProfile(
        protocolType: _selectedProtocolType,
        wireApi: _selectedWireApi,
      );

  bool get _showProtocolSelector =>
      !_isChatGptProvider &&
      (_selectedPreset.isCustom ||
          _selectedPreset.value == 'openai_compatible');

  @override
  void initState() {
    super.initState();
    _profile = widget.initialProfile;
    _remoteModels = List<ProviderModelOption>.from(widget.initialRemoteModels);
    _initializeFromWidget();

    _nameController.addListener(_onProfileChanged);
    _baseUrlController.addListener(_onProfileChanged);
    _apiKeyController.addListener(_onApiKeyChanged);
    _modelSearchController.addListener(_onModelSearchChanged);
    _nameFocusNode.addListener(_onProfileFieldFocusChanged);
    _baseUrlFocusNode.addListener(_onProfileFieldFocusChanged);
    _apiKeyFocusNode.addListener(_onProfileFieldFocusChanged);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _cachedIsEnglish = Localizations.localeOf(context).languageCode == 'en';
  }

  void _initializeFromWidget() {
    final profile = widget.initialProfile;
    if (profile != null && !widget.isNew) {
      final preset = resolvePresetForValues(
        sourceType: profile.sourceType,
        protocolType: profile.protocolType,
        wireApi: profile.wireApi,
        baseUrl: profile.baseUrl,
      );
      _selectedPresetValue = preset.value;
      _selectedSourceType = profile.sourceType;
      _selectedProtocolType = profile.protocolType;
      _selectedWireApi = _normalizeWireApiForProtocol(
        profile.protocolType,
        profile.wireApi,
      );
      _syncController(_nameController, profile.name);
      _syncController(_baseUrlController, profile.baseUrl);
      _syncController(_apiKeyController, profile.apiKey);
      _replaceCustomHeaderEntries(profile.customHeaders);
      _saveState = ProviderSaveState.saved;
      _currentTab =
          widget.initialTab ??
          (isProfileConnectionComplete(profile)
              ? ProviderDetailTab.models
              : ProviderDetailTab.connection);
      unawaited(_loadProfileModels(profile));
    } else {
      _isNew = true;
      final presetKey = widget.initialPresetValue ?? 'gemini_interactions';
      final preset = kProviderPresets.firstWhere(
        (item) => item.value == presetKey,
        orElse: () => kProviderPresets.first,
      );
      _selectedPresetValue = preset.value;
      _selectedSourceType = preset.sourceType;
      _selectedProtocolType = preset.protocolType;
      _selectedWireApi = _normalizeWireApiForProtocol(
        preset.protocolType,
        preset.wireApi,
      );
      final defaultName = _deduplicateName(preset.defaultName);
      _syncController(_nameController, defaultName);
      _syncController(_baseUrlController, preset.baseUrl);
      _syncController(_apiKeyController, '');
      _replaceCustomHeaderEntries(const <String, String>{});
      _saveState = ProviderSaveState.idle;
      _currentTab = widget.initialTab ?? ProviderDetailTab.connection;
    }
  }

  String _deduplicateName(String baseName) {
    final normalizedBase = baseName.trim().isEmpty ? 'Provider' : baseName.trim();
    final existing = widget.existingProfileNames
        .map((n) => n.trim().toLowerCase())
        .toSet();
    if (!existing.contains(normalizedBase.toLowerCase())) {
      return normalizedBase;
    }
    var index = 2;
    while (existing.contains('$normalizedBase $index'.toLowerCase())) {
      index++;
    }
    return '$normalizedBase $index';
  }

  Future<void> _loadProfileModels(ModelProviderProfileSummary profile) async {
    setState(() => _isLoading = true);
    try {
      final results = await Future.wait<dynamic>([
        ModelProviderConfigService.getManualModelIds(profileId: profile.id),
        ModelProviderConfigService.getHiddenChatModelIds(profileId: profile.id),
      ]);
      if (!mounted) return;
      final manualIds = results[0] as List<String>;
      final hiddenIds = results[1] as List<String>;
      final manualModels = manualIds
          .map(
            (id) => ProviderModelOption(
              id: id,
              displayName: id,
              ownedBy: 'manual',
            ),
          )
          .toList();
      final cachedRemote = _remoteModels.isNotEmpty
          ? _remoteModels
          : ModelProviderConfigService.getCachedDiscoveredModelsForProfile(
              profile,
            );
      setState(() {
        _manualModelIds = manualIds;
        _hiddenChatModelIds = hiddenIds.toSet();
        _manualModels = manualModels;
        _remoteModels = cachedRemote;
      });
      _scheduleMetadataRefresh(
        profile: profile,
        manualModels: manualModels,
        remoteModels: cachedRemote,
      );
      if (profile.configured &&
          !profile.needsPresetCredentials &&
          (profile.sourceType != ChatGptOAuthService.sourceType ||
              profile.ready)) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted && _profile?.id == profile.id) {
            unawaited(_fetchModelsLocalized(silentError: false));
          }
        });
      }
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  @override
  void dispose() {
    _isDisposing = true;
    _autoSaveTimer?.cancel();
    if (_shouldAutoSaveDraft) {
      unawaited(_persistProfileDraft(silentToast: true));
    }
    unawaited(_persistManualModelIds());
    _nameFocusNode.removeListener(_onProfileFieldFocusChanged);
    _baseUrlFocusNode.removeListener(_onProfileFieldFocusChanged);
    _apiKeyFocusNode.removeListener(_onProfileFieldFocusChanged);
    _nameController.removeListener(_onProfileChanged);
    _baseUrlController.removeListener(_onProfileChanged);
    _apiKeyController.removeListener(_onApiKeyChanged);
    _modelSearchController.removeListener(_onModelSearchChanged);
    _nameController.dispose();
    _baseUrlController.dispose();
    _apiKeyController.dispose();
    _modelSearchController.dispose();
    _nameFocusNode.dispose();
    _baseUrlFocusNode.dispose();
    _apiKeyFocusNode.dispose();
    _connectionScrollController.dispose();
    _modelsScrollController.dispose();
    for (final entry in _customHeaderEntries) {
      entry.dispose();
    }
    super.dispose();
  }

  void _onModelSearchChanged() {
    if (mounted) setState(() {});
  }

  void _syncController(TextEditingController controller, String value) {
    if (controller.text == value) return;
    _isSyncingControllers = true;
    controller.value = TextEditingValue(
      text: value,
      selection: TextSelection.collapsed(offset: value.length),
    );
    _isSyncingControllers = false;
  }

  String _normalizeWireApiForProtocol(String protocolType, String? wireApi) {
    if (protocolType != 'openai_compatible') {
      return 'chat_completions';
    }
    return wireApi == 'responses' ? 'responses' : 'chat_completions';
  }

  void _onProfileChanged() {
    if (_isSyncingControllers || _isLoading) return;
    _draftMutationRevision++;
    if (mounted) {
      setState(() {
        if (_saveState != ProviderSaveState.saving) {
          _saveState = _shouldAutoSaveDraft
              ? ProviderSaveState.dirty
              : (_isNew ? ProviderSaveState.idle : ProviderSaveState.saved);
          _saveErrorDiagnosis = null;
        }
      });
    }
    if (_hasAnyProfileFieldFocus) {
      _autoSaveTimer?.cancel();
      return;
    }
    _scheduleAutoSave();
  }

  void _onApiKeyChanged() {
    if (!_isSyncingControllers) {
      _apiKeyDirty = true;
    }
    _onProfileChanged();
  }

  void _onProfileFieldFocusChanged() {
    if (_isSyncingControllers || _isLoading) return;
    if (_hasAnyProfileFieldFocus) {
      _autoSaveTimer?.cancel();
      return;
    }
    _scheduleAutoSave();
  }

  void _onCustomHeadersChanged() {
    if (_isSyncingControllers || _isLoading) return;
    _customHeadersDirty = true;
    _updateCustomHeadersError();
    if (mounted && _saveState != ProviderSaveState.saving) {
      setState(() {
        _saveState = ProviderSaveState.dirty;
      });
    }
    if (_hasAnyProfileFieldFocus) {
      _autoSaveTimer?.cancel();
      return;
    }
    _scheduleAutoSave();
  }

  void _scheduleAutoSave() {
    _autoSaveTimer?.cancel();
    if (_isSelectingPreset || !_shouldAutoSaveDraft) {
      return;
    }
    // For a brand-new draft, don't auto-save if the baseUrl is still empty.
    if (_isNew &&
        !_isChatGptProvider &&
        _baseUrlController.text.trim().isEmpty) {
      return;
    }
    _autoSaveTimer = Timer(_autoSaveDebounce, () {
      _autoSaveTimer = null;
      unawaited(_persistProfileDraft(silentToast: true));
    });
  }

  bool get _shouldAutoSaveDraft {
    if (_readOnly) return false;
    final current = _profile;
    if (current == null || _isNew) {
      return _nameController.text.trim().isNotEmpty &&
          (_isChatGptProvider ||
              _baseUrlController.text.trim().isNotEmpty ||
              _apiKeyController.text.trim().isNotEmpty);
    }
    final normalizedBaseUrl = ModelProviderConfigService.normalizeApiBase(
      _baseUrlController.text,
    );
    final currentBaseUrl =
        ModelProviderConfigService.normalizeApiBase(current.baseUrl) ?? '';
    final nextBaseUrl = normalizedBaseUrl ?? '';
    final nextCustomHeaders = _validatedCustomHeadersDraft();
    final hasCustomHeaderChanges =
        _customHeadersDirty &&
        nextCustomHeaders != null &&
        !_stringMapsEqual(
          nextCustomHeaders,
          ModelProviderConfigService.normalizeCustomHeaders(
            current.customHeaders,
          ),
        );
    return _nameController.text.trim() != current.name ||
        nextBaseUrl != currentBaseUrl ||
        _apiKeyDirty ||
        hasCustomHeaderChanges ||
        _selectedSourceType != current.sourceType ||
        _selectedProtocolType != current.protocolType ||
        _selectedWireApi != current.wireApi;
  }

  bool _stringMapsEqual(Map<String, String> left, Map<String, String> right) {
    if (left.length != right.length) return false;
    for (final entry in left.entries) {
      if (right[entry.key] != entry.value) return false;
    }
    return true;
  }

  void _replaceCustomHeaderEntries(Map<String, String> headers) {
    for (final entry in _customHeaderEntries) {
      entry.dispose();
    }
    _customHeaderEntries
      ..clear()
      ..addAll(
        ModelProviderConfigService.normalizeCustomHeaders(headers).entries.map(
          (entry) => _createHeaderEntry(name: entry.key, value: entry.value),
        ),
      );
    _customHeadersErrorText = _computeCustomHeadersValidationError();
  }

  EditableHeaderEntry _createHeaderEntry({
    String name = '',
    String value = '',
  }) {
    final entry = EditableHeaderEntry(
      id: _nextCustomHeaderEntryId++,
      name: name,
      value: value,
    );
    entry.nameController.addListener(_onCustomHeadersChanged);
    entry.valueController.addListener(_onCustomHeadersChanged);
    entry.nameFocusNode.addListener(_onProfileFieldFocusChanged);
    entry.valueFocusNode.addListener(_onProfileFieldFocusChanged);
    return entry;
  }

  void _addCustomHeaderEntry() {
    if (_readOnly) return;
    setState(() {
      _advancedExpanded = true;
      _customHeaderEntries.add(_createHeaderEntry());
      _customHeadersErrorText = _computeCustomHeadersValidationError();
    });
  }

  void _removeCustomHeaderEntry(int entryId) {
    final index = _customHeaderEntries.indexWhere((e) => e.id == entryId);
    if (index < 0) return;
    setState(() {
      final entry = _customHeaderEntries.removeAt(index);
      entry.dispose();
      _customHeadersDirty = true;
      _customHeadersErrorText = _computeCustomHeadersValidationError();
    });
    _scheduleAutoSave();
  }

  void _updateCustomHeadersError() {
    final nextError = _computeCustomHeadersValidationError();
    if (!mounted || nextError == _customHeadersErrorText) {
      _customHeadersErrorText = nextError;
      return;
    }
    setState(() {
      _customHeadersErrorText = nextError;
    });
  }

  String? _computeCustomHeadersValidationError() {
    final seen = <String>{};
    for (final entry in _customHeaderEntries) {
      final name = entry.nameController.text.trim();
      final value = entry.valueController.text;
      if (name.isEmpty && value.trim().isEmpty) continue;
      if (name.isEmpty) {
        return _text('请求头名称不能为空', 'Header name cannot be empty');
      }
      if (ModelProviderConfigService.isForbiddenCustomHeaderName(name)) {
        return _text(
          '不支持 $name，请改用其他请求头',
          '$name is not allowed. Use a different header.',
        );
      }
      final normalized = ModelProviderConfigService.normalizeCustomHeaderName(
        name,
      );
      if (!seen.add(normalized)) {
        return _text(
          '请求头名称不能重复（大小写不敏感）',
          'Header names must be unique, ignoring case.',
        );
      }
    }
    return null;
  }

  Map<String, String>? _validatedCustomHeadersDraft() {
    if (_computeCustomHeadersValidationError() != null) {
      return null;
    }
    final normalized = <String, String>{};
    for (final entry in _customHeaderEntries) {
      final name = entry.nameController.text.trim();
      if (name.isEmpty) continue;
      normalized[name] = entry.valueController.text;
    }
    return ModelProviderConfigService.normalizeCustomHeaders(normalized);
  }

  String? get _missingConfigItem {
    final hasKey =
        _apiKeyController.text.trim().isNotEmpty ||
        (!_apiKeyDirty && (_profile?.hasApiKey ?? false));
    final hasHeaders =
        _customHeaderEntries.any(
          (e) => e.nameController.text.trim().isNotEmpty,
        ) ||
        (!_customHeadersDirty && (_profile?.hasCustomHeaders ?? false));
    return describeMissingConfigItem(
      sourceType: _selectedSourceType,
      baseUrl: _baseUrlController.text,
      hasApiKey: hasKey,
      hasCustomHeaders: hasHeaders,
      chatGptReady: _profile?.ready ?? false,
      customHeadersError: _customHeadersErrorText,
      english: _isEnglish,
    );
  }

  Future<bool> _persistProfileDraft({bool silentToast = true}) async {
    if (_isSelectingPreset || _readOnly) return !_readOnly;
    _autoSaveTimer?.cancel();
    _autoSaveTimer = null;

    if (_isSavingProfile) {
      _saveQueued = true;
      return false;
    }

    do {
      _saveQueued = false;
      final current = _profile;
      final nextName = _nameController.text.trim().isNotEmpty
          ? _nameController.text.trim()
          : _selectedPreset.resolvedDefaultName(english: _isEnglish);
      final rawBaseUrl = _isChatGptProvider
          ? ChatGptOAuthService.baseUrl
          : _baseUrlController.text.trim();
      final nextBaseUrl =
          ModelProviderConfigService.normalizeApiBase(rawBaseUrl) ?? '';
      if (!_isChatGptProvider &&
          rawBaseUrl.isNotEmpty &&
          nextBaseUrl.isEmpty) {
        if (mounted && !_isDisposing) {
          final invalidBaseUrlMsg = context.l10n.modelProviderInvalidBaseUrl;
          setState(() {
            _saveState = ProviderSaveState.failed;
            _saveErrorDiagnosis = ProviderErrorDiagnosis(
              category: ProviderErrorCategory.address,
              categoryLabel: _text('地址格式有误', 'Invalid URL'),
              summary: invalidBaseUrlMsg,
              hint: _text(
                'API 地址需以 http:// 或 https:// 开头，例如 https://api.openai.com/v1',
                'API URL must start with http:// or https://, e.g. https://api.openai.com/v1',
              ),
            );
          });
          if (!silentToast) {
            showToast(invalidBaseUrlMsg, type: ToastType.error);
          }
        }
        return false;
      }

      if (_customHeadersErrorText != null) {
        if (_canUpdateUi) {
          setState(() {
            _saveState = ProviderSaveState.failed;
            _saveErrorDiagnosis = ProviderErrorDiagnosis(
              category: ProviderErrorCategory.general,
              categoryLabel: _text('请求头配置有误', 'Invalid Custom Headers'),
              summary: _customHeadersErrorText!,
              hint: _text(
                '请展开“高级设置”修正自定义请求头后再保存。',
                'Expand Advanced Settings to fix custom headers before saving.',
              ),
            );
            _advancedExpanded = true;
          });
          if (!silentToast) {
            showToast(_customHeadersErrorText!, type: ToastType.error);
          }
        }
        return false;
      }

      final nextApiKey = _apiKeyController.text.trim();
      final nextCustomHeaders =
          _validatedCustomHeadersDraft() ??
          ModelProviderConfigService.normalizeCustomHeaders(
            current?.customHeaders ?? const <String, String>{},
          );
      final currentBaseUrl = current == null
          ? ''
          : (ModelProviderConfigService.normalizeApiBase(current.baseUrl) ??
                '');

      if (!_isNew &&
          current != null &&
          nextName == current.name &&
          nextBaseUrl == currentBaseUrl &&
          !_apiKeyDirty &&
          !_customHeadersDirty &&
          _selectedSourceType == current.sourceType &&
          _selectedProtocolType == current.protocolType &&
          _selectedWireApi == current.wireApi) {
        if (_canUpdateUi) {
          setState(() {
            _saveState = ProviderSaveState.saved;
            _saveErrorDiagnosis = null;
          });
        }
        return true;
      }

      final targetId = current?.id ?? widget.reusePlaceholderProfileId;
      if (_canUpdateUi) {
        setState(() {
          _isSavingProfile = true;
          _saveState = ProviderSaveState.saving;
          _saveErrorDiagnosis = null;
        });
      } else {
        _isSavingProfile = true;
      }

      try {
        final saved = await ModelProviderConfigService.saveProfile(
          id: targetId,
          name: nextName,
          baseUrl: rawBaseUrl,
          apiKey: !_isChatGptProvider && (_apiKeyDirty || _isNew)
              ? nextApiKey
              : null,
          clearApiKey: _isChatGptProvider,
          clearCustomHeaders: _isChatGptProvider,
          customHeaders: !_isChatGptProvider && (_customHeadersDirty || _isNew)
              ? nextCustomHeaders
              : null,
          sourceType: _selectedSourceType,
          protocolType: _selectedProtocolType,
          wireApi: _selectedWireApi,
        );
        _profile = saved;
        _isNew = false;
        _apiKeyDirty = false;
        _customHeadersDirty = false;
        widget.onProfileChanged?.call(saved);
        if (!_canUpdateUi) return true;
        setState(() {
          _saveState = ProviderSaveState.saved;
          _saveErrorDiagnosis = null;
        });
        if (!silentToast) {
          showToast(
            _text('连接配置已保存', 'Connection settings saved'),
            type: ToastType.success,
          );
        }
      } catch (error) {
        if (_canUpdateUi) {
          final diagnosis = ProviderErrorDiagnosis.analyze(
            error,
            english: _isEnglish,
            fallbackSummary: _text('保存服务商失败', 'Failed to save provider'),
          );
          setState(() {
            _saveState = ProviderSaveState.failed;
            _saveErrorDiagnosis = diagnosis;
          });
          if (!silentToast) {
            showToast(diagnosis.summary, type: ToastType.error);
          }
        }
        return false;
      } finally {
        _isSavingProfile = false;
        if (_canUpdateUi) {
          setState(() {});
        }
      }
    } while (_saveQueued && mounted);
    return true;
  }

  Future<void> _persistManualModelIds() async {
    final current = _profile;
    if (current == null) return;
    try {
      await ModelProviderConfigService.saveManualModelIds(
        profileId: current.id,
        ids: _manualModelIds,
      );
    } catch (_) {
      // no-op
    }
  }

  void _notifyModelsChanged() {
    final current = _profile;
    if (current == null) return;
    widget.onModelsChanged?.call(
      current.id,
      _remoteModels,
      _manualModelIds,
      _hiddenChatModelIds,
    );
  }

  Future<void> _selectPreset(String value) async {
    if (_isSelectingPreset || _readOnly) return;
    final selected = kProviderPresets.firstWhere(
      (option) => option.value == value,
      orElse: () => kProviderPresets.first,
    );
    final isOfficialSelection = selected.baseUrl.isNotEmpty;
    final isChatGptSelection = selected.isChatGptOAuth;
    final isGenericOpenAiSelection =
        selected.value == 'openai_compatible' || selected.isCustom;
    final nextProtocolType = selected.protocolType;
    final nextWireApi = isGenericOpenAiSelection
        ? _normalizeWireApiForProtocol(nextProtocolType, _selectedWireApi)
        : _normalizeWireApiForProtocol(selected.protocolType, selected.wireApi);

    if (_selectedPresetValue == selected.value &&
        _selectedSourceType == selected.sourceType &&
        _selectedProtocolType == nextProtocolType &&
        _selectedWireApi == nextWireApi) {
      return;
    }

    _autoSaveTimer?.cancel();
    final previousPresetValue = _selectedPresetValue;
    final previousSourceType = _selectedSourceType;
    final previousProtocolType = _selectedProtocolType;
    final previousWireApi = _selectedWireApi;
    final previousName = _nameController.text;
    final previousBaseUrl = _baseUrlController.text;

    final shouldAutoFillName =
        isDefaultPresetName(_nameController.text) || isOfficialSelection;
    final nextDefaultName = _deduplicateName(
      selected.resolvedDefaultName(english: _isEnglish),
    );

    setState(() {
      _isSelectingPreset = true;
      _selectedPresetValue = selected.value;
      _selectedSourceType = selected.sourceType;
      _selectedProtocolType = nextProtocolType;
      _selectedWireApi = nextWireApi;
      _saveErrorDiagnosis = null;
    });

    if (shouldAutoFillName) {
      _syncController(_nameController, nextDefaultName);
    }
    if (isOfficialSelection) {
      _syncController(_baseUrlController, selected.baseUrl);
    } else if (selected.isCustom &&
        kProviderPresets.any(
          (p) => !p.isCustom && p.baseUrl == previousBaseUrl.trim(),
        )) {
      _syncController(_baseUrlController, '');
    }

    if (_isNew && _profile == null && !isOfficialSelection) {
      setState(() {
        _isSelectingPreset = false;
        _saveState = ProviderSaveState.dirty;
      });
      return;
    }

    final current = _profile;
    final targetId = current?.id ?? widget.reusePlaceholderProfileId;
    try {
      final saved = await ModelProviderConfigService.saveProfile(
        id: targetId,
        name: _nameController.text.trim().isEmpty
            ? (current?.name ?? nextDefaultName)
            : _nameController.text.trim(),
        baseUrl: _baseUrlController.text.trim(),
        apiKey: !isChatGptSelection && _apiKeyDirty
            ? _apiKeyController.text.trim()
            : null,
        clearApiKey: isChatGptSelection,
        clearCustomHeaders: isChatGptSelection,
        customHeaders: !isChatGptSelection && _customHeadersDirty
            ? (_validatedCustomHeadersDraft() ?? const <String, String>{})
            : null,
        sourceType: selected.sourceType,
        protocolType: nextProtocolType,
        wireApi: nextWireApi,
      );
      if (!mounted) return;
      setState(() {
        _profile = saved;
        _isNew = false;
        _selectedSourceType = saved.sourceType;
        _selectedProtocolType = saved.protocolType;
        _selectedWireApi = _normalizeWireApiForProtocol(
          saved.protocolType,
          saved.wireApi,
        );
        _saveState = ProviderSaveState.saved;
      });
      widget.onProfileChanged?.call(saved);
      if (isChatGptSelection) {
        _syncController(_apiKeyController, '');
        _replaceCustomHeaderEntries(const {});
        setState(() {
          _remoteModels = const [];
          _modelFetchErrorDiagnosis = null;
        });
      }
      _apiKeyDirty = false;
      _customHeadersDirty = false;
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _selectedPresetValue = previousPresetValue;
        _selectedSourceType = previousSourceType;
        _selectedProtocolType = previousProtocolType;
        _selectedWireApi = previousWireApi;
      });
      _syncController(_nameController, previousName);
      _syncController(_baseUrlController, previousBaseUrl);
    } finally {
      if (mounted) setState(() => _isSelectingPreset = false);
    }
  }

  Future<void> _selectWireProtocol(ProviderWireProtocol protocol) async {
    if (_readOnly || _isChatGptProvider) return;
    final nextProtocolType = protocol.protocolType;
    final nextWireApi = protocol.wireApi;
    if (_selectedProtocolType == nextProtocolType &&
        _selectedWireApi == nextWireApi) {
      return;
    }

    final previousProtocolType = _selectedProtocolType;
    final previousWireApi = _selectedWireApi;
    final previousPreset = _selectedPresetValue;

    setState(() {
      _selectedProtocolType = nextProtocolType;
      _selectedWireApi = nextWireApi;
      if (nextProtocolType == 'anthropic' && !_selectedPreset.isCustom) {
        _selectedPresetValue = 'anthropic';
      } else if (nextProtocolType == 'gemini_interactions' &&
          !_selectedPreset.isCustom) {
        _selectedPresetValue = 'gemini_interactions';
      } else if (nextProtocolType == 'openai_compatible' &&
          !_selectedPreset.isCustom) {
        _selectedPresetValue = 'openai_compatible';
      }
    });

    final current = _profile;
    if (_isNew &&
        current == null &&
        _baseUrlController.text.trim().isEmpty) {
      setState(() => _saveState = ProviderSaveState.dirty);
      return;
    }

    try {
      final saved = await ModelProviderConfigService.saveProfile(
        id: current?.id ?? widget.reusePlaceholderProfileId,
        name: _nameController.text.trim().isEmpty
            ? (current?.name ??
                  _selectedPreset.resolvedDefaultName(english: _isEnglish))
            : _nameController.text.trim(),
        baseUrl: _baseUrlController.text.trim(),
        apiKey: _apiKeyController.text.trim(),
        customHeaders:
            _validatedCustomHeadersDraft() ??
            ModelProviderConfigService.normalizeCustomHeaders(
              current?.customHeaders ?? const <String, String>{},
            ),
        sourceType: BuiltinOfficialProviderCatalog.customKey,
        protocolType: nextProtocolType,
        wireApi: nextWireApi,
      );
      if (!mounted) return;
      setState(() {
        _profile = saved;
        _isNew = false;
        _selectedSourceType = saved.sourceType;
        _selectedProtocolType = saved.protocolType;
        _selectedWireApi = _normalizeWireApiForProtocol(
          saved.protocolType,
          saved.wireApi,
        );
        _saveState = ProviderSaveState.saved;
      });
      widget.onProfileChanged?.call(saved);
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _selectedProtocolType = previousProtocolType;
        _selectedWireApi = previousWireApi;
        _selectedPresetValue = previousPreset;
      });
    }
  }

  double _measurePopupWidth(
    BuildContext context,
    Iterable<String> labels,
    double maxWidth,
  ) {
    final style = TextStyle(
      fontSize: OmniMenuStyle.labelStyle.fontSize,
      fontWeight: OmniMenuStyle.labelStyle.fontWeight,
    );
    final textDirection = Directionality.of(context);
    var longestLabelWidth = 0.0;
    for (final label in labels) {
      final painter = TextPainter(
        text: TextSpan(text: label, style: style),
        maxLines: 1,
        textDirection: textDirection,
      )..layout();
      longestLabelWidth = math.max(longestLabelWidth, painter.width);
    }
    final contentWidth = longestLabelWidth + 88.0;
    return contentWidth.clamp(_kProviderPopupMinWidth, maxWidth).toDouble();
  }

  Future<void> _openPresetMenu(BuildContext anchorContext) async {
    if (_readOnly) return;
    final overlay =
        Overlay.of(context).context.findRenderObject() as RenderBox?;
    final anchorBox = anchorContext.findRenderObject() as RenderBox?;
    if (overlay == null || anchorBox == null || !anchorBox.hasSize) return;

    final topLeft = anchorBox.localToGlobal(Offset.zero, ancestor: overlay);
    final bottomRight = anchorBox.localToGlobal(
      anchorBox.size.bottomRight(Offset.zero),
      ancestor: overlay,
    );
    final anchorRect = Rect.fromPoints(topLeft, bottomRight);
    final availablePopupWidth = math.max(
      _kProviderPopupMinWidth,
      overlay.size.width - _kProviderPopupHorizontalMargin * 2,
    );
    final measuredPopupWidth = _measurePopupWidth(
      context,
      kProviderPresets.map((o) => o.displayLabel(english: _isEnglish)),
      availablePopupWidth,
    );
    final popupWidth = math
        .max(anchorRect.width, measuredPopupWidth)
        .clamp(_kProviderPopupMinWidth, availablePopupWidth)
        .toDouble();
    final estimatedHeight = (kProviderPresets.length * 46 + 24)
        .clamp(120.0, _kProviderPopupMaxHeight)
        .toDouble();

    final selected = await showOmniMenu<String>(
      context: context,
      sourceContext: anchorContext,
      anchor: anchorRect,
      width: popupWidth,
      padding: EdgeInsets.zero,
      items: [
        _PresetMenuPopupEntry(
          width: popupWidth,
          estimatedHeight: estimatedHeight,
          options: kProviderPresets,
          selectedValue: _selectedPreset.value,
          english: _isEnglish,
        ),
      ],
    );
    if (selected == null || !mounted) return;
    await _selectPreset(selected);
  }

  Future<void> _openWireProtocolMenu(BuildContext anchorContext) async {
    if (_readOnly) return;
    final overlay =
        Overlay.of(context).context.findRenderObject() as RenderBox?;
    final anchorBox = anchorContext.findRenderObject() as RenderBox?;
    if (overlay == null || anchorBox == null || !anchorBox.hasSize) return;

    final topLeft = anchorBox.localToGlobal(Offset.zero, ancestor: overlay);
    final bottomRight = anchorBox.localToGlobal(
      anchorBox.size.bottomRight(Offset.zero),
      ancestor: overlay,
    );
    final anchorRect = Rect.fromPoints(topLeft, bottomRight);
    final availablePopupWidth = math.max(
      _kProviderPopupMinWidth,
      overlay.size.width - _kProviderPopupHorizontalMargin * 2,
    );
    final options = ProviderWireProtocol.values;
    final measuredPopupWidth = _measurePopupWidth(
      context,
      options.map((o) => o.label),
      availablePopupWidth,
    );
    final popupWidth = math
        .max(anchorRect.width, measuredPopupWidth)
        .clamp(_kProviderPopupMinWidth, availablePopupWidth)
        .toDouble();
    final estimatedHeight = (options.length * 46 + 24)
        .clamp(120.0, _kProviderPopupMaxHeight)
        .toDouble();

    final selected = await showOmniMenu<ProviderWireProtocol>(
      context: context,
      sourceContext: anchorContext,
      anchor: anchorRect,
      width: popupWidth,
      padding: EdgeInsets.zero,
      items: [
        _WireProtocolPopupEntry(
          width: popupWidth,
          estimatedHeight: estimatedHeight,
          options: options,
          selectedValue: _selectedWireProtocol,
        ),
      ],
    );
    if (selected == null || !mounted) return;
    await _selectWireProtocol(selected);
  }

  List<ProviderModelItem> get _allModelItems {
    final items = <ProviderModelItem>[];
    final seen = <String>{};
    final manualModels = _manualModels.isNotEmpty || _manualModelIds.isEmpty
        ? _manualModels
        : _manualModelIds
              .map(
                (id) => ProviderModelOption(
                  id: id,
                  displayName: id,
                  ownedBy: 'manual',
                ),
              )
              .toList();

    for (final model in manualModels) {
      final normalized = model.id.trim();
      if (!ModelProviderConfigService.isValidModelName(normalized)) continue;
      if (seen.add(normalized)) {
        items.add(
          ProviderModelItem(model: model, source: ProviderModelSource.manual),
        );
      }
    }
    for (final model in _remoteModels) {
      final normalized = model.id.trim();
      if (!ModelProviderConfigService.isValidModelName(normalized)) continue;
      if (seen.add(normalized)) {
        items.add(
          ProviderModelItem(model: model, source: ProviderModelSource.remote),
        );
      }
    }
    return items;
  }

  List<ProviderModelItem> get _filteredModelItems {
    final query = _modelSearchController.text.trim().toLowerCase();
    if (query.isEmpty) return _allModelItems;
    return _allModelItems.where((item) {
      return item.id.toLowerCase().contains(query) ||
          item.model.displayName.toLowerCase().contains(query) ||
          (item.model.family?.toLowerCase().contains(query) ?? false) ||
          (item.model.ownedBy?.toLowerCase().contains(query) ?? false);
    }).toList();
  }

  List<MapEntry<String, List<ProviderModelItem>>> get _filteredModelGroups {
    final groups = <String, List<ProviderModelItem>>{};
    final current = _profile;
    for (final item in _filteredModelItems) {
      final groupKey = ModelVendorCatalog.groupKeyFor(
        item.id,
        ownedBy: item.model.ownedBy,
        providerId: item.model.modelsDevProviderId ?? current?.id,
        providerName: current?.name ?? _nameController.text,
      );
      groups.putIfAbsent(groupKey, () => <ProviderModelItem>[]).add(item);
    }
    final entries = groups.entries.toList()
      ..sort((a, b) {
        final orderCompare = ModelVendorCatalog.orderOf(a.key)
            .compareTo(ModelVendorCatalog.orderOf(b.key));
        if (orderCompare != 0) return orderCompare;
        return a.key.compareTo(b.key);
      });
    return entries;
  }

  String _modelGroupExpansionKey(String groupName) {
    final profileKey = (_profile?.id.trim().isNotEmpty ?? false)
        ? _profile!.id.trim()
        : 'default';
    return '$profileKey::$groupName';
  }

  void _toggleModelGroup(String groupName) {
    setState(() {
      final key = _modelGroupExpansionKey(groupName);
      final current = _expandedModelGroups[key] ?? true;
      _expandedModelGroups[key] = !current;
    });
  }

  void _toggleAllModelGroups() {
    final groups = _filteredModelGroups;
    if (groups.isEmpty) return;
    final allCollapsed = groups.every(
      (g) => !(_expandedModelGroups[_modelGroupExpansionKey(g.key)] ?? true),
    );
    setState(() {
      for (final group in groups) {
        _expandedModelGroups[_modelGroupExpansionKey(group.key)] = allCollapsed;
      }
    });
  }

  void _scheduleMetadataRefresh({
    required ModelProviderProfileSummary profile,
    required List<ProviderModelOption> manualModels,
    required List<ProviderModelOption> remoteModels,
  }) {
    if (manualModels.isEmpty && remoteModels.isEmpty) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted ||
          _profile?.id != profile.id ||
          !identical(_manualModels, manualModels) ||
          !identical(_remoteModels, remoteModels)) {
        return;
      }
      unawaited(
        _refreshMetadata(
          profile: profile,
          manualModels: manualModels,
          remoteModels: remoteModels,
        ),
      );
    });
  }

  Future<void> _refreshMetadata({
    required ModelProviderProfileSummary profile,
    required List<ProviderModelOption> manualModels,
    required List<ProviderModelOption> remoteModels,
  }) async {
    final enriched = await Future.wait<List<ProviderModelOption>>([
      ModelProviderConfigService.enrichModelsForProfile(
        profileId: profile.id,
        providerName: profile.name,
        apiBase: profile.baseUrl,
        models: manualModels,
      ),
      ModelProviderConfigService.enrichModelsForProfile(
        profileId: profile.id,
        providerName: profile.name,
        apiBase: profile.baseUrl,
        models: remoteModels,
      ),
    ]);
    if (!await waitForPageReady() ||
        !mounted ||
        _profile?.id != profile.id ||
        !identical(_manualModels, manualModels) ||
        !identical(_remoteModels, remoteModels)) {
      return;
    }
    setState(() {
      _manualModels = enriched[0];
      _remoteModels = enriched[1];
    });
    _notifyModelsChanged();
  }

  Future<void> _fetchModelsLocalized({bool silentError = false}) async {
    if (_isFetchingModels) return;

    if (!_isChatGptProvider) {
      final baseUrl = _baseUrlController.text.trim();
      if (baseUrl.isEmpty) {
        if (!silentError) {
          showToast(
            context.l10n.modelProviderBaseUrlRequired,
            type: ToastType.warning,
          );
        }
        return;
      }
      if (!ModelProviderConfigService.isValidApiBase(baseUrl)) {
        if (!silentError) {
          showToast(
            context.l10n.modelProviderInvalidBaseUrl,
            type: ToastType.error,
          );
        }
        return;
      }
      if (_validatedCustomHeadersDraft() == null) {
        if (!silentError) {
          showToast(
            _customHeadersErrorText ??
                _text('自定义请求头配置无效', 'Custom headers are invalid'),
            type: ToastType.error,
          );
        }
        return;
      }
    }

    if ((_shouldAutoSaveDraft || _isNew) &&
        !await _persistProfileDraft(silentToast: true)) {
      return;
    }
    if (!mounted) return;
    final savedProfile = _profile;
    if (savedProfile == null ||
        (_isChatGptProvider && !savedProfile.ready)) {
      return;
    }

    final requestedRevision = savedProfile.revision;
    final requestedDraftMutation = _draftMutationRevision;
    setState(() {
      _isFetchingModels = true;
      _modelFetchErrorDiagnosis = null;
    });

    try {
      final models = await ModelProviderConfigService.fetchModels(
        profileId: savedProfile.id,
        providerName: savedProfile.name,
      );
      if (!mounted ||
          _profile?.id != savedProfile.id ||
          _profile?.revision != requestedRevision ||
          _draftMutationRevision != requestedDraftMutation ||
          _shouldAutoSaveDraft) {
        return;
      }
      setState(() {
        _remoteModels = models;
        _modelFetchErrorDiagnosis = null;
      });
      _notifyModelsChanged();
      _scheduleMetadataRefresh(
        profile: savedProfile,
        manualModels: _manualModels,
        remoteModels: _remoteModels,
      );
      if (!silentError) {
        final message = models.isEmpty
            ? context.l10n.modelsNoAvailableModels
            : context.l10n.modelProviderFetchedModels(models.length);
        showToast(
          message,
          type: models.isEmpty ? ToastType.warning : ToastType.success,
        );
      }
    } catch (error) {
      if (!mounted ||
          _profile?.id != savedProfile.id ||
          _profile?.revision != requestedRevision ||
          _draftMutationRevision != requestedDraftMutation ||
          _shouldAutoSaveDraft) {
        return;
      }
      final diagnosis = ProviderErrorDiagnosis.analyze(
        error,
        english: _isEnglish,
      );
      setState(() {
        _modelFetchErrorDiagnosis = diagnosis;
      });
      if (!silentError) {
        showToast(diagnosis.summary, type: ToastType.error);
      }
    } finally {
      if (mounted) {
        setState(() => _isFetchingModels = false);
      }
    }
  }

  Future<void> _promptAddModel() async {
    if (_readOnly) return;
    if ((_isNew || _profile == null) &&
        !await _persistProfileDraft(silentToast: false)) {
      return;
    }
    if (!mounted) return;
    final current = _profile;
    if (current == null) return;

    final modelId = await showOmniGlassCardSheet<String>(
      context: context,
      useRootNavigator: false,
      builder: (_) => const _AddModelIdDialog(),
    );
    if (!mounted || modelId == null) return;

    final normalized = modelId.trim();
    if (!ModelProviderConfigService.isValidModelName(normalized)) {
      showToast(context.l10n.modelIdEmpty, type: ToastType.error);
      return;
    }
    final existsInManual = _manualModelIds.any((item) => item == normalized);
    final existsInRemote = _remoteModels.any((item) => item.id == normalized);
    if (existsInManual || existsInRemote) {
      showToast(context.l10n.modelAlreadyExists, type: ToastType.warning);
      return;
    }

    final nextManual = [..._manualModelIds, normalized];
    final nextManualModels = await ModelProviderConfigService
        .enrichModelsForProfile(
          profileId: current.id,
          providerName: current.name,
          apiBase: current.baseUrl,
          models: nextManual
              .map(
                (id) => ProviderModelOption(
                  id: id,
                  displayName: id,
                  ownedBy: 'manual',
                ),
              )
              .toList(),
        );
    if (!mounted) return;
    setState(() {
      _manualModelIds = nextManual;
      _manualModels = nextManualModels;
    });
    await ModelProviderConfigService.saveManualModelIds(
      profileId: current.id,
      ids: nextManual,
    );
    _notifyModelsChanged();
    if (!mounted) return;
    showToast(context.l10n.modelAdded, type: ToastType.success);
  }

  Future<void> _deleteManualModels(Iterable<String> manualIds) async {
    final current = _profile;
    if (current == null) return;
    final toRemove = manualIds.toSet();
    final prevManual = List<String>.from(_manualModelIds);
    final prevManualModels = List<ProviderModelOption>.from(_manualModels);

    final nextManual = _manualModelIds
        .where((id) => !toRemove.contains(id))
        .toList();
    final nextManualModels = _manualModels
        .where((m) => !toRemove.contains(m.id))
        .toList();

    setState(() {
      _manualModelIds = nextManual;
      _manualModels = nextManualModels;
    });

    try {
      await ModelProviderConfigService.saveManualModelIds(
        profileId: current.id,
        ids: nextManual,
      );
      _notifyModelsChanged();
      if (!mounted) return;
      showToast(context.l10n.modelDeleted, type: ToastType.success);
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _manualModelIds = prevManual;
        _manualModels = prevManualModels;
      });
      showToast(context.l10n.modelDeleteFailed, type: ToastType.error);
    }
  }

  Future<bool> _setModelVisibleInChat(
    ProviderModelOption model,
    bool visible,
  ) async {
    final current = _profile;
    if (current == null) return false;
    final previous = Set<String>.from(_hiddenChatModelIds);
    final next = Set<String>.from(_hiddenChatModelIds);
    if (visible) {
      next.remove(model.id);
    } else {
      next.add(model.id);
    }
    if (previous.length == next.length && previous.containsAll(next)) {
      return true;
    }

    setState(() => _hiddenChatModelIds = next);
    try {
      await ModelProviderConfigService.saveHiddenChatModelIds(
        profileId: current.id,
        ids: next.toList(),
      );
      _notifyModelsChanged();
      if (!mounted) return true;
      showToast(
        visible
            ? _text('已设为在聊天中显示', 'Shown in chat')
            : _text('已从聊天选择器隐藏', 'Hidden from chat selector'),
        type: ToastType.success,
      );
      return true;
    } catch (_) {
      if (!mounted) return false;
      setState(() => _hiddenChatModelIds = previous);
      showToast(
        _text('更新聊天模型显示状态失败', 'Failed to update chat model visibility'),
        type: ToastType.error,
      );
      return false;
    }
  }

  Future<void> _batchSetVisibleInChat(
    Iterable<String> modelIds,
    bool visible,
  ) async {
    final current = _profile;
    if (current == null) return;
    final previous = Set<String>.from(_hiddenChatModelIds);
    final next = Set<String>.from(_hiddenChatModelIds);
    if (visible) {
      next.removeAll(modelIds);
    } else {
      next.addAll(modelIds);
    }
    if (previous.length == next.length && previous.containsAll(next)) return;

    setState(() => _hiddenChatModelIds = next);
    try {
      await ModelProviderConfigService.saveHiddenChatModelIds(
        profileId: current.id,
        ids: next.toList(),
      );
      _notifyModelsChanged();
      if (!mounted) return;
      showToast(
        visible
            ? _text('已开启所选模型在聊天中显示', 'Enabled selected models in chat')
            : _text('已隐藏所选聊天模型', 'Hidden selected models from chat'),
        type: ToastType.success,
      );
    } catch (_) {
      if (!mounted) return;
      setState(() => _hiddenChatModelIds = previous);
      showToast(
        _text('更新聊天模型显示状态失败', 'Failed to update chat model visibility'),
        type: ToastType.error,
      );
    }
  }

  Future<ModelTestState> _testModelAvailability(
    ProviderModelOption model,
  ) async {
    if (_shouldAutoSaveDraft) {
      await _persistProfileDraft(silentToast: true);
    }
    final current = _profile;
    if (current == null) {
      return ModelTestState(
        status: ModelTestStatus.failed,
        message: _text('请先保存连接配置', 'Save connection settings first'),
      );
    }

    setState(() {
      _modelTestStates[model.id] = const ModelTestState(
        status: ModelTestStatus.testing,
      );
    });

    try {
      final result = await ModelProviderConfigService.checkModelAvailability(
        profileId: current.id,
        model: model.id,
        apiBase: current.baseUrl,
      );
      final nextState = result.available
          ? ModelTestState(
              status: ModelTestStatus.passed,
              statusCode: result.code,
              message: _text(
                '测试通过：模型可正常响应',
                'Verified: Model responded normally',
              ),
            )
          : ModelTestState(
              status: ModelTestStatus.failed,
              statusCode: result.code,
              message: result.message.isNotEmpty
                  ? result.message
                  : _text('模型未通过可用性检测', 'Model failed availability check'),
              diagnosis: ProviderErrorDiagnosis.analyze(
                result.message,
                english: _isEnglish,
                statusCode: result.code,
              ),
            );
      if (mounted) {
        setState(() {
          _modelTestStates[model.id] = nextState;
        });
      }
      return nextState;
    } catch (error) {
      final diagnosis = ProviderErrorDiagnosis.analyze(
        error,
        english: _isEnglish,
      );
      final nextState = ModelTestState(
        status: ModelTestStatus.failed,
        message: diagnosis.summary,
        diagnosis: diagnosis,
      );
      if (mounted) {
        setState(() {
          _modelTestStates[model.id] = nextState;
        });
      }
      return nextState;
    }
  }

  Future<void> _refreshChatGptProfile(String profileId) async {
    final payload = await ModelProviderConfigService.listProfiles();
    if (!mounted || !_isChatGptProvider) return;
    final updated = payload.profiles.firstWhere(
      (p) => p.id == profileId,
      orElse: () => _profile ?? payload.profiles.first,
    );
    setState(() {
      _profile = updated;
      _remoteModels = const [];
      _modelFetchErrorDiagnosis = null;
    });
    widget.onProfileChanged?.call(updated);
    if (updated.ready) {
      await _fetchModelsLocalized(silentError: true);
    }
  }

  Future<void> _promptRenameProfile() async {
    final current = _profile;
    if (current == null || _readOnly) return;
    final nextName = (await AppDialog.input(
      context,
      title: _text('重命名服务商', 'Rename Provider'),
      initialValue: _nameController.text.trim(),
      hintText: context.l10n.modelProviderNameHint,
      confirmText: _text('保存', 'Save'),
      cancelText: context.trLegacy('取消'),
    ))?.trim();
    if (nextName == null || nextName.isEmpty || !mounted) return;
    _syncController(_nameController, nextName);
    await _persistProfileDraft(silentToast: false);
  }

  Future<void> _confirmAndDeleteCurrentProfile() async {
    final current = _profile;
    if (current == null || _readOnly) return;
    final deleted = await showDeleteProviderDialog(
      context: context,
      profile: current,
      totalModelCount: _allModelItems.length,
    );
    if (deleted && mounted) {
      widget.onProfileDeleted?.call(current.id);
      Navigator.of(context).maybePop();
    }
  }

  Future<void> _openDetailHeaderMoreMenu(BuildContext anchorContext) async {
    final overlay =
        Overlay.of(context).context.findRenderObject() as RenderBox?;
    final anchorBox = anchorContext.findRenderObject() as RenderBox?;
    if (overlay == null || anchorBox == null || !anchorBox.hasSize) return;
    final topLeft = anchorBox.localToGlobal(Offset.zero, ancestor: overlay);
    final bottomRight = anchorBox.localToGlobal(
      anchorBox.size.bottomRight(Offset.zero),
      ancestor: overlay,
    );
    final anchorRect = Rect.fromPoints(topLeft, bottomRight);
    final action = await showOmniMenu<String>(
      context: context,
      sourceContext: anchorContext,
      anchor: anchorRect,
      width: 176,
      items: [
        buildProviderActionMenuItem(
          value: 'rename',
          icon: LucideIcons.pencil,
          label: _text('重命名', 'Rename'),
        ),
        buildProviderActionMenuItem(
          value: 'delete',
          destructive: true,
          icon: LucideIcons.trash2,
          label: _text('删除服务商', 'Delete Provider'),
        ),
      ],
    );
    if (!mounted || action == null) return;
    if (action == 'rename') {
      await _promptRenameProfile();
    } else if (action == 'delete') {
      await _confirmAndDeleteCurrentProfile();
    }
  }

  Future<void> _handleSaveAndOpenModels() async {
    FocusScope.of(context).unfocus();
    final wasNew = _isNew;
    final saved = await _persistProfileDraft(silentToast: false);
    if (!saved || !mounted) return;
    setState(() {
      _currentTab = ProviderDetailTab.models;
    });
    if ((wasNew || _remoteModels.isEmpty) && _missingConfigItem == null) {
      unawaited(_fetchModelsLocalized(silentError: false));
    }
  }

  void _handleTabChanged(ProviderDetailTab nextTab) {
    if (_currentTab == nextTab) return;
    FocusScope.of(context).unfocus();
    setState(() {
      _currentTab = nextTab;
    });
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final modelCount = _allModelItems.length;
    final modelsTabLabel = modelCount > 0
        ? _text('模型 ($modelCount)', 'Models ($modelCount)')
        : _text('模型', 'Models');

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        primary: true,
        onBackPressed: () => Navigator.of(context).maybePop(),
        titleWidget: ValueListenableBuilder<TextEditingValue>(
          valueListenable: _nameController,
          builder: (context, value, _) {
            final name = value.text.trim();
            final titleText = name.isNotEmpty
                ? name
                : (_isNew
                      ? _text('添加服务商', 'Add Provider')
                      : (_profile?.name ?? 'Provider'));
            return Text(
              titleText,
              key: const Key('provider-config-title-text'),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 17,
                fontWeight: FontWeight.w600,
                color: palette.textPrimary,
              ),
            );
          },
        ),
        actions: [
          if (!_isNew && !_readOnly)
            Padding(
              padding: const EdgeInsets.only(right: 16),
              child: Center(
                child: OmniCircleButton.popup(
                  key: const Key('provider-detail-more-button'),
                  icon: LucideIcons.ellipsis,
                  tooltip: _text('更多操作', 'More actions'),
                  onOpen: (anchorContext) =>
                      unawaited(_openDetailHeaderMoreMenu(anchorContext)),
                ),
              ),
            ),
        ],
      ),
      body: OmniContentWidth(
        child: SafeArea(
          top: false,
          bottom: false,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 8, 18, 6),
                child: OmniSegmentedSlider<ProviderDetailTab>(
                  keyPrefix: 'provider-detail-tab',
                  value: _currentTab,
                  options: [
                    OmniSegmentedOption<ProviderDetailTab>(
                      value: ProviderDetailTab.connection,
                      id: 'connection',
                      icon: LucideIcons.plug,
                      label: _text('连接', 'Connection'),
                    ),
                    OmniSegmentedOption<ProviderDetailTab>(
                      value: ProviderDetailTab.models,
                      id: 'models',
                      icon: LucideIcons.boxes,
                      label: modelsTabLabel,
                    ),
                  ],
                  onChanged: _handleTabChanged,
                ),
              ),
              Expanded(
                child: IndexedStack(
                  index: _currentTab == ProviderDetailTab.connection ? 0 : 1,
                  children: [
                    ProviderConnectionTab(
                      scrollController: _connectionScrollController,
                      profile: _profile,
                      isNew: _isNew,
                      readOnly: _readOnly,
                      isChatGptProvider: _isChatGptProvider,
                      selectedPreset: _selectedPreset,
                      selectedWireProtocol: _selectedWireProtocol,
                      showProtocolSelector: _showProtocolSelector,
                      nameController: _nameController,
                      baseUrlController: _baseUrlController,
                      apiKeyController: _apiKeyController,
                      nameFocusNode: _nameFocusNode,
                      baseUrlFocusNode: _baseUrlFocusNode,
                      apiKeyFocusNode: _apiKeyFocusNode,
                      obscureApiKey: _obscureApiKey,
                      onToggleObscureApiKey: () {
                        setState(() => _obscureApiKey = !_obscureApiKey);
                      },
                      saveState: _saveState,
                      saveErrorDiagnosis: _saveErrorDiagnosis,
                      missingConfigItem: _missingConfigItem,
                      advancedExpanded: _advancedExpanded,
                      onToggleAdvancedExpanded: () {
                        setState(
                          () => _advancedExpanded = !_advancedExpanded,
                        );
                      },
                      customHeaderEntries: _customHeaderEntries,
                      customHeadersErrorText: _customHeadersErrorText,
                      onAddCustomHeader: _addCustomHeaderEntry,
                      onRemoveCustomHeader: _removeCustomHeaderEntry,
                      onSelectPreset: (val) => unawaited(_selectPreset(val)),
                      onOpenPresetMenu: _openPresetMenu,
                      onOpenWireProtocolMenu: _openWireProtocolMenu,
                      onSave: () {
                        FocusScope.of(context).unfocus();
                        unawaited(_persistProfileDraft(silentToast: false));
                      },
                      onSaveAndManageModels: () =>
                          unawaited(_handleSaveAndOpenModels()),
                      beforeChatGptLogin: () =>
                          _persistProfileDraft(silentToast: true),
                      onChatGptAccountChanged: _refreshChatGptProfile,
                      isBusy: _isSavingProfile || _isSelectingPreset,
                    ),
                    ProviderModelsTab(
                      scrollController: _modelsScrollController,
                      profile: _profile,
                      readOnly: _readOnly,
                      isFetchingModels: _isFetchingModels,
                      canFetchModels:
                          !_isChatGptProvider || (_profile?.ready == true),
                      missingConfigItem: _missingConfigItem,
                      fetchErrorDiagnosis: _modelFetchErrorDiagnosis,
                      searchController: _modelSearchController,
                      allModelItems: _allModelItems,
                      filteredModelItems: _filteredModelItems,
                      filteredModelGroups: _filteredModelGroups,
                      hiddenChatModelIds: _hiddenChatModelIds,
                      expandedModelGroups: _expandedModelGroups,
                      modelTestStates: _modelTestStates,
                      onFetchModels: () =>
                          unawaited(_fetchModelsLocalized(silentError: false)),
                      onAddManualModel: () => unawaited(_promptAddModel()),
                      onSwitchToConnectionTab: () =>
                          _handleTabChanged(ProviderDetailTab.connection),
                      onSetModelVisibleInChat: _setModelVisibleInChat,
                      onBatchSetVisibleInChat: _batchSetVisibleInChat,
                      onDeleteManualModels: _deleteManualModels,
                      onToggleModelGroup: _toggleModelGroup,
                      onToggleAllModelGroups: _toggleAllModelGroups,
                      onTestModel: _testModelAvailability,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Shared delete confirmation dialog that inspects scene bindings and model
/// counts before deleting a provider, without silently switching bindings.
Future<bool> showDeleteProviderDialog({
  required BuildContext context,
  required ModelProviderProfileSummary profile,
  required int totalModelCount,
}) async {
  final english = Localizations.localeOf(context).languageCode == 'en';
  String t(String zh, String en) => english ? en : zh;

  List<SceneModelBindingEntry> affectedBindings = const [];
  try {
    final allBindings = await SceneModelConfigService.getSceneModelBindings();
    affectedBindings = allBindings
        .where((b) => b.providerProfileId == profile.id)
        .toList();
  } catch (_) {
    affectedBindings = const [];
  }
  if (!context.mounted) return false;

  final confirmed = await showOmniGlassCardSheet<bool>(
    context: context,
    useRootNavigator: false,
    builder: (sheetContext) {
      final palette = sheetContext.omniPalette;
      return OmniGlassAlertDialog(
        title: Text(sheetContext.l10n.modelDeleteProviderTitle),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(sheetContext.l10n.modelDeleteProviderMsg(profile.name)),
            const SizedBox(height: 10),
            Text(
              totalModelCount > 0
                  ? t(
                      '将移除该服务商的连接信息、密钥及 $totalModelCount 个模型配置。',
                      'This will remove the connection settings, credentials, and $totalModelCount configured models.',
                    )
                  : t(
                      '将移除该服务商的连接信息与密钥配置。',
                      'This will remove the connection settings and credentials for this provider.',
                    ),
              style: TextStyle(fontSize: 12.5, color: palette.textSecondary),
            ),
            if (affectedBindings.isNotEmpty) ...[
              const SizedBox(height: 10),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: const Color(0xFFD97706).withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(
                    color: const Color(0xFFD97706).withValues(alpha: 0.35),
                    width: 0.75,
                  ),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      t(
                        '受影响的场景绑定（不会自动改选其他服务商）：',
                        'Affected scene bindings (will not silently switch to another provider):',
                      ),
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: palette.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 4),
                    for (final binding in affectedBindings)
                      Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: Text(
                          '• ${sceneDisplayNameForId(binding.sceneId, english: english)} (${binding.modelId})',
                          style: TextStyle(
                            fontSize: 12,
                            color: palette.textSecondary,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ],
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(sheetContext).pop(false),
            child: Text(sheetContext.trLegacy('取消')),
          ),
          TextButton(
            key: const Key('provider-confirm-delete-button'),
            onPressed: () => Navigator.of(sheetContext).pop(true),
            style: TextButton.styleFrom(foregroundColor: AppColors.alertRed),
            child: Text(sheetContext.l10n.skillDelete),
          ),
        ],
      );
    },
  );
  if (confirmed != true || !context.mounted) return false;

  try {
    final allPayload = await ModelProviderConfigService.listProfiles();
    final byokProfiles = allPayload.profiles
        .where((p) => p.sourceType != 'omnibot_official')
        .toList();

    if (byokProfiles.length <= 1) {
      // Native store requires at least 1 profile entry; reset it to an
      // untouched blank placeholder so the UI cleanly transitions to empty state.
      await ModelProviderConfigService.saveProfile(
        id: profile.id,
        name: 'Provider 1',
        baseUrl: '',
        clearApiKey: true,
        clearCustomHeaders: true,
        sourceType: BuiltinOfficialProviderCatalog.customKey,
        protocolType: 'openai_compatible',
        wireApi: 'chat_completions',
      );
    } else {
      await ModelProviderConfigService.deleteProfile(profile.id);
    }
    await ModelProviderConfigService.saveManualModelIds(
      profileId: profile.id,
      ids: const [],
    );
    await ModelProviderConfigService.saveHiddenChatModelIds(
      profileId: profile.id,
      ids: const [],
    );
    if (context.mounted) {
      showToast(context.l10n.modelProviderDeleted, type: ToastType.success);
    }
    return true;
  } catch (e) {
    if (context.mounted) {
      showToast(
        context.l10n.modelProviderDeleteFailed(e),
        type: ToastType.error,
      );
    }
    return false;
  }
}

class _PresetMenuPopupEntry extends PopupMenuEntry<String> {
  const _PresetMenuPopupEntry({
    required this.width,
    required this.estimatedHeight,
    required this.options,
    required this.selectedValue,
    required this.english,
  });

  final double width;
  final double estimatedHeight;
  final List<ProviderPresetOption> options;
  final String selectedValue;
  final bool english;

  @override
  double get height => estimatedHeight;

  @override
  bool represents(String? value) => false;

  @override
  State<_PresetMenuPopupEntry> createState() => _PresetMenuPopupEntryState();
}

class _PresetMenuPopupEntryState extends State<_PresetMenuPopupEntry> {
  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(120.0, widget.estimatedHeight)
            .toDouble();
    return SizedBox(
      key: const Key('provider-protocol-type-menu'),
      width: widget.width,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
        child: Scrollbar(
          child: ListView.builder(
            padding: OmniMenuStyle.panelPadding,
            itemCount: widget.options.length,
            itemBuilder: (context, index) {
              final option = widget.options[index];
              final selected = option.value == widget.selectedValue;
              final vendor = option.vendorKey != null
                  ? ModelVendorCatalog.byKey(option.vendorKey)
                  : null;
              return OmniMenuItem(
                onTap: () => Navigator.of(context).pop(option.value),
                leading: ProviderVendorIcon(vendor: vendor, size: 16),
                label: option.displayLabel(english: widget.english),
                selected: selected,
                trailing: selected ? const Icon(LucideIcons.check) : null,
              );
            },
          ),
        ),
      ),
    );
  }
}

class _WireProtocolPopupEntry extends PopupMenuEntry<ProviderWireProtocol> {
  const _WireProtocolPopupEntry({
    required this.width,
    required this.estimatedHeight,
    required this.options,
    required this.selectedValue,
  });

  final double width;
  final double estimatedHeight;
  final List<ProviderWireProtocol> options;
  final ProviderWireProtocol selectedValue;

  @override
  double get height => estimatedHeight;

  @override
  bool represents(ProviderWireProtocol? value) => value == selectedValue;

  @override
  State<_WireProtocolPopupEntry> createState() =>
      _WireProtocolPopupEntryState();
}

class _WireProtocolPopupEntryState extends State<_WireProtocolPopupEntry> {
  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(120.0, widget.estimatedHeight)
            .toDouble();
    return SizedBox(
      key: const Key('provider-wire-api-menu'),
      width: widget.width,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
        child: Scrollbar(
          child: ListView.builder(
            padding: OmniMenuStyle.panelPadding,
            itemCount: widget.options.length,
            itemBuilder: (context, index) {
              final option = widget.options[index];
              final selected = option == widget.selectedValue;
              return OmniMenuItem(
                onTap: () => Navigator.of(context).pop(option),
                label: option.label,
                selected: selected,
                trailing: selected ? const Icon(LucideIcons.check) : null,
              );
            },
          ),
        ),
      ),
    );
  }
}

class _AddModelIdDialog extends StatefulWidget {
  const _AddModelIdDialog();

  @override
  State<_AddModelIdDialog> createState() => _AddModelIdDialogState();
}

class _AddModelIdDialogState extends State<_AddModelIdDialog> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _focusNode.requestFocus();
      }
    });
  }

  @override
  void dispose() {
    _focusNode.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _close([String? value]) {
    _focusNode.unfocus();
    Navigator.of(context).pop(value);
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: true,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) _focusNode.unfocus();
      },
      child: OmniGlassAlertDialog(
        title: Text(context.l10n.modelIdHint),
        content: TextField(
          controller: _controller,
          focusNode: _focusNode,
          style: context.omniInputTextStyle,
          decoration: InputDecoration(hintText: context.l10n.modelIdHint),
          onSubmitted: (_) => _close(_controller.text.trim()),
        ),
        actions: [
          TextButton(
            onPressed: () => _close(),
            child: Text(context.trLegacy('取消')),
          ),
          TextButton(
            onPressed: () => _close(_controller.text.trim()),
            child: Text(context.l10n.modelAddButton),
          ),
        ],
      ),
    );
  }
}
