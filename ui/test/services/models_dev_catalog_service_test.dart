import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';

const _catalogJson = '''
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
      },
      "gpt-4o-mini": {
        "id": "gpt-4o-mini",
        "name": "GPT-4o mini",
        "limit": {"context": 128000},
        "modalities": {"input": ["text"], "output": ["text"]},
        "family": "gpt",
        "tool_call": true
      }
    }
  },
  "openrouter": {
    "id": "openrouter",
    "name": "OpenRouter",
    "api": "https://openrouter.ai/api/v1",
    "models": {
      "openai/gpt-4o-mini:free": {
        "id": "openai/gpt-4o-mini:free",
        "name": "GPT-4o mini (free)",
        "limit": {"context": 64000},
        "modalities": {"input": ["text"], "output": ["text"]},
        "family": "gpt",
        "tool_call": false
      }
    }
  },
  "alibaba": {
    "id": "alibaba",
    "name": "Alibaba",
    "api": "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
    "models": {
      "qwen-max": {
        "id": "qwen-max",
        "name": "Qwen Max",
        "limit": {"context": 1000000},
        "modalities": {"input": ["text"]}
      },
      "qwen3.5-plus": {
        "id": "qwen3.5-plus",
        "name": "Qwen 3.5 Plus",
        "limit": {"context": 262144},
        "modalities": {"input": ["text"]}
      }
    }
  }
}
''';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const cacheKey = 'models_dev_catalog_cache_v1';
  const pathProvider = MethodChannel('plugins.flutter.io/path_provider');
  late Directory supportDirectory;
  File cacheFile({bool legacy = false}) => File(
    '${supportDirectory.path}/models_dev/'
    '${legacy ? 'legacy_catalog_v1.json' : 'catalog_v1.json'}',
  );

  setUp(() async {
    supportDirectory = await Directory.systemTemp.createTemp(
      'models-dev-test-',
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pathProvider, (call) async {
          expect(call.method, 'getApplicationSupportDirectory');
          return supportDirectory.path;
        });
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
  });

  tearDown(() async {
    await ModelsDevCatalogService.waitForRefreshForTesting();
    ModelsDevCatalogService.resetForTesting();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pathProvider, null);
    await supportDirectory.delete(recursive: true);
  });

  test('migrates raw legacy bytes without parsing or downloading', () async {
    // Intentionally not valid JSON: migration must not parse/re-encode it.
    const raw = '  legacy catalog text \n';
    await StorageService.setString(cacheKey, raw);
    await Future.wait(
      List.generate(8, (_) => ModelsDevCatalogService.migrateLegacyCache()),
    );

    expect(await cacheFile(legacy: true).readAsString(), raw);
    expect(StorageService.getString(cacheKey), isNull);
    expect(await cacheFile().exists(), isFalse);
    expect(await cacheFile().parent.list().map((item) => item.path).toList(), [
      cacheFile(legacy: true).path,
    ]);
  });

  test('a late legacy migration never replaces the downloaded cache', () async {
    final downloaded = jsonEncode({
      'fetchedAt': DateTime.now().millisecondsSinceEpoch,
      'payload': _catalogJson.replaceAll('GPT-4o mini', 'New model name'),
      'etag': '"new"',
    });
    await cacheFile().parent.create(recursive: true);
    await cacheFile().writeAsString(downloaded);
    await StorageService.setString(cacheKey, 'old legacy bytes');

    await ModelsDevCatalogService.migrateLegacyCache();
    final catalog = await ModelsDevCatalogService.loadCatalog(
      client: MockClient((_) async => throw StateError('must use fresh file')),
    );

    expect(await cacheFile().readAsString(), downloaded);
    expect(StorageService.getString(cacheKey), isNull);
    expect(
      catalog.providers['openai']?.findModel('gpt-4o-mini')?.name,
      'New model name',
    );
  });

  test(
    'migration failure retains the preference and its offline catalog',
    () async {
      final raw = jsonEncode({
        'fetchedAt': DateTime.now().millisecondsSinceEpoch,
        'payload': _catalogJson,
        'etag': '"legacy"',
      });
      await StorageService.setString(cacheKey, raw);
      await File('${supportDirectory.path}/models_dev')
          .writeAsString('blocked');

      await ModelsDevCatalogService.migrateLegacyCache();
      final catalog = await ModelsDevCatalogService.loadCatalog(
        client: MockClient((_) async => throw StateError('offline')),
      );

      expect(StorageService.getString(cacheKey), raw);
      expect(catalog.providers['openai']?.findModel('gpt-4o'), isNotNull);
    },
  );

  test('unwritable cache still returns a successful network catalog', () async {
    final raw = jsonEncode({
      'fetchedAt': 1,
      'payload': _catalogJson,
      'etag': '"legacy"',
    });
    await StorageService.setString(cacheKey, raw);
    await File('${supportDirectory.path}/models_dev').writeAsString('blocked');

    final catalog = await ModelsDevCatalogService.loadCatalog(
      forceRefresh: true,
      client: MockClient(
        (_) async => http.Response(
          _catalogJson.replaceAll('GPT-4o mini', 'Downloaded model'),
          200,
        ),
      ),
    );

    expect(
      catalog.providers['openai']?.findModel('gpt-4o-mini')?.name,
      'Downloaded model',
    );
    expect(StorageService.getString(cacheKey), raw);
  });

  test(
    'a corrupt downloaded file falls back to the migrated offline file',
    () async {
      final raw = jsonEncode({
        'fetchedAt': DateTime.now().millisecondsSinceEpoch,
        'payload': _catalogJson,
        'etag': '"legacy"',
      });
      await StorageService.setString(cacheKey, raw);
      await ModelsDevCatalogService.migrateLegacyCache();
      await cacheFile().writeAsString('{truncated');

      final catalog = await ModelsDevCatalogService.loadCatalog(
        client: MockClient((_) async => throw StateError('offline')),
      );

      expect(catalog.providers['openai']?.findModel('gpt-4o'), isNotNull);
      expect(await cacheFile(legacy: true).readAsString(), raw);
    },
  );

  test('deduplicates concurrent catalog refreshes', () async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    final catalog = ModelsDevCatalogService.parseCatalog(_catalogJson);
    final loader = Completer<ModelsDevCatalog>();
    final loadStarted = Completer<void>();
    var loadCount = 0;
    ModelsDevCatalogService.setCatalogLoaderForTesting(() {
      loadCount += 1;
      loadStarted.complete();
      return loader.future;
    });

    final first = ModelsDevCatalogService.loadCatalog();
    final second = ModelsDevCatalogService.loadCatalog();
    await loadStarted.future;

    expect(second, same(first));
    expect(loadCount, 1);
    loader.complete(catalog);
    final results = await Future.wait([first, second]);
    expect(results[0].providers['openai']?.name, 'OpenAI');
    expect(results[1].providers['openai']?.name, 'OpenAI');
  });

  test('parses a downloaded catalog off the main isolate', () async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    final client = MockClient((request) async {
      expect(request.url, Uri.parse(ModelsDevCatalogService.catalogUrl));
      expect(request.headers['accept'], 'application/json');
      return http.Response(
        _catalogJson,
        200,
        headers: const {'etag': '"catalog-v1"'},
      );
    });

    final catalog = await ModelsDevCatalogService.loadCatalog(client: client);

    expect(catalog.providers['openai']?.findModel('gpt-4o'), isNotNull);
    final stored = jsonDecode(await cacheFile().readAsString());
    expect(stored['etag'], '"catalog-v1"');
    expect(StorageService.getString(cacheKey), isNull);

    ModelsDevCatalogService.resetForTesting();
    var fallbackLoadCount = 0;
    ModelsDevCatalogService.setCatalogLoaderForTesting(() async {
      fallbackLoadCount += 1;
      return const ModelsDevCatalog(providers: {});
    });
    final cachedCatalog = await ModelsDevCatalogService.loadCatalog();

    expect(fallbackLoadCount, 0);
    expect(cachedCatalog.providers['openai']?.findModel('gpt-4o'), isNotNull);
  });

  test(
    'returns stale cache immediately and refreshes it conditionally',
    () async {
      final staleFetchedAt = DateTime.now()
          .subtract(const Duration(days: 2))
          .millisecondsSinceEpoch;
      SharedPreferences.setMockInitialValues({
        'models_dev_catalog_cache_v1': jsonEncode({
          'fetchedAt': staleFetchedAt,
          'payload': _catalogJson,
          'etag': '"catalog-v1"',
        }),
      });
      await StorageService.init();

      final response = Completer<http.Response>();
      final requestSeen = Completer<http.Request>();
      final client = MockClient((request) {
        requestSeen.complete(request);
        return response.future;
      });

      final catalog = await ModelsDevCatalogService.loadCatalog(client: client)
          .timeout(const Duration(seconds: 1));
      expect(catalog.providers['openai']?.findModel('gpt-4o'), isNotNull);

      final request = await requestSeen.future;
      expect(request.headers['if-none-match'], '"catalog-v1"');
      response.complete(
        http.Response('', 304, headers: const {'etag': '"catalog-v1"'}),
      );
      await ModelsDevCatalogService.waitForRefreshForTesting();

      final stored =
          jsonDecode(await cacheFile().readAsString()) as Map<String, dynamic>;
      expect(stored['fetchedAt'], greaterThan(staleFetchedAt));
      expect(stored['etag'], '"catalog-v1"');
      expect(stored['payload'], _catalogJson);
      expect(StorageService.getString(cacheKey), isNull);
      expect(await cacheFile(legacy: true).exists(), isFalse);
    },
  );

  test('keeps stale cache when a background refresh fails', () async {
    final staleFetchedAt = DateTime.now()
        .subtract(const Duration(days: 2))
        .millisecondsSinceEpoch;
    SharedPreferences.setMockInitialValues({
      'models_dev_catalog_cache_v1': jsonEncode({
        'fetchedAt': staleFetchedAt,
        'payload': _catalogJson,
        'etag': '"catalog-v1"',
      }),
    });
    await StorageService.init();
    final client = MockClient(
      (_) async => http.Response('upstream unavailable', 503),
    );

    final catalog = await ModelsDevCatalogService.loadCatalog(client: client);
    expect(catalog.providers['openai']?.findModel('gpt-4o'), isNotNull);
    await ModelsDevCatalogService.waitForRefreshForTesting();

    final stored = jsonDecode(
      await cacheFile(legacy: true).readAsString(),
    ) as Map<String, dynamic>;
    expect(stored['fetchedAt'], staleFetchedAt);
    expect(stored['etag'], '"catalog-v1"');
  });

  test('parses models.dev provider and model metadata', () {
    final catalog = ModelsDevCatalogService.parseCatalog(_catalogJson);
    final openai = catalog.providers['openai'];
    final model = openai?.findModel('gpt-4o');

    expect(openai?.name, 'OpenAI');
    expect(openai?.logoUrl, 'https://models.dev/logos/openai.svg');
    expect(model?.contextLimit, 128000);
    expect(model?.inputLimit, 96000);
    expect(model?.outputLimit, 16384);
    expect(model?.inputModalities, ['text', 'image', 'pdf']);
    expect(model?.outputModalities, ['text']);
    expect(model?.family, 'gpt');
    expect(model?.attachment, isTrue);
    expect(model?.toolCall, isTrue);
    expect(model?.structuredOutput, isTrue);
    expect(model?.temperature, isTrue);
  });

  test('matches providers by name, id, and API host', () {
    final catalog = ModelsDevCatalogService.parseCatalog(_catalogJson);

    expect(
      ModelsDevCatalogService.matchProvider(
        catalog: catalog,
        providerName: 'OpenAI',
      )?.id,
      'openai',
    );
    expect(
      ModelsDevCatalogService.matchProvider(
        catalog: catalog,
        providerId: 'alibaba',
      )?.id,
      'alibaba',
    );
    expect(
      ModelsDevCatalogService.matchProvider(
        catalog: catalog,
        apiBase: 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1',
      )?.id,
      'alibaba',
    );
  });

  test('groups models by vendor via model id patterns', () {
    expect(ModelsDevCatalogService.groupModelId('gpt-4o'), 'openai');
    expect(ModelsDevCatalogService.groupModelId('o3-mini'), 'openai');
    expect(ModelsDevCatalogService.groupModelId('chatgpt-4o-latest'), 'openai');
    expect(
      ModelsDevCatalogService.groupModelId('claude-sonnet-4-5'),
      'anthropic',
    );
    expect(
      ModelsDevCatalogService.groupModelId('anthropic/claude-3-opus'),
      'anthropic',
    );
    expect(ModelsDevCatalogService.groupModelId('gemini-2.5-pro'), 'google');
    expect(ModelsDevCatalogService.groupModelId('gemma-7b-it'), 'google');
    expect(
      ModelsDevCatalogService.groupModelId('qwen2.5-72b-instruct'),
      'alibaba',
    );
    expect(ModelsDevCatalogService.groupModelId('qwq-32b'), 'alibaba');
    expect(ModelsDevCatalogService.groupModelId('deepseek-chat'), 'deepseek');
    expect(
      ModelsDevCatalogService.groupModelId('deepseek/deepseek-r1'),
      'deepseek',
    );
    expect(ModelsDevCatalogService.groupModelId('glm-4-plus'), 'zhipu');
    expect(ModelsDevCatalogService.groupModelId('moonshot-v1-8k'), 'moonshot');
    expect(ModelsDevCatalogService.groupModelId('kimi-k2'), 'moonshot');
    expect(ModelsDevCatalogService.groupModelId('doubao-pro-32k'), 'bytedance');
    expect(ModelsDevCatalogService.groupModelId('grok-3'), 'xai');
    expect(
      ModelsDevCatalogService.groupModelId('meta-llama/llama-3.1-70b'),
      'meta',
    );
    expect(
      ModelsDevCatalogService.groupModelId('mistral-large-latest'),
      'mistral',
    );
  });

  test('vendor grouping prefers model id over generic aggregator prefix', () {
    expect(
      ModelsDevCatalogService.groupModelId(
        'openrouter/anthropic/claude-3-haiku',
      ),
      'anthropic',
    );
    expect(
      ModelsDevCatalogService.groupModelId('openrouter/auto'),
      'openrouter',
    );
  });

  test('vendor grouping falls back to ownedBy and providerId', () {
    expect(ModelsDevCatalogService.groupModelId('my-custom-model'), 'other');
    expect(
      ModelsDevCatalogService.groupModelId(
        'my-custom-model',
        ownedBy: 'anthropic',
      ),
      'anthropic',
    );
    expect(
      ModelsDevCatalogService.groupModelId('farui-plus', providerId: 'alibaba'),
      'alibaba',
    );
    expect(
      ModelsDevCatalogService.groupModelId(
        'qwen-max-latest',
        providerId: 'alibaba',
      ),
      'alibaba',
    );
    expect(ModelsDevCatalogService.groupModelId(''), 'other');
  });

  test('matches model ids with prefixes, variants, and provider inference', () {
    final catalog = ModelsDevCatalogService.parseCatalog(_catalogJson);
    final openai = catalog.providers['openai'];
    final openrouter = catalog.providers['openrouter'];

    final officialPrefixed = ModelsDevCatalogService.matchModelMetadata(
      catalog: catalog,
      provider: openai,
      modelId: 'openai/gpt-4o:free',
    );
    expect(officialPrefixed?.provider.id, 'openai');
    expect(officialPrefixed?.metadata.id, 'gpt-4o');

    final routerExact = ModelsDevCatalogService.matchModelMetadata(
      catalog: catalog,
      provider: openrouter,
      modelId: 'openai/gpt-4o-mini:free',
    );
    expect(routerExact?.provider.id, 'openrouter');
    expect(routerExact?.metadata.contextLimit, 64000);

    final inferred = ModelsDevCatalogService.matchModelMetadata(
      catalog: catalog,
      modelId: 'gpt-4o',
    );
    expect(inferred?.provider.id, 'openai');
    expect(inferred?.metadata.contextLimit, 128000);
  });

  test('fuzzy matches model metadata with known variant suffixes', () {
    final catalog = ModelsDevCatalogService.parseCatalog(_catalogJson);
    final alibaba = catalog.providers['alibaba'];

    expect(
      ModelsDevCatalogService.modelLookupCandidates(
        'qwen3.5-plus-thinking-thu',
      ),
      contains('qwen3.5-plus'),
    );
    expect(
      alibaba?.findModel('qwen3.5-plus-thinking-thu')?.contextLimit,
      262144,
    );
  });
}
