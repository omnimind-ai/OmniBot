import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';

String? _requestAgentId(Map<String, dynamic> cardData) {
  final value = cardData['agentId']?.toString().trim() ?? '';
  return value.isEmpty ? null : value;
}

int? _requestConversationId(Map<String, dynamic> cardData) {
  final value = cardData['conversationId'];
  return value is num ? value.toInt() : int.tryParse(value?.toString() ?? '');
}

class AgentRequestCard extends StatefulWidget {
  const AgentRequestCard({super.key, required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  State<AgentRequestCard> createState() => _AgentRequestCardState();
}

/// Compact fallback for request messages that arrive outside the Agent
/// timeline (for example, an old restored snapshot).  It deliberately has no
/// nested TextField and no large card surface.  User-input requests are
/// answered by the single composer; explicit permission requests, which are
/// uncommon when the default full-access mode is active, retain only tiny
/// inline actions so the ACP turn cannot deadlock.
class AgentRequestNotice extends StatefulWidget {
  const AgentRequestNotice({super.key, required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  State<AgentRequestNotice> createState() => _AgentRequestNoticeState();
}

class _AgentRequestNoticeState extends State<AgentRequestNotice> {
  bool _submitting = false;
  String? _status;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final kind = (widget.cardData['requestKind'] ?? '').toString().trim();
    final presentation = _compactRequestPresentation(widget.cardData);
    final title = presentation.title.isEmpty
        ? (kind == 'approval' ? 'Permission requested' : 'Agent question')
        : presentation.title;
    final detail = presentation.detail;
    final requestId = widget.cardData['requestId'];
    final cardStatus = widget.cardData['status']
        ?.toString()
        .trim()
        .toLowerCase();
    final pending =
        _status == null &&
        !const <String>{
          'submitted',
          'accepted',
          'declined',
          'ignored',
          'cancelled',
          'failed',
        }.contains(cardStatus);
    final unavailable =
        requestId == null || requestId.toString().trim().isEmpty;

    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: 8, bottom: 4),
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 8),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : palette.surfacePrimary,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            kind == 'approval'
                ? Icons.shield_outlined
                : Icons.help_outline_rounded,
            size: 17,
            color: palette.accentPrimary,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title.isEmpty ? 'Agent request' : title,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                if (detail.isNotEmpty && detail != title) ...[
                  const SizedBox(height: 2),
                  Text(
                    detail,
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                      height: 1.3,
                    ),
                  ),
                ],
                if (kind == 'user_input' && pending) ...[
                  const SizedBox(height: 2),
                  Text(
                    '请直接在下方输入回复',
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                    ),
                  ),
                ],
                if (kind == 'approval' && pending && !unavailable) ...[
                  const SizedBox(height: 4),
                  Wrap(
                    spacing: 4,
                    children: [
                      TextButton(
                        onPressed: _submitting ? null : () => _respond(true),
                        style: TextButton.styleFrom(
                          minimumSize: const Size(0, 30),
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        ),
                        child: const Text('允许'),
                      ),
                      TextButton(
                        onPressed: _submitting ? null : () => _respond(false),
                        style: TextButton.styleFrom(
                          minimumSize: const Size(0, 30),
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        ),
                        child: const Text('拒绝'),
                      ),
                    ],
                  ),
                ],
                if (_status != null || unavailable) ...[
                  const SizedBox(height: 2),
                  Text(
                    unavailable
                        ? '请求缺少 requestId，已跳过交互'
                        : (_status == 'accepted' ? '已允许' : '已拒绝'),
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _respond(bool accepted) async {
    final requestId = widget.cardData['requestId'];
    if (requestId == null || _submitting) return;
    setState(() => _submitting = true);
    try {
      await AgentRuntimeService.respondToApproval(
        requestId: requestId,
        accepted: accepted,
        sessionId: widget.cardData['sessionId']?.toString(),
        agentId: _requestAgentId(widget.cardData),
        conversationId: _requestConversationId(widget.cardData),
      );
      if (!mounted) return;
      setState(() {
        _status = accepted ? 'accepted' : 'declined';
        _submitting = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _submitting = false);
      showToast(
        Localizations.maybeLocaleOf(context)?.languageCode == 'en'
            ? 'Reply was not sent. Try again.'
            : '回复未送达，可以重试',
        type: ToastType.warning,
      );
    }
  }
}

({String title, String detail}) _compactRequestPresentation(
  Map<String, dynamic> card,
) {
  final storedTitle = (card['title'] ?? '').toString().trim();
  var title = storedTitle;
  var detail = (card['detail'] ?? '').toString().trim();
  final raw = _decodeRawParams(card['rawParamsJson']);
  final schema = _compactRequestSchema(raw);
  final properties = _asStringMap(schema?['properties']);
  if (properties != null && properties.isNotEmpty) {
    final first = properties.entries.first;
    final field = _asStringMap(first.value) ?? const <String, dynamic>{};
    final fieldTitle = _firstText([field['title'], field['label'], first.key]);
    final fieldDetail = _firstText([
      field['description'],
      field['placeholder'],
    ]);
    if (_isGenericCompactTitle(title) && fieldTitle != null) {
      title = fieldTitle;
    }
    if ((_isGenericCompactDetail(detail, title) || detail.isEmpty) &&
        fieldDetail != null) {
      detail = fieldDetail;
    }
    final choices = _compactSchemaChoices(field);
    if (choices.isNotEmpty && !detail.contains('可选：')) {
      detail = detail.isEmpty
          ? '可选：${choices.join('、')}'
          : '$detail\n可选：${choices.join('、')}';
    }
  }
  return (title: title, detail: detail);
}

Map<String, dynamic>? _compactRequestSchema(Map<String, dynamic>? params) {
  if (params == null) return null;
  for (final key in const <String>[
    'requestedSchema',
    'requested_schema',
    'schema',
    'inputSchema',
    'input_schema',
  ]) {
    final value = params[key];
    final map =
        _asStringMap(value) ??
        (value is String ? _asStringMap(_decodeJsonObject(value)) : null);
    if (map != null) return map;
  }
  for (final key in const <String>['request', 'elicitation', 'params']) {
    final nested =
        _asStringMap(params[key]) ??
        (params[key] is String
            ? _asStringMap(_decodeJsonObject(params[key] as String))
            : null);
    final schema = _compactRequestSchema(nested);
    if (schema != null) return schema;
  }
  return params['properties'] is Map ? params : null;
}

dynamic _decodeJsonObject(String value) {
  try {
    return jsonDecode(value);
  } catch (_) {
    return null;
  }
}

bool _isGenericCompactTitle(String value) {
  final normalized = value.trim().toLowerCase();
  return normalized.isEmpty ||
      (normalized.contains('agent') &&
          (normalized.contains('input') || normalized.contains('question'))) ||
      (value.contains('需要') && value.contains('输入'));
}

bool _isGenericCompactDetail(String detail, String title) {
  final normalized = detail.trim().toLowerCase();
  return normalized.isEmpty ||
      normalized == title.trim().toLowerCase() ||
      (normalized.contains('agent') && normalized.contains('input')) ||
      normalized.startsWith('{') ||
      normalized.startsWith('[') ||
      normalized.contains('requestedschema');
}

List<String> _compactSchemaChoices(Map<String, dynamic> field) {
  final values = field['oneOf'] ?? field['enum'];
  if (values is! List) return const <String>[];
  return values
      .map((value) {
        final map = _asStringMap(value);
        return _firstText([map?['title'], map?['label'], map?['const'], value]);
      })
      .whereType<String>()
      .toList(growable: false);
}

class _AgentRequestCardState extends State<AgentRequestCard> {
  bool _isSubmitting = false;
  String? _localStatus;
  List<String> _localAnswers = const <String>[];
  String? _selectedOptionValue;
  final Map<String, TextEditingController> _formControllers =
      <String, TextEditingController>{};
  final Map<String, dynamic> _formValues = <String, dynamic>{};

  @override
  void initState() {
    super.initState();
    _syncDefaultSelection();
    _syncFormState();
    _hydratePersistedResponse();
  }

  @override
  void didUpdateWidget(covariant AgentRequestCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_requestRenderSignature(oldWidget.cardData) !=
        _requestRenderSignature(widget.cardData)) {
      _localStatus = null;
      _localAnswers = const <String>[];
      _selectedOptionValue = null;
      _disposeFormControllers();
      _syncDefaultSelection();
      _syncFormState();
      _hydratePersistedResponse();
    }
  }

  @override
  void dispose() {
    _disposeFormControllers();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final kind = (widget.cardData['requestKind'] ?? '').toString();
    final agentName = (widget.cardData['agentName'] ?? 'Agent')
        .toString()
        .trim();
    final title =
        (widget.cardData['title'] ??
                '${agentName.isEmpty ? 'Agent' : agentName} request')
            .toString();
    final detail = _requestVisibleDetail(
      title,
      (widget.cardData['detail'] ?? '').toString(),
    );
    final cardStatus = _cardStatus(widget.cardData);
    final status = cardStatus == 'pending'
        ? (_isTerminalRequestStatus(_localStatus) ? _localStatus! : 'pending')
        : (_localStatus ?? cardStatus);
    final interactionUnavailable =
        widget.cardData['interactionUnavailable'] == true;
    final interactionUnavailableReason =
        widget.cardData['interactionUnavailableReason']?.toString().trim();
    final isPending =
        status == 'pending' && !_isSubmitting && !interactionUnavailable;
    final options = _resolveRequestOptions(widget.cardData);
    final hasOptions = options.isNotEmpty;
    final formFields = _resolveElicitationFields(widget.cardData);
    final hasStructuredForm = formFields.isNotEmpty;
    final isStructuredElicitation =
        widget.cardData['structuredElicitation'] == true;
    final answers = _localAnswers.isNotEmpty
        ? _localAnswers
        : _stringList(widget.cardData['submittedAnswers']);
    final canSubmit =
        isPending &&
        (isStructuredElicitation
            ? _isFormValid(formFields)
            : hasOptions && _selectedOptionValue != null);

    return Container(
      key: const ValueKey('agent-request-card-surface'),
      width: double.infinity,
      margin: const EdgeInsets.only(top: 8, bottom: 4),
      padding: const EdgeInsets.fromLTRB(14, 13, 14, 12),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : palette.surfacePrimary,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 30,
                height: 30,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: palette.accentPrimary.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  kind == 'approval'
                      ? Icons.shield_outlined
                      : Icons.help_outline_rounded,
                  size: 17,
                  color: palette.accentPrimary,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w800,
                    color: palette.textPrimary,
                    height: 1.2,
                  ),
                ),
              ),
            ],
          ),
          if (detail.trim().isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              detail,
              maxLines: 5,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 12,
                color: palette.textSecondary,
                height: 1.35,
              ),
            ),
          ],
          if (interactionUnavailable) ...[
            const SizedBox(height: 8),
            Text(
              interactionUnavailableReason == 'session_ended'
                  ? (Localizations.maybeLocaleOf(context)?.languageCode == 'en'
                        ? 'This request expired with the ACP session. Start a new prompt to continue.'
                        : 'ACP 会话已结束，该请求已过期。请发起新的请求继续。')
                  : 'This request cannot be answered because ACP omitted its request id.',
              style: TextStyle(
                fontSize: 12,
                color: palette.textSecondary,
                height: 1.35,
              ),
            ),
          ],
          const SizedBox(height: 14),
          if (kind == 'user_input' && status == 'pending') ...[
            if (hasStructuredForm) ...[
              for (final field in formFields) ...[
                _buildElicitationField(field),
                const SizedBox(height: 8),
              ],
            ] else if (hasOptions) ...[
              for (var index = 0; index < options.length; index++) ...[
                _RequestOptionTile(
                  index: index + 1,
                  option: options[index],
                  selected: options[index].value == _selectedOptionValue,
                  enabled: isPending,
                  onTap: () {
                    setState(() {
                      _selectedOptionValue = options[index].value;
                    });
                  },
                ),
                const SizedBox(height: 4),
              ],
            ],
          ],
          _RequestFooter(
            kind: kind,
            status: status,
            answers: answers,
            isPending: isPending,
            isSubmitting: _isSubmitting,
            canSubmit: canSubmit,
            onAccept: () => _respondApproval(true),
            onDecline: () => _respondApproval(false),
            onIgnore: _ignoreUserInput,
            onSubmit: _respondUserInput,
          ),
        ],
      ),
    );
  }

  Future<void> _respondApproval(bool accepted) async {
    final requestId = widget.cardData['requestId'];
    if (requestId == null) return;
    await _submit(() {
      return AgentRuntimeService.respondToApproval(
        requestId: requestId,
        accepted: accepted,
        sessionId: widget.cardData['sessionId']?.toString(),
        agentId: _requestAgentId(widget.cardData),
        conversationId: _requestConversationId(widget.cardData),
      );
    }, accepted ? 'accepted' : 'declined');
  }

  Future<void> _ignoreUserInput() async {
    final requestId = widget.cardData['requestId'];
    if (requestId == null) return;
    await _submit(() {
      if (widget.cardData['structuredElicitation'] == true) {
        return AgentRuntimeService.cancelElicitation(
          requestId: requestId,
          sessionId: widget.cardData['sessionId']?.toString(),
          agentId: _requestAgentId(widget.cardData),
          conversationId: _requestConversationId(widget.cardData),
        );
      }
      return AgentRuntimeService.ignoreUserInput(
        requestId: requestId,
        sessionId: widget.cardData['sessionId']?.toString(),
        agentId: _requestAgentId(widget.cardData),
        conversationId: _requestConversationId(widget.cardData),
      );
    }, 'ignored');
  }

  Future<void> _respondUserInput() async {
    final requestId = widget.cardData['requestId'];
    final questionId = (widget.cardData['questionId'] ?? 'answer').toString();
    if (requestId == null) return;
    final formFields = _resolveElicitationFields(widget.cardData);
    if (widget.cardData['structuredElicitation'] == true) {
      final content = _elicitationContent(formFields);
      await _submit(
        () => AgentRuntimeService.respondToElicitation(
          requestId: requestId,
          content: content,
          sessionId: widget.cardData['sessionId']?.toString(),
          agentId: _requestAgentId(widget.cardData),
          conversationId: _requestConversationId(widget.cardData),
        ),
        'submitted',
        answers: content.values.map((value) => value.toString()).toList(),
      );
      return;
    }
    final answer = (_selectedOptionValue ?? '').trim();
    if (answer.isEmpty) return;
    await _submit(
      () {
        return AgentRuntimeService.respondToUserInput(
          requestId: requestId,
          questionId: questionId,
          answers: <String>[answer],
          sessionId: widget.cardData['sessionId']?.toString(),
          agentId: _requestAgentId(widget.cardData),
          conversationId: _requestConversationId(widget.cardData),
        );
      },
      'submitted',
      answers: <String>[answer],
    );
  }

  Future<void> _submit(
    Future<Map<String, dynamic>> Function() action,
    String successStatus, {
    List<String> answers = const <String>[],
  }) async {
    if (_isSubmitting) return;
    setState(() {
      _isSubmitting = true;
    });
    try {
      await action();
      if (!mounted) return;
      setState(() {
        _localStatus = successStatus;
        _localAnswers = answers;
        _isSubmitting = false;
      });
      // ACP acknowledgement is the protocol lifecycle boundary. Persisting
      // the UI card is a local best-effort side effect and must not turn a
      // successfully consumed request into a false "reply not sent" error.
      try {
        await _persistResponseStatus(successStatus, answers);
      } catch (_) {
        // The live card already reflects the acknowledged ACP response. A
        // later conversation refresh may simply reconstruct it from ACP
        // history; retrying the request here would be unsafe because the
        // Harness has already consumed the one-shot response.
      }
    } catch (_) {
      if (!mounted) return;
      setState(() {
        // Keep the request pending after a transport failure. The ACP
        // request is still owned by the Harness and can be retried; marking
        // it terminal here strands the Agent in its waiting state.
        _localStatus = null;
        _isSubmitting = false;
      });
      showToast(
        Localizations.maybeLocaleOf(context)?.languageCode == 'en'
            ? 'Reply was not sent. Try again.'
            : '回复未送达，可以重试',
        type: ToastType.warning,
      );
    }
  }

  void _hydratePersistedResponse() {
    try {
      final raw =
          StorageService.getString(_requestStorageKey(widget.cardData)) ??
          StorageService.getString(_legacyRequestStorageKey(widget.cardData));
      if (raw == null || raw.trim().isEmpty) {
        return;
      }
      final decoded = jsonDecode(raw);
      if (decoded is! Map) {
        return;
      }
      final status = decoded['status']?.toString().trim().toLowerCase();
      if (status == null || status.isEmpty) {
        return;
      }
      if (!_isTerminalRequestStatus(status)) {
        return;
      }
      final currentIdentity = _requestStorageIdentity(widget.cardData);
      final cachedIdentity = decoded['identity']?.toString().trim();
      if (cachedIdentity != null &&
          cachedIdentity.isNotEmpty &&
          cachedIdentity != currentIdentity) {
        return;
      }
      if (_cardStatus(widget.cardData) == 'pending' &&
          cachedIdentity != currentIdentity) {
        return;
      }
      _localStatus = status;
      _localAnswers = _stringList(decoded['answers']);
    } catch (_) {
      return;
    }
  }

  void _syncDefaultSelection() {
    if (_cardStatus(widget.cardData) != 'pending') {
      return;
    }
    final options = _resolveRequestOptions(widget.cardData);
    if (options.isEmpty || _selectedOptionValue != null) {
      return;
    }
    _selectedOptionValue = options.first.value;
  }

  void _syncFormState() {
    final fields = _resolveElicitationFields(widget.cardData);
    for (final field in fields) {
      final defaultValue = field.type == 'boolean' && field.defaultValue == null
          ? false
          : field.defaultValue;
      _formValues.putIfAbsent(field.name, () => defaultValue);
      if (field.options.isEmpty && field.type != 'boolean') {
        final controller = _formControllers.putIfAbsent(
          field.name,
          () => TextEditingController(
            text: defaultValue == null ? '' : defaultValue.toString(),
          ),
        );
        if (controller.text.isEmpty && defaultValue != null) {
          controller.text = defaultValue.toString();
        }
      }
    }
  }

  void _disposeFormControllers() {
    for (final controller in _formControllers.values) {
      controller.dispose();
    }
    _formControllers.clear();
    _formValues.clear();
  }

  bool _isFormValid(List<_ElicitationField> fields) {
    for (final field in fields) {
      if (!field.required) continue;
      final value = _formValue(field);
      if (value == null || (value is String && value.trim().isEmpty)) {
        return false;
      }
    }
    return true;
  }

  dynamic _formValue(_ElicitationField field) {
    if (field.type == 'boolean' || field.options.isNotEmpty) {
      return _formValues[field.name];
    }
    return _formControllers[field.name]?.text ?? _formValues[field.name];
  }

  Map<String, dynamic> _elicitationContent(List<_ElicitationField> fields) {
    final content = <String, dynamic>{};
    for (final field in fields) {
      final raw = _formValue(field);
      if (raw == null || (raw is String && raw.trim().isEmpty)) continue;
      final value = raw is String ? raw.trim() : raw;
      content[field.name] = switch (field.type) {
        'integer' => int.tryParse(value.toString()) ?? value,
        'number' => double.tryParse(value.toString()) ?? value,
        'boolean' => value is bool ? value : value.toString() == 'true',
        'array' =>
          value is List
              ? value
              : value
                    .toString()
                    .split(',')
                    .map((item) => item.trim())
                    .where((item) => item.isNotEmpty)
                    .toList(growable: false),
        _ => value,
      };
    }
    return content;
  }

  Widget _buildElicitationField(_ElicitationField field) {
    final label = field.required ? '${field.label} *' : field.label;
    if (field.type == 'boolean') {
      final selected = _formValues[field.name] == true;
      return CheckboxListTile(
        dense: true,
        contentPadding: EdgeInsets.zero,
        title: Text(label, style: const TextStyle(fontSize: 13)),
        subtitle: field.description.isEmpty ? null : Text(field.description),
        value: selected,
        onChanged: _isSubmitting
            ? null
            : (value) => setState(() {
                _formValues[field.name] = value == true;
              }),
      );
    }
    if (field.options.isNotEmpty) {
      final selected = _formValues[field.name]?.toString();
      return DropdownButtonFormField<String>(
        value: field.options.contains(selected) ? selected : null,
        decoration: InputDecoration(
          labelText: label,
          helperText: field.description.isEmpty ? null : field.description,
          border: const OutlineInputBorder(),
          isDense: true,
        ),
        items: field.options
            .map(
              (option) =>
                  DropdownMenuItem<String>(value: option, child: Text(option)),
            )
            .toList(growable: false),
        onChanged: _isSubmitting
            ? null
            : (value) => setState(() {
                _formValues[field.name] = value;
              }),
      );
    }
    final controller = _formControllers[field.name]!;
    return TextField(
      controller: controller,
      enabled: !_isSubmitting,
      minLines: field.type == 'array' ? 2 : 1,
      maxLines: field.type == 'array' ? 4 : 1,
      keyboardType: switch (field.type) {
        'integer' => TextInputType.number,
        'number' => const TextInputType.numberWithOptions(decimal: true),
        _ when field.format == 'email' => TextInputType.emailAddress,
        _ when field.format == 'uri' => TextInputType.url,
        _ => TextInputType.text,
      },
      decoration: InputDecoration(
        labelText: label,
        hintText: field.description.isEmpty ? null : field.description,
        border: const OutlineInputBorder(),
        isDense: true,
      ),
      onChanged: (_) => setState(() {}),
    );
  }

  Future<void> _persistResponseStatus(
    String status,
    List<String> answers,
  ) async {
    final nextCardData = Map<String, dynamic>.from(widget.cardData)
      ..['status'] = status
      ..['submittedAnswers'] = answers;
    widget.cardData['status'] = status;
    widget.cardData['submittedAnswers'] = answers;

    final conversationId = _asInt(widget.cardData['conversationId']);
    final cardId = (widget.cardData['cardId'] ?? widget.cardData['id'] ?? '')
        .toString()
        .trim();
    if (conversationId != null && cardId.isNotEmpty) {
      await ConversationHistoryService.upsertConversationUiCard(
        conversationId,
        entryId: cardId,
        cardData: nextCardData,
        createdAtMillis: _asInt(widget.cardData['startTime']),
        mode: ConversationMode.agent,
      );
    }
    try {
      final identity = _requestStorageIdentity(widget.cardData);
      await StorageService.setString(
        _requestStorageKey(widget.cardData),
        jsonEncode(<String, dynamic>{
          'identity': identity,
          'status': status,
          'answers': answers,
        }),
      );
    } catch (_) {
      return;
    }
  }
}

