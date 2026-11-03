import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_config.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    GoRouterManager.setInitialRoute(null);
    GoRouterManager.setSubEngine(false);
  });

  tearDown(() {
    GoRouterManager.setInitialRoute(null);
    GoRouterManager.setSubEngine(false);
  });

  test('router config includes the main application routes', () {
    final routeNames = AppRouterConfig.getAllRoutes()
        .map((route) => route.name)
        .whereType<String>()
        .toSet();

    expect(routeNames, contains('home/chat'));
    expect(routeNames, contains('home/home'));
    expect(routeNames, contains('welcome/choice'));
    expect(routeNames, contains('task/omniflow'));
    expect(routeNames, contains('task/run_logs'));
    expect(routeNames, contains('home/first_use_tutorial/setup'));
    expect(routeNames, isNot(contains('home/first_use_tutorial')));
    expect(routeNames, isNot(contains('home/first_use_tutorial/features')));
    expect(routeNames, isNot(contains('home/first_use_tutorial/plugins')));
  });

  testWidgets('fresh launch starts in the first-use tutorial', (tester) async {
    GoRouter? router;
    addTearDown(() {
      router?.dispose();
    });

    await tester.pumpWidget(
      ProviderScope(
        child: Consumer(
          builder: (context, ref, child) {
            router ??= GoRouterManager.createRouter(ref);
            return const SizedBox.shrink();
          },
        ),
      ),
    );

    expect(router, isNotNull);
    expect(router!.routeInformationProvider.value.uri.path, '/welcome/choice');
  });

  testWidgets('completed onboarding starts in the conversation list', (
    tester,
  ) async {
    await StorageService.setBool(StorageKeys.welcomeCompleted, true);
    GoRouter? router;
    addTearDown(() {
      router?.dispose();
    });

    await tester.pumpWidget(
      ProviderScope(
        child: Consumer(
          builder: (context, ref, child) {
            router ??= GoRouterManager.createRouter(ref);
            return const SizedBox.shrink();
          },
        ),
      ),
    );

    expect(router, isNotNull);
    expect(router!.routeInformationProvider.value.uri.path, '/home/home');
  });

  testWidgets('sub-engine launch bypasses the onboarding guard', (
    tester,
  ) async {
    GoRouterManager.setSubEngine(true);
    GoRouter? router;
    addTearDown(() {
      router?.dispose();
    });

    await tester.pumpWidget(
      ProviderScope(
        child: Consumer(
          builder: (context, ref, child) {
            router ??= GoRouterManager.createRouter(ref);
            return const SizedBox.shrink();
          },
        ),
      ),
    );

    expect(router, isNotNull);
    expect(router!.routeInformationProvider.value.uri.path, '/home/home');
  });

  testWidgets('explicit native initial route preserves its chat target', (
    tester,
  ) async {
    await StorageService.setBool(StorageKeys.welcomeCompleted, true);
    const destination =
        '/home/chat?conversationId=42&mode=agent&agentId=codex&requestKey=native';
    GoRouterManager.setInitialRoute(destination);
    GoRouter? router;
    addTearDown(() => router?.dispose());

    await tester.pumpWidget(
      ProviderScope(
        child: Consumer(
          builder: (context, ref, child) {
            router ??= GoRouterManager.createRouter(ref);
            return const SizedBox.shrink();
          },
        ),
      ),
    );

    expect(router!.routeInformationProvider.value.uri.toString(), destination);
  });

  test(
    'ordinary home remains home and legacy thread targets resolve to chat',
    () {
      expect(GoRouterManager.resolveHomeRoute('/home/home'), '/home/home');
      expect(
        GoRouterManager.resolveHomeRoute(
          '/home/home?conversationId=42&mode=agent&agentId=codex',
        ),
        '/home/chat?conversationId=42&mode=agent&agentId=codex',
      );
      expect(
        GoRouterManager.resolveHomeRoute(
          '/home/home',
          extra: const ConversationThreadTarget.newConversation(
            mode: ConversationMode.chatOnly,
            requestKey: 'new-thread',
          ),
        ),
        '/home/chat',
      );
      expect(
        GoRouterManager.resolveHomeRoute(
          '/home/home',
          extra: const <String>['42', '__from_native__', 'mode=agent'],
        ),
        '/home/chat',
      );
      expect(
        GoRouterManager.resolveHomeRoute(
          '/home/home',
          extra: const <String, Object>{'_routeOptions': <String, Object>{}},
        ),
        '/home/home',
      );
    },
  );

  for (final coveredBySettings in <bool>[false, true]) {
    testWidgets('home pushes one chat owner and switching retains it'
        '${coveredBySettings ? ' beneath settings' : ''}', (tester) async {
      var createdChatOwners = 0;
      final router = GoRouter(
        navigatorKey: GoRouterManager.rootNavigatorKey,
        initialLocation: GoRouterManager.homeRoute,
        routes: <GoRoute>[
          GoRoute(
            path: GoRouterManager.homeRoute,
            name: 'home/home',
            pageBuilder: (context, state) =>
                GoRouterManager.buildPage(
                  key: state.pageKey,
                  name: state.name,
                  child: const Scaffold(body: Text('Conversation list')),
                ),
          ),
          GoRoute(
            path: GoRouterManager.chatRoute,
            name: 'home/chat',
            pageBuilder: (context, state) =>
                GoRouterManager.buildPage(
                  key: state.pageKey,
                  name: state.name,
                  child: _ChatOwnerProbe(
                    target: state.uri.queryParameters['conversationId'] ?? '',
                    onCreate: () => createdChatOwners++,
                  ),
                ),
          ),
          GoRoute(
            path: '/home/settings',
            name: 'home/settings',
            builder: (context, state) => const Scaffold(body: Text('Settings')),
          ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(MaterialApp.router(routerConfig: router));
      await tester.pumpAndSettle();

      GoRouterManager.push(
        GoRouterManager.chatRoute,
        queryParams: const <String, String>{'conversationId': '41'},
      );
      await tester.pumpAndSettle();
      expect(createdChatOwners, 1);
      expect(find.text('Chat 41'), findsOneWidget);
      expect(router.canPop(), isTrue);
      final chatPageKey =
          router.routerDelegate.currentConfiguration.matches.last.pageKey;

      if (coveredBySettings) {
        GoRouterManager.push('/home/settings');
        await tester.pumpAndSettle();
        expect(find.text('Settings'), findsOneWidget);
      }

      GoRouterManager.push(
        '/home/home',
        queryParams: const <String, String>{'conversationId': '42'},
      );
      await tester.pumpAndSettle();
      expect(createdChatOwners, 1);
      expect(find.text('Chat 42'), findsOneWidget);
      expect(
        router.routerDelegate.currentConfiguration.matches.last.pageKey,
        chatPageKey,
      );
      expect(router.routerDelegate.currentConfiguration.matches, hasLength(2));

      GoRouterManager.pop();
      await tester.pumpAndSettle();
      expect(find.text('Conversation list'), findsOneWidget);
      expect(router.canPop(), isFalse);

      if (!coveredBySettings) {
        GoRouterManager.resetToHomeAndPush(
          GoRouterManager.chatRoute,
          queryParams: const <String, String>{
            'conversationId': '43',
            'requestKey': 'native-return',
          },
          options: const RouteOptions(noAnim: true),
        );
        await tester.pumpAndSettle();
        expect(find.text('Chat 43'), findsOneWidget);
        expect(createdChatOwners, 2);
        expect(router.routerDelegate.currentConfiguration.matches, hasLength(2));
        GoRouterManager.pop();
        await tester.pumpAndSettle();
        expect(find.text('Conversation list'), findsOneWidget);
        expect(router.canPop(), isFalse);
      }
    });
  }
}

class _ChatOwnerProbe extends StatefulWidget {
  const _ChatOwnerProbe({required this.target, required this.onCreate});

  final String target;
  final VoidCallback onCreate;

  @override
  State<_ChatOwnerProbe> createState() => _ChatOwnerProbeState();
}

class _ChatOwnerProbeState extends State<_ChatOwnerProbe> {
  @override
  void initState() {
    super.initState();
    widget.onCreate();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(body: Text('Chat ${widget.target}'));
  }
}
