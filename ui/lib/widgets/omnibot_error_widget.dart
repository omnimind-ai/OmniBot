import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

/// Installs the app-wide rendering fallback.
///
/// Flutter's default [ErrorWidget] is intentionally bright red in debug
/// builds. That is useful for framework development, but it is not an
/// acceptable user-facing state for a chat application: one malformed
/// provider payload must not make the whole conversation look crashed.
void installOmnibotErrorWidget() {
  ErrorWidget.builder = (FlutterErrorDetails details) {
    // Keep the original failure available in device logs without exposing
    // framework diagnostics in the conversation UI.
    debugPrint('[Omni][FlutterErrorWidget] ${details.exception}');
    if (details.stack != null) {
      debugPrint('${details.stack}');
    }
    return const OmnibotSafeErrorWidget();
  };
}

/// A neutral, layout-safe replacement for Flutter's red error widget.
///
/// This intentionally contains no retry or navigation action. The owning
/// feature remains responsible for recovering its data; this widget only
/// prevents a rendering failure from leaking framework chrome to users.
class OmnibotSafeErrorWidget extends StatelessWidget {
  const OmnibotSafeErrorWidget({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      // Keep this independent of Theme: an ErrorWidget can be created while
      // the Theme/MaterialApp itself is being built.
      color: const Color(0xFFF6F8FC),
      alignment: Alignment.center,
      padding: const EdgeInsets.all(16),
      child: Text(
        '内容暂时无法显示',
        textAlign: TextAlign.center,
        style: const TextStyle(color: Color(0xFF667085), fontSize: 14),
      ),
    );
  }
}
