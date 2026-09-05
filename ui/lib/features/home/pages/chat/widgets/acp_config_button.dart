import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/conversation_model_selector.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/theme/theme_context.dart';

/// Labels are presentation only; IDs, values and types remain ACP-owned.
String acpConfigLabel(Map<String, dynamic> option, {bool english = false}) {
  final name = option['name']?.toString() ?? option['id'].toString();
  if (english) return name;
  final category = option['category'];
  if (category == 'thought_level') return '思考强度';
  if (category == 'model') return '模型';
  if (category == 'mode') return '运行模式';
  return const <String, String>{
        'reasoning_effort': '思考强度',
        'thinking_budget': '思考预算',
        'enable_thinking': '启用思考',
        'temperature': '回答随机性',
        'top_p': '采样范围',
        'max_tokens': '最大输出长度',
        'max_output_tokens': '最大输出长度',
        'max_completion_tokens': '最大输出长度',
        'model': '模型',
        'mode': '运行模式',
        'approval_policy': '操作确认方式',
        'sandbox_mode': '执行权限',
        'collaboration_mode': '协作模式',
      }[option['id']] ??
      name;
}

List<Map<String, dynamic>> acpConfigOptions(Map<String, dynamic> response) =>
    (response['configOptions'] as List? ?? const [])
        .whereType<Map>()
        .map((option) => Map<String, dynamic>.from(option))
        .toList();

typedef AcpConfigWriter =
    Future<Map<String, dynamic>> Function(
      String sessionId,
      String configId,
      Object value,
    );

class AcpConfigButton extends StatefulWidget {
  const AcpConfigButton({
    super.key,
    required this.load,
    this.refresh,
    required this.write,
    required this.isRunning,
    this.onVisibilityChanged,
  });
  final Future<Map<String, dynamic>> Function() load;
  final Future<Map<String, dynamic>> Function()? refresh;
  final AcpConfigWriter write;
  final bool isRunning;
  final ValueChanged<bool>? onVisibilityChanged;

  @override
  State<AcpConfigButton> createState() => _AcpConfigButtonState();
}

class _AcpConfigButtonState extends State<AcpConfigButton> {
  OverlayGlassPopupHandle<void>? _popup;

  @override
  void didUpdateWidget(covariant AcpConfigButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isRunning != oldWidget.isRunning) unawaited(_popup?.dismiss());
  }

  @override
  void dispose() {
    unawaited(_popup?.dismiss());
    super.dispose();
  }

  Future<void> _open(BuildContext anchorContext) async {
    if (_popup != null) return;
    final anchor = glassPopupAnchorFromContext(anchorContext);
    if (anchor == null) return;
    final handle = showOverlayGlassPopup<void>(
      context: context,
      anchor: anchor,
      preferBelow: false,
      builder: (_) => AcpConfigPanel(
        load: widget.load,
        refresh: widget.refresh,
        write: widget.write,
        readOnly: widget.isRunning,
      ),
    );
    _popup = handle;
    widget.onVisibilityChanged?.call(true);
    try {
      await handle.future;
    } finally {
      if (_popup == handle) _popup = null;
      if (mounted) widget.onVisibilityChanged?.call(false);
    }
  }

  @override
  Widget build(BuildContext context) => TextFieldTapRegion(
    child: Builder(
      builder: (anchor) => Tooltip(
        message: Localizations.localeOf(context).languageCode == 'en'
            ? 'Model & settings'
            : '模型与参数',
        child: InkWell(
          key: const ValueKey('chat-acp-config-button'),
          borderRadius: BorderRadius.circular(8),
          onTap: () => _open(anchor),
          child: SizedBox(
            width: 28,
            height: 28,
            child: Icon(
              LucideIcons.slidersHorizontal,
              size: 20,
              color: context.omniPalette.accentPrimary,
            ),
          ),
        ),
      ),
    ),
  );
}

/// Shows every declared option, including unfamiliar options, without inventing defaults.
class AcpConfigPanel extends StatefulWidget {
  const AcpConfigPanel({
    super.key,
    required this.load,
    this.refresh,
    required this.write,
    this.readOnly = false,
  });
  final Future<Map<String, dynamic>> Function() load;
  final Future<Map<String, dynamic>> Function()? refresh;
  final AcpConfigWriter write;
  final bool readOnly;
  @override
  State<AcpConfigPanel> createState() => _AcpConfigPanelState();
}