class _RequestOptionTile extends StatelessWidget {
  const _RequestOptionTile({
    required this.index,
    required this.option,
    required this.selected,
    required this.enabled,
    required this.onTap,
  });

  final int index;
  final _RequestOption option;
  final bool selected;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final foreground = enabled ? palette.textPrimary : palette.textTertiary;
    final secondary = enabled ? palette.textSecondary : palette.textTertiary;
    final selectedTextColor = context.isDarkTheme
        ? palette.surfacePrimary
        : Colors.white;
    final selectedCircleColor = context.isDarkTheme
        ? palette.textPrimary
        : const Color(0xFF20242B);
    final unselectedCircleBorder = context.isDarkTheme
        ? palette.borderSubtle
        : const Color(0xFFDADDE2);
    return Material(
      key: ValueKey('agent-request-option-row-$index'),
      color: selected
          ? (context.isDarkTheme
                ? palette.surfaceElevated.withValues(alpha: 0.82)
                : const Color(0xFFF1F1F2))
          : Colors.transparent,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        onTap: enabled ? onTap : null,
        borderRadius: BorderRadius.circular(14),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              AnimatedContainer(
                duration: const Duration(milliseconds: 180),
                width: 24,
                height: 24,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: selected ? selectedCircleColor : Colors.transparent,
                  border: selected
                      ? null
                      : Border.all(color: unselectedCircleBorder),
                ),
                child: Text(
                  '$index',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    height: 1,
                    color: selected ? selectedTextColor : secondary,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Wrap(
                  spacing: 10,
                  runSpacing: 2,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    Text(
                      option.label,
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: foreground,
                        height: 1.25,
                      ),
                    ),
                    if (option.description.isNotEmpty)
                      Text(
                        option.description,
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                          color: secondary,
                          height: 1.25,
                        ),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RequestFooter extends StatelessWidget {
  const _RequestFooter({
    required this.kind,
    required this.status,
    required this.answers,
    required this.isPending,
    required this.isSubmitting,
    required this.canSubmit,
    required this.onAccept,
    required this.onDecline,
    required this.onIgnore,
    required this.onSubmit,
  });

  final String kind;
  final String status;
  final List<String> answers;
  final bool isPending;
  final bool isSubmitting;
  final bool canSubmit;
  final VoidCallback onAccept;
  final VoidCallback onDecline;
  final VoidCallback onIgnore;
  final VoidCallback onSubmit;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isEnglish =
        Localizations.maybeLocaleOf(context)?.languageCode == 'en';
    if (isSubmitting) {
      return Align(
        alignment: Alignment.centerRight,
        child: SizedBox(
          width: 18,
          height: 18,
          child: CircularProgressIndicator(
            strokeWidth: 2,
            color: palette.accentPrimary,
          ),
        ),
      );
    }
    if (status != 'pending') {
      return Text(
        answers.isEmpty ? status : '$status: ${answers.join(', ')}',
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          color: palette.textSecondary,
        ),
      );
    }
    if (kind == 'approval') {
      return Row(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          TextButton(
            onPressed: isPending ? onDecline : null,
            style: TextButton.styleFrom(
              minimumSize: const Size(0, 36),
              padding: const EdgeInsets.symmetric(horizontal: 12),
              foregroundColor: palette.textSecondary,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(18),
              ),
            ),
            child: Text(
              isEnglish ? 'Decline' : '拒绝',
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
            ),
          ),
          const SizedBox(width: 8),
          FilledButton(
            onPressed: isPending ? onAccept : null,
            style: FilledButton.styleFrom(
              minimumSize: const Size(0, 36),
              padding: const EdgeInsets.symmetric(horizontal: 16),
              backgroundColor: palette.accentPrimary,
              disabledBackgroundColor: context.isDarkTheme
                  ? palette.surfaceElevated
                  : const Color(0xFFE2E5E9),
              foregroundColor: Colors.white,
              disabledForegroundColor: palette.textTertiary,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(18),
              ),
            ),
            child: Text(
              isEnglish ? 'Accept' : '接受',
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w800),
            ),
          ),
        ],
      );
    }
    return Row(
      mainAxisAlignment: MainAxisAlignment.end,
      children: [
        TextButton(
          onPressed: isPending ? onIgnore : null,
          style: TextButton.styleFrom(
            minimumSize: const Size(0, 36),
            padding: const EdgeInsets.symmetric(horizontal: 10),
            foregroundColor: palette.textSecondary,
            disabledForegroundColor: palette.textTertiary,
          ),
          child: Text(
            isEnglish ? 'Ignore' : '忽略',
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
          ),
        ),
        const SizedBox(width: 8),
        FilledButton(
          onPressed: canSubmit ? onSubmit : null,
          style: FilledButton.styleFrom(
            minimumSize: const Size(0, 36),
            padding: const EdgeInsets.symmetric(horizontal: 16),
            backgroundColor: const Color(0xFF2D99FF),
            disabledBackgroundColor: context.isDarkTheme
                ? palette.surfaceElevated
                : const Color(0xFFE2E5E9),
            foregroundColor: Colors.white,
            disabledForegroundColor: palette.textTertiary,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(18),
            ),
          ),
          child: Text(
            isEnglish ? 'Submit ↵' : '提交 ↵',
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800),
          ),
        ),
      ],
    );
  }
}

