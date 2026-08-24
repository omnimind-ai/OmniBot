import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/scene_model_config_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/popup_menu_anchor_position.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/agent_avatar.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/widgets/settings_section_title.dart';

const double _kSceneSelectionPopupMaxHeight = 420;

Widget _buildSceneModelIdTooltip({
  required String modelId,
  required Widget child,
}) {
  return Tooltip(
    message: modelId,
    triggerMode: TooltipTriggerMode.longPress,
    waitDuration: Duration.zero,
    showDuration: const Duration(seconds: 3),
    preferBelow: false,
    textAlign: TextAlign.start,
    constraints: const BoxConstraints(maxWidth: 320),
    child: child,
  );
}

class SceneModelSettingPage extends StatefulWidget {
  const SceneModelSettingPage({super.key});

  @override
  State<SceneModelSettingPage> createState() => _SceneModelSettingPageState();
}

class _SceneModelSettingPageState extends State<SceneModelSettingPage> {
  static const List<String> _sceneOrder = [
    'scene.dispatch.model',
    'scene.compactor.context.chat',
    'scene.memory.embedding',
    'scene.memory.rollup',
  ];

  static const Map<String, String> _sceneDisplayNameMap = {
    'scene.dispatch.model': 'Agent',
    'scene.vlm.operation.primary': 'GUI',
    'scene.compactor.context.chat': 'Chat Compactor',
    'scene.memory.embedding': 'Memory Embed',
    'scene.memory.rollup': 'Memory Rollup',
  };

  static const Map<String, String> _sceneTooltipMap = {
    'scene.dispatch.model': '负责任务理解与分流决策',
    'scene.vlm.operation.primary': '负责 Android GUI 观察与动作决策',
    'scene.compactor.context.chat': '负责聊天历史压缩总结',
    'scene.memory.embedding': '负责 workspace 记忆向量检索的嵌入模型',
    'scene.memory.rollup': '负责夜间记忆整理策略模型',
  };
  static const String _officialSourceType = 'omnibot_official';
  static const String _textCapability = 'text';
  static const String _embeddingCapability = 'embedding';

  bool _isLoading = true;
  bool _isRefreshingModels = false;
  Completer<void>? _providerRefreshCompleter;
  Map<String, Future<void>> _officialCapabilityRefreshes = {};
  int _providerRefreshGeneration = 0;
  List<SceneCatalogItem> _catalog = const [];
  List<SceneModelBindingEntry> _bindings = const [];
  List<ModelProviderProfileSummary> _profiles = const [];
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
        if (item.sceneId != 'scene.compactor.context') item.sceneId: item,
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
  String _sceneDisplayName(String sceneId) {
    return _sceneDisplayNameMap[sceneId] ?? sceneId;
  }

