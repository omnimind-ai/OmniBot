import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/model_vendor_catalog.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';
import 'package:ui/widgets/settings_ui.dart';

import 'provider_setting_models.dart';

class ProviderModelsTab extends StatefulWidget {
  const ProviderModelsTab({
    super.key,
    required this.scrollController,
    required this.profile,
    required this.readOnly,
    required this.isFetchingModels,
    required this.canFetchModels,
    required this.missingConfigItem,
    required this.fetchErrorDiagnosis,
    required this.searchController,
    required this.allModelItems,
    required this.filteredModelItems,
    required this.filteredModelGroups,
    required this.hiddenChatModelIds,
    required this.expandedModelGroups,
    required this.modelTestStates,
    required this.onFetchModels,
    required this.onAddManualModel,
    required this.onSwitchToConnectionTab,
    required this.onSetModelVisibleInChat,
    required this.onBatchSetVisibleInChat,
    required this.onDeleteManualModels,
    required this.onToggleModelGroup,
    required this.onToggleAllModelGroups,
    required this.onTestModel,
  });

  final ScrollController scrollController;
  final ModelProviderProfileSummary? profile;
  final bool readOnly;
  final bool isFetchingModels;
  final bool canFetchModels;
  final String? missingConfigItem;
  final ProviderErrorDiagnosis? fetchErrorDiagnosis;
  final TextEditingController searchController;
  final List<ProviderModelItem> allModelItems;
  final List<ProviderModelItem> filteredModelItems;
  final List<MapEntry<String, List<ProviderModelItem>>> filteredModelGroups;
  final Set<String> hiddenChatModelIds;
  final Map<String, bool> expandedModelGroups;
  final Map<String, ModelTestState> modelTestStates;
  final VoidCallback onFetchModels;
  final VoidCallback onAddManualModel;
  final VoidCallback onSwitchToConnectionTab;
  final Future<bool> Function(ProviderModelOption model, bool visible)
  onSetModelVisibleInChat;
  final Future<void> Function(Iterable<String> modelIds, bool visible)
  onBatchSetVisibleInChat;
  final Future<void> Function(Iterable<String> manualModelIds)
  onDeleteManualModels;
  final ValueChanged<String> onToggleModelGroup;
  final VoidCallback onToggleAllModelGroups;
  final Future<ModelTestState> Function(ProviderModelOption model) onTestModel;

  @override
  State<ProviderModelsTab> createState() => _ProviderModelsTabState();
}

class _ProviderModelsTabState extends State<ProviderModelsTab> {
  static const Duration _modelGroupToggleDuration = Duration(milliseconds: 240);

  bool _selectionMode = false;
  Set<String> _selectedModelIds = <String>{};
  bool _errorTechDetailExpanded = false;

  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';

  String _text(String zh, String en) => _isEnglish ? en : zh;

