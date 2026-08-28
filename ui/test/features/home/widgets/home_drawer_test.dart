import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/state/habitual_hand_controller.dart';
import 'package:ui/features/home/widgets/conversation_slidable.dart';
import 'package:ui/features/home/widgets/home_drawer.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/l10n/app_language_mode.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/models/habitual_hand.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/widgets/agent_brand_icon.dart';

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

  @override
  Future<T> loadStructuredBinaryData<T>(
    String key,
    FutureOr<T> Function(ByteData data) parser,
  ) {
    // Image.asset uses the manifest to resolve the default Xiaowan avatar.
    // Keep the test bundle's SVG stub for normal assets, but use Flutter's
    // real manifest so the image provider does not try to decode SVG bytes as
    // AssetManifest.bin.
    if (key == 'AssetManifest.bin') {
      return rootBundle.loadStructuredBinaryData(key, parser);
    }
    return super.loadStructuredBinaryData(key, parser);
  }
}

void main() {
  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );
  late List<Map<String, Object?>> nativeConversations;

  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    nativeConversations = <Map<String, Object?>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'getConversations':
              return nativeConversations;
            case 'getWorkspaceLongMemory':
              return <String, Object?>{'content': ''};
            case 'agentSkillList':
              return <Object?>[];
            case 'updateConversationTitle':
              return 'SUCCESS';
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
  });

  testWidgets('embedded mode routes new conversation through callback', (
    tester,
  ) async {
    ConversationMode? selectedMode;

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: Scaffold(
              body: SizedBox(
                width: 360,
                height: 720,
                child: HomeDrawer(
                  embedded: true,
                  closeOnNavigate: false,
                  onThreadTargetSelected: (target) {
                    selectedMode = target.mode;
                  },
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('暂无聊天记录'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('home-drawer-omnibot-guide')),
      findsNothing,
    );
    expect(find.text('小万指南'), findsNothing);

    await tester.tap(find.text('开始对话'));
    await tester.pumpAndSettle();

    expect(selectedMode, ConversationMode.agent);
  });

  testWidgets('shows the restored trajectory footer shortcut', (tester) async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    addTearDown(LegacyTextLocalizer.clearResolvedLocale);

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byTooltip('轨迹'), findsOneWidget);
  });

  testWidgets(
    'embedded mode creates new chat_only conversation when requested',
    (tester) async {
      ConversationMode? selectedMode;

      await tester.pumpWidget(
        MaterialApp(
          home: DefaultAssetBundle(
            bundle: _SvgTestAssetBundle(),
            child: _buildProviderScope(
              child: Scaffold(
                body: SizedBox(
                  width: 360,
                  height: 720,
                  child: HomeDrawer(
                    embedded: true,
                    closeOnNavigate: false,
                    newConversationMode: ConversationMode.chatOnly,
                    onThreadTargetSelected: (target) {
                      selectedMode = target.mode;
                    },
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('开始对话'));
      await tester.pumpAndSettle();

      expect(selectedMode, ConversationMode.chatOnly);
    },
  );

  testWidgets('embedded mode routes existing conversation through callback', (
    tester,
  ) async {
    ConversationThreadTarget? selectedTarget;
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 42,
        'title': '已存在会话',
        'mode': ConversationMode.openclaw.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': 'hello',
        'messageCount': 1,
        'createdAt': 1,
        'updatedAt': 2,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: Scaffold(
              body: SizedBox(
                width: 360,
                height: 720,
                child: HomeDrawer(
                  embedded: true,
                  closeOnNavigate: false,
                  onThreadTargetSelected: (target) {
                    selectedTarget = target;
                  },
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('已存在会话'));
    await tester.pumpAndSettle();

    expect(selectedTarget, isNotNull);
    expect(selectedTarget!.conversationId, 42);
    expect(selectedTarget!.mode, ConversationMode.openclaw);
  });

  testWidgets('embedded Agent conversation keeps its bound ACP agent', (
    tester,
  ) async {
    ConversationThreadTarget? selectedTarget;
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 50,
        'title': 'Claude 会话',
        'mode': ConversationMode.agent.storageValue,
        'agentId': 'claude-code-acp',
        'summary': null,
        'status': 0,
        'lastMessage': 'hello',
        'messageCount': 1,
        'createdAt': 1,
        'updatedAt': 2,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: Scaffold(
              body: SizedBox(
                width: 360,
                height: 720,
                child: HomeDrawer(
                  embedded: true,
                  closeOnNavigate: false,
                  onThreadTargetSelected: (target) {
                    selectedTarget = target;
                  },
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('Claude 会话'));
    await tester.pumpAndSettle();

    expect(selectedTarget?.conversationId, 50);
    expect(selectedTarget?.mode, ConversationMode.agent);
    expect(selectedTarget?.agentId, 'claude-code-acp');
  });

  testWidgets('shows scheduled and pinned sections before regular history', (
    tester,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await StorageService.setStringList('scheduled_tasks', [
      jsonEncode({
        'id': 'schedule-1',
        'title': '新闻整理任务',
        'targetKind': 'subagent',
        'parentConversationId': '1',
        'parentConversationMode': ConversationMode.normal.storageValue,
        'subagentPrompt': '整理新闻',
        'type': 'fixedTime',
        'fixedTime': '18:00',
        'repeatDaily': true,
        'isEnabled': true,
        'createdAt': now,
        'nextExecutionTime': now + 3600 * 1000,
      }),
    ]);
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 1,
        'title': '主会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 4000,
        'updatedAt': now - 3000,
      },
      <String, Object?>{
        'id': 2,
        'title': '子运行会话',
        'mode': ConversationMode.subagent.storageValue,
        'parentConversationId': 1,
        'parentConversationMode': ConversationMode.normal.storageValue,
        'scheduledTaskId': 'schedule-1',
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 2000,
        'updatedAt': now - 1000,
      },
      <String, Object?>{
        'id': 3,
        'title': '重点对话',
        'mode': ConversationMode.normal.storageValue,
        'isPinned': true,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 6000,
        'updatedAt': now - 5000,
      },
      <String, Object?>{
        'id': 4,
        'title': '普通会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 8000,
        'updatedAt': now - 7000,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('定时任务'), findsOneWidget);
    expect(find.text('主会话'), findsOneWidget);
    expect(find.text('子运行会话'), findsOneWidget);
    expect(find.text('置顶会话'), findsOneWidget);
    expect(find.text('重点对话'), findsOneWidget);
    expect(find.text('普通会话'), findsOneWidget);

    final scheduledChildSlidable = tester.widget<ConversationSlidable>(
      find.ancestor(
        of: find.text('子运行会话'),
        matching: find.byType(ConversationSlidable),
      ),
    );
    expect(scheduledChildSlidable.actions, hasLength(3));
  });

  testWidgets('unfocuses search field when tapping outside', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(
                width: 360,
                height: 720,
                child: HomeDrawer(embedded: true),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final searchField = tester.widget<TextField>(find.byType(TextField));
    await tester.tap(find.byType(TextField));
    await tester.pump();

    expect(searchField.focusNode!.hasFocus, isTrue);

    await tester.tapAt(const Offset(180, 180));
    await tester.pump();

    expect(searchField.focusNode!.hasFocus, isFalse);
  });

  testWidgets('search takes exclusive focus and reports the handoff', (
    tester,
  ) async {
    final chatFocusNode = FocusNode();
    final drawerKey = GlobalKey<HomeDrawerState>();
    final searchFocusChanges = <bool>[];
    addTearDown(chatFocusNode.dispose);

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: Scaffold(
              body: Column(
                children: [
                  SizedBox(
                    height: 48,
                    child: TextField(focusNode: chatFocusNode),
                  ),
                  Expanded(
                    child: HomeDrawer(
                      key: drawerKey,
                      embedded: true,
                      onSearchFocusChanged: searchFocusChanges.add,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    chatFocusNode.requestFocus();
    await tester.pump();
    expect(chatFocusNode.hasFocus, isTrue);

    final searchFinder = find.byType(TextField).last;
    final searchField = tester.widget<TextField>(searchFinder);
    await tester.tap(searchFinder);
    await tester.pump();

    expect(searchField.focusNode!.hasFocus, isTrue);
    expect(chatFocusNode.hasFocus, isFalse);
    expect(FocusManager.instance.primaryFocus, same(searchField.focusNode));
    expect(searchFocusChanges, isNotEmpty);
    expect(searchFocusChanges.last, isTrue);

    drawerKey.currentState!.unfocusSearch();
    await tester.pump();

    expect(searchField.focusNode!.hasFocus, isFalse);
    expect(searchFocusChanges.last, isFalse);
  });

  testWidgets('localizes promoted drawer section titles in English', (
    tester,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await StorageService.setStringList('scheduled_tasks', [
      jsonEncode({
        'id': 'schedule-1',
        'title': 'Daily task',
        'targetKind': 'subagent',
        'parentConversationId': '1',
        'parentConversationMode': ConversationMode.normal.storageValue,
        'subagentPrompt': 'Summarize news',
        'type': 'fixedTime',
        'fixedTime': '18:00',
        'repeatDaily': true,
        'isEnabled': true,
        'createdAt': now,
        'nextExecutionTime': now + 3600 * 1000,
      }),
    ]);
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 1,
        'title': 'Main chat',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 4000,
        'updatedAt': now - 3000,
      },
      <String, Object?>{
        'id': 2,
        'title': 'Pinned chat',
        'mode': ConversationMode.normal.storageValue,
        'isPinned': true,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 6000,
        'updatedAt': now - 5000,
      },
      <String, Object?>{
        'id': 3,
        'title': 'History chat',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 8000,
        'updatedAt': now - 7000,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Scheduled tasks'), findsOneWidget);
    expect(find.text('Pinned conversations'), findsOneWidget);
    // The default OmniAI/Xiaowan history has no redundant section header.
    expect(find.text('OmniAi'), findsNothing);
    expect(find.text('Omnibot Guide'), findsNothing);
  });

  testWidgets('scrolls promoted sections together with history', (
    tester,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await StorageService.setStringList('scheduled_tasks', [
      jsonEncode({
        'id': 'schedule-1',
        'title': '新闻整理任务',
        'targetKind': 'subagent',
        'parentConversationId': '1',
        'parentConversationMode': ConversationMode.normal.storageValue,
        'subagentPrompt': '整理新闻',
        'type': 'fixedTime',
        'fixedTime': '18:00',
        'repeatDaily': true,
        'isEnabled': true,
        'createdAt': now,
        'nextExecutionTime': now + 3600 * 1000,
      }),
    ]);
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 1,
        'title': '主会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 4000,
        'updatedAt': now - 3000,
      },
      <String, Object?>{
        'id': 2,
        'title': '重点对话',
        'mode': ConversationMode.normal.storageValue,
        'isPinned': true,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 6000,
        'updatedAt': now - 5000,
      },
      for (int index = 0; index < 24; index++)
        <String, Object?>{
          'id': 100 + index,
          'title': '普通会话 $index',
          'mode': ConversationMode.normal.storageValue,
          'summary': null,
          'status': 0,
          'lastMessage': null,
          'messageCount': 0,
          'createdAt': now - 10000 - index * 1000,
          'updatedAt': now - 9000 - index * 1000,
        },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final sectionHeaderLeft = tester.getTopLeft(find.text('定时任务')).dx;

    // 顶层区块标题（定时任务/置顶会话）左对齐；置顶条目与标题共用缩进。
    expect(tester.getTopLeft(find.text('置顶会话')).dx, sectionHeaderLeft);
    expect(tester.getTopLeft(find.text('重点对话')).dx, sectionHeaderLeft);

    // 定时任务、置顶区块与历史记录在同一个滚动列表内，上滑时随列表一起
    // 滚出视口（旧实现固定在顶部，会把过长内容顶出屏幕造成溢出）。
    await tester.drag(find.text('普通会话 0'), const Offset(0, -320));
    await tester.pumpAndSettle();

    expect(find.text('定时任务').hitTestable(), findsNothing);
    expect(find.text('置顶会话').hitTestable(), findsNothing);
  });

  testWidgets('expanding many scheduled entries does not overflow drawer', (
    tester,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await StorageService.setStringList('scheduled_tasks', [
      jsonEncode({
        'id': 'schedule-1',
        'title': '新闻整理任务',
        'targetKind': 'subagent',
        'parentConversationId': '1',
        'parentConversationMode': ConversationMode.normal.storageValue,
        'subagentPrompt': '整理新闻',
        'type': 'fixedTime',
        'fixedTime': '18:00',
        'repeatDaily': true,
        'isEnabled': true,
        'createdAt': now,
        'nextExecutionTime': now + 3600 * 1000,
      }),
    ]);
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 1,
        'title': '主会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 4000,
        'updatedAt': now - 3000,
      },
      // 展开后远超一屏高度的定时任务子会话。
      for (int index = 0; index < 30; index++)
        <String, Object?>{
          'id': 200 + index,
          'title': '定时子会话 $index',
          'mode': ConversationMode.subagent.storageValue,
          'parentConversationId': 1,
          'parentConversationMode': ConversationMode.normal.storageValue,
          'scheduledTaskId': 'schedule-1',
          'summary': null,
          'status': 0,
          'lastMessage': null,
          'messageCount': 0,
          'createdAt': now - 2000 - index * 1000,
          'updatedAt': now - 1000 - index * 1000,
        },
      <String, Object?>{
        'id': 4,
        'title': '普通会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 8000,
        'updatedAt': now - 7000,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    // 布局阶段若出现 RenderFlex 溢出会作为测试异常直接失败。
    await tester.pumpAndSettle();

    expect(find.text('定时子会话 0'), findsOneWidget);

    // 列表可以滚动到定时任务区块下方的历史会话。
    await tester.dragUntilVisible(
      find.text('普通会话'),
      find.byType(ListView),
      const Offset(0, -160),
    );
    await tester.ensureVisible(find.text('普通会话'));
    await tester.pumpAndSettle();
    expect(find.text('普通会话').hitTestable(), findsOneWidget);
  });

  testWidgets('syncs scheduled section when scheduled tasks are deleted', (
    tester,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await StorageService.setStringList('scheduled_tasks', [
      jsonEncode({
        'id': 'schedule-1',
        'title': '新闻整理任务',
        'targetKind': 'subagent',
        'parentConversationId': '1',
        'parentConversationMode': ConversationMode.normal.storageValue,
        'subagentPrompt': '整理新闻',
        'type': 'fixedTime',
        'fixedTime': '18:00',
        'repeatDaily': true,
        'isEnabled': true,
        'createdAt': now,
        'nextExecutionTime': now + 3600 * 1000,
      }),
    ]);
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 1,
        'title': '主会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 4000,
        'updatedAt': now - 3000,
      },
      <String, Object?>{
        'id': 2,
        'title': '子运行会话',
        'mode': ConversationMode.subagent.storageValue,
        'parentConversationId': 1,
        'parentConversationMode': ConversationMode.normal.storageValue,
        'scheduledTaskId': 'schedule-1',
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 2000,
        'updatedAt': now - 1000,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('定时任务'), findsOneWidget);
    expect(find.text('主会话'), findsOneWidget);
    expect(find.text('子运行会话'), findsOneWidget);

    await ScheduledTaskStorageService.deleteScheduledTask('schedule-1');
    await tester.pumpAndSettle();

    expect(find.text('定时任务'), findsNothing);
    expect(find.text('主会话'), findsOneWidget);
    expect(find.text('子运行会话'), findsOneWidget);
  });

  testWidgets('renames scheduled parent conversation from long press', (
    tester,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    String? renamedTitle;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'getConversations':
              return nativeConversations;
            case 'getWorkspaceLongMemory':
              return <String, Object?>{'content': ''};
            case 'agentSkillList':
              return <Object?>[];
            case 'updateConversationTitle':
              renamedTitle = (call.arguments as Map?)?['newTitle'] as String?;
              return 'SUCCESS';
            default:
              return null;
          }
        });
    await StorageService.setStringList('scheduled_tasks', [
      jsonEncode({
        'id': 'schedule-1',
        'title': '新闻整理任务',
        'targetKind': 'subagent',
        'parentConversationId': '1',
        'parentConversationMode': ConversationMode.normal.storageValue,
        'subagentPrompt': '整理新闻',
        'type': 'fixedTime',
        'fixedTime': '18:00',
        'repeatDaily': true,
        'isEnabled': true,
        'createdAt': now,
        'nextExecutionTime': now + 3600 * 1000,
      }),
    ]);
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 1,
        'title': '主会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 4000,
        'updatedAt': now - 3000,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.longPress(find.text('主会话'));
    await tester.pumpAndSettle();
    final titleField = find.byWidgetPredicate(
      (widget) => widget is TextField && widget.controller?.text == '主会话',
    );
    expect(titleField, findsOneWidget);
    final titleEditor = tester.widget<TextField>(titleField);

    await tester.enterText(titleField, '主会话改名');
    titleEditor.onSubmitted?.call('主会话改名');
    await tester.pumpAndSettle();
    await tester.runAsync(() async {
      for (var attempt = 0; attempt < 20 && renamedTitle == null; attempt++) {
        await Future<void>.delayed(const Duration(milliseconds: 1));
      }
    });
    await tester.pumpAndSettle();

    expect(renamedTitle, '主会话改名');
    expect(find.text('主会话改名'), findsOneWidget);
  });

  testWidgets('persists drawer section expanded states across openings', (
    tester,
  ) async {
    final currentDay = DateTime.now();
    final now = DateTime(
      currentDay.year,
      currentDay.month,
      currentDay.day,
      12,
    ).millisecondsSinceEpoch;
    final dateKey =
        '__home_drawer_date__omni_ai__'
        '${currentDay.year.toString().padLeft(4, '0')}-'
        '${currentDay.month.toString().padLeft(2, '0')}-'
        '${currentDay.day.toString().padLeft(2, '0')}';
    final todayLabel = ConversationModel(
      id: 999,
      title: '',
      status: 0,
      messageCount: 0,
      createdAt: now,
      updatedAt: now,
    ).timeDisplay;
    await StorageService.setStringList('scheduled_tasks', [
      jsonEncode({
        'id': 'schedule-1',
        'title': '新闻整理任务',
        'targetKind': 'subagent',
        'parentConversationId': '1',
        'parentConversationMode': ConversationMode.normal.storageValue,
        'subagentPrompt': '整理新闻',
        'type': 'fixedTime',
        'fixedTime': '18:00',
        'repeatDaily': true,
        'isEnabled': true,
        'createdAt': now,
        'nextExecutionTime': now + 3600 * 1000,
      }),
    ]);
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 1,
        'title': '主会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 4000,
        'updatedAt': now - 3000,
      },
      <String, Object?>{
        'id': 2,
        'title': '子运行会话',
        'mode': ConversationMode.subagent.storageValue,
        'parentConversationId': 1,
        'parentConversationMode': ConversationMode.normal.storageValue,
        'scheduledTaskId': 'schedule-1',
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 2000,
        'updatedAt': now - 1000,
      },
      <String, Object?>{
        'id': 3,
        'title': '重点对话',
        'mode': ConversationMode.normal.storageValue,
        'isPinned': true,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 6000,
        'updatedAt': now - 5000,
      },
      <String, Object?>{
        'id': 4,
        'title': '普通会话',
        'mode': ConversationMode.normal.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 8000,
        'updatedAt': now - 7000,
      },
    ];

    Widget drawerWidget() {
      return MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      );
    }

    await tester.pumpWidget(drawerWidget());
    await tester.pumpAndSettle();

    expect(find.text('主会话').hitTestable(), findsOneWidget);
    expect(find.text('子运行会话').hitTestable(), findsOneWidget);
    expect(find.text('重点对话').hitTestable(), findsOneWidget);
    expect(find.text('普通会话').hitTestable(), findsOneWidget);

    final parentTitleRect = tester.getRect(find.text('主会话'));
    await tester.tapAt(
      Offset(parentTitleRect.left - 14, parentTitleRect.center.dy),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('定时任务'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('置顶会话'));
    await tester.pumpAndSettle();
    await tester.tap(find.text(todayLabel));
    await tester.pumpAndSettle();

    final rawState = StorageService.getString(
      'home_drawer_expanded_sections_v1',
    );
    expect(rawState, contains('__home_drawer_scheduled__'));
    expect(rawState, contains('__home_drawer_pinned__'));
    expect(rawState, contains('__home_drawer_scheduled_agent:1'));
    expect(rawState, contains(dateKey));

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
    await tester.pumpWidget(drawerWidget());
    await tester.pumpAndSettle();

    expect(find.text('定时任务'), findsOneWidget);
    expect(find.text('置顶会话'), findsOneWidget);
    expect(find.text(todayLabel), findsOneWidget);
    expect(find.text('主会话').hitTestable(), findsNothing);
    expect(find.text('子运行会话').hitTestable(), findsNothing);
    expect(find.text('重点对话').hitTestable(), findsNothing);
    expect(find.text('普通会话').hitTestable(), findsNothing);
  });

  testWidgets('keeps default history flat and identifies each Agent Harness', (
    tester,
  ) async {
    // 相对时间标签依赖 LegacyTextLocalizer 的解析语言，固定为中文保证断言稳定。
    await StorageService.setLanguageMode(AppLanguageMode.zhHans);
    final now = DateTime.now().millisecondsSinceEpoch;
    const dayMs = 24 * 3600 * 1000;
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 11,
        'title': '修复登录问题',
        'mode': ConversationMode.agent.storageValue,
        'agentId': 'codex-acp',
        'agentCwd': '/root/blog',
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 1,
        'createdAt': now - 8 * dayMs,
        'updatedAt': now - 8 * dayMs,
      },
      <String, Object?>{
        'id': 12,
        'title': '写周报脚本',
        'mode': ConversationMode.agent.storageValue,
        'agentId': 'codex-acp',
        'agentCwd': '/root/blog/',
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 1,
        'createdAt': now - 9 * dayMs,
        'updatedAt': now - 9 * dayMs,
      },
      <String, Object?>{
        'id': 13,
        'title': '优化首页响应式',
        'mode': ConversationMode.agent.storageValue,
        'agentId': 'codex-acp',
        'agentCwd': '/root/CoffeeMux',
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 1,
        'createdAt': now - 10 * dayMs,
        'updatedAt': now - 10 * dayMs,
      },
      <String, Object?>{
        'id': 14,
        'title': 'Agent 会话',
        'mode': ConversationMode.normal.storageValue,
        'agentId': 'xiaowan-acp',
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 4000,
        'updatedAt': now - 3000,
      },
      <String, Object?>{
        'id': 15,
        'title': '闲聊会话',
        'mode': ConversationMode.chatOnly.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': now - 6000,
        'updatedAt': now - 5000,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    // Agent/Xiaowan histories share the default history timeline; no
    // redundant Xiaowan section header is rendered.
    expect(find.text('Agent'), findsNothing);
    expect(find.text('小万'), findsNothing);
    expect(find.text('纯聊天'), findsOneWidget);

    // 每条 Agent 会话直接显示具体 Harness，不再显示 Workspace 分组。
    expect(find.text('blog'), findsNothing);
    expect(find.text('CoffeeMux'), findsNothing);
    expect(
      find.byWidgetPredicate(
        (widget) => widget is AgentBrandIcon && widget.agentId == 'codex-acp',
      ),
      findsNWidgets(3),
    );
    expect(find.text('修复登录问题').hitTestable(), findsOneWidget);
    expect(find.text('写周报脚本').hitTestable(), findsOneWidget);
    expect(find.text('优化首页响应式').hitTestable(), findsOneWidget);
    expect(find.text('Agent 会话').hitTestable(), findsOneWidget);
    expect(find.text('闲聊会话'), findsOneWidget);

    // 折叠纯聊天区块只影响纯聊天历史。
    await tester.tap(find.text('纯聊天'));
    await tester.pumpAndSettle();
    expect(find.text('闲聊会话').hitTestable(), findsNothing);
    expect(find.text('Agent 会话').hitTestable(), findsOneWidget);
  });

  testWidgets('marks an active Xiaowan conversation with a running dot', (
    tester,
  ) async {
    final coordinator = ChatConversationRuntimeCoordinator.instance;
    coordinator.resetForTest();
    coordinator.ensureInitialized();
    addTearDown(coordinator.resetForTest);
    nativeConversations = <Map<String, Object?>>[
      <String, Object?>{
        'id': 60,
        'title': '正在处理',
        'mode': ConversationMode.agent.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': null,
        'messageCount': 0,
        'createdAt': 1,
        'updatedAt': 2,
      },
    ];
    coordinator.beginAcpTurn(
      taskId: 'running-xiaowan-task',
      conversationId: 60,
      mode: kChatRuntimeModeAgent,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: _buildProviderScope(
            child: const Scaffold(
              body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('home-drawer-running-agent:60')),
      findsOneWidget,
    );
  });

  testWidgets(
    'marks a completed Xiaowan conversation with a completion circle',
    (tester) async {
      LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
      addTearDown(LegacyTextLocalizer.clearResolvedLocale);
      final coordinator = ChatConversationRuntimeCoordinator.instance;
      coordinator.resetForTest();
      coordinator.ensureInitialized();
      addTearDown(coordinator.resetForTest);
      nativeConversations = <Map<String, Object?>>[
        <String, Object?>{
          'id': 61,
          'title': '已完成任务',
          'mode': ConversationMode.agent.storageValue,
          'summary': null,
          'status': 1,
          'lastMessage': '完成',
          'messageCount': 2,
          'createdAt': 1,
          'updatedAt': 2,
        },
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: DefaultAssetBundle(
            bundle: _SvgTestAssetBundle(),
            child: _buildProviderScope(
              child: const Scaffold(
                body: SizedBox(width: 360, height: 720, child: HomeDrawer()),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('home-drawer-completed-agent:61')),
        findsOneWidget,
      );
      expect(find.byTooltip('小万 · 已完成'), findsOneWidget);
    },
  );
}

Widget _buildProviderScope({required Widget child}) {
  return ProviderScope(
    overrides: [
      habitualHandProvider.overrideWith(
        (ref) => HabitualHandController(initial: HabitualHand.right),
      ),
    ],
    child: child,
  );
}
