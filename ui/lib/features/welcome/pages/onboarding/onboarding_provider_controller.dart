import 'package:flutter/widgets.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/scene_model_config_service.dart';

import 'onboarding_definitions.dart';
import 'onboarding_l10n.dart';

/// Owns the model-provider side of onboarding: the selected provider, the
/// connection form, fetched/manual models, and per-scene model bindings.
class OnboardingProviderController extends ChangeNotifier {
  OnboardingProviderController() {
    _applyProviderOption(providerOptions.first);
  }

  final TextEditingController nameController = TextEditingController();
  final TextEditingController baseUrlController = TextEditingController();
  final TextEditingController apiKeyController = TextEditingController();
  final TextEditingController manualModelController = TextEditingController();

  bool _disposed = false;

  bool _dataLoaded = false;
  bool _loading = false;
  bool _busy = false;
  bool _connected = false;
  bool _obscureApiKey = true;
  String _selectedProviderId = 'deepseek';
  String? _error;
  ModelProviderProfileSummary? _activeProfile;
  List<ModelProviderProfileSummary> _profiles = const [];
  List<ProviderModelOption> _modelOptions = const [];
  Map<String, String> _sceneModelSelections = <String, String>{};
  Set<String> _savingSceneIds = <String>{};
  bool _sceneModelsSaving = false;

  bool get dataLoaded => _dataLoaded;
  bool get loading => _loading;
  bool get busy => _busy;
  bool get connected => _connected;
  bool get obscureApiKey => _obscureApiKey;
  String get selectedProviderId => _selectedProviderId;
  String? get error => _error;
  ModelProviderProfileSummary? get activeProfile => _activeProfile;
  List<ProviderModelOption> get modelOptions => _modelOptions;
  Map<String, String> get sceneModelSelections => _sceneModelSelections;
  Set<String> get savingSceneIds => _savingSceneIds;
  bool get sceneModelsSaving => _sceneModelsSaving;

  ProviderOption get selectedProvider =>
      providerOptions.firstWhere((item) => item.id == _selectedProviderId);

  @override
  void dispose() {
    _disposed = true;
    nameController.dispose();
    baseUrlController.dispose();
    apiKeyController.dispose();
    manualModelController.dispose();
    super.dispose();
  }

  void _emit() {
    if (!_disposed) notifyListeners();
  }

  void toggleObscureApiKey() {
    _obscureApiKey = !_obscureApiKey;
    _emit();
  }

  void selectSceneModel(String sceneId, String modelId) {
    _sceneModelSelections = <String, String>{
      ..._sceneModelSelections,
      sceneId: modelId,
    };
    _emit();
  }

  Future<void> loadData({required OnboardingTranslator t}) async {
    if (_dataLoaded || _loading) return;
    _loading = true;
    _emit();
    try {
      final results = await Future.wait<dynamic>(<Future<dynamic>>[
        ModelProviderConfigService.listProfiles(),
        SceneModelConfigService.getSceneModelBindings(),
      ]);
      if (_disposed) return;
      final payload = results[0] as ModelProviderProfilesPayload;
      final bindings = results[1] as List<SceneModelBindingEntry>;
      final configuredProfiles = payload.profiles
          .where((profile) => profile.configured)
          .toList(growable: false);
      ModelProviderProfileSummary? profile;
      if (configuredProfiles.isNotEmpty) {
        profile = configuredProfiles.firstWhere(
          (item) => item.id == payload.editingProfileId,
          orElse: () => configuredProfiles.first,
        );
      }

      var models = const <ProviderModelOption>[];
      if (profile != null) {
        models =
            await ModelProviderConfigService.getStoredModelOptionsForProfile(
              profile.id,
              profile: profile,
              enrichMetadata: false,
            );
        if (models.isEmpty) {
          // A persisted Provider can be valid while its fetched model cache is
          // still empty (for example on the first launch after installation).
          // Do the same one-shot remote discovery as the Provider settings
          // page so onboarding never presents a false empty model inventory.
          try {
            models = await ModelProviderConfigService.fetchModels(
              profileId: profile.id,
              providerName: profile.name,
              capability: 'text',
            );
          } catch (_) {
            // Keep the empty state; the user can still add a model manually.
          }
        }
      }
      if (_disposed) return;
      _dataLoaded = true;
      _profiles = payload.profiles;
      if (profile != null) {
        _activeProfile = profile;
        _connected = true;
        _modelOptions = models;
        _applyExistingProfile(profile);
        _applyDefaultSceneSelections(bindings: bindings);
      }
      _emit();
    } catch (_) {
      if (_disposed) return;
      _dataLoaded = true;
      _error = t(
        '暂时无法读取已有配置，你仍可创建新的模型连接。',
        'Existing settings could not be loaded. You can still create a new connection.',
      );
      _emit();
    } finally {
      if (!_disposed) {
        _loading = false;
        _emit();
      }
    }
  }

