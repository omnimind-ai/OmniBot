import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/na_connector_result.dart';

void main() {
  testWidgets(
    'overflow pagination uses an opaque result id, preserves Unicode and displays text without interpretation',
    (tester) async {
      final offsets = <int>[];
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: NaConnectorResultViewer(
              overflow: const {
                'id': 'result-opaque',
                'retainedCharacters': 16001,
                'sourceTruncated': true,
              },
              readPage: (id, payload) async {
                expect(id, 'result-opaque');
                expect(payload['limit'], 8000);
                final offset = payload['offset'] as int;
                offsets.add(offset);
                return {
                  'id': id,
                  'text': offset == 0 ? '<script>资料😀</script>' : '最后一页',
                  'offset': offset,
                  'nextOffset': offset == 0 ? 8000 : 16001,
                  'retainedCharacters': 16001,
                  'sourceTruncated': true,
                };
              },
            ),
          ),
        ),
      );
      await tester.tap(find.text('查看完整结果'));
      await tester.pumpAndSettle();
      expect(find.text('<script>资料😀</script>'), findsOneWidget);
      expect(find.text('结果超过保留上限，仅能查看已保留的部分。'), findsOneWidget);
      await tester.tap(find.text('下一页'));
      await tester.pumpAndSettle();
      expect(find.text('最后一页'), findsOneWidget);
      await tester.tap(find.text('上一页'));
      await tester.pumpAndSettle();
      expect(offsets, [0, 8000, 0]);
      expect(tester.takeException(), isNull);
    },
  );
}
