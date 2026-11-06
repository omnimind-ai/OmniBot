import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/termux_setting/termux_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => LegacyTextLocalizer.setResolvedLocale(const Locale('zh')));
  tearDown(LegacyTextLocalizer.clearResolvedLocale);

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
  late String nativeDistribution;
  late Map<String, dynamic> initSnapshot;
  FlClashPageActivityController? pageActivity;

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
    pageActivity = null;
    readInventory = () async => {'packages': <String, dynamic>{}};
    nativeDistribution = 'alpine';
    initSnapshot = {};

    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      terminalEventsChannel,
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(terminalChannel, (call) async {
      switch (call.method) {
        case 'getEmbeddedTerminalDistribution':
          return nativeDistribution;
        case 'getEmbeddedTerminalInitSnapshot':
          return initSnapshot;
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
          nativeDistribution =
              (call.arguments as Map)['distribution'] as String;
          return nativeDistribution;
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
    Widget page = const TermuxSettingPage();
    final activity = pageActivity;
    if (activity != null) {
      page = FlClashPageActivityScope(
        controller: activity,
        index: 0,
        isActive: true,
        child: page,
      );
    }
    return MaterialApp(
      locale: const Locale('zh'),
      theme: AppTheme.lightTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: page,
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

  Future<void> emitReady(WidgetTester tester) async {
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      terminalEventsChannel.name,
      const StandardMethodCodec().encodeSuccessEnvelope({
        'kind': 'status',
        'phase': 'ready',
        'distribution': 'ubuntu',
        'message': 'Ubuntu ready',
      }),
      (_) {},
    );
    await tester.pump();
  }

  testWidgets('全局重试仅在原生切换完成后更新当前发行版', (tester) async {
    readInventory = () async => {
      'packages': {
        'nodejs': {'ready': true, 'version': '$nativeDistribution-detected'},
      },
    };
    await pumpTestPage(tester);
    expect(find.text('alpine-detected'), findsOneWidget);

    initSnapshot = {'running': true, 'distribution': 'ubuntu'};
    await emitReady(tester);
    expect(find.text('alpine-detected'), findsOneWidget);
    expect(
      tester
          .widget<OmniSegmentedSlider<EmbeddedTerminalDistribution>>(
            find.byType(OmniSegmentedSlider<EmbeddedTerminalDistribution>),
          )
          .value,
      EmbeddedTerminalDistribution.alpine,
    );

    nativeDistribution = 'ubuntu';
    initSnapshot = {
      'completed': true,
      'success': true,
      'distribution': 'ubuntu',
    };
    await emitReady(tester);
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('ubuntu-detected'), findsOneWidget);
    expect(
      tester
          .widget<OmniSegmentedSlider<EmbeddedTerminalDistribution>>(
            find.byType(OmniSegmentedSlider<EmbeddedTerminalDistribution>),
          )
          .value,
      EmbeddedTerminalDistribution.ubuntu,
    );
  });

  testWidgets('页面自己的切换仍等待方法返回，不被就绪事件提前结束', (tester) async {
    await pumpTestPage(tester);
    await tester.tap(find.text('Ubuntu'));
    await tester.pump();
    initSnapshot = {
      'completed': true,
      'success': true,
      'distribution': 'ubuntu',
    };
    await emitReady(tester);
    expect(find.text('取消下载'), findsOneWidget);
    expect(
      tester
          .widget<OmniSegmentedSlider<EmbeddedTerminalDistribution>>(
            find.byType(OmniSegmentedSlider<EmbeddedTerminalDistribution>),
          )
          .onChanged,
      isNull,
    );
    switchGate.complete();
    await tester.runAsync(() => switchHandlerDone.future);
    await tester.pump();
  });

  testWidgets('Ubuntu 准备期间展示取消入口', (tester) async {
    await pumpTestPage(tester);

    await tester.tap(find.text('Ubuntu'));
    await tester.pump();

    var selector = tester
        .widget<OmniSegmentedSlider<EmbeddedTerminalDistribution>>(
          find.byType(OmniSegmentedSlider<EmbeddedTerminalDistribution>),
        );
    expect(selector.value, EmbeddedTerminalDistribution.ubuntu);
    expect(selector.onChanged, isNull);
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
        .widget<OmniSegmentedSlider<EmbeddedTerminalDistribution>>(
          find.byType(OmniSegmentedSlider<EmbeddedTerminalDistribution>),
        );
    expect(selector.value, EmbeddedTerminalDistribution.alpine);
  });

  for (final fails in [false, true]) {
    testWidgets('切换${fails ? '失败' : '完成'}后检测未结束也退出下载状态', (tester) async {
      final inventory = Completer<Map<String, dynamic>>();
      var inventoryCalls = 0;
      readInventory = () async => inventoryCalls++ == 0
          ? {'packages': <String, dynamic>{}}
          : inventory.future;
      switchShouldFail = fails;
      await pumpTestPage(tester);

      await tester.tap(find.text('Ubuntu'));
      await tester.pump();
      expect(find.text('取消下载'), findsOneWidget);

      switchGate.complete();
      await tester.runAsync(() async {
        await switchHandlerDone.future;
        await Future<void>.delayed(const Duration(milliseconds: 20));
      });
      await tester.pump();

      final selector = tester
          .widget<OmniSegmentedSlider<EmbeddedTerminalDistribution>>(
            find.byType(OmniSegmentedSlider<EmbeddedTerminalDistribution>),
          );
      expect(
        selector.value,
        fails
            ? EmbeddedTerminalDistribution.alpine
            : EmbeddedTerminalDistribution.ubuntu,
      );
      expect(selector.onChanged, isNotNull);
      expect(find.text('取消下载'), findsNothing);
      expect(find.byType(LinearProgressIndicator), findsNothing);
      expect(inventoryCalls, 2);
      expect(inventory.isCompleted, isFalse);

      inventory.complete({
        'packages': {
          'nodejs': {'ready': true, 'version': 'detected-after-switch'},
        },
      });
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.text('detected-after-switch'), findsOneWidget);
    });
  }

  for (final failOld in [false, true]) {
    testWidgets('可在上次切换检测期间再次切换并忽略旧检测${failOld ? '异常' : '成功'}', (tester) async {
      final oldInventory = Completer<Map<String, dynamic>>();
      var inventoryCalls = 0;
      readInventory = () async {
        switch (++inventoryCalls) {
          case 1:
            return {'packages': <String, dynamic>{}};
          case 2:
            return oldInventory.future;
          default:
            return {
              'packages': {
                'nodejs': {'ready': true, 'version': 'alpine-current'},
              },
            };
        }
      };
      await pumpTestPage(tester);
      await tester.tap(find.text('Ubuntu'));
      switchGate.complete();
      await tester.runAsync(() async {
        await switchHandlerDone.future;
        await Future<void>.delayed(const Duration(milliseconds: 20));
      });
      await tester.pump();
      expect(inventoryCalls, 2);

      switchGate = Completer<void>();
      switchHandlerDone = Completer<void>();
      await tester.tap(find.text('Alpine'));
      await tester.pump();
      expect(find.text('取消下载'), findsOneWidget);
      switchGate.complete();
      await tester.runAsync(() async {
        await switchHandlerDone.future;
        await Future<void>.delayed(const Duration(milliseconds: 20));
      });
      await tester.pump();
      expect(find.text('alpine-current'), findsOneWidget);

      if (failOld) {
        oldInventory.completeError(
          PlatformException(code: 'OLD', message: 'ubuntu-stale-error'),
        );
      } else {
        oldInventory.complete({
          'packages': {
            'nodejs': {'ready': false, 'version': 'ubuntu-stale'},
          },
        });
      }
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.text('alpine-current'), findsOneWidget);
      expect(find.text('ubuntu-stale'), findsNothing);
      expect(find.text('ubuntu-stale-error'), findsNothing);
    });
  }

  testWidgets('等待页面恢复的旧检测不会在下一次切换中启动', (tester) async {
    final activity = FlClashPageActivityController(selectedIndex: 0);
    pageActivity = activity;
    addTearDown(activity.dispose);
    var inventoryCalls = 0;
    readInventory = () async {
      inventoryCalls++;
      return {'packages': <String, dynamic>{}};
    };
    await pumpTestPage(tester);
    await tester.tap(find.text('Ubuntu'));
    activity.update(selectedIndex: 0, transitioning: true);
    switchGate.complete();
    await tester.runAsync(() async {
      await switchHandlerDone.future;
      await Future<void>.delayed(const Duration(milliseconds: 20));
    });
    await tester.pump();
    expect(inventoryCalls, 1);

    switchGate = Completer<void>();
    switchHandlerDone = Completer<void>();
    await tester.tap(find.text('Alpine'));
    await tester.pump();
    activity.update(selectedIndex: 0, transitioning: false);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(inventoryCalls, 1);

    switchGate.complete();
    await tester.runAsync(() async {
      await switchHandlerDone.future;
      await Future<void>.delayed(const Duration(milliseconds: 20));
    });
    await tester.pump();
    expect(inventoryCalls, 2);
  });
}
