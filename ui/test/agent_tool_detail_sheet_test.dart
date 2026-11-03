import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_diff_viewer.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_summary_card.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final clipboard = <String>[];

  setUpAll(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    // Optional local review font; ordinary regression runs remain portable.
    const fontPath = String.fromEnvironment('AGENT_TOOL_PREVIEW_FONT');
    if (fontPath.isEmpty) return;
    final font = ByteData.sublistView(await File(fontPath).readAsBytes());
    for (final family in ['Roboto', 'Ahem']) {
      await (FontLoader(family)..addFont(Future.value(font))).load();
    }
    const monoPath = String.fromEnvironment('AGENT_TOOL_PREVIEW_MONO_FONT');
    if (monoPath.isNotEmpty) {
      final mono = ByteData.sublistView(await File(monoPath).readAsBytes());
      await (FontLoader('monospace')..addFont(Future.value(mono))).load();
    }
    await (FontLoader('packages/lucide_icons_flutter/Lucide')..addFont(
          rootBundle.load('packages/lucide_icons_flutter/assets/lucide.ttf'),
        ))
        .load();
    await (FontLoader(
      'MaterialIcons',
    )..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'))).load();
  });

  setUp(() {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    clipboard.clear();
    messenger.setMockMethodCallHandler(AssistsMessageService.assistCore, (
      call,
    ) async {
      if (call.method == 'copyToClipboard') {
        clipboard.add((call.arguments as Map)['text'] as String);
        return 'SUCCESS';
      }
      return null;
    });
  });

  tearDown(() {
    LegacyTextLocalizer.clearResolvedLocale();
    messenger.setMockMethodCallHandler(AssistsMessageService.assistCore, null);
  });

  for (final brightness in Brightness.values) {
    for (final glass in [false, true]) {
      testWidgets(
        'running terminal details are readable: ${brightness.name}, glass=$glass',
        (tester) async {
          _viewport(tester);
          final boundaryKey = GlobalKey();
          final theme = brightness == Brightness.light
              ? AppTheme.lightTheme
              : AppTheme.darkTheme;
          await tester.pumpWidget(
            RepaintBoundary(
              key: boundaryKey,
              child: _app(theme: theme, glass: glass, cardData: _terminalCard),
            ),
          );
          await tester.tap(find.text('Open'));
          await tester.pumpAndSettle();

          final sheet = find.byKey(kAgentToolDetailSheetKey);
          expect(sheet, findsOneWidget);
          // The material must extend behind Android's gesture navigation bar;
          // only its content should stop at the safe-area boundary.
          expect(tester.getRect(sheet).bottom, 844);
          expect(
            tester.getRect(find.byType(AgentToolDetailBody)).bottom,
            lessThanOrEqualTo(820),
          );
          final title = tester.widget<Text>(
            find.descendant(of: sheet, matching: find.text('检查运行环境')),
          );
          final palette = theme.extension<OmniThemePalette>()!;
          expect(
            _contrast(title.style!.color!, palette.surfacePrimary),
            greaterThanOrEqualTo(4.5),
          );
          final text = tester.widgetList<SelectableText>(
            find.descendant(of: sheet, matching: find.byType(SelectableText)),
          );
          expect(text, hasLength(2));
          for (final block in text) {
            expect(
              _contrast(block.style!.color!, _surfaceBehind(tester, block)),
              greaterThanOrEqualTo(7),
            );
          }
          expect(text.first.data, contains('python3 --version'));
          expect(text.last.data, contains('Python 3.13.0'));
          expect(text.last.data, isNot(contains('\u001b')));
          expect(find.text('运行中'), findsOneWidget);
          _expectStatusContrast(tester, '运行中');
          expect(find.byTooltip('复制详情').hitTestable(), findsOneWidget);
          expect(find.byTooltip('关闭'), findsNothing);
          expect(tester.takeException(), isNull);

          await _exportPreview(
            tester,
            boundaryKey,
            'terminal-${brightness.name}-${glass ? 'glass' : 'solid'}',
          );
          tester.view.viewInsets = const FakeViewPadding(bottom: 300);
          tester.view.padding = FakeViewPadding.zero;
          await tester.pumpAndSettle();
          expect(tester.getRect(sheet).bottom, 544);
          expect(
            tester.getRect(find.byType(AgentToolDetailBody)).bottom,
            lessThanOrEqualTo(544),
          );
          expect(find.byTooltip('关闭'), findsNothing);
          expect(find.byTooltip('复制详情').hitTestable(), findsOneWidget);
          expect(tester.takeException(), isNull);
          await _tapOutsideSheet(tester);
          await tester.pumpAndSettle();
          expect(sheet, findsNothing);
        },
      );
    }
  }

  testWidgets('tool card opens a readable structured input and output sheet', (
    tester,
  ) async {
    _viewport(tester);
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'toolTitle': '读取项目配置',
              'toolType': 'mcp',
              'toolName': 'read_config',
              'status': 'success',
              'argsJson': jsonEncode({'path': '/workspace/config.json'}),
              'rawResultJson': jsonEncode({'project': 'OmnibotApp'}),
            },
          ),
        ),
      ),
    );
    await tester.tap(find.text('读取项目配置'));
    await tester.pumpAndSettle();
    expect(find.byKey(kAgentToolDetailSheetKey), findsOneWidget);
    expect(find.text('输入'), findsOneWidget);
    final blocks = tester.widgetList<SelectableText>(
      find.byType(SelectableText),
    );
    expect(blocks.first.data, contains('/workspace/config.json'));
    expect(blocks.last.data, contains('OmnibotApp'));
    for (final block in blocks) {
      expect(
        _contrast(block.style!.color!, _surfaceBehind(tester, block)),
        greaterThanOrEqualTo(7),
      );
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets('long command scrolls while full-copy action remains reachable', (
    tester,
  ) async {
    _viewport(tester);
    final command = List.generate(
      80,
      (index) => 'echo command-$index',
    ).join('\n');
    final output = List.generate(80, (index) => 'output-$index').join('\n');
    await tester.pumpWidget(
      _app(
        cardData: {
          ..._terminalCard,
          'argsJson': jsonEncode({'command': command}),
          'terminalOutput': output,
          'workspaceId': 'conversation-42',
        },
      ),
    );
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
    expect(find.text('打开工作区').hitTestable(), findsOneWidget);
    expect(find.byTooltip('复制详情').hitTestable(), findsOneWidget);
    expect(find.byTooltip('关闭'), findsNothing);
    final scrollable = find
        .descendant(
          of: find.byType(SingleChildScrollView).first,
          matching: find.byType(Scrollable),
        )
        .first;
    final scroll = tester.state<ScrollableState>(scrollable).position;
    expect(scroll.maxScrollExtent, greaterThan(0));
    await tester.drag(
      find.byType(SingleChildScrollView).first,
      const Offset(0, -240),
    );
    await tester.pumpAndSettle();
    expect(scroll.pixels, greaterThan(0));
    expect(find.text('打开工作区').hitTestable(), findsOneWidget);
    expect(find.byTooltip('复制详情').hitTestable(), findsOneWidget);
    expect(find.byTooltip('关闭'), findsNothing);
    await tester.tap(find.byTooltip('复制详情'));
    await tester.pump();
    expect(clipboard.single, contains(command));
    expect(clipboard.single, contains(output));
    expect(tester.takeException(), isNull);
  });

  testWidgets('small viewport and larger text keep controls usable', (
    tester,
  ) async {
    _viewport(tester, width: 320);
    final boundaryKey = GlobalKey();
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundaryKey,
        child: _app(
          glass: true,
          textScale: 1.5,
          cardData: {
            ..._terminalCard,
            'toolTitle': '检查终端工具与工作区文件的完整运行环境',
            'status': 'interrupted',
          },
        ),
      ),
    );
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
    expect(find.byTooltip('复制详情').hitTestable(), findsOneWidget);
    expect(find.byTooltip('关闭'), findsNothing);
    expect(tester.takeException(), isNull);
    await _exportPreview(tester, boundaryKey, 'terminal-small-large-text');
  });

  testWidgets('diff discovered in terminal output retains its full copy', (
    tester,
  ) async {
    _viewport(tester);
    const diff = '''
diff --git a/lib/main.dart b/lib/main.dart
--- a/lib/main.dart
+++ b/lib/main.dart
@@ -1,2 +1,2 @@
-old line
+new line
 same line
''';
    await tester.pumpWidget(
      _app(
        cardData: {
          ..._terminalCard,
          'status': 'success',
          'argsJson': jsonEncode({'command': 'git diff'}),
          'terminalOutput': diff,
        },
      ),
    );
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
    expect(find.byType(AgentDiffViewer), findsOneWidget);
    await tester.tap(find.byTooltip('复制详情'));
    await tester.pump();
    expect(clipboard.single, contains('git diff'));
    expect(clipboard.single, contains(diff.trimRight()));
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'drag handle resizes the sheet and tapping outside dismisses it',
    (tester) async {
      _viewport(tester);
      await tester.pumpWidget(_app(cardData: _terminalCard));
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      final sheet = find.byKey(kAgentToolDetailSheetKey);
      final initial = tester.getRect(sheet);
      await tester.dragFrom(
        Offset(initial.center.dx, initial.top + 12),
        const Offset(0, -90),
      );
      await tester.pumpAndSettle();
      expect(tester.getSize(sheet).height, greaterThan(initial.height + 40));
      expect(find.byTooltip('关闭'), findsNothing);
      await _tapOutsideSheet(tester);
      await tester.pumpAndSettle();
      expect(sheet, findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('terminal result status labels retain readable semantic colors', (
    tester,
  ) async {
    _viewport(tester);
    const labels = {
      'success': '成功',
      'error': '失败',
      'timeout': '超时',
      'interrupted': '中断',
    };
    for (final theme in [AppTheme.lightTheme, AppTheme.darkTheme]) {
      for (final status in labels.entries) {
        await tester.pumpWidget(
          _app(
            theme: theme,
            glass: true,
            cardData: {..._terminalCard, 'status': status.key},
          ),
        );
        await tester.tap(find.text('Open'));
        await tester.pumpAndSettle();
        _expectStatusContrast(tester, status.value);
        expect(tester.takeException(), isNull);
        await _tapOutsideSheet(tester);
        await tester.pumpAndSettle();
      }
    }
  });
}

Future<void> _tapOutsideSheet(WidgetTester tester) async {
  final sheet = tester.getRect(find.byKey(kAgentToolDetailSheetKey));
  await tester.tapAt(Offset(sheet.center.dx, sheet.top - 16));
}

final _terminalCard = <String, dynamic>{
  'toolTitle': '检查运行环境',
  'toolType': 'terminal',
  'toolName': 'terminal_execute',
  'status': 'running',
  'argsJson': jsonEncode({
    'command': 'python3 --version\ngit --version\npwd',
    'workingDirectory': '/workspace',
  }),
  'terminalOutput':
      '\u001b[97mPython 3.13.0\u001b[0m\n'
      '\u001b[32mgit version 2.47.0\u001b[0m\n/workspace',
};

Widget _app({
  required Map<String, dynamic> cardData,
  ThemeData? theme,
  bool glass = false,
  double textScale = 1,
}) => MaterialApp(
  debugShowCheckedModeBanner: false,
  theme: theme ?? AppTheme.lightTheme,
  builder: (context, child) => MediaQuery(
    data: MediaQuery.of(context)
        .copyWith(textScaler: TextScaler.linear(textScale)),
    child: LiquidGlassScope(
      enabled: glass,
      quality: LiquidGlassQuality.ultra,
      child: LiquidGlassHost(enabled: glass, child: child!),
    ),
  ),
  home: Builder(
    builder: (context) => Scaffold(
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 32),
            Text('调用工具看看', style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 24),
            const Text('工作区 /workspace\n\n检查工具版本和当前运行环境'),
            TextButton(
              onPressed: () =>
                  showAgentToolDetailSheet(context, cardData: cardData),
              child: const Text('Open'),
            ),
          ],
        ),
      ),
    ),
  ),
);

