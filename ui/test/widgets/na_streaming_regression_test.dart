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
    'final history and replacement never retain a hidden stale tail',
    (tester) async {
      final text = '## 长回复\n\n${'内容' * 400}';
      await tester.pumpWidget(page(text));
      await tester.pumpWidget(page(text, finalText: true));
      expect(displayed(tester), text);
      await tester.pumpWidget(page('## 新回复\n\n替换'));
      expect(displayed(tester), '## 新回复\n\n替换');
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 1));
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
