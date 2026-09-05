import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';

class ModelProviderConfig {
  final String id;
  final String name;
  final String baseUrl;
  final String apiKey;
  final Map<String, String> customHeaders;
  final bool hasApiKey;
  final bool hasCustomHeaders;
  final String source;
  final String providerType;
  final bool readOnly;
  final bool ready;
  final String statusText;
  final bool configured;
  final String wireApi;

  const ModelProviderConfig({
    required this.id,
    required this.name,
    required this.baseUrl,
    required this.apiKey,
    required this.customHeaders,
    this.hasApiKey = false,
    this.hasCustomHeaders = false,
    required this.source,
    required this.providerType,
    required this.readOnly,
    required this.ready,
    required this.statusText,
    required this.configured,
    required this.wireApi,
  });

  factory ModelProviderConfig.empty() {
    return const ModelProviderConfig(
      id: '',
      name: '',
      baseUrl: '',
      apiKey: '',
      customHeaders: <String, String>{},
      hasApiKey: false,
      hasCustomHeaders: false,
      source: 'none',
      providerType: 'custom',
      readOnly: false,
      ready: false,
      statusText: '',
      configured: false,
      wireApi: 'chat_completions',
    );
  }

  factory ModelProviderConfig.fromMap(Map<dynamic, dynamic>? map) {
    if (map == null) {
      return ModelProviderConfig.empty();
    }
    final apiKey = (map['apiKey'] ?? '').toString();
    return ModelProviderConfig(
      id: (map['id'] ?? '').toString(),
      name: (map['name'] ?? '').toString(),
      baseUrl: (map['baseUrl'] ?? '').toString(),
      apiKey: apiKey,
      customHeaders: const <String, String>{},
      hasApiKey: map['hasApiKey'] == true || apiKey.isNotEmpty,
      hasCustomHeaders: map['hasCustomHeaders'] == true,
      source: (map['source'] ?? 'none').toString(),
      providerType: (map['providerType'] ?? 'custom').toString(),
      readOnly: map['readOnly'] == true,
      ready: map['ready'] == true,
      statusText: (map['statusText'] ?? '').toString(),
      configured: map['configured'] == true,
      wireApi: (map['wireApi'] ?? 'chat_completions').toString(),
    );
  }
}

class ModelProviderProfileSummary {
  final String id;
  final String name;
  final String baseUrl;
  final String apiKey;
  final Map<String, String> customHeaders;
  final bool hasApiKey;
  final bool hasCustomHeaders;
  final String sourceType;
  final bool readOnly;
  final bool ready;
  final String statusText;
  final bool configured;
  final int revision;
  final String protocolType;
  final String wireApi;

  const ModelProviderProfileSummary({
    required this.id,
    required this.name,
    required this.baseUrl,
    required this.apiKey,
    required this.customHeaders,
    this.hasApiKey = false,
    this.hasCustomHeaders = false,
    required this.sourceType,
    required this.readOnly,
    required this.ready,
    required this.statusText,
    required this.configured,
    this.revision = 0,
    this.protocolType = 'openai_compatible',
    this.wireApi = 'chat_completions',
  });

  factory ModelProviderProfileSummary.fromMap(Map<dynamic, dynamic>? map) {
    final apiKey = (map?['apiKey'] ?? '').toString();
    return ModelProviderProfileSummary(
      id: (map?['id'] ?? '').toString(),
      name: (map?['name'] ?? '').toString(),
      baseUrl: (map?['baseUrl'] ?? '').toString(),
      apiKey: apiKey,
      customHeaders: const <String, String>{},
      hasApiKey: map?['hasApiKey'] == true || apiKey.isNotEmpty,
      hasCustomHeaders: map?['hasCustomHeaders'] == true,
      sourceType: (map?['sourceType'] ?? 'custom').toString(),
      readOnly: map?['readOnly'] == true,
      ready: map?['ready'] == true,
      statusText: (map?['statusText'] ?? '').toString(),
      configured: map?['configured'] == true,
      revision: (map?['revision'] as num?)?.toInt() ?? 0,
      protocolType: (map?['protocolType'] ?? 'openai_compatible').toString(),
      wireApi: (map?['wireApi'] ?? 'chat_completions').toString(),
    );
  }

  ModelProviderConfig toConfig({String source = 'profile'}) {
    return ModelProviderConfig(
      id: id,
      name: name,
      baseUrl: baseUrl,
      apiKey: apiKey,
      customHeaders: customHeaders,
      hasApiKey: hasApiKey,
      hasCustomHeaders: hasCustomHeaders,
      source: source,
      providerType: sourceType,
      readOnly: readOnly,
      ready: ready,
      statusText: statusText,
      configured: configured,
      wireApi: wireApi,
    );
  }
}

class ModelProviderProfilesPayload {
  final List<ModelProviderProfileSummary> profiles;
  final String editingProfileId;

  const ModelProviderProfilesPayload({
    required this.profiles,
    required this.editingProfileId,
  });

  factory ModelProviderProfilesPayload.fromMap(Map<dynamic, dynamic>? map) {
    final profiles = ((map?['profiles'] as List?) ?? const [])
        .map((item) => ModelProviderProfileSummary.fromMap(item as Map?))
        .where((item) => item.id.isNotEmpty)
        .toList();
    final editingProfileId = (map?['editingProfileId'] ?? '').toString();
    return ModelProviderProfilesPayload(
      profiles: profiles,
      editingProfileId: editingProfileId,
    );
  }
}

