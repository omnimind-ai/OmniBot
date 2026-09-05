import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const base = 'https://provider.example/v1';

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    ModelsDevCatalogService.setCatalogForTesting(
      ModelsDevCatalogService.parseCatalog('{}'),
    );
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    ModelsDevCatalogService.resetForTesting();
  });

  void respondWith(List<String> modelIds) {
    messenger.setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return {
            'editingProfileId': 'provider-1',
            'profiles': [
              {
                'id': 'provider-1',
                'name': 'Provider',
                'baseUrl': base,
                'configured': true,
                'revision': 7,
              },
            ],
          };
        case 'fetchProviderModels':
          return modelIds.map((id) => {'id': id, 'displayName': id}).toList();
        default:
          throw StateError('Unexpected call: ${call.method}');
      }
    });
  }

  Future<void> seedCache() =>
      ModelProviderConfigService.saveCachedFetchedModels(
        profileId: 'provider-1',
        apiBase: base,
        profileRevision: 7,
        models: const [
          ProviderModelOption(id: 'removed-model', displayName: 'Removed'),
        ],
      );

  for (final response in <List<String>>[
    ['current-model'],
    [],
  ]) {
    test('successful refresh replaces the catalog with $response', () async {
      await seedCache();
      await ModelProviderConfigService.saveManualModelIds(
        profileId: 'provider-1',
        ids: ['manual-model'],
      );
      respondWith(response);

      final refreshed = await ModelProviderConfigService.loadChatModelGroups(
        refresh: true,
      );
      expect(
        refreshed.single.models.map((model) => model.id),
        unorderedEquals([...response, 'manual-model']),
      );
      // Reopening the selector must show the same catalog without a fetch.
      final reopened = await ModelProviderConfigService.loadChatModelGroups();
      expect(
        reopened.single.models.map((model) => model.id),
        unorderedEquals([...response, 'manual-model']),
      );
    });
  }

  for (final overrideKey in [true, false]) {
    test(
      'unsaved ${overrideKey ? 'API key' : 'headers'} cannot poison the saved catalog',
      () async {
        await seedCache();
        respondWith(['preview-model']);

        final preview = await ModelProviderConfigService.fetchModels(
          profileId: 'provider-1',
          apiBase: base,
          apiKey: overrideKey ? 'unsaved-test-key' : null,
          customHeaders: overrideKey ? null : {'X-Test-Account': 'preview'},
        );
        expect(preview.single.id, 'preview-model');
        final saved = await ModelProviderConfigService.getCachedFetchedModels(
          profileId: 'provider-1',
          apiBase: base,
          profileRevision: 7,
        );
        expect(saved.single.id, 'removed-model');
      },
    );
  }
}
