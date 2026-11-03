import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/widgets/home_conversation_tile_content.dart';
import 'package:ui/features/home/widgets/home_conversation_avatar_colors.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/widgets/agent_brand_icon.dart';
import 'package:ui/widgets/agent_avatar.dart';

ConversationModel _conversation({
  String? agentId = 'codex-acp',
  ConversationMode mode = ConversationMode.agent,
  String? lastMessage = '最新消息',
  String? summary,
  int messageCount = 2,
  DateTime? updated,
}) {
  final timestamp =
      (updated ?? DateTime(DateTime.now().year, 3, 12)).millisecondsSinceEpoch;
  return ConversationModel(
    id: 1,
    title: 'Conversation title',
    status: 0,
    messageCount: messageCount,
    createdAt: timestamp,
    updatedAt: timestamp,
    agentId: agentId,
    mode: mode,
    lastMessage: lastMessage,
    summary: summary,
    contextSummary: 'Internal context should never become a preview',
  );
}

Widget _app({
  required Widget child,
  bool dark = false,
  Locale locale = const Locale('zh'),
  double scale = 1,
}) => MaterialApp(
  theme: dark ? AppTheme.darkTheme : AppTheme.lightTheme,
  locale: locale,
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: AppLocalizations.supportedLocales,
  builder: (context, child) => MediaQuery(
    data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(scale)),
    child: child!,
  ),
  home: Scaffold(
    body: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Align(alignment: Alignment.topCenter, child: child),
    ),
  ),
);

