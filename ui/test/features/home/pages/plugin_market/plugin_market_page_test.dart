import 'dart:convert';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/plugin_market/plugin_detail_page.dart';
import 'package:ui/features/home/pages/plugin_market/plugin_market_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/models/omni_plugin_item.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/predictive_back_gesture_wrapper.dart';

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

class _PredictivePluginDetailRoute extends PageRouteBuilder<bool> {
  _PredictivePluginDetailRoute({required OmniPluginItem plugin})
    : super(
        transitionDuration: const Duration(milliseconds: 300),
        reverseTransitionDuration: const Duration(milliseconds: 300),
        pageBuilder: (context, animation, secondaryAnimation) =>
            PluginDetailPage(pluginId: plugin.id, initialPlugin: plugin),
        transitionsBuilder: (context, animation, secondaryAnimation, child) {
          return PredictiveBackGestureWrapper(
            animation: animation,
            secondaryAnimation: secondaryAnimation,
            transitionBuilder:
                (context, animation, secondaryAnimation, child) =>
                    CupertinoPageTransition(
                      primaryRouteAnimation: animation,
                      secondaryRouteAnimation: secondaryAnimation,
                      linearTransition: false,
                      child: child,
                    ),
            child: child,
          );
        },
      );
}

Future<void> _sendBackGesture(
  WidgetTester tester,
  String method, [
  Map<String, dynamic>? arguments,
]) async {
  final message = const StandardMethodCodec().encodeMethodCall(
    MethodCall(method, arguments),
  );
  await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
    'flutter/backgesture',
    message,
    (ByteData? _) {},
  );
}

Widget _app({Locale locale = const Locale('zh')}) {
  final router = GoRouter(
    initialLocation: '/home/plugin_market',
    routes: [
      GoRoute(
        path: '/home/plugin_market',
        builder: (context, state) => const PluginMarketPage(),
        routes: [
          GoRoute(
            path: ':pluginId',
            builder: (context, state) => PluginDetailPage(
              pluginId: state.pathParameters['pluginId']!,
              initialPlugin: state.extra as OmniPluginItem?,
            ),
          ),
        ],
      ),
      GoRoute(
        path: '/task/omniflow',
        builder: (context, state) =>
            const Scaffold(body: Center(child: Text('Execution center route'))),
      ),
      GoRoute(
        path: '/home/chat',
        builder: (context, state) =>
            const Scaffold(body: Center(child: Text('Chat route'))),
      ),
    ],
  );
  return MaterialApp.router(
    routerConfig: router,
    locale: locale,
    theme: AppTheme.lightTheme,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    builder: (context, child) =>
        DefaultAssetBundle(bundle: _SvgTestAssetBundle(), child: child!),
  );
}

