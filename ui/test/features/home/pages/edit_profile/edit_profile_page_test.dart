import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/edit_profile/edit_profile_page.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/omni_theme_palette.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({
      'avatarIndex': 2,
      'nickname': 'Original nickname',
    });
  });

  testWidgets('profile stays scrollable with bounded avatars and pinned save', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1366, 1024);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.darkTheme,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(1.6)),
          child: child!,
        ),
        home: const EditProfilePage(),
      ),
    );
    await tester.pumpAndSettle();
    final state = tester.state(find.byType(EditProfilePage));
    expect(
      tester.widget<Scaffold>(find.byType(Scaffold)).backgroundColor,
      AppTheme.darkTheme.extension<OmniThemePalette>()!.pageBackground,
    );
    await tester.tap(find.byKey(const ValueKey('profile-avatar-5')));
    await tester.pump();
    for (final size in const [
      Size(390, 844),
      Size(600, 900),
      Size(768, 1024),
      Size(1024, 600),
      Size(600, 360),
    ]) {
      tester.view.physicalSize = size;
      await tester.pumpAndSettle();
      expect(tester.state(find.byType(EditProfilePage)), same(state));
      expect(
        tester.getSize(find.byType(GridView)).width,
        lessThanOrEqualTo(360),
      );
      expect(
        tester.getSize(find.byKey(const ValueKey('profile-avatar-5'))).width,
        lessThanOrEqualTo(112),
      );
      final save = find.byKey(const ValueKey('profile-save'));
      expect(save.hitTestable(), findsOneWidget);
      expect(tester.getRect(save).bottom, lessThanOrEqualTo(size.height));
      expect(
        tester
            .widget<CircleAvatar>(
              find.byKey(const ValueKey('profile-selected-avatar')),
            )
            .backgroundImage,
        const AssetImage('assets/avatar/default_avatar6.png'),
      );
      await tester.ensureVisible(
        find.byKey(const ValueKey('profile-avatar-5')),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    }
  });

  testWidgets('profile saves the selected avatar and original nickname', (
    tester,
  ) async {
    Map<String, dynamic>? result;
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () async {
                result = await Navigator.of(context).push<Map<String, dynamic>>(
                  MaterialPageRoute(builder: (_) => const EditProfilePage()),
                );
              },
              child: const Text('Open profile'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open profile'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const ValueKey('profile-avatar-4')));
    await tester.tap(find.byKey(const ValueKey('profile-avatar-4')));
    await tester.tap(find.byKey(const ValueKey('profile-save')));
    await tester.pumpAndSettle();
    final prefs = await SharedPreferences.getInstance();
    expect(prefs.getInt('avatarIndex'), 4);
    expect(prefs.getString('nickname'), 'Original nickname');
    expect(result, {'avatarIndex': 4, 'nickname': 'Original nickname'});
    expect(find.text('Open profile'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
