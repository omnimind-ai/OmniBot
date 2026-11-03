import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/features/my/pages/account/account_page.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';

/// A dedicated sign-in/register destination used outside account settings.
///
/// It shares the account form and behavior with [AccountPage], while keeping
/// its own route and page chrome so onboarding never navigates into settings.
class AccountAuthPage extends StatelessWidget {
  const AccountAuthPage({super.key});

  String _text(BuildContext context, String zh, String en) {
    return Localizations.localeOf(context).languageCode == 'zh' ? zh : en;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      key: const ValueKey('standalone-account-auth-page'),
      backgroundColor: context.omniPalette.pageBackground,
      appBar: CommonAppBar(
        title: _text(context, '登录与注册', 'Sign in or register'),
        primary: true,
        onBackPressed: () => Navigator.of(context).maybePop(),
      ),
      body: OmniContentWidth(
        child: AccountPage.authOnly(
          onAuthenticated: () => Navigator.of(context).pop(true),
        ),
      ),
    );
  }
}
