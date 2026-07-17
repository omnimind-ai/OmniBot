import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:url_launcher/url_launcher_string.dart';
import 'package:ui/features/home/pages/codex/codex_bridge_qr_scanner_page.dart';
import 'package:ui/features/home/pages/codex/codex_remote_directory_picker.dart';
import 'package:ui/features/home/pages/codex/alpine_file_system_page.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/codex_app_server_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class CodexSettingPage extends StatefulWidget {
  const CodexSettingPage({super.key});

  @override
  State<CodexSettingPage> createState() => _CodexSettingPageState();
}

class _CodexSettingPageState extends State<CodexSettingPage> {
  static const String _defaultCodexModel = 'gpt-5.5';
  static const String _defaultCodexHome = '/root/.codex';
  static const Duration _autoSaveDelay = Duration(milliseconds: 700);

  late final TextEditingController _baseUrlController;
  late final TextEditingController _modelController;
  late final TextEditingController _officialModelController;
  late final TextEditingController _apiKeyController;
  late final TextEditingController _bridgeUrlController;
  late final TextEditingController _bridgeTokenController;
  late final TextEditingController _bridgeCwdController;

  Timer? _saveDebounce;
  bool _isLoading = true;
  bool _isSaving = false;
  bool _isTestingBridge = false;
  bool _isFetchingApiModels = false;
  bool _isLoadingOfficialModels = false;
  bool _isStartingLogin = false;
  bool _isSyncing = false;
  bool _obscureApiKey = true;
  bool _obscureBridgeToken = true;
  bool _remoteEnabled = false;
  CodexLocalAuthMode _localAuthMode = CodexLocalAuthMode.api;
  String _codexHome = _defaultCodexHome;
  String _runtime = 'local';
  String? _error;
  String? _status;
  String? _lastSavedSignature;
  String? _apiModelOptionsSource;
  List<String> _apiModelOptions = const <String>[];
  List<String> _officialModelOptions = const <String>[];

  bool get _isChatGptMode => _localAuthMode == CodexLocalAuthMode.chatgpt;

  String get _apiModelRequestSource => [
    _baseUrlController.text.trim(),
    _apiKeyController.text.trim(),
  ].join('\n');

  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';
  bool get _isDarkTheme => context.isDarkTheme;
  Color get _pageBackground =>
      _isDarkTheme ? context.omniPalette.pageBackground : AppColors.background;
  Color get _cardColor =>
      _isDarkTheme ? context.omniPalette.surfacePrimary : Colors.white;
  Color get _primaryTextColor =>
      _isDarkTheme ? context.omniPalette.textPrimary : AppColors.text;
  Color get _secondaryTextColor =>
      _isDarkTheme ? context.omniPalette.textSecondary : AppColors.text70;
  Color get _tertiaryTextColor =>
      _isDarkTheme ? context.omniPalette.textTertiary : AppColors.text50;
  Color get _mutedSurfaceColor => _isDarkTheme
      ? context.omniPalette.surfaceSecondary.withValues(alpha: 0.72)
      : const Color(0xFFF8FAFC);
  InputBorder get _borderlessInputBorder => OutlineInputBorder(
    borderRadius: BorderRadius.circular(10),
    borderSide: BorderSide.none,
  );

  String _localeText({required String zh, required String en}) {
    return _isEnglish ? en : zh;
  }

  @override
  void initState() {
    super.initState();
    _baseUrlController = TextEditingController();
    _modelController = TextEditingController(text: _defaultCodexModel);
    _officialModelController = TextEditingController(text: _defaultCodexModel);
    _apiKeyController = TextEditingController();
    _bridgeUrlController = TextEditingController();
    _bridgeTokenController = TextEditingController();
    _bridgeCwdController = TextEditingController();
    for (final controller in [
      _baseUrlController,
      _modelController,
      _officialModelController,
      _apiKeyController,
      _bridgeUrlController,
      _bridgeTokenController,
      _bridgeCwdController,
    ]) {
      controller.addListener(_handleEdited);
    }
    unawaited(_loadConfig());
  }

  @override
  void dispose() {
    _saveDebounce?.cancel();
    for (final controller in [
      _baseUrlController,
      _modelController,
      _officialModelController,
      _apiKeyController,
      _bridgeUrlController,
      _bridgeTokenController,
      _bridgeCwdController,
    ]) {
      controller.removeListener(_handleEdited);
      controller.dispose();
    }
    super.dispose();
  }

