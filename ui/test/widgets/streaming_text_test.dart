import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';
import 'package:ui/widgets/streaming_text.dart';

void main() {
  setUp(() {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
  });

  tearDown(() {
    LegacyTextLocalizer.clearResolvedLocale();
  });

  test('detects prose versus structured Markdown', () {
    expect(
      omnibotTextRequiresStructuredMarkdown('在呢~ 😊 小万随时待命。\n今天有什么想让我帮忙的吗？'),
      isFalse,
    );
    expect(omnibotTextRequiresStructuredMarkdown('## 标题'), isTrue);
    expect(omnibotTextRequiresStructuredMarkdown('- 第一项'), isTrue);
    expect(omnibotTextRequiresStructuredMarkdown('使用 `code`'), isTrue);
    expect(omnibotTextRequiresStructuredMarkdown('这是 *强调* 内容'), isTrue);
    expect(omnibotTextRequiresStructuredMarkdown('| 名称 | 状态 |'), isTrue);
  });

  test('limits stable bold streaming to real strong-emphasis prose', () {
    expect(omnibotTextCanUseStableBoldStreaming('这是 **加粗** 内容'), isTrue);
    expect(omnibotTextCanUseStableBoldStreaming('foo__bar'), isFalse);
    expect(omnibotTextCanUseStableBoldStreaming(r'\**literal**'), isFalse);
    expect(omnibotTextCanUseStableBoldStreaming('流式 **加粗'), isTrue);
    expect(
      omnibotTextCanUseStableBoldStreaming('最终 **未闭合', allowUnclosed: false),
      isFalse,
    );
  });

  test(
    'bold spans preserve marker semantics through every streamed prefix',
    () {
      const samples = [
        '',
        '普通正文😀𠮷',
        '**加粗😀** 后缀',
        '前缀 __粗体𠮷__ 后缀',
        '中文__粗体__后缀',
        'foo__bar__baz and **valid**',
        r'\**literal** and **real**',
        r'\\**bold** and \__literal__',
        '**first** then __second__ then **third**',
        '**outer __inside__ text** and __outer **inside** text__',
        '** bad** __ bad__ **good**',
        '**one **two**\n__three__',
        '**tab\t** __line\n__ **valid**',
        'before **未闭合😀',
        'before __未闭合𠮷',
        '**',
        '__',
        '**** and ____',
        r'**one\**two** __three\__four__',
      ];
      const style = TextStyle(fontSize: 16, fontWeight: FontWeight.normal);
      for (final source in samples) {
        for (var end = 0; end <= source.length; end++) {
          final prefix = source.substring(0, end);
          for (final visible in <int>[
            -1,
            prefix.length ~/ 2,
            prefix.length + 2,
          ]) {
            expect(
              _boldRuns(omnibotBuildStreamingBoldSpan(prefix, visible, style)),
              _referenceBoldRuns(prefix, visible),
              reason: 'source=$source, prefix=$end, visible=$visible',
            );
          }
          expect(
            _boldRuns(omnibotBuildStreamingBoldSpan(source, end, style)),
            _referenceBoldRuns(source, end),
            reason: 'source=$source, visible=$end',
          );
        }
      }
    },
  );

  test('stable bold classification preserves closed and unclosed markers', () {
    for (final source in [
      '**first** then __second__ then **third**',
      r'\\**bold**',
      '中文__粗体😀__后缀',
    ]) {
      expect(omnibotTextCanUseStableBoldStreaming(source), isTrue);
      expect(
        omnibotTextCanUseStableBoldStreaming(source, allowUnclosed: false),
        isTrue,
      );
    }
    for (final source in ['before **未闭合😀', 'before __未闭合𠮷']) {
      expect(omnibotTextCanUseStableBoldStreaming(source), isTrue);
      expect(
        omnibotTextCanUseStableBoldStreaming(source, allowUnclosed: false),
        isFalse,
      );
    }
    expect(omnibotTextCanUseStableBoldStreaming('foo__bar__baz'), isFalse);
    expect(omnibotTextCanUseStableBoldStreaming(r'\**literal**'), isFalse);
    expect(omnibotTextCanUseStableBoldStreaming('before **'), isFalse);
  });

  for (final marker in ['**', '__']) {
    test('long $marker prose keeps every bold span and all visible text', () {
      const count = 10000;
      final source = List.filled(count, '正文$marker粗体内容$marker继续正文。').join();
      final expected = List.filled(count, '正文粗体内容继续正文。').join();
      expect(expected.length, greaterThan(100000));
      expect(omnibotTextCanUseStableBoldStreaming(source), isTrue);
      expect(
        omnibotTextCanUseStableBoldStreaming(source, allowUnclosed: false),
        isTrue,
      );
      final span = omnibotBuildStreamingBoldSpan(
        source,
        source.length,
        const TextStyle(fontSize: 16),
      );
      final runs = _boldRuns(span);
      expect(runs.where((run) => run.$2), hasLength(count));
      expect(span.toPlainText(), expected);
      expect(runs.last, ('继续正文。', false));
    });
  }

  testWidgets('small reveal backlogs retain the existing typing cadence', (
    tester,
  ) async {
    await tester.pumpWidget(_revealTestApp('abc', initialVisibleLength: 0));
    await tester.pump(const Duration(milliseconds: 15));
    expect(_revealedPlainText(tester), '');
    await tester.pump(const Duration(milliseconds: 15));
    expect(_revealedPlainText(tester), 'a');
    await tester.pump(const Duration(milliseconds: 30));
    expect(_revealedPlainText(tester), 'ab');
    await tester.pump(const Duration(milliseconds: 30));
    expect(_revealedPlainText(tester), 'abc');
    expect(tester.binding.transientCallbackCount, 0);

    final moderateBurst = List.filled(80, 'x').join();
    await tester.pumpWidget(
      _revealTestApp(moderateBurst, initialVisibleLength: 0),
    );
    await tester.pump(const Duration(milliseconds: 30));
    expect(_revealedPlainText(tester), 'xxxx');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'a stopped reveal restarts without stale elapsed or lost credit',
    (tester) async {
      await tester.pumpWidget(_revealTestApp('a', initialVisibleLength: 0));
      await tester.pump(const Duration(milliseconds: 45));
      expect(_revealedPlainText(tester), 'a');
      await tester.pump(const Duration(milliseconds: 16));
      expect(tester.binding.transientCallbackCount, 0);

      // The first activation earned half a character of unused typing credit.
      await tester.pumpWidget(_revealTestApp('ab', initialVisibleLength: 0));
      await tester.pump(const Duration(milliseconds: 15));
      expect(_revealedPlainText(tester), 'ab');
      expect(tester.binding.transientCallbackCount, 0);

      await tester.pump(const Duration(seconds: 2));
      await tester.pumpWidget(_revealTestApp('abc', initialVisibleLength: 0));
      await tester.pump(const Duration(milliseconds: 30));
      expect(_revealedPlainText(tester), 'abc');
      expect(tester.binding.transientCallbackCount, 0);
    },
  );

  testWidgets('large live bursts finish revealing and stop their ticker', (
    tester,
  ) async {
    final text = '${List.filled(50000, '字').join()}😀尾';
    Widget render({required bool isFinal}) => MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: StreamingText(
            fullText: text,
            isFinal: isFinal,
            style: const TextStyle(fontSize: 14),
          ),
        ),
      ),
    );
    await tester.pumpWidget(render(isFinal: false));
    await tester.pump(const Duration(milliseconds: 16));
    var visible = _revealedPlainText(tester);
    expect(visible.length, greaterThan(1000));
    expect(visible.length, lessThan(text.length));
    await tester.pumpWidget(render(isFinal: true));
    expect(_revealedPlainText(tester), visible);

    // Virtual frame time bounds reveal latency independently of host speed.
    for (var frame = 0; frame < 180 && visible != text; frame++) {
      await tester.pump(const Duration(milliseconds: 16));
      final next = _revealedPlainText(tester);
      expect(text.startsWith(next), isTrue);
      expect(next.length, greaterThanOrEqualTo(visible.length));
      if (next.isNotEmpty) {
        final lastUnit = next.codeUnitAt(next.length - 1);
        expect(lastUnit >= 0xD800 && lastUnit <= 0xDBFF, isFalse);
      }
      visible = next;
    }
    expect(visible, text);
    expect(tester.binding.transientCallbackCount, 0);
    await tester.pump(const Duration(seconds: 1));
    expect(_revealedPlainText(tester), text);
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets('replacement clears burst catch-up and keeps emoji boundaries', (
    tester,
  ) async {
    final burst = List.filled(5000, '旧').join();
    await tester.pumpWidget(_revealTestApp(burst, initialVisibleLength: 0));
    await tester.pump(const Duration(milliseconds: 16));
    expect(_revealedPlainText(tester).length, greaterThan(100));
    await tester.pumpWidget(_revealTestApp('😀中文', initialVisibleLength: 0));
    expect(tester.takeException(), isNull);
    await tester.pump(const Duration(milliseconds: 30));
    expect(_revealedPlainText(tester), '');
    await tester.pump(const Duration(milliseconds: 30));
    expect(_revealedPlainText(tester), '😀');
    await tester.pump(const Duration(milliseconds: 30));
    expect(_revealedPlainText(tester), '😀中');
    await tester.pump(const Duration(milliseconds: 30));
    expect(_revealedPlainText(tester), '😀中文');
    expect(tester.binding.transientCallbackCount, 0);

    await tester.pumpWidget(_revealTestApp('历史全文😀'));
    expect(_revealedPlainText(tester), '历史全文😀');
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets('default history snapshots are visible without a reveal ticker', (
    tester,
  ) async {
    final history = List.filled(1000, '历史正文😀').join();
    await tester.pumpWidget(_revealTestApp(history));
    expect(_revealedPlainText(tester), history);
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets(
    'plain streamed prose keeps one layout across Markdown flush markers',
    (tester) async {
      const text = '在呢~ 😊 小万随时待命。今天有什么想让我帮忙的吗？比如查点什么、整理文件、设个提醒。';

      Widget build({
        required int? markdownRenderedLength,
        bool isFinal = false,
      }) {
        return MaterialApp(
          home: Scaffold(
            body: SizedBox(
              width: 220,
              child: StreamingText(
                enableMarkdown: true,
                markdownRenderedLength: markdownRenderedLength,
                isFinal: isFinal,
                fullText: text,
                style: const TextStyle(fontSize: 16, height: 1.57),
              ),
            ),
          ),
        );
      }

      await tester.pumpWidget(build(markdownRenderedLength: 0));
      await tester.pump(const Duration(seconds: 5));
      final initialSize = tester.getSize(find.byType(StreamingText));
      expect(find.byType(OmnibotMarkdownBody), findsNothing);
      expect(
        find.byKey(const ValueKey('omnibot-plain-reveal')),
        findsOneWidget,
      );

      await tester.pumpWidget(build(markdownRenderedLength: null));
      await tester.pump();
      expect(tester.getSize(find.byType(StreamingText)), initialSize);
      expect(find.byType(OmnibotMarkdownBody), findsNothing);

      await tester.pumpWidget(build(markdownRenderedLength: text.length ~/ 2));
      await tester.pump();
      expect(tester.getSize(find.byType(StreamingText)), initialSize);
      expect(find.byType(OmnibotMarkdownBody), findsNothing);

      await tester.pumpWidget(
        build(markdownRenderedLength: null, isFinal: true),
      );
      await tester.pump();
      expect(tester.getSize(find.byType(StreamingText)), initialSize);
      expect(find.byType(OmnibotMarkdownBody), findsNothing);
    },
  );

  testWidgets(
    'bold streamed prose keeps stable geometry across Markdown flush markers',
    (tester) async {
      const text =
          '这是一段包含 **加粗内容** 的流式回复，后面还有足够多的正文，'
          '用来验证窄宽度换行时整个段落的高度是否稳定。';

      Widget build(int? markdownRenderedLength) {
        return MaterialApp(
          home: Scaffold(
            body: SizedBox(
              width: 220,
              child: StreamingText(
                enableMarkdown: true,
                markdownRenderedLength: markdownRenderedLength,
                isFinal: false,
                fullText: text,
                style: const TextStyle(fontSize: 16, height: 1.57),
              ),
            ),
          ),
        );
      }

      final heights = <double>[];
      for (final marker in <int?>[0, 12, null, 28, text.length]) {
        await tester.pumpWidget(build(marker));
        await tester.pump(const Duration(seconds: 5));
        heights.add(tester.getSize(find.byType(StreamingText)).height);
      }

      expect(heights.toSet(), hasLength(1), reason: 'heights=$heights');
      final richText = tester
          .widgetList<RichText>(
            find.descendant(
              of: find.byType(StreamingText),
              matching: find.byType(RichText),
            ),
          )
          .firstWhere((widget) => widget.text.toPlainText().contains('加粗内容'));
      expect(richText.text.toPlainText(), text.replaceAll('**', ''));

      bool containsBold(InlineSpan span) {
        if (span is! TextSpan) return false;
        if (span.style?.fontWeight == FontWeight.bold) return true;
        return span.children?.any(containsBold) ?? false;
      }

      expect(containsBold(richText.text), isTrue);
    },
  );

  testWidgets(
    'structured streamed content keeps one coherent Markdown layout',
    (tester) async {
      const text = '## 标题\n\n- 第一项包含 **加粗** 内容\n- 第二项';

      Widget build(int? markdownRenderedLength) {
        return MaterialApp(
          home: Scaffold(
            body: SizedBox(
              width: 220,
              child: StreamingText(
                enableMarkdown: true,
                markdownRenderedLength: markdownRenderedLength,
                isFinal: false,
                fullText: text,
                style: const TextStyle(fontSize: 16, height: 1.57),
              ),
            ),
          ),
        );
      }

      final heights = <double>[];
      for (final marker in <int?>[0, text.length ~/ 2, null]) {
        await tester.pumpWidget(build(marker));
        await tester.pump();
        heights.add(tester.getSize(find.byType(StreamingText)).height);
        expect(find.byType(OmnibotMarkdownBody), findsOneWidget);
        expect(
          find.byKey(const ValueKey('omnibot-streaming-tail')),
          findsNothing,
        );
      }

      expect(heights.toSet(), hasLength(1), reason: 'heights=$heights');
    },
  );

  test('detects partial markdown table candidates', () {
    expect(omnibotMarkdownContainsTableCandidate('名称 | 状态'), isTrue);
    expect(omnibotMarkdownContainsTableCandidate('|:---'), isTrue);
    expect(omnibotMarkdownContainsTableCandidate('只是普通段落'), isFalse);
    expect(
      omnibotMarkdownWithoutTrailingTableCandidate('表格如下：\n\n| 序号 | 姓名 |'),
      '表格如下：',
    );
    const renderedTable =
        '好的，以下是一个示例表格：\n\n'
        '| 序号 | 姓名 |\n'
        '| --- | --- |\n'
        '| 1 | 张三 |';
    expect(
      omnibotMarkdownWithoutTrailingTableCandidate(
        '$renderedTable\n\n'
        '好的，以下是一个示例表格：\n'
        '| 序号 | 姓名 |\n'
        '|:---',
      ),
      renderedTable,
    );
  });

  testWidgets('StreamingText keeps surrogate pairs intact during animation', (
    tester,
  ) async {
    const text = '前缀📎后缀';

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: StreamingText(fullText: text, style: TextStyle(fontSize: 14)),
        ),
      ),
    );

    await tester.pump(const Duration(milliseconds: 20));
    expect(tester.takeException(), isNull);

    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);

    final richText = tester.widget<RichText>(
      find.byWidgetPredicate(
        (widget) =>
            widget is RichText && widget.text.toPlainText().contains('前缀'),
      ),
    );
    expect(richText.text.toPlainText(), text);
  });

  testWidgets(
    'StreamingText resets animation state when text is replaced by a new snapshot',
    (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              fullText: '第一版内容',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              fullText: '改写后的全新内容 😀',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      final richText = tester.widget<RichText>(
        find.byWidgetPredicate(
          (widget) =>
              widget is RichText &&
              widget.text.toPlainText().contains('改写后的全新内容'),
        ),
      );
      expect(richText.text.toPlainText(), '改写后的全新内容 😀');
    },
  );

  testWidgets(
    'StreamingText renders bold snapshots after replacement without exceptions',
    (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              fullText: '旧内容',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              fullText: '**新内容** 😀',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      final richText = tester.widget<RichText>(
        find.byWidgetPredicate(
          (widget) =>
              widget is RichText && widget.text.toPlainText().contains('新内容'),
        ),
      );
      expect(richText.text.toPlainText(), '新内容 😀');

      bool containsBold(InlineSpan span) {
        if (span is! TextSpan) return false;
        if (span.style?.fontWeight == FontWeight.bold) return true;
        return span.children?.any(containsBold) ?? false;
      }

      expect(containsBold(richText.text), isTrue);
    },
  );

  testWidgets('StreamingText renders streaming markdown tables safely', (
    tester,
  ) async {
    const snapshots = <String>[
      '表格如下：\n\n| 名称 | 状态 |',
      '表格如下：\n\n| 名称 | 状态 |\n| --- | --- |',
      '表格如下：\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |',
      '表格如下：\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |\n| B | 待处理 |',
      '表格如下：\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |\n| B | 待处理 |\n\n后续说明',
    ];

    for (final snapshot in snapshots) {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              fullText: snapshot,
              style: const TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(tester.takeException(), isNull);
    }

    expect(find.byType(Table), findsOneWidget);
    expect(find.textContaining('后续说明'), findsOneWidget);
  });

  for (final legacyOffset in <int?>[null, 0, 8]) {
    testWidgets(
      'live Markdown updates every table row regardless of old offset $legacyOffset',
      (tester) async {
        const snapshots = [
          '# 结果\n\n| 名称 | 状态 |',
          '# 结果\n\n| 名称 | 状态 |\n| --- | --- |',
          '# 结果\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |',
          '# 结果\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |\n| B | 处理中 |',
          '# 结果\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |\n| B | 处理中 |\n\n后续说明\n\n- 第一项\n\n```dart\nprint(1);\n```',
        ];
        Future<void> render(String text, {bool isFinal = false}) async {
          await tester.pumpWidget(
            MaterialApp(
              home: Scaffold(
                body: SingleChildScrollView(
                  child: StreamingText(
                    fullText: text,
                    enableMarkdown: true,
                    selectable: true,
                    isFinal: isFinal,
                    markdownRenderedLength: legacyOffset,
                    style: const TextStyle(fontSize: 14),
                  ),
                ),
              ),
            ),
          );
          await tester.pump(const Duration(milliseconds: 80));
          expect(tester.takeException(), isNull);
        }

        for (var index = 0; index < snapshots.length; index++) {
          await render(snapshots[index]);
          expect(
            find.byType(Table),
            index == 0 ? findsNothing : findsOneWidget,
          );
          if (index >= 2) expect(find.text('A'), findsOneWidget);
          if (index >= 3) expect(find.text('B'), findsOneWidget);
        }
        expect(find.textContaining('后续说明', findRichText: true), findsWidgets);
        expect(find.textContaining('第一项', findRichText: true), findsWidgets);
        expect(
          find.textContaining('print(1);', findRichText: true),
          findsWidgets,
        );
        // Completion/cancellation changes execution state, not visible table data.
        await render(snapshots.last, isFinal: true);
        expect(find.text('A'), findsOneWidget);
        expect(find.text('B'), findsOneWidget);
        await render('| 新表 | 数值 |\n| --- | --- |\n| 新行 | 42 |');
        expect(find.text('新行'), findsOneWidget);
        expect(find.text('A'), findsNothing);
      },
    );
  }

  testWidgets('a long live table reveals complete rows and eventually its tail', (
    tester,
  ) async {
    final text =
        '| 序号 | 内容 |\n| --- | --- |\n${List.generate(200, (index) => '| $index | 行-$index |').join('\n')}';
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: StreamingText(
              fullText: text,
              enableMarkdown: true,
              isFinal: false,
              style: const TextStyle(fontSize: 14),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(find.byType(Table), findsOneWidget);
    expect(find.text('行-199'), findsNothing);
    final first = tester
        .widget<OmnibotMarkdownBody>(find.byType(OmnibotMarkdownBody))
        .data;
    expect(first, endsWith('|'));
    await tester.pump(const Duration(milliseconds: 80));
    final next = tester
        .widget<OmnibotMarkdownBody>(find.byType(OmnibotMarkdownBody))
        .data;
    expect(next.length, greaterThan(first.length));
    expect(next, endsWith('|'));
    for (var i = 0; i < 40; i++) {
      await tester.pump(const Duration(milliseconds: 80));
    }
    expect(find.text('行-199'), findsOneWidget);
  });

  testWidgets('StreamingText renders real tables before the prompt finishes', (
    tester,
  ) async {
    const tableText =
        '表格如下：\n\n'
        '| 名称 | 状态 |\n'
        '| --- | --- |\n'
        '| A | 通过 |';

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: StreamingText(
            enableMarkdown: true,
            selectable: true,
            isFinal: false,
            fullText: tableText,
            style: TextStyle(fontSize: 14),
          ),
        ),
      ),
    );
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(find.byType(Table), findsOneWidget);
    expect(find.byType(SelectionArea), findsOneWidget);
    expect(find.byType(SelectionContainer), findsWidgets);
    expect(find.text('A'), findsOneWidget);
    expect(find.textContaining('| A | 通过 |'), findsNothing);

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: StreamingText(
            enableMarkdown: true,
            selectable: true,
            isFinal: true,
            fullText: tableText,
            style: TextStyle(fontSize: 14),
          ),
        ),
      ),
    );
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(find.byType(Table), findsOneWidget);
    expect(find.text('A'), findsOneWidget);
  });

  testWidgets(
    'StreamingText switches between plain markdown and table safely',
    (tester) async {
      const snapshots = <String>[
        '先输出普通段落',
        '先输出普通段落\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |',
        '先输出普通段落\n\n| 名称 | 状态 |\n| --- | --- |\n| A | 通过 |\n\n继续输出普通段落',
        '这次又回到普通 **Markdown** 段落',
        '这次又回到普通 **Markdown** 段落\n\n| X | Y |\n| --- | --- |\n| 1 | 2 |',
      ];

      for (final snapshot in snapshots) {
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: StreamingText(
                enableMarkdown: true,
                selectable: true,
                fullText: snapshot,
                style: const TextStyle(fontSize: 14),
              ),
            ),
          ),
        );
        await tester.pump();
        expect(tester.takeException(), isNull);
      }
    },
  );

  testWidgets('StreamingText keeps table and prose in one selection area', (
    tester,
  ) async {
    const tableText =
        '表格如下：\n\n'
        '| 名称 | 状态 |\n'
        '| --- | --- |\n'
        '| A | 通过 |\n'
        '| B | 待处理 |';

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: StreamingText(
            enableMarkdown: true,
            selectable: true,
            fullText: tableText,
            style: TextStyle(fontSize: 14),
          ),
        ),
      ),
    );
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(find.byType(SelectionArea), findsOneWidget);
    expect(
      tester
          .widgetList<SelectionContainer>(find.byType(SelectionContainer))
          .any((widget) => widget.delegate == null),
      isFalse,
    );

    await tester.tap(find.text('A'));
    await tester.pump();
    expect(tester.takeException(), isNull);

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: StreamingText(
            enableMarkdown: true,
            selectable: true,
            fullText: '切回普通 **Markdown** 段落',
            style: TextStyle(fontSize: 14),
          ),
        ),
      ),
    );
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(find.textContaining('Markdown'), findsOneWidget);
  });

  testWidgets(
    'StreamingText preserves selectable complete and incomplete Markdown',
    (tester) async {
      const prefix = '表格如下：\n\n';
      const fullText =
          '$prefix| 名称 | 状态 |\n'
          '| --- | --- |\n'
          '| A | 通过 |';

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              markdownRenderedLength: prefix.length,
              fullText: fullText,
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(find.byType(SelectionArea), findsOneWidget);
      expect(
        find.byKey(const ValueKey('omnibot-streaming-table-tail')),
        findsNothing,
      );
      expect(find.textContaining('| A |'), findsNothing);

      await tester.tap(find.textContaining('表格如下'));
      await tester.pump();
      expect(tester.takeException(), isNull);

      const headerFlushed = '$prefix| 序号 | 姓名 | 部门 | 职位 | 入职日期 | 状态 |\n';
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              markdownRenderedLength: headerFlushed.length,
              fullText: '$headerFlushed|:---',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(find.textContaining('| 序号 |'), findsOneWidget);
      expect(find.textContaining('|:---'), findsOneWidget);

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              markdownRenderedLength: prefix.length,
              fullText: '$fullText\n\n后续说明',
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(find.textContaining('| A |'), findsNothing);
      expect(find.text('后续说明'), findsOneWidget);

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              fullText: fullText,
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(find.byType(Table), findsOneWidget);
      expect(find.byType(SelectionArea), findsOneWidget);
    },
  );

  testWidgets(
    'completed Markdown ignores stale streaming length and renders the full table',
    (tester) async {
      const prefix = '表格如下：\n\n';
      const fullText =
          '$prefix| 名称 | 状态 |\n'
          '| --- | --- |\n'
          '| A | 通过 |';

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              isFinal: true,
              // A persisted intermediate snapshot may retain this value.
              markdownRenderedLength: prefix.length,
              fullText: fullText,
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(find.byType(Table), findsOneWidget);
      expect(find.text('A'), findsOneWidget);
      expect(find.text('通过'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('omnibot-streaming-table-tail')),
        findsNothing,
      );
    },
  );

  testWidgets('long press can copy table cells with surrounding prose', (
    tester,
  ) async {
    const assistCoreChannel = MethodChannel(
      'cn.com.omnimind.bot/AssistCoreEvent',
    );
    MethodCall? clipboardCall;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      if (call.method == 'copyToClipboard') {
        clipboardCall = call;
        return 'SUCCESS';
      }
      return null;
    });
    addTearDown(
      () => messenger.setMockMethodCallHandler(assistCoreChannel, null),
    );

    const tableText =
        '表格前说明\n\n'
        '| 名称 | 状态 |\n'
        '| --- | --- |\n'
        '| A | 通过 |\n'
        '| B | 待处理 |\n\n'
        '表格后说明';
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: Padding(
            padding: EdgeInsets.all(24),
            child: StreamingText(
              enableMarkdown: true,
              selectable: true,
              isFinal: true,
              fullText: tableText,
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      ),
    );
    await tester.pump();

    await tester.longPress(find.text('A'));
    await tester.pump();
    expect(find.text('全选'), findsOneWidget);
    expect(find.text('复制'), findsOneWidget);

    await tester.tap(find.text('全选'));
    await tester.pump();
    await tester.tap(find.text('复制'));
    await tester.pump();

    expect(clipboardCall?.method, 'copyToClipboard');
    final copiedText = (clipboardCall?.arguments as Map?)?['text'] as String?;
    expect(copiedText, contains('表格前说明'));
    expect(copiedText, contains('名称'));
    expect(copiedText, contains('A'));
    expect(copiedText, contains('通过'));
    expect(copiedText, contains('表格后说明'));
  });

  testWidgets(
    'StreamingText preserves incomplete content after a rendered table',
    (tester) async {
      const fullText =
          '好的，以下是一个示例表格：\n\n'
          '| 序号 | 姓名 |\n'
          '| --- | --- |\n'
          '| 1 | 张三 |\n\n'
          '好的，以下是一个示例表格：\n'
          '| 序号 | 姓名 |\n'
          '|:---';

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StreamingText(
              enableMarkdown: true,
              selectable: true,
              fullText: fullText,
              style: TextStyle(fontSize: 14),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(find.byType(Table), findsOneWidget);
      expect(find.byType(SelectionArea), findsOneWidget);
      expect(find.textContaining('| 序号 |'), findsOneWidget);
      expect(find.textContaining('|:---'), findsOneWidget);
    },
  );
}

