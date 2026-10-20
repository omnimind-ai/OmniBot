import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/features/home/pages/chat_history/chat_history_page.dart';
import 'package:ui/features/home/pages/omnibot_workspace/omnibot_artifact_preview_page.dart';
import 'package:ui/features/home/widgets/conversation_mode_badge.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/image_preview_overlay.dart';

const _assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final ces = JackeryCesConfig.enabled;
  late Directory tempDir;
  var conversations = <Map<String, Object?>>[];

  setUp(() async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    tempDir = await Directory.systemTemp.createTemp(
      'jackery-audience-preview-',
    );
    conversations = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          _assistChannel,
          (call) async =>
              call.method == 'getConversations' ? conversations : null,
        );
  });

  tearDown(() async {
    LegacyTextLocalizer.clearResolvedLocale();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_assistChannel, null);
    await tempDir.delete(recursive: true);
  });

  Widget app(Widget child) => DefaultAssetBundle(
    bundle: _SvgBundle(),
    child: MaterialApp(
      theme: AppTheme.lightTheme,
      home: ProviderScope(child: child),
    ),
  );

  testWidgets('history is English and never exposes developer sessions', (
    tester,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    ConversationModel conversation(
      int id,
      ConversationMode mode,
      String title,
    ) => ConversationModel(
      id: id,
      mode: mode,
      title: title,
      status: 0,
      messageCount: 1,
      createdAt: now,
      updatedAt: now,
    );
    conversations = [
      conversation(
        1,
        ConversationMode.agent,
        'Private workspace',
      ).toJson().cast<String, Object?>(),
      conversation(
        2,
        ConversationMode.chatOnly,
        'Plan my weekend',
      ).toJson().cast<String, Object?>(),
    ];
    await tester.pumpWidget(app(const ChatHistoryPage()));
    await tester.pumpAndSettle();
    expect(find.text('History'), findsOneWidget);
    expect(find.text('Plan my weekend'), findsOneWidget);
    expect(find.text('Private workspace'), findsNothing);
    expect(find.byType(ConversationModeBadge), findsNothing);
    expect(find.byTooltip('New conversation'), findsOneWidget);
    final gradients = tester
        .widgetList<Container>(find.byType(Container))
        .where(
          (widget) =>
              widget.decoration is BoxDecoration &&
              (widget.decoration! as BoxDecoration).gradient != null,
        );
    expect(gradients, isEmpty);
  }, skip: !ces);

  testWidgets('empty history offers an English solid orange action', (
    tester,
  ) async {
    await tester.pumpWidget(app(const ChatHistoryPage()));
    await tester.pumpAndSettle();
    expect(find.text('No conversations yet'), findsOneWidget);
    expect(find.text('Start conversation'), findsOneWidget);
    final container = tester.widget<Container>(
      find
          .ancestor(
            of: find.text('Start conversation'),
            matching: find.byType(Container),
          )
          .first,
    );
    final decoration = container.decoration! as BoxDecoration;
    expect(decoration.color, JackeryCesConfig.accent);
    expect(decoration.gradient, isNull);
  }, skip: !ces);

  testWidgets(
    'CES resource preview cannot enter editing even with an edit request',
    (tester) async {
      final file = File('${tempDir.path}/note.txt');
      await tester.runAsync(() => file.writeAsString('Read this summary'));
      await tester.pumpWidget(
        app(
          OmnibotArtifactPreviewPage(
            path: file.path,
            title: 'Summary',
            previewKind: 'text',
            mimeType: 'text/plain',
            startInEditMode: true,
          ),
        ),
      );
      await tester.runAsync(
        () async => Future<void>.delayed(const Duration(milliseconds: 50)),
      );
      for (var attempt = 0; attempt < 20; attempt++) {
        await tester.pump();
        if (find.text('Read this summary').evaluate().isNotEmpty) break;
        await tester.runAsync(
          () async => Future<void>.delayed(const Duration(milliseconds: 10)),
        );
      }
      expect(find.text('Read this summary'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('artifact-preview-editor-field')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey('artifact-preview-path-bar')),
        findsNothing,
      );
      expect(find.byIcon(Icons.edit_outlined), findsNothing);
      expect(find.byIcon(Icons.save_outlined), findsNothing);
      expect(await tester.runAsync(file.readAsString), 'Read this summary');
    },
    skip: !ces,
  );

  testWidgets('resource read failure is a visitor-readable English error', (
    tester,
  ) async {
    await tester.pumpWidget(
      app(
        OmnibotArtifactPreviewPage(
          path: '${tempDir.path}/missing.txt',
          title: 'Summary',
          previewKind: 'text',
          mimeType: 'text/plain',
        ),
      ),
    );
    await tester.runAsync(
      () async => Future<void>.delayed(const Duration(milliseconds: 50)),
    );
    await tester.pumpAndSettle();
    expect(
      find.text('Unable to read this file. Please ask a staff member.'),
      findsOneWidget,
    );
    expect(find.textContaining('读取失败'), findsNothing);
  }, skip: !ces);

  testWidgets(
    'full-screen image preview uses warm background and orange paging',
    (tester) async {
      final bytes = base64Decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO2CS1cAAAAASUVORK5CYII=',
      );
      await tester.pumpWidget(app(const Scaffold(body: Text('Home'))));
      final context = tester.element(find.text('Home'));
      ImagePreviewOverlay.showAll(
        context,
        sources: [MemoryImageSource(bytes), MemoryImageSource(bytes)],
      );
      await tester.pumpAndSettle();
      final backgrounds = tester.widgetList<ColoredBox>(
        find.byType(ColoredBox),
      );
      expect(
        backgrounds.any(
          (widget) => widget.color == JackeryCesConfig.background,
        ),
        isTrue,
      );
      final indicators = tester.widgetList<AnimatedContainer>(
        find.byType(AnimatedContainer),
      );
      expect(
        indicators.any(
          (widget) =>
              (widget.decoration as BoxDecoration?)?.color ==
              JackeryCesConfig.accent,
        ),
        isTrue,
      );
      await tester.tap(find.byType(OmnibotInteractiveImageView).first);
      await tester.pumpAndSettle();
      expect(find.text('Home'), findsOneWidget);
    },
    skip: !ces,
  );
}

class _SvgBundle extends CachingAssetBundle {
  static const svg =
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M2 2h20v20H2z"/></svg>';
  @override
  Future<ByteData> load(String key) async =>
      ByteData.sublistView(Uint8List.fromList(utf8.encode(svg)));
  @override
  Future<String> loadString(String key, {bool cache = true}) async => svg;
}
