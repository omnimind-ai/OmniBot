import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/services/account_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

enum _AuthMode { signIn, register, resetPassword }

class AccountPage extends StatefulWidget {
  const AccountPage({super.key});

  @override
  State<AccountPage> createState() => _AccountPageState();
}

class _AccountPageState extends State<AccountPage> {
  final _formKey = GlobalKey<FormState>();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  final _verificationCodeController = TextEditingController();

  bool _loading = true;
  bool _busy = false;
  _AuthMode _authMode = _AuthMode.signIn;
  bool _showPassword = false;
  AccountSessionState? _session;
  AccountOverview? _overview;
  RegistrationCodeRequest? _codeRequest;
  String? _codeRequestEmail;
  String? _error;

  bool get _english => Localizations.localeOf(context).languageCode != 'zh';

  String _text(String zh, String en) => _english ? en : zh;

  bool get _registerMode => _authMode == _AuthMode.register;

  bool get _resetPasswordMode => _authMode == _AuthMode.resetPassword;

  @override
  void initState() {
    super.initState();
    _loadAccount();
  }

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    _confirmPasswordController.dispose();
    _verificationCodeController.dispose();
    super.dispose();
  }

  Future<void> _loadAccount() async {
    if (mounted) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    try {
      final session = await AccountService.getSessionState();
      AccountOverview? overview;
      if (session.configured && session.signedIn) {
        overview = await AccountService.getOverview();
      }
      if (!mounted) return;
      setState(() {
        _session = session;
        _overview = overview;
      });
    } on PlatformException catch (error) {
      if (!mounted) return;
      if (error.code == 'NOT_AUTHENTICATED' ||
          error.code == 'invalid_refresh_token') {
        setState(() {
          _session = const AccountSessionState(
            configured: true,
            signedIn: false,
          );
          _overview = null;
        });
      } else {
        setState(() => _error = _messageFor(error));
      }
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _error = _text('账号功能暂时不可用，请稍后重试', 'Account is temporarily unavailable');
      });
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _sendVerificationCode() async {
    final email = _emailController.text.trim();
    if (!_looksLikeEmail(email)) {
      setState(() => _error = _text('请先填写正确的邮箱', 'Enter a valid email first'));
      return;
    }
    await _withBusy(() async {
      final request = _resetPasswordMode
          ? await AccountService.requestPasswordResetCode(email)
          : await AccountService.requestRegistrationCode(email);
      if (!mounted) return;
      setState(() {
        _codeRequest = request;
        _codeRequestEmail = email;
        _error = null;
      });
      _showSuccessToast(
        _text(
          '验证码已发送，${request.expiresInSeconds ~/ 60} 分钟内有效',
          'Code sent and valid for ${request.expiresInSeconds ~/ 60} minutes',
        ),
      );
    });
  }

  Future<void> _submitAuth() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    final email = _emailController.text.trim();
    final password = _passwordController.text;
    final creatingAccount = _registerMode;
    final resettingPassword = _resetPasswordMode;
    await _withBusy(() async {
      if (creatingAccount || resettingPassword) {
        final request = _codeRequest;
        if (request == null || _codeRequestEmail != email) {
          throw PlatformException(
            code: 'CODE_NOT_REQUESTED',
            message: _text(
              '请为当前邮箱重新发送验证码',
              'Request a verification code for this email first',
            ),
          );
        }
        if (resettingPassword) {
          await AccountService.resetPassword(
            email: email,
            newPassword: password,
            verificationRequestId: request.requestId,
            verificationCode: _verificationCodeController.text.trim(),
          );
          _passwordController.clear();
          _confirmPasswordController.clear();
          _verificationCodeController.clear();
          _codeRequest = null;
          _codeRequestEmail = null;
          if (!mounted) return;
          setState(() => _authMode = _AuthMode.signIn);
          _showSuccessToast(
            _text(
              '密码已重置，请使用新密码登录',
              'Password reset. Sign in with your new password.',
            ),
          );
          return;
        }
        await AccountService.register(
          email: email,
          password: password,
          verificationRequestId: request.requestId,
          verificationCode: _verificationCodeController.text.trim(),
        );
      }
      try {
        await AccountService.login(email: email, password: password);
      } catch (_) {
        if (creatingAccount && mounted) {
          setState(() => _authMode = _AuthMode.signIn);
        }
        rethrow;
      }
      _passwordController.clear();
      _confirmPasswordController.clear();
      _verificationCodeController.clear();
      _codeRequest = null;
      _codeRequestEmail = null;
      _authMode = _AuthMode.signIn;
      await _loadAccount();
      if (mounted) {
        _showSuccessToast(
          creatingAccount
              ? _text('注册并登录成功', 'Account created and signed in')
              : _text('登录成功', 'Signed in'),
        );
      }
    });
  }

  Future<void> _changeMode(AiAccessMode mode) async {
    final overview = _overview;
    if (overview == null || overview.settings.mode == mode) return;
    await _withBusy(() async {
      final settings = await AccountService.updateAiMode(mode);
      if (!mounted) return;
      setState(() {
        _overview = AccountOverview(user: overview.user, settings: settings);
      });
      if (mode == AiAccessMode.platform && !settings.officialProviderReady) {
        showToast(
          settings.officialProviderStatus ??
              _text(
                '官方模型暂时未就绪，请稍后重试',
                'Official models are not ready yet. Try again later.',
              ),
          type: ToastType.warning,
        );
      } else {
        _showSuccessToast(_text('AI 来源已更新', 'AI source updated'));
      }
    });
  }

  Future<void> _logout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(_text('退出登录', 'Sign out')),
        content: Text(
          _text('只会退出当前设备，其他设备不受影响。', 'Only this device will be signed out.'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(_text('取消', 'Cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(_text('退出', 'Sign out')),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await _withBusy(() async {
      try {
        await AccountService.logout();
      } finally {
        if (mounted) {
          setState(() {
            _session = const AccountSessionState(
              configured: true,
              signedIn: false,
            );
            _overview = null;
          });
        }
      }
    });
  }

  Future<void> _showPlatformUsage() {
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) =>
          _PlatformUsageSheet(english: _english, errorMessage: _messageFor),
    );
  }

  Future<void> _showSessions() {
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) =>
          _SessionsSheet(english: _english, errorMessage: _messageFor),
    );
  }

  Future<void> _showChangePasswordDialog() async {
    final formKey = GlobalKey<FormState>();
    var currentPassword = '';
    var newPassword = '';
    var confirmedPassword = '';
    var submitting = false;
    var showPasswords = false;
    String? dialogError;

    final changed = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialogState) => PopScope(
          canPop: !submitting,
          child: AlertDialog(
            title: Text(_text('修改密码', 'Change password')),
            content: SingleChildScrollView(
              child: Form(
                key: formKey,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      _text(
                        '修改成功后，其他设备会退出登录，当前设备不受影响。',
                        'Other devices will be signed out. This device stays signed in.',
                      ),
                    ),
                    const SizedBox(height: 16),
                    TextFormField(
                      key: const ValueKey('current-password-field'),
                      obscureText: !showPasswords,
                      autofillHints: const [AutofillHints.password],
                      onChanged: (value) => currentPassword = value,
                      decoration: InputDecoration(
                        labelText: _text('当前密码', 'Current password'),
                      ),
                      validator: (value) => (value ?? '').isEmpty
                          ? _text('请输入当前密码', 'Enter your current password')
                          : null,
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      key: const ValueKey('new-password-field'),
                      obscureText: !showPasswords,
                      autofillHints: const [AutofillHints.newPassword],
                      onChanged: (value) => newPassword = value,
                      decoration: InputDecoration(
                        labelText: _text('新密码', 'New password'),
                        helperText: _text('8 到 16 个字符', '8 to 16 characters'),
                      ),
                      validator: _passwordValidationMessage,
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      key: const ValueKey('confirm-new-password-field'),
                      obscureText: !showPasswords,
                      autofillHints: const [AutofillHints.newPassword],
                      onChanged: (value) => confirmedPassword = value,
                      decoration: InputDecoration(
                        labelText: _text('确认新密码', 'Confirm new password'),
                      ),
                      validator: (_) => confirmedPassword == newPassword
                          ? null
                          : _text('两次密码不一致', 'Passwords do not match'),
                    ),
                    CheckboxListTile(
                      value: showPasswords,
                      contentPadding: EdgeInsets.zero,
                      onChanged: submitting
                          ? null
                          : (value) => setDialogState(
                              () => showPasswords = value ?? false,
                            ),
                      title: Text(_text('显示密码', 'Show passwords')),
                      controlAffinity: ListTileControlAffinity.leading,
                    ),
                    if (dialogError != null) _errorBanner(dialogError!),
                  ],
                ),
              ),
            ),
            actions: [
              TextButton(
                onPressed: submitting
                    ? null
                    : () => Navigator.pop(dialogContext, false),
                child: Text(_text('取消', 'Cancel')),
              ),
              FilledButton(
                key: const ValueKey('confirm-change-password'),
                onPressed: submitting
                    ? null
                    : () async {
                        if (!(formKey.currentState?.validate() ?? false)) {
                          return;
                        }
                        setDialogState(() {
                          submitting = true;
                          dialogError = null;
                        });
                        try {
                          await AccountService.changePassword(
                            currentPassword: currentPassword,
                            newPassword: newPassword,
                          );
                          if (dialogContext.mounted) {
                            Navigator.pop(dialogContext, true);
                          }
                        } on PlatformException catch (error) {
                          if (dialogContext.mounted) {
                            setDialogState(() {
                              submitting = false;
                              dialogError = _messageFor(error);
                            });
                          }
                        } catch (_) {
                          if (dialogContext.mounted) {
                            setDialogState(() {
                              submitting = false;
                              dialogError = _text(
                                '修改失败，请稍后重试',
                                'Could not change the password. Try again later.',
                              );
                            });
                          }
                        }
                      },
                child: submitting
                    ? const SizedBox.square(
                        dimension: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Text(_text('确认修改', 'Change password')),
              ),
            ],
          ),
        ),
      ),
    );
    if (changed == true && mounted) {
      _showSuccessToast(_text('密码已修改', 'Password changed'));
    }
  }

  Future<void> _showDeleteAccountFlow() async {
    final overview = _overview;
    if (overview == null) return;
    final proceed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        icon: Icon(
          LucideIcons.triangleAlert,
          color: Theme.of(dialogContext).colorScheme.error,
        ),
        title: Text(_text('永久删除账号？', 'Permanently delete account?')),
        content: Text(
          _text(
            '服务器中的账号、登录会话和平台额度信息会永久删除，无法恢复。本机聊天和文件不会自动清理。',
            'Your server-side account, sessions, and platform quota data will be permanently deleted. Local chats and files on this device are not removed automatically.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: Text(_text('取消', 'Cancel')),
          ),
          FilledButton(
            key: const ValueKey('continue-delete-account'),
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(dialogContext).colorScheme.error,
            ),
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text(_text('继续验证', 'Continue')),
          ),
        ],
      ),
    );
    if (proceed != true || !mounted) return;

    final formKey = GlobalKey<FormState>();
    var confirmationEmail = '';
    var currentPassword = '';
    var submitting = false;
    var showPassword = false;
    String? dialogError;
    final deleted = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialogState) => PopScope(
          canPop: !submitting,
          child: AlertDialog(
            title: Text(_text('最后确认', 'Final confirmation')),
            content: SingleChildScrollView(
              child: Form(
                key: formKey,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      _text(
                        '请输入账号邮箱和当前密码，确认是你本人操作。',
                        'Enter your account email and current password to confirm your identity.',
                      ),
                    ),
                    const SizedBox(height: 16),
                    TextFormField(
                      key: const ValueKey('delete-account-email-field'),
                      keyboardType: TextInputType.emailAddress,
                      autocorrect: false,
                      onChanged: (value) => confirmationEmail = value,
                      decoration: InputDecoration(
                        labelText: _text('账号邮箱', 'Account email'),
                        hintText: overview.user.email,
                      ),
                      validator: (_) =>
                          confirmationEmail.trim().toLowerCase() ==
                              overview.user.email.trim().toLowerCase()
                          ? null
                          : _text(
                              '请输入当前账号的完整邮箱',
                              'Enter the full email for this account.',
                            ),
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      key: const ValueKey('delete-account-password-field'),
                      obscureText: !showPassword,
                      autofillHints: const [AutofillHints.password],
                      onChanged: (value) => currentPassword = value,
                      decoration: InputDecoration(
                        labelText: _text('当前密码', 'Current password'),
                        suffixIcon: IconButton(
                          onPressed: submitting
                              ? null
                              : () => setDialogState(
                                  () => showPassword = !showPassword,
                                ),
                          icon: Icon(
                            showPassword ? LucideIcons.eyeOff : LucideIcons.eye,
                          ),
                        ),
                      ),
                      validator: (value) => (value ?? '').isEmpty
                          ? _text('请输入当前密码', 'Enter your current password')
                          : null,
                    ),
                    if (dialogError != null) ...[
                      const SizedBox(height: 12),
                      _errorBanner(dialogError!),
                    ],
                  ],
                ),
              ),
            ),
            actions: [
              TextButton(
                onPressed: submitting
                    ? null
                    : () => Navigator.pop(dialogContext, false),
                child: Text(_text('取消', 'Cancel')),
              ),
              FilledButton(
                key: const ValueKey('confirm-delete-account'),
                style: FilledButton.styleFrom(
                  backgroundColor: Theme.of(dialogContext).colorScheme.error,
                ),
                onPressed: submitting
                    ? null
                    : () async {
                        if (!(formKey.currentState?.validate() ?? false)) {
                          return;
                        }
                        setDialogState(() {
                          submitting = true;
                          dialogError = null;
                        });
                        try {
                          await AccountService.deleteAccount(currentPassword);
                          if (dialogContext.mounted) {
                            Navigator.pop(dialogContext, true);
                          }
                        } on PlatformException catch (error) {
                          if (dialogContext.mounted) {
                            setDialogState(() {
                              submitting = false;
                              dialogError = _messageFor(error);
                            });
                          }
                        } catch (_) {
                          if (dialogContext.mounted) {
                            setDialogState(() {
                              submitting = false;
                              dialogError = _text(
                                '删除失败，请稍后重试',
                                'Could not delete the account. Try again later.',
                              );
                            });
                          }
                        }
                      },
                child: submitting
                    ? const SizedBox.square(
                        dimension: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Text(_text('永久删除', 'Delete permanently')),
              ),
            ],
          ),
        ),
      ),
    );

    if (deleted == true && mounted) {
      setState(() {
        _session = const AccountSessionState(configured: true, signedIn: false);
        _overview = null;
        _error = null;
        _authMode = _AuthMode.signIn;
      });
      _showSuccessToast(_text('账号已删除', 'Account deleted'));
    }
  }

  String? _passwordValidationMessage(String? value) {
    final length = (value ?? '').characters.length;
    if (length < 8 || length > 16) {
      return _text('密码需为 8 到 16 个字符', 'Use 8 to 16 characters.');
    }
    return null;
  }

  Future<void> _withBusy(Future<void> Function() operation) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await operation();
    } on PlatformException catch (error) {
      if (mounted) setState(() => _error = _messageFor(error));
    } catch (_) {
      if (mounted) {
        setState(() {
          _error = _text('操作失败，请稍后重试', 'Operation failed. Try again later.');
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String _messageFor(PlatformException error) {
    switch (error.code) {
      case 'invalid_credentials':
        return _text('邮箱或密码不正确', 'Incorrect email or password');
      case 'email_already_registered':
        return _text('这个邮箱已经注册', 'This email is already registered');
      case 'invalid_verification_code':
        return _text('验证码无效或已经过期', 'The code is invalid or expired');
      case 'password_reset_failed':
        return _text(
          '邮箱或验证码不正确，请重新检查',
          'The email or verification code is incorrect.',
        );
      case 'verification_unavailable':
        return _text(
          '这个验证码已经使用，请重新获取',
          'This code has already been used. Request a new one.',
        );
      case 'invalid_password':
        return _text(
          '密码需为 8 到 16 个字符',
          'Use a password between 8 and 16 characters.',
        );
      case 'current_password_invalid':
        return _text('当前密码不正确', 'The current password is incorrect.');
      case 'password_reuse':
        return _text('新密码不能与当前密码相同', 'The new password must be different.');
      case 'cannot_revoke_current_session':
        return _text(
          '当前设备不能在这里移除，请使用退出登录',
          'Sign out normally to remove the current device.',
        );
      case 'session_not_found':
      case 'invalid_session_id':
        return _text(
          '这个登录设备已不存在，请刷新后重试',
          'This session no longer exists. Refresh and try again.',
        );
      case 'ACCOUNT_FEATURE_UNAVAILABLE':
        return _text(
          '账号服务器版本尚未更新，这项功能暂不可用',
          'The account server must be updated before this feature is available.',
        );
      case 'rate_limited':
      case 'too_many_requests':
        return _text('操作太频繁，请稍后再试', 'Too many attempts. Try again later.');
      case 'ACCOUNT_NOT_CONFIGURED':
        return _text('账号服务尚未配置', 'Account service is not configured');
      case 'NOT_AUTHENTICATED':
      case 'invalid_access_token':
      case 'invalid_refresh_token':
      case 'missing_access_token':
        return _text('登录已失效，请重新登录', 'Your session expired. Sign in again.');
      case 'account_service_unavailable':
      case 'internal_error':
      case 'ACCOUNT_HTTP_500':
      case 'ACCOUNT_HTTP_502':
      case 'ACCOUNT_HTTP_503':
      case 'ACCOUNT_UNEXPECTED_ERROR':
        return _text(
          '账号服务暂时不可用，请稍后重试',
          'Account service is temporarily unavailable. Try again later.',
        );
      default:
        return _text('操作失败，请稍后重试', 'Operation failed. Try again later.');
    }
  }

  bool _looksLikeEmail(String value) {
    final at = value.indexOf('@');
    return at > 0 && value.indexOf('.', at) > at + 1;
  }

  void _showSuccessToast(String message) {
    showToast(message, type: ToastType.success);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _text('账号与 AI 服务', 'Account & AI service'),
        primary: true,
      ),
      body: Column(
        children: [
          if (_busy) const LinearProgressIndicator(minHeight: 2),
          Expanded(child: _buildBody()),
        ],
      ),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final session = _session;
    if (session == null) return _buildErrorState();
    if (!session.configured) return _buildNotConfigured();
    if (!session.signedIn || _overview == null) return _buildAuthForm();
    return _buildSignedIn(_overview!);
  }

  Widget _buildErrorState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_error ?? _text('加载失败', 'Failed to load')),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _loadAccount,
              child: Text(_text('重试', 'Retry')),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildNotConfigured() {
    return SafeArea(
      top: false,
      bottom: false,
      child: ListView(
        padding: edgeToEdgeScrollPadding(
          context,
          const EdgeInsets.fromLTRB(18, 10, 18, 28),
        ),
        children: [
          SettingsSectionTitle(label: _text('账号', 'Account')),
          Padding(
            padding: const EdgeInsets.fromLTRB(4, 4, 4, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  LucideIcons.cloudOff,
                  size: 28,
                  color: context.omniPalette.textSecondary,
                ),
                const SizedBox(height: 14),
                Text(
                  _text('账号服务尚未配置', 'Account service is not configured'),
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 8),
                Text(
                  _text(
                    '当前安装包没有设置 OMNIBOT_BASE_URL。配置品牌域名并重新构建后即可登录。',
                    'This build has no OMNIBOT_BASE_URL. Configure the public service domain and rebuild.',
                  ),
                  style: TextStyle(color: context.omniPalette.textSecondary),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAuthForm() {
    return SafeArea(
      top: false,
      bottom: false,
      child: ListView(
        padding: edgeToEdgeScrollPadding(
          context,
          const EdgeInsets.fromLTRB(18, 10, 18, 28),
        ),
        children: [
          Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SettingsSectionTitle(
                  label: switch (_authMode) {
                    _AuthMode.register => _text(
                      '创建小万账号',
                      'Create your account',
                    ),
                    _AuthMode.resetPassword => _text(
                      '重置密码',
                      'Reset your password',
                    ),
                    _AuthMode.signIn => _text('登录小万账号', 'Sign in to OmniBot'),
                  },
                  subtitle: _resetPasswordMode
                      ? _text(
                          '验证码会发送到你的注册邮箱。重置后，其他设备会退出登录。',
                          'A code will be sent to your registered email. Other devices will be signed out after reset.',
                        )
                      : _text(
                          '账号用于同步登录状态、平台额度和 AI 来源选择。',
                          'Your account syncs sessions, platform quota, and AI source choice.',
                        ),
                  bottomPadding: 16,
                ),
                if (_resetPasswordMode)
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton.icon(
                      key: const ValueKey('back-to-sign-in'),
                      onPressed: _busy
                          ? null
                          : () => _changeAuthMode(_AuthMode.signIn),
                      icon: const Icon(LucideIcons.arrowLeft, size: 18),
                      label: Text(_text('返回登录', 'Back to sign in')),
                    ),
                  )
                else
                  _authModeSelector(),
                const SizedBox(height: 20),
                TextFormField(
                  key: const ValueKey('auth-email-field'),
                  controller: _emailController,
                  keyboardType: TextInputType.emailAddress,
                  autofillHints: const [AutofillHints.email],
                  decoration: InputDecoration(
                    labelText: _text('邮箱', 'Email'),
                    prefixIcon: const Icon(LucideIcons.mail, size: 20),
                  ),
                  validator: (value) => _looksLikeEmail(value?.trim() ?? '')
                      ? null
                      : _text('请输入正确的邮箱', 'Enter a valid email'),
                ),
                const SizedBox(height: 14),
                TextFormField(
                  key: const ValueKey('auth-password-field'),
                  controller: _passwordController,
                  obscureText: !_showPassword,
                  autofillHints: _registerMode || _resetPasswordMode
                      ? const [AutofillHints.newPassword]
                      : const [AutofillHints.password],
                  decoration: InputDecoration(
                    labelText: _resetPasswordMode
                        ? _text('新密码', 'New password')
                        : _text('密码', 'Password'),
                    helperText: _registerMode || _resetPasswordMode
                        ? _text('8 到 16 个字符', '8 to 16 characters')
                        : null,
                    prefixIcon: const Icon(LucideIcons.lockKeyhole, size: 20),
                    suffixIcon: IconButton(
                      onPressed: () =>
                          setState(() => _showPassword = !_showPassword),
                      icon: Icon(
                        _showPassword ? LucideIcons.eyeOff : LucideIcons.eye,
                        size: 20,
                      ),
                    ),
                  ),
                  validator: (value) {
                    if ((value ?? '').isEmpty) {
                      return _text('请输入密码', 'Enter your password');
                    }
                    if ((_registerMode || _resetPasswordMode) &&
                        (value!.characters.length < 8 ||
                            value.characters.length > 16)) {
                      return _text('密码需为 8 到 16 个字符', 'Use 8 to 16 characters');
                    }
                    return null;
                  },
                ),
                if (_authMode == _AuthMode.signIn)
                  Align(
                    alignment: Alignment.centerRight,
                    child: TextButton(
                      key: const ValueKey('forgot-password'),
                      onPressed: _busy
                          ? null
                          : () => _changeAuthMode(_AuthMode.resetPassword),
                      child: Text(_text('忘记密码？', 'Forgot password?')),
                    ),
                  ),
                if (_registerMode || _resetPasswordMode) ...[
                  const SizedBox(height: 14),
                  TextFormField(
                    key: const ValueKey('auth-confirm-password-field'),
                    controller: _confirmPasswordController,
                    obscureText: !_showPassword,
                    autofillHints: const [AutofillHints.newPassword],
                    decoration: InputDecoration(
                      labelText: _text('确认密码', 'Confirm password'),
                      prefixIcon: const Icon(
                        LucideIcons.rotateCcwKey,
                        size: 20,
                      ),
                    ),
                    validator: (value) => value == _passwordController.text
                        ? null
                        : _text('两次密码不一致', 'Passwords do not match'),
                  ),
                  const SizedBox(height: 14),
                  TextFormField(
                    key: const ValueKey('auth-verification-code-field'),
                    controller: _verificationCodeController,
                    keyboardType: TextInputType.number,
                    maxLength: 6,
                    decoration: InputDecoration(
                      labelText: _text('邮箱验证码', 'Email verification code'),
                      counterText: '',
                      prefixIcon: const Icon(LucideIcons.mailCheck, size: 20),
                      suffixIcon: TextButton(
                        onPressed: _busy ? null : _sendVerificationCode,
                        child: Text(
                          _codeRequest == null
                              ? _text('发送', 'Send')
                              : _text('重新发送', 'Resend'),
                        ),
                      ),
                    ),
                    validator: (value) => (value ?? '').trim().length == 6
                        ? null
                        : _text('请输入 6 位验证码', 'Enter the 6-digit code'),
                  ),
                ],
                if (_error != null) ...[
                  const SizedBox(height: 14),
                  _errorBanner(_error!),
                ],
                const SizedBox(height: 22),
                FilledButton(
                  key: const ValueKey('submit-auth'),
                  onPressed: _busy ? null : _submitAuth,
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                  ),
                  child: Text(switch (_authMode) {
                    _AuthMode.register => _text(
                      '注册并登录',
                      'Create account & sign in',
                    ),
                    _AuthMode.resetPassword => _text(
                      '确认重置密码',
                      'Reset password',
                    ),
                    _AuthMode.signIn => _text('登录', 'Sign in'),
                  }),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _authModeSelector() {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: palette.segmentTrack,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          _authModeButton(_text('登录', 'Sign in'), false),
          _authModeButton(_text('注册', 'Register'), true),
        ],
      ),
    );
  }

  Widget _authModeButton(String label, bool register) {
    final selected = _registerMode == register;
    return Expanded(
      child: InkWell(
        onTap: _busy
            ? null
            : () => _changeAuthMode(
                register ? _AuthMode.register : _AuthMode.signIn,
              ),
        borderRadius: BorderRadius.circular(9),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
            color: selected
                ? context.omniPalette.segmentThumb
                : Colors.transparent,
            borderRadius: BorderRadius.circular(9),
          ),
          child: Text(
            label,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: selected
                  ? context.omniPalette.textPrimary
                  : context.omniPalette.textSecondary,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
            ),
          ),
        ),
      ),
    );
  }

  void _changeAuthMode(_AuthMode mode) {
    setState(() {
      _authMode = mode;
      _error = null;
      _codeRequest = null;
      _codeRequestEmail = null;
      _verificationCodeController.clear();
      _passwordController.clear();
      _confirmPasswordController.clear();
    });
  }

  Widget _buildSignedIn(AccountOverview overview) {
    final settings = overview.settings;
    final quotaSubtitle = !settings.platformAvailable
        ? _text(
            settings.platformUnavailableReason ?? '平台 AI 服务暂未开放，额度将在开放后使用',
            'Platform AI is not available yet; quota can be used after launch',
          )
        : settings.platform.enabled
        ? _text('可用于平台提供的 AI 服务', 'Available for the platform AI service')
        : _text('平台额度当前未启用', 'Platform quota is currently disabled');
    return SafeArea(
      top: false,
      bottom: false,
      child: RefreshIndicator(
        onRefresh: _loadAccount,
        child: ListView(
          padding: edgeToEdgeScrollPadding(
            context,
            const EdgeInsets.fromLTRB(18, 10, 18, 28),
          ),
          children: [
            SettingsSectionTitle(label: _text('账号', 'Account')),
            _summaryRow(
              icon: LucideIcons.userRound,
              title: overview.user.email,
              subtitle: _text(
                '已验证 · 当前设备已登录',
                'Verified · signed in on this device',
              ),
            ),
            _sectionDivider(),
            _summaryRow(
              icon: LucideIcons.coins,
              title: settings.platform.weeklyLimit > 0
                  ? _text('本周剩余额度', 'Remaining this week')
                  : _text('平台额度', 'Platform quota'),
              subtitle:
                  settings.platformAvailable &&
                      settings.platform.weeklyLimit > 0
                  ? _text(
                      '文字、识图、图片、语音共用，每周一自动恢复',
                      'Shared by text, vision, images and voice; renews every Monday',
                    )
                  : quotaSubtitle,
              trailing: settings.platformAvailable
                  ? Text(
                      '${settings.platform.balance}',
                      style: TextStyle(
                        color: context.omniPalette.accentPrimary,
                        fontSize: 20,
                        fontWeight: FontWeight.w700,
                      ),
                    )
                  : null,
            ),
            if (settings.platform.weeklyLimit > 0) ...[
              const SizedBox(height: 8),
              Text(
                _text(
                  '本周已用/预占 ${settings.platform.weeklyUsed} / ${settings.platform.weeklyLimit}',
                  'Used/reserved this week ${settings.platform.weeklyUsed} / ${settings.platform.weeklyLimit}',
                ),
                style: TextStyle(
                  color: context.omniPalette.textSecondary,
                  fontSize: 12,
                ),
              ),
            ],
            const SizedBox(height: 24),
            SettingsSectionTitle(label: _text('AI 来源', 'AI source')),
            _modeOption(
              mode: AiAccessMode.platform,
              selected:
                  settings.platformAvailable &&
                  settings.mode == AiAccessMode.platform,
              enabled: settings.platformAvailable,
              icon: LucideIcons.cloud,
              title: _text('使用平台额度', 'Use platform quota'),
            ),
            _sectionDivider(),
            _modeOption(
              mode: AiAccessMode.byok,
              selected: settings.mode == AiAccessMode.byok,
              icon: LucideIcons.keyRound,
              title: _text('使用自己的 API Key', 'Use my own API key'),
            ),
            if (settings.mode == AiAccessMode.byok) ...[
              _sectionDivider(left: 34),
              _apiKeyAction(),
            ],
            const SizedBox(height: 24),
            SettingsSectionTitle(
              label: _text('用量与安全', 'Usage & security'),
              subtitle: _text(
                '查看额度消耗，并管理密码和已登录设备。',
                'Review quota usage and manage your password and signed-in devices.',
              ),
            ),
            _accountAction(
              key: const ValueKey('account-usage-action'),
              icon: LucideIcons.chartNoAxesColumnIncreasing,
              title: _text('最近平台用量', 'Recent platform usage'),
              subtitle: _text(
                '查看最近 20 条模型调用和额度消耗',
                'View the latest 20 model calls and quota charges',
              ),
              onTap: _showPlatformUsage,
            ),
            _sectionDivider(),
            _accountAction(
              key: const ValueKey('account-sessions-action'),
              icon: LucideIcons.smartphone,
              title: _text('登录设备', 'Signed-in devices'),
              subtitle: _text(
                '查看并退出其他设备上的登录',
                'Review and sign out sessions on other devices',
              ),
              onTap: _showSessions,
            ),
            _sectionDivider(),
            _accountAction(
              key: const ValueKey('change-password-action'),
              icon: LucideIcons.shieldCheck,
              title: _text('修改密码', 'Change password'),
              subtitle: _text(
                '修改后，其他设备会退出登录',
                'Other devices will be signed out after the change',
              ),
              onTap: _showChangePasswordDialog,
            ),
            _sectionDivider(),
            _accountAction(
              key: const ValueKey('delete-account-action'),
              icon: LucideIcons.trash2,
              title: _text('删除账号', 'Delete account'),
              subtitle: _text(
                '永久删除服务器中的账号数据',
                'Permanently delete your account data from the server',
              ),
              onTap: _showDeleteAccountFlow,
              destructive: true,
            ),
            if (_error != null) ...[
              const SizedBox(height: 14),
              _errorBanner(_error!),
            ],
            const SizedBox(height: 24),
            TextButton.icon(
              onPressed: _busy ? null : _logout,
              style: TextButton.styleFrom(
                minimumSize: const Size.fromHeight(46),
                foregroundColor: Theme.of(context).colorScheme.error,
              ),
              icon: const Icon(LucideIcons.logOut, size: 18),
              label: Text(_text('退出当前设备', 'Sign out on this device')),
            ),
          ],
        ),
      ),
    );
  }

  Widget _summaryRow({
    required IconData icon,
    required String title,
    required String subtitle,
    Widget? trailing,
  }) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 14, 2, 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Icon(icon, size: 20, color: palette.textPrimary),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                    color: palette.textPrimary,
                    height: 1.5,
                    fontFamily: 'PingFang SC',
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 11,
                    fontWeight: FontWeight.w400,
                    height: 1.55,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ],
            ),
          ),
          if (trailing != null) ...[const SizedBox(width: 12), trailing],
        ],
      ),
    );
  }

  Widget _sectionDivider({double left = 34}) {
    return Padding(
      padding: EdgeInsets.only(left: left),
      child: Divider(
        height: 1,
        thickness: 1,
        color: context.omniPalette.borderSubtle.withValues(
          alpha: context.isDarkTheme ? 0.5 : 0.78,
        ),
      ),
    );
  }

  Widget _apiKeyAction() {
    final palette = context.omniPalette;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => GoRouterManager.push('/home/model_provider_setting'),
        borderRadius: BorderRadius.circular(14),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(4, 13, 2, 13),
          child: Row(
            children: [
              Icon(LucideIcons.settings, size: 18, color: palette.textPrimary),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  _text('配置我的 API Key', 'Configure my API key'),
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              Icon(
                LucideIcons.chevronRight,
                size: 18,
                color: palette.textTertiary,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _accountAction({
    required Key key,
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
    bool destructive = false,
  }) {
    final palette = context.omniPalette;
    final color = destructive
        ? Theme.of(context).colorScheme.error
        : palette.textPrimary;
    return Material(
      key: key,
      color: Colors.transparent,
      child: InkWell(
        onTap: _busy ? null : onTap,
        borderRadius: BorderRadius.circular(14),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(4, 13, 2, 13),
          child: Row(
            children: [
              Icon(icon, size: 19, color: color),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        color: color,
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: TextStyle(
                        color: destructive ? color : palette.textSecondary,
                        fontSize: 11,
                        height: 1.45,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Icon(
                LucideIcons.chevronRight,
                size: 18,
                color: destructive ? color : palette.textTertiary,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _modeOption({
    required AiAccessMode mode,
    required bool selected,
    required IconData icon,
    required String title,
    bool enabled = true,
  }) {
    final palette = context.omniPalette;
    return Semantics(
      button: true,
      selected: selected,
      enabled: enabled,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: _busy || !enabled ? null : () => _changeMode(mode),
          borderRadius: BorderRadius.circular(14),
          splashColor: palette.accentPrimary.withValues(alpha: 0.08),
          highlightColor: Colors.transparent,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            padding: const EdgeInsets.fromLTRB(4, 14, 2, 14),
            decoration: BoxDecoration(
              color: selected
                  ? palette.accentPrimary.withValues(alpha: 0.07)
                  : Colors.transparent,
              borderRadius: BorderRadius.circular(14),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  icon,
                  size: 20,
                  color: !enabled
                      ? palette.textTertiary
                      : selected
                      ? palette.accentPrimary
                      : palette.textPrimary,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: TextStyle(
                          color: enabled
                              ? palette.textPrimary
                              : palette.textTertiary,
                          fontSize: 14,
                          fontWeight: FontWeight.w500,
                          height: 1.5,
                          fontFamily: 'PingFang SC',
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 12),
                Icon(
                  selected ? LucideIcons.circleCheck : LucideIcons.circle,
                  size: 19,
                  color: enabled && selected
                      ? palette.accentPrimary
                      : palette.textTertiary,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _errorBanner(String message) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.red.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          const Icon(LucideIcons.circleAlert, color: Colors.red, size: 20),
          const SizedBox(width: 8),
          Expanded(
            child: Text(message, style: const TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );
  }
}

class _PlatformUsageSheet extends StatefulWidget {
  const _PlatformUsageSheet({
    required this.english,
    required this.errorMessage,
  });

  final bool english;
  final String Function(PlatformException) errorMessage;

  @override
  State<_PlatformUsageSheet> createState() => _PlatformUsageSheetState();
}

class _PlatformUsageSheetState extends State<_PlatformUsageSheet> {
  List<PlatformUsageEntry>? _entries;
  String? _error;

  String _text(String zh, String en) => widget.english ? en : zh;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _entries = null;
      _error = null;
    });
    try {
      final entries = await AccountService.listPlatformUsage();
      if (mounted) setState(() => _entries = entries);
    } on PlatformException catch (error) {
      if (mounted) setState(() => _error = widget.errorMessage(error));
    } catch (_) {
      if (mounted) {
        setState(() {
          _error = _text(
            '暂时无法读取用量，请稍后重试',
            'Usage is temporarily unavailable. Try again later.',
          );
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      top: false,
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.72,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _text('最近平台用量', 'Recent platform usage'),
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _text(
                            '仅显示最近 20 条，额度以服务器结算为准。',
                            'Shows the latest 20 records. Server settlement is authoritative.',
                          ),
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    key: const ValueKey('refresh-platform-usage'),
                    tooltip: _text('刷新', 'Refresh'),
                    onPressed: _entries == null ? null : _load,
                    icon: const Icon(LucideIcons.refreshCw),
                  ),
                  IconButton(
                    tooltip: _text('关闭', 'Close'),
                    onPressed: () => Navigator.pop(context),
                    icon: const Icon(LucideIcons.x),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(child: _buildBody(context)),
          ],
        ),
      ),
    );
  }

  Widget _buildBody(BuildContext context) {
    final error = _error;
    if (error != null) {
      return _SheetMessage(
        icon: LucideIcons.circleAlert,
        message: error,
        actionLabel: _text('重试', 'Retry'),
        onAction: _load,
      );
    }
    final entries = _entries;
    if (entries == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (entries.isEmpty) {
      return _SheetMessage(
        icon: LucideIcons.chartNoAxesColumnIncreasing,
        message: _text(
          '还没有平台用量记录。使用官方 AI 后会显示在这里。',
          'No platform usage yet. Official AI calls will appear here.',
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      itemCount: entries.length,
      separatorBuilder: (_, _) => const Divider(height: 1),
      itemBuilder: (context, index) {
        final entry = entries[index];
        final model = entry.model.trim().isEmpty
            ? _text('官方模型', 'Official model')
            : entry.model;
        return ListTile(
          key: ValueKey('platform-usage-$index'),
          contentPadding: const EdgeInsets.symmetric(horizontal: 4),
          leading: const Icon(LucideIcons.bot),
          title: Text(model, maxLines: 1, overflow: TextOverflow.ellipsis),
          subtitle: Text(
            '${_formatAccountDate(entry.createdAt)}\n'
            '${_text('输入', 'Input')} ${entry.promptTokens} · '
            '${_text('输出', 'Output')} ${entry.completionTokens} · '
            '${_text('共', 'Total')} ${entry.totalTokens}',
          ),
          isThreeLine: true,
          trailing: Text(
            _text('消耗 ${entry.quotaUsed}', 'Used ${entry.quotaUsed}'),
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
        );
      },
    );
  }
}

class _SessionsSheet extends StatefulWidget {
  const _SessionsSheet({required this.english, required this.errorMessage});

  final bool english;
  final String Function(PlatformException) errorMessage;

  @override
  State<_SessionsSheet> createState() => _SessionsSheetState();
}

class _SessionsSheetState extends State<_SessionsSheet> {
  List<AccountDeviceSession>? _sessions;
  String? _error;
  String? _notice;
  String? _busySessionId;
  bool _revokingAll = false;

  String _text(String zh, String en) => widget.english ? en : zh;

  bool get _busy => _busySessionId != null || _revokingAll;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _sessions = null;
      _error = null;
      _notice = null;
    });
    try {
      final sessions = await AccountService.listSessions();
      sessions.sort((left, right) {
        if (left.current != right.current) return left.current ? -1 : 1;
        final leftTime = left.lastUsedAt ?? left.createdAt;
        final rightTime = right.lastUsedAt ?? right.createdAt;
        return (rightTime ?? DateTime.fromMillisecondsSinceEpoch(0)).compareTo(
          leftTime ?? DateTime.fromMillisecondsSinceEpoch(0),
        );
      });
      if (mounted) setState(() => _sessions = sessions);
    } on PlatformException catch (error) {
      if (mounted) setState(() => _error = widget.errorMessage(error));
    } catch (_) {
      if (mounted) {
        setState(() {
          _error = _text(
            '暂时无法读取登录设备，请稍后重试',
            'Signed-in devices are temporarily unavailable. Try again later.',
          );
        });
      }
    }
  }

  Future<void> _revoke(AccountDeviceSession session) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(_text('退出这个设备？', 'Sign out this device?')),
        content: Text(
          _text(
            '这个设备需要重新输入邮箱和密码才能使用账号。',
            'This device will need the email and password to sign in again.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: Text(_text('取消', 'Cancel')),
          ),
          FilledButton(
            key: const ValueKey('confirm-revoke-session'),
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text(_text('确认退出', 'Sign out')),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _busySessionId = session.id;
      _error = null;
      _notice = null;
    });
    try {
      await AccountService.revokeSession(session.id);
      if (!mounted) return;
      setState(() {
        _sessions = _sessions
            ?.where((item) => item.id != session.id)
            .toList(growable: false);
        _busySessionId = null;
        _notice = _text('已退出该设备', 'Device signed out');
      });
    } on PlatformException catch (error) {
      if (mounted) {
        setState(() {
          _busySessionId = null;
          _error = widget.errorMessage(error);
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _busySessionId = null;
          _error = _text(
            '退出设备失败，请稍后重试',
            'Could not sign out the device. Try again later.',
          );
        });
      }
    }
  }

  Future<void> _revokeOtherSessions() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(_text('退出全部其他设备？', 'Sign out all other devices?')),
        content: Text(
          _text(
            '当前设备会保持登录，其他设备都需要重新登录。',
            'This device stays signed in. Every other device will need to sign in again.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: Text(_text('取消', 'Cancel')),
          ),
          FilledButton(
            key: const ValueKey('confirm-revoke-other-sessions'),
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text(_text('全部退出', 'Sign out all')),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _revokingAll = true;
      _error = null;
      _notice = null;
    });
    try {
      final revoked = await AccountService.revokeOtherSessions();
      if (!mounted) return;
      setState(() {
        _sessions = _sessions
            ?.where((session) => session.current)
            .toList(growable: false);
        _revokingAll = false;
        _notice = _text(
          '已退出 $revoked 个其他设备',
          'Signed out $revoked other device(s)',
        );
      });
    } on PlatformException catch (error) {
      if (mounted) {
        setState(() {
          _revokingAll = false;
          _error = widget.errorMessage(error);
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _revokingAll = false;
          _error = _text(
            '退出其他设备失败，请稍后重试',
            'Could not sign out other devices. Try again later.',
          );
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final sessions = _sessions;
    final hasOtherSessions =
        sessions?.any((session) => !session.current) ?? false;
    return SafeArea(
      top: false,
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.76,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _text('登录设备', 'Signed-in devices'),
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _text(
                            '服务目前仅记录登录时间，暂不读取设备名称。',
                            'The service records sign-in times without reading device names.',
                          ),
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    tooltip: _text('刷新', 'Refresh'),
                    onPressed: sessions == null || _busy ? null : _load,
                    icon: const Icon(LucideIcons.refreshCw),
                  ),
                  IconButton(
                    tooltip: _text('关闭', 'Close'),
                    onPressed: _busy ? null : () => Navigator.pop(context),
                    icon: const Icon(LucideIcons.x),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            if (_error != null && sessions != null)
              _InlineSheetNotice(message: _error!, error: true)
            else if (_notice != null)
              _InlineSheetNotice(message: _notice!),
            Expanded(child: _buildBody(context)),
            if (hasOtherSessions)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                child: OutlinedButton.icon(
                  key: const ValueKey('revoke-other-sessions'),
                  onPressed: _busy ? null : _revokeOtherSessions,
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size.fromHeight(46),
                  ),
                  icon: _revokingAll
                      ? const SizedBox.square(
                          dimension: 17,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(LucideIcons.logOut, size: 18),
                  label: Text(_text('退出全部其他设备', 'Sign out all other devices')),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildBody(BuildContext context) {
    if (_sessions == null && _error == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_sessions == null) {
      return _SheetMessage(
        icon: LucideIcons.circleAlert,
        message: _error!,
        actionLabel: _text('重试', 'Retry'),
        onAction: _load,
      );
    }
    final sessions = _sessions!;
    if (sessions.isEmpty) {
      return _SheetMessage(
        icon: LucideIcons.smartphone,
        message: _text('没有可显示的登录设备', 'No sessions to display'),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      itemCount: sessions.length,
      separatorBuilder: (_, _) => const Divider(height: 1),
      itemBuilder: (context, index) {
        final session = sessions[index];
        final otherIndex = session.current
            ? 0
            : sessions
                  .take(index + 1)
                  .where((candidate) => !candidate.current)
                  .length;
        final title = session.current
            ? _text('当前设备', 'Current device')
            : _text('其他登录设备 $otherIndex', 'Other device $otherIndex');
        final busy = _busySessionId == session.id;
        return ListTile(
          key: ValueKey('account-session-${session.id}'),
          contentPadding: const EdgeInsets.symmetric(horizontal: 4),
          leading: Icon(
            session.current ? LucideIcons.smartphone : LucideIcons.monitor,
          ),
          title: Row(
            children: [
              Flexible(child: Text(title)),
              if (session.current) ...[
                const SizedBox(width: 8),
                Chip(
                  visualDensity: VisualDensity.compact,
                  label: Text(_text('本机', 'This device')),
                ),
              ],
            ],
          ),
          subtitle: Text(
            '${_text('最近活动', 'Last active')} '
            '${_formatAccountDate(session.lastUsedAt ?? session.createdAt)}',
          ),
          trailing: session.current
              ? null
              : TextButton(
                  key: ValueKey('revoke-session-${session.id}'),
                  onPressed: _busy ? null : () => _revoke(session),
                  child: busy
                      ? const SizedBox.square(
                          dimension: 17,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : Text(_text('退出', 'Sign out')),
                ),
        );
      },
    );
  }
}

class _SheetMessage extends StatelessWidget {
  const _SheetMessage({
    required this.icon,
    required this.message,
    this.actionLabel,
    this.onAction,
  });

  final IconData icon;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 32),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            if (actionLabel != null && onAction != null) ...[
              const SizedBox(height: 16),
              FilledButton(onPressed: onAction, child: Text(actionLabel!)),
            ],
          ],
        ),
      ),
    );
  }
}

class _InlineSheetNotice extends StatelessWidget {
  const _InlineSheetNotice({required this.message, this.error = false});

  final String message;
  final bool error;

  @override
  Widget build(BuildContext context) {
    final color = error
        ? Theme.of(context).colorScheme.error
        : Theme.of(context).colorScheme.primary;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text(message, style: TextStyle(color: color)),
    );
  }
}

String _formatAccountDate(DateTime? value) {
  if (value == null) return '--';
  final local = value.toLocal();
  String twoDigits(int number) => number.toString().padLeft(2, '0');
  return '${local.year}-${twoDigits(local.month)}-${twoDigits(local.day)} '
      '${twoDigits(local.hour)}:${twoDigits(local.minute)}';
}
