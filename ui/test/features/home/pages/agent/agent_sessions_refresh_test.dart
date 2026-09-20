import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/agent_sessions_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const events = MethodChannel('cn.com.omnimind.bot/AgentRuntimeEvents');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  Widget page() => MaterialApp(
    theme: AppTheme.lightTheme,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: const AgentSessionsPage(),
  );
  setUp(() {
    messenger.setMockMethodCallHandler(events, (_) async => null);
  });
  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    messenger.setMockMethodCallHandler(events, null);
  });
  testWidgets(
    'unpaired computer offers connection without activating a runtime',
    (tester) async {
      final calls = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        if (call.method == 'config/remote/read')
          return {'remoteConfigured': false};
        throw StateError('Unexpected call: ${call.method}');
      });
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          locale: const Locale('en'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const Scaffold(
            body: AgentSessionsPage(embedded: true, remoteOnly: true),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Connect your computer'), findsOneWidget);
      expect(find.text('Connect computer'), findsOneWidget);
      expect(find.text('Retry'), findsNothing);
      expect(calls, ['config/remote/read']);
      await tester.pump(const Duration(seconds: 6));
      expect(calls, ['config/remote/read']);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'computer drawer searches folders and creates one selected session',
    (tester) async {
      final pending = Completer<Map<String, dynamic>>();
      var creates = 0;
      String? selected;
      messenger.setMockMethodCallHandler(channel, (call) async {
        switch (call.method) {
          case 'config/remote/read':
            return {
              'remoteConfigured': true,
              'remoteEnabled': true,
              'remoteBridgeUrl': 'wss://fixture.example/codex',
              'remoteCwd': '/project/alpha',
            };
          case 'connect':
            return {
              'ready': true,
              'connected': true,
              'runtime': 'remote',
              'remoteCwd': '/project/alpha',
            };
          case 'session/list':
            return {
              'sessions': [
                {
                  'sessionId': 'alpha',
                  'title': 'First task',
                  'cwd': '/project/alpha',
                },
                {
                  'sessionId': 'beta',
                  'title': 'Second task',
                  'cwd': '/project/beta',
                },
              ],
            };
          case 'session/new':
            creates++;
            expect((call.arguments as Map)['cwd'], '/project/alpha');
            return pending.future;
          default:
            throw StateError('Unexpected call: ${call.method}');
        }
      });
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: AgentSessionsPage(
              embedded: true,
              remoteOnly: true,
              onSessionSelected: (target) => selected = target.agentSessionId,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'BETA');
      await tester.pumpAndSettle();
      expect(find.text('Second task'), findsOneWidget);
      expect(find.text('First task'), findsNothing);
      final button = find.byKey(const Key('computer-sessions-new'));
      await tester.tap(button);
      await tester.pump();
      expect(tester.widget<IconButton>(button).onPressed, isNull);
      expect(creates, 1);
      pending.complete({'sessionId': 'created-on-computer'});
      await tester.pumpAndSettle();
      expect(selected, 'created-on-computer');
      expect(creates, 1);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'drawer remote selection lists one page and opens the same session',
    (tester) async {
      final calls = <String>[];
      String? selected;
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        switch (call.method) {
          case 'status':
            return {'ready': true, 'connected': true, 'runtime': 'local'};
          case 'config/remote/read':
            return {
              'remoteConfigured': true,
              'remoteEnabled': true,
              'remoteBridgeUrl': 'wss://fixture.example/codex',
              'remoteCwd': '/fixture',
            };
          case 'connect':
            return {'ready': true, 'connected': true, 'runtime': 'remote'};
          case 'session/list':
            return {
              'sessions': [
                {
                  'sessionId': 'selected-computer-session',
                  'title': 'Computer task',
                },
              ],
              'nextCursor': 'second-page',
            };
          default:
            throw StateError('Unexpected call: ${call.method}');
        }
      });
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: AgentSessionsPage(
              embedded: true,
              remoteOnly: true,
              onSessionSelected: (target) => selected = target.agentSessionId,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Computer task'), findsOneWidget);
      await tester.tap(find.text('Computer task'));
      await tester.pumpAndSettle();
      expect(selected, 'selected-computer-session');
      expect(calls.where((x) => x == 'session/list').length, 1);
      expect(calls, isNot(contains('session/prompt')));
      expect(calls, isNot(contains('session/new')));
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'local session list observes completion while staying on the page',
    (tester) async {
      var active = true;
      var reads = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'status')
          return {'ready': true, 'connected': true, 'runtime': 'local'};
        if (call.method == 'session/list') {
          reads++;
          return {
            'sessions': [
              {
                'sessionId': 'test-session',
                'title': active ? 'Still running' : 'Finished now',
                'active': active,
                'loaded': true,
              },
            ],
          };
        }
        return <String, dynamic>{};
      });
      await tester.pumpWidget(page());
      await tester.pumpAndSettle();
      expect(find.text('Still running'), findsOneWidget);
      for (var cycle = 0; cycle < 20; cycle++) {
        active = cycle.isOdd;
        await tester.pump(const Duration(seconds: 3));
        await tester.pumpAndSettle();
        expect(
          find.text(active ? 'Still running' : 'Finished now'),
          findsOneWidget,
        );
        expect(
          find.text(active ? 'Finished now' : 'Still running'),
          findsNothing,
        );
      }
      expect(reads, 21);
      final navigator = tester.state<NavigatorState>(find.byType(Navigator));
      unawaited(
        navigator.push(
          MaterialPageRoute<void>(
            builder: (_) => const Scaffold(body: Text('Other page')),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final hiddenReads = reads;
      await tester.pump(const Duration(seconds: 6));
      expect(reads, hiddenReads);
      navigator.pop();
      await tester.pumpAndSettle();
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      expect(reads, greaterThan(hiddenReads));
      await tester.pumpWidget(const SizedBox());
      final stoppedReads = reads;
      await tester.pump(const Duration(seconds: 6));
      expect(reads, stoppedReads);
    },
  );
  testWidgets('slow list request is never overlapped by periodic refresh', (
    tester,
  ) async {
    final pending = Completer<Map<String, dynamic>>();
    var reads = 0;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'status')
        return {'ready': true, 'connected': true, 'runtime': 'local'};
      if (call.method == 'session/list') {
        reads++;
        if (reads == 1) return {'sessions': []};
        return pending.future;
      }
      return <String, dynamic>{};
    });
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
    }
    expect(reads, 2);
    pending.complete({'sessions': []});
    await tester.pumpAndSettle();
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('remote sessions fetch one page and load more only on request', (
    tester,
  ) async {
    final cursors = <String?>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'status')
        return {'ready': true, 'connected': true, 'runtime': 'remote'};
      if (call.method == 'session/list') {
        final cursor = (call.arguments as Map?)?['cursor'] as String?;
        cursors.add(cursor);
        return cursor == null
            ? {
                'sessions': [
                  {'sessionId': 'first', 'title': 'Recent session'},
                ],
                'nextCursor': 'older',
              }
            : {
                'sessions': [
                  {'sessionId': 'first', 'title': 'Recent session'},
                  {'sessionId': 'second', 'title': 'Older session'},
                ],
              };
      }
      return <String, dynamic>{};
    });
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();
    expect(cursors, [null]);
    await tester.pump(const Duration(seconds: 12));
    await tester.pumpAndSettle();
    expect(cursors, [null]);
    final more = find.byKey(const Key('agent-sessions-load-more'));
    await tester.ensureVisible(more);
    await tester.tap(more);
    await tester.pumpAndSettle();
    expect(cursors, [null, 'older']);
    expect(find.text('Recent session'), findsOneWidget);
    expect(find.text('Older session'), findsOneWidget);
    expect(more, findsNothing);
    await tester.pumpWidget(const SizedBox());
  });
}
