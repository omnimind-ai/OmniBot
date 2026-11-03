import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_slidable/flutter_slidable.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/state/habitual_hand_controller.dart';
import 'package:ui/features/home/widgets/home_drawer.dart';
import 'package:ui/features/home/widgets/conversation_context_menu.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/features/home/widgets/home_search_header.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/l10n/app_language_mode.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/models/habitual_hand.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_prompt_sheet.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/widgets/agent_brand_icon.dart';
import 'package:ui/widgets/flclash/search_header.dart';
import 'package:ui/widgets/flclash/popup.dart';

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
  const pluginChannel = MethodChannel('cn.com.omnimind.bot/PluginPlatform');
  const agentRuntimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const agentEventsChannel = MethodChannel(
    'cn.com.omnimind.bot/AgentRuntimeEvents',
  );
  const appUpdateChannel = MethodChannel('cn.com.omnimind.bot/app_update');
  const overlayChannel = MethodChannel('cn.com.omnimind.bot/overlay');
  late bool petShowing;
  late bool overlayGranted;
  late List<String> overlayCalls;
  late List<Map<String, Object?>> nativeConversations;
  late List<Map<String, Object?>> nativeAgents;
  late List<String> agentMethods;
  Map<String, Object?>? nativeUpdateStatus;
  late List<MethodCall> updateCalls;

  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    AppUpdateService.statusNotifier.value = null;
    AppUpdateService.betaOptInNotifier.value = false;
    AppUpdateService.downloadSourceNotifier.value =
        AppUpdateDownloadSource.worker;
    nativeUpdateStatus = null;
    updateCalls = [];
    petShowing = false;
    overlayGranted = true;
    overlayCalls = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(overlayChannel, (call) async {
          overlayCalls.add(call.method);
          switch (call.method) {
            case 'isPetOverlayShowing':
              return petShowing;
            case 'showPetOverlay':
              petShowing = true;
              return true;
            case 'hidePetOverlay':
              petShowing = false;
              return true;
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (call) async {
          if (call.method == 'isOverlayPermission') return overlayGranted;
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(appUpdateChannel, (call) async {
          updateCalls.add(call);
          switch (call.method) {
            case 'getCachedStatus':
            case 'checkNow':
              return nativeUpdateStatus;
            case 'getBetaOptIn':
              return false;
            case 'getApkDownloadSource':
              return 'worker';
            default:
              return null;
          }
        });
    nativeConversations = <Map<String, Object?>>[];
    nativeAgents = [
      {
        'id': 'xiaowan-acp',
        'name': '小万',
        'enabled': true,
        'installed': true,
        'status': 'online',
      },
      {
        'id': 'na-cloud',
        'name': 'Na',
        'enabled': true,
        'installed': true,
        'status': 'online',
      },
      {
        'id': 'codex-acp',
        'name': 'Codex',
        'enabled': true,
        'installed': true,
        'status': 'online',
      },
    ];
    agentMethods = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          agentMethods.add(call.method);
          if (call.method == 'agent/list') {
            return {'agents': nativeAgents};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentEventsChannel, (call) async => null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'getConversations':
              return nativeConversations;
            case 'getWorkspaceLongMemory':
              return <String, Object?>{'content': ''};
            case 'agentSkillList':
              return <Object?>[];
            case 'manageConversation':
              return true;
            default:
              return null;
          }
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginChannel, (call) async {
          if (call.method == 'listActions') return <Object?>[];
          return null;
        });
  });

  tearDown(() {
    AppUpdateService.statusNotifier.value = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(overlayChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(appUpdateChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentEventsChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, null);
  });

  Map<String, Object?> updateStatus({
    String version = '1.0.1',
    bool hasUpdate = true,
  }) => {
    'currentVersion': '1.0.0',
    'latestVersion': version,
    'hasUpdate': hasUpdate,
    'checkedAt': 1,
    'publishedAt': 0,
    'releaseUrl': 'https://example.com/release',
    'releaseNotes': 'Home update regression test',
    'apkName': 'OpenOmniBot-v$version.apk',
    'apkDownloadUrl': 'https://example.com/app.apk',
  };

  Map<String, Object?> selectionRow(
    int id,
    String title, {
    String mode = 'agent',
    bool pinned = false,
    int? updatedAt,
    int? parentId,
  }) => {
    'id': id,
    'title': title,
    'mode': mode,
    'agentId': mode == 'agent' ? 'xiaowan-acp' : null,
    'isPinned': pinned,
    'parentConversationId': parentId,
    'parentConversationMode': parentId == null ? null : 'agent',
    'scheduledTaskId': parentId == null ? null : 'selection-schedule',
    'status': 0,
    'messageCount': 0,
    'createdAt': updatedAt ?? DateTime.now().millisecondsSinceEpoch,
    'updatedAt': updatedAt ?? DateTime.now().millisecondsSinceEpoch,
  };

  Future<void> selectHomeCategory(WidgetTester tester, String id) async {
    await tester.tap(find.byKey(const ValueKey('home-agent-switcher')));
    await tester.pumpAndSettle();
    final option = find.byKey(ValueKey('home-agent-option-$id'));
    await tester.ensureVisible(option);
    await tester.tap(option);
    await tester.pumpAndSettle();
  }

  Future<void> pumpSelectionHome(
    WidgetTester tester, {
    GlobalKey<HomeDrawerState>? key,
    ValueChanged<ConversationThreadTarget>? onOpen,
    double textScale = 1,
    bool disableAnimations = false,
    bool conversationSidebar = false,
    ConversationThreadTarget? selectedThreadTarget,
    String? initialCategory,
    HabitualHand habitualHand = HabitualHand.right,
  }) async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    addTearDown(LegacyTextLocalizer.clearResolvedLocale);
    await tester.pumpWidget(
      _buildProviderScope(
        habitualHand: habitualHand,
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(
              textScaler: TextScaler.linear(textScale),
              disableAnimations: disableAnimations,
            ),
            child: child!,
          ),
          home: HomeDrawer(
            key: key,
            homePage: true,
            conversationSidebar: conversationSidebar,
            selectedThreadTarget: selectedThreadTarget,
            embedded: true,
            closeOnNavigate: false,
            onThreadTargetSelected: onOpen,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    if (initialCategory != null) {
      await selectHomeCategory(tester, initialCategory);
    }
  }

  Future<void> startSelection(WidgetTester tester, String title) async {
    await tester.longPress(find.text(title));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('home-drawer-context-select')));
    await tester.pumpAndSettle();
  }

  Finder conversationAvatar(int id, {Finder? within}) {
    final avatar = find.byKey(ValueKey('home-conversation-avatar-agent:$id'));
    return within == null
        ? avatar
        : find.descendant(of: within, matching: avatar);
  }

  Color avatarFill(WidgetTester tester, int id, {Finder? within}) {
    final avatar = tester.widget<Container>(
      conversationAvatar(id, within: within),
    );
    return (avatar.decoration! as BoxDecoration).color!;
  }

  List<Color> expectDistinctAvatarSequence(WidgetTester tester, List<int> ids) {
    final colors = ids.map((id) => avatarFill(tester, id)).toList();
    for (var index = 0; index < ids.length; index++) {
      expect(colors[index].a, 1);
      if (index == 0) continue;
      expect(colors[index], isNot(colors[index - 1]));
      expect(
        tester.getCenter(conversationAvatar(ids[index])).dy,
        greaterThan(tester.getCenter(conversationAvatar(ids[index - 1])).dy),
      );
    }
    return colors;
  }

  testWidgets(
    'home avatar colors follow promoted rows and remain stable in refresh and preview',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final yesterday = DateTime.now()
          .subtract(const Duration(days: 1))
          .millisecondsSinceEpoch;
      // These identities share a preferred hue, so each visual group boundary
      // needs the list's assignment rather than a standalone row's fallback.
      nativeConversations = [
        selectionRow(1, '配色今天'),
        selectionRow(9, '配色置顶', pinned: true),
        selectionRow(13, '配色昨天', updatedAt: yesterday),
      ];
      final drawerKey = GlobalKey<HomeDrawerState>();
      await pumpSelectionHome(tester, key: drawerKey);
      const visualOrder = [9, 1, 13];
      final original = expectDistinctAvatarSequence(tester, visualOrder);

      nativeConversations = nativeConversations
          .map((row) => Map<String, Object?>.of(row))
          .toList();
      drawerKey.currentState!.reloadConversations();
      await tester.pumpAndSettle();
      expect(expectDistinctAvatarSequence(tester, visualOrder), original);

      final sourceColor = avatarFill(tester, 1);
      await tester.longPress(find.text('配色今天'));
      await tester.pumpAndSettle();
      final preview = find.byKey(
        const ValueKey('conversation-context-preview'),
      );
      expect(preview, findsOneWidget);
      expect(avatarFill(tester, 1, within: preview), sourceColor);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(avatarFill(tester, 1), sourceColor);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'home avatar colors resolve new neighbors after filtering and search',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      nativeConversations = [
        selectionRow(1, '匹配第一条'),
        {...selectionRow(2, '其他 Agent 会话'), 'agentId': 'codex-acp'},
        selectionRow(9, '匹配第二条'),
      ];
      await pumpSelectionHome(tester, initialCategory: 'all');
      expectDistinctAvatarSequence(tester, [1, 2, 9]);

      await selectHomeCategory(tester, 'xiaowan-acp');
      expect(conversationAvatar(2), findsNothing);
      expectDistinctAvatarSequence(tester, [1, 9]);

      await selectHomeCategory(tester, 'all');
      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '匹配');
      await tester.pumpAndSettle(const Duration(milliseconds: 250));
      expect(conversationAvatar(2), findsNothing);
      expectDistinctAvatarSequence(tester, [1, 9]);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'Pad current row follows host identity without owning selection',
    (tester) async {
      nativeConversations = [
        selectionRow(1, '第一条'),
        selectionRow(2, '第二条'),
        selectionRow(1, '同号纯聊天', mode: 'chat_only'),
      ];
      Future<void> show(int id) => pumpSelectionHome(
        tester,
        conversationSidebar: true,
        selectedThreadTarget: ConversationThreadTarget.existing(
          conversationId: id,
          mode: ConversationMode.agent,
        ),
      );
      bool? selected(String key) => tester
          .widget<Semantics>(find.byKey(ValueKey('home-current-thread-$key')))
          .properties
          .selected;
      await show(1);
      expect(selected('agent:1'), isTrue);
      expect(selected('agent:2'), isFalse);
      expect(selected('chat_only:1'), isFalse);
      await show(2);
      expect(selected('agent:1'), isFalse);
      expect(selected('agent:2'), isTrue);
      expect(selected('chat_only:1'), isFalse);
      expect(tester.takeException(), isNull);
    },
  );

  for (final width in [220.0, 260.0]) {
    testWidgets('Pad sidebar shares modern home controls at $width dp', (
      tester,
    ) async {
      tester.view.physicalSize = Size(width, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      nativeConversations = [
        {...selectionRow(1, 'Codex侧栏会话'), 'agentId': 'codex-acp'},
        selectionRow(2, '小万侧栏会话'),
      ];
      final opened = <ConversationThreadTarget>[];
      await pumpSelectionHome(
        tester,
        conversationSidebar: true,
        onOpen: opened.add,
      );
      expect(find.text('全部'), findsOneWidget);
      expect(find.text('Codex侧栏会话'), findsOneWidget);
      expect(find.text('小万侧栏会话'), findsOneWidget);
      expect(find.byKey(const ValueKey('home-agent-switcher')), findsOneWidget);
      expect(
        find.byKey(const ValueKey('home-new-conversation')),
        findsOneWidget,
      );
      await tester.tap(find.text('Codex侧栏会话'));
      await tester.pumpAndSettle();
      expect(opened.single.conversationId, 1);
      await startSelection(tester, 'Codex侧栏会话');
      expect(
        find.byKey(const ValueKey('home-selection-count')),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
      await tester.tap(find.byKey(const ValueKey('home-selection-cancel')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('home-close-search')), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  void installSelectionMutations(
    List<Map<String, dynamic>> calls, {
    Future<bool> Function(Map<String, dynamic>)? respond,
    Future<List<Map<String, Object?>>> Function()? load,
  }) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'getConversations') {
            return load != null ? await load() : nativeConversations;
          }
          if (call.method == 'getWorkspaceLongMemory') return {'content': ''};
          if (call.method == 'agentSkillList') return <Object?>[];
          if (call.method != 'manageConversation') return null;
          final args = Map<String, dynamic>.from(call.arguments as Map);
          calls.add(args);
          final success = await (respond?.call(args) ?? Future.value(true));
          if (success) {
            bool matches(Map<String, Object?> row) =>
                row['id'] == args['conversationId'] &&
                row['mode'] == args['mode'];
            if (args['action'] == 'delete') {
              nativeConversations.removeWhere(matches);
            } else if (args['action'] == 'archive' ||
                args['action'] == 'unarchive') {
              nativeConversations = [
                for (final row in nativeConversations)
                  if (matches(row))
                    {...row, 'isArchived': args['action'] == 'archive'}
                  else
                    row,
              ];
            }
          }
          return success;
        });
  }

  Finder swipeRow(String title) =>
      find.ancestor(of: find.text(title), matching: find.byType(Slidable));

  Finder swipeActions(String title) => find.descendant(
    of: swipeRow(title),
    matching: find.byType(CustomSlidableAction),
  );

  Future<void> swipeHomeRow(
    WidgetTester tester,
    String title, {
    bool full = false,
  }) async {
    final rect = tester.getRect(swipeRow(title));
    await tester.timedDragFrom(
      Offset(rect.right - 10, rect.center.dy),
      Offset(-rect.width * (full ? 0.9 : 0.4), 0),
      const Duration(milliseconds: 500),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('home swipe menu stays open with delete left and archive right', (
    tester,
  ) async {
    nativeConversations = [selectionRow(1, '滑动菜单')];
    final calls = <Map<String, dynamic>>[];
    installSelectionMutations(calls);
    await pumpSelectionHome(tester, habitualHand: HabitualHand.left);
    final row = find.byKey(const ValueKey('home-source-row-agent:1'));
    final originalLeft = tester.getTopLeft(row).dx;

    await swipeHomeRow(tester, '滑动菜单');
    final actions = swipeActions('滑动菜单');
    expect(actions, findsNWidgets(2));
    expect(calls, isEmpty);
    expect(tester.getTopLeft(row).dx, lessThan(originalLeft));
    expect(
      tester.widget<CustomSlidableAction>(actions.at(0)).backgroundColor,
      AppColors.alertRed,
    );
    expect(
      tester.getCenter(actions.at(0)).dx,
      lessThan(tester.getCenter(actions.at(1)).dx),
    );
    final openedLeft = tester.getTopLeft(row).dx;
    await tester.pump(const Duration(seconds: 2));
    expect(tester.getTopLeft(row).dx, openedLeft);
    expect(calls, isEmpty);

    await tester.tap(actions.at(1));
    await tester.pumpAndSettle();
    expect(calls, hasLength(1));
    expect(calls.single['action'], 'archive');
    expect(calls.single['conversationId'], 1);
    expect(find.text('滑动菜单'), findsNothing);
  });

  testWidgets('home swipe delete targets the selected conversation mode', (
    tester,
  ) async {
    nativeConversations = [
      selectionRow(7, '保留Agent同号'),
      selectionRow(7, '删除纯聊天', mode: 'chat_only'),
    ];
    final calls = <Map<String, dynamic>>[];
    installSelectionMutations(calls);
    await pumpSelectionHome(tester, initialCategory: 'all');
    await swipeHomeRow(tester, '删除纯聊天');
    await tester.tap(swipeActions('删除纯聊天').at(0));
    await tester.pumpAndSettle();
    expect(calls, hasLength(1));
    expect(calls.single['action'], 'delete');
    expect(calls.single['mode'], 'chat_only');
    expect(calls.single['conversationId'], 7);
    expect(find.text('删除纯聊天'), findsNothing);
    expect(find.text('保留Agent同号'), findsOneWidget);
  });

  testWidgets('home full swipe archives once without deleting or opening', (
    tester,
  ) async {
    nativeConversations = [selectionRow(1, '直接归档'), selectionRow(2, '保留')];
    final calls = <Map<String, dynamic>>[];
    installSelectionMutations(calls);
    ConversationThreadTarget? opened;
    await pumpSelectionHome(tester, onOpen: (target) => opened = target);
    await swipeHomeRow(tester, '直接归档', full: true);
    expect(calls, hasLength(1));
    expect(calls.single['action'], 'archive');
    expect(calls.single['conversationId'], 1);
    expect(opened, isNull);
    expect(find.text('直接归档'), findsNothing);
    expect(find.text('保留'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('home full swipe archives scheduled parent without its child', (
    tester,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await StorageService.setStringList('scheduled_tasks', [
      jsonEncode({
        'id': 'selection-schedule',
        'title': '计划',
        'targetKind': 'subagent',
        'parentConversationId': '1',
        'parentConversationMode': 'agent',
        'subagentPrompt': '执行计划',
        'type': 'fixedTime',
        'fixedTime': '18:00',
        'repeatDaily': true,
        'isEnabled': true,
        'createdAt': now,
        'nextExecutionTime': now + 3600000,
      }),
    ]);
    nativeConversations = [
      selectionRow(1, '滑动定时父对话'),
      selectionRow(2, '定时子对话保留', mode: 'subagent', parentId: 1),
    ];
    final calls = <Map<String, dynamic>>[];
    installSelectionMutations(calls);
    await pumpSelectionHome(tester);
    await swipeHomeRow(tester, '滑动定时父对话', full: true);
    expect(calls, hasLength(1));
    expect(calls.single['action'], 'archive');
    expect(calls.single['conversationId'], 1);
    expect(find.text('滑动定时父对话'), findsNothing);
    expect(find.text('定时子对话保留'), findsOneWidget);
    expect(
      await ScheduledTaskStorageService.loadScheduledTasks(),
      hasLength(1),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('home full swipe failure restores a usable conversation row', (
    tester,
  ) async {
    nativeConversations = [selectionRow(1, '归档重试')];
    final calls = <Map<String, dynamic>>[];
    var fail = true;
    installSelectionMutations(calls, respond: (_) async => !fail);
    ConversationThreadTarget? opened;
    await pumpSelectionHome(tester, onOpen: (target) => opened = target);
    await swipeHomeRow(tester, '归档重试', full: true);
    expect(find.text('归档重试'), findsOneWidget);
    expect(calls.map((call) => call['action']), ['archive']);
    expect(nativeConversations.single['isArchived'], isNull);
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('归档重试'));
    await tester.pumpAndSettle();
    expect(opened?.conversationId, 1);
    fail = false;
    await swipeHomeRow(tester, '归档重试', full: true);
    expect(calls.map((call) => call['action']), ['archive', 'archive']);
    expect(find.text('归档重试'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('home search full swipe keeps archive and unarchive reusable', (
    tester,
  ) async {
    nativeConversations = [selectionRow(1, '搜索归档目标')];
    final calls = <Map<String, dynamic>>[];
    installSelectionMutations(calls);
    await pumpSelectionHome(tester);
    await tester.tap(find.byKey(const ValueKey('home-search-button')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '搜索归档');
    await tester.pumpAndSettle(const Duration(milliseconds: 250));

    await swipeHomeRow(tester, '搜索归档目标', full: true);
    expect(calls.map((call) => call['action']), ['archive']);
    expect(find.text('搜索归档目标'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await swipeHomeRow(tester, '搜索归档目标', full: true);
    expect(calls.map((call) => call['action']), ['archive', 'unarchive']);
    expect(find.text('搜索归档目标'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('home swipe closes the previous menu and closes on scrolling', (
    tester,
  ) async {
    nativeConversations = [
      for (var id = 1; id <= 20; id++)
        selectionRow(
          id,
          '会话 $id',
          updatedAt: DateTime.now().millisecondsSinceEpoch - id * 1000,
        ),
    ];
    await pumpSelectionHome(tester);
    final firstRow = find.byKey(const ValueKey('home-source-row-agent:1'));
    final secondRow = find.byKey(const ValueKey('home-source-row-agent:2'));
    final originalLeft = tester.getTopLeft(firstRow).dx;
    await swipeHomeRow(tester, '会话 1');
    expect(tester.getTopLeft(firstRow).dx, lessThan(originalLeft));
    await swipeHomeRow(tester, '会话 2');
    expect(tester.getTopLeft(firstRow).dx, originalLeft);
    expect(tester.getTopLeft(secondRow).dx, lessThan(originalLeft));
    await tester.drag(find.byType(CustomScrollView), const Offset(0, -40));
    await tester.pumpAndSettle();
    expect(tester.getTopLeft(secondRow).dx, originalLeft);
  });

  testWidgets('home swipe is disabled while selecting or renaming', (
    tester,
  ) async {
    nativeConversations = [selectionRow(1, '保护编辑'), selectionRow(2, '另一条')];
    final calls = <Map<String, dynamic>>[];
    installSelectionMutations(calls);
    await pumpSelectionHome(tester);
    await startSelection(tester, '保护编辑');
    await tester.drag(find.text('保护编辑'), const Offset(-700, 0));
    await tester.pumpAndSettle();
    expect(calls, isEmpty);
    expect(find.byType(CustomSlidableAction), findsNothing);
    expect(find.text('已选择 1 项'), findsOneWidget);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    await tester.longPress(find.text('保护编辑'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('home-drawer-context-rename')));
    await tester.pumpAndSettle();
    expect(find.byType(TextField), findsOneWidget);
    await tester.drag(find.byType(TextField), const Offset(-700, 0));
    await tester.pumpAndSettle();
    expect(calls, isEmpty);
    expect(find.byType(CustomSlidableAction), findsNothing);
    expect(find.byType(TextField), findsOneWidget);
  });

  for (final size in const [Size(1069, 668), Size(668, 1069)]) {
    testWidgets(
      'Pad $size keeps the full row width throughout its context menu',
      (tester) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        nativeConversations = [selectionRow(1, '平板长消息条')];
        await pumpSelectionHome(tester);
        final source = find.byKey(const ValueKey('home-source-row-agent:1'));
        final sourceVisibility = find.byKey(
          const ValueKey('home-context-source-visibility-agent:1'),
        );
        final preview = find.byKey(
          const ValueKey('conversation-context-preview'),
        );
        final original = tester.getRect(source);
        expect(original.width, greaterThan(360));

        void expectFullWidth() {
          expect(tester.getSize(preview).width, closeTo(original.width, .01));
          final box = tester.renderObject<RenderBox>(preview);
          final paintedWidth =
              box.localToGlobal(Offset(box.size.width, 0)).dx -
              box.localToGlobal(Offset.zero).dx;
          expect(
            paintedWidth,
            inInclusiveRange(original.width * .969, original.width * 1.001),
          );
          expect(tester.getRect(source), original);
          expect(tester.widget<Opacity>(sourceVisibility).opacity, 0);
        }

        await tester.longPress(find.text('平板长消息条'));
        await tester.pump();
        for (final milliseconds in [40, 120, 300]) {
          await tester.pump(Duration(milliseconds: milliseconds));
          expectFullWidth();
        }
        expect(
          tester
              .getSize(
                find.byKey(const ValueKey('conversation-context-actions')),
              )
              .width,
          lessThanOrEqualTo(280),
        );
        await tester.binding.handlePopRoute();
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 100));
        expectFullWidth();
        await tester.pumpAndSettle();
        expect(preview, findsNothing);
        expect(tester.widget<Opacity>(sourceVisibility).opacity, 1);
        expect(tester.getRect(source), original);
        expect(tester.takeException(), isNull);
      },
    );
  }

  for (final scheduledParent in [false, true]) {
    testWidgets(
      'home ${scheduledParent ? 'scheduled parent' : 'regular row'} returns from its focused context preview',
      (tester) async {
        final now = DateTime.now().millisecondsSinceEpoch;
        nativeConversations = [selectionRow(1, '聚焦目标')];
        if (scheduledParent) {
          await StorageService.setStringList('scheduled_tasks', [
            jsonEncode({
              'id': 'selection-schedule',
              'title': '计划',
              'targetKind': 'subagent',
              'parentConversationId': '1',
              'parentConversationMode': 'agent',
              'subagentPrompt': '执行计划',
              'type': 'fixedTime',
              'fixedTime': '18:00',
              'repeatDaily': true,
              'isEnabled': true,
              'createdAt': now,
              'nextExecutionTime': now + 3600000,
            }),
          ]);
        }
        await pumpSelectionHome(tester);
        final source = find.byKey(const ValueKey('home-source-row-agent:1'));
        final sourceVisibility = find.byKey(
          const ValueKey('home-context-source-visibility-agent:1'),
        );
        final preview = find.byKey(
          const ValueKey('conversation-context-preview'),
        );
        final originalRect = tester.getRect(source);
        void expectSource({bool focused = false, bool samePosition = true}) {
          expect(tester.widget(source), isA<GestureDetector>());
          if (samePosition) expect(tester.getRect(source), originalRect);
          expect(
            tester.widget<Opacity>(sourceVisibility).opacity,
            focused ? 0 : 1,
          );
          expect(preview, focused ? findsOneWidget : findsNothing);
          expect(find.text('聚焦目标'), findsNWidgets(focused ? 2 : 1));
        }

        expectSource();
        await tester.longPress(find.text('聚焦目标'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 40));
        expectSource(focused: true);
        final earlyPreview = tester.getRect(preview);
        await tester.pumpAndSettle();
        expect(tester.getRect(preview).top, lessThan(earlyPreview.top));
        // A duplicate long-press callback cannot create a second menu owner.
        tester.widget<GestureDetector>(source).onLongPressStart!(
          LongPressStartDetails(globalPosition: originalRect.center),
        );
        await tester.pump();
        expect(find.byType(ConversationContextMenu), findsOneWidget);
        final contextRoute = ModalRoute.of(
          tester.element(find.byType(ConversationContextMenu)),
        )!;
        expect(
          contextRoute.transitionDuration,
          const Duration(milliseconds: 450),
        );
        expect(
          contextRoute.reverseTransitionDuration,
          const Duration(milliseconds: 200),
        );
        expect(contextRoute.barrierColor, isNull);
        await tester.binding.handlePopRoute();
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 160));
        expect(find.byType(ConversationContextMenu), findsOneWidget);
        expectSource(focused: true);
        await tester.pump(const Duration(milliseconds: 40));
        expect(tester.widget<Opacity>(sourceVisibility).opacity, 1);
        await tester.pumpAndSettle();
        expectSource();

        await tester.longPress(find.text('聚焦目标'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 40));
        expectSource(focused: true);
        await tester.binding.handlePopRoute();
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 200));
        expect(tester.widget<Opacity>(sourceVisibility).opacity, 1);
        await tester.pumpAndSettle();
        expectSource();

        await tester.longPress(find.text('聚焦目标'));
        await tester.pumpAndSettle();
        final selectAction = tester
            .widget<OmniMenuItem>(
              find.byKey(const ValueKey('home-drawer-context-select')),
            )
            .onTap!;
        final secondAction = tester
            .widget<OmniMenuItem>(
              find.byKey(const ValueKey('home-drawer-context-delete')),
            )
            .onTap!;
        selectAction();
        secondAction();
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 160));
        expectSource(focused: true);
        expect(
          find.byKey(const ValueKey('home-selection-count')),
          findsNothing,
        );
        await tester.pumpAndSettle();
        expectSource(samePosition: false);
        expect(find.text('已选择 1 项'), findsOneWidget);
        expect(find.byType(ConversationContextMenu), findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('a scrolled home row returns to its captured list position', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final now = DateTime.now().millisecondsSinceEpoch;
    nativeConversations = [
      for (var id = 1; id <= 20; id++)
        selectionRow(id, '滚动会话 $id', updatedAt: now - id * 1000),
    ];
    await pumpSelectionHome(tester);
    final source = find.byKey(const ValueKey('home-source-row-agent:12'));
    await tester.scrollUntilVisible(
      source,
      300,
      scrollable: find
          .descendant(
            of: find.byType(CustomScrollView),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    await tester.pumpAndSettle();
    final originalRect = tester.getRect(source);
    await tester.longPress(source);
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<ConversationContextMenu>(find.byType(ConversationContextMenu))
          .anchor,
      originalRect,
    );
    final sourceVisibility = find.byKey(
      const ValueKey('home-context-source-visibility-agent:12'),
    );
    expect(tester.widget<Opacity>(sourceVisibility).opacity, 0);
    await tester.binding.handlePopRoute();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(tester.widget<Opacity>(sourceVisibility).opacity, 1);
    expect(tester.getRect(source), originalRect);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('reduced-motion early dismissal restores the home source', (
    tester,
  ) async {
    nativeConversations = [selectionRow(1, '立即关闭')];
    await pumpSelectionHome(tester, disableAnimations: true);
    final source = find.byKey(const ValueKey('home-source-row-agent:1'));
    tester.widget<GestureDetector>(source).onLongPressStart!(
      LongPressStartDetails(globalPosition: tester.getCenter(source)),
    );
    Navigator.of(tester.element(source), rootNavigator: true).pop();
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<Opacity>(
            find.byKey(
              const ValueKey('home-context-source-visibility-agent:1'),
            ),
          )
          .opacity,
      1,
    );
    expect(find.byType(ConversationContextMenu), findsNothing);
    await tester.longPress(source);
    await tester.pumpAndSettle();
    expect(find.byType(ConversationContextMenu), findsOneWidget);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('home date headings stay visible despite legacy folded dates', (
    tester,
  ) async {
    final now = DateTime.now();
    final day =
        '${now.year.toString().padLeft(4, '0')}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';
    await StorageService.setString(
      'home_drawer_expanded_sections_v1',
      jsonEncode({'__home_drawer_date__omni_ai__$day': false}),
    );
    nativeConversations = [selectionRow(1, '今天的会话')];
    await pumpSelectionHome(tester);
    expect(find.text('今天的会话'), findsOneWidget);
    expect(
      find.ancestor(of: find.text('今天'), matching: find.byType(InkWell)),
      findsNothing,
    );
    await tester.tap(find.text('今天'), warnIfMissed: false);
    await tester.pumpAndSettle();
    expect(find.text('今天的会话'), findsOneWidget);
  });

  testWidgets(
    'home focused preview keeps its Agent, latest message, and running state',
    (tester) async {
      final coordinator = ChatConversationRuntimeCoordinator.instance;
      coordinator.resetForTest();
      coordinator.ensureInitialized();
      addTearDown(coordinator.resetForTest);
      nativeConversations = [
        {
          ...selectionRow(1, '检查项目配置'),
          'agentId': 'codex-acp',
          'lastMessage': '已检查配置\n没有发现冲突',
          'messageCount': 4,
          'contextSummary': '不可作为会话预览的内部上下文',
        },
      ];
      coordinator.beginAcpTurn(
        taskId: 'focused-preview-running-task',
        conversationId: 1,
        mode: kChatRuntimeModeAgent,
      );
      await pumpSelectionHome(tester, initialCategory: 'all');
      final row = find.byKey(const ValueKey('home-source-row-agent:1'));
      final icon = find.descendant(
        of: row,
        matching: find.byType(AgentBrandIcon),
      );
      expect(tester.widget<AgentBrandIcon>(icon).agentId, 'codex-acp');
      expect(find.text('已检查配置 没有发现冲突'), findsOneWidget);
      expect(find.text('不可作为会话预览的内部上下文'), findsNothing);
      expect(
        find.descendant(
          of: row,
          matching: find.byKey(const ValueKey('home-conversation-date')),
        ),
        findsOneWidget,
      );
      await tester.longPress(find.text('检查项目配置'));
      await tester.pumpAndSettle();
      expect(find.byType(ConversationContextMenu), findsOneWidget);
      final preview = find.byKey(
        const ValueKey('conversation-context-preview'),
      );
      expect(preview, findsOneWidget);
      expect(
        find.descendant(of: preview, matching: find.text('已检查配置 没有发现冲突')),
        findsOneWidget,
      );
      expect(
        tester
            .widget<AgentBrandIcon>(
              find.descendant(
                of: preview,
                matching: find.byType(AgentBrandIcon),
              ),
            )
            .agentId,
        'codex-acp',
      );
      expect(find.text('不可作为会话预览的内部上下文'), findsNothing);
      expect(
        find.descendant(
          of: preview,
          matching: find.byKey(
            const ValueKey('home-conversation-executing-dot'),
          ),
        ),
        findsOneWidget,
      );
      expect(
        tester
            .widget<Opacity>(
              find.byKey(
                const ValueKey('home-context-source-visibility-agent:1'),
              ),
            )
            .opacity,
        0,
      );
      await tester.tapAt(const Offset(5, 5));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'long-press focused preview keeps the searched snippet and archive marker',
    (tester) async {
      nativeConversations = [
        {
          ...selectionRow(1, '归档会话'),
          'summary': '历史匹配摘要',
          'lastMessage': '最新消息内容',
          'messageCount': 3,
          'isArchived': true,
        },
      ];
      await pumpSelectionHome(tester);
      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '历史匹配');
      await tester.pumpAndSettle(const Duration(milliseconds: 250));
      final row = find.byKey(const ValueKey('home-source-row-agent:1'));
      expect(
        find.descendant(of: row, matching: find.text('历史匹配摘要')),
        findsOneWidget,
      );
      expect(
        find.descendant(of: row, matching: find.byIcon(Icons.archive_outlined)),
        findsOneWidget,
      );
      await tester.longPress(find.text('归档会话'));
      await tester.pumpAndSettle();
      expect(find.byType(ConversationContextMenu), findsOneWidget);
      final preview = find.byKey(
        const ValueKey('conversation-context-preview'),
      );
      expect(preview, findsOneWidget);
      expect(
        find.descendant(of: preview, matching: find.text('历史匹配摘要')),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: preview,
          matching: find.byIcon(Icons.archive_outlined),
        ),
        findsOneWidget,
      );
      expect(
        tester
            .widget<Opacity>(
              find.byKey(
                const ValueKey('home-context-source-visibility-agent:1'),
              ),
            )
            .opacity,
        0,
      );
      expect(find.text('最新消息内容'), findsNothing);
      await tester.tapAt(const Offset(5, 5));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('home combines older dates into a static earlier section', (
    tester,
  ) async {
    final now = DateTime.now();
    nativeConversations = [
      selectionRow(
        1,
        '上个月的消息',
        updatedAt: DateTime(now.year, now.month - 1, 5).millisecondsSinceEpoch,
      ),
      selectionRow(
        2,
        '更早的消息',
        updatedAt: DateTime(now.year, now.month - 2, 16).millisecondsSinceEpoch,
      ),
    ];
    await pumpSelectionHome(tester);
    expect(find.text('更久以前'), findsOneWidget);
    expect(find.text('上个月的消息'), findsOneWidget);
    expect(find.text('更早的消息'), findsOneWidget);
    await tester.tap(find.text('更久以前'), warnIfMissed: false);
    await tester.pumpAndSettle();
    expect(find.text('上个月的消息'), findsOneWidget);
    expect(find.text('更早的消息'), findsOneWidget);
  });

  testWidgets('home context selection ignores a row removed during the menu', (
    tester,
  ) async {
    nativeConversations = [selectionRow(1, '即将移除')];
    final drawerKey = GlobalKey<HomeDrawerState>();
    await pumpSelectionHome(tester, key: drawerKey);
    await tester.longPress(find.text('即将移除'));
    await tester.pumpAndSettle();
    nativeConversations = [];
    drawerKey.currentState!.reloadConversations();
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('home-drawer-context-select')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('home-selection-count')), findsNothing);
    expect(find.byKey(const ValueKey('home-new-conversation')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'home selection toggles rows and back exits before closing search',
    (tester) async {
      nativeConversations = [selectionRow(1, '查找甲'), selectionRow(2, '查找乙')];
      ConversationThreadTarget? opened;
      await pumpSelectionHome(tester, onOpen: (value) => opened = value);
      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '查找');
      await tester.pumpAndSettle(const Duration(milliseconds: 250));
      await startSelection(tester, '查找甲');
      expect(find.text('已选择 1 项'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('home-agent-switcher')).hitTestable(),
        findsNothing,
      );
      expect(find.byKey(const ValueKey('home-new-conversation')), findsNothing);
      await tester.tap(find.text('查找乙'));
      await tester.pumpAndSettle();
      expect(find.text('已选择 2 项'), findsOneWidget);
      expect(opened, isNull);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('home-selection-count')), findsNothing);
      expect(find.byType(TextField), findsOneWidget);
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        '查找',
      );
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.byType(TextField), findsNothing);
    },
  );

  testWidgets('home batch archive targets selected thread keys across modes', (
    tester,
  ) async {
    nativeConversations = [
      selectionRow(7, 'Agent同号'),
      selectionRow(7, '纯聊天同号', mode: 'chat_only'),
      selectionRow(8, '保留的对话'),
    ];
    final calls = <Map<String, dynamic>>[];
    installSelectionMutations(calls);
    await pumpSelectionHome(tester, initialCategory: 'all');
    await startSelection(tester, 'Agent同号');
    await tester.tap(find.text('纯聊天同号'));
    await tester.tap(find.byKey(const ValueKey('home-selection-archive')));
    await tester.pumpAndSettle();
    expect(
      calls.map((call) => '${call['mode']}:${call['conversationId']}'),
      unorderedEquals(['agent:7', 'chat_only:7']),
    );
    expect(calls.every((call) => call['action'] == 'archive'), isTrue);
    expect(find.text('Agent同号'), findsNothing);
    expect(find.text('纯聊天同号'), findsNothing);
    expect(find.text('保留的对话'), findsOneWidget);
    expect(find.byKey(const ValueKey('home-selection-count')), findsNothing);
    expect(
      agentMethods.where((method) => method.startsWith('session/')),
      isEmpty,
    );
  });

  testWidgets(
    'home bulk delete confirms, cancels and prevents duplicate work',
    (tester) async {
      nativeConversations = [selectionRow(1, '删除甲'), selectionRow(2, '删除乙')];
      final calls = <Map<String, dynamic>>[];
      final pending = Completer<bool>();
      installSelectionMutations(calls, respond: (_) => pending.future);
      await pumpSelectionHome(tester);
      await startSelection(tester, '删除甲');
      await tester.tap(find.byKey(const ValueKey('home-selection-all')));
      await tester.tap(find.byKey(const ValueKey('home-selection-delete')));
      await tester.pumpAndSettle();
      expect(find.text('将删除 2 个对话，此操作无法撤销。'), findsOneWidget);
      await tester.tap(
        find.byKey(const ValueKey('home-selection-delete-cancel')),
      );
      await tester.pumpAndSettle();
      expect(calls, isEmpty);
      expect(find.text('已选择 2 项'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('home-selection-delete')));
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const ValueKey('home-selection-delete-confirm')),
      );
      await tester.pump(const Duration(milliseconds: 400));
      expect(calls, hasLength(1));
      final button = tester.widget<TextButton>(
        find.byKey(const ValueKey('home-selection-delete')),
      );
      expect(button.onPressed, isNull);
      await tester.binding.handlePopRoute();
      await tester.pump();
      expect(
        find.byKey(const ValueKey('home-selection-count')),
        findsOneWidget,
      );
      pending.complete(true);
      await tester.pumpAndSettle();
      expect(calls, hasLength(2));
      expect(find.text('删除甲'), findsNothing);
      expect(find.text('删除乙'), findsNothing);
    },
  );

  testWidgets('home batch partial failure retains only failed selections', (
    tester,
  ) async {
    nativeConversations = [selectionRow(1, '成功项'), selectionRow(2, '失败项')];
    final calls = <Map<String, dynamic>>[];
    var failSecond = true;
    installSelectionMutations(
      calls,
      respond: (args) async {
        if (args['conversationId'] == 2 && failSecond) {
          throw PlatformException(code: 'unavailable');
        }
        return true;
      },
    );
    await pumpSelectionHome(tester);
    await startSelection(tester, '成功项');
    await tester.tap(find.byKey(const ValueKey('home-selection-all')));
    await tester.tap(find.byKey(const ValueKey('home-selection-archive')));
    await tester.pumpAndSettle();
    expect(find.text('已选择 1 项'), findsOneWidget);
    expect(find.text('已归档 1 个，1 个失败，请重试'), findsOneWidget);
    expect(find.text('成功项'), findsNothing);
    expect(find.text('失败项'), findsOneWidget);
    failSecond = false;
    await tester.tap(find.byKey(const ValueKey('home-selection-archive')));
    await tester.pumpAndSettle();
    expect(calls.map((args) => args['conversationId']), [1, 2, 2]);
    expect(find.byKey(const ValueKey('home-selection-count')), findsNothing);
  });

  testWidgets(
    'home selection survives refreshed instances and prunes removed keys',
    (tester) async {
      nativeConversations = [selectionRow(1, '原始标题'), selectionRow(2, '第二条')];
      final drawerKey = GlobalKey<HomeDrawerState>();
      await pumpSelectionHome(tester, key: drawerKey);
      await startSelection(tester, '原始标题');
      nativeConversations = [
        selectionRow(2, '第二条'),
        selectionRow(1, '刷新标题'),
        selectionRow(3, '新插入'),
      ];
      drawerKey.currentState!.reloadConversations();
      await tester.pumpAndSettle();
      expect(find.text('已选择 1 项'), findsOneWidget);
      final selectedIcon = tester.widget<Icon>(
        find.byKey(const ValueKey('home-selection-check-agent:1')),
      );
      final otherIcon = tester.widget<Icon>(
        find.byKey(const ValueKey('home-selection-check-agent:3')),
      );
      expect(selectedIcon.icon, isNot(otherIcon.icon));
      nativeConversations.removeWhere((row) => row['id'] == 1);
      drawerKey.currentState!.reloadConversations();
      await tester.pumpAndSettle();
      expect(find.text('已选择 0 项'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('home-selection-check-agent:1')),
        findsNothing,
      );
    },
  );

  testWidgets(
    'home select visible includes every date and respects current search',
    (tester) async {
      final yesterday = DateTime.now()
          .subtract(const Duration(days: 1))
          .millisecondsSinceEpoch;
      nativeConversations = [
        selectionRow(1, '匹配今天'),
        selectionRow(2, '匹配置顶', pinned: true),
        selectionRow(3, '昨天的会话', updatedAt: yesterday),
      ];
      final calls = <Map<String, dynamic>>[];
      installSelectionMutations(calls);
      await pumpSelectionHome(tester);
      await startSelection(tester, '匹配今天');
      await tester.tap(find.byKey(const ValueKey('home-selection-all')));
      await tester.pumpAndSettle();
      expect(find.text('已选择 3 项'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('home-selection-cancel')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '置顶');
      await tester.pumpAndSettle(const Duration(milliseconds: 250));
      await startSelection(tester, '匹配置顶');
      await tester.tap(find.byKey(const ValueKey('home-selection-archive')));
      await tester.pumpAndSettle();
      expect(calls.map((args) => args['conversationId']), [2]);
      expect(
        nativeConversations.singleWhere((row) => row['id'] == 3)['isArchived'],
        isNull,
      );
    },
  );

  testWidgets(
    'home batch selection treats scheduled children as separate targets',
    (tester) async {
      final now = DateTime.now().millisecondsSinceEpoch;
      await StorageService.setStringList('scheduled_tasks', [
        jsonEncode({
          'id': 'selection-schedule',
          'title': '定时任务',
          'targetKind': 'subagent',
          'parentConversationId': '1',
          'parentConversationMode': 'agent',
          'subagentPrompt': '整理信息',
          'type': 'fixedTime',
          'fixedTime': '18:00',
          'repeatDaily': true,
          'isEnabled': true,
          'createdAt': now,
          'nextExecutionTime': now + 3600000,
        }),
      ]);
      nativeConversations = [
        selectionRow(1, '定时父对话'),
        selectionRow(2, '定时子对话', mode: 'subagent', parentId: 1),
        selectionRow(3, '普通条目'),
      ];
      final calls = <Map<String, dynamic>>[];
      installSelectionMutations(calls);
      await pumpSelectionHome(tester);
      await tester.tap(
        find.byKey(const ValueKey('home-scheduled-toggle-agent:1')),
      );
      await tester.pumpAndSettle();
      expect(find.text('定时子对话'), findsNothing);
      await startSelection(tester, '定时父对话');
      await tester.tap(find.byKey(const ValueKey('home-selection-all')));
      await tester.pumpAndSettle();
      expect(find.text('已选择 2 项'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('home-selection-archive')));
      await tester.pumpAndSettle();
      expect(calls.map((args) => args['conversationId']), [1, 3]);
      expect(
        nativeConversations.singleWhere((row) => row['id'] == 2)['isArchived'],
        isNull,
      );
      expect(
        await ScheduledTaskStorageService.loadScheduledTasks(),
        hasLength(1),
      );
      // After its parent is archived, the child is still an independent row.
      await startSelection(tester, '定时子对话');
      await tester.tap(find.byKey(const ValueKey('home-selection-archive')));
      await tester.pumpAndSettle();
      expect(calls.last['mode'], 'subagent');
      expect(calls.last['conversationId'], 2);
    },
  );

  testWidgets(
    'home select visible preserves selections across Agent and chat categories',
    (tester) async {
      nativeConversations = [
        selectionRow(1, '选中Agent'),
        {...selectionRow(2, 'Codex目标'), 'agentId': 'codex-acp'},
        selectionRow(3, '未选Agent'),
        selectionRow(4, '纯聊天目标', mode: 'chat_only'),
      ];
      final calls = <Map<String, dynamic>>[];
      installSelectionMutations(calls);
      await pumpSelectionHome(tester);
      await startSelection(tester, '选中Agent');
      await selectHomeCategory(tester, 'codex-acp');
      expect(find.text('选中Agent'), findsNothing);
      expect(find.text('已选择 1 项'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('home-selection-all')));
      await tester.pumpAndSettle();
      expect(find.text('已选择 2 项'), findsOneWidget);
      await selectHomeCategory(tester, 'chat');
      await tester.tap(find.byKey(const ValueKey('home-selection-all')));
      await tester.pumpAndSettle();
      expect(find.text('已选择 3 项'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('home-selection-archive')));
      await tester.pumpAndSettle();
      expect(calls.map((args) => args['conversationId']), [1, 2, 4]);
      expect(
        nativeConversations.singleWhere((row) => row['id'] == 3)['isArchived'],
        isNull,
      );
    },
  );

  testWidgets(
    'home batch fences stale reads and coalesces refresh during commit',
    (tester) async {
      nativeConversations = [selectionRow(1, '已提交'), selectionRow(2, '保留')];
      final staleRows = List<Map<String, Object?>>.from(nativeConversations);
      final staleRead = Completer<List<Map<String, Object?>>>();
      final mutation = Completer<bool>();
      var reads = 0;
      final calls = <Map<String, dynamic>>[];
      installSelectionMutations(
        calls,
        load: () async {
          reads++;
          return reads == 2 ? staleRead.future : nativeConversations;
        },
        respond: (_) => mutation.future,
      );
      final drawerKey = GlobalKey<HomeDrawerState>();
      await pumpSelectionHome(tester, key: drawerKey);
      await startSelection(tester, '已提交');
      drawerKey.currentState!.reloadConversations();
      await tester.pump();
      expect(reads, 2);
      await tester.tap(find.byKey(const ValueKey('home-selection-archive')));
      await tester.pump();
      drawerKey.currentState!.reloadConversations();
      drawerKey.currentState!.reloadConversations();
      await tester.pump();
      expect(reads, 2);
      mutation.complete(true);
      await tester.pumpAndSettle();
      expect(reads, 3);
      staleRead.complete(staleRows);
      await tester.pumpAndSettle();
      expect(find.text('已提交'), findsNothing);
      expect(find.text('保留'), findsOneWidget);
      expect(calls, hasLength(1));
    },
  );

  for (final duringConfirmation in [true, false]) {
    testWidgets(
      'home batch skips old Na owner after ${duringConfirmation ? 'confirmation' : 'pending operation'}',
      (tester) async {
        AssistsMessageService.initialize();
        addTearDown(() {
          AssistsMessageService.hasNaHistoryOwnerSnapshot = false;
          AssistsMessageService.visibleNaOwnerPrefix = null;
        });
        nativeConversations = [
          selectionRow(1, '本地项'),
          {...selectionRow(2, '旧Na项'), 'agentId': 'na-cloud'},
        ];
        final calls = <Map<String, dynamic>>[];
        final pending = Completer<bool>();
        installSelectionMutations(
          calls,
          respond: (_) =>
              duringConfirmation ? Future.value(true) : pending.future,
        );
        await pumpSelectionHome(tester, initialCategory: 'all');
        await startSelection(tester, '本地项');
        await tester.tap(find.byKey(const ValueKey('home-selection-all')));
        await tester.tap(
          find.byKey(
            ValueKey(
              duringConfirmation
                  ? 'home-selection-delete'
                  : 'home-selection-archive',
            ),
          ),
        );
        if (duringConfirmation) {
          // Let the sheet finish entering before interacting with its action;
          // the owner change still happens while confirmation is pending.
          await tester.pumpAndSettle();
        } else {
          await tester.pump(const Duration(milliseconds: 400));
        }
        nativeConversations.removeWhere((row) => row['agentId'] == 'na-cloud');
        final handled = Completer<void>();
        await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
          assistCoreChannel.name,
          const StandardMethodCodec().encodeMethodCall(
            const MethodCall('onConversationListChanged', {
              'reason': 'na_history_owner_changed',
              'naOwnerPrefix': 'new-account',
            }),
          ),
          (_) => handled.complete(),
        );
        await handled.future;
        await tester.pump();
        if (duringConfirmation) {
          await tester.tap(
            find.byKey(const ValueKey('home-selection-delete-confirm')),
          );
        } else {
          expect(calls, hasLength(1));
          pending.complete(true);
        }
        await tester.pumpAndSettle();
        expect(calls.map((args) => args['conversationId']), [1]);
        expect(find.textContaining('1 个对话已不可用，未操作'), findsOneWidget);
        expect(find.text('已选择 0 项'), findsOneWidget);
        expect(
          agentMethods.where((method) => method.startsWith('session/')),
          isEmpty,
        );
      },
    );
  }

  testWidgets(
    'home selection controls fit a narrow screen with enlarged text',
    (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      nativeConversations = [selectionRow(1, '较长的会话标题依然能够在列表上被选择')];
      await pumpSelectionHome(tester, textScale: 1.5);
      await startSelection(tester, '较长的会话标题依然能够在列表上被选择');
      expect(tester.takeException(), isNull);
      expect(
        find.byKey(const ValueKey('home-selection-delete')),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const ValueKey('home-selection-cancel')));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('home-new-conversation')),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'home categories bind new chats and retain legacy Xiaowan history',
    (tester) async {
      final now = DateTime.now().millisecondsSinceEpoch;
      Map<String, Object?> row(
        int id,
        String title,
        String mode, [
        String? agent,
      ]) => {
        'id': id,
        'title': title,
        'mode': mode,
        'agentId': agent,
        'status': 0,
        'messageCount': 0,
        'createdAt': now,
        'updatedAt': now,
      };
      nativeConversations = [
        row(1, '旧版小万对话', 'normal'),
        row(2, '空绑定小万对话', 'agent', ''),
        row(3, 'Na 对话', 'agent', 'na-cloud'),
        row(4, 'Codex 对话', 'agent', 'codex-acp'),
        row(5, '纯聊天记录', 'chat_only'),
        row(6, 'OpenClaw 对话', 'openclaw'),
      ];
      ConversationThreadTarget? selected;
      await tester.pumpWidget(
        _buildProviderScope(
          child: MaterialApp(
            theme: AppTheme.lightTheme,
            locale: const Locale('zh'),
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: HomeDrawer(
              homePage: true,
              embedded: true,
              closeOnNavigate: false,
              onThreadTargetSelected: (target) => selected = target,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // The global ACP selection must not override Home's initial Xiaowan.
      expect(find.text('旧版小万对话'), findsOneWidget);
      expect(find.text('空绑定小万对话'), findsOneWidget);
      expect(find.text('Na 对话'), findsNothing);
      expect(find.text('Codex 对话'), findsNothing);
      expect(find.text('纯聊天记录'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('home-new-conversation')));
      expect(selected?.agentId, 'xiaowan-acp');
      expect(selected?.mode, ConversationMode.agent);

      expect(find.text('OpenClaw 对话'), findsNothing);
      await tester.tap(find.text('空绑定小万对话'));
      expect(selected?.conversationId, 2);
      expect(selected?.agentId, 'xiaowan-acp');
      await tester.tap(find.byKey(const ValueKey('home-new-conversation')));
      expect(selected?.agentId, 'xiaowan-acp');

      for (final entry in {
        'na-cloud': 'Na 对话',
        'codex-acp': 'Codex 对话',
      }.entries) {
        await selectHomeCategory(tester, entry.key);
        expect(find.text(entry.value), findsOneWidget);
        expect(find.text('旧版小万对话'), findsNothing);
        expect(find.text('纯聊天记录'), findsNothing);
        await tester.tap(find.byKey(const ValueKey('home-new-conversation')));
        expect(selected?.agentId, entry.key);
        expect(selected?.mode, ConversationMode.agent);
        expect(selected?.conversationId, isNull);
      }

      await selectHomeCategory(tester, 'chat');
      expect(find.text('纯聊天记录'), findsOneWidget);
      expect(find.text('Codex 对话'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('home-new-conversation')));
      expect(selected?.mode, ConversationMode.chatOnly);
      expect(selected?.agentId, isNull);
      await selectHomeCategory(tester, 'all');
      for (final title in [
        '旧版小万对话',
        '空绑定小万对话',
        'Na 对话',
        'Codex 对话',
        '纯聊天记录',
        'OpenClaw 对话',
      ]) {
        expect(find.text(title), findsOneWidget);
      }
      await tester.tap(find.byKey(const ValueKey('home-new-conversation')));
      expect(selected?.agentId, 'xiaowan-acp');
      expect(selected?.mode, ConversationMode.agent);
      expect(agentMethods, isNot(contains('agent/select')));
      expect(tester.takeException(), isNull);
    },
  );

  for (final status in ['unchecked', 'offline']) {
    testWidgets('production Na remains selectable on home: $status', (
      tester,
    ) async {
      nativeAgents.firstWhere((agent) => agent['id'] == 'na-cloud').addAll({
        'status': status,
        'configurationAvailable': false,
      });
      ConversationThreadTarget? created;
      await pumpSelectionHome(tester, onOpen: (target) => created = target);
      await selectHomeCategory(tester, 'na-cloud');
      expect(
        tester.widget<Text>(find.byKey(const ValueKey('home-agent-name'))).data,
        'Na',
      );
      await tester.tap(find.byKey(const ValueKey('home-new-conversation')));
      expect(created?.agentId, 'na-cloud');
      expect(created?.mode, ConversationMode.agent);
      expect(created?.conversationId, isNull);
      expect(agentMethods, isNot(contains('agent/select')));
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets(
    'home Agent switcher shows current identity and only available catalog entries',
    (tester) async {
      nativeAgents.addAll([
        {
          'id': 'claude-code-acp',
          'name': 'Claude Code',
          'enabled': true,
          'installed': false,
        },
        {'id': 'unprobed-acp', 'name': 'Unchecked', 'enabled': true},
        {
          'id': 'disabled-acp',
          'name': 'Disabled',
          'enabled': false,
          'installed': true,
        },
      ]);
      await pumpSelectionHome(tester);
      final switcher = find.byKey(const ValueKey('home-agent-switcher'));
      final brand = find.descendant(
        of: switcher,
        matching: find.byType(AgentBrandIcon),
      );
      expect(tester.widget<AgentBrandIcon>(brand).agentId, 'xiaowan-acp');
      expect(
        find.descendant(of: switcher, matching: find.text('小万')),
        findsOneWidget,
      );
      expect(find.byKey(const ValueKey('home-filter-all')), findsNothing);
      expect(
        find.byKey(const ValueKey('home-filter-xiaowan-acp')),
        findsNothing,
      );
      await tester.tap(switcher);
      await tester.pumpAndSettle();
      for (final unavailable in [
        'claude-code-acp',
        'unprobed-acp',
        'disabled-acp',
      ]) {
        expect(
          find.byKey(ValueKey('home-agent-option-$unavailable')),
          findsNothing,
        );
      }
      for (final available in [
        'xiaowan-acp',
        'na-cloud',
        'codex-acp',
        'all',
        'chat',
      ]) {
        expect(
          find.byKey(ValueKey('home-agent-option-$available')),
          findsOneWidget,
        );
      }
      expect(find.text('全部'), findsOneWidget);
      final codex = find.byKey(const ValueKey('home-agent-option-codex-acp'));
      expect(
        find.descendant(of: codex, matching: find.byType(AgentBrandIcon)),
        findsOneWidget,
      );
      await tester.tap(codex);
      await tester.pumpAndSettle();
      expect(
        find.descendant(of: switcher, matching: find.text('Codex')),
        findsOneWidget,
      );
      expect(tester.widget<AgentBrandIcon>(brand).agentId, 'codex-acp');
      expect(
        find.byKey(const ValueKey('home-agent-option-codex-acp')),
        findsNothing,
      );
      expect(
        tester.getSize(find.byKey(const ValueKey('home-new-conversation'))),
        const Size(52, 52),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('returning home refreshes the existing Agent catalog', (
    tester,
  ) async {
    late BuildContext homeContext;
    await tester.pumpWidget(
      _buildProviderScope(
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Builder(
            builder: (context) {
              homeContext = context;
              return const HomeDrawer(homePage: true, embedded: true);
            },
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('home-agent-switcher')));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('home-agent-option-claude-code-acp')),
      findsNothing,
    );
    await tester.tap(find.byKey(const ValueKey('home-agent-option-codex-acp')));
    await tester.pumpAndSettle();
    final initialCatalogReads = agentMethods
        .where((method) => method == 'agent/list')
        .length;
    unawaited(
      Navigator.of(homeContext).push(
        MaterialPageRoute<void>(
          builder: (_) => const Scaffold(body: Text('Agent 配置')),
        ),
      ),
    );
    await tester.pumpAndSettle();
    nativeAgents.add({
      'id': 'claude-code-acp',
      'name': 'Claude Code',
      'enabled': true,
      'installed': true,
      'status': 'online',
    });
    nativeAgents = [
      for (final agent in nativeAgents)
        if (agent['id'] == 'codex-acp')
          {...agent, 'name': 'Codex Workspace'}
        else
          agent,
    ];
    Navigator.of(homeContext).pop();
    await tester.pumpAndSettle();
    expect(find.text('Codex Workspace'), findsOneWidget);
    expect(
      agentMethods.where((method) => method == 'agent/list').length,
      initialCatalogReads + 1,
    );
    await tester.tap(find.byKey(const ValueKey('home-agent-switcher')));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('home-agent-option-claude-code-acp')),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const ValueKey('home-agent-option-codex-acp')));
    await tester.pumpAndSettle();
    unawaited(
      Navigator.of(homeContext).push(
        MaterialPageRoute<void>(
          builder: (_) => const Scaffold(body: Text('Agent 配置')),
        ),
      ),
    );
    await tester.pumpAndSettle();
    nativeAgents = [
      for (final agent in nativeAgents)
        if (agent['id'] == 'codex-acp') {...agent, 'enabled': false} else agent,
    ];
    Navigator.of(homeContext).pop();
    await tester.pumpAndSettle();
    expect(
      tester.widget<Text>(find.byKey(const ValueKey('home-agent-name'))).data,
      '小万',
    );
    await tester.tap(find.byKey(const ValueKey('home-agent-switcher')));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('home-agent-option-codex-acp')),
      findsNothing,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'search exit retains the selected Agent and new conversation target',
    (tester) async {
      nativeConversations = [
        {...selectionRow(1, 'Codex对话'), 'agentId': 'codex-acp'},
        {...selectionRow(2, 'Na对话'), 'agentId': 'na-cloud'},
        selectionRow(3, '小万对话'),
      ];
      ConversationThreadTarget? created;
      await pumpSelectionHome(tester, onOpen: (target) => created = target);
      for (final agentId in ['codex-acp', 'na-cloud']) {
        await selectHomeCategory(tester, agentId);
        await tester.tap(find.byKey(const ValueKey('home-search-button')));
        await tester.pumpAndSettle();
        expect(
          find.byKey(const ValueKey('home-agent-switcher')).hitTestable(),
          findsNothing,
        );
        await tester.enterText(find.byType(TextField), '对话');
        await tester.pumpAndSettle(const Duration(milliseconds: 250));
        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();
        expect(find.byType(TextField), findsNothing);
        final brand = find.descendant(
          of: find.byKey(const ValueKey('home-agent-switcher')),
          matching: find.byType(AgentBrandIcon),
        );
        expect(tester.widget<AgentBrandIcon>(brand).agentId, agentId);
        await tester.tap(find.byKey(const ValueKey('home-new-conversation')));
        expect(created?.agentId, agentId);
        expect(created?.mode, ConversationMode.agent);
        expect(created?.conversationId, isNull);
      }
      expect(agentMethods, isNot(contains('agent/select')));
    },
  );

  testWidgets(
    'home update uses cached status, refreshes and opens the shared dialog',
    (tester) async {
      final refreshedStatus = Completer<Map<String, Object?>>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(appUpdateChannel, (call) async {
            updateCalls.add(call);
            switch (call.method) {
              case 'getCachedStatus':
                return updateStatus();
              case 'checkNow':
                return refreshedStatus.future;
              case 'getBetaOptIn':
                return false;
              case 'getApkDownloadSource':
                return 'worker';
              default:
                return null;
            }
          });
      await pumpSelectionHome(tester);
      final update = find.byKey(const ValueKey('home-app-update-button'));
      expect(update, findsOneWidget);
      expect(find.byTooltip('发现新版本 v1.0.1'), findsOneWidget);
      expect(
        tester.getRect(update).right,
        lessThanOrEqualTo(
          tester.getRect(find.byKey(const ValueKey('home-search-button'))).left,
        ),
      );

      refreshedStatus.complete(updateStatus(version: '1.0.2'));
      await tester.pumpAndSettle();
      await tester.tap(update);
      await tester.pumpAndSettle();
      expect(find.text('发现新版本'), findsOneWidget);
      expect(find.text('v1.0.2'), findsOneWidget);
      expect(find.text('Home update regression test'), findsOneWidget);
      expect(find.text('立即更新'), findsOneWidget);
      await tester.tap(find.text('稍后'));
      await tester.pumpAndSettle();
      expect(update, findsOneWidget);
      expect(
        updateCalls.where((call) => call.method == 'installLatestApk'),
        isEmpty,
      );

      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      expect(update, findsNothing);
      expect(
        tester.getRect(find.byKey(const ValueKey('home-search-input'))).left,
        16,
      );
      await tester.tap(find.byKey(const ValueKey('home-close-search')));
      await tester.pumpAndSettle();
      expect(update, findsOneWidget);
      expect(find.byTooltip('发现新版本 v1.0.2'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'home update follows missing, current and dismissed version status',
    (tester) async {
      await pumpSelectionHome(tester);
      final update = find.byKey(const ValueKey('home-app-update-button'));
      expect(update, findsNothing);

      final dismissed = AppUpdateStatus.fromMap(updateStatus());
      await AppUpdateService.dismissBanner(dismissed);
      nativeUpdateStatus = updateStatus();
      await AppUpdateService.refreshIfNeeded();
      await tester.pumpAndSettle();
      expect(update, findsNothing);

      nativeUpdateStatus = updateStatus(version: '1.0.2');
      await AppUpdateService.refreshIfNeeded();
      await tester.pumpAndSettle();
      expect(update, findsOneWidget);

      nativeUpdateStatus = updateStatus(version: '1.0.2', hasUpdate: false);
      await AppUpdateService.refreshIfNeeded();
      await tester.pumpAndSettle();
      expect(update, findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'home resumes with a non-forced update refresh and disposes its listener',
    (tester) async {
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await pumpSelectionHome(tester);
      updateCalls.clear();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      nativeUpdateStatus = updateStatus();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(updateCalls, hasLength(1));
      expect(updateCalls.single.method, 'checkNow');
      expect(updateCalls.single.arguments, {'force': false});
      expect(
        find.byKey(const ValueKey('home-app-update-button')),
        findsOneWidget,
      );

      await tester.pumpWidget(const SizedBox());
      updateCalls.clear();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(updateCalls, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('long Agent names fit the narrow home and selection headers', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    const longName = '一个名称很长的自定义工作环境 Agent Workspace';
    nativeAgents.add({
      'id': 'long-agent-acp',
      'name': longName,
      'enabled': true,
      'installed': true,
      'status': 'online',
    });
    nativeConversations = [
      {...selectionRow(1, '长名称Agent对话'), 'agentId': 'long-agent-acp'},
    ];
    nativeUpdateStatus = updateStatus();
    await pumpSelectionHome(tester, textScale: 1.5);
    await selectHomeCategory(tester, 'long-agent-acp');
    final name = find.byKey(const ValueKey('home-agent-name'));
    expect(tester.widget<Text>(name).data, longName);
    expect(tester.widget<Text>(name).maxLines, 1);
    expect(tester.widget<Text>(name).overflow, TextOverflow.ellipsis);
    final header = tester.getRect(find.byType(HomeSearchHeader));
    final switcher = tester.getRect(
      find.byKey(const ValueKey('home-agent-switcher')),
    );
    expect(header.contains(tester.getCenter(name)), isTrue);
    expect(find.text('首页'), findsNothing);
    expect(header.height, 72);
    expect(switcher.height, 44);
    expect(
      switcher.center.dy,
      tester.getCenter(find.byKey(const ValueKey('home-search-button'))).dy,
    );
    final paragraph = tester.renderObject<RenderParagraph>(
      find.descendant(of: name, matching: find.byType(RichText)),
    );
    final prefix = TextPainter(
      text: TextSpan(text: '一个…', style: paragraph.text.style),
      textDirection: paragraph.textDirection,
      textScaler: paragraph.textScaler,
    )..layout();
    expect(paragraph.size.width, greaterThanOrEqualTo(prefix.width));
    expect(
      paragraph.getBoxesForSelection(
        const TextSelection(baseOffset: 0, extentOffset: 2),
      ),
      isNotEmpty,
    );
    prefix.dispose();
    expect(find.byKey(const ValueKey('home-app-update-button')), findsNothing);
    expect(
      find.byKey(const ValueKey('home-menu-update-badge')),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const ValueKey('home-menu-button')));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    final update = find.byKey(const ValueKey('home-menu-app-update'));
    expect(update, findsOneWidget);
    expect(
      find.descendant(of: update, matching: find.text('v1.0.1')),
      findsOneWidget,
    );
    await tester.tap(update);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('发现新版本'), findsOneWidget);
    expect(find.text('立即更新'), findsOneWidget);
    await tester.tap(find.text('稍后'));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('home-menu-update-badge')),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
    await startSelection(tester, '长名称Agent对话');
    expect(find.byKey(const ValueKey('home-agent-switcher')), findsOneWidget);
    expect(find.text('已选择 1 项'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('home filters and opens the original conversation identity', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    final now = DateTime.now().millisecondsSinceEpoch;
    nativeConversations = [
      {
        'id': 101,
        'title': '查看当前工作环境',
        'agentId': 'codex-acp',
        'mode': 'agent',
        'status': 0,
        'messageCount': 0,
        'createdAt': now,
        'updatedAt': now,
      },
      {
        'id': 102,
        'title': '简单问候',
        'mode': 'chat_only',
        'status': 0,
        'messageCount': 0,
        'createdAt': now,
        'updatedAt': now,
      },
    ];
    ConversationThreadTarget? selected;
    await tester.pumpWidget(
      _buildProviderScope(
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: HomeDrawer(
            homePage: true,
            embedded: true,
            closeOnNavigate: false,
            onThreadTargetSelected: (target) => selected = target,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await selectHomeCategory(tester, 'all');
    expect(find.text('查看当前工作环境'), findsOneWidget);
    expect(find.text('简单问候'), findsOneWidget);
    await tester.tap(find.text('今天'));
    await tester.pumpAndSettle();
    expect(find.text('查看当前工作环境'), findsOneWidget);
    await tester.tap(find.text('查看当前工作环境'));
    expect(selected?.conversationId, 101);
    expect(selected?.agentId, 'codex-acp');
    await selectHomeCategory(tester, 'chat');
    expect(find.text('查看当前工作环境'), findsNothing);
    expect(find.text('简单问候'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('home-new-conversation')));
    expect(selected?.conversationId, isNull);
    expect(selected?.mode, ConversationMode.chatOnly);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'home uses FlClash search group and menu retains other shortcuts',
    (tester) async {
      void expectCircleGeometry(String key) {
        final button = find.byKey(ValueKey(key));
        expect(tester.getSize(button), const Size.square(44));
        for (final icon
            in find
                .descendant(of: button, matching: find.byType(Icon))
                .evaluate()) {
          expect(
            tester.getSize(find.byElementPredicate((e) => e == icon)),
            const Size.square(24),
          );
        }
      }

      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
      await tester.pumpWidget(
        _buildProviderScope(
          child: MaterialApp(
            theme: AppTheme.lightTheme,
            locale: const Locale('zh'),
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: const HomeDrawer(
              homePage: true,
              embedded: true,
              closeOnNavigate: false,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(TextField), findsNothing);
      expect(find.text('设置'), findsNothing);
      expectCircleGeometry('home-search-button');
      expectCircleGeometry('home-menu-button');
      final searchBounds = tester.getRect(
        find.byKey(const ValueKey('home-search-button')),
      );
      final menuBounds = tester.getRect(
        find.byKey(const ValueKey('home-menu-button')),
      );
      expect(searchBounds.right, menuBounds.left);
      expect(searchBounds.center.dy, menuBounds.center.dy);
      final switcher = find.byKey(const ValueKey('home-agent-switcher'));
      expect(tester.getRect(switcher).height, searchBounds.height);
      expect(tester.getRect(switcher).center.dy, searchBounds.center.dy);
      expect(find.text('首页'), findsNothing);
      expect(find.byType(FlClashToolbarCapsule), findsOneWidget);
      expect(
        tester
            .widget<Text>(
              find.byKey(const ValueKey('home-agent-switch-action')),
            )
            .style
            ?.color,
        isNot(AppColors.primaryBlue),
      );
      final sharedCapsule = find.ancestor(
        of: find.byKey(const ValueKey('home-search-button')),
        matching: find.byWidgetPredicate(
          (widget) =>
              widget is DecoratedBox &&
              widget.decoration is ShapeDecoration &&
              (widget.decoration as ShapeDecoration).shape
                  is RoundedSuperellipseBorder,
        ),
      );
      expect(sharedCapsule, findsOneWidget);
      expect(tester.getSize(sharedCapsule), const Size(88, 44));
      final groupSource = find.ancestor(
        of: sharedCapsule,
        matching: find.byType(FlClashPopupSource),
      );
      expect(groupSource, findsOneWidget);
      expect(tester.getSize(groupSource), const Size(88, 44));
      expect(
        find.descendant(
          of: sharedCapsule,
          matching: find.byKey(const ValueKey('home-menu-button')),
        ),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const ValueKey('home-menu-button')));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('home-search-button')).hitTestable(),
        findsNothing,
      );
      expect(
        find.byWidgetPredicate(
          (widget) => widget is PopupMenuItem<VoidCallback>,
        ),
        findsNWidgets(6),
      );
      expect(find.text('设置'), findsNothing);
      expect(find.text('轨迹'), findsNothing);
      expect(find.text('搜索对话'), findsNothing);
      await tester.tapAt(const Offset(4, 4));
      await tester.pumpAndSettle();
      expect(
        tester.getCenter(find.byKey(const ValueKey('home-search-button'))).dx,
        lessThan(
          tester.getCenter(find.byKey(const ValueKey('home-menu-button'))).dx,
        ),
      );
      final menuCenter = tester.getCenter(
        find.byKey(const ValueKey('home-menu-button')),
      );
      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      await tester.pumpAndSettle();
      expect(find.byType(TextField), findsOneWidget);
      expect(
        find.byKey(const ValueKey('home-agent-name')).hitTestable(),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey('home-agent-switcher')).hitTestable(),
        findsNothing,
      );
      expect(find.text('取消'), findsNothing);
      expectCircleGeometry('home-close-search');
      expect(
        tester.getCenter(find.byKey(const ValueKey('home-close-search'))),
        menuCenter,
      );
      await tester.tap(find.byKey(const ValueKey('home-close-search')));
      await tester.pumpAndSettle();
      expect(find.byType(TextField), findsNothing);
      expect(find.byKey(const ValueKey('home-menu-button')), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'home search can reverse and reopen without replacing the editor or shifting history',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      nativeConversations = [selectionRow(1, '固定会话')];
      await pumpSelectionHome(tester);
      final historyTop = tester.getTopLeft(find.byType(CustomScrollView)).dy;

      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 80));
      expect(tester.getTopLeft(find.byType(CustomScrollView)).dy, historyTop);
      final field = tester.widget<TextField>(find.byType(TextField));
      final editor = tester.state<EditableTextState>(find.byType(EditableText));
      expect(field.focusNode!.hasFocus, isTrue);
      await tester.enterText(find.byType(TextField), 'unfinished query');
      await tester.pump();

      // The container can be retargeted while its spring is still moving.
      tester
          .widget<IconButton>(find.byKey(const ValueKey('home-close-search')))
          .onPressed!();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));
      expect(field.controller!.text, isEmpty);
      expect(field.focusNode!.hasFocus, isFalse);
      expect(find.byType(TextField), findsOneWidget);
      expect(tester.getTopLeft(find.byType(CustomScrollView)).dy, historyTop);
      tester
          .widget<IconButton>(find.byKey(const ValueKey('home-search-button')))
          .onPressed!();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 80));
      expect(
        tester.state<EditableTextState>(find.byType(EditableText)),
        same(editor),
      );
      expect(field.focusNode!.hasFocus, isTrue);
      expect(field.controller!.text, isEmpty);
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(find.byType(CustomScrollView)).dy, historyTop);
      await tester.tap(find.byKey(const ValueKey('home-close-search')));
      await tester.pumpAndSettle();
      expect(find.byType(TextField), findsNothing);
      expect(tester.getTopLeft(find.byType(CustomScrollView)).dy, historyTop);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'home search reaches its final layout immediately with reduced motion',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      nativeUpdateStatus = updateStatus();
      await pumpSelectionHome(tester, disableAnimations: true);
      final menuCenter = tester.getCenter(
        find.byKey(const ValueKey('home-menu-button')),
      );
      final update = find.byKey(const ValueKey('home-app-update-button'));
      expect(update, findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pump();
      await tester.pump();
      expect(update, findsNothing);
      expect(
        tester.getRect(find.byKey(const ValueKey('home-search-input'))).left,
        16,
      );
      expect(
        tester.getCenter(find.byKey(const ValueKey('home-close-search'))),
        menuCenter,
      );
      final field = tester.widget<TextField>(find.byType(TextField));
      expect(field.focusNode!.hasFocus, isTrue);
      await tester.tap(find.byKey(const ValueKey('home-close-search')));
      await tester.pump();
      await tester.pump();
      expect(find.byType(TextField), findsNothing);
      expect(update, findsOneWidget);
      expect(field.focusNode!.hasFocus, isFalse);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('home menu refreshes plugin registry without remounting', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    var installed = false;
    var running = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginChannel, (call) async {
          if (call.method == 'listActions') {
            return installed
                ? <Map<String, Object?>>[
                    {
                      'id': 'open_kimi_web',
                      'pluginId': 'com.omnimind.agent-web',
                      'displayName': 'Kimi Code Web',
                      'presentation': <String, Object?>{
                        'placements': <String>['home_drawer_quick_launch'],
                        'agentId': 'kimi-code-acp',
                        'statusAction': 'get_kimi_web_status',
                        'stopAction': 'stop_kimi_web',
                      },
                    },
                  ]
                : <Map<String, Object?>>[];
          }
          if (call.method == 'invokeAction') {
            return <String, Object?>{
              'running': running,
              'code': running ? 'RUNNING' : 'STOPPED',
            };
          }
          return null;
        });
    await tester.pumpWidget(
      _buildProviderScope(
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const HomeDrawer(
            homePage: true,
            embedded: true,
            closeOnNavigate: false,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final homeState = tester.state(find.byType(HomeDrawer));

    await tester.tap(find.byKey(const ValueKey('home-menu-button')));
    await tester.pumpAndSettle();
    expect(find.text('Agent WebUI'), findsNothing);
    await tester.tapAt(const Offset(4, 4));
    await tester.pumpAndSettle();

    installed = true;
    final menuPress = await tester.startGesture(
      tester.getCenter(find.byKey(const ValueKey('home-menu-button'))),
    );
    await tester.pumpAndSettle();
    expect(find.text('Agent WebUI'), findsOneWidget);
    running = true;
    await menuPress.moveTo(tester.getCenter(find.text('Agent WebUI')));
    await tester.pump();
    await menuPress.up();
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('home-drawer-web-kimi-code-acp')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('home-drawer-web-stop-kimi-code-acp')),
      findsOneWidget,
    );
    await tester.tapAt(const Offset(4, 4));
    await tester.pumpAndSettle();

    installed = false;
    await tester.tap(find.byKey(const ValueKey('home-menu-button')));
    await tester.pumpAndSettle();
    expect(find.text('Agent WebUI'), findsNothing);
    expect(tester.state(find.byType(HomeDrawer)), same(homeState));
    expect(tester.takeException(), isNull);
  });

  testWidgets('home menu toggles the pet through the existing overlay owner', (
    tester,
  ) async {
    await pumpSelectionHome(tester);
    await tester.tap(find.byKey(const ValueKey('home-menu-button')));
    await tester.pumpAndSettle();
    expect(find.text('唤起宠物'), findsOneWidget);
    await tester.tap(find.text('唤起宠物'));
    await tester.pumpAndSettle();
    expect(petShowing, isTrue);
    expect(
      overlayCalls.where((method) => method == 'showPetOverlay').length,
      1,
    );

    await tester.tap(find.byKey(const ValueKey('home-menu-button')));
    await tester.pumpAndSettle();
    expect(find.text('收起宠物'), findsOneWidget);
    expect(find.text('唤起宠物'), findsNothing);
    await tester.tap(find.text('收起宠物'));
    await tester.pumpAndSettle();
    expect(petShowing, isFalse);
    expect(
      overlayCalls.where((method) => method == 'hidePetOverlay').length,
      1,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('home menu refreshes a pet hidden outside the app', (
    tester,
  ) async {
    petShowing = true;
    await pumpSelectionHome(tester);
    await tester.tap(find.byKey(const ValueKey('home-menu-button')));
    await tester.pumpAndSettle();
    expect(find.text('收起宠物'), findsOneWidget);
    await tester.tapAt(const Offset(4, 4));
    await tester.pumpAndSettle();

    petShowing = false;
    await tester.tap(find.byKey(const ValueKey('home-menu-button')));
    await tester.pumpAndSettle();
    expect(find.text('唤起宠物'), findsOneWidget);
    expect(find.text('收起宠物'), findsNothing);
    expect(overlayCalls, isNot(contains('showPetOverlay')));
    expect(overlayCalls, isNot(contains('hidePetOverlay')));
  });

  testWidgets('home pet action keeps permission gating and permits retry', (
    tester,
  ) async {
    overlayGranted = false;
    await pumpSelectionHome(tester);
    await tester.tap(find.byKey(const ValueKey('home-menu-button')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('唤起宠物'));
    await tester.pumpAndSettle();
    expect(find.byType(PermissionPromptSheet), findsOneWidget);
    expect(overlayCalls, isNot(contains('showPetOverlay')));
    expect(
      tester
          .widget<GestureDetector>(
            find.byKey(
              const ValueKey('pet-overlay-permission-continue-button'),
            ),
          )
          .onTap,
      isNull,
    );
    await tester.tapAt(const Offset(4, 4));
    await tester.pumpAndSettle();
    expect(find.byType(PermissionPromptSheet), findsNothing);
    expect(petShowing, isFalse);

    overlayGranted = true;
    await tester.tap(find.byKey(const ValueKey('home-menu-button')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('唤起宠物'));
    await tester.pumpAndSettle();
    expect(petShowing, isTrue);
    expect(
      overlayCalls.where((method) => method == 'showPetOverlay').length,
      1,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('home search remains usable with a compact keyboard viewport', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    final now = DateTime.now().millisecondsSinceEpoch;
    nativeConversations = [
      {
        'id': 103,
        'title': '聊天记录',
        'mode': 'chat_only',
        'status': 0,
        'messageCount': 0,
        'createdAt': now,
        'updatedAt': now,
      },
    ];
    await tester.pumpWidget(
      _buildProviderScope(
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(
              textScaler: TextScaler.linear(1.3),
              padding: const EdgeInsets.only(top: 24, bottom: 24),
            ),
            child: child!,
          ),
          home: const HomeDrawer(
            homePage: true,
            embedded: true,
            closeOnNavigate: false,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await selectHomeCategory(tester, 'chat');
    await tester.tap(find.byKey(const ValueKey('home-search-button')));
    await tester.pumpAndSettle();
    tester.view.viewInsets = const FakeViewPadding(bottom: 280);
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'missing');
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('home-menu-button')).hitTestable(),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('home-agent-switcher')).hitTestable(),
      findsNothing,
    );
    expect(find.byType(TextField), findsOneWidget);
    expect(tester.takeException(), isNull);
    tester.view.viewInsets = FakeViewPadding.zero;
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('home-agent-name')).hitTestable(),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('home-agent-switcher')).hitTestable(),
      findsNothing,
    );

    await tester.tap(find.byKey(const ValueKey('home-close-search')));
    tester.view.viewInsets = FakeViewPadding.zero;
    await tester.pumpAndSettle();
    expect(find.byType(TextField), findsNothing);
    expect(find.byKey(const ValueKey('home-menu-button')), findsOneWidget);
    expect(find.text('聊天记录'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'search clear and system back restore the previous category and creation target',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final now = DateTime.now().millisecondsSinceEpoch;
      nativeConversations = [
        {
          'id': 901,
          'title': '聊天记录',
          'mode': 'chat_only',
          'status': 0,
          'messageCount': 0,
          'createdAt': now,
          'updatedAt': now,
        },
        {
          'id': 902,
          'title': 'Agent记录',
          'mode': 'agent',
          'agentId': 'codex-acp',
          'status': 0,
          'messageCount': 0,
          'createdAt': now,
          'updatedAt': now,
        },
      ];
      ConversationThreadTarget? created;
      await tester.pumpWidget(
        _buildProviderScope(
          child: MaterialApp(
            theme: AppTheme.lightTheme,
            locale: const Locale('zh'),
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: HomeDrawer(
              homePage: true,
              embedded: true,
              closeOnNavigate: false,
              onThreadTargetSelected: (target) => created = target,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await selectHomeCategory(tester, 'chat');
      final home = tester.state(find.byType(HomeDrawer));
      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('home-agent-name')).hitTestable(),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey('home-agent-switcher')).hitTestable(),
        findsNothing,
      );
      await tester.enterText(find.byType(TextField), '聊天');
      await tester.pump(const Duration(milliseconds: 250));
      await tester.pumpAndSettle();
      expect(find.text('聊天记录'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('home-search-clear')));
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        isEmpty,
      );
      expect(find.byType(TextField), findsOneWidget);
      expect(
        find.byKey(const ValueKey('home-agent-name')).hitTestable(),
        findsNothing,
      );
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(tester.state(find.byType(HomeDrawer)), same(home));
      expect(find.byType(TextField), findsNothing);
      expect(find.text('纯聊天'), findsOneWidget);
      expect(find.text('聊天记录'), findsOneWidget);
      expect(find.text('Agent记录'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('home-new-conversation')));
      expect(created?.mode, ConversationMode.chatOnly);
      expect(created?.conversationId, isNull);
      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextField>(find.byType(TextField)).controller!.text,
        isEmpty,
      );
      await tester.tap(find.byKey(const ValueKey('home-close-search')));
      await tester.pumpAndSettle();
      expect(find.text('纯聊天'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('home menu opens on press without waiting for plugin refresh', (
    tester,
  ) async {
    final registryResponse = Completer<List<Object?>>();
    var registryReads = 0;
    var delayRegistry = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginChannel, (call) async {
          if (call.method == 'listActions') {
            registryReads++;
            return delayRegistry ? registryResponse.future : <Object?>[];
          }
          return null;
        });
    await tester.pumpWidget(
      _buildProviderScope(
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const HomeDrawer(
            homePage: true,
            embedded: true,
            closeOnNavigate: false,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final initialRegistryReads = registryReads;
    delayRegistry = true;
    final menuButton = find.byKey(const ValueKey('home-menu-button'));
    final press = await tester.startGesture(tester.getCenter(menuButton));
    await tester.pump();
    expect(registryReads, initialRegistryReads + 1);
    expect(
      find.byWidgetPredicate((widget) => widget is PopupMenuItem<VoidCallback>),
      findsNWidgets(6),
    );
    await press.up();
    await tester.pumpAndSettle();

    final coveredHomeBuilds = HomeDrawerState.debugBuildCount;
    registryResponse.complete(<Object?>[]);
    await tester.pumpAndSettle();
    expect(
      HomeDrawerState.debugBuildCount,
      coveredHomeBuilds,
      reason: 'The popup refresh must not rebuild its covered parent page',
    );
    expect(
      find.byWidgetPredicate((widget) => widget is PopupMenuItem<VoidCallback>),
      findsNWidgets(6),
    );
    await tester.tapAt(const Offset(4, 4));
    await tester.pumpAndSettle();
    expect(
      find.byWidgetPredicate((widget) => widget is PopupMenuItem<VoidCallback>),
      findsNothing,
    );

    delayRegistry = false;
    await tester.tap(menuButton);
    await tester.pumpAndSettle();
    expect(
      find.byWidgetPredicate((widget) => widget is PopupMenuItem<VoidCallback>),
      findsNWidgets(6),
    );
    expect(registryReads, initialRegistryReads + 2);
    expect(tester.takeException(), isNull);
  });

  testWidgets('large conversation list builds only viewport rows', (
    tester,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    nativeConversations = List.generate(
      300,
      (index) => <String, Object?>{
        'id': 10000 + index,
        'title': 'Performance conversation $index',
        'mode': ConversationMode.agent.storageValue,
        'status': 0,
        'messageCount': 0,
        'createdAt': now - index,
        'updatedAt': now - index,
      },
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
    expect(find.text('Performance conversation 0'), findsOneWidget);
    expect(
      find.textContaining('Performance conversation ').evaluate().length,
      lessThan(30),
    );
    expect(find.text('Performance conversation 299'), findsNothing);
    await tester.scrollUntilVisible(
      find.text('Performance conversation 299'),
      500,
      scrollable: find
          .descendant(
            of: find.byType(CustomScrollView),
            matching: find.byType(Scrollable),
          )
          .first,
      maxScrolls: 100,
    );
    await tester.pumpAndSettle();
    expect(
      find.text('Performance conversation 299').hitTestable(),
      findsOneWidget,
    );
    expect(
      find.textContaining('Performance conversation ').evaluate().length,
      lessThan(30),
    );
  });

  testWidgets('shows and invokes declarative Agent Web quick actions', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginChannel, (call) async {
          calls.add(call);
          if (call.method == 'listActions') {
            return <Map<String, Object?>>[
              <String, Object?>{
                'id': 'open_opencode_web',
                'pluginId': 'com.omnimind.agent-web',
                'displayName': 'OpenCode Web',
                'description': 'Open OpenCode Web',
                'presentation': <String, Object?>{
                  'placements': <String>['home_drawer_quick_launch'],
                  'agentId': 'opencode-acp',
                  'quickLaunchOrder': 2,
                  'label': <String, String>{
                    'zh': 'OpenCode Web',
                    'en': 'OpenCode Web',
                  },
                  'shortLabel': <String, String>{
                    'zh': 'OpenCode Web',
                    'en': 'OpenCode Web',
                  },
                },
              },
              <String, Object?>{
                'id': 'open_kimi_web',
                'pluginId': 'com.omnimind.agent-web',
                'displayName': 'Kimi Code Web',
                'description': 'Open Kimi Web',
                'presentation': <String, Object?>{
                  'placements': <String>['home_drawer_quick_launch'],
                  'agentId': 'kimi-code-acp',
                  'quickLaunchOrder': 0,
                  'label': <String, String>{
                    'zh': 'Kimi Code Web',
                    'en': 'Kimi Code Web',
                  },
                  'shortLabel': <String, String>{
                    'zh': 'Kimi Web',
                    'en': 'Kimi Web',
                  },
                },
              },
            ];
          }
          if (call.method == 'invokeAction') {
            return <String, Object?>{'code': 'OPENED'};
          }
          return null;
        });

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

    final webMenuButton = find.byKey(
      const ValueKey('home-drawer-web-menu-button'),
    );
    expect(webMenuButton, findsOneWidget);
    await tester.tap(webMenuButton);
    await tester.pumpAndSettle();

    final kimi = find.byKey(const ValueKey('home-drawer-web-kimi-code-acp'));
    expect(kimi, findsOneWidget);

    final openCode = find.byKey(const ValueKey('home-drawer-web-opencode-acp'));
    expect(openCode, findsOneWidget);
    expect(tester.getCenter(kimi).dy, lessThan(tester.getCenter(openCode).dy));
    await tester.tap(openCode);
    await tester.pumpAndSettle();

    final invocation = calls.lastWhere((call) => call.method == 'invokeAction');
    expect(invocation.arguments, <String, Object?>{
      'pluginId': 'com.omnimind.agent-web',
      'actionId': 'open_opencode_web',
      'arguments': <String, dynamic>{},
    });
  });

  testWidgets(
    'running Web quick action shows a status dot and stops from the menu',
    (tester) async {
      var statusCode = 'RUNNING';
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(pluginChannel, (call) async {
            calls.add(call);
            if (call.method == 'listActions') {
              return <Map<String, Object?>>[
                <String, Object?>{
                  'id': 'open_kimi_web',
                  'pluginId': 'com.omnimind.agent-web',
                  'displayName': 'Kimi Code Web',
                  'description': 'Open Kimi Web',
                  'presentation': <String, Object?>{
                    'placements': <String>['home_drawer_quick_launch'],
                    'agentId': 'kimi-code-acp',
                    'quickLaunchOrder': 0,
                    'statusAction': 'get_kimi_web_status',
                    'stopAction': 'stop_kimi_web',
                    'label': <String, String>{
                      'zh': 'Kimi Code Web',
                      'en': 'Kimi Code Web',
                    },
                    'shortLabel': <String, String>{
                      'zh': 'Kimi Web',
                      'en': 'Kimi Web',
                    },
                  },
                },
              ];
            }
            if (call.method == 'invokeAction') {
              final actionId = (call.arguments as Map)['actionId'] as String;
              if (actionId == 'get_kimi_web_status') {
                return <String, Object?>{
                  'success': true,
                  'code': statusCode,
                  'serviceId': 'kimi',
                  'packageId': 'kimi',
                  'running': statusCode != 'NOT_RUNNING',
                };
              }
              if (actionId == 'stop_kimi_web') {
                statusCode = 'NOT_RUNNING';
                return <String, Object?>{
                  'success': true,
                  'code': 'STOPPED',
                  'serviceId': 'kimi',
                  'packageId': 'kimi',
                  'running': false,
                };
              }
            }
            return null;
          });

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

      final webMenuButton = find.byKey(
        const ValueKey('home-drawer-web-menu-button'),
      );
      final menuStatusDot = find.byKey(
        const ValueKey('home-drawer-web-menu-status-dot'),
      );
      expect(webMenuButton, findsOneWidget);
      expect(menuStatusDot, findsOneWidget);

      await tester.tap(webMenuButton);
      await tester.pumpAndSettle();
      final statusDot = find.byKey(
        const ValueKey('home-drawer-web-status-kimi-code-acp'),
      );
      expect(statusDot, findsOneWidget);
      expect(find.text('Stop'), findsOneWidget);

      await tester.tap(find.text('Stop'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));

      final stopInvocation = calls.lastWhere(
        (call) =>
            call.method == 'invokeAction' &&
            (call.arguments as Map)['actionId'] == 'stop_kimi_web',
      );
      expect(stopInvocation.arguments, <String, Object?>{
        'pluginId': 'com.omnimind.agent-web',
        'actionId': 'stop_kimi_web',
        'arguments': <String, dynamic>{},
      });
      expect(menuStatusDot, findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

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

    await tester.longPress(find.text('子运行会话'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('home-drawer-context-pin')), findsNothing);
    expect(
      find.byKey(const ValueKey('home-drawer-context-rename')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('home-drawer-context-copy')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('home-drawer-context-archive')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('home-drawer-context-delete')),
      findsOneWidget,
    );
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
      find.byType(CustomScrollView),
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
            case 'manageConversation':
              expect((call.arguments as Map)['action'], 'rename');
              renamedTitle = (call.arguments as Map?)?['title'] as String?;
              return true;
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
    expect(rawState, isNot(contains(dateKey)));

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
    expect(find.text('普通会话').hitTestable(), findsOneWidget);
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

  group('drawer animation frame budget', () {
    testWidgets('streamed ACP updates do not rebuild the drawer', (
      tester,
    ) async {
      final coordinator = ChatConversationRuntimeCoordinator.instance;
      coordinator.resetForTest();
      coordinator.ensureInitialized();
      addTearDown(coordinator.resetForTest);
      final runtime = coordinator.ensureEphemeralRuntime(
        conversationId: 70,
        mode: kChatRuntimeModeAgent,
      );
      nativeConversations = <Map<String, Object?>>[
        <String, Object?>{
          'id': 70,
          'title': '流式中',
          'mode': ConversationMode.agent.storageValue,
          'status': 0,
          'messageCount': 0,
          'createdAt': 1,
          'updatedAt': 2,
        },
      ];
      coordinator.beginAcpTurn(
        taskId: 't-70',
        conversationId: 70,
        mode: kChatRuntimeModeAgent,
      );
      coordinator.bindAcpSession(
        taskId: 't-70',
        conversationId: 70,
        mode: kChatRuntimeModeAgent,
        sessionId: 'drawer-session',
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
        find.byKey(const ValueKey('home-drawer-running-agent:70')),
        findsOneWidget,
      );

      // Thirty streamed updates of the same running turn: the drawer only
      // shows which conversations run, so none of them rebuilds it.
      HomeDrawerState.debugBuildCount = 0;
      for (var token = 0; token < 30; token++) {
        final result = coordinator.applyAgentEvent(
          conversationId: 70,
          mode: kChatRuntimeModeAgent,
          event: <String, dynamic>{
            'conversationId': 70,
            'sessionId': 'drawer-session',
            'turnId': 't-70',
            'allowImplicitTurnAdmission': true,
            'message': <String, dynamic>{
              'method': 'session/update',
              'params': <String, dynamic>{
                'sessionId': 'drawer-session',
                'update': <String, dynamic>{
                  'sessionUpdate': 'agent_message_chunk',
                  'messageId': 't-70-text',
                  'content': <String, dynamic>{
                    'type': 'text',
                    'text': 'token $token ',
                  },
                },
              },
            },
          },
        );
        expect(result.handled, isTrue);
        await tester.pump();
      }
      expect(HomeDrawerState.debugBuildCount, 0);
      expect(
        runtime.messages.single.text,
        List.generate(30, (token) => 'token $token ').join(),
      );

      // The turn ends: the running dot goes away with one rebuild.
      coordinator.applyAcpPromptResponse(
        taskId: 't-70',
        conversationId: 70,
        mode: kChatRuntimeModeAgent,
        sessionId: 'drawer-session',
        stopReason: 'end_turn',
      );
      await tester.pump();
      expect(HomeDrawerState.debugBuildCount, greaterThan(0));
      expect(
        find.byKey(const ValueKey('home-drawer-running-agent:70')),
        findsNothing,
      );
    });

    testWidgets(
      'a Scaffold drawer opens on its snapshot and refreshes after the slide',
      (tester) async {
        final loads = <String>[];
        nativeConversations = <Map<String, Object?>>[
          <String, Object?>{
            'id': 80,
            'title': '快照',
            'mode': ConversationMode.agent.storageValue,
            'status': 0,
            'messageCount': 0,
            'createdAt': 1,
            'updatedAt': 2,
          },
        ];
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(assistCoreChannel, (call) async {
              loads.add(call.method);
              if (call.method == 'getConversations') return nativeConversations;
              return null;
            });
        final scaffoldKey = GlobalKey<ScaffoldState>();
        final drawerKey = GlobalKey<HomeDrawerState>();
        Widget app() => MaterialApp(
          home: DefaultAssetBundle(
            bundle: _SvgTestAssetBundle(),
            child: _buildProviderScope(
              child: Scaffold(
                key: scaffoldKey,
                drawer: HomeDrawer(key: drawerKey, deferInitialLoad: true),
                body: const SizedBox.expand(),
              ),
            ),
          ),
        );
        await tester.pumpWidget(app());
        // First open builds the snapshot cache.
        scaffoldKey.currentState!.openDrawer();
        await tester.pumpAndSettle();
        await tester.pump(HomeDrawerState.drawerSettleDelay);
        await tester.pumpAndSettle();
        scaffoldKey.currentState!.closeDrawer();
        await tester.pumpAndSettle();

        loads.clear();
        scaffoldKey.currentState!.openDrawer();
        // Every frame of the open slide: no conversation reload yet.
        for (var frame = 0; frame < 15; frame++) {
          await tester.pump(const Duration(milliseconds: 16));
          expect(
            loads.where((m) => m == 'getConversations'),
            isEmpty,
            reason: 'frame $frame',
          );
        }
        expect(find.text('快照'), findsOneWidget);
        await tester.pump(HomeDrawerState.drawerSettleDelay);
        await tester.pumpAndSettle();
        expect(loads.where((m) => m == 'getConversations'), hasLength(1));
      },
    );
  });
}

Widget _buildProviderScope({
  required Widget child,
  HabitualHand habitualHand = HabitualHand.right,
}) {
  return ProviderScope(
    overrides: [
      habitualHandProvider.overrideWith(
        (ref) => HabitualHandController(initial: habitualHand),
      ),
    ],
    child: child,
  );
}
