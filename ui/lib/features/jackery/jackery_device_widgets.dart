import 'dart:io';

import 'package:flutter/material.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/services/jackery_ces_service.dart';

const _slogan = 'Local AI. Powered anywhere.';

class _DeviceStatusBuilder extends StatefulWidget {
  const _DeviceStatusBuilder({required this.builder});
  final Widget Function(BuildContext, JackeryCesService) builder;

  @override
  State<_DeviceStatusBuilder> createState() => _DeviceStatusBuilderState();
}

class _DeviceStatusBuilderState extends State<_DeviceStatusBuilder> {
  @override
  void initState() {
    super.initState();
    JackeryCesService.instance.attach();
  }

  @override
  void dispose() {
    JackeryCesService.instance.detach();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: JackeryCesService.instance,
    builder: (context, _) =>
        widget.builder(context, JackeryCesService.instance),
  );
}

class _DeviceArtwork extends StatelessWidget {
  const _DeviceArtwork({required this.path, this.decorative = false});
  final String? path;
  final bool decorative;

  Widget _unavailable() => decorative
      ? const SizedBox.shrink()
      : const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.image_not_supported_outlined,
              color: Color(0xFF8A8782),
              size: 32,
            ),
            SizedBox(height: 8),
            Text(
              'Device image unavailable',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 11, color: Color(0xFF6B6863)),
            ),
          ],
        );

  @override
  Widget build(BuildContext context) {
    if (path == null || path!.isEmpty) return _unavailable();
    return Image.file(
      File(path!),
      fit: BoxFit.contain,
      excludeFromSemantics: decorative,
      semanticLabel: decorative ? null : 'Jackery CES device',
      errorBuilder: (_, _, _) => _unavailable(),
    );
  }
}

class JackeryDeviceCard extends StatelessWidget {
  const JackeryDeviceCard({super.key});