class ProviderModelOption {
  final String id;
  final String displayName;
  final String? ownedBy;
  final int? contextLimit;
  final int? inputLimit;
  final int? outputLimit;
  final List<String> inputModalities;
  final List<String> outputModalities;
  final String? modelsDevProviderId;
  final String? modelsDevProviderName;
  final String? providerLogoUrl;
  final String? family;
  final String? group;
  final bool? attachment;
  final bool? reasoning;
  final bool? toolCall;
  final bool? structuredOutput;
  final bool? temperature;

  const ProviderModelOption({
    required this.id,
    required this.displayName,
    this.ownedBy,
    this.contextLimit,
    this.inputLimit,
    this.outputLimit,
    this.inputModalities = const [],
    this.outputModalities = const [],
    this.modelsDevProviderId,
    this.modelsDevProviderName,
    this.providerLogoUrl,
    this.family,
    this.group,
    this.attachment,
    this.reasoning,
    this.toolCall,
    this.structuredOutput,
    this.temperature,
  });

  factory ProviderModelOption.fromMap(Map<dynamic, dynamic>? map) {
    return ProviderModelOption(
      id: (map?['id'] ?? '').toString(),
      displayName: (map?['displayName'] ?? map?['id'] ?? '').toString(),
      ownedBy: map?['ownedBy']?.toString(),
      contextLimit: _readInt(map?['contextLimit']),
      inputLimit: _readInt(map?['inputLimit']),
      outputLimit: _readInt(map?['outputLimit']),
      inputModalities: _readStringList(map?['inputModalities']),
      outputModalities: _readStringList(map?['outputModalities']),
      modelsDevProviderId: _readNonEmptyString(map?['modelsDevProviderId']),
      modelsDevProviderName: _readNonEmptyString(map?['modelsDevProviderName']),
      providerLogoUrl: _readNonEmptyString(map?['providerLogoUrl']),
      family: _readNonEmptyString(map?['family']),
      group: _readNonEmptyString(map?['group']),
      attachment: _readBool(map?['attachment']),
      reasoning: _readBool(map?['reasoning']),
      toolCall: _readBool(map?['toolCall']),
      structuredOutput: _readBool(map?['structuredOutput']),
      temperature: _readBool(map?['temperature']),
    );
  }

  ProviderModelOption copyWith({
    String? id,
    String? displayName,
    String? ownedBy,
    int? contextLimit,
    int? inputLimit,
    int? outputLimit,
    List<String>? inputModalities,
    List<String>? outputModalities,
    String? modelsDevProviderId,
    String? modelsDevProviderName,
    String? providerLogoUrl,
    String? family,
    String? group,
    bool? attachment,
    bool? reasoning,
    bool? toolCall,
    bool? structuredOutput,
    bool? temperature,
  }) {
    return ProviderModelOption(
      id: id ?? this.id,
      displayName: displayName ?? this.displayName,
      ownedBy: ownedBy ?? this.ownedBy,
      contextLimit: contextLimit ?? this.contextLimit,
      inputLimit: inputLimit ?? this.inputLimit,
      outputLimit: outputLimit ?? this.outputLimit,
      inputModalities: inputModalities ?? this.inputModalities,
      outputModalities: outputModalities ?? this.outputModalities,
      modelsDevProviderId: modelsDevProviderId ?? this.modelsDevProviderId,
      modelsDevProviderName:
          modelsDevProviderName ?? this.modelsDevProviderName,
      providerLogoUrl: providerLogoUrl ?? this.providerLogoUrl,
      family: family ?? this.family,
      group: group ?? this.group,
      attachment: attachment ?? this.attachment,
      reasoning: reasoning ?? this.reasoning,
      toolCall: toolCall ?? this.toolCall,
      structuredOutput: structuredOutput ?? this.structuredOutput,
      temperature: temperature ?? this.temperature,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'displayName': displayName,
      'ownedBy': ownedBy,
      'contextLimit': contextLimit,
      'inputLimit': inputLimit,
      'outputLimit': outputLimit,
      'inputModalities': inputModalities,
      'outputModalities': outputModalities,
      'modelsDevProviderId': modelsDevProviderId,
      'modelsDevProviderName': modelsDevProviderName,
      'providerLogoUrl': providerLogoUrl,
      'family': family,
      'group': group,
      'attachment': attachment,
      'reasoning': reasoning,
      'toolCall': toolCall,
      'structuredOutput': structuredOutput,
      'temperature': temperature,
    };
  }

  static int? _readInt(Object? value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  static String? _readNonEmptyString(Object? value) {
    final normalized = value?.toString().trim() ?? '';
    return normalized.isEmpty ? null : normalized;
  }

  static List<String> _readStringList(Object? value) {
    if (value is List) {
      return value
          .map((item) => item.toString().trim().toLowerCase())
          .where((item) => item.isNotEmpty)
          .toSet()
          .toList();
    }
    final raw = value?.toString().trim() ?? '';
    if (raw.isEmpty) return const [];
    return raw
        .split(',')
        .map((item) => item.trim().toLowerCase())
        .where((item) => item.isNotEmpty)
        .toSet()
        .toList();
  }

  static bool? _readBool(Object? value) {
    if (value is bool) return value;
    final normalized = value?.toString().trim().toLowerCase() ?? '';
    if (normalized == 'true') return true;
    if (normalized == 'false') return false;
    return null;
  }
}

class ProviderModelGroup {
  final ModelProviderProfileSummary profile;
  final List<ProviderModelOption> models;

