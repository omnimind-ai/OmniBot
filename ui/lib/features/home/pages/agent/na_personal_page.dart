import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/omni_circle_button.dart';

import 'na_composio_settings.dart';
import 'na_connector_grants.dart';
import 'na_composio_catalog.dart';
import 'na_composio_events.dart';
import 'na_notification_settings.dart';
import 'na_connector_result.dart';
import 'na_routine_editor.dart';

import 'package:ui/widgets/navigation/page_route.dart';

import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/settings_ui.dart';

/// Client views of the existing Na service. Chat/session/turn ownership stays
/// with the shared Agent runtime; this page never starts another Agent loop.
class NaPersonalPage extends StatefulWidget {
  const NaPersonalPage({super.key});

  @override
  State<NaPersonalPage> createState() => _NaPersonalPageState();
}

class _NaPersonalPageState extends State<NaPersonalPage>
    with WidgetsBindingObserver {
  static const _labels = ['收件箱', '连接应用', '记忆', '职责', '资料'];
  String? _owner;
  String? _dataOwner;
  Set<String> _capabilities = {};
  Map<String, dynamic> _data = {};
  int _tab = 0, _revision = 0;
  bool _loading = true, _busy = false;
  bool _connectionConfigurationAvailable = false;
  bool _openingConnection = false, _openingCatalog = false;
  String? _error;
  final _search = TextEditingController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_load());
    unawaited(_loadConnectionAccess());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _search.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed &&
        !_busy &&
        !_openingConnection &&
        !_openingCatalog) {
      unawaited(_load());
    }
  }

  // The catalog is native/local policy and remains readable without a working
  // cloud connection. It does not return a development access token.
  Future<void> _loadConnectionAccess() async {
    try {
      final catalog = await AgentRuntimeService.listAgents();
      final available = catalog.agents.any(
        (agent) => agent.id == 'na-cloud' && agent.configurationAvailable,
      );
      if (mounted) {
        setState(() => _connectionConfigurationAvailable = available);
      }
    } catch (_) {
      if (mounted) setState(() => _connectionConfigurationAvailable = false);
    }
  }

  Future<void> _openConnection() async {
    if (!_connectionConfigurationAvailable || _openingConnection || _busy) {
      return;
    }
    ++_revision;
    setState(() {
      _openingConnection = true;
      _owner = null;
      _dataOwner = null;
      _data = {};
      _error = null;
    });
    try {
      await GoRouterManager.pushForResult<Object?>('/home/na/connection');
    } finally {
      if (mounted) {
        setState(() => _openingConnection = false);
        await _loadConnectionAccess();
        if (mounted) await _load(reconnect: true);
      }
    }
  }

  bool get _disabled =>
      _loading ||
      _busy ||
      _openingConnection ||
      _error != null ||
      _owner == null ||
      _dataOwner != _owner;
  Map<String, dynamic> _map(dynamic value) => value is Map
      ? value.map((key, value) => MapEntry(key.toString(), value))
      : {};
  List<Map<String, dynamic>> _list(dynamic value) =>
      value is List ? value.whereType<Map>().map(_map).toList() : [];
  String _json(dynamic value) =>
      const JsonEncoder.withIndent('  ').convert(value);

  Future<Map<String, dynamic>> _request(
    String operation, {
    String? id,
    Map<String, dynamic> payload = const {},
  }) async {
    final capturedOwner = _owner;
    final capturedRevision = _revision;
    final response = await AgentRuntimeService.requestNaPersonal(
      operation: operation,
      owner: capturedOwner,
      id: id,
      payload: payload,
    );
    if (capturedRevision != _revision) {
      throw StateError('Na 页面请求已过期。');
    }
    if (capturedOwner != null && response.owner != capturedOwner) {
      throw StateError('Na 连接已更改，请重新载入连接。');
    }
    if (!mounted) return {};
    if (_owner != capturedOwner) throw StateError('Na 页面连接已更改。');
    _owner = response.owner;
    return response.data;
  }

  Future<void> _load({bool reconnect = false}) async {
    final revision = ++_revision;
    if (reconnect) {
      _owner = null;
      _dataOwner = null;
      _data = {};
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final status = await _request('status');
      if (!mounted || revision != _revision) return;
      _capabilities = (status['capabilities'] as List? ?? [])
          .whereType<String>()
          .toSet();
      if (!_capabilities.contains('profile')) {
        throw StateError('此 Na 服务尚未提供个人助理管理能力，请升级开发服务。');
      }
      final tab = _tab;
      Map<String, dynamic> data;
      switch (tab) {
        case 0:
          final result = await Future.wait([
            _request('notifications.list'),
            _request('actions.list'),
          ]);
          data = {...result[0], ...result[1]};
        case 1:
          final settingsResponse = await _request('composio.settings.read');
          final settings = _map(settingsResponse['settings']);
          Map<String, dynamic> inventory = {};
          String? inventoryError;
          try {
            inventory = await _request('composio.read');
          } catch (_) {
            // Recheck the Na owner separately: provider/key trouble should not
            // hide the very settings needed to repair that connection.
            await _request('status');
            inventoryError = 'Composio 账号列表暂不可用。连接服务设置仍可编辑，请检查密钥、配置或网络后刷新。';
          }
          data = {
            'composio': inventory,
            'composioSettings': settings,
            'inventoryError': ?inventoryError,
          };
        case 2:
          data =
              _search.text.trim().isNotEmpty &&
                  _capabilities.contains('memory_search')
              ? await _request(
                  'memories.search',
                  payload: {'query': _search.text.trim(), 'limit': 20},
                )
              : await _request('memories.list');
        case 3:
          final result = await Future.wait([
            _request('scenarios.list'),
            _request('routines.list'),
          ]);
          data = {...result[0], ...result[1]};
        default:
          data = await _request('profile.read');
      }
      if (mounted && revision == _revision) {
        setState(() {
          _data = data;
          _dataOwner = _owner;
        });
      }
    } catch (error) {
      if (mounted && revision == _revision) {
        setState(() {
          _error = _message(error);
          _data = {};
          _dataOwner = null;
        });
      }
    } finally {
      if (mounted && revision == _revision) setState(() => _loading = false);
    }
  }

  String _message(Object error) {
    if (error is StateError) return error.message.toString();
    // This page calls only the bounded native Na bridge, whose public errors
    // already remove credentials and access-bearing URLs.
    if (error is PlatformException &&
        error.code == 'AGENT_RUNTIME_CALL_FAILED' &&
        error.message?.trim().isNotEmpty == true) {
      return error.message!.trim();
    }
    return formatAgentRuntimeErrorForUser(error, fallback: '操作未完成，请刷新状态后重试。');
  }

  Future<void> _mutate(
    String operation, {
    String? id,
    Map<String, dynamic> payload = const {},
  }) async {
    if (_disabled) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final result = await _request(operation, id: id, payload: payload);
      if (!mounted) return;
      final action = _map(result['action']);
      final message =
          result['warning']?.toString() ??
          (action['status'] == 'unknown' ? '操作结果待核实，请先到对应应用检查，勿重复执行。' : '已更新。');
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(message)));
      await _load();
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<bool> _confirm(
    String title,
    Widget content, {
    String confirm = '确认',
  }) async {
    final displayedOwner = _dataOwner;
    final confirmed =
        await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: Text(title),
            scrollable: true,
            content: content,
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('取消'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: Text(confirm),
              ),
            ],
          ),
        ) ==
        true;
    if (!mounted) return false;
    if (confirmed &&
        (displayedOwner == null ||
            displayedOwner != _owner ||
            displayedOwner != _dataOwner)) {
      setState(() => _error = 'Na 连接已更改，请重新载入后审核。');
      return false;
    }
    return confirmed;
  }

  Future<Map<String, String>?> _edit(
    String title,
    List<({String key, String label, String value, int lines})> fields,
  ) async {
    final displayedOwner = _dataOwner;
    final result = await showDialog<Map<String, String>>(
      context: context,
      builder: (context) => _NaPersonalEditDialog(title: title, fields: fields),
    );
    if (!mounted) return null;
    if (result != null &&
        (displayedOwner == null ||
            displayedOwner != _owner ||
            displayedOwner != _dataOwner)) {
      setState(() => _error = 'Na 连接已更改，请重新载入后编辑。');
      return null;
    }
    return result;
  }

  Future<void> _openEvents() async {
    if (_disabled || _openingCatalog) return;
    setState(() => _openingCatalog = true);
    try {
      await Navigator.of(context).push<void>(
        OmniPageRoute<void>(
          builder: (_) =>
              NaComposioEventsPage(request: _request, ownerKey: _owner),
        ),
      );
    } finally {
      if (mounted) {
        setState(() => _openingCatalog = false);
        await _load();
      }
    }
  }

  Future<void> _openCatalog() async {
    if (_disabled || _openingCatalog) return;
    setState(() => _openingCatalog = true);
    try {
      await Navigator.of(context).push<void>(
        OmniPageRoute<void>(
          builder: (_) => NaComposioCatalogPage(
            request: (operation, payload) =>
                _request(operation, payload: payload),
          ),
        ),
      );
    } finally {
      if (mounted) {
        setState(() => _openingCatalog = false);
        await _load();
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: context.omniPalette.pageBackground,
    appBar: CommonAppBar(
      titleWidget: _connectionConfigurationAvailable
          ? TextButton(
              key: const Key('na-connection-title'),
              onPressed: _openingConnection || _busy ? null : _openConnection,
              style: TextButton.styleFrom(
                foregroundColor: context.omniPalette.textPrimary,
              ),
              child: const Text(
                'Na',
                style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
              ),
            )
          : const Text(
              'Na',
              key: Key('na-personal-title'),
              style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
            ),
      primary: true,
      actions: [
        Padding(
          padding: const EdgeInsets.only(right: 12),
          child: OmniCircleButton(
            key: const Key('na-personal-refresh'),
            tooltip: '刷新',
            onPressed: _loading || _busy ? null : () => _load(),
            icon: LucideIcons.refreshCw,
          ),
        ),
      ],
    ),
    body: SafeArea(
      top: false,
      child: OmniContentWidth(
        child: Column(
          children: [
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Row(
                children: [
                  for (var index = 0; index < _labels.length; index++)
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: ChoiceChip(
                        key: Key('na-personal-tab-$index'),
                        label: Text(_labels[index]),
                        showCheckmark: false,
                        selected: _tab == index,
                        onSelected: _busy
                            ? null
                            : (_) {
                                setState(() {
                                  _tab = index;
                                  _data = {};
                                });
                                unawaited(_load());
                              },
                      ),
                    ),
                ],
              ),
            ),
            if (_loading || _busy) const LinearProgressIndicator(minHeight: 2),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 8, 18, 0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _error!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                    TextButton(
                      onPressed: _busy ? null : () => _load(reconnect: true),
                      child: const Text('重新载入连接'),
                    ),
                  ],
                ),
              ),
            Expanded(
              child: RefreshIndicator(
                onRefresh: _busy ? () async {} : _load,
                child: ListView(
                  key: ValueKey(_tab),
                  padding: const EdgeInsets.fromLTRB(18, 14, 18, 32),
                  physics: const AlwaysScrollableScrollPhysics(),
                  children: switch (_tab) {
                    0 => _inbox(),
                    1 => _connections(),
                    2 => _memories(),
                    3 => _responsibilities(),
                    _ => _profile(),
                  },
                ),
              ),
            ),
          ],
        ),
      ),
    ),
  );

  Widget _heading(String title, {Widget? trailing}) => Padding(
    padding: const EdgeInsets.only(top: 12, bottom: 10),
    child: Row(
      children: [
        Expanded(
          child: Text(title, style: Theme.of(context).textTheme.titleMedium),
        ),
        ?trailing,
      ],
    ),
  );
  Widget _note(String text) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 8),
    child: Text(
      text,
      style: TextStyle(color: context.omniPalette.textSecondary, height: 1.5),
    ),
  );
  Widget _record(Widget child) => Padding(
    padding: const EdgeInsets.only(bottom: 14),
    child: OmniSettingsCard(child: child),
  );
  Widget _details(String label, dynamic value) => ExpansionTile(
    tilePadding: EdgeInsets.zero,
    childrenPadding: const EdgeInsets.only(bottom: 12),
    title: Text(label),
    children: [
      Align(
        alignment: Alignment.centerLeft,
        child: SelectableText(
          _json(value),
          style: const TextStyle(
            fontFamily: 'monospace',
            fontSize: 12,
            height: 1.5,
          ),
        ),
      ),
    ],
  );

  List<Widget> _inbox() {
    final notices = _list(_data['notifications']),
        actions = _list(_data['actions']);
    return [
      NaNotificationSettings(disabled: _busy || _openingConnection),
      const SizedBox(height: 12),
      _heading(
        '需要你确认',
        trailing: TextButton(
          onPressed: _disabled ? null : () => _mutate('notifications.readAll'),
          child: const Text('全部已读'),
        ),
      ),
      if (actions.isEmpty && !_loading) _note('暂无待确认操作。新的提醒和外部操作会保存在这里。'),
      for (final action in actions) _actionCard(action),
      _heading('跟进消息'),
      if (notices.isEmpty && !_loading) _note('暂无跟进消息。'),
      for (final notice in notices)
        _record(
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                notice['title']?.toString() ?? '跟进消息',
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 8),
              SelectableText(notice['body']?.toString() ?? ''),
              _note(
                notice['deliveredAt'] == null
                    ? '等待提醒时段'
                    : notice['createdAt']?.toString() ?? '',
              ),
              if (notice['readAt'] == null)
                TextButton(
                  onPressed: _disabled
                      ? null
                      : () => _mutate(
                          'notifications.read',
                          id: notice['id'].toString(),
                          payload: {'read': true},
                        ),
                  child: const Text('标为已读'),
                ),
            ],
          ),
        ),
    ];
  }

  Widget _actionCard(Map<String, dynamic> action) {
    final account = _map(action['account']),
        connector = _map(action['connector']);
    final status = action['status']?.toString() ?? '';
    final statuses = {
      'pending': '等待确认',
      'executing': '执行中',
      'completed': '已完成',
      'unknown': '结果待核实',
      'failed': '执行失败',
      'rejected': '已拒绝',
    };
    final content = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          action['operation']?.toString() ?? '',
          style: Theme.of(context).textTheme.titleSmall,
        ),
        const SizedBox(height: 10),
        Text(
          '执行账号：${account['label'] ?? account['email'] ?? account['alias'] ?? ''}',
        ),
        if (account['id'] != null) SelectableText(account['id'].toString()),
        if (connector['version'] != null) Text('工具版本：${connector['version']}'),
        const SizedBox(height: 12),
        SelectableText(_json(action['args'] ?? {})),
      ],
    );
    return _record(
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            action['summary']?.toString() ??
                action['operation']?.toString() ??
                '',
            style: Theme.of(context).textTheme.titleSmall,
          ),
          _note(statuses[status] ?? status),
          Text(
            '执行账号：${account['label'] ?? account['email'] ?? account['alias'] ?? account['id'] ?? '—'}',
          ),
          if (account['id'] != null) SelectableText(account['id'].toString()),
          _details('完整操作内容', {
            'operation': action['operation'],
            if (connector['version'] != null) 'version': connector['version'],
            'args': action['args'] ?? {},
          }),
          if (action['result'] != null) _details('执行结果', action['result']),
          if (_map(_map(action['result'])['overflow']).isNotEmpty)
            NaConnectorResultViewer(
              overflow: _map(_map(action['result'])['overflow']),
              readPage: (id, payload) =>
                  _request('connectors.resultPage', id: id, payload: payload),
            ),
          if (_capabilities.contains('connector_grants'))
            NaRepeatGrantButton(
              action: action,
              request: _request,
              onChanged: _load,
              disabled: _busy,
              ownerKey: _owner,
            ),
          if (action['error'] != null) _note(action['error'].toString()),
          if (status == 'unknown') _note('请先到对应应用核实结果，避免重复执行。'),
          if (action['unavailableReason'] != null)
            _note(action['unavailableReason'].toString()),
          if (status == 'pending')
            Wrap(
              spacing: 12,
              children: [
                FilledButton(
                  onPressed: _disabled || action['unavailableReason'] != null
                      ? null
                      : () async {
                          if (await _confirm(
                                '确认执行这一次操作',
                                content,
                                confirm: '确认执行',
                              ) &&
                              mounted) {
                            await _mutate(
                              'actions.approve',
                              id: action['id'].toString(),
                            );
                          }
                        },
                  child: const Text('审核并执行'),
                ),
                TextButton(
                  onPressed: _disabled
                      ? null
                      : () => _mutate(
                          'actions.reject',
                          id: action['id'].toString(),
                        ),
                  child: const Text('拒绝'),
                ),
              ],
            ),
        ],
      ),
    );
  }

  Future<Map<String, dynamic>?> _saveComposioSettings(
    Map<String, dynamic> payload,
  ) async {
    if (_disabled) return null;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final response = await _request(
        'composio.settings.update',
        payload: payload,
      );
      final saved = _map(response['settings']);
      if (!mounted) return null;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('连接服务设置已保存到 Na 服务端。')));
      await _load();
      return saved;
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
      return null;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  List<Widget> _connections() {
    final composio = _map(_data['composio']);
    final settings = _map(_data['composioSettings']);
    final accounts = _list(composio['accounts']);
    return [
      _heading(
        '应用连接',
        trailing: TextButton.icon(
          key: const Key('na-composio-browse'),
          onPressed: _disabled || _openingCatalog ? null : _openCatalog,
          icon: const Icon(LucideIcons.layoutGrid, size: 16),
          label: const Text('浏览所有应用'),
        ),
      ),
      _note('浏览 Composio 全部应用，在服务端管理连接和账号。'),
      if (_capabilities.contains('composio_events'))
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
            key: const Key('na-composio-events'),
            onPressed: _disabled || _openingCatalog ? null : _openEvents,
            icon: const Icon(LucideIcons.zap, size: 16),
            label: const Text('管理应用事件'),
          ),
        ),
      if (_data['inventoryError'] != null)
        _note(_data['inventoryError'].toString()),
      if (settings.isNotEmpty)
        NaComposioSettings(
          key: ValueKey('composio-settings-$_dataOwner'),
          settings: settings,
          busy: _disabled,
          onSave: _saveComposioSettings,
        ),
      if (composio['configured'] != true)
        _note(
          settings['enabled'] == false
              ? '连接服务已关闭。修改服务设置并保存后即可启用。'
              : '请先在连接服务设置中保存项目密钥和需要开放的应用。',
        )
      else ...[
        _note('应用操作默认先由你审核。你也可为特定职责明确保存相同操作的重复授权，随时撤销。'),
        for (final account in accounts)
          _record(
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  account['label']?.toString() ??
                      account['alias']?.toString() ??
                      account['toolkit'].toString(),
                  style: Theme.of(context).textTheme.titleSmall,
                ),
                _note(
                  '${account['toolkit']} · ${_connectionStatus(account['status'])}',
                ),
                SelectableText(account['id'].toString()),
                TextButton(
                  onPressed: _disabled
                      ? null
                      : () async {
                          if (await _confirm(
                                '断开此账号',
                                Text(
                                  '${account['toolkit']}\n${account['label'] ?? account['alias'] ?? ''}\n${account['id']}',
                                ),
                              ) &&
                              mounted) {
                            await _mutate(
                              'composio.disconnect',
                              payload: {
                                'toolkit': account['toolkit'],
                                'accountId': account['id'],
                              },
                            );
                          }
                        },
                  child: const Text('断开此账号'),
                ),
              ],
            ),
          ),
        if (accounts.isEmpty) _note('尚无连接账号。完成浏览器授权后点击右上角刷新。'),
      ],
    ];
  }

  String _connectionStatus(dynamic status) =>
      const {
        'ACTIVE': '已连接',
        'DISABLED': '已停用',
        'EXPIRED': '授权已过期',
        'INITIATED': '等待授权',
        'INITIALIZING': '准备授权',
        'FAILED': '连接失败',
        'REVOKED': '已撤销',
      }[status] ??
      status?.toString() ??
      '未知状态';

  Future<void> _editMemory([Map<String, dynamic>? memory]) async {
    final result = await _edit(memory == null ? '新增记忆' : '编辑记忆', [
      (
        key: 'key',
        label: '名称',
        value: memory?['key']?.toString() ?? '',
        lines: 1,
      ),
      (
        key: 'value',
        label: '记忆内容',
        value: memory?['value']?.toString() ?? '',
        lines: 3,
      ),
    ]);
    if (result != null && mounted) {
      await _mutate(
        memory == null ? 'memories.save' : 'memories.update',
        id: memory?['id']?.toString(),
        payload: result,
      );
    }
  }

  List<Widget> _memories() => [
    _heading(
      '长期记忆',
      trailing: TextButton.icon(
        onPressed: _disabled ? null : _editMemory,
        icon: const Icon(LucideIcons.plus, size: 16),
        label: const Text('新增'),
      ),
    ),
    _note('这里保存你明确要求记住的资料。每轮上下文有预算，Na 可按需检索额外记忆。'),
    TextField(
      key: const Key('na-memory-search'),
      controller: _search,
      decoration: InputDecoration(
        labelText: '搜索记忆',
        hintText: '输入中文或英文关键词',
        suffixIcon: IconButton(
          onPressed: _disabled ? null : _load,
          icon: const Icon(LucideIcons.search, size: 18),
        ),
      ),
      onSubmitted: _disabled ? null : (_) => _load(),
    ),
    const SizedBox(height: 16),
    if (_list(_data['memories']).isEmpty && !_loading) _note('没有匹配的记忆。'),
    for (final memory in _list(_data['memories']))
      _record(
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              memory['key'].toString(),
              style: Theme.of(context).textTheme.titleSmall,
            ),
            const SizedBox(height: 8),
            SelectableText(memory['value'].toString()),
            if (memory['truncated'] == true) _note('搜索结果已截断；清空关键词后查看完整记忆。'),
            Wrap(
              spacing: 12,
              children: [
                TextButton(
                  onPressed: _disabled || memory['truncated'] == true
                      ? null
                      : () => _editMemory(memory),
                  child: const Text('编辑'),
                ),
                TextButton(
                  onPressed: _disabled
                      ? null
                      : () async {
                          if (await _confirm(
                                '忘记这条记忆',
                                Text(memory['key'].toString()),
                              ) &&
                              mounted) {
                            await _mutate(
                              'memories.delete',
                              id: memory['id'].toString(),
                            );
                          }
                        },
                  child: const Text('忘记'),
                ),
              ],
            ),
          ],
        ),
      ),
  ];

  List<Widget> _responsibilities() => [
    if (_capabilities.contains('connector_grants'))
      NaConnectorGrantList(
        request: _request,
        disabled: _busy,
        ownerKey: _owner,
      ),
    _heading('主动跟进场景'),
    for (final scenario in _list(_data['scenarios']))
      _record(
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              scenario['title'].toString(),
              style: Theme.of(context).textTheme.titleSmall,
            ),
            _note(scenario['description']?.toString() ?? ''),
            if (scenario['available'] != true)
              _note(
                scenario['unavailableReason']?.toString() ?? '请先完成所需资料和应用连接。',
              ),
            FilledButton(
              onPressed:
                  _disabled ||
                      scenario['available'] != true ||
                      scenario['enabled'] == true
                  ? null
                  : () => _mutate(
                      'scenarios.activate',
                      id: scenario['id'].toString(),
                    ),
              child: Text(scenario['enabled'] == true ? '已启用' : '启用此职责'),
            ),
          ],
        ),
      ),
    _heading(
      '持续任务',
      trailing: TextButton.icon(
        onPressed: _disabled ? null : () => _editRoutine(),
        icon: const Icon(LucideIcons.plus, size: 16),
        label: const Text('新增'),
      ),
    ),
    for (final routine in _list(_data['routines']))
      _record(
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            OmniSettingsTile(
              padding: EdgeInsets.zero,
              title: routine['title'].toString(),
              translateText: false,
              trailing: OmniSettingsSwitch(
                value: routine['enabled'] == true,
                enabled: !_disabled,
                onToggle: (enabled) => _mutate(
                  'routines.update',
                  id: routine['id'].toString(),
                  payload: {'enabled': enabled},
                ),
              ),
            ),
            _note(routine['prompt']?.toString() ?? ''),
            Text(naRoutineStatus(routine)),
            Text(
              '下次执行：${routine['nextRunAt'] ?? (routine['trigger'] == 'event' ? '等待应用事件' : '—')}',
            ),
            if (routine['nextAllowedAt'] != null ||
                routine['nextCheckAllowedAt'] != null)
              _note(
                '下次允许重试：${routine['nextAllowedAt'] ?? routine['nextCheckAllowedAt']}',
              ),
            if (routine['lastCheckFailureCause'] != null)
              _note('读取失败原因：${routine['lastCheckFailureCause']}'),
            Wrap(
              spacing: 8,
              children: [
                TextButton(
                  onPressed: _disabled
                      ? null
                      : () => _mutate(
                          'routines.run',
                          id: routine['id'].toString(),
                        ),
                  child: const Text('检查一次'),
                ),
                TextButton(
                  onPressed: _disabled ? null : () => _editRoutine(routine),
                  child: const Text('编辑'),
                ),
                TextButton(
                  onPressed: _disabled
                      ? null
                      : () async {
                          if (await _confirm(
                                '删除职责',
                                Text(routine['title'].toString()),
                              ) &&
                              mounted) {
                            await _mutate(
                              'routines.delete',
                              id: routine['id'].toString(),
                            );
                          }
                        },
                  child: const Text('删除'),
                ),
              ],
            ),
          ],
        ),
      ),
    if (_list(_data['routines']).isEmpty && !_loading)
      _note('暂无持续任务。检查结果有变化时才应提醒你。'),
  ];

  Future<void> _editRoutine([Map<String, dynamic>? routine]) async {
    final owner = _owner, revision = _revision;
    try {
      final profile = await _request('profile.read');
      if (!mounted || owner != _owner || revision != _revision) return;
      final user = _map(_map(profile['profile'])['user']);
      final timezone = user['timezone']?.toString();
      if (timezone == null || timezone.isEmpty) {
        throw StateError('请先在资料中设置计划时区。');
      }
      final result = await showNaRoutineEditor(
        context,
        routine: routine,
        timezone: timezone,
        isCurrent: () => mounted && owner == _owner && revision == _revision,
      );
      if (result == null ||
          !mounted ||
          owner != _owner ||
          revision != _revision) {
        return;
      }
      await _mutate(
        routine == null ? 'routines.create' : 'routines.update',
        id: routine?['id']?.toString(),
        payload: result,
      );
    } catch (error) {
      if (mounted && owner == _owner && revision == _revision) {
        setState(
          () => _error = error is PlatformException
              ? (error.message ?? '无法打开职责编辑，请重试。')
              : error.toString(),
        );
      }
    }
  }

  List<Widget> _profile() {
    final profile = _map(_data['profile']),
        user = _map(profile['user']),
        agent = _map(profile['agent']),
        proactivity = _map(profile['proactivity']);
    return [
      _heading(
        '你与 Na',
        trailing: TextButton(
          onPressed: _disabled ? null : () => _editProfile(profile),
          child: const Text('编辑资料'),
        ),
      ),
      _record(
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              user['name']?.toString().isNotEmpty == true
                  ? user['name'].toString()
                  : '你的资料',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            _note('时区：${user['timezone'] ?? '—'}'),
            _note(user['about']?.toString() ?? ''),
            _note(user['goals']?.toString() ?? ''),
            const Divider(),
            Text('${agent['name'] ?? 'Na'} · ${agent['role'] ?? '个人助理'}'),
            _note(agent['tone']?.toString() ?? ''),
            _note(agent['instructions']?.toString() ?? ''),
          ],
        ),
      ),
      _heading('主动提醒'),
      OmniSettingsTile(
        padding: EdgeInsets.zero,
        title: '允许主动跟进',
        subtitle: '关闭后暂停自动检查和提醒交付。',
        translateText: false,
        trailing: OmniSettingsSwitch(
          value: proactivity['enabled'] == true,
          enabled: !_disabled,
          onToggle: (value) => _mutate(
            'profile.update',
            payload: {
              'proactivity': {'enabled': value},
            },
          ),
        ),
      ),
      _note(
        '安静时段：${proactivity['quietStart'] ?? '—'} — ${proactivity['quietEnd'] ?? '—'}\n提醒最小间隔：${proactivity['minIntervalMinutes'] ?? '—'} 分钟',
      ),
      TextButton(
        onPressed: _disabled
            ? null
            : () async {
                final result = await _edit('提醒时段', [
                  (
                    key: 'quietStart',
                    label: '安静开始（HH:mm）',
                    value: proactivity['quietStart']?.toString() ?? '22:00',
                    lines: 1,
                  ),
                  (
                    key: 'quietEnd',
                    label: '安静结束（HH:mm）',
                    value: proactivity['quietEnd']?.toString() ?? '08:00',
                    lines: 1,
                  ),
                  (
                    key: 'minIntervalMinutes',
                    label: '提醒最小间隔（分钟）',
                    value:
                        proactivity['minIntervalMinutes']?.toString() ?? '15',
                    lines: 1,
                  ),
                ]);
                if (result != null && mounted) {
                  final minutes = num.tryParse(result['minIntervalMinutes']!);
                  if (minutes == null) {
                    setState(() => _error = '提醒间隔需为数字。');
                    return;
                  }
                  await _mutate(
                    'profile.update',
                    payload: {
                      'proactivity': {...result, 'minIntervalMinutes': minutes},
                    },
                  );
                }
              },
        child: const Text('调整提醒时段'),
      ),
    ];
  }

  Future<void> _editProfile(Map<String, dynamic> profile) async {
    final user = _map(profile['user']), agent = _map(profile['agent']);
    final result = await _edit('编辑个人助理资料', [
      for (final field in [
        ('name', '你的称呼'),
        ('timezone', 'IANA 时区'),
        ('locale', '语言（zh-CN / en）'),
        ('about', '关于你'),
        ('goals', '当前目标'),
      ])
        (
          key: 'user.${field.$1}',
          label: field.$2,
          value: user[field.$1]?.toString() ?? '',
          lines: field.$1 == 'about' || field.$1 == 'goals' ? 3 : 1,
        ),
      for (final field in [
        ('name', '助理名称'),
        ('role', '助理职责'),
        ('tone', '沟通语气'),
        ('instructions', '偏好说明'),
      ])
        (
          key: 'agent.${field.$1}',
          label: field.$2,
          value: agent[field.$1]?.toString() ?? '',
          lines: field.$1 == 'instructions' ? 3 : 1,
        ),
    ]);
    if (result != null && mounted) {
      await _mutate(
        'profile.update',
        payload: {
          'user': {
            for (final entry in result.entries.where(
              (entry) => entry.key.startsWith('user.'),
            ))
              entry.key.substring(5): entry.value,
          },
          'agent': {
            for (final entry in result.entries.where(
              (entry) => entry.key.startsWith('agent.'),
            ))
              entry.key.substring(6): entry.value,
          },
        },
      );
    }
  }
}

