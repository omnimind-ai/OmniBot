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
  late bool retryInitCalled;
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
    retryInitCalled = false;
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
        case 'retryEmbeddedTerminalInit':
          retryInitCalled = true;
          return <String, dynamic>{
            'success': true,
            'wrapperReady': true,
            'sharedStorageReady': true,
            'message': '内嵌终端环境已就绪。',
          };
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

  Widget buildTestApp({Locale locale = const Locale('zh')}) {
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
      locale: locale,
      theme: AppTheme.lightTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: page,
    );
  }

  Future<void> pumpTestPage(
    WidgetTester tester, {
    Locale locale = const Locale('zh'),
  }) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(800, 1600);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);

    await tester.pumpWidget(buildTestApp(locale: locale));
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

  testWidgets('英文环境下本地化原生终端报错并可通过修复环境按钮恢复', (tester) async {
    readInventory = () async => throw PlatformException(
      code: 'READ_SETUP_INVENTORY_FAILED',
      message: '环境检测失败，无法确认组件状态：Failed to extract alpine rootfs.',
    );

    await pumpTestPage(tester, locale: const Locale('en'));

    expect(
      find.text(
        'Environment check failed; unable to confirm component status: Failed to extract alpine rootfs.',
      ),
      findsOneWidget,
    );
    expect(find.text('Repair environment'), findsOneWidget);

    readInventory = () async => {
      'packages': {
        'nodejs': {'ready': true, 'version': 'v22.14.0'},
      },
    };
    await tester.tap(
      find.byKey(const ValueKey('terminal-environment-repair-button')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(retryInitCalled, isTrue);
    expect(find.text('v22.14.0'), findsOneWidget);
    expect(find.text('Repair environment'), findsNothing);
  });

  testWidgets('pt-BR 环境下完整展示葡语界面与错误提示', (tester) async {
    readInventory = () async => throw PlatformException(
      code: 'READ_SETUP_INVENTORY_FAILED',
      message: '环境检测失败，无法确认组件状态：Failed to extract alpine rootfs.',
    );

    await pumpTestPage(tester, locale: const Locale('pt', 'BR'));

    expect(find.text('Ambiente de Terminal'), findsOneWidget);
    expect(find.text('Sistema do terminal'), findsOneWidget);
    expect(
      find.text(
        'Escolha o sistema Linux compartilhado pelo terminal e pelo Agent.',
      ),
      findsOneWidget,
    );
    expect(find.text('Configuração do ambiente'), findsOneWidget);
    expect(
      find.text(
        'Falha na verificação do ambiente; não foi possível confirmar o status dos componentes: Failed to extract alpine rootfs.',
      ),
      findsOneWidget,
    );
    expect(find.text('Reparar ambiente'), findsOneWidget);
  });

  testWidgets('启动基础包安装失败时即使清单探测返回 0 项也展示失败原因与修复环境入口', (tester) async {
    initSnapshot = {
      'completed': true,
      'success': false,
      'distribution': 'alpine',
      'error': '内嵌终端环境已初始化，但基础 Agent CLI 包安装失败：403 Forbidden',
    };
    readInventory = () async => {'packages': <String, dynamic>{}};

    await pumpTestPage(tester, locale: const Locale('en'));

    expect(
      find.text(
        'Embedded terminal initialized, but installing base Agent CLI packages failed: 403 Forbidden',
      ),
      findsOneWidget,
    );
    expect(find.text('Repair environment'), findsOneWidget);

    readInventory = () async => {
      'packages': {
        'nodejs': {'ready': true, 'version': 'v22.14.0'},
      },
    };
    initSnapshot = {
      'completed': true,
      'success': true,
      'distribution': 'alpine',
    };
    await tester.tap(
      find.byKey(const ValueKey('terminal-environment-repair-button')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(retryInitCalled, isTrue);
    expect(find.text('Repair environment'), findsNothing);
    expect(find.text('v22.14.0'), findsOneWidget);
  });
}
