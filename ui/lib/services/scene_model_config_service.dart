import 'package:flutter/services.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/model_provider_config_service.dart';

class SceneCatalogItem {
  final String sceneId;
  final String description;
  final String defaultModel;
  final String effectiveModel;
  final String effectiveProviderProfileId;
  final String effectiveProviderProfileName;
  final String boundProviderProfileId;
  final String boundProviderProfileName;
  final String transport;
  final String configSource;
  final bool overrideApplied;
  final String overrideModel;
  final bool providerConfigured;
  final bool bindingExists;
  final bool bindingProfileMissing;

  const SceneCatalogItem({
    required this.sceneId,
    required this.description,
    required this.defaultModel,
    required this.effectiveModel,
    required this.effectiveProviderProfileId,
    required this.effectiveProviderProfileName,
    required this.boundProviderProfileId,
    required this.boundProviderProfileName,
    required this.transport,
    required this.configSource,
    required this.overrideApplied,
    required this.overrideModel,
    required this.providerConfigured,
    required this.bindingExists,
    required this.bindingProfileMissing,
  });

  factory SceneCatalogItem.fromMap(Map<dynamic, dynamic>? map) {
    return SceneCatalogItem(
      sceneId: (map?['sceneId'] ?? '').toString(),
      description: (map?['description'] ?? '').toString(),
      defaultModel: (map?['defaultModel'] ?? '').toString(),
      effectiveModel: (map?['effectiveModel'] ?? '').toString(),
      effectiveProviderProfileId: (map?['effectiveProviderProfileId'] ?? '')
          .toString(),
      effectiveProviderProfileName: (map?['effectiveProviderProfileName'] ?? '')
          .toString(),
      boundProviderProfileId: (map?['boundProviderProfileId'] ?? '').toString(),
      boundProviderProfileName: (map?['boundProviderProfileName'] ?? '')
          .toString(),
      transport: (map?['transport'] ?? '').toString(),
      configSource: (map?['configSource'] ?? '').toString(),
      overrideApplied: map?['overrideApplied'] == true,
      overrideModel: (map?['overrideModel'] ?? '').toString(),
      providerConfigured: map?['providerConfigured'] == true,
      bindingExists: map?['bindingExists'] == true,
      bindingProfileMissing: map?['bindingProfileMissing'] == true,
    );
  }
}

class SceneModelOverrideEntry {
  final String sceneId;
  final String model;

  const SceneModelOverrideEntry({required this.sceneId, required this.model});

  factory SceneModelOverrideEntry.fromMap(Map<dynamic, dynamic>? map) {
    return SceneModelOverrideEntry(
      sceneId: (map?['sceneId'] ?? '').toString(),
      model: (map?['model'] ?? '').toString(),
    );
  }
}

class SceneModelBindingEntry {
  final String sceneId;
  final String providerProfileId;
  final String modelId;

  const SceneModelBindingEntry({
    required this.sceneId,
    required this.providerProfileId,
    required this.modelId,
  });

  factory SceneModelBindingEntry.fromMap(Map<dynamic, dynamic>? map) {
    return SceneModelBindingEntry(
      sceneId: (map?['sceneId'] ?? '').toString(),
      providerProfileId: (map?['providerProfileId'] ?? '').toString(),
      modelId: (map?['modelId'] ?? '').toString(),
    );
  }
}

class SceneOperationConfig {
  final bool useOfficialService;

  const SceneOperationConfig({this.useOfficialService = false});

  factory SceneOperationConfig.fromMap(Map<dynamic, dynamic>? map) {
    return SceneOperationConfig(
      useOfficialService: map?['useOfficialService'] == true,
    );
  }

  SceneOperationConfig copyWith({bool? useOfficialService}) {
    return SceneOperationConfig(
      useOfficialService: useOfficialService ?? this.useOfficialService,
    );
  }

  @override
  bool operator ==(Object other) {
    return other is SceneOperationConfig &&
        other.useOfficialService == useOfficialService;
  }

  @override
  int get hashCode => useOfficialService.hashCode;
}

