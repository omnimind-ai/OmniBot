import 'package:dotted_border/dotted_border.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/deep_thinking_card.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/widgets/agent_brand_icon.dart';
import 'package:ui/widgets/agent_avatar.dart';
import 'package:ui/widgets/streaming_text.dart';

void main() {
  testWidgets('empty chat state offsets with bottom overlay inset', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildLocalizedApp(
        child: ChatMessageList(
          messages: const [],
          scrollController: ScrollController(),
          bottomOverlayInset: 128,
          onBeforeTaskExecute: () async {},
        ),
      ),
    );

    await tester.pump();

    final emptyPadding = tester.widget<Padding>(
      find.byKey(const ValueKey('chat-empty-bottom-overlay-padding')),
    );

    expect(emptyPadding.padding, const EdgeInsets.only(bottom: 128));
    expect(find.text('你好👋，我是小万'), findsOneWidget);
  });

  testWidgets(
    'parent handoff keeps list away from latest on follow-up frames',
    (tester) async {
      final controller = ScrollController();
      final messages = _buildMessagesWithThinkingCard();

      await tester.pumpWidget(
        _buildChatMessageListHarness(
          controller: controller,
          messages: messages,
        ),
      );
      await tester.pumpAndSettle();

      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 1),
      );

      final deepThinkingCard = find.descendant(
        of: find.byType(ChatMessageList),
        matching: find.byType(DeepThinkingCard),
      );
      expect(deepThinkingCard, findsOneWidget);

      await tester.tap(
        find.descendant(of: deepThinkingCard, matching: find.byType(InkWell)),
      );
      await tester.pumpAndSettle();

      final dragStart =
          tester.getTopLeft(deepThinkingCard) + const Offset(120, 96);
      await tester.dragFrom(dragStart, const Offset(0, 60));
      await tester.pump();

      final movedOffset = controller.offset;
      expect(movedOffset, lessThan(controller.position.maxScrollExtent - 48));

      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));
      await tester.pumpAndSettle();

      expect(controller.offset, closeTo(movedOffset, 1));
    },
  );

  testWidgets('list resumes auto-follow after layout returns it to latest', (
    tester,
  ) async {
    final controller = ScrollController();
    var messages = _buildMessagesWithThinkingCard();
    late StateSetter setState;

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: StatefulBuilder(
          builder: (context, stateSetter) {
            setState = stateSetter;
            return SizedBox(
              width: 400,
              height: 520,
              child: ChatMessageList(
                messages: messages,
                scrollController: controller,
                onBeforeTaskExecute: () async {},
              ),
            );
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    final deepThinkingCard = find.descendant(
      of: find.byType(ChatMessageList),
      matching: find.byType(DeepThinkingCard),
    );
    await tester.tap(
      find.descendant(of: deepThinkingCard, matching: find.byType(InkWell)),
    );
    await tester.pumpAndSettle();

    final dragStart =
        tester.getTopLeft(deepThinkingCard) + const Offset(120, 96);
    await tester.dragFrom(dragStart, const Offset(0, 60));
    await tester.pumpAndSettle();

    expect(controller.offset, lessThan(controller.position.maxScrollExtent));

    setState(() {
      messages = <ChatMessageModel>[
        messages.first,
        ...messages.skip(1).take(1),
      ];
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    await tester.pumpAndSettle();

    expect(controller.offset, closeTo(controller.position.maxScrollExtent, 1));

    setState(() {
      messages = <ChatMessageModel>[
        ChatMessageModel.assistantMessage('新的最新消息', id: 'new-latest'),
        ...messages,
      ];
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    await tester.pumpAndSettle();

    expect(controller.offset, closeTo(controller.position.maxScrollExtent, 1));
  });

  testWidgets(
    'small manual drag away from latest disables follow-up auto stick',
    (tester) async {
      final controller = ScrollController();
      var messages = _buildSimpleAssistantMessages(20, prefix: '初始消息');
      late StateSetter setState;

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: StatefulBuilder(
            builder: (context, stateSetter) {
              setState = stateSetter;
              return SizedBox(
                width: 400,
                height: 520,
                child: ChatMessageList(
                  messages: messages,
                  scrollController: controller,
                  onBeforeTaskExecute: () async {},
                ),
              );
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 1),
      );

      await tester.drag(find.byType(ListView), const Offset(0, 36));
      await tester.pumpAndSettle();

      final movedOffset = controller.offset;
      expect(movedOffset, lessThan(controller.position.maxScrollExtent));
      expect(
        movedOffset,
        greaterThan(controller.position.maxScrollExtent - 48),
      );

      setState(() {
        messages = <ChatMessageModel>[
          ChatMessageModel.assistantMessage('新的最新消息', id: 'new-latest'),
          ...messages,
        ];
      });
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));
      await tester.pumpAndSettle();

      expect(
        controller.offset,
        closeTo(movedOffset, 2),
        reason: 'A small manual drag away from latest should not snap back.',
      );
      expect(controller.offset, lessThan(controller.position.maxScrollExtent));
    },
  );

  testWidgets('latest user message exposes dashed tap-to-edit affordance', (
    tester,
  ) async {
    final controller = ScrollController();
    ChatMessageModel? tappedMessage;
    final messages = <ChatMessageModel>[
      ChatMessageModel.userMessage('最新用户消息', id: 'latest-user'),
      ChatMessageModel.assistantMessage('收到', id: 'assistant-1'),
      ChatMessageModel.userMessage('更早的用户消息', id: 'older-user'),
    ];

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onLatestUserMessageEditTap: (message) {
              tappedMessage = message;
            },
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final latestBubble = find.byKey(
      const ValueKey('user-message-bubble-latest-user'),
    );

    expect(latestBubble, findsOneWidget);
    expect(
      find.descendant(of: latestBubble, matching: find.byType(IconButton)),
      findsNothing,
    );
    expect(find.byIcon(Icons.edit_outlined), findsNothing);
    final editTrigger = find.byKey(
      const ValueKey('user-message-edit-trigger-latest-user'),
    );
    expect(editTrigger, findsOneWidget);
    expect(
      find.descendant(of: editTrigger, matching: find.byType(DottedBorder)),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('user-message-edit-trigger-older-user')),
      findsNothing,
    );
    expect(find.byType(TextField), findsNothing);

    await tester.tap(editTrigger);
    await tester.pump();

    expect(tappedMessage?.id, 'latest-user');
    expect(find.byType(TextField), findsNothing);
  });

  testWidgets('ACP user message forwards long press for copy actions', (
    tester,
  ) async {
    final controller = ScrollController();
    ChatMessageModel? pressedMessage;
    final messages = <ChatMessageModel>[
      ChatMessageModel.userMessage('Agent 用户消息', id: 'agent-user'),
    ];

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            useAcpPresentation: true,
            onUserMessageLongPressStart: (message, _) {
              pressedMessage = message;
            },
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.longPress(
      find.byKey(const ValueKey('user-message-bubble-agent-user')),
    );
    await tester.pump();

    expect(pressedMessage?.id, 'agent-user');
  });

  testWidgets(
    'shared message scroll controller does not crash during long-message rebuilds',
    (tester) async {
      final controller = ScrollController();
      final messages = <ChatMessageModel>[
        ChatMessageModel.assistantMessage(
          List.generate(
            120,
            (index) => '超长消息第 ${index + 1} 行，用于复现多滚动位置场景。',
          ).join('\n'),
          id: 'long-message',
        ),
      ];

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: Column(
            children: [
              Expanded(
                child: ChatMessageList(
                  messages: messages,
                  scrollController: controller,
                  onBeforeTaskExecute: () async {},
                ),
              ),
              Expanded(
                child: ChatMessageList(
                  messages: messages,
                  scrollController: controller,
                  onBeforeTaskExecute: () async {},
                ),
              ),
            ],
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));

      expect(controller.positions.length, 2);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'shared message scroll controller stays safe with deep thinking cards',
    (tester) async {
      final controller = ScrollController();
      final messages = _buildMessagesWithThinkingCard();

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: SizedBox(
            width: 960,
            child: Column(
              children: [
                Expanded(
                  child: ChatMessageList(
                    messages: messages,
                    scrollController: controller,
                    onBeforeTaskExecute: () async {},
                  ),
                ),
                Expanded(
                  child: ChatMessageList(
                    messages: messages,
                    scrollController: controller,
                    onBeforeTaskExecute: () async {},
                  ),
                ),
              ],
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));

      expect(controller.positions.length, 2);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'streaming deep thinking updates keep the message list pinned to latest',
    (tester) async {
      final controller = ScrollController();
      final messages = ObservableChatMessageList()
        ..replaceAllMessages(_buildStreamingThinkingMessages(thinkingLines: 1));

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: SizedBox(
            width: 400,
            height: 520,
            child: ChatMessageList(
              messages: messages,
              scrollController: controller,
              onBeforeTaskExecute: () async {},
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));

      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 1),
      );

      messages[0] = ChatMessageModel.cardMessage(<String, dynamic>{
        'type': 'deep_thinking',
        'thinkingContent': List.generate(
          40,
          (index) => '第 ${index + 1} 行流式思考内容，验证列表持续跟随最新位置。',
        ).join('\n'),
        'stage': 1,
        'isLoading': true,
        'isCollapsible': true,
        'taskID': 'streaming-thinking-card',
      }, id: 'streaming-thinking-card');

      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));
      await tester.pump(const Duration(milliseconds: 16));

      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 1),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'observable agent text updates rebuild the visible streaming bubble',
    (tester) async {
      final controller = ScrollController();
      final messages = ObservableChatMessageList()
        ..replaceAllMessages([
          ChatMessageModel(
            id: 'agent-task-text',
            type: 1,
            user: 2,
            content: {
              'text': '第一段回复',
              'id': 'agent-task-text',
              'renderMarkdown': true,
            },
            streamMeta: const {
              'parentTaskId': 'agent-task',
              'kind': 'text_snapshot',
              'seq': 1,
              'isFinal': false,
            },
          ),
        ]);

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: SizedBox(
            width: 400,
            height: 520,
            child: ChatMessageList(
              messages: messages,
              scrollController: controller,
              activeAgentTaskIds: const {'agent-task'},
              onBeforeTaskExecute: () async {},
            ),
          ),
        ),
      );
      await tester.pump();

      expect(
        tester.widget<StreamingText>(find.byType(StreamingText)).fullText,
        '第一段回复',
      );

      final existing = messages[0];
      final content = Map<String, dynamic>.from(existing.content ?? const {});
      content['text'] = '第一段回复\n第二段已经流式到达';
      messages[0] = existing.copyWith(
        content: content,
        streamMeta: const {
          'parentTaskId': 'agent-task',
          'kind': 'text_snapshot',
          'seq': 2,
          'isFinal': false,
        },
      );

      await tester.pump();

      expect(
        tester.widget<StreamingText>(find.byType(StreamingText)).fullText,
        '第一段回复\n第二段已经流式到达',
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('streaming body growth stays pinned during every layout frame', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = ObservableChatMessageList()
      ..replaceAllMessages(<ChatMessageModel>[
        ChatMessageModel(
          id: 'body-task-text',
          type: 1,
          user: 2,
          content: const <String, dynamic>{
            'text': '正文开始',
            'id': 'body-task-text',
            'renderMarkdown': true,
          },
          streamMeta: const <String, dynamic>{
            'parentTaskId': 'body-task',
            'kind': 'text_snapshot',
            'seq': 1,
            'isFinal': false,
          },
        ),
        ...List.generate(18, (index) {
          return ChatMessageModel.assistantMessage(
            List.generate(
              4,
              (line) => '较早正文 ${index + 1} - 第 ${line + 1} 行',
            ).join('\n'),
            id: 'body-older-$index',
          );
        }),
      ]);

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            activeAgentTaskIds: const <String>{'body-task'},
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));

    expect(
      controller.offset,
      closeTo(controller.position.maxScrollExtent, 0.5),
    );

    final existing = messages[0];
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    content['text'] = List.generate(
      48,
      (index) => '正文第 ${index + 1} 行持续流式增长，用于验证布局期间保持最新位置。',
    ).join('\n');
    messages[0] = existing.copyWith(
      content: content,
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'body-task',
        'kind': 'text_snapshot',
        'seq': 2,
        'isFinal': false,
      },
    );

    for (var frame = 0; frame < 30; frame += 1) {
      await tester.pump(const Duration(milliseconds: 16));
      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 0.5),
        reason: 'frame $frame should remain pinned while body layout grows',
      );
    }

    messages[0] = messages[0].copyWith(
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'body-task',
        'kind': 'text_snapshot',
        'seq': 3,
        'isFinal': true,
      },
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));

    expect(
      controller.offset,
      closeTo(controller.position.maxScrollExtent, 0.5),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'expanding an older thinking card does not snap the list back to latest',
    (tester) async {
      final controller = ScrollController();
      final messages = _buildToggleRegressionThinkingMessages();

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: SizedBox(
            width: 400,
            height: 520,
            child: ChatMessageList(
              messages: messages,
              scrollController: controller,
              onBeforeTaskExecute: () async {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 1),
      );

      final inkWells = find.byType(InkWell);
      expect(inkWells, findsNWidgets(2));

      final offsetBefore = controller.offset;
      final maxBefore = controller.position.maxScrollExtent;

      await tester.tap(inkWells.first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 220));
      await tester.pumpAndSettle();

      expect(controller.position.maxScrollExtent, greaterThan(maxBefore + 40));
      expect(controller.offset, closeTo(offsetBefore, 8));
      expect(
        controller.offset,
        lessThan(controller.position.maxScrollExtent - 40),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('chat history no longer uses pull-to-refresh wrapper', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildSimpleAssistantMessages(24, prefix: '刷新机制移除');

    await tester.pumpWidget(
      _buildChatMessageListHarness(controller: controller, messages: messages),
    );
    await tester.pumpAndSettle();

    expect(find.byType(RefreshIndicator), findsNothing);
  });

  testWidgets('completed Xiaowan run keeps Xiaowan presentation', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildCompletedAgentRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    // Collapsed state is unified across all agent modes: the header reads
    // "已处理" rather than the per-tool count summary, regardless of how
    // many tool calls happened inside. The count summary only resurfaces
    // when the user expands the run.
    expect(find.text('已处理'), findsOneWidget);
    expect(find.text('已运行 1 条命令'), findsNothing);
    expect(find.text('最终回答'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('agent-run-avatar-task-1')),
      findsOneWidget,
    );
    expect(find.text('运行 git status'), findsNothing);
    expect(find.text('详细思考过程'), findsNothing);

    await tester.tap(find.byKey(const ValueKey('agent-run-summary-task-1')));
    await tester.pump();
    // The process fold's key carries the first process message id, so two
    // folds in one turn (prose between two tool batches) stay distinct.
    expect(
      find.byKey(const ValueKey('agent-run-process-task-1-task-1-thinking')),
      findsOneWidget,
    );
    expect(find.byType(DeepThinkingCard), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 120));
    await tester.pumpAndSettle();

    expect(find.text('运行 git status'), findsOneWidget);
    expect(find.text('详细思考过程'), findsNothing);
    expect(find.byType(AgentAvatarCircle), findsOneWidget);
    expect(find.byType(AgentAvatarButton), findsNothing);
  });

  testWidgets('ACP Agent run shows its own brand avatar and processed label', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildCompletedAcpAgentRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            useAcpPresentation: true,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    // Collapsed: header reads "已处理 …" (possibly suffixed with an
    // elapsed-time string), NEVER "已探索 N 次搜索 …".
    expect(find.textContaining('已处理'), findsOneWidget);
    expect(find.text('已探索 2 次搜索'), findsNothing);
    final acpAvatar = find.byKey(const ValueKey('agent-run-acp-avatar-task-1'));
    expect(acpAvatar, findsOneWidget);
    final brandIcon = tester.widget<AgentBrandIcon>(
      find.descendant(of: acpAvatar, matching: find.byType(AgentBrandIcon)),
    );
    expect(brandIcon.agentId, 'claude-code-acp');
    expect(find.byKey(const ValueKey('agent-run-avatar-task-1')), findsNothing);

    // Expanded: header still says "已处理 …" — and any inner tool-group
    // capsule (when consecutive tool cards group together) ALSO says
    // "已处理" instead of the previous count summary. So we expect AT
    // LEAST one widget with "已处理" (could be the outer header alone,
    // or outer + inner capsule depending on the messages).
    await tester.tap(find.byKey(const ValueKey('agent-run-summary-task-1')));
    await tester.pumpAndSettle();
    expect(find.textContaining('已处理'), findsWidgets);
    expect(find.textContaining('已探索'), findsNothing);
    final acpToolGroupToggle = find.byKey(
      const ValueKey(
        'agent-tool-call-group-toggle-task-1-task-1-tool-search-2-task-1-tool-search-1',
      ),
    );
    expect(acpToolGroupToggle, findsOneWidget);

    await tester.tap(acpToolGroupToggle);
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('inline-file-diff-title-toggle')),
      findsNWidgets(2),
    );
    expect(
      find.byKey(
        const ValueKey('agent-tool-summary-capsule-task-1-tool-search-1'),
      ),
      findsNothing,
    );
  });

  testWidgets(
    'ACP run shows its avatar and processing timer before the first response',
    (tester) async {
      final controller = ScrollController();
      final startedAt = DateTime.now().subtract(const Duration(seconds: 3));
      final userMessageId = '${startedAt.millisecondsSinceEpoch}-user';
      final taskId = '${startedAt.millisecondsSinceEpoch}-ai';

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: SizedBox(
            width: 400,
            height: 520,
            child: ChatMessageList(
              messages: <ChatMessageModel>[
                ChatMessageModel.userMessage(
                  '请检查项目',
                  id: userMessageId,
                ).copyWith(createAt: startedAt),
              ],
              activeAgentTaskIds: <String>{taskId},
              useAcpPresentation: true,
              activeAcpAgentId: 'codex-acp',
              scrollController: controller,
              onBeforeTaskExecute: () async {},
            ),
          ),
        ),
      );
      await tester.pump();

      // One header per turn, live or restored — so the in-flight state uses
      // the same key as the folded state.
      final processingHeader = find.byKey(
        ValueKey('agent-run-summary-$taskId'),
      );
      expect(processingHeader, findsOneWidget);
      expect(find.textContaining('正在处理 3s'), findsOneWidget);
      expect(
        tester.getTopLeft(find.text('请检查项目')).dy,
        lessThan(tester.getTopLeft(processingHeader).dy),
      );
      expect(
        find.descendant(
          of: processingHeader,
          matching: find.byType(ShaderMask),
        ),
        findsOneWidget,
      );
      final brandIcon = tester.widget<AgentBrandIcon>(
        find.descendant(
          of: processingHeader,
          matching: find.byType(AgentBrandIcon),
        ),
      );
      expect(brandIcon.agentId, 'codex-acp');

      await tester.pump(const Duration(seconds: 1));
      expect(find.textContaining('正在处理 4s'), findsOneWidget);
    },
  );

  testWidgets(
    'active Claude response shows its brand icon before text and keeps it after folding',
    (tester) async {
      final controller = ScrollController();
      final messages = <ChatMessageModel>[
        ChatMessageModel(
          id: 'task-1-text-2',
          type: 1,
          user: 2,
          content: const <String, dynamic>{
            'text': '工具完成后的正文',
            'id': 'task-1-text-2',
            'agentId': 'claude-code-acp',
            'agentName': 'Claude Code',
          },
          streamMeta: const <String, dynamic>{
            'parentTaskId': 'task-1',
            'kind': 'text_snapshot',
            'seq': 31,
            'entryId': 'task-1-text-2',
            'isFinal': true,
          },
        ),
        ..._buildCompletedAcpAgentRunMessages(),
      ];
      var activeTaskIds = <String>{'task-1'};
      late StateSetter setState;

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: StatefulBuilder(
            builder: (context, stateSetter) {
              setState = stateSetter;
              return SizedBox(
                width: 400,
                height: 520,
                child: ChatMessageList(
                  messages: messages,
                  activeAgentTaskIds: activeTaskIds,
                  useAcpPresentation: true,
                  scrollController: controller,
                  onBeforeTaskExecute: () async {},
                ),
              );
            },
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 320));

      // While running, the run's single header owns the avatar. There is no
      // per-message avatar any more, which is what makes it impossible for one
      // turn to show several.
      final activeAvatar = find.byKey(
        const ValueKey('agent-run-acp-avatar-task-1'),
      );
      expect(activeAvatar, findsOneWidget);
      final activeBrandIcon = tester.widget<AgentBrandIcon>(
        find.descendant(
          of: activeAvatar,
          matching: find.byType(AgentBrandIcon),
        ),
      );
      expect(activeBrandIcon.agentId, 'claude-code-acp');
      expect(
        tester.getTopLeft(activeAvatar).dy,
        lessThan(tester.getTopLeft(find.text('最终回答')).dy),
      );
      expect(
        find.byKey(const ValueKey('agent-run-summary-task-1')),
        findsOneWidget,
      );
      expect(find.byType(AgentBrandIcon), findsOneWidget);
      expect(find.textContaining('正在处理'), findsOneWidget);

      setState(() {
        activeTaskIds = <String>{};
      });
      await tester.pumpAndSettle();

      // Same header, same avatar — only the label and the fold affordance
      // change once the turn ends.
      expect(activeAvatar, findsOneWidget);
      expect(
        find.byKey(const ValueKey('agent-run-summary-task-1')),
        findsOneWidget,
      );
      expect(find.textContaining('已处理'), findsOneWidget);
      expect(find.text('工具完成后的正文'), findsOneWidget);
    },
  );

  testWidgets(
    'legacy ACP text run falls back to generic Agent avatar when identity is absent',
    (tester) async {
      final controller = ScrollController();
      final messages = _buildCompletedLegacyAcpTextRunMessages();

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: SizedBox(
            width: 400,
            height: 520,
            child: ChatMessageList(
              messages: messages,
              useAcpPresentation: true,
              scrollController: controller,
              onBeforeTaskExecute: () async {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('已处理'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('agent-run-acp-avatar-task-1')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('agent-run-avatar-task-1')),
        findsNothing,
      );
      final genericBrandIcon = tester.widget<AgentBrandIcon>(
        find.descendant(
          of: find.byKey(const ValueKey('agent-run-acp-avatar-task-1')),
          matching: find.byType(AgentBrandIcon),
        ),
      );
      expect(genericBrandIcon.agentId, 'generic-agent');
      expect(find.text('旧 Agent 纯文本回答'), findsOneWidget);
    },
  );

  testWidgets(
    'agent run summary removes divider and keeps chevron beside label',
    (tester) async {
      final controller = ScrollController();
      final messages = _buildCompletedAgentRunMessages();

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: SizedBox(
            width: 400,
            height: 520,
            child: ChatMessageList(
              messages: messages,
              scrollController: controller,
              onBeforeTaskExecute: () async {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final summaryToggle = find.byKey(
        const ValueKey('agent-run-summary-task-1'),
      );
      expect(summaryToggle, findsOneWidget);
      final summaryInkWell = tester.widget<InkWell>(
        find.descendant(of: summaryToggle, matching: find.byType(InkWell)),
      );
      expect(summaryInkWell.splashFactory, same(NoSplash.splashFactory));
      expect(
        summaryInkWell.overlayColor?.resolve(const <WidgetState>{
          WidgetState.pressed,
        }),
        Colors.transparent,
      );
      final rowFinder = find.descendant(
        of: summaryToggle,
        matching: find.byType(Row),
      );
      expect(rowFinder, findsOneWidget);
      final dividerFinder = find.descendant(
        of: rowFinder,
        matching: find.byWidgetPredicate((widget) {
          if (widget is! Container) return false;
          final constraints = widget.constraints;
          return constraints != null && constraints.maxHeight == 1.0;
        }),
      );
      expect(dividerFinder, findsNothing);

      final labelFinder = find.descendant(
        of: summaryToggle,
        matching: find.textContaining('已处理'),
      );
      final chevronFinder = find.byKey(
        const ValueKey('agent-run-summary-chevron-task-1'),
      );
      expect(labelFinder, findsOneWidget);
      expect(chevronFinder, findsOneWidget);

      final labelRect = tester.getRect(labelFinder);
      final chevronRect = tester.getRect(chevronFinder);
      expect(
        chevronRect.left - labelRect.right,
        inInclusiveRange(0, 4),
        reason:
            'chevron should immediately follow the processed label without '
            'a divider or flexible gap',
      );
    },
  );

  testWidgets('adjacent Xiaowan tool calls render as independent capsules', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildCompletedAgentRunMessagesWithToolGroup();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('agent-run-summary-task-1')));
    await tester.pumpAndSettle();

    // 小万完成后的 turn 仍由外层“已处理”统一折叠，但展开后每次工具调用
    // 必须保持为独立胶囊，不能套用 ACP 的并行工具合并胶囊。
    expect(find.text('已运行 1 条命令 · 已读取 1 个文件'), findsNothing);
    expect(find.textContaining('已处理'), findsOneWidget);

    final toolGroupToggle = find.byKey(
      const ValueKey(
        'agent-tool-call-group-toggle-task-1-task-1-tool-1-task-1-tool-2',
      ),
    );
    expect(toolGroupToggle, findsNothing);
    expect(
      find.byKey(const ValueKey('agent-run-task-1-task-1-tool-1')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('agent-run-task-1-task-1-tool-2')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('inline-file-diff-title-toggle')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('agent-tool-summary-capsule-task-1-tool-1')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('agent-tool-summary-capsule-task-1-tool-2')),
      findsOneWidget,
    );
    expect(find.text('运行 git status'), findsOneWidget);
    expect(find.text('读取 README.md'), findsOneWidget);
  });

  testWidgets('reopening run collapses thinking details by default again', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildCompletedAgentRunMessages();

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: SizedBox(
          width: 400,
          height: 520,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final summaryToggle = find.byKey(
      const ValueKey('agent-run-summary-task-1'),
    );
    await tester.tap(summaryToggle);
    await tester.pumpAndSettle();

    final thinkingToggle = find.descendant(
      of: find.byType(DeepThinkingCard),
      matching: find.byType(InkWell),
    );
    expect(thinkingToggle, findsOneWidget);

    await tester.tap(thinkingToggle);
    await tester.pumpAndSettle();
    expect(find.text('详细思考过程'), findsOneWidget);

    await tester.tap(summaryToggle);
    await tester.pumpAndSettle();
    expect(find.text('详细思考过程'), findsNothing);

    await tester.tap(summaryToggle);
    await tester.pumpAndSettle();
    expect(find.byType(DeepThinkingCard), findsOneWidget);
    expect(find.text('详细思考过程'), findsNothing);
  });

  testWidgets('agent run expansion can be controlled by the parent page', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = _buildCompletedAgentRunMessages();
    Set<String> expandedTaskIds = <String>{};
    late StateSetter setState;

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: StatefulBuilder(
          builder: (context, stateSetter) {
            setState = stateSetter;
            return SizedBox(
              width: 400,
              height: 520,
              child: ChatMessageList(
                messages: messages,
                scrollController: controller,
                expandedAgentRunTaskIds: expandedTaskIds,
                onExpandedAgentRunTaskIdsChanged: (nextTaskIds) {
                  setState(() {
                    expandedTaskIds = nextTaskIds;
                  });
                },
                onBeforeTaskExecute: () async {},
              ),
            );
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    final summaryToggle = find.byKey(
      const ValueKey('agent-run-summary-task-1'),
    );
    expect(find.text('运行 git status'), findsNothing);

    await tester.tap(summaryToggle);
    await tester.pumpAndSettle();
    expect(expandedTaskIds, const {'task-1'});
    expect(find.text('运行 git status'), findsOneWidget);

    await tester.tap(summaryToggle);
    await tester.pumpAndSettle();
    expect(expandedTaskIds, isEmpty);
    expect(find.text('运行 git status'), findsNothing);
  });

  testWidgets(
    'cancelled agent run auto-collapses trace and shows cancel body',
    (tester) async {
      final controller = ScrollController();
      final messages = ObservableChatMessageList()
        ..replaceAllMessages(_buildCompletedAgentRunMessages());
      Set<String> expandedTaskIds = <String>{'task-1'};
      late StateSetter setState;

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: StatefulBuilder(
            builder: (context, stateSetter) {
              setState = stateSetter;
              return SizedBox(
                width: 400,
                height: 520,
                child: ChatMessageList(
                  messages: messages,
                  scrollController: controller,
                  expandedAgentRunTaskIds: expandedTaskIds,
                  onExpandedAgentRunTaskIdsChanged: (nextTaskIds) {
                    setState(() {
                      expandedTaskIds = nextTaskIds;
                    });
                  },
                  onBeforeTaskExecute: () async {},
                ),
              );
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('运行 git status'), findsOneWidget);

      messages.insert(
        0,
        ChatMessageModel(
          id: 'task-1-cancelled',
          type: 1,
          user: 2,
          content: const <String, dynamic>{
            'text': '任务已取消',
            'id': 'task-1-cancelled',
            'renderMarkdown': false,
          },
          streamMeta: const <String, dynamic>{
            'parentTaskId': 'task-1',
            'kind': 'text_snapshot',
            'seq': 1000000000,
            'entryId': 'task-1-cancelled',
            'isFinal': true,
          },
        ),
      );
      await tester.pumpAndSettle();

      expect(expandedTaskIds, isEmpty);
      expect(find.text('任务已取消'), findsOneWidget);
      expect(find.text('运行 git status'), findsNothing);
    },
  );

  testWidgets(
    'expanding latest agent run keeps the summary row anchored while inset grows',
    (tester) async {
      final controller = ScrollController();
      final messages = _buildCompletedAgentRunMessages();
      Set<String> expandedTaskIds = <String>{};
      late StateSetter setState;

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: StatefulBuilder(
            builder: (context, stateSetter) {
              setState = stateSetter;
              return SizedBox(
                width: 400,
                height: 220,
                child: ChatMessageList(
                  messages: messages,
                  scrollController: controller,
                  expandedAgentRunTaskIds: expandedTaskIds,
                  onExpandedAgentRunTaskIdsChanged: (nextTaskIds) {
                    setState(() {
                      expandedTaskIds = nextTaskIds;
                    });
                  },
                  bottomOverlayInset: expandedTaskIds.isEmpty ? 0 : 96,
                  onBeforeTaskExecute: () async {},
                ),
              );
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 1),
      );

      final summaryToggle = find.byKey(
        const ValueKey('agent-run-summary-task-1'),
      );
      final initialTop = tester.getTopLeft(summaryToggle).dy;
      final initialOffset = controller.offset;

      await tester.tap(summaryToggle);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 120));

      final midAnimationTop = tester.getTopLeft(summaryToggle).dy;
      expect(midAnimationTop, closeTo(initialTop, 4));
      expect(controller.offset, closeTo(initialOffset, 4));
      expect(
        controller.offset,
        lessThan(controller.position.maxScrollExtent - 24),
      );
    },
  );

  testWidgets(
    'thinking auto-collapses before the run folds at task completion',
    (tester) async {
      final controller = ScrollController();
      var messages = _buildActiveAgentRunMessages();
      var activeTaskIds = <String>{'task-1'};
      late StateSetter setState;

      await tester.pumpWidget(
        _buildLocalizedApp(
          child: StatefulBuilder(
            builder: (context, stateSetter) {
              setState = stateSetter;
              return SizedBox(
                width: 400,
                height: 520,
                child: ChatMessageList(
                  messages: messages,
                  activeAgentTaskIds: activeTaskIds,
                  scrollController: controller,
                  onBeforeTaskExecute: () async {},
                ),
              );
            },
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 32));

      expect(
        find.byKey(const ValueKey('agent-run-summary-task-1')),
        findsNothing,
      );
      expect(
        find.descendant(
          of: find.byType(DeepThinkingCard),
          matching: find.byType(AgentAvatarButton),
        ),
        findsOneWidget,
      );
      expect(find.text('详细思考过程'), findsOneWidget);
      expect(find.text('运行 git status'), findsOneWidget);
      expect(find.text('最终回答'), findsOneWidget);

      // Finishing one thinking/content stage collapses that thinking card, but
      // must not fold the whole run while the task is still active.
      setState(() {
        messages = _buildCompletedAgentRunMessages();
      });
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('agent-run-summary-task-1')),
        findsNothing,
      );
      expect(find.byType(DeepThinkingCard), findsOneWidget);
      expect(find.text('详细思考过程'), findsNothing);
      expect(find.text('运行 git status'), findsOneWidget);
      expect(find.text('最终回答'), findsOneWidget);

      setState(() {
        activeTaskIds = <String>{};
      });
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('agent-run-summary-task-1')),
        findsOneWidget,
      );
      expect(find.text('已处理'), findsOneWidget);
      expect(find.byType(DeepThinkingCard), findsNothing);
      expect(find.text('详细思考过程'), findsNothing);
      expect(find.text('运行 git status'), findsNothing);
      expect(find.text('最终回答'), findsOneWidget);
    },
  );

  testWidgets('reaching top auto-loads older messages without jumping to top', (
    tester,
  ) async {
    final controller = ScrollController();
    var messages = _buildSimpleAssistantMessages(20, prefix: '初始消息');
    var loadMoreCalls = 0;
    late StateSetter setState;

    await tester.pumpWidget(
      _buildLocalizedApp(
        child: StatefulBuilder(
          builder: (context, stateSetter) {
            setState = stateSetter;
            return SizedBox(
              width: 400,
              height: 520,
              child: ChatMessageList(
                messages: messages,
                scrollController: controller,
                hasMore: loadMoreCalls == 0,
                onLoadMore: () async {
                  loadMoreCalls += 1;
                  setState(() {
                    messages = <ChatMessageModel>[
                      ...messages,
                      ..._buildSimpleAssistantMessages(
                        8,
                        prefix: '更早消息',
                        idPrefix: 'older',
                        startIndex: messages.length,
                      ),
                    ];
                  });
                },
                onBeforeTaskExecute: () async {},
              ),
            );
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    controller.jumpTo(24);
    await tester.pump();

    await tester.drag(find.byType(ListView), const Offset(0, 120));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    await tester.pumpAndSettle();

    expect(loadMoreCalls, 1);
    expect(messages.length, 28);
    expect(controller.offset, greaterThan(24));
    expect(tester.takeException(), isNull);
  });
}

Widget _buildChatMessageListHarness({
  required ScrollController controller,
  required List<ChatMessageModel> messages,
}) {
  return _buildLocalizedApp(
    child: SizedBox(
      width: 400,
      height: 520,
      child: ChatMessageList(
        messages: messages,
        scrollController: controller,
        onBeforeTaskExecute: () async {},
      ),
    ),
  );
}

Widget _buildLocalizedApp({required Widget child}) {
  return MaterialApp(
    locale: const Locale('zh'),
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: Scaffold(body: child),
  );
}

List<ChatMessageModel> _buildMessagesWithThinkingCard() {
  return [
    ChatMessageModel.cardMessage(<String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': List.generate(
        80,
        (index) => '第 ${index + 1} 行思考内容，供消息列表滚动回归测试使用。',
      ).join('\n'),
      'stage': 4,
      'isLoading': false,
      'isCollapsible': true,
      'taskID': 'thinking-card',
    }, id: 'thinking-card'),
    ...List.generate(12, (index) {
      return ChatMessageModel.assistantMessage(
        List.generate(
          4,
          (line) => '较早消息 ${index + 1} - 第 ${line + 1} 行',
        ).join('\n'),
        id: 'older-$index',
      );
    }),
  ];
}

List<ChatMessageModel> _buildSimpleAssistantMessages(
  int count, {
  required String prefix,
  String idPrefix = 'assistant',
  int startIndex = 0,
}) {
  return List<ChatMessageModel>.generate(count, (index) {
    final resolvedIndex = startIndex + index;
    return ChatMessageModel.assistantMessage(
      List.generate(
        3,
        (line) => '$prefix ${resolvedIndex + 1} - 第 ${line + 1} 行内容，用于分页加载测试。',
      ).join('\n'),
      id: '$idPrefix-$resolvedIndex',
    );
  });
}

List<ChatMessageModel> _buildStreamingThinkingMessages({
  required int thinkingLines,
}) {
  return <ChatMessageModel>[
    ChatMessageModel.cardMessage(<String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': List.generate(
        thinkingLines,
        (index) => '第 ${index + 1} 行流式思考内容，验证列表持续跟随最新位置。',
      ).join('\n'),
      'stage': 1,
      'isLoading': true,
      'isCollapsible': true,
      'taskID': 'streaming-thinking-card',
    }, id: 'streaming-thinking-card'),
    ...List.generate(18, (index) {
      return ChatMessageModel.assistantMessage(
        List.generate(
          5,
          (line) => '较早消息 ${index + 1} - 第 ${line + 1} 行',
        ).join('\n'),
        id: 'streaming-older-$index',
      );
    }),
  ];
}