  @override
  Widget build(BuildContext context) => _DeviceStatusBuilder(
    builder: (context, service) {
      final status = service.status;
      return Material(
        color: Colors.white,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: const BorderSide(color: JackeryCesConfig.border),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => const JackeryDeviceDetailPage(),
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Jackery Power AI',
                  style: TextStyle(
                    color: JackeryCesConfig.text,
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 4),
                const Text(
                  _slogan,
                  style: TextStyle(color: Color(0xFF6B6863), fontSize: 12),
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    SizedBox(
                      width: 92,
                      height: 108,
                      child: _DeviceArtwork(path: service.deviceImagePath),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            status.batteryLabel,
                            style: TextStyle(
                              color: status.batteryPercent == null
                                  ? const Color(0xFF6B6863)
                                  : JackeryCesConfig.accent,
                              fontWeight: FontWeight.w600,
                              fontSize: 16,
                            ),
                          ),
                          if (status.batteryPercent != null) ...[
                            const SizedBox(height: 8),
                            ClipRRect(
                              borderRadius: BorderRadius.circular(4),
                              child: LinearProgressIndicator(
                                value: status.batteryPercent! / 100,
                                color: JackeryCesConfig.accent,
                                backgroundColor: JackeryCesConfig.border,
                                minHeight: 5,
                              ),
                            ),
                          ],
                          const SizedBox(height: 10),
                          Text(
                            status.connectionLabel,
                            style: const TextStyle(
                              fontSize: 12,
                              color: JackeryCesConfig.text,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            status.powerLabel,
                            style: const TextStyle(
                              fontSize: 12,
                              color: JackeryCesConfig.text,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            status.modelLabel,
                            style: const TextStyle(
                              fontSize: 12,
                              color: JackeryCesConfig.text,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                if (!status.connected) ...[
                  const SizedBox(height: 12),
                  const Text(
                    'Connect the CES device to see live battery and AI status.',
                    style: TextStyle(fontSize: 12, color: Color(0xFF6B6863)),
                  ),
                ],
                const SizedBox(height: 12),
                const Row(
                  children: [
                    Text(
                      'View device',
                      style: TextStyle(
                        color: JackeryCesConfig.accent,
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    SizedBox(width: 4),
                    Icon(
                      Icons.arrow_forward,
                      size: 16,
                      color: JackeryCesConfig.accent,
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}

class JackeryDeviceStatusBar extends StatelessWidget {
  const JackeryDeviceStatusBar({super.key});

  @override
  Widget build(BuildContext context) => _DeviceStatusBuilder(
    builder: (context, service) => Semantics(
      label:
          '${service.status.connectionLabel}, ${service.status.batteryLabel}',
      child: Container(
        color: JackeryCesConfig.background,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
        child: Wrap(
          spacing: 8,
          runSpacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Icon(
              service.status.connected ? Icons.link : Icons.link_off,
              size: 15,
              color: const Color(0xFF6B6863),
            ),
            Text(
              service.status.connectionLabel,
              style: const TextStyle(fontSize: 11, color: Color(0xFF6B6863)),
            ),
            const Text('·', style: TextStyle(color: Color(0xFF6B6863))),
            Text(
              service.status.batteryLabel,
              style: const TextStyle(fontSize: 11, color: Color(0xFF6B6863)),
            ),
          ],
        ),
      ),
    ),
  );
}

/// Decorative only: no device parameters, hit targets or detail-page link.
class JackeryChatBackdrop extends StatelessWidget {
  const JackeryChatBackdrop({super.key});

  @override
  Widget build(BuildContext context) => IgnorePointer(
    child: ExcludeSemantics(
      child: _DeviceStatusBuilder(
        builder: (context, service) => Center(
          child: Opacity(
            opacity: 0.065,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                SizedBox(
                  width: 260,
                  height: 200,
                  child: _DeviceArtwork(
                    path: service.deviceImagePath,
                    decorative: true,
                  ),
                ),
                const SizedBox(height: 12),
                const Text(
                  _slogan,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: JackeryCesConfig.text,
                    fontSize: 18,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}

class JackeryDeviceDetailPage extends StatelessWidget {
  const JackeryDeviceDetailPage({super.key});

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: JackeryCesConfig.background,
    appBar: AppBar(
      backgroundColor: JackeryCesConfig.background,
      surfaceTintColor: Colors.transparent,
      leadingWidth: 86,
      leading: TextButton.icon(
        onPressed: () => Navigator.of(context).pop(),
        icon: const Icon(Icons.arrow_back, size: 17),
        label: const Text('Back'),
        style: TextButton.styleFrom(foregroundColor: JackeryCesConfig.text),
      ),
      title: const Text(
        'Device',
        style: TextStyle(color: JackeryCesConfig.text, fontSize: 17),
      ),
      centerTitle: true,
    ),
    body: _DeviceStatusBuilder(
      builder: (context, service) => ListView(
        padding: const EdgeInsets.all(24),
        children: [
          SizedBox(
            height: 240,
            child: _DeviceArtwork(path: service.deviceImagePath),
          ),
          const SizedBox(height: 24),
          const Text(
            'Jackery Power AI',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: JackeryCesConfig.text,
              fontSize: 24,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 8),
          const Text(
            _slogan,
            textAlign: TextAlign.center,
            style: TextStyle(color: Color(0xFF6B6863)),
          ),
          const SizedBox(height: 32),
          _DetailStatus(label: 'Battery', value: service.status.batteryLabel),
          _DetailStatus(label: 'Power', value: service.status.powerLabel),
          _DetailStatus(
            label: 'Connection',
            value: service.status.connectionDetailLabel,
          ),
          _DetailStatus(label: 'AI model', value: service.status.modelLabel),
          if (!service.status.connected) ...[
            const SizedBox(height: 20),
            const Text(
              'Connect the CES device to see live battery and AI status.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Color(0xFF6B6863)),
            ),
          ],
        ],
      ),
    ),
  );
}

class _DetailStatus extends StatelessWidget {
  const _DetailStatus({required this.label, required this.value});
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Container(
    decoration: const BoxDecoration(
      border: Border(bottom: BorderSide(color: JackeryCesConfig.border)),
    ),
    padding: const EdgeInsets.symmetric(vertical: 18),
    child: Row(
      children: [
        Text(
          label,
          style: const TextStyle(
            color: JackeryCesConfig.text,
            fontWeight: FontWeight.w500,
          ),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: Text(
            value,
            textAlign: TextAlign.end,
            style: const TextStyle(color: Color(0xFF6B6863)),
          ),
        ),
      ],
    ),
  );
}
