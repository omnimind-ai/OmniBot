import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:flutter_slidable/flutter_slidable.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/builtin_official_provider_catalog.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/model_vendor_catalog.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/popup_menu_anchor_position.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';
import 'package:ui/widgets/settings_section_title.dart';

const double _kProviderSwitchPopupMaxHeight = 320;
const double _kProviderTypePopupMinWidth = 200;
const double _kProviderTypePopupHorizontalMargin = 16;
const double _kProviderTypePopupTextFontSize = 13;

enum _ProviderModelSource { manual, remote }

class _ProviderTypeOption {
  const _ProviderTypeOption({
    required this.value,
    required this.label,
    required this.sourceType,
    required this.protocolType,
    required this.wireApi,
    this.baseUrl = '',
    this.providerName = '',
  });

  final String value;
  final String label;
  final String sourceType;
  final String baseUrl;
  final String providerName;
  final String protocolType;
  final String wireApi;
}

class _SelectionOption {
  const _SelectionOption({required this.value, required this.label});

  final String value;
  final String label;
}

const List<_ProviderTypeOption> _kProviderTypeOptions = <_ProviderTypeOption>[
  _ProviderTypeOption(
    value: 'deepseek',
    label: 'DeepSeek',
    sourceType: 'deepseek',
    baseUrl: 'https://api.deepseek.com',
    providerName: 'DeepSeek',
    protocolType: 'deepseek',
    wireApi: 'chat_completions',
  ),
  _ProviderTypeOption(
    value: 'mimo',
    label: 'Mimo',
    sourceType: 'mimo',
    baseUrl: 'https://api.xiaomimimo.com/v1',
    providerName: 'Mimo',
    protocolType: 'openai_compatible',
    wireApi: 'chat_completions',
  ),
  _ProviderTypeOption(
    value: 'moonshot',
    label: 'Kimi',
    sourceType: 'moonshot',
    baseUrl: 'https://api.moonshot.cn/v1',
    providerName: 'Kimi',
    protocolType: 'openai_compatible',
    wireApi: 'chat_completions',
  ),
  _ProviderTypeOption(
    value: 'minimax',
    label: 'MiniMax',
    sourceType: 'minimax',
    baseUrl: 'https://api.minimaxi.com/v1',
    providerName: 'MiniMax',
    protocolType: 'openai_compatible',
    wireApi: 'chat_completions',
  ),
  _ProviderTypeOption(
    value: 'bailian',
    label: '阿里百炼',
    sourceType: 'bailian',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    providerName: '阿里百炼',
    protocolType: 'openai_compatible',
    wireApi: 'chat_completions',
  ),
  _ProviderTypeOption(
    value: 'openai_compatible',
    label: 'OpenAI',
    sourceType: 'custom',
    protocolType: 'openai_compatible',
    wireApi: 'chat_completions',
  ),
  _ProviderTypeOption(
    value: 'anthropic',
    label: 'Anthropic',
    sourceType: 'custom',
    protocolType: 'anthropic',
    wireApi: 'chat_completions',
  ),
];

const List<_SelectionOption> _kOpenAiWireApiOptions = <_SelectionOption>[
  _SelectionOption(value: 'chat_completions', label: 'Chat Completions'),
  _SelectionOption(value: 'responses', label: 'Responses'),
];

class _ProviderModelItem {
  const _ProviderModelItem({required this.model, required this.source});

  final ProviderModelOption model;
  final _ProviderModelSource source;

  String get id => model.id;
}

class _EditableHeaderEntry {
  _EditableHeaderEntry({required this.id, String name = '', String value = ''})
    : nameController = TextEditingController(text: name),
      valueController = TextEditingController(text: value);

  final int id;
  final TextEditingController nameController;
  final TextEditingController valueController;
  final FocusNode nameFocusNode = FocusNode();
  final FocusNode valueFocusNode = FocusNode();

  void dispose() {
    nameController.dispose();
    valueController.dispose();
    nameFocusNode.dispose();
    valueFocusNode.dispose();
  }
}

class ModelProviderSettingPage extends StatefulWidget {
  const ModelProviderSettingPage({super.key});

  @override
  State<ModelProviderSettingPage> createState() =>
      _ModelProviderSettingPageState();
}

class _ModelProviderSettingPageState extends State<ModelProviderSettingPage> {
  static List<ModelProviderProfileSummary> _byokProfiles(
    List<ModelProviderProfileSummary> profiles,
  ) => profiles
      .where((profile) => profile.sourceType != 'omnibot_official')
      .toList(growable: false);

  final TextEditingController _nameController = TextEditingController();
  final TextEditingController _baseUrlController = TextEditingController();
  final TextEditingController _apiKeyController = TextEditingController();
  final FocusNode _nameFocusNode = FocusNode();
  final FocusNode _baseUrlFocusNode = FocusNode();
  final FocusNode _apiKeyFocusNode = FocusNode();

  static const Duration _autoSaveDebounce = Duration(milliseconds: 600);
  static const Duration _modelGroupToggleDuration = Duration(milliseconds: 240);
  static const double _modelDeleteExtentRatio = 0.24;
  static const double _modelDeleteIconSize = 18;
  static const BorderRadius _modelDeleteActionRadius = BorderRadius.only(
    topRight: Radius.circular(4),
    bottomRight: Radius.circular(4),
  );

  bool _isLoading = true;
  bool _isFetchingModels = false;
  bool _obscureApiKey = true;
  bool _isSyncingControllers = false;
  bool _isSavingProfile = false;
  bool _saveQueued = false;
  bool _isSwitchingProfile = false;
  bool _apiKeyDirty = false;
  bool _customHeadersDirty = false;
  String _selectedSourceType = BuiltinOfficialProviderCatalog.customKey;
  String _selectedProtocolType = 'openai_compatible';
  String _selectedWireApi = 'chat_completions';

  Timer? _autoSaveTimer;

  List<ModelProviderProfileSummary> _profiles = const [];
  String _editingProfileId = '';
  List<ProviderModelOption> _manualModels = const [];
  List<ProviderModelOption> _remoteModels = const [];
  List<String> _manualModelIds = const [];
  Set<String> _hiddenChatModelIds = <String>{};
  Set<String> _deletingModelIds = <String>{};
  final Map<String, bool> _expandedModelGroups = <String, bool>{};
  bool _customHeadersExpanded = false;
  final List<_EditableHeaderEntry> _customHeaderEntries =
      <_EditableHeaderEntry>[];
  int _nextCustomHeaderEntryId = 0;
  String? _customHeadersErrorText;

  ModelProviderProfileSummary? get _currentProfile {
    for (final profile in _profiles) {
      if (profile.id == _editingProfileId) {
        return profile;
      }
    }
    return _profiles.isEmpty ? null : _profiles.first;
  }

  bool get _hasAnyProfileFieldFocus =>
      _nameFocusNode.hasFocus ||
      _baseUrlFocusNode.hasFocus ||
      _apiKeyFocusNode.hasFocus ||
      _customHeaderEntries.any(
        (entry) =>
            entry.nameFocusNode.hasFocus || entry.valueFocusNode.hasFocus,
      );

  bool get _isDarkTheme => context.isDarkTheme;
  Color get _pageBackground => context.omniPalette.pageBackground;
  Color get _cardColor =>
      _isDarkTheme ? context.omniPalette.surfacePrimary : Colors.white;
  Color get _primaryTextColor =>
      _isDarkTheme ? context.omniPalette.textPrimary : AppColors.text;
  Color get _secondaryTextColor =>
      _isDarkTheme ? context.omniPalette.textSecondary : AppColors.text70;
  Color get _tertiaryTextColor =>
      _isDarkTheme ? context.omniPalette.textTertiary : AppColors.text50;

  String get _selectedProviderValue {
    final officialProvider = BuiltinOfficialProviderCatalog.findByKey(
      _selectedSourceType,
    );
    if (officialProvider != null) {
      return officialProvider.key;
    }
    if (_selectedProtocolType == 'anthropic') {
      return 'anthropic';
    }
    if (_selectedProtocolType == 'deepseek') {
      return 'deepseek';
    }
    return 'openai_compatible';
  }

  String get _selectedProviderLabel {
    final selectedValue = _selectedProviderValue;
    for (final option in _kProviderTypeOptions) {
      if (option.value == selectedValue) {
        return option.label;
      }
    }
    return 'OpenAI';
  }

  String get _selectedWireApiLabel {
    return _selectedWireApi == 'responses' ? 'Responses' : 'Chat Completions';
  }

  double _measurePopupWidth(
    BuildContext context,
    Iterable<String> labels,
    double maxWidth,
  ) {
    final style = TextStyle(
      fontSize: _kProviderTypePopupTextFontSize,
      fontWeight: FontWeight.w500,
      fontFamily: 'PingFang SC',
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
    const popupHorizontalPadding = 20.0;
    const tileHorizontalPadding = 24.0;
    const selectedCheckWidth = 20.0;
    const popupSafetyPadding = 12.0;
    final contentWidth =
        longestLabelWidth +
        popupHorizontalPadding +
        tileHorizontalPadding +
        selectedCheckWidth +
        popupSafetyPadding;
    return contentWidth.clamp(_kProviderTypePopupMinWidth, maxWidth).toDouble();
  }

  List<_ProviderModelItem> get _modelItems {
    final items = <_ProviderModelItem>[];
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
      if (!ModelProviderConfigService.isValidModelName(normalized)) {
        continue;
      }
      if (seen.add(normalized)) {
        items.add(
          _ProviderModelItem(model: model, source: _ProviderModelSource.manual),
        );
      }
    }

    for (final model in _remoteModels) {
      final normalized = model.id.trim();
      if (!ModelProviderConfigService.isValidModelName(normalized)) {
        continue;
      }
      if (seen.add(normalized)) {
        items.add(
          _ProviderModelItem(model: model, source: _ProviderModelSource.remote),
        );
      }
    }

    return items;
  }

  List<MapEntry<String, List<_ProviderModelItem>>> get _modelGroups {
    final groups = <String, List<_ProviderModelItem>>{};
    final current = _currentProfile;
    for (final item in _modelItems) {
      // 缓存中的 model.group 可能是旧前缀分组语义，这里始终现场识别厂商。
      final groupKey = ModelVendorCatalog.groupKeyFor(
        item.id,
        ownedBy: item.model.ownedBy,
        providerId: item.model.modelsDevProviderId ?? current?.id,
        providerName: current?.name,
      );
      groups.putIfAbsent(groupKey, () => <_ProviderModelItem>[]).add(item);
    }
    final entries = groups.entries.toList()
      ..sort((a, b) {
        final orderCompare = ModelVendorCatalog.orderOf(
          a.key,
        ).compareTo(ModelVendorCatalog.orderOf(b.key));
        if (orderCompare != 0) return orderCompare;
        return a.key.compareTo(b.key);
      });
    return entries;
  }

  String _modelGroupExpansionKey(String groupName) {
    final profileKey = _editingProfileId.trim().isEmpty
        ? 'default'
        : _editingProfileId.trim();
    return '$profileKey::$groupName';
  }

  bool _isModelGroupExpanded(String groupName) {
    return _expandedModelGroups[_modelGroupExpansionKey(groupName)] ?? true;
  }

  void _toggleModelGroup(String groupName) {
    setState(() {
      final key = _modelGroupExpansionKey(groupName);
      _expandedModelGroups[key] = !_isModelGroupExpanded(groupName);
    });
  }

  bool _hasAnyExpandedModelGroup(
    List<MapEntry<String, List<_ProviderModelItem>>> groups,
  ) {
    return groups.any((group) => _isModelGroupExpanded(group.key));
  }

  bool _areAllModelGroupsCollapsed(
    List<MapEntry<String, List<_ProviderModelItem>>> groups,
  ) {
    return groups.isNotEmpty &&
        groups.every((group) => !_isModelGroupExpanded(group.key));
  }

  void _toggleAllModelGroups(
    List<MapEntry<String, List<_ProviderModelItem>>> groups,
  ) {
    if (groups.isEmpty) {
      return;
    }
    final shouldExpand = _areAllModelGroupsCollapsed(groups);
    setState(() {
      for (final group in groups) {
        _expandedModelGroups[_modelGroupExpansionKey(group.key)] = shouldExpand;
      }
    });
  }

