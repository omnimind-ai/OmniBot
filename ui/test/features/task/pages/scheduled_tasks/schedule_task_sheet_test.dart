import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/scheduled_tasks/widgets/schedule_task_sheet.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/scheduled_task.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';
import 'package:ui/widgets/omni_glass.dart';

void main() {
  testWidgets(
    'short Pad sheet scrolls settings with confirm always reachable',
    (tester) async {
      LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
      addTearDown(LegacyTextLocalizer.clearResolvedLocale);
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(1024, 420);
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      ScheduledTask? result;
      final task = ScheduledTask(
        id: 'pad-short-sheet',
        title: 'A scheduled task with a longer title that wraps on a tablet',
        type: ScheduledTaskType.fixedTime,
        fixedTime: '08:00',
        createdAt: 1,
      );
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () async {
                  result = await ScheduleTaskSheet.show(
                    context: context,
                    existingTask: task,
                  );
                },
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(
        tester.getSize(find.byType(ScheduleTaskSheet)).width,
        lessThanOrEqualTo(640),
      );
      final confirm = find.byKey(const ValueKey('schedule-task-confirm'));
      final confirmBounds = tester.getRect(confirm);
      expect(confirmBounds.bottom, lessThanOrEqualTo(420));
      expect(confirmBounds.top, greaterThan(0));
      expect(
        tester
            .getSize(find.byKey(const ValueKey('schedule-task-close')))
            .shortestSide,
        greaterThanOrEqualTo(48),
      );
      final countdown = find.byKey(const ValueKey('schedule-time-mode-1'));
      await tester.ensureVisible(countdown);
      await tester.pumpAndSettle();
      await tester.tap(countdown);
      await tester.pumpAndSettle();
      final increase = find.byTooltip('增加 5 分钟');
      await tester.ensureVisible(increase);
      await tester.pumpAndSettle();
      await tester.tap(increase);
      await tester.pumpAndSettle();
      expect(find.text('35分钟'), findsOneWidget);
      // Resizing keeps the current editor rather than recreating the schedule.
      tester.view.physicalSize = const Size(800, 600);
      await tester.pumpAndSettle();
      expect(find.text('35分钟'), findsOneWidget);
      await tester.tap(confirm);
      await tester.pumpAndSettle();
      expect(result?.id, task.id);
      expect(result?.type, ScheduledTaskType.countdown);
      expect(result?.countdownMinutes, 35);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('countdown input swipe closes only itself and preserves submit', (
    tester,
  ) async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    addTearDown(LegacyTextLocalizer.clearResolvedLocale);
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetViewInsets);
    ScheduledTask? result;
    final existing = ScheduledTask(
      id: 'countdown-input-test',
      title: 'Test task',
      type: ScheduledTaskType.countdown,
      countdownMinutes: 30,
      createdAt: 1,
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () async {
                result = await ScheduleTaskSheet.show(
                  context: context,
                  existingTask: existing,
                );
              },
              child: const Text('Open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('30分钟'));
    await tester.pumpAndSettle();
    await tester.dragFrom(
      tester.getTopLeft(find.byType(OmniGlassPanel)) + const Offset(100, 24),
      const Offset(0, 500),
    );
    await tester.pumpAndSettle();
    expect(find.byType(OmniGlassAlertDialog), findsNothing);
    expect(find.byType(ScheduleTaskSheet), findsOneWidget);
    expect(result, isNull);

    await tester.tap(find.text('30分钟'));
    await tester.pumpAndSettle();
    tester.view.viewInsets = const FakeViewPadding(bottom: 320);
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '45');
    await tester.tap(find.text('确定'));
    await tester.pumpAndSettle();
    expect(find.byType(OmniGlassAlertDialog), findsNothing);
    expect(find.text('45分钟'), findsOneWidget);
    tester.view.viewInsets = const FakeViewPadding();
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认'));
    await tester.pumpAndSettle();
    expect(result?.countdownMinutes, 45);
    expect(tester.takeException(), isNull);
  });

  testWidgets('dragging time mode saves the selected schedule type', (
    tester,
  ) async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    addTearDown(LegacyTextLocalizer.clearResolvedLocale);
    ScheduledTask? result;
    final existing = ScheduledTask(
      id: 'scheduled-segment-test',
      title: 'Test task',
      type: ScheduledTaskType.fixedTime,
      fixedTime: '08:00',
      createdAt: 1,
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () async {
                result = await ScheduleTaskSheet.show(
                  context: context,
                  existingTask: existing,
                );
              },
              child: const Text('Open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
    final first = find.byKey(const ValueKey('schedule-time-mode-0'));
    final second = find.byKey(const ValueKey('schedule-time-mode-1'));
    await tester.drag(
      first,
      tester.getCenter(second) - tester.getCenter(first),
    );
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<OmniSegmentedSlider<int>>(
            find.byType(OmniSegmentedSlider<int>),
          )
          .value,
      1,
    );
    await tester.tap(find.text('确认'));
    await tester.pumpAndSettle();
    expect(result?.type, ScheduledTaskType.countdown);
    expect(result?.countdownMinutes, 30);
    expect(result?.id, existing.id);
    expect(tester.takeException(), isNull);
  });
}
