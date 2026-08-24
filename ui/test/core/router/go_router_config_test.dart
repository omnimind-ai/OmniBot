import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_config.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/services/storage_service.dart';

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

  testWidgets('completed onboarding starts in chat', (tester) async {
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
    expect(router!.routeInformationProvider.value.uri.path, '/home/chat');
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
    expect(router!.routeInformationProvider.value.uri.path, '/home/chat');
  });
}