void _viewport(WidgetTester tester, {double width = 390}) {
  tester.view.physicalSize = Size(width, 844);
  tester.view.devicePixelRatio = 1;
  tester.view.viewPadding = const FakeViewPadding(bottom: 24);
  tester.view.padding = const FakeViewPadding(bottom: 24);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetViewPadding);
  addTearDown(tester.view.resetPadding);
  addTearDown(tester.view.resetViewInsets);
}

void _expectStatusContrast(WidgetTester tester, String label) {
  final text = tester.widget<Text>(find.text(label));
  expect(
    _contrast(text.style!.color!, _surfaceBehind(tester, text)),
    greaterThanOrEqualTo(4.5),
    reason: '$label should remain readable on its status badge',
  );
}

Color _surfaceBehind(WidgetTester tester, Widget text) {
  final containers = tester.widgetList<Container>(
    find.ancestor(of: find.byWidget(text), matching: find.byType(Container)),
  );
  for (final container in containers) {
    final decoration = container.decoration;
    if (decoration is BoxDecoration && decoration.color?.a == 1) {
      return decoration.color!;
    }
  }
  throw StateError('Selectable details require a stable text surface');
}

double _contrast(Color foreground, Color background) {
  final first = Color.alphaBlend(foreground, background).computeLuminance();
  final second = background.computeLuminance();
  return first > second
      ? (first + 0.05) / (second + 0.05)
      : (second + 0.05) / (first + 0.05);
}

Future<void> _exportPreview(
  WidgetTester tester,
  GlobalKey boundaryKey,
  String name,
) async {
  const output = String.fromEnvironment('AGENT_TOOL_PREVIEW_OUTPUT');
  if (output.isEmpty) return;
  final boundary =
      boundaryKey.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  final image = boundary.toImageSync();
  await tester.runAsync(() async {
    final png = await image.toByteData(format: ui.ImageByteFormat.png);
    await Directory(output).create(recursive: true);
    await File('$output/$name.png').writeAsBytes(png!.buffer.asUint8List());
    image.dispose();
  });
}