  @override
  void initState() {
    super.initState();
    _loadData();
    _nameController.addListener(_onProfileChanged);
    _baseUrlController.addListener(_onProfileChanged);
    _apiKeyController.addListener(_onApiKeyChanged);
    _nameFocusNode.addListener(_onProfileFieldFocusChanged);
    _baseUrlFocusNode.addListener(_onProfileFieldFocusChanged);
    _apiKeyFocusNode.addListener(_onProfileFieldFocusChanged);
  }

  @override
  void dispose() {
    _autoSaveTimer?.cancel();
    unawaited(_persistProfileDraft());
    unawaited(_persistManualModelIds());
    _nameFocusNode.removeListener(_onProfileFieldFocusChanged);
    _baseUrlFocusNode.removeListener(_onProfileFieldFocusChanged);
    _apiKeyFocusNode.removeListener(_onProfileFieldFocusChanged);
    _nameController.removeListener(_onProfileChanged);
    _baseUrlController.removeListener(_onProfileChanged);
    _apiKeyController.removeListener(_onApiKeyChanged);
    _nameController.dispose();
    _baseUrlController.dispose();
    _apiKeyController.dispose();
    _nameFocusNode.dispose();
    _baseUrlFocusNode.dispose();
    _apiKeyFocusNode.dispose();
    for (final entry in _customHeaderEntries) {
      entry.dispose();
    }
    super.dispose();
  }

