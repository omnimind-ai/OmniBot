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
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  Widget page() => MaterialApp(
    theme: AppTheme.lightTheme,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: const AgentSessionsPage(),
  );
  setUp(() { messenger.setMockMethodCallHandler(events, (_) async => null); });
  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    messenger.setMockMethodCallHandler(events, null);
  });
  testWidgets('local session list observes completion while staying on the page', (tester) async {
    var active = true;
    var reads = 0;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'status') return {'ready':true,'connected':true,'runtime':'local'};
      if (call.method == 'session/list') {
        reads++;
        return {'sessions':[{'sessionId':'test-session','title':active ? 'Still running' : 'Finished now','active':active,'loaded':true}]};
      }
      return <String,dynamic>{};
    });
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();
    expect(find.text('Still running'), findsOneWidget);
    for (var cycle = 0; cycle < 20; cycle++) {
      active = cycle.isOdd;
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      expect(find.text(active ? 'Still running' : 'Finished now'), findsOneWidget);
      expect(find.text(active ? 'Finished now' : 'Still running'), findsNothing);
    }
    expect(reads, 21);
    final navigator = tester.state<NavigatorState>(find.byType(Navigator));
    unawaited(navigator.push(MaterialPageRoute<void>(builder: (_) => const Scaffold(body: Text('Other page')))));
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
  });
  testWidgets('slow list request is never overlapped by periodic refresh', (tester) async {
    final pending = Completer<Map<String,dynamic>>();
    var reads = 0;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'status') return {'ready':true,'connected':true,'runtime':'remote'};
      if (call.method == 'session/list') {
        reads++;
        if (reads == 1) return {'sessions':[]};
        return pending.future;
      }
      return <String,dynamic>{};
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
    pending.complete({'sessions':[]});
    await tester.pumpAndSettle();
    await tester.pumpWidget(const SizedBox());
  });
}