  void _applyExistingProfile(ModelProviderProfileSummary profile) {
    final matching = providerOptions.where((option) {
      if (option.id == 'custom') return false;
      if (option.sourceType != 'custom' &&
          profile.sourceType == option.sourceType) {
        return true;
      }
      final normalizedProfileBase =
          ModelProviderConfigService.normalizeApiBase(profile.baseUrl) ?? '';
      final normalizedOptionBase =
          ModelProviderConfigService.normalizeApiBase(option.baseUrl) ?? '';
      return normalizedOptionBase.isNotEmpty &&
          normalizedProfileBase == normalizedOptionBase;
    });
    final option = matching.isEmpty ? providerOptions.last : matching.first;
    _selectedProviderId = option.id;
    nameController.text = profile.name;
    baseUrlController.text = profile.baseUrl;
    apiKeyController.text = profile.apiKey;
  }

  void applyProviderOption(ProviderOption option) {
    _applyProviderOption(option);
    _emit();
  }

  void _applyProviderOption(ProviderOption option) {
    _selectedProviderId = option.id;
    nameController.text = option.id == 'custom' ? '' : option.label;
    baseUrlController.text = option.baseUrl;
    apiKeyController.clear();
    _activeProfile = null;
    _connected = false;
    _modelOptions = const [];
    _sceneModelSelections = <String, String>{};
    _error = null;
  }

  /// Validates and saves the connection, then fetches available models.
  /// Returns true when the flow may advance to the model inventory page.
  Future<bool> configure({required OnboardingTranslator t}) async {
    if (_busy) return false;
    final option = selectedProvider;
    final name = nameController.text.trim();
    final baseUrl = baseUrlController.text.trim();
    final apiKey = apiKeyController.text.trim();
    if (name.isEmpty) {
      _error = t('请填写提供商名称。', 'Enter a provider name.');
      _emit();
      return false;
    }
    if (!ModelProviderConfigService.isValidApiBase(baseUrl)) {
      _error = t(
        '请输入有效的 HTTP 或 HTTPS API 地址。',
        'Enter a valid HTTP or HTTPS API base URL.',
      );
      _emit();
      return false;
    }
    if (option.id != 'custom' && apiKey.isEmpty) {
      _error = t('此提供商需要 API Key。', 'This provider requires an API key.');
      _emit();
      return false;
    }

    _busy = true;
    _error = null;
    _emit();
    try {
      final normalizedBase =
          ModelProviderConfigService.normalizeApiBase(baseUrl) ?? '';
      final existing = _profiles.where((profile) {
        final profileBase =
            ModelProviderConfigService.normalizeApiBase(profile.baseUrl) ?? '';
        if (option.sourceType != 'custom') {
          return profile.sourceType == option.sourceType;
        }
        return profileBase.isNotEmpty && profileBase == normalizedBase;
      });
      final saved = await ModelProviderConfigService.saveProfile(
        id: existing.isEmpty ? null : existing.first.id,
        name: name,
        baseUrl: baseUrl,
        apiKey: apiKey,
        sourceType: option.sourceType,
        protocolType: option.protocolType,
        wireApi: 'chat_completions',
      );
      List<ProviderModelOption> models = const [];
      String? fetchError;
      try {
        models = await ModelProviderConfigService.fetchModels(
          apiBase: baseUrl,
          apiKey: apiKey,
          profileId: saved.id,
          providerName: saved.name,
        );
        if (models.isEmpty) {
          fetchError = t(
            '连接已保存，但没有读取到模型。你可以在下方手动添加模型 ID。',
            'The connection was saved, but no models were returned. Add a model ID below.',
          );
        }
      } catch (error) {
        fetchError = t(
          '连接已保存，但模型列表读取失败。请检查地址与密钥，或手动添加模型 ID。',
          'The connection was saved, but models could not be fetched. Check the URL and key, or add a model ID.',
        );
      }
      if (_disposed) return false;
      _activeProfile = saved;
      _profiles = <ModelProviderProfileSummary>[
        ..._profiles.where((profile) => profile.id != saved.id),
        saved,
      ];
      _connected = true;
      _modelOptions = models;
      _error = fetchError;
      _applyDefaultSceneSelections();
      _emit();
      return true;
    } catch (error) {
      if (_disposed) return false;
      _error = t(
        '无法保存模型提供商：$error',
        'Could not save the model provider: $error',
      );
      _emit();
      return false;
    } finally {
      if (!_disposed) {
        _busy = false;
        _emit();
      }
    }
  }

