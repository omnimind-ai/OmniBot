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

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
  });
  tearDown(ModelsDevCatalogService.resetForTesting);

  test(
    'reading model preferences preserves the native launch catalog across reload',
    () async {
      const key = 'cached_provider_models_with_base_v2';
      const catalog =
          '{"provider-1":{"apiBase":"https://provider.example/v1","profileRevision":7,"models":[{"id":"model-a"},{"id":"model-b"}]}}';
      await StorageService.setString(key, catalog);
      await ModelProviderConfigService.getManualModelIds(
        profileId: 'provider-1',
      );
      await ModelProviderConfigService.getHiddenChatModelIds(
        profileId: 'provider-1',
      );
      expect(StorageService.getString(key), catalog);
      await StorageService.init();
      await ModelProviderConfigService.getManualModelIds(
        profileId: 'provider-1',
      );
      expect(StorageService.getString(key), catalog);
    },
  );

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
}
