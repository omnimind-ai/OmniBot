import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/theme_context.dart';

import '../onboarding_environment_controller.dart';
import '../onboarding_l10n.dart';
import '../widgets/onboarding_common.dart';
import '../widgets/onboarding_footer.dart';

/// Transient page shown while the local environment is being installed.
class OnboardingEnvironmentProgressPage extends StatelessWidget {
  const OnboardingEnvironmentProgressPage({
    super.key,
    required this.controller,
    required this.onBack,
    required this.onContinue,
    required this.onRetry,
  });

  final OnboardingEnvironmentController controller;
  final VoidCallback onBack;
  final VoidCallback onContinue;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final success = controller.ready;
    final failed = controller.failed;
    final accent = failed
        ? Theme.of(context).colorScheme.error
        : success
        ? const Color(0xFF2F8F6B)
        : palette.accentPrimary;
    final progressLabel = '${(controller.progress * 100).round()}%';
    final systemName = controller.distributionName;

    return LayoutBuilder(
      key: const ValueKey('tutorial-environment-progress'),
      builder: (context, constraints) {
        return SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 28, 24, 32),
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minHeight: (constraints.maxHeight - 60).clamp(0, double.infinity),
            ),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 520),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Semantics(
                      label: onbTr(
                        context,
                        '环境配置进度',
                        'Environment setup progress',
                      ),
                      value: progressLabel,
                      child: SizedBox(
                        width: 178,
                        height: 178,
                        child: Stack(
                          alignment: Alignment.center,
                          children: [
                            SizedBox.expand(
                              child: CircularProgressIndicator(
                                key: const ValueKey(
                                  'tutorial-environment-progress-ring',
                                ),
                                value: controller.progress.clamp(0, 1),
                                strokeWidth: 12,
                                strokeCap: StrokeCap.round,
                                backgroundColor: palette.borderSubtle,
                                valueColor: AlwaysStoppedAnimation<Color>(
                                  accent,
                                ),
                              ),
                            ),
                            Text(
                              progressLabel,
                              style: Theme.of(context).textTheme.headlineMedium
                                  ?.copyWith(
                                    color: palette.textPrimary,
                                    fontWeight: FontWeight.w800,
                                    letterSpacing: -0.8,
                                  ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 24),
                    _CrossFadeText(
                      text: _progressTitle(
                        context,
                        systemName: systemName,
                        failed: failed,
                      ),
                      style: Theme.of(context).textTheme.headlineSmall
                          ?.copyWith(
                            color: palette.textPrimary,
                            fontWeight: FontWeight.w800,
                          ),
                    ),
                    const SizedBox(height: 12),
                    _CrossFadeText(
                      text: controller.stage.isEmpty
                          ? onbTr(context, '正在准备环境…', 'Preparing environment…')
                          : _localizedStage(context, controller.stage),
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: palette.textSecondary,
                        height: 1.55,
                      ),
                    ),
                    const SizedBox(height: 22),
                    _MilestoneStepper(controller: controller),
                    const SizedBox(height: 20),
                    _SetupDetails(controller: controller),
                    const SizedBox(height: 16),
                    Text(
                      failed
                          ? onbTr(
                              context,
                              '你的选择已经保留，请根据上方错误处理后重试。',
                              'Your choices are preserved. Resolve the error above and try again.',
                            )
                          : success
                          ? onbTr(
                              context,
                              '无需打开终端，Agent 会直接使用这套环境。',
                              'No terminal is needed. Agents will use this setup directly.',
                            )
                          : onbTr(
                              context,
                              '安装时间取决于网络和所选工具，请保持应用在前台。',
                              'Setup time depends on your connection and selected tools. Keep the app open.',
                            ),
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: palette.textTertiary,
                        height: 1.5,
                      ),
                    ),
                    if (failed) ...[
                      const SizedBox(height: 28),
                      OnboardingPrimaryButton(
                        buttonKey: const ValueKey('tutorial-environment-retry'),
                        label: onbTr(context, '重新配置', 'Try setup again'),
                        icon: LucideIcons.rotateCw,
                        onPressed: onRetry,
                      ),
                    ] else if (controller.isBusy) ...[
                      const SizedBox(height: 28),
                      TextButton.icon(
                        key: const ValueKey('tutorial-environment-cancel'),
                        onPressed: controller.cancelSetup,
                        icon: const Icon(LucideIcons.x),
                        label: Text(
                          onbTr(
                            context,
                            '取消并保留下载进度',
                            'Cancel and keep download progress',
                          ),
                        ),
                      ),
                    ],
                    const SizedBox(height: 10),
                    _buildNavigation(context, success: success, failed: failed),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  String _progressTitle(
    BuildContext context, {
    required String systemName,
    required bool failed,
  }) {
    if (failed) {
      return onbTr(context, '配置没有完成', 'Setup did not finish');
    }
    if (controller.ready) {
      return onbTr(context, '开发环境已准备完成', 'Your development setup is ready');
    }
    return switch (controller.phaseIndex(controller.stage)) {
      0 => onbTr(context, '正在保存配置', 'Saving your setup'),
      1 => onbTr(
        context,
        '正在准备 $systemName 系统',
        'Preparing the $systemName system',
      ),
      2 => onbTr(context, '正在安装开发工具', 'Installing development tools'),
      3 => onbTr(context, '正在验证安装结果', 'Verifying the installation'),
      _ => onbTr(context, '正在完成配置', 'Finishing setup'),
    };
  }

  String _localizedStage(BuildContext context, String value) {
    if (value.contains('基础 Agent CLI 包尚未完成预装')) {
      return onbTr(
        context,
        '终端系统已就绪，接下来配置所选开发工具',
        'The terminal system is ready; preparing your selected tools',
      );
    }
    if (!onboardingIsEnglish(context)) return value;
    const stages = <String, String>{
      '开始准备内嵌终端环境': 'Starting the local terminal environment',
      '正在准备 workspace 和运行目录': 'Preparing the workspace and runtime directories',
      '正在初始化宿主终端运行时': 'Initializing the terminal runtime',
      '正在校验终端环境运行资源': 'Checking runtime resources',
      '正在安装终端环境运行资源': 'Installing runtime resources',
      '宿主终端环境校验完成': 'Runtime resources verified',
      '正在检查所选开发工具': 'Checking selected development tools',
      '正在安装所选开发工具': 'Installing selected development tools',
      '正在验证所选开发工具': 'Verifying selected development tools',
      '开发环境配置完成': 'Development environment ready',
      '所选开发工具已就绪': 'Selected development tools are ready',
    };
    for (final entry in stages.entries) {
      if (value.contains(entry.key)) return entry.value;
    }
    return value;
  }

  Widget _buildNavigation(
    BuildContext context, {
    required bool success,
    required bool failed,
  }) {
    final canLeave = !controller.isBusy;
    final canContinue = canLeave && (success || failed);
    return SizedBox(
      key: const ValueKey('tutorial-environment-progress-navigation'),
      height: 58,
      child: Row(
        children: [
          OnboardingArrowButton(
            buttonKey: const ValueKey('tutorial-bottom-back'),
            icon: LucideIcons.arrowLeft,
            tooltip: onbTr(context, '上一步', 'Previous'),
            onPressed: canLeave ? onBack : null,
          ),
          const Spacer(),
          OnboardingArrowButton(
            buttonKey: failed
                ? const ValueKey('tutorial-skip-environment-progress')
                : const ValueKey('tutorial-environment-continue'),
            icon: LucideIcons.arrowRight,
            tooltip: failed
                ? onbTr(context, '暂不配置环境，先设置模型', 'Set up the environment later')
                : onbTr(context, '继续配置模型', 'Continue to models'),
            onPressed: canContinue ? onContinue : null,
          ),
        ],
      ),
    );
  }
}

/// Cross-fades when [text] changes so stage updates feel smooth.
class _CrossFadeText extends StatelessWidget {
  const _CrossFadeText({required this.text, this.style});

  final String text;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) {
    final reduceMotion =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    return AnimatedSwitcher(
      duration: reduceMotion
          ? Duration.zero
          : const Duration(milliseconds: 180),
      child: Text(
        text,
        key: ValueKey<String>(text),
        textAlign: TextAlign.center,
        style: style,
      ),
    );
  }
}

/// Four milestones joined by animated connector lines.
class _MilestoneStepper extends StatelessWidget {
  const _MilestoneStepper({required this.controller});

  static const double _milestoneDiameter = 30;
  static const double _milestoneRadius = _milestoneDiameter / 2;

  final OnboardingEnvironmentController controller;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final reduceMotion =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    final currentPhase = controller.ready
        ? 4
        : controller.phaseIndex(controller.stage);
    final labels = <String>[
      onbTr(context, '保存选择', 'Save'),
      onbTr(context, '准备系统', 'System'),
      onbTr(context, '安装工具', 'Tools'),
      onbTr(context, '验证', 'Verify'),
    ];

    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        return SizedBox(
          height: 58,
          child: Stack(
            children: [
              Positioned(
                top: 14,
                left: width / 8,
                right: width / 8,
                child: Row(
                  children: List<Widget>.generate(labels.length - 1, (index) {
                    final filled = index < currentPhase || controller.ready;
                    return Expanded(
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: _milestoneRadius,
                        ),
                        child: AnimatedContainer(
                          key: ValueKey(
                            'tutorial-environment-milestone-connector-$index',
                          ),
                          duration: reduceMotion
                              ? Duration.zero
                              : const Duration(milliseconds: 300),
                          height: 2,
                          color: filled
                              ? palette.accentPrimary
                              : palette.borderStrong.withValues(alpha: 0.4),
                        ),
                      ),
                    );
                  }),
                ),
              ),
              Row(
                children: List<Widget>.generate(labels.length, (index) {
                  final completed = index < currentPhase || controller.ready;
                  final active = !controller.ready && index == currentPhase;
                  final failed = controller.failed && active;
                  final color = failed
                      ? Theme.of(context).colorScheme.error
                      : completed || active
                      ? palette.accentPrimary
                      : palette.borderStrong;
                  return Expanded(
                    child: Column(
                      children: [
                        Container(
                          key: ValueKey(
                            'tutorial-environment-milestone-node-$index',
                          ),
                          width: _milestoneDiameter,
                          height: _milestoneDiameter,
                          decoration: BoxDecoration(
                            color: completed
                                ? color
                                : color.withValues(alpha: active ? 0.14 : 0.08),
                            shape: BoxShape.circle,
                            border: Border.all(color: color),
                          ),
                          alignment: Alignment.center,
                          child: active && !failed
                              ? SizedBox(
                                  key: const ValueKey(
                                    'tutorial-environment-active-milestone-spinner',
                                  ),
                                  width: 15,
                                  height: 15,
                                  child: CircularProgressIndicator(
                                    value: reduceMotion ? 0.72 : null,
                                    strokeWidth: 2.2,
                                    color: color,
                                  ),
                                )
                              : Icon(
                                  failed
                                      ? LucideIcons.x
                                      : completed
                                      ? LucideIcons.check
                                      : LucideIcons.circle,
                                  size: 15,
                                  color: completed
                                      ? Theme.of(context).colorScheme.onPrimary
                                      : color,
                                ),
                        ),
                        const SizedBox(height: 7),
                        Text(
                          labels[index],
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.labelSmall
                              ?.copyWith(
                                color: completed || active
                                    ? palette.textPrimary
                                    : palette.textTertiary,
                                fontWeight: completed || active
                                    ? FontWeight.w700
                                    : FontWeight.w500,
                              ),
                        ),
                      ],
                    ),
                  );
                }),
              ),
            ],
          ),
        );
      },
    );
  }
}