Finder get _preview => find.byKey(const ValueKey('home-conversation-preview'));
Finder get _date => find.byKey(const ValueKey('home-conversation-date'));

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  for (final dark in [false, true]) {
    testWidgets(
      'assigned avatar color follows ${dark ? 'dark' : 'light'} theme',
      (tester) async {
        final conversation = _conversation();
        await tester.pumpWidget(
          _app(
            dark: dark,
            child: HomeConversationTileContent(
              conversation: conversation,
              title: 'Title',
              avatarColorIndex: 3,
            ),
          ),
        );
        final avatar = find.byKey(
          ValueKey('home-conversation-avatar-${conversation.threadKey}'),
        );
        final decoration =
            tester.widget<Container>(avatar).decoration! as BoxDecoration;
        expect(decoration.shape, BoxShape.circle);
        expect(decoration.gradient, isNull);
        expect(
          decoration.color,
          HomeConversationAvatarColors.colorAt(
            3,
            dark ? Brightness.dark : Brightness.light,
          ),
        );
        expect(tester.getSize(avatar), const Size.square(42));
        expect(tester.takeException(), isNull);
      },
    );

    testWidgets(
      'two-line content follows the ${dark ? 'dark' : 'light'} theme',
      (tester) async {
        await tester.pumpWidget(
          _app(
            dark: dark,
            child: HomeConversationTileContent(
              conversation: _conversation(lastMessage: '  最新\n消息\t  摘要  '),
              title: '标题',
            ),
          ),
        );
        await tester.pumpAndSettle();
        final palette = dark ? OmniThemePalette.dark : OmniThemePalette.light;
        final title = tester.element(find.text('标题'));
        final titleStyle = DefaultTextStyle.of(title).style;
        expect(titleStyle.fontSize, 16);
        expect(titleStyle.fontWeight, FontWeight.w600);
        expect(titleStyle.color, palette.textPrimary);
        expect(tester.widget<Text>(_preview).data, '最新 消息 摘要');
        expect(tester.widget<Text>(_preview).style!.fontSize, 14);
        expect(
          tester.widget<Text>(_preview).style!.color,
          palette.textSecondary,
        );
        expect(tester.widget<Text>(_date).style!.fontSize, 12);
        expect(
          tester.getSize(find.byType(HomeConversationTileContent)).height,
          74,
        );
        final row = tester.getRect(find.byType(HomeConversationTileContent));
        expect(tester.getRect(_date).center.dy, closeTo(row.center.dy, 0.01));
        expect(tester.getRect(find.text('标题')).left, row.left + 58);
        expect(
          tester.getRect(_preview).right,
          closeTo(tester.getRect(_date).left - 12, 0.01),
        );
        expect(find.byType(InkWell), findsNothing);
        expect(find.byType(GestureDetector), findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'search preview wins while blank override preserves the latest message',
    (tester) async {
      final conversation = _conversation(lastMessage: 'latest\nmessage');
      await tester.pumpWidget(
        _app(
          child: HomeConversationTileContent(
            conversation: conversation,
            title: 'Title',
            previewOverride: '  matched\nsearch   result ',
          ),
        ),
      );
      expect(tester.widget<Text>(_preview).data, 'matched search result');
      await tester.pumpWidget(
        _app(
          child: HomeConversationTileContent(
            conversation: conversation,
            title: 'Title',
            previewOverride: '\n\t ',
          ),
        ),
      );
      expect(tester.widget<Text>(_preview).data, 'latest message');
    },
  );

  for (final locale in [const Locale('zh'), const Locale('en')]) {
    for (final hasMessages in [false, true]) {
      testWidgets(
        'missing preview uses localized metadata fallback $locale messages=$hasMessages',
        (tester) async {
          await tester.pumpWidget(
            _app(
              locale: locale,
              child: HomeConversationTileContent(
                conversation: _conversation(
                  lastMessage: ' \n',
                  summary: 'Short summary title is not a message',
                  messageCount: hasMessages ? 3 : 0,
                ),
                title: 'Title',
              ),
            ),
          );
          await tester.pumpAndSettle();
          final english = locale.languageCode == 'en';
          expect(
            tester.widget<Text>(_preview).data,
            hasMessages
                ? (english ? 'No preview available' : '暂无预览')
                : (english ? 'No messages yet' : '暂无消息'),
          );
          expect(
            find.text('Short summary title is not a message'),
            findsNothing,
          );
          expect(
            find.text('Internal context should never become a preview'),
            findsNothing,
          );
          expect(tester.takeException(), isNull);
        },
      );
    }
  }

  testWidgets(
    'previews beginning with structured punctuation remain readable text',
    (tester) async {
      await tester.pumpWidget(
        _app(
          child: HomeConversationTileContent(
            conversation: _conversation(
              lastMessage: '[完成] {"file":"main.dart"}',
            ),
            title: 'Title',
          ),
        ),
      );
      expect(tester.widget<Text>(_preview).data, '[完成] {"file":"main.dart"}');
    },
  );

  for (final agent in [
    'codex-acp',
    'kimi-code-acp',
    'claude-code-acp',
    'opencode-acp',
    'deepseek-harness-acp', // Retired Agent history keeps its brand.
    'na-cloud',
    'pi-acp',
    'unknown-custom-agent',
  ]) {
    testWidgets('keeps the shared Agent brand for $agent', (tester) async {
      await tester.pumpWidget(
        _app(
          child: HomeConversationTileContent(
            conversation: _conversation(
              agentId: agent,
              mode: ConversationMode.chatOnly,
            ),
            title: 'Title',
            isExecuting: true,
          ),
        ),
      );
      await tester.pumpAndSettle();
      final brand = tester.widget<AgentBrandIcon>(find.byType(AgentBrandIcon));
      expect(brand.agentId, agent);
      expect(brand.size, 28);
      expect(brand.tint, const Color(0xFF20242A));
      expect(
        find.byKey(const ValueKey('home-conversation-executing-dot')),
        findsOneWidget,
      );
      if (agent == 'unknown-custom-agent') {
        expect(find.byIcon(Icons.smart_toy_outlined), findsOneWidget);
      }
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('legacy conversations retain the editable Xiaowan identity', (
    tester,
  ) async {
    await tester.pumpWidget(
      _app(
        child: HomeConversationTileContent(
          conversation: _conversation(
            agentId: null,
            mode: ConversationMode.normal,
          ),
          title: 'Title',
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      tester.widget<AgentBrandIcon>(find.byType(AgentBrandIcon)).agentId,
      'xiaowan-acp',
    );
    final image = tester.widget<Image>(find.byType(Image));
    expect(
      image.color,
      tester
          .widget<AgentBrandIcon>(find.byType(AgentBrandIcon))
          .avatarBackgroundColor,
    );
    expect(image.color, isNotNull);
    expect(image.colorBlendMode, BlendMode.modulate);
    expect(tester.takeException(), isNull);
  });

  testWidgets('solid backing preserves other preset and custom image colors', (
    tester,
  ) async {
    const backing = Color(0xFFDBB9C7);
    await tester.pumpWidget(
      _app(
        child: const AgentAvatarCircle(
          avatarIndex: 1,
          backgroundColor: backing,
        ),
      ),
    );
    final preset = tester.widget<Image>(find.byType(Image));
    expect(preset.image, isA<AssetImage>());
    expect(preset.color, isNull);

    final customPath = File('assets/avatar/default_avatar2.png').absolute.path;
    await tester.pumpWidget(
      _app(
        child: AgentAvatarCircle(
          customImagePath: customPath,
          backgroundColor: backing,
        ),
      ),
    );
    final custom = tester.widget<Image>(find.byType(Image));
    expect(custom.image, isA<FileImage>());
    expect((custom.image as FileImage).file.path, customPath);
    expect(custom.color, isNull);
    expect(tester.takeException(), isNull);
  });

  for (final mode in [ConversationMode.chatOnly, ConversationMode.openclaw]) {
    testWidgets('$mode uses its established icon asset', (tester) async {
      await tester.pumpWidget(
        _app(
          child: HomeConversationTileContent(
            conversation: _conversation(agentId: null, mode: mode),
            title: 'Title',
          ),
        ),
      );
      await tester.pumpAndSettle();
      final picture = tester.widget<SvgPicture>(find.byType(SvgPicture));
      expect(
        (picture.bytesLoader as SvgAssetLoader).assetName,
        mode == ConversationMode.chatOnly
            ? 'assets/home/chat/pure_chat.svg'
            : 'assets/home/openclaw.svg',
      );
      expect(find.byType(AgentBrandIcon), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  for (final width in [320.0, 1024.0]) {
    for (final scale in [1.0, 2.0]) {
      testWidgets('long content and badge fit width=$width scale=$scale', (
        tester,
      ) async {
        await tester.binding.setSurfaceSize(Size(width, 600));
        addTearDown(() => tester.binding.setSurfaceSize(null));
        await tester.pumpWidget(
          _app(
            scale: scale,
            child: HomeConversationTileContent(
              conversation: _conversation(
                lastMessage: 'Long message preview ' * 40,
                updated: DateTime(DateTime.now().year - 1, 10, 28),
              ),
              title: 'A conversation title with a very long project name ' * 12,
              badge: const Text('Archived', key: ValueKey('archive badge')),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(tester.getSize(_date).width, lessThanOrEqualTo(80));
        expect(
          tester.widget<Text>(_date).data,
          startsWith('${DateTime.now().year - 1}/'),
        );
        final row = tester.getRect(find.byType(HomeConversationTileContent));
        expect(row.width, width - 32);
        expect(row.height, scale == 1 ? 74 : greaterThan(74));
        expect(tester.getRect(_preview).right, lessThanOrEqualTo(row.right));
        expect(tester.getRect(_date).right, lessThanOrEqualTo(row.right));
        expect(find.byKey(const ValueKey('archive badge')), findsOneWidget);
        expect(tester.takeException(), isNull);
      });
    }
  }

  testWidgets('custom selection and editor remain caller-owned', (
    tester,
  ) async {
    final controller = TextEditingController(text: 'Editable title');
    final focus = FocusNode();
    addTearDown(controller.dispose);
    addTearDown(focus.dispose);
    await tester.pumpWidget(
      _app(
        child: HomeConversationTileContent(
          conversation: _conversation(),
          title: 'Unused display title',
          leading: const Center(
            child: Icon(Icons.check_circle, key: ValueKey('selection')),
          ),
          titleWidget: TextField(
            controller: controller,
            focusNode: focus,
            maxLines: 1,
            decoration: const InputDecoration(
              isDense: true,
              border: InputBorder.none,
            ),
          ),
          isExecuting: true,
        ),
      ),
    );
    expect(find.byType(AgentBrandIcon), findsNothing);
    expect(find.byKey(const ValueKey('selection')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('home-conversation-executing-dot')),
      findsNothing,
    );
    await tester.tap(find.byType(TextField));
    await tester.enterText(find.byType(TextField), 'Renamed');
    expect(controller.text, 'Renamed');
    expect(focus.hasFocus, isTrue);
    expect(tester.takeException(), isNull);
  });

  testWidgets('dates follow Chinese and English locale', (tester) async {
    for (final locale in [const Locale('zh'), const Locale('en')]) {
      await tester.pumpWidget(
        _app(
          locale: locale,
          child: HomeConversationTileContent(
            conversation: _conversation(),
            title: 'Title',
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        tester.widget<Text>(_date).data,
        locale.languageCode == 'zh' ? '3月12日' : 'Mar 12',
      );
    }
  });
}
