import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_prompt_sheet.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/features/home/pages/chat/widgets/pet_overlay_permission_sheet.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
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
  testWidgets('continues to pet only after overlay permission is granted', (
    tester,
  ) async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    addTearDown(LegacyTextLocalizer.clearResolvedLocale);

    var granted = false;
    var shouldShowPet = false;
    final permission = PermissionData(
      id: 'overlay',
      iconPath: 'assets/welcome/permission_overlay.svg',
      iconWidth: 32,
      iconHeight: 32,
      name: '悬浮窗权限',
      description: '桌面悬浮显示，快速唤起小万',
      onAuthorize: () async {
        granted = true;
      },
      checkAuthorization: () async => granted,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Builder(
            builder: (context) {
              return Scaffold(
                body: TextButton(
                  key: const ValueKey('open-permission-sheet'),
                  onPressed: () async {
                    shouldShowPet = await PetOverlayPermissionSheet.show(
                      context,
                      permission: permission,
                    );
                  },
                  child: const Text('open'),
                ),
              );
            },
          ),
        ),
      ),
    );

    await tester.tap(find.byKey(const ValueKey('open-permission-sheet')));
    await tester.pumpAndSettle();

    final continueButton = find.byKey(
      const ValueKey('pet-overlay-permission-continue-button'),
    );
    expect(find.byType(PermissionPromptSheet), findsOneWidget);
    expect(find.byType(OmniGlassPanel), findsOneWidget);
    expect(find.text('请检查下列权限'), findsOneWidget);
    expect(find.text('悬浮窗权限'), findsOneWidget);
    expect(tester.widget<GestureDetector>(continueButton).onTap, isNull);

    await tester.tap(find.text('悬浮窗权限'));
    await tester.pumpAndSettle();

    expect(permission.notifier.value, isTrue);
    expect(tester.widget<GestureDetector>(continueButton).onTap, isNotNull);

    await tester.tap(continueButton);
    await tester.pumpAndSettle();

    expect(shouldShowPet, isTrue);
  });
}
