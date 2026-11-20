import 'dart:async';

import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/model_vendor_catalog.dart';
import 'package:ui/services/scene_model_config_service.dart';
import 'package:ui/services/voice_playback_coordinator.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/agent_avatar.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/widgets/settings_section_title.dart';
import 'package:ui/widgets/settings_ui.dart';

typedef _SceneModelChoice = ({String providerProfileId, String modelId});

const _SceneModelChoice _defaultSceneModelChoice = (
  providerProfileId: '',
  modelId: '',
);

class SceneModelSettingPage extends StatefulWidget {
  const SceneModelSettingPage({super.key});

  @override
  State<SceneModelSettingPage> createState() => _SceneModelSettingPageState();
}

class _SceneModelSettingPageState extends State<SceneModelSettingPage>
    with FlClashPageLoadMixin {
  static const List<String> _sceneOrder = [
    'scene.dispatch.model',
    'scene.voice',
    'scene.conversation.title',
    'scene.compactor.context.chat',
    'scene.memory.embedding',
    'scene.memory.rollup',
  ];

  static const Map<String, String> _sceneDisplayNameMap = {
    'scene.dispatch.model': 'Agent',
    'scene.voice': '语音合成',
    'scene.vlm.operation.primary': 'GUI',
    'scene.compactor.context.chat': 'Chat Compactor',
    'scene.memory.embedding': 'Memory Embed',
    'scene.memory.rollup': 'Memory Rollup',
  };

  static const Map<String, String> _sceneTooltipMap = {
    'scene.dispatch.model': '负责任务理解与分流决策',
    'scene.voice': '负责助手回复的语音合成与播放',
    'scene.vlm.operation.primary': '负责 Android GUI 观察与动作决策',
    'scene.compactor.context.chat': '负责聊天历史压缩总结',
    'scene.memory.embedding': '负责 workspace 记忆向量检索的嵌入模型',
    'scene.memory.rollup': '负责夜间记忆整理策略模型',
  };
  static const String _officialSourceType = 'omnibot_official';
  static const String _textCapability = 'text';
  static const String _embeddingCapability = 'embedding';

  bool _titleEnabled = true;
  bool _savingTitle = false;
  bool _savingVoice = false;
  bool _isLoading = true;
  bool _isRefreshingModels = false;
  Completer<void>? _providerRefreshCompleter;
  Map<String, Future<void>> _officialCapabilityRefreshes = {};
  int _providerRefreshGeneration = 0;
  List<SceneCatalogItem> _catalog = const [];
  List<SceneModelBindingEntry> _bindings = const [];
  List<ModelProviderProfileSummary> _profiles = const [];
  SceneVoiceConfig _voiceConfig = const SceneVoiceConfig();
  Map<String, List<ProviderModelOption>> _providerModelsByProfileId = {};
  Map<String, Map<String, List<ProviderModelOption>>>
  _officialProviderModelsByCapability = {};
  Set<String> _savingSceneIds = <String>{};
  Set<String> _loadingSceneModelIds = <String>{};
  @override
  void initState() {
    super.initState();
    _loadData();
  }

  List<SceneCatalogItem> get _orderedCatalog {
    final map = {
      for (final item in _catalog)
        if (item.sceneId != 'scene.compactor.context' &&
            (!_voiceConfig.usesSpeechProvider ||
                item.sceneId != VoicePlaybackCoordinator.sceneVoiceId))
          item.sceneId: item,
    };

    final ordered = <SceneCatalogItem>[];
    for (final sceneId in _sceneOrder) {
      final item = map.remove(sceneId);
      if (item != null) {
        ordered.add(item);
      }
    }
    ordered.addAll(map.values);
    return ordered;
  }

  Map<String, SceneModelBindingEntry> get _bindingMap {
    return {for (final item in _bindings) item.sceneId: item};
  }

  Color get _pageBackground => context.omniPalette.pageBackground;
  Color get _primaryTextColor => context.omniPalette.textPrimary;
  Color get _secondaryTextColor => context.omniPalette.textSecondary;
  Color get _tertiaryTextColor => context.omniPalette.textTertiary;
  String _sceneDisplayName(String sceneId) {
    if (sceneId == 'scene.conversation.title') {
      return Localizations.localeOf(context).languageCode == 'en'
          ? 'Conversation title'
          : '对话标题';
    }
    return _sceneDisplayNameMap[sceneId] ?? sceneId;
  }

  String _sceneTooltip(SceneCatalogItem item) {
    if (item.sceneId == 'scene.conversation.title') {
      return Localizations.localeOf(context).languageCode == 'en'
          ? 'Generate a title after the first successful reply'
          : '首次成功回答后自动生成标题';
    }
    final mapped = _sceneTooltipMap[item.sceneId];
    if (mapped != null) {
      return context.trLegacy(mapped);
    }
    if (item.description.trim().isNotEmpty) {
      return context.trLegacy(item.description.trim());
    }
    return item.sceneId;
  }

  bool _isSavingScene(String sceneId) {
    return _savingSceneIds.contains(sceneId);
  }

  bool _isAgentScene(String sceneId) {
    return sceneId == 'scene.dispatch.model';
  }

  String _modelCapabilityForScene(String sceneId) {
    if (sceneId == 'scene.memory.embedding') {
      return _embeddingCapability;
    }
    return _textCapability;
  }

  Set<String> get _requiredOfficialCapabilities => {
    for (final scene in _catalog) _modelCapabilityForScene(scene.sceneId),
  };

  bool _isOfficialProfile(ModelProviderProfileSummary profile) =>
      profile.sourceType == _officialSourceType;

  Map<String, List<ProviderModelOption>> _modelsForScene(
    SceneCatalogItem scene,
  ) {
    final capability = _modelCapabilityForScene(scene.sceneId);
    return {
      for (final profile in _profiles)
        profile.id: _isOfficialProfile(profile)
            ? (_officialProviderModelsByCapability[capability]?[profile.id] ??
                  const <ProviderModelOption>[])
            : (_providerModelsByProfileId[profile.id] ??
                  const <ProviderModelOption>[]),
    };
  }

  bool _isOfficialCapabilityLoaded(String capability) {
    final officialProfiles = _profiles.where(_isOfficialProfile).toList();
    if (officialProfiles.isEmpty) {
      return true;
    }
    final loaded = _officialProviderModelsByCapability[capability];
    return loaded != null &&
        officialProfiles.every((profile) => loaded.containsKey(profile.id));
  }

  Future<void> _ensureOfficialCapabilityLoaded(String capability) async {
    if (_isOfficialCapabilityLoaded(capability)) {
      return;
    }
    final capabilityRefresh = _officialCapabilityRefreshes[capability];
    if (capabilityRefresh != null) {
      await capabilityRefresh;
      return;
    }
    await _refreshProviderModelsInBackground();
  }

  Future<void> _loadData({bool showLoading = true}) async {
    if (!await waitForPageReady() || !mounted) return;
    if (showLoading && mounted) {
      setState(() => _isLoading = true);
    }
    try {
      final results = await Future.wait<dynamic>([
        SceneModelConfigService.getSceneCatalog(),
        SceneModelConfigService.getSceneModelBindings(),
        ModelProviderConfigService.listProfiles(),
        SceneModelConfigService.getSceneVoiceConfig(),
        SceneModelConfigService.getSceneTitleEnabled(),
      ]);
      if (!await waitForPageReady() || !mounted) return;

      final catalog = results[0] as List<SceneCatalogItem>;
      final bindings = results[1] as List<SceneModelBindingEntry>;
      final profilesPayload = results[2] as ModelProviderProfilesPayload;
      final voiceConfig = results[3] as SceneVoiceConfig;
      final providerModelsByProfileId = <String, List<ProviderModelOption>>{};
      for (final profile in profilesPayload.profiles) {
        providerModelsByProfileId[profile.id] = _isOfficialProfile(profile)
            ? const <ProviderModelOption>[]
            : await ModelProviderConfigService.getStoredModelOptionsForProfile(
                profile.id,
                profile: profile,
                enrichMetadata: false,
              );
      }

      if (!await waitForPageReady() || !mounted) return;
      setState(() {
        _catalog = catalog;
        _bindings = bindings;
        _profiles = profilesPayload.profiles;
        _voiceConfig = voiceConfig;
        _titleEnabled = results[4] as bool;
        _providerModelsByProfileId = providerModelsByProfileId;
        _officialProviderModelsByCapability = {};
      });
      _scheduleMetadataRefresh(
        profiles: profilesPayload.profiles,
        providerModelsByProfileId: providerModelsByProfileId,
      );
      if (profilesPayload.profiles.any(_isOfficialProfile)) {
        unawaited(_refreshProviderModelsInBackground());
      }
    } catch (_) {
      if (!await waitForPageReady() || !mounted) return;
      showToast(context.l10n.sceneModelLoadFailed, type: ToastType.error);
    } finally {
      if (showLoading && await waitForPageReady() && mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  Future<void> _saveVoiceConfig(SceneVoiceConfig config) async {
    if (_savingVoice) return;
    setState(() => _savingVoice = true);
    try {
      final saved = await SceneModelConfigService.saveSceneVoiceConfig(config);
      if (!mounted) return;
      setState(() => _voiceConfig = saved);
      await VoicePlaybackCoordinator.instance.refreshConfiguration();
      if (mounted) {
        showToast(context.trLegacy('语音设置已保存'), type: ToastType.success);
      }
    } catch (error) {
      if (mounted) {
        showToast(context.trLegacy('语音设置保存失败：$error'), type: ToastType.error);
      }
    } finally {
      if (mounted) setState(() => _savingVoice = false);
    }
  }

  void _scheduleMetadataRefresh({
    required List<ModelProviderProfileSummary> profiles,
    required Map<String, List<ProviderModelOption>> providerModelsByProfileId,
  }) {
    if (!providerModelsByProfileId.values.any((models) => models.isNotEmpty)) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted ||
          !identical(_providerModelsByProfileId, providerModelsByProfileId)) {
        return;
      }
      unawaited(
        _refreshMetadata(
          profiles: profiles,
          providerModelsByProfileId: providerModelsByProfileId,
        ),
      );
    });
  }

  Future<void> _refreshMetadata({
    required List<ModelProviderProfileSummary> profiles,
    required Map<String, List<ProviderModelOption>> providerModelsByProfileId,
  }) async {
    if (!await waitForPageReady() || !mounted) return;
    final enrichedEntries =
        await Future.wait<MapEntry<String, List<ProviderModelOption>>>(
          profiles.map((profile) async {
            final models =
                providerModelsByProfileId[profile.id] ??
                const <ProviderModelOption>[];
            return MapEntry(
              profile.id,
              await ModelProviderConfigService.enrichModelsForProfile(
                profileId: profile.id,
                providerName: profile.name,
                apiBase: profile.baseUrl,
                models: models,
              ),
            );
          }),
        );
    if (!await waitForPageReady() ||
        !identical(_providerModelsByProfileId, providerModelsByProfileId)) {
      return;
    }
    setState(() {
      _providerModelsByProfileId = <String, List<ProviderModelOption>>{
        ...providerModelsByProfileId,
        for (final entry in enrichedEntries) entry.key: entry.value,
      };
    });
  }

  Future<void> _refreshProviderModelsInBackground() async {
    if (!await waitForPageReady() || !mounted) return;
    if (_isRefreshingModels) {
      await _providerRefreshCompleter?.future;
      return;
    }
    final refreshCompleter = Completer<void>();
    _providerRefreshCompleter = refreshCompleter;
    final refreshGeneration = ++_providerRefreshGeneration;
    _isRefreshingModels = true;
    try {
      final snapshots = List<ModelProviderProfileSummary>.from(_profiles);
      final nextOfficialModels = {
        for (final capabilityEntry
            in _officialProviderModelsByCapability.entries)
          capabilityEntry.key: {
            for (final profileEntry in capabilityEntry.value.entries)
              profileEntry.key: List<ProviderModelOption>.from(
                profileEntry.value,
              ),
          },
      };
      final officialProfiles = snapshots.where(_isOfficialProfile).toList();
      if (officialProfiles.isEmpty) {
        return;
      }
      final capabilityRefreshes = <String, Future<void>>{
        for (final capability in _requiredOfficialCapabilities)
          capability: _refreshOfficialCapability(
            capability: capability,
            profiles: officialProfiles,
            refreshGeneration: refreshGeneration,
            target: nextOfficialModels,
          ),
      };
      _officialCapabilityRefreshes = capabilityRefreshes;
      await Future.wait(capabilityRefreshes.values);

      if (!await waitForPageReady() ||
          !_isProviderRefreshActive(refreshGeneration)) {
        return;
      }
      setState(() {
        _officialProviderModelsByCapability = nextOfficialModels;
      });
    } catch (_) {
      if (mounted && _isProviderRefreshActive(refreshGeneration)) {
        showToast(context.l10n.sceneModelLoadFailed, type: ToastType.error);
      }
    } finally {
      if (refreshGeneration == _providerRefreshGeneration) {
        _isRefreshingModels = false;
        _officialCapabilityRefreshes = {};
      }
      if (!refreshCompleter.isCompleted) {
        refreshCompleter.complete();
      }
      if (identical(_providerRefreshCompleter, refreshCompleter)) {
        _providerRefreshCompleter = null;
      }
    }
  }

  Future<void> _refreshOfficialCapability({
    required String capability,
    required List<ModelProviderProfileSummary> profiles,
    required int refreshGeneration,
    required Map<String, Map<String, List<ProviderModelOption>>> target,
  }) async {
    final capabilityModels = <String, List<ProviderModelOption>>{
      for (final entry
          in (target[capability] ?? const <String, List<ProviderModelOption>>{})
              .entries)
        entry.key: List<ProviderModelOption>.from(entry.value),
    };
    for (final profile in profiles) {
      if (!_isProviderRefreshActive(refreshGeneration)) return;
      try {
        capabilityModels[profile.id] = await _fetchModelsForSnapshot(
          profile,
          refreshGeneration: refreshGeneration,
          capability: capability,
        );
      } catch (_) {
        capabilityModels[profile.id] = const [];
      }
    }
    if (!_isProviderRefreshActive(refreshGeneration)) return;
    target[capability] = capabilityModels;
    setState(() {
      _officialProviderModelsByCapability = {
        ..._officialProviderModelsByCapability,
        capability: {
          for (final entry in capabilityModels.entries)
            entry.key: List<ProviderModelOption>.from(entry.value),
        },
      };
    });
  }

  Future<List<ProviderModelOption>> _fetchModelsForSnapshot(
    ModelProviderProfileSummary snapshot, {
    required int refreshGeneration,
    String? capability,
  }) async {
    if (!_isProviderRefreshActive(refreshGeneration)) return const [];
    final current = _findProfile(_profiles, snapshot.id);
    if (current == null || !_sameProviderSnapshot(snapshot, current)) {
      return const [];
    }
    final models = await ModelProviderConfigService.fetchModels(
      apiBase: snapshot.baseUrl,
      profileId: snapshot.id,
      providerName: snapshot.name,
      capability: capability,
      forceRefresh: true,
    );
    if (!_isProviderRefreshActive(refreshGeneration)) return const [];
    final latestPayload = await ModelProviderConfigService.listProfiles();
    if (!_isProviderRefreshActive(refreshGeneration)) return const [];
    final latest = _findProfile(latestPayload.profiles, snapshot.id);
    final local = _findProfile(_profiles, snapshot.id);
    if (latest == null ||
        local == null ||
        !_sameProviderSnapshot(snapshot, latest) ||
        !_sameProviderSnapshot(snapshot, local)) {
      return const [];
    }
    return models;
  }

  bool _isProviderRefreshActive(int generation) =>
      mounted &&
      _isRefreshingModels &&
      generation == _providerRefreshGeneration;

  ModelProviderProfileSummary? _findProfile(
    List<ModelProviderProfileSummary> profiles,
    String id,
  ) {
    for (final profile in profiles) {
      if (profile.id == id) return profile;
    }
    return null;
  }

  bool _sameProviderSnapshot(
    ModelProviderProfileSummary left,
    ModelProviderProfileSummary right,
  ) =>
      left.id == right.id &&
      left.baseUrl == right.baseUrl &&
      left.revision == right.revision &&
      left.sourceType == right.sourceType &&
      left.configured == right.configured;

  Future<void> _saveSceneBinding({
    required SceneCatalogItem scene,
    required String providerProfileId,
    required String modelId,
  }) async {
    final sceneId = scene.sceneId;
    final current = _bindingMap[sceneId];
    if (current?.providerProfileId == providerProfileId &&
        current?.modelId == modelId) {
      return;
    }
    if (!SceneModelConfigService.isValidModelName(modelId)) {
      showToast(context.l10n.sceneModelInvalidModelId, type: ToastType.error);
      return;
    }

    setState(() {
      _savingSceneIds = {..._savingSceneIds, sceneId};
    });
    try {
      final bindings = await SceneModelConfigService.saveSceneModelBinding(
        sceneId: sceneId,
        providerProfileId: providerProfileId,
        modelId: modelId,
      );
      if (!mounted) return;
      setState(() {
        _bindings = bindings;
      });
      if (sceneId == VoicePlaybackCoordinator.sceneVoiceId) {
        await VoicePlaybackCoordinator.instance.refreshConfiguration();
      }
      if (!mounted) return;
      showToast(
        context.l10n.sceneModelBoundToast(_sceneDisplayName(sceneId), modelId),
        type: ToastType.success,
      );
    } catch (e) {
      if (!mounted) return;
      showToast(
        context.l10n.sceneModelSaveFailed(
          _sceneDisplayName(sceneId),
          e.toString(),
        ),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _savingSceneIds = {..._savingSceneIds}..remove(sceneId);
        });
      }
    }
  }

  Future<void> _prepareSceneSelector(SceneCatalogItem scene) async {
    final sceneId = scene.sceneId;
    if (_loadingSceneModelIds.contains(sceneId)) {
      return;
    }
    final capability = _modelCapabilityForScene(sceneId);
    if (!_isOfficialCapabilityLoaded(capability)) {
      setState(() {
        _loadingSceneModelIds = {..._loadingSceneModelIds, sceneId};
      });
      try {
        await _ensureOfficialCapabilityLoaded(capability);
      } finally {
        if (mounted) {
          setState(() {
            _loadingSceneModelIds = {..._loadingSceneModelIds}..remove(sceneId);
          });
        }
      }
    }
  }

  Widget _buildCard({required Widget child}) {
    return OmniSettingsCard(child: child);
  }

  String _selectionLabel(SceneCatalogItem scene) {
    final binding = _bindingMap[scene.sceneId];
    if (binding == null) {
      if (scene.defaultModel.trim().isEmpty) {
        return context.trLegacy('未绑定');
      }
      return context.trLegacy('默认：${scene.defaultModel}');
    }
    final profile = _profiles.where(
      (item) => item.id == binding.providerProfileId,
    );
    final profileName = profile.isEmpty
        ? 'Provider unavailable'
        : profile.first.name;
    return '$profileName / ${binding.modelId}';
  }

  Widget _buildSceneLabel(SceneCatalogItem scene) {
    return Tooltip(
      message: _sceneTooltip(scene),
      triggerMode: TooltipTriggerMode.tap,
      showDuration: const Duration(seconds: 3),
      child: Row(
        children: [
          Flexible(
            child: Text(
              _sceneDisplayName(scene.sceneId),
              style: TextStyle(
                color: _primaryTextColor,
                fontSize: 15,
                fontWeight: FontWeight.w500,
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(width: 6),
          if (_isAgentScene(scene.sceneId)) ...[
            const AgentAvatarButton(size: 30, showEditBadge: true),
            const SizedBox(width: 6),
          ],
          Icon(LucideIcons.info, size: 15, color: _tertiaryTextColor),
        ],
      ),
    );
  }

  Widget _buildSceneSelectorField(
    SceneCatalogItem scene, {
    required bool isSaving,
  }) {
    final binding = _bindingMap[scene.sceneId];
    final modelsByProfile = _modelsForScene(scene);
    return Align(
      alignment: Alignment.centerRight,
      child: OmniSettingsDropdown<_SceneModelChoice>(
        dropdownKey: Key('scene-model-selector-${scene.sceneId}'),
        value: binding == null
            ? _defaultSceneModelChoice
            : (
                providerProfileId: binding.providerProfileId,
                modelId: binding.modelId,
              ),
        selectedLabel: _selectionLabel(scene),
        maxWidth: 164,
        menuMinWidth: 188,
        menuMaxWidth: 360,
        searchHint: context.trLegacy('搜索模型或服务商'),
        onBeforeOpen: () => _prepareSceneSelector(scene),
        options: [
          for (final profile in _profiles)
            if (profile.configured && !profile.needsPresetCredentials)
              for (final model
                  in modelsByProfile[profile.id] ??
                      const <ProviderModelOption>[])
                OmniSettingsOption(
                  value: (providerProfileId: profile.id, modelId: model.id),
                  label: model.id,
                  leading: ProviderVendorIcon(
                    vendor: ModelVendorCatalog.resolve(
                      model.id,
                      ownedBy: model.ownedBy,
                      providerId: model.modelsDevProviderId,
                    ),
                    size: OmniMenuStyle.iconSize,
                    forceMonochrome: true,
                    monochromeColor: _primaryTextColor,
                  ),
                  trailingLabel: profile.name,
                  searchText: model.displayName,
                ),
        ],
        onChanged: isSaving
            ? null
            : (selection) {
                if (selection == null || !mounted) return;
                unawaited(
                  _saveSceneBinding(
                    scene: scene,
                    providerProfileId: selection.providerProfileId,
                    modelId: selection.modelId,
                  ),
                );
              },
      ),
    );
  }

  Widget _buildDefaultSceneRow(SceneCatalogItem scene) {
    final isSaving = _isSavingScene(scene.sceneId);
    final isLoadingModels = _loadingSceneModelIds.contains(scene.sceneId);
    final isBusy = isSaving || isLoadingModels;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(child: _buildSceneLabel(scene)),
          const SizedBox(width: 12),
          _buildSceneSelectorField(scene, isSaving: isBusy),
          if (isBusy) ...[
            const SizedBox(width: 8),
            const SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildSceneRow(SceneCatalogItem scene) {
    if (scene.sceneId != 'scene.conversation.title') {
      return _buildDefaultSceneRow(scene);
    }
    final english = Localizations.localeOf(context).languageCode == 'en';
    return Column(
      children: [
        _buildDefaultSceneRow(scene),
        Padding(
          padding: const EdgeInsets.only(top: 10, bottom: 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  english
                      ? 'Automatically name new conversations'
                      : '自动为新对话生成标题',
                  style: TextStyle(color: _secondaryTextColor, fontSize: 13),
                ),
              ),
              const SizedBox(width: 12),
              OmniSettingsSwitch(
                key: const Key('scene-title-enabled-switch'),
                value: _titleEnabled,
                enabled: !_savingTitle,
                onToggle: _saveTitleEnabled,
              ),
            ],
          ),
        ),
      ],
    );
  }

  Future<void> _saveTitleEnabled(bool enabled) async {
    setState(() => _savingTitle = true);
    try {
      final saved = await SceneModelConfigService.saveSceneTitleEnabled(
        enabled,
      );
      if (mounted) setState(() => _titleEnabled = saved);
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              Localizations.localeOf(context).languageCode == 'en'
                  ? 'Could not save setting'
                  : '设置保存失败',
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _savingTitle = false);
    }
  }

  Widget _buildVoiceSection() {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final coordinator = VoicePlaybackCoordinator.instance;
    final providers = _voiceConfig.speechProviders;
    final recognizers = providers
        .where((provider) => provider.recognition)
        .toList();
    final recognizer =
        _voiceConfig.recognitionProvider ??
        recognizers.where((provider) => provider.ready).firstOrNull ??
        recognizers.firstOrNull;
    final synthesizer = _voiceConfig.synthesisProvider;
    final voices = synthesizer?.voices ?? const <SpeechVoiceOption>[];
    final voice =
        voices
            .where((voice) => voice.id == _voiceConfig.speechSynthesisVoiceId)
            .firstOrNull ??
        voices.firstOrNull;
    final builtinLabel = english ? 'Scene model' : '场景模型';
    final customLabel = english ? 'Custom cURL' : '自定义 cURL';
    final synthesisChoice = (
      mode: _voiceConfig.ttsMode,
      id: synthesizer?.id ?? '',
    );
    final missingKey =
        (recognizer != null && !recognizer.ready) ||
        (_voiceConfig.usesSpeechProvider && synthesizer?.ready != true);

    return AnimatedBuilder(
      animation: coordinator,
      builder: (context, _) {
        final bound = coordinator.isVoiceSceneBound;
        final status = bound
            ? (english ? 'Connected' : '已连接')
            : (english ? 'Not connected' : '未连接');
        final synthesisNote =
            _voiceConfig.usesSpeechProvider && synthesizer != null
            ? (english
                  ? 'Text is sent to ${synthesizer.label} when you play a reply or enable automatic reading.'
                  : '播放回复或开启自动朗读时，回复文本会发送给 ${synthesizer.label}。')
            : bound
            ? context.trLegacy('助手回复支持手动播放和自动朗读。')
            : context.trLegacy('在上方绑定语音合成场景的 Provider 和模型，即可播放。');

        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (recognizer != null) ...[
              OmniSettingsGroup(
                dividerIndent: 54,
                footer: Text(
                  english
                      ? 'Your recording is sent to ${recognizer.label} when you finish dictating.'
                      : '结束听写后，录音会发送给 ${recognizer.label} 转写。',
                ),
                children: [
                  OmniSettingsTile(
                    icon: LucideIcons.mic,
                    title: english ? 'Voice input' : '语音输入',
                    subtitle: english
                        ? 'Tap to dictate or hold to talk'
                        : '点击听写或长按说话',
                    translateText: false,
                    trailing: OmniSettingsSwitch(
                      key: const Key('scene-voice-recognition-switch'),
                      value: _voiceConfig.recognitionProvider != null,
                      enabled:
                          !_savingVoice &&
                          (_voiceConfig.recognitionProvider != null ||
                              recognizer.ready),
                      onToggle: (value) => _saveVoiceConfig(
                        _voiceConfig.copyWith(
                          speechRecognitionProviderId: value
                              ? recognizer.id
                              : '',
                        ),
                      ),
                    ),
                  ),
                  if (recognizers.length > 1 &&
                      _voiceConfig.recognitionProvider != null)
                    OmniSettingsTile(
                      icon: LucideIcons.audioLines,
                      title: english ? 'Recognition provider' : '转写服务',
                      translateText: false,
                      trailing: OmniSettingsDropdown<String>(
                        value: recognizer.id,
                        selectedLabel: recognizer.label,
                        options: [
                          for (final provider in recognizers.where(
                            (provider) => provider.ready,
                          ))
                            OmniSettingsOption(
                              value: provider.id,
                              label: provider.label,
                            ),
                        ],
                        onChanged: _savingVoice
                            ? null
                            : (id) {
                                if (id != null) {
                                  _saveVoiceConfig(
                                    _voiceConfig.copyWith(
                                      speechRecognitionProviderId: id,
                                    ),
                                  );
                                }
                              },
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 14),
            ],
            OmniSettingsGroup(
              dividerIndent: 54,
              footer: Text(synthesisNote),
              children: [
                OmniSettingsTile(
                  icon: LucideIcons.volume2,
                  title: english ? 'Speech synthesis' : '语音合成服务',
                  translateText: false,
                  trailing: OmniSettingsDropdown<({String mode, String id})>(
                    dropdownKey: const Key('scene-voice-synthesis-provider'),
                    value: synthesisChoice,
                    selectedLabel: _voiceConfig.usesSpeechProvider
                        ? (synthesizer?.label ?? status)
                        : _voiceConfig.isCustomCurl
                        ? customLabel
                        : builtinLabel,
                    options: [
                      OmniSettingsOption(
                        value: (mode: SceneVoiceConfig.ttsModeBuiltin, id: ''),
                        label: builtinLabel,
                      ),
                      if (_voiceConfig.hasCustomCurlCommand)
                        OmniSettingsOption(
                          value: (
                            mode: SceneVoiceConfig.ttsModeCustomCurl,
                            id: '',
                          ),
                          label: customLabel,
                        ),
                      for (final provider in providers.where(
                        (provider) => provider.synthesis,
                      ))
                        OmniSettingsOption(
                          value: (
                            mode: SceneVoiceConfig.ttsModeProvider,
                            id: provider.id,
                          ),
                          label: provider.label,
                        ),
                    ],
                    onChanged: _savingVoice
                        ? null
                        : (selection) {
                            if (selection == null ||
                                selection == synthesisChoice) {
                              return;
                            }
                            final selected = providers
                                .where(
                                  (provider) => provider.id == selection.id,
                                )
                                .firstOrNull;
                            _saveVoiceConfig(
                              _voiceConfig.copyWith(
                                ttsMode: selection.mode,
                                speechSynthesisProviderId: selected?.id,
                                speechSynthesisVoiceId:
                                    selected?.id == synthesizer?.id
                                    ? null
                                    : selected?.voices.firstOrNull?.id ?? '',
                              ),
                            );
                          },
                  ),
                ),
                if (voice != null)
                  OmniSettingsTile(
                    icon: LucideIcons.audioWaveform,
                    title: english ? 'Voice' : '音色',
                    translateText: false,
                    trailing: OmniSettingsDropdown<String>(
                      dropdownKey: const Key('scene-voice-synthesis-voice'),
                      value: voice.id,
                      selectedLabel: english ? voice.englishLabel : voice.label,
                      options: [
                        for (final option in voices)
                          OmniSettingsOption(
                            value: option.id,
                            label: english ? option.englishLabel : option.label,
                          ),
                      ],
                      onChanged: _savingVoice
                          ? null
                          : (id) {
                              if (id != null) {
                                _saveVoiceConfig(
                                  _voiceConfig.copyWith(
                                    speechSynthesisVoiceId: id,
                                  ),
                                );
                              }
                            },
                    ),
                  ),
                OmniSettingsTile(
                  icon: LucideIcons.headphones,
                  title: english ? 'Read replies aloud automatically' : '自动朗读回复',
                  subtitle: bound
                      ? null
                      : (english
                            ? 'Configure a synthesis service first'
                            : '需先配置可用的语音合成服务'),
                  translateText: false,
                  trailing: OmniSettingsSwitch(
                    key: const Key('scene-voice-autoplay-switch'),
                    value: _voiceConfig.autoPlay,
                    enabled: bound && !_savingVoice,
                    onToggle: (value) => _saveVoiceConfig(
                      _voiceConfig.copyWith(autoPlay: value),
                    ),
                  ),
                ),
              ],
            ),
            if (missingKey) ...[
              const SizedBox(height: 14),
              OmniSettingsGroup(
                dividerIndent: 54,
                children: [
                  OmniSettingsTile(
                    icon: LucideIcons.keyRound,
                    iconColor: context.omniPalette.brandPrimary,
                    title: english
                        ? 'Set up a model provider API key'
                        : '配置模型提供商 API Key',
                    subtitle: english
                        ? 'Speech features reuse your configured provider key'
                        : '语音能力复用已配置的模型提供商 API Key',
                    translateText: false,
                    showChevron: true,
                    onTap: () async {
                      await context.push('/home/model_provider_setting');
                      if (mounted) await _loadData(showLoading: false);
                    },
                  ),
                ],
              ),
            ],
          ],
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.settingsSceneModelTitle,
        primary: true,
      ),
      body: OmniContentWidth(
        child: SafeArea(
          top: false,
          bottom: false,
          child: _isLoading
              ? const Center(child: CircularProgressIndicator())
              : ListView(
                  padding: edgeToEdgeScrollPadding(
                    context,
                    const EdgeInsets.fromLTRB(22, 12, 22, 24),
                  ),
                  children: [
                    SettingsSectionTitle(
                      label: context.l10n.sceneModelMapping,
                      subtitle: context.l10n.sceneModelMappingDesc,
                    ),
                    _buildCard(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            context.trLegacy(
                              '选择各场景使用的已启用模型；模型获取与启停请在“模型提供商”中统一管理。',
                            ),
                            style: TextStyle(
                              color: _secondaryTextColor,
                              fontSize: 12,
                              height: 1.5,
                              fontFamily: 'PingFang SC',
                            ),
                          ),
                          const SizedBox(height: 12),
                          if (_orderedCatalog.isEmpty)
                            Padding(
                              padding: EdgeInsets.symmetric(vertical: 12),
                              child: Text(
                                context.l10n.sceneModelNoScenes,
                                style: TextStyle(
                                  color: _secondaryTextColor,
                                  fontSize: 12,
                                  fontFamily: 'PingFang SC',
                                ),
                              ),
                            )
                          else
                            ListView.separated(
                              physics: const NeverScrollableScrollPhysics(),
                              shrinkWrap: true,
                              itemCount: _orderedCatalog.length,
                              itemBuilder: (context, index) {
                                final scene = _orderedCatalog[index];
                                return _buildSceneRow(scene);
                              },
                              separatorBuilder: (_, _) => Divider(
                                height: 20,
                                thickness: 0.6,
                                color: context.omniPalette.borderSubtle
                                    .withValues(alpha: 0.9),
                              ),
                            ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 22),
                    SettingsSectionTitle(label: context.trLegacy('语音能力')),
                    _buildVoiceSection(),
                  ],
                ),
        ),
      ),
    );
  }
}
