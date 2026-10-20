import 'package:flutter/material.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/features/jackery_settings/pages/jackery_ces_model_provider_page.dart';
import 'package:ui/services/jackery_ces_service.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/widgets/predictive_back_route.dart';

/// The two exhibition actions are the entire audience-facing settings surface.
class JackeryCesSettingsPage extends StatefulWidget {
  const JackeryCesSettingsPage({
    super.key,
    this.resetDemo,
    this.modelConfigured,
  });

  final Future<void> Function()? resetDemo;
  final bool? modelConfigured;

  @override
  State<JackeryCesSettingsPage> createState() => _JackeryCesSettingsPageState();
}

class _JackeryCesSettingsPageState extends State<JackeryCesSettingsPage> {
  bool _resetting = false;
  String? _resetFeedback;
  bool _resetFailed = false;

  Future<void> _resetDemo() async {
    if (_resetting) return;
    setState(() {
      _resetting = true;
      _resetFeedback = null;
      _resetFailed = false;
    });
    try {
      await (widget.resetDemo ?? JackeryCesService.resetDemo)();
      if (!mounted) return;
      setState(() {
        _resetFeedback = 'Demo reset. You can start a new conversation.';
      });
    } catch (error, stack) {
      debugPrint('Jackery demo reset failed: $error\n$stack');
      if (!mounted) return;
      setState(() {
        _resetFailed = true;
        _resetFeedback =
            'Reset could not finish. Please ask the exhibition staff to retry.';
      });
    } finally {
      if (mounted) setState(() => _resetting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    const palette = OmniThemePalette.jackeryCes;
    return PopScope(
      canPop: !_resetting,
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        appBar: AppBar(
          title: const Text('Settings'),
          backgroundColor: palette.pageBackground,
          foregroundColor: palette.textPrimary,
          leading: IconButton(
            tooltip: 'Back',
            onPressed: _resetting
                ? null
                : () => Navigator.of(context).maybePop(),
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
                    padding: const EdgeInsets.all(20),
                    decoration: BoxDecoration(
                      color: palette.surfacePrimary,
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: JackeryCesConfig.border),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Ready for the next demo',
                          style: TextStyle(
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                            color: JackeryCesConfig.text,
                          ),
                        ),
                        const SizedBox(height: 10),
                        const Text(
                          'Clear conversations, memories, activity, scheduled tasks and temporary workspace files, then restore the demo defaults.',
                          style: TextStyle(
                            fontSize: 14,
                            height: 1.5,
                            color: JackeryCesConfig.text,
                          ),
                        ),
                        const SizedBox(height: 20),
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton.icon(
                            key: const ValueKey('jackery-reset-demo'),
                            onPressed: _resetting ? null : _resetDemo,
                            style: FilledButton.styleFrom(
                              backgroundColor: palette.accentPrimary,
                              foregroundColor: Colors.white,
                              minimumSize: const Size(48, 48),
                              padding: const EdgeInsets.symmetric(
                                horizontal: 18,
                                vertical: 14,
                              ),
                            ),
                            icon: _resetting
                                ? const SizedBox.square(
                                    dimension: 18,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                      color: JackeryCesConfig.text,
                                    ),
                                  )
                                : const Icon(Icons.restart_alt_rounded),
                            label: Text(
                              _resetting ? 'Resetting demo…' : 'Reset demo',
                            ),
                          ),
                        ),
                        if (_resetFeedback != null) ...[
                          const SizedBox(height: 14),
                          Semantics(
                            liveRegion: true,
                            child: Row(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Icon(
                                  _resetFailed
                                      ? Icons.error_outline_rounded
                                      : Icons.check_circle_outline_rounded,
                                  color: JackeryCesConfig.text,
                                  size: 18,
                                ),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: Text(
                                    _resetFeedback!,
                                    key: const ValueKey(
                                      'jackery-reset-feedback',
                                    ),
                                    style: const TextStyle(
                                      color: JackeryCesConfig.text,
                                      fontSize: 13,
                                      height: 1.5,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(height: 24),
                  ListTile(
                    key: const ValueKey('jackery-model-providers'),
                    enabled: !_resetting,
                    contentPadding: const EdgeInsets.symmetric(horizontal: 4),
                    minTileHeight: 56,
                    title: const Text('Model providers'),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: _resetting
                        ? null
                        : () => Navigator.of(context).push(
                            PredictiveBackMaterialPageRoute<void>(
                              builder: (_) => JackeryCesModelProviderPage(
                                modelConfigured: widget.modelConfigured,
                              ),
                            ),
                          ),
                  ),
                  const Divider(height: 1, color: JackeryCesConfig.border),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