class SceneModelConfigService {
  static Future<List<SceneCatalogItem>> getSceneCatalog() async {
    try {
      final result = await AssistsMessageService.assistCore
          .invokeMethod<List<dynamic>>('getSceneModelCatalog');
      return (result ?? const [])
          .map((item) => SceneCatalogItem.fromMap(item as Map?))
          .where((item) => item.sceneId.isNotEmpty)
          .toList();
    } on PlatformException {
      return const [];
    }
  }

  static Future<List<SceneModelBindingEntry>> getSceneModelBindings() async {
    try {
      final result = await AssistsMessageService.assistCore
          .invokeMethod<List<dynamic>>('getSceneModelBindings');
      return (result ?? const [])
          .map((item) => SceneModelBindingEntry.fromMap(item as Map?))
          .where(
            (item) =>
                item.sceneId.isNotEmpty &&
                item.providerProfileId.isNotEmpty &&
                item.modelId.isNotEmpty,
          )
          .toList();
    } on PlatformException {
      return const [];
    }
  }

  static Future<List<SceneModelBindingEntry>> saveSceneModelBinding({
    required String sceneId,
    required String providerProfileId,
    required String modelId,
  }) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<List<dynamic>>('saveSceneModelBinding', {
          'sceneId': sceneId,
          'providerProfileId': providerProfileId,
          'modelId': modelId,
        });
    return (result ?? const [])
        .map((item) => SceneModelBindingEntry.fromMap(item as Map?))
        .where(
          (item) =>
              item.sceneId.isNotEmpty &&
              item.providerProfileId.isNotEmpty &&
              item.modelId.isNotEmpty,
        )
        .toList();
  }

  static Future<List<SceneModelBindingEntry>> clearSceneModelBinding(
    String sceneId,
  ) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<List<dynamic>>('clearSceneModelBinding', {
          'sceneId': sceneId,
        });
    return (result ?? const [])
        .map((item) => SceneModelBindingEntry.fromMap(item as Map?))
        .where(
          (item) =>
              item.sceneId.isNotEmpty &&
              item.providerProfileId.isNotEmpty &&
              item.modelId.isNotEmpty,
        )
        .toList();
  }

  static Future<List<SceneModelOverrideEntry>> getSceneModelOverrides() async {
    try {
      final result = await AssistsMessageService.assistCore
          .invokeMethod<List<dynamic>>('getSceneModelOverrides');
      return (result ?? const [])
          .map((item) => SceneModelOverrideEntry.fromMap(item as Map?))
          .where((item) => item.sceneId.isNotEmpty && item.model.isNotEmpty)
          .toList();
    } on PlatformException {
      return const [];
    }
  }

  static Future<List<SceneModelOverrideEntry>> saveSceneModelOverride({
    required String sceneId,
    required String model,
  }) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<List<dynamic>>('saveSceneModelOverride', {
          'sceneId': sceneId,
          'model': model,
        });
    return (result ?? const [])
        .map((item) => SceneModelOverrideEntry.fromMap(item as Map?))
        .where((item) => item.sceneId.isNotEmpty && item.model.isNotEmpty)
        .toList();
  }

  static Future<List<SceneModelOverrideEntry>> clearSceneModelOverride(
    String sceneId,
  ) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<List<dynamic>>('clearSceneModelOverride', {
          'sceneId': sceneId,
        });
    return (result ?? const [])
        .map((item) => SceneModelOverrideEntry.fromMap(item as Map?))
        .where((item) => item.sceneId.isNotEmpty && item.model.isNotEmpty)
        .toList();
  }

  static Future<SceneOperationConfig> getSceneOperationConfig() async {
    try {
      final result = await AssistsMessageService.assistCore
          .invokeMethod<Map<dynamic, dynamic>>('getSceneOperationConfig');
      return SceneOperationConfig.fromMap(result);
    } on PlatformException {
      return const SceneOperationConfig();
    }
  }

  static Future<SceneOperationConfig> saveSceneOperationConfig(
    SceneOperationConfig config,
  ) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('saveSceneOperationConfig', {
          'useOfficialService': config.useOfficialService,
        });
    return SceneOperationConfig.fromMap(result);
  }

  static bool isValidModelName(String value) {
    return ModelProviderConfigService.isValidModelName(value);
  }
}