class _AcpConfigPanelState extends State<AcpConfigPanel> {
  List<Map<String, dynamic>> _options = [];
  String? _sessionId;
  String? _error;
  bool _loading = true;
  bool _saving = false;
  String? _expandedId;
  bool get _english => Localizations.localeOf(context).languageCode == 'en';

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load({bool refresh = false}) async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final response = await (refresh
          ? (widget.refresh ?? widget.load)
          : widget.load)();
      if (!mounted) return;
      _sessionId = (response['sessionId'] ?? response['threadId'])?.toString();
      _options = acpConfigOptions(response);
    } catch (error) {
      if (mounted) _error = formatAgentRuntimeErrorForUser(error);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _save(String id, Object value) async {
    if (_saving || widget.readOnly || _sessionId == null) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final result = await widget.write(_sessionId!, id, value);
      if (!mounted) return;
      // The complete official response is authoritative, including dependent options.
      _options = acpConfigOptions(result);
      _expandedId = null;
    } catch (error) {
      if (mounted) _error = formatAgentRuntimeErrorForUser(error);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  String _valueLabel(Map<String, dynamic> option, Map value) {
    final raw = value['value']?.toString() ?? '';
    if (!_english &&
        (option['category'] == 'thought_level' ||
            option['id'] == 'reasoning_effort')) {
      return const {
            'default': '模型默认',
            'none': '关闭',
            'minimal': '极低',
            'low': '低',
            'medium': '中',
            'high': '高',
            'xhigh': '极高',
            'max': '最高',
          }[raw] ??
          value['name']?.toString() ??
          raw;
    }
    return value['name']?.toString() ?? raw;
  }

  Widget _option(Map<String, dynamic> option) {
    final id = option['id'].toString();
    final enabled = !_saving && !widget.readOnly && _sessionId != null;
    final label = acpConfigLabel(option, english: _english);
    final value = option['currentValue'];
    final palette = context.omniPalette;
    final textStyle = TextStyle(
      fontSize: 13,
      fontWeight: FontWeight.w500,
      color: palette.textPrimary,
    );
    if (option['type'] == 'boolean') {
      return SwitchListTile.adaptive(
        key: ValueKey('acp-config-$id'),
        contentPadding: const EdgeInsets.symmetric(horizontal: 12),
        dense: true,
        visualDensity: VisualDensity.compact,
        activeTrackColor: palette.accentPrimary,
        title: Text(label, style: textStyle),
        value: value == true,
        onChanged: enabled ? (next) => _save(id, next) : null,
      );
    }
    final values = <Map>[];
    for (final item
        in (option['options'] as List? ?? const []).whereType<Map>()) {
      if (item['options'] is List) {
        values.addAll((item['options'] as List).whereType<Map>());
      } else {
        values.add(item);
      }
    }
    final current = values.where((item) => item['value'] == value).firstOrNull;
    final currentLabel = current == null
        ? value?.toString() ?? '—'
        : _valueLabel(option, current);
    final expandable = option['type'] == 'select' && values.isNotEmpty;
    final expanded = expandable && _expandedId == id;
    final isThought =
        option['category'] == 'thought_level' || id == 'reasoning_effort';
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        InkWell(
          key: ValueKey('acp-config-$id'),
          borderRadius: BorderRadius.circular(12),
          onTap: !_loading && !_saving && expandable
              ? () {
                  setState(() => _expandedId = expanded ? null : id);
                  if (!expanded && option['category'] == 'model')
                    unawaited(_load(refresh: true));
                }
              : null,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 13),
            child: Row(
              children: [
                Icon(
                  isThought
                      ? LucideIcons.brain
                      : option['category'] == 'model'
                      ? LucideIcons.sparkles
                      : LucideIcons.slidersHorizontal,
                  size: 16,
                  color: palette.textSecondary,
                ),
                const SizedBox(width: 9),
                Expanded(child: Text(label, style: textStyle)),
                const SizedBox(width: 8),
                Flexible(
                  child: Text(
                    currentLabel,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      color: palette.textSecondary,
                    ),
                  ),
                ),
                if (expandable) ...[
                  const SizedBox(width: 4),
                  Icon(
                    LucideIcons.chevronRight,
                    size: 14,
                    color: palette.textTertiary,
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final selected = _options
        .where((item) => item['id'].toString() == _expandedId)
        .firstOrNull;
    if (selected != null) {
      final choices = <Map>[];
      for (final item
          in (selected['options'] as List? ?? const []).whereType<Map>()) {
        if (item['options'] is List) {
          choices.addAll((item['options'] as List).whereType<Map>());
        } else {
          choices.add(item);
        }
      }
      return ConversationModelSelectorContent(
        key: ValueKey('acp-config-choices-$_expandedId'),
        width: 280,
        maxHeight: 420,
        options: (_loading ? <Map>[] : choices)
            .map(
              (item) => ProviderModelOption(
                id: item['value'].toString(),
                displayName: _valueLabel(selected, item),
              ),
            )
            .toList(),
        selectedValue: selected['currentValue']?.toString(),
        modelRowKeyPrefix: 'acp-config-$_expandedId-value',
        showVendorIcons: selected['category'] == 'model',
        showSearchField: selected['category'] == 'model' && choices.length > 5,
        emptyMatchesLabel: _loading
            ? (_english ? 'Loading models…' : '正在实时获取模型…')
            : null,
        onSelectValue: _loading || _saving || widget.readOnly
            ? null
            : (value) => _save(selected['id'].toString(), value),
        header: Padding(
          padding: const EdgeInsets.fromLTRB(8, 7, 10, 3),
          child: InkWell(
            key: const ValueKey('acp-config-back'),
            borderRadius: BorderRadius.circular(10),
            onTap: () => setState(() => _expandedId = null),
            child: SizedBox(
              height: 34,
              child: Row(
                children: [
                  const SizedBox(
                    width: 34,
                    child: Icon(Icons.chevron_left_rounded, size: 20),
                  ),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text(
                      acpConfigLabel(selected, english: _english),
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: palette.textPrimary,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        footer: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (_saving) const LinearProgressIndicator(minHeight: 2),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.all(12),
                child: Text(
                  _error!,
                  style: TextStyle(
                    fontSize: 12,
                    color: Theme.of(context).colorScheme.error,
                  ),
                ),
              ),
          ],
        ),
      );
    }
    return OmniGlassPanel(
      width: 280,
      borderRadius: BorderRadius.circular(18),
      child: Material(
        color: Colors.transparent,
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight:
                (MediaQuery.sizeOf(context).height -
                        MediaQuery.viewInsetsOf(context).bottom -
                        110)
                    .clamp(150, 420),
          ),
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 3, 12, 8),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          _english ? 'Model & settings' : '模型与参数',
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                            color: palette.textSecondary,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Text(
                        widget.readOnly
                            ? (_english ? 'Running' : '运行中')
                            : (_english ? 'Next turn' : '下一轮生效'),
                        style: TextStyle(
                          fontSize: 11,
                          color: palette.textTertiary,
                        ),
                      ),
                    ],
                  ),
                ),
                if (_loading || _saving)
                  Padding(
                    padding: const EdgeInsets.all(12),
                    child: LinearProgressIndicator(
                      minHeight: 2,
                      color: palette.accentPrimary,
                    ),
                  ),
                if (!_loading && _options.isEmpty && _error == null)
                  Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text(
                      _english
                          ? 'This Agent exposes no configurable options'
                          : '当前 Agent 未提供可调整参数',
                      style: TextStyle(
                        fontSize: 12,
                        color: palette.textSecondary,
                      ),
                    ),
                  ),
                for (var i = 0; i < _options.length; i++) ...[
                  if (i > 0)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      child: Divider(
                        height: 1,
                        thickness: 0.5,
                        color: palette.textTertiary.withValues(alpha: 0.15),
                      ),
                    ),
                  _option(_options[i]),
                ],
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text(
                      _error!,
                      style: TextStyle(
                        fontSize: 12,
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ),
                if (_error != null && _options.isEmpty)
                  TextButton(
                    onPressed: _load,
                    child: Text(_english ? 'Reload' : '重新加载'),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