/// Flat recap of the pending install: icon rows with hairline separators.
class _SetupDetails extends StatelessWidget {
  const _SetupDetails({required this.controller});

  final OnboardingEnvironmentController controller;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final extras = controller.selectedToolLabels;
    final preset = controller.selectedPreset;
    final tools = extras.isEmpty
        ? preset.contents
        : '${preset.contents} · $extras';

    Widget detailRow({
      required IconData icon,
      required String label,
      required String value,
    }) {
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 17, color: palette.accentPrimary),
          const SizedBox(width: 10),
          SizedBox(
            width: 72,
            child: Text(
              label,
              style: Theme.of(context).textTheme.labelMedium?.copyWith(
                color: palette.textTertiary,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: Theme.of(context).textTheme.labelMedium?.copyWith(
                color: palette.textPrimary,
                fontWeight: FontWeight.w700,
                height: 1.4,
              ),
            ),
          ),
        ],
      );
    }

    Widget hairline() => Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Divider(height: 1, thickness: 1, color: palette.borderSubtle),
    );

    return Column(
      children: [
        detailRow(
          icon: LucideIcons.server,
          label: onbTr(context, '系统', 'System'),
          value: controller.distributionName,
        ),
        hairline(),
        detailRow(
          icon: LucideIcons.codeXml,
          label: onbTr(context, '开发环境', 'Setup'),
          value: onbTr(context, preset.titleZh, preset.titleEn),
        ),
        hairline(),
        detailRow(
          icon: LucideIcons.packageCheck,
          label: onbTr(context, '安装组件', 'Packages'),
          value: tools,
        ),
      ],
    );
  }
}
