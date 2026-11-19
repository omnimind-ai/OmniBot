import 'package:flutter/foundation.dart';
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

typedef SpeechVoiceOption = ({String id, String label, String englishLabel});

class SpeechProviderOption {
  final String id;
  final String label;
  final bool ready;
  final bool recognition;
  final bool synthesis;
  final List<SpeechVoiceOption> voices;

  const SpeechProviderOption({
    required this.id,
    required this.label,
    required this.ready,
    required this.recognition,
    required this.synthesis,
    required this.voices,
  });

  factory SpeechProviderOption.fromMap(Map<dynamic, dynamic> map) {
    return SpeechProviderOption(
      id: map['id'] as String,
      label: map['label'] as String,
      ready: map['ready'] == true,
      recognition: map['recognition'] == true,
      synthesis: map['synthesis'] == true,
      voices: [
        for (final voice
            in (map['voices'] as List? ?? const []).whereType<Map>())
          (
            id: voice['id'] as String,
            label: voice['label'] as String,
            englishLabel: voice['englishLabel'] as String,
          ),
      ],
    );
  }

  @override
  bool operator ==(Object other) =>
      other is SpeechProviderOption &&
      id == other.id &&
      label == other.label &&
      ready == other.ready &&
      recognition == other.recognition &&
      synthesis == other.synthesis &&
      listEquals(voices, other.voices);

  @override
  int get hashCode => Object.hash(
    id,
    label,
    ready,
    recognition,
    synthesis,
    Object.hashAll(voices),
  );
}

class SceneVoiceConfig {
  static const String ttsModeBuiltin = 'builtin';
  static const String ttsModeProvider = 'provider';
  static const String ttsModeCustomCurl = 'custom_curl';

  final bool autoPlay;
  final String voiceId;
  final String stylePreset;
  final String customStyle;
  final String ttsMode;
  final String customCurlCommand;
  final bool hasCustomCurlCommand;
  final String speechRecognitionProviderId;
  final String speechSynthesisProviderId;
  final String speechSynthesisVoiceId;
  final List<SpeechProviderOption> speechProviders;

  const SceneVoiceConfig({
    this.autoPlay = false,
    this.voiceId = 'default_zh',
    this.stylePreset = '默认',
    this.customStyle = '',
    this.ttsMode = ttsModeBuiltin,
    this.customCurlCommand = '',
    this.hasCustomCurlCommand = false,
    this.speechRecognitionProviderId = '',
    this.speechSynthesisProviderId = '',
    this.speechSynthesisVoiceId = '',
    this.speechProviders = const [],
  });

  bool get usesSpeechProvider => ttsMode == ttsModeProvider;

  SpeechProviderOption? get recognitionProvider => speechProviders
      .where(
        (provider) =>
            provider.recognition && provider.id == speechRecognitionProviderId,
      )
      .firstOrNull;

  SpeechProviderOption? get synthesisProvider => speechProviders
      .where(
        (provider) =>
            provider.synthesis && provider.id == speechSynthesisProviderId,
      )
      .firstOrNull;

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
      speechRecognitionProviderId: (map?['speechRecognitionProviderId'] ?? '')
          .toString(),
      speechSynthesisProviderId: (map?['speechSynthesisProviderId'] ?? '')
          .toString(),
      speechSynthesisVoiceId: (map?['speechSynthesisVoiceId'] ?? '').toString(),
      speechProviders: [
        for (final provider
            in (map?['speechProviders'] as List? ?? const []).whereType<Map>())
          SpeechProviderOption.fromMap(provider),
      ],
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
    String? speechRecognitionProviderId,
    String? speechSynthesisProviderId,
    String? speechSynthesisVoiceId,
  }) {
    return SceneVoiceConfig(
      autoPlay: autoPlay ?? this.autoPlay,
      voiceId: voiceId ?? this.voiceId,
      stylePreset: stylePreset ?? this.stylePreset,
      customStyle: customStyle ?? this.customStyle,
      ttsMode: ttsMode ?? this.ttsMode,
      customCurlCommand: customCurlCommand ?? this.customCurlCommand,
      hasCustomCurlCommand: hasCustomCurlCommand ?? this.hasCustomCurlCommand,
      speechRecognitionProviderId:
          speechRecognitionProviderId ?? this.speechRecognitionProviderId,
      speechSynthesisProviderId:
          speechSynthesisProviderId ?? this.speechSynthesisProviderId,
      speechSynthesisVoiceId:
          speechSynthesisVoiceId ?? this.speechSynthesisVoiceId,
      speechProviders: speechProviders,
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
        other.hasCustomCurlCommand == hasCustomCurlCommand &&
        other.speechRecognitionProviderId == speechRecognitionProviderId &&
        other.speechSynthesisProviderId == speechSynthesisProviderId &&
        other.speechSynthesisVoiceId == speechSynthesisVoiceId &&
        listEquals(other.speechProviders, speechProviders);
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
    speechRecognitionProviderId,
    speechSynthesisProviderId,
    speechSynthesisVoiceId,
    Object.hashAll(speechProviders),
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
  static Future<bool> getSceneTitleEnabled() async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('getSceneTitleConfig');
    return result?['enabled'] != false;
  }

  static Future<bool> saveSceneTitleEnabled(bool enabled) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('saveSceneTitleConfig', {
          'enabled': enabled,
        });
    return result?['enabled'] != false;
  }

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
          'speechRecognitionProviderId': config.speechRecognitionProviderId,
          'speechSynthesisProviderId': config.speechSynthesisProviderId,
          'speechSynthesisVoiceId': config.speechSynthesisVoiceId,
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