  void _setControllerText(TextEditingController controller, String text) {
    if (controller.text == text) return;
    controller.value = TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    );
  }

  void _syncControllers(CodexLocalConfig config) {
    _isSyncing = true;
    try {
      _setControllerText(_baseUrlController, config.baseUrl);
      _setControllerText(
        _modelController,
        config.model.trim().isEmpty ? _defaultCodexModel : config.model,
      );
      _setControllerText(
        _officialModelController,
        config.officialModel.trim().isEmpty
            ? _defaultCodexModel
            : config.officialModel,
      );
      _setControllerText(_apiKeyController, config.apiKey);
      _setControllerText(_bridgeUrlController, config.remoteBridgeUrl);
      _setControllerText(_bridgeTokenController, config.remoteBridgeToken);
      _setControllerText(_bridgeCwdController, config.remoteCwd);
      _remoteEnabled = config.remoteEnabled;
      _localAuthMode = config.localAuthMode;
    } finally {
      _isSyncing = false;
    }
  }

  String _signature({
    required String baseUrl,
    required String model,
    required String officialModel,
    required String apiKey,
    required CodexLocalAuthMode localAuthMode,
    required String remoteBridgeUrl,
    required String remoteBridgeToken,
    required String remoteCwd,
    required bool remoteEnabled,
  }) {
    return [
      baseUrl.trim(),
      model.trim(),
      officialModel.trim(),
      apiKey.trim(),
      localAuthMode.payloadValue,
      remoteEnabled ? 'remote' : 'local',
      remoteBridgeUrl.trim(),
      remoteBridgeToken.trim(),
      remoteCwd.trim(),
    ].join('\n');
  }

  String _currentSignature() {
    return _signature(
      baseUrl: _baseUrlController.text,
      model: _modelController.text,
      officialModel: _officialModelController.text,
      apiKey: _apiKeyController.text,
      localAuthMode: _localAuthMode,
      remoteBridgeUrl: _bridgeUrlController.text,
      remoteBridgeToken: _bridgeTokenController.text,
      remoteCwd: _bridgeCwdController.text,
      remoteEnabled: _remoteEnabled,
    );
  }

  bool get _hasAnyLocalInput =>
      _baseUrlController.text.trim().isNotEmpty ||
      _modelController.text.trim().isNotEmpty ||
      _apiKeyController.text.trim().isNotEmpty ||
      _officialModelController.text.trim().isNotEmpty;

  bool get _hasAnyRemoteInput =>
      _bridgeUrlController.text.trim().isNotEmpty ||
      _bridgeTokenController.text.trim().isNotEmpty ||
      _bridgeCwdController.text.trim().isNotEmpty;

  bool get _hasCompleteLocalInput =>
      _isChatGptMode ||
      (_baseUrlController.text.trim().isNotEmpty &&
          _modelController.text.trim().isNotEmpty &&
          _apiKeyController.text.trim().isNotEmpty);

  bool get _hasCompleteRemoteInput =>
      _bridgeUrlController.text.trim().isNotEmpty &&
      _bridgeCwdController.text.trim().isNotEmpty;

  bool get _isChangingRemoteEnabled => _remoteEnabled != (_runtime == 'remote');

  bool get _hasCompleteInput {
    if (_remoteEnabled) return _hasCompleteRemoteInput;
    if (_isChangingRemoteEnabled) return true;
    if (_hasAnyLocalInput) return _hasCompleteLocalInput;
    return true;
  }

  bool get _isRemoteIncomplete => _remoteEnabled && !_hasCompleteRemoteInput;

  void _handleEdited() {
    if (_isSyncing || !mounted) return;
    _saveDebounce?.cancel();
    final signature = _currentSignature();
    final anyInput = _hasAnyLocalInput || _hasAnyRemoteInput || _remoteEnabled;
    final apiModelRequestSource = _apiModelRequestSource;
    setState(() {
      _error = null;
      if (_apiModelOptionsSource != null &&
          _apiModelOptionsSource != apiModelRequestSource) {
        _apiModelOptionsSource = null;
        _apiModelOptions = const <String>[];
      }
      if (_isRemoteIncomplete) {
        _status = _localeText(
          zh: '远程 Bridge URL 与远程工作目录填写完整后将自动保存。',
          en: 'Remote Bridge URL and remote cwd are required to autosave.',
        );
      } else if (!anyInput) {
        _status = null;
      } else if (!_hasCompleteInput) {
        _status = _localeText(
          zh: _remoteEnabled ? '填写完整后将自动保存。' : '自定义 API 配置填写完整后将自动保存。',
          en: _remoteEnabled
              ? 'Complete all fields to autosave.'
              : 'Complete the custom API config to autosave.',
        );
      } else if (signature == _lastSavedSignature) {
        _status = _localeText(zh: '已自动保存。', en: 'Autosaved.');
      } else {
        _status = _localeText(zh: '即将自动保存...', en: 'Autosave pending...');
      }
    });
    if (_hasCompleteInput && signature != _lastSavedSignature) {
      _scheduleAutoSave();
    }
  }

  void _setLocalAuthMode(CodexLocalAuthMode value) {
    if (_localAuthMode == value || _isSaving) return;
    setState(() {
      _localAuthMode = value;
      _error = null;
      _status = value == CodexLocalAuthMode.chatgpt
          ? _localeText(
              zh: '将切换到 ChatGPT 账号模式。',
              en: 'Switching to ChatGPT account mode.',
            )
          : _localeText(
              zh: '将切换到自定义 API 模式。',
              en: 'Switching to custom API mode.',
            );
    });
    _handleEdited();
  }

  void _setRemoteEnabled(bool value) {
    if (_remoteEnabled == value) return;
    setState(() {
      _remoteEnabled = value;
      _error = null;
      _status = value
          ? _localeText(
              zh: '远程模式已开启，填写 Bridge URL 与远程工作目录后将自动保存。',
              en: 'Remote mode is enabled. Fill Bridge URL and remote cwd to autosave.',
            )
          : _localeText(
              zh: '远程模式已关闭，将切换为本地 Alpine Codex。',
              en: 'Remote mode is disabled. Codex will use local Alpine.',
            );
    });
    if (_hasCompleteInput && _currentSignature() != _lastSavedSignature) {
      _scheduleAutoSave(delay: const Duration(milliseconds: 300));
    }
  }

  void _scheduleAutoSave({Duration delay = _autoSaveDelay}) {
    _saveDebounce?.cancel();
    _saveDebounce = Timer(delay, () => unawaited(_saveConfig()));
  }

  Future<void> _loadConfig() async {
    if (mounted) {
      setState(() {
        _isLoading = true;
        _error = null;
      });
    }
    try {
      final config = await CodexAppServerService.readLocalConfig();
      if (!mounted) return;
      _syncControllers(config);
      setState(() {
        _codexHome = config.codexHome ?? _defaultCodexHome;
        _runtime = config.runtime ?? 'local';
        _apiModelOptionsSource = null;
        _apiModelOptions = const <String>[];
        _isLoading = false;
        _error = null;
        _status = null;
        _lastSavedSignature = _currentSignature();
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _error = _localeText(
          zh: 'Codex 配置读取失败：$error',
          en: 'Failed to read Codex config: $error',
        );
      });
    }
  }

  Future<bool> _saveConfig() async {
    if (_isSaving) return false;
    if (_isRemoteIncomplete || !_hasCompleteInput) {
      if (!mounted) return false;
      setState(() {
        _status = _isRemoteIncomplete
            ? _localeText(
                zh: '远程 Bridge URL 与远程工作目录填写完整后将自动保存。',
                en: 'Remote Bridge URL and remote cwd are required to autosave.',
              )
            : _localeText(
                zh: _remoteEnabled ? '填写完整后将自动保存。' : '本地配置填写完整后将自动保存。',
                en: _remoteEnabled
                    ? 'Complete all fields to autosave.'
                    : 'Complete the local config to autosave.',
              );
      });
      return false;
    }

    final savingSignature = _currentSignature();
    if (savingSignature == _lastSavedSignature) {
      if (mounted) {
        setState(() => _status = _localeText(zh: '已自动保存。', en: 'Autosaved.'));
      }
      return true;
    }

    setState(() {
      _isSaving = true;
      _error = null;
      _status = _localeText(zh: '正在自动保存...', en: 'Autosaving...');
    });
    try {
      final saved = await CodexAppServerService.writeLocalConfig(
        baseUrl: _baseUrlController.text.trim(),
        model: _modelController.text.trim(),
        apiKey: _apiKeyController.text.trim(),
        officialModel: _officialModelController.text.trim(),
        localAuthMode: _localAuthMode,
        remoteEnabled: _remoteEnabled,
        remoteBridgeUrl: _bridgeUrlController.text.trim(),
        remoteBridgeToken: _bridgeTokenController.text.trim(),
        remoteCwd: _bridgeCwdController.text.trim(),
      );
      if (!mounted) return false;
      final savedSignature = _signature(
        baseUrl: saved.baseUrl,
        model: saved.model,
        officialModel: saved.officialModel,
        apiKey: saved.apiKey,
        localAuthMode: saved.localAuthMode,
        remoteBridgeUrl: saved.remoteBridgeUrl,
        remoteBridgeToken: saved.remoteBridgeToken,
        remoteCwd: saved.remoteCwd,
        remoteEnabled: saved.remoteEnabled,
      );
      if (_currentSignature() == savingSignature) {
        _syncControllers(saved);
      }
      setState(() {
        _codexHome = saved.codexHome ?? _defaultCodexHome;
        _runtime = saved.runtime ?? 'local';
        _lastSavedSignature = savedSignature;
        _error = null;
        _status = _currentSignature() == savedSignature
            ? _localeText(
                zh: saved.remoteEnabled
                    ? '已自动保存，Codex 模式将使用远程 PC Bridge。'
                    : saved.localAuthMode == CodexLocalAuthMode.chatgpt
                    ? '已自动保存，将使用本地 ChatGPT 账号。'
                    : '已自动保存，将使用本地自定义 API。',
                en: saved.remoteEnabled
                    ? 'Autosaved. Codex mode will use the remote PC Bridge.'
                    : saved.localAuthMode == CodexLocalAuthMode.chatgpt
                    ? 'Autosaved. Local Codex will use the ChatGPT account.'
                    : 'Autosaved. Local Codex will use the custom API.',
              )
            : _localeText(zh: '即将自动保存...', en: 'Autosave pending...');
      });
      return true;
    } catch (error) {
      if (!mounted) return false;
      setState(() {
        _error = _localeText(
          zh: 'Codex 配置保存失败：$error',
          en: 'Failed to save Codex config: $error',
        );
        _status = null;
      });
      return false;
    } finally {
      if (mounted) {
        setState(() => _isSaving = false);
        if (_hasCompleteInput && _currentSignature() != savingSignature) {
          _scheduleAutoSave(delay: const Duration(milliseconds: 300));
        }
      }
    }
  }

  Future<void> _testBridgeConnection() async {
    if (_isTestingBridge) return;
    final bridgeUrl = _bridgeUrlController.text.trim();
    final cwd = _bridgeCwdController.text.trim();
    if (bridgeUrl.isEmpty || cwd.isEmpty) {
      showToast(
        _localeText(
          zh: '测试连接需要填写 Bridge URL 与远程工作目录。',
          en: 'Bridge URL and remote cwd are required for the connection test.',
        ),
        type: ToastType.warning,
      );
      return;
    }
    setState(() {
      _isTestingBridge = true;
      _error = null;
    });
    showToast(
      _localeText(zh: '正在测试远程 PC Bridge...', en: 'Testing remote PC Bridge...'),
      type: ToastType.loading,
      duration: const Duration(milliseconds: 1200),
    );
    try {
      final result = await CodexAppServerService.testRemoteConfig(
        remoteBridgeUrl: bridgeUrl,
        remoteBridgeToken: _bridgeTokenController.text.trim(),
        remoteCwd: cwd,
      );
      if (!mounted) return;
      final ok = result['ok'] == true || result['ready'] == true;
      final version = result['version']?.toString().trim() ?? '';
      final resolvedCwd = result['cwd']?.toString().trim() ?? '';
      showToast(
        ok
            ? _localeText(
                zh: '远程 Bridge 可用${version.isEmpty ? '' : '：$version'}${resolvedCwd.isEmpty ? '' : '，目录：$resolvedCwd'}',
                en: 'Remote Bridge is ready${version.isEmpty ? '' : ': $version'}${resolvedCwd.isEmpty ? '' : ', cwd: $resolvedCwd'}',
              )
            : _localeText(
                zh: '远程 Bridge 测试失败：${result['error'] ?? 'unknown'}',
                en: 'Remote Bridge test failed: ${result['error'] ?? 'unknown'}',
              ),
        type: ok ? ToastType.success : ToastType.error,
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        _localeText(
          zh: '远程 Bridge 测试失败：$error',
          en: 'Remote Bridge test failed: $error',
        ),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() => _isTestingBridge = false);
      }
    }
  }

  Future<void> _fetchApiModels() async {
    if (_isFetchingApiModels) return;
    final baseUrl = _baseUrlController.text.trim();
    final apiKey = _apiKeyController.text.trim();
    if (baseUrl.isEmpty || apiKey.isEmpty) {
      showToast(
        _localeText(
          zh: '请先填写 Base URL 和 API Key。',
          en: 'Enter the Base URL and API Key first.',
        ),
        type: ToastType.warning,
      );
      return;
    }
    final requestSource = _apiModelRequestSource;
    setState(() => _isFetchingApiModels = true);
    try {
      final response = await CodexAppServerService.listLocalApiModels(
        baseUrl: baseUrl,
        apiKey: apiKey,
      );
      final ids = _extractCodexModelIds(response);
      if (!mounted) return;
      if (_apiModelRequestSource != requestSource) return;
      setState(() {
        _apiModelOptionsSource = requestSource;
        _apiModelOptions = ids;
      });
      showToast(
        ids.isEmpty
            ? _localeText(
                zh: '接口未返回模型，可继续手动输入模型 ID。',
                en: 'No models returned. You can still enter a model ID.',
              )
            : _localeText(
                zh: '已拉取 ${ids.length} 个模型。',
                en: 'Fetched ${ids.length} models.',
              ),
        type: ids.isEmpty ? ToastType.warning : ToastType.success,
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        _localeText(
          zh: '模型列表拉取失败：$error，可继续手动输入模型 ID。',
          en: 'Failed to fetch models: $error. You can enter a model ID manually.',
        ),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() => _isFetchingApiModels = false);
      }
    }
  }

  Future<void> _loadOfficialModels() async {
    if (_isLoadingOfficialModels || _isSaving || _remoteEnabled) return;
    setState(() => _isLoadingOfficialModels = true);
    try {
      if (!await _saveConfig()) return;
      await CodexAppServerService.connect();
      final response = await CodexAppServerService.listModels();
      final models = _extractCodexModelIds(response);
      if (!mounted) return;
      setState(() => _officialModelOptions = models);
      showToast(
        models.isEmpty
            ? _localeText(
                zh: 'Codex 未返回可选官方模型。',
                en: 'Codex returned no selectable official models.',
              )
            : _localeText(
                zh: '已加载 ${models.length} 个官方模型。',
                en: 'Loaded ${models.length} official models.',
              ),
        type: models.isEmpty ? ToastType.warning : ToastType.success,
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        _localeText(
          zh: '官方模型加载失败：$error',
          en: 'Failed to load official models: $error',
        ),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() => _isLoadingOfficialModels = false);
      }
    }
  }

  List<String> _extractCodexModelIds(Map<String, dynamic> response) {
    final raw = response['data'] ?? response['models'] ?? response['items'];
    if (raw is! List) return const <String>[];
    final ids = <String>{};
    for (final item in raw) {
      if (item is! Map) continue;
      final id = (item['id'] ?? item['model'])?.toString().trim() ?? '';
      if (id.isNotEmpty) ids.add(id);
    }
    return ids.toList(growable: false);
  }

  Future<void> _startChatGptLogin() async {
    if (_isStartingLogin || _isSaving || _remoteEnabled) return;
    setState(() => _isStartingLogin = true);
    try {
      if (!await _saveConfig()) return;
      await CodexAppServerService.connect();
      final response = await CodexAppServerService.startLogin(
        type: CodexLoginType.chatgptDeviceCode,
      );
      final loginId = response['loginId']?.toString().trim() ?? '';
      final verificationUrl =
          response['verificationUrl']?.toString().trim() ?? '';
      final userCode = response['userCode']?.toString().trim() ?? '';
      if (verificationUrl.isEmpty || userCode.isEmpty) {
        throw StateError(
          'Codex did not return a device verification URL and code.',
        );
      }
      if (!mounted) return;
      unawaited(
        launchUrlString(verificationUrl, mode: LaunchMode.externalApplication),
      );
      final shouldRefresh = await showDialog<bool>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => AlertDialog(
          title: Text(_localeText(zh: '登录 ChatGPT', en: 'Sign in to ChatGPT')),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                _localeText(
                  zh: '浏览器打开后输入以下设备码：',
                  en: 'Enter this device code in the browser:',
                ),
              ),
              const SizedBox(height: 12),
              SelectableText(
                userCode,
                key: const Key('codex-chatgpt-device-code'),
                style: const TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 2,
                ),
              ),
              const SizedBox(height: 8),
              SelectableText(verificationUrl),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: Text(_localeText(zh: '取消', en: 'Cancel')),
            ),
            TextButton.icon(
              onPressed: () async {
                await Clipboard.setData(ClipboardData(text: userCode));
              },
              icon: const Icon(Icons.copy_rounded, size: 17),
              label: Text(_localeText(zh: '复制设备码', en: 'Copy code')),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: Text(_localeText(zh: '已完成登录', en: 'Signed in')),
            ),
          ],
        ),
      );
      if (shouldRefresh == true && mounted) {
        unawaited(_loadOfficialModels());
      } else if (loginId.isNotEmpty) {
        await CodexAppServerService.cancelLogin(loginId: loginId);
      }
    } catch (error) {
      if (!mounted) return;
      showToast(
        _localeText(
          zh: 'ChatGPT 登录启动失败：$error',
          en: 'Failed to start ChatGPT login: $error',
        ),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() => _isStartingLogin = false);
      }
    }
  }

  Future<void> _openRemoteDirectoryPicker() async {
    final bridgeUrl = _bridgeUrlController.text.trim();
    if (bridgeUrl.isEmpty) {
      showToast(
        _localeText(
          zh: '请先填写 Bridge URL。',
          en: 'Bridge URL is required first.',
        ),
        type: ToastType.warning,
      );
      return;
    }
    final selected = await showCodexRemoteDirectoryPicker(
      context: context,
      remoteBridgeUrl: bridgeUrl,
      remoteBridgeToken: _bridgeTokenController.text.trim(),
      initialPath: _bridgeCwdController.text.trim(),
    );
    if (!mounted || selected == null || selected.trim().isEmpty) return;
    _setControllerText(_bridgeCwdController, selected.trim());
    _handleEdited();
  }

  Future<void> _openBridgeQrScanner() async {
    final result = await Navigator.of(context).push<CodexBridgeQrScanResult>(
      MaterialPageRoute(
        builder: (_) => const CodexBridgeQrScannerPage(),
        fullscreenDialog: true,
      ),
    );
    if (!mounted || result == null) return;
    _saveDebounce?.cancel();
    _isSyncing = true;
    try {
      _setControllerText(_bridgeUrlController, result.bridgeUrl.trim());
      _setControllerText(_bridgeTokenController, result.token.trim());
      _setControllerText(_bridgeCwdController, result.cwd.trim());
      setState(() {
        _remoteEnabled = true;
        _error = null;
        _status = _localeText(
          zh: '已识别 Bridge 二维码，配置即将自动保存。',
          en: 'Bridge QR code scanned. Autosave pending.',
        );
      });
    } finally {
      _isSyncing = false;
    }
    showToast(
      _localeText(zh: '已填入远程 Bridge 配置。', en: 'Remote Bridge config filled.'),
      type: ToastType.success,
    );
    _handleEdited();
  }

  Widget _buildTextField({
    required Key key,
    required TextEditingController controller,
    required String label,
    required String hint,
    TextInputType keyboardType = TextInputType.text,
    bool obscureText = false,
    bool readOnly = false,
    Widget? suffixIcon,
  }) {
    return TextField(
      key: key,
      controller: controller,
      obscureText: obscureText,
      readOnly: readOnly,
      keyboardType: keyboardType,
      textInputAction: TextInputAction.next,
      style: TextStyle(
        color: _primaryTextColor,
        fontSize: 13,
        fontFamily: 'PingFang SC',
      ),
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        filled: true,
        fillColor: _mutedSurfaceColor,
        border: _borderlessInputBorder,
        enabledBorder: _borderlessInputBorder,
        focusedBorder: _borderlessInputBorder,
        disabledBorder: _borderlessInputBorder,
        errorBorder: _borderlessInputBorder,
        focusedErrorBorder: _borderlessInputBorder,
        isDense: true,
        suffixIcon: suffixIcon,
      ),
    );
  }

  Widget _buildModelField({
    required Key fieldKey,
    required Key refreshKey,
    required Key menuKey,
    required TextEditingController controller,
    required String label,
    required String hint,
    required List<String> options,
    required bool loading,
    required VoidCallback onRefresh,
    bool readOnly = false,
  }) {
    return _buildTextField(
      key: fieldKey,
      controller: controller,
      label: label,
      hint: hint,
      readOnly: readOnly,
      suffixIcon: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            key: refreshKey,
            tooltip: _localeText(zh: '拉取模型列表', en: 'Fetch models'),
            onPressed: loading ? null : onRefresh,
            icon: loading
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.refresh_rounded, size: 18),
          ),
          PopupMenuButton<String>(
            key: menuKey,
            tooltip: _localeText(zh: '选择模型', en: 'Choose model'),
            enabled: options.isNotEmpty,
            icon: const Icon(Icons.arrow_drop_down_rounded, size: 22),
            onSelected: (model) {
              _setControllerText(controller, model);
              _handleEdited();
            },
            itemBuilder: (context) => options
                .map(
                  (model) => PopupMenuItem<String>(
                    value: model,
                    child: Text(
                      model,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                )
                .toList(growable: false),
          ),
        ],
      ),
    );
  }

  Widget _buildLocalAuthModeSelector() {
    return Row(
      children: [
        Expanded(
          child: ChoiceChip(
            key: const Key('codex-local-auth-chatgpt'),
            label: Text(_localeText(zh: 'ChatGPT 账号', en: 'ChatGPT account')),
            selected: _localAuthMode == CodexLocalAuthMode.chatgpt,
            onSelected: _isSaving
                ? null
                : (_) => _setLocalAuthMode(CodexLocalAuthMode.chatgpt),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: ChoiceChip(
            key: const Key('codex-local-auth-api'),
            label: Text(_localeText(zh: '自定义 API', en: 'Custom API')),
            selected: _localAuthMode == CodexLocalAuthMode.api,
            onSelected: _isSaving
                ? null
                : (_) => _setLocalAuthMode(CodexLocalAuthMode.api),
          ),
        ),
      ],
    );
  }

  Widget _buildChatGptAccountCard() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: _mutedSurfaceColor,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _localeText(
              zh: '通过 Codex CLI 的设备码流程登录或切换 ChatGPT 账号，使用套餐包含的 Codex 用量。',
              en: 'Use the Codex CLI device-code flow to sign in or switch ChatGPT accounts and use Codex access included with the plan.',
            ),
            style: TextStyle(
              color: _secondaryTextColor,
              fontSize: 12,
              height: 1.4,
              fontFamily: 'PingFang SC',
            ),
          ),
          const SizedBox(height: 10),
          FilledButton.icon(
            key: const Key('codex-chatgpt-login-button'),
            onPressed: _isStartingLogin || _isSaving || _remoteEnabled
                ? null
                : () => unawaited(_startChatGptLogin()),
            icon: _isStartingLogin
                ? const SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.login_rounded, size: 17),
            label: Text(
              _localeText(
                zh: '登录或切换 ChatGPT 账号',
                en: 'Sign in or switch account',
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRemoteSwitch() {
    final palette = context.omniPalette;
    final enabled = !_isSaving;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: enabled ? () => _setRemoteEnabled(!_remoteEnabled) : null,
      child: Padding(
        padding: const EdgeInsets.only(left: 12),
        child: AbsorbPointer(
          child: Opacity(
            opacity: enabled ? 1 : 0.5,
            child: FlutterSwitch(
              width: 32,
              height: 18.67,
              toggleSize: 11.3,
              padding: 3,
              activeColor: palette.accentPrimary,
              inactiveColor: palette.borderStrong,
              borderRadius: 28.75,
              value: _remoteEnabled,
              onToggle: _setRemoteEnabled,
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final borderColor = _isDarkTheme
        ? context.omniPalette.borderSubtle
        : const Color(0x1A000000);
    return Scaffold(
      backgroundColor: _pageBackground,
      appBar: CommonAppBar(
        title: LegacyTextLocalizer.localize('Codex 配置'),
        primary: true,
      ),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
          children: [
            SettingsSectionTitle(
              label: _localeText(zh: 'Codex 配置', en: 'Codex Config'),
              subtitle: _localeText(
                zh: '用开关明确选择远程 PC Bridge 或本地 Alpine。',
                en: 'Use the switch to choose remote PC Bridge or local Alpine.',
              ),
            ),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.fromLTRB(14, 14, 14, 14),
              decoration: BoxDecoration(
                color: _cardColor,
                borderRadius: BorderRadius.circular(12),
              ),
              child: _isLoading
                  ? const Padding(
                      padding: EdgeInsets.symmetric(vertical: 28),
                      child: Center(child: CircularProgressIndicator()),
                    )
                  : Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Icon(
                              _runtime == 'remote'
                                  ? Icons.hub_rounded
                                  : Icons.terminal_rounded,
                              size: 18,
                              color: Theme.of(context).colorScheme.primary,
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                _localeText(
                                  zh: _runtime == 'remote'
                                      ? '当前运行时：远程 PC Bridge'
                                      : '当前运行时：本地 Alpine（配置目录：$_codexHome）',
                                  en: _runtime == 'remote'
                                      ? 'Runtime: Remote PC Bridge'
                                      : 'Runtime: Local Alpine (config: $_codexHome)',
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  color: _secondaryTextColor,
                                  fontSize: 12,
                                  fontFamily: 'PingFang SC',
                                ),
                              ),
                            ),
                            IconButton(
                              key: const Key('codex-config-refresh-button'),
                              visualDensity: VisualDensity.compact,
                              constraints: const BoxConstraints.tightFor(
                                width: 28,
                                height: 28,
                              ),
                              padding: EdgeInsets.zero,
                              tooltip: _localeText(zh: '重新读取', en: 'Reload'),
                              onPressed: _isSaving
                                  ? null
                                  : () => unawaited(_loadConfig()),
                              icon: Icon(
                                Icons.refresh_rounded,
                                size: 17,
                                color: _tertiaryTextColor,
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                _localeText(
                                  zh: '远程 PC Bridge',
                                  en: 'Remote PC Bridge',
                                ),
                                style: TextStyle(
                                  color: _primaryTextColor,
                                  fontSize: 13,
                                  fontWeight: FontWeight.w600,
                                  fontFamily: 'PingFang SC',
                                ),
                              ),
                            ),
                            _buildRemoteSwitch(),
                          ],
                        ),
                        Text(
                          _remoteEnabled
                              ? _localeText(
                                  zh: '已启用：Codex 模式将连接远程 PC Bridge。',
                                  en: 'Enabled: Codex mode will connect to the remote PC Bridge.',
                                )
                              : _localeText(
                                  zh: '已关闭：Codex 模式使用本地 Alpine。',
                                  en: 'Disabled: Codex mode uses local Alpine.',
                                ),
                          style: TextStyle(
                            color: _secondaryTextColor,
                            fontSize: 12,
                            height: 1.35,
                            fontFamily: 'PingFang SC',
                          ),
                        ),
                        const SizedBox(height: 8),
                        _buildTextField(
                          key: const Key(
                            'codex-config-remote-bridge-url-field',
                          ),
                          controller: _bridgeUrlController,
                          label: _localeText(
                            zh: 'Bridge URL',
                            en: 'Bridge URL',
                          ),
                          hint: 'ws://192.168.1.10:17321/codex',
                          keyboardType: TextInputType.url,
                        ),
                        const SizedBox(height: 12),
                        _buildTextField(
                          key: const Key('codex-config-remote-cwd-field'),
                          controller: _bridgeCwdController,
                          label: _localeText(zh: '远程工作目录', en: 'Remote cwd'),
                          hint: '/Users/name/code/project',
                          suffixIcon: IconButton(
                            tooltip: _localeText(
                              zh: '选择目录',
                              en: 'Choose directory',
                            ),
                            onPressed: _openRemoteDirectoryPicker,
                            icon: const Icon(
                              Icons.folder_open_rounded,
                              size: 18,
                            ),
                          ),
                        ),
                        const SizedBox(height: 12),
                        _buildTextField(
                          key: const Key('codex-config-remote-token-field'),
                          controller: _bridgeTokenController,
                          label: _localeText(
                            zh: 'Bridge Token（可选）',
                            en: 'Bridge Token (optional)',
                          ),
                          hint: 'OMNIBOT_BRIDGE_TOKEN',
                          obscureText: _obscureBridgeToken,
                          suffixIcon: IconButton(
                            tooltip: _obscureBridgeToken
                                ? _localeText(zh: '显示 Token', en: 'Show token')
                                : _localeText(zh: '隐藏 Token', en: 'Hide token'),
                            onPressed: () {
                              setState(() {
                                _obscureBridgeToken = !_obscureBridgeToken;
                              });
                            },
                            icon: Icon(
                              _obscureBridgeToken
                                  ? Icons.visibility_outlined
                                  : Icons.visibility_off_outlined,
                              size: 18,
                            ),
                          ),
                        ),
                        const SizedBox(height: 10),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            OutlinedButton.icon(
                              key: const Key(
                                'codex-config-scan-bridge-qr-button',
                              ),
                              onPressed: _isSaving
                                  ? null
                                  : () => unawaited(_openBridgeQrScanner()),
                              icon: const Icon(
                                Icons.qr_code_scanner_rounded,
                                size: 17,
                              ),
                              label: Text(
                                _localeText(zh: '扫码连接', en: 'Scan QR'),
                              ),
                            ),
                            OutlinedButton.icon(
                              onPressed: _isTestingBridge
                                  ? null
                                  : () => unawaited(_testBridgeConnection()),
                              icon: _isTestingBridge
                                  ? const SizedBox(
                                      width: 14,
                                      height: 14,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  : const Icon(
                                      Icons.wifi_tethering_rounded,
                                      size: 17,
                                    ),
                              label: Text(
                                _isTestingBridge
                                    ? _localeText(
                                        zh: '测试中...',
                                        en: 'Testing...',
                                      )
                                    : _localeText(
                                        zh: '测试 Bridge 连接',
                                        en: 'Test Bridge',
                                      ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 14),
                        Divider(height: 1, color: borderColor),
                        const SizedBox(height: 12),
                        Text(
                          _localeText(
                            zh: '本地 Alpine Codex',
                            en: 'Local Alpine Codex',
                          ),
                          style: TextStyle(
                            color: _primaryTextColor,
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                            fontFamily: 'PingFang SC',
                          ),
                        ),
                        const SizedBox(height: 8),
                        OutlinedButton.icon(
                          key: const Key('codex-open-alpine-filesystem-button'),
                          onPressed: () {
                            Navigator.of(context).push<void>(
                              MaterialPageRoute<void>(
                                builder: (_) => const AlpineFileSystemPage(
                                  initialPath: '/',
                                ),
                              ),
                            );
                          },
                          icon: const Icon(Icons.folder_open_rounded, size: 18),
                          label: Text(
                            _localeText(
                              zh: '管理 Alpine 文件系统',
                              en: 'Manage Alpine filesystem',
                            ),
                          ),
                        ),
                        const SizedBox(height: 12),
                        _buildLocalAuthModeSelector(),
                        const SizedBox(height: 12),
                        if (_isChatGptMode) ...[
                          _buildChatGptAccountCard(),
                          const SizedBox(height: 12),
                          _buildModelField(
                            fieldKey: const Key(
                              'codex-config-official-model-field',
                            ),
                            refreshKey: const Key(
                              'codex-config-official-model-refresh',
                            ),
                            menuKey: const Key(
                              'codex-config-official-model-menu',
                            ),
                            controller: _officialModelController,
                            label: _localeText(
                              zh: '官方 Codex 模型',
                              en: 'Official Codex model',
                            ),
                            hint: _defaultCodexModel,
                            options: _officialModelOptions,
                            loading: _isLoadingOfficialModels,
                            onRefresh: () => unawaited(_loadOfficialModels()),
                            readOnly: true,
                          ),
                        ] else ...[
                          _buildTextField(
                            key: const Key('codex-config-base-url-field'),
                            controller: _baseUrlController,
                            label: 'Base URL',
                            hint: 'https://bring_your_own_key.endpoint/v1',
                            keyboardType: TextInputType.url,
                          ),
                          const SizedBox(height: 12),
                          _buildTextField(
                            key: const Key('codex-config-api-key-field'),
                            controller: _apiKeyController,
                            label: 'API Key',
                            hint: 'your_own_key',
                            obscureText: _obscureApiKey,
                            suffixIcon: IconButton(
                              tooltip: _obscureApiKey
                                  ? _localeText(zh: '显示密钥', en: 'Show key')
                                  : _localeText(zh: '隐藏密钥', en: 'Hide key'),
                              onPressed: () {
                                setState(() {
                                  _obscureApiKey = !_obscureApiKey;
                                });
                              },
                              icon: Icon(
                                _obscureApiKey
                                    ? Icons.visibility_outlined
                                    : Icons.visibility_off_outlined,
                                size: 18,
                              ),
                            ),
                          ),
                          const SizedBox(height: 12),
                          _buildModelField(
                            fieldKey: const Key('codex-config-model-field'),
                            refreshKey: const Key(
                              'codex-config-api-model-refresh',
                            ),
                            menuKey: const Key('codex-config-api-model-menu'),
                            controller: _modelController,
                            label: _localeText(
                              zh: '模型 ID（可手动输入）',
                              en: 'Model ID (editable)',
                            ),
                            hint: _defaultCodexModel,
                            options: _apiModelOptions,
                            loading: _isFetchingApiModels,
                            onRefresh: () => unawaited(_fetchApiModels()),
                          ),
                        ],
                        const SizedBox(height: 12),
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: _mutedSurfaceColor,
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Icon(
                                Icons.restart_alt_rounded,
                                size: 17,
                                color: _tertiaryTextColor,
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  _localeText(
                                    zh: '远程开关关闭时使用本地 Codex；官网账号与自定义 API 凭证相互隔离。配置修改会自动保存并断开当前 Codex 会话。',
                                    en: 'When the remote switch is off, local Codex is used. ChatGPT and custom API credentials stay separate. Changes autosave and disconnect the current Codex session.',
                                  ),
                                  style: TextStyle(
                                    color: _secondaryTextColor,
                                    fontSize: 12,
                                    height: 1.45,
                                    fontFamily: 'PingFang SC',
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                        if (_error != null) ...[
                          const SizedBox(height: 10),
                          Text(
                            _error!,
                            style: TextStyle(
                              color: Theme.of(context).colorScheme.error,
                              fontSize: 12,
                              height: 1.45,
                              fontFamily: 'PingFang SC',
                            ),
                          ),
                        ],
                        if (_status != null || _isSaving) ...[
                          const SizedBox(height: 10),
                          Row(
                            children: [
                              if (_isSaving) ...[
                                const SizedBox(
                                  width: 13,
                                  height: 13,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                ),
                                const SizedBox(width: 8),
                              ] else ...[
                                Icon(
                                  Icons.check_circle_outline_rounded,
                                  size: 15,
                                  color: _tertiaryTextColor,
                                ),
                                const SizedBox(width: 7),
                              ],
                              Expanded(
                                child: Text(
                                  _status ??
                                      _localeText(
                                        zh: '正在自动保存...',
                                        en: 'Autosaving...',
                                      ),
                                  style: TextStyle(
                                    color: _secondaryTextColor,
                                    fontSize: 12,
                                    height: 1.45,
                                    fontFamily: 'PingFang SC',
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ],
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }
}