class _RequestOption {
  const _RequestOption({
    required this.label,
    required this.value,
    this.description = '',
  });

  final String label;
  final String value;
  final String description;
}

List<_RequestOption> _resolveRequestOptions(Map<String, dynamic> cardData) {
  final raw = _decodeRawParams(cardData['rawParamsJson']);
  final questionId = (cardData['questionId'] ?? '').toString().trim();
  final question = _resolveQuestion(raw, questionId);
  final optionSource =
      question?['options'] ??
      question?['choices'] ??
      question?['items'] ??
      raw['options'] ??
      raw['choices'];
  if (optionSource is! List) {
    return const <_RequestOption>[];
  }
  final seen = <String>{};
  final options = <_RequestOption>[];
  for (final item in optionSource) {
    final option = _requestOptionFromValue(item);
    if (option == null || !seen.add(option.value)) {
      continue;
    }
    options.add(option);
  }
  return options;
}

Map<String, dynamic> _decodeRawParams(dynamic rawParamsJson) {
  final raw = rawParamsJson?.toString().trim() ?? '';
  if (raw.isEmpty) {
    return const <String, dynamic>{};
  }
  try {
    final decoded = jsonDecode(raw);
    if (decoded is Map) {
      return decoded.map((key, value) => MapEntry(key.toString(), value));
    }
  } catch (_) {
    return const <String, dynamic>{};
  }
  return const <String, dynamic>{};
}

