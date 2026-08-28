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

class SceneVoiceConfig {
  static const String ttsModeBuiltin = 'builtin';
  static const String ttsModeCustomCurl = 'custom_curl';

  final bool autoPlay;
  final String voiceId;
  final String stylePreset;
  final String customStyle;
  final String ttsMode;
  final String customCurlCommand;
  final bool hasCustomCurlCommand;

  const SceneVoiceConfig({
    this.autoPlay = false,
    this.voiceId = 'default_zh',
    this.stylePreset = '默认',
    this.customStyle = '',
    this.ttsMode = ttsModeBuiltin,
    this.customCurlCommand = '',
    this.hasCustomCurlCommand = false,
  });

  bool get isCustomCurl => ttsMode == ttsModeCustomCurl;

  factory SceneVoiceConfig.fromMap(Map<dynamic, dynamic>? map) {
    return SceneVoiceConfig(
      autoPlay: map?['autoPlay'] == true,
      voiceId: (map?['voiceId'] ?? 'default_zh').toString(),
      stylePreset: (map?['stylePreset'] ?? '默认').toString(),
      customStyle: (map?['customStyle'] ?? '').toString(),
      ttsMode: (map?['ttsMode'] ?? ttsModeBuiltin).toString(),
      // Native credentials are write-only. Discard any legacy value even if an
      // older native build accidentally includes it in the payload.
      customCurlCommand: '',
      hasCustomCurlCommand: map?['hasCustomCurlCommand'] == true,
    );
  }

  SceneVoiceConfig copyWith({
    bool? autoPlay,
    String? voiceId,
    String? stylePreset,
    String? customStyle,
    String? ttsMode,
    String? customCurlCommand,
    bool? hasCustomCurlCommand,
  }) {
    return SceneVoiceConfig(
      autoPlay: autoPlay ?? this.autoPlay,
      voiceId: voiceId ?? this.voiceId,
      stylePreset: stylePreset ?? this.stylePreset,
      customStyle: customStyle ?? this.customStyle,
      ttsMode: ttsMode ?? this.ttsMode,
      customCurlCommand: customCurlCommand ?? this.customCurlCommand,
      hasCustomCurlCommand: hasCustomCurlCommand ?? this.hasCustomCurlCommand,
    );
  }

  @override
  bool operator ==(Object other) {
    return other is SceneVoiceConfig &&
        other.autoPlay == autoPlay &&
        other.voiceId == voiceId &&
        other.stylePreset == stylePreset &&
        other.customStyle == customStyle &&
        other.ttsMode == ttsMode &&
        other.customCurlCommand == customCurlCommand &&
        other.hasCustomCurlCommand == hasCustomCurlCommand;
  }

  @override
  int get hashCode => Object.hash(
    autoPlay,
    voiceId,
    stylePreset,
    customStyle,
    ttsMode,
    customCurlCommand,
    hasCustomCurlCommand,
  );
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

  static Future<SceneVoiceConfig> getSceneVoiceConfig() async {
    try {
      final result = await AssistsMessageService.assistCore
          .invokeMethod<Map<dynamic, dynamic>>('getSceneVoiceConfig');
      return SceneVoiceConfig.fromMap(result);
    } on PlatformException {
      return const SceneVoiceConfig();
    }
  }

  static Future<SceneVoiceConfig> saveSceneVoiceConfig(
    SceneVoiceConfig config, {
    String? replacementCustomCurlCommand,
    bool clearCustomCurlCommand = false,
  }) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('saveSceneVoiceConfig', {
          'autoPlay': config.autoPlay,
          'voiceId': config.voiceId,
          'stylePreset': config.stylePreset,
          'customStyle': config.customStyle,
          'ttsMode': config.ttsMode,
          if (replacementCustomCurlCommand != null)
            'customCurlCommand': replacementCustomCurlCommand,
          if (replacementCustomCurlCommand != null)
            'replaceCustomCurlCommand': true,
          if (clearCustomCurlCommand) 'clearCustomCurlCommand': true,
        });
    return SceneVoiceConfig.fromMap(result);
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
