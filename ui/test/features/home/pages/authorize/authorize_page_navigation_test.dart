import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/features/home/pages/authorize/authorize_page.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_theme.dart';

class _SvgTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<rect width="24" height="24" fill="#000000"/>'
      '</svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async => ByteData.view(_svgBytes.buffer);

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const deviceInfoChannel = MethodChannel('device_info');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(deviceInfoChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
  });

  testWidgets(
    'already granted required permission dismisses authorize page once',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(deviceInfoChannel, (call) async {
            if (call.method == 'getDeviceInfo') {
              return <String, Object?>{'brand': 'other'};
            }
            return null;
          });
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            if (call.method == 'getShizukuStatus') {
              return <String, Object?>{
                'status': 'GRANTED_ADB',
                'backend': 'SHIZUKU',
                'installed': true,
                'running': true,
                'permissionGranted': true,
                'binderReady': true,
                'serviceBound': true,
                'availableActions': <String>[],
                'message': '',
              };
            }
            return true;
          });

      final router = GoRouter(
        initialLocation: '/home/chat',
        routes: <RouteBase>[
          GoRoute(
            path: '/home/chat',
            builder: (context, state) =>
                const Scaffold(body: Center(child: Text('Chat root'))),
          ),
          GoRoute(
            path: '/home/authorize',
            builder: (context, state) => const AuthorizePage(
              args: AuthorizePageArgs(
                requiredPermissionIds: <String>[kAccessibilityPermissionId],
              ),
            ),
          ),
        ],
      );
      addTearDown(router.dispose);

      await tester.pumpWidget(
        MaterialApp.router(
          routerConfig: router,
          theme: AppTheme.lightTheme,
          builder: (context, child) =>
              DefaultAssetBundle(bundle: _SvgTestAssetBundle(), child: child!),
        ),
      );
      await tester.pumpAndSettle();

      unawaited(router.push<bool>('/home/authorize'));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(router.state.uri.path, '/home/chat');
      expect(find.text('Chat root'), findsOneWidget);
    },
  );

  testWidgets(
    'automatically opens a missing task permission and resumes after grant',
    (tester) async {
      tester.view.physicalSize = const Size(1366, 1024);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      var overlayGranted = false;
      var openOverlaySettingsCalls = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(deviceInfoChannel, (call) async {
            if (call.method == 'getDeviceInfo') {
              return <String, Object?>{'brand': 'other'};
            }
            return null;
          });
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            switch (call.method) {
              case 'isOverlayPermission':
                return overlayGranted;
              case 'openOverlaySettings':
                openOverlaySettingsCalls += 1;
                return null;
              case 'getShizukuStatus':
                return <String, Object?>{
                  'status': 'GRANTED_ADB',
                  'backend': 'SHIZUKU',
                  'installed': true,
                  'running': true,
                  'permissionGranted': true,
                  'binderReady': true,
                  'serviceBound': true,
                  'availableActions': <String>[],
                  'message': '',
                };
              default:
                return true;
            }
          });

      final router = GoRouter(
        initialLocation: '/home/chat',
        routes: <RouteBase>[
          GoRoute(
            path: '/home/chat',
            builder: (context, state) =>
                const Scaffold(body: Center(child: Text('Chat root'))),
          ),
          GoRoute(
            path: '/home/authorize',
            builder: (context, state) => const AuthorizePage(
              args: AuthorizePageArgs(
                requiredPermissionIds: <String>[kOverlayPermissionId],
              ),
            ),
          ),
        ],
      );
      addTearDown(router.dispose);

      await tester.pumpWidget(
        MaterialApp.router(
          routerConfig: router,
          theme: AppTheme.lightTheme,
          builder: (context, child) =>
              DefaultAssetBundle(bundle: _SvgTestAssetBundle(), child: child!),
        ),
      );
      await tester.pumpAndSettle();

      unawaited(router.push<bool>('/home/authorize'));
      await tester.pumpAndSettle();
      expect(openOverlaySettingsCalls, 1);
      expect(router.state.uri.path, '/home/authorize');
      expect(
        tester
            .getSize(find.byKey(const ValueKey('authorize-permission-scroll')))
            .width,
        840,
      );
      final continueButton = find.byKey(const ValueKey('authorize-continue'));
      expect(tester.getSize(continueButton).width, 796);
      final pageState = tester.state(find.byType(AuthorizePage));
      tester.view.physicalSize = const Size(600, 900);
      await tester.pumpAndSettle();
      expect(
        tester
            .getSize(find.byKey(const ValueKey('authorize-permission-scroll')))
            .width,
        600,
      );
      expect(tester.getSize(continueButton).width, 556);
      expect(tester.state(find.byType(AuthorizePage)), same(pageState));
      expect(openOverlaySettingsCalls, 1);

      overlayGranted = true;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(router.state.uri.path, '/home/chat');
    },
  );
}