List<ChatMessageModel> _buildToggleRegressionThinkingMessages() {
  return <ChatMessageModel>[
    ChatMessageModel.cardMessage(<String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': List.generate(
        3,
        (index) => '最新思考卡第 ${index + 1} 行，保持可见。',
      ).join('\n'),
      'stage': 4,
      'isLoading': false,
      'isCollapsible': true,
      'taskID': 'latest-thinking-card',
    }, id: 'latest-thinking-card'),
    ChatMessageModel.cardMessage(<String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': List.generate(
        60,
        (index) => '较早思考卡第 ${index + 1} 行，展开后高度明显增加。',
      ).join('\n'),
      'stage': 4,
      'isLoading': false,
      'isCollapsible': true,
      'taskID': 'older-thinking-card',
    }, id: 'older-thinking-card'),
    ...List.generate(6, (index) {
      return ChatMessageModel.assistantMessage(
        List.generate(
          3,
          (line) => '普通消息 ${index + 1} - 第 ${line + 1} 行',
        ).join('\n'),
        id: 'toggle-regression-$index',
      );
    }),
  ];
}

List<ChatMessageModel> _buildCompletedAgentRunMessages({bool isFinal = true}) {
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-1-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{'text': '最终回答', 'id': 'task-1-text'},
      streamMeta: <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'text_snapshot',
        'seq': 30,
        'entryId': 'task-1-text',
        'isFinal': isFinal,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_tool_summary',
        // 原生历史恢复层会给小万工具卡补上这个通用渲染样式；
        // 它不能被当成 ACP Agent 身份标记。
        'uiStyle': 'agent_tool',
        'status': 'success',
        'toolType': 'terminal',
        'toolTitle': '运行 git status',
        'summary': '命令执行完成',
        'terminalOutput': 'On branch main',
      },
      id: 'task-1-tool',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'tool_completed',
        'seq': 20,
        'entryId': 'task-1-tool',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'deep_thinking',
        'thinkingContent': '详细思考过程',
        'stage': 4,
        'isLoading': false,
        'taskID': 'task-1',
        'cardId': 'task-1-thinking',
      },
      id: 'task-1-thinking',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'thinking_snapshot',
        'seq': 10,
        'entryId': 'task-1-thinking',
        'isFinal': false,
      },
    ),
    ChatMessageModel.userMessage('用户问题', id: 'task-1-user'),
  ];
}