Map<String, dynamic>? _resolveQuestion(
  Map<String, dynamic> raw,
  String questionId,
) {
  final questions = raw['questions'];
  if (questions is! List || questions.isEmpty) {
    return null;
  }
  for (final item in questions) {
    final map = _asStringMap(item);
    if (map == null) {
      continue;
    }
    final id = (map['id'] ?? map['questionId'] ?? '').toString();
    if (questionId.isNotEmpty && id == questionId) {
      return map;
    }
  }
  return _asStringMap(questions.first);
}

_RequestOption? _requestOptionFromValue(dynamic value) {
  if (value is String || value is num || value is bool) {
    final label = value.toString().trim();
    return label.isEmpty ? null : _RequestOption(label: label, value: label);
  }
  final map = _asStringMap(value);
  if (map == null) {
    return null;
  }
  final label =
      _firstText([
        map['label'],
        map['title'],
        map['name'],
        map['value'],
        map['id'],
      ]) ??
      '';
  if (label.isEmpty) {
    return null;
  }
  final optionValue =
      _firstText([map['value'], map['id'], map['label']]) ?? label;
  final description =
      _firstText([map['description'], map['detail'], map['subtitle']]) ?? '';
  return _RequestOption(
    label: label,
    value: optionValue,
    description: description,
  );
}

