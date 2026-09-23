import '../../support/ui_preferences_channel.dart';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/storage_service.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    installUiPreferencesChannelFixture();
  });
  tearDown(clearUiPreferencesChannelFixture);

  testWidgets(
    'native compatibility page returns only when its route is popped',
    (tester) async {
      final router = GoRouter(
        navigatorKey: GoRouterManager.rootNavigatorKey,
        initialLocation: '/home/blank_page',
        routes: [
          GoRoute(
            path: '/home/blank_page',
            builder: (_, _) => const SizedBox(),
          ),
          GoRoute(
            path: '/settings',
            builder: (_, _) => Scaffold(
              body: TextButton(
                onPressed: GoRouterManager.pop,
                child: const Text('Back to native'),
              ),
            ),
          ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(
        ProviderScope(child: MaterialApp.router(routerConfig: router)),
      );
      var returned = false;
      final navigation = GoRouterManager.openLegacyPage('/settings')
          .then((_) => returned = true);
      await tester.pumpAndSettle();
      expect(find.text('Back to native'), findsOneWidget);
      expect(returned, isFalse);
      await tester.tap(find.text('Back to native'));
      await tester.pumpAndSettle();
      await navigation;
      expect(returned, isTrue);
    },
  );
}
