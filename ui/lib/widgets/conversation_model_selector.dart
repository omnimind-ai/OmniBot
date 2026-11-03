import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/model_vendor_catalog.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';

class ConversationModelSelection {
  const ConversationModelSelection({
    required this.providerProfileId,
    required this.modelId,
  });

  final String providerProfileId;
  final String modelId;
}

Widget buildConversationModelIdTooltip({
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

class ConversationModelSelectorContent extends StatefulWidget {
  const ConversationModelSelectorContent({
    super.key,
    required this.width,
    required this.maxHeight,
    this.profiles = const [],
    this.providerModelsByProfileId = const {},
    this.currentSelection,
    this.options,
    this.selectedValue,
    this.onSelectValue,
    this.header,
    this.showVendorIcons = true,
    this.onSearchSubmitted,
    this.onSelect,
    this.footer,
    this.emptyProvidersLabel,
    this.emptyMatchesLabel,
    this.emptyModelsLabel,
    this.modelRowKeyPrefix,
    this.showSearchField = true,
    this.showProfileHeaders = true,
    this.allowProfileCollapse = true,
    this.loadLiveProviders = false,
  });

  /// Session-declared choices use the same card without fabricating providers.
  final List<ProviderModelOption>? options;
  final String? selectedValue;
  final ValueChanged<String>? onSelectValue;
  final Widget? header;
  final bool showVendorIcons;

  final double width;
  final double maxHeight;
  final List<ModelProviderProfileSummary> profiles;
  final Map<String, List<ProviderModelOption>> providerModelsByProfileId;
  final ConversationModelSelection? currentSelection;
  final VoidCallback? onSearchSubmitted;
  final ValueChanged<ConversationModelSelection>? onSelect;
  final Widget? footer;
  final String? emptyProvidersLabel;
  final String? emptyMatchesLabel;
  final String? emptyModelsLabel;
  final String? modelRowKeyPrefix;
  final bool showSearchField;
  final bool showProfileHeaders;
  final bool allowProfileCollapse;
  final bool loadLiveProviders;

  @override
  State<ConversationModelSelectorContent> createState() =>
      _ConversationModelSelectorContentState();
}

class _ConversationModelSelectorContentState
    extends State<ConversationModelSelectorContent> {
  static const double _kProfileHeaderExtent = 43.0;
  static const double _kModelRowExtent = 43.0;
  static const double _kProfileGapExtent = 6.0;

  final TextEditingController _searchController = TextEditingController();
  final ScrollController _listScrollController = ScrollController();
  final GlobalKey _selectedModelRowKey = GlobalKey();
  late final Set<String> _expandedProfileIds;
  List<ModelProviderProfileSummary> _liveProfiles = [];
  final Map<String, List<ProviderModelOption>> _liveModels = {};
  final Set<String> _loadingProviders = {};
  final Set<String> _failedProviders = {};
  final Map<String, String> _providerErrors = {};
  bool _loadingProfiles = false;
  bool _profilesFailed = false;
  List<ModelProviderProfileSummary> get _profiles =>
      widget.loadLiveProviders ? _liveProfiles : widget.profiles;

  Future<void> _loadProfiles() async {
    setState(() {
      _loadingProfiles = true;
      _profilesFailed = false;
    });
    try {
      final payload = await ModelProviderConfigService.listProfiles();
      if (!mounted) return;
      setState(() {
        _liveProfiles = payload.profiles
            .where((p) => p.configured && !p.needsPresetCredentials)
            .toList();
        _expandedProfileIds.addAll(_liveProfiles.map((p) => p.id));
        _loadingProfiles = false;
      });
      for (final profile in _liveProfiles) {
        unawaited(_loadProvider(profile));
      }
    } catch (_) {
      if (mounted)
        setState(() {
          _loadingProfiles = false;
          _profilesFailed = true;
        });
    }
  }

  Future<void> _loadProvider(ModelProviderProfileSummary profile) async {
    if (profile.needsPresetCredentials) return;
    if (_loadingProviders.contains(profile.id)) return;
    setState(() {
      _loadingProviders.add(profile.id);
      _failedProviders.remove(profile.id);
      _providerErrors.remove(profile.id);
      _liveModels.remove(profile.id);
    });
    try {
      final models = await ModelProviderConfigService.fetchChatModelsForProfile(
        profile,
      );
      if (mounted) setState(() => _liveModels[profile.id] = models);
    } catch (error) {
      if (mounted) {
        final manual =
            await ModelProviderConfigService.getChatModelOptionsForProfile(
              profile.id,
              profile: profile,
            );
        if (mounted)
          setState(() {
            _failedProviders.add(profile.id);
            _providerErrors[profile.id] = formatAgentRuntimeErrorForUser(
              error,
              english: Localizations.localeOf(context).languageCode == 'en',
            );
            _liveModels[profile.id] = manual;
          });
      }
    } finally {
      if (mounted) setState(() => _loadingProviders.remove(profile.id));
    }
  }

  bool get _hasSearchQuery =>
      widget.showSearchField && _searchController.text.trim().isNotEmpty;

  @override
  void initState() {
    super.initState();
    _expandedProfileIds = <String>{
      if (widget.currentSelection != null)
        widget.currentSelection!.providerProfileId,
    };
    if (_expandedProfileIds.isEmpty && widget.profiles.isNotEmpty) {
      _expandedProfileIds.add(widget.profiles.first.id);
    }
    _searchController.addListener(() {
      setState(() {});
    });
    if (widget.loadLiveProviders) unawaited(_loadProfiles());
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _autoScrollToSelectedModel();
      }
    });
  }

  @override
  void dispose() {
    _searchController.dispose();
    _listScrollController.dispose();
    super.dispose();
  }

  void _autoScrollToSelectedModel() {
    final selection = widget.currentSelection;
    if (selection == null || !_listScrollController.hasClients) {
      return;
    }
    final profiles = _visibleProfiles;
    var offset = 0.0;
    var found = false;
    for (final profile in profiles) {
      if (widget.showProfileHeaders) {
        offset += _kProfileHeaderExtent;
      }
      final isTargetProfile = profile.id == selection.providerProfileId;
      final expanded = _isExpanded(profile.id);
      if (expanded) {
        final models = _filteredModels(profile.id);
        final index = isTargetProfile
            ? models.indexWhere((m) => m.id == selection.modelId)
            : -1;
        if (index >= 0) {
          offset += index * _kModelRowExtent;
          found = true;
        } else {
          offset += models.length * _kModelRowExtent;
        }
      }
      if (found || isTargetProfile) {
        found = found || isTargetProfile;
        break;
      }
      offset += _kProfileGapExtent;
    }
    if (!found) {
      return;
    }
    final position = _listScrollController.position;
    if (position.maxScrollExtent <= 0) {
      return;
    }
    final target = (offset - position.viewportDimension * 0.35).clamp(
      0.0,
      position.maxScrollExtent,
    );
    _listScrollController.jumpTo(target);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final rowContext = _selectedModelRowKey.currentContext;
      if (rowContext != null) {
        Scrollable.ensureVisible(
          rowContext,
          alignment: 0.35,
          duration: const Duration(milliseconds: 160),
          curve: Curves.easeOutCubic,
        );
      }
    });
  }

  List<ProviderModelOption> _filteredModels(String profileId) {
    final query = _searchController.text.trim().toLowerCase();
    final models =
        (widget.loadLiveProviders
            ? _liveModels[profileId]
            : widget.providerModelsByProfileId[profileId]) ??
        const <ProviderModelOption>[];
    if (query.isEmpty) {
      return models;
    }
    return models.where((item) {
      final modelId = item.id.toLowerCase();
      final displayName = item.displayName.toLowerCase();
      return modelId.contains(query) || displayName.contains(query);
    }).toList();
  }

  List<ModelProviderProfileSummary> get _visibleProfiles {
    final configuredProfiles = _profiles
        .where(
          (profile) => profile.configured && !profile.needsPresetCredentials,
        )
        .toList();
    final currentProviderId = widget.currentSelection?.providerProfileId;
    if (currentProviderId != null) {
      final index = configuredProfiles.indexWhere(
        (p) => p.id == currentProviderId,
      );
      if (index > 0) {
        configuredProfiles.insert(0, configuredProfiles.removeAt(index));
      }
    }
    if (!_hasSearchQuery) {
      return configuredProfiles;
    }
    // Keep real search matches before loading/failed connections. A failed
    // unrelated connection must not push the model the user found off screen.
    final matches = configuredProfiles
        .where((profile) => _filteredModels(profile.id).isNotEmpty)
        .toList();
    return [
      ...matches,
      ...configuredProfiles.where(
        (profile) =>
            !matches.contains(profile) &&
            (_loadingProviders.contains(profile.id) ||
                _failedProviders.contains(profile.id)),
      ),
    ];
  }

  bool _isExpanded(String profileId) {
    if (!widget.allowProfileCollapse) return true;
    if (_hasSearchQuery) return true;
    return _expandedProfileIds.contains(profileId);
  }

  Widget _buildSearchRow() {
    final palette = context.omniPalette;
    return Padding(
      key: const ValueKey('conversation-model-selector-search'),
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 8),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: palette.surfaceSecondary,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: palette.borderSubtle),
        ),
        child: Row(
          children: [
            Icon(Icons.search, size: 18, color: palette.textTertiary),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                controller: _searchController,
                autofocus: false,
                scrollPadding: EdgeInsets.zero,
                textInputAction: TextInputAction.search,
                onSubmitted: (_) => widget.onSearchSubmitted?.call(),
                cursorColor: palette.accentPrimary,
                style: TextStyle(
                  fontSize: 13,
                  color: palette.textPrimary,
                  fontWeight: FontWeight.w500,
                ),
                decoration: InputDecoration(
                  isDense: true,
                  filled: false,
                  fillColor: Colors.transparent,
                  hintText: LegacyTextLocalizer.localize('搜索模型 ID'),
                  hintStyle: TextStyle(
                    fontSize: 13,
                    color: palette.textTertiary,
                    fontWeight: FontWeight.w500,
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
      ),
    );
  }

  Widget _buildProfileHeader(ModelProviderProfileSummary profile) {
    final expanded = _isExpanded(profile.id);
    final models = _filteredModels(profile.id);
    final isSelectedProvider =
        widget.currentSelection?.providerProfileId == profile.id;
    final palette = context.omniPalette;
    return OmniMenuItem(
      onTap: !widget.allowProfileCollapse || _hasSearchQuery
          ? null
          : () {
              setState(() {
                if (expanded) {
                  _expandedProfileIds.remove(profile.id);
                } else {
                  _expandedProfileIds.add(profile.id);
                }
              });
            },
      selected: isSelectedProvider,
      child: Row(
        children: [
          Expanded(
            child: Text(
              profile.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(color: palette.textSecondary),
            ),
          ),
          Text(
            widget.loadLiveProviders &&
                    (profile.needsPresetCredentials ||
                        _failedProviders.contains(profile.id) ||
                        _loadingProviders.contains(profile.id))
                ? '—'
                : '${models.length}',
            style: TextStyle(fontSize: 13, color: palette.textTertiary),
          ),
          if (isSelectedProvider) ...[
            const SizedBox(width: 8),
            Icon(Icons.check_circle_rounded, color: palette.accentPrimary),
          ],
          if (widget.allowProfileCollapse) ...[
            const SizedBox(width: 8),
            Icon(
              _hasSearchQuery
                  ? Icons.unfold_more_rounded
                  : expanded
                  ? Icons.expand_less_rounded
                  : Icons.expand_more_rounded,
              color: palette.textTertiary,
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildModelRow({
    ModelProviderProfileSummary? profile,
    required ProviderModelOption model,
  }) {
    final displayName = model.displayName.trim();
    final selected = widget.options != null
        ? widget.selectedValue == model.id
        : widget.currentSelection?.providerProfileId == profile?.id &&
              widget.currentSelection?.modelId == model.id;
    final rowKeyPrefix = widget.modelRowKeyPrefix;
    return KeyedSubtree(
      key: selected ? _selectedModelRowKey : null,
      child: buildConversationModelIdTooltip(
        modelId: model.id,
        child: OmniMenuItem(
          key: rowKeyPrefix == null
              ? null
              : ValueKey('$rowKeyPrefix-${model.id}'),
          selected: selected,
          enabled: widget.options == null || widget.onSelectValue != null,
          onTap: widget.options != null && widget.onSelectValue == null
              ? null
              : () {
                  if (widget.options != null) {
                    widget.onSelectValue?.call(model.id);
                    return;
                  }
                  final selection = ConversationModelSelection(
                    providerProfileId: profile!.id,
                    modelId: model.id,
                  );
                  final onSelect = widget.onSelect;
                  if (onSelect != null) {
                    onSelect(selection);
                  } else {
                    Navigator.of(context).pop(selection);
                  }
                },
          label: displayName.isEmpty ? model.id : displayName,
          leading: widget.showVendorIcons
              ? ProviderVendorIcon(
                  vendor: ModelVendorCatalog.resolve(
                    model.id,
                    ownedBy: model.ownedBy,
                    providerId: model.modelsDevProviderId,
                  ),
                  size: OmniMenuStyle.iconSize,
                )
              : null,
          trailing: selected
              ? Icon(
                  Icons.check_rounded,
                  color: context.omniPalette.accentPrimary,
                )
              : null,
        ),
      ),
    );
  }

  Widget _buildMutedMessage(String? label) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 4, 12, 8),
      child: Text(
        label ?? LegacyTextLocalizer.localize('该 Provider 暂无可选模型'),
        style: TextStyle(fontSize: 12, color: context.omniPalette.textTertiary),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(220.0, widget.maxHeight)
            .toDouble();
    final configuredProfiles = _profiles
        .where(
          (profile) => profile.configured && !profile.needsPresetCredentials,
        )
        .toList();
    final visibleProfiles = _visibleProfiles;
    final query = _searchController.text.trim().toLowerCase();
    final choices = widget.options
        ?.where(
          (item) =>
              !widget.showSearchField ||
              query.isEmpty ||
              item.id.toLowerCase().contains(query) ||
              item.displayName.toLowerCase().contains(query),
        )
        .toList();
    return SizedBox(
      width: widget.width,
      child: OmniMenuSurface(
        width: widget.width,
        child: Material(
          color: Colors.transparent,
          child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (widget.header != null) widget.header!,
                if (widget.showSearchField) _buildSearchRow(),
                if (_loadingProfiles) const LinearProgressIndicator(),
                if (_profilesFailed)
                  OmniMenuItem(
                    onTap: _loadProfiles,
                    leading: const Icon(Icons.refresh_rounded),
                    label: '服务商加载失败，重试',
                  ),
                if (choices != null)
                  Flexible(
                    child: ListView(
                      shrinkWrap: true,
                      controller: _listScrollController,
                      padding: EdgeInsets.zero,
                      children: [
                        if (choices.isEmpty)
                          _buildMutedMessage(
                            widget.emptyMatchesLabel ?? '没有匹配的选项',
                          ),
                        for (final model in choices)
                          _buildModelRow(model: model),
                      ],
                    ),
                  )
                else if (configuredProfiles.isEmpty &&
                    !_loadingProfiles &&
                    !_profilesFailed)
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      widget.emptyProvidersLabel ??
                          LegacyTextLocalizer.localize('请先在模型提供商页配置 Provider'),
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 12,
                        color: palette.textTertiary,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  )
                else if (visibleProfiles.isEmpty)
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      widget.emptyMatchesLabel ??
                          LegacyTextLocalizer.localize('没有匹配的模型'),
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 12,
                        color: palette.textTertiary,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  )
                else
                  Flexible(
                    child: Scrollbar(
                      controller: _listScrollController,
                      child: ListView.builder(
                        controller: _listScrollController,
                        padding: EdgeInsets.zero,
                        itemCount: visibleProfiles.length,
                        itemBuilder: (context, index) {
                          final profile = visibleProfiles[index];
                          final expanded = _isExpanded(profile.id);
                          final models = _filteredModels(profile.id);
                          return Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              if (widget.showProfileHeaders)
                                _buildProfileHeader(profile),
                              if (_loadingProviders.contains(profile.id))
                                const LinearProgressIndicator(),
                              if (_failedProviders.contains(profile.id))
                                OmniMenuItem(
                                  onTap: () => _loadProvider(profile),
                                  leading: const Icon(Icons.refresh_rounded),
                                  label: '模型加载失败，重试',
                                ),
                              if (_failedProviders.contains(profile.id))
                                _buildMutedMessage(_providerErrors[profile.id]),
                              if (expanded)
                                if (models.isEmpty &&
                                    !_failedProviders.contains(profile.id) &&
                                    !_loadingProviders.contains(profile.id) &&
                                    !(widget.loadLiveProviders &&
                                        profile.needsPresetCredentials))
                                  _buildMutedMessage(widget.emptyModelsLabel)
                                else if (models.isNotEmpty)
                                  Column(
                                    children: models
                                        .map(
                                          (item) => _buildModelRow(
                                            profile: profile,
                                            model: item,
                                          ),
                                        )
                                        .toList(),
                                  ),
                              if (index != visibleProfiles.length - 1)
                                const SizedBox(height: 6),
                            ],
                          );
                        },
                      ),
                    ),
                  ),
                if (widget.footer != null) widget.footer!,
              ],
            ),
          ),
        ),
      ),
    );
  }
}
