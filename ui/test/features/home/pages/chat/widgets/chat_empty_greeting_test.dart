import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_empty_greeting.dart';
import 'package:ui/l10n/generated/app_localizations.dart';

void main() {
  testWidgets('does not show a standalone guide action', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: MediaQuery(
            data: const MediaQueryData(disableAnimations: true),
            child: const ChatEmptyGreeting(),
          ),
        ),
      ),
    );
    await tester.pump();

    expect(
      find.byKey(const ValueKey('chat-empty-omnibot-guide')),
      findsNothing,
    );
  });

  testWidgets('uses the active Harness name in the empty greeting', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: MediaQuery(
            data: MediaQueryData(disableAnimations: true),
            child: ChatEmptyGreeting(agentName: 'OpenCode'),
          ),
        ),
      ),
    );

    expect(find.text('你好👋，我是OpenCode'), findsOneWidget);
    expect(find.textContaining('我是小万'), findsNothing);
  });
}