  @override
  void didUpdateWidget(covariant ProviderModelsTab oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.profile?.id != widget.profile?.id) {
      _selectionMode = false;
      _selectedModelIds = <String>{};
      _errorTechDetailExpanded = false;
    }
    if (oldWidget.fetchErrorDiagnosis != widget.fetchErrorDiagnosis) {
      _errorTechDetailExpanded = false;
    }
  }

  bool _isModelVisibleInChat(String modelId) =>
      !widget.hiddenChatModelIds.contains(modelId);

  String _groupExpansionKey(String groupName) {
    final profileKey = (widget.profile?.id.trim().isNotEmpty ?? false)
        ? widget.profile!.id.trim()
        : 'default';
    return '$profileKey::$groupName';
  }

  bool _isGroupExpanded(String groupName) =>
      widget.expandedModelGroups[_groupExpansionKey(groupName)] ?? true;

  bool get _areAllGroupsCollapsed =>
      widget.filteredModelGroups.isNotEmpty &&
      widget.filteredModelGroups.every((g) => !_isGroupExpanded(g.key));

  void _enterSelectionMode([String? initialModelId]) {
    setState(() {
      _selectionMode = true;
      _selectedModelIds = initialModelId == null
          ? <String>{}
          : <String>{initialModelId};
    });
  }

  void _exitSelectionMode() {
    setState(() {
      _selectionMode = false;
      _selectedModelIds = <String>{};
    });
  }

  void _toggleSelectedModel(String modelId) {
    setState(() {
      final next = Set<String>.from(_selectedModelIds);
      if (!next.remove(modelId)) {
        next.add(modelId);
      }
      _selectedModelIds = next;
    });
  }

  void _toggleSelectAllVisible() {
    final visibleIds = widget.filteredModelItems.map((m) => m.id).toSet();
    final allSelected =
        visibleIds.isNotEmpty && _selectedModelIds.containsAll(visibleIds);
    setState(() {
      if (allSelected) {
        _selectedModelIds = _selectedModelIds.difference(visibleIds);
      } else {
        _selectedModelIds = _selectedModelIds.union(visibleIds);
      }
    });
  }

  Future<void> _openMoreActionsMenu(BuildContext anchorContext) async {
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
    final hasModels = widget.allModelItems.isNotEmpty;
    final hasMultipleGroups = widget.filteredModelGroups.length > 1;

    final action = await showOmniMenu<String>(
      context: context,
      sourceContext: anchorContext,
      anchor: anchorRect,
      width: 210,
      items: [
        buildProviderActionMenuItem(
          value: 'batch',
          enabled: hasModels,
          icon: LucideIcons.checkCheck,
          label: _text('批量管理', 'Batch Manage'),
        ),
        buildProviderActionMenuItem(
          value: 'show_all',
          enabled: hasModels && widget.hiddenChatModelIds.isNotEmpty,
          icon: LucideIcons.eye,
          label: _text('全部在聊天中显示', 'Show All in Chat'),
        ),
        buildProviderActionMenuItem(
          value: 'hide_all',
          enabled:
              hasModels &&
              widget.allModelItems.any((m) => _isModelVisibleInChat(m.id)),
          icon: LucideIcons.eyeOff,
          label: _text('全部从聊天中隐藏', 'Hide All from Chat'),
        ),
        if (hasMultipleGroups)
          buildProviderActionMenuItem(
            value: 'toggle_groups',
            icon: _areAllGroupsCollapsed
                ? LucideIcons.listChevronsUpDown
                : LucideIcons.listCollapse,
            label: _areAllGroupsCollapsed
                ? _text('展开全部分组', 'Expand All Groups')
                : _text('折叠全部分组', 'Collapse All Groups'),
          ),
      ],
    );
    if (!mounted || action == null) return;
    switch (action) {
      case 'batch':
        _enterSelectionMode();
      case 'show_all':
        await widget.onBatchSetVisibleInChat(
          widget.allModelItems.map((m) => m.id),
          true,
        );
      case 'hide_all':
        await widget.onBatchSetVisibleInChat(
          widget.allModelItems.map((m) => m.id),
          false,
        );
      case 'toggle_groups':
        widget.onToggleAllModelGroups();
    }
  }

  Future<void> _confirmAndDeleteManualModels(List<String> manualIds) async {
    if (manualIds.isEmpty) return;
    final confirmed = await showOmniGlassCardSheet<bool>(
      context: context,
      useRootNavigator: false,
      builder: (sheetContext) {
        return OmniGlassAlertDialog(
          title: Text(
            manualIds.length == 1
                ? _text('删除手动模型', 'Delete Manual Model')
                : _text(
                    '删除 ${manualIds.length} 个手动模型',
                    'Delete ${manualIds.length} Manual Models',
                  ),
          ),
          content: Text(
            manualIds.length == 1
                ? _text(
                    '确定要删除手动添加的模型“${manualIds.first}”吗？如果只是不想在聊天模型选择器中看到它，可改为关闭“在聊天中显示”。',
                    'Delete manually added model "${manualIds.first}"? If you only want to hide it from the chat model selector, turn off "Show in Chat" instead.',
                  )
                : _text(
                    '确定要删除选中的 ${manualIds.length} 个手动模型吗？远端获取的模型不会被删除。',
                    'Delete ${manualIds.length} selected manual models? Fetched remote models will not be deleted.',
                  ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(sheetContext).pop(false),
              child: Text(context.trLegacy('取消')),
            ),
            TextButton(
              onPressed: () => Navigator.of(sheetContext).pop(true),
              style: TextButton.styleFrom(foregroundColor: AppColors.alertRed),
              child: Text(context.l10n.skillDelete),
            ),
          ],
        );
      },
    );
    if (confirmed == true && mounted) {
      await widget.onDeleteManualModels(manualIds);
      if (mounted && _selectionMode) {
        _exitSelectionMode();
      }
    }
  }

  Future<void> _openModelDetailSheet(ProviderModelItem item) async {
    await showOmniGlassCardSheet<void>(
      context: context,
      useRootNavigator: false,
      builder: (sheetContext) {
        return _ModelDetailSheet(
          item: item,
          initialVisibleInChat: _isModelVisibleInChat(item.id),
          initialTestState:
              widget.modelTestStates[item.id] ?? const ModelTestState(),
          readOnly: widget.readOnly,
          onToggleVisibleInChat: (visible) =>
              widget.onSetModelVisibleInChat(item.model, visible),
          onTestModel: () => widget.onTestModel(item.model),
          onDeleteManualModel: item.source == ProviderModelSource.manual
              ? () async {
                  Navigator.of(sheetContext).pop();
                  await _confirmAndDeleteManualModels([item.id]);
                }
              : null,
        );
      },
    );
  }

  Widget _buildTopToolbar(BuildContext context) {
    final palette = context.omniPalette;
    final totalCount = widget.allModelItems.length;
    final visibleInChatCount = widget.allModelItems
        .where((item) => _isModelVisibleInChat(item.id))
        .length;
    final hasQuery = widget.searchController.text.trim().isNotEmpty;
    final summaryText = hasQuery
        ? _text(
            '匹配 ${widget.filteredModelItems.length} / $totalCount 个模型',
            'Matching ${widget.filteredModelItems.length} of $totalCount models',
          )
        : totalCount == 0
        ? _text('暂未获取模型', 'No models yet')
        : _text(
            '共 $totalCount 个模型 · $visibleInChatCount 个在聊天中显示',
            '$totalCount models · $visibleInChatCount shown in chat',
          );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        OmniSettingsSearchField(
          key: const Key('provider-model-search-field'),
          controller: widget.searchController,
          hintText: _text('搜索模型名称或 ID', 'Search model name or ID'),
        ),
        const SizedBox(height: 10),
        if (_selectionMode)
          _buildSelectionHeader(context)
        else
          Wrap(
            alignment: WrapAlignment.spaceBetween,
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: 8,
            runSpacing: 8,
            children: [
              Text(
                summaryText,
                style: TextStyle(
                  color: palette.textSecondary,
                  fontSize: 12.5,
                  fontWeight: FontWeight.w500,
                ),
              ),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  _LabeledActionChipButton(
                    buttonKey: const Key('provider-fetch-models-button'),
                    icon: LucideIcons.arrowBigDown,
                    label: widget.isFetchingModels
                        ? _text('获取中…', 'Fetching…')
                        : _text('获取模型', 'Fetch Models'),
                    primary: true,
                    loading: widget.isFetchingModels,
                    onPressed: widget.canFetchModels && !widget.isFetchingModels
                        ? widget.onFetchModels
                        : null,
                  ),
                  _LabeledActionChipButton(
                    buttonKey: const Key('provider-add-manual-model-button'),
                    icon: LucideIcons.plus,
                    label: _text('手动添加', 'Add Manually'),
                    onPressed: widget.readOnly
                        ? null
                        : widget.onAddManualModel,
                  ),
                  if (widget.filteredModelGroups.length > 1)
                    Tooltip(
                      message: _areAllGroupsCollapsed
                          ? _text('展开全部分组', 'Expand all groups')
                          : _text('折叠全部分组', 'Collapse all groups'),
                      child: InkWell(
                        key: const ValueKey(
                          'provider-model-group-toggle-button',
                        ),
                        borderRadius: BorderRadius.circular(10),
                        onTap: widget.onToggleAllModelGroups,
                        child: Container(
                          width: 34,
                          height: 34,
                          decoration: BoxDecoration(
                            color: palette.surfacePrimary,
                            borderRadius: BorderRadius.circular(10),
                            border: Border.all(
                              color: palette.borderSubtle,
                              width: 0.75,
                            ),
                          ),
                          child: Icon(
                            _areAllGroupsCollapsed
                                ? LucideIcons.listChevronsUpDown
                                : LucideIcons.listCollapse,
                            size: 16,
                            color: palette.textSecondary,
                          ),
                        ),
                      ),
                    ),
                  OmniCircleButton.popup(
                    key: const Key('provider-models-more-button'),
                    icon: LucideIcons.ellipsis,
                    size: 34,
                    tooltip: _text('更多模型操作', 'More model actions'),
                    onOpen: (anchorContext) =>
                        unawaited(_openMoreActionsMenu(anchorContext)),
                  ),
                ],
              ),
            ],
          ),
      ],
    );
  }

  Widget _buildSelectionHeader(BuildContext context) {
    final palette = context.omniPalette;
    final visibleIds = widget.filteredModelItems.map((m) => m.id).toSet();
    final allSelected =
        visibleIds.isNotEmpty && _selectedModelIds.containsAll(visibleIds);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle, width: 0.75),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              _text(
                '已选择 ${_selectedModelIds.length} 个模型',
                '${_selectedModelIds.length} models selected',
              ),
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: palette.textPrimary,
              ),
            ),
          ),
          TextButton(
            onPressed: visibleIds.isEmpty ? null : _toggleSelectAllVisible,
            child: Text(
              allSelected
                  ? _text('取消全选', 'Deselect All')
                  : _text('全选', 'Select All'),
            ),
          ),
          TextButton(
            onPressed: _exitSelectionMode,
            child: Text(_text('完成', 'Done')),
          ),
        ],
      ),
    );
  }

  Widget _buildFetchErrorBanner(
    BuildContext context,
    ProviderErrorDiagnosis diagnosis,
  ) {
    final palette = context.omniPalette;
    return Container(
      key: const Key('provider-model-fetch-error-banner'),
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.alertRed.withValues(
          alpha: context.isDarkTheme ? 0.14 : 0.08,
        ),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: AppColors.alertRed.withValues(
            alpha: context.isDarkTheme ? 0.35 : 0.25,
          ),
          width: 0.75,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Padding(
                padding: EdgeInsets.only(top: 2),
                child: Icon(
                  LucideIcons.circleAlert,
                  size: 16,
                  color: AppColors.alertRed,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      diagnosis.categoryLabel,
                      style: const TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        color: AppColors.alertRed,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      diagnosis.summary,
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: palette.textPrimary,
                        height: 1.35,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      diagnosis.hint,
                      style: TextStyle(
                        fontSize: 12,
                        color: palette.textSecondary,
                        height: 1.4,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          if (diagnosis.technicalDetail != null) ...[
            const SizedBox(height: 6),
            GestureDetector(
              onTap: () {
                setState(() {
                  _errorTechDetailExpanded = !_errorTechDetailExpanded;
                });
              },
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    _errorTechDetailExpanded
                        ? _text('收起技术详情', 'Hide Details')
                        : _text('查看技术详情', 'Technical Details'),
                    style: TextStyle(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w500,
                      color: palette.textTertiary,
                    ),
                  ),
                  const SizedBox(width: 4),
                  Icon(
                    _errorTechDetailExpanded
                        ? LucideIcons.chevronUp
                        : LucideIcons.chevronDown,
                    size: 13,
                    color: palette.textTertiary,
                  ),
                ],
              ),
            ),
            if (_errorTechDetailExpanded) ...[
              const SizedBox(height: 6),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 6,
                ),
                decoration: BoxDecoration(
                  color: palette.surfacePrimary.withValues(alpha: 0.7),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: SelectableText(
                  diagnosis.technicalDetail!,
                  style: TextStyle(
                    fontSize: 11,
                    fontFamily: 'monospace',
                    color: palette.textSecondary,
                  ),
                ),
              ),
            ],
          ],
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              FilledButton.tonalIcon(
                key: const Key('provider-model-fetch-retry-button'),
                onPressed: widget.isFetchingModels
                    ? null
                    : widget.onFetchModels,
                icon: const Icon(LucideIcons.refreshCw, size: 14),
                label: Text(_text('重试', 'Retry')),
                style: FilledButton.styleFrom(
                  visualDensity: VisualDensity.compact,
                ),
              ),
              OutlinedButton.icon(
                onPressed: widget.onSwitchToConnectionTab,
                icon: const Icon(LucideIcons.slidersHorizontal, size: 14),
                label: Text(_text('检查连接设置', 'Check Connection')),
                style: OutlinedButton.styleFrom(
                  visualDensity: VisualDensity.compact,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildEmptyModelsState(BuildContext context) {
    final palette = context.omniPalette;
    final hasQuery = widget.searchController.text.trim().isNotEmpty;
    final missingConfig = widget.missingConfigItem;

    if (hasQuery) {
      return OmniSettingsCard(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(LucideIcons.searchX, size: 36, color: palette.textTertiary),
            const SizedBox(height: 10),
            Text(
              _text('未找到匹配的模型', 'No matching models found'),
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 14.5,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              _text(
                '可尝试更换关键词，或点击上方“手动添加”补充模型 ID。',
                'Try another keyword, or tap "Add Manually" above to add a model ID.',
              ),
              textAlign: TextAlign.center,
              style: TextStyle(color: palette.textSecondary, fontSize: 12.5),
            ),
          ],
        ),
      );
    }

    if (missingConfig != null && missingConfig.isNotEmpty) {
      return OmniSettingsCard(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              LucideIcons.plugZap,
              size: 36,
              color: palette.textTertiary,
            ),
            const SizedBox(height: 10),
            Text(
              _text(
                '连接配置尚未完成（$missingConfig）',
                'Connection incomplete ($missingConfig)',
              ),
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 14.5,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              _text(
                '完成连接信息后即可从服务商获取模型列表，也可直接手动添加已知模型 ID。',
                'Complete the connection settings to fetch models from the provider, or add known model IDs manually.',
              ),
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 12.5,
                height: 1.45,
              ),
            ),
            const SizedBox(height: 14),
            Wrap(
              spacing: 10,
              runSpacing: 8,
              alignment: WrapAlignment.center,
              children: [
                FilledButton.icon(
                  onPressed: widget.onSwitchToConnectionTab,
                  style: FilledButton.styleFrom(
                    backgroundColor: palette.accentPrimary,
                    foregroundColor: Colors.white,
                  ),
                  icon: const Icon(LucideIcons.slidersHorizontal, size: 15),
                  label: Text(_text('前往填写连接信息', 'Configure Connection')),
                ),
                if (!widget.readOnly)
                  OutlinedButton.icon(
                    onPressed: widget.onAddManualModel,
                    icon: const Icon(LucideIcons.plus, size: 15),
                    label: Text(_text('手动添加模型', 'Add Manually')),
                  ),
              ],
            ),
          ],
        ),
      );
    }

    return OmniSettingsCard(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 28),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(LucideIcons.package, size: 42, color: palette.textTertiary),
          const SizedBox(height: 10),
          Text(
            widget.fetchErrorDiagnosis?.summary ??
                context.l10n.modelAddPrompt,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 14.5,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            _text(
              '从服务商拉取远端模型目录，或手动添加指定的模型 ID；开启“在聊天中显示”的模型会出现在聊天模型选择器中。',
              'Fetch the model catalog from the provider or add model IDs manually. Models with "Show in Chat" enabled appear in the chat model selector.',
            ),
            textAlign: TextAlign.center,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 12.5,
              height: 1.45,
            ),
          ),
          const SizedBox(height: 14),
          Wrap(
            spacing: 10,
            runSpacing: 8,
            alignment: WrapAlignment.center,
            children: [
              FilledButton.icon(
                onPressed: widget.canFetchModels && !widget.isFetchingModels
                    ? widget.onFetchModels
                    : null,
                style: FilledButton.styleFrom(
                  backgroundColor: palette.accentPrimary,
                  foregroundColor: Colors.white,
                ),
                icon: const Icon(LucideIcons.arrowBigDown, size: 16),
                label: Text(_text('获取模型', 'Fetch Models')),
              ),
              if (!widget.readOnly)
                OutlinedButton.icon(
                  onPressed: widget.onAddManualModel,
                  icon: const Icon(LucideIcons.plus, size: 16),
                  label: Text(_text('手动添加', 'Add Manually')),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildCompactIconChip({
    required String key,
    required IconData icon,
    required String tooltip,
    String? label,
  }) {
    final palette = context.omniPalette;
    return Tooltip(
      message: tooltip,
      child: Container(
        key: Key(key),
        height: 20,
        constraints: const BoxConstraints(minWidth: 20),
        padding: label == null
            ? const EdgeInsets.symmetric(horizontal: 4)
            : const EdgeInsets.symmetric(horizontal: 6),
        decoration: BoxDecoration(
          color: palette.surfaceSecondary,
          borderRadius: BorderRadius.circular(5),
          border: Border.all(color: palette.borderSubtle, width: 0.65),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 12, color: palette.textSecondary),
            if (label != null) ...[
              const SizedBox(width: 3),
              Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 10.5,
                  color: palette.textSecondary,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildTextTagChip({
    Key? chipKey,
    required String text,
    Color? textColor,
    Color? backgroundColor,
    Color? borderColor,
  }) {
    final palette = context.omniPalette;
    return Container(
      key: chipKey,
      height: 20,
      padding: const EdgeInsets.symmetric(horizontal: 6),
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: backgroundColor ?? palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(5),
        border: Border.all(
          color: borderColor ?? palette.borderSubtle,
          width: 0.65,
        ),
      ),
      child: Text(
        text,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          fontSize: 10.5,
          color: textColor ?? palette.textSecondary,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }

  List<Widget> _buildModelMetadataChips(ProviderModelItem item) {
    final model = item.model;
    final widgets = <Widget>[];
    final contextLimit = formatCompactTokenLimit(model.contextLimit);
    widgets.add(
      _buildTextTagChip(
        chipKey: Key('provider-model-context-${model.id}'),
        text: contextLimit,
      ),
    );
    if (model.reasoning == true) {
      widgets.add(
        _buildCompactIconChip(
          key: 'provider-model-reasoning-${model.id}',
          icon: LucideIcons.brain,
          tooltip: 'Reasoning',
        ),
      );
    }
    final modalities = model.inputModalities
        .map((m) => m.trim().toLowerCase())
        .where((m) => m.isNotEmpty)
        .toSet();
    if (modalities.contains('text')) {
      widgets.add(
        _buildCompactIconChip(
          key: 'provider-model-modality-text',
          icon: LucideIcons.fileText,
          tooltip: 'Text input',
        ),
      );
    }
    if (modalities.contains('image')) {
      widgets.add(
        _buildCompactIconChip(
          key: 'provider-model-modality-image',
          icon: LucideIcons.image,
          tooltip: 'Image input',
        ),
      );
    }
    if (modalities.contains('pdf')) {
      widgets.add(
        _buildCompactIconChip(
          key: 'provider-model-modality-pdf',
          icon: LucideIcons.file,
          tooltip: 'PDF input',
        ),
      );
    }
    for (final modality in modalities) {
      if (modality == 'text' || modality == 'image' || modality == 'pdf') {
        continue;
      }
      widgets.add(_buildTextTagChip(text: modality.toUpperCase()));
    }
    if (item.source == ProviderModelSource.manual) {
      widgets.add(
        _buildTextTagChip(
          chipKey: Key('provider-model-manual-tag-${model.id}'),
          text: _text('手动', 'Manual'),
        ),
      );
    }
    final testState = widget.modelTestStates[item.id];
    if (testState?.status == ModelTestStatus.passed) {
      widgets.add(
        _buildTextTagChip(
          chipKey: Key('provider-model-test-passed-${model.id}'),
          text: _text('测试通过', 'Verified'),
          textColor: const Color(0xFF2EAF67),
          backgroundColor: const Color(0xFF2EAF67).withValues(alpha: 0.12),
          borderColor: const Color(0xFF2EAF67).withValues(alpha: 0.35),
        ),
      );
    } else if (testState?.status == ModelTestStatus.failed) {
      widgets.add(
        _buildTextTagChip(
          chipKey: Key('provider-model-test-failed-${model.id}'),
          text: _text('测试失败', 'Test Failed'),
          textColor: AppColors.alertRed,
          backgroundColor: AppColors.alertRed.withValues(alpha: 0.12),
          borderColor: AppColors.alertRed.withValues(alpha: 0.35),
        ),
      );
    }
    return widgets;
  }

  Widget _buildModelRow(ProviderModelItem item) {
    final palette = context.omniPalette;
    final visibleInChat = _isModelVisibleInChat(item.id);
    final selected = _selectedModelIds.contains(item.id);
    final chips = _buildModelMetadataChips(item);
    final displayTitle = item.model.displayName.trim().isNotEmpty
        ? item.model.displayName.trim()
        : item.id;
    final showSubtitleId = displayTitle != item.id;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: ValueKey<String>('provider-model-${item.id}'),
        onTap: () {
          if (_selectionMode) {
            _toggleSelectedModel(item.id);
          } else {
            unawaited(_openModelDetailSheet(item));
          }
        },
        onLongPress: () {
          if (_selectionMode) {
            _toggleSelectedModel(item.id);
          } else {
            _enterSelectionMode(item.id);
          }
        },
        borderRadius: BorderRadius.circular(10),
        splashColor: palette.accentPrimary.withValues(alpha: 0.06),
        highlightColor: Colors.transparent,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 48),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Row(
                        children: [
                          Flexible(
                            child: Text(
                              item.id,
                              style: TextStyle(
                                fontSize: 14.5,
                                fontWeight: FontWeight.w600,
                                color: visibleInChat
                                    ? palette.textPrimary
                                    : palette.textSecondary,
                                height: 1.25,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          if (showSubtitleId) ...[
                            const SizedBox(width: 6),
                            Flexible(
                              child: Text(
                                displayTitle,
                                style: TextStyle(
                                  fontSize: 12,
                                  color: palette.textTertiary,
                                  height: 1.2,
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ],
                        ],
                      ),
                      if (chips.isNotEmpty) ...[
                        const SizedBox(height: 5),
                        SingleChildScrollView(
                          scrollDirection: Axis.horizontal,
                          physics: const BouncingScrollPhysics(),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              for (var i = 0; i < chips.length; i++) ...[
                                if (i > 0) const SizedBox(width: 5),
                                chips[i],
                              ],
                            ],
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(width: 10),
                if (_selectionMode)
                  Checkbox(
                    key: Key('provider-model-checkbox-${item.id}'),
                    value: selected,
                    activeColor: palette.accentPrimary,
                    onChanged: (_) => _toggleSelectedModel(item.id),
                  )
                else
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Tooltip(
                        message: visibleInChat
                            ? _text('在聊天中显示（点击隐藏）', 'Shown in chat (tap to hide)')
                            : _text('已从聊天隐藏（点击显示）', 'Hidden from chat (tap to show)'),
                        child: Semantics(
                          label: _text('在聊天中显示 ${item.id}', 'Show ${item.id} in chat'),
                          child: OmniSettingsSwitch(
                            key: Key(
                              'provider-chat-model-visibility-toggle-${item.id}',
                            ),
                            value: visibleInChat,
                            onToggle: (next) {
                              unawaited(
                                widget.onSetModelVisibleInChat(
                                  item.model,
                                  next,
                                ),
                              );
                            },
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
    );
  }

  Widget _buildSingleGroupModelsCard(List<ProviderModelItem> items) {
    final palette = context.omniPalette;
    return OmniSettingsCard(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var i = 0; i < items.length; i++) ...[
            _buildModelRow(items[i]),
            if (i < items.length - 1)
              Divider(
                height: 1,
                thickness: 0.6,
                color: palette.borderSubtle.withValues(alpha: 0.7),
              ),
          ],
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
      color: palette.textTertiary,
      fontSize: 11,
      fontWeight: FontWeight.w700,
      letterSpacing: 0.4,
    );
    final countStyle = TextStyle(
      color: palette.textTertiary,
      fontSize: 11,
      fontWeight: FontWeight.w500,
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
                final textPainter = TextPainter(
                  text: TextSpan(text: '$count', style: countStyle),
                  textDirection: Directionality.of(context),
                  maxLines: 1,
                )..layout();
                final countWidth = textPainter.width;
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
                      monochromeColor: palette.textTertiary,
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
                        color: palette.borderSubtle,
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
                            color: palette.textTertiary,
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

  Widget _buildMultiGroupCard(
    MapEntry<String, List<ProviderModelItem>> group,
  ) {
    final palette = context.omniPalette;
    final expanded = _isGroupExpanded(group.key);
    final itemsColumn = Column(
      children: [
        const SizedBox(height: 2),
        for (var i = 0; i < group.value.length; i++) ...[
          _buildModelRow(group.value[i]),
          if (i < group.value.length - 1)
            Divider(
              height: 1,
              thickness: 0.6,
              color: palette.borderSubtle.withValues(alpha: 0.7),
            ),
        ],
      ],
    );

    return OmniSettingsCard(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildModelGroupHeader(
            group.key,
            group.value.length,
            expanded: expanded,
            onTap: () => widget.onToggleModelGroup(group.key),
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
            child: itemsColumn,
          ),
        ],
      ),
    );
  }

  Widget _buildBatchBottomBar(BuildContext context) {
    final palette = context.omniPalette;
    final selectedManualIds = widget.allModelItems
        .where(
          (item) =>
              item.source == ProviderModelSource.manual &&
              _selectedModelIds.contains(item.id),
        )
        .map((item) => item.id)
        .toList();
    final hasSelection = _selectedModelIds.isNotEmpty;

    return SafeArea(
      top: false,
      child: Container(
        key: const Key('provider-model-batch-bar'),
        margin: const EdgeInsets.fromLTRB(18, 8, 18, 16),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: palette.surfacePrimary,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: palette.borderSubtle, width: 0.8),
          boxShadow: [
            BoxShadow(
              color: palette.shadowColor.withValues(alpha: 0.14),
              blurRadius: 12,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          alignment: WrapAlignment.spaceEvenly,
          children: [
            TextButton.icon(
              key: const Key('provider-batch-show-chat-button'),
              onPressed: hasSelection
                  ? () => unawaited(
                      widget.onBatchSetVisibleInChat(_selectedModelIds, true),
                    )
                  : null,
              icon: const Icon(LucideIcons.eye, size: 15),
              label: Text(_text('在聊天中显示', 'Show in Chat')),
            ),
            TextButton.icon(
              key: const Key('provider-batch-hide-chat-button'),
              onPressed: hasSelection
                  ? () => unawaited(
                      widget.onBatchSetVisibleInChat(_selectedModelIds, false),
                    )
                  : null,
              icon: const Icon(LucideIcons.eyeOff, size: 15),
              label: Text(_text('从聊天隐藏', 'Hide from Chat')),
            ),
            if (selectedManualIds.isNotEmpty)
              TextButton.icon(
                key: const Key('provider-batch-delete-manual-button'),
                onPressed: () =>
                    unawaited(_confirmAndDeleteManualModels(selectedManualIds)),
                style: TextButton.styleFrom(
                  foregroundColor: AppColors.alertRed,
                ),
                icon: const Icon(LucideIcons.trash2, size: 15),
                label: Text(
                  _text(
                    '删除手动模型 (${selectedManualIds.length})',
                    'Delete Manual (${selectedManualIds.length})',
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
    final groups = widget.filteredModelGroups;
    final items = widget.filteredModelItems;
    final singleGroup = groups.length == 1;

    return Stack(
      children: [
        ListView(
          key: const PageStorageKey<String>('provider-models-tab-scroll'),
          controller: widget.scrollController,
          padding: edgeToEdgeScrollPadding(
            context,
            EdgeInsets.fromLTRB(18, 12, 18, _selectionMode ? 96 : 28),
          ),
          children: [
            _buildTopToolbar(context),
            if (widget.fetchErrorDiagnosis != null &&
                widget.allModelItems.isNotEmpty)
              _buildFetchErrorBanner(context, widget.fetchErrorDiagnosis!),
            const SizedBox(height: 12),
            if (items.isEmpty) ...[
              if (widget.fetchErrorDiagnosis != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: _buildFetchErrorBanner(
                    context,
                    widget.fetchErrorDiagnosis!,
                  ),
                ),
              _buildEmptyModelsState(context),
            ] else if (singleGroup)
              _buildSingleGroupModelsCard(groups.first.value)
            else
              for (var i = 0; i < groups.length; i++) ...[
                _buildMultiGroupCard(groups[i]),
                if (i < groups.length - 1) const SizedBox(height: 12),
              ],
          ],
        ),
        if (_selectionMode)
          Align(
            alignment: Alignment.bottomCenter,
            child: _buildBatchBottomBar(context),
          ),
      ],
    );
  }
}

class _LabeledActionChipButton extends StatelessWidget {
  const _LabeledActionChipButton({
    this.buttonKey,
    required this.icon,
    required this.label,
    required this.onPressed,
    this.primary = false,
    this.loading = false,
  });

  final Key? buttonKey;
  final IconData icon;
  final String label;
  final VoidCallback? onPressed;
  final bool primary;
  final bool loading;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final enabled = onPressed != null;
    final bgColor = primary
        ? palette.accentPrimary.withValues(
            alpha: enabled ? (context.isDarkTheme ? 0.22 : 0.12) : 0.06,
          )
        : palette.surfacePrimary;
    final fgColor = !enabled
        ? palette.textTertiary
        : primary
        ? palette.accentPrimary
        : palette.textPrimary;
    final borderColor = primary && enabled
        ? palette.accentPrimary.withValues(alpha: 0.45)
        : palette.borderSubtle;

    return Material(
      color: bgColor,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        key: buttonKey,
        onTap: onPressed,
        borderRadius: BorderRadius.circular(10),
        child: Container(
          height: 34,
          padding: const EdgeInsets.symmetric(horizontal: 11),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: borderColor, width: 0.75),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (loading)
                SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: fgColor,
                  ),
                )
              else
                Icon(icon, size: 15, color: fgColor),
              const SizedBox(width: 5),
              Text(
                label,
                style: TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w600,
                  color: fgColor,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ModelDetailSheet extends StatefulWidget {
  const _ModelDetailSheet({
    required this.item,
    required this.initialVisibleInChat,
    required this.initialTestState,
    required this.readOnly,
    required this.onToggleVisibleInChat,
    required this.onTestModel,
    this.onDeleteManualModel,
  });

  final ProviderModelItem item;
  final bool initialVisibleInChat;
  final ModelTestState initialTestState;
  final bool readOnly;
  final Future<bool> Function(bool visible) onToggleVisibleInChat;
  final Future<ModelTestState> Function() onTestModel;
  final VoidCallback? onDeleteManualModel;

  @override
  State<_ModelDetailSheet> createState() => _ModelDetailSheetState();
}

class _ModelDetailSheetState extends State<_ModelDetailSheet> {
  late bool _visibleInChat = widget.initialVisibleInChat;
  late ModelTestState _testState = widget.initialTestState;
  bool _techDetailExpanded = false;

  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';
  String _text(String zh, String en) => _isEnglish ? en : zh;

  Future<void> _handleToggleChat(bool next) async {
    setState(() => _visibleInChat = next);
    final ok = await widget.onToggleVisibleInChat(next);
    if (!ok && mounted) {
      setState(() => _visibleInChat = !next);
    }
  }

  Future<void> _handleTestModel() async {
    if (_testState.status == ModelTestStatus.testing) return;
    setState(() {
      _testState = const ModelTestState(status: ModelTestStatus.testing);
    });
    final nextState = await widget.onTestModel();
    if (!mounted) return;
    setState(() {
      _testState = nextState;
    });
  }

  Widget _buildDetailRow(BuildContext context, String label, String value) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 96,
            child: Text(
              label,
              style: TextStyle(
                fontSize: 12.5,
                color: palette.textSecondary,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: TextStyle(
                fontSize: 12.5,
                fontWeight: FontWeight.w500,
                color: palette.textPrimary,
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final model = widget.item.model;
    final isManual = widget.item.source == ProviderModelSource.manual;
    final contextText = model.contextLimit != null && model.contextLimit! > 0
        ? '${model.contextLimit} tokens (${formatCompactTokenLimit(model.contextLimit)})'
        : _text('未声明', 'Not declared');
    final ioLimits = <String>[
      if (model.inputLimit != null && model.inputLimit! > 0)
        _text(
          '输入 ${formatCompactTokenLimit(model.inputLimit)}',
          'In ${formatCompactTokenLimit(model.inputLimit)}',
        ),
      if (model.outputLimit != null && model.outputLimit! > 0)
        _text(
          '输出 ${formatCompactTokenLimit(model.outputLimit)}',
          'Out ${formatCompactTokenLimit(model.outputLimit)}',
        ),
    ].join(' · ');
    final inputModalitiesText = model.inputModalities.isEmpty
        ? _text('文本（默认）', 'Text (default)')
        : model.inputModalities.join(', ');
    final outputModalitiesText = model.outputModalities.isEmpty
        ? _text('文本（默认）', 'Text (default)')
        : model.outputModalities.join(', ');
    final reasoningText = model.reasoning == true
        ? (model.supportedReasoningLevels.isNotEmpty
              ? _text(
                  '支持（档位：${model.supportedReasoningLevels.join(', ')}）',
                  'Supported (${model.supportedReasoningLevels.join(', ')})',
                )
              : _text('支持', 'Supported'))
        : _text('未声明 / 默认关闭', 'Not declared');
    final capabilitiesList = <String>[
      if (model.toolCall == true) _text('工具调用', 'Tool Call'),
      if (model.structuredOutput == true)
        _text('结构化输出', 'Structured Output'),
      if (model.attachment == true) _text('文件附件', 'Attachments'),
    ];

    return OmniGlassAlertDialog(
      title: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  model.displayName.trim().isNotEmpty
                      ? model.displayName.trim()
                      : model.id,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 2),
                Text(
                  model.id,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w400,
                    color: palette.textSecondary,
                  ),
                ),
              ],
            ),
          ),
          IconButton(
            tooltip: _text('复制模型 ID', 'Copy Model ID'),
            onPressed: () {
              Clipboard.setData(ClipboardData(text: model.id));
              showToast(
                _text('已复制模型 ID', 'Copied model ID'),
                type: ToastType.success,
              );
            },
            icon: Icon(
              LucideIcons.copy,
              size: 16,
              color: palette.textSecondary,
            ),
          ),
        ],
      ),
      content: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              decoration: BoxDecoration(
                color: palette.surfaceSecondary,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: palette.borderSubtle, width: 0.75),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _text('在聊天中显示', 'Show in Chat'),
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            color: palette.textPrimary,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          _text(
                            '开启后，该模型会出现在聊天页的模型选择器中',
                            'When enabled, this model appears in the chat model selector',
                          ),
                          style: TextStyle(
                            fontSize: 11.5,
                            color: palette.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  OmniSettingsSwitch(
                    key: Key('model-detail-chat-switch-${model.id}'),
                    value: _visibleInChat,
                    onToggle: _handleToggleChat,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
            Text(
              _text('模型参数与能力', 'Parameters & Capabilities'),
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: palette.textPrimary,
              ),
            ),
            const SizedBox(height: 6),
            _buildDetailRow(
              context,
              _text('来源', 'Source'),
              isManual
                  ? _text('手动添加', 'Manually added')
                  : _text('服务商远端目录', 'Provider remote catalog'),
            ),
            _buildDetailRow(
              context,
              _text('上下文长度', 'Context Window'),
              contextText,
            ),
            if (ioLimits.isNotEmpty)
              _buildDetailRow(
                context,
                _text('输入/输出上限', 'Input/Output'),
                ioLimits,
              ),
            _buildDetailRow(
              context,
              _text('输入模态', 'Input Modalities'),
              inputModalitiesText,
            ),
            _buildDetailRow(
              context,
              _text('输出模态', 'Output Modalities'),
              outputModalitiesText,
            ),
            _buildDetailRow(
              context,
              _text('深度思考', 'Reasoning'),
              reasoningText,
            ),
            if (capabilitiesList.isNotEmpty)
              _buildDetailRow(
                context,
                _text('特性支持', 'Features'),
                capabilitiesList.join(' · '),
              ),
            const SizedBox(height: 14),
            Divider(height: 1, thickness: 0.6, color: palette.borderSubtle),
            const SizedBox(height: 12),
            Text(
              _text('可用性测试', 'Availability Test'),
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: palette.textPrimary,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              _text(
                '获取列表成功不代表每个模型都具备调用权限；可发送测试请求验证该模型是否可正常响应。',
                'Fetching the model list does not guarantee every model is callable. Run a test request to verify.',
              ),
              style: TextStyle(
                fontSize: 11.5,
                color: palette.textSecondary,
                height: 1.4,
              ),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                FilledButton.tonalIcon(
                  key: Key('provider-test-model-button-${model.id}'),
                  onPressed: _testState.status == ModelTestStatus.testing
                      ? null
                      : _handleTestModel,
                  icon: _testState.status == ModelTestStatus.testing
                      ? const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(LucideIcons.play, size: 14),
                  label: Text(
                    _testState.status == ModelTestStatus.testing
                        ? _text('测试中…', 'Testing…')
                        : _text('测试模型', 'Test Model'),
                  ),
                ),
                const SizedBox(width: 10),
                if (_testState.status == ModelTestStatus.passed)
                  Expanded(
                    child: Text(
                      _testState.message ?? _text('测试通过', 'Test passed'),
                      style: const TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                        color: Color(0xFF2EAF67),
                      ),
                    ),
                  ),
              ],
            ),
            if (_testState.status == ModelTestStatus.failed) ...[
              const SizedBox(height: 8),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: AppColors.alertRed.withValues(alpha: 0.08),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(
                    color: AppColors.alertRed.withValues(alpha: 0.28),
                    width: 0.75,
                  ),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _testState.diagnosis != null
                          ? '${_testState.diagnosis!.categoryLabel}：${_testState.message ?? _testState.diagnosis!.summary}'
                          : (_testState.message ?? _text('测试失败', 'Test failed')),
                      style: const TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: AppColors.alertRed,
                      ),
                    ),
                    if (_testState.diagnosis?.hint != null) ...[
                      const SizedBox(height: 3),
                      Text(
                        _testState.diagnosis!.hint,
                        style: TextStyle(
                          fontSize: 11.5,
                          color: palette.textSecondary,
                        ),
                      ),
                    ],
                    if (_testState.diagnosis?.technicalDetail != null) ...[
                      const SizedBox(height: 4),
                      GestureDetector(
                        onTap: () => setState(
                          () => _techDetailExpanded = !_techDetailExpanded,
                        ),
                        child: Text(
                          _techDetailExpanded
                              ? _text('收起技术详情', 'Hide Details')
                              : _text('展开技术详情', 'Technical Details'),
                          style: TextStyle(
                            fontSize: 11,
                            color: palette.textTertiary,
                          ),
                        ),
                      ),
                      if (_techDetailExpanded) ...[
                        const SizedBox(height: 4),
                        SelectableText(
                          _testState.diagnosis!.technicalDetail!,
                          style: TextStyle(
                            fontSize: 11,
                            fontFamily: 'monospace',
                            color: palette.textSecondary,
                          ),
                        ),
                      ],
                    ],
                  ],
                ),
              ),
            ],
            if (!isManual) ...[
              const SizedBox(height: 12),
              Text(
                _text(
                  '提示：远端获取的模型来自服务商目录；若不想在聊天中使用，关闭上方“在聊天中显示”即可，不会误删配置。',
                  'Tip: Fetched models come from the provider catalog. Turn off "Show in Chat" above to hide a model without deleting your configuration.',
                ),
                style: TextStyle(
                  fontSize: 11.5,
                  color: palette.textTertiary,
                  height: 1.38,
                ),
              ),
            ],
          ],
        ),
      ),
      actions: [
        if (widget.onDeleteManualModel != null && !widget.readOnly)
          TextButton.icon(
            key: Key('provider-delete-manual-model-button-${model.id}'),
            onPressed: widget.onDeleteManualModel,
            style: TextButton.styleFrom(foregroundColor: AppColors.alertRed),
            icon: const Icon(LucideIcons.trash2, size: 15),
            label: Text(_text('删除手动模型', 'Delete Manual Model')),
          ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(_text('关闭', 'Close')),
        ),
      ],
    );
  }
}
