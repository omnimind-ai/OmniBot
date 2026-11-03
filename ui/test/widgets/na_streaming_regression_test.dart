import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/streaming_text.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';

void main() {
  Widget page(String text, {bool finalText = false}) => MaterialApp(
    home: Scaffold(
      body: SingleChildScrollView(
        child: StreamingText(
          fullText: text,
          isFinal: finalText,
          enableMarkdown: true,
          style: const TextStyle(fontSize: 16),
        ),
      ),
    ),
  );
  String displayed(WidgetTester tester) =>
      tester.widget<OmnibotMarkdownBody>(find.byType(OmnibotMarkdownBody)).data;

  testWidgets('large Markdown burst reveals progressively and catches up', (
    tester,
  ) async {
    final text = '## 回答\n\n${List.filled(80, '内容🌍仍在继续。').join()}\n\n结束';
    await tester.pumpWidget(page(text));
    final initial = displayed(tester);
    expect(initial.length, lessThan(text.length));
    expect(initial, isNot(endsWith('结束')));
    await tester.pump(const Duration(milliseconds: 80));
    expect(displayed(tester).length, greaterThan(initial.length));
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 80));
    }
    expect(displayed(tester), text);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'small fast updates retain the rendered document until the next refresh',
    (tester) async {
      const initial = '## 回答\n\n第一段';
      await tester.pumpWidget(page(initial));
      final body = tester.widget<OmnibotMarkdownBody>(
        find.byType(OmnibotMarkdownBody),
      );
      await tester.pumpWidget(page('$initial第二段'));
      expect(
        identical(body, tester.widget(find.byType(OmnibotMarkdownBody))),
        isTrue,
      );
      await tester.pump(const Duration(milliseconds: 80));
      expect(displayed(tester), '$initial第二段');
    },
  );

  testWidgets(
    'completion preserves the reveal progress and eventually shows the tail',
    (tester) async {
      final text = '## 长回复\n\n${'内容' * 400}';
      await tester.pumpWidget(page(text));
      final before = displayed(tester);
      await tester.pumpWidget(page(text, finalText: true));
      expect(displayed(tester), before);
      await tester.pump(const Duration(milliseconds: 80));
      expect(displayed(tester).length, greaterThan(before.length));
      expect(displayed(tester).length, lessThan(text.length));
      for (var i = 0; i < 20; i++) {
        await tester.pump(const Duration(milliseconds: 80));
      }
      expect(displayed(tester), text);
      await tester.pumpWidget(page('## 新回复\n\n替换'));
      expect(displayed(tester), '## 新回复\n\n替换');
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 1));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('a table burst keeps following prose paced', (tester) async {
    final text =
        '| 项目 | 数值 |\n| --- | --- |\n| A | 1 |\n\n'
        '${'后续正文🌍。' * 200}\n\n尾部标记';
    await tester.pumpWidget(page(text));
    final first = displayed(tester);
    expect(find.byType(Table), findsOneWidget);
    expect(first, isNot(contains('尾部标记')));
    expect(first.length, lessThan(text.length));
    await tester.pump(const Duration(milliseconds: 80));
    expect(displayed(tester).length, greaterThan(first.length));
    expect(displayed(tester).length, lessThan(text.length));
    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 80));
    }
    expect(displayed(tester), text);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a final chunk does not flush a live Markdown backlog', (
    tester,
  ) async {
    final text = '## 回答\n\n${'内容' * 400}';
    await tester.pumpWidget(page(text));
    final before = displayed(tester);
    final completed = '$text\n\n最后一段';
    await tester.pumpWidget(page(completed, finalText: true));
    expect(displayed(tester), before);
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 80));
    }
    expect(displayed(tester), completed);
  });

  testWidgets('completed history appears immediately, including replacement', (
    tester,
  ) async {
    final text = '## 历史\n\n${'历史内容' * 200}';
    await tester.pumpWidget(page(text, finalText: true));
    expect(displayed(tester), text);
    await tester.pumpWidget(page('$text\n\n结尾', finalText: true));
    expect(displayed(tester), '$text\n\n结尾');
    await tester.pumpWidget(page('## 另一条历史\n\n完整内容', finalText: true));
    expect(displayed(tester), '## 另一条历史\n\n完整内容');
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'completion keeps progress when unfinished bold becomes Markdown',
    (tester) async {
      final text = '**未闭合的加粗${'正文' * 400}';
      await tester.pumpWidget(page(text));
      await tester.pump(const Duration(milliseconds: 80));
      expect(find.byType(OmnibotPacedRevealText), findsOneWidget);
      await tester.pumpWidget(page(text, finalText: true));
      expect(displayed(tester).length, lessThan(text.length));
      for (var i = 0; i < 20; i++) {
        await tester.pump(const Duration(milliseconds: 80));
      }
      expect(displayed(tester), text);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('a late heading does not hide prose that was already revealed', (
    tester,
  ) async {
    final prose = '已经显示的正文。' * 30;
    await tester.pumpWidget(page(prose, finalText: true));
    await tester.pumpWidget(page('$prose\n\n## 新标题\n\n继续'));
    expect(displayed(tester).startsWith(prose), isTrue);
    await tester.pumpWidget(const SizedBox());
  });

  test(
    'table reveal boundaries preserve rows without exposing later prose',
    () {
      for (final newline in ['\n', '\r\n']) {
        final header = '| 名称 | 数值 |$newline| --- | --- |';
        final row = '$newline| A🌍 | 1 |';
        final text =
            '$header$row$newline$newline'
            '后续正文';
        expect(omnibotMarkdownRevealBoundary(text, 3), header.length);
        expect(
          omnibotMarkdownRevealBoundary(
            text,
            header.length + newline.length + 3,
          ),
          header.length + row.length,
        );
        final proseStart = text.indexOf('后续正文');
        expect(
          omnibotMarkdownRevealBoundary(text, proseStart + 2),
          proseStart + 2,
        );
      }
    },
  );

  test('table-like code and ordinary pipes keep their reveal offset', () {
    const code = '```text\n| A | B |\n| --- | --- |\n| 1 | 2 |\n```';
    final offset = code.indexOf('A') + 1;
    expect(omnibotMarkdownRevealBoundary(code, offset), offset);
    expect(omnibotMarkdownRevealBoundary('正文 a | b 后续文字', 8), 8);
    expect(omnibotMarkdownRevealBoundary('| A | B |', 3), 3);
  });

  test(
    'fence mask excludes nested-looking tables and respects longer fences',
    () {
      for (final fence in ['```', '~~~~']) {
        final code = '$fence text\n| A | B |\n| --- | --- |\n| 1 | 2 |\n$fence';
        expect(omnibotMarkdownContainsTableCandidate(code), isFalse);
        expect(omnibotMarkdownTrailingTableBlock(code), isNull);
        expect(omnibotMarkdownWithoutTrailingTableCandidate(code), code);
        expect(
          omnibotMarkdownContainsTableCandidate('$code\n\n| A | B |'),
          isTrue,
        );
      }
      expect(
        omnibotMarkdownContainsTableCandidate('````\n```\n| A | B |\n````'),
        isFalse,
      );
      expect(
        omnibotMarkdownContainsTableCandidate('```\n~~~\n| A | B |'),
        isFalse,
      );
    },
  );
}
