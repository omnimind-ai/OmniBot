import 'package:flutter/material.dart';
import 'package:ui/config/jackery_ces_config.dart';

/// The visitor picker exposes only the provisioned device model. Provider
/// configuration remains in the existing Provider store and Settings surface.
class JackeryModelPicker extends StatelessWidget {
  const JackeryModelPicker({super.key, required this.available, this.onSelect});

  final bool available;
  final VoidCallback? onSelect;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 10, 24, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(
                  color: JackeryCesConfig.border,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 22),
            const Text(
              'Select model',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 16),
            ListTile(
              key: const ValueKey('jackery-on-device-model'),
              contentPadding: const EdgeInsets.symmetric(horizontal: 14),
              tileColor: Colors.white,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
                side: BorderSide(color: JackeryCesConfig.border),
              ),
              leading: Icon(Icons.tune_rounded, color: JackeryCesConfig.accent),
              title: Text(JackeryCesConfig.modelDisplayName),
              subtitle: available
                  ? const Text('Local AI on your device')
                  : const Text('AI unavailable'),
              trailing: available
                  ? Icon(
                      Icons.check_circle_rounded,
                      color: JackeryCesConfig.accent,
                    )
                  : const Icon(
                      Icons.cloud_off_outlined,
                      color: Color(0xFF77736D),
                    ),
              onTap: available ? onSelect : null,
            ),
            if (!available) ...[
              const SizedBox(height: 12),
              const Text('Please ask a staff member to connect the device.'),
            ],
          ],
        ),
      ),
    );
  }
}
