import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/welcome/pages/onboarding/onboarding_environment_controller.dart';
import 'package:ui/features/welcome/pages/onboarding/pages/environment_progress_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/theme/app_theme.dart';

class _InstallingEnvironmentController extends OnboardingEnvironmentController {
  bool completed = false;

  @override
  bool get isBusy => !completed;

  @override
  bool get ready => completed;

  @override
  double get progress => completed ? 1 : 0.72;

  @override
  String get stage => '正在安装所选开发工具';
}

void main() {
  for (final language in ['zh', 'en']) {
    testWidgets(
      'installation content scrolls independently of navigation in $language',
      (tester) async {
        tester.view.physicalSize = const Size(320, 400);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final controller = _InstallingEnvironmentController();
        addTearDown(controller.dispose);

        Widget buildPage() => MaterialApp(
          theme: AppTheme.lightTheme,
          locale: Locale(language),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: const TextScaler.linear(1.3)),
            child: child!,
          ),
          home: Scaffold(
            body: OnboardingEnvironmentProgressPage(controller: controller),
          ),
        );

        await tester.pumpWidget(buildPage());
        await tester.pump();
        expect(
          find.byKey(const ValueKey('tutorial-environment-progress-ring')),
          findsOneWidget,
        );
        // Navigation is owned by the tutorial's fixed footer, not this scroll view.
        expect(
          find.byKey(const ValueKey('tutorial-environment-cancel')),
          findsNothing,
        );
        expect(
          find.byKey(const ValueKey('tutorial-bottom-back')),
          findsNothing,
        );
        await tester.drag(
          find.byType(SingleChildScrollView),
          const Offset(0, -500),
        );
        await tester.pump();
        final hint = find.text(
          language == 'zh' ? '安装时间取决于网络和所选工具，请保持应用在前台。' : 'Setup time depends on your connection and selected tools. Keep the app open.',
        );
        await tester.ensureVisible(hint);
        expect(hint.hitTestable(), findsOneWidget);
        expect(tester.takeException(), isNull);

        controller.completed = true;
        await tester.pumpWidget(buildPage());
        await tester.pumpAndSettle();
        expect(
          find.text(
            language == 'zh' ? '开发环境已准备完成' : 'Your development setup is ready',
          ),
          findsOneWidget,
        );
        expect(tester.takeException(), isNull);
      },
    );
  }
}
