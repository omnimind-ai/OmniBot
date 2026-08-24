import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/my/pages/account/account_lifecycle_sheets.dart';
import 'package:ui/services/account_service.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/app_update_dialog.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';
import 'package:ui/widgets/settings_section_title.dart';

String formatWeeklyQuotaResetCountdown(DateTime now, {required bool english}) {
  final startOfToday = DateTime(now.year, now.month, now.day);
  final nextMonday = startOfToday.add(Duration(days: 8 - now.weekday));
  final remaining = nextMonday.difference(now);
  final totalHours = (remaining.inMinutes / Duration.minutesPerHour).ceil();
  final days = totalHours ~/ Duration.hoursPerDay;
  final hours = totalHours % Duration.hoursPerDay;
  return english ? '${days}d ${hours}h' : '$days天 $hours小时';
}

class AccountPage extends StatefulWidget {
  const AccountPage({super.key})
    : authOnly = false,
      onAuthenticated = null,
      showAuthHeading = true;

  const AccountPage.authOnly({
    super.key,
    this.onAuthenticated,
    this.showAuthHeading = true,
  }) : authOnly = true;

  final bool authOnly;
  final VoidCallback? onAuthenticated;
  final bool showAuthHeading;

  @override
  State<AccountPage> createState() => _AccountPageState();
}

class _AccountPageState extends State<AccountPage> {
  final _formKey = GlobalKey<FormState>();
  final _registerFormKey = GlobalKey<FormState>();
  final _authPageController = PageController();
  final _registerPasswordFocusNode = FocusNode();
  final _emailController = TextEditingController();
  final _registerEmailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _registerPasswordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  final _verificationCodeController = TextEditingController();

  bool _loading = true;
  bool _busy = false;
  bool _registerMode = false;
  bool _showPassword = false;
  bool _passwordResetDialogOpen = false;
  int? _authSwipePointer;
  Offset _authSwipeDelta = Offset.zero;
  AccountSessionState? _session;
  AccountOverview? _overview;
  RegistrationCodeRequest? _codeRequest;
  String? _codeRequestEmail;
  String? _error;

  bool get _english => Localizations.localeOf(context).languageCode != 'zh';

  String _text(String zh, String en) => _english ? en : zh;

  @override
  void initState() {
    super.initState();
    _registerPasswordFocusNode.addListener(_onRegisterPasswordFocusChanged);
    _loadAccount();
  }

