import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/device_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/app_update_dialog.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_ui.dart';

class AboutPage extends StatefulWidget {
  const AboutPage({super.key});

  @override
  State<AboutPage> createState() => _AboutPageState();
}

class _AboutPageState extends State<AboutPage> {
  static const String _zhUserGuideUrl =
      'https://omnimind-ai.github.io/OmniBot-Docs';
  static const String _enUserGuideUrl =
      'https://omnimind-ai.github.io/OmniBot-Docs/en/';

  String _version = '';
  AppUpdateStatus? _updateStatus = AppUpdateService.statusNotifier.value;
  bool _betaOptIn = AppUpdateService.betaOptInNotifier.value;
  AppUpdateDownloadSource _downloadSource =
      AppUpdateService.downloadSourceNotifier.value;
  bool _isCheckingUpdate = false;
  bool _isUpdatingBetaOptIn = false;
  bool _isUpdatingDownloadSource = false;
  bool _hasSyncedUpdatePreferences = false;

  @override
  void initState() {
    super.initState();
    AppUpdateService.statusNotifier.addListener(_handleUpdateStatusChanged);
    AppUpdateService.betaOptInNotifier.addListener(_handleBetaOptInChanged);
    AppUpdateService.downloadSourceNotifier.addListener(
      _handleDownloadSourceChanged,
    );
    _loadVersion();
    _loadUpdateStatus();
  }

  @override
  void dispose() {
    AppUpdateService.statusNotifier.removeListener(_handleUpdateStatusChanged);
    AppUpdateService.betaOptInNotifier.removeListener(_handleBetaOptInChanged);
    AppUpdateService.downloadSourceNotifier.removeListener(
      _handleDownloadSourceChanged,
    );
    super.dispose();
  }

  Future<void> _loadVersion() async {
    try {
      final versionInfo = await DeviceService.getAppVersion();
      if (!mounted) return;
      if (versionInfo != null) {
        final versionName = versionInfo['versionName'] as String?;
        setState(() {
          _version = 'Version ${versionName ?? '-'}';
        });
        return;
      }
      setState(() {
        _version = 'Version -';
      });
    } catch (e) {
      debugPrint('加载版本号失败: $e');
      if (!mounted) return;
      setState(() {
        _version = 'Version -';
      });
    }
  }