  String _sceneTooltip(SceneCatalogItem item) {
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
    if (showLoading && mounted) {
      setState(() => _isLoading = true);
    }
    try {
      final results = await Future.wait<dynamic>([
        SceneModelConfigService.getSceneCatalog(),
        SceneModelConfigService.getSceneModelBindings(),
        ModelProviderConfigService.listProfiles(),
      ]);
      if (!mounted) return;

      final catalog = results[0] as List<SceneCatalogItem>;
      final bindings = results[1] as List<SceneModelBindingEntry>;
      final profilesPayload = results[2] as ModelProviderProfilesPayload;
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

      final enriched = _mergeBindingModels(
        providerModelsByProfileId: providerModelsByProfileId,
        bindings: bindings,
      );

      setState(() {
        _catalog = catalog;
        _bindings = bindings;
        _profiles = profilesPayload.profiles;
        _providerModelsByProfileId = enriched;
        _officialProviderModelsByCapability = {};
      });
      _scheduleMetadataRefresh(
        profiles: profilesPayload.profiles,
        providerModelsByProfileId: enriched,
      );
      _scheduleProviderModelRefresh(profilesPayload.profiles);
    } catch (_) {
      if (!mounted) return;
      showToast(context.l10n.sceneModelLoadFailed, type: ToastType.error);
    } finally {
      if (showLoading && mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  void _scheduleProviderModelRefresh(
    List<ModelProviderProfileSummary> profiles,
  ) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !identical(_profiles, profiles)) return;
      unawaited(_refreshProviderModelsInBackground());
    });
  }

  Map<String, List<ProviderModelOption>> _mergeBindingModels({
    required Map<String, List<ProviderModelOption>> providerModelsByProfileId,
    required List<SceneModelBindingEntry> bindings,
  }) {
    final result = {
      for (final entry in providerModelsByProfileId.entries)
        entry.key: [...entry.value],
    };
    for (final binding in bindings) {
      final bucket = result.putIfAbsent(binding.providerProfileId, () => []);
      final exists = bucket.any((item) => item.id == binding.modelId);
      if (!exists) {
        bucket.add(
          ProviderModelOption(
            id: binding.modelId,
            displayName: binding.modelId,
            ownedBy: 'binding',
          ),
        );
      }
    }
    return result;
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
    if (!mounted ||
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
      final nextModels = <String, List<ProviderModelOption>>{
        for (final entry in _providerModelsByProfileId.entries)
          entry.key: List<ProviderModelOption>.from(entry.value),
      };
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
      for (final profile in snapshots) {
        if (!_isProviderRefreshActive(refreshGeneration)) return;
        final isOfficial = _isOfficialProfile(profile);
        if (!isOfficial && !profile.configured) {
          continue;
        }
        if (isOfficial) {
          continue;
        }
        try {
          final remoteModels = await _fetchModelsForSnapshot(
            profile,
            refreshGeneration: refreshGeneration,
          );
          if (!_isProviderRefreshActive(refreshGeneration)) return;
          final manualModelIds =
              await ModelProviderConfigService.getManualModelIds(
                profileId: profile.id,
              );
          nextModels[profile.id] = ModelProviderConfigService.mergeModelOptions(
            remoteModels: remoteModels,
            manualModelIds: manualModelIds,
          );
        } catch (_) {
          // Keep the last cached list. Background refresh must not interrupt
          // scene settings with dialogs or transient network error toasts.
        }
      }
      await Future.wait(capabilityRefreshes.values);

      if (!_isProviderRefreshActive(refreshGeneration)) return;
      final merged = _mergeBindingModels(
        providerModelsByProfileId: nextModels,
        bindings: _bindings,
      );
      setState(() {
        _providerModelsByProfileId = merged;
        _officialProviderModelsByCapability = nextOfficialModels;
      });
      _scheduleMetadataRefresh(
        profiles: snapshots,
        providerModelsByProfileId: merged,
      );
    } catch (_) {
      // Initial cached content remains usable when background refresh fails.
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
        // Preserve the last capability-specific official list.
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
        _providerModelsByProfileId = _mergeBindingModels(
          providerModelsByProfileId: _providerModelsByProfileId,
          bindings: bindings,
        );
      });
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

  Future<void> _clearSceneBindingLocalized(SceneCatalogItem scene) async {
    final sceneId = scene.sceneId;
    if (!_bindingMap.containsKey(sceneId)) {
      return;
    }
    setState(() {
      _savingSceneIds = {..._savingSceneIds, sceneId};
    });
    try {
      final bindings = await SceneModelConfigService.clearSceneModelBinding(
        sceneId,
      );
      if (!mounted) return;
      setState(() {
        _bindings = bindings;
      });
      showToast(
        context.l10n.sceneModelDefaultRestored(_sceneDisplayName(sceneId)),
        type: ToastType.success,
      );
    } catch (e) {
      if (!mounted) return;
      showToast(
        context.l10n.sceneModelClearFailed(
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

  Future<void> _openSceneSelector(
    SceneCatalogItem scene,
    BuildContext anchorContext,
  ) async {
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
      if (!mounted || !anchorContext.mounted) {
        return;
      }
    }
    final binding = _bindingMap[scene.sceneId];
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
    final popupWidth = anchorBox.size.width
        .clamp(160.0, overlay.size.width - 16.0)
        .toDouble();
    final position = PopupMenuAnchorPosition.fromAnchorRect(
      anchorRect: anchorRect,
      overlaySize: overlay.size,
      estimatedMenuHeight: _kSceneSelectionPopupMaxHeight,
      reservedBottom: (() {
        final viewInsetBottom = MediaQuery.of(context).viewInsets.bottom;
        return viewInsetBottom > 0 ? viewInsetBottom : 280.0;
      })(),
    );

    final result = await showMenu<_SceneSelectionAction>(
      context: context,
      color: _cardColor,
      elevation: 8,
      constraints: BoxConstraints(minWidth: popupWidth, maxWidth: popupWidth),
      position: position,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      items: [
        _SceneSelectionPopupEntry(
          width: popupWidth,
          estimatedHeight: _kSceneSelectionPopupMaxHeight,
          scene: scene,
          profiles: _profiles,
          providerModelsByProfileId: _modelsForScene(scene),
          currentBinding: binding,
        ),
      ],
    );
    if (result == null) {
      return;
    }
    if (result.restoreDefault) {
      await _clearSceneBindingLocalized(scene);
      return;
    }
    if (result.providerProfileId.isNotEmpty && result.modelId.isNotEmpty) {
      await _saveSceneBinding(
        scene: scene,
        providerProfileId: result.providerProfileId,
        modelId: result.modelId,
      );
    }
  }

  Widget _buildCard({required Widget child}) {
    return SizedBox(width: double.infinity, child: child);
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
                fontSize: 14,
                fontWeight: FontWeight.w600,
                fontFamily: 'PingFang SC',
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
    return Builder(
      builder: (fieldContext) {
        return InkWell(
          key: Key('scene-model-selector-${scene.sceneId}'),
          onTap: isSaving
              ? null
              : () => _openSceneSelector(scene, fieldContext),
          borderRadius: BorderRadius.circular(8),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 11),
            decoration: BoxDecoration(
              color: _cardColor,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    _selectionLabel(scene),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: _primaryTextColor,
                      fontSize: 13,
                      fontFamily: 'PingFang SC',
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Icon(
                  LucideIcons.chevronDown,
                  size: 18,
                  color: _tertiaryTextColor,
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildDefaultSceneRow(SceneCatalogItem scene) {
    final isSaving = _isSavingScene(scene.sceneId);
    final isLoadingModels = _loadingSceneModelIds.contains(scene.sceneId);
    final isBusy = isSaving || isLoadingModels;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Expanded(flex: 4, child: _buildSceneLabel(scene)),
          const SizedBox(width: 10),
          Expanded(
            flex: 6,
            child: _buildSceneSelectorField(scene, isSaving: isBusy),
          ),
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
    return _buildDefaultSceneRow(scene);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.settingsSceneModelTitle,
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
                    label: context.l10n.sceneModelMapping,
                    subtitle: context.l10n.sceneModelMappingDesc,
                  ),
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          context.trLegacy('点击右侧按钮后，可按 Provider 搜索、折叠并选择模型。'),
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
                ],
              ),
      ),
    );
  }
}

class _SceneSelectionAction {
  final bool restoreDefault;
  final String providerProfileId;
  final String modelId;

  const _SceneSelectionAction.restore()
    : restoreDefault = true,
      providerProfileId = '',
      modelId = '';

  const _SceneSelectionAction.select({
    required this.providerProfileId,
    required this.modelId,
  }) : restoreDefault = false;
}

class _SceneSelectionPopupEntry extends PopupMenuEntry<_SceneSelectionAction> {
  const _SceneSelectionPopupEntry({
    required this.width,
    required this.estimatedHeight,
    required this.scene,
    required this.profiles,
    required this.providerModelsByProfileId,
    required this.currentBinding,
  });

  final double width;
  final double estimatedHeight;
  final SceneCatalogItem scene;
  final List<ModelProviderProfileSummary> profiles;
  final Map<String, List<ProviderModelOption>> providerModelsByProfileId;
  final SceneModelBindingEntry? currentBinding;

  @override
  double get height => estimatedHeight;

  @override
  bool represents(_SceneSelectionAction? value) => false;

  @override
  State<_SceneSelectionPopupEntry> createState() =>
      _SceneSelectionPopupEntryState();
}

class _SceneSelectionPopupEntryState extends State<_SceneSelectionPopupEntry> {
  final TextEditingController _searchController = TextEditingController();
  late final Set<String> _expandedProfileIds;

  bool get _hasSearchQuery => _searchController.text.trim().isNotEmpty;

  @override
  void initState() {
    super.initState();
    _expandedProfileIds = <String>{
      if (widget.currentBinding != null)
        widget.currentBinding!.providerProfileId,
    };
    if (_expandedProfileIds.isEmpty && widget.profiles.isNotEmpty) {
      _expandedProfileIds.add(widget.profiles.first.id);
    }
    _searchController.addListener(() {
      setState(() {});
    });
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<ProviderModelOption> _filteredModels(String profileId) {
    final query = _searchController.text.trim().toLowerCase();
    final models = widget.providerModelsByProfileId[profileId] ?? const [];
    if (query.isEmpty) {
      return models;
    }
    return models
        .where((item) => item.id.toLowerCase().contains(query))
        .toList();
  }

  List<ModelProviderProfileSummary> get _visibleProfiles {
    if (!_hasSearchQuery) {
      return widget.profiles;
    }
    return widget.profiles.where((profile) {
      return _filteredModels(profile.id).isNotEmpty;
    }).toList();
  }

  bool _isExpanded(String profileId) {
    if (_hasSearchQuery) {
      return true;
    }
    return _expandedProfileIds.contains(profileId);
  }

  bool get _isDarkTheme => context.isDarkTheme;
  Color get _selectedSurfaceColor =>
      _isDarkTheme ? context.omniPalette.segmentThumb : const Color(0xFFEAF3FF);
  Color get _primaryTextColor =>
      _isDarkTheme ? context.omniPalette.textPrimary : AppColors.text;
  Color get _secondaryTextColor => _isDarkTheme
      ? context.omniPalette.textSecondary
      : const Color(0xFF64748B);
  Color get _tertiaryTextColor =>
      _isDarkTheme ? context.omniPalette.textTertiary : const Color(0xFF94A3B8);

  Widget _buildSearchRow() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 8),
      child: Row(
        children: [
          Icon(LucideIcons.search, size: 18, color: _tertiaryTextColor),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: _searchController,
              autofocus: false,
              scrollPadding: EdgeInsets.zero,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => FocusManager.instance.primaryFocus?.unfocus(),
              style: TextStyle(
                fontSize: 13,
                color: _primaryTextColor,
                fontWeight: FontWeight.w500,
                fontFamily: 'PingFang SC',
              ),
              decoration: InputDecoration(
                isDense: true,
                hintText: 'Filter model ID',
                hintStyle: TextStyle(
                  fontSize: 13,
                  color: _tertiaryTextColor,
                  fontWeight: FontWeight.w500,
                  fontFamily: 'PingFang SC',
                ),
                border: InputBorder.none,
                focusedBorder: InputBorder.none,
                enabledBorder: InputBorder.none,
                contentPadding: EdgeInsets.zero,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRestoreDefaultTile() {
    final selected = widget.currentBinding == null;
    final label = context.trLegacy('恢复默认（${widget.scene.defaultModel}）');
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 4),
      child: InkWell(
        onTap: () {
          Navigator.of(context).pop(const _SceneSelectionAction.restore());
        },
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: selected ? _selectedSurfaceColor : Colors.transparent,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    color: _primaryTextColor,
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              if (selected)
                Icon(
                  LucideIcons.check,
                  size: 15,
                  color: _isDarkTheme
                      ? context.omniPalette.accentPrimary
                      : const Color(0xFF2C7FEB),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSectionHeader(ModelProviderProfileSummary profile) {
    final expanded = _isExpanded(profile.id);
    final models = _filteredModels(profile.id);
    final isCurrent = widget.currentBinding?.providerProfileId == profile.id;

    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: InkWell(
        onTap: () {
          if (_hasSearchQuery) {
            return;
          }
          setState(() {
            if (expanded) {
              _expandedProfileIds.remove(profile.id);
            } else {
              _expandedProfileIds.add(profile.id);
            }
          });
        },
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: isCurrent ? _selectedSurfaceColor : Colors.transparent,
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
                    fontSize: 12,
                    color: _secondaryTextColor,
                    fontWeight: FontWeight.w600,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              Text(
                profile.configured ? '${models.length}' : 'Not configured',
                style: TextStyle(
                  fontSize: 11,
                  color: _tertiaryTextColor,
                  fontWeight: FontWeight.w600,
                  fontFamily: 'PingFang SC',
                ),
              ),
              if (isCurrent) ...[
                const SizedBox(width: 6),
                Icon(
                  LucideIcons.circleCheck,
                  size: 13,
                  color: _isDarkTheme
                      ? context.omniPalette.accentPrimary
                      : const Color(0xFF2C7FEB),
                ),
              ],
              const SizedBox(width: 6),
              Icon(
                _hasSearchQuery
                    ? LucideIcons.chevronsUpDown
                    : expanded
                    ? LucideIcons.chevronUp
                    : LucideIcons.chevronDown,
                size: 16,
                color: _tertiaryTextColor,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildModelRow({
    required ModelProviderProfileSummary profile,
    required ProviderModelOption item,
  }) {
    final selected =
        widget.currentBinding?.providerProfileId == profile.id &&
        widget.currentBinding?.modelId == item.id;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: _buildSceneModelIdTooltip(
        modelId: item.id,
        child: InkWell(
          onTap: () {
            Navigator.of(context).pop(
              _SceneSelectionAction.select(
                providerProfileId: profile.id,
                modelId: item.id,
              ),
            );
          },
          borderRadius: BorderRadius.circular(12),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: selected ? _selectedSurfaceColor : Colors.transparent,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    item.id,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      color: _primaryTextColor,
                      fontWeight: FontWeight.w500,
                      fontFamily: 'PingFang SC',
                    ),
                  ),
                ),
                if (selected)
                  Icon(
                    LucideIcons.check,
                    size: 15,
                    color: _isDarkTheme
                        ? context.omniPalette.accentPrimary
                        : const Color(0xFF2C7FEB),
                  ),
              ],
            ),
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
            .clamp(220.0, _kSceneSelectionPopupMaxHeight)
            .toDouble();
    final visibleProfiles = _visibleProfiles;
    return SizedBox(
      width: widget.width,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _buildSearchRow(),
            _buildRestoreDefaultTile(),
            if (widget.profiles.isEmpty)
              Padding(
                padding: EdgeInsets.all(16),
                child: Text(
                  'No available Providers yet. Please configure a model provider first.',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 12,
                    color: _tertiaryTextColor,
                    fontWeight: FontWeight.w500,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              )
            else if (visibleProfiles.isEmpty)
              Padding(
                padding: EdgeInsets.all(16),
                child: Text(
                  'No matching models',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 12,
                    color: _tertiaryTextColor,
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
                    itemCount: visibleProfiles.length,
                    itemBuilder: (context, index) {
                      final profile = visibleProfiles[index];
                      final expanded = _isExpanded(profile.id);
                      final models = _filteredModels(profile.id);
                      return Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          _buildSectionHeader(profile),
                          if (expanded)
                            profile.configured
                                ? models.isEmpty
                                      ? Padding(
                                          padding: EdgeInsets.fromLTRB(
                                            12,
                                            4,
                                            12,
                                            8,
                                          ),
                                          child: Text(
                                            'No selectable models for this Provider',
                                            style: TextStyle(
                                              fontSize: 12,
                                              color: _tertiaryTextColor,
                                              fontFamily: 'PingFang SC',
                                            ),
                                          ),
                                        )
                                      : Column(
                                          children: models.map((item) {
                                            return _buildModelRow(
                                              profile: profile,
                                              item: item,
                                            );
                                          }).toList(),
                                        )
                                : Padding(
                                    padding: EdgeInsets.fromLTRB(12, 4, 12, 8),
                                    child: Text(
                                      'Please configure this Provider in the model provider settings first',
                                      style: TextStyle(
                                        fontSize: 12,
                                        color: _tertiaryTextColor,
                                        fontFamily: 'PingFang SC',
                                      ),
                                    ),
                                  ),
                          if (index != visibleProfiles.length - 1)
                            const SizedBox(height: 6),
                        ],
                      );
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
