import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/agent/codex_bridge_qr_scanner_page.dart';
import 'package:ui/features/home/pages/agent/codex_remote_directory_picker.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class RemoteCodexSettingPage extends StatefulWidget {
  const RemoteCodexSettingPage({super.key});

  @override
  State<RemoteCodexSettingPage> createState() => _RemoteCodexSettingPageState();
}

class _RemoteCodexSettingPageState extends State<RemoteCodexSettingPage> {
  static const Duration _autoSaveDelay = Duration(milliseconds: 700);

  late final TextEditingController _bridgeUrlController;
  late final TextEditingController _bridgeTokenController;
  late final TextEditingController _bridgeCwdController;

  Timer? _saveDebounce;
  bool _loading = true;
  bool _saving = false;
  bool _testing = false;
  bool _syncing = false;
  bool _enabled = false;
  bool _obscureToken = true;
  String? _error;
  String? _status;
  String? _lastSavedSignature;

  bool get _english =>
      Localizations.localeOf(context).languageCode.toLowerCase() == 'en';

  String _text(String zh, String en) => _english ? en : zh;

  bool get _complete =>
      !_enabled ||
      (_bridgeUrlController.text.trim().isNotEmpty &&
          _bridgeCwdController.text.trim().isNotEmpty);

  String get _signature => [
    _enabled ? 'enabled' : 'disabled',
    _bridgeUrlController.text.trim(),
    _bridgeTokenController.text.trim(),
    _bridgeCwdController.text.trim(),
  ].join('\n');

  @override
  void initState() {
    super.initState();
    _bridgeUrlController = TextEditingController();
    _bridgeTokenController = TextEditingController();
    _bridgeCwdController = TextEditingController();
    for (final controller in [
      _bridgeUrlController,
      _bridgeTokenController,
      _bridgeCwdController,
    ]) {
      controller.addListener(_handleEdited);
    }
    unawaited(_load());
  }

  @override
  void dispose() {
    _saveDebounce?.cancel();
    for (final controller in [
      _bridgeUrlController,
      _bridgeTokenController,
      _bridgeCwdController,
    ]) {
      controller.removeListener(_handleEdited);
      controller.dispose();
    }
    super.dispose();
  }

  void _setText(TextEditingController controller, String value) {
    controller.value = TextEditingValue(
      text: value,
      selection: TextSelection.collapsed(offset: value.length),
    );
  }

  void _sync(CodexRemoteBridgeConfig config) {
    _syncing = true;
    try {
      _setText(_bridgeUrlController, config.remoteBridgeUrl);
      _setText(_bridgeTokenController, config.remoteBridgeToken);
      _setText(_bridgeCwdController, config.remoteCwd);
      _enabled = config.remoteEnabled;
    } finally {
      _syncing = false;
    }
  }

  Future<void> _load() async {
    try {
      final config = await AgentRuntimeService.readRemoteBridgeConfig();
      if (!mounted) return;
      _sync(config);
      setState(() {
        _loading = false;
        _error = null;
        _status = null;
        _lastSavedSignature = _signature;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = _text(
          '远程 PC Bridge 配置读取失败：$error',
          'Failed to read Remote PC Bridge settings: $error',
        );
      });
    }
  }

  void _handleEdited() {
    if (_syncing || !mounted) return;
    _saveDebounce?.cancel();
    setState(() {
      _error = null;
      _status = !_complete
          ? _text(
              '启用远程模式需要填写 Bridge URL 和远程工作目录。',
              'Bridge URL and remote cwd are required when Remote mode is enabled.',
            )
          : _signature == _lastSavedSignature
          ? _text('已自动保存。', 'Autosaved.')
          : _text('即将自动保存…', 'Autosave pending…');
    });
    if (_complete && _signature != _lastSavedSignature) {
      _saveDebounce = Timer(_autoSaveDelay, () => unawaited(_save()));
    }
  }

  void _setEnabled(bool value) {
    if (_enabled == value || _saving) return;
    setState(() => _enabled = value);
    _handleEdited();
  }