List<ChatMessageModel> _buildCompletedAgentRunMessagesWithToolGroup() {
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-1-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{'text': '最终回答', 'id': 'task-1-text'},
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'text_snapshot',
        'seq': 30,
        'entryId': 'task-1-text',
        'isFinal': true,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_tool_summary',
        'uiStyle': 'agent_tool',
        'status': 'success',
        'toolType': 'workspace',
        'toolTitle': '读取 README.md',
        'summary': '读取完成',
      },
      id: 'task-1-tool-2',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'tool_completed',
        'seq': 25,
        'entryId': 'task-1-tool-2',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_tool_summary',
        'uiStyle': 'agent_tool',
        'status': 'success',
        'toolType': 'terminal',
        'toolTitle': '运行 git status',
        'summary': '命令执行完成',
        'terminalOutput': 'On branch main',
      },
      id: 'task-1-tool-1',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'tool_completed',
        'seq': 20,
        'entryId': 'task-1-tool-1',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'deep_thinking',
        'thinkingContent': '详细思考过程',
        'stage': 4,
        'isLoading': false,
        'taskID': 'task-1',
        'cardId': 'task-1-thinking',
      },
      id: 'task-1-thinking',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'thinking_snapshot',
        'seq': 10,
        'entryId': 'task-1-thinking',
        'isFinal': false,
      },
    ),
    ChatMessageModel.userMessage('用户问题', id: 'task-1-user'),
  ];
}

