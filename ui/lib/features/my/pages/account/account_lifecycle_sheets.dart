import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/account_service.dart';
import 'package:ui/services/model_vendor_catalog.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';

class PlatformUsageSheet extends StatefulWidget {
  const PlatformUsageSheet({
    super.key,
    required this.english,
    required this.errorMessage,
  });

  final bool english;
  final String Function(PlatformException) errorMessage;

  @override
  State<PlatformUsageSheet> createState() => _PlatformUsageSheetState();
}

class _PlatformUsageSheetState extends State<PlatformUsageSheet> {
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
    return SettingsDetailSheet(
      key: const ValueKey('platform-usage-sheet'),
      title: _text('最近平台用量', 'Recent platform usage'),
      subtitle: _text(
        '仅显示最近 20 条，额度以服务器结算为准。',
        'Shows the latest 20 records. Server settlement is authoritative.',
      ),
      fillAvailableHeight: true,
      headerAction: TextButton(
        key: const ValueKey('refresh-platform-usage'),
        style: settingsDetailSheetActionStyle(context),
        onPressed: _entries == null ? null : _load,
        child: Text(_text('刷新', 'Refresh')),
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
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
      return const _SheetLoading();
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
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: List<Widget>.generate(entries.length * 2 - 1, (itemIndex) {
        if (itemIndex.isOdd) return const _SheetListDivider();
        final index = itemIndex ~/ 2;
        final entry = entries[index];
        final model = entry.model.trim().isEmpty
            ? _text('官方模型', 'Official model')
            : entry.model;
        return _PlatformUsageRow(
          key: ValueKey('platform-usage-$index'),
          index: index,
          model: model,
          createdAt: entry.createdAt,
          promptTokens: entry.promptTokens,
          completionTokens: entry.completionTokens,
          totalTokens: entry.totalTokens,
          quotaUsed: entry.quotaUsed,
          english: widget.english,
        );
      }),
    );
  }
}

class SessionsSheet extends StatefulWidget {
  const SessionsSheet({
    super.key,
    required this.english,
    required this.errorMessage,
  });

  final bool english;
  final String Function(PlatformException) errorMessage;

  @override
  State<SessionsSheet> createState() => _SessionsSheetState();
}

