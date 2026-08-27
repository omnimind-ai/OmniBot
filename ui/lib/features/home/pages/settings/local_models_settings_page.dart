import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

/// Local Models Management Settings Page
/// Allows users to view, download, and manage offline GGUF models
class LocalModelsSettingsPage extends ConsumerStatefulWidget {
  const LocalModelsSettingsPage({super.key});

  @override
  ConsumerState<LocalModelsSettingsPage> createState() =>
      _LocalModelsSettingsPageState();
}

class _LocalModelsSettingsPageState
    extends ConsumerState<LocalModelsSettingsPage> {
  final TextEditingController _modelUrlController = TextEditingController();
  final TextEditingController _modelNameController = TextEditingController();

  @override
  void dispose() {
    _modelUrlController.dispose();
    _modelNameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Local Models'),
        elevation: 0,
      ),
      body: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Info Section
            Padding(
              padding: const EdgeInsets.all(16.0),
              child: Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Local Model Inference',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Run AI models offline on your device. Download GGUF format models for privacy and fast inference.',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      const SizedBox(height: 16),
                      _buildInfoRow('Format', 'GGUF (llama.cpp)'),
                      _buildInfoRow('Support', 'Text generation, streaming'),
                      _buildInfoRow('Privacy', 'All processing on-device'),
                    ],
                  ),
                ),
              ),
            ),
            // Storage Stats Section
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16.0),
              child: _buildStorageStats(),
            ),
            const SizedBox(height: 16),
            // Downloaded Models Section
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16.0),
              child: Text(
                'Downloaded Models',
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            const SizedBox(height: 8),
            _buildDownloadedModelsList(),
            const SizedBox(height: 16),
            // Add Model Section
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16.0),
              child: Text(
                'Download New Model',
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            const SizedBox(height: 8),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16.0),
              child: _buildAddModelForm(context),
            ),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }

  Widget _buildInfoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4.0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: const TextStyle(fontWeight: FontWeight.w500)),
          Text(value, style: const TextStyle(color: Colors.grey)),
        ],
      ),
    );
  }

  Widget _buildStorageStats() {
    // Placeholder for storage stats widget
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Storage Usage',
              style: Theme.of(context).textTheme.titleSmall,
            ),
            const SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text('Models: 0 models'),
                const Text('0 MB / 1 GB'),
              ],
            ),
            const SizedBox(height: 8),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: 0.0,
                minHeight: 8,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildDownloadedModelsList() {
    // Placeholder for models list
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16.0),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Center(
            child: Text(
              'No models downloaded yet',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Colors.grey,
                  ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildAddModelForm(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextField(
              controller: _modelNameController,
              decoration: InputDecoration(
                labelText: 'Model Name',
                hintText: 'e.g., Llama 2 7B',
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
                contentPadding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 12,
                ),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _modelUrlController,
              decoration: InputDecoration(
                labelText: 'Download URL',
                hintText: 'https://example.com/model.gguf',
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
                contentPadding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 12,
                ),
              ),
              maxLines: 1,
            ),
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                onPressed: () {
                  _showDownloadConfirmDialog(context);
                },
                icon: const Icon(Icons.download),
                label: const Text('Download Model'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _showDownloadConfirmDialog(BuildContext context) {
    final modelName = _modelNameController.text.trim();
    final modelUrl = _modelUrlController.text.trim();

    if (modelName.isEmpty || modelUrl.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please fill in all fields')),
      );
      return;
    }

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Download Model?'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Name: $modelName'),
            const SizedBox(height: 8),
            Text(
              'URL: $modelUrl',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 16),
            const Text(
              'Download will begin in background. You can continue using the app.',
              style: TextStyle(fontSize: 12, color: Colors.grey),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              _startDownload(modelName, modelUrl);
            },
            child: const Text('Download'),
          ),
        ],
      ),
    );
  }

  void _startDownload(String modelName, String modelUrl) {
    // TODO: Implement download logic via platform channel
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Downloading $modelName...'),
        duration: const Duration(seconds: 2),
      ),
    );
  }
}
