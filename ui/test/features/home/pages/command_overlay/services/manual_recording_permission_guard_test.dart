import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_prompt_sheet.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_permission_guard.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/widgets/omni_glass.dart';

class _SvgTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<rect width="24" height="24" fill="#000000"/>'
      '</svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async {
    return ByteData.view(_svgBytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
  });

  test('requires both accessibility and overlay permissions', () async {
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (call) async {
          calls.add(call.method);
          return call.method == 'isAndroidGuiAccessibilityReady';
        });

    final check = await ManualRecordingPermissionGuard.check();

    expect(check.accessibilityReady, isTrue);
    expect(check.overlayGranted, isFalse);
    expect(check.isAuthorized, isFalse);
    expect(calls, <String>[
      'isAndroidGuiAccessibilityReady',
      'isOverlayPermission',
    ]);
  });

  testWidgets('uses the shared pet-style card for recording permissions', (
    tester,
  ) async {
    var accessibilityGranted = false;
    var overlayGranted = false;
    var authorized = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (call) async {
          switch (call.method) {
            case 'isAndroidGuiAccessibilityReady':
              return accessibilityGranted;
            case 'isOverlayPermission':
              return overlayGranted;
            case 'openAndroidGuiAccessibilitySettings':
              accessibilityGranted = true;
              return null;
            case 'openOverlaySettings':
              overlayGranted = true;
              return null;
          }
          return null;
        });

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const <Locale>[Locale('zh')],
        localizationsDelegates: const <LocalizationsDelegate<dynamic>>[
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                key: const ValueKey('start-manual-recording'),
                onPressed: () async {
                  authorized =
                      await ManualRecordingPermissionGuard.ensureAuthorized(
                        context,
                      );
                },
                child: const Text('start'),
              ),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.byKey(const ValueKey('start-manual-recording')));
    await tester.pumpAndSettle();

    final continueButton = find.byKey(
      const ValueKey('manual-recording-permission-continue-button'),
    );
    expect(find.byType(PermissionPromptSheet), findsOneWidget);
    expect(find.byType(OmniGlassPanel), findsOneWidget);
    expect(find.text('请检查下列权限'), findsOneWidget);
    expect(find.text('无障碍权限'), findsOneWidget);
    expect(find.text('悬浮窗权限'), findsOneWidget);
    expect(tester.widget<GestureDetector>(continueButton).onTap, isNull);

    await tester.tap(find.text('无障碍权限'));
    await tester.pumpAndSettle();
    expect(tester.widget<GestureDetector>(continueButton).onTap, isNull);

    await tester.tap(find.text('悬浮窗权限'));
    await tester.pumpAndSettle();
    expect(tester.widget<GestureDetector>(continueButton).onTap, isNotNull);

    await tester.tap(continueButton);
    await tester.pumpAndSettle();
    expect(authorized, isTrue);
  });
}
