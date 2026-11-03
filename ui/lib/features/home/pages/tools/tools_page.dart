import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/tool_catalog_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';
import 'package:ui/widgets/settings_ui.dart';

class ToolsPage extends StatefulWidget {
  const ToolsPage({super.key});

  @override
  State<ToolsPage> createState() => _ToolsPageState();
}

class _ToolsPageState extends State<ToolsPage>
    with WidgetsBindingObserver, FlClashPageLoadMixin {
  final _search = TextEditingController();
  final _focus = FocusNode();
  List<CatalogTool> _local = [];
  List<CatalogServer> _servers = [];
  bool _localLoading = true;
  bool _remoteLoading = true;
  bool _localFailed = false;
  bool _remoteFailed = false;

  String _text(String zh, String en) =>
      Localizations.localeOf(context).languageCode == 'en' ? en : zh;
  String get _query => _search.text.trim().toLowerCase();
  bool get _loading => _localLoading || _remoteLoading;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _search.addListener(_searchChanged);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _search.dispose();
    _focus.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !_loading) _load();
  }

  void _searchChanged() => setState(() {});

  Future<void> _load() async {
    if (!await waitForPageReady() || !mounted) return;
    setState(() {
      _localLoading = _remoteLoading = true;
      _localFailed = _remoteFailed = false;
    });
    await Future.wait([_loadLocal(), _loadRemote()]);
  }

  Future<void> _loadLocal() async {
    if (!await waitForPageReady() || !mounted) return;
    try {
      final tools = await ToolCatalogService.localTools();
      if (await waitForPageReady() && mounted) {
        setState(() => _local = tools);
      }
    } catch (_) {
      if (await waitForPageReady() && mounted) {
        setState(() {
          _local = [];
          _localFailed = true;
        });
      }
    } finally {
      if (await waitForPageReady() && mounted) {
        setState(() => _localLoading = false);
      }
    }
  }

  Future<void> _loadRemote() async {
    if (!await waitForPageReady() || !mounted) return;
    try {
      final servers = await ToolCatalogService.remoteTools();
      if (await waitForPageReady() && mounted) {
        setState(() => _servers = servers);
      }
    } catch (_) {
      if (await waitForPageReady() && mounted) {
        setState(() {
          _servers = [];
          _remoteFailed = true;
        });
      }
    } finally {
      if (await waitForPageReady() && mounted) {
        setState(() => _remoteLoading = false);
      }
    }
  }

  Future<void> _configure(String path) async {
    await context.push(path);
    if (mounted && !_loading) await _load();
  }

  String _groupName(String group) => switch (group) {
    'plugin' => _text('插件工具', 'Plugin tools'),
    'mcp' => _text('远程 MCP', 'Remote MCP'),
    _ => _text('内置工具', 'Built-in tools'),
  };

  String _status(String status) => switch (status) {
    'discovered' => _text('已发现', 'Discovered'),
    'disabled' => _text('未启用', 'Disabled'),
    'unavailable' => _text('连接失败', 'Connection failed'),
    _ => _text('已注册', 'Registered'),
  };

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final all = [..._local, ..._servers.expand((s) => s.tools)];
    final visible = all.where((tool) => tool.matches(_query)).toList();
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _text('工具', 'Tools'),
        primary: true,
        actions: [
          Padding(
            padding: const EdgeInsetsDirectional.only(end: 12),
            child: OmniCircleButton(
              tooltip: _text('刷新工具', 'Refresh tools'),
              onPressed: _loading ? null : _load,
              icon: LucideIcons.refreshCw,
              size: 40,
              tapTargetSize: MaterialTapTargetSize.padded,
              iconWidget: _loading
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : null,
            ),
          ),
        ],
      ),
      body: OmniContentWidth(
        child: SafeArea(
          top: false,
          bottom: false,
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(22, 12, 22, 8),
                child: OmniSettingsSearchField(
                  controller: _search,
                  focusNode: _focus,
                  hintText: _text(
                    '搜索名称、用途或来源',
                    'Search name, purpose or source',
                  ),
                ),
              ),
              Expanded(
                child: RefreshIndicator(
                  onRefresh: () async {
                    if (!_loading) await _load();
                  },
                  child: ListView(
                    keyboardDismissBehavior:
                        ScrollViewKeyboardDismissBehavior.onDrag,
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: edgeToEdgeScrollPadding(
                      context,
                      const EdgeInsets.fromLTRB(22, 8, 22, 32),
                    ),
                    children: [
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 4),
                        child: Text(
                          _text(
                            '查看内置 Agent 与已配置扩展的工具目录。实际调用取决于 Agent、会话配置、权限及运行环境。',
                            'Explore the built-in agent and configured extensions. Actual access depends on the agent, session, permissions and runtime.',
                          ),
                          style: TextStyle(
                            fontSize: 12,
                            height: 1.55,
                            color: palette.textSecondary,
                          ),
                        ),
                      ),
                      if (_localLoading || _remoteLoading)
                        Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 4,
                            vertical: 14,
                          ),
                          child: Row(
                            children: [
                              const SizedBox(
                                width: 14,
                                height: 14,
                                child: CircularProgressIndicator(
                                  strokeWidth: 1.5,
                                ),
                              ),
                              const SizedBox(width: 10),
                              Text(
                                _localLoading
                                    ? _text('读取工具目录…', 'Loading tools…')
                                    : _text(
                                        '发现远程工具…',
                                        'Discovering remote tools…',
                                      ),
                                style: TextStyle(
                                  fontSize: 12,
                                  color: palette.textSecondary,
                                ),
                              ),
                            ],
                          ),
                        ),
                      if (_localFailed)
                        _error(_text('本地工具加载失败', 'Could not load local tools')),
                      if (_remoteFailed)
                        _error(
                          _text('远程工具加载失败', 'Could not load remote tools'),
                        ),
                      if (_query.isNotEmpty && visible.isEmpty && !_loading)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 32),
                          child: Text(
                            _text(
                              '没有找到匹配的工具，试试其他关键词。',
                              'No matching tools. Try another keyword.',
                            ),
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 15,
                            ),
                          ),
                        ),
                      for (final group in ['builtin', 'plugin', 'mcp'])
                        if (_query.isEmpty ||
                            visible.any((t) => t.group == group)) ...[
                          _heading(
                            group,
                            visible.where((t) => t.group == group).length,
                          ),
                          _buildGroupSection(
                            group,
                            visible.where((t) => t.group == group).toList(),
                          ),
                        ],
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildGroupSection(String group, List<CatalogTool> groupTools) {
    final rows = <Widget>[
      for (final tool in groupTools) _toolRow(tool),
      if (group == 'plugin' &&
          !_localLoading &&
          !_localFailed &&
          !_local.any((t) => t.group == group))
        _note(
          _text('启用插件后，其工具会显示在这里。', 'Enable a plugin to see its tools here.'),
        ),
      if (group == 'mcp' && !_remoteLoading && !_remoteFailed) ...[
        for (final server in _servers.where((s) => s.tools.isEmpty))
          _note(
            '${server.name} · ${server.status == 'discovered' ? _text('未提供工具', 'No tools exposed') : _status(server.status)}',
          ),
        if (_servers.isEmpty)
          _note(_text('尚未配置远程 MCP 服务。', 'No remote MCP servers configured.')),
      ],
    ];
    if (rows.isEmpty) return const SizedBox.shrink();
    return OmniSettingsGroup(dividerIndent: 16, children: rows);
  }

  Widget _heading(String group, int count) => Padding(
    padding: const EdgeInsets.fromLTRB(4, 20, 4, 8),
    child: Row(
      children: [
        Expanded(
          child: Text(
            '${_groupName(group)} · $count',
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.3,
              color: context.omniPalette.textTertiary,
            ),
          ),
        ),
        if (group != 'builtin')
          TextButton(
            style: TextButton.styleFrom(
              minimumSize: const Size(0, 28),
              padding: const EdgeInsets.symmetric(horizontal: 8),
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            onPressed: () => _configure(
              group == 'plugin' ? '/home/plugin_market' : '/home/mcp_tools',
            ),
            child: Text(
              _text('管理', 'Manage'),
              style: const TextStyle(
                fontSize: 12.5,
                fontWeight: FontWeight.w500,
              ),
            ),
          ),
      ],
    ),
  );

  Widget _note(String text) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
    child: Align(
      alignment: Alignment.centerLeft,
      child: Text(
        text,
        style: TextStyle(
          fontSize: 12.5,
          height: 1.5,
          color: context.omniPalette.textSecondary,
        ),
      ),
    ),
  );

  Widget _error(String text) => Padding(
    padding: const EdgeInsets.only(top: 8),
    child: OmniSettingsCard(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      child: Row(
        children: [
          Expanded(
            child: Text(
              text,
              style: TextStyle(
                fontSize: 12.5,
                color: context.omniPalette.textSecondary,
              ),
            ),
          ),
          TextButton(
            onPressed: _loading ? null : _load,
            child: Text(_text('重试', 'Retry')),
          ),
        ],
      ),
    ),
  );

  Widget _toolRow(CatalogTool tool) {
    final palette = context.omniPalette;
    final hasSource = tool.source.trim().isNotEmpty;
    final hasNonDefaultStatus =
        tool.status != 'registered' && tool.status != 'discovered';
    final metaParts = <String>[
      if (hasSource) tool.source.trim(),
      if (hasNonDefaultStatus) _status(tool.status),
    ];

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => _showDetail(tool),
        splashColor: palette.accentPrimary.withValues(alpha: 0.06),
        highlightColor: palette.accentPrimary.withValues(alpha: 0.03),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13.5),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            tool.displayName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w500,
                              color: palette.textPrimary,
                              height: 1.35,
                            ),
                          ),
                        ),
                        if (metaParts.isNotEmpty) ...[
                          const SizedBox(width: 8),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6.5,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color: palette.surfaceSecondary,
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: Text(
                              metaParts.join(' · '),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: 10.5,
                                fontWeight: FontWeight.w500,
                                color: palette.textTertiary,
                                height: 1.2,
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 3.5),
                    Text(
                      tool.description.isEmpty ? tool.name : tool.description,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 12,
                        height: 1.45,
                        color: palette.textSecondary,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 10),
              Icon(
                LucideIcons.chevronRight,
                size: 16,
                color: palette.textTertiary,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _showDetail(CatalogTool tool) async {
    final path = await showSettingsDetailSheet<String>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) {
        final palette = sheetContext.omniPalette;
        return SettingsDetailSheet(
          title: tool.displayName,
          subtitle:
              '${_groupName(tool.group)} · ${tool.source.isEmpty ? _text('内置 Agent', 'Built-in agent') : tool.source} · ${_status(tool.status)}',
          body: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(7),
                ),
                child: SelectableText(
                  tool.name,
                  style: TextStyle(
                    fontSize: 12,
                    fontFamily: 'monospace',
                    color: palette.textSecondary,
                  ),
                ),
              ),
              const SizedBox(height: 14),
              SelectableText(
                tool.description.isEmpty
                    ? _text('暂无工具说明', 'No description provided')
                    : tool.description,
                style: TextStyle(
                  fontSize: 13.5,
                  height: 1.6,
                  color: palette.textPrimary,
                ),
              ),
              const SizedBox(height: 16),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _text('使用条件', 'Requirements'),
                      style: TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                        color: palette.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _text(
                        '工具由 Agent 在对话中调用。已注册或已发现表示目录中存在此工具，不代表权限、模型或运行环境已就绪；具体要求见工具说明。远程结果可能来自最近的发现缓存。',
                        'Tools are invoked by agents in a conversation. Registered or discovered tools may still require permissions, a model or runtime setup; see the description for requirements. Remote results may use the recent discovery cache.',
                      ),
                      style: TextStyle(
                        fontSize: 12,
                        height: 1.55,
                        color: palette.textSecondary,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              style: settingsDetailSheetActionStyle(sheetContext),
              onPressed: () => Navigator.pop(
                sheetContext,
                tool.group == 'plugin'
                    ? '/home/plugin_market'
                    : tool.group == 'mcp'
                    ? '/home/mcp_tools'
                    : '/home/authorize_setting',
              ),
              child: Text(
                tool.group == 'builtin'
                    ? _text('权限设置', 'Permissions')
                    : _text('管理来源', 'Manage source'),
              ),
            ),
          ],
        );
      },
    );
    if (mounted && path != null) await _configure(path);
  }
}
