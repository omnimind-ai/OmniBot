import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/models/agent_skill_item.dart';
import 'package:ui/services/agent_skill_store_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';
import 'package:ui/widgets/settings_ui.dart';

const String _kOfficialSkillsDownloadAsset =
    'assets/home/hard_drive_download.svg';

const String _kBuiltinSkillBadgeCheckSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round" '
    'class="lucide lucide-badge-check-icon lucide-badge-check">'
    '<path d="M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 '
    '4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 4 4 0 0 1-4.77 4.78 '
    '4 4 0 0 1-6.75 0 4 4 0 0 1-4.78-4.77 4 4 0 0 1 0-6.76Z"/>'
    '<path d="m9 12 2 2 4-4"/></svg>';

class SkillStorePage extends StatefulWidget {
  const SkillStorePage({super.key});

  @override
  State<SkillStorePage> createState() => _SkillStorePageState();
}

class _SkillStorePageState extends State<SkillStorePage>
    with FlClashPageLoadMixin {
  static const double _kSkillTileTrailingWidth = 64;
  static const double _kSkillTileTrailingHeight = 28;

  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocusNode = FocusNode();
  bool _loading = true;
  bool _syncingOfficial = false;
  final Set<String> _busyIds = <String>{};
  List<AgentSkillItem> _skills = [];

  String get _searchQuery => _searchController.text.trim();

  List<AgentSkillItem> get _visibleSkills {
    final query = _searchQuery.toLowerCase();
    if (query.isEmpty) {
      return _skills;
    }

    return _skills
        .where((skill) {
          return skill.name.toLowerCase().contains(query) ||
              skill.description.toLowerCase().contains(query);
        })
        .toList(growable: false);
  }

  @override
  void initState() {
    super.initState();
    _searchController.addListener(_handleSearchChanged);
    _searchFocusNode.addListener(_handleSearchFocusChanged);
    _loadSkills();
  }

  @override
  void dispose() {
    _searchController
      ..removeListener(_handleSearchChanged)
      ..dispose();
    _searchFocusNode
      ..removeListener(_handleSearchFocusChanged)
      ..dispose();
    super.dispose();
  }

  void _handleSearchChanged() {
    if (!mounted) return;
    setState(() {});
  }

  void _handleSearchFocusChanged() {
    if (!mounted) return;
    setState(() {});
  }

  Future<void> _loadSkills() async {
    if (!await waitForPageReady() || !mounted) return;
    setState(() => _loading = true);
    try {
      final skills = await AgentSkillStoreService.listSkills();
      if (!await waitForPageReady() || !mounted) return;
      setState(() {
        _skills = skills;
        _loading = false;
      });
    } catch (_) {
      if (!await waitForPageReady() || !mounted) return;
      setState(() => _loading = false);
      showToast(context.l10n.skillLoadFailed, type: ToastType.error);
    }
  }

  void _setBusy(String id, bool busy) {
    if (!mounted) return;
    setState(() {
      if (busy) {
        _busyIds.add(id);
      } else {
        _busyIds.remove(id);
      }
    });
  }

  Future<void> _toggleSkill(AgentSkillItem item, bool enabled) async {
    _setBusy(item.id, true);
    try {
      final updated = await AgentSkillStoreService.setEnabled(
        skillId: item.id,
        enabled: enabled,
      );
      if (!mounted) return;
      if (updated == null) {
        showToast(context.l10n.skillToggleFailed, type: ToastType.error);
        return;
      }
      setState(() {
        _skills = _skills
            .map((skill) => skill.id == item.id ? updated : skill)
            .toList();
      });
      showToast(
        enabled
            ? context.l10n.skillEnabledMsg(item.name)
            : context.l10n.skillDisabledMsg(item.name),
      );
    } catch (_) {
      showToast(context.l10n.skillToggleFailed, type: ToastType.error);
    } finally {
      _setBusy(item.id, false);
    }
  }

  Future<void> _installBuiltinSkill(AgentSkillItem item) async {
    _setBusy(item.id, true);
    try {
      final installed = await AgentSkillStoreService.installBuiltinSkill(
        skillId: item.id,
      );
      if (!mounted) return;
      if (installed == null) {
        showToast(context.l10n.skillInstallFailed, type: ToastType.error);
        return;
      }
      setState(() {
        _skills = _skills
            .map((skill) => skill.id == item.id ? installed : skill)
            .toList();
        _skills.sort((a, b) {
          if (a.installed != b.installed) {
            return a.installed ? -1 : 1;
          }
          final sourceOrder = _sourceRank(a).compareTo(_sourceRank(b));
          if (sourceOrder != 0) {
            return sourceOrder;
          }
          return a.name.toLowerCase().compareTo(b.name.toLowerCase());
        });
      });
      showToast(
        context.l10n.skillInstalledMsg(item.name),
        type: ToastType.success,
      );
    } catch (_) {
      showToast(context.l10n.skillInstallFailed, type: ToastType.error);
    } finally {
      _setBusy(item.id, false);
    }
  }

  Future<void> _syncOfficialSkills() async {
    if (_syncingOfficial) return;
    setState(() => _syncingOfficial = true);
    try {
      final result = await AgentSkillStoreService.syncOfficialSkills();
      if (!mounted) return;
      if (result == null) {
        showToast(context.l10n.skillSyncOfficialFailed, type: ToastType.error);
        return;
      }
      setState(() {
        _skills = result.skills;
      });
      showToast(
        context.l10n.skillSyncOfficialSuccess(result.skillCount),
        type: ToastType.success,
      );
    } catch (_) {
      if (!mounted) return;
      showToast(context.l10n.skillSyncOfficialFailed, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _syncingOfficial = false);
      }
    }
  }

  Future<void> _deleteSkill(AgentSkillItem item) async {
    final confirmed = await showSettingsDetailSheet<bool>(
      context: context,
      builder: (sheetContext) => SettingsDetailSheet(
        title: context.l10n.skillDeleteTitle,
        body: Text(context.l10n.skillDeleteConfirmMsg(item.name)),
        actions: [
          TextButton(
            style: settingsDetailSheetActionStyle(sheetContext),
            onPressed: () => Navigator.of(sheetContext).pop(false),
            child: Text(context.trLegacy('取消')),
          ),
          TextButton(
            style: settingsDetailSheetActionStyle(
              sheetContext,
              foregroundColor: AppColors.alertRed,
            ),
            onPressed: () => Navigator.of(sheetContext).pop(true),
            child: Text(context.l10n.skillDelete),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    _setBusy(item.id, true);
    try {
      final deleted = await AgentSkillStoreService.deleteSkill(
        skillId: item.id,
      );
      if (!mounted) return;
      if (!deleted) {
        showToast(context.l10n.skillDeleteFailed, type: ToastType.error);
        return;
      }
      await _loadSkills();
      if (!mounted) return;
      showToast(context.l10n.skillDeleted, type: ToastType.success);
    } catch (_) {
      if (!mounted) return;
      showToast(context.l10n.skillDeleteFailed, type: ToastType.error);
    } finally {
      _setBusy(item.id, false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final visibleSkills = _visibleSkills;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.skillStoreTitle,
        primary: true,
        actions: [
          Padding(
            padding: const EdgeInsetsDirectional.only(end: 12),
            child: OmniCircleButton(
              tooltip: context.l10n.skillSyncOfficialTooltip,
              icon: Icons.download_rounded,
              size: 40,
              tapTargetSize: MaterialTapTargetSize.padded,
              onPressed: _syncingOfficial ? null : _syncOfficialSkills,
              iconWidget: _syncingOfficial
                  ? SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: palette.accentPrimary,
                      ),
                    )
                  : SvgPicture.asset(
                      _kOfficialSkillsDownloadAsset,
                      width: 20,
                      height: 20,
                      colorFilter: ColorFilter.mode(
                        palette.textPrimary,
                        BlendMode.srcIn,
                      ),
                    ),
            ),
          ),
        ],
      ),
      body: OmniContentWidth(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : SafeArea(
                top: false,
                bottom: false,
                child: Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 12, 16, 10),
                      child: OmniSettingsSearchField(
                        controller: _searchController,
                        focusNode: _searchFocusNode,
                        hintText: context.trLegacy('搜索技能名称或描述'),
                      ),
                    ),
                    Expanded(
                      child: RefreshIndicator(
                        onRefresh: _loadSkills,
                        child: _buildScrollableContent(visibleSkills),
                      ),
                    ),
                  ],
                ),
              ),
      ),
    );
  }

  Widget _buildScrollableContent(List<AgentSkillItem> visibleSkills) {
    if (_skills.isEmpty) {
      return _buildStateList(child: _buildEmpty());
    }
    if (visibleSkills.isEmpty) {
      return _buildStateList(child: _buildSearchEmpty());
    }
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: edgeToEdgeScrollPadding(
        context,
        const EdgeInsets.fromLTRB(16, 6, 16, 28),
      ),
      children: [
        OmniSettingsGroup(
          dividerIndent: 16,
          children: [for (final skill in visibleSkills) _buildSkillTile(skill)],
        ),
      ],
    );
  }

  Widget _buildStateList({required Widget child}) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: edgeToEdgeScrollPadding(
        context,
        const EdgeInsets.fromLTRB(18, 24, 18, 24),
      ),
      children: [SizedBox(height: 160, child: child)],
    );
  }

  Widget _buildEmpty() {
    final palette = context.omniPalette;
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.extension_outlined, size: 40, color: palette.textTertiary),
          const SizedBox(height: 12),
          Text(
            context.l10n.skillEmpty,
            style: TextStyle(fontSize: 15, color: palette.textSecondary),
          ),
        ],
      ),
    );
  }

  Widget _buildSkillTile(AgentSkillItem item) {
    final palette = context.omniPalette;
    final busy = _busyIds.contains(item.id);
    final description = item.description.trim().isEmpty
        ? context.l10n.skillNoDescription
        : item.description;
    final statusSummary = _buildStatusSummary(item);

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildSkillTitle(item),
                const SizedBox(height: 3),
                Text(
                  description,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 12,
                    fontWeight: FontWeight.w400,
                    height: 1.48,
                  ),
                ),
                if (statusSummary.isNotEmpty) ...[
                  const SizedBox(height: 5),
                  Text(
                    statusSummary,
                    style: TextStyle(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w500,
                      color: item.installed && item.enabled
                          ? palette.accentPrimary
                          : palette.textTertiary,
                      height: 1.4,
                    ),
                  ),
                ],
                if (!item.installed && item.isBuiltin) ...[
                  const SizedBox(height: 6),
                  Text(
                    context.l10n.skillBuiltinRemovedDesc,
                    style: TextStyle(
                      fontSize: 11.5,
                      color: palette.textTertiary,
                      height: 1.45,
                    ),
                  ),
                ],
                if (item.installed) ...[
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          item.shellSkillFilePath,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 11,
                            color: palette.textTertiary,
                            height: 1.4,
                          ),
                        ),
                      ),
                      if (!item.isOfficial) ...[
                        const SizedBox(width: 12),
                        TextButton(
                          onPressed: busy ? null : () => _deleteSkill(item),
                          style: TextButton.styleFrom(
                            foregroundColor: AppColors.alertRed,
                            padding: EdgeInsets.zero,
                            minimumSize: const Size(0, 28),
                            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                          ),
                          child: Text(
                            context.l10n.skillDelete,
                            style: const TextStyle(fontSize: 12),
                          ),
                        ),
                      ],
                    ],
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: _kSkillTileTrailingWidth,
            height: _kSkillTileTrailingHeight,
            child: Align(
              alignment: Alignment.topRight,
              child: _buildTrailingControl(item, busy: busy),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSkillTitle(AgentSkillItem item) {
    final palette = context.omniPalette;
    final titleStyle = TextStyle(
      fontSize: 15,
      fontWeight: FontWeight.w500,
      color: palette.textPrimary,
      height: 1.38,
    );

    if (!item.isBuiltin && !item.isOfficial) {
      return Text(item.name, style: titleStyle);
    }
    final badgeLabel = item.isBuiltin
        ? context.l10n.skillBuiltin
        : context.l10n.skillOfficial;

    return Text.rich(
      TextSpan(
        children: [
          TextSpan(text: item.name, style: titleStyle),
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: Padding(
              padding: const EdgeInsets.only(left: 6),
              child: Tooltip(
                message: badgeLabel,
                child: SvgPicture.string(
                  _kBuiltinSkillBadgeCheckSvg,
                  width: 16,
                  height: 16,
                  colorFilter: ColorFilter.mode(
                    palette.accentPrimary,
                    BlendMode.srcIn,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _buildStatusSummary(AgentSkillItem item) {
    final labels = <String>[];
    if (item.isBuiltin) {
      labels.add(context.l10n.skillBuiltin);
    } else if (item.isOfficial) {
      labels.add(context.l10n.skillOfficial);
    } else if (item.installed) {
      labels.add(context.l10n.skillInstalled);
    }
    if (item.installed) {
      labels.add(
        item.enabled ? context.l10n.skillEnabled : context.l10n.skillDisabled,
      );
    }
    return labels.join(' · ');
  }

  Widget _buildTrailingControl(AgentSkillItem item, {required bool busy}) {
    final palette = context.omniPalette;
    if (item.installed) {
      return SizedBox(
        width: _kSkillTileTrailingWidth,
        height: _kSkillTileTrailingHeight,
        child: Stack(
          alignment: Alignment.centerRight,
          children: [
            OmniSettingsSwitch(
              value: item.enabled,
              enabled: !busy,
              onToggle: (next) => _toggleSkill(item, next),
            ),
            IgnorePointer(
              ignoring: !busy,
              child: AnimatedOpacity(
                duration: const Duration(milliseconds: 160),
                curve: Curves.easeOutCubic,
                opacity: busy ? 1 : 0,
                child: const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2.1),
                ),
              ),
            ),
          ],
        ),
      );
    }
    return SizedBox(
      width: _kSkillTileTrailingWidth,
      height: _kSkillTileTrailingHeight,
      child: Stack(
        alignment: Alignment.centerRight,
        children: [
          AnimatedOpacity(
            duration: const Duration(milliseconds: 180),
            curve: Curves.easeOutCubic,
            opacity: busy ? 0.4 : 1,
            child: TextButton(
              onPressed: busy
                  ? null
                  : item.isBuiltin
                  ? () => _installBuiltinSkill(item)
                  : null,
              style: TextButton.styleFrom(
                foregroundColor: palette.accentPrimary,
                padding: EdgeInsets.zero,
                minimumSize: const Size(44, 28),
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: Text(
                context.l10n.skillInstall,
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ),
          IgnorePointer(
            ignoring: !busy,
            child: AnimatedOpacity(
              duration: const Duration(milliseconds: 160),
              curve: Curves.easeOutCubic,
              opacity: busy ? 1 : 0,
              child: const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2.1),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSearchEmpty() {
    final palette = context.omniPalette;
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.search_off_rounded,
            size: 48,
            color: context.isDarkTheme
                ? palette.textTertiary
                : AppColors.text50,
          ),
          const SizedBox(height: 12),
          Text(
            context.trLegacy('未找到匹配的技能'),
            style: TextStyle(
              fontSize: 16,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : AppColors.text70,
            ),
          ),
        ],
      ),
    );
  }

  int _sourceRank(AgentSkillItem item) {
    if (item.isBuiltin) return 0;
    if (item.isOfficial) return 1;
    return 2;
  }
}