  const ProviderModelGroup({required this.profile, required this.models});
}

class ModelProviderConfigService {
  static const String _kOfficialProfileId = 'omnibot-official-ai';
  static const String _kOfficialSourceType = 'omnibot_official';
  static const String _kOfficialProfileName = 'OmniBot 官方 AI';
  static const String _kManualModelIdsKey = 'manual_provider_model_ids_v2';
  static const String _kHiddenChatModelIdsKey =
      'hidden_chat_provider_model_ids_v1';
  static const String _kCachedFetchedModelsKey =
      'cached_provider_models_with_base_v2';
  static const String _kLegacyManualModelIdsKey =
      'manual_provider_model_ids_v1';
  static const String _kLegacyCachedFetchedModelsKey =
      'cached_provider_models_with_base_v1';
  static const String _kDirectRequestUrlMarker = '#';
  static const Set<String> _kForbiddenCustomHeaderNames = <String>{
    'host',
    'content-length',
    'connection',
    'transfer-encoding',
  };
  static const List<String> _kCanonicalEndpointSuffixes = <String>[
    '/v1/chat/completions',
    '/chat/completions',
    '/v1/responses',
    '/responses',
    '/v1/models',
    '/models',
    '/v1/messages',
    '/messages',
  ];
  static const List<String> _kCanonicalVersionBaseSuffixes = <String>[
    '/v1',
    '/compatible-mode/v1',
  ];
  static String _canonicalProfileId(String profileId) {
    return profileId.trim();
  }

  static Future<ModelProviderConfig> getConfig() async {
    try {
      final result = await AssistsMessageService.assistCore
          .invokeMethod<Map<dynamic, dynamic>>('getModelProviderConfig');
      return ModelProviderConfig.fromMap(result);
    } on PlatformException {
      return ModelProviderConfig.empty();
    }
  }

  static Future<ModelProviderProfilesPayload> listProfiles() async {
    try {
      final result = await AssistsMessageService.assistCore
          .invokeMethod<Map<dynamic, dynamic>>('listModelProviderProfiles');
      final payload = ModelProviderProfilesPayload.fromMap(result);
      // A clean install can legitimately have no editable profile yet.  Keep
      // the configuration page usable so the user can register the first
      // Provider instead of rendering an empty page and losing the save path.
      if (payload.profiles.isNotEmpty) return payload;
      return _emptyEditableProfilePayload();
    } on PlatformException {
      return _emptyEditableProfilePayload();
    }
  }

  static ModelProviderProfilesPayload _emptyEditableProfilePayload() {
    const profile = ModelProviderProfileSummary(
      id: 'profile-1',
      name: 'Provider 1',
      baseUrl: '',
      apiKey: '',
      customHeaders: <String, String>{},
      sourceType: 'custom',
      readOnly: false,
      ready: false,
      statusText: '',
      configured: false,
      wireApi: 'chat_completions',
    );
    return const ModelProviderProfilesPayload(
      profiles: <ModelProviderProfileSummary>[profile],
      editingProfileId: 'profile-1',
    );
  }

  static Future<ModelProviderProfileSummary> saveProfile({
    String? id,
    required String name,
    required String baseUrl,
    String? apiKey,
    Map<String, String>? customHeaders,
    bool clearApiKey = false,
    bool clearCustomHeaders = false,
    String sourceType = 'custom',
    String protocolType = 'openai_compatible',
    String? wireApi,
  }) async {
    final resolvedWireApi = inferWireApi(
      baseUrl,
      explicitWireApi: wireApi,
      protocolType: protocolType,
    );
    final normalizedCustomHeaders = customHeaders == null
        ? null
        : normalizeCustomHeaders(customHeaders);
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('saveModelProviderProfile', {
          if (id != null && id.trim().isNotEmpty) 'id': id.trim(),
          'name': name,
          'baseUrl': baseUrl,
          if (apiKey != null) 'apiKey': apiKey,
          if (apiKey != null) 'replaceApiKey': true,
          if (clearApiKey) 'clearApiKey': true,
          if (normalizedCustomHeaders != null)
            'customHeaders': normalizedCustomHeaders,
          if (normalizedCustomHeaders != null) 'replaceCustomHeaders': true,
          if (clearCustomHeaders) 'clearCustomHeaders': true,
          'sourceType': sourceType,
          'protocolType': protocolType,
          'wireApi': resolvedWireApi,
        });
    final saved = ModelProviderProfileSummary.fromMap(result);
    // Provider credentials/endpoint changes invalidate the previously
    // verified catalog. The next explicit refresh repopulates the same
    // persisted Provider document with the new profile revision.
    await invalidateCachedFetchedModels(saved.id);
    return saved;
  }

  static Future<ModelProviderProfilesPayload> deleteProfile(
    String profileId,
  ) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('deleteModelProviderProfile', {
          'profileId': profileId,
        });
    return ModelProviderProfilesPayload.fromMap(result);
  }

