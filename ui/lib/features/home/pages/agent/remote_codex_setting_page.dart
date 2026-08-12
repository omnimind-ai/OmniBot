import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/agent/codex_bridge_qr_scanner_page.dart';
import 'package:ui/features/home/pages/agent/codex_remote_directory_picker.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/data_destination_confirmation.dart';
import 'package:ui/theme/app_colors.dart';
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
  late final TextEditingController _bridgeUrlController;
  late final TextEditingController _bridgeTokenController;
  late final TextEditingController _bridgeCwdController;

  bool _loading = true;
  bool _saving = false;
  bool _testing = false;
  bool _syncing = false;
  bool _enabled = false;
  bool _obscureToken = true;
  bool _hasStoredToken = false;
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
      _hasStoredToken = config.hasRemoteBridgeToken;
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
    setState(() {
      _error = null;
      _status = !_complete
          ? _text(
              '启用远程模式需要填写 Bridge URL 和远程工作目录。',
              'Bridge URL and remote cwd are required when Remote mode is enabled.',
            )
          : _signature == _lastSavedSignature
          ? _text('已保存。', 'Saved.')
          : _text('有尚未保存的更改。', 'Unsaved changes.');
    });
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
      _status = _text('等待确认接收方…', 'Waiting for destination confirmation…');
    });
    try {
      final endpoint = _bridgeUrlController.text.trim();
      final outcome =
          await confirmDataDestinationAndRun<CodexRemoteBridgeConfig>(
            context: context,
            rawEndpoint: endpoint,
            capability: 'Remote Codex Bridge',
            operation: _text('保存配置', 'Save configuration'),
            dataTypes: [
              _text('Bridge 认证凭据（如已配置）', 'Bridge credential, when configured'),
              _text('远程工作目录路径', 'Remote workspace path'),
              if (_enabled)
                _text(
                  '启用后发送的提示词、对话、附件和工作区文件操作',
                  'Future prompts, conversations, attachments, and workspace file operations',
                ),
            ],
            action: () => AgentRuntimeService.writeRemoteBridgeConfig(
              remoteEnabled: _enabled,
              remoteBridgeUrl: endpoint,
              remoteBridgeToken: _bridgeTokenController.text.trim(),
              remoteCwd: _bridgeCwdController.text.trim(),
            ),
          );
      if (!outcome.confirmed || outcome.value == null) {
        if (mounted) {
          setState(() {
            _status = _text(
              '未保存；你没有确认本次接收方。远程模式不会被启用。',
              'Not saved; the destination was not confirmed. Remote mode was not enabled.',
            );
            _enabled = _lastSavedSignature?.startsWith('enabled\n') == true;
          });
        }
        return false;
      }
      final saved = outcome.value!;
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
            ? _text('已保存。', 'Saved.')
            : _text('有尚未保存的更改。', 'Unsaved changes.');
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
      final outcome = await confirmDataDestinationAndRun<Map<String, dynamic>>(
        context: context,
        rawEndpoint: url,
        capability: 'Remote Codex Bridge',
        operation: _text('测试连接', 'Test connection'),
        dataTypes: [
          _text('Bridge 认证凭据（如已配置）', 'Bridge credential, when configured'),
          _text('远程工作目录路径和连接探测信息', 'Remote workspace path and connection probe'),
        ],
        action: () => AgentRuntimeService.testRemoteConfig(
          remoteBridgeUrl: url,
          remoteBridgeToken: _bridgeTokenController.text.trim(),
          remoteCwd: cwd,
        ),
      );
      if (!outcome.confirmed || outcome.value == null) return;
      final result = outcome.value!;
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
    final outcome = await confirmDataDestinationAndRun<String?>(
      context: context,
      rawEndpoint: url,
      capability: 'Remote Codex Bridge',
      operation: _text('浏览远程目录', 'Browse remote directories'),
      dataTypes: [
        _text('Bridge 认证凭据（如已配置）', 'Bridge credential, when configured'),
        _text('当前目录路径和远程目录列表请求', 'Current path and remote directory listing request'),
      ],
      action: () => showCodexRemoteDirectoryPicker(
        context: context,
        remoteBridgeUrl: url,
        remoteBridgeToken: _bridgeTokenController.text.trim(),
        initialPath: _bridgeCwdController.text.trim(),
      ),
    );
    if (!outcome.confirmed) return;
    final selected = outcome.value;
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
    final background = dark ? palette.pageBackground : AppColors.background;
    final card = dark ? palette.surfacePrimary : Colors.white;
    final border = dark ? palette.borderSubtle : const Color(0x1A000000);
    return Scaffold(
      backgroundColor: background,
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
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: card,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: border),
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
                                  'Agent 聊天将使用远程 Codex app-server。',
                                  'Agent chat will use the remote Codex app-server.',
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
                            icon: const Icon(
                              Icons.folder_open_rounded,
                              size: 18,
                            ),
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
                          hint: _hasStoredToken
                              ? _text(
                                  '已安全保存；留空保留，输入新值替换',
                                  'Stored securely; leave blank to keep it, or enter a replacement',
                                )
                              : 'OMNIBOT_BRIDGE_TOKEN',
                          obscure: _obscureToken,
                          suffix: IconButton(
                            tooltip: _obscureToken
                                ? _text('显示 Token', 'Show token')
                                : _text('隐藏 Token', 'Hide token'),
                            onPressed: () =>
                                setState(() => _obscureToken = !_obscureToken),
                            icon: Icon(
                              _obscureToken
                                  ? Icons.visibility_outlined
                                  : Icons.visibility_off_outlined,
                              size: 18,
                            ),
                          ),
                        ),
                        const SizedBox(height: 12),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            FilledButton.icon(
                              key: const Key('codex-config-save-button'),
                              onPressed:
                                  _saving || !_complete || _signature == _lastSavedSignature
                                  ? null
                                  : _save,
                              icon: _saving
                                  ? const SizedBox(
                                      width: 14,
                                      height: 14,
                                      child: CircularProgressIndicator(strokeWidth: 2),
                                    )
                                  : const Icon(Icons.save_outlined, size: 17),
                              label: Text(_text('保存', 'Save')),
                            ),
                            OutlinedButton.icon(
                              key: const Key(
                                'codex-config-scan-bridge-qr-button',
                              ),
                              onPressed: _saving ? null : _scanQr,
                              icon: const Icon(
                                Icons.qr_code_scanner_rounded,
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
                                      Icons.wifi_tethering_rounded,
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
