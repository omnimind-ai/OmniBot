import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/features/home/pages/chat_history/chat_history_page.dart';
import 'package:ui/models/conversation_model.dart';

class _SvgTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<rect width="24" height="24" fill="#000000"/>'
      '</svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async {
    return ByteData.view(_svgBytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

void main() {
  setUp(() => LegacyTextLocalizer.setResolvedLocale(const Locale('zh')));
  tearDown(LegacyTextLocalizer.clearResolvedLocale);
  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'getConversations':
              return <Object?>[];
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
  });

  testWidgets('archive page groups conversations by date and toggles all', (
    tester,
  ) async {
    final todayConversation = _conversation(
      id: 1,
      title: 'Archived today',
      summary: 'Today summary',
      updatedAt: DateTime.now().subtract(const Duration(minutes: 5)),
    );
    final yesterdayConversation = _conversation(
      id: 2,
      title: 'Archived yesterday',
      summary: 'Yesterday summary',
      updatedAt: DateTime.now().subtract(const Duration(days: 1, minutes: 5)),
    );

    final nativeConversations = <Map<String, Object?>>[
      todayConversation.toJson().cast<String, Object?>(),
      yesterdayConversation.toJson().cast<String, Object?>(),
    ];

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'getConversations':
              return nativeConversations;
            default:
              return null;
          }
        });

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: const ProviderScope(
            child: ChatHistoryPage(archivedOnly: true),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final todayLabel = todayConversation.timeDisplay;
    final yesterdayLabel = yesterdayConversation.timeDisplay;

    expect(find.text(todayLabel), findsWidgets);
    expect(find.text(yesterdayLabel), findsWidgets);
    expect(find.text('Archived today'), findsOneWidget);
    expect(find.text('Archived yesterday'), findsOneWidget);
    expect(find.text('3 条消息'), findsNWidgets(2));
    expect(find.text('Today summary'), findsNothing);
    expect(find.text('Yesterday summary'), findsNothing);

    final todaySectionBody = find.byKey(
      ValueKey('chat-history-date-section-body-$todayLabel'),
    );
    final yesterdaySectionBody = find.byKey(
      ValueKey('chat-history-date-section-body-$yesterdayLabel'),
    );
    final toggleButton = find.byKey(
      const ValueKey('chat-history-date-toggle-button'),
    );

    expect(tester.getSize(todaySectionBody).height, greaterThan(0));
    expect(tester.getSize(yesterdaySectionBody).height, greaterThan(0));

    await tester.tap(toggleButton);
    await tester.pumpAndSettle();

    expect(tester.getSize(todaySectionBody).height, closeTo(0, 0.1));
    expect(tester.getSize(yesterdaySectionBody).height, closeTo(0, 0.1));

    await tester.tap(toggleButton);
    await tester.pumpAndSettle();

    expect(tester.getSize(todaySectionBody).height, greaterThan(0));
    expect(tester.getSize(yesterdaySectionBody).height, greaterThan(0));
  });
}

ConversationModel _conversation({
  required int id,
  required String title,
  required String summary,
  required DateTime updatedAt,
}) {
  return ConversationModel(
    id: id,
    title: title,
    summary: summary,
    isArchived: true,
    status: 0,
    lastMessage: summary,
    messageCount: 3,
    createdAt: updatedAt
        .subtract(const Duration(hours: 1))
        .millisecondsSinceEpoch,
    updatedAt: updatedAt.millisecondsSinceEpoch,
  );
}
