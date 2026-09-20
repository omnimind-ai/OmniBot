import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/widgets/function_arguments_dialog.dart';
import 'package:ui/l10n/generated/app_localizations.dart';

void main() {
  Future<void> open(
    WidgetTester tester,
    Map<String, dynamic> schema,
    void Function(Map<String, dynamic>?) complete,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () async => complete(
                await showDialog<Map<String, dynamic>>(
                  context: context,
                  builder: (_) => FunctionArgumentsDialog(schema: schema),
                ),
              ),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  testWidgets(
    'invalid numeric input stays visible instead of silently sending a string',
    (tester) async {
      Map<String, dynamic>? result;
      await open(tester, {
        'properties': {
          'count': {'type': 'integer'},
        },
        'required': ['count'],
      }, (v) => result = v);
      await tester.enterText(find.byType(TextFormField), '1.5');
      await tester.tap(find.text('开始执行'));
      await tester.pumpAndSettle();
      expect(find.text('请输入整数'), findsOneWidget);
      expect(result, isNull);
      await tester.enterText(find.byType(TextFormField), '2');
      await tester.tap(find.text('开始执行'));
      await tester.pumpAndSettle();
      expect(result, {'count': 2});
    },
  );

  testWidgets(
    'JSON type mismatch is rejected and corrected object reaches runtime',
    (tester) async {
      Map<String, dynamic>? result;
      await open(tester, {
        'properties': {
          'data': {'type': 'object'},
        },
        'required': ['data'],
      }, (v) => result = v);
      await tester.enterText(find.byType(TextFormField), '[]');
      await tester.tap(find.text('开始执行'));
      await tester.pumpAndSettle();
      expect(find.textContaining('请输入 JSON 对象'), findsOneWidget);
      expect(result, isNull);
      await tester.enterText(find.byType(TextFormField), '{"value":3}');
      await tester.tap(find.text('开始执行'));
      await tester.pumpAndSettle();
      expect(result, {
        'data': {'value': 3},
      });
    },
  );

  testWidgets(
    'false and enum defaults keep their types, optional blank is omitted',
    (tester) async {
      Map<String, dynamic>? result;
      await open(tester, {
        'properties': {
          'enabled': {'type': 'boolean', 'default': false},
          'mode': {
            'type': 'string',
            'enum': ['fast', 'slow'],
            'default': 'fast',
          },
          'note': {'type': 'string'},
        },
      }, (v) => result = v);
      await tester.tap(find.text('开始执行'));
      await tester.pumpAndSettle();
      expect(result, {'enabled': false, 'mode': 'fast'});
    },
  );
}
