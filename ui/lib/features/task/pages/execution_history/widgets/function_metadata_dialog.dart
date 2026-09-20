import 'package:flutter/material.dart';

class FunctionMetadataDialog extends StatefulWidget {
  const FunctionMetadataDialog({
    super.key,
    required this.function,
    required this.onSave,
  });
  final Map<String, dynamic> function;
  final Future<Map<String, dynamic>> Function(
    String id,
    String name,
    String description,
  )
  onSave;

  @override
  State<FunctionMetadataDialog> createState() => _FunctionMetadataDialogState();
}

class _FunctionMetadataDialogState extends State<FunctionMetadataDialog> {
  final _form = GlobalKey<FormState>();
  late final _name = TextEditingController(
    text: widget.function['name']?.toString() ?? '',
  );
  late final _description = TextEditingController(
    text: widget.function['description']?.toString() ?? '',
  );
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    _description.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_saving || !_form.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final saved = await widget.onSave(
        widget.function['function_id'].toString(),
        _name.text.trim(),
        _description.text.trim(),
      );
      if (mounted) Navigator.of(context).pop(saved);
    } catch (error) {
      if (mounted) {
        setState(() {
          _saving = false;
          _error = error.toString();
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final zh = Localizations.localeOf(context).languageCode == 'zh';
    String? requiredText(String? value) => value == null || value.trim().isEmpty
        ? (zh ? '请填写此项' : 'Required')
        : null;
    return PopScope(
      canPop: !_saving,
      child: AlertDialog(
        title: Text(zh ? '编辑指令' : 'Edit Function'),
        content: SingleChildScrollView(
          child: Form(
            key: _form,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextFormField(
                  key: const ValueKey('function-edit-name'),
                  controller: _name,
                  enabled: !_saving,
                  maxLength: 120,
                  decoration: InputDecoration(labelText: zh ? '名称' : 'Name'),
                  validator: requiredText,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  key: const ValueKey('function-edit-description'),
                  controller: _description,
                  enabled: !_saving,
                  minLines: 2,
                  maxLines: 4,
                  decoration: InputDecoration(
                    labelText: zh ? '描述' : 'Description',
                  ),
                  validator: requiredText,
                ),
                if (_error != null) ...[
                  const SizedBox(height: 12),
                  Text(
                    _error!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: _saving ? null : () => Navigator.of(context).pop(),
            child: Text(zh ? '取消' : 'Cancel'),
          ),
          FilledButton(
            key: const ValueKey('function-edit-save'),
            onPressed: _saving ? null : _save,
            child: Text(
              _saving ? (zh ? '保存中…' : 'Saving…') : (zh ? '保存' : 'Save'),
            ),
          ),
        ],
      ),
    );
  }
}
