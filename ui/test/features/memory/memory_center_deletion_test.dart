import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/memory/pages/memory_center/memory_center_page.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/memory_card_list.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/selection_bottom_bar.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<Map<String, Object>> entries;
  late List<MethodCall> deletes;
  bool failDeletion = false;
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    entries = [
      for (final id in ['a', 'b'])
        {
          'id': id,
          'date': '2026-09-06',
          'time': '12:00:00',
          'content': 'Memory $id',
          'timestampMillis': 1788681600000,
        },
    ];
    deletes = [];
    failDeletion = false;
    messenger.setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'getWorkspaceShortMemories':
          return {'items': entries};
        case 'getWorkspaceLongMemory':
          return {'content': '# MEMORY\n- Keep long-term'};
        case 'deleteWorkspaceShortMemories':
          deletes.add(call);
          if (failDeletion)
            throw PlatformException(code: 'DELETE_SHORT_MEMORY_ERROR');
          final targets = ((call.arguments as Map)['items'] as List)
              .cast<Map>();
          entries.removeWhere(
            (entry) => targets.any((target) => target['id'] == entry['id']),
          );
          return {'deletedCount': targets.length};
        default:
          throw StateError('Unexpected method ${call.method}');
      }
    });
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  Future<void> mount(WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(theme: AppTheme.lightTheme, home: const MemoryCenterPage()),
    );
    for (var i = 0; i < 12; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  for (final batch in [false, true]) {
    testWidgets(
      '${batch ? 'batch' : 'single'} deletion confirms and refreshes persisted list',
      (tester) async {
        await mount(tester);
        await tester.longPress(find.text('Memory a'));
        await tester.pump();
        if (batch) {
          await tester.tap(find.text('Memory b'));
          await tester.pump();
        }
        tester
            .widget<SelectionBottomBar>(find.byType(SelectionBottomBar))
            .onDeletePressed!();
        await tester.pump(const Duration(milliseconds: 300));
        expect(deletes, isEmpty);
        expect(find.text('删除短期记忆？'), findsOneWidget);
        await tester.tap(find.text('Delete').last);
        for (var i = 0; i < 6; i++) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expect(deletes.length, 1);
        expect(entries.length, batch ? 0 : 1);
        expect(
          tester
              .widget<MemoryCardList>(find.byType(MemoryCardList))
              .cards
              .length,
          batch ? 0 : 1,
        );
        await tester.pumpWidget(const SizedBox());
        await mount(tester);
        expect(
          tester
              .widget<MemoryCardList>(find.byType(MemoryCardList))
              .cards
              .length,
          batch ? 0 : 1,
        );
        await tester.pumpWidget(const SizedBox());
      },
    );
  }

  testWidgets(
    'cancel preserves selected memory without calling native deletion',
    (tester) async {
      await mount(tester);
      await tester.longPress(find.text('Memory a'));
      await tester.pump();
      tester
          .widget<SelectionBottomBar>(find.byType(SelectionBottomBar))
          .onDeletePressed!();
      await tester.pump(const Duration(milliseconds: 300));
      await tester.tap(find.text('Cancel').last);
      await tester.pump(const Duration(milliseconds: 300));
      expect(deletes, isEmpty);
      expect(entries.length, 2);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('failure reloads without hiding undeleted entries', (
    tester,
  ) async {
    failDeletion = true;
    await mount(tester);
    await tester.longPress(find.text('Memory a'));
    await tester.pump();
    tester
        .widget<SelectionBottomBar>(find.byType(SelectionBottomBar))
        .onDeletePressed!();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tap(find.text('Delete').last);
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(deletes.length, 1);
    expect(entries.length, 2);
    expect(
      tester.widget<MemoryCardList>(find.byType(MemoryCardList)).cards.length,
      2,
    );
    await tester.pumpWidget(const SizedBox());
  });
}
