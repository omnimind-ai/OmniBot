import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/webview/webview_page.dart';
import 'package:ui/l10n/l10n.dart';

/// Shared documentation entry while its WebView remains in the compatibility UI.
class UserGuidePage extends StatelessWidget {
  const UserGuidePage({super.key});

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    return WebViewPage(
      url: english
          ? 'https://omnimind-ai.github.io/OmniBot-Docs/en/'
          : 'https://omnimind-ai.github.io/OmniBot-Docs',
      title: context.trLegacy('使用手册'),
      appBarBackClosesPage: true,
    );
  }
}
