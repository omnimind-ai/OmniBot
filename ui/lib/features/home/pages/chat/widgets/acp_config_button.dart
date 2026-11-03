import 'dart:async';

import 'package:ui/widgets/flclash/popup.dart';
import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/widgets/settings_ui.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/conversation_model_selector.dart';
import 'package:ui/services/model_provider_config_service.dart';
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

typedef AcpConfigWriter = Future<Map<String, dynamic>> Function(
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
    this.configureModel,
    this.presentationIdentity,
    this.modelLabel,
  });
  final Future<Map<String, dynamic>> Function() load;
  final Future<Map<String, dynamic>> Function()? refresh;
  final AcpConfigWriter write;
  final bool isRunning;
  final ValueChanged<bool>? onVisibilityChanged;
  final Future<void> Function(BuildContext anchorContext)? configureModel;
  final Object? presentationIdentity;

  /// Actual selection supplied by the conversation/runtime owner. An absent
  /// value is shown as “Model”, never as an invented automatic selection.
  final String? modelLabel;

  @override
  State<AcpConfigButton> createState() => AcpConfigButtonState();
}

class AcpConfigButtonState extends State<AcpConfigButton> {
  OverlayGlassPopupHandle<void>? _popup;
  String? _returnedModelLabel;
  bool _openingSharedSelector = false;

  Future<Map<String, dynamic>> _projectConfigResponse(
    Future<Map<String, dynamic>> response,
    Object? identity,
  ) async {
    final result = await response;
    if (!mounted || identity != widget.presentationIdentity) return result;
    for (final option in acpConfigOptions(result)) {
      if (option['category'] != 'model' && option['id'] != 'model') continue;
      final value = option['currentValue']?.toString().trim() ?? '';
      if (value.isEmpty) continue;
      final choices = <Map>[];
      for (final item
          in (option['options'] as List? ?? const []).whereType<Map>()) {
        if (item['options'] is List) {
          choices.addAll((item['options'] as List).whereType<Map>());
        } else {
          choices.add(item);
        }
      }
      final current = choices
          .where((item) => item['value']?.toString() == value)
          .firstOrNull;
      final label = current?['name']?.toString().trim();
      setState(
        () => _returnedModelLabel = label?.isNotEmpty == true ? label : value,
      );
      break;
    }
    return result;
  }