List<ChatMessageModel> _buildCompletedAcpAgentRunMessages() {
  // Legacy snapshots remain readable, while the persisted Agent identity
  // drives the visible avatar.
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-1-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{
        'text': '最终回答',
        'id': 'task-1-text',
        'agentId': 'claude-code-acp',
        'agentName': 'Claude Code',
      },
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'text_snapshot',
        'seq': 30,
        'entryId': 'task-1-text',
        'isFinal': true,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_tool_summary',
        'uiStyle': 'codex_tool',
        'agentId': 'claude-code-acp',
        'agentName': 'Claude Code',
        'status': 'success',
        'toolType': 'search',
        'toolTitle': 'rg foo',
        'summary': 'rg 完成',
      },
      id: 'task-1-tool-search-1',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'tool_completed',
        'seq': 26,
        'entryId': 'task-1-tool-search-1',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_tool_summary',
        'uiStyle': 'codex_tool',
        'agentId': 'claude-code-acp',
        'agentName': 'Claude Code',
        'status': 'success',
        'toolType': 'search',
        'toolTitle': 'rg bar',
        'summary': 'rg 完成',
      },
      id: 'task-1-tool-search-2',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'tool_completed',
        'seq': 25,
        'entryId': 'task-1-tool-search-2',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'deep_thinking',
        'agentId': 'claude-code-acp',
        'agentName': 'Claude Code',
        'thinkingContent': 'Agent 在思考',
        'stage': 4,
        'isLoading': false,
        'taskID': 'task-1',
        'cardId': 'task-1-thinking',
      },
      id: 'task-1-thinking',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'thinking_snapshot',
        'seq': 10,
        'entryId': 'task-1-thinking',
        'isFinal': false,
      },
    ),
    ChatMessageModel.userMessage('用户问题', id: 'task-1-user'),
  ];
}

