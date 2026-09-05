import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/model_provider_setting/model_provider_setting_page.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

const _modelsDevCatalogJson = '''
{
  "openai": {
    "id": "openai",
    "name": "OpenAI",
    "models": {
      "gpt-4o": {
        "id": "gpt-4o",
        "name": "GPT-4o",
        "limit": {"context": 128000, "input": 96000, "output": 16384},
        "modalities": {"input": ["text", "image", "pdf"], "output": ["text"]},
        "family": "gpt",
        "attachment": true,
        "reasoning": true,
        "tool_call": true,
        "structured_output": true,
        "temperature": true
      },
      "gpt-4o-mini": {
        "id": "gpt-4o-mini",
        "name": "GPT-4o mini",
        "limit": {"context": 128000, "output": 16384},
        "modalities": {"input": ["text"], "output": ["text"]},
        "family": "gpt",
        "reasoning": true
      },
      "text-embedding-3-large": {
        "id": "text-embedding-3-large",
        "name": "Text Embedding 3 Large",
        "limit": {"context": 8191},
        "modalities": {"input": ["text"], "output": ["text"]},
        "family": "embedding"
      }
    }
  }
}
''';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  Map<String, dynamic> profilePayload({
    String name = 'Provider 1',
    String baseUrl = 'https://api.openai.com/v1',
    String sourceType = 'custom',
    String protocolType = 'openai_compatible',
    String wireApi = 'chat_completions',
  }) {
    return <String, dynamic>{
      'profiles': <Map<String, dynamic>>[
        <String, dynamic>{
          'id': 'provider-1',
          'name': name,
          'baseUrl': baseUrl,
          'apiKey': 'sk-demo',
          'sourceType': sourceType,
          'readOnly': false,
          'ready': true,
          'statusText': '',
          'configured': true,
          'protocolType': protocolType,
          'wireApi': wireApi,
        },
      ],
      'editingProfileId': 'provider-1',
    };
  }

  Map<String, dynamic> savedProfileResponse(Map<dynamic, dynamic> args) {
    return <String, dynamic>{
      'id': 'provider-1',
      'name': (args['name'] ?? 'Provider 1').toString(),
      'baseUrl': (args['baseUrl'] ?? '').toString(),
      'apiKey': (args['apiKey'] ?? '').toString(),
      'customHeaders':
          (args['customHeaders'] as Map?) ?? const <String, String>{},
      'sourceType': (args['sourceType'] ?? 'custom').toString(),
      'readOnly': false,
      'ready': true,
      'statusText': '',
      'configured': true,
      'protocolType': (args['protocolType'] ?? 'openai_compatible').toString(),
      'wireApi': (args['wireApi'] ?? 'chat_completions').toString(),
    };
  }

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    ModelsDevCatalogService.setCatalogForTesting(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return profilePayload();
      }
      return null;
    });
  });

  tearDown(() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, null);
    ModelsDevCatalogService.resetForTesting();
  });

  for (final fieldLabel in ['API Key', 'Base URL']) {
    testWidgets(
      'refresh persists the $fieldLabel draft before caching models',
      (tester) async {
        tester.view.physicalSize = const Size(1080, 2200);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        var profile = Map<String, dynamic>.from(
          (profilePayload()['profiles'] as List).single as Map,
        )..['revision'] = 7;
        final operations = <String>[];
        Map<dynamic, dynamic>? fetchArgs;
        final messenger =
            TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
        messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'listModelProviderProfiles':
              return {
                'profiles': [profile],
                'editingProfileId': profile['id'],
              };
            case 'saveModelProviderProfile':
              operations.add('save');
              profile = {
                ...profile,
                ...savedProfileResponse(
                  Map<dynamic, dynamic>.from(call.arguments as Map),
                ),
                'revision': (profile['revision'] as int) + 1,
              };
              return profile;
            case 'fetchProviderModels':
              operations.add('fetch');
              fetchArgs = Map<dynamic, dynamic>.from(call.arguments as Map);
              return [
                {'id': 'gpt-4o', 'displayName': 'gpt-4o'},
              ];
          }
          return null;
        });
        Future<void> openPage() async {
          await tester.pumpWidget(
            MaterialApp(
              theme: AppTheme.lightTheme,
              home: const ModelProviderSettingPage(),
            ),
          );
          await tester.pumpAndSettle();
        }

        await openPage();
        final field = find.byWidgetPredicate(
          (widget) =>
              widget is TextField && widget.decoration?.labelText == fieldLabel,
        );
        await tester.enterText(
          field,
          fieldLabel == 'API Key'
              ? 'updated-test-key'
              : 'https://updated.example/v1',
        );
        await tester.tap(find.byIcon(LucideIcons.arrowBigDown));
        await tester.pumpAndSettle();

        expect(operations, ['save', 'fetch']);
        expect(fetchArgs?['expectedProfileRevision'], 8);
        expect(fetchArgs?.containsKey('apiKey'), isFalse);
        expect(fetchArgs?.containsKey('customHeaders'), isFalse);
        expect(
          find.byKey(const ValueKey('provider-model-gpt-4o')),
          findsOneWidget,
        );

        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pumpAndSettle();
        await openPage();
        expect(
          find.byKey(const ValueKey('provider-model-gpt-4o')),
          findsOneWidget,
        );
        final cached = await ModelProviderConfigService.getCachedFetchedModels(
          profileId: profile['id'] as String,
          apiBase: profile['baseUrl'] as String,
          profileRevision: 8,
        );
        expect(cached.single.id, 'gpt-4o');
        expect(operations, ['save', 'fetch']);
      },
    );
  }

  testWidgets('failed draft save does not fetch or replace the saved catalog', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1080, 2200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final payload = profilePayload();
    (payload['profiles'] as List).single['revision'] = 7;
    var fetchCalls = 0;
    var saveCalls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'listModelProviderProfiles':
              return payload;
            case 'saveModelProviderProfile':
              saveCalls++;
              throw PlatformException(code: 'SAVE_FAILED');
            case 'fetchProviderModels':
              fetchCalls++;
              return [
                {'id': 'unexpected-model'},
              ];
          }
          return null;
        });
    await ModelProviderConfigService.saveCachedFetchedModels(
      profileId: 'provider-1',
      apiBase: 'https://api.openai.com/v1',
      profileRevision: 7,
      models: const [ProviderModelOption(id: 'gpt-4o', displayName: 'gpt-4o')],
    );
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: const ModelProviderSettingPage(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byWidgetPredicate(
        (widget) =>
            widget is TextField && widget.decoration?.labelText == 'API Key',
      ),
      'unsaved-key',
    );
    await tester.tap(find.byIcon(LucideIcons.arrowBigDown));
    await tester.pumpAndSettle();
    expect(saveCalls, 1);
    expect(fetchCalls, 0);
    expect(
      (await ModelProviderConfigService.getCachedFetchedModels(
        profileId: 'provider-1',
        apiBase: 'https://api.openai.com/v1',
        profileRevision: 7,
      )).single.id,
      'gpt-4o',
    );
    // The failed operation releases the existing refresh button.
    await tester.tap(find.byIcon(LucideIcons.arrowBigDown));
    await tester.pumpAndSettle();
    expect(saveCalls, 2);
    expect(fetchCalls, 0);
  });

  testWidgets('late refresh cannot display models for a changed draft', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1080, 2200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final response = Completer<List<Map<String, dynamic>>>();
    var fetchCalls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'listModelProviderProfiles':
              return profilePayload();
            case 'fetchProviderModels':
              fetchCalls++;
              return response.future;
            case 'saveModelProviderProfile':
              return savedProfileResponse(
                Map<dynamic, dynamic>.from(call.arguments as Map),
              );
          }
          return null;
        });
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: const ModelProviderSettingPage(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(LucideIcons.arrowBigDown));
    await tester.pump();
    expect(fetchCalls, 1);
    await tester.enterText(
      find.byWidgetPredicate(
        (widget) =>
            widget is TextField && widget.decoration?.labelText == 'API Key',
      ),
      'new-draft-key',
    );
    response.complete([
      {'id': 'gpt-4o', 'displayName': 'gpt-4o'},
    ]);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('provider-model-gpt-4o')), findsNothing);
  });

  testWidgets('deleted fetched model stays deleted after reopening provider', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1080, 2200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final payload = profilePayload();
    (payload['profiles'] as List).single['revision'] = 7;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'listModelProviderProfiles') return payload;
          throw StateError('Unexpected call: ${call.method}');
        });
    await ModelProviderConfigService.saveCachedFetchedModels(
      profileId: 'provider-1',
      apiBase: 'https://api.openai.com/v1',
      profileRevision: 7,
      models: const [ProviderModelOption(id: 'gpt-4o', displayName: 'gpt-4o')],
    );
    Future<void> openPage() async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: const ModelProviderSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
    }

    await openPage();
    final row = find.byKey(const ValueKey('provider-model-gpt-4o'));
    await tester.drag(row, const Offset(-250, 0));
    await tester.pumpAndSettle();
    await tester.tap(
      find.descendant(of: row, matching: find.byIcon(LucideIcons.trash2)),
    );
    await tester.pumpAndSettle();
    expect(row, findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
    await openPage();
    expect(row, findsNothing);
    expect(
      await ModelProviderConfigService.getCachedFetchedModels(
        profileId: 'provider-1',
        apiBase: 'https://api.openai.com/v1',
        profileRevision: 7,
      ),
      isEmpty,
    );
  });

  testWidgets(
    'provider page renders header actions without layout exceptions',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          darkTheme: AppTheme.darkTheme,
          home: const ModelProviderSettingPage(),
        ),
      );

      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));

      expect(
        find.descendant(
          of: find.byKey(const Key('provider-config-title')),
          matching: find.text('Provider 1'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: find.byKey(const Key('provider-protocol-type-button')),
          matching: find.text('OpenAI'),
        ),
        findsOneWidget,
      );
      expect(find.text('模型类型'), findsNothing);
      final providerRight = tester
          .getTopRight(find.byKey(const Key('provider-config-title')))
          .dx;
      final protocolLeft = tester
          .getTopLeft(find.byKey(const Key('provider-protocol-type-button')))
          .dx;
      expect(protocolLeft - providerRight, 4);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('saved API key can be revealed without triggering a save', (
    tester,
  ) async {
    var saveCalls = 0;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return profilePayload();
        case 'saveModelProviderProfile':
          saveCalls += 1;
          return savedProfileResponse(
            Map<dynamic, dynamic>.from(call.arguments as Map),
          );
      }
      return null;
    });

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const ModelProviderSettingPage(),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    final apiKeyFieldFinder = find.byWidgetPredicate(
      (widget) =>
          widget is TextField && widget.decoration?.labelText == 'API Key',
    );
    final hiddenField = tester.widget<TextField>(apiKeyFieldFinder);
    expect(hiddenField.controller?.text, 'sk-demo');
    expect(hiddenField.obscureText, isTrue);

    await tester.tap(
      find.byKey(const Key('provider-api-key-visibility-button')),
    );
    await tester.pump();

    final revealedField = tester.widget<TextField>(apiKeyFieldFinder);
    expect(revealedField.controller?.text, 'sk-demo');
    expect(revealedField.obscureText, isFalse);

    await tester.pump(const Duration(milliseconds: 700));
    expect(saveCalls, 0);
  });

  testWidgets('provider page filters the runtime OmniBot official channel', (
    tester,
  ) async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      if (call.method == 'listModelProviderProfiles') {
        final payload = profilePayload();
        (payload['profiles'] as List<Map<String, dynamic>>)
            .add(<String, dynamic>{
              'id': 'omnibot-official-ai',
              'name': 'OmniBot 官方 AI',
              'baseUrl': 'https://official.example/ai',
              'sourceType': 'omnibot_official',
              'readOnly': true,
              'ready': true,
              'configured': true,
              'protocolType': 'openai_compatible',
              'wireApi': 'chat_completions',
            });
        return payload;
      }
      return null;
    });

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const ModelProviderSettingPage(),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('Provider 1'), findsWidgets);
    await tester.tap(find.byKey(const Key('provider-config-title')));
    await tester.pumpAndSettle();
    expect(find.text('OmniBot 官方 AI'), findsNothing);
  });

  testWidgets('provider page does not wait for metadata refresh', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1080, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    await ModelProviderConfigService.saveManualModelIds(
      profileId: 'provider-1',
      ids: const <String>['gpt-4o'],
    );
    ModelsDevCatalogService.resetForTesting();
    final loader = Completer<ModelsDevCatalog>();
    addTearDown(() {
      if (!loader.isCompleted) {
        loader.complete(const ModelsDevCatalog(providers: {}));
      }
    });
    var loadCount = 0;
    ModelsDevCatalogService.setCatalogLoaderForTesting(() {
      loadCount += 1;
      return loader.future;
    });

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const ModelProviderSettingPage(),
      ),
    );
    for (var index = 0; index < 6; index++) {
      await tester.pump(const Duration(milliseconds: 1));
    }

    expect(find.byType(ListView), findsWidgets);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(
      find.byKey(const ValueKey<String>('provider-model-gpt-4o')),
      findsOneWidget,
    );
    expect(
      find.byKey(const Key('provider-model-reasoning-gpt-4o')),
      findsNothing,
    );
    expect(loadCount, 1);

    loader.complete(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );
    for (var index = 0; index < 4; index++) {
      await tester.pump(const Duration(milliseconds: 1));
    }

    expect(
      find.byKey(const Key('provider-model-reasoning-gpt-4o')),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('provider labels stay bounded on narrow layout', (tester) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(360, 800);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);

    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    for (final entry in const <Map<String, String>>[
      <String, String>{
        'sourceType': 'deepseek',
        'baseUrl': 'https://api.deepseek.com',
        'protocolType': 'deepseek',
        'label': 'DeepSeek',
      },
      <String, String>{
        'sourceType': 'mimo',
        'baseUrl': 'https://api.xiaomimimo.com/v1',
        'label': 'Mimo',
      },
      <String, String>{
        'sourceType': 'moonshot',
        'baseUrl': 'https://api.moonshot.cn/v1',
        'label': 'Kimi',
      },
      <String, String>{
        'sourceType': 'minimax',
        'baseUrl': 'https://api.minimaxi.com/v1',
        'label': 'MiniMax',
      },
      <String, String>{
        'sourceType': 'bailian',
        'baseUrl': 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        'label': '阿里百炼',
      },
      <String, String>{
        'sourceType': 'custom',
        'baseUrl': 'https://api.openai.com/v1',
        'label': 'OpenAI',
      },
      <String, String>{
        'sourceType': 'custom',
        'baseUrl': 'https://api.anthropic.com',
        'protocolType': 'anthropic',
        'label': 'Anthropic',
      },
    ]) {
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return profilePayload(
              sourceType: entry['sourceType']!,
              baseUrl: entry['baseUrl']!,
              protocolType: entry['protocolType'] ?? 'openai_compatible',
            );
        }
        return null;
      });

      await tester.pumpWidget(
        MaterialApp(
          key: ValueKey('provider-type-${entry['sourceType']}'),
          theme: AppTheme.lightTheme,
          darkTheme: AppTheme.darkTheme,
          home: const ModelProviderSettingPage(),
        ),
      );

      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));

      expect(
        find.descendant(
          of: find.byKey(const Key('provider-protocol-type-button')),
          matching: find.text(entry['label']!),
        ),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);

      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    }
  });

  testWidgets('provider menu exposes builtin providers and protocols', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(360, 800);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);

    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return profilePayload();
      }
      return null;
    });

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const ModelProviderSettingPage(),
      ),
    );

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    await tester.tap(find.byKey(const Key('provider-protocol-type-button')));
    await tester.pumpAndSettle();

    final menuRect = tester.getRect(
      find.byKey(const Key('provider-protocol-type-menu')),
    );
    expect(menuRect.width, greaterThanOrEqualTo(200));
    expect(find.text('DeepSeek'), findsOneWidget);
    expect(find.text('Mimo'), findsOneWidget);
    expect(find.text('Kimi'), findsOneWidget);
    expect(find.text('MiniMax'), findsOneWidget);
    expect(find.text('阿里百炼'), findsOneWidget);
    expect(find.text('OpenAI'), findsAtLeastNWidgets(1));
    expect(find.text('Anthropic'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('base url hint mentions trailing marker override', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const ModelProviderSettingPage(),
      ),
    );

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    final baseUrlField = tester.widget<TextField>(
      find.byWidgetPredicate(
        (widget) =>
            widget is TextField && widget.decoration?.labelText == 'Base URL',
      ),
    );
    expect(baseUrlField.decoration?.hintText, contains('末尾加 #'));
  });

  testWidgets('anthropic profile shows full messages request url', (
    tester,
  ) async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return profilePayload(
            baseUrl: 'https://api.anthropic.com',
            protocolType: 'anthropic',
          );
      }
      return null;
    });

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const ModelProviderSettingPage(),
      ),
    );

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(
      find.descendant(
        of: find.byKey(const Key('provider-protocol-type-button')),
        matching: find.text('Anthropic'),
      ),
      findsOneWidget,
    );
    expect(find.text('https://api.anthropic.com/v1/messages'), findsOneWidget);
    expect(find.byKey(const Key('provider-wire-api-button')), findsNothing);
  });

  testWidgets('openai compatible profile shows direct wire api choice', (
    tester,
  ) async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return profilePayload(
            baseUrl: 'https://api.openai.com/v1',
            wireApi: 'responses',
          );
      }
      return null;
    });

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const ModelProviderSettingPage(),
      ),
    );

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(
      find.descendant(
        of: find.byKey(const Key('provider-protocol-type-button')),
        matching: find.text('OpenAI'),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: find.byKey(const Key('provider-wire-api-button')),
        matching: find.text('Responses'),
      ),
      findsOneWidget,
    );
    expect(find.text('https://api.openai.com/v1/responses'), findsOneWidget);
  });

  testWidgets('selecting official provider saves builtin profile payload', (
    tester,
  ) async {
    var saveCalls = 0;
    Map<dynamic, dynamic>? savedArgs;
    String savedWireApi = '';
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return profilePayload();
        case 'saveModelProviderProfile':
          saveCalls += 1;
          savedArgs = Map<dynamic, dynamic>.from(
            (call.arguments as Map?) ?? const <String, dynamic>{},
          );
          savedWireApi = (savedArgs!['wireApi'] ?? '').toString();
          return savedProfileResponse(savedArgs!);
      }
      return null;
    });

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const ModelProviderSettingPage(),
      ),
    );

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    await tester.tap(find.byKey(const Key('provider-protocol-type-button')));
    await tester.pumpAndSettle();

    expect(find.text('Kimi'), findsOneWidget);
    expect(find.text('MiniMax'), findsOneWidget);

    await tester.tap(find.text('Kimi'));
    await tester.pumpAndSettle();

    expect(saveCalls, 1);
    expect(savedWireApi, 'chat_completions');
    expect(savedArgs?['sourceType'], 'moonshot');
    expect(savedArgs?['baseUrl'], 'https://api.moonshot.cn/v1');
    expect(savedArgs?['protocolType'], 'openai_compatible');
    expect(
      find.descendant(
        of: find.byKey(const Key('provider-protocol-type-button')),
        matching: find.text('Kimi'),
      ),
      findsOneWidget,
    );
  });

  testWidgets('provider fields do not auto-save while focused', (tester) async {
    var saveCalls = 0;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return profilePayload();
        case 'saveModelProviderProfile':
          saveCalls += 1;
          final args = Map<dynamic, dynamic>.from(
            (call.arguments as Map?) ?? const <String, dynamic>{},
          );
          return savedProfileResponse(args);
      }
      return null;
    });

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const ModelProviderSettingPage(),
      ),
    );

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    final nameField = find.byWidgetPredicate(
      (widget) =>
          widget is TextField && widget.decoration?.labelText == 'Provider 名称',
    );
    await tester.tap(nameField);
    await tester.pump();
    await tester.enterText(nameField, 'DeepSeek Pro');

    await tester.pump(const Duration(milliseconds: 700));

    expect(saveCalls, 0);

    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 700));

    expect(saveCalls, 1);
  });

  testWidgets('provider page saves focused draft when leaving', (tester) async {
    var saveCalls = 0;
    Map<dynamic, dynamic>? savedArgs;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return profilePayload();
        case 'saveModelProviderProfile':
          saveCalls += 1;
          savedArgs = Map<dynamic, dynamic>.from(
            (call.arguments as Map?) ?? const <String, dynamic>{},
          );
          return savedProfileResponse(savedArgs!);
      }
      return null;
    });

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const ModelProviderSettingPage(),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    final apiKeyField = find.byWidgetPredicate(
      (widget) =>
          widget is TextField && widget.decoration?.labelText == 'API Key',
    );
    await tester.tap(apiKeyField);
    await tester.enterText(apiKeyField, 'sk-updated');
    await tester.pump(const Duration(milliseconds: 700));
    expect(saveCalls, 0);

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();

    expect(saveCalls, 1);
    expect(savedArgs?['apiKey'], 'sk-updated');
  });

  testWidgets('renders models.dev grouping, context, and input modalities', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(800, 1000);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);

    await ModelProviderConfigService.saveCachedFetchedModels(
      profileId: 'provider-1',
      apiBase: 'https://api.openai.com/v1',
      models: const [
        ProviderModelOption(id: 'gpt-4o', displayName: 'gpt-4o'),
        ProviderModelOption(id: 'gpt-4o-mini', displayName: 'gpt-4o-mini'),
        ProviderModelOption(
          id: 'claude-3-haiku',
          displayName: 'claude-3-haiku',
        ),
      ],
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        home: const ModelProviderSettingPage(),
      ),
    );

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.byKey(const Key('provider-logo')), findsNothing);
    expect(
      find.byKey(const ValueKey('provider-model-group-toggle-button')),
      findsOneWidget,
    );
    expect(
      find.byKey(const Key('provider-model-group-openai')),
      findsOneWidget,
    );
    expect(
      find.byKey(const Key('provider-model-group-anthropic')),
      findsOneWidget,
    );
    expect(find.text('2'), findsOneWidget);
    expect(find.text('128K'), findsNWidgets(2));
    expect(
      find.descendant(
        of: find.byKey(const Key('provider-model-context-gpt-4o')),
        matching: find.byType(SvgPicture),
      ),
      findsNothing,
    );
    expect(
      find.byKey(const Key('provider-model-reasoning-gpt-4o')),
      findsOneWidget,
    );
    expect(find.text('Context 128K'), findsNothing);
    expect(find.text('Tools'), findsNothing);
    expect(find.text('JSON'), findsNothing);
    expect(find.text('Files'), findsNothing);
    expect(find.text('Temp'), findsNothing);
    expect(find.text('自动'), findsNothing);
    expect(find.text('手动'), findsNothing);
    expect(
      find.byKey(const Key('provider-model-modality-text')),
      findsNWidgets(2),
    );
    expect(
      find.byKey(const Key('provider-model-modality-image')),
      findsOneWidget,
    );
    expect(
      find.byKey(const Key('provider-model-modality-pdf')),
      findsOneWidget,
    );

    final groupBody = find.byKey(const Key('provider-model-group-body-openai'));
    expect(tester.getSize(groupBody).height, greaterThan(0));
    final shortLine = tester.getSize(
      find.byKey(const Key('provider-model-group-line-openai')),
    );
    final longLine = tester.getSize(
      find.byKey(const Key('provider-model-group-line-anthropic')),
    );
    expect(shortLine.width, greaterThan(longLine.width));
    final shortHeaderRight = tester.getTopRight(
      find.byKey(const Key('provider-model-group-openai')),
    );
    final shortIconRight = tester.getTopRight(
      find.byKey(const Key('provider-model-group-icon-openai')),
    );
    expect((shortHeaderRight.dx - shortIconRight.dx).abs(), lessThan(6));
    final shortCountRight = tester.getTopRight(
      find.byKey(const Key('provider-model-group-count-openai')),
    );
    final shortLineLeft = tester.getTopLeft(
      find.byKey(const Key('provider-model-group-line-openai')),
    );
    final shortLineRight = tester.getTopRight(
      find.byKey(const Key('provider-model-group-line-openai')),
    );
    final shortIconLeft = tester.getTopLeft(
      find.byKey(const Key('provider-model-group-icon-openai')),
    );
    expect(shortLineLeft.dx - shortCountRight.dx, closeTo(10, 0.5));
    expect(shortIconLeft.dx - shortLineRight.dx, closeTo(6, 0.5));
    final firstModel = find.byKey(
      const ValueKey<String>('provider-model-gpt-4o'),
    );
    final secondModel = find.byKey(
      const ValueKey<String>('provider-model-gpt-4o-mini'),
    );
    expect(tester.getSize(firstModel).height, 44);
    expect(tester.getSize(secondModel).height, 44);
    expect(tester.getSize(firstModel).width, tester.getSize(secondModel).width);

    await tester.tap(
      find.byKey(const ValueKey('provider-model-group-toggle-button')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));
    expect(tester.getSize(groupBody).height, 0);
    expect(
      tester
          .getSize(find.byKey(const Key('provider-model-group-body-anthropic')))
          .height,
      0,
    );

    await tester.tap(
      find.byKey(const ValueKey('provider-model-group-toggle-button')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));
    expect(tester.getSize(groupBody).height, greaterThan(0));

    await tester.scrollUntilVisible(
      find.byKey(const Key('provider-model-group-openai')),
      120,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.byKey(const Key('provider-model-group-openai')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));
    expect(tester.getSize(groupBody).height, 0);

    await tester.scrollUntilVisible(
      find.byKey(const Key('provider-model-group-openai')),
      120,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.byKey(const Key('provider-model-group-openai')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));
    expect(tester.getSize(groupBody).height, greaterThan(0));

    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'chat visibility popup hides fetched models without deleting rows',
    (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(800, 1000);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);

      await ModelProviderConfigService.saveCachedFetchedModels(
        profileId: 'provider-1',
        apiBase: 'https://api.openai.com/v1',
        models: const [
          ProviderModelOption(id: 'gpt-4o', displayName: 'gpt-4o'),
          ProviderModelOption(id: 'gpt-4o-mini', displayName: 'gpt-4o-mini'),
        ],
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          darkTheme: AppTheme.darkTheme,
          home: const ModelProviderSettingPage(),
        ),
      );

      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));

      expect(
        find.byKey(const Key('provider-chat-model-visibility-button')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('provider-model-gpt-4o-mini')),
        findsOneWidget,
      );

      await tester.tap(
        find.byKey(const Key('provider-chat-model-visibility-button')),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 260));

      expect(
        find.byKey(const Key('provider-chat-model-visibility-menu')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('provider-chat-model-visibility-row-gpt-4o-mini')),
        findsOneWidget,
      );

      await tester.tap(
        find.byKey(
          const Key('provider-chat-model-visibility-toggle-gpt-4o-mini'),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 80));

      final hiddenIds = await ModelProviderConfigService.getHiddenChatModelIds(
        profileId: 'provider-1',
      );
      expect(hiddenIds, ['gpt-4o-mini']);
      expect(
        find.byKey(const ValueKey<String>('provider-model-gpt-4o-mini')),
        findsOneWidget,
      );

      expect(tester.takeException(), isNull);
    },
  );
}
