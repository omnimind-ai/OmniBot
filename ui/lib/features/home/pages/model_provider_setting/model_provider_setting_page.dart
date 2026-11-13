import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:ui/widgets/navigation/page_route.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';
import 'package:ui/widgets/settings_ui.dart';

import 'model_provider_detail_page.dart';
import 'provider_setting_models.dart';

export 'model_provider_detail_page.dart';
export 'provider_setting_models.dart';

class ModelProviderSettingPage extends StatefulWidget {
  const ModelProviderSettingPage({super.key});

  @override
  State<ModelProviderSettingPage> createState() =>
      _ModelProviderSettingPageState();
}

class _ModelProviderSettingPageState extends State<ModelProviderSettingPage>
    with FlClashPageLoadMixin {
  static List<ModelProviderProfileSummary> _byokProfiles(
    List<ModelProviderProfileSummary> profiles,
  ) => profiles
      .where((profile) => profile.sourceType != 'omnibot_official')
      .toList(growable: false);

  final TextEditingController _searchController = TextEditingController();

  bool _isLoading = true;
  List<ModelProviderProfileSummary> _allProfiles = const [];
  final Map<String, List<String>> _manualModelIdsByProfileId = {};
  final Map<String, Set<String>> _hiddenModelIdsByProfileId = {};
  final Map<String, List<ProviderModelOption>> _remoteModelsByProfileId = {};

  bool get _isEnglish => Localizations.localeOf(context).languageCode != 'zh';
  String _text(String zh, String en) => _isEnglish ? en : zh;

  @override
  void initState() {
    super.initState();
    _searchController.addListener(_onSearchChanged);
    _loadData();
  }

  @override
  void dispose() {
    _searchController.removeListener(_onSearchChanged);
    _searchController.dispose();
    super.dispose();
  }

  void _onSearchChanged() {
    if (mounted) setState(() {});
  }

  Future<void> _loadData() =>
      runPageLoad(#providerListLoad, _readData, revalidate: true);

  Future<void> _readData() async {
    setState(() => _isLoading = true);
    try {
      final payload = await ModelProviderConfigService.listProfiles();
      if (!await waitForPageReady() ||
          !mounted ||
          !pageLoadIsCurrent(#providerListLoad)) {
        return;
      }
      final profiles = _byokProfiles(payload.profiles);
      final manualMap = <String, List<String>>{};
      final hiddenMap = <String, Set<String>>{};
      final remoteMap = <String, List<ProviderModelOption>>{};

      for (final profile in profiles) {
        final manualIds = await ModelProviderConfigService.getManualModelIds(
          profileId: profile.id,
        );
        final hiddenIds =
            await ModelProviderConfigService.getHiddenChatModelIds(
              profileId: profile.id,
            );
        manualMap[profile.id] = manualIds;
        hiddenMap[profile.id] = hiddenIds.toSet();
        remoteMap[profile.id] =
            _remoteModelsByProfileId[profile.id] ??
            ModelProviderConfigService.getCachedDiscoveredModelsForProfile(
              profile,
            );
      }

      if (!await waitForPageReady() ||
          !mounted ||
          !pageLoadIsCurrent(#providerListLoad)) {
        return;
      }
      setState(() {
        _allProfiles = profiles;
        _manualModelIdsByProfileId
          ..clear()
          ..addAll(manualMap);
        _hiddenModelIdsByProfileId
          ..clear()
          ..addAll(hiddenMap);
        _remoteModelsByProfileId
          ..clear()
          ..addAll(remoteMap);
      });
    } catch (_) {
      if (!await waitForPageReady() ||
          !mounted ||
          !pageLoadIsCurrent(#providerListLoad)) {
        return;
      }
      showToast(context.l10n.modelProviderLoadFailed, type: ToastType.error);
    } finally {
      if (await waitForPageReady() &&
          mounted &&
          pageLoadIsCurrent(#providerListLoad)) {
        setState(() => _isLoading = false);
      }
    }
  }

  /// If the only profile in storage is the untouched initial placeholder
  /// (`profile-1` / `Provider 1` with no baseUrl, key, headers, or models),
  /// treat the list as empty and reuse that placeholder ID when creating the first provider.
  String? get _reusablePlaceholderId {
    if (_allProfiles.length == 1) {
      final single = _allProfiles.single;
      final manuals = _manualModelIdsByProfileId[single.id] ?? const [];
      if (isUntouchedPlaceholderProfile(single, manualModelIds: manuals)) {
        return single.id;
      }
    }
    return null;
  }

  List<ModelProviderProfileSummary> get _displayProfiles {
    if (_reusablePlaceholderId != null) {
      return const [];
    }
    return _allProfiles;
  }

  List<ModelProviderProfileSummary> get _filteredProfiles {
    final query = _searchController.text.trim().toLowerCase();
    if (query.isEmpty) return _displayProfiles;
    return _displayProfiles.where((profile) {
      final preset = resolvePresetForValues(
        sourceType: profile.sourceType,
        protocolType: profile.protocolType,
        wireApi: profile.wireApi,
        baseUrl: profile.baseUrl,
      );
      return profile.name.toLowerCase().contains(query) ||
          profile.baseUrl.toLowerCase().contains(query) ||
          preset.label.toLowerCase().contains(query) ||
          preset.wireProtocol.label.toLowerCase().contains(query);
    }).toList();
  }

  int _totalModelCountFor(String profileId) {
    final manualIds = _manualModelIdsByProfileId[profileId] ?? const [];
    final remoteModels = _remoteModelsByProfileId[profileId] ?? const [];
    final seen = <String>{...manualIds};
    for (final m in remoteModels) {
      seen.add(m.id);
    }
    return seen.length;
  }

  int _visibleChatModelCountFor(String profileId) {
    final manualIds = _manualModelIdsByProfileId[profileId] ?? const [];
    final remoteModels = _remoteModelsByProfileId[profileId] ?? const [];
    final hiddenIds = _hiddenModelIdsByProfileId[profileId] ?? const <String>{};
    final seen = <String>{...manualIds};
    for (final m in remoteModels) {
      seen.add(m.id);
    }
    return seen.where((id) => !hiddenIds.contains(id)).length;
  }

  Future<void> _openProviderDetail(
    ModelProviderProfileSummary profile, {
    ProviderDetailTab? initialTab,
  }) async {
    try {
      await ModelProviderConfigService.setEditingProfile(profile.id);
    } catch (_) {
      // Non-fatal: detail page still works with the selected profile.
    }
    if (!mounted) return;
    await Navigator.of(context).push<void>(
      OmniPageRoute<void>(
        builder: (_) => ModelProviderDetailPage(
          initialProfile: profile,
          isNew: false,
          initialTab: initialTab,
          initialRemoteModels:
              _remoteModelsByProfileId[profile.id] ??
              const <ProviderModelOption>[],
          existingProfileNames: _displayProfiles
              .where((p) => p.id != profile.id)
              .map((p) => p.name)
              .toList(),
          onProfileChanged: _handleProfileUpserted,
          onModelsChanged: _handleModelsUpdated,
          onProfileDeleted: _handleProfileRemoved,
        ),
      ),
    );
    if (mounted) {
      await _loadData();
    }
  }

  Future<void> _openAddProvider({String? presetValue}) async {
    await Navigator.of(context).push<void>(
      OmniPageRoute<void>(
        builder: (_) => ModelProviderDetailPage(
          isNew: true,
          reusePlaceholderProfileId: _reusablePlaceholderId,
          initialPresetValue: presetValue,
          initialTab: ProviderDetailTab.connection,
          existingProfileNames: _displayProfiles.map((p) => p.name).toList(),
          onProfileChanged: _handleProfileUpserted,
          onModelsChanged: _handleModelsUpdated,
          onProfileDeleted: _handleProfileRemoved,
        ),
      ),
    );
    if (mounted) {
      await _loadData();
    }
  }

  void _handleProfileUpserted(ModelProviderProfileSummary saved) {
    if (!mounted) return;
    setState(() {
      final index = _allProfiles.indexWhere((p) => p.id == saved.id);
      if (index >= 0) {
        final next = List<ModelProviderProfileSummary>.from(_allProfiles);
        next[index] = saved;
        _allProfiles = next;
      } else {
        _allProfiles = [..._allProfiles, saved];
      }
    });
  }

  void _handleModelsUpdated(
    String profileId,
    List<ProviderModelOption> remoteModels,
    List<String> manualModelIds,
    Set<String> hiddenChatModelIds,
  ) {
    if (!mounted) return;
    setState(() {
      _remoteModelsByProfileId[profileId] = List<ProviderModelOption>.from(
        remoteModels,
      );
      _manualModelIdsByProfileId[profileId] = List<String>.from(manualModelIds);
      _hiddenModelIdsByProfileId[profileId] = Set<String>.from(
        hiddenChatModelIds,
      );
    });
  }

  void _handleProfileRemoved(String profileId) {
    if (!mounted) return;
    setState(() {
      _allProfiles = _allProfiles.where((p) => p.id != profileId).toList();
      _remoteModelsByProfileId.remove(profileId);
      _manualModelIdsByProfileId.remove(profileId);
      _hiddenModelIdsByProfileId.remove(profileId);
    });
  }

  Future<void> _promptRenameProfile(ModelProviderProfileSummary profile) async {
    if (profile.readOnly) return;
    final nextName = (await AppDialog.input(
      context,
      title: _text('重命名服务商', 'Rename Provider'),
      initialValue: profile.name,
      hintText: context.l10n.modelProviderNameHint,
      confirmText: _text('保存', 'Save'),
      cancelText: context.trLegacy('取消'),
    ))?.trim();
    if (nextName == null ||
        nextName.isEmpty ||
        nextName == profile.name ||
        !mounted) {
      return;
    }
    try {
      final saved = await ModelProviderConfigService.saveProfile(
        id: profile.id,
        name: nextName,
        baseUrl: profile.baseUrl,
        sourceType: profile.sourceType,
        protocolType: profile.protocolType,
        wireApi: profile.wireApi,
      );
      if (!mounted) return;
      _handleProfileUpserted(saved);
      showToast(_text('已重命名', 'Renamed'), type: ToastType.success);
    } catch (_) {
      if (!mounted) return;
      showToast(
        _text('重命名失败', 'Failed to rename provider'),
        type: ToastType.error,
      );
    }
  }

  Future<void> _confirmDeleteProfile(
    ModelProviderProfileSummary profile,
  ) async {
    if (profile.readOnly) return;
    final deleted = await showDeleteProviderDialog(
      context: context,
      profile: profile,
      totalModelCount: _totalModelCountFor(profile.id),
    );
    if (deleted && mounted) {
      _handleProfileRemoved(profile.id);
    }
  }

  Future<void> _openRowMoreMenu(
    BuildContext anchorContext,
    ModelProviderProfileSummary profile,
  ) async {
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
      width: 184,
      items: [
        buildProviderActionMenuItem(
          value: 'models',
          icon: LucideIcons.boxes,
          label: _text('管理模型', 'Manage Models'),
        ),
        buildProviderActionMenuItem(
          value: 'connection',
          icon: LucideIcons.plug,
          label: _text('连接设置', 'Connection Settings'),
        ),
        if (!profile.readOnly)
          buildProviderActionMenuItem(
            value: 'rename',
            icon: LucideIcons.pencil,
            label: _text('重命名', 'Rename'),
          ),
        if (!profile.readOnly)
          buildProviderActionMenuItem(
            value: 'delete',
            destructive: true,
            icon: LucideIcons.trash2,
            label: _text('删除服务商', 'Delete Provider'),
          ),
      ],
    );
    if (!mounted || action == null) return;
    switch (action) {
      case 'models':
        await _openProviderDetail(
          profile,
          initialTab: ProviderDetailTab.models,
        );
      case 'connection':
        await _openProviderDetail(
          profile,
          initialTab: ProviderDetailTab.connection,
        );
      case 'rename':
        await _promptRenameProfile(profile);
      case 'delete':
        await _confirmDeleteProfile(profile);
    }
  }

  Widget _buildEmptyState(BuildContext context) {
    final palette = context.omniPalette;
    return OmniSettingsCard(
      key: const Key('provider-empty-state'),
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 28),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Container(
            width: 52,
            height: 52,
            decoration: BoxDecoration(
              color: palette.accentPrimary.withValues(alpha: 0.1),
              shape: BoxShape.circle,
            ),
            child: Icon(
              LucideIcons.server,
              size: 24,
              color: palette.accentPrimary,
            ),
          ),
          const SizedBox(height: 14),
          Text(
            _text('尚未添加模型服务商', 'No Model Providers Configured'),
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: palette.textPrimary,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            _text(
              '添加服务商并填写连接信息后，即可获取模型并选择哪些出现在聊天模型选择器中。',
              'Add a provider and fill in its connection details to fetch models and choose which ones appear in chat.',
            ),
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 13,
              color: palette.textSecondary,
              height: 1.45,
            ),
          ),
          const SizedBox(height: 18),
          FilledButton.icon(
            key: const Key('provider-empty-add-button'),
            onPressed: () => unawaited(_openAddProvider()),
            style: FilledButton.styleFrom(
              backgroundColor: palette.accentPrimary,
              foregroundColor: Colors.white,
              minimumSize: const Size(180, 44),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            icon: const Icon(LucideIcons.plus, size: 17),
            label: Text(
              _text('添加服务商', 'Add Provider'),
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
            ),
          ),
          const SizedBox(height: 22),
          Divider(height: 1, thickness: 0.6, color: palette.borderSubtle),
          const SizedBox(height: 14),
          Align(
            alignment: Alignment.centerLeft,
            child: Text(
              _text('或直接选择常用服务商快速添加', 'Or start with a popular provider'),
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w500,
                color: palette.textTertiary,
              ),
            ),
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final preset in kProviderPresets)
                ActionChip(
                  key: Key('provider-empty-preset-${preset.value}'),
                  avatar: ProviderVendorIcon(
                    vendor: preset.vendorKey != null
                        ? resolveVendorForProfile(
                            ModelProviderProfileSummary(
                              id: '',
                              name: preset.label,
                              baseUrl: preset.baseUrl,
                              apiKey: '',
                              customHeaders: const {},
                              sourceType: preset.sourceType,
                              readOnly: false,
                              ready: false,
                              statusText: '',
                              configured: false,
                              protocolType: preset.protocolType,
                              wireApi: preset.wireApi,
                            ),
                          )
                        : null,
                    size: 14,
                  ),
                  label: Text(preset.displayLabel(english: _isEnglish)),
                  onPressed: () =>
                      unawaited(_openAddProvider(presetValue: preset.value)),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildProviderRow(
    BuildContext context,
    ModelProviderProfileSummary profile,
  ) {
    final palette = context.omniPalette;
    final totalModels = _totalModelCountFor(profile.id);
    final visibleModels = _visibleChatModelCountFor(profile.id);
    final vendor = resolveVendorForProfile(profile);
    final missingItem = describeMissingConfigItem(
      sourceType: profile.sourceType,
      baseUrl: profile.baseUrl,
      hasApiKey: profile.hasApiKey || profile.apiKey.trim().isNotEmpty,
      hasCustomHeaders:
          profile.hasCustomHeaders || profile.customHeaders.isNotEmpty,
      chatGptReady: profile.ready,
      english: _isEnglish,
    );
    final subtitle = buildProviderRowSubtitle(
      profile,
      totalModelCount: totalModels,
      visibleChatModelCount: visibleModels,
      english: _isEnglish,
    );

    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: ValueKey<String>('provider-list-item-${profile.id}'),
        onTap: () => unawaited(_openProviderDetail(profile)),
        splashColor: palette.accentPrimary.withValues(alpha: 0.06),
        highlightColor: palette.accentPrimary.withValues(alpha: 0.03),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 10, 14),
          child: Row(
            children: [
              Container(
                width: 38,
                height: 38,
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(11),
                  border: Border.all(
                    color: palette.borderSubtle,
                    width: 0.65,
                  ),
                ),
                child: Center(
                  child: ProviderVendorIcon(vendor: vendor, size: 20),
                ),
              ),
              const SizedBox(width: 13),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            profile.name,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 15.5,
                              fontWeight: FontWeight.w600,
                              color: palette.textPrimary,
                              height: 1.3,
                            ),
                          ),
                        ),
                        if (missingItem != null) ...[
                          const SizedBox(width: 8),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color: const Color(
                                0xFFD97706,
                              ).withValues(alpha: 0.12),
                              borderRadius: BorderRadius.circular(5),
                            ),
                            child: Text(
                              missingItem,
                              style: const TextStyle(
                                fontSize: 10.5,
                                fontWeight: FontWeight.w600,
                                color: Color(0xFFD97706),
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 3),
                    Text(
                      subtitle,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 12.5,
                        color: palette.textSecondary,
                        height: 1.35,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 6),
              OmniCircleButton.popup(
                key: Key('provider-list-more-${profile.id}'),
                icon: LucideIcons.ellipsis,
                size: 32,
                tooltip: _text('更多操作', 'More actions'),
                onOpen: (anchorContext) =>
                    unawaited(_openRowMoreMenu(anchorContext, profile)),
              ),
              const SizedBox(width: 2),
              Icon(
                LucideIcons.chevronRight,
                size: 18,
                color: palette.textTertiary,
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
    final displayProfiles = _displayProfiles;
    final filteredProfiles = _filteredProfiles;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.settingsModelProviderTitle,
        primary: true,
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 16),
            child: Center(
              child: OmniCircleButton(
                key: const Key('provider-list-add-button'),
                icon: LucideIcons.plus,
                tooltip: _text('添加服务商', 'Add Provider'),
                onPressed: () => unawaited(_openAddProvider()),
              ),
            ),
          ),
        ],
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
                    const EdgeInsets.fromLTRB(18, 10, 18, 28),
                  ),
                  children: [
                    if (displayProfiles.length > 2) ...[
                      OmniSettingsSearchField(
                        key: const Key('provider-list-search-field'),
                        controller: _searchController,
                        hintText: _text(
                          '搜索服务商名称、域名或协议',
                          'Search provider name, domain, or protocol',
                        ),
                      ),
                      const SizedBox(height: 12),
                    ],
                    if (displayProfiles.isEmpty)
                      _buildEmptyState(context)
                    else ...[
                      OmniSettingsGroup(
                        title: _text(
                          '已配置服务商 (${filteredProfiles.length})',
                          'Configured Providers (${filteredProfiles.length})',
                        ),
                        subtitle: _text(
                          '点击服务商管理连接与模型；编辑服务商不会改变当前对话正在使用的模型。',
                          'Tap a provider to manage its connection and models. Editing a provider does not switch the active model in chat.',
                        ),
                        translateTitle: false,
                        dividerIndent: 67,
                        children: filteredProfiles.isEmpty
                            ? [
                                Padding(
                                  padding: const EdgeInsets.symmetric(
                                    vertical: 28,
                                    horizontal: 16,
                                  ),
                                  child: Center(
                                    child: Text(
                                      _text(
                                        '未找到匹配的服务商',
                                        'No matching providers found',
                                      ),
                                      style: TextStyle(
                                        color: palette.textSecondary,
                                        fontSize: 13.5,
                                      ),
                                    ),
                                  ),
                                ),
                              ]
                            : [
                                for (final profile in filteredProfiles)
                                  _buildProviderRow(context, profile),
                              ],
                      ),
                      const SizedBox(height: 16),
                      OmniSettingsGroup(
                        translateTitle: false,
                        children: [
                          OmniSettingsTile(
                            tileKey: const Key('provider-add-row-button'),
                            icon: LucideIcons.plus,
                            iconColor: palette.accentPrimary,
                            title: _text('添加服务商', 'Add Provider'),
                            subtitle: _text(
                              '支持 Gemini、DeepSeek、Kimi、ChatGPT 及自定义 OpenAI / Anthropic 接口',
                              'Supports Gemini, DeepSeek, Kimi, ChatGPT, and custom OpenAI / Anthropic endpoints',
                            ),
                            translateText: false,
                            onTap: () => unawaited(_openAddProvider()),
                          ),
                        ],
                      ),
                    ],
                  ],
                ),
        ),
      ),
    );
  }
}
