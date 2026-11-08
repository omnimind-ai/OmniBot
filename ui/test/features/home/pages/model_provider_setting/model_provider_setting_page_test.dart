import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/model_provider_setting/model_provider_setting_page.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/omni_glass.dart';

final _discoveryModels = <String, List<ProviderModelOption>>{};

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
    String id = 'provider-1',
    String name = 'Provider 1',
    String baseUrl = 'https://api.openai.com/v1',
    String apiKey = 'sk-demo',
    bool hasApiKey = true,
    String sourceType = 'custom',
    String protocolType = 'openai_compatible',
    String wireApi = 'chat_completions',
    bool configured = true,
    bool ready = true,
  }) {
    return <String, dynamic>{
      'profiles': <Map<String, dynamic>>[
        <String, dynamic>{
          'id': id,
          'name': name,
          'baseUrl': baseUrl,
          'apiKey': apiKey,
          'hasApiKey': hasApiKey,
          'sourceType': sourceType,
          'readOnly': false,
          'ready': ready,
          'statusText': '',
          'configured': configured,
          'protocolType': protocolType,
          'wireApi': wireApi,
        },
      ],
      'editingProfileId': id,
    };
  }

  Map<String, dynamic> savedProfileResponse(Map<dynamic, dynamic> args) {
    final apiKey = (args['apiKey'] ?? '').toString();
    final baseUrl = (args['baseUrl'] ?? '').toString();
    return <String, dynamic>{
      'id': (args['id'] ?? 'provider-1').toString(),
      'name': (args['name'] ?? 'Provider 1').toString(),
      'baseUrl': baseUrl,
      'apiKey': apiKey,
      'hasApiKey': apiKey.isNotEmpty,
      'customHeaders':
          (args['customHeaders'] as Map?) ?? const <String, String>{},
      'sourceType': (args['sourceType'] ?? 'custom').toString(),
      'readOnly': false,
      'ready': true,
      'statusText': '',
      'configured': baseUrl.isNotEmpty,
      'protocolType': (args['protocolType'] ?? 'openai_compatible').toString(),
      'wireApi': (args['wireApi'] ?? 'chat_completions').toString(),
    };
  }

  Future<void> openFirstProviderDetail(
    WidgetTester tester, {
    String profileId = 'provider-1',
  }) async {
    final row = find.byKey(ValueKey<String>('provider-list-item-$profileId'));
    expect(row, findsOneWidget);
    await tester.tap(row);
    await tester.pumpAndSettle();
  }

  Future<void> switchToConnectionTab(WidgetTester tester) async {
    final tab = find.byKey(const ValueKey('provider-detail-tab-connection'));
    if (tab.evaluate().isNotEmpty) {
      await tester.tap(tab);
      await tester.pumpAndSettle();
    }
  }

  Future<void> switchToModelsTab(WidgetTester tester) async {
    final tab = find.byKey(const ValueKey('provider-detail-tab-models'));
    if (tab.evaluate().isNotEmpty) {
      await tester.tap(tab);
      await tester.pumpAndSettle();
    }
  }

  setUp(() async {
    _discoveryModels.clear();
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
        case 'setEditingModelProviderProfile':
          return (profilePayload()['profiles'] as List).first;
        case 'fetchProviderModels':
          return (_discoveryModels[(call.arguments as Map)['profileId']] ?? [])
              .map((model) => model.toMap())
              .toList();
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

  testWidgets(
    'provider list shows configured providers without misleading current badge and opens detail on Models tab',
    (tester) async {
      _discoveryModels['provider-1'] = const [
        ProviderModelOption(id: 'gpt-4o', displayName: 'GPT-4o'),
      ];
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: const ModelProviderSettingPage(),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('当前提供商'), findsNothing);
      expect(find.text('当前使用中'), findsNothing);
      expect(
        find.byKey(const ValueKey<String>('provider-list-item-provider-1')),
        findsOneWidget,
      );
      expect(find.text('Provider 1'), findsOneWidget);
      expect(find.textContaining('api.openai.com'), findsOneWidget);
      expect(
        find.byKey(const Key('provider-list-add-button')),
        findsOneWidget,
      );

      await openFirstProviderDetail(tester);

      expect(find.byType(ModelProviderDetailPage), findsOneWidget);
      expect(
        find.byKey(const Key('provider-fetch-models-button')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('provider-model-gpt-4o')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'empty state guides user to add a provider and pre-fills preset connection',
    (tester) async {
      var saved = false;
      Map<String, dynamic>? savedProfile;
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            if (saved && savedProfile != null) {
              return {
                'profiles': [savedProfile!],
                'editingProfileId': savedProfile!['id'],
              };
            }
            return profilePayload(
              id: 'profile-1',
              name: 'Provider 1',
              baseUrl: '',
              apiKey: '',
              hasApiKey: false,
              configured: false,
            );
          case 'saveModelProviderProfile':
            saved = true;
            savedProfile = savedProfileResponse(
              Map<dynamic, dynamic>.from(call.arguments as Map),
            );
            return savedProfile;
          case 'fetchProviderModels':
            return [
              {'id': 'deepseek-chat', 'displayName': 'DeepSeek Chat'},
            ];
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

      expect(find.byKey(const Key('provider-empty-state')), findsOneWidget);
      expect(
        find.byKey(const Key('provider-empty-add-button')),
        findsOneWidget,
      );

      await tester.tap(find.byKey(const Key('provider-empty-preset-deepseek')));
      await tester.pumpAndSettle();

      expect(find.byType(ModelProviderDetailPage), findsOneWidget);
      final nameField = tester.widget<TextField>(
        find.byKey(const Key('provider-name-field')),
      );
      final urlField = tester.widget<TextField>(
        find.byKey(const Key('provider-base-url-field')),
      );
      expect(nameField.controller?.text, 'DeepSeek');
      expect(urlField.controller?.text, 'https://api.deepseek.com');

      await tester.enterText(
        find.byKey(const Key('provider-api-key-field')),
        'sk-deepseek-key',
      );
      await tester.tap(find.byKey(const Key('provider-save-and-models-button')));
      await tester.pumpAndSettle();

      expect(saved, isTrue);
      expect(savedProfile?['id'], 'profile-1');
      expect(savedProfile?['name'], 'DeepSeek');
      expect(
        find.byKey(const ValueKey<String>('provider-model-deepseek-chat')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'incomplete provider points out missing item and opens on Connection tab',
    (tester) async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        if (call.method == 'listModelProviderProfiles') {
          return profilePayload(
            id: 'deepseek-official',
            name: 'DeepSeek',
            baseUrl: 'https://api.deepseek.com',
            apiKey: '',
            hasApiKey: false,
            sourceType: 'deepseek',
            protocolType: 'deepseek',
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

      expect(find.textContaining('Missing API Key'), findsWidgets);

      await openFirstProviderDetail(tester, profileId: 'deepseek-official');

      expect(
        find.byKey(const Key('provider-connection-status-banner')),
        findsOneWidget,
      );
      expect(find.textContaining('Missing API Key'), findsOneWidget);
      expect(find.byKey(const Key('provider-api-key-field')), findsOneWidget);
    },
  );

  testWidgets(
    'switching between Connection and Models tabs preserves input and search query',
    (tester) async {
      _discoveryModels['provider-1'] = const [
        ProviderModelOption(id: 'gpt-4o', displayName: 'GPT-4o'),
        ProviderModelOption(id: 'gpt-4o-mini', displayName: 'GPT-4o mini'),
      ];

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: const ModelProviderSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
      await openFirstProviderDetail(tester);

      final searchField = find.descendant(
        of: find.byKey(const Key('provider-model-search-field')),
        matching: find.byType(TextField),
      );
      await tester.enterText(searchField, 'mini');
      await tester.pump();
      expect(
        find.byKey(const ValueKey<String>('provider-model-gpt-4o-mini')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey<String>('provider-model-gpt-4o')),
        findsNothing,
      );

      await switchToConnectionTab(tester);
      await tester.enterText(
        find.byKey(const Key('provider-name-field')),
        'Edited OpenAI Name',
      );
      await tester.pump();

      await switchToModelsTab(tester);
      expect(
        tester.widget<TextField>(searchField).controller?.text,
        'mini',
      );
      expect(
        find.byKey(const ValueKey<String>('provider-model-gpt-4o-mini')),
        findsOneWidget,
      );

      await switchToConnectionTab(tester);
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('provider-name-field')))
            .controller
            ?.text,
        'Edited OpenAI Name',
      );
    },
  );

  testWidgets('model input sheet can swipe close and submit above keyboard', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 720);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetViewInsets);
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: const ModelProviderSettingPage(),
      ),
    );
    await tester.pumpAndSettle();
    await openFirstProviderDetail(tester);

    final addModel = find.byKey(const Key('provider-add-manual-model-button'));
    await tester.ensureVisible(addModel);
    await tester.pumpAndSettle();
    await tester.tap(addModel);
    await tester.pumpAndSettle();
    final pane = find.byType(OmniGlassPanel);
    await tester.dragFrom(
      tester.getTopLeft(pane) + const Offset(100, 24),
      const Offset(0, 500),
    );
    await tester.pumpAndSettle();
    expect(find.byType(OmniGlassAlertDialog), findsNothing);
    expect(find.byType(ModelProviderDetailPage), findsOneWidget);

    await tester.ensureVisible(addModel);
    await tester.pumpAndSettle();
    await tester.tap(addModel);
    await tester.pumpAndSettle();
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    await tester.pumpAndSettle();
    final input = find.descendant(
      of: find.byType(OmniGlassAlertDialog),
      matching: find.byType(TextField),
    );
    await tester.enterText(input, 'local-test-model');
    final submit = find
        .descendant(
          of: find.byType(OmniGlassAlertDialog),
          matching: find.byType(TextButton),
        )
        .last;
    expect(tester.getRect(submit).bottom, lessThan(420));
    await tester.tap(submit);
    await tester.pumpAndSettle();
    expect(find.byType(OmniGlassAlertDialog), findsNothing);
    expect(find.byType(ModelProviderDetailPage), findsOneWidget);
    expect(
      await ModelProviderConfigService.getManualModelIds(
        profileId: 'provider-1',
      ),
      contains('local-test-model'),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'ChatGPT provider shows account controls without editable credentials',
    (tester) async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return profilePayload(
              name: 'Personal ChatGPT',
              sourceType: 'chatgpt_oauth',
              wireApi: 'responses',
            );
          case 'getChatGptAccount':
            return {
              'connected': true,
              'email': 'sample@example.com',
              'planEnabled': true,
            };
          case 'fetchProviderModels':
            return <Map<String, dynamic>>[];
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
      await openFirstProviderDetail(tester);
      await switchToConnectionTab(tester);

      expect(find.byKey(const Key('chatgpt-account-section')), findsOneWidget);
      expect(find.text('sample@example.com'), findsOneWidget);
      expect(find.text('ChatGPT'), findsOneWidget);
      expect(
        find.widgetWithText(TextField, 'Personal ChatGPT'),
        findsOneWidget,
      );
      expect(find.byKey(const Key('provider-api-key-field')), findsNothing);
      expect(find.byKey(const Key('provider-base-url-field')), findsNothing);
      expect(
        find.byKey(const Key('provider-api-key-visibility-button')),
        findsNothing,
      );
      expect(
        find.byKey(const Key('provider-custom-headers-toggle')),
        findsNothing,
      );
      expect(tester.takeException(), isNull);
    },
  );

  for (final fieldKey in [
    const Key('provider-api-key-field'),
    const Key('provider-base-url-field'),
  ]) {
    testWidgets(
      'refresh persists the $fieldKey draft and reopening queries again',
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
        Future<void> openDetail() async {
          await tester.pumpWidget(
            MaterialApp(
              theme: AppTheme.lightTheme,
              home: const ModelProviderSettingPage(),
            ),
          );
          await tester.pumpAndSettle();
          await openFirstProviderDetail(tester);
        }

        await openDetail();
        operations.clear();

        await switchToConnectionTab(tester);
        final field = find.byKey(fieldKey);
        await tester.enterText(
          field,
          fieldKey == const Key('provider-api-key-field')
              ? 'updated-test-key'
              : 'https://updated.example/v1',
        );

        await switchToModelsTab(tester);
        await tester.tap(find.byKey(const Key('provider-fetch-models-button')));
        await tester.pumpAndSettle();

        expect(operations, containsAllInOrder(['save', 'fetch']));
        expect(fetchArgs?['expectedProfileRevision'], 8);
        expect(fetchArgs?.containsKey('apiKey'), isFalse);
        expect(fetchArgs?.containsKey('customHeaders'), isFalse);
        expect(
          find.byKey(const ValueKey('provider-model-gpt-4o')),
          findsOneWidget,
        );

        final countBeforeReopen = operations.length;
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pumpAndSettle();
        await openDetail();
        expect(
          find.byKey(const ValueKey('provider-model-gpt-4o')),
          findsOneWidget,
        );
        expect(operations.length, greaterThan(countBeforeReopen));
        expect(operations.last, 'fetch');
      },
    );
  }

  testWidgets(
    'model discovery TLS failure stays visible, retains existing models, and explicit retry recovers',
    (tester) async {
      tester.view.physicalSize = const Size(1080, 2200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      var calls = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            if (call.method == 'listModelProviderProfiles') {
              return profilePayload();
            }
            if (call.method == 'fetchProviderModels') {
              calls++;
              if (calls == 1) {
                throw PlatformException(
                  code: 'FETCH_PROVIDER_MODELS_ERROR',
                  message: 'Trust anchor for private-server not found',
                  details: {'failureKind': 'provider_tls_certificate_failure'},
                );
              }
              return [
                {'id': 'gpt-4o', 'displayName': 'gpt-4o'},
              ];
            }
            return null;
          });
      await ModelProviderConfigService.saveManualModelIds(
        profileId: 'provider-1',
        ids: const ['manual-keep-model'],
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: const ModelProviderSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
      await openFirstProviderDetail(tester);
      await tester.pump(const Duration(seconds: 2));

      expect(calls, 1);
      expect(
        find.textContaining('Check whether your network requires sign-in'),
        findsWidgets,
      );
      expect(find.textContaining('private-server'), findsNothing);
      // Existing models are preserved even when remote fetch fails.
      expect(
        find.byKey(const ValueKey('provider-model-manual-keep-model')),
        findsOneWidget,
      );

      await tester.tap(
        find.byKey(const Key('provider-model-fetch-retry-button')),
      );
      await tester.pumpAndSettle();
      expect(calls, 2);
      expect(
        find.byKey(const ValueKey('provider-model-gpt-4o')),
        findsOneWidget,
      );
      expect(
        find.textContaining('Check whether your network requires sign-in'),
        findsNothing,
      );
    },
  );

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
    await tester.tap(
      find.byKey(const ValueKey<String>('provider-list-item-provider-1')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(fetchCalls, 1);

    await tester.tap(find.byKey(const ValueKey('provider-detail-tab-connection')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    await tester.enterText(
      find.byKey(const Key('provider-api-key-field')),
      'new-draft-key',
    );
    await tester.tap(find.byKey(const ValueKey('provider-detail-tab-models')));
    await tester.pump();
    response.complete([
      {'id': 'gpt-4o', 'displayName': 'gpt-4o'},
    ]);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('provider-model-gpt-4o')), findsNothing);
  });

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
    await tester.pumpAndSettle();
    await openFirstProviderDetail(tester);
    await switchToConnectionTab(tester);

    final apiKeyFieldFinder = find.byKey(const Key('provider-api-key-field'));
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
        (payload['profiles'] as List<Map<String, dynamic>>).add(
          <String, dynamic>{
            'id': 'omnibot-official-ai',
            'name': 'OmniBot 官方 AI',
            'baseUrl': 'https://official.example/ai',
            'sourceType': 'omnibot_official',
            'readOnly': true,
            'ready': true,
            'configured': true,
            'protocolType': 'openai_compatible',
            'wireApi': 'chat_completions',
          },
        );
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
    await tester.pumpAndSettle();

    expect(find.text('Provider 1'), findsOneWidget);
    expect(find.text('OmniBot 官方 AI'), findsNothing);
  });

  for (final reasoning in <bool?>[true, false, null]) {
    testWidgets('reasoning badge uses native capability: $reasoning', (
      tester,
    ) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(800, 1000);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      _discoveryModels['provider-1'] = [
        ProviderModelOption(
          id: 'gpt-4o',
          displayName: 'gpt-4o',
          reasoning: reasoning,
          reasoningCapabilitySource: reasoning == null ? null : 'provider',
        ),
      ];
      ModelsDevCatalogService.setCatalogForTesting(
        ModelsDevCatalogService.parseCatalog(
          reasoning == true
              ? _modelsDevCatalogJson.replaceAll(
                  '"reasoning": true',
                  '"reasoning": false',
                )
              : _modelsDevCatalogJson,
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: const ModelProviderSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
      await openFirstProviderDetail(tester);

      expect(
        find.byKey(const Key('provider-model-reasoning-gpt-4o')),
        reasoning == true ? findsOneWidget : findsNothing,
      );
      expect(find.text('128K'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

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
      await tester.pumpAndSettle();
      await openFirstProviderDetail(tester);
      await switchToConnectionTab(tester);

      expect(
        find.descendant(
          of: find.byKey(const Key('provider-protocol-type-button')),
          matching: find.text(entry['label']!),
        ),
        findsOneWidget,
      );
      expect(
        tester
            .renderObject<RenderParagraph>(
              find.byKey(const Key('provider-config-title-text')),
            )
            .didExceedMaxLines,
        isFalse,
      );
      expect(tester.takeException(), isNull);

      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    }
  });

  testWidgets(
    'connection tab supports builtin presets, 4 wire protocols, and collapsible Advanced Settings',
    (tester) async {
      Map<dynamic, dynamic>? lastSavedArgs;
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return profilePayload(
              baseUrl: 'https://generativelanguage.googleapis.com/v1',
              protocolType: 'gemini_interactions',
            );
          case 'saveModelProviderProfile':
            lastSavedArgs = Map<dynamic, dynamic>.from(call.arguments as Map);
            return savedProfileResponse(lastSavedArgs!);
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
      await openFirstProviderDetail(tester);
      await switchToConnectionTab(tester);

      // Full request URL is tucked inside Advanced Settings by default.
      expect(
        find.text('https://generativelanguage.googleapis.com/v1/interactions'),
        findsNothing,
      );
      await tester.tap(find.byKey(const Key('provider-advanced-settings-toggle')));
      await tester.pumpAndSettle();
      expect(
        find.text('https://generativelanguage.googleapis.com/v1/interactions'),
        findsOneWidget,
      );

      // Verify preset menu lists familiar providers.
      await tester.tap(find.byKey(const Key('provider-protocol-type-button')));
      await tester.pumpAndSettle();
      expect(find.text('ChatGPT'), findsOneWidget);
      expect(find.text('DeepSeek'), findsOneWidget);
      expect(find.text('Kimi'), findsOneWidget);
      await tester.tap(find.text('DeepSeek'));
      await tester.pumpAndSettle();
      expect(lastSavedArgs?['sourceType'], 'deepseek');
      expect(lastSavedArgs?['baseUrl'], 'https://api.deepseek.com');

      // Test the 4 wire protocols in the API Protocol selector.
      await tester.tap(find.byKey(const Key('provider-wire-api-button')));
      await tester.pumpAndSettle();
      expect(find.text('OpenAI Chat Completions'), findsWidgets);
      expect(find.text('OpenAI Responses'), findsOneWidget);
      expect(find.text('Anthropic Messages'), findsOneWidget);
      expect(find.text('Gemini Interactions'), findsOneWidget);

      await tester.tap(find.text('OpenAI Responses'));
      await tester.pumpAndSettle();
      expect(lastSavedArgs?['protocolType'], 'openai_compatible');
      expect(lastSavedArgs?['wireApi'], 'responses');
      expect(find.text('https://api.deepseek.com/v1/responses'), findsOneWidget);
    },
  );

  testWidgets('provider fields do not auto-save while focused and save on exit', (
    tester,
  ) async {
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
        home: const ModelProviderSettingPage(),
      ),
    );
    await tester.pumpAndSettle();
    await openFirstProviderDetail(tester);
    await switchToConnectionTab(tester);

    final nameField = find.byKey(const Key('provider-name-field'));
    await tester.tap(nameField);
    await tester.pump();
    await tester.enterText(nameField, 'DeepSeek Pro');
    await tester.pump(const Duration(milliseconds: 700));
    expect(saveCalls, 0);

    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 700));
    expect(saveCalls, 1);

    final apiKeyField = find.byKey(const Key('provider-api-key-field'));
    await tester.tap(apiKeyField);
    await tester.enterText(apiKeyField, 'sk-updated');
    await tester.pump(const Duration(milliseconds: 700));
    expect(saveCalls, 1);

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    expect(saveCalls, 2);
    expect(savedArgs?['apiKey'], 'sk-updated');
  });

  testWidgets(
    'single model group avoids redundant vendor header while multi-group shows collapsible headers',
    (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(800, 1000);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);

      // Case 1: Single vendor group (only OpenAI models) -> no redundant group header.
      _discoveryModels['provider-1'] = const [
        ProviderModelOption(id: 'gpt-4o', displayName: 'gpt-4o'),
        ProviderModelOption(id: 'gpt-4o-mini', displayName: 'gpt-4o-mini'),
      ];

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: const ModelProviderSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
      await openFirstProviderDetail(tester);

      expect(
        find.byKey(const ValueKey<String>('provider-model-gpt-4o')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('provider-model-group-openai')),
        findsNothing,
      );

      // Case 2: Multiple vendor groups (OpenAI + Anthropic) -> shows collapsible headers.
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      _discoveryModels['provider-1'] = const [
        ProviderModelOption(id: 'gpt-4o', displayName: 'gpt-4o'),
        ProviderModelOption(id: 'gpt-4o-mini', displayName: 'gpt-4o-mini'),
        ProviderModelOption(
          id: 'claude-3-haiku',
          displayName: 'claude-3-haiku',
        ),
      ];

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: const ModelProviderSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
      await openFirstProviderDetail(tester);

      expect(
        find.byKey(const Key('provider-model-group-openai')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('provider-model-group-anthropic')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('provider-model-group-toggle-button')),
        findsOneWidget,
      );

      final groupBody = find.byKey(
        const Key('provider-model-group-body-openai'),
      );
      expect(tester.getSize(groupBody).height, greaterThan(0));

      await tester.tap(
        find.byKey(const ValueKey('provider-model-group-toggle-button')),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 260));
      expect(tester.getSize(groupBody).height, 0);
    },
  );

  testWidgets(
    'inline chat visibility switch and model detail sheet test model availability',
    (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(800, 1000);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);

      _discoveryModels['provider-1'] = const [
        ProviderModelOption(id: 'gpt-4o', displayName: 'GPT-4o'),
        ProviderModelOption(id: 'gpt-4o-mini', displayName: 'GPT-4o mini'),
      ];

      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return profilePayload();
          case 'fetchProviderModels':
            return _discoveryModels['provider-1']!
                .map((m) => m.toMap())
                .toList();
          case 'checkProviderModelAvailability':
            return {'available': true, 'code': 200, 'message': 'OK'};
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
      await openFirstProviderDetail(tester);

      // Toggle chat visibility directly on the model row.
      await tester.tap(
        find.byKey(
          const Key('provider-chat-model-visibility-toggle-gpt-4o-mini'),
        ),
      );
      await tester.pumpAndSettle();

      final hiddenIds = await ModelProviderConfigService.getHiddenChatModelIds(
        profileId: 'provider-1',
      );
      expect(hiddenIds, ['gpt-4o-mini']);
      expect(
        find.byKey(const ValueKey<String>('provider-model-gpt-4o-mini')),
        findsOneWidget,
      );

      // Tap model row to open Model Detail sheet and run availability test.
      await tester.tap(
        find.byKey(const ValueKey<String>('provider-model-gpt-4o')),
      );
      await tester.pumpAndSettle();

      expect(find.text('128000 tokens (128K)'), findsOneWidget);
      await tester.tap(
        find.byKey(const Key('provider-test-model-button-gpt-4o')),
      );
      await tester.pumpAndSettle();

      expect(find.text('Verified: Model responded normally'), findsOneWidget);
      Navigator.of(tester.element(find.byType(OmniGlassAlertDialog))).pop();
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('provider-model-test-passed-gpt-4o')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'delete confirmation lists affected scene bindings before deleting provider',
    (tester) async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return {
              'profiles': [
                {
                  'id': 'provider-1',
                  'name': 'Work OpenAI',
                  'baseUrl': 'https://api.openai.com/v1',
                  'apiKey': 'sk-1',
                  'hasApiKey': true,
                  'sourceType': 'custom',
                  'configured': true,
                  'ready': true,
                },
                {
                  'id': 'provider-2',
                  'name': 'Backup DeepSeek',
                  'baseUrl': 'https://api.deepseek.com',
                  'apiKey': 'sk-2',
                  'hasApiKey': true,
                  'sourceType': 'deepseek',
                  'configured': true,
                  'ready': true,
                },
              ],
              'editingProfileId': 'provider-1',
            };
          case 'getSceneModelBindings':
            return [
              {
                'sceneId': 'scene.dispatch.model',
                'providerProfileId': 'provider-1',
                'modelId': 'gpt-4o',
              },
            ];
          case 'deleteModelProviderProfile':
            return {
              'profiles': [
                {
                  'id': 'provider-2',
                  'name': 'Backup DeepSeek',
                  'baseUrl': 'https://api.deepseek.com',
                  'apiKey': 'sk-2',
                  'hasApiKey': true,
                  'sourceType': 'deepseek',
                  'configured': true,
                  'ready': true,
                },
              ],
              'editingProfileId': 'provider-2',
            };
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

      await tester.tap(find.byKey(const Key('provider-list-more-provider-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Delete Provider'));
      await tester.pumpAndSettle();

      expect(find.textContaining('Agent (gpt-4o)'), findsOneWidget);
      await tester.tap(find.byKey(const Key('provider-confirm-delete-button')));
      await tester.pumpAndSettle();

      expect(find.text('Work OpenAI'), findsNothing);
      expect(find.text('Backup DeepSeek'), findsOneWidget);
    },
  );

  testWidgets(
    'save failure surfaces error banner and preserves user input in fields',
    (tester) async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return profilePayload();
          case 'saveModelProviderProfile':
            throw PlatformException(
              code: 'SAVE_MODEL_PROVIDER_PROFILE_ERROR',
              message: 'storage write failed',
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
      await openFirstProviderDetail(tester);
      await switchToConnectionTab(tester);

      await tester.enterText(
        find.byKey(const Key('provider-name-field')),
        'My Unsaved Custom Name',
      );
      await tester.enterText(
        find.byKey(const Key('provider-api-key-field')),
        'sk-unsaved-secret',
      );
      await tester.tap(find.byKey(const Key('provider-save-button')));
      await tester.pumpAndSettle();

      // User input is preserved even after save failure.
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('provider-name-field')))
            .controller
            ?.text,
        'My Unsaved Custom Name',
      );
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('provider-api-key-field')))
            .controller
            ?.text,
        'sk-unsaved-secret',
      );
      expect(
        find.byKey(const Key('provider-connection-status-banner')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'batch management distinguishes hiding remote models from deleting manual models',
    (tester) async {
      _discoveryModels['provider-1'] = const [
        ProviderModelOption(id: 'gpt-4o', displayName: 'GPT-4o'),
      ];
      await ModelProviderConfigService.saveManualModelIds(
        profileId: 'provider-1',
        ids: const ['my-manual-model'],
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: const ModelProviderSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
      await openFirstProviderDetail(tester);

      // Long-press a model row to enter batch selection mode.
      await tester.longPress(
        find.byKey(const ValueKey<String>('provider-model-my-manual-model')),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('provider-model-batch-bar')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('provider-batch-delete-manual-button')),
        findsOneWidget,
      );

      // Delete the manual model via batch action with confirmation.
      await tester.tap(
        find.byKey(const Key('provider-batch-delete-manual-button')),
      );
      await tester.pumpAndSettle();
      await tester.tap(
        find.descendant(
          of: find.byType(OmniGlassAlertDialog),
          matching: find.byType(TextButton),
        ).last,
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey<String>('provider-model-my-manual-model')),
        findsNothing,
      );
      // Remote model remains intact.
      expect(
        find.byKey(const ValueKey<String>('provider-model-gpt-4o')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'renders cleanly in dark mode with large text scale without overflow',
    (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(360, 800);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);

      _discoveryModels['provider-1'] = const [
        ProviderModelOption(id: 'gpt-4o', displayName: 'GPT-4o'),
      ];

      await tester.pumpWidget(
        MediaQuery(
          data: const MediaQueryData(textScaler: TextScaler.linear(1.3)),
          child: MaterialApp(
            theme: AppTheme.darkTheme,
            home: const ModelProviderSettingPage(),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);

      await openFirstProviderDetail(tester);
      expect(tester.takeException(), isNull);

      await switchToConnectionTab(tester);
      expect(tester.takeException(), isNull);
    },
  );
}
