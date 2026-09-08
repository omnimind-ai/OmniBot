import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/widgets/conversation_model_selector.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <String>[];
  late Future<List<dynamic>> Function(String) fetch;
  var includeUnconfiguredPreset = false;
  setUp(() async {
    calls.clear();
    includeUnconfiguredPreset = false;
    SharedPreferences.setMockInitialValues({
      'cached_provider_models_with_base_v2':
          '{"p1":{"models":[{"id":"obsolete"}]}}',
    });
    await StorageService.init();
    ModelsDevCatalogService.setCatalogForTesting(
      ModelsDevCatalogService.parseCatalog('{}'),
    );
    fetch = (id) async => [
      {'id': '$id-model'},
    ];
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'listModelProviderProfiles') {
        return {
          'profiles': [
            if (includeUnconfiguredPreset)
              {
                'id': 'deepseek-official',
                'name': 'DeepSeek',
                'configured': true,
                'sourceType': 'deepseek',
                'hasApiKey': false,
                'revision': 1,
                'baseUrl': 'https://api.deepseek.com',
              },
            for (final id in ['p1', 'p2'])
              {
                'id': id,
                'name': id,
                'configured': true,
                'revision': 1,
                'baseUrl': 'https://$id.example/v1',
              },
          ],
          'editingProfileId': 'p1',
        };
      }
      if (call.method == 'fetchProviderModels') {
        final args = call.arguments as Map;
        expect(args['forceRefresh'], true);
        final id = args['profileId'] as String;
        calls.add(id);
        return fetch(id);
      }
      throw StateError('Unexpected method ${call.method}');
    });
  });
  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    ModelsDevCatalogService.resetForTesting();
  });

  test(
    'fresh queries ignore stored launch metadata without deleting it',
    () async {
      await ModelProviderConfigService.saveManualModelIds(
        profileId: 'p1',
        ids: ['manual'],
      );
      final groups = await ModelProviderConfigService.refreshChatModelGroups();
      expect(
        groups.first.models.map((m) => m.id),
        containsAll(['manual', 'p1-model']),
      );
      expect(groups.first.models.map((m) => m.id), isNot(contains('obsolete')));
      final stored =
          await ModelProviderConfigService.getStoredModelOptionsForProfile(
            'p1',
          );
      expect(stored.map((m) => m.id), ['manual']);
      expect(
        StorageService.getString('cached_provider_models_with_base_v2'),
        isNotNull,
      );
      fetch = (_) async => throw PlatformException(code: 'offline');
      await expectLater(
        ModelProviderConfigService.refreshChatModelGroups(),
        throwsA(isA<PlatformException>()),
      );
      expect(
        (await ModelProviderConfigService.getManualModelIds(profileId: 'p1')),
        ['manual'],
      );
    },
  );

  Widget selector({ValueChanged<ConversationModelSelection>? onSelect}) =>
      MaterialApp(
        home: Scaffold(
          body: ConversationModelSelectorContent(
            width: 320,
            maxHeight: 600,
            loadLiveProviders: true,
            onSelect: onSelect,
            showSearchField: false,
          ),
        ),
      );

  testWidgets('slow provider does not block selecting a completed provider', (
    tester,
  ) async {
    final slow = Completer<List<dynamic>>();
    fetch = (id) async => id == 'p1'
        ? slow.future
        : [
            {'id': 'ready'},
          ];
    ConversationModelSelection? selected;
    await tester.pumpWidget(selector(onSelect: (value) => selected = value));
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 30));
    }
    expect(find.text('ready'), findsOneWidget);
    expect(find.byType(LinearProgressIndicator), findsOneWidget);
    await tester.tap(find.text('ready'));
    expect(selected?.providerProfileId, 'p2');
    await tester.pumpWidget(const SizedBox());
    slow.complete([
      {'id': 'late'},
    ]);
    await tester.pump();
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'failed provider retries independently and reopening fetches again',
    (tester) async {
      var failing = true;
      fetch = (id) async {
        if (id == 'p1' && failing) throw PlatformException(code: 'offline');
        return [
          {'id': '$id-model'},
        ];
      };
      await tester.pumpWidget(selector());
      await tester.pumpAndSettle();
      expect(find.text('模型加载失败，重试'), findsOneWidget);
      expect(find.text('该 Provider 暂无可选模型'), findsNothing);
      expect(find.text('p2-model'), findsOneWidget);
      failing = false;
      await tester.tap(find.text('模型加载失败，重试'));
      await tester.pumpAndSettle();
      expect(find.text('p1-model'), findsOneWidget);
      expect(calls.where((id) => id == 'p2').length, 1);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpWidget(selector());
      await tester.pumpAndSettle();
      expect(calls.where((id) => id == 'p2').length, 2);
    },
  );

  testWidgets(
    'preset without credentials is not requested while anonymous custom providers still load',
    (tester) async {
      includeUnconfiguredPreset = true;
      await tester.pumpWidget(selector());
      await tester.pumpAndSettle();
      expect(find.text('DeepSeek'), findsNothing);
      expect(find.text('请先配置此服务商的密钥'), findsNothing);
      expect(calls, containsAll(['p1', 'p2']));
      expect(calls, isNot(contains('deepseek-official')));
      expect(find.text('模型加载失败，重试'), findsNothing);
    },
  );

  testWidgets(
    'credential failure retains useful error without claiming no models',
    (tester) async {
      fetch = (id) async => throw PlatformException(
        code: 'FETCH_PROVIDER_MODELS_ERROR',
        details: {'failureKind': 'provider_authentication_failed'},
      );
      await tester.pumpWidget(selector());
      await tester.pumpAndSettle();
      expect(
        find.text(
          'Could not verify the model connection. Check its address and key in model settings.',
        ),
        findsNWidgets(2),
      );
      expect(find.text('该 Provider 暂无可选模型'), findsNothing);
    },
  );
}
