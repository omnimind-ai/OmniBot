import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/agent_sessions_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/widgets/omni_menu.dart';

void main() {
  const runtime = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const events = MethodChannel('cn.com.omnimind.bot/AgentRuntimeEvents');

  testWidgets(
    'session more menu returns before archiving the selected session',
    (tester) async {
      final writes = <MethodCall>[];
      var archived = false;
      final messenger = tester.binding.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(events, (_) async => null);
      messenger.setMockMethodCallHandler(runtime, (call) async {
        if (call.method == 'status') {
          return {'ready': true, 'connected': true, 'runtime': 'local'};
        }
        if (call.method == 'session/list') {
          return {
            'sessions': [
              {
                'sessionId': 'chosen-session',
                'title': 'Selected session',
                'archived': archived,
              },
            ],
          };
        }
        if (call.method == 'session/archive') {
          writes.add(call);
          archived = true;
        }
        return <String, dynamic>{};
      });
      addTearDown(() {
        messenger.setMockMethodCallHandler(runtime, null);
        messenger.setMockMethodCallHandler(events, null);
      });
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('en'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const AgentSessionsPage(agentId: 'codex-remote'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Session actions'));
      await tester.pumpAndSettle();
      expect(find.byType(OmniMenuSurface), findsOneWidget);
      expect(find.byType(BottomSheet), findsNothing);
      expect(find.text('Archive').hitTestable(), findsOneWidget);
      await tester.tap(find.text('Archive'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));
      expect(writes, isEmpty);
      await tester.pumpAndSettle();
      expect(writes, hasLength(1));
      expect(writes.single.arguments, {'sessionId': 'chosen-session', 'agentId': 'codex-remote'});
      expect(find.byType(OmniMenuSurface), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