List<ChatMessageModel> _buildCompletedLegacyAcpTextRunMessages() {
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-1-codex-agent',
      type: 1,
      user: 2,
      content: const <String, dynamic>{
        'text': '旧 Agent 纯文本回答',
        'id': 'task-1-codex-agent',
      },
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'text_snapshot',
        'seq': 20,
        'entryId': 'task-1-codex-agent',
        'isFinal': true,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'deep_thinking',
        'thinkingContent': '旧 Agent 在思考',
        'stage': 4,
        'isLoading': false,
        'taskID': 'task-1',
        'cardId': 'task-1-codex-thinking',
      },
      id: 'task-1-codex-thinking',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'thinking_snapshot',
        'seq': 10,
        'entryId': 'task-1-codex-thinking',
        'isFinal': false,
      },
    ),
    ChatMessageModel.userMessage('用户问题', id: 'task-1-user'),
  ];
}

List<ChatMessageModel> _buildActiveAgentRunMessages() {
  return <ChatMessageModel>[
    ChatMessageModel(
      id: 'task-1-text',
      type: 1,
      user: 2,
      content: const <String, dynamic>{'text': '最终回答', 'id': 'task-1-text'},
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'text_snapshot',
        'seq': 30,
        'entryId': 'task-1-text',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'running',
        'toolType': 'terminal',
        'toolTitle': '运行 git status',
        'summary': '命令执行中',
        'terminalOutput': 'On branch main',
      },
      id: 'task-1-tool',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'tool_progress',
        'seq': 20,
        'entryId': 'task-1-tool',
        'isFinal': false,
      },
    ),
    ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'deep_thinking',
        'thinkingContent': '详细思考过程',
        'stage': 1,
        'isLoading': true,
        'taskID': 'task-1',
        'cardId': 'task-1-thinking',
      },
      id: 'task-1-thinking',
      streamMeta: const <String, dynamic>{
        'parentTaskId': 'task-1',
        'kind': 'thinking_snapshot',
        'seq': 10,
        'entryId': 'task-1-thinking',
        'isFinal': false,
      },
    ),
    ChatMessageModel.userMessage('用户问题', id: 'task-1-user'),
  ];
}
