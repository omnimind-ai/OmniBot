import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/termux_setting/termux_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const terminalChannel = MethodChannel(
    'cn.com.omnimind.bot/SpecialPermissionEvent',
  );
  const terminalEventsChannel = MethodChannel(
    'cn.com.omnimind.bot/SpecialPermissionEvents',
  );
  late Directory workspaceDirectory;
  late Completer<void> switchGate;
  late Completer<void> switchHandlerDone;
  late bool switchShouldFail;
  late bool cancelCalled;
  late Future<Map<String, dynamic>> Function() readInventory;

  setUp(() async {
    workspaceDirectory = await Directory.systemTemp.createTemp(
      'omnibot-termux-setting-test-',
    );
    OmnibotResourceService.debugSetWorkspacePaths(
      OmnibotWorkspacePaths(
        rootPath: workspaceDirectory.path,
        shellRootPath: '/workspace',
        internalRootPath: '${workspaceDirectory.path}/.omnibot',
      ),
    );
    switchGate = Completer<void>();
    switchHandlerDone = Completer<void>();
    switchShouldFail = false;
    cancelCalled = false;
    readInventory = () async => {'packages': <String, dynamic>{}};

    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      terminalEventsChannel,
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(terminalChannel, (call) async {
      switch (call.method) {
        case 'getEmbeddedTerminalDistribution':
          return 'alpine';
        case 'getEmbeddedTerminalSetupInventory':
          return readInventory();
        case 'getEmbeddedTerminalAutoStartTasks':
          return <String, dynamic>{'tasks': <dynamic>[]};
        case 'switchEmbeddedTerminalDistribution':
          await switchGate.future;
          if (switchShouldFail) {
            switchHandlerDone.complete();
            throw PlatformException(code: 'DOWNLOAD_FAILED', message: '下载失败');
          }
          switchHandlerDone.complete();
          return 'ubuntu';
        case 'cancelEmbeddedTerminalInit':
          cancelCalled = true;
          return true;
      }
      return null;
    });
  });

  tearDown(() async {
    if (!switchGate.isCompleted) {
      switchGate.complete();
    }
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(terminalChannel, null);
    messenger.setMockMethodCallHandler(terminalEventsChannel, null);
    OmnibotResourceService.debugResetWorkspacePaths();
    await workspaceDirectory.delete(recursive: true);
  });

  Widget buildTestApp() {
    return MaterialApp(
      locale: const Locale('zh'),
      theme: AppTheme.lightTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: const TermuxSettingPage(),
    );
  }

  Future<void> pumpTestPage(WidgetTester tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(800, 1600);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);

    await tester.pumpWidget(buildTestApp());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
  }

  testWidgets('Ubuntu 准备期间展示取消入口', (tester) async {
    await pumpTestPage(tester);

    await tester.tap(find.text('Ubuntu'));
    await tester.pump();

    var selector = tester.widget<SegmentedButton<EmbeddedTerminalDistribution>>(
      find.byType(SegmentedButton<EmbeddedTerminalDistribution>),
    );
    expect(selector.selected, <EmbeddedTerminalDistribution>{
      EmbeddedTerminalDistribution.ubuntu,
    });
    expect(find.text('取消下载'), findsOneWidget);

    await tester.tap(find.text('取消下载'));
    await tester.pump();
    expect(cancelCalled, isTrue);

    switchGate.complete();
    await tester.runAsync(() => switchHandlerDone.future);
    await tester.pump();
  });

  testWidgets('检测异常不显示全部缺失且不勾选安装', (tester) async {
    readInventory = () async =>
        throw PlatformException(code: 'PROBE_FAILED', message: 'probe failed');
    await pumpTestPage(tester);
    expect(find.text('probe failed'), findsOneWidget);
    expect(find.text('检测未完成，请重新检测以确认组件状态。'), findsOneWidget);
    final boxes = tester.widgetList<Checkbox>(find.byType(Checkbox));
    expect(boxes, isNotEmpty);
    expect(
      boxes.every((box) => box.value == false && box.onChanged == null),
      isTrue,
    );
  });

  testWidgets('仅明确缺失项可选，检测失败项不可安装', (tester) async {
    readInventory = () async => {
      'packages': {
        'nodejs': {'ready': false, 'version': null},
        'npm': {'ready': null, 'version': null},
        'git': {'ready': true, 'version': 'git-test'},
      },
    };
    await pumpTestPage(tester);
    final boxes = tester.widgetList<Checkbox>(find.byType(Checkbox)).toList();
    expect(boxes.where((box) => box.value == true).length, 1);
    expect(boxes.where((box) => box.onChanged != null).length, 1);
    expect(find.text('git-test'), findsOneWidget);
  });

  for (final failOld in [false, true]) {
    testWidgets('切换发行版后忽略旧检测${failOld ? '异常' : '成功'}', (tester) async {
      final old = Completer<Map<String, dynamic>>();
      var calls = 0;
      readInventory = () async {
        if (calls++ == 0) return old.future;
        return {
          'packages': {
            'nodejs': {'ready': true, 'version': 'ubuntu-current'},
          },
        };
      };
      await pumpTestPage(tester);
      await tester.tap(find.text('Ubuntu'));
      switchGate.complete();
      await tester.runAsync(() async {
        await switchHandlerDone.future;
        await Future<void>.delayed(const Duration(milliseconds: 20));
      });
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.text('ubuntu-current'), findsOneWidget);
      if (failOld) {
        old.completeError(
          PlatformException(code: 'OLD', message: 'stale-error'),
        );
      } else {
        old.complete({
          'packages': {
            'nodejs': {'ready': true, 'version': 'alpine-stale'},
          },
        });
      }
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.text('ubuntu-current'), findsOneWidget);
      expect(find.text('alpine-stale'), findsNothing);
      expect(find.text('stale-error'), findsNothing);
    });
  }

  testWidgets('失败后刷新可恢复且不保留旧错误', (tester) async {
    readInventory = () async =>
        throw PlatformException(code: 'FAIL', message: 'initial-error');
    await pumpTestPage(tester);
    expect(find.text('initial-error'), findsOneWidget);
    readInventory = () async => {
      'packages': {
        'nodejs': {'ready': true, 'version': 'recovered'},
      },
    };
    final refresh = tester.widget<RefreshIndicator>(
      find.byType(RefreshIndicator),
    );
    await tester.runAsync(refresh.onRefresh);
    await tester.pump();
    expect(find.text('initial-error'), findsNothing);
    expect(find.text('recovered'), findsOneWidget);
  });

  testWidgets('同一发行版重复刷新只采用最后一次请求', (tester) async {
    final old = Completer<Map<String, dynamic>>();
    var calls = 0;
    readInventory = () async => calls++ == 0
        ? old.future
        : {
            'packages': {
              'nodejs': {'ready': true, 'version': 'latest'},
            },
          };
    await pumpTestPage(tester);
    final refresh = tester.widget<RefreshIndicator>(
      find.byType(RefreshIndicator),
    );
    await tester.runAsync(refresh.onRefresh);
    await tester.pump();
    expect(find.text('latest'), findsOneWidget);
    old.complete({
      'packages': {
        'nodejs': {'ready': false, 'version': 'obsolete'},
      },
    });
    await tester.pump();
    expect(find.text('latest'), findsOneWidget);
    expect(find.text('obsolete'), findsNothing);
  });

  testWidgets('Ubuntu 准备失败时回滚原发行版', (tester) async {
    switchShouldFail = true;
    switchGate.complete();
    await pumpTestPage(tester);

    await tester.tap(find.text('Ubuntu'));
    await tester.pump();
    await tester.runAsync(() async {
      await switchHandlerDone.future;
      await Future<void>.delayed(const Duration(milliseconds: 20));
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    final selector = tester
        .widget<SegmentedButton<EmbeddedTerminalDistribution>>(
          find.byType(SegmentedButton<EmbeddedTerminalDistribution>),
        );
    expect(selector.selected, <EmbeddedTerminalDistribution>{
      EmbeddedTerminalDistribution.alpine,
    });
  });
}