class _ElicitationField {
  const _ElicitationField({
    required this.name,
    required this.label,
    required this.type,
    required this.format,
    required this.description,
    required this.required,
    required this.options,
    required this.defaultValue,
  });

  final String name;
  final String label;
  final String type;
  final String format;
  final String description;
  final bool required;
  final List<String> options;
  final dynamic defaultValue;
}

List<_ElicitationField> _resolveElicitationFields(
  Map<String, dynamic> cardData,
) {
  if (cardData['structuredElicitation'] != true) {
    return const <_ElicitationField>[];
  }
  final raw = _decodeRawParams(cardData['rawParamsJson']);
  final schema = _asStringMap(
    raw['requestedSchema'] ?? raw['requested_schema'],
  );
  final properties = _asStringMap(schema?['properties']);
  if (properties == null || properties.isEmpty) {
    return const <_ElicitationField>[];
  }
  final requiredValues = schema?['required'];
  final requiredNames = requiredValues is List
      ? requiredValues.map((value) => value.toString()).toSet()
      : const <String>{};
  final fields = <_ElicitationField>[];
  for (final entry in properties.entries) {
    final property = _asStringMap(entry.value) ?? const <String, dynamic>{};
    final type = (property['type'] ?? 'string').toString().toLowerCase();
    final enumValues = property['enum'];
    final options = enumValues is List
        ? enumValues
              .map((value) => value?.toString().trim() ?? '')
              .where((value) => value.isNotEmpty)
              .toList(growable: false)
        : const <String>[];
    fields.add(
      _ElicitationField(
        name: entry.key,
        label: (property['title'] ?? entry.key).toString(),
        type: type,
        format: (property['format'] ?? '').toString().toLowerCase(),
        description: (property['description'] ?? '').toString(),
        required: requiredNames.contains(entry.key),
        options: options,
        defaultValue: property['default'],
      ),
    );
  }
  return fields;
}

