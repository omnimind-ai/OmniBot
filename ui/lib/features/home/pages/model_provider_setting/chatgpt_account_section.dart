import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/services/chatgpt_oauth_service.dart';
import 'package:url_launcher/url_launcher.dart';

/// Authentication is scoped to one saved Provider. This widget never receives
/// credentials; only the native OAuth owner can exchange or refresh them.
class ChatGptAccountSection extends StatefulWidget {
  const ChatGptAccountSection({
    super.key,
    required this.profileId,
    required this.beforeLogin,
    required this.onAccountChanged,
    this.enabled = true,
    this.externalLauncher,
  });

  final String profileId;
  final Future<bool> Function() beforeLogin;
  final Future<void> Function(String profileId) onAccountChanged;
  final bool enabled;
  final Future<bool> Function(Uri)? externalLauncher;

  @override
  State<ChatGptAccountSection> createState() => _ChatGptAccountSectionState();
}

class _ChatGptAccountSectionState extends State<ChatGptAccountSection> {
  ChatGptAccount? _account;
  ChatGptOAuthFailure? _error;
  String? _notice;
  String? _attemptId;
  bool _busy = false;
  bool _signingIn = false;
  bool _isLoadingAccount = false;
  int _generation = 0;
  Timer? _pollTimer;
  Timer? _expiryTimer;

  String _text(String zh, String en) =>
      Localizations.localeOf(context).languageCode != 'zh' ? en : zh;

  @override
  void initState() {
    super.initState();
    if (widget.enabled) unawaited(_loadAccount(_generation));
  }