  Future<bool> _save() async {
    if (_saving || !_complete) return false;
    final signature = _signature;
    if (signature == _lastSavedSignature) return true;
    setState(() {
      _saving = true;
      _error = null;
      _status = _text('正在自动保存…', 'Autosaving…');
    });
    try {
      final saved = await AgentRuntimeService.writeRemoteBridgeConfig(
        remoteEnabled: _enabled,
        remoteBridgeUrl: _bridgeUrlController.text.trim(),
        remoteBridgeToken: _bridgeTokenController.text.trim(),
        remoteCwd: _bridgeCwdController.text.trim(),
      );
      if (!mounted) return false;
      if (_signature == signature) {
        _sync(saved);
      }
      setState(() {
        _lastSavedSignature = [
          saved.remoteEnabled ? 'enabled' : 'disabled',
          saved.remoteBridgeUrl,
          saved.remoteBridgeToken,
          saved.remoteCwd,
        ].join('\n');
        _status = _signature == _lastSavedSignature
            ? _text('已自动保存。', 'Autosaved.')
            : _text('即将自动保存…', 'Autosave pending…');
      });
      return true;
    } catch (error) {
      if (!mounted) return false;
      setState(() {
        _error = _text(
          '远程 PC Bridge 配置保存失败：$error',
          'Failed to save Remote PC Bridge settings: $error',
        );
        _status = null;
      });
      return false;
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _testConnection() async {
    if (_testing) return;
    final url = _bridgeUrlController.text.trim();
    final cwd = _bridgeCwdController.text.trim();
    if (url.isEmpty || cwd.isEmpty) {
      showToast(
        _text(
          '请先填写 Bridge URL 和远程工作目录。',
          'Enter the Bridge URL and remote cwd first.',
        ),
        type: ToastType.warning,
      );
      return;
    }
    setState(() => _testing = true);
    try {
      final result = await AgentRuntimeService.testRemoteConfig(
        remoteBridgeUrl: url,
        remoteBridgeToken: _bridgeTokenController.text.trim(),
        remoteCwd: cwd,
      );
      if (!mounted) return;
      final ok = result['ok'] == true || result['ready'] == true;
      showToast(
        ok
            ? _text('远程 PC Bridge 可用。', 'Remote PC Bridge is ready.')
            : _text(
                '连接失败：${result['error'] ?? 'unknown'}',
                'Connection failed: ${result['error'] ?? 'unknown'}',
              ),
        type: ok ? ToastType.success : ToastType.error,
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        _text('连接失败：$error', 'Connection failed: $error'),
        type: ToastType.error,
      );
    } finally {
      if (mounted) setState(() => _testing = false);
    }
  }

  Future<void> _chooseDirectory() async {
    final url = _bridgeUrlController.text.trim();
    if (url.isEmpty) {
      showToast(
        _text('请先填写 Bridge URL。', 'Enter the Bridge URL first.'),
        type: ToastType.warning,
      );
      return;
    }
    final selected = await showCodexRemoteDirectoryPicker(
      context: context,
      remoteBridgeUrl: url,
      remoteBridgeToken: _bridgeTokenController.text.trim(),
      initialPath: _bridgeCwdController.text.trim(),
    );
    if (!mounted || selected == null || selected.trim().isEmpty) return;
    _setText(_bridgeCwdController, selected.trim());
    _handleEdited();
  }

  Future<void> _scanQr() async {
    final result = await Navigator.of(context).push<CodexBridgeQrScanResult>(
      MaterialPageRoute(
        builder: (_) => const CodexBridgeQrScannerPage(),
        fullscreenDialog: true,
      ),
    );
    if (!mounted || result == null) return;
    _syncing = true;
    try {
      _setText(_bridgeUrlController, result.bridgeUrl.trim());
      _setText(_bridgeTokenController, result.token.trim());
      _setText(_bridgeCwdController, result.cwd.trim());
      _enabled = true;
    } finally {
      _syncing = false;
    }
    _handleEdited();
  }

  Widget _field({
    required Key key,
    required TextEditingController controller,
    required String label,
    required String hint,
    bool obscure = false,
    TextInputType keyboardType = TextInputType.text,
    Widget? suffix,
  }) {
    final palette = context.omniPalette;
    return TextField(
      key: key,
      controller: controller,
      obscureText: obscure,
      keyboardType: keyboardType,
      textInputAction: TextInputAction.next,
      style: TextStyle(color: palette.textPrimary, fontSize: 13),
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        filled: true,
        fillColor: context.isDarkTheme
            ? palette.surfaceSecondary.withValues(alpha: 0.72)
            : const Color(0xFFF8FAFC),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide.none,
        ),
        suffixIcon: suffix,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final dark = context.isDarkTheme;
    final card = dark ? palette.surfacePrimary : Colors.white;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _text('远程 PC Bridge', 'Remote PC Bridge'),
        primary: true,
      ),
      body: SafeArea(
        top: false,
        bottom: false,
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: edgeToEdgeScrollPadding(
                  context,
                  const EdgeInsets.fromLTRB(18, 12, 18, 28),
                ),
                children: [
                  SettingsSectionTitle(
                    label: _text('Codex 远程运行', 'Remote Codex runtime'),
                    subtitle: _text(
                      '这里只保留远程 PC Bridge。本地 Agent 的 API、账号与默认模型请在“Agent 模式”中分别配置。',
                      'This page only manages Remote PC Bridge. Configure each local Agent API, account, and default model in Agent mode.',
                    ),
                  ),
                  Container(
                    key: const Key('remote-pc-bridge-settings-card'),
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: card,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                _text(
                                  '启用远程 PC Bridge',
                                  'Enable Remote PC Bridge',
                                ),
                                style: TextStyle(
                                  color: palette.textPrimary,
                                  fontSize: 14,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ),
                            Switch.adaptive(
                              value: _enabled,
                              onChanged: _saving ? null : _setEnabled,
                            ),
                          ],
                        ),
                        Text(
                          _enabled
                              ? _text(
                                  'Agent 聊天将使用远程 ACP。',
                                  'Agent chat will use the remote ACP runtime.',
                                )
                              : _text(
                                  '远程连接已关闭，本地聊天使用所选 ACP Agent。',
                                  'Remote is off; local chat uses the selected ACP Agent.',
                                ),
                          style: TextStyle(
                            color: palette.textSecondary,
                            fontSize: 12,
                          ),
                        ),
                        const SizedBox(height: 14),
                        _field(
                          key: const Key(
                            'codex-config-remote-bridge-url-field',
                          ),
                          controller: _bridgeUrlController,
                          label: 'Bridge URL',
                          hint: 'ws://192.168.1.10:17321/codex',
                          keyboardType: TextInputType.url,
                        ),
                        const SizedBox(height: 12),
                        _field(
                          key: const Key('codex-config-remote-cwd-field'),
                          controller: _bridgeCwdController,
                          label: _text('远程工作目录', 'Remote cwd'),
                          hint: '/Users/name/code/project',
                          suffix: IconButton(
                            tooltip: _text('选择目录', 'Choose directory'),
                            onPressed: _chooseDirectory,
                            icon: const Icon(LucideIcons.folderOpen, size: 18),
                          ),
                        ),
                        const SizedBox(height: 12),
                        _field(
                          key: const Key('codex-config-remote-token-field'),
                          controller: _bridgeTokenController,
                          label: _text(
                            'Bridge Token（可选）',
                            'Bridge Token (optional)',
                          ),
                          hint: 'OMNIBOT_BRIDGE_TOKEN',
                          obscure: _obscureToken,
                          suffix: IconButton(
                            tooltip: _obscureToken
                                ? _text('显示 Token', 'Show token')
                                : _text('隐藏 Token', 'Hide token'),
                            onPressed: () =>
                                setState(() => _obscureToken = !_obscureToken),
                            icon: Icon(
                              _obscureToken
                                  ? LucideIcons.eye
                                  : LucideIcons.eyeOff,
                              size: 18,
                            ),
                          ),
                        ),
                        const SizedBox(height: 12),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            OutlinedButton.icon(
                              key: const Key(
                                'codex-config-scan-bridge-qr-button',
                              ),
                              onPressed: _saving ? null : _scanQr,
                              icon: const Icon(
                                LucideIcons.scanQrCode,
                                size: 17,
                              ),
                              label: Text(_text('扫码连接', 'Scan QR')),
                            ),
                            OutlinedButton.icon(
                              onPressed: _testing ? null : _testConnection,
                              icon: _testing
                                  ? const SizedBox(
                                      width: 14,
                                      height: 14,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  : const Icon(
                                      LucideIcons.radioTower,
                                      size: 17,
                                    ),
                              label: Text(
                                _testing
                                    ? _text('测试中…', 'Testing…')
                                    : _text('测试连接', 'Test connection'),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  if (_status != null || _error != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      _error ?? _status!,
                      style: TextStyle(
                        fontSize: 12,
                        color: _error != null
                            ? Theme.of(context).colorScheme.error
                            : palette.textSecondary,
                      ),
                    ),
                  ],
                ],
              ),
      ),
    );
  }
}