class _NaPersonalEditDialog extends StatefulWidget {
  const _NaPersonalEditDialog({required this.title, required this.fields});
  final String title;
  final List<({String key, String label, String value, int lines})> fields;
  @override
  State<_NaPersonalEditDialog> createState() => _NaPersonalEditDialogState();
}

class _NaPersonalEditDialogState extends State<_NaPersonalEditDialog> {
  late final Map<String, TextEditingController> _controllers;
  @override
  void initState() {
    super.initState();
    _controllers = {
      for (final field in widget.fields)
        field.key: TextEditingController(text: field.value),
    };
  }

  @override
  void dispose() {
    for (final controller in _controllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.title),
    scrollable: true,
    content: SizedBox(
      width: 460,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final field in widget.fields)
            Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: TextField(
                controller: _controllers[field.key],
                minLines: field.lines,
                maxLines: field.lines + 3,
                maxLength: field.key == 'value'
                    ? 8000
                    : field.key == 'prompt'
                    ? 16000
                    : 4000,
                decoration: InputDecoration(
                  labelText: field.label,
                  counterText: '',
                ),
              ),
            ),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        onPressed: () => Navigator.pop(context, {
          for (final field in widget.fields)
            field.key: _controllers[field.key]!.text.trim(),
        }),
        child: const Text('保存'),
      ),
    ],
  );
}
