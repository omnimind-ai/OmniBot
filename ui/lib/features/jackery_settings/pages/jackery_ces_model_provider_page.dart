import 'package:flutter/material.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/theme/omni_theme_palette.dart';

/// The exhibition connection is provisioned by staff and cannot be edited here.
class JackeryCesModelProviderPage extends StatelessWidget {
  const JackeryCesModelProviderPage({super.key, this.modelConfigured});

  final bool? modelConfigured;

  @override
  Widget build(BuildContext context) {
    const palette = OmniThemePalette.jackeryCes;
    final configured = modelConfigured ?? JackeryCesConfig.modelConfigured;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: AppBar(
        title: const Text('Model providers'),
        backgroundColor: palette.pageBackground,
        foregroundColor: palette.textPrimary,
        leading: IconButton(
          tooltip: 'Back',
          onPressed: () => Navigator.of(context).maybePop(),
          icon: const Icon(Icons.arrow_back_ios_new_rounded, size: 20),
        ),
      ),
      body: SafeArea(
        top: false,
        child: Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 680),
            child: ListView(
              padding: const EdgeInsets.fromLTRB(18, 24, 18, 32),
              children: [
                Container(
                  key: const ValueKey('jackery-read-only-model'),
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: palette.surfacePrimary,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: JackeryCesConfig.border),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Icon(
                        Icons.memory_rounded,
                        color: JackeryCesConfig.accent,
                      ),
                      const SizedBox(height: 16),
                      const Text(
                        JackeryCesConfig.modelDisplayName,
                        style: TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w600,
                          color: JackeryCesConfig.text,
                        ),
                      ),
                      const SizedBox(height: 10),
                      Text(
                        configured ? 'Configuration ready' : 'AI unavailable',
                        key: const ValueKey(
                          'jackery-model-configuration-status',
                        ),
                        style: const TextStyle(
                          fontSize: 14,
                          color: JackeryCesConfig.text,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        configured
                            ? 'Available when the device is connected. Live connection and AI status are shown on the home screen.'
                            : 'Please ask the exhibition staff to connect and configure the device.',
                        style: const TextStyle(
                          fontSize: 13,
                          height: 1.5,
                          color: JackeryCesConfig.text,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