Map<String, Object?> _runtimePlugin() => <String, Object?>{
  'id': 'com.omnimind.omni-vlm-lite',
  'name': 'OmniFlow',
  'version': '1.0.0',
  'interfaceVersion': 1,
  'description': '内置的视觉操作能力',
  'publisher': 'OmniMind',
  'kind': 'runtime_bundle',
  'downloadSizeBytes': 0,
  'capabilities': <String>[
    'Manual recording',
    'Canonical RunLog',
    'RunLog to Function',
    'Function recall and parameter binding',
    'Function replay with OmniTransfer',
    'Mobilerun Manager and Executor',
    'OmniFlow Python runtime',
  ],
  'settingsSchema': <String, Object?>{},
  'presentation': <String, Object?>{
    'description': <String, Object?>{
      'zh':
          '小万原生手机操作能力随 APK 提供，无需安装插件即可在线点击、滑动和输入。安装本插件后，还可以录下操作过程、查看每一步，并把成功流程保存下来，在相似任务中自动复用。',
      'en':
          "XiaoWan's native phone controls ship with the APK, so online taps, swipes, and text input work without this plugin. Install the plugin to record actions, inspect every step, and save successful flows for automatic reuse in similar tasks.",
    },
    'readiness': 'vlm_provider',
    'usage': <Object?>[
      <String, Object?>{
        'icon': 'power',
        'title': <String, Object?>{
          'zh': '首次启动自动准备',
          'en': 'Prepared automatically on first launch',
        },
        'description': <String, Object?>{
          'zh': '安装后会自动准备 OmniFlow 运行环境，失败时可以重试。',
          'en':
              'The OmniFlow runtime is prepared automatically after installation; retry is available if preparation fails.',
        },
      },
      <String, Object?>{
        'icon': 'touch',
        'title': <String, Object?>{
          'zh': '基础操作开箱即用',
          'en': 'Phone controls work out of the box',
        },
        'description': <String, Object?>{
          'zh': '打开无障碍权限后，即使不安装插件，也可以直接让小万在线操作手机。',
          'en':
              "The APK uses XiaoWan's accessibility runtime directly for Kotlin online vlm_task.",
        },
      },
    ],
    'ready': <String, Object?>{
      'key': 'omniflow-ready-guide',
      'title': <String, Object?>{
        'zh': 'OmniFlow 自动化增强已启用',
        'en': 'OmniFlow automation is enabled',
      },
      'steps': <Object?>[
        <String, Object?>{
          'zh': '在线执行：回到聊天，直接说“打开蓝牙”或“新建联系人”。',
          'en':
              'Online: return to chat and ask “Turn on Bluetooth” or “Create a contact”.',
        },
      ],
      'actions': <Object?>[
        <String, Object?>{
          'route': '/home/chat',
          'navigation': 'go',
          'icon': 'chat',
          'requiresReadiness': true,
          'label': <String, Object?>{'zh': '去聊天试用', 'en': 'Try in chat'},
        },
        <String, Object?>{
          'route': '/task/omniflow',
          'navigation': 'push',
          'icon': 'route',
          'requiresReadiness': false,
          'label': <String, Object?>{
            'zh': '查看已保存操作',
            'en': 'View saved actions',
          },
        },
      ],
    },
    'installedAction': <String, Object?>{
      'route': '/task/omniflow',
      'navigation': 'push',
      'icon': 'route',
      'label': <String, Object?>{
        'zh': '已保存操作与执行记录',
        'en': 'Saved actions & history',
      },
    },
    'capabilityLabels': <String, Object?>{
      'Manual recording': <String, Object?>{
        'zh': '录下你的手机操作',
        'en': 'Record your phone actions',
      },
      'Canonical RunLog': <String, Object?>{
        'zh': '查看任务执行的每一步',
        'en': 'Review every step of a task',
      },
      'RunLog to Function': <String, Object?>{
        'zh': '把成功操作保存下来重复使用',
        'en': 'Save successful actions for reuse',
      },
      'Function recall and parameter binding': <String, Object?>{
        'zh': '相似任务自动找到流程并替换新内容',
        'en': 'Find a matching flow and substitute new details',
      },
      'Function replay with OmniTransfer': <String, Object?>{
        'zh': '换一台手机也能找到对应按钮并执行',
        'en': 'Adapt saved actions to another phone',
      },
      'Mobilerun Manager and Executor': <String, Object?>{
        'zh': '管理并执行已保存的手机操作',
        'en': 'Manage and run saved phone actions',
      },
      'OmniFlow Python runtime': <String, Object?>{
        'zh': '需要高级能力时自动准备运行环境',
        'en': 'Prepare advanced automation only when needed',
      },
    },
  },
  'installed': false,
  'enabled': false,
  'compatible': true,
  'required': true,
  'installByDefault': true,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/PluginPlatform');
  final calls = <MethodCall>[];
  var plugins = <Map<String, Object?>>[];

  setUp(() {
    calls.clear();
    plugins = <Map<String, Object?>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'list':
              return plugins;
            case 'install':
              final installed = <String, Object?>{
                ...plugins.single,
                'installed': true,
                'enabled': true,
              };
              plugins = <Map<String, Object?>>[installed];
              return installed;
            case 'update':
              final updated = <String, Object?>{
                ...plugins.single,
                'version': '2.0.0',
              };
              plugins = <Map<String, Object?>>[updated];
              return updated;
            case 'setEnabled':
              final arguments = Map<Object?, Object?>.from(
                call.arguments as Map,
              );
              final updated = <String, Object?>{
                ...plugins.single,
                'enabled': arguments['enabled'] == true,
              };
              plugins = <Map<String, Object?>>[updated];
              return updated;
            case 'getVlmReadiness':
              return <String, Object?>{
                'debugBuild': true,
                'providerConfigured': true,
                'providerName': 'OmniMind GPT Luna (Debug)',
                'model': 'gpt-5.6-sol',
              };
            case 'uninstall':
              plugins = <Map<String, Object?>>[
                <String, Object?>{
                  ...plugins.single,
                  'installed': false,
                  'enabled': false,
                },
              ];
              return true;
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('shows official empty state when catalog has no plugins', (
    tester,
  ) async {
    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();

    expect(find.text('插件市场'), findsOneWidget);
    expect(find.text('暂无可用插件'), findsOneWidget);
    expect(find.text('官方插件接入后会显示在这里'), findsOneWidget);
  });

  testWidgets('opens a listed runtime plugin in a separate detail page', (
    tester,
  ) async {
    plugins = <Map<String, Object?>>[_runtimePlugin()];

    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();

    expect(find.text('OmniFlow'), findsOneWidget);
    expect(find.textContaining('运行时包'), findsOneWidget);
    expect(find.byType(Card), findsNothing);
    expect(find.text('安装'), findsNothing);

    await tester.tap(find.text('OmniFlow'));
    await tester.pumpAndSettle();

    expect(find.text('插件详情'), findsOneWidget);
    expect(find.textContaining('小万原生手机操作能力随 APK 提供'), findsOneWidget);
    expect(find.text('核心功能'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('录下你的手机操作'),
      120,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('录下你的手机操作'), findsOneWidget);
    expect(find.text('把成功操作保存下来重复使用'), findsOneWidget);
    expect(find.text('下载大小'), findsNothing);
    await tester.scrollUntilVisible(
      find.text('工作方式'),
      240,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('工作方式'), findsOneWidget);
    expect(find.textContaining('自动准备 OmniFlow 运行环境'), findsOneWidget);
    expect(find.textContaining('即使不安装插件，也可以直接让小万在线操作手机'), findsOneWidget);
    expect(find.text('安装'), findsOneWidget);
  });

  testWidgets('lists plugins while hiding internal runtimes', (tester) async {
    plugins = <Map<String, Object?>>[
      <String, Object?>{
        ..._runtimePlugin(),
        'id': 'com.omnimind.vibe-project-builder',
        'name': 'Vibe Builder',
      },
      <String, Object?>{
        ..._runtimePlugin(),
        'id': 'com.omnimind.internal-runtime',
        'name': 'Internal Runtime',
        'presentation': <String, Object?>{'visibility': 'hidden'},
      },
      <String, Object?>{
        ..._runtimePlugin(),
        'id': 'local.project.fitness-beast',
        'name': '健身兽',
        'installed': true,
        'enabled': true,
      },
    ];

    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();

    final vibeBuilder = find.ancestor(
      of: find.text('Vibe Builder'),
      matching: find.byType(InkWell),
    );
    expect(vibeBuilder, findsOneWidget);
    expect(
      find.descendant(of: vibeBuilder, matching: find.text('未安装')),
      findsOneWidget,
    );
    expect(find.text('Internal Runtime'), findsNothing);
    expect(find.text('健身兽'), findsOneWidget);
    expect(find.byIcon(Icons.dashboard_outlined), findsNothing);
  });
  testWidgets(
    'plugin detail allows predictive back to drive its route',
    (tester) async {
      plugins = <Map<String, Object?>>[_runtimePlugin()];
      SharedPreferences.setMockInitialValues(<String, Object>{});
      await StorageService.init();
      await StorageService.setPredictiveBackEnabled(true);

      tester.view.physicalSize = const Size(1080, 2200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          builder: (context, child) =>
              DefaultAssetBundle(bundle: _SvgTestAssetBundle(), child: child!),
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => Navigator.of(context).push(
                  _PredictivePluginDetailRoute(
                    plugin: OmniPluginItem.fromMap(plugins.single),
                  ),
                ),
                child: const Text('open plugin detail'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open plugin detail'));
      await tester.pumpAndSettle();

      final route = ModalRoute.of(
        tester.element(find.byType(PluginDetailPage)),
      )!;
      expect(route.popGestureEnabled, isTrue);

      await _sendBackGesture(tester, 'startBackGesture', <String, dynamic>{
        'touchOffset': <double>[0.0, 300.0],
        'progress': 0.0,
        'swipeEdge': 0,
      });
      await tester.pump();

      expect(route.popGestureInProgress, isTrue);

      await _sendBackGesture(tester, 'cancelBackGesture');
      await tester.pumpAndSettle();
      expect(find.byType(PluginDetailPage), findsOneWidget);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets('system back refreshes the plugin catalog', (tester) async {
    plugins = <Map<String, Object?>>[_runtimePlugin()];

    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();
    await tester.tap(find.text('OmniFlow'));
    await tester.pumpAndSettle();
    final listCallsBeforeBack = calls
        .where((call) => call.method == 'list')
        .length;

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(find.text('插件市场'), findsOneWidget);
    expect(
      calls.where((call) => call.method == 'list').length,
      greaterThan(listCallsBeforeBack),
    );
  });

  testWidgets('localizes the OmniFlow description in English', (tester) async {
    plugins = <Map<String, Object?>>[_runtimePlugin()];

    await tester.pumpWidget(_app(locale: const Locale('en')));
    await tester.pumpAndSettle();

    expect(
      find.textContaining("XiaoWan's native phone controls ship with the APK"),
      findsOneWidget,
    );
    expect(find.textContaining('小万原生手机操作能力随 APK 提供'), findsNothing);

    await tester.tap(find.text('OmniFlow'));
    await tester.pumpAndSettle();

    expect(
      find.textContaining("XiaoWan's native phone controls ship with the APK"),
      findsOneWidget,
    );
    expect(find.textContaining('小万原生手机操作能力随 APK 提供'), findsNothing);
  });

  testWidgets('renders a second runtime plugin from presentation', (
    tester,
  ) async {
    plugins = <Map<String, Object?>>[
      <String, Object?>{
        ..._runtimePlugin(),
        'id': 'com.omnimind.sample-runtime',
        'name': 'Sample Runtime',
        'installed': true,
        'enabled': true,
        'presentation': <String, Object?>{
          'description': <String, Object?>{
            'zh': '来自插件 manifest 的示例说明',
            'en': 'Sample description from the plugin manifest',
          },
          'usage': <Object?>[
            <String, Object?>{
              'icon': 'layers',
              'title': <String, Object?>{
                'zh': '独立扩展',
                'en': 'Independent extension',
              },
              'description': <String, Object?>{
                'zh': '无需修改插件市场页面',
                'en': 'No plugin market changes required',
              },
            },
          ],
          'ready': <String, Object?>{
            'title': <String, Object?>{
              'zh': '示例 Runtime 已就绪',
              'en': 'Sample Runtime is ready',
            },
          },
        },
      },
    ];

    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();
    expect(find.text('来自插件 manifest 的示例说明'), findsOneWidget);
    expect(calls.any((call) => call.method == 'getVlmReadiness'), isFalse);
  });

  testWidgets('install atomically enables a plugin from its detail page', (
    tester,
  ) async {
    plugins = <Map<String, Object?>>[_runtimePlugin()];

    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();
    await tester.tap(find.text('OmniFlow'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('安装'));
    await tester.pumpAndSettle();

    await tester.scrollUntilVisible(
      find.byKey(const Key('omniflow-ready-guide')),
      240,
      scrollable: find.byType(Scrollable).first,
    );

    expect(calls.any((call) => call.method == 'install'), isTrue);
    expect(find.text('卸载'), findsNothing);
    expect(find.byType(Switch), findsNothing);
    expect(calls.any((call) => call.method == 'setEnabled'), isFalse);
    expect(find.text('OmniFlow 自动化增强已启用'), findsOneWidget);
    expect(find.textContaining('OmniMind GPT Luna (Debug)'), findsOneWidget);
    expect(find.text('去聊天试用'), findsOneWidget);
    expect(find.text('查看已保存操作'), findsOneWidget);
    expect(find.text('已保存操作与执行记录'), findsOneWidget);
    await tester.tap(find.text('已保存操作与执行记录'));
    await tester.pumpAndSettle();
    expect(find.text('Execution center route'), findsOneWidget);
  });

  testWidgets('ready guide opens chat for an enabled OmniFlow plugin', (
    tester,
  ) async {
    plugins = <Map<String, Object?>>[
      <String, Object?>{
        ..._runtimePlugin(),
        'installed': true,
        'enabled': true,
      },
    ];

    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();
    await tester.tap(find.text('OmniFlow'));
    await tester.pumpAndSettle();

    await tester.scrollUntilVisible(
      find.text('去聊天试用'),
      600,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('去聊天试用'));
    await tester.pumpAndSettle();
    expect(find.text('Chat route'), findsOneWidget);
  });

  testWidgets('updates an installed plugin from its detail page', (
    tester,
  ) async {
    plugins = <Map<String, Object?>>[
      <String, Object?>{
        ..._runtimePlugin(),
        'installed': true,
        'enabled': true,
      },
    ];

    await tester.pumpWidget(_app());
    await tester.pumpAndSettle();
    await tester.tap(find.text('OmniFlow'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('更新'));
    await tester.pumpAndSettle();

    expect(calls.any((call) => call.method == 'update'), isTrue);
    expect(find.textContaining('v2.0.0'), findsOneWidget);
  });
}
