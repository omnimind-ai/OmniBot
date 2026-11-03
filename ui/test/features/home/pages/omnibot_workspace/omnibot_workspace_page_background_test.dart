import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/omnibot_workspace/omnibot_workspace_page.dart';
import 'package:ui/features/home/pages/omnibot_workspace/widgets/omnibot_workspace_browser.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/widgets/app_background_widgets.dart';

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
    AppBackgroundService.notifier.value = AppBackgroundConfig.defaults;
  });

  testWidgets(
    'workspace page renders shared background layer and translucent bar',
    (tester) async {
      tester.view.physicalSize = const Size(1366, 1024);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final workspacePath = Directory.systemTemp.path;

      AppBackgroundService.notifier.value = const AppBackgroundConfig(
        enabled: true,
        sourceType: AppBackgroundSourceType.local,
        localImagePath: '/tmp/missing-background.png',
        remoteImageUrl: '',
        blurSigma: 6,
        frostOpacity: 0.2,
        brightness: 1,
        focalX: 0,
        focalY: 0,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: DefaultAssetBundle(
            bundle: _SvgTestAssetBundle(),
            child: OmnibotWorkspacePage(workspacePath: workspacePath),
          ),
        ),
      );
      await tester.pump();

      expect(
        find.byKey(const ValueKey('workspace-page-background')),
        findsOneWidget,
      );

      expect(
        tester.getSize(find.byKey(const ValueKey('workspace-page-background'))),
        const Size(1366, 1024),
      );
      final browser = find.byType(OmnibotWorkspaceBrowser);
      expect(tester.getSize(browser).width, 840);
      final browserState = tester.state(browser);
      tester.view.physicalSize = const Size(768, 1024);
      await tester.pump();
      expect(tester.getSize(browser).width, 768);
      expect(tester.state(browser), same(browserState));
      final appBar = tester.widget<AppBar>(find.byType(AppBar));
      expect(
        appBar.backgroundColor,
        backgroundSurfaceColor(translucent: true, opacity: 0.68),
      );
    },
  );
}