  void _applyDefaultSceneSelections({
    List<SceneModelBindingEntry> bindings = const [],
  }) {
    if (_modelOptions.isEmpty) {
      _sceneModelSelections = <String, String>{};
      return;
    }
    final bindingMap = <String, SceneModelBindingEntry>{
      for (final binding in bindings) binding.sceneId: binding,
    };
    final firstGeneral = _modelOptions.firstWhere(
      (model) => !_looksLikeEmbeddingModel(model.id),
      orElse: () => _modelOptions.first,
    );
    final firstEmbedding = _modelOptions.firstWhere(
      (model) => _looksLikeEmbeddingModel(model.id),
      orElse: () => firstGeneral,
    );
    _sceneModelSelections = <String, String>{
      for (final scene in sceneDefinitions)
        scene.id: () {
          final boundModelId = bindingMap[scene.id]?.modelId;
          if (boundModelId != null &&
              _modelOptions.any((model) => model.id == boundModelId)) {
            return boundModelId;
          }
          return scene.id == 'scene.memory.embedding'
              ? firstEmbedding.id
              : firstGeneral.id;
        }(),
    };
  }

  bool _looksLikeEmbeddingModel(String modelId) {
    final normalized = modelId.toLowerCase();
    return normalized.contains('embed') ||
        normalized.contains('bge-') ||
        normalized.contains('text-embedding');
  }

  Future<void> addManualModel({required OnboardingTranslator t}) async {
    final profile = _activeProfile;
    final modelId = manualModelController.text.trim();
    if (profile == null || modelId.isEmpty) return;
    if (!ModelProviderConfigService.isValidModelName(modelId)) {
      _error = t(
        '模型 ID 格式无效，请检查空格或特殊字符。',
        'The model ID is invalid. Check spaces and special characters.',
      );
      _emit();
      return;
    }
    if (_modelOptions.any((model) => model.id == modelId)) {
      manualModelController.clear();
      _emit();
      return;
    }
    try {
      final currentIds = await ModelProviderConfigService.getManualModelIds(
        profileId: profile.id,
      );
      await ModelProviderConfigService.saveManualModelIds(
        profileId: profile.id,
        ids: <String>[...currentIds, modelId],
      );
      if (_disposed) return;
      _modelOptions = <ProviderModelOption>[
        ..._modelOptions,
        ProviderModelOption(
          id: modelId,
          displayName: modelId,
          ownedBy: 'manual',
        ),
      ];
      manualModelController.clear();
      _error = null;
      _applyDefaultSceneSelections();
      _emit();
    } catch (error) {
      if (_disposed) return;
      _error = t('无法添加模型：$error', 'Could not add the model: $error');
      _emit();
    }
  }

  /// Persists every scene binding. Returns true when all saves succeeded.
  Future<bool> saveSceneModels({required OnboardingTranslator t}) async {
    final profile = _activeProfile;
    if (profile == null || _modelOptions.isEmpty || _sceneModelsSaving) {
      return false;
    }
    final missingScene = sceneDefinitions.any(
      (scene) => (_sceneModelSelections[scene.id] ?? '').trim().isEmpty,
    );
    if (missingScene) {
      _error = t('请为每个场景选择模型。', 'Choose a model for every scene.');
      _emit();
      return false;
    }

    _sceneModelsSaving = true;
    _error = null;
    _emit();
    var saved = false;
    try {
      for (final scene in sceneDefinitions) {
        if (_disposed) return false;
        _savingSceneIds = <String>{..._savingSceneIds, scene.id};
        _emit();
        await SceneModelConfigService.saveSceneModelBinding(
          sceneId: scene.id,
          providerProfileId: profile.id,
          modelId: _sceneModelSelections[scene.id]!,
        );
        if (_disposed) return false;
        _savingSceneIds = <String>{..._savingSceneIds}..remove(scene.id);
        _emit();
      }
      saved = true;
    } catch (error) {
      if (_disposed) return false;
      _error = t('场景模型保存失败：$error', 'Scene model setup failed: $error');
      _emit();
    } finally {
      if (!_disposed) {
        _sceneModelsSaving = false;
        _savingSceneIds = <String>{};
        _emit();
      }
    }
    return saved;
  }
}