class _SessionsSheetState extends State<SessionsSheet> {
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
    final confirmed = await showSettingsDetailSheet<bool>(
      context: context,
      builder: (sheetContext) => SettingsDetailSheet(
        key: ValueKey('revoke-session-sheet-${session.id}'),
        title: _text('退出这个设备？', 'Sign out this device?'),
        body: Text(
          _text(
            '这个设备需要重新输入邮箱和密码才能使用账号。',
            'This device must sign in again with the email and password.',
          ),
        ),
        actions: [
          TextButton(
            style: settingsDetailSheetActionStyle(sheetContext),
            onPressed: () => Navigator.pop(sheetContext, false),
            child: Text(_text('取消', 'Cancel')),
          ),
          TextButton(
            key: const ValueKey('confirm-revoke-session'),
            style: settingsDetailSheetActionStyle(
              sheetContext,
              foregroundColor: Theme.of(sheetContext).colorScheme.error,
            ),
            onPressed: () => Navigator.pop(sheetContext, true),
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
      if (!mounted) return;
      setState(() {
        _busySessionId = null;
        _error = widget.errorMessage(error);
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _busySessionId = null;
        _error = _text(
          '退出设备失败，请稍后重试',
          'Could not sign out the device. Try again later.',
        );
      });
    }
  }

  Future<void> _revokeOtherSessions() async {
    final confirmed = await showSettingsDetailSheet<bool>(
      context: context,
      builder: (sheetContext) => SettingsDetailSheet(
        key: const ValueKey('revoke-other-sessions-sheet'),
        title: _text('退出全部其他设备？', 'Sign out all other devices?'),
        body: Text(
          _text(
            '当前设备会保持登录，其他设备都需要重新登录。',
            'This device stays signed in. Other devices must sign in again.',
          ),
        ),
        actions: [
          TextButton(
            style: settingsDetailSheetActionStyle(sheetContext),
            onPressed: () => Navigator.pop(sheetContext, false),
            child: Text(_text('取消', 'Cancel')),
          ),
          TextButton(
            key: const ValueKey('confirm-revoke-other-sessions'),
            style: settingsDetailSheetActionStyle(
              sheetContext,
              foregroundColor: Theme.of(sheetContext).colorScheme.error,
            ),
            onPressed: () => Navigator.pop(sheetContext, true),
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
      if (!mounted) return;
      setState(() {
        _revokingAll = false;
        _error = widget.errorMessage(error);
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _revokingAll = false;
        _error = _text(
          '退出其他设备失败，请稍后重试',
          'Could not sign out other devices. Try again later.',
        );
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final sessions = _sessions;
    final hasOtherSessions =
        sessions?.any((session) => !session.current) ?? false;
    return SettingsDetailSheet(
      key: const ValueKey('account-sessions-sheet'),
      title: _text('登录设备', 'Signed-in devices'),
      subtitle: _text(
        '服务目前仅记录登录时间，暂不读取设备名称。',
        'The service records sign-in times without reading device names.',
      ),
      fillAvailableHeight: true,
      body: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_error != null && sessions != null)
            _InlineSheetNotice(message: _error!, error: true)
          else if (_notice != null)
            _InlineSheetNotice(message: _notice!),
          _buildBody(),
        ],
      ),
      actionsKey: const ValueKey('account-sessions-actions'),
      actions: [
        TextButton(
          key: const ValueKey('refresh-account-sessions'),
          style: settingsDetailSheetActionStyle(context),
          onPressed: sessions == null || _busy ? null : _load,
          child: Text(_text('刷新', 'Refresh')),
        ),
        if (hasOtherSessions)
          TextButton.icon(
            key: const ValueKey('revoke-other-sessions'),
            onPressed: _busy ? null : _revokeOtherSessions,
            style: settingsDetailSheetActionStyle(
              context,
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            icon: _revokingAll
                ? const SizedBox.square(
                    dimension: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(LucideIcons.logOut, size: 16),
            label: Text(_text('退出全部其他设备', 'Sign out all other devices')),
          ),
      ],
    );
  }

  Widget _buildBody() {
    if (_sessions == null && _error == null) {
      return const _SheetLoading();
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
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: List<Widget>.generate(sessions.length * 2 - 1, (itemIndex) {
        if (itemIndex.isOdd) return const _SheetListDivider();
        final index = itemIndex ~/ 2;
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
        return _SheetListRow(
          key: ValueKey('account-session-${session.id}'),
          icon: session.current ? LucideIcons.smartphone : LucideIcons.monitor,
          title: title,
          details: [
            '${_text('最近活动', 'Last active')} '
                '${formatAccountDate(session.lastUsedAt ?? session.createdAt)}',
          ],
          trailing: session.current
              ? null
              : TextButton(
                  key: ValueKey('revoke-session-${session.id}'),
                  style: settingsDetailSheetActionStyle(
                    context,
                    foregroundColor: Theme.of(context).colorScheme.error,
                  ),
                  onPressed: _busy ? null : () => _revoke(session),
                  child: busy
                      ? const SizedBox.square(
                          dimension: 17,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : Text(_text('退出', 'Sign out')),
                ),
        );
      }),
    );
  }
}

class _PlatformUsageRow extends StatelessWidget {
  const _PlatformUsageRow({
    super.key,
    required this.index,
    required this.model,
    required this.createdAt,
    required this.promptTokens,
    required this.completionTokens,
    required this.totalTokens,
    required this.quotaUsed,
    required this.english,
  });

  final int index;
  final String model;
  final DateTime? createdAt;
  final int promptTokens;
  final int completionTokens;
  final int totalTokens;
  final int quotaUsed;
  final bool english;

  String _text(String zh, String en) => english ? en : zh;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final vendor = ModelVendorCatalog.resolve(model);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 34,
            height: 34,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: palette.surfaceSecondary,
              borderRadius: BorderRadius.circular(10),
            ),
            child: ProviderVendorIcon(
              key: ValueKey('platform-usage-model-icon-$index'),
              vendor: vendor,
              size: 20,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            model,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: palette.textPrimary,
                              fontSize: 14,
                              height: 1.35,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 3),
                          Text(
                            formatAccountDate(createdAt),
                            style: TextStyle(
                              color: palette.textTertiary,
                              fontSize: 11,
                              height: 1.4,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 12),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.center,
                      children: [
                        Icon(
                          LucideIcons.coins,
                          size: 14,
                          color: palette.accentPrimary,
                        ),
                        const SizedBox(width: 5),
                        Text.rich(
                          key: ValueKey('platform-usage-quota-$index'),
                          TextSpan(
                            style: TextStyle(
                              color: palette.accentPrimary,
                              fontSize: 11,
                              height: 1.2,
                            ),
                            children: [
                              TextSpan(text: '${_text('消耗', 'Used')} '),
                              TextSpan(
                                text: '$quotaUsed',
                                style: const TextStyle(
                                  fontSize: 20,
                                  fontWeight: FontWeight.w700,
                                  height: 1,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Wrap(
                  spacing: 14,
                  runSpacing: 8,
                  children: [
                    _UsageMetric(
                      icon: LucideIcons.arrowDownToLine,
                      label: _text('输入', 'Input'),
                      value: promptTokens,
                    ),
                    _UsageMetric(
                      icon: LucideIcons.arrowUpFromLine,
                      label: _text('输出', 'Output'),
                      value: completionTokens,
                    ),
                    _UsageMetric(
                      icon: LucideIcons.sigma,
                      label: _text('总计', 'Total'),
                      value: totalTokens,
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _UsageMetric extends StatelessWidget {
  const _UsageMetric({
    required this.icon,
    required this.label,
    required this.value,
  });

  final IconData icon;
  final String label;
  final int value;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 13, color: palette.textTertiary),
        const SizedBox(width: 4),
        Text.rich(
          TextSpan(
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 11,
              height: 1.35,
            ),
            children: [
              TextSpan(text: '$label '),
              TextSpan(
                text: '$value',
                style: TextStyle(
                  color: palette.textPrimary,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _SheetLoading extends StatelessWidget {
  const _SheetLoading();

  @override
  Widget build(BuildContext context) {
    return const SizedBox(
      height: 280,
      child: Center(child: CircularProgressIndicator()),
    );
  }
}

class _SheetListDivider extends StatelessWidget {
  const _SheetListDivider();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsetsDirectional.only(start: 28),
      child: Divider(
        height: 1,
        thickness: 1,
        color: context.omniPalette.borderSubtle.withValues(
          alpha: context.isDarkTheme ? 0.5 : 0.78,
        ),
      ),
    );
  }
}

class _SheetListRow extends StatelessWidget {
  const _SheetListRow({
    super.key,
    required this.icon,
    required this.title,
    required this.details,
    this.trailing,
  });

  final IconData icon;
  final String title;
  final List<String> details;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Icon(icon, size: 18, color: palette.textSecondary),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 14,
                    height: 1.45,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                for (final detail in details) ...[
                  const SizedBox(height: 2),
                  Text(
                    detail,
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 11,
                      height: 1.5,
                    ),
                  ),
                ],
              ],
            ),
          ),
          if (trailing != null) ...[const SizedBox(width: 8), trailing!],
        ],
      ),
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
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text(message, style: TextStyle(color: color)),
    );
  }
}

String formatAccountDate(DateTime? value) {
  if (value == null) return '--';
  final local = value.toLocal();
  String twoDigits(int number) => number.toString().padLeft(2, '0');
  return '${local.year}-${twoDigits(local.month)}-${twoDigits(local.day)} '
      '${twoDigits(local.hour)}:${twoDigits(local.minute)}';
}
