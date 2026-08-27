import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_message_anchor_bar.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/widgets/agent_avatar.dart';
import 'package:ui/widgets/agent_brand_icon.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
  });

  Widget buildAnchorBar(Brightness brightness) {
    return MaterialApp(
      theme: ThemeData(brightness: brightness),
      home: Scaffold(
        body: SizedBox.expand(
          child: ChatMessageAnchorBar(
            messages: <ChatMessageModel>[
              ChatMessageModel.userMessage('需要突出显示的消息锚点', id: 'user-1'),
            ],
            activeAgentTurnIds: const <String>{},
            conversationSignature: 'normal:1',
            bottomInset: 72,
            visible: true,
            onJumpToEntry: (_) async => true,
          ),
        ),
      ),
    );
  }

  Widget buildSystemBarsScrim({required bool expanded}) {
    return MaterialApp(
      theme: ThemeData(brightness: Brightness.light),
      home: MediaQuery(
        data: const MediaQueryData(
          viewPadding: EdgeInsets.only(top: 24, bottom: 34),
        ),
        child: Scaffold(
          body: ChatMessageAnchorSystemBarsScrim(expanded: expanded),
        ),
      ),
    );
  }

  testWidgets('expanded fan spotlights anchors over a dismissible scrim', (
    tester,
  ) async {
    await tester.pumpWidget(buildAnchorBar(Brightness.light));
    await tester.pump();

    var scrim = tester.widget<FadeTransition>(
      find.byKey(chatMessageAnchorScrimKey),
    );
    expect(scrim.opacity.value, 0);

    await tester.tap(find.byIcon(LucideIcons.galleryVerticalEnd));
    await tester.pumpAndSettle();

    scrim = tester.widget<FadeTransition>(
      find.byKey(chatMessageAnchorScrimKey),
    );
    expect(scrim.opacity.value, 1);
    expect(find.text('需要突出显示的消息锚点'), findsOneWidget);
    expect(find.byIcon(LucideIcons.galleryVerticalEnd), findsOneWidget);

    final scrimColor = tester.widget<ColoredBox>(
      find.descendant(
        of: find.byKey(chatMessageAnchorScrimKey),
        matching: find.byType(ColoredBox),
      ),
    );
    expect(scrimColor.color, Colors.black.withValues(alpha: 0.46));

    await tester.tapAt(const Offset(12, 12));
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<FadeTransition>(find.byKey(chatMessageAnchorScrimKey))
          .opacity
          .value,
      0,
    );
  });

  testWidgets('dark mode uses a stronger scrim behind foreground anchors', (
    tester,
  ) async {
    await tester.pumpWidget(buildAnchorBar(Brightness.dark));
    await tester.pump();
    await tester.tap(find.byIcon(LucideIcons.galleryVerticalEnd));
    await tester.pumpAndSettle();

    final scrimColor = tester.widget<ColoredBox>(
      find.descendant(
        of: find.byKey(chatMessageAnchorScrimKey),
        matching: find.byType(ColoredBox),
      ),
    );
    expect(scrimColor.color, Colors.black.withValues(alpha: 0.64));
    expect(find.text('需要突出显示的消息锚点'), findsOneWidget);
  });

  testWidgets('ACP anchors use the producing agents own brand avatars', (
    tester,
  ) async {
    final messages = <ChatMessageModel>[
      ChatMessageModel(
        id: 'codex-answer',
        type: 1,
        user: 2,
        content: const <String, dynamic>{
          'text': 'Codex 回答',
          'id': 'codex-answer',
          'agentId': 'codex-acp',
        },
      ),
      ChatMessageModel(
        id: 'claude-answer',
        type: 1,
        user: 2,
        content: const <String, dynamic>{
          'text': 'Claude 回答',
          'id': 'claude-answer',
          'agentId': 'claude-code-acp',
        },
      ),
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ChatMessageAnchorBar(
            messages: messages,
            activeAgentTurnIds: const <String>{},
            conversationSignature: 'agent:1',
            bottomInset: 72,
            visible: true,
            onJumpToEntry: (_) async => true,
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byIcon(LucideIcons.galleryVerticalEnd));
    await tester.pumpAndSettle();

    final agentIds = tester
        .widgetList<AgentBrandIcon>(find.byType(AgentBrandIcon))
        .map((icon) => icon.agentId)
        .toSet();
    expect(agentIds, <String>{'codex-acp', 'claude-code-acp'});
    expect(find.byType(AgentAvatarCircle), findsNothing);
  });

  testWidgets('plain assistant anchor keeps the Xiaowan avatar', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ChatMessageAnchorBar(
            messages: <ChatMessageModel>[
              ChatMessageModel.assistantMessage('小万回答', id: 'xiaowan'),
            ],
            activeAgentTurnIds: const <String>{},
            conversationSignature: 'normal:1',
            bottomInset: 72,
            visible: true,
            onJumpToEntry: (_) async => true,
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byIcon(LucideIcons.galleryVerticalEnd));
    await tester.pumpAndSettle();

    expect(find.byType(AgentAvatarCircle), findsOneWidget);
    expect(find.byType(AgentBrandIcon), findsNothing);
  });

  testWidgets('spotlight extends into status and gesture navigation insets', (
    tester,
  ) async {
    await tester.pumpWidget(buildSystemBarsScrim(expanded: false));

    expect(
      tester
          .widget<AnimatedOpacity>(
            find.byKey(chatMessageAnchorStatusBarScrimKey),
          )
          .opacity,
      0,
    );
    expect(
      tester
          .widget<AnimatedOpacity>(
            find.byKey(chatMessageAnchorNavigationBarScrimKey),
          )
          .opacity,
      0,
    );
    var overlayRegion = tester.widget<AnnotatedRegion<SystemUiOverlayStyle>>(
      find.byKey(chatMessageAnchorSystemBarsScrimKey),
    );
    expect(overlayRegion.value.statusBarIconBrightness, Brightness.dark);

    await tester.pumpWidget(buildSystemBarsScrim(expanded: true));
    await tester.pumpAndSettle();

    expect(
      tester.getSize(find.byKey(chatMessageAnchorStatusBarScrimKey)).height,
      24,
    );
    expect(
      tester.getSize(find.byKey(chatMessageAnchorNavigationBarScrimKey)).height,
      34,
    );
    expect(
      tester
          .getBottomRight(find.byKey(chatMessageAnchorNavigationBarScrimKey))
          .dy,
      tester.view.physicalSize.height / tester.view.devicePixelRatio,
    );
    expect(
      tester
          .widget<AnimatedOpacity>(
            find.byKey(chatMessageAnchorStatusBarScrimKey),
          )
          .opacity,
      1,
    );
    expect(
      tester
          .widget<ColoredBox>(
            find.descendant(
              of: find.byKey(chatMessageAnchorStatusBarScrimKey),
              matching: find.byType(ColoredBox),
            ),
          )
          .color,
      Colors.black.withValues(alpha: 0.46),
    );
    overlayRegion = tester.widget<AnnotatedRegion<SystemUiOverlayStyle>>(
      find.byKey(chatMessageAnchorSystemBarsScrimKey),
    );
    expect(overlayRegion.value.statusBarIconBrightness, Brightness.light);
    expect(
      overlayRegion.value.systemNavigationBarIconBrightness,
      Brightness.light,
    );

    await tester.pumpWidget(buildSystemBarsScrim(expanded: false));
    await tester.pump(const Duration(milliseconds: 60));

    // At this early point the center scrim has already dropped well below
    // full opacity. The system inset scrims must follow instead of lingering
    // near 1 as they did with an easeIn target animation.
    double renderedOpacity(ValueKey<String> key) {
      return tester
          .widget<FadeTransition>(
            find.descendant(
              of: find.byKey(key),
              matching: find.byType(FadeTransition),
            ),
          )
          .opacity
          .value;
    }

    final statusOpacity = renderedOpacity(chatMessageAnchorStatusBarScrimKey);
    final navigationOpacity = renderedOpacity(
      chatMessageAnchorNavigationBarScrimKey,
    );
    expect(statusOpacity, lessThan(0.6));
    expect(navigationOpacity, closeTo(statusOpacity, 0.001));
  });
}
