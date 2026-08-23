import 'package:flutter/material.dart';
import 'package:flutter_math_fork/flutter_math.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';

void main() {
  const plainText = '这是普通文本。';
  const inlineSample = r'这是行内公式 $E=mc^2$。';
  const blockSample = r'''
$$
\int_0^1 x^2 dx = \frac{1}{3}
$$
''';
  const inlineWideFractionSample =
      r'复杂分式 $\frac{\frac{a+b+c+d+e+f+g+h}{x+y+z+w}}{\frac{1}{2}+\frac{3}{4}+\frac{5}{6}}$。';
  const mixedSample = r'''
这是行内公式 $E=mc^2$。

$$
\int_0^1 x^2 dx = \frac{1}{3}
$$
''';

  Widget wrap(Widget child) {
    return MaterialApp(
      home: Scaffold(
        body: SingleChildScrollView(
          child: Padding(padding: const EdgeInsets.all(12), child: child),
        ),
      ),
    );
  }

  Future<void> expectNoException(
    WidgetTester tester, {
    required String data,
    bool selectable = false,
    bool useSelectionArea = false,
  }) async {
    await tester.pumpWidget(
      wrap(
        useSelectionArea
            ? SelectionArea(
                child: OmnibotMarkdownBody(
                  data: data,
                  baseStyle: const TextStyle(fontSize: 14),
                  selectable: selectable,
                ),
              )
            : OmnibotMarkdownBody(
                data: data,
                baseStyle: const TextStyle(fontSize: 14),
                selectable: selectable,
              ),
      ),
    );
    await tester.pump();
    expect(tester.takeException(), isNull);
  }

  testWidgets('renders plain text without exception in default mode', (
    tester,
  ) async {
    await expectNoException(tester, data: plainText);
  });

  testWidgets('renders inline math without exception in default mode', (
    tester,
  ) async {
    await expectNoException(tester, data: inlineSample);
  });

  testWidgets('renders wide inline fraction without overflow exception', (
    tester,
  ) async {
    await expectNoException(tester, data: inlineWideFractionSample);
  });

  testWidgets('renders block math without exception in default mode', (
    tester,
  ) async {
    await expectNoException(tester, data: blockSample);
  });

  testWidgets('renders mixed math without exception in default mode', (
    tester,
  ) async {
    await expectNoException(tester, data: mixedSample);
  });

  testWidgets(
    'renders mixed math without exception when wrapped by SelectionArea',
    (tester) async {
      await expectNoException(
        tester,
        data: mixedSample,
        useSelectionArea: true,
      );
    },
  );

  testWidgets('renders mixed math without exception when selectable=true', (
    tester,
  ) async {
    await expectNoException(tester, data: mixedSample, selectable: true);
  });

  testWidgets('markdown headings and table text inherit base text color', (
    tester,
  ) async {
    const customColor = Color(0xFFE8E0CF);
    late MarkdownStyleSheet styleSheet;

    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) {
            styleSheet = buildOmnibotMarkdownStyleSheet(
              context,
              const TextStyle(fontSize: 14, color: customColor),
            );
            return const SizedBox.shrink();
          },
        ),
      ),
    );

    expect(styleSheet.h3?.color, customColor);
    expect(styleSheet.h4?.color, customColor);
    expect(styleSheet.h5?.color, customColor);
    expect(styleSheet.h6?.color, customColor);
    expect(styleSheet.tableHead?.color, customColor);
    expect(styleSheet.tableBody?.color, customColor);
  });

  testWidgets('renders inline math inside markdown table cells', (
    tester,
  ) async {
    const tableSample = r'''
| 变量 | 公式 |
| --- | --- |
| 能量 | $E=mc^2$ |
''';

    await expectNoException(tester, data: tableSample);

    expect(find.byType(Table), findsOneWidget);
    expect(find.byType(Math), findsOneWidget);
  });

  testWidgets('does not parse table-looking text inside a fenced code block', (
    tester,
  ) async {
    const codeSample = '''```text
| name | status |
| --- | --- |
| app | ready |
```''';

    await expectNoException(tester, data: codeSample);

    expect(find.byType(Table), findsNothing);
    expect(find.textContaining('| name | status |'), findsOneWidget);
  });

  test(
    'does not rewrite ordinary pipe expressions or inline Latin Markdown',
    () {
      const source = 'Use `a || b` and **bold**text here.';
      expect(normalizeOmnibotMarkdown(source), source);
      expect(normalizeOmnibotMarkdown('**重点**中文内容'), '**重点**中文内容');
      expect(
        normalizeOmnibotMarkdown('| **湿度：** | 58% |'),
        '| **湿度：** | 58% |',
      );
    },
  );

  test(
    'repairs incomplete list and blockquote markers without changing prose',
    () {
      expect(
        normalizeOmnibotMarkdown('-☀️晴天\n1.明天\n>说明'),
        '- ☀️晴天\n1. 明天\n> 说明',
      );
      expect(normalizeOmnibotMarkdown('*italic*\n-10°C'), '*italic*\n-10°C');
    },
  );

  testWidgets('repairs a heading glued to a Markdown resource link', (
    tester,
  ) async {
    const malformed =
        '[report.md](omnibot://workspace/report.md)### 📋汇报内容概览\n\n正文';

    expect(
      normalizeOmnibotMarkdown(malformed),
      '[report.md](omnibot://workspace/report.md)\n\n### 📋汇报内容概览\n\n正文',
    );

    await expectNoException(tester, data: malformed);
    expect(find.textContaining('###'), findsNothing);
    expect(find.text('📋汇报内容概览'), findsOneWidget);
  });

  testWidgets('repairs provider Markdown before rendering', (tester) async {
    const malformed = '''###🌤️今日天气 —北京

| 项目| 详情 |
|------|------|| **当前温度** | 29°C || **体感温度** |31°C || **天气状况** |⛅ 多云转晴 |
| **湿度** |58% || **风向/风速** | 东偏南风，7 km/h |
|**降水** |0 mm|###📅 全天概况

|项目 | 详情 ||------|------|| **日均气温** |28°C || **日出** |05:33|

72%**未来几天预报：**
-☀️ 紫外线指数：最弱
-👕穿衣指数：短袖-🚗洗车指数：较适宜-🤧感冒指数：少发---###⚠️ 全国主要天气动态：
- **"沙德尔"**已加强为台风级''';

    final normalized = normalizeOmnibotMarkdown(malformed);
    expect(normalized, contains('### 🌤️今日天气 —北京'));
    expect(normalized, contains('|------|------|\n| **当前温度**'));
    expect(normalized, contains('|0 mm|\n\n### 📅 全天概况'));
    expect(normalized, contains('72%\n\n**未来几天预报：**'));
    expect(normalized, contains('- ☀️ 紫外线指数：最弱'));
    expect(normalized, contains('少发\n\n### ⚠️ 全国主要天气动态：'));
    expect(normalized, contains('- 👕穿衣指数：短袖\n- 🚗洗车指数：较适宜'));

    await expectNoException(tester, data: malformed);

    expect(find.byType(Table), findsNWidgets(2));
    expect(find.textContaining('###'), findsNothing);
    expect(find.textContaining('| 项目'), findsNothing);
  });

  testWidgets('renders a persisted weather response without parser failure', (
    tester,
  ) async {
    const weatherResponse = '''查到了！这是 **北京** 今天（8月22日 星期六）的天气情况：

---

###🌤️今日北京天气**当前：** ☁️多云，**32°C**（体感34°C），西北风5 km/h，能见度10 km，无降水。| 时段 |天气 | 温度 | 体感 |风速|降水概率|
|------|------|------|------|------|----------|
|🌅早上 |☀️晴 | 28°C | 30°C | ←6-7 km/h |4% ||☀️ 中午| ☁️ 多云 |33°C |35°C |↖ 9-13km/h |9% |
|🌇傍晚| ☁️多云 |32°C |35°C |↖ 6-11 km/h| 7% |
|🌙 夜间|🌧️局部小雨| 28°C| 31°C| ↖7-18 km/h | 42% |

###📌小提示
-今天白天以晴到多云为主，气温较高，最高33°C，注意防晒补水。
- **夜间可能有零星小雨**，出门建议带把伞。
- 明天（周日）早上也有可能下雨，午后转晴。

---如果你不在北京，告诉我你所在的城市，我帮你查当地天气！''';

    await expectNoException(tester, data: weatherResponse);
    expect(find.byType(Table), findsOneWidget);
    expect(find.textContaining('###'), findsNothing);
  });
}
