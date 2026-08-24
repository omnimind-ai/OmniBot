import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';

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
        "reasoning": false,
        "tool_call": true,
        "structured_output": true,
        "temperature": true
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

  tearDown(ModelsDevCatalogService.resetForTesting);

  test('provider payload exposes API key for the settings editor', () {
    final payload = <String, dynamic>{
      'id': 'provider-1',
      'name': 'Provider',
      'baseUrl': 'https://provider.example/v1',
      'apiKey': 'sk-persisted',
      'customHeaders': {'Authorization': 'custom-header-secret'},
      'hasApiKey': true,
      'hasCustomHeaders': true,
      'revision': 7,
    };
    final config = ModelProviderConfig.fromMap(payload);
    final profile = ModelProviderProfileSummary.fromMap(payload);

    expect(config.apiKey, 'sk-persisted');
    expect(profile.apiKey, 'sk-persisted');
    expect(profile.customHeaders, isEmpty);
    expect(profile.hasApiKey, isTrue);
    expect(profile.hasCustomHeaders, isTrue);
    expect(profile.revision, 7);
  });

  test(
    'save sends replace intent only for explicitly entered secrets',
    () async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        calls.add(call);
        return <String, dynamic>{
          'id': 'provider-1',
          'name': 'Provider',
          'baseUrl': 'https://provider.example/v1',
          'hasApiKey': true,
          'hasCustomHeaders': true,
          'configured': true,
        };
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(assistCoreChannel, null),
      );

      await ModelProviderConfigService.saveProfile(
        id: 'provider-1',
        name: 'Provider',
        baseUrl: 'https://provider.example/v1',
      );
      final preserved = Map<dynamic, dynamic>.from(
        calls.single.arguments as Map,
      );
      expect(preserved.containsKey('apiKey'), isFalse);
      expect(preserved.containsKey('customHeaders'), isFalse);
      expect(preserved['replaceApiKey'], isNull);
      expect(preserved['replaceCustomHeaders'], isNull);

      calls.clear();
      await ModelProviderConfigService.saveProfile(
        id: 'provider-1',
        name: 'Provider',
        baseUrl: 'https://provider.example/v1',
        apiKey: 'replacement',
        customHeaders: const {'X-Provider-Token': 'replacement-header'},
      );
      final replaced = Map<dynamic, dynamic>.from(
        calls.single.arguments as Map,
      );
      expect(replaced['replaceApiKey'], isTrue);
      expect(replaced['replaceCustomHeaders'], isTrue);
    },
  );

  test(
    'fetch binds native credential lookup to one profile revision',
    () async {
      SharedPreferences.setMockInitialValues({});
      await StorageService.init();
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final fetchCalls = <MethodCall>[];
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return <String, dynamic>{
              'profiles': <Map<String, dynamic>>[
                <String, dynamic>{
                  'id': 'provider-1',
                  'name': 'Provider',
                  'baseUrl': 'https://provider.example/v1',
                  'hasApiKey': true,
                  'configured': true,
                  'revision': 9,
                },
              ],
              'editingProfileId': 'provider-1',
            };
          case 'fetchProviderModels':
            fetchCalls.add(call);
            return <Map<String, dynamic>>[
              <String, dynamic>{'id': 'model-1', 'displayName': 'Model 1'},
            ];
          default:
            throw PlatformException(code: 'unexpected_method');
        }
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(assistCoreChannel, null),
      );

      final models = await ModelProviderConfigService.fetchModels(
        apiBase: 'https://provider.example/v1',
        profileId: 'provider-1',
        capability: 'embedding',
      );

      expect(models.single.id, 'model-1');
      final arguments = Map<dynamic, dynamic>.from(
        fetchCalls.single.arguments as Map,
      );
      expect(arguments['expectedProfileRevision'], 9);
      expect(arguments['capability'], 'embedding');
      expect(arguments.containsKey('forceRefresh'), isFalse);
      expect(
        arguments['expectedProfileBaseUrl'],
        'https://provider.example/v1',
      );
    },
  );

  test(
    'explicit official refresh works with active BYOK and a cold cache',
    () async {
      SharedPreferences.setMockInitialValues({});
      await StorageService.init();
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final fetchCalls = <MethodCall>[];
      var officialCatalogReady = false;
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return <String, dynamic>{
              'profiles': <Map<String, dynamic>>[
                <String, dynamic>{
                  'id': 'byok-provider',
                  'name': 'BYOK Provider',
                  'baseUrl': 'https://byok.example/v1',
                  'configured': true,
                  'revision': 4,
                },
                if (officialCatalogReady)
                  <String, dynamic>{
                    'id': 'omnibot-official-ai',
                    'name': 'OmniBot 官方 AI',
                    'sourceType': 'omnibot_official',
                    'readOnly': true,
                    'ready': true,
                    'configured': true,
                    'revision': 0,
                  },
              ],
              'editingProfileId': 'byok-provider',
            };
          case 'fetchProviderModels':
            fetchCalls.add(call);
            officialCatalogReady = true;
            return <Map<String, dynamic>>[
              <String, dynamic>{'id': 'opus-6', 'displayName': 'opus 6☺️'},
            ];
          default:
            throw PlatformException(code: 'unexpected_method');
        }
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(assistCoreChannel, null),
      );

      final group =
          await ModelProviderConfigService.refreshOfficialChatModelGroup();

      expect(group, isNotNull);
      expect(group!.profile.id, 'omnibot-official-ai');
      expect(group.profile.sourceType, 'omnibot_official');
      expect(group.models.single.id, 'opus-6');
      expect(group.models.single.displayName, 'opus 6☺️');
      final arguments = Map<dynamic, dynamic>.from(
        fetchCalls.single.arguments as Map,
      );
      expect(arguments['profileId'], 'omnibot-official-ai');
      expect(arguments['capability'], 'text');
      expect(arguments['forceRefresh'], isTrue);
      expect(
        (await ModelProviderConfigService.getCachedFetchedModels(
          profileId: 'omnibot-official-ai',
          profileRevision: 0,
        )).single.displayName,
        'opus 6☺️',
      );
    },
  );

  test(
    'explicit official refresh replaces an expired cached catalog',
    () async {
      SharedPreferences.setMockInitialValues({});
      await StorageService.init();
      await ModelProviderConfigService.saveCachedFetchedModels(
        profileId: 'omnibot-official-ai',
        apiBase: '',
        profileRevision: 0,
        models: const <ProviderModelOption>[
          ProviderModelOption(id: 'old-model', displayName: 'old-model'),
        ],
      );
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final fetchCalls = <MethodCall>[];
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return <String, dynamic>{
              'profiles': <Map<String, dynamic>>[
                <String, dynamic>{
                  'id': 'byok-provider',
                  'name': 'BYOK Provider',
                  'baseUrl': 'https://byok.example/v1',
                  'configured': true,
                  'revision': 4,
                },
                <String, dynamic>{
                  'id': 'omnibot-official-ai',
                  'name': 'OmniBot 官方 AI',
                  'sourceType': 'omnibot_official',
                  'readOnly': true,
                  'ready': true,
                  'configured': true,
                  'revision': 0,
                },
              ],
              'editingProfileId': 'byok-provider',
            };
          case 'fetchProviderModels':
            fetchCalls.add(call);
            return <Map<String, dynamic>>[
              <String, dynamic>{'id': 'new-model', 'displayName': 'New Model'},
            ];
          default:
            throw PlatformException(code: 'unexpected_method');
        }
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(assistCoreChannel, null),
      );

      final group =
          await ModelProviderConfigService.refreshOfficialChatModelGroup();

      expect(group!.models.map((model) => model.id), <String>['new-model']);
      final arguments = Map<dynamic, dynamic>.from(
        fetchCalls.single.arguments as Map,
      );
      expect(arguments['forceRefresh'], isTrue);
      expect(
        (await ModelProviderConfigService.getCachedFetchedModels(
          profileId: 'omnibot-official-ai',
          profileRevision: 0,
        )).map((model) => model.id),
        <String>['new-model'],
      );
    },
  );

  test('builds request urls from root base url', () {
    expect(
      ModelProviderConfigService.buildModelsRequestUrl(
        'https://api.example.com',
      ),
      'https://api.example.com/v1/models',
    );
    expect(
      ModelProviderConfigService.buildChatCompletionsRequestUrl(
        'https://api.example.com',
      ),
      'https://api.example.com/v1/chat/completions',
    );
    expect(
      ModelProviderConfigService.buildResponsesRequestUrl(
        'https://api.example.com',
      ),
      'https://api.example.com/v1/responses',
    );
  });

  test('allows trailing marker to bypass automatic request suffixes', () {
    expect(
      ModelProviderConfigService.buildChatCompletionsRequestUrl(
        'https://api.example.com/custom/chat#',
      ),
      'https://api.example.com/custom/chat',
    );
    expect(
      ModelProviderConfigService.buildAnthropicMessagesRequestUrl(
        'https://api.example.com/custom/messages#',
      ),
      'https://api.example.com/custom/messages',
    );
  });

  test('builds request urls without duplicating v1 suffix', () {
    expect(
      ModelProviderConfigService.buildModelsRequestUrl(
        'https://api.example.com/v1',
      ),
      'https://api.example.com/v1/models',
    );
    expect(
      ModelProviderConfigService.buildChatCompletionsRequestUrl(
        'https://api.example.com/v1',
      ),
      'https://api.example.com/v1/chat/completions',
    );
  });

  test('builds request urls for compatible-mode versioned base', () {
    expect(
      ModelProviderConfigService.buildModelsRequestUrl(
        'https://dashscope.aliyuncs.com/compatible-mode/v1',
      ),
      'https://dashscope.aliyuncs.com/compatible-mode/v1/models',
    );
    expect(
      ModelProviderConfigService.buildChatCompletionsRequestUrl(
        'https://dashscope.aliyuncs.com/compatible-mode/v1',
      ),
      'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
    );
    expect(
      ModelProviderConfigService.buildResponsesRequestUrl(
        'https://dashscope.aliyuncs.com/compatible-mode/v1',
      ),
      'https://dashscope.aliyuncs.com/compatible-mode/v1/responses',
    );
  });

  test(
    'normalizes explicit endpoint inputs before rebuilding request urls',
    () {
      expect(
        ModelProviderConfigService.buildModelsRequestUrl(
          'https://api.example.com/v1/responses',
        ),
        'https://api.example.com/v1/models',
      );
      expect(
        ModelProviderConfigService.buildChatCompletionsRequestUrl(
          'https://api.example.com/v1/models',
        ),
        'https://api.example.com/v1/chat/completions',
      );
      expect(
        ModelProviderConfigService.buildResponsesRequestUrl(
          'https://api.example.com/v1/chat/completions',
        ),
        'https://api.example.com/v1/responses',
      );
    },
  );

  test('builds anthropic request urls from base url', () {
    expect(
      ModelProviderConfigService.buildAnthropicMessagesRequestUrl(
        'https://api.anthropic.com',
      ),
      'https://api.anthropic.com/v1/messages',
    );
    expect(
      ModelProviderConfigService.buildAnthropicMessagesRequestUrl(
        'https://api.anthropic.com/v1',
      ),
      'https://api.anthropic.com/v1/messages',
    );
    expect(
      ModelProviderConfigService.buildAnthropicMessagesRequestUrl(
        'https://api.anthropic.com/v1/messages',
      ),
      'https://api.anthropic.com/v1/messages',
    );
  });

  test('returns null for invalid base url input', () {
    expect(
      ModelProviderConfigService.buildModelsRequestUrl('api.example.com'),
      isNull,
    );
    expect(
      ModelProviderConfigService.buildChatCompletionsRequestUrl(''),
      isNull,
    );
  });

  test('infers responses wire api from explicit responses endpoint input', () {
    expect(
      ModelProviderConfigService.inferWireApi(
        'https://api.example.com/v1/responses',
      ),
      'responses',
    );
    expect(
      ModelProviderConfigService.inferWireApi(
        'https://api.example.com/responses#',
      ),
      'responses',
    );
    expect(
      ModelProviderConfigService.inferWireApi('https://api.example.com/v1'),
      'chat_completions',
    );
  });

  test('parses legacy cached model options without metadata', () {
    final option = ProviderModelOption.fromMap({
      'id': 'legacy-model',
      'displayName': 'Legacy Model',
      'ownedBy': 'remote',
    });

    expect(option.id, 'legacy-model');
    expect(option.displayName, 'Legacy Model');
    expect(option.contextLimit, isNull);
    expect(option.inputModalities, isEmpty);
  });

  test('enriches model options with models.dev metadata', () async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    ModelsDevCatalogService.setCatalogForTesting(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );

    final enriched = await ModelProviderConfigService.enrichModelsForProfile(
      profileId: 'provider-1',
      providerName: 'OpenAI',
      apiBase: 'https://api.openai.com/v1',
      models: const [ProviderModelOption(id: 'gpt-4o', displayName: 'gpt-4o')],
    );

    expect(enriched.single.displayName, 'GPT-4o');
    expect(enriched.single.contextLimit, 128000);
    expect(enriched.single.inputLimit, 96000);
    expect(enriched.single.outputLimit, 16384);
    expect(enriched.single.inputModalities, ['text', 'image', 'pdf']);
    expect(enriched.single.outputModalities, ['text']);
    expect(enriched.single.attachment, isTrue);
    expect(enriched.single.toolCall, isTrue);
    expect(enriched.single.structuredOutput, isTrue);
    expect(enriched.single.temperature, isTrue);
    expect(
      enriched.single.providerLogoUrl,
      'https://models.dev/logos/openai.svg',
    );
    expect(enriched.single.group, 'openai');
  });

  test('keeps remote limit metadata when catalog fallback is lower', () async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    ModelsDevCatalogService.setCatalogForTesting(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );

    final enriched = await ModelProviderConfigService.enrichModelsForProfile(
      profileId: 'provider-1',
      providerName: 'OpenAI',
      apiBase: 'https://api.openai.com/v1',
      models: const [
        ProviderModelOption(
          id: 'gpt-4o',
          displayName: 'gpt-4o',
          contextLimit: 1000000,
          inputLimit: 800000,
          outputLimit: 32000,
          toolCall: false,
        ),
      ],
    );

    expect(enriched.single.contextLimit, 1000000);
    expect(enriched.single.inputLimit, 800000);
    expect(enriched.single.outputLimit, 32000);
    expect(enriched.single.toolCall, isFalse);
  });

  test(
    'enriches common model ids even when provider is a custom proxy',
    () async {
      SharedPreferences.setMockInitialValues({});
      await StorageService.init();
      ModelsDevCatalogService.setCatalogForTesting(
        ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
      );

      final enriched = await ModelProviderConfigService.enrichModelsForProfile(
        profileId: 'custom-proxy',
        providerName: 'My Proxy',
        apiBase: 'https://llm.example.com/v1',
        models: const [
          ProviderModelOption(id: 'openai/gpt-4o:free', displayName: 'gpt-4o'),
        ],
      );

      expect(enriched.single.contextLimit, 128000);
      expect(enriched.single.modelsDevProviderId, 'openai');
      expect(
        enriched.single.providerLogoUrl,
        'https://models.dev/logos/openai.svg',
      );
      expect(enriched.single.toolCall, isTrue);
    },
  );

  test('filters chat model options by hidden ids and defaults to visible', () {
    const models = [
      ProviderModelOption(id: 'gpt-4o', displayName: 'GPT-4o'),
      ProviderModelOption(id: 'gpt-4o-mini', displayName: 'GPT-4o mini'),
    ];

    expect(
      ModelProviderConfigService.filterChatModelOptions(
        models: models,
        hiddenModelIds: const [],
      ).map((item) => item.id),
      ['gpt-4o', 'gpt-4o-mini'],
    );
    expect(
      ModelProviderConfigService.filterChatModelOptions(
        models: models,
        hiddenModelIds: const ['gpt-4o-mini', 'missing', 'gpt-4o-mini'],
      ).map((item) => item.id),
      ['gpt-4o'],
    );
  });

  test(
    'chat groups refresh every official text model without scene filtering',
    () async {
      SharedPreferences.setMockInitialValues({});
      await StorageService.init();
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final fetchCalls = <MethodCall>[];
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return <String, dynamic>{
              'profiles': <Map<String, dynamic>>[
                <String, dynamic>{
                  'id': 'omnibot-official-ai',
                  'name': 'OmniBot 官方 AI',
                  'sourceType': 'omnibot_official',
                  'readOnly': true,
                  'ready': true,
                  'configured': true,
                  'revision': 0,
                },
              ],
              'editingProfileId': 'omnibot-official-ai',
            };
          case 'getModelProviderConfig':
            return <String, dynamic>{
              'id': 'omnibot-official-ai',
              'name': 'OmniBot 官方 AI',
              'providerType': 'omnibot_official',
              'ready': true,
              'configured': true,
            };
          case 'fetchProviderModels':
            fetchCalls.add(call);
            return <Map<String, dynamic>>[
              <String, dynamic>{'id': 'official-text-a'},
              <String, dynamic>{'id': 'official-text-b'},
            ];
          default:
            throw PlatformException(code: 'unexpected_method');
        }
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(assistCoreChannel, null),
      );

      final groups = await ModelProviderConfigService.loadChatModelGroups();

      expect(groups, hasLength(1));
      expect(groups.single.models.map((model) => model.id), <String>[
        'official-text-a',
        'official-text-b',
      ]);
      expect(fetchCalls, hasLength(1));
      final arguments = Map<dynamic, dynamic>.from(
        fetchCalls.single.arguments as Map,
      );
      expect(arguments['capability'], 'text');
      expect(arguments.containsKey('forceRefresh'), isFalse);
      expect(
        (await ModelProviderConfigService.getCachedFetchedModels(
          profileId: 'omnibot-official-ai',
          profileRevision: 0,
        )).map((model) => model.id),
        <String>['official-text-a', 'official-text-b'],
      );
    },
  );

  test('provider model cache is bound to the profile revision', () async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();

    await ModelProviderConfigService.saveCachedFetchedModels(
      profileId: 'provider-1',
      apiBase: 'https://provider.example/v1',
      profileRevision: 7,
      models: const [
        ProviderModelOption(id: 'revision-7', displayName: 'revision-7'),
      ],
    );

    expect(
      await ModelProviderConfigService.getCachedFetchedModels(
        profileId: 'provider-1',
        apiBase: 'https://provider.example/v1',
        profileRevision: 8,
      ),
      isEmpty,
    );
    expect(
      (await ModelProviderConfigService.getCachedFetchedModels(
        profileId: 'provider-1',
        apiBase: 'https://provider.example/v1',
        profileRevision: 7,
      )).single.id,
      'revision-7',
    );
  });

  test('late older cache write cannot replace a newer revision', () async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();

    await ModelProviderConfigService.saveCachedFetchedModels(
      profileId: 'provider-1',
      apiBase: 'https://provider.example/v1',
      profileRevision: 8,
      models: const [
        ProviderModelOption(id: 'revision-8', displayName: 'revision-8'),
      ],
    );
    await ModelProviderConfigService.saveCachedFetchedModels(
      profileId: 'provider-1',
      apiBase: 'https://provider.example/v1',
      profileRevision: 7,
      models: const [ProviderModelOption(id: 'stale', displayName: 'stale')],
    );

    final cached = await ModelProviderConfigService.getCachedFetchedModels(
      profileId: 'provider-1',
      apiBase: 'https://provider.example/v1',
      profileRevision: 8,
    );
    expect(cached.single.id, 'revision-8');
  });
}