  static Future<ModelProviderProfileSummary> setEditingProfile(
    String profileId,
  ) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('setEditingModelProviderProfile', {
          'profileId': profileId,
        });
    return ModelProviderProfileSummary.fromMap(result);
  }

  static Future<ModelProviderConfig> saveConfig({
    required String baseUrl,
    required String apiKey,
    Map<String, String> customHeaders = const <String, String>{},
  }) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('saveModelProviderConfig', {
          'baseUrl': baseUrl,
          'apiKey': apiKey,
          'replaceApiKey': true,
          'customHeaders': normalizeCustomHeaders(customHeaders),
          'replaceCustomHeaders': true,
        });
    return ModelProviderConfig.fromMap(result);
  }

  static Future<ModelProviderConfig> clearConfig() async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<dynamic, dynamic>>('clearModelProviderConfig');
    return ModelProviderConfig.fromMap(result);
  }

  static Future<String?> resolveProviderLogoUrl({
    String providerId = '',
    String providerName = '',
    String apiBase = '',
  }) async {
    final provider = await ModelsDevCatalogService.resolveProvider(
      providerId: providerId,
      providerName: providerName,
      apiBase: apiBase,
    );
    return provider?.logoUrl;
  }

  static Future<List<ProviderModelOption>> enrichModelsForProfile({
    required String profileId,
    required String providerName,
    required String apiBase,
    required List<ProviderModelOption> models,
  }) async {
    if (models.isEmpty) {
      return const [];
    }
    var catalog = const ModelsDevCatalog(providers: {});
    ModelsDevProviderEntry? provider;
    try {
      catalog = await ModelsDevCatalogService.loadCatalog();
      provider = ModelsDevCatalogService.matchProvider(
        catalog: catalog,
        providerId: profileId,
        providerName: providerName,
        apiBase: apiBase,
      );
    } catch (_) {
      catalog = const ModelsDevCatalog(providers: {});
      provider = null;
    }
    final providerGroupId =
        provider?.id ??
        (providerName.trim().isNotEmpty ? providerName.trim() : profileId);
    return models
        .map(
          (item) => _enrichModelOption(
            item: item,
            catalog: catalog,
            provider: provider,
            providerGroupId: providerGroupId,
          ),
        )
        .toList();
  }

  static ProviderModelOption _enrichModelOption({
    required ProviderModelOption item,
    required ModelsDevCatalog catalog,
    required ModelsDevProviderEntry? provider,
    required String providerGroupId,
  }) {
    final modelMatch = catalog.isEmpty
        ? null
        : ModelsDevCatalogService.matchModelMetadata(
            catalog: catalog,
            provider: provider,
            modelId: item.id,
          );
    final metadata = modelMatch?.metadata;
    final metadataProvider = modelMatch?.provider ?? provider;
    final metadataDisplayName = metadata?.name.trim() ?? '';
    final shouldUseMetadataName =
        item.displayName.trim().isEmpty || item.displayName.trim() == item.id;
    final metadataProviderGroupId = metadataProvider?.id ?? providerGroupId;
    return item.copyWith(
      displayName: shouldUseMetadataName && metadataDisplayName.isNotEmpty
          ? metadataDisplayName
          : item.displayName,
      contextLimit: item.contextLimit ?? metadata?.contextLimit,
      inputLimit: item.inputLimit ?? metadata?.inputLimit,
      outputLimit: item.outputLimit ?? metadata?.outputLimit,
      inputModalities: item.inputModalities.isNotEmpty
          ? item.inputModalities
          : (metadata?.inputModalities ?? item.inputModalities),
      outputModalities: item.outputModalities.isNotEmpty
          ? item.outputModalities
          : (metadata?.outputModalities ?? item.outputModalities),
      modelsDevProviderId: metadataProvider?.id,
      modelsDevProviderName: metadataProvider?.name,
      providerLogoUrl: metadataProvider?.logoUrl,
      family: metadata?.family,
      attachment: item.attachment ?? metadata?.attachment,
      reasoning: item.reasoning ?? metadata?.reasoning,
      toolCall: item.toolCall ?? metadata?.toolCall,
      structuredOutput: item.structuredOutput ?? metadata?.structuredOutput,
      temperature: item.temperature ?? metadata?.temperature,
      group: ModelsDevCatalogService.groupModelId(
        item.id,
        providerId: metadataProviderGroupId,
        ownedBy: item.ownedBy ?? '',
      ),
    );
  }

  static Future<List<ProviderModelOption>> fetchModels({
    String apiBase = '',
    String? apiKey,
    Map<String, String>? customHeaders,
    String? profileId,
    String providerName = '',
    String? capability,
    bool forceRefresh = false,
  }) async {
    // Capture the persisted profile before starting the network request. A
    // response obtained for an older profile revision must never replace the
    // cache belonging to a newer endpoint or credential set.
    final targetProfileId = await _resolveProfileId(profileId);
    final profileSnapshot = targetProfileId == null
        ? null
        : await _findProfileById(targetProfileId);
    final result = await AssistsMessageService.assistCore
        .invokeMethod<List<dynamic>>('fetchProviderModels', {
          'apiBase': apiBase,
          if (apiKey != null) 'apiKey': apiKey,
          if (apiKey != null) 'useProvidedApiKey': true,
          if (customHeaders != null)
            'customHeaders': normalizeCustomHeaders(customHeaders),
          if (customHeaders != null) 'useProvidedCustomHeaders': true,
          if (profileId != null && profileId.trim().isNotEmpty)
            'profileId': profileId.trim(),
          if (capability != null && capability.trim().isNotEmpty)
            'capability': capability.trim(),
          if (forceRefresh) 'forceRefresh': true,
          if (profileSnapshot != null)
            'expectedProfileRevision': profileSnapshot.revision,
          if (profileSnapshot != null)
            'expectedProfileBaseUrl': profileSnapshot.baseUrl,
        });
    final models = (result ?? const [])
        .map((item) => ProviderModelOption.fromMap(item as Map?))
        .where((item) => item.id.isNotEmpty)
        .toList();

    // A cold official catalog may not expose its synthetic profile until the
    // forced native refresh finishes. Resolve it again so that first response
    // is cached for subsequent page loads just like an already-ready catalog.
    var cacheProfileSnapshot = profileSnapshot;
    if (cacheProfileSnapshot == null &&
        targetProfileId == _kOfficialProfileId) {
      final refreshedProfile = await _findProfileById(_kOfficialProfileId);
      if (refreshedProfile?.sourceType == _kOfficialSourceType) {
        cacheProfileSnapshot = refreshedProfile;
      }
    }

    var cacheBase = normalizeApiBase(apiBase) ?? '';
    if (cacheBase.isEmpty && cacheProfileSnapshot != null) {
      cacheBase = normalizeApiBase(cacheProfileSnapshot.baseUrl) ?? '';
    }
    if (cacheBase.isEmpty &&
        cacheProfileSnapshot?.sourceType != _kOfficialSourceType) {
      final config = await getConfig();
      cacheBase = normalizeApiBase(config.baseUrl) ?? '';
    }
    var resolvedProviderName = providerName.trim();
    if (resolvedProviderName.isEmpty && cacheProfileSnapshot != null) {
      resolvedProviderName = cacheProfileSnapshot.name;
    }
    final enrichedModels = await enrichModelsForProfile(
      profileId: targetProfileId ?? '',
      providerName: resolvedProviderName,
      apiBase: cacheBase,
      models: models,
    );
    final normalizedCapability = capability?.trim().toLowerCase() ?? '';
    final isNonTextCapabilityScopedOfficialRequest =
        cacheProfileSnapshot?.sourceType == _kOfficialSourceType &&
        normalizedCapability.isNotEmpty &&
        normalizedCapability != 'text';
    // Explicit credential/header overrides are previews, not the saved
    // Provider identity. The settings page saves first when refreshing.
    if (targetProfileId != null &&
        cacheProfileSnapshot != null &&
        apiKey == null &&
        customHeaders == null &&
        !isNonTextCapabilityScopedOfficialRequest) {
      try {
        final latestProfile = await _findProfileById(targetProfileId);
        final requestedBase = normalizeApiBase(apiBase) ?? '';
        final snapshotBase =
            normalizeApiBase(cacheProfileSnapshot.baseUrl) ?? '';
        final requestMatchesSnapshot =
            requestedBase.isEmpty ||
            (snapshotBase.isNotEmpty && requestedBase == snapshotBase);
        if (latestProfile != null &&
            requestMatchesSnapshot &&
            _sameProfileCacheIdentity(cacheProfileSnapshot, latestProfile)) {
          await _saveCachedFetchedModels(
            profileId: targetProfileId,
            apiBase: cacheBase,
            models: enrichedModels,
            profileRevision: cacheProfileSnapshot.revision,
          );
        }
      } catch (_) {
        // ignore cache write failures
      }
    }

    return enrichedModels;
  }

  static Future<List<ProviderModelOption>> getCachedFetchedModels({
    required String profileId,
    String apiBase = '',
    int? profileRevision,
  }) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    await _migrateLegacyStorageIfNeeded(normalizedProfileId);
    final raw = StorageService.getString(
      _kCachedFetchedModelsKey,
      defaultValue: '',
    );
    if (raw == null || raw.trim().isEmpty) {
      return const [];
    }

    final requestedBase = normalizeApiBase(apiBase) ?? '';
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic>) {
        return const [];
      }
      final bucket = decoded[normalizedProfileId];
      if (bucket is! Map<String, dynamic>) {
        return const [];
      }
      final cacheBase = (bucket['apiBase'] ?? '').toString();
      if (requestedBase.isNotEmpty && cacheBase != requestedBase) {
        return const [];
      }
      final cachedRevision = _readCacheRevision(bucket['profileRevision']);
      if (profileRevision != null) {
        if (profileRevision > 0 && cachedRevision != profileRevision) {
          return const [];
        }
        if (profileRevision == 0 &&
            cachedRevision != null &&
            cachedRevision != 0) {
          return const [];
        }
      }
      final modelsRaw = bucket['models'];
      if (modelsRaw is! List) {
        return const [];
      }
      return modelsRaw
          .map((item) => ProviderModelOption.fromMap(item as Map?))
          .where((item) => item.id.isNotEmpty)
          .toList();
    } catch (_) {
      return const [];
    }
  }

  /// Remove the catalog document for a Provider whose credentials or endpoint
  /// changed. This is deliberately local-only; it never starts a replacement
  /// `/models` request. The caller decides when discovery is appropriate.
  static Future<void> invalidateCachedFetchedModels(String profileId) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    if (normalizedProfileId.isEmpty) return;
    await _migrateLegacyStorageIfNeeded(normalizedProfileId);
    final current = _readJsonMap(_kCachedFetchedModelsKey);
    if (!current.containsKey(normalizedProfileId)) return;
    current.remove(normalizedProfileId);
    await StorageService.setString(
      _kCachedFetchedModelsKey,
      jsonEncode(current),
    );
  }

  static Future<void> saveCachedFetchedModels({
    required String profileId,
    required String apiBase,
    required List<ProviderModelOption> models,
    int? profileRevision,
  }) async {
    await _saveCachedFetchedModels(
      profileId: profileId,
      apiBase: apiBase,
      models: models,
      profileRevision: profileRevision,
    );
  }

  static Future<void> _saveCachedFetchedModels({
    required String profileId,
    required String apiBase,
    required List<ProviderModelOption> models,
    int? profileRevision,
  }) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    await _migrateLegacyStorageIfNeeded(normalizedProfileId);
    final current = _readJsonMap(_kCachedFetchedModelsKey);
    final existing = current[normalizedProfileId];
    final existingRevision = existing is Map
        ? _readCacheRevision(existing['profileRevision'])
        : null;
    // The synchronous read/compare/set invocation runs on one Dart isolate,
    // and SharedPreferences updates its local cache as setString is invoked.
    // Thus a late old response observes (and cannot replace) a newer revision
    // without retaining a Future mutex across unrelated Flutter test zones.
    if (existingRevision != null &&
        (profileRevision == null || existingRevision > profileRevision)) {
      return;
    }
    final normalizedBase = normalizeApiBase(apiBase) ?? '';
    current[normalizedProfileId] = {
      'apiBase': normalizedBase,
      if (profileRevision != null) 'profileRevision': profileRevision,
      'models': models.map((item) => item.toMap()).toList(),
    };
    final persisted = await StorageService.setString(
      _kCachedFetchedModelsKey,
      jsonEncode(current),
    );
    if (!persisted) {
      throw StateError('provider model cache persistence failed');
    }
  }

  static Future<List<String>> getManualModelIds({
    required String profileId,
  }) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    await _migrateLegacyStorageIfNeeded(normalizedProfileId);
    final current = _readJsonMap(_kManualModelIdsKey);
    final rawIds = (current[normalizedProfileId] as List?)
        ?.map((item) => item.toString())
        .toList();
    return _normalizeModelIds(rawIds ?? const []);
  }

  static Future<void> saveManualModelIds({
    required String profileId,
    required List<String> ids,
  }) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    await _migrateLegacyStorageIfNeeded(normalizedProfileId);
    final current = _readJsonMap(_kManualModelIdsKey);
    current[normalizedProfileId] = _normalizeModelIds(ids);
    await StorageService.setString(_kManualModelIdsKey, jsonEncode(current));
  }

  static Future<List<String>> getHiddenChatModelIds({
    required String profileId,
  }) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    await _migrateLegacyStorageIfNeeded(normalizedProfileId);
    final current = _readJsonMap(_kHiddenChatModelIdsKey);
    final rawIds = (current[normalizedProfileId] as List?)
        ?.map((item) => item.toString())
        .toList();
    return _normalizeModelIds(rawIds ?? const []);
  }

  static Future<void> saveHiddenChatModelIds({
    required String profileId,
    required List<String> ids,
  }) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    await _migrateLegacyStorageIfNeeded(normalizedProfileId);
    final current = _readJsonMap(_kHiddenChatModelIdsKey);
    current[normalizedProfileId] = _normalizeModelIds(ids);
    await StorageService.setString(
      _kHiddenChatModelIdsKey,
      jsonEncode(current),
    );
  }

  static Future<List<ProviderModelOption>> getStoredModelOptionsForProfile(
    String profileId, {
    ModelProviderProfileSummary? profile,
    bool enrichMetadata = true,
  }) async {
    final normalizedProfileId = _canonicalProfileId(profileId);
    final resolvedProfile = profile ?? await _findProfileById(profileId);
    final isOfficial = resolvedProfile?.sourceType == 'omnibot_official';
    final manualModelIds = isOfficial
        ? const <String>[]
        : await getManualModelIds(profileId: normalizedProfileId);
    final remoteModels = await getCachedFetchedModels(
      profileId: normalizedProfileId,
      apiBase: resolvedProfile?.baseUrl ?? '',
      profileRevision: resolvedProfile?.revision,
    );
    final merged = mergeModelOptions(
      remoteModels: remoteModels,
      manualModelIds: manualModelIds,
    );
    if (!enrichMetadata || merged.isEmpty) {
      return merged;
    }
    return enrichModelsForProfile(
      profileId: normalizedProfileId,
      providerName: resolvedProfile?.name ?? '',
      apiBase: resolvedProfile?.baseUrl ?? '',
      models: merged,
    );
  }

  static Future<List<ProviderModelOption>> getChatModelOptionsForProfile(
    String profileId, {
    ModelProviderProfileSummary? profile,
  }) async {
    final storedModels = await getStoredModelOptionsForProfile(
      profileId,
      profile: profile,
    );
    if (profile?.sourceType == 'omnibot_official') {
      return storedModels;
    }
    final hiddenModelIds = await getHiddenChatModelIds(profileId: profileId);
    return filterChatModelOptions(
      models: storedModels,
      hiddenModelIds: hiddenModelIds,
    );
  }

  static Future<List<ProviderModelGroup>> loadModelGroups() async {
    final payload = await listProfiles();
    final groups = <ProviderModelGroup>[];
    for (final profile in payload.profiles) {
      final models = await getStoredModelOptionsForProfile(
        profile.id,
        profile: profile,
      );
      groups.add(ProviderModelGroup(profile: profile, models: models));
    }
    return groups;
  }

  static Future<List<ProviderModelOption>> _loadChatModelOptionsForProfile(
    ModelProviderProfileSummary profile, {
    required bool refresh,
  }) async {
    if (!profile.configured) {
      return const <ProviderModelOption>[];
    }

    final cached = await getCachedFetchedModels(
      profileId: profile.id,
      apiBase: profile.baseUrl,
      profileRevision: profile.revision,
    );
    final cachedForDisplay = cached;
    final manualIds = profile.sourceType == 'omnibot_official'
        ? const <String>[]
        : await getManualModelIds(profileId: profile.id);
    final hiddenModelIds = await getHiddenChatModelIds(profileId: profile.id);

    List<ProviderModelOption> visibleModels(
      List<ProviderModelOption> remoteModels,
    ) {
      return filterChatModelOptions(
        models: mergeModelOptions(
          remoteModels: remoteModels,
          manualModelIds: manualIds,
        ),
        hiddenModelIds: hiddenModelIds,
      );
    }

    if (!refresh) {
      return visibleModels(cachedForDisplay);
    }

    try {
      final fetched = await fetchModels(
        profileId: profile.id,
        providerName: profile.name,
        capability: 'text',
      );
      // A successful response is the current catalog, including an empty
      // response. Only a failed refresh may reuse the previous document.
      return visibleModels(fetched);
    } catch (_) {
      // Keep only the catalog verified for this exact Provider revision. A
      // credential/endpoint edit must not resurrect an older document merely
      // because the network refresh failed.
      return visibleModels(cached);
    }
  }

  static Future<List<ProviderModelGroup>> loadChatModelGroups({
    bool refresh = false,
  }) async {
    final payload = await listProfiles();
    // Startup and conversation reads are cache-only. Callers must opt into
    // refresh=true only for an explicit catalog refresh action.
    return Future.wait(
      payload.profiles.map((profile) async {
        final models = await _loadChatModelOptionsForProfile(
          profile,
          refresh: refresh,
        );
        return ProviderModelGroup(profile: profile, models: models);
      }),
    );
  }

  /// Forces the small platform-owned text catalog independently of whichever
  /// BYOK/custom profile is currently active in the conversation.
  static Future<ProviderModelGroup?> refreshOfficialChatModelGroup() async {
    var officialProfile = await _findProfileById(_kOfficialProfileId);
    final fetched = await fetchModels(
      profileId: _kOfficialProfileId,
      providerName: officialProfile?.name ?? _kOfficialProfileName,
      capability: 'text',
      forceRefresh: true,
    );
    officialProfile ??= await _findProfileById(_kOfficialProfileId);
    if (officialProfile == null ||
        officialProfile.sourceType != _kOfficialSourceType ||
        !officialProfile.configured) {
      return null;
    }
    final cached = await getChatModelOptionsForProfile(
      officialProfile.id,
      profile: officialProfile,
    );
    return ProviderModelGroup(
      profile: officialProfile,
      models: cached.isNotEmpty ? cached : fetched,
    );
  }

  static List<ProviderModelOption> mergeModelOptions({
    required List<ProviderModelOption> remoteModels,
    required List<String> manualModelIds,
  }) {
    final merged = <ProviderModelOption>[];
    final seen = <String>{};

    for (final modelId in _normalizeModelIds(manualModelIds)) {
      if (seen.add(modelId)) {
        merged.add(
          ProviderModelOption(
            id: modelId,
            displayName: modelId,
            ownedBy: 'manual',
          ),
        );
      }
    }

    for (final item in remoteModels) {
      if (seen.add(item.id)) {
        merged.add(item);
      }
    }
    return merged;
  }

  static List<ProviderModelOption> filterChatModelOptions({
    required List<ProviderModelOption> models,
    required List<String> hiddenModelIds,
  }) {
    if (hiddenModelIds.isEmpty) {
      return List<ProviderModelOption>.from(models);
    }
    final hidden = _normalizeModelIds(hiddenModelIds).toSet();
    return models.where((item) => !hidden.contains(item.id)).toList();
  }

  static String defaultModelGroupName(
    String modelId, {
    String providerId = '',
    String ownedBy = '',
  }) {
    return ModelsDevCatalogService.groupModelId(
      modelId,
      providerId: providerId,
      ownedBy: ownedBy,
    );
  }

  static Future<String?> _resolveProfileId(String? profileId) async {
    if (profileId != null && profileId.trim().isNotEmpty) {
      return _canonicalProfileId(profileId);
    }
    final config = await getConfig();
    final normalized = _canonicalProfileId(config.id);
    return normalized.isEmpty ? null : normalized;
  }

  static Future<ModelProviderProfileSummary?> _findProfileById(
    String profileId,
  ) async {
    final normalized = _canonicalProfileId(profileId);
    if (normalized.isEmpty) {
      return null;
    }
    try {
      final payload = await listProfiles();
      for (final profile in payload.profiles) {
        if (_canonicalProfileId(profile.id) == normalized) {
          return profile;
        }
      }
    } catch (_) {
      // Ignore lookup failures; metadata enrichment can still use base URL.
    }
    return null;
  }

  static bool _sameProfileCacheIdentity(
    ModelProviderProfileSummary left,
    ModelProviderProfileSummary right,
  ) {
    return left.id == right.id &&
        left.revision == right.revision &&
        normalizeApiBase(left.baseUrl) == normalizeApiBase(right.baseUrl) &&
        left.sourceType == right.sourceType &&
        left.configured == right.configured;
  }

  static int? _readCacheRevision(Object? value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  static Map<String, dynamic> _readJsonMap(String key) {
    final raw = StorageService.getString(key, defaultValue: '');
    if (raw == null || raw.trim().isEmpty) {
      return <String, dynamic>{};
    }
    try {
      final decoded = jsonDecode(raw);
      if (decoded is Map<String, dynamic>) {
        return Map<String, dynamic>.from(decoded);
      }
    } catch (_) {
      // ignore broken cache
    }
    return <String, dynamic>{};
  }

  static Future<void> _migrateLegacyStorageIfNeeded(String profileId) async {
    final targetProfileId = _canonicalProfileId(profileId);
    if (targetProfileId.isEmpty) {
      return;
    }

    final currentManual = _readJsonMap(_kManualModelIdsKey);
    if (!currentManual.containsKey(targetProfileId)) {
      final legacyManual = StorageService.getStringList(
        _kLegacyManualModelIdsKey,
        defaultValue: [],
      );
      if (legacyManual != null && legacyManual.isNotEmpty) {
        currentManual[targetProfileId] = _normalizeModelIds(legacyManual);
        await StorageService.setString(
          _kManualModelIdsKey,
          jsonEncode(currentManual),
        );
        await StorageService.remove(_kLegacyManualModelIdsKey);
      }
    }

    final currentCached = _readJsonMap(_kCachedFetchedModelsKey);
    if (!currentCached.containsKey(targetProfileId)) {
      final legacyRaw = StorageService.getString(
        _kLegacyCachedFetchedModelsKey,
        defaultValue: '',
      );
      if (legacyRaw != null && legacyRaw.trim().isNotEmpty) {
        try {
          final decoded = jsonDecode(legacyRaw);
          if (decoded is Map<String, dynamic>) {
            currentCached[targetProfileId] = decoded;
            await StorageService.setString(
              _kCachedFetchedModelsKey,
              jsonEncode(currentCached),
            );
            await StorageService.remove(_kLegacyCachedFetchedModelsKey);
          }
        } catch (_) {
          // ignore
        }
      }
    }
  }

  static List<String> _normalizeModelIds(List<String> ids) {
    final result = <String>[];
    final seen = <String>{};
    for (final raw in ids) {
      final normalized = raw.trim();
      if (!isValidModelName(normalized)) {
        continue;
      }
      if (seen.add(normalized)) {
        result.add(normalized);
      }
    }
    return result;
  }

  static bool isValidApiBase(String value) {
    return normalizeApiBase(value) != null;
  }

  static String inferWireApi(
    String baseUrl, {
    String? explicitWireApi,
    String protocolType = 'openai_compatible',
  }) {
    final normalizedExplicit = explicitWireApi?.trim().toLowerCase();
    if (normalizedExplicit == 'responses' ||
        normalizedExplicit == 'chat_completions') {
      return normalizedExplicit!;
    }
    if (protocolType.trim().toLowerCase() != 'openai_compatible') {
      return 'chat_completions';
    }
    final raw = _stripDirectRequestUrlMarker(baseUrl.trim()).toLowerCase();
    if (raw.endsWith('/v1/responses') || raw.endsWith('/responses')) {
      return 'responses';
    }
    return 'chat_completions';
  }

  static bool _hasDirectRequestUrlMarker(String value) {
    return value.trim().endsWith(_kDirectRequestUrlMarker);
  }

  static String _stripDirectRequestUrlMarker(String value) {
    var result = value.trim();
    if (result.endsWith(_kDirectRequestUrlMarker)) {
      result = result.substring(
        0,
        result.length - _kDirectRequestUrlMarker.length,
      );
    }
    return result.replaceAll(RegExp(r'/+$'), '');
  }

  static bool _hasVersionedBasePath(String value) {
    final normalized = _stripDirectRequestUrlMarker(value).toLowerCase();
    return _kCanonicalVersionBaseSuffixes.any(normalized.endsWith);
  }

  static String? normalizeApiBase(String value) {
    final normalized = value.trim();
    if (normalized.isEmpty) {
      return null;
    }

    final hasDirectRequestUrl = _hasDirectRequestUrlMarker(normalized);
    final candidate = hasDirectRequestUrl
        ? normalized
              .substring(0, normalized.length - _kDirectRequestUrlMarker.length)
              .trim()
        : normalized;
    if (candidate.isEmpty) {
      return null;
    }

    final uri = Uri.tryParse(candidate);
    if (uri == null || !uri.hasScheme || !uri.hasAuthority) {
      return null;
    }
    if (uri.scheme != 'http' && uri.scheme != 'https') {
      return null;
    }

    var result = candidate.replaceAll(RegExp(r'/+$'), '');
    if (!hasDirectRequestUrl) {
      for (final suffix in _kCanonicalEndpointSuffixes) {
        if (result.toLowerCase().endsWith(suffix)) {
          result = result.substring(0, result.length - suffix.length);
          break;
        }
      }
    }
    result = result.replaceAll(RegExp(r'/+$'), '');
    if (result.isEmpty) {
      return null;
    }
    return hasDirectRequestUrl ? '$result$_kDirectRequestUrlMarker' : result;
  }

  static String? buildModelsRequestUrl(String value) {
    return _buildRequestUrl(
      value,
      suffixAfterV1: '/models',
      suffixWithVersion: '/v1/models',
    );
  }

  static String? buildChatCompletionsRequestUrl(String value) {
    return _buildRequestUrl(
      value,
      suffixAfterV1: '/chat/completions',
      suffixWithVersion: '/v1/chat/completions',
    );
  }

  static String? buildResponsesRequestUrl(String value) {
    return _buildRequestUrl(
      value,
      suffixAfterV1: '/responses',
      suffixWithVersion: '/v1/responses',
    );
  }

  static String? buildAnthropicMessagesRequestUrl(String value) {
    return _buildRequestUrl(
      value,
      suffixAfterV1: '/messages',
      suffixWithVersion: '/v1/messages',
    );
  }

  static String? _buildRequestUrl(
    String value, {
    required String suffixAfterV1,
    required String suffixWithVersion,
  }) {
    final normalizedBase = normalizeApiBase(value);
    if (normalizedBase == null) {
      return null;
    }
    final base = _stripDirectRequestUrlMarker(normalizedBase);
    if (_hasDirectRequestUrlMarker(normalizedBase)) {
      return base;
    }
    if (_hasVersionedBasePath(base)) {
      return '$base$suffixAfterV1';
    }
    return '$base$suffixWithVersion';
  }

  static bool isValidModelName(String value) {
    final normalized = value.trim();
    return normalized.isNotEmpty && !normalized.startsWith('scene.');
  }

  static String normalizeCustomHeaderName(String value) {
    return value.trim().toLowerCase();
  }

  static bool isForbiddenCustomHeaderName(String value) {
    return _kForbiddenCustomHeaderNames.contains(
      normalizeCustomHeaderName(value),
    );
  }

  static Map<String, String> normalizeCustomHeaders(
    Map<String, String> headers,
  ) {
    if (headers.isEmpty) {
      return const <String, String>{};
    }
    final normalized = <String, MapEntry<String, String>>{};
    for (final entry in headers.entries) {
      final key = entry.key.trim();
      if (key.isEmpty) {
        continue;
      }
      final normalizedKey = normalizeCustomHeaderName(key);
      if (_kForbiddenCustomHeaderNames.contains(normalizedKey)) {
        continue;
      }
      normalized.remove(normalizedKey);
      normalized[normalizedKey] = MapEntry(key, entry.value);
    }
    return Map<String, String>.unmodifiable(
      normalized.values.fold<Map<String, String>>(<String, String>{}, (
        acc,
        entry,
      ) {
        acc[entry.key] = entry.value;
        return acc;
      }),
    );
  }
}