  @override
  void didUpdateWidget(covariant ChatGptAccountSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.profileId != widget.profileId ||
        (oldWidget.enabled && !widget.enabled)) {
      _cancelAttempt();
      _account = null;
      _error = null;
      _notice = null;
      if (widget.enabled) unawaited(_loadAccount(_generation));
    } else if (!oldWidget.enabled && widget.enabled) {
      unawaited(_loadAccount(_generation));
    }
  }

  @override
  void dispose() {
    _cancelAttempt();
    super.dispose();
  }

  bool _isCurrent(int generation) => mounted && generation == _generation;

  bool get _storageUnavailable =>
      _account?.secureStorageAvailable == false ||
      _error?.isStorageFailure == true;

  Future<void> _loadAccount(int generation) async {
    if (!_isCurrent(generation)) return;
    setState(() => _isLoadingAccount = true);
    try {
      final account = await ChatGptOAuthService.getAccount(widget.profileId);
      if (!_isCurrent(generation)) return;
      setState(() {
        _account = account;
        if (!account.secureStorageAvailable) {
          _error = ChatGptOAuthFailure.fromNativeCode(
            account.errorCode ?? 'secure_storage_unavailable',
            'read_account',
          );
        } else if (_error?.phase == 'read_account' ||
            _error?.isStorageFailure == true) {
          _error = null;
        }
      });
    } catch (error) {
      if (!_isCurrent(generation)) return;
      setState(
        () => _error = ChatGptOAuthFailure.fromError(
          error,
          phase: 'read_account',
        ),
      );
    } finally {
      if (_isCurrent(generation)) setState(() => _isLoadingAccount = false);
    }
  }

  void _cancelNative(String attemptId) {
    unawaited(ChatGptOAuthService.cancelLogin(attemptId).catchError((_) {}));
  }

  void _cancelAttempt() {
    ++_generation;
    _pollTimer?.cancel();
    _expiryTimer?.cancel();
    final attemptId = _attemptId;
    _attemptId = null;
    _busy = false;
    _signingIn = false;
    _isLoadingAccount = false;
    if (attemptId != null) _cancelNative(attemptId);
  }

  Future<void> _startLogin() async {
    if (_busy || !widget.enabled || _storageUnavailable || _isLoadingAccount) {
      return;
    }
    _cancelAttempt();
    final generation = _generation;
    var phase = 'save_profile';
    setState(() {
      _busy = true;
      _signingIn = true;
      _error = null;
      _notice = null;
    });
    try {
      final saved = await widget.beforeLogin();
      if (!_isCurrent(generation)) return;
      if (!saved) {
        throw const ChatGptOAuthFailure(
          code: 'profile_save_failed',
          phase: 'save_profile',
        );
      }
      phase = 'start_login';
      final attempt = await ChatGptOAuthService.startLogin(widget.profileId);
      if (!_isCurrent(generation)) {
        _cancelNative(attempt.id);
        return;
      }
      _attemptId = attempt.id;
      _expiryTimer = Timer(const Duration(minutes: 5), () {
        if (!_isCurrent(generation)) return;
        _cancelAttempt();
        setState(
          () => _error = const ChatGptOAuthFailure(
            code: 'login_timeout',
            phase: 'login_status',
          ),
        );
      });
      phase = 'open_browser';
      final opened =
          await (widget.externalLauncher?.call(attempt.authorizationUrl) ??
              launchUrl(
                attempt.authorizationUrl,
                mode: LaunchMode.externalApplication,
              ));
      if (!_isCurrent(generation)) return;
      if (!opened) {
        throw const ChatGptOAuthFailure(
          code: 'browser_unavailable',
          phase: 'open_browser',
        );
      }
      await _poll(generation, attempt.id);
    } catch (error) {
      if (!_isCurrent(generation)) return;
      _cancelAttempt();
      setState(
        () => _error = ChatGptOAuthFailure.fromError(
          error,
          phase: phase,
          fallbackCode: switch (phase) {
            'save_profile' => 'profile_save_failed',
            'open_browser' => 'browser_unavailable',
            _ => 'native_error',
          },
        ),
      );
    }
  }

  Future<void> _poll(int generation, String attemptId) async {
    if (!_isCurrent(generation)) return;
    var phase = 'login_status';
    try {
      final result = await ChatGptOAuthService.getLoginStatus(attemptId);
      if (!_isCurrent(generation)) return;
      if (result.status == 'pending') {
        _pollTimer = Timer(const Duration(seconds: 1), () {
          unawaited(_poll(generation, attemptId));
        });
        return;
      }
      _expiryTimer?.cancel();
      _attemptId = null;
      if (result.status == 'completed') {
        await _loadAccount(generation);
        if (!_isCurrent(generation)) return;
        phase = 'refresh_provider';
        await widget.onAccountChanged(widget.profileId);
        if (!_isCurrent(generation)) return;
        setState(() => _busy = false);
      } else {
        setState(() {
          _busy = false;
          _error = result.status == 'cancelled'
              ? null
              : ChatGptOAuthFailure.fromNativeCode(
                  result.errorCode ?? 'login_failed',
                  'login_status',
                );
        });
      }
    } catch (error) {
      if (!_isCurrent(generation)) return;
      _cancelAttempt();
      setState(
        () => _error = ChatGptOAuthFailure.fromError(
          error,
          phase: phase,
          fallbackCode: phase == 'refresh_provider'
              ? 'profile_refresh_failed'
              : 'native_error',
        ),
      );
    }
  }

  Future<void> _logout() async {
    if (_busy || !widget.enabled) return;
    _cancelAttempt();
    final generation = _generation;
    var phase = 'logout';
    setState(() {
      _busy = true;
      _error = null;
      _notice = null;
    });
    try {
      final revoked = await ChatGptOAuthService.logout(widget.profileId);
      if (!_isCurrent(generation)) return;
      await _loadAccount(generation);
      if (!_isCurrent(generation)) return;
      phase = 'refresh_provider';
      await widget.onAccountChanged(widget.profileId);
      if (!_isCurrent(generation)) return;
      if (!revoked) {
        setState(
          () => _notice = _text(
            '已退出本机账号，但无法确认远程授权已撤销。可在 ChatGPT 设置中断开连接。',
            'Signed out on this device. Remote revocation could not be confirmed; you can disconnect this app in ChatGPT Settings.',
          ),
        );
      }
    } catch (error) {
      if (!_isCurrent(generation)) return;
      setState(
        () => _error = ChatGptOAuthFailure.fromError(
          error,
          phase: phase,
          fallbackCode: phase == 'refresh_provider'
              ? 'profile_refresh_failed'
              : 'native_error',
        ),
      );
    } finally {
      if (_isCurrent(generation)) setState(() => _busy = false);
    }
  }

  Future<void> _openUsage() async {
    final generation = _generation;
    try {
      final uri = Uri.parse('https://chatgpt.com/settings/usage');
      final opened =
          await (widget.externalLauncher?.call(uri) ??
              launchUrl(uri, mode: LaunchMode.externalApplication));
      if (!opened) throw StateError('Browser unavailable');
    } catch (_) {
      if (!_isCurrent(generation)) return;
      setState(
        () => _error = const ChatGptOAuthFailure(
          code: 'browser_unavailable',
          phase: 'open_browser',
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final account = _account;
    final connected = account?.connected == true;
    final needsReauth = account?.needsReauth == true;
    final textColor = Theme.of(context).colorScheme.onSurfaceVariant;
    return Column(
      key: const Key('chatgpt-account-section'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (account != null && (connected || needsReauth)) ...[
          Text(
            account.email.isEmpty ? 'ChatGPT' : account.email,
            key: const Key('chatgpt-account-email'),
          ),
          const SizedBox(height: 8),
          Text(
            needsReauth
                ? _text(
                    '请重新登录以连接账号。',
                    'Sign in again to reconnect this account.',
                  )
                : account.planEnabled
                ? _text('已授权使用 ChatGPT 套餐。', 'ChatGPT plan access enabled.')
                : _text(
                    '尚未授权使用 ChatGPT 套餐，请重新授权并允许套餐访问。',
                    'ChatGPT plan access is not enabled. Re-authorize and allow plan access.',
                  ),
            key: const Key('chatgpt-plan-status'),
            style: TextStyle(color: textColor),
          ),
        ] else
          Text(
            _text(
              '通过 OpenAI 登录，并授权使用你的 ChatGPT 套餐。',
              'Sign in with OpenAI and authorize access to your ChatGPT plan.',
            ),
            style: TextStyle(color: textColor),
          ),
        const SizedBox(height: 8),
        Text(
          _text(
            '已连接账号可用于小万，也可供已适配 Responses 的 Agent 复用。',
            'Connected accounts support Xiaowan and Agents with a compatible Responses integration.',
          ),
          style: TextStyle(color: textColor),
        ),
        if (_busy && _signingIn) ...[
          const SizedBox(height: 12),
          Text(_text('请在浏览器中完成登录。', 'Complete sign-in in your browser.')),
          const SizedBox(height: 8),
          const LinearProgressIndicator(),
        ],
        if (_notice != null) ...[
          const SizedBox(height: 12),
          Text(
            _notice!,
            key: const Key('chatgpt-account-notice'),
            style: TextStyle(color: textColor),
          ),
        ],
        if (_error != null) ...[
          const SizedBox(height: 12),
          Text(
            _error!.message(
              english: Localizations.localeOf(context).languageCode != 'zh',
            ),
            key: const Key('chatgpt-login-error'),
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
          const SizedBox(height: 4),
          Text(
            _error!.diagnostic,
            key: const Key('chatgpt-login-diagnostic'),
            style: TextStyle(color: textColor, fontSize: 12),
          ),
        ],
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton(
              key: const Key('chatgpt-login-button'),
              onPressed:
                  _busy ||
                      !widget.enabled ||
                      _storageUnavailable ||
                      _isLoadingAccount
                  ? null
                  : _startLogin,
              child: Text(
                connected || needsReauth
                    ? _text('重新授权', 'Re-authorize')
                    : _text('使用 ChatGPT 登录', 'Continue with ChatGPT'),
              ),
            ),
            if (_storageUnavailable || _error?.phase == 'read_account')
              TextButton(
                key: const Key('chatgpt-retry-account-button'),
                onPressed: _busy || _isLoadingAccount || !widget.enabled
                    ? null
                    : () => _loadAccount(_generation),
                child: Text(_text('重试读取账号', 'Retry account check')),
              ),
            if (connected)
              TextButton(
                key: const Key('chatgpt-manage-usage-button'),
                onPressed: _openUsage,
                child: Text(_text('管理用量', 'Manage usage')),
              ),
            if (_busy && _signingIn)
              TextButton(
                key: const Key('chatgpt-cancel-login-button'),
                onPressed: () {
                  _cancelAttempt();
                  setState(() {});
                },
                child: Text(_text('取消', 'Cancel')),
              )
            else if (connected)
              TextButton(
                key: const Key('chatgpt-logout-button'),
                onPressed: !widget.enabled || _busy ? null : _logout,
                child: Text(_text('退出登录', 'Sign out')),
              ),
          ],
        ),
      ],
    );
  }
}
