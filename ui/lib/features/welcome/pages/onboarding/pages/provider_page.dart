import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/my/pages/account/account_page.dart';
import 'package:ui/theme/theme_context.dart';

import '../onboarding_l10n.dart';
import '../widgets/onboarding_page_scaffold.dart';

/// Sign in or register directly inside the tutorial, using the shared auth form.
class OnboardingProviderPage extends StatelessWidget {
  const OnboardingProviderPage({super.key});

  @override
  Widget build(BuildContext context) {
    final keyboardVisible = MediaQuery.viewInsetsOf(context).bottom > 0;
    return Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (!keyboardVisible)
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    OnboardingPageHeading(
                      icon: LucideIcons.logIn,
                      title: onbTr(context, '登录与注册', 'Sign in or register'),
                    ),
                    const SizedBox(height: 10),
                    Text(
                      onbTr(
                        context,
                        '登录小万账号，使用平台 AI 服务与额度。也可以稍后登录，或配置自己的模型。',
                        'Sign in to use the platform AI service and quota. You can also sign in later or configure your own model.',
                      ),
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: context.omniPalette.textSecondary,
                        height: 1.65,
                      ),
                    ),
                  ],
                ),
              ),
            const Expanded(
              child: AccountPage.authOnly(
                key: ValueKey('tutorial-inline-account-auth'),
                showAuthHeading: false,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
