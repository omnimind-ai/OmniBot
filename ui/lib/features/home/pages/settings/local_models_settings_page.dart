import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';

/// Settings UI for the curated on-device GGUF model catalog.
class LocalModelsSettingsPage extends StatefulWidget {
  const LocalModelsSettingsPage({super.key});

  @override
  State<LocalModelsSettingsPage> createState() => _LocalModelsSettingsPageState();
}

class _LocalModelsSettingsPageState extends State<LocalModelsSettingsPage> {
  static const _channel = MethodChannel('omnibot/local_models');

  List<Map<String, dynamic>> _catalog = const [];
  List<Map<String, dynamic>> _installed = const [];
  List<Map<String, dynamic>> _downloads = const [];
  Map<String, dynamic> _storage = const {};
  String? _selectedModel;
  String _mode = 'automatic';
  Timer? _poller;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _refresh();
    _poller = Timer.periodic(const Duration(seconds: 1), (_) => _refresh(silent: true));
  }

  @override
  void dispose() {
    _poller?.cancel();
    super.dispose();
  }

  Future<void> _refresh({bool silent = false}) async {
    try {
      final results = await Future.wait<dynamic>([
        _channel.invokeMethod<List<dynamic>>('catalog'),
        _channel.invokeMethod<List<dynamic>>('installed'),
        _channel.invokeMethod<List<dynamic>>('downloads'),
        _channel.invokeMethod<Map<dynamic, dynamic>>('storage'),
        _channel.invokeMethod<String>('selected'),
        _channel.invokeMethod<String>('mode'),
      ]);
      if (!mounted) return;
      setState(() {
        _catalog = _maps(results[0]);
        _installed = _maps(results[1]);
        _downloads = _maps(results[2]);
        _storage = _map(results[3]);
        _selectedModel = results[4] as String?;
        _mode = results[5] as String? ?? 'automatic';
      });
    } catch (e) {
      if (!silent && mounted) _showError('Could not load offline model settings: $e');
    }
  }

  Future<void> _setMode(String mode) async {
    try {
      await _channel.invokeMethod('setMode', {'mode': mode});
      if (mounted) setState(() => _mode = mode);
    } catch (e) {
      _showError('Could not change inference mode: $e');
    }
  }

  Future<void> _download(Map<String, dynamic> model) async {
    try {
      await _channel.invokeMethod('download', {'modelId': model['id']});
      await _refresh();
    } on PlatformException catch (e) {
      _showError(e.message ?? 'Could not start the download.');
    }
  }

  Future<void> _pause(String id) async {
    await _channel.invokeMethod('pause', {'modelId': id});
    await _refresh();
  }

  Future<void> _cancel(String id) async {
    await _channel.invokeMethod('cancel', {'modelId': id});
    await _refresh();
  }

  Future<void> _delete(String id) async {
    try {
      await _channel.invokeMethod('delete', {'modelId': id});
      await _refresh();
    } catch (e) {
      _showError('Could not delete the model: $e');
    }
  }

  Future<void> _useModel(String id) async {
    setState(() => _busy = true);
    try {
      await _channel.invokeMethod('select', {'modelId': id});
      final loaded = await _channel.invokeMethod<bool>('load', {'modelId': id});
      if (loaded != true) throw Exception('The model could not be loaded on this device.');
      if (mounted) {
        setState(() => _selectedModel = id);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Offline model is ready.')),
        );
      }
    } on PlatformException catch (e) {
      _showError(e.message ?? 'The model could not be loaded.');
    } catch (e) {
      _showError('$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _unload() async {
    await _channel.invokeMethod('unload');
    await _refresh();
  }

  void _showError(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), backgroundColor: AppColors.error),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final modelCount = (_storage['totalModels'] as num?)?.toInt() ?? _installed.length;
    final usedBytes = (_storage['totalSize'] as num?)?.toInt() ?? 0;
    final used = _formatBytes(usedBytes);

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: AppBar(
        title: const Text('Offline AI'),
        backgroundColor: palette.pageBackground,
        elevation: 0,
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(18, 4, 18, 32),
          children: [
            _sectionCard(
              context,
              title: 'Inference mode',
              child: DropdownButtonFormField<String>(
                value: _mode,
                decoration: const InputDecoration(
                  labelText: 'How OmniBot should choose a model',
                  border: OutlineInputBorder(),
                ),
                items: const [
                  DropdownMenuItem(value: 'automatic', child: Text('Automatic')),
                  DropdownMenuItem(value: 'online', child: Text('Online')),
                  DropdownMenuItem(value: 'offline', child: Text('Offline')),
                ],
                onChanged: (value) {
                  if (value != null) _setMode(value);
                },
              ),
            ),
            const SizedBox(height: 12),
            _sectionCard(
              context,
              title: 'On-device inference',
              child: const Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('GGUF models are downloaded once and run locally with llama.cpp.'),
                  SizedBox(height: 8),
                  Text('Offline mode never falls back to OpenAI, Gemini, OpenRouter, or another remote model.'),
                ],
              ),
            ),
            const SizedBox(height: 12),
            _sectionCard(
              context,
              title: 'Storage',
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('$modelCount model${modelCount == 1 ? '' : 's'} installed'),
                  Text(used),
                ],
              ),
            ),
            const SizedBox(height: 24),
            Text('Recommended models', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            ..._catalog.map(_buildCatalogCard),
            const SizedBox(height: 24),
            Text('Installed models', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            if (_installed.isEmpty)
              _emptyCard('No models downloaded yet.')
            else
              ..._installed.map(_buildInstalledCard),
          ],
        ),
      ),
    );
  }

  Widget _buildCatalogCard(Map<String, dynamic> model) {
    final id = model['id'].toString();
    final installed = _installed.any((item) => item['id'] == id);
    final download = _downloads.cast<Map<String, dynamic>?>().firstWhere(
          (item) => item?['modelId'] == id,
          orElse: () => null,
        );
    final state = download?['state']?.toString();
    final progress = ((download?['progressPercent'] as num?)?.toDouble() ?? 0) / 100;

    return _sectionCard(
      context,
      title: model['name']?.toString() ?? id,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(model['description']?.toString() ?? ''),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              _chip('${model['quantization']}'),
              _chip(_formatBytes((model['sizeBytes'] as num?)?.toInt() ?? 0)),
              _chip('RAM ${_formatBytes((model['recommendedRamBytes'] as num?)?.toInt() ?? 0)}+'),
              _chip('Apache-2.0'),
            ],
          ),
          if (state == 'downloading' || state == 'verifying' || state == 'paused') ...[
            const SizedBox(height: 14),
            LinearProgressIndicator(value: progress),
            const SizedBox(height: 6),
            Text('${download?['downloadedBytes'] ?? 0} / ${download?['totalBytes'] ?? 0} bytes • ${download?['progressPercent'] ?? 0}%'),
            const SizedBox(height: 8),
            Row(
              children: [
                if (state == 'downloading')
                  TextButton(onPressed: () => _pause(id), child: const Text('Pause')),
                if (state == 'paused')
                  TextButton(onPressed: () => _download(model), child: const Text('Resume')),
                TextButton(onPressed: () => _cancel(id), child: const Text('Cancel')),
              ],
            ),
          ] else if (installed) ...[
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: _busy ? null : () => _useModel(id),
              icon: Icon(_selectedModel == id ? Icons.check : Icons.play_arrow),
              label: Text(_selectedModel == id ? 'Active model' : 'Use this model'),
            ),
          ] else ...[
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: () => _download(model),
              icon: const Icon(Icons.download),
              label: const Text('Download'),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildInstalledCard(Map<String, dynamic> model) {
    final id = model['id'].toString();
    final active = _selectedModel == id;
    return _sectionCard(
      context,
      title: model['displayName']?.toString() ?? id,
      child: Row(
        children: [
          Expanded(
            child: Text(
              '${_formatBytes((model['fileSize'] as num?)?.toInt() ?? 0)} • ${model['quantization'] ?? 'GGUF'}${active ? ' • Active' : ''}',
            ),
          ),
          IconButton(
            tooltip: 'Delete model',
            onPressed: () => _delete(id),
            icon: const Icon(Icons.delete_outline),
          ),
        ],
      ),
    );
  }

  Widget _sectionCard(BuildContext context, {required String title, required Widget child}) {
    final palette = context.omniPalette;
    return Card(
      elevation: 0,
      color: palette.surface,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600)),
            const SizedBox(height: 12),
            child,
          ],
        ),
      ),
    );
  }

  Widget _emptyCard(String text) => Card(
        elevation: 0,
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Text(text, style: const TextStyle(color: Colors.grey)),
        ),
      );

  Widget _chip(String text) => Chip(label: Text(text, style: const TextStyle(fontSize: 11)));

  List<Map<String, dynamic>> _maps(dynamic value) => (value as List<dynamic>? ?? const [])
      .whereType<Map<dynamic, dynamic>>()
      .map((item) => item.map((key, value) => MapEntry(key.toString(), value)))
      .toList();

  Map<String, dynamic> _map(dynamic value) {
    final map = value as Map<dynamic, dynamic>?;
    if (map == null) return {};
    return map.map((key, value) => MapEntry(key.toString(), value));
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(0)} KB';
    if (bytes < 1024 * 1024 * 1024) return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
  }
}
