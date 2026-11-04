import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/services/agent_runtime_service.dart';

/// Opens only an opaque result id through the existing owner-bound Na bridge.
class NaConnectorResultViewer extends StatefulWidget {
  const NaConnectorResultViewer({
    super.key,
    required this.overflow,
    required this.readPage,
  });
  final Map<String, dynamic> overflow;
  final Future<Map<String, dynamic>> Function(
    String id,
    Map<String, dynamic> payload,
  )
  readPage;
  @override
  State<NaConnectorResultViewer> createState() =>
      _NaConnectorResultViewerState();
}

class _NaConnectorResultViewerState extends State<NaConnectorResultViewer> {
  bool _opening = false;
  Future<void> _open() async {
    final id = widget.overflow['id'];
    if (_opening || id is! String || id.isEmpty) return;
    setState(() => _opening = true);
    try {
      await showDialog<void>(
        context: context,
        builder: (_) => _ResultDialog(
          id: id,
          overflow: widget.overflow,
          readPage: widget.readPage,
        ),
      );
    } finally {
      if (mounted) setState(() => _opening = false);
    }
  }

  @override
  Widget build(BuildContext context) => TextButton(
    onPressed: _opening ? null : _open,
    child: const Text('查看完整结果'),
  );
}

class _ResultDialog extends StatefulWidget {
  const _ResultDialog({
    required this.id,
    required this.overflow,
    required this.readPage,
  });
  final String id;
  final Map<String, dynamic> overflow;
  final Future<Map<String, dynamic>> Function(
    String id,
    Map<String, dynamic> payload,
  )
  readPage;
  @override
  State<_ResultDialog> createState() => _ResultDialogState();
}

class _ResultDialogState extends State<_ResultDialog> {
  final _previous = <int>[];
  Map<String, dynamic> _page = {};
  int _offset = 0, _generation = 0;
  bool _loading = true;
  String? _error;
  @override
  void initState() {
    super.initState();
    _load(0);
  }

  @override
  void dispose() {
    ++_generation;
    super.dispose();
  }

  Future<void> _load(
    int offset, {
    bool back = false,
    bool forward = false,
  }) async {
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await widget.readPage(widget.id, {
        'offset': offset,
        'limit': 8000,
      });
      if (!mounted || generation != _generation) return;
      if (page['id'] != widget.id || page['text'] is! String) {
        throw StateError('返回的结果不属于当前操作。');
      }
      setState(() {
        if (forward) _previous.add(_offset);
        if (back && _previous.isNotEmpty) _previous.removeLast();
        _offset = (page['offset'] as num?)?.toInt() ?? offset;
        _page = page;
      });
    } catch (error) {
      if (mounted && generation == _generation) {
        setState(
          () => _error = error is StateError
              ? error.message.toString()
              : error is PlatformException &&
                    error.code == 'AGENT_RUNTIME_CALL_FAILED'
              ? error.message
              : formatAgentRuntimeErrorForUser(
                  error,
                  fallback: '无法读取结果，可能已过期或连接已更改。',
                ),
        );
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final next = (_page['nextOffset'] as num?)?.toInt();
    final retained =
        (_page['retainedCharacters'] as num?)?.toInt() ??
        (widget.overflow['retainedCharacters'] as num?)?.toInt() ??
        0;
    final truncated =
        _page['sourceTruncated'] == true ||
        widget.overflow['sourceTruncated'] == true;
    return AlertDialog(
      title: const Text('操作结果'),
      content: SizedBox(
        width: 640,
        height: MediaQuery.sizeOf(context).height * .55,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (truncated)
              const Padding(
                padding: EdgeInsets.only(bottom: 10),
                child: Text('结果超过保留上限，仅能查看已保留的部分。'),
              ),
            if (_loading) const LinearProgressIndicator(minHeight: 2),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Text(_error!),
              ),
            Expanded(
              child: SingleChildScrollView(
                child: SelectableText(
                  _page['text']?.toString() ?? '',
                  style: const TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 12,
                    height: 1.5,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 10),
            Text(
              '已保留 $retained 字符 · 当前从 ${_offset + 1} 开始',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            Row(
              children: [
                TextButton(
                  onPressed: _loading || _previous.isEmpty
                      ? null
                      : () => _load(_previous.last, back: true),
                  child: const Text('上一页'),
                ),
                TextButton(
                  onPressed:
                      _loading ||
                          next == null ||
                          next <= _offset ||
                          next >= retained
                      ? null
                      : () => _load(next, forward: true),
                  child: const Text('下一页'),
                ),
                if (_error != null)
                  TextButton(
                    onPressed: _loading ? null : () => _load(_offset),
                    child: const Text('重新读取'),
                  ),
              ],
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}