String _requestStorageKey(Map<String, dynamic> cardData) {
  return 'agent_request_response.${_requestStorageIdentity(cardData)}';
}

String _legacyRequestStorageKey(Map<String, dynamic> cardData) {
  return 'codex_request_response.${_requestStorageIdentity(cardData)}';
}

String _requestStorageIdentity(Map<String, dynamic> cardData) {
  final parts = <String>[
    (cardData['requestId'] ?? '').toString().trim(),
    (cardData['cardId'] ?? cardData['id'] ?? '').toString().trim(),
    (cardData['questionId'] ?? '').toString().trim(),
    (cardData['startTime'] ?? '').toString().trim(),
  ].where((part) => part.isNotEmpty).toList(growable: false);
  if (parts.isEmpty) {
    return 'unknown';
  }
  return parts.join('.');
}

String _requestRenderSignature(Map<String, dynamic> cardData) {
  return [
    _requestStorageIdentity(cardData),
    _cardStatus(cardData),
    (cardData['rawParamsJson'] ?? '').toString(),
  ].join('|');
}

String _cardStatus(Map<String, dynamic> cardData) {
  final normalized = (cardData['status'] ?? 'pending')
      .toString()
      .trim()
      .toLowerCase();
  return normalized.isEmpty ? 'pending' : normalized;
}

bool _isTerminalRequestStatus(String? status) {
  return status == 'submitted' ||
      status == 'ignored' ||
      status == 'accepted' ||
      status == 'declined';
}

String _requestVisibleDetail(String title, String detail) {
  final normalizedTitle = _normalizeComparableText(title);
  final normalizedDetail = _normalizeComparableText(detail);
  if (normalizedDetail.isEmpty || normalizedDetail == normalizedTitle) {
    return '';
  }
  return detail;
}

String _normalizeComparableText(String value) {
  return value.trim().replaceAll(RegExp(r'\s+'), ' ').toLowerCase();
}

Map<String, dynamic>? _asStringMap(dynamic value) {
  if (value is! Map) {
    return null;
  }
  return value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue));
}

List<String> _stringList(dynamic value) {
  if (value is! List) {
    return const <String>[];
  }
  return value
      .map((item) => item?.toString().trim() ?? '')
      .where((item) => item.isNotEmpty)
      .toList(growable: false);
}

String? _firstText(Iterable<dynamic> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) {
      return text;
    }
  }
  return null;
}

int? _asInt(dynamic value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    return value.toInt();
  }
  return int.tryParse(value?.toString() ?? '');
}
