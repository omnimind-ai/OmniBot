import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const agentRuntimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<Map<String, dynamic>> nativeConversations;
  late List<MethodCall> agentRuntimeCalls;
  late bool agentRuntimeArchiveShouldThrow;
  late Map<String, dynamic> lastGetConversationsArguments;

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    nativeConversations = <Map<String, dynamic>>[];
    agentRuntimeCalls = <MethodCall>[];
    agentRuntimeArchiveShouldThrow = false;
    lastGetConversationsArguments = <String, dynamic>{};
    messenger.setMockMethodCallHandler(channel, (call) async {
      final args = Map<String, dynamic>.from(
        (call.arguments as Map?) ?? const {},
      );
      switch (call.method) {
        case 'getConversationsByPage':
          return nativeConversations
              .where(
                (row) => args['mode'] == null || row['mode'] == args['mode'],
              )
              .skip(args['offset'] as int)
              .take(args['limit'] as int)
              .toList();
        case 'getConversations':
          lastGetConversationsArguments = args;
          final archiveBefore = (args['archiveBefore'] as num?)?.toInt();
          if (archiveBefore != null) {
            nativeConversations = nativeConversations.map((conversation) {
              final updatedAt =
                  (conversation['updatedAt'] as num?)?.toInt() ?? 0;
              if (conversation['isArchived'] != true &&
                  updatedAt < archiveBefore) {
                return <String, dynamic>{...conversation, 'isArchived': true};
              }
              return conversation;
            }).toList();
          }
          if (args['archivedOnly'] == true) {
            return nativeConversations
                .where((conversation) => conversation['isArchived'] == true)
                .toList();
          }
          if (args['includeArchived'] != true) {
            return nativeConversations
                .where((conversation) => conversation['isArchived'] != true)
                .toList();
          }
          return nativeConversations;
        case 'createConversation':
          final nextId =
              nativeConversations.fold<int>(
                0,
                (maxId, item) =>
                    item['id'] as int > maxId ? item['id'] as int : maxId,
              ) +
              1;
          nativeConversations.add({
            'id': nextId,
            'title': args['title'] ?? '新对话',
            'mode': args['mode'] ?? ConversationMode.normal.storageValue,
            'summary': args['summary'],
            'parentConversationId': args['parentConversationId'],
            'parentConversationMode': args['parentConversationMode'],
            'scheduledTaskId': args['scheduledTaskId'],
            'agentId': args['agentId'],
            'status': 0,
            'lastMessage': null,
            'messageCount': 0,
            'createdAt': 1,
            'updatedAt': 1,
          });
          return nextId;
        case 'updateConversation':
          final conversation = Map<String, dynamic>.from(
            (args['conversation'] as Map).cast<String, dynamic>(),
          );
          final conversationId = (conversation['id'] as num?)?.toInt();
          final index = nativeConversations.indexWhere(
            (item) => item['id'] == conversationId,
          );
          if (index >= 0) {
            nativeConversations[index] = <String, dynamic>{
              ...nativeConversations[index],
              ...conversation,
            };
          }
          return 'SUCCESS';
        case 'updateConversationTitle':
        case 'completeConversation':
        case 'setCurrentConversationId':
          return 'SUCCESS';
        case 'updateConversationPromptTokenThreshold':
          final conversationId = (args['conversationId'] as num?)?.toInt();
          final threshold = (args['promptTokenThreshold'] as num?)?.toInt();
          final index = nativeConversations.indexWhere(
            (item) => item['id'] == conversationId,
          );
          if (index >= 0 && threshold != null) {
            nativeConversations[index] = <String, dynamic>{
              ...nativeConversations[index],
              'promptTokenThreshold': threshold,
            };
          }
          return 'SUCCESS';
        case 'deleteConversation':
          final conversationId = (args['conversationId'] as num?)?.toInt();
          nativeConversations.removeWhere(
            (item) => item['id'] == conversationId,
          );
          return 'SUCCESS';
        default:
          return null;
      }
    });
    messenger.setMockMethodCallHandler(agentRuntimeChannel, (call) async {
      agentRuntimeCalls.add(call);
      if (agentRuntimeArchiveShouldThrow &&
          (call.method == 'thread/archive' ||
              call.method == 'thread/unarchive')) {
        throw PlatformException(
          code: 'CODEX_THREAD_NOT_FOUND',
          message: 'thread not found',
        );
      }
      return <String, dynamic>{'ok': true};
    });
  });

  tearDown(() async {
    messenger.setMockMethodCallHandler(channel, null);
    messenger.setMockMethodCallHandler(agentRuntimeChannel, null);
  });

  test('loads conversations from native source', () async {
    nativeConversations = <Map<String, dynamic>>[
      {
        'id': 42,
        'title': 'openclaw hello',
        'mode': ConversationMode.openclaw.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': 'openclaw hello',
        'messageCount': 2,
        'createdAt': 1,
        'updatedAt': 2,
      },
    ];

    final conversations = await ConversationService.getAllConversations();

    expect(conversations, hasLength(1));
    expect(conversations.single.id, 42);
    expect(conversations.single.mode, ConversationMode.openclaw);
    expect(conversations.single.title, 'openclaw hello');
  });

  test('an explicitly enabled sidebar policy archives every conversation mode older than seven days', () async {
    await StorageService.setBool(
      StorageService.kRecentConversationsOnlyEnabledKey,
      true,
    );
    final now = DateTime.utc(2026, 8, 13, 12);
    final cutoff = ConversationService.recentConversationCutoff(now: now);
    nativeConversations = <Map<String, dynamic>>[
      for (final entry in <(int, ConversationMode)>[
        (1, ConversationMode.normal),
        (2, ConversationMode.agent),
        (3, ConversationMode.chatOnly),
      ])
        {
          'id': entry.$1,
          'title': 'old ${entry.$2.storageValue}',
          'mode': entry.$2.storageValue,
          'isArchived': false,
          'status': 0,
          'messageCount': 0,
          'createdAt': cutoff - 1,
          'updatedAt': cutoff - 1,
        },
      {
        'id': 4,
        'title': 'recent conversation',
        'mode': ConversationMode.normal.storageValue,
        'isArchived': false,
        'status': 0,
        'messageCount': 0,
        'createdAt': cutoff,
        'updatedAt': cutoff,
      },
    ];

    final conversations = await ConversationService.getSidebarConversations(
      now: now,
    );

    expect(conversations.map((conversation) => conversation.id), <int>[4]);
    expect(
      nativeConversations
          .take(3)
          .every((conversation) => conversation['isArchived'] == true),
      isTrue,
    );
    expect(lastGetConversationsArguments['archiveBefore'], cutoff);
    expect(lastGetConversationsArguments['includeArchived'], isFalse);
    expect(lastGetConversationsArguments['archivedOnly'], isFalse);
  });

  test('sidebar snapshot applies the enabled seven-day window', () async {
    await StorageService.setBool(
      StorageService.kRecentConversationsOnlyEnabledKey,
      true,
    );
    final now = DateTime.utc(2026, 8, 13, 12);
    final cutoff = ConversationService.recentConversationCutoff(now: now);
    final snapshot = <ConversationModel>[
      ConversationModel(
        id: 1,
        title: 'old',
        status: 0,
        messageCount: 0,
        createdAt: cutoff - 1,
        updatedAt: cutoff - 1,
      ),
      ConversationModel(
        id: 2,
        title: 'recent',
        status: 0,
        messageCount: 0,
        createdAt: cutoff,
        updatedAt: cutoff,
      ),
      ConversationModel(
        id: 3,
        title: 'already archived',
        isArchived: true,
        status: 0,
        messageCount: 0,
        createdAt: cutoff + 1,
        updatedAt: cutoff + 1,
      ),
    ];

    expect(
      ConversationService.filterSidebarSnapshot(
        snapshot,
        now: now,
      ).map((conversation) => conversation.id),
      <int>[2],
    );
  });

  test(
    'default sidebar policy keeps older active conversations visible',
    () async {
      nativeConversations = <Map<String, dynamic>>[
        {
          'id': 5,
          'title': 'old but active',
          'mode': ConversationMode.chatOnly.storageValue,
          'isArchived': false,
          'status': 0,
          'messageCount': 0,
          'createdAt': 1,
          'updatedAt': 1,
        },
      ];

      final conversations = await ConversationService.getSidebarConversations();

      expect(conversations.map((conversation) => conversation.id), <int>[5]);
      expect(lastGetConversationsArguments, isNot(contains('archiveBefore')));
      expect(lastGetConversationsArguments['includeArchived'], isTrue);
      expect(nativeConversations.single['isArchived'], isFalse);
    },
  );

  test(
    'keeps the bound ACP agent in conversation and thread targets',
    () async {
      nativeConversations = <Map<String, dynamic>>[
        {
          'id': 50,
          'title': 'Claude conversation',
          'mode': ConversationMode.agent.storageValue,
          'agentCwd': '/workspace',
          'agentId': 'claude-code-acp',
          'summary': null,
          'status': 0,
          'lastMessage': 'hello',
          'messageCount': 1,
          'createdAt': 1,
          'updatedAt': 2,
        },
      ];

      final conversation =
          (await ConversationService.getAllConversations()).single;
      final target = await ConversationService.getLatestConversationTarget(
        mode: ConversationMode.agent,
      );

      expect(conversation.agentCwd, '/workspace');
      expect(conversation.agentId, 'claude-code-acp');
      expect(target?.agentId, 'claude-code-acp');
    },
  );

  test('loads chat_only conversations without collapsing mode', () async {
    nativeConversations = <Map<String, dynamic>>[
      {
        'id': 8,
        'title': '纯聊线程',
        'mode': ConversationMode.chatOnly.storageValue,
        'summary': null,
        'status': 0,
        'lastMessage': '你好',
        'messageCount': 1,
        'createdAt': 1,
        'updatedAt': 3,
      },
    ];

    final conversations = await ConversationService.getAllConversations();

    expect(conversations, hasLength(1));
    expect(conversations.single.mode, ConversationMode.chatOnly);
    expect(
      await ConversationService.getLatestConversation(
        mode: ConversationMode.chatOnly,
      ),
      isNotNull,
    );
  });

  test('parses context compaction metadata from native source', () async {
    nativeConversations = <Map<String, dynamic>>[
      {
        'id': 7,
        'title': 'normal hello',
        'mode': ConversationMode.normal.storageValue,
        'summary': '摘要',
        'contextSummary': '【用户目标与约束】\n- 测试',
        'contextSummaryCutoffEntryDbId': 33,
        'contextSummaryUpdatedAt': 101,
        'status': 0,
        'lastMessage': 'hello',
        'messageCount': 9,
        'latestPromptTokens': 64000,
        'promptTokenThreshold': 128000,
        'latestPromptTokensUpdatedAt': 202,
        'createdAt': 1,
        'updatedAt': 2,
      },
    ];

    final conversations = await ConversationService.getAllConversations();

    expect(conversations, hasLength(1));
    expect(conversations.single.contextSummary, contains('用户目标'));
    expect(conversations.single.contextSummaryCutoffEntryDbId, 33);
    expect(conversations.single.latestPromptTokens, 64000);
    expect(conversations.single.promptTokenThreshold, 128000);
    expect(conversations.single.contextUsageRatio, closeTo(0.5, 0.0001));
  });

  test(
    'updates conversation prompt token threshold via native channel',
    () async {
      nativeConversations = <Map<String, dynamic>>[
        {
          'id': 11,
          'title': 'normal hello',
          'mode': ConversationMode.normal.storageValue,
          'summary': null,
          'status': 0,
          'lastMessage': null,
          'messageCount': 0,
          'promptTokenThreshold': 128000,
          'createdAt': 1,
          'updatedAt': 2,
        },
      ];

      final updated =
          await ConversationService.updateConversationPromptTokenThreshold(
            conversationId: 11,
            promptTokenThreshold: 400000,
          );

      expect(updated, isTrue);
      expect(nativeConversations.single['promptTokenThreshold'], 400000);
    },
  );

  test('creates conversations with chat_only mode', () async {
    final conversationId = await ConversationService.createConversation(
      title: '纯聊新线程',
      mode: ConversationMode.chatOnly,
    );

    expect(conversationId, isNotNull);
    final created = nativeConversations.singleWhere(
      (item) => item['id'] == conversationId,
    );
    expect(created['mode'], ConversationMode.chatOnly.storageValue);
  });

  test(
    'binds an Agent conversation to its Harness when it is created',
    () async {
      final conversationId = await ConversationService.createConversation(
        title: 'DSH 对话',
        mode: ConversationMode.agent,
        agentId: 'deepseek-harness-acp',
      );

      final created = nativeConversations.singleWhere(
        (item) => item['id'] == conversationId,
      );
      expect(created['mode'], ConversationMode.agent.storageValue);
      expect(created['agentId'], 'deepseek-harness-acp');
    },
  );

  test(
    'creates scheduled subagent run conversations with parent metadata',
    () async {
      final conversationId = await ConversationService.createConversation(
        title: '新闻整理',
        mode: ConversationMode.subagent,
        parentConversationId: 7,
        parentConversationMode: ConversationMode.normal,
        scheduledTaskId: 'schedule-news',
      );

      expect(conversationId, isNotNull);
      final created = nativeConversations.singleWhere(
        (item) => item['id'] == conversationId,
      );
      expect(created['mode'], ConversationMode.subagent.storageValue);
      expect(created['parentConversationId'], 7);
      expect(
        created['parentConversationMode'],
        ConversationMode.normal.storageValue,
      );
      expect(created['scheduledTaskId'], 'schedule-news');
    },
  );

  test('paging requests only the requested page from Kotlin', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <Map<String, dynamic>>[];
    });
    await ConversationService.getConversationsByPage(
      offset: 1000,
      limit: 20,
      archivedOnly: true,
    );
    expect(calls.single.method, 'getConversationsByPage');
    expect(calls.single.arguments, {
      'offset': 1000,
      'limit': 20,
      'includeArchived': false,
      'archivedOnly': true,
    });
  });

  test('management delegates to the native ACP and history owner', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return true;
    });
    expect(
      await ConversationService.deleteConversation(
        9,
        mode: ConversationMode.agent,
      ),
      isTrue,
    );
    expect(calls.single.method, 'manageConversation');
    expect(calls.single.arguments, {
      'action': 'delete',
      'conversationId': 9,
      'mode': 'agent',
    });
  });

  test('metadata preservation is requested atomically without reading all conversations', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return 'SUCCESS';
    });
    await ConversationService.updateConversation(
      ConversationModel(
        id: 9,
        title: 'Old',
        status: 0,
        messageCount: 0,
        createdAt: 1,
        updatedAt: 2,
      ),
      preserveLatestMetadata: true,
    );
    expect(calls.single.method, 'updateConversation');
    expect(calls.single.arguments['preserveLatestMetadata'], isTrue);
  });
}
