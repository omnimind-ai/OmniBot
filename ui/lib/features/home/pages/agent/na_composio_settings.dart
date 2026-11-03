import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:url_launcher/url_launcher.dart';

/// An editor for server-owned connector configuration. The private key is
/// write-only and is never initialized from a server response or local storage.
class NaComposioSettings extends StatefulWidget {
  const NaComposioSettings({
    super.key,
    required this.settings,
    required this.busy,
    required this.onSave,
  });
  final Map<String, dynamic> settings;
  final bool busy;
  final Future<Map<String, dynamic>?> Function(Map<String, dynamic>) onSave;
  @override
  State<NaComposioSettings> createState() => _NaComposioSettingsState();
}

class _NaComposioSettingsState extends State<NaComposioSettings> {
  final _key = TextEditingController();
  final _retired = <TextEditingController>[];
  final _rows = <_ToolkitFields>[];
  bool _enabled = false, _clearKey = false, _dirty = false, _saving = false;
  int _revision = 0;
  String? _error;

  @override
  void initState() {
    super.initState();
    _adopt(widget.settings);
  }

  @override
  void didUpdateWidget(covariant NaComposioSettings oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.settings['revision'] != oldWidget.settings['revision'] &&
        !_dirty &&
        !_saving) {
      _adopt(widget.settings);
    }
  }

  void _adopt(Map<String, dynamic> settings) {
    for (final row in _rows) {
      _retired.addAll([row.slug, row.authConfig]);
    }
    _rows.clear();
    final configs = settings['authConfigs'] is Map
        ? settings['authConfigs'] as Map
        : const {};
    for (final slug
        in (settings['toolkits'] as List? ?? []).whereType<String>()) {
      _rows.add(_ToolkitFields(slug, configs[slug]?.toString() ?? ''));
    }
    _revision = (settings['revision'] as num?)?.toInt() ?? 0;
    _enabled = settings['enabled'] == true;
    _clearKey = false;
    _dirty = false;
    _error = null;
    _key.clear();
  }

  @override
  void dispose() {
    _key.dispose();
    for (final row in _rows) {
      row.slug.dispose();
      row.authConfig.dispose();
    }
    for (final controller in _retired) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    if (widget.busy || _saving) return;
    if (_key.text.trim().startsWith('ck_')) {
      setState(
        () => _error = '这是 Connect 消费者密钥。请在 Platform 项目的 Settings → API Keys 获取项目 API Key。',
      );
      return;
    }
    final toolkits = <String>[], authConfigs = <String, String>{};
    for (final row in _rows) {
      final slug = row.slug.text.trim(), config = row.authConfig.text.trim();
      if (!RegExp(r'^[a-z][a-z0-9_]{0,63}$').hasMatch(slug) ||
          toolkits.contains(slug)) {
        setState(() => _error = '应用标识需为不重复的小写 Composio toolkit，例如 gmail。');
        return;
      }
      toolkits.add(slug);
      if (config.isNotEmpty) authConfigs[slug] = config;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final saved = await widget.onSave({
        'expectedRevision': _revision,
        'enabled': _enabled,
        'toolkits': toolkits,
        'authConfigs': authConfigs,
        if (_clearKey)
          'clearApiKey': true
        else if (_key.text.trim().isNotEmpty)
          'apiKey': _key.text.trim(),
      });
      if (mounted && saved != null) setState(() => _adopt(saved));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final disabled = widget.busy || _saving;
    final keyConfigured = widget.settings['apiKeyConfigured'] == true;
    final changedElsewhere =
        _dirty && _revision != (widget.settings['revision'] as num?)?.toInt();
    return ExpansionTile(
      key: const Key('na-composio-settings'),
      tilePadding: EdgeInsets.zero,
      title: const Text('连接服务设置'),
      subtitle: Text(keyConfigured ? '已配置项目密钥 · 设置保存在 Na 服务端' : '设置保存在 Na 服务端'),
      children: [
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('启用 Composio'),
          value: _enabled,
          onChanged: disabled
              ? null
              : (value) => setState(() {
                  _enabled = value;
                  _dirty = true;
                }),
        ),
        TextField(
          key: const Key('na-composio-api-key'),
          controller: _key,
          obscureText: true,
          enabled: !disabled && !_clearKey,
          autocorrect: false,
          enableSuggestions: false,
          decoration: InputDecoration(
            labelText: '项目 API Key',
            hintText: keyConfigured ? '留空保留现有密钥' : 'Platform 项目密钥（ak_…）',
          ),
          onChanged: (_) => setState(() => _dirty = true),
        ),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
            key: const Key('na-composio-get-key'),
            onPressed: disabled
                ? null
                : () async {
                    var opened = false;
                    try {
                      opened = await launchUrl(
                        Uri.parse(
                          'https://dashboard.composio.dev/~/project/settings/api-keys',
                        ),
                        mode: LaunchMode.externalApplication,
                      );
                    } catch (_) {
                      // Keep plugin failures local and never include form values.
                    }
                    if (!opened && mounted) {
                      setState(
                        () => _error = '无法打开 Composio 控制台，请在 Platform 项目的 Settings → API Keys 获取项目密钥。',
                      );
                    }
                  },
            icon: const Icon(LucideIcons.externalLink, size: 16),
            label: const Text('获取项目 API Key'),
          ),
        ),
        if (keyConfigured)
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('清除服务端密钥'),
            value: _clearKey,
            onChanged: disabled
                ? null
                : (value) => setState(() {
                    _clearKey = value == true;
                    _dirty = true;
                    if (_clearKey) _key.clear();
                  }),
          ),
        const SizedBox(height: 18),
        const Align(alignment: Alignment.centerLeft, child: Text('开放的应用')),
        const SizedBox(height: 8),
        for (var index = 0; index < _rows.length; index++)
          Padding(
            key: ObjectKey(_rows[index]),
            padding: const EdgeInsets.only(bottom: 16),
            child: Column(
              children: [
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _rows[index].slug,
                        enabled: !disabled,
                        decoration: const InputDecoration(
                          labelText: '应用标识',
                          hintText: '例如 gmail、slack',
                        ),
                        onChanged: (_) => _dirty = true,
                      ),
                    ),
                    IconButton(
                      tooltip: '移除此应用',
                      onPressed: disabled
                          ? null
                          : () => setState(() {
                              final row = _rows.removeAt(index);
                              _retired.addAll([row.slug, row.authConfig]);
                              _dirty = true;
                            }),
                      icon: const Icon(LucideIcons.minus, size: 18),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                TextField(
                  controller: _rows[index].authConfig,
                  enabled: !disabled,
                  decoration: const InputDecoration(
                    labelText: '授权配置 ID（可选）',
                    hintText: '留空使用默认托管授权',
                  ),
                  onChanged: (_) => _dirty = true,
                ),
              ],
            ),
          ),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
            onPressed: disabled || _rows.length >= 30
                ? null
                : () => setState(() {
                    _rows.add(_ToolkitFields('', ''));
                    _dirty = true;
                  }),
            icon: const Icon(LucideIcons.plus, size: 16),
            label: const Text('添加应用'),
          ),
        ),
        if (_error != null)
          Text(
            _error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        if (changedElsewhere) const Text('服务配置已在其他客户端更新。保存会检查版本，请先重新载入。'),
        Wrap(
          spacing: 12,
          children: [
            FilledButton(
              key: const Key('na-composio-settings-save'),
              onPressed: disabled ? null : _save,
              child: Text(_saving ? '正在保存…' : '保存到 Na 服务端'),
            ),
            if (changedElsewhere)
              TextButton(
                onPressed: disabled
                    ? null
                    : () => setState(() => _adopt(widget.settings)),
                child: const Text('重新载入服务配置'),
              ),
          ],
        ),
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 12),
          child: Text('密钥只写入服务端，不会回显；应用授权仍在官方页面由你完成。'),
        ),
      ],
    );
  }
}

class _ToolkitFields {
  _ToolkitFields(String slug, String authConfig)
    : slug = TextEditingController(text: slug),
      authConfig = TextEditingController(text: authConfig);
  final TextEditingController slug;
  final TextEditingController authConfig;
}