  @override
  void didUpdateWidget(covariant AcpConfigButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.modelLabel != oldWidget.modelLabel ||
        widget.presentationIdentity != oldWidget.presentationIdentity) {
      _returnedModelLabel = null;
    }
    if (widget.isRunning != oldWidget.isRunning ||
        widget.presentationIdentity != oldWidget.presentationIdentity) {
      final popup = _popup;
      // didUpdateWidget runs during build; dismissing the overlay immediately
      // can notify an animation outside the current build subtree.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        unawaited(popup?.dismiss());
      });
    }
  }

  @override
  void dispose() {
    unawaited(_popup?.dismiss());
    super.dispose();
  }

  Future<void> open(
    BuildContext anchorContext, {
    bool preferBelow = false,
  }) async {
    if (_popup != null || _openingSharedSelector) return;
    final anchor = glassPopupAnchorFromContext(anchorContext);
    if (anchor == null) return;
    final identity = widget.presentationIdentity;
    final handle = showOverlayGlassPopup<void>(
      context: context,
      sourceContext: anchorContext,
      anchor: anchor,
      preferBelow: preferBelow,
      builder: (_) => AcpConfigPanel(
        load: () => _projectConfigResponse(widget.load(), identity),
        refresh: widget.refresh == null
            ? null
            : () => _projectConfigResponse(widget.refresh!(), identity),
        write: (session, option, value) => _projectConfigResponse(
          widget.write(session, option, value),
          identity,
        ),
        readOnly: widget.isRunning,
        configureModel: widget.configureModel == null
            ? null
            : () async {
                if (_openingSharedSelector) return;
                _openingSharedSelector = true;
                try {
                  await _popup?.dismiss();
                  if (mounted &&
                      anchorContext.mounted &&
                      identity == widget.presentationIdentity) {
                    await widget.configureModel!(anchorContext);
                  }
                } finally {
                  _openingSharedSelector = false;
                  if (mounted) widget.onVisibilityChanged?.call(false);
                }
              },
      ),
    );
    _popup = handle;
    widget.onVisibilityChanged?.call(true);
    try {
      await handle.future;
      await handle.closed;
    } finally {
      if (_popup == handle) _popup = null;
      if (mounted && !_openingSharedSelector) {
        widget.onVisibilityChanged?.call(false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final rawLabel = (_returnedModelLabel ?? widget.modelLabel ?? '').trim();
    // Provider prefixes are redundant in this narrow control; the complete
    // value remains available to accessibility and in the configuration panel.
    final label = rawLabel.isEmpty
        ? (english ? 'Model' : '模型')
        : rawLabel.split('/').last;
    final palette = context.omniPalette;
    return TextFieldTapRegion(
      child: FlClashPopupSource(
        glass: false,
        color: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        builder: (anchor) => Tooltip(
          message: rawLabel.isEmpty
              ? (english ? 'Model & settings' : '模型与参数')
              : (english ? 'Model & settings: $rawLabel' : '模型与参数：$rawLabel'),
          child: InkWell(
            key: const ValueKey('chat-acp-config-button'),
            borderRadius: BorderRadius.circular(10),
            onTap: () => open(anchor),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 144, minHeight: 36),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    Flexible(
                      child: Text(
                        label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 12,
                          color: palette.textSecondary,
                        ),
                      ),
                    ),
                    const SizedBox(width: 3),
                    Icon(
                      LucideIcons.chevronDown,
                      size: 13,
                      color: palette.textSecondary,
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Shows every declared option, including unfamiliar options, without inventing defaults.
class AcpConfigPanel extends StatefulWidget {
  const AcpConfigPanel({
    super.key,
    required this.load,
    this.refresh,
    required this.write,
    this.readOnly = false,
    this.configureModel,
  });
  final Future<Map<String, dynamic>> Function() load;
  final Future<Map<String, dynamic>> Function()? refresh;
  final AcpConfigWriter write;
  final bool readOnly;
  final Future<void> Function()? configureModel;
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
    if (_loading || _saving || widget.readOnly || _sessionId == null) return;
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
    if (option['category'] == 'thought_level' ||
        option['id'] == 'reasoning_effort') {
      return raw;
    }
    return value['name']?.toString() ?? raw;
  }

  Widget _option(Map<String, dynamic> option) {
    final id = option['id'].toString();
    final enabled =
        !_loading && !_saving && !widget.readOnly && _sessionId != null;
    final label = acpConfigLabel(option, english: _english);
    final value = option['currentValue'];
    final palette = context.omniPalette;
    if (option['type'] == 'boolean') {
      return MergeSemantics(
        child: OmniMenuItem(
          key: ValueKey('acp-config-$id'),
          enabled: enabled,
          onTap: enabled ? () => _save(id, value != true) : null,
          label: label,
          trailing: OmniSettingsSwitch(
            value: value == true,
            enabled: enabled,
            onToggle: (next) => _save(id, next),
          ),
        ),
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
    final expandable =
        option['type'] == 'select' &&
        (values.isNotEmpty || option['category'] == 'model');
    final expanded = expandable && _expandedId == id;
    final isThought =
        option['category'] == 'thought_level' || id == 'reasoning_effort';
    return OmniMenuItem(
      key: ValueKey('acp-config-$id'),
      enabled: !_loading && !_saving && expandable,
      onTap: !_loading && !_saving && expandable
          ? () {
              if (option['category'] == 'model' &&
                  widget.configureModel != null) {
                if (!widget.readOnly) unawaited(widget.configureModel!());
                return;
              }
              setState(() => _expandedId = expanded ? null : id);
              if (!expanded && option['category'] == 'model') {
                unawaited(_load(refresh: true));
              }
            }
          : null,
      child: Row(
        children: [
          Icon(
            isThought
                ? LucideIcons.brain
                : option['category'] == 'model'
                ? LucideIcons.sparkles
                : LucideIcons.slidersHorizontal,
          ),
          const SizedBox(width: OmniMenuStyle.iconGap),
          Expanded(child: Text(label)),
          const SizedBox(width: 8),
          Flexible(
            child: Text(
              currentLabel,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(color: palette.textSecondary),
            ),
          ),
          if (expandable) ...[
            const SizedBox(width: 4),
            Icon(LucideIcons.chevronRight, color: palette.textTertiary),
          ],
        ],
      ),
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
        showSearchField: selected['category'] == 'model',
        emptyMatchesLabel: _loading
            ? (_english ? 'Loading models…' : '正在实时获取模型…')
            : null,
        onSelectValue: _loading || _saving || widget.readOnly
            ? null
            : (value) => _save(selected['id'].toString(), value),
        header: OmniMenuItem(
          key: const ValueKey('acp-config-back'),
          onTap: () => setState(() => _expandedId = null),
          leading: const Icon(Icons.chevron_left_rounded),
          label: acpConfigLabel(selected, english: _english),
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
    return OmniMenuSurface(
      width: 280,
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
            padding: EdgeInsets.zero,
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
                if (!_loading &&
                    _error == null &&
                    _options.isNotEmpty &&
                    !_options.any(
                      (option) =>
                          option['category'] == 'thought_level' ||
                          option['id'] == 'reasoning_effort',
                    ))
                  OmniMenuItem(
                    key: const ValueKey('acp-reasoning-model-default'),
                    onTap: null,
                    child: Row(
                      children: [
                        const Icon(LucideIcons.brain),
                        const SizedBox(width: OmniMenuStyle.iconGap),
                        Expanded(
                          child: Text(_english ? 'Reasoning effort' : '思考强度'),
                        ),
                        const SizedBox(width: 8),
                        Flexible(
                          child: Text(
                            _english ? 'Model default' : '模型默认',
                            style: TextStyle(color: palette.textSecondary),
                          ),
                        ),
                      ],
                    ),
                  ),
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
                if ((_error != null || _options.isEmpty) &&
                    widget.configureModel != null)
                  OmniMenuItem(
                    enabled: !widget.readOnly,
                    onTap: widget.readOnly ? null : widget.configureModel,
                    leading: const Icon(LucideIcons.sparkles),
                    label: _english ? 'Choose model' : '选择模型',
                  ),
                if (_error != null && _options.isEmpty)
                  OmniMenuItem(
                    onTap: _load,
                    leading: const Icon(LucideIcons.refreshCw),
                    label: _english ? 'Reload' : '重新加载',
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