  void _onProfileChanged() {
    if (_isSyncingControllers || _isLoading || _isSwitchingProfile) {
      return;
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
    if (_isSyncingControllers || _isLoading || _isSwitchingProfile) {
      return;
    }
    if (_hasAnyProfileFieldFocus) {
      _autoSaveTimer?.cancel();
      return;
    }
    _scheduleAutoSave();
  }

  void _onCustomHeadersChanged() {
    if (_isSyncingControllers || _isLoading || _isSwitchingProfile) {
      return;
    }
    _customHeadersDirty = true;
    _updateCustomHeadersError();
    if (_hasAnyProfileFieldFocus) {
      _autoSaveTimer?.cancel();
      return;
    }
    _scheduleAutoSave();
  }

  void _scheduleAutoSave() {
    _autoSaveTimer?.cancel();
    if (!_shouldAutoSaveDraft) {
      return;
    }
    _autoSaveTimer = Timer(_autoSaveDebounce, () {
      _autoSaveTimer = null;
      unawaited(_persistProfileDraft());
    });
  }

  bool get _shouldAutoSaveDraft {
    final current = _currentProfile;
    if (current == null || current.readOnly) {
      return false;
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

  Future<void> _persistManualModelIds() async {
    final current = _currentProfile;
    if (current == null) {
      return;
    }
    try {
      await ModelProviderConfigService.saveManualModelIds(
        profileId: current.id,
        ids: _manualModelIds,
      );
    } catch (_) {
      // no-op
    }
  }

  Future<bool> _persistProfileDraft() async {
    final current = _currentProfile;
    if (current == null || current.readOnly) {
      return true;
    }
    _autoSaveTimer?.cancel();
    _autoSaveTimer = null;

    if (_isSavingProfile) {
      _saveQueued = true;
      return false;
    }

    do {
      _saveQueued = false;
      final nextName = _nameController.text.trim();
      final rawBaseUrl = _baseUrlController.text.trim();
      final nextBaseUrl =
          ModelProviderConfigService.normalizeApiBase(rawBaseUrl) ?? '';
      if (rawBaseUrl.isNotEmpty && nextBaseUrl.isEmpty) {
        if (mounted) {
          showToast(
            context.l10n.modelProviderInvalidBaseUrl,
            type: ToastType.error,
          );
        }
        return false;
      }
      final nextApiKey = _apiKeyController.text.trim();
      final nextCustomHeaders =
          _validatedCustomHeadersDraft() ??
          ModelProviderConfigService.normalizeCustomHeaders(
            current.customHeaders,
          );
      final currentBaseUrl =
          ModelProviderConfigService.normalizeApiBase(current.baseUrl) ?? '';

      if (nextName == current.name &&
          nextBaseUrl == currentBaseUrl &&
          !_apiKeyDirty &&
          !_customHeadersDirty &&
          _selectedSourceType == current.sourceType &&
          _selectedProtocolType == current.protocolType &&
          _selectedWireApi == current.wireApi) {
        return true;
      }

      _isSavingProfile = true;
      try {
        final saved = await ModelProviderConfigService.saveProfile(
          id: current.id,
          name: nextName.isEmpty ? current.name : nextName,
          baseUrl: rawBaseUrl,
          apiKey: _apiKeyDirty ? nextApiKey : null,
          customHeaders: _customHeadersDirty ? nextCustomHeaders : null,
          sourceType: _selectedSourceType,
          protocolType: _selectedProtocolType,
          wireApi: _selectedWireApi,
        );
        if (!mounted) return false;
        setState(() {
          _profiles = _profiles
              .map((profile) => profile.id == saved.id ? saved : profile)
              .toList();
          _editingProfileId = saved.id;
        });
        _apiKeyDirty = false;
        _customHeadersDirty = false;
      } catch (_) {
        if (mounted) {
          showToast(
            _headerText('保存提供商失败', 'Failed to save provider'),
            type: ToastType.error,
          );
        }
        return false;
      } finally {
        _isSavingProfile = false;
      }
    } while (_saveQueued && mounted);
    return true;
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);
    try {
      final payload = await ModelProviderConfigService.listProfiles();
      if (!mounted) return;
      final profiles = _byokProfiles(payload.profiles);
      if (profiles.isEmpty) {
        // The native store may contain only the read-only official profile on
        // a clean install.  Keep the editor alive with a real draft so the
        // first Provider can be registered from this page.
        const draft = ModelProviderProfileSummary(
          id: 'profile-1',
          name: 'Provider 1',
          baseUrl: '',
          apiKey: '',
          customHeaders: <String, String>{},
          sourceType: BuiltinOfficialProviderCatalog.customKey,
          readOnly: false,
          ready: false,
          statusText: '',
          configured: false,
          wireApi: 'chat_completions',
        );
        _applyProfile(
          profiles: const <ModelProviderProfileSummary>[draft],
          editingProfileId: draft.id,
          manualModelIds: const <String>[],
          hiddenChatModelIds: const <String>[],
          manualModels: const <ProviderModelOption>[],
          remoteModels: const <ProviderModelOption>[],
          syncControllers: true,
        );
        return;
      }

      final editingProfile = profiles.firstWhere(
        (profile) => profile.id == payload.editingProfileId,
        orElse: () => profiles.first,
      );
      final manualModelIds = await ModelProviderConfigService.getManualModelIds(
        profileId: editingProfile.id,
      );
      final hiddenChatModelIds =
          await ModelProviderConfigService.getHiddenChatModelIds(
            profileId: editingProfile.id,
          );
      final storedModels = await Future.wait<dynamic>([
        _loadManualModelsForProfile(
          editingProfile,
          manualModelIds,
          enrichMetadata: false,
        ),
        _loadRemoteModelsForProfile(editingProfile, enrichMetadata: false),
      ]);
      if (!mounted) return;

      final manualModels = storedModels[0] as List<ProviderModelOption>;
      final remoteModels = storedModels[1] as List<ProviderModelOption>;
      _applyProfile(
        profiles: profiles,
        editingProfileId: editingProfile.id,
        manualModelIds: manualModelIds,
        hiddenChatModelIds: hiddenChatModelIds,
        manualModels: manualModels,
        remoteModels: remoteModels,
        syncControllers: true,
      );
      _scheduleMetadataRefresh(
        profile: editingProfile,
        manualModels: manualModels,
        remoteModels: remoteModels,
      );
    } catch (_) {
      if (!mounted) return;
      showToast(context.l10n.modelProviderLoadFailed, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  void _applyProfile({
    required List<ModelProviderProfileSummary> profiles,
    required String editingProfileId,
    required List<String> manualModelIds,
    required List<String> hiddenChatModelIds,
    required List<ProviderModelOption> manualModels,
    required List<ProviderModelOption> remoteModels,
    required bool syncControllers,
  }) {
    final current = profiles.firstWhere(
      (profile) => profile.id == editingProfileId,
      orElse: () => profiles.first,
    );
    if (syncControllers) {
      _syncController(_nameController, current.name);
      _syncController(_baseUrlController, current.baseUrl);
      _syncController(_apiKeyController, current.apiKey);
      _replaceCustomHeaderEntries(current.customHeaders);
      _apiKeyDirty = false;
      _customHeadersDirty = false;
    }
    setState(() {
      _profiles = profiles;
      _editingProfileId = current.id;
      _manualModelIds = manualModelIds;
      _hiddenChatModelIds = hiddenChatModelIds.toSet();
      _manualModels = manualModels;
      _remoteModels = remoteModels;
      _selectedSourceType = current.sourceType;
      _selectedProtocolType = current.protocolType;
      _selectedWireApi = _normalizeWireApiForProtocol(
        current.protocolType,
        current.wireApi,
      );
      _customHeadersErrorText = _computeCustomHeadersValidationError();
    });
  }

  void _syncController(TextEditingController controller, String value) {
    if (controller.text == value) {
      return;
    }
    _isSyncingControllers = true;
    controller.value = TextEditingValue(
      text: value,
      selection: TextSelection.collapsed(offset: value.length),
    );
    _isSyncingControllers = false;
  }

  Future<List<ProviderModelOption>> _loadRemoteModelsForProfile(
    ModelProviderProfileSummary profile, {
    bool enrichMetadata = true,
  }) async {
    final cached = await ModelProviderConfigService.getCachedFetchedModels(
      profileId: profile.id,
      apiBase: profile.baseUrl,
      profileRevision: profile.revision,
    );
    // Opening or switching the Provider editor is read-only. The persisted
    // catalog is the Provider document; network discovery belongs only to
    // save/verify or the explicit "refresh models" action.
    if (!enrichMetadata) return cached;
    return _enrichModelsForProfile(profile, cached);
  }

  Future<List<ProviderModelOption>> _loadManualModelsForProfile(
    ModelProviderProfileSummary profile,
    List<String> manualModelIds, {
    bool enrichMetadata = true,
  }) {
    final manualModels = manualModelIds
        .map(
          (modelId) => ProviderModelOption(
            id: modelId,
            displayName: modelId,
            ownedBy: 'manual',
          ),
        )
        .toList();
    if (!enrichMetadata) {
      return Future<List<ProviderModelOption>>.value(manualModels);
    }
    return _enrichModelsForProfile(profile, manualModels);
  }

  Future<List<ProviderModelOption>> _enrichModelsForProfile(
    ModelProviderProfileSummary profile,
    List<ProviderModelOption> models,
  ) {
    return ModelProviderConfigService.enrichModelsForProfile(
      profileId: profile.id,
      providerName: profile.name,
      apiBase: profile.baseUrl,
      models: models,
    );
  }

  void _scheduleMetadataRefresh({
    required ModelProviderProfileSummary profile,
    required List<ProviderModelOption> manualModels,
    required List<ProviderModelOption> remoteModels,
  }) {
    if (manualModels.isEmpty && remoteModels.isEmpty) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted ||
          _editingProfileId != profile.id ||
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
      _enrichModelsForProfile(profile, manualModels),
      _enrichModelsForProfile(profile, remoteModels),
    ]);
    if (!mounted ||
        _editingProfileId != profile.id ||
        !identical(_manualModels, manualModels) ||
        !identical(_remoteModels, remoteModels)) {
      return;
    }
    setState(() {
      _manualModels = enriched[0];
      _remoteModels = enriched[1];
    });
  }

  String? _buildBaseUrlHelperText(String rawValue) {
    final input = rawValue.trim();
    if (input.isEmpty) {
      return null;
    }

    if (_selectedProtocolType == 'anthropic') {
      return ModelProviderConfigService.buildAnthropicMessagesRequestUrl(input);
    }
    if (_selectedProtocolType == 'openai_compatible' &&
        _selectedWireApi == 'responses') {
      return ModelProviderConfigService.buildResponsesRequestUrl(input);
    }
    return ModelProviderConfigService.buildChatCompletionsRequestUrl(input);
  }

  String _normalizeWireApiForProtocol(String protocolType, String? wireApi) {
    if (protocolType != 'openai_compatible') {
      return 'chat_completions';
    }
    return wireApi == 'responses' ? 'responses' : 'chat_completions';
  }

  String _headerText(String zh, String en) {
    return Localizations.localeOf(context).languageCode == 'en' ? en : zh;
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

  _EditableHeaderEntry _createHeaderEntry({
    String name = '',
    String value = '',
  }) {
    final entry = _EditableHeaderEntry(
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
    if (_currentProfile?.readOnly == true) {
      return;
    }
    setState(() {
      _customHeaderEntries.add(_createHeaderEntry());
      _customHeadersErrorText = _computeCustomHeadersValidationError();
    });
  }

  void _removeCustomHeaderEntry(int entryId) {
    final index = _customHeaderEntries.indexWhere(
      (entry) => entry.id == entryId,
    );
    if (index < 0) {
      return;
    }
    setState(() {
      final entry = _customHeaderEntries.removeAt(index);
      entry.dispose();
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
      if (name.isEmpty && value.trim().isEmpty) {
        continue;
      }
      if (name.isEmpty) {
        return _headerText('请求头名称不能为空', 'Header name cannot be empty');
      }
      if (ModelProviderConfigService.isForbiddenCustomHeaderName(name)) {
        return _headerText(
          '不支持 $name，请改用其他请求头',
          '$name is not allowed. Use a different header.',
        );
      }
      final normalized = ModelProviderConfigService.normalizeCustomHeaderName(
        name,
      );
      if (!seen.add(normalized)) {
        return _headerText(
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
      if (name.isEmpty) {
        continue;
      }
      normalized[name] = entry.valueController.text;
    }
    return ModelProviderConfigService.normalizeCustomHeaders(normalized);
  }

  bool _stringMapsEqual(Map<String, String> left, Map<String, String> right) {
    if (left.length != right.length) {
      return false;
    }
    for (final entry in left.entries) {
      if (right[entry.key] != entry.value) {
        return false;
      }
    }
    return true;
  }

  Future<void> _switchToProfile(String profileId) async {
    if (_isSwitchingProfile || profileId == _editingProfileId) {
      return;
    }
    final index = _profiles.indexWhere((profile) => profile.id == profileId);
    if (index == -1) {
      return;
    }
    _isSwitchingProfile = true;
    try {
      if (_shouldAutoSaveDraft) {
        if (!await _persistProfileDraft()) return;
      }
      final selected = await ModelProviderConfigService.setEditingProfile(
        profileId,
      );
      final manualModelIds = await ModelProviderConfigService.getManualModelIds(
        profileId: selected.id,
      );
      final hiddenChatModelIds =
          await ModelProviderConfigService.getHiddenChatModelIds(
            profileId: selected.id,
          );
      final storedModels = await Future.wait<dynamic>([
        _loadManualModelsForProfile(selected, manualModelIds),
        _loadRemoteModelsForProfile(selected),
      ]);
      if (!mounted) return;
      _applyProfile(
        profiles: _profiles,
        editingProfileId: selected.id,
        manualModelIds: manualModelIds,
        hiddenChatModelIds: hiddenChatModelIds,
        manualModels: storedModels[0] as List<ProviderModelOption>,
        remoteModels: storedModels[1] as List<ProviderModelOption>,
        syncControllers: true,
      );
    } catch (e) {
      if (!mounted) return;
      showToast(
        context.l10n.modelProviderSwitchFailed(e.toString()),
        type: ToastType.error,
      );
    } finally {
      _isSwitchingProfile = false;
    }
  }

  Future<void> _promptAddProfile() async {
    if (!mounted) {
      return;
    }
    FocusScope.of(context).unfocus();
    _autoSaveTimer?.cancel();
    if (_shouldAutoSaveDraft) {
      await _persistProfileDraft();
      if (!mounted) {
        return;
      }
    }
    final name = (await AppDialog.input(
      context,
      title: context.l10n.modelAddProviderTitle,
      hintText: context.l10n.modelProviderNameHint,
      confirmText: context.l10n.modelAddButton,
      cancelText: context.trLegacy('取消'),
    ))?.trim();
    if (name == null || name.isEmpty) {
      return;
    }
    try {
      final saved = await ModelProviderConfigService.saveProfile(
        name: name,
        baseUrl: '',
        apiKey: '',
        customHeaders: const <String, String>{},
        sourceType: BuiltinOfficialProviderCatalog.customKey,
      );
      if (!mounted) return;
      final nextProfiles = [..._profiles, saved];
      _applyProfile(
        profiles: nextProfiles,
        editingProfileId: saved.id,
        manualModelIds: const [],
        hiddenChatModelIds: const [],
        manualModels: const [],
        remoteModels: const [],
        syncControllers: true,
      );
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) {
          return;
        }
        showToast(context.l10n.modelProviderAdded, type: ToastType.success);
      });
    } catch (e) {
      if (!mounted) return;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) {
          return;
        }
        showToast(
          context.l10n.modelProviderAddFailed(e),
          type: ToastType.error,
        );
      });
    }
  }

  Future<void> _fetchModelsLocalized({bool silentError = false}) async {
    final current = _currentProfile;
    if (current == null || _isFetchingModels) return;
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
    final customHeaders = _validatedCustomHeadersDraft();
    if (customHeaders == null) {
      if (!silentError) {
        showToast(
          _customHeadersErrorText ??
              _headerText('自定义请求头配置无效', 'Custom headers are invalid'),
          type: ToastType.error,
        );
      }
      return;
    }

    setState(() => _isFetchingModels = true);
    try {
      // Discovery must describe the saved Provider revision. Otherwise a
      // later autosave invalidates the just-fetched list on page exit.
      if (_shouldAutoSaveDraft && !await _persistProfileDraft()) return;
      if (!mounted) return;
      final savedProfile = _currentProfile;
      if (savedProfile == null || savedProfile.id != current.id) return;
      final models = await ModelProviderConfigService.fetchModels(
        profileId: savedProfile.id,
        providerName: savedProfile.name,
      );
      if (!mounted ||
          _currentProfile?.id != savedProfile.id ||
          _currentProfile?.revision != savedProfile.revision ||
          _shouldAutoSaveDraft)
        return;
      setState(() {
        _remoteModels = models;
      });
      if (!silentError) {
        final message = models.isEmpty
            ? context.l10n.modelsNoAvailableModels
            : context.l10n.modelProviderFetchedModels(models.length);
        showToast(
          message,
          type: models.isEmpty ? ToastType.warning : ToastType.success,
        );
      }
    } catch (_) {
      if (!mounted || silentError) return;
      showToast(
        _headerText(
          '模型列表刷新失败，请检查配置后重试',
          'Failed to refresh models. Check the configuration and try again.',
        ),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() => _isFetchingModels = false);
      }
    }
  }

  Future<void> _deleteCurrentProfile() async {
    final current = _currentProfile;
    if (current == null || current.readOnly || _profiles.length <= 1) {
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: Text(context.l10n.modelDeleteProviderTitle),
          content: Text(context.l10n.modelDeleteProviderMsg(current.name)),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(context.trLegacy('取消')),
            ),
            TextButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: Text(context.l10n.skillDelete),
            ),
          ],
        );
      },
    );
    if (confirmed != true) {
      return;
    }
    try {
      final payload = await ModelProviderConfigService.deleteProfile(
        current.id,
      );
      if (!mounted) return;
      final profiles = _byokProfiles(payload.profiles);
      if (profiles.isEmpty) {
        throw StateError('No editable BYOK provider profile is available');
      }
      final fallback = profiles.firstWhere(
        (profile) => profile.id == payload.editingProfileId,
        orElse: () => profiles.first,
      );
      final manualModelIds = await ModelProviderConfigService.getManualModelIds(
        profileId: fallback.id,
      );
      final hiddenChatModelIds =
          await ModelProviderConfigService.getHiddenChatModelIds(
            profileId: fallback.id,
          );
      final storedModels = await Future.wait<dynamic>([
        _loadManualModelsForProfile(fallback, manualModelIds),
        _loadRemoteModelsForProfile(fallback),
      ]);
      if (!mounted) return;
      _applyProfile(
        profiles: profiles,
        editingProfileId: fallback.id,
        manualModelIds: manualModelIds,
        hiddenChatModelIds: hiddenChatModelIds,
        manualModels: storedModels[0] as List<ProviderModelOption>,
        remoteModels: storedModels[1] as List<ProviderModelOption>,
        syncControllers: true,
      );
      showToast(context.l10n.modelProviderDeleted, type: ToastType.success);
    } catch (e) {
      if (!mounted) return;
      showToast(
        context.l10n.modelProviderDeleteFailed(e),
        type: ToastType.error,
      );
    }
  }

  Future<void> _promptAddModel() async {
    final current = _currentProfile;
    if (current == null || current.readOnly) {
      return;
    }
    final modelId = await showDialog<String>(
      context: context,
      useRootNavigator: false,
      builder: (_) => const _AddModelIdDialog(),
    );

    if (!mounted) return;
    if (modelId == null) {
      return;
    }
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
    final nextManualModels = await _loadManualModelsForProfile(
      current,
      nextManual,
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
    if (!mounted) return;
    showToast(context.l10n.modelAdded, type: ToastType.success);
  }

  Future<void> _deleteModel(_ProviderModelItem item) async {
    final current = _currentProfile;
    if (current == null || _deletingModelIds.contains(item.id)) {
      return;
    }

    final prevManual = List<String>.from(_manualModelIds);
    final prevManualModels = List<ProviderModelOption>.from(_manualModels);
    final prevRemote = List<ProviderModelOption>.from(_remoteModels);

    setState(() {
      _deletingModelIds = {..._deletingModelIds, item.id};
      _manualModelIds = _manualModelIds.where((id) => id != item.id).toList();
      _manualModels = _manualModels.where((m) => m.id != item.id).toList();
      _remoteModels = _remoteModels.where((m) => m.id != item.id).toList();
    });

    try {
      await Future.wait([
        ModelProviderConfigService.saveManualModelIds(
          profileId: current.id,
          ids: _manualModelIds,
        ),
        ModelProviderConfigService.saveCachedFetchedModels(
          profileId: current.id,
          apiBase: current.baseUrl,
          profileRevision: current.revision,
          models: _remoteModels,
        ),
      ]);

      if (!mounted) return;
      showToast(context.l10n.modelDeleted, type: ToastType.success);
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _manualModelIds = prevManual;
        _manualModels = prevManualModels;
        _remoteModels = prevRemote;
      });
      showToast(context.l10n.modelDeleteFailed, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _deletingModelIds = {..._deletingModelIds}..remove(item.id);
        });
      }
    }
  }

  bool _isModelVisibleInChat(ProviderModelOption model) {
    return !_hiddenChatModelIds.contains(model.id);
  }

  Future<bool> _setModelVisibleInChat(
    ProviderModelOption model,
    bool visible,
  ) async {
    final current = _currentProfile;
    if (current == null) {
      return false;
    }
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

    setState(() {
      _hiddenChatModelIds = next;
    });

    try {
      await ModelProviderConfigService.saveHiddenChatModelIds(
        profileId: current.id,
        ids: next.toList(),
      );
      if (!mounted) {
        return true;
      }
      showToast(
        visible
            ? _headerText('已添加到聊天页', 'Added to chat list')
            : _headerText('已从聊天页移除', 'Removed from chat list'),
        type: ToastType.success,
      );
      return true;
    } catch (_) {
      if (!mounted) {
        return false;
      }
      setState(() {
        _hiddenChatModelIds = previous;
      });
      showToast(
        _headerText('更新聊天页模型失败', 'Failed to update chat list'),
        type: ToastType.error,
      );
      return false;
    }
  }

  Future<bool> _hideAllChatModels() async {
    final current = _currentProfile;
    if (current == null || _remoteModels.isEmpty) {
      return false;
    }
    final previous = Set<String>.from(_hiddenChatModelIds);
    final next = Set<String>.from(_hiddenChatModelIds)
      ..addAll(_remoteModels.map((model) => model.id));
    if (previous.length == next.length && previous.containsAll(next)) {
      return true;
    }

    setState(() {
      _hiddenChatModelIds = next;
    });

    try {
      await ModelProviderConfigService.saveHiddenChatModelIds(
        profileId: current.id,
        ids: next.toList(),
      );
      if (!mounted) {
        return true;
      }
      showToast(
        _headerText('已移除全部聊天页模型', 'Removed all chat list models'),
        type: ToastType.success,
      );
      return true;
    } catch (_) {
      if (!mounted) {
        return false;
      }
      setState(() {
        _hiddenChatModelIds = previous;
      });
      showToast(
        _headerText('更新聊天页模型失败', 'Failed to update chat list'),
        type: ToastType.error,
      );
      return false;
    }
  }

  Future<void> _selectProviderType(String value) async {
    final selected = _kProviderTypeOptions.firstWhere(
      (option) => option.value == value,
      orElse: () => _kProviderTypeOptions[0],
    );
    final isOfficialSelection = selected.baseUrl.isNotEmpty;
    final isGenericOpenAiSelection = selected.value == 'openai_compatible';
    final nextProtocolType = selected.protocolType;
    final nextWireApi = isGenericOpenAiSelection
        ? _normalizeWireApiForProtocol(nextProtocolType, _selectedWireApi)
        : _normalizeWireApiForProtocol(selected.protocolType, selected.wireApi);
    if (_selectedProviderValue == selected.value &&
        _selectedProtocolType == nextProtocolType &&
        _selectedWireApi == nextWireApi) {
      return;
    }
    final current = _currentProfile;
    if (current == null || current.readOnly) {
      return;
    }
    final previousSourceType = _selectedSourceType;
    final previousValue = _selectedProtocolType;
    final previousWireApi = _selectedWireApi;
    final previousName = _nameController.text;
    final previousBaseUrl = _baseUrlController.text;
    setState(() {
      _selectedSourceType = selected.sourceType;
      _selectedProtocolType = nextProtocolType;
      _selectedWireApi = nextWireApi;
    });
    if (isOfficialSelection) {
      _syncController(_nameController, selected.providerName);
      _syncController(_baseUrlController, selected.baseUrl);
    }
    try {
      final saved = await ModelProviderConfigService.saveProfile(
        id: current.id,
        name: _nameController.text.trim().isEmpty
            ? current.name
            : _nameController.text.trim(),
        baseUrl: _baseUrlController.text.trim(),
        apiKey: _apiKeyDirty ? _apiKeyController.text.trim() : null,
        customHeaders: _customHeadersDirty
            ? (_validatedCustomHeadersDraft() ?? const <String, String>{})
            : null,
        sourceType: selected.sourceType,
        protocolType: nextProtocolType,
        wireApi: nextWireApi,
      );
      if (!mounted) return;
      setState(() {
        _profiles = _profiles.map((p) => p.id == saved.id ? saved : p).toList();
        _selectedSourceType = saved.sourceType;
        _selectedProtocolType = saved.protocolType;
        _selectedWireApi = _normalizeWireApiForProtocol(
          saved.protocolType,
          saved.wireApi,
        );
      });
      _apiKeyDirty = false;
      _customHeadersDirty = false;
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _selectedSourceType = previousSourceType;
        _selectedProtocolType = previousValue;
        _selectedWireApi = previousWireApi;
      });
      _syncController(_nameController, previousName);
      _syncController(_baseUrlController, previousBaseUrl);
    }
  }

  Future<void> _selectWireApi(String value) async {
    final normalizedWireApi = _normalizeWireApiForProtocol(
      _selectedProtocolType,
      value,
    );
    if (_selectedWireApi == normalizedWireApi ||
        _selectedProviderValue != 'openai_compatible' ||
        _selectedProtocolType != 'openai_compatible') {
      return;
    }
    final current = _currentProfile;
    if (current == null || current.readOnly) {
      return;
    }
    final previousWireApi = _selectedWireApi;
    setState(() {
      _selectedWireApi = normalizedWireApi;
    });
    try {
      final saved = await ModelProviderConfigService.saveProfile(
        id: current.id,
        name: _nameController.text.trim().isEmpty
            ? current.name
            : _nameController.text.trim(),
        baseUrl: _baseUrlController.text.trim(),
        apiKey: _apiKeyController.text.trim(),
        customHeaders:
            _validatedCustomHeadersDraft() ??
            ModelProviderConfigService.normalizeCustomHeaders(
              current.customHeaders,
            ),
        sourceType: BuiltinOfficialProviderCatalog.customKey,
        protocolType: _selectedProtocolType,
        wireApi: normalizedWireApi,
      );
      if (!mounted) return;
      setState(() {
        _profiles = _profiles.map((p) => p.id == saved.id ? saved : p).toList();
        _selectedSourceType = saved.sourceType;
        _selectedProtocolType = saved.protocolType;
        _selectedWireApi = _normalizeWireApiForProtocol(
          saved.protocolType,
          saved.wireApi,
        );
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _selectedWireApi = previousWireApi;
      });
    }
  }

  Future<void> _openProviderTypeMenu(BuildContext anchorContext) async {
    final current = _currentProfile;
    if (current == null || current.readOnly) {
      return;
    }
    final overlay =
        Overlay.of(context).context.findRenderObject() as RenderBox?;
    final anchorBox = anchorContext.findRenderObject() as RenderBox?;
    if (overlay == null || anchorBox == null || !anchorBox.hasSize) {
      return;
    }
    final topLeft = anchorBox.localToGlobal(Offset.zero, ancestor: overlay);
    final bottomRight = anchorBox.localToGlobal(
      anchorBox.size.bottomRight(Offset.zero),
      ancestor: overlay,
    );
    final anchorRect = Rect.fromPoints(topLeft, bottomRight);
    final availablePopupWidth = math.max(
      _kProviderTypePopupMinWidth,
      overlay.size.width - _kProviderTypePopupHorizontalMargin * 2,
    );
    final measuredPopupWidth = _measurePopupWidth(
      context,
      _kProviderTypeOptions.map((option) => option.label),
      availablePopupWidth,
    );
    final popupWidth = math
        .max(anchorRect.width, measuredPopupWidth)
        .clamp(_kProviderTypePopupMinWidth, availablePopupWidth)
        .toDouble();
    final estimatedHeight = (_kProviderTypeOptions.length * 48 + 24)
        .clamp(120.0, _kProviderSwitchPopupMaxHeight)
        .toDouble();
    final position = PopupMenuAnchorPosition.fromAnchorRect(
      anchorRect: anchorRect,
      overlaySize: overlay.size,
      estimatedMenuHeight: estimatedHeight,
      reservedBottom: MediaQuery.of(context).viewInsets.bottom,
      verticalGap: 6,
    );
    final selected = await showMenu<String>(
      context: context,
      color: _cardColor,
      elevation: _isDarkTheme ? 0 : 8,
      shadowColor: _isDarkTheme ? context.omniPalette.shadowColor : null,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: _isDarkTheme
            ? BorderSide(color: context.omniPalette.borderSubtle)
            : BorderSide.none,
      ),
      constraints: BoxConstraints(minWidth: popupWidth, maxWidth: popupWidth),
      position: position,
      items: [
        _ProviderTypePopupEntry(
          width: popupWidth,
          estimatedHeight: estimatedHeight,
          options: _kProviderTypeOptions,
          selectedValue: _selectedProviderValue,
        ),
      ],
    );
    if (selected == null) {
      return;
    }
    await _selectProviderType(selected);
  }

  Future<void> _openWireApiMenu(BuildContext anchorContext) async {
    final selected = await _showSelectionMenu(
      anchorContext: anchorContext,
      options: _kOpenAiWireApiOptions,
      selectedValue: _selectedWireApi,
      menuKey: const Key('provider-wire-api-menu'),
    );
    if (selected == null) {
      return;
    }
    await _selectWireApi(selected);
  }

  Future<String?> _showSelectionMenu({
    required BuildContext anchorContext,
    required List<_SelectionOption> options,
    required String selectedValue,
    required Key menuKey,
  }) async {
    final current = _currentProfile;
    if (current == null || current.readOnly) {
      return null;
    }
    final overlay =
        Overlay.of(context).context.findRenderObject() as RenderBox?;
    final anchorBox = anchorContext.findRenderObject() as RenderBox?;
    if (overlay == null || anchorBox == null || !anchorBox.hasSize) {
      return null;
    }
    final topLeft = anchorBox.localToGlobal(Offset.zero, ancestor: overlay);
    final bottomRight = anchorBox.localToGlobal(
      anchorBox.size.bottomRight(Offset.zero),
      ancestor: overlay,
    );
    final anchorRect = Rect.fromPoints(topLeft, bottomRight);
    final availablePopupWidth = math.max(
      _kProviderTypePopupMinWidth,
      overlay.size.width - _kProviderTypePopupHorizontalMargin * 2,
    );
    final measuredPopupWidth = _measurePopupWidth(
      context,
      options.map((option) => option.label),
      availablePopupWidth,
    );
    final popupWidth = math
        .max(anchorRect.width, measuredPopupWidth)
        .clamp(_kProviderTypePopupMinWidth, availablePopupWidth)
        .toDouble();
    final estimatedHeight = (options.length * 48 + 24)
        .clamp(120.0, _kProviderSwitchPopupMaxHeight)
        .toDouble();
    final position = PopupMenuAnchorPosition.fromAnchorRect(
      anchorRect: anchorRect,
      overlaySize: overlay.size,
      estimatedMenuHeight: estimatedHeight,
      reservedBottom: MediaQuery.of(context).viewInsets.bottom,
      verticalGap: 6,
    );
    return showMenu<String>(
      context: context,
      color: _cardColor,
      elevation: _isDarkTheme ? 0 : 8,
      shadowColor: _isDarkTheme ? context.omniPalette.shadowColor : null,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: _isDarkTheme
            ? BorderSide(color: context.omniPalette.borderSubtle)
            : BorderSide.none,
      ),
      constraints: BoxConstraints(minWidth: popupWidth, maxWidth: popupWidth),
      position: position,
      items: [
        _SelectionPopupEntry(
          width: popupWidth,
          estimatedHeight: estimatedHeight,
          options: options,
          selectedValue: selectedValue,
          menuKey: menuKey,
        ),
      ],
    );
  }

  Widget _buildCard({required Widget child}) {
    return SizedBox(width: double.infinity, child: child);
  }

  InputDecoration _buildInputDecoration({
    required String label,
    String? hint,
    Widget? suffixIcon,
  }) {
    return InputDecoration(
      labelText: label,
      hintText: hint,
      suffixIcon: suffixIcon,
    );
  }

  Widget _buildCustomHeadersEditor() {
    final readOnly = _currentProfile?.readOnly ?? false;
    final hasHeaders = _customHeaderEntries.isNotEmpty;
    final headerCount = _customHeaderEntries
        .where((e) => e.nameController.text.trim().isNotEmpty)
        .length;
    final palette = context.omniPalette;
    final subtitle = headerCount > 0
        ? _headerText('已配置 $headerCount 项', '$headerCount configured')
        : _headerText('点击展开配置', 'Tap to configure');

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Collapsible header row
        Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(8),
          child: InkWell(
            borderRadius: BorderRadius.circular(8),
            onTap: () {
              setState(() {
                _customHeadersExpanded = !_customHeadersExpanded;
              });
            },
            splashColor: palette.accentPrimary.withValues(alpha: 0.06),
            highlightColor: Colors.transparent,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                children: [
                  Expanded(
                    child: Row(
                      children: [
                        Flexible(
                          child: Text(
                            _headerText('自定义请求头', 'Custom Headers'),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: _primaryTextColor,
                              fontSize: 14,
                              fontWeight: FontWeight.w600,
                              fontFamily: 'PingFang SC',
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          subtitle,
                          style: TextStyle(
                            color: _tertiaryTextColor,
                            fontSize: 12,
                            fontFamily: 'PingFang SC',
                          ),
                        ),
                      ],
                    ),
                  ),
                  AnimatedRotation(
                    turns: _customHeadersExpanded ? 0.5 : 0,
                    duration: const Duration(milliseconds: 180),
                    curve: Curves.easeOutCubic,
                    child: Icon(
                      LucideIcons.chevronDown,
                      size: 20,
                      color: _tertiaryTextColor,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        // Expandable body
        TweenAnimationBuilder<double>(
          tween: Tween<double>(
            begin: _customHeadersExpanded ? 1 : 0,
            end: _customHeadersExpanded ? 1 : 0,
          ),
          duration: const Duration(milliseconds: 240),
          curve: Curves.easeInOutCubicEmphasized,
          builder: (context, value, child) {
            return ClipRect(
              child: Align(
                alignment: Alignment.topCenter,
                heightFactor: value,
                child: Opacity(
                  opacity: value.clamp(0.0, 1.0).toDouble(),
                  child: IgnorePointer(ignoring: value < 0.99, child: child),
                ),
              ),
            );
          },
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: 8),
              Text(
                _headerText(
                  '用于对接要求额外请求头的服务商，例如 HTTP-Referer、X-Title、x-api-key。Host、Content-Length、Connection、Transfer-Encoding 会被拦截。',
                  'Use for providers that require extra headers such as HTTP-Referer, X-Title, or x-api-key. Host, Content-Length, Connection, and Transfer-Encoding are blocked.',
                ),
                style: TextStyle(
                  color: _tertiaryTextColor,
                  fontSize: 12,
                  fontFamily: 'PingFang SC',
                ),
              ),
              const SizedBox(height: 10),
              if (!readOnly)
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    onPressed: _addCustomHeaderEntry,
                    icon: const Icon(LucideIcons.plus, size: 16),
                    label: Text(_headerText('新增', 'Add')),
                  ),
                ),
              if (!readOnly && hasHeaders) const SizedBox(height: 4),
              if (_customHeaderEntries.isEmpty)
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 14,
                  ),
                  decoration: BoxDecoration(
                    color: _isDarkTheme
                        ? context.omniPalette.surfaceSecondary
                        : const Color(0xFFF7F9FC),
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(
                      color: _isDarkTheme
                          ? context.omniPalette.borderSubtle
                          : const Color(0x14000000),
                    ),
                  ),
                  child: Text(
                    _headerText('暂未配置自定义请求头', 'No custom headers configured'),
                    style: TextStyle(
                      color: _secondaryTextColor,
                      fontSize: 12,
                      fontFamily: 'PingFang SC',
                    ),
                  ),
                )
              else
                Column(
                  children: [
                    for (
                      var index = 0;
                      index < _customHeaderEntries.length;
                      index++
                    ) ...[
                      _buildCustomHeaderRow(
                        _customHeaderEntries[index],
                        readOnly,
                      ),
                      if (index < _customHeaderEntries.length - 1)
                        const SizedBox(height: 10),
                    ],
                  ],
                ),
              if (_customHeadersErrorText != null) ...[
                const SizedBox(height: 8),
                Text(
                  _customHeadersErrorText!,
                  style: const TextStyle(
                    color: AppColors.alertRed,
                    fontSize: 12,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ],
              const SizedBox(height: 4),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildCustomHeaderRow(_EditableHeaderEntry entry, bool readOnly) {
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 12, 8, 8),
      decoration: BoxDecoration(
        color: _isDarkTheme
            ? context.omniPalette.surfaceSecondary
            : const Color(0xFFF7F9FC),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: _isDarkTheme
              ? context.omniPalette.borderSubtle
              : const Color(0x14000000),
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              children: [
                TextField(
                  controller: entry.nameController,
                  focusNode: entry.nameFocusNode,
                  enabled: !readOnly,
                  style: context.omniInputTextStyle,
                  decoration: _buildInputDecoration(
                    label: _headerText('Header 名称', 'Header Name'),
                    hint: 'HTTP-Referer',
                  ),
                ),
                const SizedBox(height: 10),
                TextField(
                  controller: entry.valueController,
                  focusNode: entry.valueFocusNode,
                  enabled: !readOnly,
                  style: context.omniInputTextStyle,
                  decoration: _buildInputDecoration(
                    label: _headerText('Header 值', 'Header Value'),
                    hint: 'https://example.com',
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          IconButton(
            tooltip: _headerText('删除', 'Delete'),
            onPressed: readOnly
                ? null
                : () => _removeCustomHeaderEntry(entry.id),
            icon: Icon(
              LucideIcons.trash2,
              size: 18,
              color: readOnly ? _tertiaryTextColor : AppColors.alertRed,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildModelActionButton({
    required IconData icon,
    required VoidCallback? onPressed,
    bool highlighted = false,
    bool loading = false,
  }) {
    final isEnabled = onPressed != null;
    final useHighlightStyle = highlighted || loading;
    final accentColor = _isDarkTheme
        ? context.omniPalette.accentPrimary
        : const Color(0xFF2C7FEB);
    final iconColor = !isEnabled && !loading
        ? _tertiaryTextColor
        : useHighlightStyle
        ? accentColor
        : _primaryTextColor;

    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: onPressed,
        splashColor: accentColor.withValues(alpha: 0.08),
        highlightColor: accentColor.withValues(alpha: 0.04),
        child: SizedBox(
          width: 42,
          height: 42,
          child: Center(
            child: loading
                ? SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      valueColor: AlwaysStoppedAnimation<Color>(iconColor),
                    ),
                  )
                : Icon(icon, size: 20, color: iconColor),
          ),
        ),
      ),
    );
  }

  Widget _buildLucideModelActionButton({
    required IconData icon,
    required VoidCallback? onPressed,
    required String tooltip,
    Key? key,
    bool highlighted = false,
  }) {
    final isEnabled = onPressed != null;
    final accentColor = _isDarkTheme
        ? context.omniPalette.accentPrimary
        : const Color(0xFF2C7FEB);
    final iconColor = !isEnabled
        ? _tertiaryTextColor
        : highlighted
        ? accentColor
        : _primaryTextColor;

    return Tooltip(
      message: tooltip,
      child: Semantics(
        button: true,
        label: tooltip,
        enabled: isEnabled,
        child: Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(10),
          child: InkWell(
            key: key,
            borderRadius: BorderRadius.circular(10),
            onTap: onPressed,
            splashColor: accentColor.withValues(alpha: 0.08),
            highlightColor: accentColor.withValues(alpha: 0.04),
            child: SizedBox(
              width: 42,
              height: 42,
              child: Center(child: Icon(icon, size: 20, color: iconColor)),
            ),
          ),
        ),
      ),
    );
  }

  String _formatTokenLimit(int? value) {
    if (value == null || value <= 0) {
      return '--';
    }
    if (value >= 1000000) {
      final formatted = value % 1000000 == 0
          ? '${value ~/ 1000000}'
          : (value / 1000000).toStringAsFixed(1);
      return '${formatted}M';
    }
    if (value >= 1000) {
      final formatted = value % 1000 == 0
          ? '${value ~/ 1000}'
          : (value / 1000).toStringAsFixed(1);
      return '${formatted}K';
    }
    return value.toString();
  }

  Widget _buildCompactIconChip({
    required String key,
    required IconData icon,
    required String tooltip,
    String? label,
  }) {
    return Tooltip(
      message: tooltip,
      child: Container(
        key: Key(key),
        height: 22,
        constraints: const BoxConstraints(minWidth: 22),
        padding: label == null
            ? EdgeInsets.zero
            : const EdgeInsets.symmetric(horizontal: 6),
        decoration: BoxDecoration(
          color: _cardColor,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(
            color: _isDarkTheme
                ? context.omniPalette.borderSubtle
                : const Color(0x14000000),
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 14, color: _secondaryTextColor),
            if (label != null) ...[
              const SizedBox(width: 4),
              Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 11,
                  color: _tertiaryTextColor,
                  fontWeight: FontWeight.w700,
                  fontFamily: 'PingFang SC',
                  letterSpacing: 0,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildModalityIcon({required IconData icon, required String tooltip}) {
    return _buildCompactIconChip(
      key: 'provider-model-modality-${tooltip.toLowerCase().split(' ').first}',
      icon: icon,
      tooltip: tooltip,
    );
  }

  Widget _buildContextLimitChip(String modelId, String label) {
    return Tooltip(
      message: 'Context limit $label',
      child: Container(
        key: Key('provider-model-context-$modelId'),
        height: 22,
        padding: const EdgeInsets.symmetric(horizontal: 7),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: _cardColor,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(
            color: _isDarkTheme
                ? context.omniPalette.borderSubtle
                : const Color(0x14000000),
          ),
        ),
        child: Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 11,
            color: _tertiaryTextColor,
            fontWeight: FontWeight.w700,
            fontFamily: 'PingFang SC',
            letterSpacing: 0,
          ),
        ),
      ),
    );
  }

  Widget _buildModalityTextChip(String modality) {
    return Container(
      height: 22,
      padding: const EdgeInsets.symmetric(horizontal: 6),
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: _cardColor,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(
          color: _isDarkTheme
              ? context.omniPalette.borderSubtle
              : const Color(0x14000000),
        ),
      ),
      child: Text(
        modality.toUpperCase(),
        style: TextStyle(
          color: _secondaryTextColor,
          fontSize: 9,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
      ),
    );
  }

  List<Widget> _buildModelMetadataWidgets(ProviderModelOption model) {
    final widgets = <Widget>[];
    final contextLimit = _formatTokenLimit(model.contextLimit);
    widgets.add(_buildContextLimitChip(model.id, contextLimit));
    if (model.reasoning == true) {
      widgets.add(
        _buildCompactIconChip(
          key: 'provider-model-reasoning-${model.id}',
          icon: LucideIcons.brain,
          tooltip: 'Reasoning',
        ),
      );
    }
    widgets.addAll(_buildInputModalityWidgets(model));
    return widgets;
  }

  List<Widget> _buildInputModalityWidgets(ProviderModelOption model) {
    final modalities = model.inputModalities
        .map((item) => item.trim().toLowerCase())
        .where((item) => item.isNotEmpty)
        .toSet();
    if (modalities.isEmpty) {
      return const [];
    }
    final widgets = <Widget>[];
    if (modalities.contains('text')) {
      widgets.add(
        _buildModalityIcon(icon: LucideIcons.fileText, tooltip: 'Text input'),
      );
    }
    if (modalities.contains('image')) {
      widgets.add(
        _buildModalityIcon(icon: LucideIcons.image, tooltip: 'Image input'),
      );
    }
    if (modalities.contains('pdf')) {
      widgets.add(
        _buildModalityIcon(icon: LucideIcons.file, tooltip: 'PDF input'),
      );
    }
    for (final modality in modalities) {
      if (modality == 'text' || modality == 'image' || modality == 'pdf') {
        continue;
      }
      widgets.add(_buildModalityTextChip(modality));
    }
    return widgets;
  }

  Widget _buildModelGroupSection(
    MapEntry<String, List<_ProviderModelItem>> group, {
    required bool isLast,
  }) {
    final expanded = _isModelGroupExpanded(group.key);
    final items = Column(
      children: [
        const SizedBox(height: 2),
        for (var itemIndex = 0; itemIndex < group.value.length; itemIndex++)
          _buildSwipeModelItem(group.value[itemIndex]),
      ],
    );

    return Padding(
      padding: EdgeInsets.only(bottom: isLast ? 0 : 12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildModelGroupHeader(
            group.key,
            group.value.length,
            expanded: expanded,
            onTap: () => _toggleModelGroup(group.key),
          ),
          TweenAnimationBuilder<double>(
            tween: Tween<double>(
              begin: expanded ? 1 : 0,
              end: expanded ? 1 : 0,
            ),
            duration: _modelGroupToggleDuration,
            curve: Curves.easeInOutCubicEmphasized,
            builder: (context, value, child) {
              return ClipRect(
                child: Align(
                  key: Key('provider-model-group-body-${group.key}'),
                  alignment: Alignment.topCenter,
                  heightFactor: value,
                  child: Opacity(
                    opacity: value.clamp(0.0, 1.0).toDouble(),
                    child: IgnorePointer(ignoring: value < 0.99, child: child),
                  ),
                ),
              );
            },
            child: items,
          ),
        ],
      ),
    );
  }

  Widget _buildModelGroupHeader(
    String groupName,
    int count, {
    required bool expanded,
    required VoidCallback onTap,
  }) {
    final palette = context.omniPalette;
    final vendor = ModelVendorCatalog.byKey(groupName);
    final languageCode = Localizations.localeOf(context).languageCode;
    final displayLabel =
        vendor?.labelForLanguage(languageCode) ??
        (languageCode == 'en' ? 'Other' : '其他');
    final labelStyle = TextStyle(
      color: _tertiaryTextColor,
      fontSize: 11,
      fontWeight: FontWeight.w700,
      fontFamily: 'PingFang SC',
      letterSpacing: 0.4,
    );
    final countStyle = TextStyle(
      color: _tertiaryTextColor,
      fontSize: 11,
      fontWeight: FontWeight.w500,
      fontFamily: 'PingFang SC',
    );
    const labelToCountGap = 8.0;
    const countToLineGap = 10.0;
    const lineToIconGap = 6.0;
    const iconSlotWidth = 20.0;
    const minLineWidth = 48.0;
    const vendorIconSize = 16.0;
    const vendorIconToLabelGap = 6.0;

    return Padding(
      padding: const EdgeInsets.only(bottom: 5),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          key: Key('provider-model-group-$groupName'),
          onTap: onTap,
          borderRadius: BorderRadius.circular(10),
          splashColor: palette.accentPrimary.withValues(alpha: 0.06),
          highlightColor: Colors.transparent,
          child: Container(
            width: double.infinity,
            constraints: const BoxConstraints(minHeight: 28),
            alignment: Alignment.centerLeft,
            padding: const EdgeInsets.fromLTRB(4, 5, 2, 5),
            child: LayoutBuilder(
              builder: (context, constraints) {
                final countWidth = _measureSingleLineTextWidth(
                  '$count',
                  countStyle,
                );
                final fixedTrailingWidth =
                    labelToCountGap +
                    countWidth +
                    countToLineGap +
                    lineToIconGap +
                    iconSlotWidth;
                final labelAndLineWidth = math.max(
                  0.0,
                  constraints.maxWidth -
                      fixedTrailingWidth -
                      vendorIconSize -
                      vendorIconToLabelGap,
                );
                final reservedLineWidth = math.min(
                  minLineWidth,
                  labelAndLineWidth * 0.32,
                );
                final groupNameMaxWidth = math.max(
                  0.0,
                  labelAndLineWidth - reservedLineWidth,
                );
                return Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    ProviderVendorIcon(
                      key: Key('provider-model-group-vendor-icon-$groupName'),
                      vendor: vendor,
                      size: vendorIconSize,
                      monochromeColor: _tertiaryTextColor,
                    ),
                    const SizedBox(width: vendorIconToLabelGap),
                    ConstrainedBox(
                      constraints: BoxConstraints(maxWidth: groupNameMaxWidth),
                      child: Text(
                        key: Key('provider-model-group-label-$groupName'),
                        displayLabel,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: labelStyle,
                      ),
                    ),
                    const SizedBox(width: labelToCountGap),
                    Text(
                      key: Key('provider-model-group-count-$groupName'),
                      '$count',
                      style: countStyle,
                    ),
                    const SizedBox(width: countToLineGap),
                    Expanded(
                      child: Container(
                        key: Key('provider-model-group-line-$groupName'),
                        height: 1,
                        color: _isDarkTheme
                            ? palette.borderSubtle.withValues(alpha: 0.56)
                            : const Color(0x16000000),
                      ),
                    ),
                    const SizedBox(width: lineToIconGap),
                    SizedBox(
                      key: Key('provider-model-group-icon-$groupName'),
                      width: iconSlotWidth,
                      child: Align(
                        alignment: Alignment.centerRight,
                        child: AnimatedRotation(
                          turns: expanded ? 0 : -0.25,
                          duration: _modelGroupToggleDuration,
                          curve: Curves.easeInOutCubicEmphasized,
                          child: Icon(
                            LucideIcons.chevronDown,
                            size: 18,
                            color: _tertiaryTextColor,
                          ),
                        ),
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      ),
    );
  }

  double _measureSingleLineTextWidth(String text, TextStyle style) {
    final textPainter = TextPainter(
      text: TextSpan(text: text, style: style),
      textDirection: Directionality.of(context),
      maxLines: 1,
    )..layout();
    return textPainter.width;
  }

  Widget _buildModelGroupToggleButton(
    List<MapEntry<String, List<_ProviderModelItem>>> groups,
  ) {
    final palette = context.omniPalette;
    final enabled = groups.isNotEmpty;
    final isOpen = enabled && _hasAnyExpandedModelGroup(groups);
    final iconColor = !enabled
        ? palette.textTertiary
        : isOpen
        ? palette.accentPrimary
        : palette.textSecondary;
    final tooltip = !enabled
        ? context.trLegacy('暂无模型分组')
        : _areAllModelGroupsCollapsed(groups)
        ? context.trLegacy('展开全部分组')
        : context.trLegacy('折叠全部分组');

    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        key: const ValueKey('provider-model-group-toggle-button'),
        onTap: enabled ? () => _toggleAllModelGroups(groups) : null,
        behavior: HitTestBehavior.opaque,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 180),
          opacity: enabled ? 1 : 0.36,
          child: SizedBox(
            width: 48,
            height: 44,
            child: Center(
              child: Icon(
                isOpen
                    ? LucideIcons.listCollapse
                    : LucideIcons.listChevronsUpDown,
                size: 20,
                color: iconColor,
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSwipeModelItem(_ProviderModelItem item) {
    final isDeleting = _deletingModelIds.contains(item.id);
    final metadataWidgets = _buildModelMetadataWidgets(item.model);

    return IgnorePointer(
      ignoring: isDeleting,
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 180),
        opacity: isDeleting ? 0.72 : 1,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final initialActionWidth =
                constraints.maxWidth * _modelDeleteExtentRatio;
            final deleteIconRightPadding =
                ((initialActionWidth - _modelDeleteIconSize) / 2)
                    .clamp(0.0, double.infinity)
                    .toDouble();
            final metadataMaxWidth = (constraints.maxWidth * 0.46)
                .clamp(116.0, 190.0)
                .toDouble();

            return Slidable(
              key: ValueKey<String>('provider-model-${item.id}'),
              groupTag: 'provider-model-items',
              closeOnScroll: true,
              endActionPane: ActionPane(
                motion: const BehindMotion(),
                extentRatio: _modelDeleteExtentRatio,
                dismissible: DismissiblePane(
                  dismissThreshold: 0.4,
                  closeOnCancel: true,
                  motion: const InversedDrawerMotion(),
                  onDismissed: () => _deleteModel(item),
                ),
                children: [
                  CustomSlidableAction(
                    onPressed: (_) => _deleteModel(item),
                    backgroundColor: AppColors.alertRed,
                    borderRadius: _modelDeleteActionRadius,
                    padding: EdgeInsets.zero,
                    alignment: Alignment.centerRight,
                    child: Padding(
                      padding: EdgeInsets.only(right: deleteIconRightPadding),
                      child: Icon(
                        LucideIcons.trash2,
                        size: _modelDeleteIconSize,
                        color: Colors.white,
                      ),
                    ),
                  ),
                ],
              ),
              child: Material(
                color: Colors.transparent,
                child: InkWell(
                  onTap: () {},
                  borderRadius: BorderRadius.circular(10),
                  splashColor: context.omniPalette.accentPrimary.withValues(
                    alpha: 0.06,
                  ),
                  highlightColor: Colors.transparent,
                  child: SizedBox(
                    width: double.infinity,
                    height: 44,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 4),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Expanded(
                            child: Text(
                              item.id,
                              style: TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.w600,
                                color: _primaryTextColor,
                                fontFamily: 'PingFang SC',
                                height: 1.2,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          const SizedBox(width: 10),
                          SizedBox(
                            width: metadataMaxWidth,
                            child: SingleChildScrollView(
                              scrollDirection: Axis.horizontal,
                              physics: const BouncingScrollPhysics(),
                              reverse: true,
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  for (
                                    var index = 0;
                                    index < metadataWidgets.length;
                                    index++
                                  ) ...[
                                    if (index > 0) const SizedBox(width: 6),
                                    metadataWidgets[index],
                                  ],
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Future<void> _openProviderSwitchMenu(BuildContext anchorContext) async {
    if (_profiles.isEmpty) {
      return;
    }
    final overlay =
        Overlay.of(context).context.findRenderObject() as RenderBox?;
    final anchorBox = anchorContext.findRenderObject() as RenderBox?;
    if (overlay == null || anchorBox == null || !anchorBox.hasSize) {
      return;
    }
    final topLeft = anchorBox.localToGlobal(Offset.zero, ancestor: overlay);
    final bottomRight = anchorBox.localToGlobal(
      anchorBox.size.bottomRight(Offset.zero),
      ancestor: overlay,
    );
    final anchorRect = Rect.fromPoints(topLeft, bottomRight);
    final popupWidth = anchorRect.width.clamp(220.0, 300.0).toDouble();
    final estimatedHeight = (_profiles.length * 48 + 24)
        .clamp(120.0, _kProviderSwitchPopupMaxHeight)
        .toDouble();
    final position = PopupMenuAnchorPosition.fromAnchorRect(
      anchorRect: anchorRect,
      overlaySize: overlay.size,
      estimatedMenuHeight: estimatedHeight,
      reservedBottom: MediaQuery.of(context).viewInsets.bottom,
      verticalGap: 6,
    );
    final selected = await showMenu<String>(
      context: context,
      color: _cardColor,
      elevation: _isDarkTheme ? 0 : 8,
      shadowColor: _isDarkTheme ? context.omniPalette.shadowColor : null,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: _isDarkTheme
            ? BorderSide(color: context.omniPalette.borderSubtle)
            : BorderSide.none,
      ),
      constraints: BoxConstraints(minWidth: popupWidth, maxWidth: popupWidth),
      position: position,
      items: [
        _ProviderSwitchPopupEntry(
          width: popupWidth,
          estimatedHeight: estimatedHeight,
          profiles: _profiles,
          selectedProfileId: _editingProfileId,
        ),
      ],
    );
    if (selected == null) {
      return;
    }
    await _switchToProfile(selected);
  }

  Future<void> _openChatModelVisibilityMenu(BuildContext anchorContext) async {
    final current = _currentProfile;
    if (current == null) {
      return;
    }
    final overlay =
        Overlay.of(context).context.findRenderObject() as RenderBox?;
    final anchorBox = anchorContext.findRenderObject() as RenderBox?;
    if (overlay == null || anchorBox == null || !anchorBox.hasSize) {
      return;
    }
    final topLeft = anchorBox.localToGlobal(Offset.zero, ancestor: overlay);
    final bottomRight = anchorBox.localToGlobal(
      anchorBox.size.bottomRight(Offset.zero),
      ancestor: overlay,
    );
    final anchorRect = Rect.fromPoints(topLeft, bottomRight);
    final availableWidth = math.max(288.0, overlay.size.width - 32);
    final popupWidth = math
        .min(380.0, availableWidth)
        .clamp(288.0, availableWidth)
        .toDouble();
    final estimatedHeight = (_remoteModels.length * 48 + 72)
        .clamp(160.0, 360.0)
        .toDouble();
    final position = PopupMenuAnchorPosition.fromAnchorRect(
      anchorRect: anchorRect,
      overlaySize: overlay.size,
      estimatedMenuHeight: estimatedHeight,
      reservedBottom: MediaQuery.of(context).viewInsets.bottom,
      verticalGap: 6,
    );
    await showMenu<String>(
      context: context,
      color: _cardColor,
      elevation: _isDarkTheme ? 0 : 8,
      shadowColor: _isDarkTheme ? context.omniPalette.shadowColor : null,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: _isDarkTheme
            ? BorderSide(color: context.omniPalette.borderSubtle)
            : BorderSide.none,
      ),
      constraints: BoxConstraints(minWidth: popupWidth, maxWidth: popupWidth),
      position: position,
      items: [
        _ChatModelVisibilityPopupEntry(
          width: popupWidth,
          estimatedHeight: estimatedHeight,
          models: _remoteModels,
          hiddenModelIds: _hiddenChatModelIds,
          title: _headerText('聊天页模型', 'Chat List Models'),
          emptyText: _headerText('暂无已拉取模型', 'No fetched models'),
          addTooltip: _headerText('添加到聊天页', 'Add to chat list'),
          removeTooltip: _headerText('从聊天页移除', 'Remove from chat list'),
          removeAllTooltip: _headerText(
            '移除全部聊天页模型',
            'Remove all chat list models',
          ),
          onVisibilityChanged: _setModelVisibleInChat,
          onRemoveAll: _hideAllChatModels,
        ),
      ],
    );
  }

  Widget _buildProviderConfigTitle({double? maxWidth}) {
    final current = _currentProfile;
    final name = current?.name.trim();
    final displayName = (name == null || name.isEmpty) ? 'Provider' : name;
    final label = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          displayName,
          key: const Key('provider-config-title-text'),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: _primaryTextColor,
            fontSize: 14,
            fontWeight: FontWeight.w600,
            fontFamily: 'PingFang SC',
          ),
        ),
        if (current != null &&
            (current.readOnly || current.statusText.isNotEmpty))
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Text(
              current.statusText.isNotEmpty
                  ? current.statusText
                  : (current.ready
                        ? context.l10n.modelBuiltinProvider
                        : '${context.l10n.modelBuiltinProvider} not ready'),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: _tertiaryTextColor,
                fontSize: 11,
                fontFamily: 'PingFang SC',
              ),
            ),
          ),
      ],
    );
    final boundedMaxWidth = maxWidth != null && maxWidth.isFinite;
    final titleButton = Builder(
      builder: (anchorContext) {
        return InkWell(
          key: const Key('provider-config-title'),
          onTap: _profiles.isEmpty
              ? null
              : () {
                  unawaited(_openProviderSwitchMenu(anchorContext));
                },
          borderRadius: BorderRadius.circular(8),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 4),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (boundedMaxWidth) Flexible(child: label) else label,
                if (current?.readOnly == true)
                  Padding(
                    padding: const EdgeInsets.only(left: 6),
                    child: Icon(
                      LucideIcons.lockKeyhole,
                      size: 14,
                      color: _tertiaryTextColor,
                    ),
                  ),
                const SizedBox(width: 2),
                Icon(
                  LucideIcons.chevronDown,
                  size: 18,
                  color: _secondaryTextColor,
                ),
              ],
            ),
          ),
        );
      },
    );
    if (!boundedMaxWidth) {
      return titleButton;
    }
    return ConstrainedBox(
      constraints: BoxConstraints(maxWidth: math.max(0, maxWidth)),
      child: titleButton,
    );
  }

  Widget _buildProtocolTypeButton({double maxWidth = 176}) {
    return Builder(
      builder: (anchorContext) {
        final current = _currentProfile;
        final enabled = !(current?.readOnly ?? false);
        return ConstrainedBox(
          constraints: BoxConstraints(maxWidth: math.max(0, maxWidth)),
          child: Opacity(
            opacity: enabled ? 1 : 0.68,
            child: InkWell(
              key: const Key('provider-protocol-type-button'),
              onTap: enabled
                  ? () {
                      unawaited(_openProviderTypeMenu(anchorContext));
                    }
                  : null,
              borderRadius: BorderRadius.circular(8),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 4),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Flexible(
                      child: Text(
                        _selectedProviderLabel,
                        key: const Key('provider-protocol-type-text'),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: _primaryTextColor,
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          fontFamily: 'PingFang SC',
                        ),
                      ),
                    ),
                    const SizedBox(width: 2),
                    Icon(
                      LucideIcons.chevronDown,
                      size: 18,
                      color: _secondaryTextColor,
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildWireApiField() {
    final enabled = !(_currentProfile?.readOnly ?? false);
    return Builder(
      builder: (anchorContext) {
        return Opacity(
          opacity: enabled ? 1 : 0.68,
          child: Material(
            color: Colors.transparent,
            borderRadius: BorderRadius.circular(12),
            child: InkWell(
              key: const Key('provider-wire-api-button'),
              onTap: enabled
                  ? () {
                      unawaited(_openWireApiMenu(anchorContext));
                    }
                  : null,
              borderRadius: BorderRadius.circular(12),
              child: InputDecorator(
                decoration: _buildInputDecoration(
                  label: _headerText('接口方式', 'Wire API'),
                ),
                isEmpty: false,
                isFocused: false,
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        _selectedWireApiLabel,
                        key: const Key('provider-wire-api-text'),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: context.omniInputTextStyle,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Icon(
                      LucideIcons.chevronDown,
                      size: 20,
                      color: _secondaryTextColor,
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final modelItems = _modelItems;
    final modelGroups = _modelGroups;

    return Scaffold(
      backgroundColor: _pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.settingsModelProviderTitle,
        primary: true,
      ),
      body: SafeArea(
        top: false,
        bottom: false,
        child: _isLoading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: edgeToEdgeScrollPadding(
                  context,
                  const EdgeInsets.fromLTRB(18, 12, 18, 24),
                ),
                children: [
                  SettingsSectionTitle(
                    label: context.l10n.modelProviderConfigTitle,
                    subtitle: context.l10n.modelProviderConfigDesc,
                  ),
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: LayoutBuilder(
                                builder: (context, constraints) {
                                  const providerTitleMinWidth = 72.0;
                                  const protocolButtonPreferredWidth = 176.0;
                                  const titleSpacing = 4.0;
                                  final availableWidth =
                                      constraints.maxWidth.isFinite
                                      ? constraints.maxWidth
                                      : providerTitleMinWidth +
                                            titleSpacing +
                                            protocolButtonPreferredWidth;
                                  final usableWidth = math.max(
                                    0.0,
                                    availableWidth - titleSpacing,
                                  );
                                  final protocolButtonMaxWidth =
                                      (usableWidth - providerTitleMinWidth)
                                          .clamp(
                                            0.0,
                                            protocolButtonPreferredWidth,
                                          )
                                          .toDouble();
                                  final providerTitleMaxWidth = math.max(
                                    0.0,
                                    usableWidth - protocolButtonMaxWidth,
                                  );
                                  return Align(
                                    alignment: Alignment.centerLeft,
                                    child: Row(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        _buildProviderConfigTitle(
                                          maxWidth: providerTitleMaxWidth,
                                        ),
                                        const SizedBox(width: titleSpacing),
                                        _buildProtocolTypeButton(
                                          maxWidth: protocolButtonMaxWidth,
                                        ),
                                      ],
                                    ),
                                  );
                                },
                              ),
                            ),
                            const SizedBox(width: 8),
                            _buildModelActionButton(
                              icon: LucideIcons.trash2,
                              onPressed:
                                  _profiles.length <= 1 ||
                                      _currentProfile?.readOnly == true
                                  ? null
                                  : _deleteCurrentProfile,
                            ),
                            const SizedBox(width: 8),
                            _buildModelActionButton(
                              icon: LucideIcons.plus,
                              onPressed: _promptAddProfile,
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _nameController,
                          focusNode: _nameFocusNode,
                          enabled: !(_currentProfile?.readOnly ?? false),
                          style: context.omniInputTextStyle,
                          decoration: _buildInputDecoration(
                            label: context.l10n.modelProviderName,
                            hint: context.l10n.modelProviderNameHint,
                          ),
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _baseUrlController,
                          focusNode: _baseUrlFocusNode,
                          enabled: !(_currentProfile?.readOnly ?? false),
                          style: context.omniInputTextStyle,
                          decoration: _buildInputDecoration(
                            label: 'Base URL',
                            hint: context.l10n.modelProviderBaseUrlHint,
                          ),
                        ),
                        const SizedBox(height: 8),
                        ValueListenableBuilder<TextEditingValue>(
                          valueListenable: _baseUrlController,
                          builder: (context, value, child) {
                            final url = _buildBaseUrlHelperText(value.text);
                            if (url == null) {
                              return const SizedBox.shrink();
                            }
                            return Text(
                              url,
                              style: TextStyle(
                                color: _tertiaryTextColor,
                                fontSize: 12,
                                fontFamily: 'PingFang SC',
                              ),
                            );
                          },
                        ),
                        if (_selectedProviderValue == 'openai_compatible') ...[
                          const SizedBox(height: 12),
                          _buildWireApiField(),
                        ],
                        const SizedBox(height: 14),
                        TextField(
                          controller: _apiKeyController,
                          focusNode: _apiKeyFocusNode,
                          enabled: !(_currentProfile?.readOnly ?? false),
                          style: context.omniInputTextStyle,
                          obscureText: _obscureApiKey,
                          decoration: _buildInputDecoration(
                            label: 'API Key',
                            hint: 'e.g., sk-xxxx',
                            suffixIcon: IconButton(
                              key: const Key(
                                'provider-api-key-visibility-button',
                              ),
                              splashRadius: 18,
                              onPressed: () {
                                setState(() {
                                  _obscureApiKey = !_obscureApiKey;
                                });
                              },
                              icon: Icon(
                                _obscureApiKey
                                    ? LucideIcons.eyeOff
                                    : LucideIcons.eye,
                                color: _tertiaryTextColor,
                                size: 18,
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          context.l10n.modelProviderApiKeyHint,
                          style: TextStyle(
                            color: _tertiaryTextColor,
                            fontSize: 12,
                            fontFamily: 'PingFang SC',
                          ),
                        ),
                        const SizedBox(height: 16),
                        _buildCustomHeadersEditor(),
                      ],
                    ),
                  ),
                  const SizedBox(height: 18),
                  SettingsSectionTitle(
                    label: context.l10n.modelListTitle,
                    subtitle: context.l10n.modelListDesc,
                  ),
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                context.l10n.modelListCount(modelItems.length),
                                style: TextStyle(
                                  color: _secondaryTextColor,
                                  fontSize: 12,
                                  fontWeight: FontWeight.w500,
                                  fontFamily: 'PingFang SC',
                                ),
                              ),
                            ),
                            _buildModelActionButton(
                              icon: LucideIcons.plus,
                              onPressed: _currentProfile?.readOnly == true
                                  ? null
                                  : _promptAddModel,
                            ),
                            const SizedBox(width: 8),
                            _buildModelActionButton(
                              icon: LucideIcons.arrowBigDown,
                              onPressed: _isFetchingModels
                                  ? null
                                  : _fetchModelsLocalized,
                              highlighted: true,
                              loading: _isFetchingModels,
                            ),
                            const SizedBox(width: 4),
                            Builder(
                              builder: (anchorContext) {
                                final hasHiddenRemoteModel = _remoteModels.any(
                                  (model) => !_isModelVisibleInChat(model),
                                );
                                return _buildLucideModelActionButton(
                                  key: const Key(
                                    'provider-chat-model-visibility-button',
                                  ),
                                  icon: LucideIcons.columns3Cog,
                                  tooltip: _headerText(
                                    '管理聊天页模型',
                                    'Manage chat list models',
                                  ),
                                  highlighted: hasHiddenRemoteModel,
                                  onPressed: _currentProfile == null
                                      ? null
                                      : () {
                                          unawaited(
                                            _openChatModelVisibilityMenu(
                                              anchorContext,
                                            ),
                                          );
                                        },
                                );
                              },
                            ),
                            const SizedBox(width: 4),
                            _buildModelGroupToggleButton(modelGroups),
                          ],
                        ),
                        const SizedBox(height: 12),
                        SizedBox(
                          height: 280,
                          child: modelItems.isEmpty
                              ? Padding(
                                  padding: const EdgeInsets.all(10),
                                  child: Center(
                                    child: Column(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        Icon(
                                          LucideIcons.package,
                                          size: 64,
                                          color: _tertiaryTextColor,
                                        ),
                                        const SizedBox(height: 10),
                                        Text(
                                          context.l10n.modelAddPrompt,
                                          style: TextStyle(
                                            color: _secondaryTextColor,
                                            fontSize: 14,
                                            fontWeight: FontWeight.w600,
                                            fontFamily: 'PingFang SC',
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                )
                              : ListView.builder(
                                  padding: const EdgeInsets.fromLTRB(
                                    2,
                                    2,
                                    2,
                                    8,
                                  ),
                                  itemCount: modelGroups.length,
                                  itemBuilder: (context, index) {
                                    final group = modelGroups[index];
                                    return _buildModelGroupSection(
                                      group,
                                      isLast: index == modelGroups.length - 1,
                                    );
                                  },
                                ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
      ),
    );
  }
}

class _ProviderSwitchPopupEntry extends PopupMenuEntry<String> {
  const _ProviderSwitchPopupEntry({
    required this.width,
    required this.estimatedHeight,
    required this.profiles,
    required this.selectedProfileId,
  });

  final double width;
  final double estimatedHeight;
  final List<ModelProviderProfileSummary> profiles;
  final String selectedProfileId;

  @override
  double get height => estimatedHeight;

  @override
  bool represents(String? value) => false;

  @override
  State<_ProviderSwitchPopupEntry> createState() =>
      _ProviderSwitchPopupEntryState();
}

class _ProviderSwitchPopupEntryState extends State<_ProviderSwitchPopupEntry> {
  Widget _buildProviderTile(ModelProviderProfileSummary profile) {
    final palette = context.omniPalette;
    final selected = profile.id == widget.selectedProfileId;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: InkWell(
        onTap: () {
          Navigator.of(context).pop(profile.id);
        },
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
          decoration: BoxDecoration(
            color: selected
                ? (_isDarkTheme(context)
                      ? palette.segmentThumb
                      : const Color(0xFFEAF3FF))
                : Colors.transparent,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  profile.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    color: _isDarkTheme(context)
                        ? palette.textPrimary
                        : AppColors.text,
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              if (selected)
                Icon(
                  LucideIcons.check,
                  size: 16,
                  color: _isDarkTheme(context)
                      ? palette.accentPrimary
                      : const Color(0xFF2C7FEB),
                ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(120.0, widget.estimatedHeight)
            .toDouble();
    return SizedBox(
      width: widget.width,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
        child: widget.profiles.isEmpty
            ? Padding(
                padding: EdgeInsets.all(16),
                child: Text(
                  'No providers',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 12,
                    color: _isDarkTheme(context)
                        ? palette.textTertiary
                        : const Color(0xFF94A3B8),
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              )
            : Scrollbar(
                child: ListView.builder(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  itemCount: widget.profiles.length,
                  itemBuilder: (context, index) {
                    return _buildProviderTile(widget.profiles[index]);
                  },
                ),
              ),
      ),
    );
  }
}

class _ChatModelVisibilityPopupEntry extends PopupMenuEntry<String> {
  const _ChatModelVisibilityPopupEntry({
    required this.width,
    required this.estimatedHeight,
    required this.models,
    required this.hiddenModelIds,
    required this.title,
    required this.emptyText,
    required this.addTooltip,
    required this.removeTooltip,
    required this.removeAllTooltip,
    required this.onVisibilityChanged,
    required this.onRemoveAll,
  });

  final double width;
  final double estimatedHeight;
  final List<ProviderModelOption> models;
  final Set<String> hiddenModelIds;
  final String title;
  final String emptyText;
  final String addTooltip;
  final String removeTooltip;
  final String removeAllTooltip;
  final Future<bool> Function(ProviderModelOption model, bool visible)
  onVisibilityChanged;
  final Future<bool> Function() onRemoveAll;

  @override
  double get height => estimatedHeight;

  @override
  bool represents(String? value) => false;

  @override
  State<_ChatModelVisibilityPopupEntry> createState() =>
      _ChatModelVisibilityPopupEntryState();
}

class _ChatModelVisibilityPopupEntryState
    extends State<_ChatModelVisibilityPopupEntry> {
  late Set<String> _hiddenModelIds;
  Set<String> _pendingModelIds = <String>{};
  bool _removingAll = false;

  @override
  void initState() {
    super.initState();
    _hiddenModelIds = Set<String>.from(widget.hiddenModelIds);
  }

  @override
  void didUpdateWidget(covariant _ChatModelVisibilityPopupEntry oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.hiddenModelIds != widget.hiddenModelIds) {
      _hiddenModelIds = Set<String>.from(widget.hiddenModelIds);
    }
  }

  Future<void> _toggleModel(ProviderModelOption model) async {
    if (_removingAll || _pendingModelIds.contains(model.id)) {
      return;
    }
    final wasVisible = !_hiddenModelIds.contains(model.id);
    final nextVisible = !wasVisible;
    setState(() {
      _pendingModelIds = {..._pendingModelIds, model.id};
      if (nextVisible) {
        _hiddenModelIds.remove(model.id);
      } else {
        _hiddenModelIds.add(model.id);
      }
    });
    final success = await widget.onVisibilityChanged(model, nextVisible);
    if (!mounted) {
      return;
    }
    setState(() {
      _pendingModelIds = {..._pendingModelIds}..remove(model.id);
      if (!success) {
        if (wasVisible) {
          _hiddenModelIds.remove(model.id);
        } else {
          _hiddenModelIds.add(model.id);
        }
      }
    });
  }

  Future<void> _removeAllModels() async {
    if (_removingAll || widget.models.isEmpty) {
      return;
    }
    final previous = Set<String>.from(_hiddenModelIds);
    final next = Set<String>.from(_hiddenModelIds)
      ..addAll(widget.models.map((model) => model.id));
    if (previous.length == next.length && previous.containsAll(next)) {
      return;
    }
    setState(() {
      _removingAll = true;
      _hiddenModelIds = next;
    });
    final success = await widget.onRemoveAll();
    if (!mounted) {
      return;
    }
    setState(() {
      _removingAll = false;
      if (!success) {
        _hiddenModelIds = previous;
      }
    });
  }

  Widget _buildModelTile(ProviderModelOption model) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final visible = !_hiddenModelIds.contains(model.id);
    final pending = _removingAll || _pendingModelIds.contains(model.id);
    final accentColor = isDark
        ? palette.accentPrimary
        : const Color(0xFF2C7FEB);
    final removeColor = isDark ? const Color(0xFFFCA5A5) : AppColors.alertRed;
    final actionColor = visible ? removeColor : accentColor;
    final tooltip = visible ? widget.removeTooltip : widget.addTooltip;
    final vendor = ModelVendorCatalog.resolve(
      model.id,
      ownedBy: model.ownedBy,
      providerId: model.modelsDevProviderId,
    );

    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: Container(
        key: Key('provider-chat-model-visibility-row-${model.id}'),
        constraints: const BoxConstraints(minHeight: 44),
        padding: const EdgeInsets.fromLTRB(12, 7, 6, 7),
        decoration: BoxDecoration(borderRadius: BorderRadius.circular(12)),
        child: Row(
          children: [
            ProviderVendorIcon(vendor: vendor, size: 15),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                model.id,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 13,
                  color: isDark ? palette.textPrimary : AppColors.text,
                  fontWeight: FontWeight.w600,
                  fontFamily: 'PingFang SC',
                ),
              ),
            ),
            const SizedBox(width: 8),
            Tooltip(
              message: tooltip,
              child: Semantics(
                button: true,
                label: '$tooltip ${model.id}',
                enabled: !pending,
                child: Material(
                  color: Colors.transparent,
                  borderRadius: BorderRadius.circular(9),
                  child: InkWell(
                    key: Key(
                      'provider-chat-model-visibility-toggle-${model.id}',
                    ),
                    onTap: pending
                        ? null
                        : () => unawaited(_toggleModel(model)),
                    borderRadius: BorderRadius.circular(9),
                    splashColor: actionColor.withValues(alpha: 0.08),
                    highlightColor: actionColor.withValues(alpha: 0.04),
                    child: SizedBox(
                      width: 34,
                      height: 34,
                      child: Center(
                        child: pending
                            ? SizedBox(
                                width: 14,
                                height: 14,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  valueColor: AlwaysStoppedAnimation<Color>(
                                    actionColor,
                                  ),
                                ),
                              )
                            : Icon(
                                visible ? LucideIcons.minus : LucideIcons.plus,
                                size: 17,
                                color: actionColor,
                              ),
                      ),
                    ),
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
    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(160.0, widget.estimatedHeight)
            .toDouble();
    final visibleCount = widget.models
        .where((model) => !_hiddenModelIds.contains(model.id))
        .length;
    final canRemoveAll =
        widget.models.isNotEmpty && visibleCount > 0 && !_removingAll;
    final removeColor = isDark ? const Color(0xFFFCA5A5) : AppColors.alertRed;

    return SizedBox(
      key: const Key('provider-chat-model-visibility-menu'),
      width: widget.width,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 6, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      widget.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 13,
                        color: isDark ? palette.textPrimary : AppColors.text,
                        fontWeight: FontWeight.w700,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                  ),
                  Text(
                    '$visibleCount/${widget.models.length}',
                    style: TextStyle(
                      fontSize: 12,
                      color: isDark
                          ? palette.textTertiary
                          : const Color(0xFF94A3B8),
                      fontWeight: FontWeight.w600,
                      fontFamily: 'PingFang SC',
                    ),
                  ),
                  const SizedBox(width: 8),
                  Tooltip(
                    message: widget.removeAllTooltip,
                    child: Semantics(
                      button: true,
                      label: widget.removeAllTooltip,
                      enabled: canRemoveAll,
                      child: Material(
                        color: Colors.transparent,
                        borderRadius: BorderRadius.circular(9),
                        child: InkWell(
                          key: const Key(
                            'provider-chat-model-visibility-remove-all',
                          ),
                          onTap: canRemoveAll
                              ? () => unawaited(_removeAllModels())
                              : null,
                          borderRadius: BorderRadius.circular(9),
                          splashColor: removeColor.withValues(alpha: 0.08),
                          highlightColor: removeColor.withValues(alpha: 0.04),
                          child: SizedBox(
                            width: 34,
                            height: 34,
                            child: Center(
                              child: _removingAll
                                  ? SizedBox(
                                      width: 14,
                                      height: 14,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                        valueColor:
                                            AlwaysStoppedAnimation<Color>(
                                              removeColor,
                                            ),
                                      ),
                                    )
                                  : Icon(
                                      LucideIcons.minus,
                                      size: 17,
                                      color: canRemoveAll
                                          ? removeColor
                                          : (isDark
                                                ? palette.textTertiary
                                                : const Color(0xFF94A3B8)),
                                    ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            if (widget.models.isEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 18, 16, 24),
                child: Text(
                  widget.emptyText,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 12,
                    color: isDark
                        ? palette.textTertiary
                        : const Color(0xFF94A3B8),
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              )
            else
              Flexible(
                child: Scrollbar(
                  child: ListView.builder(
                    padding: const EdgeInsets.only(bottom: 8),
                    itemCount: widget.models.length,
                    itemBuilder: (context, index) {
                      return _buildModelTile(widget.models[index]);
                    },
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _ProviderTypePopupEntry extends PopupMenuEntry<String> {
  const _ProviderTypePopupEntry({
    required this.width,
    required this.estimatedHeight,
    required this.options,
    required this.selectedValue,
  });

  final double width;
  final double estimatedHeight;
  final List<_ProviderTypeOption> options;
  final String selectedValue;

  @override
  double get height => estimatedHeight;

  @override
  bool represents(String? value) => false;

  @override
  State<_ProviderTypePopupEntry> createState() =>
      _ProviderTypePopupEntryState();
}

class _SelectionPopupEntry extends PopupMenuEntry<String> {
  const _SelectionPopupEntry({
    required this.width,
    required this.estimatedHeight,
    required this.options,
    required this.selectedValue,
    required this.menuKey,
  });

  final double width;
  final double estimatedHeight;
  final List<_SelectionOption> options;
  final String selectedValue;
  final Key menuKey;

  @override
  double get height => estimatedHeight;

  @override
  bool represents(String? value) => value == selectedValue;

  @override
  State<_SelectionPopupEntry> createState() => _SelectionPopupEntryState();
}

class _SelectionPopupEntryState extends State<_SelectionPopupEntry> {
  Widget _buildTile(_SelectionOption option) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final selected = option.value == widget.selectedValue;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: InkWell(
        onTap: () => Navigator.of(context).pop(option.value),
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
          decoration: BoxDecoration(
            color: selected
                ? (isDark ? palette.segmentThumb : const Color(0xFFEAF3FF))
                : Colors.transparent,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  option.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    color: isDark ? palette.textPrimary : AppColors.text,
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              if (selected)
                Icon(
                  LucideIcons.check,
                  size: 16,
                  color: isDark
                      ? palette.accentPrimary
                      : const Color(0xFF2C7FEB),
                ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(120.0, widget.estimatedHeight)
            .toDouble();
    return SizedBox(
      key: widget.menuKey,
      width: widget.width,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
        child: Scrollbar(
          child: ListView.builder(
            padding: const EdgeInsets.symmetric(vertical: 8),
            itemCount: widget.options.length,
            itemBuilder: (context, index) {
              return _buildTile(widget.options[index]);
            },
          ),
        ),
      ),
    );
  }
}

class _ProviderTypePopupEntryState extends State<_ProviderTypePopupEntry> {
  Widget _buildProtocolTile(_ProviderTypeOption option) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final selected = option.value == widget.selectedValue;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: InkWell(
        onTap: () => Navigator.of(context).pop(option.value),
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
          decoration: BoxDecoration(
            color: selected
                ? (isDark ? palette.segmentThumb : const Color(0xFFEAF3FF))
                : Colors.transparent,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  option.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    color: isDark ? palette.textPrimary : AppColors.text,
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              if (selected)
                Icon(
                  LucideIcons.check,
                  size: 16,
                  color: isDark
                      ? palette.accentPrimary
                      : const Color(0xFF2C7FEB),
                ),
            ],
          ),
        ),
      ),
    );
  }

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
            padding: const EdgeInsets.symmetric(vertical: 8),
            itemCount: widget.options.length,
            itemBuilder: (context, index) {
              return _buildProtocolTile(widget.options[index]);
            },
          ),
        ),
      ),
    );
  }
}

bool _isDarkTheme(BuildContext context) => context.isDarkTheme;

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
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        _close();
      },
      child: AlertDialog(
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
