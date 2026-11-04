import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/omni_circle_button.dart';

typedef NaComposioRequest = Future<Map<String, dynamic>> Function(
  String operation,
  Map<String, dynamic> payload,
);

/// A view of server-owned catalog and connection settings. The callback keeps
/// the original Na owner; opening this route creates no Agent/session runtime.
class NaComposioCatalogPage extends StatefulWidget {
  const NaComposioCatalogPage({super.key, required this.request});
  final NaComposioRequest request;

  @override
  State<NaComposioCatalogPage> createState() => _NaComposioCatalogPageState();
}

class _NaComposioCatalogPageState extends State<NaComposioCatalogPage>
    with WidgetsBindingObserver {
  final _search = TextEditingController();
  Timer? _debounce;
  Map<String, dynamic> _settings = {};
  List<Map<String, dynamic>> _cards = [], _accounts = [];
  String _category = '', _source = '';
  String? _nextCursor, _error, _inventoryError, _busy;
  int _generation = 0, _total = 0;
  bool _loading = true, _ready = false, _complete = false, _choosing = false;

  Map<String, dynamic> _map(dynamic value) => value is Map
      ? value.map((key, value) => MapEntry(key.toString(), value))
      : {};
  List<Map<String, dynamic>> _list(dynamic value) =>
      value is List ? value.whereType<Map>().map(_map).toList() : [];
  Set<String> get _enabled =>
      (_settings['toolkits'] as List? ?? []).whereType<String>().toSet();
  bool get _disabled =>
      _busy != null ||
      !_ready ||
      _loading ||
      _settings['apiKeyConfigured'] != true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_reload());
  }

  @override
  void dispose() {
    ++_generation;
    WidgetsBinding.instance.removeObserver(this);
    _debounce?.cancel();
    _search.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _busy == null) {
      unawaited(_reload());
    }
  }

  String _message(Object error) {
    if (error is StateError) return error.message.toString();
    if (error is PlatformException &&
        error.code == 'AGENT_RUNTIME_CALL_FAILED' &&
        error.message?.trim().isNotEmpty == true) {
      return error.message!.trim();
    }
    return formatAgentRuntimeErrorForUser(error, fallback: '暂时无法完成操作，请刷新后重试。');
  }

  Future<void> _reload() async {
    if (_busy != null) return;
    _debounce?.cancel();
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _ready = false;
      _error = null;
    });
    try {
      final settings = _map(
        (await widget.request('composio.settings.read', {}))['settings'],
      );
      List<Map<String, dynamic>> accounts = [];
      String? inventoryError;
      try {
        accounts = _list(
          (await widget.request('composio.read', {}))['accounts'],
        );
      } catch (_) {
        // Provider inventory failure is separate from a changed Na owner.
        await widget.request('status', {});
        inventoryError = '账号状态暂不可用，仍可浏览应用目录。';
      }
      final response = await widget.request('composio.catalog', {
        'query': _search.text.trim(),
        'limit': 40,
        if (_category.isNotEmpty) 'category': _category,
      });
      if (!mounted || generation != _generation) return;
      setState(() {
        _settings = settings;
        _accounts = accounts;
        _inventoryError = inventoryError;
        _ready = settings.isNotEmpty;
        _adoptPage(response, append: false);
      });
    } catch (error) {
      if (mounted && generation == _generation) {
        setState(() {
          _error = _message(error);
          _ready = false;
        });
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  void _adoptPage(Map<String, dynamic> response, {required bool append}) {
    final values = _list(response['cards']);
    final records = <String, Map<String, dynamic>>{
      if (append)
        for (final value in _cards) value['slug'].toString(): value,
      for (final value in values) value['slug'].toString(): value,
    };
    _cards = records.values.toList();
    _nextCursor = response['nextCursor'] as String?;
    _total = (response['total'] as num?)?.toInt() ?? _cards.length;
    _source = response['source']?.toString() ?? '';
    _complete = response['complete'] == true;
  }

  void _searchChanged(String _) {
    _debounce?.cancel();
    ++_generation; // Invalidate an older search before the debounce fires.
    setState(() => _loading = true);
    _debounce = Timer(const Duration(milliseconds: 300), () {
      unawaited(_loadPage());
    });
  }

  Future<void> _loadPage({bool append = false}) async {
    if (_busy != null || (append && (_loading || _nextCursor == null))) return;
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final response = await widget.request('composio.catalog', {
        'query': _search.text.trim(),
        'limit': 40,
        if (_category.isNotEmpty) 'category': _category,
        if (append) 'cursor': _nextCursor,
      });
      if (!mounted || generation != _generation) return;
      setState(() => _adoptPage(response, append: append));
    } catch (error) {
      if (mounted && generation == _generation) {
        setState(() {
          _error = _message(error);
          _ready = false;
        });
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _manage(Map<String, dynamic> card) async {
    if (_disabled) return;
    final slug = card['slug'].toString();
    setState(() {
      _busy = slug;
      _error = null;
    });
    try {
      await widget.request('status', {});
      final configs = card['noAuth'] == true
          ? <String, dynamic>{}
          : await widget.request('composio.authConfigs', {'toolkit': slug});
      if (!mounted) return;
      setState(() => _choosing = true);
      final choice = await showDialog<_ConnectionChoice>(
        context: context,
        builder: (context) => _ConnectionDialog(
          card: card,
          enabled: _enabled.contains(slug),
          accounts: _accounts
              .where((value) => value['toolkit'] == slug)
              .toList(),
          configs: configs,
          selectedConfig: _map(_settings['authConfigs'])[slug]?.toString(),
        ),
      );
      if (mounted) setState(() => _choosing = false);
      if (!mounted || choice == null) return;
      // Recheck the captured owner after the human has inspected the dialog.
      await widget.request('status', {});
      if (!mounted) return;
      if (choice.disconnectAccount != null) {
        final result = await widget.request('composio.disconnect', {
          'toolkit': slug,
          'accountId': choice.disconnectAccount,
        });
        if (mounted) _notice(result['warning']?.toString() ?? '账号已断开。');
        return;
      }
      final enabled = _enabled;
      final authConfigs = _map(_settings['authConfigs']);
      if (choice.disable) {
        enabled.remove(slug);
        authConfigs.remove(slug);
      } else {
        enabled.add(slug);
        if (choice.config == null) {
          authConfigs.remove(slug);
        } else {
          authConfigs[slug] = choice.config;
        }
      }
      final oldConfig = _map(_settings['authConfigs'])[slug];
      if (choice.disable ||
          !_enabled.contains(slug) ||
          oldConfig != choice.config ||
          _settings['enabled'] != true) {
        final response = await widget.request('composio.settings.update', {
          'expectedRevision': _settings['revision'],
          'enabled': choice.disable ? _settings['enabled'] == true : true,
          'toolkits': enabled.toList()..sort(),
          'authConfigs': authConfigs,
        });
        if (!mounted) return;
        _settings = _map(response['settings']);
      }
      if (choice.disable) {
        _notice('已停止 Na 使用此应用，已有账号授权未被移除。');
        return;
      }
      if (card['noAuth'] == true) {
        _notice('应用已启用，无需账号授权。');
        return;
      }
      final result = await widget.request('composio.authorize', {
        'toolkit': slug,
        if (choice.alias.isNotEmpty) 'alias': choice.alias,
      });
      if (!mounted) return;
      if (result['noAuth'] == true) {
        _notice('此应用无需账号授权。');
        return;
      }
      final url = Uri.tryParse(result['url']?.toString() ?? '');
      if (url == null ||
          url.scheme != 'https' ||
          !{'connect.composio.dev', 'app.composio.dev'}.contains(url.host) ||
          url.hasPort ||
          url.userInfo.isNotEmpty ||
          url.hasQuery ||
          url.hasFragment ||
          !RegExp(r'^/link/[A-Za-z0-9_-]+/?$').hasMatch(url.path)) {
        throw StateError('服务返回了不受支持的授权地址。');
      }
      if (!await launchUrl(url, mode: LaunchMode.externalApplication)) {
        throw StateError('无法打开授权页，请稍后重试。');
      }
      if (mounted) _notice('完成授权后返回，账号状态会自动刷新。');
    } catch (error) {
      if (mounted) {
        setState(() {
          _error = _message(error);
          _ready = false;
        });
      }
    } finally {
      if (mounted) {
        setState(() {
          _busy = null;
          _choosing = false;
        });
        if (_ready) await _reload();
      }
    }
  }

  void _notice(String text) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  @override
  Widget build(BuildContext context) {
    final categories = <String>{
      if (_category.isNotEmpty) _category,
      for (final card in _cards)
        ...(card['categories'] as List? ?? []).whereType<String>(),
    }.toList()..sort();
    return Scaffold(
      backgroundColor: context.omniPalette.pageBackground,
      appBar: CommonAppBar(
        primary: true,
        title: '所有应用',
        onBackPressed: () => Navigator.of(context).pop(),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 12),
            child: OmniCircleButton(
              tooltip: '刷新应用目录',
              onPressed: _busy != null || _loading ? null : _reload,
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
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 10, 18, 8),
                child: TextField(
                  key: const Key('na-composio-catalog-search'),
                  controller: _search,
                  enabled: _busy == null,
                  onChanged: _searchChanged,
                  decoration: const InputDecoration(
                    hintText: '搜索应用名称或用途',
                    prefixIcon: Icon(LucideIcons.search, size: 19),
                  ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 18),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        _source == 'curated' ? '常用应用 · 目录暂不可用' : '$_total 个应用',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ),
                    DropdownButton<String>(
                      key: const Key('na-composio-catalog-category'),
                      value: _category,
                      underline: const SizedBox.shrink(),
                      items: [
                        const DropdownMenuItem(value: '', child: Text('全部分类')),
                        for (final category in categories)
                          DropdownMenuItem(
                            value: category,
                            child: Text(category),
                          ),
                      ],
                      onChanged: _busy != null
                          ? null
                          : (value) {
                              _debounce?.cancel();
                              setState(() => _category = value ?? '');
                              unawaited(_loadPage());
                            },
                    ),
                  ],
                ),
              ),
              if (_loading || _busy != null && !_choosing)
                const LinearProgressIndicator(minHeight: 2),
              if (_error != null ||
                  _inventoryError != null ||
                  (!_complete && !_loading))
                Padding(
                  padding: const EdgeInsets.fromLTRB(18, 8, 18, 8),
                  child: Text(
                    _error ?? _inventoryError ?? '目录尚未完整加载，可稍后刷新。',
                    style: TextStyle(
                      color: _error == null
                          ? context.omniPalette.textSecondary
                          : Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
              Expanded(
                child: RefreshIndicator(
                  onRefresh: _reload,
                  child: ListView.separated(
                    key: const Key('na-composio-catalog-list'),
                    padding: const EdgeInsets.fromLTRB(10, 8, 10, 24),
                    physics: const AlwaysScrollableScrollPhysics(),
                    itemCount: _cards.length + 1,
                    separatorBuilder: (_, _) =>
                        const Divider(indent: 76, endIndent: 8, height: 1),
                    itemBuilder: (context, index) {
                      if (index == _cards.length) {
                        return Padding(
                          padding: const EdgeInsets.all(18),
                          child: _nextCursor != null
                              ? TextButton(
                                  onPressed: _loading || _busy != null
                                      ? null
                                      : () => _loadPage(append: true),
                                  child: const Text('加载更多应用'),
                                )
                              : Text(
                                  _cards.isEmpty && !_loading
                                      ? '没有找到匹配的应用。'
                                      : '启用应用后再连接账号；每次操作仍遵循 Na 的授权设置。',
                                  style: Theme.of(context).textTheme.bodySmall,
                                ),
                        );
                      }
                      final card = _cards[index],
                          slug = card['slug'].toString();
                      final accounts = _accounts
                          .where((value) => value['toolkit'] == slug)
                          .toList();
                      final active = accounts
                          .where((value) => value['status'] == 'ACTIVE')
                          .length;
                      final enabled = _enabled.contains(slug);
                      final status = card['noAuth'] == true
                          ? (enabled ? '已启用 · 无需授权' : '无需账号授权')
                          : active > 0
                          ? '$active 个账号已连接'
                          : accounts.isNotEmpty
                          ? '账号需要重新授权'
                          : enabled
                          ? '已启用 · 尚未连接'
                          : '';
                      return ListTile(
                        key: Key('na-composio-card-$slug'),
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 9,
                        ),
                        leading: _AppLogo(card: card),
                        title: Text(
                          card['label']?.toString() ?? slug,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        subtitle: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            if (status.isNotEmpty)
                              Padding(
                                padding: const EdgeInsets.only(top: 3),
                                child: Text(
                                  status,
                                  style: TextStyle(
                                    color:
                                        active > 0 ||
                                            enabled && card['noAuth'] == true
                                        ? context.omniPalette.textPrimary
                                        : context.omniPalette.textSecondary,
                                  ),
                                ),
                              ),
                            if (card['description']?.toString().isNotEmpty ==
                                true)
                              Padding(
                                padding: const EdgeInsets.only(top: 4),
                                child: Text(
                                  card['description'].toString(),
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                          ],
                        ),
                        trailing: _busy == slug && !_choosing
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(LucideIcons.chevronRight, size: 18),
                        onTap: _disabled ? null : () => _manage(card),
                      );
                    },
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AppLogo extends StatelessWidget {
  const _AppLogo({required this.card});
  final Map<String, dynamic> card;
  @override
  Widget build(BuildContext context) {
    final label = card['label']?.toString() ?? card['slug'].toString();
    final fallback = Center(
      child: Text(
        label.isEmpty ? '?' : label.substring(0, 1).toUpperCase(),
        style: Theme.of(context).textTheme.titleMedium,
      ),
    );
    return SizedBox(
      width: 46,
      height: 46,
      child: card['logo'] is String
          ? Image.network(
              card['logo'] as String,
              width: 38,
              height: 38,
              fit: BoxFit.contain,
              errorBuilder: (_, _, _) => fallback,
            )
          : fallback,
    );
  }
}

class _ConnectionChoice {
  const _ConnectionChoice({
    this.alias = '',
    this.config,
    this.disable = false,
    this.disconnectAccount,
  });
  final String alias;
  final String? config, disconnectAccount;
  final bool disable;
}

class _ConnectionDialog extends StatefulWidget {
  const _ConnectionDialog({
    required this.card,
    required this.enabled,
    required this.accounts,
    required this.configs,
    this.selectedConfig,
  });
  final Map<String, dynamic> card, configs;
  final bool enabled;
  final List<Map<String, dynamic>> accounts;
  final String? selectedConfig;
  @override
  State<_ConnectionDialog> createState() => _ConnectionDialogState();
}

class _ConnectionDialogState extends State<_ConnectionDialog> {
  final _alias = TextEditingController();
  bool _finished = false;
  void _finish(_ConnectionChoice? choice) {
    if (_finished) return;
    _finished = true;
    Navigator.pop(context, choice);
  }

  late String _config = widget.selectedConfig ?? '';
  @override
  void dispose() {
    _alias.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final raw = widget.configs['configs'] as List? ?? [];
    final configs = raw
        .whereType<Map>()
        .where(
          (value) =>
              value['managed'] != true &&
              value['status']?.toString().toUpperCase() == 'ENABLED',
        )
        .toList();
    final noAuth = widget.card['noAuth'] == true;
    return AlertDialog(
      title: Text(
        widget.card['label']?.toString() ?? widget.card['slug'].toString(),
      ),
      scrollable: true,
      content: SizedBox(
        width: 340,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(widget.card['description']?.toString() ?? ''),
            const SizedBox(height: 16),
            if (noAuth)
              const Text('此应用无需连接账号。启用后，操作仍遵循 Na 的授权设置。')
            else ...[
              DropdownButtonFormField<String>(
                key: const Key('na-composio-auth-config'),
                initialValue: _config,
                decoration: const InputDecoration(labelText: '授权配置'),
                isExpanded: true,
                items: [
                  const DropdownMenuItem(value: '', child: Text('自动选择（推荐）')),
                  if (_config.isNotEmpty &&
                      !configs.any((value) => value['id'] == _config))
                    DropdownMenuItem(
                      value: _config,
                      child: Text(
                        '当前配置 · $_config',
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  for (final config in configs)
                    DropdownMenuItem(
                      value: config['id']?.toString(),
                      child: Text(
                        '${config['name']?.toString().isNotEmpty == true ? config['name'] : config['id']} · ${config['id']}',
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                ],
                onChanged: (value) => setState(() => _config = value ?? ''),
              ),
              if (widget.configs['readError'] != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(widget.configs['readError'].toString()),
                ),
              const SizedBox(height: 16),
              TextField(
                key: const Key('na-composio-account-alias'),
                controller: _alias,
                maxLength: 80,
                decoration: InputDecoration(
                  labelText: widget.accounts.isEmpty ? '账号名称（可选）' : '新账号名称',
                  hintText: '例如：工作账号',
                ),
              ),
              for (final account in widget.accounts)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${account['alias'] ?? account['label'] ?? account['id']} · ${account['status']}',
                      ),
                      SelectableText(
                        account['id'].toString(),
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      TextButton(
                        onPressed: () => _finish(
                          _ConnectionChoice(
                            disconnectAccount: account['id'].toString(),
                          ),
                        ),
                        child: const Text('断开此账号'),
                      ),
                    ],
                  ),
                ),
            ],
          ],
        ),
      ),
      actions: [
        if (widget.enabled)
          TextButton(
            onPressed: () => _finish(const _ConnectionChoice(disable: true)),
            child: const Text('停用应用'),
          ),
        TextButton(onPressed: () => _finish(null), child: const Text('取消')),
        FilledButton(
          onPressed: () => _finish(
            _ConnectionChoice(
              alias: _alias.text.trim(),
              config: _config.isEmpty ? null : _config,
            ),
          ),
          child: Text(
            noAuth
                ? '启用应用'
                : widget.accounts.isEmpty
                ? '启用并连接'
                : '连接另一个账号',
          ),
        ),
      ],
    );
  }
}