  @override
  void dispose() {
    _authPageController.dispose();
    _registerPasswordFocusNode.removeListener(_onRegisterPasswordFocusChanged);
    _registerPasswordFocusNode.dispose();
    _emailController.dispose();
    _registerEmailController.dispose();
    _passwordController.dispose();
    _registerPasswordController.dispose();
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
      var session = await AccountService.getSessionState();
      if (session.configured && !session.cloudServicePolicyKnown) {
        try {
          await AppUpdateService.checkNow();
          session = await AccountService.getSessionState();
        } catch (_) {
          // The native account guard remains fail-closed. Keep the policy
          // unavailable state so the page can offer an explicit retry.
        }
      }
      AccountOverview? overview;
      if (session.configured &&
          session.signedIn &&
          session.cloudServiceAccessAllowed) {
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
    final email = _registerEmailController.text.trim();
    if (!_looksLikeEmail(email)) {
      setState(() => _error = _text('请先填写正确的邮箱', 'Enter a valid email first'));
      return;
    }
    await _withBusy(() async {
      final request = await AccountService.requestRegistrationCode(email);
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
    final creatingAccount = _registerMode;
    final formKey = creatingAccount ? _registerFormKey : _formKey;
    if (!(formKey.currentState?.validate() ?? false)) return;
    final email = creatingAccount
        ? _registerEmailController.text.trim()
        : _emailController.text.trim();
    final password = creatingAccount
        ? _registerPasswordController.text
        : _passwordController.text;
    await _withBusy(() async {
      if (creatingAccount) {
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
          _selectAuthMode(false);
        }
        rethrow;
      }
      _passwordController.clear();
      _registerPasswordController.clear();
      _confirmPasswordController.clear();
      _verificationCodeController.clear();
      _codeRequest = null;
      _codeRequestEmail = null;
      if (mounted) _selectAuthMode(false);
      await _loadAccount();
      if (mounted) {
        _showSuccessToast(
          creatingAccount
              ? _text('注册并登录成功', 'Account created and signed in')
              : _text('登录成功', 'Signed in'),
        );
        widget.onAuthenticated?.call();
      }
    });
  }

  Future<void> _logout() async {
    final confirmed = await showSettingsDetailSheet<bool>(
      context: context,
      builder: (sheetContext) => SettingsDetailSheet(
        key: const ValueKey('sign-out-sheet'),
        title: _text('退出登录', 'Sign out'),
        body: Text(
          _text('只会退出当前设备，其他设备不受影响。', 'Only this device will be signed out.'),
        ),
        actions: [
          TextButton(
            style: settingsDetailSheetActionStyle(sheetContext),
            onPressed: () => Navigator.pop(sheetContext, false),
            child: Text(_text('取消', 'Cancel')),
          ),
          TextButton(
            style: settingsDetailSheetActionStyle(
              sheetContext,
              foregroundColor: Theme.of(sheetContext).colorScheme.error,
            ),
            onPressed: () => Navigator.pop(sheetContext, true),
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

  Future<void> _showPasswordResetDialog() async {
    if (_passwordResetDialogOpen) return;
    setState(() => _passwordResetDialogOpen = true);
    final formKey = GlobalKey<FormState>();
    var emailValue = _emailController.text;
    var passwordValue = '';
    var confirmValue = '';
    var codeValue = '';
    RegistrationCodeRequest? request;
    String? requestEmail;
    String? dialogError;
    var submitting = false;
    var sendingCode = false;
    var showPassword = false;

    try {
      final reset = await showSettingsDetailSheet<bool>(
        context: context,
        isScrollControlled: true,
        isDismissible: false,
        enableDrag: false,
        builder: (sheetContext) => StatefulBuilder(
          builder: (context, setDialogState) => PopScope(
            canPop: !submitting && !sendingCode,
            child: SettingsDetailSheet(
              key: const ValueKey('reset-password-sheet'),
              title: _text('重置密码', 'Reset password'),
              subtitle: _text(
                '验证码会发送到注册邮箱。重置成功后，其他设备需要重新登录。',
                'A code will be sent to your registered email. Other devices must sign in again after reset.',
              ),
              avoidKeyboard: true,
              body: Form(
                key: formKey,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    TextFormField(
                      key: const ValueKey('auth-email-field'),
                      initialValue: emailValue,
                      onChanged: (value) => emailValue = value,
                      keyboardType: TextInputType.emailAddress,
                      decoration: InputDecoration(
                        labelText: _text('邮箱', 'Email'),
                        suffixIcon: TextButton(
                          onPressed: submitting || sendingCode
                              ? null
                              : () async {
                                  final email = emailValue.trim();
                                  if (!_looksLikeEmail(email)) {
                                    setDialogState(() {
                                      dialogError = _text(
                                        '请输入正确的邮箱',
                                        'Enter a valid email',
                                      );
                                    });
                                    return;
                                  }
                                  setDialogState(() {
                                    sendingCode = true;
                                    dialogError = null;
                                  });
                                  try {
                                    final result =
                                        await AccountService.requestPasswordResetCode(
                                          email,
                                        );
                                    if (!sheetContext.mounted) return;
                                    setDialogState(() {
                                      request = result;
                                      requestEmail = email;
                                      sendingCode = false;
                                    });
                                  } on PlatformException catch (error) {
                                    if (!sheetContext.mounted) return;
                                    setDialogState(() {
                                      sendingCode = false;
                                      dialogError = _messageFor(error);
                                    });
                                  } catch (_) {
                                    if (!sheetContext.mounted) return;
                                    setDialogState(() {
                                      sendingCode = false;
                                      dialogError = _text(
                                        '验证码发送失败，请稍后重试',
                                        'Could not send the code. Try again later.',
                                      );
                                    });
                                  }
                                },
                          child: sendingCode
                              ? const SizedBox.square(
                                  dimension: 16,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : Text(
                                  request == null
                                      ? _text('发送', 'Send')
                                      : _text('重新发送', 'Resend'),
                                ),
                        ),
                      ),
                      validator: (value) => _looksLikeEmail(value?.trim() ?? '')
                          ? null
                          : _text('请输入正确的邮箱', 'Enter a valid email'),
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      key: const ValueKey('auth-password-field'),
                      onChanged: (value) => passwordValue = value,
                      obscureText: !showPassword,
                      autofillHints: const [AutofillHints.newPassword],
                      decoration: InputDecoration(
                        labelText: _text('新密码', 'New password'),
                        helperText: _text('8 到 16 个字符', '8 to 16 characters'),
                      ),
                      validator: _passwordValidationMessage,
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      key: const ValueKey('auth-confirm-password-field'),
                      onChanged: (value) => confirmValue = value,
                      obscureText: !showPassword,
                      autofillHints: const [AutofillHints.newPassword],
                      decoration: InputDecoration(
                        labelText: _text('确认新密码', 'Confirm new password'),
                      ),
                      validator: (_) => confirmValue == passwordValue
                          ? null
                          : _text('两次密码不一致', 'Passwords do not match'),
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      key: const ValueKey('auth-verification-code-field'),
                      onChanged: (value) => codeValue = value,
                      keyboardType: TextInputType.number,
                      maxLength: 6,
                      decoration: InputDecoration(
                        labelText: _text('邮箱验证码', 'Email verification code'),
                        counterText: '',
                      ),
                      validator: (value) => (value ?? '').trim().length == 6
                          ? null
                          : _text('请输入 6 位验证码', 'Enter the 6-digit code'),
                    ),
                    CheckboxListTile(
                      value: showPassword,
                      contentPadding: EdgeInsets.zero,
                      controlAffinity: ListTileControlAffinity.leading,
                      onChanged: submitting
                          ? null
                          : (value) => setDialogState(
                              () => showPassword = value ?? false,
                            ),
                      title: Text(_text('显示密码', 'Show passwords')),
                    ),
                    if (dialogError != null) _errorBanner(dialogError!),
                  ],
                ),
              ),
              actions: [
                TextButton(
                  style: settingsDetailSheetActionStyle(sheetContext),
                  onPressed: submitting || sendingCode
                      ? null
                      : () => Navigator.pop(sheetContext, false),
                  child: Text(_text('取消', 'Cancel')),
                ),
                TextButton(
                  key: const ValueKey('submit-auth'),
                  style: settingsDetailSheetActionStyle(sheetContext),
                  onPressed: submitting || sendingCode
                      ? null
                      : () async {
                          if (!(formKey.currentState?.validate() ?? false)) {
                            return;
                          }
                          final email = emailValue.trim();
                          if (request == null || requestEmail != email) {
                            setDialogState(() {
                              dialogError = _text(
                                '请先为当前邮箱发送验证码',
                                'Send a code to this email first.',
                              );
                            });
                            return;
                          }
                          setDialogState(() {
                            submitting = true;
                            dialogError = null;
                          });
                          try {
                            await AccountService.resetPassword(
                              email: email,
                              newPassword: passwordValue,
                              verificationRequestId: request!.requestId,
                              verificationCode: codeValue.trim(),
                            );
                            if (sheetContext.mounted) {
                              Navigator.pop(sheetContext, true);
                            }
                          } on PlatformException catch (error) {
                            if (!sheetContext.mounted) return;
                            setDialogState(() {
                              submitting = false;
                              dialogError = _messageFor(error);
                            });
                          } catch (_) {
                            if (!sheetContext.mounted) return;
                            setDialogState(() {
                              submitting = false;
                              dialogError = _text(
                                '重置失败，请稍后重试',
                                'Could not reset the password. Try again later.',
                              );
                            });
                          }
                        },
                  child: submitting
                      ? const SizedBox.square(
                          dimension: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : Text(_text('确认重置', 'Confirm reset')),
                ),
              ],
            ),
          ),
        ),
      );
      if (reset == true && mounted) {
        _passwordController.clear();
        _showSuccessToast(
          _text('密码已重置，请重新登录', 'Password reset. Sign in again.'),
        );
      }
    } finally {
      await Future<void>.delayed(const Duration(milliseconds: 250));
      if (mounted) setState(() => _passwordResetDialogOpen = false);
    }
  }

  Future<void> _showPlatformUsage() => showSettingsDetailSheet<void>(
    context: context,
    builder: (context) =>
        PlatformUsageSheet(english: _english, errorMessage: _messageFor),
  );

  Future<void> _showSessions() => showSettingsDetailSheet<void>(
    context: context,
    builder: (context) =>
        SessionsSheet(english: _english, errorMessage: _messageFor),
  );

  Future<void> _showChangePasswordDialog() async {
    final formKey = GlobalKey<FormState>();
    var currentPassword = '';
    var newPassword = '';
    var confirmedPassword = '';
    var submitting = false;
    var showPasswords = false;
    String? dialogError;
    final changed = await showSettingsDetailSheet<bool>(
      context: context,
      isScrollControlled: true,
      isDismissible: true,
      enableDrag: true,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialogState) => PopScope(
          canPop: !submitting,
          child: SettingsDetailSheet(
            key: const ValueKey('change-password-sheet'),
            title: _text('修改密码', 'Change password'),
            subtitle: _text(
              '修改成功后，其他设备会退出登录，当前设备不受影响。',
              'Other devices will be signed out. This device stays signed in.',
            ),
            avoidKeyboard: true,
            body: Form(
              key: formKey,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  TextFormField(
                    key: const ValueKey('current-password-field'),
                    obscureText: !showPasswords,
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
                    onChanged: (value) => confirmedPassword = value,
                    decoration: InputDecoration(
                      labelText: _text('确认新密码', 'Confirm new password'),
                    ),
                    validator: (_) => confirmedPassword == newPassword
                        ? null
                        : _text('两次密码不一致', 'Passwords do not match'),
                  ),
                  if (dialogError != null) ...[
                    const SizedBox(height: 12),
                    _errorBanner(dialogError!),
                  ],
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      Expanded(
                        child: CheckboxListTile(
                          key: const ValueKey('show-change-passwords'),
                          value: showPasswords,
                          dense: true,
                          visualDensity: VisualDensity.compact,
                          contentPadding: EdgeInsets.zero,
                          controlAffinity: ListTileControlAffinity.leading,
                          onChanged: submitting
                              ? null
                              : (value) => setDialogState(
                                  () => showPasswords = value ?? false,
                                ),
                          title: Text(_text('显示密码', 'Show passwords')),
                        ),
                      ),
                      const SizedBox(width: 12),
                      FilledButton(
                        key: const ValueKey('confirm-change-password'),
                        onPressed: submitting
                            ? null
                            : () async {
                                if (!(formKey.currentState?.validate() ??
                                    false)) {
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
                                  if (!dialogContext.mounted) return;
                                  setDialogState(() {
                                    submitting = false;
                                    dialogError = _messageFor(error);
                                  });
                                } catch (_) {
                                  if (!dialogContext.mounted) return;
                                  setDialogState(() {
                                    submitting = false;
                                    dialogError = _text(
                                      '修改失败，请稍后重试',
                                      'Could not change the password. Try again later.',
                                    );
                                  });
                                }
                              },
                        child: submitting
                            ? const SizedBox.square(
                                dimension: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : Text(_text('确认修改', 'Change password')),
                      ),
                    ],
                  ),
                ],
              ),
            ),
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
    final proceed = await showSettingsDetailSheet<bool>(
      context: context,
      builder: (sheetContext) => SettingsDetailSheet(
        key: const ValueKey('delete-account-warning-sheet'),
        title: _text('永久删除账号？', 'Permanently delete account?'),
        body: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              LucideIcons.triangleAlert,
              size: 18,
              color: Theme.of(sheetContext).colorScheme.error,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                _text(
                  '服务器中的账号、登录会话和平台额度信息会永久删除，无法恢复。本机聊天和文件不会自动清理。',
                  'Your server-side account, sessions, and platform quota data will be permanently deleted. Local chats and files are not removed automatically.',
                ),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            style: settingsDetailSheetActionStyle(sheetContext),
            onPressed: () => Navigator.pop(sheetContext, false),
            child: Text(_text('取消', 'Cancel')),
          ),
          TextButton(
            key: const ValueKey('continue-delete-account'),
            style: settingsDetailSheetActionStyle(
              sheetContext,
              foregroundColor: Theme.of(sheetContext).colorScheme.error,
            ),
            onPressed: () => Navigator.pop(sheetContext, true),
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
    String? dialogError;
    final deleted = await showSettingsDetailSheet<bool>(
      context: context,
      isScrollControlled: true,
      isDismissible: false,
      enableDrag: false,
      builder: (sheetContext) => StatefulBuilder(
        builder: (context, setDialogState) => PopScope(
          canPop: !submitting,
          child: SettingsDetailSheet(
            key: const ValueKey('delete-account-confirmation-sheet'),
            title: _text('最后确认', 'Final confirmation'),
            avoidKeyboard: true,
            body: Form(
              key: formKey,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextFormField(
                    key: const ValueKey('delete-account-email-field'),
                    keyboardType: TextInputType.emailAddress,
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
                    obscureText: true,
                    onChanged: (value) => currentPassword = value,
                    decoration: InputDecoration(
                      labelText: _text('当前密码', 'Current password'),
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
            actions: [
              TextButton(
                style: settingsDetailSheetActionStyle(sheetContext),
                onPressed: submitting
                    ? null
                    : () => Navigator.pop(sheetContext, false),
                child: Text(_text('取消', 'Cancel')),
              ),
              TextButton(
                key: const ValueKey('confirm-delete-account'),
                style: settingsDetailSheetActionStyle(
                  sheetContext,
                  foregroundColor: Theme.of(sheetContext).colorScheme.error,
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
                          if (sheetContext.mounted) {
                            Navigator.pop(sheetContext, true);
                          }
                        } on PlatformException catch (error) {
                          if (!sheetContext.mounted) return;
                          setDialogState(() {
                            submitting = false;
                            dialogError = _messageFor(error);
                          });
                        } catch (_) {
                          if (!sheetContext.mounted) return;
                          setDialogState(() {
                            submitting = false;
                            dialogError = _text(
                              '删除失败，请稍后重试',
                              'Could not delete the account. Try again later.',
                            );
                          });
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
          _error = _text('云服务开小差啦，请稍后重试', 'Operation failed. Try again later.');
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
      case 'rate_limited':
        return _text('操作太频繁，请稍后再试', 'Too many attempts. Try again later.');
      case 'current_password_invalid':
        return _text('当前密码不正确', 'The current password is incorrect');
      case 'password_reuse':
        return _text('新密码不能与当前密码相同', 'Choose a different password');
      case 'session_not_found':
        return _text('该登录设备已经退出', 'That device is already signed out');
      case 'cannot_revoke_current_session':
        return _text(
          '不能在这里退出当前设备',
          'The current device cannot be revoked here',
        );
      case 'invalid_verification_request':
        return _text('请重新发送验证码', 'Request a new verification code');
      case 'ACCOUNT_NOT_CONFIGURED':
        return _text('账号服务尚未配置', 'Account service is not configured');
      case 'CLOUD_SERVICE_UPDATE_REQUIRED':
        return _text(
          '当前版本过旧，请升级后使用账号与官方云服务',
          'Update the app before using account and official cloud services',
        );
      case 'CLOUD_SERVICE_POLICY_UNAVAILABLE':
        return _text(
          '请联网检查更新后再使用账号服务',
          'Connect to the internet and check for updates before using account services',
        );
      default:
        return _text('云服务开小差啦，请稍后重试', 'Operation failed. Try again later.');
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
    final content = Stack(
      children: [
        Positioned.fill(child: _buildBody()),
        if (_busy)
          Positioned.fill(
            child: ColoredBox(
              color: palette.overlayScrim,
              child: const Center(child: CircularProgressIndicator()),
            ),
          ),
      ],
    );
    if (widget.authOnly) {
      return ColoredBox(
        key: const ValueKey('account-auth-only-surface'),
        color: Colors.transparent,
        child: content,
      );
    }
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _text('账号与 AI 服务', 'Account & AI service'),
        primary: true,
      ),
      body: content,
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final session = _session;
    if (session == null) return _buildErrorState();
    if (!session.configured) return _buildNotConfigured();
    if (!session.cloudServiceAccessAllowed) {
      return _buildCloudServiceBlocked(session);
    }
    if (!session.signedIn || _overview == null) return _buildAuthForm();
    if (widget.authOnly) return _buildAuthenticatedAuthState(_overview!);
    return _buildSignedIn(_overview!);
  }

  Widget _buildAuthenticatedAuthState(AccountOverview overview) {
    final palette = context.omniPalette;
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              LucideIcons.circleCheckBig,
              size: 42,
              color: palette.accentPrimary,
            ),
            const SizedBox(height: 14),
            Text(
              _text('当前账号已登录', 'You are signed in'),
              style: Theme.of(context).textTheme.titleLarge,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 6),
            Text(
              overview.user.email,
              style: TextStyle(color: palette.textSecondary),
              textAlign: TextAlign.center,
            ),
            if (widget.onAuthenticated != null) ...[
              const SizedBox(height: 22),
              FilledButton(
                key: const ValueKey('account-auth-finish'),
                onPressed: widget.onAuthenticated,
                child: Text(_text('完成', 'Done')),
              ),
            ],
          ],
        ),
      ),
    );
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

  Widget _buildCloudServiceBlocked(AccountSessionState session) {
    final policyKnown = session.cloudServicePolicyKnown;
    final minimumVersion = session.minimumVersion;
    final currentVersion = session.currentVersion.isEmpty
        ? '-'
        : session.currentVersion;
    final reason =
        session.cloudServiceUnavailableReason ??
        (policyKnown
            ? _text(
                '当前版本过旧，请升级后继续使用账号与官方云服务。',
                'This version is too old for account and official cloud services.',
              )
            : _text(
                '尚未取得有效的云服务版本策略，请联网检查更新。',
                'A valid cloud-service version policy is not available. Check for updates while online.',
              ));
    return SafeArea(
      top: false,
      bottom: false,
      child: ListView(
        key: const ValueKey('account-cloud-service-version-gate'),
        padding: edgeToEdgeScrollPadding(
          context,
          const EdgeInsets.fromLTRB(18, 10, 18, 28),
        ),
        children: [
          SettingsSectionTitle(
            label: policyKnown
                ? _text('需要升级', 'Update required')
                : _text('需要检查更新', 'Update check required'),
          ),
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
                Text(reason, style: Theme.of(context).textTheme.titleMedium),
                if (minimumVersion.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  Text(
                    _text(
                      '当前 v$currentVersion · 最低 v$minimumVersion',
                      'Current v$currentVersion · Minimum v$minimumVersion',
                    ),
                    style: TextStyle(color: context.omniPalette.textSecondary),
                  ),
                ],
                const SizedBox(height: 10),
                Text(
                  _text(
                    '账号注册、登录与官方 AI 已停用；返回后仍可继续使用自己的 API Key（BYOK）。',
                    'Account sign-up, sign-in, and official AI are disabled. You can go back and keep using your own API key (BYOK).',
                  ),
                  style: TextStyle(color: context.omniPalette.textSecondary),
                ),
                const SizedBox(height: 20),
                FilledButton.icon(
                  key: const ValueKey('account-required-update-action'),
                  onPressed: _busy ? null : _checkRequiredUpdate,
                  icon: Icon(
                    policyKnown ? LucideIcons.download : LucideIcons.refreshCw,
                    size: 18,
                  ),
                  label: Text(
                    policyKnown
                        ? _text('立即升级', 'Update now')
                        : _text('检查更新', 'Check for updates'),
                  ),
                ),
                if (session.signedIn) ...[
                  const SizedBox(height: 8),
                  TextButton(
                    key: const ValueKey('account-gated-logout-action'),
                    onPressed: _busy ? null : _logout,
                    child: Text(_text('退出登录', 'Sign out')),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _checkRequiredUpdate() async {
    await _withBusy(() async {
      final status = await AppUpdateService.checkNow();
      final refreshedSession = await AccountService.getSessionState();
      if (!mounted) return;
      setState(() => _session = refreshedSession);
      if (refreshedSession.cloudServiceAccessAllowed) {
        await _loadAccount();
        return;
      }
      if (status?.hasUpdate == true) {
        await showAppUpdateDialog(context, status!);
        return;
      }
      showToast(
        _text(
          '当前没有可安装的新版本，请稍后重试',
          'No installable update is available. Try again later.',
        ),
        type: ToastType.warning,
      );
    });
  }

  Widget _buildAuthForm() {
    return SafeArea(
      top: false,
      bottom: false,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(18, 10, 18, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (widget.showAuthHeading)
                  AnimatedSwitcher(
                    duration: const Duration(milliseconds: 180),
                    child: SettingsSectionTitle(
                      key: ValueKey(_registerMode),
                      label: _registerMode
                          ? _text('创建小万账号', 'Create your account')
                          : _text('登录小万账号', 'Sign in to OmniBot'),
                      subtitle: _text(
                        '账号用于同步登录状态和平台额度；登录后官方 AI 会作为可选渠道提供。',
                        'Your account syncs sessions and platform quota; official AI becomes an optional provider after sign-in.',
                      ),
                      bottomPadding: 16,
                    ),
                  ),
                _authModeSelector(),
              ],
            ),
          ),
          const SizedBox(key: Key('account-auth-content-gap'), height: 12),
          Expanded(
            child: Listener(
              onPointerDown: _onAuthPointerDown,
              onPointerMove: _onAuthPointerMove,
              onPointerUp: _onAuthPointerUp,
              onPointerCancel: _onAuthPointerCancel,
              child: PageView(
                key: const Key('account-auth-page-view'),
                controller: _authPageController,
                onPageChanged: _onAuthPageChanged,
                children: [
                  _buildAuthPage(register: false),
                  _buildAuthPage(register: true),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAuthPage({required bool register}) {
    final emailController = register
        ? _registerEmailController
        : _emailController;
    final passwordController = register
        ? _registerPasswordController
        : _passwordController;

    return Form(
      key: register ? _registerFormKey : _formKey,
      child: ListView(
        key: PageStorageKey(
          register ? 'account-register-page' : 'account-login-page',
        ),
        padding: edgeToEdgeScrollPadding(
          context,
          const EdgeInsets.fromLTRB(18, 8, 18, 28),
        ),
        children: [
          TextFormField(
            key: ValueKey(
              register ? 'account-register-email' : 'account-login-email',
            ),
            controller: emailController,
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
            key: ValueKey(
              register ? 'account-register-password' : 'account-login-password',
            ),
            controller: passwordController,
            focusNode: register ? _registerPasswordFocusNode : null,
            obscureText: !_showPassword,
            autofillHints: register
                ? const [AutofillHints.newPassword]
                : const [AutofillHints.password],
            decoration: InputDecoration(
              labelText: _text('密码', 'Password'),
              hintText: register && _registerPasswordFocusNode.hasFocus
                  ? _text('8 到 16 个字符', '8 to 16 characters')
                  : null,
              prefixIcon: const Icon(LucideIcons.lockKeyhole, size: 20),
              suffixIcon: IconButton(
                onPressed: () => setState(() => _showPassword = !_showPassword),
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
              if (register &&
                  (value!.characters.length < 8 ||
                      value.characters.length > 16)) {
                return _text('密码需为 8 到 16 个字符', 'Use 8 to 16 characters');
              }
              return null;
            },
          ),
          if (!register)
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                key: const ValueKey('forgot-password'),
                onPressed: _busy ? null : _showPasswordResetDialog,
                child: Text(_text('忘记密码？', 'Forgot password?')),
              ),
            ),
          if (register) ...[
            const SizedBox(height: 14),
            TextFormField(
              key: const ValueKey('auth-confirm-password-field'),
              controller: _confirmPasswordController,
              obscureText: !_showPassword,
              autofillHints: const [AutofillHints.newPassword],
              decoration: InputDecoration(
                labelText: _text('确认密码', 'Confirm password'),
                prefixIcon: const Icon(LucideIcons.rotateCcwKey, size: 20),
              ),
              validator: (value) => value == _registerPasswordController.text
                  ? null
                  : _text('两次密码不一致', 'Passwords do not match'),
            ),
            const SizedBox(height: 14),
            TextFormField(
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
            key: ValueKey(
              register
                  ? 'account-register-submit'
                  : _passwordResetDialogOpen
                  ? 'account-login-submit'
                  : 'submit-auth',
            ),
            onPressed: _busy ? null : _submitAuth,
            child: KeyedSubtree(
              key: ValueKey(register ? 'submit-auth' : 'account-login-submit'),
              child: Text(
                register
                    ? _text('注册并登录', 'Create account & sign in')
                    : _text('登录', 'Sign in'),
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _onRegisterPasswordFocusChanged() {
    if (mounted) setState(() {});
  }

  void _selectAuthMode(bool register) {
    if (!mounted) return;
    FocusScope.of(context).unfocus();
    final destinationEmail = register
        ? _registerEmailController
        : _emailController;
    final sourceEmail = register ? _emailController : _registerEmailController;
    if (destinationEmail.text.isEmpty && sourceEmail.text.isNotEmpty) {
      destinationEmail.text = sourceEmail.text;
    }
    if (_registerMode != register || _error != null) {
      setState(() {
        _registerMode = register;
        _error = null;
      });
    }
    if (_authPageController.hasClients) {
      _authPageController.animateToPage(
        register ? 1 : 0,
        duration: const Duration(milliseconds: 280),
        curve: Curves.easeOutCubic,
      );
    }
  }

  void _onAuthPageChanged(int page) {
    final register = page == 1;
    if (_registerMode == register) return;
    FocusScope.of(context).unfocus();
    final destinationEmail = register
        ? _registerEmailController
        : _emailController;
    final sourceEmail = register ? _emailController : _registerEmailController;
    if (destinationEmail.text.isEmpty && sourceEmail.text.isNotEmpty) {
      destinationEmail.text = sourceEmail.text;
    }
    setState(() {
      _registerMode = register;
      _error = null;
    });
  }

  void _onAuthPointerDown(PointerDownEvent event) {
    if (_authSwipePointer != null) return;
    _authSwipePointer = event.pointer;
    _authSwipeDelta = Offset.zero;
  }

  void _onAuthPointerMove(PointerMoveEvent event) {
    if (_authSwipePointer != event.pointer) return;
    _authSwipeDelta += event.delta;
  }

  void _onAuthPointerUp(PointerUpEvent event) {
    if (_authSwipePointer != event.pointer) return;
    final delta = _authSwipeDelta;
    _resetAuthSwipeTracking();
    if (delta.dx.abs() < 64 || delta.dx.abs() <= delta.dy.abs() * 1.2) {
      return;
    }
    _selectAuthMode(delta.dx < 0);
  }

  void _onAuthPointerCancel(PointerCancelEvent event) {
    if (_authSwipePointer == event.pointer) _resetAuthSwipeTracking();
  }

  void _resetAuthSwipeTracking() {
    _authSwipePointer = null;
    _authSwipeDelta = Offset.zero;
  }

  Widget _authModeSelector() {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Container(
      key: const Key('account-auth-mode-selector'),
      height: 40,
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: isDark ? palette.segmentTrack : palette.surfacePrimary,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Stack(
        children: [
          AnimatedAlign(
            key: const Key('account-auth-mode-thumb-align'),
            duration: const Duration(milliseconds: 280),
            curve: Curves.easeOutCubic,
            alignment: _registerMode
                ? Alignment.centerRight
                : Alignment.centerLeft,
            child: FractionallySizedBox(
              widthFactor: 0.5,
              child: Container(
                key: const Key('account-auth-mode-thumb'),
                margin: const EdgeInsets.symmetric(horizontal: 1),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(999),
                  gradient: isDark
                      ? LinearGradient(
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                          colors: [
                            Color.lerp(
                              palette.surfaceElevated,
                              palette.accentPrimary,
                              0.18,
                            )!,
                            Color.lerp(
                              palette.surfaceSecondary,
                              palette.accentPrimary,
                              0.30,
                            )!,
                          ],
                        )
                      : const LinearGradient(
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                          colors: [Color(0xFF2DA5F0), Color(0xFF1930D9)],
                        ),
                  boxShadow: isDark
                      ? null
                      : const [
                          BoxShadow(
                            color: Color(0x1F1930D9),
                            blurRadius: 10,
                            offset: Offset(0, 4),
                          ),
                        ],
                  border: isDark
                      ? Border.all(color: palette.borderSubtle)
                      : null,
                ),
              ),
            ),
          ),
          Row(
            children: [
              _authModeButton(_text('登录', 'Sign in'), false),
              _authModeButton(_text('注册', 'Register'), true),
            ],
          ),
        ],
      ),
    );
  }

  Widget _authModeButton(String label, bool register) {
    final palette = context.omniPalette;
    final selected = _registerMode == register;
    return Expanded(
      child: Semantics(
        button: true,
        selected: selected,
        child: GestureDetector(
          key: ValueKey(
            register ? 'account-auth-mode-register' : 'account-auth-mode-login',
          ),
          behavior: HitTestBehavior.opaque,
          onTap: () => _selectAuthMode(register),
          child: Center(
            child: AnimatedScale(
              duration: const Duration(milliseconds: 220),
              curve: Curves.easeOutCubic,
              scale: selected ? 1 : 0.97,
              child: AnimatedDefaultTextStyle(
                duration: const Duration(milliseconds: 220),
                curve: Curves.easeOutCubic,
                style: TextStyle(
                  color: selected
                      ? (context.isDarkTheme
                            ? palette.textPrimary
                            : Colors.white)
                      : palette.textSecondary,
                  fontSize: 13,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                ),
                child: Text(label),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSignedIn(AccountOverview overview) {
    final settings = overview.settings;
    final platformQuota = settings.platform;
    final String? quotaSubtitle = !settings.platformAvailable
        ? _text(
            settings.platformUnavailableReason ?? '平台 AI 服务暂未开放，额度将在开放后使用',
            'Platform AI is not available yet; quota can be used after launch',
          )
        : settings.platform.enabled
        ? platformQuota.weeklyLimit > 0
              ? _text(
                  '距离重置 ${formatWeeklyQuotaResetCountdown(DateTime.now(), english: false)}',
                  'Resets in ${formatWeeklyQuotaResetCountdown(DateTime.now(), english: true)}',
                )
              : null
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
              title: _text('平台额度', 'Platform quota'),
              subtitle: quotaSubtitle,
              trailing: settings.platformAvailable
                  ? _platformQuotaValue(platformQuota)
                  : null,
            ),
            const SizedBox(height: 24),
            SettingsSectionTitle(label: _text('账号管理', 'Account management')),
            _accountAction(
              key: const ValueKey('account-usage-action'),
              icon: LucideIcons.chartNoAxesColumnIncreasing,
              title: _text('最近平台用量', 'Recent platform usage'),
              onTap: _showPlatformUsage,
            ),
            _sectionDivider(),
            _accountAction(
              key: const ValueKey('account-sessions-action'),
              icon: LucideIcons.smartphone,
              title: _text('登录设备', 'Signed-in devices'),
              onTap: _showSessions,
            ),
            _sectionDivider(),
            _accountAction(
              key: const ValueKey('change-password-action'),
              icon: LucideIcons.shieldCheck,
              title: _text('修改密码', 'Change password'),
              onTap: _showChangePasswordDialog,
            ),
            _sectionDivider(),
            _accountAction(
              key: const ValueKey('delete-account-action'),
              icon: LucideIcons.trash2,
              title: _text('删除账号', 'Delete account'),
              destructive: true,
              onTap: _showDeleteAccountFlow,
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

  Widget _platformQuotaValue(PlatformQuota quota) {
    final palette = context.omniPalette;
    final limit = quota.weeklyLimit;
    if (limit <= 0) {
      return Text(
        '${quota.balance}',
        style: TextStyle(
          color: palette.accentPrimary,
          fontSize: 22,
          fontWeight: FontWeight.w700,
        ),
      );
    }
    final balance = quota.balance;
    final percentage = (balance / limit * 100).clamp(0, 100).round();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.end,
      mainAxisSize: MainAxisSize.min,
      children: [
        RichText(
          key: const ValueKey('account-platform-quota-ratio'),
          text: TextSpan(
            children: [
              TextSpan(
                text: '$balance',
                style: TextStyle(
                  color: palette.accentPrimary,
                  fontSize: 23,
                  fontWeight: FontWeight.w800,
                  height: 1.1,
                ),
              ),
              TextSpan(
                text: '/',
                style: TextStyle(
                  color: palette.textTertiary,
                  fontSize: 13,
                  fontWeight: FontWeight.w500,
                ),
              ),
              TextSpan(
                text: '$limit',
                style: TextStyle(
                  color: palette.textSecondary,
                  fontSize: 15,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 3),
        Text(
          '$percentage%',
          key: const ValueKey('account-platform-quota-percent'),
          style: TextStyle(
            color: palette.textSecondary,
            fontSize: 11,
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
  }

  Widget _summaryRow({
    required IconData icon,
    required String title,
    required String? subtitle,
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
                if (subtitle != null && subtitle.isNotEmpty) ...[
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

  Widget _accountAction({
    required Key key,
    required IconData icon,
    required String title,
    required VoidCallback onTap,
    bool destructive = false,
  }) {
    final palette = context.omniPalette;
    final color = destructive
        ? Theme.of(context).colorScheme.error
        : palette.textPrimary;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: key,
        onTap: _busy ? null : onTap,
        borderRadius: BorderRadius.circular(14),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(4, 14, 2, 14),
          child: Row(
            children: [
              Icon(icon, size: 20, color: color),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  title,
                  style: TextStyle(
                    color: color,
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
