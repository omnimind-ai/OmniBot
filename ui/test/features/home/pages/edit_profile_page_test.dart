import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/edit_profile/edit_profile_page.dart';

class _PngTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _pngBytes = base64Decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/'
    'x8AAwMCAO2CS1cAAAAASUVORK5CYII=',
  );
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<path d="M15 4l-8 8 8 8" fill="none" stroke="#000"/>'
      '</svg>',
    ),
  );
  static final ByteData _assetManifest = const StandardMessageCodec()
      .encodeMessage(<String, Object?>{
        'assets/avatar/default_avatar1.png': <Object?>[
          <String, Object?>{'asset': 'assets/avatar/default_avatar1.png'},
        ],
        'assets/avatar/default_avatar2.png': <Object?>[
          <String, Object?>{'asset': 'assets/avatar/default_avatar2.png'},
        ],
        'assets/avatar/default_avatar3.png': <Object?>[
          <String, Object?>{'asset': 'assets/avatar/default_avatar3.png'},
        ],
        'assets/avatar/default_avatar4.png': <Object?>[
          <String, Object?>{'asset': 'assets/avatar/default_avatar4.png'},
        ],
        'assets/avatar/default_avatar5.png': <Object?>[
          <String, Object?>{'asset': 'assets/avatar/default_avatar5.png'},
        ],
        'assets/avatar/default_avatar6.png': <Object?>[
          <String, Object?>{'asset': 'assets/avatar/default_avatar6.png'},
        ],
      })!;

  @override
  Future<ByteData> load(String key) async {
    if (key == 'AssetManifest.bin') {
      return _assetManifest;
    }
    return ByteData.sublistView(key.endsWith('.svg') ? _svgBytes : _pngBytes);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Widget buildTestApp() {
    return MaterialApp(
      home: DefaultAssetBundle(
        bundle: _PngTestAssetBundle(),
        child: const EditProfilePage(),
      ),
    );
  }

  CircleAvatar readProfileAvatar(WidgetTester tester) {
    final finder = find.byWidgetPredicate(
      (widget) => widget is CircleAvatar && widget.radius == 60,
    );
    expect(finder, findsOneWidget);
    return tester.widget<CircleAvatar>(finder);
  }

  String readProfileAvatarAsset(WidgetTester tester) {
    final image = readProfileAvatar(tester).backgroundImage;
    expect(image, isA<AssetImage>());
    return (image! as AssetImage).assetName;
  }

  Future<void> pumpProfilePage(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(buildTestApp());
    await tester.pump();
    await tester.pumpAndSettle();
  }

  testWidgets(
    'uses avatar zero and an empty nickname when no profile is saved',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});

      await pumpProfilePage(tester);

      expect(tester.takeException(), isNull);
      expect(
        readProfileAvatarAsset(tester),
        'assets/avatar/default_avatar1.png',
      );

      await tester.tap(find.byType(ElevatedButton));
      await tester.pumpAndSettle();

      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getInt('avatarIndex'), 0);
      expect(prefs.getString('nickname'), '');
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('clamps a negative saved avatar index to the first asset', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues(<String, Object>{
      'avatarIndex': -42,
      'nickname': 'existing user',
    });

    await pumpProfilePage(tester);

    expect(readProfileAvatarAsset(tester), 'assets/avatar/default_avatar1.png');
    expect(tester.takeException(), isNull);
  });

  testWidgets('clamps an oversized saved avatar index to the last asset', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues(<String, Object>{
      'avatarIndex': 9999,
      'nickname': 'existing user',
    });

    await pumpProfilePage(tester);

    expect(readProfileAvatarAsset(tester), 'assets/avatar/default_avatar6.png');
    expect(tester.takeException(), isNull);
  });
}
