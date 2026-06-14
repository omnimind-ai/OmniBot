import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:ui/features/home/pages/codex/codex_bridge_qr_scanner_page.dart';
import 'package:ui/features/home/pages/codex/codex_remote_directory_picker.dart';
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
  late final TextEditingController _apiKeyController;
  late final TextEditingController _bridgeUrlController;
  late final TextEditingController _bridgeTokenController;
  late final TextEditingController _bridgeCwdController;

  Timer? _saveDebounce;
  bool _isLoading = true;
  bool _isSaving = false;
  bool _isTestingBridge = false;
  bool _isSyncing = false;
  bool _obscureApiKey = true;
  bool _obscureBridgeToken = true;
  bool _remoteEnabled = false;
  bool _contextInjectionEnabled = false;
  String _codexHome = _defaultCodexHome;
  String _runtime = 'local';
  String? _error;
  String? _status;
  String? _lastSavedSignature;
  String? _lastSavedConfigFieldsSignature;

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

  String _localeText({required String zh, required String en}) {
    return _isEnglish ? en : zh;
  }

  @override
  void initState() {
    super.initState();
    _baseUrlController = TextEditingController();
    _modelController = TextEditingController(text: _defaultCodexModel);
    _apiKeyController = TextEditingController();
    _bridgeUrlController = TextEditingController();
    _bridgeTokenController = TextEditingController();
    _bridgeCwdController = TextEditingController();
    for (final controller in [
      _baseUrlController,
      _modelController,
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
      _setControllerText(_apiKeyController, config.apiKey);
      _setControllerText(_bridgeUrlController, config.remoteBridgeUrl);
      _setControllerText(_bridgeTokenController, config.remoteBridgeToken);
      _setControllerText(_bridgeCwdController, config.remoteCwd);
      _remoteEnabled = config.remoteEnabled;
      _contextInjectionEnabled = config.contextInjectionEnabled;
    } finally {
      _isSyncing = false;
    }
  }

  String _configFieldsSignature({
    required String baseUrl,
    required String model,
    required String apiKey,
    required String remoteBridgeUrl,
    required String remoteBridgeToken,
    required String remoteCwd,
    required bool remoteEnabled,
  }) {
    return [
      baseUrl.trim(),
      model.trim(),
      apiKey.trim(),
      remoteEnabled ? 'remote' : 'local',
      remoteBridgeUrl.trim(),
      remoteBridgeToken.trim(),
      remoteCwd.trim(),
    ].join('\n');
  }

  String _signature({
    required String baseUrl,
    required String model,
    required String apiKey,
    required String remoteBridgeUrl,
    required String remoteBridgeToken,
    required String remoteCwd,
    required bool remoteEnabled,
    required bool contextInjectionEnabled,
  }) {
    return [
      _configFieldsSignature(
        baseUrl: baseUrl,
        model: model,
        apiKey: apiKey,
        remoteBridgeUrl: remoteBridgeUrl,
        remoteBridgeToken: remoteBridgeToken,
        remoteCwd: remoteCwd,
        remoteEnabled: remoteEnabled,
      ),
      contextInjectionEnabled ? 'context:on' : 'context:off',
    ].join('\n');
  }

  String _currentConfigFieldsSignature() {
    return _configFieldsSignature(
      baseUrl: _baseUrlController.text,
      model: _modelController.text,
      apiKey: _apiKeyController.text,
      remoteBridgeUrl: _bridgeUrlController.text,
      remoteBridgeToken: _bridgeTokenController.text,
      remoteCwd: _bridgeCwdController.text,
      remoteEnabled: _remoteEnabled,
    );
  }

  String _currentSignature() {
    return _signature(
      baseUrl: _baseUrlController.text,
      model: _modelController.text,
      apiKey: _apiKeyController.text,
      remoteBridgeUrl: _bridgeUrlController.text,
      remoteBridgeToken: _bridgeTokenController.text,
      remoteCwd: _bridgeCwdController.text,
      remoteEnabled: _remoteEnabled,
      contextInjectionEnabled: _contextInjectionEnabled,
    );
  }

  bool get _hasAnyLocalInput =>
      _baseUrlController.text.trim().isNotEmpty ||
      _modelController.text.trim().isNotEmpty ||
      _apiKeyController.text.trim().isNotEmpty;

  bool get _hasAnyRemoteInput =>
      _bridgeUrlController.text.trim().isNotEmpty ||
      _bridgeTokenController.text.trim().isNotEmpty ||
      _bridgeCwdController.text.trim().isNotEmpty;

  bool get _hasCompleteLocalInput =>
      _baseUrlController.text.trim().isNotEmpty &&
      _modelController.text.trim().isNotEmpty &&
      _apiKeyController.text.trim().isNotEmpty;

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

  bool get _onlyContextInjectionChanged =>
      _lastSavedConfigFieldsSignature != null &&
      _currentConfigFieldsSignature() == _lastSavedConfigFieldsSignature &&
      _currentSignature() != _lastSavedSignature;

  bool get _canSaveCurrentConfig =>
      !_isRemoteIncomplete && (_hasCompleteInput || _onlyContextInjectionChanged);

  void _handleEdited() {
    if (_isSyncing || !mounted) return;
    _saveDebounce?.cancel();
    final signature = _currentSignature();
    final anyInput = _hasAnyLocalInput ||
        _hasAnyRemoteInput ||
        _remoteEnabled ||
        _contextInjectionEnabled;
    setState(() {
      _error = null;
      if (_isRemoteIncomplete) {
        _status = _localeText(
          zh: '远程 Bridge URL 与远程工作目录填写完整后将自动保存。',
          en: 'Remote Bridge URL and remote cwd are required to autosave.',
        );
      } else if (!anyInput) {
        _status = null;
      } else if (!_canSaveCurrentConfig) {
        _status = _localeText(
          zh: _remoteEnabled ? '填写完整后将自动保存。' : '本地配置填写完整后将自动保存。',
          en: _remoteEnabled
              ? 'Complete all fields to autosave.'
              : 'Complete the local config to autosave.',
        );
      } else if (signature == _lastSavedSignature) {
        _status = _localeText(zh: '已自动保存。', en: 'Autosaved.');
      } else {
        _status = _localeText(zh: '即将自动保存...', en: 'Autosave pending...');
      }
    });
    if (_canSaveCurrentConfig && signature != _lastSavedSignature) {
      _scheduleAutoSave();
    }
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
    if (_canSaveCurrentConfig && _currentSignature() != _lastSavedSignature) {
      _scheduleAutoSave(delay: const Duration(milliseconds: 300));
    }
  }

  void _setContextInjectionEnabled(bool value) {
    if (_contextInjectionEnabled == value) return;
    setState(() {
      _contextInjectionEnabled = value;
      _error = null;
    });
    _handleEdited();
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
        _isLoading = false;
        _error = null;
        _status = null;
        _lastSavedSignature = _currentSignature();
        _lastSavedConfigFieldsSignature = _currentConfigFieldsSignature();
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

  Future<void> _saveConfig() async {
    if (_isSaving) return;
    if (!_canSaveCurrentConfig) {
      if (!mounted) return;
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
      return;
    }

    final savingSignature = _currentSignature();
    if (savingSignature == _lastSavedSignature) {
      if (mounted) {
        setState(() => _status = _localeText(zh: '已自动保存。', en: 'Autosaved.'));
      }
      return;
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
        remoteEnabled: _remoteEnabled,
        remoteBridgeUrl: _bridgeUrlController.text.trim(),
        remoteBridgeToken: _bridgeTokenController.text.trim(),
        remoteCwd: _bridgeCwdController.text.trim(),
        contextInjectionEnabled: _contextInjectionEnabled,
      );
      if (!mounted) return;
      final savedConfigFieldsSignature = _configFieldsSignature(
        baseUrl: saved.baseUrl,
        model: saved.model,
        apiKey: saved.apiKey,
        remoteBridgeUrl: saved.remoteBridgeUrl,
        remoteBridgeToken: saved.remoteBridgeToken,
        remoteCwd: saved.remoteCwd,
        remoteEnabled: saved.remoteEnabled,
      );
      final savedSignature = _signature(
        baseUrl: saved.baseUrl,
        model: saved.model,
        apiKey: saved.apiKey,
        remoteBridgeUrl: saved.remoteBridgeUrl,
        remoteBridgeToken: saved.remoteBridgeToken,
        remoteCwd: saved.remoteCwd,
        remoteEnabled: saved.remoteEnabled,
        contextInjectionEnabled: saved.contextInjectionEnabled,
      );
      if (_currentSignature() == savingSignature) {
        _syncControllers(saved);
      }
      setState(() {
        _codexHome = saved.codexHome ?? _defaultCodexHome;
        _runtime = saved.runtime ?? 'local';
        _lastSavedSignature = savedSignature;
        _lastSavedConfigFieldsSignature = savedConfigFieldsSignature;
        _error = null;
        _status = _currentSignature() == savedSignature
            ? _localeText(
                zh: saved.remoteEnabled
                    ? '已自动保存，Codex 模式将使用远程 PC Bridge。'
                    : '已自动保存，将使用本地 Alpine Codex。',
                en: saved.remoteEnabled
                    ? 'Autosaved. Codex mode will use the remote PC Bridge.'
                    : 'Autosaved. Codex mode will use local Alpine Codex.',
              )
            : _localeText(zh: '即将自动保存...', en: 'Autosave pending...');
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = _localeText(
          zh: 'Codex 配置保存失败：$error',
          en: 'Failed to save Codex config: $error',
        );
        _status = null;
      });
    } finally {
      if (mounted) {
        setState(() => _isSaving = false);
        if (_canSaveCurrentConfig && _currentSignature() != savingSignature) {
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
    Widget? suffixIcon,
  }) {
    return TextField(
      key: key,
      controller: controller,
      obscureText: obscureText,
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
        border: const OutlineInputBorder(),
        isDense: true,
        suffixIcon: suffixIcon,
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

  Widget _buildContextInjectionSwitch() {
    final palette = context.omniPalette;
    final enabled = !_isSaving;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: enabled
          ? () => _setContextInjectionEnabled(!_contextInjectionEnabled)
          : null,
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
              value: _contextInjectionEnabled,
              onToggle: _setContextInjectionEnabled,
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
    final mutedSurface = _isDarkTheme
        ? context.omniPalette.surfaceSecondary
        : const Color(0xFFF8FAFC);
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
                border: Border.all(color: borderColor),
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
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                _localeText(
                                  zh: '注入 Omnibot 上下文',
                                  en: 'Inject Omnibot Context',
                                ),
                                style: TextStyle(
                                  color: _primaryTextColor,
                                  fontSize: 13,
                                  fontWeight: FontWeight.w600,
                                  fontFamily: 'PingFang SC',
                                ),
                              ),
                            ),
                            _buildContextInjectionSwitch(),
                          ],
                        ),
                        Text(
                          _contextInjectionEnabled
                              ? _localeText(
                                  zh: '已开启：Codex 每轮会收到 SOUL、长期记忆、今日短期记忆和终端变量名；变量值不会明文注入。',
                                  en: 'Enabled: each Codex turn receives SOUL, long-term memory, today memory, and terminal variable names. Values are hidden.',
                                )
                              : _localeText(
                                  zh: '已关闭：Codex 使用干净上下文。',
                                  en: 'Disabled: Codex uses a clean context.',
                                ),
                          style: TextStyle(
                            color: _secondaryTextColor,
                            fontSize: 12,
                            height: 1.35,
                            fontFamily: 'PingFang SC',
                          ),
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
                        _buildTextField(
                          key: const Key('codex-config-base-url-field'),
                          controller: _baseUrlController,
                          label: 'Base URL',
                          hint: 'https://bring_your_own_key.endpoint/v1',
                          keyboardType: TextInputType.url,
                        ),
                        const SizedBox(height: 12),
                        _buildTextField(
                          key: const Key('codex-config-model-field'),
                          controller: _modelController,
                          label: 'Model',
                          hint: _defaultCodexModel,
                        ),
                        const SizedBox(height: 12),
                        _buildTextField(
                          key: const Key('codex-config-api-key-field'),
                          controller: _apiKeyController,
                          label: 'OPENAI_API_KEY',
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
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: mutedSurface,
                            borderRadius: BorderRadius.circular(10),
                            border: Border.all(color: borderColor),
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
                                    zh: '远程开关关闭时使用本地 Codex；配置修改会自动保存并断开当前 Codex 会话。',
                                    en: 'When the remote switch is off, local Codex is used. Changes autosave and disconnect the current Codex session.',
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
