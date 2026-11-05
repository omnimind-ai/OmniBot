import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/theme_context.dart';

import '../onboarding_l10n.dart';
import '../onboarding_provider_controller.dart';
import '../widgets/onboarding_common.dart';
import '../widgets/onboarding_page_scaffold.dart';

/// Review the fetched models and optionally add one manually.
class OnboardingModelInventoryPage extends StatelessWidget {
  const OnboardingModelInventoryPage({
    super.key,
    required this.controller,
    required this.scrollController,
    required this.onAddManualModel,
  });

  final OnboardingProviderController controller;
  final ScrollController scrollController;
  final FutureOr<void> Function() onAddManualModel;

  @override
  Widget build(BuildContext context) {
    return OnboardingPageScaffold(
      icon: LucideIcons.boxes,
      title: onbTr(context, '确认可用模型', 'Review available models'),
      description: onbTr(
        context,
        '若服务没有返回模型列表，可以手动添加文档中的准确模型 ID。',
        'If the service does not return a model list, add an exact model ID from its documentation.',
      ),
      scrollController: scrollController,
      children: [
        if (controller.modelOptions.isEmpty)
          _EmptyModelsHint(connected: controller.connected)
        else ...[
          _ModelsReadyHeader(count: controller.modelOptions.length),
          const SizedBox(height: 13),
          _ModelChips(controller: controller),
        ],
        if (controller.connected) ...[
          const SizedBox(height: 18),
          _ManualModelRow(
            controller: controller,
            onAdd: onAddManualModel,
          ),
        ],
        if (controller.error != null) ...[
          const SizedBox(height: 12),
          OnboardingInlineError(message: controller.error!),
        ],
      ],
    );
  }
}

class _EmptyModelsHint extends StatelessWidget {
  const _EmptyModelsHint({required this.connected});

  final bool connected;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(LucideIcons.packageSearch, size: 20, color: palette.textTertiary),
        const SizedBox(width: 11),
        Expanded(
          child: Text(
            connected
                ? onbTr(
                    context,
                    '还没有可用模型。输入提供商文档中的模型 ID，例如 gpt-4.1-mini。',
                    'No models are available yet. Enter an exact model ID from the provider docs, such as gpt-4.1-mini.',
                  )
                : onbTr(
                    context,
                    '连接提供商后，这里会显示可用于聊天和场景配置的模型。',
                    'Connect a provider to load models for chat and background roles.',
                  ),
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: palette.textSecondary,
              height: 1.55,
            ),
          ),
        ),
      ],
    );
  }
}

class _ModelsReadyHeader extends StatelessWidget {
  const _ModelsReadyHeader({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Row(
      children: [
        const Icon(LucideIcons.circleCheck, size: 19, color: Color(0xFF2F8F6B)),
        const SizedBox(width: 9),
        Expanded(
          child: Text(
            onbTr(context, '已准备 $count 个模型', '$count models ready'),
            style: Theme.of(context).textTheme.titleSmall?.copyWith(
              color: palette.textPrimary,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
      ],
    );
  }
}

class _ModelChips extends StatelessWidget {
  const _ModelChips({required this.controller});

  final OnboardingProviderController controller;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final models = controller.modelOptions;
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        ...models.take(10).map(
          (model) => Container(
            constraints: const BoxConstraints(maxWidth: 260),
            padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 8),
            decoration: BoxDecoration(
              color: palette.surfaceSecondary,
              borderRadius: BorderRadius.circular(999),
            ),
            child: Text(
              model.displayName.trim().isEmpty ? model.id : model.displayName,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: palette.textSecondary,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ),
        if (models.length > 10)
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 8),
            decoration: BoxDecoration(
              color: palette.accentPrimary.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(999),
            ),
            child: Text(
              '+${models.length - 10}',
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: palette.accentPrimary,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
      ],
    );
  }
}

class _ManualModelRow extends StatelessWidget {
  const _ManualModelRow({required this.controller, required this.onAdd});

  final OnboardingProviderController controller;
  final FutureOr<void> Function() onAdd;

  @override
  Widget build(BuildContext context) {
    final enabled = !controller.busy && !controller.sceneModelsSaving;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: TextField(
            key: const ValueKey('tutorial-manual-model'),
            controller: controller.manualModelController,
            enabled: enabled,
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => onAdd(),
            decoration: onboardingInputDecoration(
              context,
              label: onbTr(context, '手动添加模型 ID', 'Add a model ID manually'),
              hint: 'model-name',
              icon: LucideIcons.plus,
            ),
          ),
        ),
        const SizedBox(width: 10),
        SizedBox(
          height: 56,
          child: FilledButton(
            key: const ValueKey('tutorial-add-manual-model'),
            onPressed: enabled ? onAdd : null,
            style: FilledButton.styleFrom(
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
              ),
            ),
            child: Text(onbTr(context, '添加', 'Add')),
          ),
        ),
      ],
    );
  }
}
