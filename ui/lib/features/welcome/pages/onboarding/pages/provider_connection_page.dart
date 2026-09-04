import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/theme_context.dart';

import '../onboarding_l10n.dart';
import '../onboarding_provider_controller.dart';
import '../widgets/onboarding_common.dart';
import '../widgets/onboarding_page_scaffold.dart';

/// Step 5: enter the connection details for the chosen provider.
class OnboardingProviderConnectionPage extends StatelessWidget {
  const OnboardingProviderConnectionPage({
    super.key,
    required this.controller,
    required this.scrollController,
    required this.onConnect,
  });

  final OnboardingProviderController controller;
  final ScrollController scrollController;
  final FutureOr<void> Function() onConnect;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isCustom = controller.selectedProviderId == 'custom';
    return OnboardingPageScaffold(
      icon: LucideIcons.plugZap,
      title: onbTr(
        context,
        '连接 ${controller.selectedProvider.label}',
        'Connect ${controller.selectedProvider.label}',
      ),
      description: onbTr(
        context,
        '连接成功后会读取可用模型。密钥仅保存在本机。',
        'Available models are loaded after connection. Your key stays on this device.',
      ),
      scrollController: scrollController,
      children: [
        Row(
          children: [
            Icon(LucideIcons.keyRound, size: 19, color: palette.accentPrimary),
            const SizedBox(width: 9),
            Expanded(
              child: Text(
                onbTr(context, '连接信息', 'Connection details'),
                style: Theme.of(context).textTheme.titleSmall?.copyWith(
                  color: palette.textPrimary,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            OnboardingBadge(
              text: onbTr(context, '仅保存在本机', 'On-device only'),
            ),
          ],
        ),
        const SizedBox(height: 18),
        TextField(
          key: const ValueKey('tutorial-provider-name'),
          controller: controller.nameController,
          enabled: !controller.busy,
          textInputAction: TextInputAction.next,
          decoration: onboardingInputDecoration(
            context,
            label: onbTr(context, '提供商名称', 'Provider name'),
            hint: onbTr(context, '例如：公司模型网关', 'Example: Company model gateway'),
            icon: LucideIcons.building2,
          ),
        ),
        const SizedBox(height: 13),
        TextField(
          key: const ValueKey('tutorial-provider-base-url'),
          controller: controller.baseUrlController,
          enabled: !controller.busy,
          keyboardType: TextInputType.url,
          textInputAction: TextInputAction.next,
          autocorrect: false,
          decoration: onboardingInputDecoration(
            context,
            label: 'API Base URL',
          hint: 'https://api.example.com/v1 或 http://192.168.1.10:8000/v1',
            icon: LucideIcons.globe2,
            helper: isCustom
                ? onbTr(
                    context,
                    '填写兼容 OpenAI 或 Anthropic 协议的 HTTP/HTTPS 地址。',
                    'Use an HTTP/HTTPS endpoint compatible with OpenAI or Anthropic.',
                  )
                : onbTr(
                    context,
                    '已按所选提供商填写，只有使用代理网关时才需要修改。',
                    'Pre-filled for this provider. Change it only when using a gateway.',
                  ),
          ),
        ),
        const SizedBox(height: 13),
        TextField(
          key: const ValueKey('tutorial-provider-api-key'),
          controller: controller.apiKeyController,
          enabled: !controller.busy,
          obscureText: controller.obscureApiKey,
          autocorrect: false,
          enableSuggestions: false,
          onSubmitted: (_) => onConnect(),
          decoration: onboardingInputDecoration(
            context,
            label: 'API Key',
            hint: isCustom ? onbTr(context, '无鉴权时可留空', 'Optional without auth') : 'sk-…',
            icon: LucideIcons.key,
            suffix: IconButton(
              onPressed: controller.toggleObscureApiKey,
              tooltip: controller.obscureApiKey
                  ? onbTr(context, '显示密钥', 'Show key')
                  : onbTr(context, '隐藏密钥', 'Hide key'),
              icon: Icon(
                controller.obscureApiKey ? LucideIcons.eye : LucideIcons.eyeOff,
                size: 19,
              ),
            ),
          ),
        ),
        if (controller.error != null) ...[
          const SizedBox(height: 12),
          OnboardingInlineError(message: controller.error!),
        ],
      ],
    );
  }
}
