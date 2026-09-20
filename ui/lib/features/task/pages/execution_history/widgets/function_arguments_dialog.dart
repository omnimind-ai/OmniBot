import 'dart:convert';

import 'package:flutter/material.dart';

/// A small form for the Function's declared inputs. The runtime still owns
/// full schema validation; this prevents invalid basic types from being sent.
class FunctionArgumentsDialog extends StatefulWidget {
  const FunctionArgumentsDialog({super.key, required this.schema});
  final Map<String, dynamic> schema;

  @override
  State<FunctionArgumentsDialog> createState() =>
      _FunctionArgumentsDialogState();
}

class _FunctionArgumentsDialogState extends State<FunctionArgumentsDialog> {
  final _form = GlobalKey<FormState>();
  final _values = <String, String>{};
  late final Map<String, dynamic> _properties;
  late final Set<String> _required;

  @override
  void initState() {
    super.initState();
    _properties = Map<String, dynamic>.from(
      widget.schema['properties'] as Map? ?? {},
    );
    _required = (widget.schema['required'] as List? ?? [])
        .map((v) => v.toString())
        .toSet();
    for (final entry in _properties.entries) {
      final value = (entry.value as Map?)?['default'];
      if (value != null) {
        _values[entry.key] = value is String ? value : jsonEncode(value);
      }
    }
  }

  String text(String zh, String en) =>
      Localizations.localeOf(context).languageCode == 'en' ? en : zh;

  dynamic _parse(String value, String type) => switch (type) {
    'integer' => int.parse(value),
    'number' => num.parse(value),
    'boolean' || 'object' || 'array' => jsonDecode(value),
    _ => value,
  };

  String? _validate(String name, Map schema, String? input) {
    final value = input?.trim() ?? '';
    if (value.isEmpty) {
      return _required.contains(name)
          ? text('请填写此参数', 'This field is required')
          : null;
    }
    final type = schema['type']?.toString() ?? 'string';
    try {
      final parsed = _parse(value, type);
      if ((type == 'boolean' && parsed is! bool) ||
          (type == 'object' && parsed is! Map) ||
          (type == 'array' && parsed is! List) ||
          (parsed is num && !parsed.isFinite)) {
        throw const FormatException();
      }
      if (schema['enum'] is List &&
          !(schema['enum'] as List).contains(parsed)) {
        return text('请选择有效选项', 'Choose a valid option');
      }
    } on FormatException {
      return switch (type) {
        'integer' => text('请输入整数', 'Enter a whole number'),
        'number' => text('请输入有效数字', 'Enter a valid number'),
        'object' => text(
          '请输入 JSON 对象，例如 {"key":"value"}',
          'Enter a JSON object, e.g. {"key":"value"}',
        ),
        'array' => text(
          '请输入 JSON 数组，例如 ["value"]',
          'Enter a JSON array, e.g. ["value"]',
        ),
        _ => text('请输入有效值', 'Enter a valid value'),
      };
    }
    return null;
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(text('填写执行参数', 'Run arguments')),
    content: SingleChildScrollView(
      child: Form(
        key: _form,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final entry in _properties.entries)
              _field(entry.key, Map.from(entry.value as Map? ?? {})),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: Text(text('取消', 'Cancel')),
      ),
      FilledButton(
        onPressed: () {
          if (!_form.currentState!.validate()) return;
          final arguments = <String, dynamic>{};
          for (final entry in _properties.entries) {
            final value = (_values[entry.key] ?? '').trim();
            if (value.isNotEmpty) {
              arguments[entry.key] = _parse(
                value,
                (entry.value as Map)['type']?.toString() ?? 'string',
              );
            }
          }
          Navigator.pop(context, arguments);
        },
        child: Text(text('开始执行', 'Run')),
      ),
    ],
  );

  Widget _field(String name, Map schema) {
    final type = schema['type'];
    final enumValues = schema['enum'] as List?;
    final options = enumValues ?? (type == 'boolean' ? [true, false] : null);
    final label = (schema['title'] ?? name).toString();
    final description = schema['description']?.toString();
    final decoration = InputDecoration(
      labelText: _required.contains(name) ? '$label *' : label,
      helperText: description?.isNotEmpty == true ? description : null,
      helperMaxLines: 3,
      errorMaxLines: 3,
    );
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: options != null
          ? DropdownButtonFormField<String>(
              initialValue: _values[name],
              isExpanded: true,
              decoration: decoration,
              items: [
                for (final value in options)
                  DropdownMenuItem(
                    value: value is String ? value : jsonEncode(value),
                    child: Text(
                      value is bool
                          ? (value ? text('是', 'Yes') : text('否', 'No'))
                          : value.toString(),
                    ),
                  ),
              ],
              onChanged: (value) => _values[name] = value ?? '',
              validator: (value) => _validate(name, schema, value),
            )
          : TextFormField(
              initialValue: _values[name],
              decoration: decoration,
              keyboardType: type == 'integer' || type == 'number'
                  ? const TextInputType.numberWithOptions(
                      signed: true,
                      decimal: true,
                    )
                  : TextInputType.text,
              minLines: 1,
              maxLines: type == 'object' || type == 'array' ? 4 : 1,
              onChanged: (value) => _values[name] = value,
              validator: (value) => _validate(name, schema, value),
            ),
    );
  }
}