  Future<void> _loadUpdateStatus() async {
    await AppUpdateService.initialize();
    if (!mounted) return;
    setState(() {
      _betaOptIn = AppUpdateService.betaOptInNotifier.value;
      _updateStatus = AppUpdateService.statusNotifier.value;
      _downloadSource = AppUpdateService.downloadSourceNotifier.value;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _hasSyncedUpdatePreferences) {
        return;
      }
      setState(() {
        _hasSyncedUpdatePreferences = true;
      });
    });
  }

  void _handleBetaOptInChanged() {
    if (!mounted) return;
    setState(() {
      _betaOptIn = AppUpdateService.betaOptInNotifier.value;
    });
  }

  void _handleUpdateStatusChanged() {
    if (!mounted) return;
    setState(() {
      _updateStatus = AppUpdateService.statusNotifier.value;
    });
  }

  void _handleDownloadSourceChanged() {
    if (!mounted) return;
    setState(() {
      _downloadSource = AppUpdateService.downloadSourceNotifier.value;
    });
  }

  Future<void> _handleToggleBetaOptIn(bool enabled) async {
    if (_isUpdatingBetaOptIn) return;
    setState(() {
      _isUpdatingBetaOptIn = true;
    });

    try {
      final updated = await AppUpdateService.setBetaOptIn(enabled);
      if (!mounted) return;
      setState(() {
        _betaOptIn = updated;
        _updateStatus = AppUpdateService.statusNotifier.value;
      });
    } catch (_) {
      if (!mounted) return;
      showToast(
        context.l10n.aboutBetaProgramToggleFailed,
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _isUpdatingBetaOptIn = false;
        });
      }
    }
  }

  Future<void> _handleCheckUpdate() async {
    if (_isCheckingUpdate) return;
    setState(() {
      _isCheckingUpdate = true;
    });

    try {
      final status = await AppUpdateService.checkNow();
      if (!mounted) return;
      if (status == null) {
        showToast(context.trLegacy('检查更新失败'), type: ToastType.error);
        return;
      }
      if (status.hasUpdate) {
        await showAppUpdateDialog(context, status);
        return;
      }
      showToast(context.trLegacy('已是最新版'), type: ToastType.success);
    } catch (_) {
      if (!mounted) return;
      showToast(context.trLegacy('检查更新失败'), type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _isCheckingUpdate = false;
        });
      }
    }
  }

  Future<void> _handlePrimaryAction() async {
    final status = _updateStatus;
    if (status != null && status.hasUpdate) {
      await showAppUpdateDialog(context, status);
      return;
    }
    await _handleCheckUpdate();
  }

  Future<void> _handleSelectDownloadSource(
    AppUpdateDownloadSource? source,
  ) async {
    if (source == null ||
        _isUpdatingDownloadSource ||
        source == _downloadSource) {
      return;
    }
    setState(() {
      _isUpdatingDownloadSource = true;
    });

    try {
      final updatedSource = await AppUpdateService.setDownloadSource(source);
      if (!mounted) return;
      setState(() {
        _downloadSource = updatedSource;
        _updateStatus = AppUpdateService.statusNotifier.value;
      });
    } catch (_) {
      if (!mounted) return;
      showToast(context.l10n.aboutApkSourceSwitchFailed, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _isUpdatingDownloadSource = false;
        });
      }
    }
  }

  String? _buildUpdateHint() {
    final status = _updateStatus;
    if (status?.hasUpdate != true) return null;
    return '${context.trLegacy('发现新版本')} ${status!.latestVersionLabel}';
  }

  void _openUserGuide() {
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    GoRouterManager.push(
      '/webview/webview_page',
      extra: <String, dynamic>{
        'url': isEnglish ? _enUserGuideUrl : _zhUserGuideUrl,
        'title': context.trLegacy('使用手册'),
        'appBarBackClosesPage': true,
      },
    );
  }

  void _openFeedback() => GoRouterManager.push('/my/feedback');

  String _downloadSourceLabel(AppUpdateDownloadSource source) {
    switch (source) {
      case AppUpdateDownloadSource.worker:
        return context.l10n.aboutApkSourceOptionCnb;
      case AppUpdateDownloadSource.github:
        return context.l10n.aboutApkSourceOptionGithub;
    }
  }

  String _downloadSourceDescription(AppUpdateDownloadSource source) {
    switch (source) {
      case AppUpdateDownloadSource.worker:
        return context.l10n.aboutApkSourceOptionCnbDescription;
      case AppUpdateDownloadSource.github:
        return context.l10n.aboutApkSourceOptionGithubDescription;
    }
  }

  Widget _buildHero(bool compact) {
    final palette = context.omniPalette;
    return Column(
      children: [
        SizedBox(
          width: compact ? 72 : 88,
          height: compact ? 72 : 88,
          child: Image.asset(
            'assets/common/omnibot_launcher.png',
            fit: BoxFit.contain,
            errorBuilder: (context, error, stackTrace) {
              return Icon(
                LucideIcons.bot,
                size: 56,
                color: palette.accentPrimary,
              );
            },
          ),
        ),
        SizedBox(height: compact ? 8 : 12),
        Text(
          context.l10n.brandName,
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: compact ? 21 : 24,
            fontWeight: FontWeight.w600,
            letterSpacing: 0.2,
            color: palette.textPrimary,
          ),
        ),
        const SizedBox(height: 6),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3.5),
          decoration: BoxDecoration(
            color: palette.surfaceSecondary,
            borderRadius: BorderRadius.circular(999),
            border: Border.all(
              color: palette.borderSubtle.withValues(alpha: 0.7),
              width: 0.65,
            ),
          ),
          child: Text(
            _version,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w500,
              color: palette.textSecondary,
              letterSpacing: 0.2,
            ),
          ),
        ),
        SizedBox(height: compact ? 10 : 12),
        ConstrainedBox(
          constraints: BoxConstraints(maxWidth: compact ? 300 : 320),
          child: Text(
            context.l10n.aboutDescription,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 12.5,
              fontWeight: FontWeight.w400,
              height: 1.5,
              color: palette.textSecondary,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildDownloadSourceTrailing() {
    return OmniSettingsDropdown<AppUpdateDownloadSource>(
      dropdownKey: const ValueKey('about-download-source-dropdown'),
      value: _downloadSource,
      maxWidth: 148,
      menuMinWidth: 216,
      menuMaxWidth: 272,
      options: AppUpdateDownloadSource.values
          .map(
            (source) => OmniSettingsOption<AppUpdateDownloadSource>(
              value: source,
              label: _downloadSourceLabel(source),
              subtitle: _downloadSourceDescription(source),
            ),
          )
          .toList(),
      onChanged: _isUpdatingDownloadSource ? null : _handleSelectDownloadSource,
    );
  }

  Widget _buildGroups(bool compact) {
    final palette = context.omniPalette;
    final updateHint = _buildUpdateHint();
    final hasUpdate = _updateStatus?.hasUpdate == true;
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        OmniSettingsGroup(
          title: context.l10n.aboutPreferencesSectionTitle,
          children: [
            OmniSettingsTile(
              icon: hasUpdate ? LucideIcons.sparkles : LucideIcons.refreshCw,
              iconColor: hasUpdate ? palette.accentPrimary : null,
              title: _isCheckingUpdate
                  ? context.trLegacy('检查中...')
                  : (hasUpdate
                        ? context.trLegacy('查看新版本')
                        : context.trLegacy('检查更新')),
              trailingText: updateHint,
              trailingTextColor: hasUpdate ? palette.accentPrimary : null,
              trailingStatusDotColor: hasUpdate ? palette.accentPrimary : null,
              onTap: _isCheckingUpdate ? null : _handlePrimaryAction,
            ),
            AnimatedOpacity(
              duration: const Duration(milliseconds: 180),
              opacity: _isUpdatingBetaOptIn ? 0.72 : 1,
              child: OmniSettingsTile(
                icon: LucideIcons.flaskConical,
                title: context.l10n.aboutBetaProgramTitle,
                subtitle: context.l10n.aboutBetaProgramDescription,
                onTap: _isUpdatingBetaOptIn
                    ? null
                    : () => _handleToggleBetaOptIn(!_betaOptIn),
                trailing: OmniSettingsSwitch(
                  value: _betaOptIn,
                  enabled: !_isUpdatingBetaOptIn,
                  duration: _hasSyncedUpdatePreferences
                      ? const Duration(milliseconds: 200)
                      : Duration.zero,
                  onToggle: _handleToggleBetaOptIn,
                ),
              ),
            ),
            AnimatedOpacity(
              duration: const Duration(milliseconds: 180),
              opacity: _isUpdatingDownloadSource ? 0.72 : 1,
              child: OmniSettingsTile(
                icon: LucideIcons.download,
                title: context.l10n.aboutApkSourceTitle,
                subtitle: context.l10n.aboutApkSourceDescription,
                bottomNote: context.l10n.aboutApkSourceDisclaimer,
                trailing: _buildDownloadSourceTrailing(),
              ),
            ),
          ],
        ),
        const SizedBox(height: 20),
        OmniSettingsGroup(
          title: isEnglish ? 'Help & Feedback' : '帮助与反馈',
          children: [
            OmniSettingsTile(
              icon: LucideIcons.bookOpen,
              title: context.trLegacy('使用手册'),
              onTap: _openUserGuide,
            ),
            OmniSettingsTile(
              icon: LucideIcons.messageSquare,
              title: context.trLegacy('我要反馈'),
              onTap: _openFeedback,
            ),
          ],
        ),
        const SizedBox(height: 20),
        OmniSettingsGroup(
          title: isEnglish ? 'Diagnostics & Logs' : '诊断与日志',
          children: [
            OmniSettingsTile(
              icon: LucideIcons.receiptText,
              title: context.trLegacy('请求日志'),
              onTap: () {
                GoRouterManager.push('/my/about/request-logs');
              },
            ),
            OmniSettingsTile(
              icon: LucideIcons.bug,
              title: context.trLegacy('运行日志'),
              onTap: () {
                GoRouterManager.push('/my/about/runtime-logs');
              },
            ),
          ],
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.settingsAboutTitle,
        primary: true,
      ),
      body: OmniContentWidth(
        child: SafeArea(
          top: false,
          bottom: false,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final compact = constraints.maxHeight < 760;
              return Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 520),
                  child: SingleChildScrollView(
                    padding: edgeToEdgeScrollPadding(
                      context,
                      EdgeInsets.fromLTRB(
                        16,
                        compact ? 8 : 16,
                        16,
                        compact ? 20 : 32,
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        SizedBox(height: compact ? 4 : 8),
                        _buildHero(compact),
                        SizedBox(height: compact ? 18 : 24),
                        _buildGroups(compact),
                      ],
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}