Widget _revealTestApp(String text, {int? initialVisibleLength}) => MaterialApp(
  home: Scaffold(
    body: SingleChildScrollView(
      child: OmnibotPacedRevealText(
        text: text,
        initialVisibleLength: initialVisibleLength,
        style: const TextStyle(fontSize: 14),
      ),
    ),
  ),
);

String _revealedPlainText(WidgetTester tester) {
  final richText = find.descendant(
    of: find.byType(OmnibotPacedRevealText),
    matching: find.byType(RichText),
  );
  if (richText.evaluate().isEmpty) return '';
  return tester.widget<RichText>(richText).text.toPlainText();
}

List<(String, bool)> _boldRuns(InlineSpan span, [bool inheritedBold = false]) {
  if (span is! TextSpan) return const [];
  final bold = span.style?.fontWeight == null
      ? inheritedBold
      : span.style!.fontWeight == FontWeight.bold;
  return [
    if (span.text?.isNotEmpty == true) (span.text!, bold),
    for (final child in span.children ?? const <InlineSpan>[])
      ..._boldRuns(child, bold),
  ];
}

// The previous scanner is a reference only for small semantic fixtures. Large
// coverage above checks complete output without imposing host timing limits.
List<(String, bool)> _referenceBoldRuns(String source, int requestedVisible) {
  var visibleEnd = requestedVisible.clamp(0, source.length);
  if (visibleEnd > 0 && visibleEnd < source.length) {
    final current = source.codeUnitAt(visibleEnd);
    final previous = source.codeUnitAt(visibleEnd - 1);
    if (current >= 0xDC00 &&
        current <= 0xDFFF &&
        previous >= 0xD800 &&
        previous <= 0xDBFF) {
      visibleEnd--;
    }
  }
  final runs = <(String, bool)>[];
  final ranges =
      <({int openStart, int openEnd, int closeStart, int closeEnd})>[];
  var cursor = 0;
  while (cursor + 1 < source.length) {
    final starIndex = source.indexOf('**', cursor);
    final underscoreIndex = source.indexOf('__', cursor);
    final openStart = switch ((starIndex, underscoreIndex)) {
      (-1, -1) => -1,
      (-1, _) => underscoreIndex,
      (_, -1) => starIndex,
      _ => starIndex < underscoreIndex ? starIndex : underscoreIndex,
    };
    if (openStart < 0) break;
    final marker = source.substring(openStart, openStart + 2);
    final contentStart = openStart + 2;
    if (_referenceEscapedMarker(source, openStart) ||
        (marker == '__' &&
            openStart > 0 &&
            _referenceAsciiWord(source.codeUnitAt(openStart - 1))) ||
        contentStart >= source.length ||
        _referenceWhitespace(source.codeUnitAt(contentStart))) {
      cursor = contentStart;
      continue;
    }
    var closeStart = source.indexOf(marker, contentStart);
    while (closeStart >= 0 &&
        (_referenceEscapedMarker(source, closeStart) ||
            closeStart == contentStart ||
            _referenceWhitespace(source.codeUnitAt(closeStart - 1)) ||
            (marker == '__' &&
                closeStart + 2 < source.length &&
                _referenceAsciiWord(source.codeUnitAt(closeStart + 2))))) {
      closeStart = source.indexOf(marker, closeStart + 2);
    }
    ranges.add((
      openStart: openStart,
      openEnd: contentStart,
      closeStart: closeStart < 0 ? source.length : closeStart,
      closeEnd: closeStart < 0 ? source.length : closeStart + 2,
    ));
    cursor = closeStart < 0 ? source.length : closeStart + 2;
  }

  void append(String text, bool bold) {
    if (text.isNotEmpty) runs.add((text, bold));
  }

  cursor = 0;
  for (final range in ranges) {
    if (cursor >= visibleEnd) break;
    append(
      source.substring(cursor, range.openStart.clamp(cursor, visibleEnd)),
      false,
    );
    if (visibleEnd <= range.openEnd) {
      cursor = visibleEnd;
      break;
    }
    append(
      source.substring(
        range.openEnd,
        range.closeStart.clamp(range.openEnd, visibleEnd),
      ),
      true,
    );
    cursor = range.closeEnd.clamp(0, visibleEnd);
  }
  if (cursor < visibleEnd) append(source.substring(cursor, visibleEnd), false);
  return runs;
}

bool _referenceEscapedMarker(String source, int index) {
  var slashCount = 0;
  for (
    var cursor = index - 1;
    cursor >= 0 && source.codeUnitAt(cursor) == 92;
    cursor--
  ) {
    slashCount++;
  }
  return slashCount.isOdd;
}

bool _referenceWhitespace(int unit) =>
    unit == 9 || unit == 10 || unit == 13 || unit == 32;

bool _referenceAsciiWord(int unit) =>
    (unit >= 48 && unit <= 57) ||
    (unit >= 65 && unit <= 90) ||
    (unit >= 97 && unit <= 122) ||
    unit == 95;
